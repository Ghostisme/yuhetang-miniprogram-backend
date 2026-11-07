package com.yuwatang.reservation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestAdmissionTest {
    @Test void overloadIsRejectedAndPermitsAreReleasedOnFailure()throws Exception{
        var metrics=new SimpleMeterRegistry();var filter=new RequestAdmissionFilter(2,1,1,1,1,metrics);
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        var first=executor.submit(()->{try{filter.doFilter(new MockHttpServletRequest("POST","/api/appointments"),new MockHttpServletResponse(),(r,s)->{held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});}catch(Exception e){throw new RuntimeException(e);}});
        try{
            assertTrue(held.await(2,TimeUnit.SECONDS));var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST","/api/appointments"),response,(r,s)->fail("Overload must not reach the database"));
            assertEquals(503,response.getStatus());assertEquals("2",response.getHeader("Retry-After"));
            var live=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/health/live"),live,(r,s)->{});assertEquals(200,live.getStatus());
        }finally{release.countDown();first.get(3,TimeUnit.SECONDS);executor.shutdownNow();}
        assertThrows(IllegalStateException.class,()->filter.doFilter(new MockHttpServletRequest("POST","/api/appointments"),new MockHttpServletResponse(),(r,s)->{throw new IllegalStateException("fixture");}));
        assertEquals(2,filter.all.availablePermits());assertEquals(1,filter.writes.availablePermits());assertEquals(1,metrics.counter("app.admission.rejected").count());
    }
    @Test void wechatSessionsDoNotConsumePasswordLoginCapacity()throws Exception{
        var filter=new RequestAdmissionFilter(4,2,1,2,1,new SimpleMeterRegistry());
        filter.doFilter(new MockHttpServletRequest("POST","/api/auth/wechat"),new MockHttpServletResponse(),(request,response)->{
            assertEquals(1,filter.logins.availablePermits());assertEquals(1,filter.sessions.availablePermits());
            filter.doFilter(new MockHttpServletRequest("POST","/api/admin/login"),new MockHttpServletResponse(),(r,s)->assertEquals(0,filter.logins.availablePermits()));
        });
        assertEquals(4,filter.all.availablePermits());assertEquals(2,filter.sessions.availablePermits());
    }
}
