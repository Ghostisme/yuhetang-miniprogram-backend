package com.yuwatang.reservation;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import static com.yuwatang.reservation.Data.*;

@Service
class SmsGateway {
    static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
            .readTimeout(12,TimeUnit.SECONDS).writeTimeout(12,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    final String secretId,secretKey,appId,signName,staffTemplate,customerTemplate,region;
    final List<String> staffParams,customerParams;
    record Result(String state,String code,String messageId) {}

    SmsGateway(@Value("${app.sms.secret-id:}") String secretId,
               @Value("${app.sms.secret-key:}") String secretKey,
               @Value("${app.sms.app-id:}") String appId,
               @Value("${app.sms.sign-name:}") String signName,
               @Value("${app.sms.staff-template:}") String staffTemplate,
               @Value("${app.sms.customer-template:}") String customerTemplate,
               @Value("${app.sms.region:ap-guangzhou}") String region,
               @Value("${app.sms.staff-params:storeName,time,eventLabel}") String staffParams,
               @Value("${app.sms.customer-params:storeName,time,status}") String customerParams) {
        this.secretId=secretId;this.secretKey=secretKey;this.appId=appId;this.signName=signName;
        this.staffTemplate=staffTemplate;this.customerTemplate=customerTemplate;this.region=region;
        this.staffParams=params(staffParams);this.customerParams=params(customerParams);
    }
    List<String> params(String source){return Arrays.stream(source.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();}
    boolean configured(){return !secretId.isBlank()&&!secretKey.isBlank()&&!appId.isBlank()&&!signName.isBlank();}
    String issue(boolean staff){
        if(!configured())return "待配置腾讯云短信凭证、应用和签名。";
        if((staff?staffTemplate:customerTemplate).isBlank())return "待配置"+(staff?"门店":"客户")+"短信模板。";
        var keys=staff?staffParams:customerParams;
        if(keys.isEmpty()||!Set.of("storeName","serviceName","time","status","appointmentId","event","eventLabel").containsAll(keys))return "短信模板参数顺序配置不正确。";
        return "";
    }
    OkHttpClient httpClient(){return HTTP;}
    Result send(boolean staff,String phone,Map<String,Object> payload,String notificationId){
        if(!issue(staff).isEmpty())return new Result("BLOCKED","ConfigurationMissing","");
        if(!phone.matches("1[3-9]\\d{9}"))return new Result("BLOCKED","InvalidPhone","");
        HttpProfile http=new HttpProfile();http.setEndpoint("sms.tencentcloudapi.com");http.setConnTimeout(8);http.setReadTimeout(12);http.setHttpClient(httpClient());
        ClientProfile profile=new ClientProfile();profile.setHttpProfile(http);
        SmsClient client=new SmsClient(new Credential(secretId,secretKey),region,profile);
        SendSmsRequest request=new SendSmsRequest();
        request.setPhoneNumberSet(new String[]{"+86"+phone});request.setSmsSdkAppId(appId);
        request.setSignName(signName);request.setTemplateId(staff?staffTemplate:customerTemplate);
        request.setTemplateParamSet((staff?staffParams:customerParams).stream().map(k->str(payload,k).replaceAll("[\\r\\n\\t]"," ")).toArray(String[]::new));
        request.setSessionContext(notificationId);
        try{
            var response=client.SendSms(request);var statuses=response.getSendStatusSet();
            if(statuses==null||statuses.length!=1)return new Result("UNKNOWN","ResponseUnclear","");
            var status=statuses[0];String code=Objects.toString(status.getCode(),"Unknown");
            String state="Ok".equals(code)?"ACCEPTED":code.startsWith("LimitExceeded.")||code.startsWith("RequestLimitExceeded")?"RETRY":"BLOCKED";
            return new Result(state,code,Objects.toString(status.getSerialNo(),""));
        }catch(TencentCloudSDKException e){
            String code=Objects.toString(e.getErrorCode(),"");
            // Only explicit provider rejection is safe to retry. A timeout may follow a successful send.
            if(code.startsWith("RequestLimitExceeded")||code.startsWith("LimitExceeded."))return new Result("RETRY",code,"");
            if(code.startsWith("AuthFailure")||code.startsWith("InvalidParameter")||code.startsWith("UnauthorizedOperation"))return new Result("BLOCKED",code,"");
            return new Result("UNKNOWN",code.isBlank()?"TransportUnclear":code,"");
        }
    }
}
