package com.yuwatang.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
public class WechatGateway {
    final String appId,secret,serviceId,serviceSecret,customerTemplate,customerReminderTemplate,serviceTemplate,publicUrl;
    final RestClient http=RestClient.builder().requestFactory(requestFactory()).build();
    private static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory(){
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(12));
        return factory;
    }
    record Access(String value,Instant expiry){} final Map<String,Access> cache=new HashMap<>();
    WechatGateway(@Value("${app.wechat.app-id}")String appId,@Value("${app.wechat.secret}")String secret,@Value("${app.wechat.service-app-id}")String serviceId,@Value("${app.wechat.service-secret}")String serviceSecret,@Value("${app.wechat.customer-template}")String customerTemplate,@Value("${app.wechat.customer-reminder-template:}")String customerReminderTemplate,@Value("${app.wechat.service-template}")String serviceTemplate,@Value("${app.public-url}")String publicUrl){this.appId=appId;this.secret=secret;this.serviceId=serviceId;this.serviceSecret=serviceSecret;this.customerTemplate=customerTemplate;this.customerReminderTemplate=customerReminderTemplate;this.serviceTemplate=serviceTemplate;this.publicUrl=publicUrl;}
    List<String> customerTemplates(){return java.util.stream.Stream.of(customerTemplate,customerReminderTemplate).filter(t->!t.isBlank()).distinct().toList();}
    String customerTemplate(String event){return event.equals("appointment.reminder")&&!customerReminderTemplate.isBlank()?customerReminderTemplate:customerTemplate;}
    String customerMapping(String event){return event.equals("appointment.reminder")&&!customerReminderTemplate.isBlank()&&!customerReminderTemplate.equals(customerTemplate)?"customerReminderTemplateMapping":"customerTemplateMapping";}
    @SuppressWarnings("unchecked") Map<String,Object> get(String url){try{var r=http.get().uri(url).retrieve().body(Map.class);if(r==null)throw error(502,"微信接口暂未响应。");return r;}catch(org.springframework.web.client.RestClientException e){throw error(502,"微信接口暂不可用，请稍后重试。");}}
    @SuppressWarnings("unchecked") Map<String,Object> post(String url,Map<String,Object> body){try{var r=http.post().uri(url).body(body).retrieve().body(Map.class);if(r==null)throw error(502,"微信接口暂未响应。");return r;}catch(org.springframework.web.client.RestClientException e){throw error(502,"微信接口暂不可用。");}}
    String login(String code){if(appId.isBlank()||secret.isBlank())throw error(503,"小程序登录尚未配置，请联系管理员完成微信账号接入。");var r=get("https://api.weixin.qq.com/sns/jscode2session?appid="+enc(appId)+"&secret="+enc(secret)+"&js_code="+enc(code)+"&grant_type=authorization_code");if(!r.containsKey("openid"))throw error(401,"微信登录未完成，请重新进入小程序。");return str(r,"openid");}
    String oauth(String code){if(serviceId.isBlank()||serviceSecret.isBlank())throw error(503,"服务号尚未配置。");var r=get("https://api.weixin.qq.com/sns/oauth2/access_token?appid="+enc(serviceId)+"&secret="+enc(serviceSecret)+"&code="+enc(code)+"&grant_type=authorization_code");if(!r.containsKey("openid"))throw error(400,"微信授权未完成，请重新生成绑定链接。");String openid=str(r,"openid");var follow=get("https://api.weixin.qq.com/cgi-bin/user/info?access_token="+enc(access(true))+"&openid="+enc(openid)+"&lang=zh_CN");if(integer(follow,"subscribe",0)!=1)throw error(400,"请先关注品牌服务号，再重新绑定。");return openid;}
    synchronized String access(boolean service){String k=service?"service":"mini";var c=cache.get(k);if(c!=null&&Instant.now().isBefore(c.expiry))return c.value;String id=service?serviceId:appId,sec=service?serviceSecret:secret;if(id.isBlank()||sec.isBlank())throw error(503,"微信应用密钥未配置。");var r=get("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="+enc(id)+"&secret="+enc(sec));if(!r.containsKey("access_token"))throw error(502,"微信 access_token 获取失败，错误码："+r.get("errcode"));String value=str(r,"accessToken");if(value.isBlank())value=str(r,"access_token");cache.put(k,new Access(value,Instant.now().plusSeconds(Math.max(60,integer(r,"expires_in",7200)-300))));return value;}
    Map<String,Object> send(boolean service,String recipient,String template,Map<String,Object> data,String appointment){Map<String,Object> body=new LinkedHashMap<>();body.put("touser",recipient);body.put("template_id",template);body.put("data",data);if(service){if(publicUrl.startsWith("https://"))body.put("url",publicUrl.replaceAll("/+$","")+"/admin");}else body.put("page","pages/orders/index?id="+appointment);return post("https://api.weixin.qq.com/cgi-bin/"+(service?"message/template/send":"message/subscribe/send")+"?access_token="+enc(access(service)),body);}
    Map<String,Object> sendCustomerText(String openid,String content){return post("https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token="+enc(access(false)),Map.of("touser",openid,"msgtype","text","text",Map.of("content",content)));}
    byte[] customerImage(String mediaId){try{var response=http.get().uri("https://api.weixin.qq.com/cgi-bin/media/get?access_token="+enc(access(false))+"&media_id="+enc(mediaId)).retrieve().toEntity(byte[].class);byte[] bytes=response.getBody();String type=Objects.toString(response.getHeaders().getContentType(),"");if(bytes==null||bytes.length>10*1024*1024||!type.startsWith("image/"))throw error(410,"微信图片已过期或暂不可用。");return bytes;}catch(org.springframework.web.client.RestClientException e){throw error(502,"图片读取失败，请稍后重试。");}}
    static String enc(String text){return java.net.URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8);}
}
