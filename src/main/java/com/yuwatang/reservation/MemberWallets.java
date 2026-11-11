package com.yuwatang.reservation;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;

/** RMB balances are integer fen. Every mutation has an immutable, idempotent ledger entry. */
@Service
class MemberWallets {
    static final long MAX_FEN=999_999_999L;
    final Data d; final TransactionTemplate tx; final Ku52MemberSync sync;
    MemberWallets(Data d,PlatformTransactionManager tm,Ku52MemberSync sync){this.d=d;this.sync=sync;tx=new TransactionTemplate(tm);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    static long fen(Map<String,?> in,boolean zero){
        String value=text(in,"amount",1,16);
        if(!value.matches("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?"))throw error(400,"金额请填写最多两位小数的人民币元。");
        long amount=new BigDecimal(value).movePointRight(2).longValueExact();
        if(amount<0||(!zero&&amount==0)||amount>MAX_FEN)throw error(400,"金额超出允许范围。");return amount;
    }
    static long number(Map<String,?> row,String key){return ((Number)row.get(key)).longValue();}
    static String yuan(long fen){return BigDecimal.valueOf(fen,2).toPlainString();}
    Map<String,Object> verified(String identity){
        var rows=d.rows("SELECT a.*,p.phone FROM customer_account a JOIN customer_profile p ON p.id=a.customer_profile_id WHERE a.identity_id=? AND a.phone_verified_at IS NOT NULL AND a.phone_verified_at<>''",identity);
        if(rows.isEmpty())throw error(409,"请先在个人资料中授权手机号，以核实会员账户。");return rows.get(0);
    }
    List<Map<String,Object>> accounts(String pid,String store,boolean internal){
        var rows=d.rows("SELECT w.*,s.name store_name FROM member_wallet w JOIN store s ON s.id=w.store_id WHERE w.customer_profile_id=?"+(store.isBlank()?"":" AND w.store_id=?")+" ORDER BY s.name,w.created_at,w.id",store.isBlank()?new Object[]{pid}:new Object[]{pid,store});
        for(var row:rows){row.put("currency","CNY");if(!internal){row.remove("customerProfileId");row.remove("externalAccount");}}
        return rows;
    }
    Map<String,Object> lookup(String pid,String store,boolean internal){
        boolean member=bool(d.one("SELECT member_verified FROM customer_profile WHERE id=?",pid),"memberVerified");
        var accounts=new ArrayList<>(accounts(pid,store,internal));accounts.addAll(sync.accounts(pid,store));boolean known=!accounts.isEmpty();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("accounts",accounts);result.put("asOf",now());result.put("currency","CNY");
        result.put("lookupStatus",known?"AVAILABLE":member?"MIGRATION_PENDING":"MEMBERSHIP_PENDING");
        result.put("memberVerified",member);result.put("balanceKnown",known);
        // A phone-linked profile is not proof of membership or of a zero balance.
        // During gradual migration, the sum covers only the accounts already imported.
        result.put("balanceScope",accounts.stream().anyMatch(w->"KU52".equals(str(w,"source")))?"LOCAL_AND_SOURCE_CARDS":"MIGRATED_ACCOUNTS_ONLY");
        result.put("sourceSync",sync.status(pid));
        result.put("totalFen",known?accounts.stream().mapToLong(w->number(w,"balanceFen")).sum():null);
        return result;
    }
    Map<String,Object> own(String identity){
        var profile=verified(identity);String pid=str(profile,"customerProfileId");sync.touch(pid);return lookup(pid,"",false);
    }
    Map<String,Object> admin(Admin a,String customer,String store){
        a.require("wallets:read");if(!a.hq())store=a.storeId();else if(!store.isBlank())d.one("SELECT id FROM store WHERE id=?",store);
        d.one("SELECT id FROM customer_profile WHERE id=?",customer);
        if(!a.hq()&&d.count("SELECT COUNT(*) FROM store_customer WHERE customer_profile_id=? AND store_id=?",customer,store)==0&&d.count("SELECT COUNT(*) FROM member_wallet WHERE customer_profile_id=? AND store_id=?",customer,store)==0)throw error(404,"记录不存在或不在您的权限范围内。");
        return lookup(customer,store,true);
    }
    Map<String,Object> ledger(String wallet,int page,boolean internal){
        if(page<1||page>100000)throw error(400,"流水页码不正确。");
        var rows=d.rows("SELECT e.id,e.entry_version,e.kind,e.delta_fen,e.balance_after_fen,e.business_at,e.created_at,c.project_name,c.public_note"+(internal?",e.reference_no,e.note,e.reversal_of,e.operator_name":"")+" FROM member_wallet_entry e LEFT JOIN member_consumption_detail c ON c.entry_id=e.id WHERE e.wallet_id=? ORDER BY e.entry_version DESC LIMIT 20 OFFSET ?",wallet,(page-1)*20);
        if(internal)for(var row:rows)row.put("reversed",d.count("SELECT COUNT(*) FROM member_wallet_entry WHERE reversal_of=?",row.get("id"))>0);
        return Map.of("items",rows,"total",d.count("SELECT COUNT(*) FROM member_wallet_entry WHERE wallet_id=?",wallet),"page",page,"pageSize",20);
    }
    Map<String,Object> ownLedger(String identity,String wallet,int page){
        var profile=verified(identity);
        if(wallet.startsWith("ku52-")){d.one("SELECT id FROM ku52_member_card WHERE id=? AND customer_profile_id=?",wallet,profile.get("customerProfileId"));return Map.of("items",List.of(),"total",0,"page",page,"pageSize",20,"readOnlySource",true);}
        d.one("SELECT id FROM member_wallet WHERE id=? AND customer_profile_id=?",wallet,profile.get("customerProfileId"));return ledger(wallet,page,false);
    }
    Map<String,Object> adminLedger(Admin a,String wallet,int page){
        a.require("wallets:read");if(wallet.startsWith("ku52-")){var c=d.one("SELECT store_id FROM ku52_member_card WHERE id=?",wallet);a.store(str(c,"storeId"));return Map.of("items",List.of(),"total",0,"page",page,"pageSize",20,"readOnlySource",true);}
        var w=d.one("SELECT * FROM member_wallet WHERE id=?",wallet);a.store(str(w,"storeId"));return ledger(wallet,page,true);
    }
    String fingerprint(String action,Map<String,Object> in){var input=new TreeMap<>(in);input.remove("requestKey");return CustomerProfiles.digest(action+":"+d.stringify(input));}
    Map<String,Object> previous(Admin a,String key,String hash){
        var entries=d.rows("SELECT * FROM member_wallet_entry WHERE request_key=?",key);if(entries.isEmpty())return null;
        var entry=entries.get(0);if(!hash.equals(str(entry,"requestHash"))||!a.id().equals(str(entry,"operatorId")))throw error(409,"提交标识已被其他操作使用，请核对流水。");
        var w=d.one("SELECT * FROM member_wallet WHERE id=?",entry.get("walletId"));a.store(str(w,"storeId"));return Map.of("account",w,"entryId",entry.get("id"),"replayed",true);
    }
    Map<String,Object> open(Admin a,String customer,Map<String,Object> in){
        a.require("wallets:opening");if(!a.hq())throw error(403,"期初余额仅限总部核对后录入。");
        CustomerProfiles.checkFields(in,Set.of("storeId","externalAccount","label","amount","asOf","referenceNo","note","requestKey"));
        String store=text(in,"storeId",1,64),external=text(in,"externalAccount",1,100),label=text(in,"label",1,80);
        long amount=fen(in,true);String reference=text(in,"referenceNo",1,100),note=text(in,"note",1,300),key=text(in,"requestKey",16,80);
        OffsetDateTime cutoff;try{cutoff=OffsetDateTime.parse(text(in,"asOf",1,40));}catch(Exception e){throw error(400,"请填写含时区的余额核对时间。");}
        if(cutoff.isAfter(OffsetDateTime.now(ZONE).plusMinutes(5)))throw error(400,"余额核对时间不能晚于当前时间。");
        String hash=fingerprint("opening:"+customer,in);
        return tx.execute(status->{
            new BookingLocks(d).acquire("wallet-key:"+key,"wallet-external:"+store+":"+external);
            var previous=previous(a,key,hash);if(previous!=null)return previous;
            d.one("SELECT id FROM store WHERE id=?",store);var profile=d.one("SELECT * FROM customer_profile WHERE id=?",customer);
            if(!bool(profile,"memberVerified"))throw error(409,"请先核实该客户的会员身份，再录入期初余额。");
            if(d.count("SELECT COUNT(*) FROM member_wallet WHERE store_id=? AND external_account=?",store,external)>0)throw error(409,"该门店的原会员账户已导入；请查看现有流水，不可覆盖余额。");
            String wallet=id(),time=now();d.update("INSERT INTO member_wallet (id,customer_profile_id,store_id,external_account,label,balance_fen,version,opening_as_of,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",wallet,customer,store,external,label,amount,1,cutoff.toString(),time,time);
            String entry=insert(a,wallet,1,"OPENING",amount,amount,cutoff.toString(),reference,note,null,key,hash);
            d.audit(a,store,"wallet.opening",wallet,"期初余额分="+amount+"；凭证="+reference);
            return Map.of("account",d.one("SELECT * FROM member_wallet WHERE id=?",wallet),"entryId",entry,"replayed",false);
        });
    }
    Map<String,Object> post(Admin a,String wallet,Map<String,Object> in){
        a.require("wallets:write");CustomerProfiles.checkFields(in,Set.of("kind","amount","version","referenceNo","note","requestKey","reversalOf","projectName","publicNote"));
        String kind=text(in,"kind",1,16),key=text(in,"requestKey",16,80),reference=text(in,"referenceNo",1,100),note=text(in,"note",1,300);
        if(!Set.of("RECHARGE","CONSUME","REVERSAL").contains(kind))throw error(400,"流水类型不正确。");
        String project=text(in,"projectName",0,120),publicNote=text(in,"publicNote",0,300);
        if(!kind.equals("CONSUME")&&(!project.isBlank()||!publicNote.isBlank()))throw error(400,"消费项目与客户备注仅用于消费登记。");
        if(kind.equals("REVERSAL")){a.require("wallets:opening");if(!a.hq())throw error(403,"冲正仅限总部核实后操作。");}
        long amount=kind.equals("REVERSAL")?0:fen(in,false);int version=integer(in,"version",-1);String hash=fingerprint("entry:"+wallet,in);
        String consumptionStore=kind.equals("CONSUME")?str(d.one("SELECT store_id FROM member_wallet WHERE id=?",wallet),"storeId"):"";
        if(!consumptionStore.isBlank())a.store(consumptionStore);
        return tx.execute(status->{
            new BookingLocks(d).acquire("wallet-key:"+key,"wallet:"+wallet,consumptionStore.isBlank()?"":"consumption-order:"+consumptionStore+":"+reference);
            var prior=previous(a,key,hash);if(prior!=null)return prior;
            var w=d.one("SELECT * FROM member_wallet WHERE id=? FOR UPDATE",wallet);a.store(str(w,"storeId"));
            if(version!=integer(w,"version",0))throw error(409,"余额已变更，请刷新核对后重新提交。");
            if(kind.equals("CONSUME")&&d.count("SELECT COUNT(*) FROM customer_consumption_history WHERE store_id=? AND reference_no=?",consumptionStore,reference)>0)throw error(409,"该凭证已补录为历史消费，不能再次扣减余额，请先核对原记录。");
            long delta=kind.equals("CONSUME")?-amount:amount;String reversal=null;
            if(kind.equals("REVERSAL")){
                reversal=text(in,"reversalOf",1,64);var original=d.one("SELECT * FROM member_wallet_entry WHERE id=? AND wallet_id=?",reversal,wallet);
                if(!Set.of("RECHARGE","CONSUME").contains(str(original,"kind")))throw error(409,"仅充值或消费流水可冲正；期初差错请联系总部核对。");
                if(d.count("SELECT COUNT(*) FROM member_wallet_entry WHERE reversal_of=?",reversal)>0)throw error(409,"这条流水已经冲正。");delta=-number(original,"deltaFen");
            }
            long next=Math.addExact(number(w,"balanceFen"),delta);if(next<0)throw error(409,"余额不足，无法完成这笔登记。");if(next>MAX_FEN)throw error(409,"账户余额超出允许范围。");
            String time=now();d.update("UPDATE member_wallet SET balance_fen=?,version=version+1,updated_at=? WHERE id=?",next,time,wallet);
            String entry=insert(a,wallet,version+1,kind,delta,next,time,reference,note,reversal,key,hash);
            if(kind.equals("CONSUME"))d.update("INSERT INTO member_consumption_detail (entry_id,project_name,public_note) VALUES(?,?,?)",entry,project,publicNote);
            d.audit(a,str(w,"storeId"),"wallet."+kind.toLowerCase(Locale.ROOT),wallet,"变动分="+delta+"；凭证="+reference);
            return Map.of("account",d.one("SELECT * FROM member_wallet WHERE id=?",wallet),"entryId",entry,"replayed",false);
        });
    }
    String insert(Admin a,String wallet,int version,String kind,long delta,long balance,String business,String ref,String note,String reversal,String key,String hash){
        String entry=id();d.update("INSERT INTO member_wallet_entry VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",entry,wallet,version,kind,delta,balance,business,ref,note,reversal,key,hash,a.id(),a.name(),now());return entry;
    }
}
