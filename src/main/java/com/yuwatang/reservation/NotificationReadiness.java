package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class NotificationReadiness {
    final Data d;final AuthService auth;final WechatGateway wx;final SmsGateway sms;
    NotificationReadiness(Data d,AuthService auth,WechatGateway wx,SmsGateway sms){this.d=d;this.auth=auth;this.wx=wx;this.sms=sms;}
    boolean mappingReady(String key){var rows=d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?",key);if(rows.isEmpty())return false;try{return !d.object(rows.get(0).get("settingValue")).isEmpty();}catch(RuntimeException invalid){return false;}}
    Map<String,Object> template(boolean reminder){
        String id=reminder?wx.customerReminderTemplate:wx.customerTemplate;
        String issue=id.isBlank()?"服务器尚未配置此订阅模板。":!mappingReady(wx.customerMapping(reminder?"appointment.reminder":"appointment.confirm"))?"总部尚未配置此模板的字段映射。":wx.appId.isBlank()||wx.secret.isBlank()?"服务器尚未配置小程序 AppID / AppSecret。":"";
        return Map.of("kind",reminder?"reminder":"result","title",reminder?"到店时间提醒":"预约处理提醒","templateId",id,"ready",issue.isEmpty(),"issue",issue);
    }
    @GetMapping("/api/admin/notifications/readiness") @Permit("notifications:read")
    Map<String,Object> readiness(HttpServletRequest r){
        auth.admin(r);var issues=new ArrayList<String>();
        if(wx.serviceId.isBlank()||wx.serviceSecret.isBlank())issues.add("待配置认证服务号 AppID / AppSecret。");
        if(wx.serviceTemplate.isBlank())issues.add("待配置门店人员服务号模板 ID。");
        if(!mappingReady("serviceTemplateMapping"))issues.add("待配置服务号模板字段映射。");
        if(!wx.publicUrl.startsWith("https://"))issues.add("待配置 HTTPS 的 PUBLIC_URL，并在服务号配置网页授权域名。");
        return Map.of("customer",Map.of("templates",List.of(template(false),template(true)),"credentialsConfigured",!wx.appId.isBlank()&&!wx.secret.isBlank()),
                "staff",Map.of("ready",issues.isEmpty(),"issues",issues),"sms",Map.of("configured",sms.configured(),"staffIssue",sms.issue(true),"customerIssue",sms.issue(false)));
    }
    @PostMapping("/api/admin/notifications/wechat/check") @Permit("notifications:write")
    Map<String,Object> check(HttpServletRequest r,@RequestBody Map<String,Object> in){
        auth.admin(r);String audience=str(in,"audience");
        if(!Set.of("CUSTOMER","STAFF").contains(audience))throw error(400,"请选择客户或门店人员微信渠道。");
        boolean service=audience.equals("STAFF");var remote=wx.notificationTemplates(service);var templates=new ArrayList<Map<String,Object>>();
        for(boolean reminder:service?List.of(false):List.of(false,true)){
            String id=service?wx.serviceTemplate:reminder?wx.customerReminderTemplate:wx.customerTemplate;
            var found=remote.stream().filter(t->id.equals(str(t,service?"templateId":"priTmplId"))||id.equals(str(t,"template_id"))).findFirst();
            String issue=id.isBlank()?"服务器尚未填写模板 ID。":found.isEmpty()?"此模板不在当前微信账号的“我的模板”中。":"";
            String mapping=service?"serviceTemplateMapping":wx.customerMapping(reminder?"appointment.reminder":"appointment.confirm");
            if(issue.isEmpty()){
                var fields=new HashSet<String>();var matcher=java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z_0-9]*)\\.DATA\\s*\\}\\}").matcher(str(found.get(),"content"));
                while(matcher.find())fields.add(matcher.group(1));
                var stored=d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?",mapping);
                Set<String> configured=stored.isEmpty()?Set.of():d.object(stored.get(0).get("settingValue")).keySet();
                if(configured.isEmpty())issue="总部尚未配置模板字段映射。";
                else if(!fields.isEmpty()&&!configured.containsAll(fields)){fields.removeAll(configured);issue="缺少微信模板字段："+String.join("、",new TreeSet<>(fields));}
            }
            templates.add(Map.of("title",service?"店长微信提醒":reminder?"到店时间提醒":"预约处理提醒","templateId",id,"ready",issue.isEmpty(),"issue",issue));
        }
        return Map.of("credentialsVerified",true,"templates",templates,"ready",templates.stream().allMatch(t->bool(t,"ready")),"message","已从微信读取当前账号模板；本次检查未发送客户或店长消息。");
    }
}
