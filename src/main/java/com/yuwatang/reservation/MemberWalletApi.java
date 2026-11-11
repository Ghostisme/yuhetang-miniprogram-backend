package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
class MemberWalletApi {
    final AuthService auth;final MemberWallets wallets;
    MemberWalletApi(AuthService auth,MemberWallets wallets){this.auth=auth;this.wallets=wallets;}
    @GetMapping("/api/me/wallets") Map<String,Object> own(HttpServletRequest r){return wallets.own(auth.customer(r));}
    @GetMapping("/api/me/wallets/{id}/entries") Map<String,Object> entries(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="1")int page){return wallets.ownLedger(auth.customer(r),id,page);}
    @GetMapping("/api/admin/customers/{id}/wallets") @Permit("wallets:read")
    Map<String,Object> accounts(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="")String storeId){return wallets.admin(auth.admin(r),id,storeId);}
    @PostMapping("/api/admin/customers/{id}/wallets") @Permit("wallets:opening")
    Map<String,Object> opening(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return wallets.open(auth.admin(r),id,in);}
    @GetMapping("/api/admin/wallets/{id}/entries") @Permit("wallets:read")
    Map<String,Object> ledger(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="1")int page){return wallets.adminLedger(auth.admin(r),id,page);}
    @PostMapping("/api/admin/wallets/{id}/entries") @Permit("wallets:write")
    Map<String,Object> post(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return wallets.post(auth.admin(r),id,in);}
}
