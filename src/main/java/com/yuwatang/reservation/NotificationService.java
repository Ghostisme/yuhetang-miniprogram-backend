package com.yuwatang.reservation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import static com.yuwatang.reservation.Data.*;

@Service
public class NotificationService {
    final Data d;final WechatGateway wx;final CatalogService catalog;final NotificationChannels channels;final SmsGateway sms;final TransactionTemplate tx;
    final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(40),r->{Thread t=new Thread(r,"notification-delivery");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    final String jobOwner=UUID.randomUUID().toString();
    @PreDestroy void stopWorkers(){workers.shutdown();try{if(!workers.awaitTermination(20,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}}
    static final Map<String,String> EVENTS=Map.ofEntries(
        Map.entry("appointment.created","新预约申请"),Map.entry("appointment.confirm","预约已确认"),
        Map.entry("appointment.reject","预约未通过"),Map.entry("appointment.cancel","预约已取消"),
        Map.entry("appointment.reschedule","预约已改期"),Map.entry("appointment.arrive","客户已到店"),
        Map.entry("appointment.complete","接待已完成"),Map.entry("appointment.noshow","客户未到店"),
        Map.entry("appointment.overdue","预约待确认超时"),Map.entry("appointment.reminder","到店时间提醒"));
    static final Set<String> CUSTOMER_SMS_EVENTS=Set.of("appointment.confirm","appointment.reject","appointment.cancel","appointment.reschedule","appointment.reminder");
    NotificationService(Data d,WechatGateway wx,CatalogService catalog,NotificationChannels channels,SmsGateway sms,PlatformTransactionManager manager){this.d=d;this.wx=wx;this.catalog=catalog;this.channels=channels;this.sms=sms;this.tx=new TransactionTemplate(manager);}
    void enqueue(Map<String,Object> booking,String type){
        String store=str(booking,"storeId"),bid=str(booking,"id"),event=bid+":"+booking.get("version")+":"+type;
        var config=channels.get(store);
        Map<String,Object> payload=Map.of("appointmentId",bid,"storeName",catalog.store(store).get("name"),"serviceName",booking.get("serviceName"),"customerName",str(booking,"customerName"),"time",booking.get("bookingDate")+" "+booking.get("startTime"),"status",BookingService.LABELS.get(str(booking,"status")),"event",type,"eventLabel",EVENTS.getOrDefault(type,type));
        if(!type.equals("appointment.reminder")){
            if(bool(config,"pcEnabled"))add(event,store,bid,"INBOX",store,0,type,payload,"AVAILABLE","");
            if(bool(config,"wechatStaffEnabled")){
                var receivers=d.rows("SELECT nr.* FROM notification_recipient nr JOIN app_user u ON nr.admin_user_id=u.id WHERE nr.store_id=? AND nr.enabled=TRUE AND u.enabled=TRUE AND u.store_id=nr.store_id",store);
                if(receivers.isEmpty())add(event,store,bid,"SERVICE","unbound",0,type,payload,"BLOCKED","本门店尚未绑定微信接收人。");
                else{var r=receivers.get(0);String openid=str(r,"openid");add(event,store,bid,"SERVICE",str(r,"adminUserId"),integer(r,"generation",0),type,payload,openid.isBlank()?"BLOCKED":"PENDING",openid.isBlank()?"接收人尚未在微信完成确认绑定。":"");}
            }
            if(bool(config,"smsStaffEnabled"))add(event,store,bid,"SMS","staff",integer(config,"revision",0),type,payload,"PENDING","");
        }
        String customer=str(booking,"customerId");
        if(bool(config,"wechatCustomerEnabled")&&!customer.isEmpty()&&CUSTOMER_SMS_EVENTS.contains(type))add(event,store,bid,"SUBSCRIBE",customer,0,type,payload,"PENDING","");
        if(bool(config,"smsCustomerEnabled")&&CUSTOMER_SMS_EVENTS.contains(type))add(event,store,bid,"SMS","customer",integer(config,"revision",0),type,payload,"PENDING","");
    }
    void add(String key,String store,String bid,String channel,String recipient,int generation,String type,Map<String,Object> payload,String status,String reason){
        d.update("INSERT INTO notification_outbox(id,event_key,store_id,appointment_id,channel,recipient_id,recipient_generation,event_type,payload_json,delivery_status,attempts,last_error,created_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_key=event_key",id(),key,store,bid,channel,recipient,generation,type,d.stringify(payload),status,0,reason,now(),now());
    }
    List<Map<String,Object>> pending(){return d.rows("SELECT * FROM notification_outbox WHERE delivery_status IN ('PENDING','RETRY') AND next_attempt_at<=? ORDER BY created_at LIMIT 40",now());}
    @Scheduled(fixedDelay=5000,initialDelay=20000) public void scheduledDispatch(){
        d.update("UPDATE notification_outbox SET delivery_status='UNKNOWN',last_error=? WHERE delivery_status='SENDING' AND claimed_at<?","Worker interrupted; verify provider result before resending.",OffsetDateTime.now(ZONE).minusMinutes(10).toString());
        for(var item:pending())try{workers.execute(()->deliver(item));}catch(RejectedExecutionException full){break;}
    }
    // Kept synchronous for operator-triggered execution and deterministic contract tests.
    public void dispatch(){for(var item:pending())deliver(item);}
    void deliver(Map<String,Object> item){
        String id=str(item,"id");if(!claim(id))return;item=d.one("SELECT * FROM notification_outbox WHERE id=?",id);int attempts=integer(item,"attempts",0)+1;
        // Claims and reservations commit before network I/O; no pooled connection is held while waiting for providers.
        try{if("SMS".equals(str(item,"channel")))dispatchSms(item,attempts);else dispatchWechat(item,attempts);}
        catch(Exception e){finish(id,"UNKNOWN",attempts,"发送结果待核实，已暂停自动重发。请先核实接收方是否收到。");}
    }
    boolean claim(String id){String time=now();return d.update("UPDATE notification_outbox SET delivery_status='SENDING',claimed_at=? WHERE id=? AND delivery_status IN ('PENDING','RETRY') AND next_attempt_at<=?",time,id,time)==1;}
    void dispatchSms(Map<String,Object> item,int attempts){
        String id=str(item,"id");boolean staff="staff".equals(str(item,"recipientId"));
        var config=channels.get(str(item,"storeId"));
        if(!bool(config,staff?"smsStaffEnabled":"smsCustomerEnabled")||integer(config,"revision",0)!=integer(item,"recipientGeneration",-1)){
            finish(id,"CANCELLED",attempts,"短信渠道已关闭或接收设置已变更。");return;
        }
        var bookings=d.rows("SELECT * FROM appointment WHERE id=? AND store_id=?",item.get("appointmentId"),item.get("storeId"));
        if(bookings.isEmpty()){finish(id,"CANCELLED",attempts,"预约已不存在。");return;}
        var booking=bookings.get(0);
        if(!str(item,"eventKey").startsWith(str(booking,"id")+":"+booking.get("version")+":")){
            finish(id,"CANCELLED",attempts,"预约已有更新，停止发送旧状态短信。");return;
        }
        String issue=sms.issue(staff);
        if(!issue.isEmpty()){finish(id,"BLOCKED",attempts,issue);return;}
        try{
            var result=sms.send(staff,staff?str(config,"smsPhone"):str(booking,"phone"),d.object(item.get("payloadJson")),id);
            if(!result.messageId().isBlank())d.update("UPDATE notification_outbox SET provider_message_id=? WHERE id=?",result.messageId(),id);
            if(result.state().equals("RETRY")){retry(id,attempts,"短信平台限流："+result.code());return;}
            String reason=switch(result.state()){
                case "ACCEPTED"->"短信平台已受理，尚未确认送达。";
                case "UNKNOWN"->"短信发送结果待核实，暂停自动重发以避免重复。";
                default->"短信平台未受理："+result.code();
            };
            finish(id,result.state(),attempts,reason);
        }catch(Exception e){finish(id,"UNKNOWN",attempts,"短信发送结果待核实，暂停自动重发以避免重复。");}
    }
    void dispatchWechat(Map<String,Object> item,int attempts){
        String id=str(item,"id"),target="";boolean service="SERVICE".equals(str(item,"channel"));
        var config=channels.get(str(item,"storeId"));
        if(!bool(config,service?"wechatStaffEnabled":"wechatCustomerEnabled")){finish(id,"CANCELLED",attempts,"该微信通知渠道已关闭。");return;}
        Map<String,Object> values=d.object(item.get("payloadJson"));
        // Freeze the template and rendered fields when reserving the allowance. Retries must
        // use that same template, even if the operator changes the current configuration.
        String template=service?wx.serviceTemplate:values.containsKey("subscriptionTemplateId")?str(values,"subscriptionTemplateId"):wx.customerTemplate(str(item,"eventType"));
        if(template.isBlank()){finish(id,"BLOCKED",attempts,"尚未配置匹配的微信消息模板，不代表已发送。");return;}
        if(!service){var current=d.rows("SELECT id,version FROM appointment WHERE id=?",item.get("appointmentId"));if(current.isEmpty()||!str(item,"eventKey").startsWith(str(current.get(0),"id")+":"+current.get(0).get("version")+":")){finish(id,"CANCELLED",attempts,"预约已有更新，停止发送旧状态订阅消息。");return;}}
        if(service){var receivers=d.rows("SELECT nr.* FROM notification_recipient nr JOIN app_user u ON nr.admin_user_id=u.id WHERE nr.store_id=? AND nr.enabled=TRUE AND u.enabled=TRUE AND u.store_id=nr.store_id",item.get("storeId"));if(receivers.isEmpty()||integer(receivers.get(0),"generation",0)!=integer(item,"recipientGeneration",0)||!str(receivers.get(0),"adminUserId").equals(str(item,"recipientId"))){finish(id,"CANCELLED",attempts,"接收人已更换或解绑，停止给原人员发送。");return;}target=str(receivers.get(0),"openid");if(target.isBlank()){finish(id,"BLOCKED",attempts,"接收人尚未绑定。");return;}}
        else{var identities=d.rows("SELECT * FROM customer_identity WHERE id=?",item.get("recipientId"));if(identities.isEmpty()||bool(identities.get(0),"isDevelopment")){finish(id,"BLOCKED",attempts,"本地模拟身份不能发送真实微信消息。");return;}target=str(identities.get(0),"openid");if(!bool(item,"grantReserved")&&d.count("SELECT COUNT(*) FROM subscription_grant WHERE customer_id=? AND template_id=? AND remaining>0",item.get("recipientId"),template)==0){finish(id,"BLOCKED",attempts,"客户未订阅或可用次数不足。");return;}}
        Map<String,Object> data=new LinkedHashMap<>();
        if(!service&&values.containsKey("subscriptionData"))data.putAll(d.object(values.get("subscriptionData")));
        else{
            var mapping=d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?",service?"serviceTemplateMapping":wx.customerMapping(str(item,"eventType")));if(mapping.isEmpty()){finish(id,"BLOCKED",attempts,"需按正式账号模板字段配置映射。");return;}
            for(var e:d.object(mapping.get(0).get("settingValue")).entrySet()){
                String value=str(values,e.getValue().toString());int limit=e.getKey().startsWith("phrase")?5:e.getKey().startsWith("name")?10:20;
                if(value.codePointCount(0,value.length())>limit)value=value.substring(0,value.offsetByCodePoints(0,limit));
                data.put(e.getKey(),Map.of("value",value));
            }
        }
        if(data.isEmpty()){finish(id,"BLOCKED",attempts,"模板字段映射不能为空。");return;}
        if(!service){values.put("subscriptionTemplateId",template);values.put("subscriptionData",data);}
        if(!service){Boolean reserved=tx.execute(status->{
            if(bool(d.one("SELECT grant_reserved FROM notification_outbox WHERE id=?",id),"grantReserved"))return true;
            if(d.update("UPDATE subscription_grant SET remaining=remaining-1 WHERE customer_id=? AND template_id=? AND remaining>0",item.get("recipientId"),template)!=1)return false;
            d.update("UPDATE notification_outbox SET grant_reserved=TRUE,payload_json=? WHERE id=?",d.stringify(values),id);return true;
        });if(!Boolean.TRUE.equals(reserved)){finish(id,"BLOCKED",attempts,"客户可用订阅次数不足，请客户在小程序重新授权。");return;}}
        Map<String,Object> result;
        try{result=wx.send(service,target,template,data,str(item,"appointmentId"));}
        catch(WechatGateway.NotificationNotAttemptedException e){if(!service)releaseGrant(item,template);finish(id,"BLOCKED",attempts,e.getMessage());return;}
        catch(WechatGateway.ExchangeException e){finish(id,"UNKNOWN",attempts,e.getReason()+" 发送结果待核实，已暂停自动重发。");return;}
        // A malformed/empty business response is not an explicit rejection: retrying could duplicate a delivered message.
        int err;
        try{if(!result.containsKey("errcode"))throw new IllegalArgumentException();err=integer(result,"errcode",-99999);}
        catch(RuntimeException invalid){finish(id,"UNKNOWN",attempts,"微信未返回有效的 errcode，发送结果待核实，已暂停自动重发。");return;}
        if(err==0){d.update("UPDATE notification_outbox SET provider_message_id=? WHERE id=?",str(result,"msgid"),id);finish(id,"ACCEPTED",attempts,"微信接口已受理；不代表用户已读。");}
        else if(Set.of(-1,45009,45011,43108).contains(err)){
            if(!service&&attempts>=3)releaseGrant(item,template);
            retry(id,attempts,"微信暂未受理（错误码 "+err+"），最多尝试3次。");
        }else{
            if(!service){releaseGrant(item,template);if(err==43101)d.update("UPDATE subscription_grant SET remaining=0 WHERE customer_id=? AND template_id=?",item.get("recipientId"),template);}
            if(Set.of(40001,40014,42001).contains(err))wx.invalidateAccess(service);
            finish(id,"BLOCKED",attempts,notificationReason(err));
        }
    }
    void releaseGrant(Map<String,Object> item,String template){tx.executeWithoutResult(status->{
        if(d.update("UPDATE notification_outbox SET grant_reserved=FALSE WHERE id=? AND grant_reserved=TRUE",item.get("id"))==1)
            d.update("UPDATE subscription_grant SET remaining=LEAST(remaining+1,20) WHERE customer_id=? AND template_id=?",item.get("recipientId"),template);
    });}
    static String notificationReason(int code){return "微信未受理（错误码 "+code+"）："+switch(code){
        case 43101 -> "客户未授权此模板或一次性订阅次数已用完，请在小程序重新订阅。";
        case 47003 -> "模板字段与微信后台不匹配，请核对字段键、类型和字数。";
        case 40037 -> "模板 ID 无效，请核对当前微信账号下的正式模板。";
        case 40003 -> "接收人 OpenID 无效或不属于当前微信账号。";
        case 48001 -> "当前微信账号没有此模板消息接口权限。";
        case 43004 -> "接收人未关注服务号，请关注后重新绑定。";
        case 40001,40014,42001 -> "微信凭证失效，已清除本服务缓存；请检查是否有其他系统重复获取凭证。";
        default -> "请根据错误码核对模板、接收条件与微信平台配置。";
    };}
    void finish(String id,String state,int attempts,String reason){d.update("UPDATE notification_outbox SET delivery_status=?,attempts=?,last_error=? WHERE id=?",state,attempts,reason,id);}
    void retry(String id,int attempts,String reason){d.update("UPDATE notification_outbox SET delivery_status=?,attempts=?,last_error=?,next_attempt_at=? WHERE id=?",attempts>=3?"FAILED":"RETRY",attempts,reason,OffsetDateTime.now(ZONE).plusMinutes(attempts).toString(),id);}
    @Scheduled(fixedDelay=60000,initialDelay=30000) public void reminders(){
        String until=OffsetDateTime.now(ZONE).plusSeconds(120).toString();
        if(d.update("UPDATE job_lease SET owner_id=?,lease_until=? WHERE job_name='notification-reminders' AND lease_until<?",jobOwner,until,now())!=1)return;
        try{
            Map<String,Map<String,Object>> ruleMap=new HashMap<>();String cursor="";
            while(true){
                var page=d.rows("SELECT * FROM appointment WHERE status IN ('PENDING','CONFIRMED') AND id>? ORDER BY id LIMIT 200",cursor);
                if(page.isEmpty())break;
                if(d.update("UPDATE job_lease SET lease_until=? WHERE job_name='notification-reminders' AND owner_id=? AND lease_until>=?",OffsetDateTime.now(ZONE).plusSeconds(120).toString(),jobOwner,now())!=1)return;
                for(var b:page){
                    var rules=ruleMap.computeIfAbsent(str(b,"storeId"),catalog::rules);boolean due=false;
                    if(str(b,"status").equals("PENDING")){LocalDateTime created=OffsetDateTime.parse(str(b,"createdAt")).toLocalDateTime();due=businessMinutes(created,LocalDateTime.now(ZONE),rules)>=15;}
                    else{LocalDateTime at=LocalDate.parse(str(b,"bookingDate")).atTime(LocalTime.parse(str(b,"startTime")));long left=Duration.between(LocalDateTime.now(ZONE),at).toMinutes();due=left>=0&&left<=integer(rules,"remindMinutes",15);}
                    if(due)tx.executeWithoutResult(status->{var current=d.one("SELECT * FROM appointment WHERE id=?",b.get("id"));if(Objects.equals(current.get("version"),b.get("version"))&&Objects.equals(current.get("status"),b.get("status")))enqueue(current,str(current,"status").equals("PENDING")?"appointment.overdue":"appointment.reminder");});
                }
                cursor=str(page.get(page.size()-1),"id");
            }
        }finally{d.update("UPDATE job_lease SET lease_until=?,owner_id=NULL WHERE job_name='notification-reminders' AND owner_id=?",now(),jobOwner);}
    }
    long businessMinutes(LocalDateTime from,LocalDateTime to,Map<String,Object> rules){long total=0;for(LocalDate day=from.toLocalDate();!day.isAfter(to.toLocalDate());day=day.plusDays(1)){if(d.strings(rules.get("closedDates")).contains(day.toString()))continue;var open=day.atTime(LocalTime.parse(str(rules,"opens")));var close=day.atTime(LocalTime.parse(str(rules,"closes")));var start=from.isAfter(open)?from:open;var end=to.isBefore(close)?to:close;if(end.isAfter(start))total+=Duration.between(start,end).toMinutes();if(total>=15)return total;}return total;}
}
