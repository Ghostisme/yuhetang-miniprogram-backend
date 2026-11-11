package com.yuwatang.reservation;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yuwatang.reservation.Data.*;

/** Read-only customer history. A legacy order is never replayed as a balance debit. */
@Service
class ConsumptionRecords {
    final Data d; final MemberWallets wallets;
    ConsumptionRecords(Data d,MemberWallets wallets){this.d=d;this.wallets=wallets;}
    static final String RECORDS="""
        SELECT e.id,w.customer_profile_id,w.store_id,s.name store_name,'WALLET' source,
          COALESCE(c.project_name,'') project_name,-e.delta_fen paid_fen,e.business_at,
          e.reference_no,COALESCE(c.public_note,'') public_note,w.label account_label,
          e.balance_after_fen,
          CASE WHEN EXISTS(SELECT 1 FROM member_wallet_entry r WHERE r.reversal_of=e.id) THEN 'REVERSED' ELSE 'RECORDED' END status,
          1 version
        FROM member_wallet_entry e JOIN member_wallet w ON w.id=e.wallet_id
          JOIN store s ON s.id=w.store_id LEFT JOIN member_consumption_detail c ON c.entry_id=e.id
        WHERE e.kind='CONSUME'
        UNION ALL
        SELECT h.id,h.customer_profile_id,h.store_id,s.name store_name,'LEGACY' source,
          h.project_name,h.paid_fen,h.business_at,h.reference_no,h.public_note,'' account_label,
          NULL balance_after_fen,h.status,h.version
        FROM customer_consumption_history h JOIN store s ON s.id=h.store_id
        UNION ALL
        SELECT k.id,k.customer_profile_id,k.store_id,s.name store_name,'KU52' source,
          k.project_name,k.paid_fen,k.business_at,k.reference_no,'' public_note,'' account_label,
          NULL balance_after_fen,k.status,1 version
        FROM ku52_consumption_record k JOIN store s ON s.id=k.store_id
        WHERE NOT EXISTS(SELECT 1 FROM customer_consumption_history h WHERE h.customer_profile_id=k.customer_profile_id AND h.store_id=k.store_id AND h.reference_no=k.reference_no)
          AND NOT EXISTS(SELECT 1 FROM member_wallet_entry e JOIN member_wallet w ON w.id=e.wallet_id WHERE w.customer_profile_id=k.customer_profile_id AND w.store_id=k.store_id AND e.kind='CONSUME' AND e.reference_no=k.reference_no)
        """;
    static final String PUBLIC_FIELDS="id,store_id,store_name,source,project_name,paid_fen,business_at,reference_no,public_note,account_label,balance_after_fen,status,version";
    String ownProfile(String identity){return str(wallets.verified(identity),"customerProfileId");}
    String adminStore(Admin actor,String customer,String store){
        // Reuse wallet permissions and the existing customer/store visibility boundary.
        wallets.admin(actor,customer,store);return actor.hq()?store:actor.storeId();
    }
    Map<String,Object> own(String identity,String store,String from,String to,int page){String pid=ownProfile(identity);wallets.sync.touch(pid);return list(pid,store,from,to,page,false);}
    Map<String,Object> admin(Admin actor,String customer,String store,String from,String to,int page){return list(customer,adminStore(actor,customer,store),from,to,page,!actor.hq());}
    Map<String,Object> detail(String identity,String record){
        return d.one("SELECT "+PUBLIC_FIELDS+" FROM ("+RECORDS+") c WHERE c.customer_profile_id=? AND c.id=?",ownProfile(identity),record);
    }
    Map<String,Object> list(String customer,String store,String from,String to,int page,boolean restrictStores){
        if(page<1||page>100000)throw error(400,"消费记录页码不正确。");
        LocalDate start=date(from),end=date(to);if(start!=null&&end!=null&&start.isAfter(end))throw error(400,"开始日期不能晚于结束日期。");
        var args=new ArrayList<Object>();args.add(customer);String filter=" WHERE c.customer_profile_id=?";
        if(!store.isBlank()){filter+=" AND c.store_id=?";args.add(store);}
        if(start!=null){filter+=" AND c.business_at>=?";args.add(start+"T00:00:00");}
        if(end!=null){filter+=" AND c.business_at<?";args.add(end.plusDays(1)+"T00:00:00");}
        int total=d.count("SELECT COUNT(*) FROM ("+RECORDS+") c"+filter,args.toArray());
        args.add((page-1)*20);
        var rows=d.rows("SELECT "+PUBLIC_FIELDS+" FROM ("+RECORDS+") c"+filter+" ORDER BY c.business_at DESC,c.id DESC LIMIT 20 OFFSET ?",args.toArray());
        var stores=d.rows("SELECT DISTINCT s.id,s.name FROM store s WHERE EXISTS(SELECT 1 FROM member_wallet w WHERE w.store_id=s.id AND w.customer_profile_id=?) OR EXISTS(SELECT 1 FROM customer_consumption_history h WHERE h.store_id=s.id AND h.customer_profile_id=?) OR EXISTS(SELECT 1 FROM ku52_consumption_record k WHERE k.store_id=s.id AND k.customer_profile_id=?) ORDER BY s.name",customer,customer,customer);
        if(restrictStores)stores.removeIf(row->!store.equals(str(row,"id")));
        var result=new LinkedHashMap<String,Object>(Map.of("items",rows,"total",total,"page",page,"pageSize",20,"stores",stores,"asOf",now(),"historyComplete",false,"scope","RECORDED_ONLY"));result.put("sourceSync",wallets.sync.status(customer));return result;
    }
    static LocalDate date(String value){
        if(value.isBlank())return null;
        try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(Exception e){throw error(400,"消费记录日期格式应为 YYYY-MM-DD。");}
    }
    Map<String,Object> legacy(Admin actor,String customer,Map<String,Object> in){
        actor.require("wallets:write");
        CustomerProfiles.checkFields(in,Set.of("storeId","projectName","amount","businessAt","referenceNo","publicNote","requestKey"));
        String store=text(in,"storeId",1,64),project=text(in,"projectName",1,120),reference=text(in,"referenceNo",1,100),note=text(in,"publicNote",0,300),key=text(in,"requestKey",16,80);
        actor.store(store);adminStore(actor,customer,store);
        long amount=MemberWallets.fen(in,false);OffsetDateTime business;
        try{business=OffsetDateTime.parse(text(in,"businessAt",1,40)).atZoneSameInstant(ZONE).toOffsetDateTime();}catch(Exception e){throw error(400,"请填写含时区的消费时间。");}
        if(business.isAfter(OffsetDateTime.now(ZONE).plusMinutes(5)))throw error(400,"历史消费时间不能晚于当前时间。");
        String hash=wallets.fingerprint("legacy-consumption:"+customer,in),time=business.toString();
        return wallets.tx.execute(status->{
            new BookingLocks(d).acquire("consumption-key:"+key,"consumption-order:"+store+":"+reference);
            var replay=d.rows("SELECT * FROM customer_consumption_history WHERE request_key=?",key);
            if(!replay.isEmpty()){
                var old=replay.get(0);if(!hash.equals(str(old,"requestHash"))||!actor.id().equals(str(old,"operatorId")))throw error(409,"提交标识已被其他操作使用，请核对消费记录。");
                return Map.of("id",old.get("id"),"replayed",true);
            }
            if(d.count("SELECT COUNT(*) FROM customer_consumption_history WHERE store_id=? AND reference_no=?",store,reference)>0||d.count("SELECT COUNT(*) FROM member_wallet_entry e JOIN member_wallet w ON w.id=e.wallet_id WHERE w.store_id=? AND e.reference_no=? AND e.kind='CONSUME'",store,reference)>0)throw error(409,"该门店的消费凭证已登记，请先核对现有记录。");
            String id="legacy-"+id();
            d.update("INSERT INTO customer_consumption_history (id,customer_profile_id,store_id,reference_no,project_name,paid_fen,business_at,public_note,status,version,request_key,request_hash,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,customer,store,reference,project,amount,time,note,"RECORDED",1,key,hash,actor.id(),now());
            d.audit(actor,store,"consumption.legacy",id,"仅补录历史消费，不改变余额；凭证="+reference);
            return Map.of("id",id,"replayed",false);
        });
    }
    Map<String,Object> voidLegacy(Admin actor,String record,Map<String,Object> in){
        actor.require("wallets:write");CustomerProfiles.checkFields(in,Set.of("version","reason"));String reason=text(in,"reason",1,300);int version=integer(in,"version",-1);
        return wallets.tx.execute(status->{
            var old=d.one("SELECT * FROM customer_consumption_history WHERE id=? FOR UPDATE",record);actor.store(str(old,"storeId"));
            if("VOIDED".equals(str(old,"status")))return Map.of("saved",true,"replayed",true);
            if(version!=integer(old,"version",0))throw error(409,"消费记录已变更，请刷新后重新核对。");
            d.update("UPDATE customer_consumption_history SET status='VOIDED',version=version+1 WHERE id=?",record);
            d.audit(actor,str(old,"storeId"),"consumption.void",record,reason);
            return Map.of("saved",true,"replayed",false);
        });
    }
}
