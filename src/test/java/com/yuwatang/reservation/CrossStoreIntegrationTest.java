package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_cross_store;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-cross-store","app.jwt-secret=cross-store-tests-only-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc
class CrossStoreIntegrationTest {
    @Autowired Data d;@Autowired MockMvc mvc;@Autowired AuthService auth;
    String identity,token,hq,receiving,home,frontdesk,pid;
    final String phone="13900004501",tomorrow=LocalDate.now(ZONE).plusDays(1).toString();
    @BeforeEach void reset(){
        for(String table:List.of("notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_identity","customer_profile"))d.update("DELETE FROM "+table);
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));
        var session=auth.customerSession("cross-member-fixture",false);token=str(session,"token");
        identity=str(d.one("SELECT id FROM customer_identity WHERE openid='cross-member-fixture'"),"id");
        hq=admin("hq");receiving=admin("yuyao-manager");home=admin("bai-manager");frontdesk=admin("yuyao-frontdesk");
        pid=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",pid,phone,"跨店会员",true,"年卡","bai-le-men","共享接待提示",null,now());
        d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)","bai-le-men",pid,"归属店私有跟进","DIANPING",null);
        d.update("UPDATE customer_account SET customer_profile_id=?,phone_verified_at=?,display_name=? WHERE identity_id=?",pid,now(),"跨店会员",identity);
    }
    String admin(String uid){return auth.token(uid,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",uid),"tokenVersion",0));}
    Map<String,Object> request(MockHttpServletRequestBuilder req,String bearer,Object input,int expected)throws Exception{
        if(bearer!=null)req.header("Authorization","Bearer "+bearer);
        if(input!=null)req.contentType("application/json").content(d.stringify(input));
        var response=mvc.perform(req).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(expected,response.getStatus(),text);return d.object(text);
    }
    Map<String,Object> input(){var value=new LinkedHashMap<String,Object>();value.put("storeId","yuyao");value.put("serviceId","moxa");value.put("date",tomorrow);value.put("startTime","10:00");value.put("headcount",1);value.put("name","跨店会员");value.put("phone",phone);value.put("therapistIntentId","yuyao-t1");value.put("memberClaim","YES");value.put("visitedClaim","NO");value.put("remark","");value.put("privacyConsent",true);return value;}
    Map<String,Object> create()throws Exception{return request(post("/api/appointments").header("Idempotency-Key",id()),token,input(),200);}
    Map<String,Object> change(Map<String,Object> booking,String action,Map<String,Object> extra)throws Exception{
        var input=new LinkedHashMap<>(extra);input.put("version",booking.get("version"));input.put("action",action);
        return request(patch("/api/admin/appointments/"+booking.get("id")),receiving,input,200);
    }
    Map<String,Object> quote(Map<String,Object> booking,String channel,Object amount,String key,int expected)throws Exception{
        var payment=d.one("SELECT * FROM reservation_payment WHERE appointment_id=?",booking.get("id"));
        var input=new LinkedHashMap<String,Object>();input.put("channel",channel);input.put("amountFen",amount);input.put("version",payment.get("version"));input.put("appointmentVersion",booking.get("version"));
        return request(put("/api/admin/appointments/"+booking.get("id")+"/payment").header("Idempotency-Key",key),receiving,input,expected);
    }
    @Test void verifiedMemberBooksAnotherBranchWithoutMovingHomeOrCopyingPrivateNotes()throws Exception{
        var own=request(get("/api/me/profile"),token,null,200);assertEquals("bai-le-men",own.get("memberStoreId"));assertTrue(bool(own,"crossStoreBookingSupported"));
        var booking=create();var member=d.object(booking.get("membership"));assertTrue(bool(member,"memberVerified"));assertTrue(bool(member,"crossStore"));
        assertEquals("bai-le-men",member.get("memberStoreId"));assertEquals("yuyao",member.get("serviceStoreId"));assertEquals("WECHAT_PHONE",member.get("verificationMethod"));
        assertEquals("bai-le-men",d.one("SELECT * FROM customer_profile WHERE id=?",pid).get("originalStoreId"));
        assertEquals("",d.one("SELECT * FROM store_customer WHERE store_id='yuyao' AND customer_profile_id=?",pid).get("privateNote"));
        assertEquals("归属店私有跟进",d.one("SELECT * FROM store_customer WHERE store_id='bai-le-men' AND customer_profile_id=?",pid).get("privateNote"));
        request(get("/api/admin/appointments/"+booking.get("id")),home,null,404);
        assertEquals("CONFIRMED",change(booking,"confirm",Map.of()).get("status"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile"));
    }
    @Test void claimingMembershipOrTypingAnotherMembersPhoneDoesNotVerifyMembership()throws Exception{
        d.update("UPDATE customer_account SET phone_verified_at=NULL WHERE identity_id=?",identity);
        var b=create();assertFalse(bool(d.object(b.get("membership")),"memberVerified"));
        quote(b,"MEMBER_BALANCE",20000,id(),409);
        assertEquals("UNPRICED",d.object(b.get("payment")).get("status"));
    }
    @Test void membershipDoesNotFollowAnUnverifiedChangedBookingContact()throws Exception{
        var in=input();in.put("phone","13900004502");in.put("memberVerified",true);in.put("memberStoreId","yuyao");
        var b=request(post("/api/appointments").header("Idempotency-Key",id()),token,in,200);
        assertFalse(bool(d.object(b.get("membership")),"memberVerified"));assertEquals("",d.object(b.get("membership")).get("memberStoreId"));
        assertTrue(bool(d.one("SELECT * FROM customer_profile WHERE id=?",pid),"memberVerified"));
    }
    @Test void aVerifiedMemberWithUnknownHomeCanBookButCannotReserveBalanceSettlement()throws Exception{
        d.update("UPDATE customer_profile SET original_store_id=NULL WHERE id=?",pid);
        var b=create();assertEquals("ORIGIN_PENDING",d.object(b.get("membership")).get("status"));assertFalse(bool(d.object(b.get("membership")),"crossStore"));
        quote(b,"MEMBER_BALANCE",20000,id(),409);quote(b,"WECHAT_PAY",20000,id(),200);
    }
    @Test void nonMembersCanStillBookAcrossBranchesWithoutMemberBenefits()throws Exception{
        d.update("UPDATE customer_profile SET member_verified=FALSE WHERE id=?",pid);
        var b=create();assertEquals("NON_MEMBER",d.object(b.get("membership")).get("status"));assertEquals("",d.object(b.get("membership")).get("memberStoreId"));
    }
    @Test void bookingIdempotencyCreatesOneContextAndOneUnpricedPayment()throws Exception{
        String key=id();var first=request(post("/api/appointments").header("Idempotency-Key",key),token,input(),200);
        var second=request(post("/api/appointments").header("Idempotency-Key",key),token,input(),200);assertEquals(first.get("id"),second.get("id"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM appointment_membership"));assertEquals(1,d.count("SELECT COUNT(*) FROM reservation_payment"));
        var payment=d.object(first.get("payment"));assertNull(payment.get("amountFen"));assertFalse(bool(payment,"canPay"));assertEquals("UNPRICED",payment.get("status"));
    }
    @Test void membershipStillRequiresReceivingStoresProjectsAndTherapists()throws Exception{
        var in=input();in.put("therapistIntentId","bai-le-men-t1");
        request(post("/api/appointments").header("Idempotency-Key",id()),token,in,400);assertEquals(0,d.count("SELECT COUNT(*) FROM appointment"));
    }
    @Test void headquartersCanMaintainHomeWithoutMovingAnExistingBookingSnapshot()throws Exception{
        var b=create();request(put("/api/admin/customers/"+pid),hq,Map.of("storeId","bai-le-men","name","跨店会员","memberVerified",true,"memberGrade","年卡","originalStoreId","shanghai","sharedNote","","privateNote","归属店私有跟进"),200);
        assertEquals("shanghai",request(get("/api/me/profile"),token,null,200).get("memberStoreId"));
        assertEquals("bai-le-men",d.object(request(get("/api/me/appointments/"+b.get("id")),token,null,200).get("membership")).get("memberStoreId"));
        quote(b,"MEMBER_BALANCE",20000,id(),409);
    }
    @Test void storeCannotChangeMembershipOrHomeOfAnAssociatedCustomer()throws Exception{
        create();request(put("/api/admin/customers/"+pid),receiving,Map.of("storeId","yuyao","privateNote","本店接待","memberVerified",false,"originalStoreId","yuyao"),200);
        var profile=d.one("SELECT * FROM customer_profile WHERE id=?",pid);assertTrue(bool(profile,"memberVerified"));assertEquals("bai-le-men",profile.get("originalStoreId"));
    }
    @Test void paymentReservationIsScopedToReceivingStoreAndPermission()throws Exception{
        var b=create();String url="/api/admin/appointments/"+b.get("id")+"/payment";
        request(get(url),home,null,404);request(get(url),frontdesk,null,200);
        request(put(url).header("Idempotency-Key",id()),frontdesk,Map.of("channel","WECHAT_PAY","amountFen",25000,"version",1,"appointmentVersion",1),403);
        var value=quote(b,"MEMBER_BALANCE",25000,id(),200);assertEquals("RULES_PENDING",value.get("settlementStatus"));assertEquals("RESERVED",value.get("status"));assertFalse(bool(value,"paymentEnabled"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM reservation_payment_event"));
    }
    @Test void quoteMustUseIntegerFenAndDoesNotParseDisplayPrices()throws Exception{
        var b=create();for(Object amount:List.of(-1,0,12.5,"0.01",1000000000))quote(b,"WECHAT_PAY",amount,id(),400);
        assertNull(d.one("SELECT * FROM reservation_payment").get("amountFen"));
        var value=quote(b,"WECHAT_PAY",128800,id(),200);assertEquals(128800,((Number)value.get("amountFen")).intValue());
        assertEquals("NOT_STARTED",value.get("settlementStatus"));
    }
    @Test void quoteIsIdempotentAndRejectsStaleOrChangedSubmissions()throws Exception{
        var b=create();String url="/api/admin/appointments/"+b.get("id")+"/payment",key=id();
        var in=new LinkedHashMap<String,Object>(Map.of("channel","WECHAT_PAY","amountFen",25000,"version",1,"appointmentVersion",1));
        request(put(url).header("Idempotency-Key",key),receiving,in,200);request(put(url).header("Idempotency-Key",key),receiving,in,200);
        assertEquals(1,d.count("SELECT COUNT(*) FROM reservation_payment_event"));
        in.put("amountFen",26000);request(put(url).header("Idempotency-Key",key),receiving,in,409);
        request(put(url).header("Idempotency-Key",id()),receiving,in,409);
        var update=change(b,"confirm",Map.of());quote(b,"WECHAT_PAY",26000,id(),409);quote(update,"WECHAT_PAY",26000,id(),200);
    }
    @Test void simultaneousQuotesWithTheSameVersionCannotOverwriteEachOther()throws Exception{
        var b=create();String url="/api/admin/appointments/"+b.get("id")+"/payment";
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            List<Future<Integer>> jobs=new ArrayList<>();
            for(int amount:List.of(10000,20000))jobs.add(pool.submit(()->{gate.await();return mvc.perform(put(url).header("Authorization","Bearer "+receiving).header("Idempotency-Key",id()).contentType("application/json").content(d.stringify(Map.of("channel","WECHAT_PAY","amountFen",amount,"version",1,"appointmentVersion",1)))).andReturn().getResponse().getStatus();}));
            gate.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(20,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,409),codes);
        }finally{pool.shutdownNow();}
        assertEquals(1,d.count("SELECT COUNT(*) FROM reservation_payment_event"));
    }
    @Test void bothPaymentChannelsAreDisabledAndDoNotMoveMoney()throws Exception{
        var b=change(create(),"confirm",Map.of());var capabilities=request(get("/api/payment-capabilities"),null,null,200);assertFalse(bool(capabilities,"paymentEnabled"));
        for(String channel:List.of("MEMBER_BALANCE","WECHAT_PAY")){
            var quote=quote(b,channel,20000,id(),200);var before=d.one("SELECT * FROM reservation_payment");int events=d.count("SELECT COUNT(*) FROM reservation_payment_event");
            request(post("/api/me/appointments/"+b.get("id")+"/payment/start").header("Idempotency-Key",id()),token,Map.of("version",quote.get("version")),501);
            assertEquals(before,d.one("SELECT * FROM reservation_payment"));assertEquals(events,d.count("SELECT COUNT(*) FROM reservation_payment_event"));
        }
    }
    @Test void anotherCustomerCannotReadOrInitiatePayment()throws Exception{
        var b=create();String stranger=str(auth.customerSession("another-customer-fixture",false),"token"),url="/api/me/appointments/"+b.get("id")+"/payment";
        request(get(url),stranger,null,404);request(post(url+"/start").header("Idempotency-Key",id()),stranger,Map.of("version",1),404);
    }
    @Test void cancellationClosesReservationAndPreventsReopening()throws Exception{
        var b=create();quote(b,"MEMBER_BALANCE",20000,id(),200);var closed=change(b,"cancel",Map.of());
        assertEquals("CLOSED",d.one("SELECT * FROM reservation_payment").get("status"));quote(closed,"WECHAT_PAY",25000,id(),409);
    }
    @Test void aHeadcountChangeInvalidatesTheOldTotalAndKeepsAttribution()throws Exception{
        var b=create();quote(b,"MEMBER_BALANCE",20000,id(),200);
        var changed=change(b,"reschedule",Map.of("date",tomorrow,"startTime","10:30","headcount",2));
        var payment=d.one("SELECT * FROM reservation_payment");assertEquals("UNPRICED",payment.get("status"));assertNull(payment.get("amountFen"));
        assertEquals("bai-le-men",payment.get("memberStoreId"));assertEquals("yuyao",payment.get("serviceStoreId"));quote(changed,"MEMBER_BALANCE",40000,id(),200);
    }
}
