package com.yuwatang.reservation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_customer_profile;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-customer-profile","app.jwt-secret=customer-profile-tests-only-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret="
})
@AutoConfigureMockMvc
class CustomerProfileIntegrationTest {
    @Autowired Data d;@Autowired MockMvc mvc;@Autowired AuthService auth;
    @Autowired BookingService bookings;@Autowired CustomerProfiles profiles;@Autowired PlatformTransactionManager tm;
    @MockitoSpyBean WechatGateway wx;
    String identity,token,hq,manager,other;
    @BeforeEach void setup(){
        for(String table:List.of("chat_message","chat_conversation","dianping_customer_link","store_customer","customer_profile","customer_identity"))d.update("DELETE FROM "+table);
        var session=auth.customerSession("profile-fixture",false);token=str(session,"token");
        identity=str(d.one("SELECT id FROM customer_identity WHERE openid='profile-fixture'"),"id");
        hq=admin("hq");manager=admin("yuyao-manager");other=admin("bai-manager");
    }
    String admin(String uid){return auth.token(uid,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",uid),"tokenVersion",0));}
    Map<String,Object> request(MockHttpServletRequestBuilder request,String bearer,Object body,int expected)throws Exception{
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        if(body!=null)request.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(request).andReturn().getResponse();
        String content=response.getContentAsString(StandardCharsets.UTF_8);assertEquals(expected,response.getStatus(),content);
        return d.object(content);
    }
    Map<String,Object> bind(String code,String phone,String name)throws Exception{
        doReturn(phone).when(wx).phoneNumber(code);
        return request(post("/api/me/profile/phone"),token,Map.of("code",code,"name",name,"consent",true),200);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    void relation(String phone,String source){new TransactionTemplate(tm).execute(status->{new BookingLocks(d).acquire("phone:"+phone);bookings.customerRelation("yuyao","预约称呼",phone,source);return null;});}

    @Test void loginCreatesAnAccountWithoutInventingPhoneAndHeadquartersCanSeeItBeforeBooking()throws Exception{
        var initial=request(get("/api/me/profile"),token,null,200);
        assertEquals("",initial.get("phone"));assertFalse(bool(initial,"phoneVerified"));assertEquals("NON_MEMBER",initial.get("memberStatus"));
        var first=d.one("SELECT * FROM customer_account WHERE identity_id=?",identity);
        auth.customerSession("profile-fixture",false);assertEquals(1,d.count("SELECT COUNT(*) FROM customer_account"));
        assertEquals(first.get("firstLoginAt"),d.one("SELECT * FROM customer_account WHERE identity_id=?",identity).get("firstLoginAt"));
        var page=request(get("/api/admin/customers?source=MINIPROGRAM"),hq,null,200);
        assertEquals(1,integer(page,"total",0));assertTrue(bool(items(page).get(0),"registrationOnly"));
        assertEquals(List.of("MINIPROGRAM"),items(page).get(0).get("sources"));
        assertEquals(0,integer(request(get("/api/admin/customers"),manager,null,200),"total",-1));
        request(put("/api/me/profile"),token,Map.of("name","王女士","consent",true),200);
        assertEquals("王女士",items(request(get("/api/admin/customers?search=王女士"),hq,null,200)).get(0).get("name"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM customer_profile"));
    }
    @Test void authorizedPhoneLinksExistingDianpingMemberWithoutChangingMasterDataOrPrivateNotes()throws Exception{
        String pid=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",pid,"13900003001","王女士原始档案",true,"金卡","yuyao","原有共享备注","external-fixture",now());
        d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)","yuyao",pid,"原有私有备注","DIANPING",null);
        var before=d.one("SELECT * FROM customer_profile WHERE id=?",pid);relation("13900003001","DIANPING");
        var own=bind("phone-fixture-code","13900003001","王女士");
        assertTrue(bool(own,"phoneVerified"));assertTrue(bool(own,"memberVerified"));assertEquals("金卡",own.get("memberGrade"));
        assertEquals(Set.of("MINIPROGRAM","DIANPING"),new HashSet<>((List<?>)own.get("sources")));
        assertEquals(before,d.one("SELECT * FROM customer_profile WHERE id=?",pid));
        assertEquals("原有私有备注",d.one("SELECT * FROM store_customer WHERE customer_profile_id=?",pid).get("privateNote"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile"));
        request(post("/api/me/profile/phone"),token,Map.of("code","phone-fixture-code","name","王女士","consent",true),200);
        verify(wx,times(1)).phoneNumber("phone-fixture-code");
        var listed=items(request(get("/api/admin/customers?member=MEMBER&source=MINIPROGRAM"),hq,null,200)).get(0);
        assertEquals("139****3001",listed.get("phone"));assertEquals("王女士",listed.get("registeredName"));
        request(get("/api/admin/customers/"+pid+"/phone"),other,null,404);
        assertEquals("13900003001",request(get("/api/admin/customers/"+pid+"/phone"),manager,null,200).get("phone"));
    }
    @Test void newVerifiedCustomerIsNonMemberAndEditableByHeadquartersWithoutABooking()throws Exception{
        var own=bind("new-phone-code","13900003002","李先生");assertEquals("NON_MEMBER",own.get("memberStatus"));
        assertTrue(bool(own,"complete"));assertEquals(0,d.count("SELECT COUNT(*) FROM appointment"));
        var row=items(request(get("/api/admin/customers?member=NON_MEMBER"),hq,null,200)).get(0);String pid=str(row,"id");
        assertFalse(bool(row,"registrationOnly"));assertTrue(bool(row,"phoneVerified"));
        request(put("/api/admin/customers/"+pid),hq,Map.of("name","李先生","storeId","","memberVerified",true,"memberGrade","年卡","sharedNote","已核实","privateNote",""),200);
        var member=request(get("/api/me/profile"),token,null,200);assertEquals("MEMBER",member.get("memberStatus"));assertEquals("年卡",member.get("memberGrade"));
        request(put("/api/admin/customers/"+pid),manager,Map.of("storeId","yuyao","privateNote","越权"),404);
        relation("13900003002","MINIPROGRAM");assertEquals(1,integer(request(get("/api/admin/customers"),manager,null,200),"total",0));
        request(put("/api/admin/customers/"+pid),manager,Map.of("storeId","yuyao","privateNote","本店跟进","memberVerified",false),200);
        assertTrue(bool(request(get("/api/me/profile"),token,null,200),"memberVerified"));
        request(put("/api/me/profile"),token,Map.of("name","李女士","consent",true),200);
        assertEquals("李女士",d.one("SELECT name FROM customer_profile WHERE id=?",pid).get("name"));
    }
    @Test void rejectsForgedMembershipPhoneAndSourceAndRequiresExplicitConsent()throws Exception{
        request(get("/api/me/profile"),null,null,401);
        for(var body:List.of(Map.of("name","张先生","phone","13900003009","consent",true),Map.of("name","张先生","memberVerified",true,"consent",true),Map.of("name","张先生","source","DIANPING","consent",true)))request(put("/api/me/profile"),token,body,400);
        request(post("/api/me/profile/phone"),token,Map.of("name","张先生","code","forged","consent",false),400);
        request(put("/api/me/profile"),token,Map.of("name","张\n先生","consent",true),400);
        verify(wx,never()).phoneNumber(anyString());assertEquals(0,d.count("SELECT COUNT(*) FROM customer_profile"));
    }
    @Test void providerFailureDoesNotCreateAProfileOrPretendThePhoneWasVerified()throws Exception{
        doThrow(error(502,"微信手机号接口尚未开通")).when(wx).phoneNumber("provider-failure");
        request(post("/api/me/profile/phone"),token,Map.of("name","张先生","code","provider-failure","consent",true),502);
        assertEquals(0,d.count("SELECT COUNT(*) FROM customer_profile"));assertEquals(0,d.count("SELECT COUNT(*) FROM customer_phone_receipt"));
        assertFalse(bool(request(get("/api/me/profile"),token,null,200),"phoneVerified"));
    }
    @Test void differentIdentitiesWithTheSameAuthorizedPhoneShareOneProfileAndUsedCodesAreBoundToTheirOwner()throws Exception{
        bind("same-phone-first","13900003003","陈女士");
        String second=str(auth.customerSession("profile-fixture-second",false),"token");
        request(post("/api/me/profile/phone"),second,Map.of("name","陈女士","code","same-phone-first","consent",true),409);
        doReturn("13900003003").when(wx).phoneNumber("same-phone-second");
        request(post("/api/me/profile/phone"),second,Map.of("name","陈女士","code","same-phone-second","consent",true),200);
        assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile"));assertEquals(2,d.count("SELECT COUNT(*) FROM customer_account WHERE customer_profile_id IS NOT NULL"));
        assertEquals(1,integer(request(get("/api/admin/customers"),hq,null,200),"total",0));
    }
}
