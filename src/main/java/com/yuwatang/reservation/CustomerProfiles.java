package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;

/** Link WeChat identities to shared customer records only after phone authorization. */
@Service
class CustomerProfiles {
    final Data d;final WechatGateway wx;final TransactionTemplate tx;
    static final Set<String> SOURCES=Set.of("MINIPROGRAM","DIANPING","WECHAT","PHONE","WALKIN","IMPORT");
    CustomerProfiles(Data d,WechatGateway wx,PlatformTransactionManager tm){
        this.d=d;this.wx=wx;tx=new TransactionTemplate(tm);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    static void recordLogin(Data d,String identity){
        String time=now();
        if(d.update("UPDATE customer_account SET last_login_at=? WHERE identity_id=?",time,identity)==0)
            d.update("INSERT INTO customer_account(identity_id,display_name,first_login_at,last_login_at) VALUES(?,'',?,?)",identity,time,time);
    }
    // Invoked under the phone guard used by creation/import. Sources accumulate.
    static void source(Data d,String profile,String source){
        if(!SOURCES.contains(source))return;
        if(d.update("UPDATE customer_source SET last_seen_at=? WHERE customer_profile_id=? AND source=?",now(),profile,source)==0)
            d.update("INSERT INTO customer_source VALUES(?,?,?,?)",profile,source,now(),now());
    }
    static List<String> sources(Data d,String pid){return d.rows("SELECT source FROM customer_source WHERE customer_profile_id=? ORDER BY first_seen_at,source",pid).stream().map(r->str(r,"source")).toList();}
    Map<String,Object> get(String identity){
        var account=d.one("SELECT a.*,i.is_development FROM customer_account a JOIN customer_identity i ON i.id=a.identity_id WHERE a.identity_id=?",identity);
        String pid=str(account,"customerProfileId");
        var profile=pid.isBlank()?Map.<String,Object>of():d.one("SELECT * FROM customer_profile WHERE id=?",pid);
        boolean verified=!pid.isBlank()&&!str(account,"phoneVerifiedAt").isBlank();
        String name=str(account,"displayName");if(name.isBlank())name=str(profile,"name");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("name",name);result.put("phone",verified?str(profile,"phone"):"");
        result.put("phoneVerified",verified);result.put("memberVerified",verified&&bool(profile,"memberVerified"));
        result.put("memberStatus",verified&&bool(profile,"memberVerified")?"MEMBER":"NON_MEMBER");
        result.put("memberGrade",verified?str(profile,"memberGrade"):"");
        result.put("sources",pid.isBlank()?List.of("MINIPROGRAM"):sources(d,pid));
        result.put("complete",verified&&!name.isBlank());result.put("firstLoginAt",account.get("firstLoginAt"));
        result.put("lastLoginAt",account.get("lastLoginAt"));result.put("development",bool(account,"isDevelopment"));
        return result;
    }
    Map<String,Object> save(String identity,Map<String,Object> input){
        checkFields(input,Set.of("name","consent"));String name=customerName(input);consent(input);
        return tx.execute(status->{
            new BookingLocks(d).acquire("customer-account:"+identity);
            var old=d.one("SELECT * FROM customer_account WHERE identity_id=?",identity);
            writeName(identity,old,name);return get(identity);
        });
    }
    Map<String,Object> phone(String identity,Map<String,Object> input){
        checkFields(input,Set.of("code","name","consent"));consent(input);
        String code=text(input,"code",1,256),name=customerName(input),hash=digest(code);
        var receipt=d.rows("SELECT * FROM customer_phone_receipt WHERE code_hash=?",hash);
        if(!receipt.isEmpty()){
            if(!identity.equals(receipt.get(0).get("identityId")))throw error(409,"该手机号授权已使用，请重新点击授权。");
            return get(identity);
        }
        String number=wx.phoneNumber(code);
        return tx.execute(status->bind(identity,number,name,hash));
    }
    Map<String,Object> bind(String identity,String phone,String name,String hash){
        new BookingLocks(d).acquire("customer-account:"+identity,"phone:"+phone);
        var account=d.one("SELECT * FROM customer_account WHERE identity_id=?",identity);
        var profiles=d.rows("SELECT * FROM customer_profile WHERE phone=?",phone);String pid;
        if(profiles.isEmpty()){
            pid=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",pid,phone,name,false,"",null,"",null,now());
        }else pid=str(profiles.get(0),"id");
        // Keep imported membership, master names and existing store notes authoritative.
        d.update("UPDATE customer_account SET customer_profile_id=?,phone_verified_at=? WHERE identity_id=?",pid,now(),identity);
        account.put("customerProfileId",pid);writeName(identity,account,name);source(d,pid,"MINIPROGRAM");
        d.update("INSERT INTO customer_phone_receipt VALUES(?,?,?,?)",hash,identity,pid,now());return get(identity);
    }
    private void writeName(String identity,Map<String,Object> previous,String name){
        d.update("UPDATE customer_account SET display_name=?,profile_consent_at=? WHERE identity_id=?",name,now(),identity);
        String pid=str(previous,"customerProfileId");
        if(!pid.isBlank())d.update("UPDATE customer_profile SET name=? WHERE id=? AND (name='' OR name=?)",name,pid,str(previous,"displayName"));
        String openid=str(d.one("SELECT openid FROM customer_identity WHERE id=?",identity),"openid");
        d.update("UPDATE chat_conversation SET display_name=? WHERE openid=? AND (display_name='访客' OR display_name=?)",name,openid,str(previous,"displayName"));
    }
    static String customerName(Map<String,Object> input){
        String name=text(input,"name",1,40);
        if(name.codePoints().anyMatch(Character::isISOControl))throw error(400,"姓名或称呼不能包含换行等控制字符。");return name;
    }
    static void consent(Map<String,Object> input){if(!bool(input,"consent"))throw error(400,"请同意将姓名和授权手机号用于客户档案与预约联系。");}
    static void checkFields(Map<String,Object> input,Set<String> allowed){if(!allowed.containsAll(input.keySet()))throw error(400,"个人资料包含不支持的字段。");}
    static String digest(String code){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
