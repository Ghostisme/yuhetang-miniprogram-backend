package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WechatGatewayTest {
    MockRestServiceServer server;
    WechatGateway gateway;
    final String tokenUrl="https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=fixture-app&secret=fixture-secret";
    final String sendUrl="https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=fixture-token";

    @BeforeEach void setup(){
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        gateway=new WechatGateway("fixture-app","fixture-secret","","","","","","",new ObjectMapper(),builder.build());
    }
    void token(){server.expect(requestTo(tokenUrl)).andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"access_token\":\"fixture-token\",\"expires_in\":7200}",MediaType.TEXT_PLAIN));}

    void expectMediaUpload(String type,String media){
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/media/upload?access_token=fixture-token&type="+type)).andExpect(method(HttpMethod.POST))
            .andExpect(request->{
                var mock=(org.springframework.mock.http.client.MockClientHttpRequest)request;
                assertEquals(mock.getBodyAsBytes().length,request.getHeaders().getContentLength());
                assertFalse(request.getHeaders().containsKey("Transfer-Encoding"));
                assertTrue(request.getHeaders().getContentType().toString().startsWith("multipart/form-data;boundary="));
                assertTrue(mock.getBodyAsString().contains("name=\"media\""));
            }).andRespond(withSuccess("{\""+(type.equals("thumb")?"thumb_media_id":"media_id")+"\":\""+media+"\"}",MediaType.TEXT_PLAIN));
    }
    @Test void customerVideoDownloadAcceptsBinaryMediaAndRejectsWechatErrors(){
        token();String url="https://api.weixin.qq.com/cgi-bin/media/get?access_token=fixture-token&media_id=fixture-video";
        server.expect(requestTo(url)).andRespond(withSuccess(new byte[]{1,2,3},MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"errcode\":40007}",MediaType.APPLICATION_JSON));
        assertArrayEquals(new byte[]{1,2,3},gateway.customerVideo("fixture-video"));
        var error=assertThrows(ResponseStatusException.class,()->gateway.customerVideo("fixture-video"));assertEquals(410,error.getStatusCode().value());server.verify();
    }
    @Test void customerImageUploadAndSendUseFixedLengthBodies(){
        token();expectMediaUpload("image","image-media");
        server.expect(requestTo(sendUrl)).andExpect(content().json("{\"touser\":\"customer\",\"msgtype\":\"image\",\"image\":{\"media_id\":\"image-media\"}}"))
            .andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        assertEquals(0,gateway.sendCustomerMedia("customer",new ChatAttachments.Upload("image","介绍.png","image/png","png",new byte[]{1,2,3},null,"sha")).get("errcode"));server.verify();
    }
    @Test void miniProgramVideoUsesASupportedLinkCardWithFixedLength(){
        token();var card=Map.of("title","客服发来的视频","description","点击播放","url","https://fixture.example/api/chat-media/fixture/watch?ticket=signed","thumb_url","https://fixture.example/api/chat-media/fixture/thumbnail?ticket=signed");
        server.expect(requestTo(sendUrl)).andExpect(content().json("{\"touser\":\"customer\",\"msgtype\":\"link\",\"link\":{\"title\":\"客服发来的视频\",\"description\":\"点击播放\",\"url\":\"https://fixture.example/api/chat-media/fixture/watch?ticket=signed\",\"thumb_url\":\"https://fixture.example/api/chat-media/fixture/thumbnail?ticket=signed\"}}"))
            .andExpect(request->{var mock=(org.springframework.mock.http.client.MockClientHttpRequest)request;assertEquals(mock.getBodyAsBytes().length,request.getHeaders().getContentLength());assertFalse(request.getHeaders().containsKey("Transfer-Encoding"));})
            .andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        assertEquals(0,gateway.sendCustomerVideoCard("customer",card).get("errcode"));server.verify();
    }
    @Test void unsupportedNativeVideoIsRejectedBeforeAnyWechatRequest(){
        assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerMedia("customer",new ChatAttachments.Upload("video","介绍.mp4","video/mp4","mp4",new byte[]{1},new byte[]{2},"sha")));server.verify();
    }
    @Test void rejectedMediaUploadDoesNotSendACustomerMessage(){
        token();server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/media/upload?access_token=fixture-token&type=image"))
            .andRespond(withSuccess("{\"errcode\":40004,\"errmsg\":\"private diagnostics\"}",MediaType.APPLICATION_JSON));
        var failure=assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerMedia("customer",new ChatAttachments.Upload("image","介绍.png","image/png","png",new byte[]{1},null,"sha")));
        assertTrue(failure.getMessage().contains("40004"));assertFalse(failure.getMessage().contains("fixture-token"));assertFalse(failure.getMessage().contains("private diagnostics"));server.verify();
    }
    @Test void phoneAuthorizationUsesTheCodeAndFixedLengthBodyWithMiniProgramCredentials(){
        token();server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=fixture-token"))
            .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"code\":\"one-use-code\"}"))
            .andExpect(header("Content-Length","23"))
            .andRespond(withSuccess("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13900003001\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"fixture-app\"}}}",MediaType.TEXT_PLAIN));
        assertEquals("13900003001",gateway.phoneNumber("one-use-code"));server.verify();
    }
    @Test void phoneAuthorizationReportsPermissionFailureWithoutEchoingSecretsOrProviderBody(){
        token();server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=fixture-token"))
            .andRespond(withSuccess("{\"errcode\":48001,\"errmsg\":\"fixture-token private diagnostics\"}",MediaType.APPLICATION_JSON));
        var failure=assertThrows(ResponseStatusException.class,()->gateway.phoneNumber("one-use-code"));
        assertTrue(failure.getReason().contains("48001"));assertTrue(failure.getReason().contains("手机号接口权限"));
        assertFalse(failure.getReason().contains("fixture-token"));assertFalse(failure.getReason().contains("private diagnostics"));server.verify();
    }
    @Test void phoneAuthorizationRejectsAnotherAppsWatermark(){
        token();server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=fixture-token"))
            .andRespond(withSuccess("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13900003001\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"another-app\"}}}",MediaType.APPLICATION_JSON));
        assertThrows(ResponseStatusException.class,()->gateway.phoneNumber("one-use-code"));server.verify();
    }

    @Test void acceptsPlainTextJsonForBothTokenAndCustomerSendAndCachesToken(){
        token();
        for(int i=0;i<2;i++)server.expect(requestTo(sendUrl)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"touser\":\"fixture-openid\",\"msgtype\":\"text\",\"text\":{\"content\":\"您好，门店已收到咨询\"}}"))
            .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}",MediaType.TEXT_PLAIN));
        for(int i=0;i<2;i++)assertEquals(0,gateway.sendCustomerText("fixture-openid","您好，门店已收到咨询").get("errcode"));
        server.verify();
    }
    @Test void acceptsJsonWhenResponseHasNoContentType(){
        server.expect(requestTo("https://fixture.invalid/json")).andRespond(withSuccess().body("{\"errcode\":0}"));
        assertEquals(0,gateway.post("https://fixture.invalid/json",Map.of()).get("errcode"));server.verify();
    }
    @Test void staffNoticeUsesItsOwnAccountAndLinksToTheExactAppointment(){
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        var staff=new WechatGateway("fixture-app","fixture-secret","staff-app","staff-secret","","",StaffAppointmentTemplate.ID,"https://mp-admin.hetuonian.cn/",new ObjectMapper(),builder.build());
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=staff-app&secret=staff-secret"))
            .andRespond(withSuccess("{\"access_token\":\"staff-token\",\"expires_in\":7200}",MediaType.APPLICATION_JSON));
        var fields=Map.of("thing10",Map.of("value","上海中心店"),"thing14",Map.of("value","舒缓养护"),"time12",Map.of("value","2026-11-10 15:00"),"thing16",Map.of("value","测试客户"),"phone_number9",Map.of("value","13900003002"));
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=staff-token"))
            .andExpect(method(HttpMethod.POST)).andExpect(content().json(new ObjectMapper().valueToTree(Map.of(
                "touser","bound-manager","template_id",StaffAppointmentTemplate.ID,"data",fields,
                "url","https://mp-admin.hetuonian.cn/admin?appointment=YHT-ABC123")).toString(),true))
            .andExpect(request->assertTrue(request.getHeaders().getContentLength()>0,"WeChat requires a fixed body length"))
            .andRespond(withSuccess("{\"errcode\":0,\"msgid\":123}",MediaType.APPLICATION_JSON));
        assertEquals(0,staff.send(true,"bound-manager",StaffAppointmentTemplate.ID,new java.util.LinkedHashMap<>(fields),"YHT-ABC123").get("errcode"));server.verify();
    }
    @Test void tokenRejectionDoesNotAttemptCustomerSendOrExposeProviderDetails(){
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess("{\"errcode\":40164,\"errmsg\":\"fixture-secret and raw provider diagnostics\"}",MediaType.TEXT_PLAIN));
        var failure=assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerText("fixture-openid","您好"));
        assertTrue(failure.getMessage().contains("40164"));assertTrue(failure.getMessage().contains("IP 白名单"));
        assertFalse(failure.getMessage().contains("fixture-secret"));assertFalse(failure.getMessage().contains("raw provider"));server.verify();
    }
    @Test void subscriptionTokenFailureIsKnownToOccurBeforeSubmission(){
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess("{\"errcode\":40164,\"errmsg\":\"fixture-secret private details\"}",MediaType.TEXT_PLAIN));
        var failure=assertThrows(WechatGateway.NotificationNotAttemptedException.class,()->gateway.send(false,"fixture-openid","fixture-template",Map.of(),"fixture-booking"));
        assertTrue(failure.getMessage().contains("尚未提交"));assertFalse(failure.getMessage().contains("fixture-secret"));server.verify();
    }
    @Test void customerTemplateInspectionUsesTheMiniProgramAccount(){
        token();server.expect(requestTo("https://api.weixin.qq.com/wxaapi/newtmpl/gettemplate?access_token=fixture-token")).andRespond(withSuccess("{\"errcode\":0,\"data\":[{\"priTmplId\":\"fixture-template\",\"content\":\"{{thing1.DATA}}\"}]}",MediaType.TEXT_PLAIN));
        assertEquals("fixture-template",gateway.notificationTemplates(false).get(0).get("priTmplId"));server.verify();
    }
    @Test void missingCredentialsDoNotAttemptSend(){
        var missing=new WechatGateway("fixture-app","","","","","","","",new ObjectMapper(),RestClient.builder().build());
        var failure=assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->missing.sendCustomerText("fixture-openid","您好"));
        assertTrue(failure.getMessage().contains("AppSecret"));
    }
    @Test void tokenTransportFailureIsClassifiedBeforeSend(){
        server.expect(requestTo(tokenUrl)).andRespond(request->{throw new ResourceAccessException("fixture interrupted");});
        assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerText("fixture-openid","您好"));server.verify();
    }
    @Test void actualSendTransportFailureIsNotClassifiedAsNotAttemptedOrRetried(){
        token();server.expect(requestTo(sendUrl)).andRespond(request->{throw new ResourceAccessException("fixture interrupted");});
        assertThrows(ResponseStatusException.class,()->gateway.sendCustomerText("fixture-openid","您好"));server.verify();
    }
    @Test void sendTimeoutPreservesItsCategoryWithoutLeakingTheTokenOrMessage(){
        token();server.expect(requestTo(sendUrl)).andRespond(request->{throw new ResourceAccessException(sendUrl,new java.net.http.HttpTimeoutException("fixture-token private details"));});
        var failure=assertThrows(WechatGateway.ExchangeException.class,()->gateway.sendCustomerText("fixture-openid","private customer text"));
        assertEquals("READ_TIMEOUT",failure.category);assertTrue(failure.getReason().contains("响应超时"));
        for(String secret:new String[]{"fixture-token","fixture-openid","private customer text","private details","access_token"})assertFalse(failure.toString().contains(secret));
        server.verify();
    }
    @Test void sendHttpAndMalformedResponseFailuresRetainSafeActionableCategories(){
        token();server.expect(requestTo(sendUrl)).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY).body("private provider details"));
        server.expect(requestTo(sendUrl)).andRespond(withSuccess("<html>private provider details</html>",MediaType.TEXT_PLAIN));
        server.expect(requestTo(sendUrl)).andRespond(withSuccess("",MediaType.TEXT_PLAIN));
        for(String expected:new String[]{"HTTP_502","INVALID_RESPONSE","EMPTY_RESPONSE"}){
            var failure=assertThrows(WechatGateway.ExchangeException.class,()->gateway.sendCustomerText("fixture-openid","private customer text"));
            assertEquals(expected,failure.category);assertFalse(failure.toString().contains("private"));assertFalse(failure.toString().contains("fixture-token"));
        }
        server.verify();
    }
    @Test void networkFailureCategoriesDistinguishDnsConnectionAndTls(){
        var causes=java.util.List.of(new java.net.UnknownHostException("private hostname"),new java.net.ConnectException("private host"),new javax.net.ssl.SSLHandshakeException("private certificate"),new java.net.http.HttpConnectTimeoutException("private timeout"));
        var categories=java.util.List.of("DNS_FAILURE","CONNECT_FAILURE","TLS_FAILURE","CONNECT_TIMEOUT");
        for(int i=0;i<causes.size();i++){
            var failure=WechatGateway.transportFailure(new ResourceAccessException("private URL",causes.get(i)));
            assertEquals(categories.get(i),failure.category);assertFalse(failure.toString().contains("private"));
        }
    }
    @Test void emptyMalformedAndNonObjectResponsesAreNotReportedAsSuccess(){
        for(String body:new String[]{"","<html>unavailable</html>","[]","null"}){
            server.expect(requestTo("https://fixture.invalid/json")).andRespond(withSuccess(body,MediaType.TEXT_PLAIN));
        }
        for(int i=0;i<4;i++){
            assertThrows(ResponseStatusException.class,()->gateway.post("https://fixture.invalid/json",Map.of()));
        }
        server.verify();
    }
    @Test void blankAccessTokenDoesNotAttemptSend(){
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess("{\"access_token\":\"\"}",MediaType.APPLICATION_JSON));
        assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerText("fixture-openid","您好"));server.verify();
    }
    @Test void sendsUtf8JsonWithFixedLengthToEndpointsThatRejectChunkedRequests()throws Exception{
        var headers=new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
        var received=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var local=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        local.createContext("/custom",exchange->{
            try{
                headers.set(exchange.getRequestHeaders());received.set(exchange.getRequestBody().readAllBytes());
                if(exchange.getRequestHeaders().containsKey("Transfer-Encoding"))exchange.sendResponseHeaders(412,-1);
                else{
                    byte[] response="{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
                }
            }finally{exchange.close();}
        });
        local.start();
        try{
            var mapper=new ObjectMapper();
            var wire=new WechatGateway("fixture-app","fixture-secret","","","","","","",mapper,
                RestClient.builder().requestFactory(WechatGateway.requestFactory()).build());
            var payload=Map.<String,Object>of("touser","fixture-openid","msgtype","text","text",Map.of("content","您好，裕和堂已收到咨询🌿\n回复：\"已确认\""));
            var result=wire.post("http://127.0.0.1:"+local.getAddress().getPort()+"/custom",payload);
            assertEquals(0,result.get("errcode"));
            assertNull(headers.get().getFirst("Transfer-Encoding"));
            assertEquals(received.get().length,Integer.parseInt(headers.get().getFirst("Content-Length")));
            assertEquals(mapper.valueToTree(payload),mapper.readTree(received.get()));
            assertEquals("application/json",headers.get().getFirst("Content-Type"));
        }finally{local.stop(0);}
    }
}
