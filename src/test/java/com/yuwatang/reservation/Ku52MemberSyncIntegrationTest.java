package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:yht_ku52;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa","spring.datasource.password=", "app.mode=local", "app.data-dir=target/isolated-ku52", "app.jwt-secret=ku52-tests-only-more-than-32-characters", "app.admin-password=Yht@9800dev", "app.pms.enabled=false", "app.wechat.app-id=", "app.wechat.secret=", "app.ku52.sync-token=fixture-key-more-than-thirty-two-characters"})
@AutoConfigureMockMvc
class Ku52MemberSyncIntegrationTest {
    @Autowired Data d;@Autowired Ku52MemberSync sync;@Autowired MemberWallets wallets;@Autowired ConsumptionRecords consumption;
    @Autowired AuthService auth;@Autowired CustomerProfiles profiles;@Autowired PlatformTransactionManager tm;@Autowired MockMvc mvc;
    String customer,identity;
    @BeforeEach void setup(){
        d.update("DELETE FROM member_wallet_entry");d.update("DELETE FROM member_wallet");d.update("DELETE FROM customer_profile");
        customer=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",customer,"13900007001","隔离测试",true,"","bai-le-men","","ku52:1031:fixture",now());
        String openid="ku52-"+id();auth.customerSession(openid,false);
        identity=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
        // Bind through the existing verified account contract; unrelated profiles stay isolated.
        new TransactionTemplate(tm).execute(status->profiles.bind(identity,"13900007001","隔离测试",CustomerProfiles.digest(id())));
        sync.touch(customer);
    }
    @SuppressWarnings("unchecked") Map<String,Object> job(){return (Map<String,Object>)sync.claim().get("job");}
    Map<String,Object> card(String key,String store,String number,long balance){return new LinkedHashMap<>(Map.of("sourceKey",key,"storeId",store,"memberNumber","fixture-member-01","cardNumber",number,"label","七折卡","balanceFen",balance,"discount",70,"validity","2026.07.23 - 2027.07.23"));}
    Map<String,Object> snapshot(Map<String,Object> job){
        var result=new LinkedHashMap<String,Object>();result.putAll(job);result.put("state","READY");result.put("observedAt",now());result.put("phone","13900007001");result.put("cardsComplete",true);result.put("historyComplete",false);result.put("cardCount",2);result.put("sourceTotalFen",755100);
        result.put("cards",new ArrayList<>(List.of(card("card-a","changde","fixture-a",601800),card("card-b","shanghai","fixture-b",153300))));
        result.put("orders",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("sourceKey","order-fixture","storeId","yuyao","referenceNo","fixture-order-001","projectName","中医推拿","paidFen",32500,"businessAt","2025-10-09T15:00:00+08:00","status","RECORDED")))));
        return result;
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(Map<String,Object> result,String key){return (List<Map<String,Object>>)result.get(key);}
    void due(){d.update("UPDATE ku52_sync_job SET next_due=0,lease_until=0 WHERE customer_profile_id=?",customer);}
    @Test void exactCardsAreSeparateFromMemberNumberAndDoNotChangeTheHomeStore(){
        sync.complete(snapshot(job()));var result=wallets.own(identity);
        assertEquals(755100L,((Number)result.get("totalFen")).longValue());assertEquals(2,list(result,"accounts").size());assertEquals("LOCAL_AND_SOURCE_CARDS",result.get("balanceScope"));
        assertTrue(list(result,"accounts").stream().allMatch(r->"fixture-member-01".equals(str(r,"memberNumber"))));
        assertEquals("bai-le-men",d.one("SELECT original_store_id FROM customer_profile WHERE id=?",customer).get("originalStoreId"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
    }
    @Test void changingDatesDoesNotChangeSourceJobOrRequireASelectedDate(){
        sync.complete(snapshot(job()));var first=consumption.own(identity,"","","",1);assertEquals(1,first.get("total"));
        Object next=d.one("SELECT next_due FROM ku52_sync_job WHERE customer_profile_id=?",customer).get("nextDue");
        assertEquals(0,consumption.own(identity,"","2026-01-01","2026-12-31",1).get("total"));
        assertEquals(1,consumption.own(identity,"yuyao","2025-01-01","2025-12-31",1).get("total"));
        assertEquals(next,d.one("SELECT next_due FROM ku52_sync_job WHERE customer_profile_id=?",customer).get("nextDue"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
    }
    @Test void sourceRefreshIsIdempotentAndFailedFetchNeverTurnsBalanceIntoZero(){
        sync.complete(snapshot(job()));due();sync.complete(snapshot(job()));assertEquals(1,d.count("SELECT COUNT(*) FROM ku52_consumption_record"));
        due();var job=job();job.put("state","LOGIN_REQUIRED");sync.complete(job);
        assertEquals(755100L,((Number)wallets.own(identity).get("totalFen")).longValue());assertEquals("LOGIN_REQUIRED",sync.status(customer).get("state"));
    }
    @Test void incompleteOrUnbalancedSnapshotsAreRolledBackAndLeaseAllowsFailureReport(){
        var initial=job();var wrong=snapshot(initial);wrong.put("sourceTotalFen",0);assertThrows(Exception.class,()->sync.complete(wrong));assertEquals(0,d.count("SELECT COUNT(*) FROM ku52_member_card"));
        var incomplete=snapshot(initial);incomplete.put("cardCount",3);assertThrows(Exception.class,()->sync.complete(incomplete));
        initial.put("state","ERROR");sync.complete(initial);assertEquals("ERROR",sync.status(customer).get("state"));
    }
    @Test void phoneOrCardOwnershipMismatchCannotMergeCustomers(){
        var j=job();var wrong=snapshot(j);wrong.put("phone","13900007002");assertThrows(Exception.class,()->sync.complete(wrong));
        sync.complete(snapshot(j));String other=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",other,"13900007002","另一个客户",true,"","shanghai","","",now());
        d.update("UPDATE ku52_member_card SET customer_profile_id=? WHERE source_key='card-a'",other);due();var conflict=snapshot(job());assertThrows(Exception.class,()->sync.complete(conflict));
    }
    @Test void secondWorkerCannotClaimTheSameLiveLeaseAndExpiredLeaseIsRejected(){
        var old=job();assertTrue(job().isEmpty());due();var current=job();assertNotEquals(old.get("leaseToken"),current.get("leaseToken"));assertThrows(Exception.class,()->sync.complete(snapshot(old)));sync.complete(snapshot(current));
    }
    @Test void migratedLocalAccountWinsForTheSameStoreAndCardNumber(){
        sync.complete(snapshot(job()));String at=now();d.update("INSERT INTO member_wallet VALUES(?,?,?,?,?,?,?,?,?,?)",id(),customer,"changde","fixture-a","已迁入账户",600000,1,at,at,at);
        var result=wallets.own(identity);assertEquals(2,list(result,"accounts").size());assertEquals(753300L,((Number)result.get("totalFen")).longValue());
    }
    @Test void apiRequiresDedicatedCredentialAndNeverExposesAnotherCustomersCard()throws Exception{
        mvc.perform(post("/api/internal/ku52-sync/claim")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(post("/api/internal/ku52-sync/claim").header("X-YHT-Sync-Key","wrong")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        sync.complete(snapshot(job()));var account=list(wallets.own(identity),"accounts").get(0);
        assertThrows(Exception.class,()->wallets.ownLedger("another-identity",str(account,"id"),1));
        assertTrue(list(wallets.ownLedger(identity,str(account,"id"),1),"items").isEmpty());
    }
}
