package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_subscription_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "app.mode=local","app.data-dir=target/isolated-subscription-test-data",
    "app.jwt-secret=isolated-subscription-tests-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=subscription-fixture","app.wechat.secret=fixture-not-a-real-secret",
    "app.wechat.service-app-id=","app.wechat.service-secret=","app.wechat.service-template=",
    "app.wechat.customer-template=fixture-result","app.wechat.customer-reminder-template=fixture-reminder"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerSubscriptionIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired BookingService bookings; @Autowired NotificationService notifications; @Autowired Bootstrap bootstrap;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @MockitoSpyBean WechatGateway wx;
    String hq,manager,customerToken,customerId;
    // Exact field keys from the two templates selected in this mini-program account.
    final Map<String,String> resultMapping=Map.of("name1","customerName","date2","time","thing3","serviceName","thing4","storeName","phrase5","status");
    final Map<String,String> reminderMapping=Map.of("thing31","customerName","time2","time","thing7","serviceName","thing8","storeName","phrase11","status");

    @BeforeAll void manualDispatchOnly(){scheduler.destroy();}
    @BeforeEach void reset()throws Exception{
        for(String table:List.of("notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile","subscription_grant","notification_recipient","notification_channel_config"))d.update("DELETE FROM "+table);
        d.update("DELETE FROM app_setting WHERE setting_key IN ('customerTemplateMapping','customerReminderTemplateMapping')");
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));
        String openid="subscription-test-"+id();customerToken=str(auth.customerSession(openid,false),"token");customerId=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
        hq=auth.token("hq","ADMIN",0);manager=auth.token("yuyao-manager","ADMIN",0);
        doReturn(Map.of("errcode",0)).when(wx).send(anyBoolean(),anyString(),anyString(),anyMap(),anyString());
        request(put("/api/admin/settings"),hq,Map.of("customerTemplateMapping",resultMapping,"customerReminderTemplateMapping",reminderMapping),200);
    }
    Map<String,Object> request(MockHttpServletRequestBuilder req,String token,Object body,int status)throws Exception{
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(body!=null)req.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(req).andReturn().getResponse();String result=response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status,response.getStatus(),result);return d.object(result);
    }
    void grant(String template)throws Exception{request(post("/api/me/subscriptions"),customerToken,Map.of("templateId",template,"accepted",true),200);}
    int remaining(String template){return d.count("SELECT COALESCE(SUM(remaining),0) FROM subscription_grant WHERE customer_id=? AND template_id=?",customerId,template);}
    Map<String,Object> confirm()throws Exception{
        var input=new LinkedHashMap<String,Object>(Map.of("storeId","yuyao","serviceId","moxa","date",LocalDate.now(ZONE).plusDays(1).toString(),"startTime","10:00","headcount",1,"name","订阅验证客户","phone","13900003002","privacyConsent",true));
        input.put("memberClaim","NO");input.put("visitedClaim","NO");
        var created=request(post("/api/appointments").header("Idempotency-Key",id()),customerToken,input,200);
        return request(patch("/api/admin/appointments/"+created.get("id")),manager,Map.of("action","confirm","version",created.get("version")),200);
    }
    Map<String,Object> resultRow(){return d.one("SELECT * FROM notification_outbox WHERE channel='SUBSCRIBE' AND event_type='appointment.confirm'");}

    @Test void runtimeAndGrantsKeepTemplateChoicesSeparate()throws Exception{
        var runtime=request(get("/api/runtime"),null,null,200);
        assertEquals(List.of("fixture-result","fixture-reminder"),runtime.get("customerTemplateIds"));
        grant("fixture-result");grant("fixture-reminder");grant("fixture-reminder");
        request(post("/api/me/subscriptions"),customerToken,Map.of("templateId","fixture-result","accepted",false),200);
        request(post("/api/me/subscriptions"),customerToken,Map.of("templateId","other-app-template","accepted",true),400);
        assertEquals(1,remaining("fixture-result"));assertEquals(2,remaining("fixture-reminder"));
        request(put("/api/admin/settings"),manager,Map.of("customerReminderTemplateMapping",reminderMapping),403);
        request(put("/api/admin/settings"),hq,Map.of("customerReminderTemplateMapping",Map.of("thing1","phone")),400);
        var settings=request(get("/api/admin/settings"),hq,null,200);assertEquals(reminderMapping,d.object(settings.get("customerReminderTemplateMapping")));
    }
    @Test void confirmationAndReminderConsumeTheirOwnAllowanceAndFields()throws Exception{
        grant("fixture-result");grant("fixture-reminder");var booking=confirm();notifications.dispatch();
        assertEquals(0,remaining("fixture-result"));assertEquals(1,remaining("fixture-reminder"));
        assertEquals("ACCEPTED",resultRow().get("deliveryStatus"));
        String expectedTime=booking.get("bookingDate")+" "+booking.get("startTime");
        verify(wx).send(eq(false),anyString(),eq("fixture-result"),argThat(data->data.keySet().equals(resultMapping.keySet())&&Map.of("value","订阅验证客户").equals(data.get("name1"))&&Map.of("value",expectedTime).equals(data.get("date2"))),eq(str(booking,"id")));
        notifications.enqueue(bookings.get(str(booking,"id")),"appointment.reminder");notifications.dispatch();
        assertEquals(0,remaining("fixture-reminder"));
        verify(wx).send(eq(false),anyString(),eq("fixture-reminder"),argThat(data->data.keySet().equals(reminderMapping.keySet())&&Map.of("value",expectedTime).equals(data.get("time2"))&&Map.of("value","门店已确认").equals(data.get("phrase11"))),eq(str(booking,"id")));
        notifications.enqueue(bookings.get(str(booking,"id")),"appointment.complete");
        assertEquals(2,d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='SUBSCRIBE'"),"Arrival/completion must not waste a result/reminder allowance.");
    }
    @Test void missingReminderAuthorizationOrMappingNeverConsumesResultAllowance()throws Exception{
        grant("fixture-result");var booking=confirm();d.update("DELETE FROM notification_outbox");
        notifications.enqueue(bookings.get(str(booking,"id")),"appointment.reminder");notifications.dispatch();
        assertEquals(1,remaining("fixture-result"));assertEquals("BLOCKED",d.one("SELECT delivery_status FROM notification_outbox WHERE channel='SUBSCRIBE'").get("deliveryStatus"));
        grant("fixture-reminder");d.update("DELETE FROM app_setting WHERE setting_key='customerReminderTemplateMapping'");
        d.update("UPDATE notification_outbox SET delivery_status='PENDING' WHERE channel='SUBSCRIBE'");notifications.dispatch();
        assertEquals(1,remaining("fixture-reminder"));assertEquals(1,remaining("fixture-result"));
        verify(wx,never()).send(anyBoolean(),anyString(),anyString(),anyMap(),anyString());
    }
    @Test void explicitRetryReusesReservedTemplateAndFields()throws Exception{
        grant("fixture-result");confirm();
        doReturn(Map.of("errcode",-1),Map.of("errcode",0)).when(wx).send(eq(false),anyString(),eq("fixture-result"),anyMap(),anyString());
        notifications.dispatch();assertEquals("RETRY",resultRow().get("deliveryStatus"));assertEquals(0,remaining("fixture-result"));
        d.update("UPDATE app_setting SET setting_value=? WHERE setting_key='customerTemplateMapping'",d.stringify(Map.of("thing99","storeName")));
        d.update("UPDATE notification_outbox SET next_attempt_at=? WHERE channel='SUBSCRIBE'",now());notifications.dispatch();
        assertEquals("ACCEPTED",resultRow().get("deliveryStatus"));assertEquals(0,remaining("fixture-result"));
        verify(wx,times(2)).send(eq(false),anyString(),eq("fixture-result"),argThat(data->data.keySet().equals(resultMapping.keySet())),anyString());
    }
    @Test void staleConfirmationIsCancelledBeforeConsumingAnAllowance()throws Exception{
        grant("fixture-result");var booking=confirm();
        request(patch("/api/admin/appointments/"+booking.get("id")),manager,Map.of("action","cancel","version",booking.get("version"),"note","客户要求取消"),200);
        notifications.dispatch();assertEquals("CANCELLED",resultRow().get("deliveryStatus"));assertEquals(0,remaining("fixture-result"));
        verify(wx,times(1)).send(eq(false),anyString(),eq("fixture-result"),argThat(data->Map.of("value","已取消").equals(data.get("phrase5"))),anyString());
    }
    @Test void parallelWorkersSendOneMessageAndConsumeOneAllowance()throws Exception{
        grant("fixture-result");grant("fixture-result");confirm();
        doAnswer(invocation->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());Thread.sleep(40);return Map.of("errcode",0);}).when(wx).send(eq(false),anyString(),anyString(),anyMap(),anyString());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
        try{var tasks=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<16;i++)tasks.add(executor.submit(()->notifications.dispatch()));for(var task:tasks)task.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        finally{executor.shutdownNow();}
        assertEquals(1,remaining("fixture-result"));assertEquals("ACCEPTED",resultRow().get("deliveryStatus"));
        verify(wx,times(1)).send(eq(false),anyString(),eq("fixture-result"),anyMap(),anyString());
    }
    @Test void deploymentDefaultsDoNotOverwriteHeadquartersMapping(){
        var configured=new Bootstrap(d,auth,"",d.stringify(resultMapping),d.stringify(reminderMapping));
        d.update("DELETE FROM app_setting WHERE setting_key IN ('customerTemplateMapping','customerReminderTemplateMapping')");
        configured.initializeTemplateMappings();
        assertEquals(resultMapping,d.object(d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerTemplateMapping'").get("settingValue")));
        assertEquals(reminderMapping,d.object(d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerReminderTemplateMapping'").get("settingValue")));
        var edited=Map.of("thing99","storeName");d.update("UPDATE app_setting SET setting_value=? WHERE setting_key='customerTemplateMapping'",d.stringify(edited));
        configured.initializeTemplateMappings();
        assertEquals(edited,d.object(d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerTemplateMapping'").get("settingValue")));
    }
}
