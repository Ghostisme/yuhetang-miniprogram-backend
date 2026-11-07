package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class CustomerNotifications {
    final Data d;final AuthService auth;final WechatGateway wx;final NotificationReadiness readiness;
    CustomerNotifications(Data d,AuthService auth,WechatGateway wx,NotificationReadiness readiness){this.d=d;this.auth=auth;this.wx=wx;this.readiness=readiness;}
    @GetMapping("/api/me/subscriptions")
    Map<String,Object> status(HttpServletRequest r){
        String customer=auth.customer(r);boolean development=bool(d.one("SELECT is_development FROM customer_identity WHERE id=?",customer),"isDevelopment");
        var templates=new ArrayList<Map<String,Object>>();
        for(boolean reminder:List.of(false,true)){
            var template=new LinkedHashMap<>(readiness.template(reminder));
            template.put("remaining",d.count("SELECT COALESCE(SUM(remaining),0) FROM subscription_grant WHERE customer_id=? AND template_id=?",customer,template.get("templateId")));
            if(development){template.put("ready",false);template.put("issue","模拟登录无法接收真实微信订阅消息。");}
            templates.add(template);
        }
        var items=d.rows("SELECT id,appointment_id,event_type,delivery_status,last_error,created_at FROM notification_outbox WHERE channel='SUBSCRIBE' AND recipient_id=? ORDER BY created_at DESC,id DESC LIMIT 10",customer);
        for(var item:items)item.put("eventLabel",NotificationService.EVENTS.getOrDefault(str(item,"eventType"),"预约通知"));
        return Map.of("templates",templates,"items",items,"development",development);
    }
    @PostMapping("/api/me/subscriptions") @Transactional
    public Map<String,Object> subscribe(HttpServletRequest r,@RequestBody Map<String,Object> in){
        String customer=auth.customer(r),template=str(in,"templateId");
        if(!wx.customerTemplates().contains(template))throw error(400,"模板未配置或不匹配。");
        if(!bool(in,"accepted"))return Map.of("recorded",false);
        String request=str(in,"requestId");
        if(!request.isEmpty()&&!request.matches("[A-Za-z0-9_-]{16,64}"))throw error(400,"订阅确认编号不正确。");
        new BookingLocks(d).acquire("subscription:"+customer);
        if(!request.isEmpty()){
            if(d.count("SELECT COUNT(*) FROM subscription_receipt WHERE customer_id=? AND template_id=? AND request_id=?",customer,template,request)>0)return Map.of("recorded",true);
            d.update("INSERT INTO subscription_receipt VALUES(?,?,?,?)",customer,template,request,now());
        }
        if(d.count("SELECT COUNT(*) FROM subscription_grant WHERE customer_id=? AND template_id=?",customer,template)==0)d.update("INSERT INTO subscription_grant VALUES(?,?,?)",customer,template,1);
        else d.update("UPDATE subscription_grant SET remaining=LEAST(remaining+1,20) WHERE customer_id=? AND template_id=?",customer,template);
        return Map.of("recorded",true);
    }
}
