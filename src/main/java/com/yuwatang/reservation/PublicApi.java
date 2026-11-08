package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController @RequestMapping("/api")
class PublicApi {
    final Data d;final AuthService auth;final CatalogService catalog;final BookingService booking;final WechatGateway wx;
    PublicApi(Data d,AuthService a,CatalogService c,BookingService b,WechatGateway wx){this.d=d;auth=a;catalog=c;booking=b;this.wx=wx;}
    @GetMapping("/health")Map<String,Object> health(){d.jdbc.queryForObject("SELECT 1",Integer.class);return Map.of("status","ok","mode",auth.local?"local":"production","backend","Java 17 / Spring Boot","database","connected");}
    @GetMapping("/health/live")Map<String,Object> live(){return Map.of("status","ok");}
    @GetMapping("/site")org.springframework.http.ResponseEntity<?> site(org.springframework.web.context.request.WebRequest request){
        var site=catalog.site();String etag="\""+Integer.toHexString(d.stringify(site).hashCode())+"\"";
        if(request.checkNotModified(etag))return null;
        return org.springframework.http.ResponseEntity.ok().eTag(etag).cacheControl(org.springframework.http.CacheControl.maxAge(3,java.util.concurrent.TimeUnit.SECONDS).cachePublic()).body(site);
    }
    @GetMapping("/runtime")Map<String,Object> runtime(){return Map.of("developmentLogin",auth.local,"customerTemplateId",wx.customerTemplate,"customerReminderTemplateId",wx.customerReminderTemplate,"customerTemplateIds",wx.customerTemplates(),"customerServiceEnabled",true,"wechatLoginConfigured",!wx.appId.isBlank()&&!wx.secret.isBlank(),"independentStoreServices",true,"customerProfiles",true);}
    @PostMapping("/auth/wechat")Map<String,Object> wechat(@RequestBody Map<String,Object> in){return auth.customerSession(wx.login(text(in,"code",1,200)),false);}
    @PostMapping("/auth/dev-customer")Map<String,Object> local(@RequestBody Map<String,Object> in){if(!auth.local)throw error(404,"接口不存在。");String device=text(in,"deviceId",16,100);if(!device.matches("[A-Za-z0-9_-]+"))throw error(400,"开发身份标识无效。");return auth.customerSession("dev:"+device,true);}
    @GetMapping("/stores/{id}/slots")Map<String,Object> slots(@PathVariable String id,@RequestParam String date){return catalog.slots(id,date);}
    @GetMapping("/stores/{id}/therapists")List<Map<String,Object>> therapists(@PathVariable String id){return catalog.therapists(id,true);}
    @PostMapping("/appointments")Map<String,Object> create(HttpServletRequest r,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){return booking.create(body,auth.customer(r),null,key);}
    @GetMapping("/me/appointments")List<Map<String,Object>> mine(HttpServletRequest r){return booking.mine(auth.customer(r));}
    @GetMapping("/me/appointments/{id}")Map<String,Object> one(HttpServletRequest r,@PathVariable String id){String customer=auth.customer(r);var b=booking.get(id);if(!customer.equals(b.get("customerId")))throw error(404,"预约不存在。");var v=booking.view(b,false);v.put("history",d.rows("SELECT status,created_at FROM appointment_history WHERE appointment_id=? ORDER BY created_at",id));return v;}
    @GetMapping("/me/appointments/{id}/slots")Map<String,Object> rescheduleSlots(HttpServletRequest r,@PathVariable String id,@RequestParam String date){String customer=auth.customer(r);var b=booking.get(id);if(!customer.equals(b.get("customerId")))throw error(404,"预约不存在。");return catalog.slots(str(b,"storeId"),date,id);}
    @PatchMapping("/me/appointments/{id}")Map<String,Object> change(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return booking.change(id,in,auth.customer(r),null);}
}
