package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController @RequestMapping("/api/admin")
class AdminApi {
    final Data d;final AuthService auth;final AdminService service;final CatalogService catalog;final BookingService booking;
    AdminApi(Data d,AuthService a,AdminService s,CatalogService c,BookingService b){this.d=d;auth=a;service=s;catalog=c;booking=b;}
    @PostMapping("/login")Map<String,Object> login(@RequestBody Map<String,Object> in){return auth.login(in);}
    @GetMapping("/me") @Permit Map<String,Object> me(HttpServletRequest r){return auth.me(auth.admin(r));}
    @GetMapping("/dashboard") @Permit("dashboard:read")Map<String,Object> dashboard(HttpServletRequest r,@RequestParam(defaultValue="")String storeId){return service.dashboard(auth.admin(r),storeId);}
    @GetMapping("/appointments") @Permit("appointments:read")Map<String,Object> appointments(HttpServletRequest r,@RequestParam Map<String,String> filters){return service.appointments(auth.admin(r),filters);}
    @GetMapping("/appointments/{id}") @Permit("appointments:read")Map<String,Object> appointment(HttpServletRequest r,@PathVariable String id){var a=auth.admin(r);var b=booking.get(id);a.store(str(b,"storeId"));return booking.view(b,true);}
    @GetMapping("/appointments/{id}/phone") @Permit("appointments:phone")Map<String,Object> phone(HttpServletRequest r,@PathVariable String id){var a=auth.admin(r);var b=booking.get(id);a.store(str(b,"storeId"));d.audit(a,str(b,"storeId"),"appointment.phone",id,"查看完整联系电话");return Map.of("phone",b.get("phone"));}
    @GetMapping("/appointments/{id}/slots") @Permit("appointments:write")Map<String,Object> rescheduleSlots(HttpServletRequest r,@PathVariable String id,@RequestParam String date){var a=auth.admin(r);var b=booking.get(id);a.store(str(b,"storeId"));return catalog.slots(str(b,"storeId"),date,id);}
    @PostMapping("/appointments") @Permit("appointments:write")Map<String,Object> create(HttpServletRequest r,@RequestBody Map<String,Object> in,@RequestHeader(value="Idempotency-Key",required=false)String key){return booking.create(in,null,auth.admin(r),key);}
    @PatchMapping("/appointments/{id}") @Permit Map<String,Object> change(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return booking.change(id,in,null,auth.admin(r));}
    @GetMapping("/stores") @Permit List<Map<String,Object>> stores(HttpServletRequest r){var a=auth.admin(r);return d.rows("SELECT * FROM store"+(a.hq()?"":" WHERE id=?")+" ORDER BY name",a.hq()?new Object[]{}:new Object[]{a.storeId()}).stream().map(s->d.object(s.get("profileJson"))).toList();}
    @PutMapping("/stores/{id}") @Permit("stores:write")Map<String,Object> store(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return service.saveStore(auth.admin(r),id,in);}
    @GetMapping("/stores/{id}/rules") @Permit("rules:write")Map<String,Object> rules(HttpServletRequest r,@PathVariable String id){auth.admin(r).store(id);return catalog.rules(id);}
    @PutMapping("/stores/{id}/rules") @Permit("rules:write")Map<String,Object> rules(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return catalog.saveRules(auth.admin(r),id,in);}
    @PutMapping("/stores/{id}/slots") @Permit("rules:write")Map<String,Object> slots(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){catalog.slotOverride(auth.admin(r),id,in);return Map.of("saved",true);}
    @GetMapping("/stores/{id}/slots") @Permit("rules:write")List<Map<String,Object>> slotOverrides(HttpServletRequest r,@PathVariable String id){auth.admin(r).store(id);return catalog.slotOverrides(id);}
    @GetMapping("/therapists") @Permit("therapists:read")List<Map<String,Object>> therapists(HttpServletRequest r,@RequestParam(defaultValue="") String storeId){return service.therapists(auth.admin(r),storeId);}
    @PutMapping("/therapists") @Permit("therapists:write")Map<String,Object> therapist(HttpServletRequest r,@RequestBody Map<String,Object> in){service.saveTherapist(auth.admin(r),in);return Map.of("saved",true);}
    @GetMapping("/content") @Permit("content:read")Map<String,Object> content(HttpServletRequest r){return service.content(auth.admin(r));}
    @PutMapping("/content/draft") @Permit("content:write")Map<String,Object> draft(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.saveContent(auth.admin(r),in,false);}
    @PutMapping("/content/publish") @Permit("content:publish")Map<String,Object> publish(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.saveContent(auth.admin(r),in,true);}
    @GetMapping("/users") @Permit("users:read")List<Map<String,Object>> users(HttpServletRequest r){return service.users(auth.admin(r));}
    @PutMapping("/users") @Permit("users:write")Map<String,Object> user(HttpServletRequest r,@RequestBody Map<String,Object> in){service.saveUser(auth.admin(r),in);return Map.of("saved",true);}
    @GetMapping("/roles") @Permit("roles:read")List<Map<String,Object>> roles(HttpServletRequest r){return service.roles(auth.admin(r));}
    @GetMapping("/permissions") @Permit("roles:read")List<Map<String,Object>> permissions(){return d.rows("SELECT * FROM permission ORDER BY module,code");}
    @PutMapping("/roles") @Permit("roles:write")Map<String,Object> role(HttpServletRequest r,@RequestBody Map<String,Object> in){service.saveRole(auth.admin(r),in);return Map.of("saved",true);}
    @GetMapping("/customers") @Permit("customers:read")Map<String,Object> customers(HttpServletRequest r,@RequestParam(defaultValue="")String storeId,@RequestParam(defaultValue="1")int page){return service.customers(auth.admin(r),storeId,page);}
    @PutMapping("/customers/{id}") @Permit("customers:write")Map<String,Object> customer(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){service.saveCustomer(auth.admin(r),id,in);return Map.of("saved",true);}
    @PostMapping("/customers/shared-lookup") @Permit("customers:shared")Map<String,Object> lookup(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.lookup(auth.admin(r),text(in,"phone",11,11));}
    @GetMapping("/settings") @Permit("integrations:read")Map<String,Object> settings(HttpServletRequest r){AdminService.hq(auth.admin(r));Map<String,Object> out=new LinkedHashMap<>();for(var row:d.rows("SELECT * FROM app_setting WHERE setting_key IN ('sharedLookupEnabled','serviceTemplateMapping','customerTemplateMapping')"))out.put(str(row,"settingKey"),row.get("settingValue"));return out;}
    @PutMapping("/settings") @Permit("roles:write")Map<String,Object> settings(HttpServletRequest r,@RequestBody Map<String,Object> in){var a=auth.admin(r);AdminService.hq(a);for(String key:List.of("sharedLookupEnabled","serviceTemplateMapping","customerTemplateMapping")){if(!in.containsKey(key))continue;String value=key.equals("sharedLookupEnabled")?Boolean.toString(bool(in,key)):d.stringify(d.object(in.get(key)));if(value.length()>2000)throw error(400,"配置内容过长。");if(!key.equals("sharedLookupEnabled"))for(Object v:d.object(value).values())if(!Set.of("storeName","serviceName","time","status","appointmentId","event").contains(v.toString()))throw error(400,"模板只能发送必要预约摘要字段。");if(d.update("UPDATE app_setting SET setting_value=? WHERE setting_key=?",value,key)==0)d.update("INSERT INTO app_setting VALUES(?,?)",key,value);}d.audit(a,null,"settings.update","brand","更新共享开关/消息字段映射");return Map.of("saved",true);}
    @GetMapping("/audit") @Permit("audit:read")Map<String,Object> audit(HttpServletRequest r,@RequestParam(defaultValue="1")int page){var a=auth.admin(r);String where=a.hq()?"":" WHERE store_id=?";List<Object> args=new ArrayList<>();if(!a.hq())args.add(a.storeId());int total=d.count("SELECT COUNT(*) FROM audit_log"+where,args.toArray());args.add(30);args.add(Math.max(0,page-1)*30);return Map.of("total",total,"items",d.rows("SELECT * FROM audit_log"+where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",args.toArray()));}
}
