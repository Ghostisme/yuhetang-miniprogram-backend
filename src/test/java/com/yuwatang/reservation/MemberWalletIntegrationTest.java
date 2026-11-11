package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_member_wallet;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-member-wallet","app.jwt-secret=member-wallet-tests-only-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=wallet-service-fixture",
    "app.wechat.service-secret=","app.wechat.service-message-token=MemberFixtureToken",
    "app.wechat.service-message-aes-key=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
    "app.wechat.service-message-allow-plaintext=true"
})
@AutoConfigureMockMvc
class MemberWalletIntegrationTest {
    @Autowired Data d;@Autowired MockMvc mvc;@Autowired AuthService auth;@Autowired MemberWallets wallets;
    @Autowired MemberServiceMessages service;@Autowired CustomerProfiles profiles;@Autowired PlatformTransactionManager tm;
    String customer,identity,token,hq,manager,other,front;
    @BeforeEach void setup(){
        for(String table:List.of("member_service_receipt","member_service_code","member_service_binding","member_wallet_entry","member_wallet","store_customer","customer_profile","customer_identity"))d.update("DELETE FROM "+table);
        service.limits.invalidateAll();
        customer=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",customer,"13900006001","会员测试",true,"金卡","yuyao","","wallet-customer",now());
        var session=auth.customerSession("wallet-customer-openid",false);token=str(session,"token");identity=str(d.one("SELECT id FROM customer_identity WHERE openid='wallet-customer-openid'"),"id");
        new TransactionTemplate(tm).execute(status->profiles.bind(identity,"13900006001","会员测试",CustomerProfiles.digest(id())));
        d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)","yuyao",customer,"","IMPORT",null);
        hq=admin("hq");manager=admin("yuyao-manager");other=admin("bai-manager");front=admin("yuyao-frontdesk");
    }
    String admin(String id){return auth.token(id,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",id),"tokenVersion",0));}
    Map<String,Object> json(MockHttpServletRequestBuilder request,String bearer,Object input,int expected)throws Exception{
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);if(input!=null)request.contentType("application/json").content(d.stringify(input));
        var response=mvc.perform(request).andReturn().getResponse();String body=response.getContentAsString(StandardCharsets.UTF_8);assertEquals(expected,response.getStatus(),body);return d.object(body);
    }
    Map<String,Object> opening(String store,String amount){return new LinkedHashMap<>(Map.of("storeId",store,"externalAccount",id(),"label","储值账户","amount",amount,"asOf",OffsetDateTime.now(ZONE).minusMinutes(10).toString(),"referenceNo","fixture-opening","note","仅隔离测试","requestKey",id()));}
    Map<String,Object> entry(String kind,String amount,int version){return new LinkedHashMap<>(Map.of("kind",kind,"amount",amount,"version",version,"referenceNo","fixture-transaction","note","内部测试备注不可发给客户","requestKey",id()));}
    @SuppressWarnings("unchecked") Map<String,Object> account(Map<String,Object> result){return (Map<String,Object>)result.get("account");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result,String key){return (List<Map<String,Object>>)result.get(key);}
    String open(String store,String amount)throws Exception{return str(account(json(post("/api/admin/customers/"+customer+"/wallets"),hq,opening(store,amount),200)),"id");}
    @Test void noAccountMeansUnknownAndOpeningIsExactAndIndependentPerStore()throws Exception{
        var pending=json(get("/api/me/wallets"),token,null,200);
        assertTrue(rows(pending,"accounts").isEmpty());assertEquals("MIGRATION_PENDING",pending.get("lookupStatus"));
        assertFalse(bool(pending,"balanceKnown"));assertNull(pending.get("totalFen"));
        open("yuyao","3928.01");open("bai-le-men","10.20");
        var result=json(get("/api/me/wallets"),token,null,200);assertEquals(393821L,((Number)result.get("totalFen")).longValue());assertEquals(2,rows(result,"accounts").size());
        assertEquals("AVAILABLE",result.get("lookupStatus"));assertTrue(bool(result,"balanceKnown"));assertEquals("MIGRATED_ACCOUNTS_ONLY",result.get("balanceScope"));
        assertFalse(rows(result,"accounts").get(0).containsKey("externalAccount"));
    }
    @Test void verifiedPhoneWithoutMigratedMembershipNeverReportsAZeroBalance()throws Exception{
        d.update("UPDATE customer_profile SET member_verified=FALSE WHERE id=?",customer);
        var result=json(get("/api/me/wallets"),token,null,200);assertEquals("MEMBERSHIP_PENDING",result.get("lookupStatus"));
        assertFalse(bool(result,"memberVerified"));assertFalse(bool(result,"balanceKnown"));assertTrue(result.containsKey("totalFen"));assertNull(result.get("totalFen"));
        var internal=json(get("/api/admin/customers/"+customer+"/wallets"),hq,null,200);
        assertEquals("MEMBERSHIP_PENDING",internal.get("lookupStatus"));assertNull(internal.get("totalFen"));
    }
    @Test void anExplicitlyMigratedZeroBalanceIsKnownAndStoreScopesRemainIndependent()throws Exception{
        open("yuyao","0");var result=json(get("/api/me/wallets"),token,null,200);
        assertEquals("AVAILABLE",result.get("lookupStatus"));assertTrue(bool(result,"balanceKnown"));assertEquals(0L,((Number)result.get("totalFen")).longValue());
        var otherStore=json(get("/api/admin/customers/"+customer+"/wallets").queryParam("storeId","bai-le-men"),hq,null,200);
        assertEquals("MIGRATION_PENDING",otherStore.get("lookupStatus"));assertNull(otherStore.get("totalFen"));
    }
    @Test void openingRetryIsIdempotentButSourceAccountCannotBeOverwritten()throws Exception{
        var in=opening("yuyao","3928");json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,200);
        assertTrue(bool(json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,200),"replayed"));
        in.put("requestKey",id());json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,409);
        assertEquals(1,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
        in.put("storeId","bai-le-men");json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,200);
    }
    @Test void rejectsFractionalFenNegativeScientificAndMissingMoney()throws Exception{
        for(String amount:List.of("-1","1.001","1e3","NaN","1,000","10000000.00","")){var in=opening("yuyao",amount);json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,400);}
        open("yuyao","0");assertEquals(1,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
    }
    @Test void requiresVerifiedMembershipSourceReferenceAndPastCutoff()throws Exception{
        var in=opening("yuyao","20");in.put("referenceNo","");json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,400);
        in=opening("yuyao","20");in.put("asOf",OffsetDateTime.now(ZONE).plusDays(1).toString());json(post("/api/admin/customers/"+customer+"/wallets"),hq,in,400);
        d.update("UPDATE customer_profile SET member_verified=FALSE WHERE id=?",customer);json(post("/api/admin/customers/"+customer+"/wallets"),hq,opening("yuyao","20"),409);
    }
    @Test void rechargeConsumeAndReversalKeepOriginalImmutableLedgerAndPreventOverdraft()throws Exception{
        String wallet=open("yuyao","100");var recharge=entry("RECHARGE","25.50",1);var credited=json(post("/api/admin/wallets/"+wallet+"/entries"),manager,recharge,200);assertEquals(12550,integer(account(credited),"balanceFen",0));
        json(post("/api/admin/wallets/"+wallet+"/entries"),manager,entry("CONSUME","200",2),409);
        var consumed=json(post("/api/admin/wallets/"+wallet+"/entries"),manager,entry("CONSUME","20.20",2),200);assertEquals(10530,integer(account(consumed),"balanceFen",0));
        var reversal=new LinkedHashMap<>(Map.of("kind","REVERSAL","version",3,"reversalOf",consumed.get("entryId"),"referenceNo","REV-1","note","核实后冲正","requestKey",id()));
        json(post("/api/admin/wallets/"+wallet+"/entries"),manager,reversal,403);json(post("/api/admin/wallets/"+wallet+"/entries"),hq,reversal,200);
        reversal.put("version",4);reversal.put("requestKey",id());json(post("/api/admin/wallets/"+wallet+"/entries"),hq,reversal,409);
        assertEquals(4,d.count("SELECT COUNT(*) FROM member_wallet_entry WHERE wallet_id=?",wallet));assertEquals(12550,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));
    }
    @Test void sameRequestDoesNotChargeTwiceAndChangedRetryIsRejected()throws Exception{
        String wallet=open("yuyao","100");var debit=entry("CONSUME","10.11",1);
        json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit,200);assertTrue(bool(json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit,200),"replayed"));
        debit.put("amount","20");json(post("/api/admin/wallets/"+wallet+"/entries"),manager,debit,409);
        assertEquals(8989,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));
    }
    @Test void concurrentDebitsCannotUseTheSameBalanceVersion()throws Exception{
        String wallet=open("yuyao","100");var actor=new Admin("hq","总部","",Set.of("hq"),Set.of("wallets:write"),true);
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(executor.submit(()->{start.await();try{wallets.post(actor,wallet,entry("CONSUME","80",1));return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}}));start.countDown();
            int successful=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successful++;assertEquals(1,successful);
        }finally{executor.shutdownNow();}
        assertEquals(2000,integer(d.one("SELECT balance_fen FROM member_wallet WHERE id=?",wallet),"balanceFen",0));assertEquals(2,d.count("SELECT COUNT(*) FROM member_wallet_entry"));
    }
    @Test void scopedAdminCannotReadOrChangeAnotherStoreAndFrontDeskCannotWrite()throws Exception{
        String wallet=open("yuyao","30");json(get("/api/admin/customers/"+customer+"/wallets"),other,null,404);
        json(get("/api/admin/wallets/"+wallet+"/entries"),other,null,404);json(post("/api/admin/wallets/"+wallet+"/entries"),other,entry("CONSUME","1",1),404);
        json(post("/api/admin/customers/"+customer+"/wallets"),manager,opening("yuyao","2"),403);json(post("/api/admin/wallets/"+wallet+"/entries"),front,entry("CONSUME","1",1),403);
    }
    @Test void customerReadRequiresAuthorizedPhoneAndNeverAcceptsAnotherWalletOrRevealsInternalNotes()throws Exception{
        String wallet=open("yuyao","12");json(post("/api/admin/wallets/"+wallet+"/entries"),manager,entry("RECHARGE","1",1),200);
        var view=json(get("/api/me/wallets/"+wallet+"/entries"),token,null,200);assertFalse(d.stringify(view).contains("内部测试备注"));assertFalse(d.stringify(view).contains("operatorName"));
        String otherToken=str(auth.customerSession("other-customer",false),"token"),otherId=str(d.one("SELECT id FROM customer_identity WHERE openid='other-customer'"),"id");
        json(get("/api/me/wallets"),otherToken,null,409);new TransactionTemplate(tm).execute(status->profiles.bind(otherId,"13900006002","另一客户",CustomerProfiles.digest(id())));
        json(get("/api/me/wallets/"+wallet+"/entries"),otherToken,null,404);json(get("/api/me/wallets"),null,null,401);
    }
    String sign(String...parts)throws Exception{var values=new ArrayList<>(List.of(parts));Collections.sort(values);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("",values).getBytes(StandardCharsets.UTF_8)));}
    String callback(String content,String from,String msgId,int expected)throws Exception{
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="fixture-nonce",xml="<xml><ToUserName>gh_fixture</ToUserName><FromUserName>"+from+"</FromUserName><MsgType>text</MsgType><MsgId>"+msgId+"</MsgId><Content>"+MemberServiceMessages.cdata(content)+"</Content></xml>";
        var response=mvc.perform(post("/api/wechat/service/messages").queryParam("signature",sign("MemberFixtureToken",timestamp,nonce)).queryParam("timestamp",timestamp).queryParam("nonce",nonce).contentType("application/xml").content(xml)).andReturn().getResponse();
        String body=response.getContentAsString(StandardCharsets.UTF_8);assertEquals(expected,response.getStatus(),body);return body;
    }
    String issue()throws Exception{return str(json(post("/api/me/wallet-service/code"),token,null,200),"command");}
    @Test void bindingBeforeGradualMigrationStartsWorkingWithoutRebinding()throws Exception{
        d.update("UPDATE customer_profile SET member_verified=FALSE WHERE id=?",customer);
        String pending=callback(issue(),"oa-gradual","migration-bind",200);
        assertTrue(pending.contains("绑定成功"));assertTrue(pending.contains("这不代表您不是会员"));assertFalse(pending.contains("¥0.00"));
        assertTrue(bool(json(get("/api/me/wallet-service"),token,null,200),"bound"));
        d.update("UPDATE customer_profile SET member_verified=TRUE WHERE id=?",customer);
        assertTrue(callback("余额","oa-gradual","migration-confirmed",200).contains("余额账户尚未迁入"));
        open("yuyao","3928");String first=callback("余额","oa-gradual","migration-first-account",200);
        assertTrue(first.contains("3928.00"));assertTrue(first.contains("仅显示已迁入并核对的账户"));
        open("bai-le-men","10.20");String second=callback("余额","oa-gradual","migration-second-account",200);
        assertTrue(second.contains("3928.00"));assertTrue(second.contains("10.20"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM member_service_binding"));
    }
    @Test void serviceAccountMustBindSeparatelyAndCannotUseMiniOrStaffOpenidAsCustomerProof()throws Exception{
        open("yuyao","3928");String unbound=callback("余额","official-customer","msg-unbound",200);assertFalse(unbound.contains("3928.00"));assertTrue(unbound.contains("绑定"));
        assertFalse(bool(json(get("/api/me/wallet-service"),token,null,200),"bound"));
        String command=issue(),bound=callback(command,"official-customer","msg-bind",200);assertTrue(bound.contains("绑定成功"));assertTrue(bound.contains("3928.00"));
        assertTrue(bool(json(get("/api/me/wallet-service"),token,null,200),"bound"));assertTrue(callback("余额","official-customer","msg-balance",200).contains("3928.00"));
        assertFalse(callback("余额","wallet-customer-openid","msg-mini",200).contains("3928.00"));
    }
    @Test void codeIsOneUseExpiredCodesFailAndWechatRetriesUseReceipt()throws Exception{
        open("yuyao","300");String code=issue(),reply=callback(code,"oa-one","wx-one",200);assertEquals(reply,callback(code,"oa-one","wx-one",200));
        assertTrue(callback(code,"oa-two","wx-two",200).contains("失效"));assertEquals(1,d.count("SELECT COUNT(*) FROM member_service_binding"));
        code=issue();d.update("UPDATE member_service_code SET expires_epoch=1");assertTrue(callback(code,"oa-one","wx-expired",200).contains("失效"));
    }
    @Test void unbindingOrPhoneChangeRemovesBalanceDisclosure()throws Exception{
        open("yuyao","3928");callback(issue(),"oa-one","bind-one",200);
        json(delete("/api/me/wallet-service"),token,null,200);assertFalse(callback("余额","oa-one","after-unbind",200).contains("3928.00"));
        callback(issue(),"oa-one","bind-again",200);new TransactionTemplate(tm).execute(status->profiles.bind(identity,"13900006003","会员测试",CustomerProfiles.digest(id())));
        assertFalse(callback("余额","oa-one","after-phone-change",200).contains("3928.00"));
    }
    @Test void safeModeVerifiesAndEncryptsPassiveRepliesAndInvalidSignaturesNeverBind()throws Exception{
        open("yuyao","3928");String command=issue();String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="safe-test";
        String original="<xml><ToUserName>gh_fixture</ToUserName><FromUserName>oa-encrypted</FromUserName><MsgType>text</MsgType><MsgId>encrypted-one</MsgId><Content>"+command+"</Content></xml>";
        var envelope=service.codec.parse(service.codec.encryptedReply(original));String encrypted=str(envelope,"Encrypt");
        var request=post("/api/wechat/service/messages").queryParam("timestamp",timestamp).queryParam("nonce",nonce).queryParam("msg_signature",sign("MemberFixtureToken",timestamp,nonce,encrypted)).contentType("application/xml").content("<xml><Encrypt>"+encrypted+"</Encrypt></xml>");
        var response=mvc.perform(request).andReturn().getResponse();assertEquals(200,response.getStatus());var reply=service.codec.parse(response.getContentAsString(StandardCharsets.UTF_8));
        service.codec.verify(str(reply,"MsgSignature"),str(reply,"TimeStamp"),str(reply,"Nonce"),str(reply,"Encrypt"));String plain=service.codec.decrypt(str(reply,"Encrypt"));assertTrue(plain.contains("3928.00"));assertTrue(plain.contains("oa-encrypted"));
        var invalid=mvc.perform(post("/api/wechat/service/messages").queryParam("timestamp",timestamp).queryParam("nonce",nonce).queryParam("signature","invalid").contentType("application/xml").content(original)).andReturn().getResponse();assertEquals(403,invalid.getStatus());
    }
    @Test void serviceVerificationEchoAndXmlEntityDefenseAreEnforced()throws Exception{
        String ts=Long.toString(Instant.now().getEpochSecond()),nonce="echo";var r=mvc.perform(get("/api/wechat/service/messages").queryParam("signature",sign("MemberFixtureToken",ts,nonce)).queryParam("timestamp",ts).queryParam("nonce",nonce).queryParam("echostr","fixture-echo")).andReturn().getResponse();assertEquals(200,r.getStatus());assertEquals("fixture-echo",r.getContentAsString());
        var xxe=mvc.perform(post("/api/wechat/service/messages").queryParam("timestamp",ts).queryParam("nonce",nonce).queryParam("signature",sign("MemberFixtureToken",ts,nonce)).contentType("application/xml").content("<!DOCTYPE xml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><xml><Content>&x;</Content></xml>")).andReturn().getResponse();assertEquals(400,xxe.getStatus());
    }
}
