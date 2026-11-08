package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WechatMenuGatewayTest {
    MockRestServiceServer server;WechatMenuGateway gateway;
    static final String READ="https://api.weixin.qq.com/cgi-bin/menu/get?access_token=fixture-menu-token";
    static final String PUBLISH="https://api.weixin.qq.com/cgi-bin/menu/create?access_token=fixture-menu-token";
    @BeforeEach void setup(){
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        gateway=new WechatMenuGateway(new WechatGateway("fixture-mini","fixture-mini-secret","fixture-service","fixture-service-secret","","","","",new ObjectMapper(),builder.build()));
    }
    void token(){server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
        .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"grant_type\":\"client_credential\",\"appid\":\"fixture-service\",\"secret\":\"fixture-service-secret\",\"force_refresh\":false}"))
        .andRespond(withSuccess("{\"access_token\":\"fixture-menu-token\",\"expires_in\":7200}",MediaType.TEXT_PLAIN));}
    @Test void usesServiceStableTokenCachesItAndKeepsConditionalMenuMetadata(){
        token();for(int i=0;i<2;i++)server.expect(requestTo(READ)).andRespond(withSuccess("{\"menu\":{\"button\":[]},\"conditionalmenu\":[{\"menuid\":1}]}",MediaType.TEXT_PLAIN));
        for(int i=0;i<2;i++){var result=gateway.read();assertEquals(1,result.get("conditionalCount"));assertEquals(true,result.get("exists"));}
        server.verify();
    }
    @Test void absentMenuCanBeBackedUpBeforeTheFirstPublication(){
        token();server.expect(requestTo(READ)).andRespond(withSuccess("{\"errcode\":46003,\"errmsg\":\"menu no exist\"}",MediaType.APPLICATION_JSON));
        var result=gateway.read();assertEquals(false,result.get("exists"));assertEquals(Map.of("button",List.of()),result.get("menu"));server.verify();
    }
    @Test void publishesUtf8BodyOnlyAfterExplicitSuccessAndDoesNotTouchMessageToken(){
        token();var menu=Map.<String,Object>of("button",List.of(Map.of("name","在线预约","type","miniprogram","appid","wxce054e18303efb8a","pagepath","pages/booking/index","url","https://www.yuhetangtcm.com")));
        server.expect(requestTo(PUBLISH)).andExpect(method(HttpMethod.POST)).andExpect(content().json(new Data(null,new ObjectMapper()).stringify(menu)))
            .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}",MediaType.TEXT_PLAIN));
        gateway.publish(menu);server.verify();
    }
    @Test void permissionFailureKeepsTheErrorCodeWithoutReturningRawProviderDetails(){
        token();server.expect(requestTo(PUBLISH)).andRespond(withSuccess("{\"errcode\":48001,\"errmsg\":\"fixture-menu-token private provider details\"}",MediaType.TEXT_PLAIN));
        var failure=assertThrows(ResponseStatusException.class,()->gateway.publish(Map.of("button",List.of())));
        assertTrue(failure.getReason().contains("48001"));assertTrue(failure.getReason().contains("权限"));assertFalse(failure.toString().contains("fixture-menu-token"));assertFalse(failure.toString().contains("private provider"));server.verify();
    }
    @Test void tokenWhitelistFailureRefersToServiceAccountAndDoesNotAttemptPublish(){
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token")).andRespond(withSuccess("{\"errcode\":40164,\"errmsg\":\"fixture-service-secret private details\"}",MediaType.TEXT_PLAIN));
        var failure=assertThrows(ResponseStatusException.class,()->gateway.publish(Map.of("button",List.of())));
        assertTrue(failure.getReason().contains("服务号 API IP 白名单"));assertFalse(failure.toString().contains("fixture-service-secret"));server.verify();
    }
    @Test void timeoutAfterSubmissionIsUncertainAndIsNeverRetried(){
        token();server.expect(requestTo(PUBLISH)).andRespond(request->{throw new ResourceAccessException(PUBLISH,new java.net.http.HttpTimeoutException("private token"));});
        var failure=assertThrows(ResponseStatusException.class,()->gateway.publish(Map.of("button",List.of())));
        assertTrue(failure.getReason().contains("发布结果尚未确认"));assertTrue(failure.getReason().contains("读取微信菜单"));assertFalse(failure.toString().contains("fixture-menu-token"));server.verify();
    }
    @Test void missingSuccessCodeAndInvalidRemoteStructureAreNotSuccessful(){
        token();server.expect(requestTo(PUBLISH)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(requestTo(READ)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThrows(ResponseStatusException.class,()->gateway.publish(Map.of("button",List.of())));assertThrows(ResponseStatusException.class,()->gateway.read());server.verify();
    }
    @Test void missingServiceCredentialsCannotUseTheMiniProgramSecret(){
        var missing=new WechatMenuGateway(new WechatGateway("mini","mini-secret","","","","","","",new ObjectMapper(),RestClient.builder().build()));
        assertEquals(409,assertThrows(ResponseStatusException.class,missing::read).getStatusCode().value());
    }
}
