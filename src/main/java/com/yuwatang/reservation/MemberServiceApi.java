package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class MemberServiceApi {
    final AuthService auth;final MemberServiceMessages messages;
    MemberServiceApi(AuthService auth,MemberServiceMessages messages){this.auth=auth;this.messages=messages;}
    @GetMapping("/api/me/wallet-service") Map<String,Object> status(HttpServletRequest r){return messages.binding(auth.customer(r));}
    @PostMapping("/api/me/wallet-service/code") Map<String,Object> code(HttpServletRequest r){return messages.code(auth.customer(r));}
    @DeleteMapping("/api/me/wallet-service") Map<String,Object> unbind(HttpServletRequest r){return messages.unbind(auth.customer(r));}
    @GetMapping(value="/api/wechat/service/messages",produces="text/plain;charset=UTF-8")
    String verify(@RequestParam(required=false)String signature,@RequestParam String timestamp,@RequestParam String nonce,@RequestParam String echostr,@RequestParam(required=false)String msg_signature){
        if(!messages.enabled)throw error(503,"服务号余额查询尚未配置。");
        if(echostr.length()>4096)throw error(400,"校验内容过长。");
        if(msg_signature!=null){messages.codec.verify(msg_signature,timestamp,nonce,echostr);return messages.codec.decrypt(echostr);}
        messages.codec.verify(signature,timestamp,nonce,null);return echostr;
    }
    @PostMapping(value="/api/wechat/service/messages",produces="application/xml;charset=UTF-8")
    String receive(@RequestBody String body,@RequestParam(required=false)String signature,@RequestParam(required=false)String msg_signature,@RequestParam String timestamp,@RequestParam String nonce){
        if(!messages.enabled)throw error(503,"服务号余额查询尚未配置。");
        var envelope=messages.codec.parse(body);boolean encrypted=!str(envelope,"Encrypt").isBlank();
        var message=messages.codec.decode(body,signature,msg_signature,timestamp,nonce);String reply=messages.handle(message);
        if(reply.isEmpty())return "success";return encrypted?messages.codec.encryptedReply(reply):reply;
    }
}
