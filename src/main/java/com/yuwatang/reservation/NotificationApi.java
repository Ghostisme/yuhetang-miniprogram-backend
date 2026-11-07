package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class NotificationApi {
    final Data d;final AuthService auth;final AdminService admin;final WechatGateway wx;final String oauthUrl;
    final NotificationChannels channels;final SmsGateway sms;
    NotificationApi(Data d,AuthService auth,AdminService admin,WechatGateway wx,NotificationChannels channels,SmsGateway sms,@Value("${app.wechat.oauth-url}")String oauth){this.d=d;this.auth=auth;this.admin=admin;this.wx=wx;this.channels=channels;this.sms=sms;oauthUrl=oauth;}
    @GetMapping("/api/admin/notifications") @Permit("notifications:read")
    Map<String,Object> logs(HttpServletRequest r,@RequestParam(defaultValue="")String storeId,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="")String channel,
            @RequestParam(defaultValue="")String status){
        var a=auth.admin(r);String store=admin.scope(a,storeId);
        if(page<1)throw error(400,"消息页码不正确。");
        if(!Set.of("","PC","WECHAT","SMS").contains(channel))throw error(400,"请选择 PC、微信个人消息或短信通知。");
        if(!Set.of("","AVAILABLE","CANCELLED","PENDING","RETRY","SENDING","ACCEPTED","BLOCKED","FAILED","UNKNOWN").contains(status))throw error(400,"消息状态不正确。");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!store.isEmpty()){where+=" AND store_id=?";args.add(store);}
        if(!channel.isEmpty())where+=switch(channel){case "PC"->" AND channel='INBOX'";case "WECHAT"->" AND channel IN ('SERVICE','SUBSCRIBE')";default->" AND channel='SMS'";};
        if(!status.isEmpty()){where+=" AND delivery_status=?";args.add(status);}
        int total=d.count("SELECT COUNT(*) FROM notification_outbox"+where,args.toArray());args.add(30);args.add((long)(page-1)*30);
        var rows=d.rows("SELECT id,store_id,appointment_id,channel,recipient_id,event_type,payload_json,delivery_status,attempts,last_error,provider_message_id,created_at FROM notification_outbox"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var row:rows){
            String type=str(row,"channel");row.put("channelGroup",NotificationChannels.group(type));
            row.put("audience",type.equals("SUBSCRIBE")||type.equals("SMS")&&str(row,"recipientId").equals("customer")?"预约客户":type.equals("INBOX")?"后台账号":"门店接收人");
            row.put("eventLabel",NotificationService.EVENTS.getOrDefault(str(row,"eventType"),str(row,"eventType")));row.remove("recipientId");
        }
        return Map.of("total",total,"items",rows);
    }
    @GetMapping("/api/admin/inbox") @Permit("notifications:read")
    List<Map<String,Object>> inbox(HttpServletRequest r){var a=auth.admin(r);return d.rows("SELECT id,store_id,appointment_id,event_type,payload_json,created_at FROM notification_outbox WHERE channel='INBOX' AND delivery_status='AVAILABLE'"+(a.hq()?"":" AND store_id=?")+" ORDER BY created_at DESC LIMIT 20",a.hq()?new Object[]{}:new Object[]{a.storeId()}).stream().peek(m->m.put("payload",d.object(m.remove("payloadJson")))).toList();}
    @GetMapping("/api/admin/notifications/config") @Permit("notifications:read")
    Map<String,Object> config(HttpServletRequest r,@RequestParam String storeId){
        var a=auth.admin(r);a.store(storeId);d.one("SELECT id FROM store WHERE id=?",storeId);
        var rows=d.rows("SELECT store_id,admin_user_id,enabled,generation,CASE WHEN openid IS NULL OR openid='' THEN FALSE ELSE TRUE END bound FROM notification_recipient WHERE store_id=?",storeId);
        var settings=new LinkedHashMap<>(channels.get(storeId));String phone=str(settings,"smsPhone");
        settings.put("smsPhoneMasked",phone.isEmpty()?"":phone.substring(0,3)+"****"+phone.substring(7));
        if(!a.permissions().contains("notifications:write"))settings.put("smsPhone","");
        return Map.of("recipient",rows.isEmpty()?Map.of():rows.get(0),"channels",settings,
                "serviceConfigured",!wx.serviceId.isBlank()&&!wx.serviceSecret.isBlank(),
                "serviceTemplateConfigured",!wx.serviceTemplate.isBlank(),"customerTemplateConfigured",!wx.customerTemplate.isBlank(),
                "customerReminderTemplateConfigured",!wx.customerReminderTemplate.isBlank(),
                "smsConfigured",sms.configured(),"smsStaffIssue",sms.issue(true),"smsCustomerIssue",sms.issue(false),"smsProvider","腾讯云短信");
    }
    @PutMapping("/api/admin/notifications/config") @Permit("notifications:write") @Transactional
    public Map<String,Object> saveConfig(HttpServletRequest r,@RequestBody Map<String,Object> in){
        channels.save(auth.admin(r),in);return config(r,text(in,"storeId",1,64));
    }
    @PostMapping("/api/admin/notifications/binding") @Permit("notifications:write") @Transactional public Map<String,Object> binding(HttpServletRequest r,@RequestBody Map<String,Object> in){var a=auth.admin(r);String store=text(in,"storeId",1,64),user=text(in,"adminUserId",1,64);a.store(store);d.one("SELECT id FROM store WHERE id=?",store);var u=d.one("SELECT * FROM app_user WHERE id=? AND enabled=TRUE",user);if(!store.equals(str(u,"storeId")))throw error(400,"接收人必须是本门店有效账号。");if(wx.serviceId.isBlank()||wx.serviceSecret.isBlank()||wx.publicUrl.isBlank())throw error(409,"需先配置认证服务号 AppID/密钥、PUBLIC_URL 及微信网页授权域名，再生成真实绑定链接。");var previous=d.rows("SELECT * FROM notification_recipient WHERE store_id=?",store);int gen=previous.isEmpty()?1:integer(previous.get(0),"generation",0)+1;d.update("DELETE FROM notification_recipient WHERE store_id=?",store);d.update("INSERT INTO notification_recipient VALUES(?,?,?,?,?)",store,user,null,true,gen);d.update("UPDATE service_binding SET used=TRUE WHERE store_id=?",store);String state=id();d.update("INSERT INTO service_binding VALUES(?,?,?,?,?)",state,store,user,OffsetDateTime.now(ZONE).plusMinutes(15).toString(),false);String callback=wx.publicUrl.replaceAll("/$","")+"/api/wechat/service/callback";String url="https://open.weixin.qq.com/connect/oauth2/authorize?appid="+WechatGateway.enc(wx.serviceId)+"&redirect_uri="+WechatGateway.enc(callback)+"&response_type=code&scope=snsapi_base&state="+state+"#wechat_redirect";d.audit(a,store,"notification.bind-request",user,"生成15分钟有效绑定链接");return Map.of("url",url,"expiresIn",900);}
    @DeleteMapping("/api/admin/notifications/binding/{store}") @Permit("notifications:write") @Transactional public Map<String,Object> unbind(HttpServletRequest r,@PathVariable String store){var a=auth.admin(r);a.store(store);d.one("SELECT id FROM store WHERE id=?",store);d.update("UPDATE notification_recipient SET openid=NULL,enabled=FALSE,generation=generation+1 WHERE store_id=?",store);d.update("UPDATE service_binding SET used=TRUE WHERE store_id=?",store);d.audit(a,store,"notification.unbind",store,"停止向原接收人发送新消息");return Map.of("saved",true);}
    Map<String,Object> valid(String state){var b=d.one("SELECT * FROM service_binding WHERE token=?",state);if(bool(b,"used")||OffsetDateTime.parse(str(b,"expiresAt")).isBefore(OffsetDateTime.now(ZONE)))throw error(400,"绑定链接已失效，请在后台重新生成。");return b;}
    @GetMapping(value="/api/wechat/service/callback",produces=MediaType.TEXT_HTML_VALUE)String callback(@RequestParam String code,@RequestParam String state){var b=valid(state);String openid=wx.oauth(code);String key="bind:"+state;if(d.update("UPDATE app_setting SET setting_value=? WHERE setting_key=?",openid,key)==0)d.update("INSERT INTO app_setting VALUES(?,?)",key,openid);String store=str(d.one("SELECT name FROM store WHERE id=?",b.get("storeId")),"name");return "<!doctype html><html lang='zh-CN'><meta name='viewport' content='width=device-width,initial-scale=1'><meta charset='utf-8'><body style='font:17px sans-serif;padding:40px;color:#354b3b;background:#faf9f4'><h2>裕和堂 · 消息绑定</h2><p>确认接收"+org.springframework.web.util.HtmlUtils.htmlEscape(store)+"的新预约及待处理提醒？</p><form method='post' action='/api/wechat/service/confirm'><input type='hidden' name='state' value='"+org.springframework.web.util.HtmlUtils.htmlEscape(state)+"'><button style='padding:15px;background:#43594d;color:white;border:0'>本人确认绑定并接收通知</button></form></body></html>";}
    @PostMapping(value="/api/wechat/service/confirm",produces=MediaType.TEXT_HTML_VALUE) @Transactional public String confirm(@RequestParam String state){var b=valid(state);var setting=d.one("SELECT * FROM app_setting WHERE setting_key=?","bind:"+state);if(d.update("UPDATE service_binding SET used=TRUE WHERE token=? AND used=FALSE",state)!=1)throw error(409,"绑定已处理。");if(d.update("UPDATE notification_recipient SET openid=?,enabled=TRUE WHERE store_id=? AND admin_user_id=?",setting.get("settingValue"),b.get("storeId"),b.get("adminUserId"))!=1)throw error(409,"接收人已更换。");d.update("DELETE FROM app_setting WHERE setting_key=?","bind:"+state);return "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><p style='padding:40px'>绑定已完成，可关闭此页面。实际消息以服务号模板权限和微信发送结果为准。</p>";}
}
