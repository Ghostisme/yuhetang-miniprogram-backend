package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/admin/wechat-menu")
class WechatMenuApi {
    final AuthService auth;final WechatMenuService service;
    WechatMenuApi(AuthService auth,WechatMenuService service){this.auth=auth;this.service=service;}
    @GetMapping @Permit("wechat-menu:read")Map<String,Object> state(HttpServletRequest r){return service.state(auth.admin(r));}
    @PutMapping("/draft") @Permit("wechat-menu:write")Map<String,Object> save(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.save(auth.admin(r),in);}
    @GetMapping("/remote") @Permit("wechat-menu:read")Map<String,Object> remote(HttpServletRequest r){return service.remote(auth.admin(r));}
    @PostMapping("/publish") @Permit("wechat-menu:publish")Map<String,Object> publish(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.publish(auth.admin(r),in);}
    @PostMapping("/restore-draft") @Permit("wechat-menu:write")Map<String,Object> restore(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.restore(auth.admin(r),in);}
}
