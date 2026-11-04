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
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;

@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @interface Permit {String value() default "";}
@Configuration
class WebSecurity implements WebMvcConfigurer {
    final AuthService auth;final String dataDir; final Map<String,Window> limits=new ConcurrentHashMap<>();
    record Window(long minute,int count){}
    WebSecurity(AuthService auth,@Value("${app.data-dir}")String dir){this.auth=auth;dataDir=dir;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","same-origin");
        if(req.getRequestURI().startsWith("/api/")){
            res.setHeader("Cache-Control","no-store");long minute=System.currentTimeMillis()/60000;
            String bucket=req.getRequestURI().contains("login")?"login":(req.getRequestURI().contains("shared-lookup")?"lookup":"api");String key=req.getRemoteAddr()+":"+bucket;
            if(limits.size()>5000)limits.entrySet().removeIf(e->e.getValue().minute<minute-1);
            Window w=limits.compute(key,(k,v)->new Window(minute,v!=null&&v.minute==minute?v.count+1:1));if(w.count>(bucket.equals("login")?20:bucket.equals("lookup")?30:600))throw Data.error(429,"操作频繁，请稍后重试。");
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
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("message","数据已存在或关联资料有冲突，请刷新后重试。"));}
}
@org.springframework.stereotype.Controller
class FrontendController {@GetMapping({"/","/admin","/admin/"})String index(){return "forward:/index.html";}}
