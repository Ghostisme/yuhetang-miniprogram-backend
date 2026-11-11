package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
class ConsumptionApi {
    final AuthService auth;final ConsumptionRecords records;
    ConsumptionApi(AuthService auth,ConsumptionRecords records){this.auth=auth;this.records=records;}
    @GetMapping("/api/me/consumption-records")
    Map<String,Object> own(HttpServletRequest r,@RequestParam(defaultValue="")String storeId,@RequestParam(defaultValue="")String from,@RequestParam(defaultValue="")String to,@RequestParam(defaultValue="1")int page){return records.own(auth.customer(r),storeId,from,to,page);}
    @GetMapping("/api/me/consumption-records/{id}")
    Map<String,Object> detail(HttpServletRequest r,@PathVariable String id){return records.detail(auth.customer(r),id);}
    @GetMapping("/api/admin/customers/{id}/consumption-records") @Permit("wallets:read")
    Map<String,Object> admin(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="")String storeId,@RequestParam(defaultValue="")String from,@RequestParam(defaultValue="")String to,@RequestParam(defaultValue="1")int page){return records.admin(auth.admin(r),id,storeId,from,to,page);}
    @PostMapping("/api/admin/customers/{id}/consumption-records") @Permit("wallets:write")
    Map<String,Object> legacy(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return records.legacy(auth.admin(r),id,in);}
    @PostMapping("/api/admin/consumption-records/{id}/void") @Permit("wallets:write")
    Map<String,Object> cancel(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return records.voidLegacy(auth.admin(r),id,in);}
}
