package com.yuwatang.reservation;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.lang.annotation.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.*;
import org.springframework.beans.factory.annotation.Value;

@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @interface Permit {String value() default "";}
@Configuration
class WebSecurity implements WebMvcConfigurer {
    final AuthService auth;final String dataDir; final Map<String,Window> limits=Caffeine.newBuilder().maximumSize(100000).expireAfterWrite(java.time.Duration.ofMinutes(2)).<String,Window>build().asMap();
    record Window(long minute,AtomicInteger count){}
    WebSecurity(AuthService auth,@Value("${app.data-dir}")String dir){this.auth=auth;dataDir=dir;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","same-origin");
        if(req.getRequestURI().startsWith("/api/")){
            res.setHeader("Cache-Control","no-store");long minute=System.currentTimeMillis()/60000;
            String bucket=req.getRequestURI().contains("login")?"login":(req.getRequestURI().contains("shared-lookup")?"lookup":"api");String key=req.getRemoteAddr()+":"+bucket;
            String identity=req.getRemoteAddr();
            if(bucket.equals("api")&&req.getHeader("Authorization")!=null)try{
                var jwt=auth.decoder.decode(req.getHeader("Authorization").replaceFirst("^Bearer ",""));
                req.setAttribute("verified-jwt",jwt);identity=jwt.getClaimAsString("kind")+":"+jwt.getSubject();
            }catch(Exception ignored){}
            key=identity+":"+bucket;
            Window w=limits.compute(key,(k,v)->v!=null&&v.minute==minute?v:new Window(minute,new AtomicInteger()));
            int ceiling=bucket.equals("login")?20:bucket.equals("lookup")?30:identity.equals(req.getRemoteAddr())?6000:600;
            if(w.count.incrementAndGet()>ceiling){res.setHeader("Retry-After","60");throw Data.error(429,"请求较频繁，请稍后再试。");}
        }
        if(req.getRequestURI().startsWith("/api/admin/")&&!req.getRequestURI().equals("/api/admin/login")){
            Admin a=auth.admin(req);if(handler instanceof HandlerMethod method){Permit p=method.getMethodAnnotation(Permit.class);if(p==null)throw Data.error(403,"接口未授权。");a.require(p.value());}else throw Data.error(404,"接口不存在。");
        }return true;
    }});}
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry){registry.addResourceHandler("/uploads/**").addResourceLocations(Path.of(dataDir,"uploads").toAbsolutePath().toUri().toString()+"/");}
}
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(e.getReason(),"请求未完成。")));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("message","数据格式不正确，请检查后重新提交。"));}
    @ExceptionHandler({org.springframework.dao.TransientDataAccessException.class,org.springframework.transaction.TransactionTimedOutException.class}) ResponseEntity<?> busy(Exception e){return ResponseEntity.status(503).header("Retry-After","2").body(Map.of("message","当前访问较多，请稍后重试；重复提交请沿用原提交标识。"));}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("message","数据已存在或关联资料有冲突，请刷新后重试。"));}
}
@org.springframework.stereotype.Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnResource(resources="classpath:static/index.html")
class FrontendController {@GetMapping({"/","/admin","/admin/"})String index(){return "forward:/index.html";}}
