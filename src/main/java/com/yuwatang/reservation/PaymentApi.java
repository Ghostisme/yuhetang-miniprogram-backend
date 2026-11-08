package com.yuwatang.reservation;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class PaymentApi {
    final Data d;final AuthService auth;final PaymentReservations payments;final PaymentPort port;
    PaymentApi(Data d,AuthService auth,PaymentReservations payments,PaymentPort port){this.d=d;this.auth=auth;this.payments=payments;this.port=port;}

    @GetMapping("/api/payment-capabilities") Map<String,Object> capabilities(){return port.capabilities();}
    @GetMapping("/api/me/appointments/{id}/payment") Map<String,Object> mine(HttpServletRequest r,@PathVariable String id){
        d.one("SELECT id FROM appointment WHERE id=? AND customer_id=?",id,auth.customer(r));return payments.get(id);
    }
    @PostMapping("/api/me/appointments/{id}/payment/start") Map<String,Object> start(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> input,@RequestHeader(value="Idempotency-Key",required=false) String key){
        return payments.initiate(auth.customer(r),id,input,key);
    }
    @GetMapping("/api/admin/appointments/{id}/payment") @Permit("payments:read")
    Map<String,Object> get(HttpServletRequest r,@PathVariable String id){
        var actor=auth.admin(r);actor.store(str(d.one("SELECT store_id FROM appointment WHERE id=?",id),"storeId"));return payments.get(id);
    }
    @PutMapping("/api/admin/appointments/{id}/payment") @Permit("payments:write")
    Map<String,Object> reserve(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> input,@RequestHeader(value="Idempotency-Key",required=false) String key){
        return payments.reserve(auth.admin(r),id,input,key);
    }
}
