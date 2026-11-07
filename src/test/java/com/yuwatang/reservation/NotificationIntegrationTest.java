package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    "spring.datasource.url=jdbc:h2:mem:yht_notification_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "app.mode=local","app.data-dir=target/isolated-notification-test-data",
    "app.jwt-secret=isolated-notification-tests-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired BookingService bookings; @Autowired NotificationService notifications;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @MockitoBean SmsGateway sms;
    String hq,manager,frontdesk,customerToken;

    @BeforeAll void manualDispatchOnly(){scheduler.destroy();}
    @BeforeEach void reset(){
        for(String table:List.of("notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile","subscription_grant","service_binding","notification_recipient","notification_channel_config"))d.update("DELETE FROM "+table);
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));
        var session=auth.customerSession("notification-test-"+id(),true);customerToken=str(session,"token");
        hq=token("hq");manager=token("yuyao-manager");frontdesk=token("yuyao-frontdesk");
        when(sms.configured()).thenReturn(true);when(sms.issue(anyBoolean())).thenReturn("");
        when(sms.send(anyBoolean(),anyString(),anyMap(),anyString())).thenReturn(new SmsGateway.Result("ACCEPTED","Ok","test-provider-id"));
    }
    String token(String user){return auth.token(user,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",user),"tokenVersion",0));}
    Map<String,Object> request(MockHttpServletRequestBuilder req,String token,Object body,int status)throws Exception{
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(body!=null)req.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(req).andReturn().getResponse();String result=response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status,response.getStatus(),result);return d.object(result);
    }
    Map<String,Object> config(String store,String token)throws Exception{return request(get("/api/admin/notifications/config").param("storeId",store),token,null,200);}
    Map<String,Object> settings(String store)throws Exception{return new LinkedHashMap<>(d.object(config(store,hq).get("channels")));}
    Map<String,Object> enableSms()throws Exception{
        var input=settings("yuyao");input.put("smsStaffEnabled",true);input.put("smsCustomerEnabled",true);input.put("smsPhone","13900002001");
        return request(put("/api/admin/notifications/config"),manager,input,200);
    }
    Map<String,Object> create()throws Exception{
        var input=new LinkedHashMap<String,Object>();input.put("storeId","yuyao");input.put("serviceId","moxa");input.put("date",LocalDate.now(ZONE).plusDays(1).toString());
        input.put("startTime","10:00");input.put("headcount",1);input.put("name","通知验证客户");input.put("phone","13900002002");input.put("memberClaim","NO");input.put("visitedClaim","NO");input.put("privacyConsent",true);
        return request(post("/api/appointments").header("Idempotency-Key",id()),customerToken,input,200);
    }
    Map<String,Object> confirm(Map<String,Object> booking)throws Exception{
        return request(patch("/api/admin/appointments/"+booking.get("id")),manager,Map.of("action","confirm","version",booking.get("version")),200);
    }
    List<Map<String,Object>> smsRows(){return d.rows("SELECT * FROM notification_outbox WHERE channel='SMS' ORDER BY event_type,recipient_id");}

    @Test void existingStoresKeepPcAndWechatEnabledWithSmsOff()throws Exception{
        var config=settings("yuyao");assertTrue(bool(config,"pcEnabled"));assertTrue(bool(config,"wechatStaffEnabled"));assertTrue(bool(config,"wechatCustomerEnabled"));
        assertFalse(bool(config,"smsStaffEnabled"));assertFalse(bool(config,"smsCustomerEnabled"));
        confirm(create());assertEquals(0,smsRows().size());assertEquals(2,d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='INBOX'"));
    }
    @Test void channelSettingsEnforceRbacScopeValidationAndRevision()throws Exception{
        enableSms();var current=settings("yuyao");
        var view=d.object(config("yuyao",frontdesk).get("channels"));assertEquals("",view.get("smsPhone"));assertEquals("139****2001",view.get("smsPhoneMasked"));
        request(put("/api/admin/notifications/config"),frontdesk,current,403);
        request(get("/api/admin/notifications/config").param("storeId","bai-le-men"),manager,null,404);
        var other=settings("bai-le-men");request(put("/api/admin/notifications/config"),manager,other,404);
        var invalid=new LinkedHashMap<>(current);invalid.put("smsPhone","bad");request(put("/api/admin/notifications/config"),manager,invalid,400);
        request(put("/api/admin/notifications/config"),manager,current,200);request(put("/api/admin/notifications/config"),manager,current,409);
    }
    @Test void threeChannelsAreDeduplicatedAndSmsTargetsRemainSeparate()throws Exception{
        enableSms();var booking=confirm(create());notifications.enqueue(bookings.get(str(booking,"id")),"appointment.confirm");
        // An updated appointment makes its older 'created' SMS obsolete.
        notifications.dispatch();assertEquals(3,smsRows().size());
        assertEquals(1,d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='SMS' AND delivery_status='CANCELLED'"));
        assertEquals(2,d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='SMS' AND delivery_status='ACCEPTED' AND provider_message_id='test-provider-id'"));
        verify(sms).send(eq(true),eq("13900002001"),anyMap(),anyString());verify(sms).send(eq(false),eq("13900002002"),anyMap(),anyString());
        for(String channel:List.of("PC","WECHAT","SMS")){
            var page=request(get("/api/admin/notifications").param("channel",channel),hq,null,200);
            var items=(List<?>)page.get("items");assertFalse(items.isEmpty());
            for(Object value:items){var row=d.object(value);assertEquals(channel,row.get("channelGroup"));assertFalse(row.containsKey("recipientId"));assertNotEquals(str(row,"eventType"),str(row,"eventLabel"));}
        }
        var accepted=request(get("/api/admin/notifications").param("channel","SMS").param("status","ACCEPTED"),manager,null,200);assertEquals(2,accepted.get("total"));
        request(get("/api/admin/notifications").param("channel","invalid"),hq,null,400);request(get("/api/admin/notifications").param("page","0"),hq,null,400);
        request(get("/api/admin/notifications").param("storeId","bai-le-men"),manager,null,404);
    }
    @Test void switchingRecipientsCancelsQueuedMessagesInsteadOfSendingToNewPhone()throws Exception{
        enableSms();create();var edited=settings("yuyao");edited.put("smsPhone","13900002003");request(put("/api/admin/notifications/config"),manager,edited,200);
        notifications.dispatch();assertEquals("CANCELLED",smsRows().get(0).get("deliveryStatus"));verify(sms,never()).send(anyBoolean(),anyString(),anyMap(),anyString());
    }
    @Test void disabledChannelsDoNotEnqueueNewMessages()throws Exception{
        var config=settings("yuyao");for(String flag:NotificationChannels.FLAGS)config.put(flag,false);
        request(put("/api/admin/notifications/config"),manager,config,200);confirm(create());
        assertEquals(0,d.count("SELECT COUNT(*) FROM notification_outbox"));
    }
    @Test void missingSmsConfigurationIsRecordedWithoutCallingProvider()throws Exception{
        enableSms();create();when(sms.issue(true)).thenReturn("待配置腾讯云短信凭证、应用和签名。");notifications.dispatch();
        assertEquals("BLOCKED",smsRows().get(0).get("deliveryStatus"));assertTrue(str(smsRows().get(0),"lastError").contains("待配置"));verify(sms,never()).send(anyBoolean(),anyString(),anyMap(),anyString());
    }
    @Test void uncertainSmsResultsAreNotAutomaticallyResent()throws Exception{
        enableSms();create();when(sms.send(anyBoolean(),anyString(),anyMap(),anyString())).thenReturn(new SmsGateway.Result("UNKNOWN","TransportUnclear",""));
        notifications.dispatch();notifications.dispatch();assertEquals("UNKNOWN",smsRows().get(0).get("deliveryStatus"));verify(sms,times(1)).send(anyBoolean(),anyString(),anyMap(),anyString());
    }
    @Test void explicitSmsThrottlingRetriesAtMostThreeTimes()throws Exception{
        enableSms();create();when(sms.send(anyBoolean(),anyString(),anyMap(),anyString())).thenReturn(new SmsGateway.Result("RETRY","RequestLimitExceeded",""));
        for(int attempt=1;attempt<=3;attempt++){d.update("UPDATE notification_outbox SET next_attempt_at=? WHERE channel='SMS'",now());notifications.dispatch();assertEquals(attempt,smsRows().get(0).get("attempts"));}
        notifications.dispatch();assertEquals("FAILED",smsRows().get(0).get("deliveryStatus"));verify(sms,times(3)).send(anyBoolean(),anyString(),anyMap(),anyString());
    }
    @Test void staleQueuedCopiesRespectProviderRetryTime()throws Exception{
        enableSms();create();when(sms.send(anyBoolean(),anyString(),anyMap(),anyString())).thenReturn(new SmsGateway.Result("RETRY","RequestLimitExceeded",""));
        var stale=notifications.pending().get(0);notifications.deliver(stale);notifications.deliver(stale);
        assertEquals("RETRY",smsRows().get(0).get("deliveryStatus"));assertEquals(1,smsRows().get(0).get("attempts"));
        verify(sms,times(1)).send(anyBoolean(),anyString(),anyMap(),anyString());
    }
    @Test void arrivalAndCompletionDoNotSendUnnecessaryCustomerSms()throws Exception{
        enableSms();var booking=confirm(create());d.update("DELETE FROM notification_outbox");
        for(String type:List.of("appointment.arrive","appointment.complete","appointment.noshow"))notifications.enqueue(bookings.get(str(booking,"id")),type);
        assertEquals(3,smsRows().size());assertTrue(smsRows().stream().allMatch(row->"staff".equals(row.get("recipientId"))));
    }
    @Test void parallelWorkersClaimSmsOnceWithoutHoldingADatabaseTransaction()throws Exception{
        enableSms();create();
        when(sms.send(anyBoolean(),anyString(),anyMap(),anyString())).thenAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Thread.sleep(80);return new SmsGateway.Result("ACCEPTED","Ok","concurrent-provider-id");
        });
        var executor=java.util.concurrent.Executors.newFixedThreadPool(12);
        try{var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<24;i++)tasks.add(executor.submit(()->notifications.dispatch()));for(var task:tasks)task.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        finally{executor.shutdownNow();}
        verify(sms,times(1)).send(anyBoolean(),anyString(),anyMap(),anyString());assertEquals("ACCEPTED",smsRows().get(0).get("deliveryStatus"));
    }

}
