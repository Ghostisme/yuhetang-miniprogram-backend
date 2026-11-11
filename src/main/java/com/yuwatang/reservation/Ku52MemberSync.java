package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;

/** Scheduled worker inbox and read-only source snapshots, independent of customer date filters. */
@Service
class Ku52MemberSync {
    final Data d; final TransactionTemplate tx; final String token; final int interval;
    Ku52MemberSync(Data d, PlatformTransactionManager tm,
        @Value("${app.ku52.sync-token:}") String token,
        @Value("${app.ku52.interval-seconds:300}") int interval) {
        this.d=d;this.tx=new TransactionTemplate(tm);this.token=token;this.interval=Math.max(60,interval);
    }
    boolean enabled(){return token.length()>=32;}
    void authorize(String value){
        if(!enabled()||value==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8)))throw error(403,"同步凭证无效。");
    }
    void touch(String customer){
        if(!enabled())return;
        tx.executeWithoutResult(status->{
            new BookingLocks(d).acquire("ku52-job:"+customer);
            if(d.count("SELECT COUNT(*) FROM ku52_sync_job WHERE customer_profile_id=?",customer)==0)
                d.update("INSERT INTO ku52_sync_job VALUES(?,?,?,?,?,?,?,?,?,?)",customer,"WAITING",0,0,"","","","",null,false);
        });
    }
    Map<String,Object> status(String customer){
        if(!enabled())return Map.of("enabled",false,"state","NOT_CONFIGURED","intervalSeconds",interval);
        var rows=d.rows("SELECT state,last_attempt,last_success,last_error,history_complete FROM ku52_sync_job WHERE customer_profile_id=?",customer);
        var result=new LinkedHashMap<String,Object>();if(!rows.isEmpty())result.putAll(rows.get(0));else result.put("state","WAITING");
        result.put("enabled",true);result.put("intervalSeconds",interval);
        String last=str(result,"lastSuccess");result.put("stale",last.isBlank()||OffsetDateTime.parse(last).plusSeconds(interval*2L).isBefore(OffsetDateTime.now(ZONE)));
        return result;
    }
    Map<String,Object> claim(){
        return tx.execute(status->{
            new BookingLocks(d).acquire("ku52-worker-claim");
            long epoch=System.currentTimeMillis();
            var candidates=d.rows("SELECT j.customer_profile_id,p.phone,p.external_id FROM ku52_sync_job j JOIN customer_profile p ON p.id=j.customer_profile_id WHERE j.next_due<=? AND j.lease_until<=? AND EXISTS(SELECT 1 FROM customer_account a WHERE a.customer_profile_id=p.id AND a.phone_verified_at IS NOT NULL AND a.phone_verified_at<>'') ORDER BY j.next_due,j.customer_profile_id LIMIT 1",epoch,epoch);
            if(candidates.isEmpty())return Map.of("job",Map.of());
            var row=candidates.get(0);String customer=str(row,"customerProfileId"),lease=id();
            new BookingLocks(d).acquire("ku52-job:"+customer);
            d.update("UPDATE ku52_sync_job SET state='SYNCING',lease_until=?,lease_token=?,last_attempt=? WHERE customer_profile_id=?",epoch+900_000L,lease,now(),customer);
            row.put("leaseToken",lease);return Map.of("job",row);
        });
    }
    long amount(Map<String,Object> row,String key){
        try{long n=new java.math.BigDecimal(str(row,key)).longValueExact();if(n<0||n>MemberWallets.MAX_FEN)throw new IllegalArgumentException();return n;}catch(Exception e){throw error(400,"同步金额必须是非负整数分。");}
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> in,String key,int max){
        if(!(in.get(key) instanceof List<?> list)||list.size()>max)throw error(400,"同步集合不正确："+key);
        var result=new ArrayList<Map<String,Object>>();for(Object item:list){if(!(item instanceof Map<?,?>))throw error(400,"同步记录不正确。");result.add(d.object(item));}return result;
    }
    String observed(Map<String,Object> in){
        try{var date=OffsetDateTime.parse(text(in,"observedAt",1,40)).atZoneSameInstant(ZONE).toOffsetDateTime();if(date.isAfter(OffsetDateTime.now(ZONE).plusMinutes(5))||date.isBefore(OffsetDateTime.now(ZONE).minusMinutes(30)))throw new IllegalArgumentException();return date.toString();}catch(Exception e){throw error(400,"源数据采集时间不正确，请重新采集。");}
    }
    Map<String,Object> complete(Map<String,Object> in){
        String customer=text(in,"customerProfileId",1,64),lease=text(in,"leaseToken",1,64),state=text(in,"state",1,24);
        if(!Set.of("READY","NO_MATCH","LOGIN_REQUIRED","ERROR").contains(state))throw error(400,"同步状态不正确。");
        return tx.execute(status->{
            new BookingLocks(d).acquire("ku52-job:"+customer,"ku52-snapshot");
            var job=d.one("SELECT * FROM ku52_sync_job WHERE customer_profile_id=? FOR UPDATE",customer);
            if(!lease.equals(str(job,"leaseToken"))||((Number)job.get("leaseUntil")).longValue()<System.currentTimeMillis())throw error(409,"同步任务租约已失效，请重新领取。");
            if(!state.equals("READY")){
                d.update("UPDATE ku52_sync_job SET state=?,next_due=?,lease_until=0,lease_token='',last_error=? WHERE customer_profile_id=?",state,System.currentTimeMillis()+interval*1000L,state,customer);
                return Map.of("state",state,"preserved",true);
            }
            var profile=d.one("SELECT phone FROM customer_profile WHERE id=?",customer);
            if(!str(profile,"phone").equals(text(in,"phone",1,32)))throw error(409,"采集手机号与已验证客户不一致。");
            String at=observed(in);var cards=rows(in,"cards",200);var orders=rows(in,"orders",5000);
            if(!bool(in,"cardsComplete")||integer(in,"cardCount",-1)!=cards.size())throw error(400,"卡数量未核对完整，保留上次余额。");
            long total=amount(in,"sourceTotalFen"),sum=0;var seen=new HashSet<String>();var numbers=new HashSet<String>();
            for(var card:cards){
                String key=text(card,"sourceKey",1,100),store=text(card,"storeId",1,64),number=text(card,"cardNumber",1,100);
                if(!seen.add(key)||!numbers.add(store+":"+number))throw error(409,"源卡重复，不能重复计入余额。");
                d.one("SELECT id FROM store WHERE id=?",store);long balance=amount(card,"balanceFen");sum=Math.addExact(sum,balance);
                int discount=integer(card,"discount",100);if(discount<0||discount>100)throw error(400,"折扣不正确。");
                var old=d.rows("SELECT id,customer_profile_id FROM ku52_member_card WHERE source_key=? OR (store_id=? AND card_number=?)",key,store,number);
                if(old.stream().anyMatch(r->!customer.equals(str(r,"customerProfileId"))))throw error(409,"源卡已关联其他客户，请人工核对。");
                String cid=old.isEmpty()?"ku52-"+id():str(old.get(0),"id");
                if(!old.isEmpty())d.update("DELETE FROM ku52_member_card WHERE id=?",cid);
                d.update("INSERT INTO ku52_member_card VALUES(?,?,?,?,?,?,?,?,?,?,?)",cid,customer,key,store,text(card,"memberNumber",1,100),number,text(card,"label",1,80),balance,discount,text(card,"validity",0,100),at);
            }
            if(sum!=total)throw error(409,"逐卡余额之和与源账户总额不符，保留上次余额。");
            // Full card snapshots may retire missing cards; failed/partial runs never reach this point.
            for(var old:d.rows("SELECT id,source_key FROM ku52_member_card WHERE customer_profile_id=?",customer))if(!seen.contains(str(old,"sourceKey")))d.update("DELETE FROM ku52_member_card WHERE id=?",old.get("id"));
            seen.clear();
            for(var order:orders){
                String key=text(order,"sourceKey",1,100),store=text(order,"storeId",1,64),ref=text(order,"referenceNo",1,100);
                if(!seen.add(key))throw error(409,"源消费记录重复。");d.one("SELECT id FROM store WHERE id=?",store);
                String business;try{business=OffsetDateTime.parse(text(order,"businessAt",1,40)).atZoneSameInstant(ZONE).toOffsetDateTime().toString();if(OffsetDateTime.parse(business).isAfter(OffsetDateTime.now(ZONE).plusMinutes(5)))throw new IllegalArgumentException();}catch(Exception e){throw error(400,"源消费时间不正确。");}
                String os=text(order,"status",1,16);if(!Set.of("RECORDED","VOIDED","REVERSED").contains(os))throw error(400,"源消费状态待核实。");
                var old=d.rows("SELECT id,customer_profile_id FROM ku52_consumption_record WHERE source_key=? OR (store_id=? AND reference_no=?)",key,store,ref);
                if(old.stream().anyMatch(r->!customer.equals(str(r,"customerProfileId"))))throw error(409,"源订单已关联其他客户。");
                String oid=old.isEmpty()?"ku52-"+id():str(old.get(0),"id");if(!old.isEmpty())d.update("DELETE FROM ku52_consumption_record WHERE id=?",oid);
                d.update("INSERT INTO ku52_consumption_record VALUES(?,?,?,?,?,?,?,?,?,?)",oid,customer,key,store,ref,text(order,"projectName",1,120),amount(order,"paidFen"),business,os,at);
            }
            // Missing source orders do not imply deletion. Retain previous pages and immutable local ledgers.
            d.update("UPDATE ku52_sync_job SET state='READY',next_due=?,lease_until=0,lease_token='',last_success=?,last_error='',source_total_fen=?,history_complete=? WHERE customer_profile_id=?",System.currentTimeMillis()+interval*1000L,at,total,bool(in,"historyComplete"),customer);
            return Map.of("state","READY","cards",cards.size(),"orders",orders.size());
        });
    }
    List<Map<String,Object>> accounts(String customer,String store){
        var args=store.isBlank()?new Object[]{customer}:new Object[]{customer,store};
        return d.rows("SELECT c.id,c.store_id,s.name store_name,c.member_number,c.card_number,c.label,c.balance_fen,c.discount,c.validity,c.source_updated_at updated_at,c.source_updated_at opening_as_of,1 version,'KU52' source FROM ku52_member_card c JOIN store s ON s.id=c.store_id WHERE c.customer_profile_id=?"+(store.isBlank()?"":" AND c.store_id=?")+" AND NOT EXISTS(SELECT 1 FROM member_wallet w WHERE w.customer_profile_id=c.customer_profile_id AND w.store_id=c.store_id AND (w.external_account=c.card_number OR w.external_account=c.source_key)) ORDER BY s.name,c.card_number",args);
    }
}
