package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_consumption;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-consumption","app.jwt-secret=consumption-tests-only-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false","app.wechat.app-id=","app.wechat.secret="
})
@AutoConfigureMockMvc
class ConsumptionIntegrationTest {
    @Autowired Data d;@Autowired MockMvc mvc;@Autowired AuthService auth;@Autowired CustomerProfiles profiles;@Autowired PlatformTransactionManager tm;
    String customer,token,hq,manager,other,front;
    @BeforeEach void setup(){
        for(String table:List.of("member_wallet_entry","member_wallet","store_customer","customer_profile","customer_identity"))d.update("DELETE FROM "+table);
        customer=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",customer,"13900006011","消费测试",true,"金卡","yuyao","","fixture",now());
        token=verified("consumption-customer","13900006011");d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)","yuyao",customer,"","IMPORT",null);
        hq=admin("hq");manager=admin("yuyao-manager");other=admin("bai-manager");front=admin("yuyao-frontdesk");
    }
    String verified(String openid,String phone){
        String bearer=str(auth.customerSession(openid,false),"token"),identity=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
        new TransactionTemplate(tm).execute(status->profiles.bind(identity,phone,"消费测试",CustomerProfiles.digest(id())));return bearer;
    }
    String admin(String user){return auth.token(user,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",user),"tokenVersion",0));}
    Map<String,Object> json(MockHttpServletRequestBuilder request,String bearer,Object body,int expected)throws Exception{
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);if(body!=null)request.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(request).andReturn().getResponse();String result=response.getContentAsString(StandardCharsets.UTF_8);assertEquals(expected,response.getStatus(),result);return d.object(result);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    @SuppressWarnings("unchecked") String wallet(String store)throws Exception{
        var result=json(post("/api/admin/customers/"+customer+"/wallets"),hq,Map.of("storeId",store,"externalAccount",id(),"label","储值账户","amount","1000","asOf",OffsetDateTime.now(ZONE).minusDays(2).toString(),"referenceNo",id(),"note","private-opening-note","requestKey",id()),200);
        return str((Map<String,Object>)result.get("account"),"id");
    }
    Map<String,Object> debit(String ref){return new LinkedHashMap<>(Map.of("kind","CONSUME","amount","88.50","version",1,"referenceNo",ref,"note","secret-internal-note","requestKey",id(),"projectName","中医推拿 60 分钟","publicNote","本次使用会员储值"));}
    Map<String,Object> legacy(String ref){return new LinkedHashMap<>(Map.of("storeId","yuyao","projectName","扶阳悬灸","amount","120.25","businessAt","2025-10-02T23:30:00+08:00","referenceNo",ref,"publicNote","已核对原消费凭证","requestKey",id()));}
    Map<String,Object> own()throws Exception{return json(get("/api/me/consumption-records"),token,null,200);}
    @Test void exposesOnlyConsumptionAndItsPublicDetails()throws Exception{
        String wallet=wallet("yuyao"),ref=id();var saved=json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit(ref),200);
        var result=own();assertEquals(1,items(result).size());var entry=items(result).get(0);
        assertEquals(8850,integer(entry,"paidFen",0));assertEquals("中医推拿 60 分钟",entry.get("projectName"));assertEquals("WALLET",entry.get("source"));assertEquals(ref,entry.get("referenceNo"));
        assertFalse(d.stringify(result).contains("secret-internal-note"));assertFalse(entry.containsKey("customerProfileId"));assertFalse(entry.containsKey("operatorId"));assertFalse(bool(result,"historyComplete"));
        assertEquals(entry,json(get("/api/me/consumption-records/"+saved.get("entryId")),token,null,200));
    }
    @Test void legacyEntryIsIdempotentAndNeverTouchesBalance()throws Exception{
        String wallet=wallet("yuyao");var input=legacy("00000000000000000000012345");
        var saved=json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,input,200);
        assertTrue(bool(json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,input,200),"replayed"));
        assertEquals(100000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));assertEquals(1,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
        assertEquals("LEGACY",items(own()).get(0).get("source"));assertEquals("00000000000000000000012345",items(own()).get(0).get("referenceNo"));
        input.put("amount","10");json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,input,409);
        json(post("/api/admin/consumption-records/"+saved.get("id")+"/void"),manager,Map.of("version",1,"reason","原凭证录入有误"),200);
        assertEquals("VOIDED",items(own()).get(0).get("status"));assertEquals(100000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));
    }
    @Test void blocksDuplicateLegacyOrderAndARecordedLiveOrder()throws Exception{
        var input=legacy("old-order-1");json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,input,200);input.put("requestKey",id());json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,input,409);
        String wallet=wallet("yuyao");json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit("old-order-1"),409);
        assertEquals(100000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));
        json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit("live-order-1"),200);
        json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,legacy("live-order-1"),409);
    }
    @Test void rejectsInvalidPublicFieldsBeforeChangingABalance()throws Exception{
        String wallet=wallet("yuyao");var input=debit(id());input.put("projectName","x".repeat(121));
        json(post("/api/admin/wallets/"+wallet+"/entries"),manager,input,400);
        input=debit(id());input.put("publicNote","x".repeat(301));json(post("/api/admin/wallets/"+wallet+"/entries"),manager,input,400);
        input=debit(id());input.put("kind","RECHARGE");json(post("/api/admin/wallets/"+wallet+"/entries"),manager,input,400);
        assertEquals(100000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));assertEquals(1,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
    }
    @SuppressWarnings("unchecked") @Test void customerStoreFilterRetainsOwnChoicesAndBranchMetadataStaysScoped()throws Exception{
        wallet("yuyao");wallet("bai-le-men");
        json(post("/api/admin/customers/"+customer+"/consumption-records"),hq,legacy(id()),200);
        var selected=json(get("/api/me/consumption-records?storeId=yuyao"),token,null,200);
        assertEquals(2,((List<?>)selected.get("stores")).size());
        var branch=json(get("/api/admin/customers/"+customer+"/consumption-records"),manager,null,200);
        var choices=(List<Map<String,Object>>)branch.get("stores");assertEquals(1,choices.size());assertEquals("yuyao",choices.get(0).get("id"));
    }
    @Test void reversalIsVisibleWithoutDeletingOriginalConsumption()throws Exception{
        String wallet=wallet("yuyao");var saved=json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit(id()),200);
        json(post("/api/admin/wallets/"+wallet+"/entries"),hq,Map.of("kind","REVERSAL","version",2,"reversalOf",saved.get("entryId"),"referenceNo",id(),"note","private-reversal","requestKey",id()),200);
        assertEquals(1,items(own()).size());assertEquals("REVERSED",items(own()).get(0).get("status"));
        assertEquals(100000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));
    }
    @Test void historicalWalletDebitsRemainReadableWithoutNewDetails()throws Exception{
        String wallet=wallet("yuyao");var input=debit(id());input.remove("projectName");input.remove("publicNote");var saved=json(post("/api/admin/wallets/"+wallet+"/entries"),manager,input,200);
        d.update("DELETE FROM member_consumption_detail WHERE entry_id=?",saved.get("entryId"));
        assertEquals("",items(own()).get(0).get("projectName"));assertFalse(d.stringify(own()).contains("secret-internal-note"));
    }
    @Test void verifiesPhoneAndRestrictsCustomerAndStoreAccess()throws Exception{
        var saved=json(post("/api/admin/customers/"+customer+"/consumption-records"),hq,legacy(id()),200);
        json(get("/api/me/consumption-records"),null,null,401);
        String unverified=str(auth.customerSession("unverified-consumption",false),"token");json(get("/api/me/consumption-records"),unverified,null,409);
        String stranger=verified("consumption-stranger","13900006012");assertTrue(items(json(get("/api/me/consumption-records"),stranger,null,200)).isEmpty());json(get("/api/me/consumption-records/"+saved.get("id")),stranger,null,404);
        json(get("/api/admin/customers/"+customer+"/consumption-records"),other,null,404);json(post("/api/admin/consumption-records/"+saved.get("id")+"/void"),other,Map.of("version",1,"reason","fixture"),404);
        json(post("/api/admin/customers/"+customer+"/consumption-records"),front,legacy(id()),403);
        var otherStore=legacy(id());otherStore.put("storeId","bai-le-men");json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,otherStore,404);
    }
    @Test void dateFiltersRespectShanghaiDayAndPagination()throws Exception{
        for(int i=0;i<22;i++)json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,legacy("fixture-"+i),200);
        var first=json(get("/api/me/consumption-records?from=2025-10-02&to=2025-10-02"),token,null,200);assertEquals(22,integer(first,"total",0));assertEquals(20,items(first).size());
        assertEquals(2,items(json(get("/api/me/consumption-records?page=2"),token,null,200)).size());
        assertTrue(items(json(get("/api/me/consumption-records?from=2025-10-03"),token,null,200)).isEmpty());
        assertTrue(items(json(get("/api/me/consumption-records?storeId=bai-le-men"),token,null,200)).isEmpty());
        for(String q:List.of("page=0","from=2025-99-02","from=2025-10-03&to=2025-10-02"))json(get("/api/me/consumption-records?"+q),token,null,400);
    }
    @Test void permitsLegacyHistoryForNonMembersWithoutOpeningAnAccount()throws Exception{
        d.update("UPDATE customer_profile SET member_verified=FALSE WHERE id=?",customer);
        json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,legacy(id()),200);assertEquals(1,items(own()).size());assertEquals(0,d.count("SELECT COUNT(*) FROM member_wallet"));
        var future=legacy(id());future.put("businessAt",OffsetDateTime.now(ZONE).plusDays(1).toString());json(post("/api/admin/customers/"+customer+"/consumption-records"),manager,future,400);
    }
}
