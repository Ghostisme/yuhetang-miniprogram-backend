package com.yuwatang.reservation;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject excess work before it can exhaust servlet threads or database connections. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestAdmissionFilter extends OncePerRequestFilter {
    final Semaphore all, writes, logins, sessions, bulk;
    final Counter rejected;
    RequestAdmissionFilter(@Value("${app.admission.requests:96}") int requests,
            @Value("${app.admission.writes:16}") int writeLimit,
            @Value("${app.admission.logins:2}") int loginLimit,
            @Value("${app.admission.sessions:8}") int sessionLimit,
            @Value("${app.admission.bulk:1}") int bulkLimit, MeterRegistry metrics) {
        all = new Semaphore(Math.max(1,requests)); writes = new Semaphore(Math.max(1,writeLimit));
        logins = new Semaphore(Math.max(1,loginLimit)); sessions = new Semaphore(Math.max(1,sessionLimit)); bulk = new Semaphore(Math.max(1,bulkLimit));
        rejected = metrics.counter("app.admission.rejected");
        Gauge.builder("app.admission.active", all, s -> Math.max(1,requests)-s.availablePermits()).register(metrics);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        String path=r.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health") || path.startsWith("/api/health/");
    }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
        String path=r.getRequestURI();
        boolean write=!"GET".equals(r.getMethod())&&!"HEAD".equals(r.getMethod());
        Semaphore category=path.contains("login")?logins:path.startsWith("/api/auth/")?sessions:
            path.contains("/imports")||path.contains("/export")||path.contains("/import")?bulk:write?writes:null;
        boolean admitted=all.tryAcquire(), special=admitted&&(category==null||category.tryAcquire());
        if(!special){
            if(admitted)all.release();rejected.increment();
            s.setStatus(503);s.setHeader("Retry-After","2");s.setHeader("Cache-Control","no-store");
            s.setContentType("application/json;charset=UTF-8");
            s.getWriter().write("{\"message\":\"当前访问较多，请稍后重试；预约提交请保留原提交标识。\"}");return;
        }
        try{chain.doFilter(r,s);}finally{if(category!=null)category.release();all.release();}
    }
}
