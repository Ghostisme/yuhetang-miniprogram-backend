package com.yuwatang.reservation;

import com.github.benmanes.caffeine.cache.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;

/** Customer balance lookup in the official account. Independent of mini-program chat and staff OAuth. */
@Service
class MemberServiceMessages {
    final Data d;final MemberWallets wallets;final TransactionTemplate tx;final WechatMessageCodec codec;
    final String appId;final boolean enabled;
    final SecureRandom random=new SecureRandom();
    final Cache<String,AtomicInteger> limits=Caffeine.newBuilder().maximumSize(20000).expireAfterWrite(Duration.ofMinutes(1)).build();
    MemberServiceMessages(Data d,MemberWallets wallets,PlatformTransactionManager tm,
            @Value("${app.wechat.service-app-id:}")String appId,
            @Value("${app.wechat.service-message-token:}")String token,
            @Value("${app.wechat.service-message-aes-key:}")String key,
            @Value("${app.wechat.service-message-allow-plaintext:false}")boolean plain){
        this.d=d;this.wallets=wallets;tx=new TransactionTemplate(tm);this.appId=appId;
        codec=new WechatMessageCodec(d,token,key,appId,plain);
        enabled=!appId.isBlank()&&token.matches("[a-zA-Z0-9]{3,32}")&&(plain||key.matches("[A-Za-z0-9+/]{43}"));
    }
    Map<String,Object> binding(String identity){
        var account=wallets.verified(identity);
        return Map.of("available",enabled,"bound",validBindingForProfile(str(account,"customerProfileId")),"expiresIn",300);
    }
    boolean validBindingForProfile(String profile){
        return d.count("SELECT COUNT(*) FROM member_service_binding b JOIN customer_account a ON a.identity_id=b.identity_id JOIN customer_profile p ON p.id=a.customer_profile_id WHERE b.app_id=? AND b.customer_profile_id=? AND b.customer_profile_id=a.customer_profile_id AND b.phone_snapshot=p.phone AND a.phone_verified_at IS NOT NULL AND a.phone_verified_at<>''",appId,profile)>0;
    }
    void limit(String key,int ceiling){if(limits.get(key,ignored->new AtomicInteger()).incrementAndGet()>ceiling)throw error(429,"操作较频繁，请稍后再试。");}
    Map<String,Object> code(String identity){
        if(!enabled)throw error(409,"服务号余额查询尚未启用，请联系门店。");
        limit("issue:"+identity,5);
        return tx.execute(status->{
            new BookingLocks(d).acquire("member-service:"+appId);
            var current=wallets.verified(identity);String pid=str(current,"customerProfileId");
            d.update("DELETE FROM member_service_code WHERE (app_id=? AND customer_profile_id=?) OR expires_epoch<?",appId,pid,Instant.now().minusSeconds(600).getEpochSecond());
            String alphabet="ABCDEFGHJKMNPQRSTUVWXYZ23456789";StringBuilder code=new StringBuilder();for(int i=0;i<12;i++)code.append(alphabet.charAt(random.nextInt(alphabet.length())));
            long expires=Instant.now().plusSeconds(300).getEpochSecond();String hash=CustomerProfiles.digest(appId+":"+code);
            d.update("INSERT INTO member_service_code VALUES(?,?,?,?,?,?,?)",hash,appId,identity,pid,current.get("phone"),expires,false);
            return Map.of("command","绑定 "+code,"expiresIn",300,"expiresAt",Instant.ofEpochSecond(expires).toString());
        });
    }
    Map<String,Object> unbind(String identity){
        return tx.execute(status->{
            new BookingLocks(d).acquire("member-service:"+appId);var account=wallets.verified(identity);
            d.update("DELETE FROM member_service_binding WHERE app_id=? AND customer_profile_id=?",appId,account.get("customerProfileId"));
            d.update("DELETE FROM member_service_code WHERE app_id=? AND customer_profile_id=?",appId,account.get("customerProfileId"));return Map.of("saved",true);
        });
    }
    String handle(Map<String,Object> message){
        String from=text(message,"FromUserName",1,160),to=text(message,"ToUserName",1,160),type=str(message,"MsgType");
        if(!Set.of("text","event").contains(type))return "";
        String event=str(message,"Event");
        // Do not interpret image/file data as commands. Original MsgId is stable across WeChat retries.
        String receipt=CustomerProfiles.digest(appId+":"+from+":"+(str(message,"MsgId").isBlank()?d.stringify(new TreeMap<>(message)):str(message,"MsgId")));
        return tx.execute(status->{
            // A single ordered guard covers code consumption, binding and retry receipts together.
            new BookingLocks(d).acquire("member-service:"+appId);
            long epoch=Instant.now().getEpochSecond();var previous=d.rows("SELECT reply_text FROM member_service_receipt WHERE receipt_key=? AND expires_epoch>=?",receipt,epoch);
            if(!previous.isEmpty())return str(previous.get(0),"replyText");
            d.update("DELETE FROM member_service_receipt WHERE expires_epoch<?",epoch);
            limit("receive:"+from,30);
            String command=str(message,"Content").strip(),reply="";
            if(type.equals("event")){
                if(event.equalsIgnoreCase("unsubscribe")){d.update("DELETE FROM member_service_binding WHERE app_id=? AND openid=?",appId,from);}
                else if(event.equalsIgnoreCase("CLICK")&&str(message,"EventKey").equals("MEMBER_BALANCE"))reply=balance(from);
                else if(event.equalsIgnoreCase("subscribe"))reply="欢迎关注裕和堂 YU Health。发送“余额”可查询会员余额；首次查询请先在预约小程序的个人资料中完成手机号授权和服务号绑定。";
            }else if(command.matches("(?i)绑定\\s*[A-Z2-9]{12}"))reply=bind(from,command.replaceFirst("^绑定\\s*","").toUpperCase(Locale.ROOT));
            else if(Set.of("余额","查余额","会员余额","balance").contains(command.toLowerCase(Locale.ROOT)))reply=balance(from);
            else if(Set.of("解绑","解除绑定").contains(command)){d.update("DELETE FROM member_service_binding WHERE app_id=? AND openid=?",appId,from);reply="服务号余额查询已解绑。再次查询请在小程序中重新生成绑定码。";}
            else if(command.startsWith("绑定"))reply="绑定口令格式不正确，请复制小程序“会员余额 → 服务号查询”中新生成的完整口令，5分钟内发送。";
            // Persist only handled replies; unknown messages may be handled by future official-account features.
            String xml=reply.isBlank()?"":textXml(from,to,reply);
            d.update("INSERT INTO member_service_receipt VALUES(?,?,?)",receipt,xml,epoch+600);return xml;
        });
    }
    String bind(String openid,String code){
        limit("bind:"+openid,5);
        String hash=CustomerProfiles.digest(appId+":"+code);
        var codes=d.rows("SELECT * FROM member_service_code WHERE code_hash=? AND app_id=? AND used=FALSE AND expires_epoch>=?",hash,appId,Instant.now().getEpochSecond());
        if(codes.isEmpty())return "绑定口令已失效或不正确，请在小程序中重新生成。";
        var c=codes.get(0);String pid=str(c,"customerProfileId");
        var accounts=d.rows("SELECT a.customer_profile_id,p.phone FROM customer_account a JOIN customer_profile p ON p.id=a.customer_profile_id WHERE a.identity_id=? AND a.phone_verified_at IS NOT NULL AND a.phone_verified_at<>''",c.get("identityId"));
        if(accounts.isEmpty()||!pid.equals(str(accounts.get(0),"customerProfileId"))||!str(c,"phoneSnapshot").equals(str(accounts.get(0),"phone")))return "手机号资料已变化，请在小程序中重新核实手机号并生成绑定口令。";
        var old=d.rows("SELECT customer_profile_id FROM member_service_binding WHERE app_id=? AND openid=?",appId,openid);
        if(!old.isEmpty()&&!pid.equals(str(old.get(0),"customerProfileId")))return "此微信已绑定其他会员档案。请先发送“解绑”，再重新绑定。";
        if(d.update("UPDATE member_service_code SET used=TRUE WHERE code_hash=? AND used=FALSE",hash)!=1)return "绑定口令已使用，请重新生成。";
        d.update("DELETE FROM member_service_binding WHERE app_id=? AND customer_profile_id=?",appId,pid);
        d.update("INSERT INTO member_service_binding VALUES(?,?,?,?,?,?)",appId,openid,pid,c.get("identityId"),c.get("phoneSnapshot"),now());
        return "绑定成功。以后发送“余额”即可查询您的会员账户。\n"+balance(openid);
    }
    String balance(String openid){
        var bindings=d.rows("SELECT b.customer_profile_id FROM member_service_binding b JOIN customer_account a ON a.identity_id=b.identity_id JOIN customer_profile p ON p.id=a.customer_profile_id WHERE b.app_id=? AND b.openid=? AND b.customer_profile_id=a.customer_profile_id AND b.phone_snapshot=p.phone AND a.phone_verified_at IS NOT NULL AND a.phone_verified_at<>''",appId,openid);
        if(bindings.isEmpty())return "为保护会员资料，请先进入裕和堂预约小程序，打开“我的预约 → 个人资料 → 会员余额 → 服务号查询”，复制绑定口令并发送到本服务号。绑定后发送“余额”即可查询。";
        String pid=str(bindings.get(0),"customerProfileId");var accounts=wallets.accounts(pid,"",false);
        if(accounts.isEmpty()){
            boolean member=bool(d.one("SELECT member_verified FROM customer_profile WHERE id=?",pid),"memberVerified");
            return member?"您的会员身份已确认，余额账户尚未迁入新系统。请联系门店核对原账户；暂未录入不代表余额为0。账户迁入后可直接发送“余额”查询，无需重新绑定。"
                :"您的会员资料尚未在新系统完成核对。原会员资料正在分批迁移，请联系门店核实；这不代表您不是会员，也不代表余额为0。账户迁入后可直接发送“余额”查询，无需重新绑定。";
        }
        StringBuilder answer=new StringBuilder("裕和堂 YU Health · 会员余额\n");
        // Bound reply size. The mini program always lists every account with pagination for its ledger.
        for(int i=0;i<Math.min(accounts.size(),6);i++){var w=accounts.get(i);answer.append(str(w,"storeName")).append(" · ").append(str(w,"label")).append("：¥").append(MemberWallets.yuan(MemberWallets.number(w,"balanceFen"))).append("\n");}
        if(accounts.size()>6)answer.append("其余账户请在小程序中查看。\n");
        return answer.append("仅显示已迁入并核对的账户，其他账户请联系门店核对。\n查询时间：").append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(ZonedDateTime.now(ZONE))).append("\n余额由门店后台维护，充值和消费明细请在小程序中查看。").toString();
    }
    static String cdata(String value){return "<![CDATA["+value.replace("]]>","]]]]><![CDATA[>")+"]]>";}
    static String textXml(String to,String from,String content){return "<xml><ToUserName>"+cdata(to)+"</ToUserName><FromUserName>"+cdata(from)+"</FromUserName><CreateTime>"+Instant.now().getEpochSecond()+"</CreateTime><MsgType><![CDATA[text]]></MsgType><Content>"+cdata(content)+"</Content></xml>";}
}
