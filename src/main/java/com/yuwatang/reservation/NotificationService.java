package com.yuwatang.reservation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
public class NotificationService {
    final Data d;final WechatGateway wx;final CatalogService catalog;
    NotificationService(Data d,WechatGateway wx,CatalogService catalog){this.d=d;this.wx=wx;this.catalog=catalog;}
    void enqueue(Map<String,Object> booking,String type){
        String store=str(booking,"storeId"),bid=str(booking,"id"),event=bid+":"+booking.get("version")+":"+type;
        Map<String,Object> payload=Map.of("appointmentId",bid,"storeName",catalog.store(store).get("name"),"serviceName",booking.get("serviceName"),"time",booking.get("bookingDate")+" "+booking.get("startTime"),"status",BookingService.LABELS.get(str(booking,"status")),"event",type);
        if(!type.equals("appointment.reminder")){
            add(event,store,bid,"INBOX",store,0,type,payload,"AVAILABLE","");
            var receivers=d.rows("SELECT nr.* FROM notification_recipient nr JOIN app_user u ON nr.admin_user_id=u.id WHERE nr.store_id=? AND nr.enabled=TRUE AND u.enabled=TRUE AND u.store_id=nr.store_id",store);
            if(receivers.isEmpty())add(event,store,bid,"SERVICE","unbound",0,type,payload,"BLOCKED","本门店尚未绑定服务号接收人。");
            else{var r=receivers.get(0);String openid=str(r,"openid");add(event,store,bid,"SERVICE",str(r,"adminUserId"),integer(r,"generation",0),type,payload,openid.isBlank()?"BLOCKED":"PENDING",openid.isBlank()?"接收人尚未在微信完成确认绑定。":"");}
        }
        String customer=str(booking,"customerId");if(!customer.isEmpty()&&!Set.of("appointment.overdue","appointment.created").contains(type))add(event,store,bid,"SUBSCRIBE",customer,0,type,payload,"PENDING","");
    }
    void add(String key,String store,String bid,String channel,String recipient,int generation,String type,Map<String,Object> payload,String status,String reason){if(d.count("SELECT COUNT(*) FROM notification_outbox WHERE event_key=? AND channel=? AND recipient_id=?",key,channel,recipient)>0)return;d.update("INSERT INTO notification_outbox(id,event_key,store_id,appointment_id,channel,recipient_id,recipient_generation,event_type,payload_json,delivery_status,attempts,last_error,created_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id(),key,store,bid,channel,recipient,generation,type,d.stringify(payload),status,0,reason,now(),now());}
    @Scheduled(fixedDelay=15000,initialDelay=20000) @Transactional public void dispatch(){
        var work=d.rows("SELECT * FROM notification_outbox WHERE delivery_status IN ('PENDING','RETRY') AND next_attempt_at<=? ORDER BY created_at LIMIT 20",now());
        for(var item:work){String id=str(item,"id");if(d.update("UPDATE notification_outbox SET delivery_status='SENDING' WHERE id=? AND delivery_status IN ('PENDING','RETRY')",id)!=1)continue;
            boolean service="SERVICE".equals(str(item,"channel"));String template=service?wx.serviceTemplate:wx.customerTemplate;String target="";int attempts=integer(item,"attempts",0)+1;
            try{
                if(template.isBlank()){finish(id,"BLOCKED",attempts,"尚未配置匹配的微信消息模板，不代表已发送。");continue;}
                if(service){var receivers=d.rows("SELECT nr.* FROM notification_recipient nr JOIN app_user u ON nr.admin_user_id=u.id WHERE nr.store_id=? AND nr.enabled=TRUE AND u.enabled=TRUE AND u.store_id=nr.store_id",item.get("storeId"));if(receivers.isEmpty()||integer(receivers.get(0),"generation",0)!=integer(item,"recipientGeneration",0)||!str(receivers.get(0),"adminUserId").equals(str(item,"recipientId"))){finish(id,"CANCELLED",attempts,"接收人已更换或解绑，停止给原人员发送。");continue;}target=str(receivers.get(0),"openid");if(target.isBlank()){finish(id,"BLOCKED",attempts,"接收人尚未绑定。");continue;}}
                else{var identities=d.rows("SELECT * FROM customer_identity WHERE id=?",item.get("recipientId"));if(identities.isEmpty()||bool(identities.get(0),"isDevelopment")){finish(id,"BLOCKED",attempts,"本地模拟身份不能发送真实微信消息。");continue;}target=str(identities.get(0),"openid");if(d.count("SELECT COUNT(*) FROM subscription_grant WHERE customer_id=? AND template_id=? AND remaining>0",item.get("recipientId"),template)==0){finish(id,"BLOCKED",attempts,"客户未订阅或可用次数不足。");continue;}}
                var mapping=d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?",service?"serviceTemplateMapping":"customerTemplateMapping");if(mapping.isEmpty()){finish(id,"BLOCKED",attempts,"需按正式账号模板字段配置映射。");continue;}Map<String,Object> values=d.object(item.get("payloadJson")),fields=d.object(mapping.get(0).get("settingValue")),data=new LinkedHashMap<>();for(var e:fields.entrySet()){String value=str(values,e.getValue().toString());if(value.length()>20)value=value.substring(0,20);data.put(e.getKey(),Map.of("value",value));}if(data.isEmpty()){finish(id,"BLOCKED",attempts,"模板字段映射不能为空。");continue;}
                var result=wx.send(service,target,template,data,str(item,"appointmentId"));int err=integer(result,"errcode",-1);if(err==0){if(!service)d.update("UPDATE subscription_grant SET remaining=CASE WHEN remaining>0 THEN remaining-1 ELSE 0 END WHERE customer_id=? AND template_id=?",item.get("recipientId"),template);finish(id,"ACCEPTED",attempts,"微信接口已受理；不代表用户已读。msgid="+str(result,"msgid"));}
                else if(Set.of(40003,43101,47003,40037,48001,43004).contains(err)){finish(id,"BLOCKED",attempts,"微信拒绝："+err+" "+str(result,"errmsg"));if(!service&&err==43101)d.update("UPDATE subscription_grant SET remaining=0 WHERE customer_id=? AND template_id=?",item.get("recipientId"),template);}
                else retry(id,attempts,"微信错误码："+err);
            }catch(Exception e){retry(id,attempts,"微信调用失败；请检查服务端配置和网络。");}
        }
    }
    void finish(String id,String state,int attempts,String reason){d.update("UPDATE notification_outbox SET delivery_status=?,attempts=?,last_error=? WHERE id=?",state,attempts,reason,id);}
    void retry(String id,int attempts,String reason){d.update("UPDATE notification_outbox SET delivery_status=?,attempts=?,last_error=?,next_attempt_at=? WHERE id=?",attempts>=3?"FAILED":"RETRY",attempts,reason,OffsetDateTime.now(ZONE).plusMinutes(attempts).toString(),id);}
    @Scheduled(fixedDelay=60000,initialDelay=30000) @Transactional public void reminders(){
        for(var b:d.rows("SELECT * FROM appointment WHERE status IN ('PENDING','CONFIRMED')")){var rules=catalog.rules(str(b,"storeId"));if(str(b,"status").equals("PENDING")){LocalDateTime created=OffsetDateTime.parse(str(b,"createdAt")).toLocalDateTime();if(businessMinutes(created,LocalDateTime.now(ZONE),rules)>=15)enqueue(b,"appointment.overdue");}else{LocalDateTime at=LocalDate.parse(str(b,"bookingDate")).atTime(LocalTime.parse(str(b,"startTime")));long left=Duration.between(LocalDateTime.now(ZONE),at).toMinutes();if(left>=0&&left<=integer(rules,"remindMinutes",15))enqueue(b,"appointment.reminder");}}
    }
    long businessMinutes(LocalDateTime from,LocalDateTime to,Map<String,Object> rules){long total=0;for(LocalDate day=from.toLocalDate();!day.isAfter(to.toLocalDate());day=day.plusDays(1)){if(d.strings(rules.get("closedDates")).contains(day.toString()))continue;var open=day.atTime(LocalTime.parse(str(rules,"opens")));var close=day.atTime(LocalTime.parse(str(rules,"closes")));var start=from.isAfter(open)?from:open;var end=to.isBefore(close)?to:close;if(end.isAfter(start))total+=Duration.between(start,end).toMinutes();if(total>=15)return total;}return total;}
}
