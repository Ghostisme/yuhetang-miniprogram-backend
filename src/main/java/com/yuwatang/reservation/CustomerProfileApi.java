package com.yuwatang.reservation;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/me/profile")
class CustomerProfileApi {
    final AuthService auth;final CustomerProfiles profiles;
    CustomerProfileApi(AuthService auth,CustomerProfiles profiles){this.auth=auth;this.profiles=profiles;}
    @GetMapping Map<String,Object> get(HttpServletRequest r){return profiles.get(auth.customer(r));}
    @PutMapping Map<String,Object> save(HttpServletRequest r,@RequestBody Map<String,Object> in){return profiles.save(auth.customer(r),in);}
    @PostMapping("/phone") Map<String,Object> phone(HttpServletRequest r,@RequestBody Map<String,Object> in){return profiles.phone(auth.customer(r),in);}
}
