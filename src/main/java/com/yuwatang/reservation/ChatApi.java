package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class ChatApi {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ChatApi.class);
    final ChatService chat;final AuthService auth;final WechatMessageCodec codec;
    ChatApi(ChatService c,AuthService a,WechatMessageCodec w){chat=c;auth=a;codec=w;}
    @GetMapping(value="/api/wechat/customer-service",produces="text/plain")String verify(@RequestParam String signature,@RequestParam String timestamp,@RequestParam String nonce,@RequestParam String echostr){codec.verify(signature,timestamp,nonce,null);if(echostr.length()>1000)throw error(400,"校验内容过长。");return echostr;}
    @PostMapping(value="/api/wechat/customer-service",produces="text/plain")String receive(@RequestBody String body,@RequestParam(required=false)String signature,@RequestParam(required=false)String msg_signature,@RequestParam String timestamp,@RequestParam String nonce){
        String type="unverified",event="unverified";
        try{
            var payload=codec.decode(body,signature,msg_signature,timestamp,nonce);
            type=diagnosticValue(str(payload,"MsgType"),Set.of("text","image","miniprogrampage","event"));
            event=diagnosticValue(str(payload,"Event"),Set.of("user_enter_tempsession","yht_diagnostic_probe"));
            String outcome=chat.receive(payload);
            log.info("WeChat callback type={} event={} outcome={}",type,event,outcome);
            return "success";
        }catch(org.springframework.web.server.ResponseStatusException e){
            log.warn("WeChat callback type={} event={} outcome=REJECTED http={}",type,event,e.getStatusCode().value());throw e;
        }catch(RuntimeException e){
            log.warn("WeChat callback type={} event={} outcome=ERROR exception={}",type,event,e.getClass().getSimpleName());throw e;
        }
    }
    static String diagnosticValue(String value,Set<String> allowed){return value.isBlank()?"none":allowed.contains(value)?value:"other";}
    @PostMapping("/api/me/chat/entry")Map<String,Object> entry(HttpServletRequest r,@RequestBody Map<String,Object> in){return chat.entry(auth.customer(r),str(in,"storeId"));}
    @PostMapping("/api/me/chats")Map<String,Object> open(HttpServletRequest r,@RequestBody Map<String,Object> in){return chat.webOpen(auth.customer(r),str(in,"storeId"));}
    @GetMapping("/api/me/chats/{id}/messages")Map<String,Object> mine(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="0")long before){chat.owned(auth.customer(r),id);return Map.of("items",chat.messages(id,after,before));}
    @PostMapping("/api/me/chats/{id}/messages")Map<String,Object> send(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return chat.webSend(auth.customer(r),id,in);}
    @GetMapping("/api/admin/chats")@Permit("chat:read")Map<String,Object> list(HttpServletRequest r,@RequestParam(defaultValue="")String storeId,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="1")int page){return chat.list(auth.admin(r),storeId,status,q,page);}
    @GetMapping("/api/admin/chats/{id}/messages")@Permit("chat:read")Map<String,Object> messages(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="0")long before){var c=chat.scoped(auth.admin(r),id);return Map.of("conversation",chat.publicConversation(c),"items",chat.messages(id,after,before));}
    @PostMapping("/api/admin/chats/{id}/messages")@Permit("chat:reply")Map<String,Object> reply(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return chat.reply(auth.admin(r),id,in);}
    @PatchMapping("/api/admin/chats/{id}/read")@Permit("chat:read")Map<String,Object> read(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){long seq;try{seq=Long.parseLong(str(in,"throughSeq"));}catch(Exception e){throw error(400,"消息序号不正确。");}chat.markRead(auth.admin(r),id,seq);return Map.of("saved",true);}
    @PatchMapping("/api/admin/chats/{id}/status")@Permit("chat:reply")Map<String,Object> status(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){chat.status(auth.admin(r),id,str(in,"status"));return Map.of("saved",true);}
    @PatchMapping("/api/admin/chats/{id}/route")@Permit("chat:route")Map<String,Object> route(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){chat.route(auth.admin(r),id,str(in,"storeId"));return Map.of("saved",true);}
    @GetMapping("/api/admin/chats/{id}/messages/{mid}/image")@Permit("chat:read")ResponseEntity<byte[]> image(HttpServletRequest r,@PathVariable String id,@PathVariable String mid){byte[] bytes=chat.image(auth.admin(r),id,mid);return ResponseEntity.ok().header("Content-Type","application/octet-stream").header("Content-Disposition","attachment; filename=customer-image").header("X-Content-Type-Options","nosniff").body(bytes);}
}
