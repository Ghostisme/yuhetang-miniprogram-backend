package com.yuwatang.reservation;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.yuwatang.reservation.Data.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_launch_mode;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "app.mode=local",
    "app.data-dir=target/isolated-launch-test", "app.public-url=https://fixture.example"
})
@AutoConfigureMockMvc
class LaunchModeIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth; @Autowired WebSecurity security;
    @MockitoBean WechatGateway wx;
    String token(String id) { return auth.token(id, "ADMIN", integer(d.one("SELECT token_version FROM app_user WHERE id=?", id), "tokenVersion", 0)); }
    @BeforeEach void reset() { d.update("DELETE FROM app_setting WHERE setting_key=?", LaunchMode.KEY); d.update("DELETE FROM audit_log WHERE action='miniprogram.launch-mode'"); security.limits.clear(); clearInvocations(wx); }
    Map<String,Object> call(MockHttpServletRequestBuilder request, String token, Object input, int status) throws Exception {
        if(token!=null)request.header("Authorization", "Bearer "+token);
        if(input!=null)request.contentType("application/json").content(d.stringify(input));
        var result=mvc.perform(request).andReturn().getResponse();
        String body=result.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, result.getStatus(), body);
        assertEquals("no-store", result.getHeader("Cache-Control"));
        return d.object(body);
    }
    Map<String,Object> configure(boolean enabled) throws Exception {
        var input=call(get("/api/admin/launch-mode"), token("hq"), null, 200);
        input.put("enabled", enabled);
        return call(put("/api/admin/launch-mode"), token("hq"), input, 200);
    }
    @Test void defaultIsCompatibleAndPublicResponseContainsNoPrivateSettings() throws Exception {
        var status=call(get("/api/launch-status"), null, null, 200);
        assertEquals(false, status.get("enabled")); assertEquals(0, status.get("revision"));
        assertEquals(Set.of("enabled","revision","title","message","updatedAt"), status.keySet());
        assertEquals(Set.of("zh","en","ja"), d.object(status.get("message")).keySet());
    }
    @Test void customerMutationsAreRejectedBeforeAnyBusinessOrWechatCall() throws Exception {
        configure(true);
        var before=new LinkedHashMap<String,Integer>();
        for(String table:List.of("appointment","chat_conversation","chat_message","customer_account","subscription_grant","notification_outbox")) before.put(table,d.count("SELECT COUNT(*) FROM "+table));
        for(String path:List.of("/api/auth/wechat","/api/auth/dev-customer","/api/appointments","/api/me/chat/entry","/api/me/chats","/api/me/chats/example/messages","/api/me/subscriptions","/api/me/profile/phone","/api/me/appointments/example/payment/start","/api/me/wallet-service/code")) {
            var result=call(post(path), null, Map.of("code","fake-test"),423);
            assertEquals("MINIPROGRAM_UNDER_CONSTRUCTION",result.get("code"));
            assertEquals(true, d.object(result.get("launchStatus")).get("enabled"));
        }
        call(patch("/api/me/appointments/example"), null, Map.of("action","cancel"),423);
        call(put("/api/me/profile"), null, Map.of("name","test"),423);
        call(delete("/api/me/wallet-service"), null, null,423);
        for(var row:before.entrySet())assertEquals(row.getValue(),d.count("SELECT COUNT(*) FROM "+row.getKey()),row.getKey());
        verifyNoInteractions(wx);
    }
    @Test void catalogHealthAdminAndExistingCustomerReadsRemainAvailable() throws Exception {
        configure(true);
        assertEquals("ok", call(get("/api/health"),null,null,200).get("status"));
        var site=mvc.perform(get("/api/site")).andReturn().getResponse();assertEquals(200,site.getStatus());
        call(get("/api/admin/launch-mode"),token("hq"),null,200);
        String customer=str(auth.customerSession("launch-read-customer",true),"token");
        assertEquals(200,mvc.perform(get("/api/me/appointments").header("Authorization","Bearer "+customer)).andReturn().getResponse().getStatus());
        // Invalid signed callbacks still reach signature validation rather than the customer gate.
        var callback=mvc.perform(post("/api/wechat/customer-service?timestamp=1&nonce=fixture").contentType("text/plain").content("<xml/>" )).andReturn().getResponse();
        assertNotEquals(423, callback.getStatus());
    }
    @Test void disablingImmediatelyRestoresCustomerOperationsWithoutADeploy() throws Exception {
        configure(true);var enabled=call(get("/api/launch-status"),null,null,200);assertEquals(1,enabled.get("revision"));
        configure(false);
        when(wx.login("fixture-code")).thenReturn("launch-login-"+id());
        assertTrue(call(post("/api/auth/wechat"),null,Map.of("code","fixture-code"),200).containsKey("token"));
        assertEquals(false,call(get("/api/launch-status"),null,null,200).get("enabled"));
        assertEquals(2,d.count("SELECT COUNT(*) FROM audit_log WHERE action='miniprogram.launch-mode' AND detail LIKE '%模式%' AND target_id='brand'"));
    }
    @Test void authorizationRevisionAndTextValidationProtectTheSwitch() throws Exception {
        var initial=LaunchMode.defaults(); initial.put("enabled",true);
        call(put("/api/admin/launch-mode"),null,initial,401);
        call(put("/api/admin/launch-mode"),token("yuyao-manager"),initial,403);
        call(get("/api/admin/launch-mode"),token("yuyao-manager"),null,403);
        var bad=new LinkedHashMap<>(initial);bad.put("title",Map.of("zh","缺少语言"));
        call(put("/api/admin/launch-mode"),token("hq"),bad,400);
        assertEquals(0,d.count("SELECT COUNT(*) FROM app_setting WHERE setting_key=?",LaunchMode.KEY));
        call(put("/api/admin/launch-mode"),token("hq"),initial,200);
        call(put("/api/admin/launch-mode"),token("hq"),initial,409);
        assertEquals(true,call(get("/api/launch-status"),null,null,200).get("enabled"));
    }
    @Test void noticeTextUsesTheCustomersSelectedLanguage() throws Exception {
        var saved=configure(true);
        for(String locale:List.of("zh-CN","en-US","ja-JP")) {
            var result=call(post("/api/appointments").header("Accept-Language",locale),null,Map.of(),423);
            assertEquals(d.object(saved.get("message")).get(locale.substring(0,2)), result.get("message"));
        }
    }
}
