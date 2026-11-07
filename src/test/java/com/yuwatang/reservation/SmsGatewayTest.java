package com.yuwatang.reservation;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Buffer;
import static org.junit.jupiter.api.Assertions.*;

class SmsGatewayTest {
    SmsGateway intercepted(Interceptor interceptor){
        return new SmsGateway("test","test","app","签名","staff","customer","ap-guangzhou","storeName,time,eventLabel","storeName,time,status"){
            @Override OkHttpClient httpClient(){return HTTP.newBuilder().addInterceptor(interceptor).build();}
        };
    }
    Response response(Request request,String code){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"Response\":{\"SendStatusSet\":[{\"Code\":\""+code+"\",\"SerialNo\":\"serial-test\"}],\"RequestId\":\"request-test\"}}",MediaType.get("application/json"))).build();}
    SmsGateway gateway(String secret,String staff,String params){return new SmsGateway(secret,secret,"app","签名",staff,"customer","ap-guangzhou",params,"storeName,time,status");}
    @Test void incompleteConfigurationCannotInitiateASend(){
        var gateway=gateway("","","storeName,time,eventLabel");assertFalse(gateway.configured());
        assertTrue(gateway.issue(true).contains("待配置"));assertEquals("BLOCKED",gateway.send(true,"13900002001",Map.of(),"test").state());
    }
    @Test void templateParametersAndPhoneAreCheckedBeforeNetwork(){
        assertTrue(gateway("test","","storeName,time").issue(true).contains("模板"));
        assertTrue(gateway("test","staff","unknownField").issue(true).contains("参数"));
        var valid=gateway("test","staff","storeName,time,eventLabel");assertEquals("",valid.issue(true));
        assertEquals("InvalidPhone",valid.send(true,"invalid",Map.of(),"test").code());
    }
    @Test void officialSdkUsesConfiguredTemplatePhoneAndParameterOrder(){
        AtomicReference<String> body=new AtomicReference<>();
        var gateway=intercepted(chain->{var request=chain.request();var buffer=new Buffer();request.body().writeTo(buffer);body.set(buffer.readUtf8());return response(request,"Ok");});
        var result=gateway.send(true,"13900002001",Map.of("storeName","余姚路店","time","明天 10:00","eventLabel","新预约申请"),"notification-test");
        assertEquals("ACCEPTED",result.state());assertEquals("serial-test",result.messageId());
        assertTrue(body.get().contains("+8613900002001"));assertTrue(body.get().contains("\"TemplateId\":\"staff\""));
        assertTrue(body.get().contains("\"TemplateParamSet\":[\"余姚路店\",\"明天 10:00\",\"新预约申请\"]"));
    }
    @Test void explicitProviderRejectionsAndThrottlingAreDistinguished(){
        assertEquals("RETRY",intercepted(chain->response(chain.request(),"LimitExceeded.PhoneNumberThirtySecondLimit")).send(true,"13900002001",Map.of(),"test").state());
        assertEquals("BLOCKED",intercepted(chain->response(chain.request(),"InvalidParameterValue.TemplateIdNotExist")).send(true,"13900002001",Map.of(),"test").state());
    }
    @Test void transportFailureLeavesSmsForVerification(){
        var gateway=intercepted(chain->{throw new IOException("simulated timeout");});
        assertFalse(gateway.httpClient().retryOnConnectionFailure());assertEquals("UNKNOWN",gateway.send(true,"13900002001",Map.of(),"test").state());
    }
}
