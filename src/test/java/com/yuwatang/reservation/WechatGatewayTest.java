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
    @Test void tokenRejectionDoesNotAttemptCustomerSendOrExposeProviderDetails(){
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess("{\"errcode\":40164,\"errmsg\":\"fixture-secret and raw provider diagnostics\"}",MediaType.TEXT_PLAIN));
        var failure=assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->gateway.sendCustomerText("fixture-openid","您好"));
        assertTrue(failure.getMessage().contains("40164"));assertTrue(failure.getMessage().contains("IP 白名单"));
        assertFalse(failure.getMessage().contains("fixture-secret"));assertFalse(failure.getMessage().contains("raw provider"));server.verify();
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
}
