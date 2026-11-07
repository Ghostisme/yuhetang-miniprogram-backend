package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_store_chat_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "app.mode=local","app.data-dir=target/isolated-chat-test-data",
    "app.jwt-secret=isolated-chat-tests-only-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=wx-chat-test","app.wechat.secret=",
    "app.wechat.message-token=isolated-callback-token",
    "app.wechat.message-aes-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    "app.wechat.message-allow-plaintext=false",
    "app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreChatIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired CatalogService catalog; @Autowired ChatService chat;
    @Autowired WechatMessageCodec codec; @Autowired WebSecurity security;
    @MockitoBean WechatGateway wx;
    String customer, customerToken, otherCustomerToken, openid, hq, manager, otherStore;
    String published; List<Map<String,Object>> stores;

    @BeforeAll void baseline(){
        published=str(d.one("SELECT published_json FROM site_config WHERE id='brand'"),"publishedJson");
        stores=d.rows("SELECT id,profile_json,enabled FROM store");
    }
    @BeforeEach void reset(){
        for(String table:List.of("chat_route","chat_message","chat_receipt","chat_entry","chat_conversation","store_service",
                "notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile","subscription_grant"))d.update("DELETE FROM "+table);
        d.update("UPDATE site_config SET published_json=? WHERE id='brand'",published);
        for(var s:stores)d.update("UPDATE store SET profile_json=?,enabled=?,rules_json=? WHERE id=?",s.get("profileJson"),s.get("enabled"),d.stringify(Bootstrap.defaultRules()),s.get("id"));
        security.limits.clear();
        openid="chat-test-"+id();customerToken=str(auth.customerSession(openid,true),"token");
        customer=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
        otherCustomerToken=str(auth.customerSession("another-"+id(),true),"token");
        hq=adminToken("hq");manager=adminToken("yuyao-manager");otherStore=adminToken("bai-manager");
        when(wx.sendCustomerText(anyString(),anyString())).thenReturn(Map.of("errcode",0));
    }
    String adminToken(String uid){return auth.token(uid,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",uid),"tokenVersion",0));}
    ResultActions send(MockHttpServletRequestBuilder request,String token,Object body)throws Exception{
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(d.stringify(body));
        return mvc.perform(request);
    }
    String response(ResultActions result,int status)throws Exception{
        var r=result.andReturn().getResponse();String text=r.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status,r.getStatus(),text);return text;
    }
    Map<String,Object> json(ResultActions result,int status)throws Exception{return d.object(response(result,status));}
    List<Map<String,Object>> array(ResultActions result)throws Exception{return d.json.readValue(response(result,200),new com.fasterxml.jackson.core.type.TypeReference<>(){});}
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    Map<String,Object> project(String name){return new LinkedHashMap<>(Map.of("name",name,"duration","45分钟","description","门店独立养护介绍","enabled",true,"image","/media/ritual.webp","steps",List.of("沟通","养护"),"sortOrder",1));}
    Map<String,Object> createProject()throws Exception{return json(send(post("/api/admin/stores/yuyao/services"),manager,project("余姚专属项目")),200);}
    Map<String,Object> booking(String store,String service,String time){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("storeId",store),Map.entry("serviceId",service),Map.entry("date",LocalDate.now(ZONE).plusDays(1).toString()),
        Map.entry("startTime",time),Map.entry("headcount",1),Map.entry("name","品项测试客户"),Map.entry("phone","13900009001"),
        Map.entry("therapistIntentId",""),Map.entry("memberClaim","UNKNOWN"),Map.entry("visitedClaim","NO"),Map.entry("privacyConsent",true)));
    }
    String web(String store)throws Exception{return str(json(send(post("/api/me/chats"),customerToken,Map.of("storeId",store)),200),"id");}
    Map<String,Object> message(String text,String key){return Map.of("content",text,"clientKey",key);}
    Map<String,Object> webSend(String cid,String text,String key)throws Exception{return json(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message(text,key)),200);}
    Map<String,Object> reply(String cid,String text,String key)throws Exception{return json(send(post("/api/admin/chats/"+cid+"/messages"),manager,message(text,key)),200);}
    Map<String,Object> conversation(String cid){return d.one("SELECT * FROM chat_conversation WHERE id=?",cid);}
    long epoch(){return Instant.now().getEpochSecond();}
    Map<String,Object> inbound(String type,long time){return new LinkedHashMap<>(Map.of("ToUserName","mini-program","FromUserName",openid,"CreateTime",time,"MsgType",type));}
    String entry(String store)throws Exception{return str(json(send(post("/api/me/chat/entry"),customerToken,Map.of("storeId",store)),200),"sessionFrom");}
    String nativeEntry(String store,long time)throws Exception{
        var event=inbound("event",time);event.put("Event","user_enter_tempsession");event.put("SessionFrom",entry(store));callback(event,200);
        return str(d.one("SELECT conversation_id FROM chat_route WHERE openid=?",openid),"conversationId");
    }
    void nativeText(String content,long time,String msgId)throws Exception{var in=inbound("text",time);in.put("Content",content);in.put("MsgId",msgId);callback(in,200);}
    String directNativeEntry(String source,long time)throws Exception{
        var event=inbound("event",time);event.put("Event","user_enter_tempsession");event.put("SessionFrom",source);callback(event,200);
        return str(d.one("SELECT conversation_id FROM chat_route WHERE openid=?",openid),"conversationId");
    }
    String signature(String timestamp,String nonce,String encrypted)throws Exception{
        var values=new ArrayList<>(List.of("isolated-callback-token",timestamp,nonce));if(encrypted!=null)values.add(encrypted);Collections.sort(values);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("",values).getBytes(StandardCharsets.UTF_8)));
    }
    String encrypt(String payload,String appId)throws Exception{
        byte[] key=Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),body=payload.getBytes(StandardCharsets.UTF_8),app=appId.getBytes(StandardCharsets.UTF_8);
        int length=20+body.length+app.length,padding=32-length%32;
        byte[] plain=new byte[length+padding];ByteBuffer.wrap(plain).position(16).putInt(body.length).put(body).put(app);Arrays.fill(plain,length,plain.length,(byte)padding);
        var cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain));
    }
    void callback(Map<String,Object> payload,int status)throws Exception{
        String timestamp=Long.toString(epoch()),nonce=id(),encrypted=encrypt(d.stringify(payload),"wx-chat-test");
        String result=response(mvc.perform(post("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce)
            .param("msg_signature",signature(timestamp,nonce,encrypted)).contentType("application/xml")
            .content("<xml><Encrypt><![CDATA["+encrypted+"]]></Encrypt></xml>")),status);
        if(status==200)assertEquals("success",result);
    }

    @Test void branchOverridesStayLocalAndRejectStaleOrForeignEdits()throws Exception{
        var base=catalog.storeServices("yuyao",false).stream().filter(s->"moxa".equals(s.get("id"))).findFirst().orElseThrow();
        String original=str(base,"name");base.put("name","余姚艾灸专属方案");base.put("duration","75分钟");
        var saved=json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,base),200);assertEquals(1,integer(saved,"version",0));
        assertEquals("余姚艾灸专属方案",catalog.storeService("yuyao","moxa").get("name"));assertEquals(original,catalog.storeService("bai-le-men","moxa").get("name"));
        json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,base),409);
        json(send(put("/api/admin/stores/yuyao/services/moxa"),otherStore,saved),404);
        response(send(get("/api/admin/stores/yuyao/services"),otherStore,null),404);
        assertTrue(d.stringify(catalog.site()).contains("余姚艾灸专属方案"));
        assertFalse(d.stringify(array(send(get("/api/stores/yuyao/services"),null,null))).contains("\"version\""));
    }

    @Test void originalContentFormSavesLocalEditsAndNewItemsTogether()throws Exception{
        var base=catalog.storeServices("yuyao",false).stream().filter(s->"moxa".equals(s.get("id"))).findFirst().orElseThrow();
        String original=str(base,"name");base.put("name","原版表单本店艾灸");base.put("duration","75分钟");
        var result=array(send(put("/api/admin/stores/yuyao/services"),manager,Map.of("items",List.of(base,project("原版表单本店新增")))));
        var created=result.stream().filter(s->"原版表单本店新增".equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals("STORE",created.get("source"));
        assertEquals("原版表单本店艾灸",catalog.storeService("yuyao","moxa").get("name"));
        assertEquals("75分钟",catalog.storeService("yuyao","moxa").get("duration"));
        assertEquals(original,catalog.storeService("bai-le-men","moxa").get("name"));
        assertTrue(catalog.storeServices("bai-le-men",false).stream().noneMatch(s->created.get("id").equals(s.get("id"))));
        assertEquals(published,str(d.one("SELECT published_json FROM site_config WHERE id='brand'"),"publishedJson"));
        assertEquals("原版表单本店新增",catalog.storeService("yuyao",str(created,"id")).get("name"));
        response(send(put("/api/admin/stores/yuyao/services"),otherStore,Map.of("items",List.of())),404);
    }

    @Test void contentFormValidationRollsBackTheWholeSave()throws Exception{
        var base=catalog.storeServices("yuyao",false).stream().filter(s->"moxa".equals(s.get("id"))).findFirst().orElseThrow();
        String original=str(base,"name");base.put("name","不应部分保存");
        response(send(put("/api/admin/stores/yuyao/services"),manager,Map.of("items",List.of(base,project("")))),400);
        assertEquals(0,d.count("SELECT COUNT(*) FROM store_service WHERE store_id='yuyao'"));
        assertEquals(original,catalog.storeService("yuyao","moxa").get("name"));
        response(send(put("/api/admin/stores/yuyao/services"),manager,Map.of("items",List.of(base,base))),400);
        assertEquals(0,d.count("SELECT COUNT(*) FROM store_service WHERE store_id='yuyao'"));
    }

    @Test void staleContentFormDoesNotOverwriteOrPartlyCreateItems()throws Exception{
        var stale=catalog.storeServices("yuyao",false).stream().filter(s->"moxa".equals(s.get("id"))).findFirst().orElseThrow();
        var newer=new LinkedHashMap<>(stale);newer.put("name","另一位同事已保存");
        json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,newer),200);
        response(send(put("/api/admin/stores/yuyao/services"),manager,Map.of("items",List.of(project("不能部分新增"),stale))),409);
        assertEquals(1,d.count("SELECT COUNT(*) FROM store_service WHERE store_id='yuyao'"));
        assertEquals("另一位同事已保存",catalog.storeService("yuyao","moxa").get("name"));
    }

    @Test void customProjectsSupportCustomerAndManualBookingsAndDisabledProjectsKeepHistory()throws Exception{
        var custom=createProject();String sid=str(custom,"id");assertEquals("STORE",custom.get("source"));
        assertTrue(catalog.storeServices("bai-le-men",true).stream().noneMatch(s->sid.equals(s.get("id"))));
        json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,booking("bai-le-men",sid,"10:00")),400);
        var b=json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,booking("yuyao",sid,"10:00")),200);
        assertEquals("余姚专属项目",b.get("serviceName"));assertEquals("PENDING",b.get("status"));
        var manual=booking("yuyao",sid,"11:00");manual.put("source","PHONE");
        assertEquals("CONFIRMED",json(send(post("/api/admin/appointments").header("Idempotency-Key",id()),manager,manual),200).get("status"));
        custom.put("name","专属项目新名称");custom.put("enabled",false);
        json(send(put("/api/admin/stores/yuyao/services/"+sid),manager,custom),200);
        json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,booking("yuyao",sid,"12:00")),400);
        assertEquals("余姚专属项目",d.one("SELECT service_name FROM appointment WHERE id=?",b.get("id")).get("serviceName"));
    }

    @Test void therapistsCanServeTheirLocalCustomProjects()throws Exception{
        String sid=str(createProject(),"id");var teacher=new LinkedHashMap<>(catalog.therapists("yuyao",false).get(0));String tid=str(teacher,"id");
        var original=d.one("SELECT profile_json FROM therapist WHERE id=?",tid);
        try{
            teacher.put("serviceIds",List.of(sid));json(send(put("/api/admin/therapists"),manager,teacher),200);
            var b=booking("yuyao",sid,"10:00");b.put("therapistIntentId",tid);
            assertEquals(tid,json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,b),200).get("therapistIntentId"));
            var other=new LinkedHashMap<>(catalog.therapists("bai-le-men",false).get(0));other.put("serviceIds",List.of(sid));
            json(send(put("/api/admin/therapists"),otherStore,other),400);
        }finally{d.update("UPDATE therapist SET profile_json=? WHERE id=?",original.get("profileJson"),tid);}
    }

    @Test void brandDisableCannotBeOverriddenAndLegacyStoreDisablesArePreserved()throws Exception{
        var profile=d.object(catalog.store("yuyao").get("profileJson"));profile.put("disabledServiceIds",List.of("moxa"));
        d.update("UPDATE store SET profile_json=? WHERE id='yuyao'",d.stringify(profile));
        assertTrue(catalog.storeServices("yuyao",true).stream().noneMatch(s->"moxa".equals(s.get("id"))));
        var base=catalog.storeServices("yuyao",false).stream().filter(s->"moxa".equals(s.get("id"))).findFirst().orElseThrow();base.put("enabled",true);
        json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,base),200);
        var brand=d.object(published);catalog.collection(brand,"services").stream().filter(s->"moxa".equals(s.get("id"))).forEach(s->s.put("enabled",false));
        d.update("UPDATE site_config SET published_json=? WHERE id='brand'",d.stringify(brand));
        assertTrue(catalog.storeServices("yuyao",true).stream().noneMatch(s->"moxa".equals(s.get("id"))));
        base.put("version",1);json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,base),409);
    }

    @Test void readOnlyStaffCanViewButCannotMaintainProjectsOrReply()throws Exception{
        String cid=web("yuyao"),token=adminToken("yuyao-frontdesk");
        d.update("DELETE FROM role_permission WHERE role_id='frontdesk' AND permission_code IN ('services:write','chat:reply')");
        try{
            array(send(get("/api/admin/stores/yuyao/services"),token,null));json(send(get("/api/admin/chats"),token,null),200);
            json(send(post("/api/admin/stores/yuyao/services"),token,project("不应创建")),403);
            json(send(put("/api/admin/stores/yuyao/services"),token,Map.of("items",List.of(project("不应批量保存")))),403);
            json(send(post("/api/admin/chats/"+cid+"/messages"),token,message("不应发出",id())),403);
            json(send(patch("/api/admin/chats/"+cid+"/status"),token,Map.of("status","RESOLVED")),403);
        }finally{for(String p:List.of("services:write","chat:reply"))d.update("INSERT INTO role_permission VALUES('frontdesk',?)",p);}
    }

    @Test void onlineMessagesArePrivateIdempotentAndVisibleToTheAssignedStore()throws Exception{
        String cid=web("yuyao");assertEquals(cid,web("yuyao"));
        var first=webSend(cid,"请问今天可以预约吗","web-request-001");
        assertEquals(first.get("id"),webSend(cid,"请问今天可以预约吗","web-request-001").get("id"));
        json(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message("更改了内容","web-request-001")),409);
        response(send(get("/api/me/chats/"+cid+"/messages"),otherCustomerToken,null),404);
        response(send(get("/api/admin/chats/"+cid+"/messages"),otherStore,null),404);
        json(send(post("/api/admin/chats/"+cid+"/messages"),otherStore,message("跨店回复",id())),404);
        assertEquals(0,integer(json(send(get("/api/admin/chats?storeId=yuyao"),otherStore,null),200),"total",-1));
        var inbox=json(send(get("/api/admin/chats"),manager,null),200);assertEquals(1,integer(inbox,"unreadTotal",0));
        var answer=reply(cid,"您好，请选择本店可约时段","admin-request-001");assertEquals("SENT",answer.get("deliveryStatus"));
        var history=json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200);assertEquals(2,items(history).size());
        assertFalse(d.stringify(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200)).contains(openid));
        json(send(patch("/api/admin/chats/"+cid+"/read"),manager,Map.of("throughSeq",first.get("seq"))),200);
        assertEquals(0,integer(json(send(get("/api/admin/chats"),manager,null),200),"unreadTotal",-1));
        json(send(patch("/api/admin/chats/"+cid+"/status"),manager,Map.of("status","RESOLVED")),200);
        webSend(cid,"我还有一个问题","web-request-002");assertEquals("OPEN",conversation(cid).get("status"));
        assertEquals(1,integer(json(send(get("/api/admin/chats"),manager,null),200),"unreadTotal",0));
        verify(wx,never()).sendCustomerText(anyString(),anyString());
    }

    @Test void incrementalPollingDoesNotSkipMessagesWhenMoreThanOnePageArrives()throws Exception{
        String cid=web("yuyao");long cursor=((Number)webSend(cid,"首条消息",id()).get("seq")).longValue();
        for(int i=0;i<205;i++)webSend(cid,"批次消息"+i,id());
        var page=items(json(send(get("/api/me/chats/"+cid+"/messages").param("after",Long.toString(cursor)),customerToken,null),200));
        assertEquals(100,page.size());assertEquals("批次消息0",page.get(0).get("content"));assertEquals("批次消息99",page.get(99).get("content"));
        cursor=((Number)page.get(99).get("seq")).longValue();
        page=items(json(send(get("/api/me/chats/"+cid+"/messages").param("after",Long.toString(cursor)),customerToken,null),200));
        assertEquals("批次消息100",page.get(0).get("content"));
        var latest=items(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200));
        var older=items(json(send(get("/api/me/chats/"+cid+"/messages").param("before",str(latest.get(0),"seq")),customerToken,null),200));
        assertEquals(100,older.size());assertEquals("批次消息104",older.get(99).get("content"));
    }

    @Test void directNativeContactWorksBeforeLoginAndRepliesThroughWechat()throws Exception{
        openid="native-guest-"+id();
        String cid=directNativeEntry("yht-store:yuyao",epoch());
        assertEquals("WECHAT",conversation(cid).get("channel"));assertEquals("yuyao",conversation(cid).get("storeId"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM customer_identity WHERE openid=?",openid));
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_entry"));assertEquals(0,d.count("SELECT COUNT(*) FROM chat_conversation WHERE channel='WEB'"));
        nativeText("微信里咨询本店项目",epoch(),"native-direct-001");nativeText("微信里咨询本店项目",epoch(),"native-direct-001");
        assertEquals(1,items(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200)).size());
        response(send(get("/api/admin/chats/"+cid+"/messages"),otherStore,null),404);
        assertEquals("SENT",reply(cid,"您好，余姚店为您服务",id()).get("deliveryStatus"));
        verify(wx,times(1)).sendCustomerText(openid,"您好，余姚店为您服务");
    }

    @Test void directNativeStoreSwitchKeepsHistoryAndCanReturnToHeadquarters()throws Exception{
        long time=epoch()-3;String first=directNativeEntry("yht-store:yuyao",time);
        nativeText("先咨询余姚店",time,"native-switch-001");
        String next=directNativeEntry("yht-store:bai-le-men",time+2);
        assertNotEquals(first,next);assertEquals("bai-le-men",conversation(next).get("storeId"));
        assertEquals(next,directNativeEntry("yht-store:yuyao",time+1),"late events must not restore the previous store");
        json(send(post("/api/admin/chats/"+first+"/messages"),manager,message("旧门店回复",id())),409);
        assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",first));
        String brand=directNativeEntry("yht-store:",time+3);assertEquals("",conversation(brand).get("storeId"));
        response(send(get("/api/admin/chats/"+brand+"/messages"),manager,null),404);
        nativeText("请总部协助",epoch(),"native-brand-001");
        assertEquals(1,items(json(send(get("/api/admin/chats/"+brand+"/messages"),hq,null),200)).size());
    }

    @Test void directNativeContextOnlyAcceptsEnabledStoresFromEntryEvents()throws Exception{
        String cid=directNativeEntry("yht-store:yuyao",epoch()-2);
        d.update("UPDATE store SET enabled=FALSE WHERE id='bai-le-men'");
        for(String source:List.of("yht-store:bai-le-men","yht-store:unknown-store","yht-store:"+"x".repeat(65)))assertEquals(cid,directNativeEntry(source,epoch()-1));
        var text=inbound("text",epoch());text.put("Content","消息不能更改门店");text.put("MsgId","native-context-001");text.put("SessionFrom","yht-store:");callback(text,200);
        assertEquals(cid,str(d.one("SELECT conversation_id FROM chat_route WHERE openid=?",openid),"conversationId"));
        assertEquals("yuyao",conversation(cid).get("storeId"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM chat_conversation"));
    }

    @Test void encryptedWechatCallbacksAreDeduplicatedAndImagesRequireStoreScope()throws Exception{
        String cid=nativeEntry("yuyao",epoch());var in=inbound("image",epoch());in.put("MsgId","image-001");in.put("MediaId","fixture-image-media");
        callback(in,200);callback(in,200);assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",cid));
        assertEquals(5,integer(conversation(cid),"replyRemaining",0));
        String mid=str(d.one("SELECT id FROM chat_message WHERE conversation_id=?",cid),"id");
        when(wx.customerImage("fixture-image-media")).thenReturn(new byte[]{1,2,3});
        response(send(get("/api/admin/chats/"+cid+"/messages/"+mid+"/image"),otherStore,null),404);
        var result=send(get("/api/admin/chats/"+cid+"/messages/"+mid+"/image"),manager,null).andReturn().getResponse();
        assertEquals(200,result.getStatus());assertArrayEquals(new byte[]{1,2,3},result.getContentAsByteArray());
        verify(wx,times(1)).customerImage("fixture-image-media");
    }

    @Test void diagnosticCallbackProbeDoesNotCreateBusinessDataOrSendMessages()throws Exception{
        var probe=inbound("event",epoch());probe.put("Event","yht_diagnostic_probe");
        callback(probe,200);
        String timestamp=Long.toString(epoch()),nonce=id(),encrypted=encrypt(d.stringify(probe),"wx-chat-test");
        assertEquals("success",response(mvc.perform(post("/api/wechat/customer-service")
            .param("timestamp",timestamp).param("nonce",nonce)
            .param("signature",signature(timestamp,nonce,null)).param("msg_signature",signature(timestamp,nonce,encrypted))
            .contentType("application/json").content(d.stringify(Map.of("Encrypt",encrypted)))),200));
        for(String table:List.of("chat_conversation","chat_route","chat_message","chat_receipt","chat_entry"))
            assertEquals(0,d.count("SELECT COUNT(*) FROM "+table),table);
        verifyNoInteractions(wx);
    }

    @Test void callbacksRejectInvalidSignaturesPlaintextAndWrongApplication()throws Exception{
        String timestamp=Long.toString(epoch()),nonce="nonce-test";
        assertEquals("echo-value",response(mvc.perform(get("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce)
            .param("signature",signature(timestamp,nonce,null)).param("echostr","echo-value")),200));
        response(mvc.perform(get("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce).param("signature","wrong").param("echostr","x")),403);
        response(mvc.perform(post("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce).param("signature",signature(timestamp,nonce,null))
            .contentType("application/json").content(d.stringify(inbound("text",epoch())))),403);
        String encrypted=encrypt(d.stringify(inbound("text",epoch())),"another-app-id");
        response(mvc.perform(post("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce).param("msg_signature",signature(timestamp,nonce,encrypted))
            .contentType("application/json").content(d.stringify(Map.of("Encrypt",encrypted)))),403);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->codec.parse("<!DOCTYPE xml [<!ENTITY x SYSTEM 'file:///unreadable-fixture'>]><xml><Content>&x;</Content></xml>"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_message"));
    }

    @Test void nativeSessionRoutingCannotBeForgedAndOldStoresCannotReplyAfterSwitch()throws Exception{
        long time=epoch()-2;String first=nativeEntry("yuyao",time);nativeText("咨询余姚项目",time,"route-001");
        var wrong=inbound("event",time+1);wrong.put("Event","user_enter_tempsession");
        wrong.put("SessionFrom",str(json(send(post("/api/me/chat/entry"),otherCustomerToken,Map.of("storeId","bai-le-men")),200),"sessionFrom"));
        callback(wrong,200);assertEquals(first,d.one("SELECT conversation_id FROM chat_route WHERE openid=?",openid).get("conversationId"));
        String next=nativeEntry("bai-le-men",time+2);assertNotEquals(first,next);nativeText("改为咨询百乐门",time+2,"route-002");
        json(send(post("/api/admin/chats/"+first+"/messages"),manager,message("旧门店不应发送",id())),409);
        var active=json(send(get("/api/admin/chats/"+first+"/messages"),manager,null),200);
        assertEquals(false,d.object(d.stringify(active.get("conversation"))).get("activeRoute"));
        assertEquals("SENT",json(send(post("/api/admin/chats/"+next+"/messages"),otherStore,message("百乐门接待您",id())),200).get("deliveryStatus"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=? AND direction='IN'",next));
    }

    @Test void nativeReplyQuotasAndIdempotencyFollowTheWechatWindow()throws Exception{
        String cid=nativeEntry("yuyao",epoch());assertEquals(2,integer(conversation(cid),"replyRemaining",0));
        var first=reply(cid,"欢迎咨询","native-reply-001");assertEquals("SENT",first.get("deliveryStatus"));
        assertEquals(first.get("id"),reply(cid,"欢迎咨询","native-reply-001").get("id"));assertEquals(1,integer(conversation(cid),"replyRemaining",0));
        reply(cid,"请问需要哪项养护","native-reply-002");
        json(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("超额消息",id())),409);
        nativeText("我想了解艾灸",epoch(),"window-001");assertEquals(5,integer(conversation(cid),"replyRemaining",0));
        for(int i=0;i<5;i++)reply(cid,"项目介绍"+i,id());
        json(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("第六条消息",id())),409);
        verify(wx,times(7)).sendCustomerText(eq(openid),anyString());
        d.update("UPDATE chat_conversation SET reply_deadline=?,reply_remaining=5 WHERE id=?",epoch()-1,cid);
        json(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("窗口过期",id())),409);
    }

    @Test void failedOrUnknownWechatSendsAreRecordedWithoutSilentRetries()throws Exception{
        String cid=nativeEntry("yuyao",epoch());nativeText("请介绍项目",epoch(),"delivery-001");
        when(wx.sendCustomerText(eq(openid),eq("微信拒收"))).thenReturn(Map.of("errcode",45015));
        when(wx.sendCustomerText(eq(openid),eq("网络中断"))).thenThrow(new IllegalStateException("fixture connection interrupted"));
        assertEquals("FAILED",reply(cid,"微信拒收","failure-reply-01").get("deliveryStatus"));
        assertEquals("FAILED",reply(cid,"微信拒收","failure-reply-01").get("deliveryStatus"));
        assertEquals("UNKNOWN",reply(cid,"网络中断","unknown-reply-01").get("deliveryStatus"));
        assertEquals("UNKNOWN",reply(cid,"网络中断","unknown-reply-01").get("deliveryStatus"));
        verify(wx,times(1)).sendCustomerText(openid,"微信拒收");verify(wx,times(1)).sendCustomerText(openid,"网络中断");
    }

    @Test void concurrentRepliesCannotOverspendTheWechatQuota()throws Exception{
        String cid=nativeEntry("yuyao",epoch());d.update("UPDATE chat_conversation SET reply_remaining=1 WHERE id=?",cid);
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{
            List<Future<Integer>> requests=new ArrayList<>();
            for(int i=0;i<2;i++)requests.add(pool.submit(()->{ready.countDown();start.await(5,TimeUnit.SECONDS);return send(post("/api/admin/chats/"+cid+"/messages"),manager,message("并发回复",id())).andReturn().getResponse().getStatus();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var results=new ArrayList<>(List.of(requests.get(0).get(15,TimeUnit.SECONDS),requests.get(1).get(15,TimeUnit.SECONDS)));Collections.sort(results);
            assertEquals(List.of(200,409),results);assertEquals(0,integer(conversation(cid),"replyRemaining",-1));verify(wx,times(1)).sendCustomerText(anyString(),anyString());
        }finally{pool.shutdownNow();}
    }

    @Test void tokenFailurePreservesReplyQuotaAndRecordsActionableFailureWithoutRetry()throws Exception{
        String cid=nativeEntry("yuyao",epoch());
        when(wx.sendCustomerText(eq(openid),eq("凭证失败"))).thenThrow(new WechatGateway.CustomerSendNotAttemptedException("消息尚未提交微信：微信错误码 40164：请添加 IP 白名单。"));
        var result=reply(cid,"凭证失败","token-failure-001");
        assertEquals("FAILED",result.get("deliveryStatus"));assertTrue(str(result,"errorMessage").contains("40164"));
        assertEquals(2,integer(conversation(cid),"replyRemaining",0));
        assertEquals(result.get("id"),reply(cid,"凭证失败","token-failure-001").get("id"));
        assertEquals(2,integer(conversation(cid),"replyRemaining",0));verify(wx,times(1)).sendCustomerText(openid,"凭证失败");
    }

    @Test void concurrentTokenFailuresRestoreBothReservedReplies()throws Exception{
        String cid=nativeEntry("yuyao",epoch());var barrier=new CyclicBarrier(2);
        when(wx.sendCustomerText(eq(openid),startsWith("并发凭证失败"))).thenAnswer(invocation->{barrier.await(10,TimeUnit.SECONDS);throw new WechatGateway.CustomerSendNotAttemptedException("消息尚未提交微信：凭证不可用。");});
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->reply(cid,"并发凭证失败1","token-concurrent-001"));
            var second=pool.submit(()->reply(cid,"并发凭证失败2","token-concurrent-002"));
            assertEquals("FAILED",first.get(15,TimeUnit.SECONDS).get("deliveryStatus"));
            assertEquals("FAILED",second.get(15,TimeUnit.SECONDS).get("deliveryStatus"));
            assertEquals(2,integer(conversation(cid),"replyRemaining",0));
        }finally{pool.shutdownNow();}
    }

    @Test void tokenFailureDoesNotAddQuotaToARefreshedCustomerWindow()throws Exception{
        long time=epoch();String cid=nativeEntry("yuyao",time);
        nativeText("第一次消息",time,"quota-window-001");
        when(wx.sendCustomerText(eq(openid),eq("凭证获取期间客户再发消息"))).thenAnswer(invocation->{
            nativeText("同一秒再次发消息",time,"quota-window-002");
            throw new WechatGateway.CustomerSendNotAttemptedException("消息尚未提交微信：凭证不可用。");
        });
        assertEquals("FAILED",reply(cid,"凭证获取期间客户再发消息","token-new-window-001").get("deliveryStatus"));
        assertEquals(5,integer(conversation(cid),"replyRemaining",0));
    }

    @Test void missingOrInvalidSendResultRemainsUnknownWithoutRetry()throws Exception{
        String cid=nativeEntry("yuyao",epoch());nativeText("请回复",epoch(),"unknown-shape-001");
        int index=0;
        for(Map<String,Object> result:List.of(Map.<String,Object>of("errmsg","ok"),Map.<String,Object>of("errcode","0"),Map.<String,Object>of("errcode",0.5))){
            String body="结果待核实"+index,key="missing-code-00"+index++;
            when(wx.sendCustomerText(eq(openid),eq(body))).thenReturn(result);
            assertEquals("UNKNOWN",reply(cid,body,key).get("deliveryStatus"));
            assertEquals("UNKNOWN",reply(cid,body,key).get("deliveryStatus"));verify(wx,times(1)).sendCustomerText(openid,body);
        }
        assertEquals(2,integer(conversation(cid),"replyRemaining",0));
    }

    @Test void outOfRangeChatPageReturnsExistingConversationsAndTheCorrectPage()throws Exception{
        String cid=web("yuyao");webSend(cid,"分页仍能找到客户",id());
        var result=json(send(get("/api/admin/chats?page=99999"),manager,null),200);
        assertEquals(1,integer(result,"page",0));assertEquals(1,integer(result,"total",0));assertEquals(cid,items(result).get(0).get("id"));
        var all=json(send(get("/api/admin/chats"),hq,null),200);
        assertEquals(1,integer(all,"unreadTotal",0));
        json(send(patch("/api/admin/chats/"+cid+"/status"),manager,Map.of("status","RESOLVED")),200);
        assertEquals(cid,items(json(send(get("/api/admin/chats"),manager,null),200)).get(0).get("id"));
        assertEquals(0,integer(json(send(get("/api/admin/chats?status=OPEN"),manager,null),200),"total",-1));
    }

    @Test void headquartersCanAssignUnroutedConversationsWithoutExposingOtherStores()throws Exception{
        String cid=web("");webSend(cid,"请协助选择门店",id());
        assertEquals(0,integer(json(send(get("/api/admin/chats"),manager,null),200),"total",-1));
        json(send(patch("/api/admin/chats/"+cid+"/route"),manager,Map.of("storeId","yuyao")),403);
        json(send(patch("/api/admin/chats/"+cid+"/route"),hq,Map.of("storeId","yuyao")),200);
        response(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200);
        response(send(get("/api/admin/chats/"+cid+"/messages"),otherStore,null),404);
        json(send(patch("/api/admin/chats/"+cid+"/route"),hq,Map.of("storeId","bai-le-men")),409);
        reply(cid,"余姚店已接待",id());assertEquals(2,items(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200)).size());
    }

    @Test void upgradingAnExistingDatabasePreservesStoreDataAndGrantsBuiltInRoles()throws Exception{
        String url="jdbc:h2:mem:yht_v1_upgrade_"+id()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").target("1").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()){
            sql.execute("INSERT INTO app_role VALUES('hq','原总部','GLOBAL',TRUE),('manager','原店长','STORE',TRUE),('frontdesk','原前台','STORE',TRUE),('custom-reader','自定义只读','STORE',FALSE)");
            sql.execute("INSERT INTO store VALUES('old-store','原门店',TRUE,'{\"disabledServiceIds\":[\"moxa\"]}','{}')");
        }
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()){
            try(var result=sql.executeQuery("SELECT profile_json FROM store WHERE id='old-store'")){assertTrue(result.next());assertTrue(result.getString(1).contains("moxa"));}
            try(var result=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_id='frontdesk' AND permission_code IN ('services:read','services:write','chat:read','chat:reply')")){result.next();assertEquals(4,result.getInt(1));}
            try(var result=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_id='custom-reader'")){result.next();assertEquals(0,result.getInt(1));}
        }
    }
}
