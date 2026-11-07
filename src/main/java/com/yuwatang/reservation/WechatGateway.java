package com.yuwatang.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
public class WechatGateway {
    final String appId,secret,serviceId,serviceSecret,customerTemplate,customerReminderTemplate,serviceTemplate,publicUrl;
    final RestClient http;
    final ObjectMapper json;
    private static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory(){
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(12));
        return factory;
    }
    record Access(String value,Instant expiry){} final Map<String,Access> cache=new HashMap<>();
    @Autowired
    WechatGateway(@Value("${app.wechat.app-id}")String appId,@Value("${app.wechat.secret}")String secret,@Value("${app.wechat.service-app-id}")String serviceId,@Value("${app.wechat.service-secret}")String serviceSecret,@Value("${app.wechat.customer-template}")String customerTemplate,@Value("${app.wechat.customer-reminder-template:}")String customerReminderTemplate,@Value("${app.wechat.service-template}")String serviceTemplate,@Value("${app.public-url}")String publicUrl,ObjectMapper json){this(appId,secret,serviceId,serviceSecret,customerTemplate,customerReminderTemplate,serviceTemplate,publicUrl,json,RestClient.builder().requestFactory(requestFactory()).build());}
    WechatGateway(String appId,String secret,String serviceId,String serviceSecret,String customerTemplate,String customerReminderTemplate,String serviceTemplate,String publicUrl,ObjectMapper json,RestClient http){this.appId=appId;this.secret=secret;this.serviceId=serviceId;this.serviceSecret=serviceSecret;this.customerTemplate=customerTemplate;this.customerReminderTemplate=customerReminderTemplate;this.serviceTemplate=serviceTemplate;this.publicUrl=publicUrl;this.json=json;this.http=http;}
    List<String> customerTemplates(){return java.util.stream.Stream.of(customerTemplate,customerReminderTemplate).filter(t->!t.isBlank()).distinct().toList();}
    String customerTemplate(String event){return event.equals("appointment.reminder")&&!customerReminderTemplate.isBlank()?customerReminderTemplate:customerTemplate;}
    String customerMapping(String event){return event.equals("appointment.reminder")&&!customerReminderTemplate.isBlank()&&!customerReminderTemplate.equals(customerTemplate)?"customerReminderTemplateMapping":"customerTemplateMapping";}
    Map<String,Object> response(byte[] body){
        // Some WeChat endpoints return a JSON object with a text/plain content type.
        // Parse the payload explicitly instead of selecting a converter by that header.
        if(body==null||body.length==0)throw error(502,"微信接口未返回响应内容。");
        try{Map<String,Object> result=json.readValue(body,new TypeReference<>(){});if(result==null)throw new IllegalArgumentException();return result;}
        catch(java.io.IOException|IllegalArgumentException e){throw error(502,"微信接口返回的内容无法解析。");}
    }
    Map<String,Object> get(String url){try{return response(http.get().uri(url).retrieve().body(byte[].class));}catch(org.springframework.web.client.RestClientException e){throw error(502,"微信接口暂不可用，请稍后重试。");}}
    Map<String,Object> post(String url,Map<String,Object> body){try{return response(http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(byte[].class));}catch(org.springframework.web.client.RestClientException e){throw error(502,"微信接口暂不可用。");}}
    String login(String code){if(appId.isBlank()||secret.isBlank())throw error(503,"小程序登录尚未配置，请联系管理员完成微信账号接入。");var r=get("https://api.weixin.qq.com/sns/jscode2session?appid="+enc(appId)+"&secret="+enc(secret)+"&js_code="+enc(code)+"&grant_type=authorization_code");if(!r.containsKey("openid"))throw error(401,"微信登录未完成，请重新进入小程序。");return str(r,"openid");}
    String oauth(String code){if(serviceId.isBlank()||serviceSecret.isBlank())throw error(503,"服务号尚未配置。");var r=get("https://api.weixin.qq.com/sns/oauth2/access_token?appid="+enc(serviceId)+"&secret="+enc(serviceSecret)+"&code="+enc(code)+"&grant_type=authorization_code");if(!r.containsKey("openid"))throw error(400,"微信授权未完成，请重新生成绑定链接。");String openid=str(r,"openid");var follow=get("https://api.weixin.qq.com/cgi-bin/user/info?access_token="+enc(access(true))+"&openid="+enc(openid)+"&lang=zh_CN");if(integer(follow,"subscribe",0)!=1)throw error(400,"请先关注品牌服务号，再重新绑定。");return openid;}
    synchronized String access(boolean service){String k=service?"service":"mini";var c=cache.get(k);if(c!=null&&Instant.now().isBefore(c.expiry))return c.value;String id=service?serviceId:appId,sec=service?serviceSecret:secret;if(id.isBlank()||sec.isBlank())throw error(503,"微信应用密钥未配置，请检查服务器的 AppID 和 AppSecret。");var r=get("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="+enc(id)+"&secret="+enc(sec));String value=str(r,"access_token");if(value.isBlank())throw error(502,"微信 access_token 获取失败。"+failureReason(integer(r,"errcode",-1)));cache.put(k,new Access(value,Instant.now().plusSeconds(Math.max(60,integer(r,"expires_in",7200)-300))));return value;}
    static String failureReason(int code){return "微信错误码 "+code+"："+switch(code){
        case 40013 -> "AppID 无效，请核对小程序 AppID。";
        case 40125 -> "AppSecret 无效，请核对服务器密钥配置。";
        case 40164 -> "服务器出口 IP 不在微信 IP 白名单，请在小程序后台添加。";
        case 40001,40014,42001 -> "access_token 无效或已过期，请检查是否有其他系统共用此小程序并重复获取凭证。";
        case 48001 -> "当前小程序没有此接口权限，请检查客服功能权限。";
        case 45015 -> "微信回复窗口已过期，请等待客户再次发送消息。";
        case 45047 -> "微信客服可回复条数已用完，请等待客户再次发送消息。";
        case 40003 -> "客户 OpenID 无效，请检查消息与小程序账号是否一致。";
        case -1 -> "微信暂未提供明确错误原因，请稍后检查服务状态。";
        default -> "微信未接受请求，请按此错误码检查客服接口配置。";
    };}
    static class CustomerSendNotAttemptedException extends RuntimeException {
        CustomerSendNotAttemptedException(String reason){super(reason);}
    }
    Map<String,Object> send(boolean service,String recipient,String template,Map<String,Object> data,String appointment){Map<String,Object> body=new LinkedHashMap<>();body.put("touser",recipient);body.put("template_id",template);body.put("data",data);if(service){if(publicUrl.startsWith("https://"))body.put("url",publicUrl.replaceAll("/+$","")+"/admin");}else body.put("page","pages/orders/index?id="+appointment);return post("https://api.weixin.qq.com/cgi-bin/"+(service?"message/template/send":"message/subscribe/send")+"?access_token="+enc(access(service)),body);}
    Map<String,Object> sendCustomerText(String openid,String content){
        String token;
        try{token=access(false);}catch(ResponseStatusException e){throw new CustomerSendNotAttemptedException("消息尚未提交微信："+e.getReason());}
        return post("https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token="+enc(token),Map.of("touser",openid,"msgtype","text","text",Map.of("content",content)));
    }
    byte[] customerImage(String mediaId){try{var response=http.get().uri("https://api.weixin.qq.com/cgi-bin/media/get?access_token="+enc(access(false))+"&media_id="+enc(mediaId)).retrieve().toEntity(byte[].class);byte[] bytes=response.getBody();String type=Objects.toString(response.getHeaders().getContentType(),"");if(bytes==null||bytes.length>10*1024*1024||!type.startsWith("image/"))throw error(410,"微信图片已过期或暂不可用。");return bytes;}catch(org.springframework.web.client.RestClientException e){throw error(502,"图片读取失败，请稍后重试。");}}
    static String enc(String text){return java.net.URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8);}
}
