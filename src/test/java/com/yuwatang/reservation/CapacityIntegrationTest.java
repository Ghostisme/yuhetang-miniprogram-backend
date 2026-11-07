package com.yuwatang.reservation;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=${capacity.test-db-url:jdbc:h2:mem:yht_capacity_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000}",
    "spring.datasource.username=${capacity.test-db-user:sa}","spring.datasource.password=${capacity.test-db-password:}","app.mode=local",
    "app.data-dir=target/isolated-capacity-test-data","app.jwt-secret=capacity-tests-only-secret-at-least-32-chars",
    "app.admin-password=capacity-initial-password","app.wechat.app-id=","app.wechat.secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapacityIntegrationTest {
    @Autowired Data d; @Autowired AuthService auth; @Autowired BookingService bookings;
    @Autowired CatalogService catalog; @Autowired PlatformTransactionManager tm;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    final String day=LocalDate.now(ZONE).plusDays(1).toString();
    String customer;
    final Admin hq=new Admin("hq","Capacity tests","",Set.of("hq"),Set.of("appointments:write","appointments:review","rules:write"),true);
    @BeforeAll void stopJobs()throws Exception{
        try(var connection=d.jdbc.getDataSource().getConnection()){
            String url=connection.getMetaData().getURL();
            if(!url.startsWith("jdbc:h2:mem:yht_capacity_test")&&!url.startsWith("jdbc:mysql://127.0.0.1:13379/yuwatang_capacity_fixture?"))
                throw new IllegalStateException("Capacity tests may only use the named isolated fixtures.");
        }
        scheduler.destroy();
    }
    @BeforeEach void reset(){
        for(String table:List.of("notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile"))d.update("DELETE FROM "+table);
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));catalog.invalidateSite();
        String openid="capacity-"+id();auth.customerSession(openid,true);customer=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
    }
    Map<String,Object> input(String store,String phone){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("storeId",store),Map.entry("serviceId","moxa"),Map.entry("date",day),Map.entry("startTime","10:00"),
        Map.entry("headcount",1),Map.entry("name","并发验证"),Map.entry("phone",phone),Map.entry("privacyConsent",true),
        Map.entry("memberClaim","UNKNOWN"),Map.entry("visitedClaim","UNKNOWN")));
    }
    <T> List<T> parallel(int count,IntFunction<T> work)throws Exception{
        var executor=Executors.newFixedThreadPool(16);var start=new CountDownLatch(1);List<Future<T>> futures=new ArrayList<>();
        try{for(int i=0;i<count;i++){final int n=i;futures.add(executor.submit(()->{start.await();return work.apply(n);}));}start.countDown();List<T> result=new ArrayList<>();for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));return result;}
        finally{executor.shutdownNow();}
    }
    boolean attempt(Map<String,Object> in,String key){try{bookings.create(in,customer,null,key);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value(),e.getReason());return false;}}

    @Test void eightyClientsCannotOversellFourSeats()throws Exception{
        var results=parallel(80,n->attempt(input("yuyao",String.format("13980%06d",n)),id()));
        assertEquals(4,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(4,catalog.used("yuyao",day,"10:00",null));assertEquals(4,d.count("SELECT COUNT(*) FROM appointment"));
        assertEquals(4,d.count("SELECT COUNT(*) FROM pms_outbox"));
    }
    @Test void simultaneousRetriesProduceOneAppointmentAndOneEvent()throws Exception{
        String key=id();var in=input("yuyao","13981000001");
        var ids=parallel(48,n->str(bookings.create(in,customer,null,key),"id"));
        assertEquals(1,new HashSet<>(ids).size());assertEquals(1,d.count("SELECT COUNT(*) FROM appointment"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM appointment_history"));assertEquals(1,d.count("SELECT COUNT(*) FROM pms_outbox"));
    }
    @Test void phoneLimitIsEnforcedAcrossStores()throws Exception{
        var rules=Bootstrap.defaultRules();rules.put("phoneOpenLimit",1);d.update("UPDATE store SET rules_json=?",d.stringify(rules));
        var stores=d.rows("SELECT * FROM store WHERE enabled=TRUE ORDER BY id").stream().filter(s->bool(d.object(s.get("profileJson")),"onlineBooking")).toList();
        var results=parallel(36,n->attempt(input(str(stores.get(n%stores.size()),"id"),"13982000001"),id()));
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile WHERE phone='13982000001'"));
    }
    @Test void oneVersionCanOnlyBeChangedOnce()throws Exception{
        var booking=bookings.create(input("yuyao","13983000001"),customer,null,id());
        var results=parallel(32,n->{try{bookings.change(str(booking,"id"),Map.of("action","confirm","version",1),null,hq);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals(2,integer(bookings.get(str(booking,"id")),"version",0));
        assertEquals(2,d.count("SELECT COUNT(*) FROM appointment_history"));
    }
    @Test void ruleChangeAndBookingShareTheSameGuard()throws Exception{
        var results=parallel(32,n->{if(n==0){var rules=Bootstrap.defaultRules();rules.put("capacity",1);try{catalog.saveRules(hq,"yuyao",rules);}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());}return true;}return attempt(input("yuyao",String.format("13984%06d",n)),id());});
        assertEquals(32,results.size());assertTrue(catalog.used("yuyao",day,"10:00",null)<=integer(catalog.rules("yuyao"),"capacity",0));
    }
    @Test void oldBrandWideLockDoesNotBlockNormalBookings()throws Exception{
        var executor=Executors.newSingleThreadExecutor();CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
        var holding=executor.submit(()->new TransactionTemplate(tm).execute(status->{catalog.bookingLock();acquired.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
        try{assertTrue(acquired.await(3,TimeUnit.SECONDS));assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->bookings.create(input("yuyao","13985000001"),customer,null,id()));}
        finally{release.countDown();holding.get(5,TimeUnit.SECONDS);executor.shutdownNow();}
    }
}
