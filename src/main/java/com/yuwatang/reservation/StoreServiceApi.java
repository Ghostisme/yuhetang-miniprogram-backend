package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class StoreServiceApi {
    final CatalogService catalog; final AuthService auth;
    StoreServiceApi(CatalogService catalog,AuthService auth){this.catalog=catalog;this.auth=auth;}
    @GetMapping("/api/stores/{id}/services") List<Map<String,Object>> services(@PathVariable String id){
        if(!bool(catalog.store(id),"enabled"))throw error(404,"门店暂未开放。");
        return catalog.storeServices(id,true);
    }
    @GetMapping("/api/admin/stores/{id}/services") @Permit("services:read")
    List<Map<String,Object>> adminServices(HttpServletRequest r,@PathVariable String id){auth.admin(r).store(id);return catalog.storeServices(id,false);}
    @PostMapping("/api/admin/stores/{id}/services") @Permit("services:write")
    Map<String,Object> create(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> body){return catalog.saveStoreService(auth.admin(r),id,"",body);}
    @PutMapping("/api/admin/stores/{id}/services") @Permit("services:write")
    List<Map<String,Object>> save(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> body){return catalog.saveStoreServices(auth.admin(r),id,body.get("items"));}
    @PutMapping("/api/admin/stores/{id}/services/{serviceId}") @Permit("services:write")
    Map<String,Object> update(HttpServletRequest r,@PathVariable String id,@PathVariable String serviceId,@RequestBody Map<String,Object> body){return catalog.saveStoreService(auth.admin(r),id,serviceId,body);}
}
