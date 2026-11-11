package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
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
    "app.mode=local","app.data-dir=target/isolated-chat-test-data","app.public-url=https://fixture.example",
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
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreChatIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired CatalogService catalog; @Autowired ChatService chat; @Autowired CustomerProfiles profiles; @Autowired ChatAttachments attachments;
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
        String code=id();when(wx.phoneNumber(code)).thenReturn("13900009101");profiles.phone(customer,Map.of("name","咨询测试客户","code",code,"consent",true));
        String other=str(d.one("SELECT identity_id FROM customer_account WHERE identity_id<>? ORDER BY last_login_at DESC LIMIT 1",customer),"identityId");
        code=id();when(wx.phoneNumber(code)).thenReturn("13900009102");profiles.phone(other,Map.of("name","另一客户","code",code,"consent",true));
        clearInvocations(wx);
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
    @Test void independentStoreCatalogsKeepOwnRecordsAndIgnoreFutureBrandChanges()throws Exception{
        var left=json(send(post("/api/admin/stores/yuyao/services"),hq,project("中医推拿")),200);
        var right=json(send(post("/api/admin/stores/bai-le-men/services"),hq,project("中医推拿")),200);
        assertNotEquals(left.get("id"),right.get("id"));
        for(String storeId:List.of("yuyao","bai-le-men")){
            var p=d.object(catalog.store(storeId).get("profileJson"));p.put("catalogMode","INDEPENDENT");
            json(send(put("/api/admin/stores/"+storeId),hq,p),200);
        }
        assertEquals(List.of(left.get("id")),catalog.storeServices("yuyao",true).stream().map(s->s.get("id")).toList());
        var updated=new LinkedHashMap<>(left);updated.put("description","余姚高级228元");
        json(send(put("/api/admin/stores/yuyao/services/"+left.get("id")),manager,updated),200);
        assertEquals("门店独立养护介绍",catalog.storeServices("bai-le-men",true).get(0).get("description"));
        var site=d.object(published);var services=new ArrayList<>(catalog.collection(site,"services"));
        services.forEach(s->s.put("enabled",false));services.add(new LinkedHashMap<>(Map.of("id","new-hq-only","name","总部新模板","enabled",true)));
        site.put("services",services);d.update("UPDATE site_config SET published_json=? WHERE id='brand'",d.stringify(site));catalog.invalidateSite();
        assertEquals(List.of(left.get("id")),catalog.storeServices("yuyao",true).stream().map(s->s.get("id")).toList());
        assertEquals(List.of(right.get("id")),catalog.storeServices("bai-le-men",true).stream().map(s->s.get("id")).toList());
        assertTrue(catalog.storeServices("huashan",true).stream().anyMatch(s->"new-hq-only".equals(s.get("id"))));
        for(String field:List.of("appId","secret","customerTemplate","customerReminderTemplate"))org.springframework.test.util.ReflectionTestUtils.setField(wx,field,"");
        assertEquals(true,json(send(get("/api/runtime"),null,null),200).get("independentStoreServices"));
        response(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,booking("yuyao",str(right,"id"),"14:00")),400);
    }
    @Test void onlyHeadquartersChangesCatalogModeAndOmittedModeIsPreserved()throws Exception{
        var p=d.object(catalog.store("yuyao").get("profileJson"));p.put("catalogMode","INDEPENDENT");
        json(send(put("/api/admin/stores/yuyao"),manager,p),200);
        assertEquals("INHERIT",d.object(catalog.store("yuyao").get("profileJson")).get("catalogMode"));
        json(send(put("/api/admin/stores/yuyao"),hq,p),200);
        p.remove("catalogMode");json(send(put("/api/admin/stores/yuyao"),manager,p),200);
        assertEquals("INDEPENDENT",d.object(catalog.store("yuyao").get("profileJson")).get("catalogMode"));
        json(send(put("/api/admin/stores/yuyao"),hq,p),200);
        assertEquals("INDEPENDENT",d.object(catalog.store("yuyao").get("profileJson")).get("catalogMode"));
        p.put("catalogMode","invalid");response(send(put("/api/admin/stores/yuyao"),hq,p),400);
    }
    Map<String,Object> booking(String store,String service,String time){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("storeId",store),Map.entry("serviceId",service),Map.entry("date",LocalDate.now(ZONE).plusDays(1).toString()),
        Map.entry("startTime",time),Map.entry("headcount",1),Map.entry("name","品项测试客户"),Map.entry("phone","13900009001"),
        Map.entry("therapistIntentId",""),Map.entry("memberClaim","UNKNOWN"),Map.entry("visitedClaim","NO"),Map.entry("privacyConsent",true)));
    }
    String web(String store)throws Exception{return str(json(send(post("/api/me/chats"),customerToken,Map.of("storeId",store)),200),"id");}
    Map<String,Object> message(String text,String key){return Map.of("content",text,"clientKey",key);}
    Map<String,Object> webSend(String cid,String text,String key)throws Exception{return json(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message(text,key)),200);}
    void accept(String cid)throws Exception{json(send(post("/api/admin/chats/"+cid+"/accept"),manager,null),200);}
    Map<String,Object> reply(String cid,String text,String key)throws Exception{if("OPEN".equals(conversation(cid).get("status")))accept(cid);return json(send(post("/api/admin/chats/"+cid+"/messages"),manager,message(text,key)),200);}
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
    byte[] chatPicture()throws Exception{
        var image=new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();
    }
    Map<String,Object> uploadChat(String cid,String token,String key,String filename,byte[] bytes,int status)throws Exception{
        var request=multipart("/api/admin/chats/"+cid+"/attachments").file(new org.springframework.mock.web.MockMultipartFile("file",filename,"application/octet-stream",bytes)).param("clientKey",key);
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json(mvc.perform(request),status);
    }
    @Test void chatPicturesArePrivatePreviewableAndVisibleToTheOwningCustomer()throws Exception{
        String cid=web("yuyao");accept(cid);byte[] bytes=chatPicture();
        var sent=uploadChat(cid,manager,id(),"门店介绍.png",bytes,200);var attachment=d.object(sent.get("attachment"));
        assertEquals("image",sent.get("kind"));assertEquals("SENT",sent.get("deliveryStatus"));assertEquals("门店介绍.png",attachment.get("name"));
        assertArrayEquals(bytes,mvc.perform(get(str(attachment,"url"))).andReturn().getResponse().getContentAsByteArray());
        var mine=items(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200));
        assertEquals("门店介绍.png",d.object(mine.get(0).get("attachment")).get("name"));
        assertFalse(d.stringify(mine).contains("chat-private"));assertFalse(d.stringify(mine).contains("attachmentJson"));assertFalse(d.stringify(mine).contains("mediaId"));
        response(send(get("/api/me/chats/"+cid+"/messages"),otherCustomerToken,null),404);
        response(send(get("/api/admin/chats/"+cid+"/messages"),otherStore,null),404);
        var meta=d.object(d.one("SELECT attachment_json FROM chat_message WHERE id=?",sent.get("id")).get("attachmentJson"));
        assertEquals(404,mvc.perform(get("/uploads/chat-private/"+str(meta,"file"))).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get(str(attachment,"url").replace(str(sent,"id"),id()))).andReturn().getResponse().getStatus());
        verify(wx,never()).sendCustomerMedia(anyString(),any());
    }
    @Test void attachmentRetryUsesOneMessageAndRejectsChangedContents()throws Exception{
        String cid=web("yuyao"),key=id();accept(cid);byte[] bytes=chatPicture();
        var first=uploadChat(cid,manager,key,"介绍.png",bytes,200);var again=uploadChat(cid,manager,key,"介绍.png",bytes,200);
        assertEquals(first.get("id"),again.get("id"));assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",cid));
        uploadChat(cid,manager,key,"改名.png",bytes,409);
        json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        assertEquals(first.get("id"),uploadChat(cid,manager,key,"介绍.png",bytes,200).get("id"));
    }
    @Test void attachmentsRequireReceptionOwnershipAndValidMedia()throws Exception{
        String cid=web("yuyao");byte[] bytes=chatPicture();
        uploadChat(cid,manager,id(),"介绍.png",bytes,409);uploadChat(cid,otherStore,id(),"介绍.png",bytes,404);uploadChat(cid,null,id(),"介绍.png",bytes,401);
        accept(cid);uploadChat(cid,hq,id(),"介绍.png",bytes,409);
        uploadChat(cid,manager,id(),"伪造.jpg","<html>invalid</html>".getBytes(StandardCharsets.UTF_8),400);
        uploadChat(cid,manager,id(),"损坏.png",Arrays.copyOf(bytes,12),400);
        byte[] large=Arrays.copyOf(bytes,2*1024*1024+1);uploadChat(cid,manager,id(),"过大.png",large,400);
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",cid));
        json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);uploadChat(cid,manager,id(),"介绍.png",bytes,409);
    }
    @Test void wechatMediaConsumesOneReplyAndDoesNotRepeatOnRetry()throws Exception{
        String cid=nativeEntry("yuyao",epoch()),key=id();accept(cid);
        when(wx.sendCustomerMedia(eq(openid),any())).thenReturn(Map.of("errcode",0));
        var sent=uploadChat(cid,manager,key,"介绍.png",chatPicture(),200);
        assertEquals("SENT",sent.get("deliveryStatus"));assertEquals(1,integer(conversation(cid),"replyRemaining",-1));
        assertEquals(sent.get("id"),uploadChat(cid,manager,key,"介绍.png",chatPicture(),200).get("id"));
        verify(wx,times(1)).sendCustomerMedia(eq(openid),any());
    }
    @Test void uploadFailureBeforeWechatSendRestoresQuotaAndKeepsFailureVisible()throws Exception{
        String cid=nativeEntry("yuyao",epoch());accept(cid);
        when(wx.sendCustomerMedia(eq(openid),any())).thenThrow(new WechatGateway.CustomerSendNotAttemptedException("附件尚未发送给客户：素材上传失败。"));
        var sent=uploadChat(cid,manager,id(),"介绍.png",chatPicture(),200);
        assertEquals("FAILED",sent.get("deliveryStatus"));assertTrue(str(sent,"errorMessage").contains("素材上传失败"));assertEquals(2,integer(conversation(cid),"replyRemaining",-1));
        assertTrue(sent.containsKey("attachment"));
    }
    @Test void videoAttachmentHasCoverAndSupportsPartialPlayback()throws Exception{
        String cid=web("yuyao");accept(cid);
        byte[] bytes=ByteBuffer.allocate(40).putInt(16).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("mdat".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("moov".getBytes(StandardCharsets.US_ASCII)).putInt(0).array();
        var sent=uploadChat(cid,manager,id(),"门店视频.mp4",bytes,200);var attachment=d.object(sent.get("attachment"));
        assertEquals("video",sent.get("kind"));assertTrue(str(attachment,"thumbnailUrl").startsWith("/api/chat-media/"));
        var response=mvc.perform(get(str(attachment,"url")).header("Range","bytes=0-15")).andReturn().getResponse();
        assertEquals(206,response.getStatus());assertEquals("video/mp4",response.getContentType());assertArrayEquals(Arrays.copyOf(bytes,16),response.getContentAsByteArray());
        var poster=mvc.perform(get(str(attachment,"thumbnailUrl"))).andReturn().getResponse();assertEquals(200,poster.getStatus());assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(poster.getContentAsByteArray())));
    }
    @Test void nativeVideoCardOpensASignedPlayerAndRetriesDoNotResend()throws Exception{
        String cid=nativeEntry("yuyao",epoch()),key=id();accept(cid);
        byte[] bytes=ByteBuffer.allocate(40).putInt(16).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("mdat".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("moov".getBytes(StandardCharsets.US_ASCII)).putInt(0).array();
        when(wx.sendCustomerVideoCard(eq(openid),anyMap())).thenReturn(Map.of("errcode",0));
        var sent=uploadChat(cid,manager,key,"门店视频.mp4",bytes,200);assertEquals("SENT",sent.get("deliveryStatus"));
        var card=attachments.videoCard(str(sent,"id"));assertTrue(card.get("url").startsWith("https://fixture.example/api/chat-media/"));
        var page=mvc.perform(get(card.get("url"))).andReturn().getResponse();assertEquals(200,page.getStatus());
        assertTrue(page.getContentType().startsWith("text/html"));assertTrue(page.getContentAsString().contains("<video controls playsinline"));assertEquals("no-referrer",page.getHeader("Referrer-Policy"));
        assertTrue(page.getContentAsString(StandardCharsets.UTF_8).contains("客服发来的视频"));
        assertEquals(200,mvc.perform(get(card.get("thumb_url"))).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get(card.get("url").replace(str(sent,"id"),id()))).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get(card.get("url").replace("/watch?","/file?"))).andReturn().getResponse().getStatus());
        assertEquals(sent.get("id"),uploadChat(cid,manager,key,"门店视频.mp4",bytes,200).get("id"));
        assertEquals(1,integer(conversation(cid),"replyRemaining",-1));verify(wx,times(1)).sendCustomerVideoCard(eq(openid),anyMap());verify(wx,never()).sendCustomerMedia(anyString(),any());
    }
    @Test void nativeVideoRequiresAnHttpsOriginWithoutExposingPrivateConfiguration(){
        for(String origin:List.of("","http://fixture.example","https://name:secret@fixture.example","https://fixture.example/admin","https://fixture.example?token=secret")){
            var media=new ChatAttachments(d,auth,wx,"target/isolated-chat-test-data",origin);
            var failure=assertThrows(WechatGateway.CustomerSendNotAttemptedException.class,()->media.videoCard("fixture"));assertTrue(failure.getMessage().contains("PUBLIC_URL"));assertFalse(failure.getMessage().contains("secret"));
        }
    }
    @Test void incomingWechatPicturesArePreviewedAndCachedInPrivateStorage()throws Exception{
        String cid=nativeEntry("yuyao",epoch());var in=inbound("image",epoch());in.put("MsgId","preview-image-"+id());in.put("MediaId","preview-media");callback(in,200);
        when(wx.customerImage("preview-media")).thenReturn(chatPicture());
        var rows=items(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200));String url=str(d.object(rows.get(0).get("attachment")),"url");
        assertEquals(200,mvc.perform(get(url)).andReturn().getResponse().getStatus());assertEquals(200,mvc.perform(get(url)).andReturn().getResponse().getStatus());
        verify(wx,times(1)).customerImage("preview-media");
    }
    @Test void incomingWechatVideosAreStoredPreviewableAndCachedOnce()throws Exception{
        String cid=nativeEntry("yuyao",epoch());var in=inbound("video",epoch());in.put("MsgId","preview-video-"+id());in.put("MediaId","preview-video-media");callback(in,200);
        byte[] bytes=ByteBuffer.allocate(40).putInt(16).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("mdat".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(12).put("moov".getBytes(StandardCharsets.US_ASCII)).putInt(0).array();
        when(wx.customerVideo("preview-video-media")).thenReturn(bytes);
        var rows=items(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200));assertEquals("video",rows.get(0).get("kind"));var media=d.object(rows.get(0).get("attachment"));
        var poster=mvc.perform(get(str(media,"thumbnailUrl"))).andReturn().getResponse();assertEquals(200,poster.getStatus());assertEquals("image/jpeg",poster.getContentType());
        var response=mvc.perform(get(str(media,"url")).header("Range","bytes=0-15")).andReturn().getResponse();assertEquals(206,response.getStatus());assertArrayEquals(Arrays.copyOf(bytes,16),response.getContentAsByteArray());
        verify(wx,times(1)).customerVideo("preview-video-media");assertEquals(5,integer(conversation(cid),"replyRemaining",-1));
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
            for(String action:List.of("accept","end","release"))response(send(post("/api/admin/chats/"+cid+"/"+action),token,null),403);
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
        response(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message("我还有一个问题","web-request-002")),409);
        assertEquals(cid,web("yuyao"));
        String next=str(json(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),200),"id");assertNotEquals(cid,next);
        webSend(next,"我还有一个问题","web-request-002");assertEquals("OPEN",conversation(next).get("status"));assertEquals("RESOLVED",conversation(cid).get("status"));
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

    @Test void nativeContactRequiresACompletedAuthorizedCustomerAndNeverTrustsSessionFrom()throws Exception{
        openid="native-guest-"+id();
        var event=inbound("event",epoch());event.put("Event","user_enter_tempsession");event.put("SessionFrom","yht-store:yuyao");
        callback(event,200);nativeText("未授权的文字",epoch(),"guest-message-001");
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_conversation"));assertEquals(0,d.count("SELECT COUNT(*) FROM chat_receipt"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_message"));verifyNoInteractions(wx);
    }

    @Test void chatEndpointsRequirePhoneNameAndConsentEvenWhenCallingTheApiDirectly()throws Exception{
        String cid=web("yuyao");
        response(send(post("/api/me/chats"),null,Map.of("storeId","yuyao")),401);
        for(String column:List.of("phone_verified_at","profile_consent_at","display_name")){
            String old=str(d.one("SELECT "+column+" previous_value FROM customer_account WHERE identity_id=?",customer),"previousValue");
            d.update("UPDATE customer_account SET "+column+"=? WHERE identity_id=?","display_name".equals(column)?"":null,customer);
            response(send(post("/api/me/chats"),customerToken,Map.of("storeId","yuyao")),403);
            response(send(post("/api/me/chat/entry"),customerToken,Map.of("storeId","yuyao")),403);
            response(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),403);
            response(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message("绕过界面",id())),403);
            response(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),403);
            d.update("UPDATE customer_account SET "+column+"=? WHERE identity_id=?",old,customer);
        }
        assertEquals(0,d.count("SELECT COUNT(*) FROM chat_message"));
    }

    @Test void legacyAnonymousChatRemainsHistoryButCannotBeAcceptedOrRepliedToUntilAuthorized()throws Exception{
        String cid=web("yuyao");webSend(cid,"保留历史消息",id());
        String consent=str(d.one("SELECT profile_consent_at previous_value FROM customer_account WHERE identity_id=?",customer),"previousValue");
        d.update("UPDATE customer_account SET profile_consent_at=NULL WHERE identity_id=?",customer);
        var denied=d.object(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200).get("conversation"));
        assertEquals(false,d.object(denied.get("customer")).get("profileComplete"));assertEquals(false,denied.get("canAccept"));
        response(send(post("/api/admin/chats/"+cid+"/accept"),manager,null),403);
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("不能继续匿名对话",id())),403);
        assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",cid));
        d.update("UPDATE customer_account SET profile_consent_at=? WHERE identity_id=?",consent,customer);accept(cid);
        d.update("UPDATE customer_account SET profile_consent_at=NULL WHERE identity_id=?",customer);
        var active=d.object(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200).get("conversation"));
        assertEquals(false,active.get("canReply"));assertEquals(true,active.get("canEnd"));
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("已接入仍不能绕过",id())),403);
        json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        assertEquals("RESOLVED",conversation(cid).get("status"));verifyNoInteractions(wx);
    }

    @Test void adminChatShowsVerifiedIdentityMembershipAndSearchesByPhoneWithoutLeakingToCustomer()throws Exception{
        String cid=web("yuyao");webSend(cid,"咨询项目",id());
        String pid=str(d.one("SELECT customer_profile_id FROM customer_account WHERE identity_id=?",customer),"customerProfileId");
        d.update("UPDATE customer_profile SET member_verified=TRUE,member_grade='金卡会员' WHERE id=?",pid);
        var page=json(send(get("/api/admin/chats").param("q","13900009101"),manager,null),200);
        assertEquals(1,integer(page,"total",0));
        var item=items(page).get(0);assertFalse(item.containsKey("openid"));
        var details=json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200);
        var customerInfo=d.object(d.object(details.get("conversation")).get("customer"));
        assertEquals("咨询测试客户",customerInfo.get("name"));assertEquals("13900009101",customerInfo.get("phone"));
        assertEquals(true,customerInfo.get("phoneVerified"));assertEquals(true,customerInfo.get("memberVerified"));
        assertEquals("金卡会员",customerInfo.get("memberGrade"));assertTrue(d.strings(customerInfo.get("sources")).contains("MINIPROGRAM"));
        var mine=d.object(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200).get("conversation"));
        assertFalse(mine.containsKey("customer"));assertFalse(d.stringify(mine).contains("13900009101"));
        String nativeId=nativeEntry("yuyao",epoch());nativeText("微信咨询",epoch(),"identified-native-001");
        var nativeInfo=d.object(d.object(json(send(get("/api/admin/chats/"+nativeId+"/messages"),manager,null),200).get("conversation")).get("customer"));
        assertEquals(customerInfo,nativeInfo);
        response(send(get("/api/admin/chats/"+nativeId+"/messages"),otherStore,null),404);
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

    @Test void xmlCustomerTextInsideJsonEnvelopeIsStoredAndItsOutcomeLoggedWithoutCustomerData(CapturedOutput output)throws Exception{
        String cid=nativeEntry("yuyao",epoch());
        String privateText="客服链路测试 & 养护咨询 <正文>";
        String inner="<xml><ToUserName><![CDATA[fixture-app-original-id]]></ToUserName><FromUserName><![CDATA["+openid+"]]></FromUserName><CreateTime>"+epoch()+"</CreateTime><MsgType><![CDATA[text]]></MsgType><Content><![CDATA["+privateText+"]]></Content><MsgId>7890123456789012345</MsgId></xml>";
        String timestamp=Long.toString(epoch()),nonce=id(),encrypted=encrypt(inner,"wx-chat-test");
        int start=output.getAll().length();
        for(int attempt=0;attempt<2;attempt++)assertEquals("success",response(mvc.perform(post("/api/wechat/customer-service")
            .param("timestamp",timestamp).param("nonce",nonce).param("msg_signature",signature(timestamp,nonce,encrypted))
            .contentType("application/json").content(d.stringify(Map.of("Encrypt",encrypted)))),200));
        assertEquals(1,d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=? AND direction='IN'",cid));
        assertEquals(privateText,chat.messages(cid,0,0).get(0).get("content"));
        assertEquals(5,integer(conversation(cid),"replyRemaining",0));
        String logs=output.getAll().substring(start);
        assertTrue(logs.contains("WeChat callback type=text event=none outcome=STORED"));
        assertTrue(logs.contains("WeChat callback type=text event=none outcome=DUPLICATE"));
        for(String value:List.of(openid,privateText,encrypted,"7890123456789012345"))assertFalse(logs.contains(value));
    }

    @Test void rejectedAndUnsupportedCallbacksAreLoggedWithoutLeakingTheirFields(CapturedOutput output)throws Exception{
        String timestamp=Long.toString(epoch()),nonce=id();
        var unsupported=inbound("event",epoch());unsupported.put("Event","private-event\nprivate-body");
        int start=output.getAll().length();
        callback(unsupported,200);
        response(mvc.perform(post("/api/wechat/customer-service").param("timestamp",timestamp).param("nonce",nonce).param("msg_signature","invalid-signature")
            .contentType("application/json").content(d.stringify(Map.of("Encrypt",encrypt(d.stringify(unsupported),"wx-chat-test"))))),403);
        String logs=output.getAll().substring(start);
        assertTrue(logs.contains("WeChat callback type=event event=other outcome=IGNORED"));
        assertTrue(logs.contains("outcome=REJECTED http=403"));
        for(String value:List.of("private-event","private-body",openid,"invalid-signature",nonce))assertFalse(logs.contains(value));
        for(String table:List.of("chat_receipt","chat_conversation","chat_message"))assertEquals(0,d.count("SELECT COUNT(*) FROM "+table));
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
        json(send(post("/api/admin/chats/"+next+"/accept"),otherStore,null),200);
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

    @Test void specificTransportFailureIsVisibleToAdminAndLoggedWithoutRetryOrCustomerData(CapturedOutput output)throws Exception{
        String cid=nativeEntry("yuyao",epoch());nativeText("请介绍项目",epoch(),"diagnostic-inbound-001");
        when(wx.sendCustomerText(eq(openid),eq("private reply body"))).thenThrow(new WechatGateway.ExchangeException("READ_TIMEOUT","等待微信接口响应超时。"));
        int start=output.getAll().length();
        var result=reply(cid,"private reply body","diagnostic-outbound-001");
        assertEquals("UNKNOWN",result.get("deliveryStatus"));assertTrue(str(result,"errorMessage").contains("响应超时"));
        assertTrue(str(result,"errorMessage").contains("避免重复发送"));
        assertEquals(4,integer(conversation(cid),"replyRemaining",0));
        assertEquals(result.get("id"),reply(cid,"private reply body","diagnostic-outbound-001").get("id"));
        verify(wx,times(1)).sendCustomerText(openid,"private reply body");
        String logs=output.getAll().substring(start);
        assertTrue(logs.contains("state=UNKNOWN category=READ_TIMEOUT"));
        assertTrue(logs.contains(str(result,"id")));
        for(String value:List.of(openid,"private reply body","diagnostic-outbound-001"))assertFalse(logs.contains(value));
    }

    @Test void concurrentRepliesCannotOverspendTheWechatQuota()throws Exception{
        String cid=nativeEntry("yuyao",epoch());accept(cid);d.update("UPDATE chat_conversation SET reply_remaining=1 WHERE id=?",cid);
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
        accept(cid);json(send(patch("/api/admin/chats/"+cid+"/status"),manager,Map.of("status","RESOLVED")),200);
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

    @Test void onlyTheAssignedAgentMayReplyOrEndAndCustomerMessagesKeepOwnership()throws Exception{
        String cid=web("yuyao");webSend(cid,"需要人工接待",id());
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("未接入回复",id())),409);
        response(send(post("/api/admin/chats/"+cid+"/end"),manager,null),409);
        response(send(post("/api/admin/chats/"+cid+"/accept"),otherStore,null),404);
        var accepted=json(send(post("/api/admin/chats/"+cid+"/accept"),manager,null),200);
        assertEquals("ACTIVE",accepted.get("status"));assertEquals("yuyao-manager",accepted.get("assignedAdminId"));assertEquals(true,accepted.get("canReply"));
        assertEquals(accepted.get("acceptedAt"),json(send(post("/api/admin/chats/"+cid+"/accept"),manager,null),200).get("acceptedAt"));
        webSend(cid,"补充咨询内容",id());assertEquals("ACTIVE",conversation(cid).get("status"));
        for(String action:List.of("accept","end","release"))response(send(post("/api/admin/chats/"+cid+"/"+action),adminToken("yuyao-frontdesk"),null),409);
        response(send(post("/api/admin/chats/"+cid+"/messages"),hq,message("总部不能抢答",id())),409);
        var viewer=d.object(json(send(get("/api/admin/chats/"+cid+"/messages"),hq,null),200).get("conversation"));
        assertEquals(false,viewer.get("canReply"));assertEquals(false,viewer.get("canAccept"));assertEquals(true,viewer.get("canRelease"));
        assertFalse(d.stringify(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200)).contains("assignedAdminId"));
        json(send(post("/api/admin/chats/"+cid+"/release"),hq,null),200);
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("退回后旧人不能回",id())),409);
        json(send(post("/api/admin/chats/"+cid+"/accept"),adminToken("yuyao-frontdesk"),null),200);
        assertEquals("yuyao-frontdesk",conversation(cid).get("assignedAdminId"));
    }

    @Test void endedConversationStaysReadOnlyAndNewConsultationHasANewIdentity()throws Exception{
        String cid=web("yuyao");var first=webSend(cid,"第一次咨询",id());accept(cid);
        var end=json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        assertEquals("RESOLVED",end.get("status"));assertEquals(false,end.get("canAccept"));assertEquals(false,end.get("canReply"));
        assertEquals(end.get("endedAt"),json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200).get("endedAt"));
        assertEquals(0,integer(json(send(get("/api/admin/chats/unread"),manager,null),200),"unreadTotal",-1));
        response(send(post("/api/admin/chats/"+cid+"/accept"),manager,null),409);
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("已结束不能回复",id())),409);
        response(send(patch("/api/admin/chats/"+cid+"/status"),manager,Map.of("status","OPEN")),409);
        response(send(post("/api/me/chats/"+cid+"/messages"),customerToken,message("旧页面不能重开",id())),409);
        response(send(post("/api/me/chats/"+cid+"/restart"),otherCustomerToken,null),404);
        assertEquals(cid,web("yuyao"));
        String next=str(json(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),200),"id");
        assertNotEquals(cid,next);assertEquals(next,str(json(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),200),"id"));
        assertEquals(next,web("yuyao"));assertEquals("",str(conversation(next),"assignedAdminId"));
        assertEquals(first.get("id"),items(json(send(get("/api/me/chats/"+cid+"/messages"),customerToken,null),200)).get(0).get("id"));
        assertTrue(items(json(send(get("/api/me/chats/"+next+"/messages"),customerToken,null),200)).isEmpty());
        assertEquals(1,integer(json(send(get("/api/admin/chats?status=OPEN"),manager,null),200),"total",0));
        assertEquals(1,integer(json(send(get("/api/admin/chats?status=RESOLVED"),manager,null),200),"total",0));
    }

    @Test void simultaneousAgentAcceptsHaveExactlyOneWinner()throws Exception{
        String cid=web("yuyao");var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try{
            List<Future<Integer>> calls=new ArrayList<>();for(String token:List.of(manager,adminToken("yuyao-frontdesk")))calls.add(pool.submit(()->{ready.countDown();go.await(5,TimeUnit.SECONDS);return send(post("/api/admin/chats/"+cid+"/accept"),token,null).andReturn().getResponse().getStatus();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();var codes=new ArrayList<>(List.of(calls.get(0).get(15,TimeUnit.SECONDS),calls.get(1).get(15,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);
            assertEquals(1,d.count("SELECT COUNT(*) FROM audit_log WHERE target_id=? AND action='chat.accept'",cid));
            assertEquals("ACTIVE",conversation(cid).get("status"));
        }finally{pool.shutdownNow();}
    }

    @Test void simultaneousNewConsultationRequestsCannotDuplicateTheLiveSession()throws Exception{
        String cid=web("yuyao");accept(cid);json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        var pool=Executors.newFixedThreadPool(2);try{
            var first=pool.submit(()->json(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),200));
            var second=pool.submit(()->json(send(post("/api/me/chats/"+cid+"/restart"),customerToken,null),200));
            assertEquals(first.get(15,TimeUnit.SECONDS).get("id"),second.get(15,TimeUnit.SECONDS).get("id"));
            assertEquals(1,d.count("SELECT COUNT(*) FROM chat_conversation WHERE openid=? AND active_key='LIVE'",openid));
        }finally{pool.shutdownNow();}
    }

    @Test void nativeEntryCannotResumeEndedHistoryButANewTextQueuesANewConversation()throws Exception{
        String cid=nativeEntry("yuyao",epoch());nativeText("本次问题",epoch(),"native-before-end");reply(cid,"已处理",id());
        assertEquals("ACTIVE",conversation(cid).get("status"));json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        directNativeEntry("yht-store:yuyao",epoch());assertEquals("RESOLVED",conversation(cid).get("status"));assertEquals(1,d.count("SELECT COUNT(*) FROM chat_conversation"));
        nativeText("这是新的问题",epoch(),"native-after-end");String next=str(d.one("SELECT conversation_id FROM chat_route WHERE openid=?",openid),"conversationId");
        assertNotEquals(cid,next);assertEquals("OPEN",conversation(next).get("status"));assertEquals("",str(conversation(next),"assignedAdminId"));
        response(send(post("/api/admin/chats/"+cid+"/messages"),manager,message("旧接待不能继续发",id())),409);
        response(send(post("/api/admin/chats/"+next+"/messages"),manager,message("新会话先接入",id())),409);
        assertEquals("SENT",reply(next,"新咨询已接待",id()).get("deliveryStatus"));
        verify(wx,times(2)).sendCustomerText(eq(openid),anyString());
    }

    @Test void delayedOldNativeTextIsPreservedWithoutReopeningOrCreatingUnread()throws Exception{
        String cid=nativeEntry("yuyao",epoch()-60);accept(cid);json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        nativeText("延迟到达的旧消息",epoch()-20,"late-native-before-end");
        assertEquals("RESOLVED",conversation(cid).get("status"));assertEquals(1,d.count("SELECT COUNT(*) FROM chat_conversation"));
        assertEquals(1,items(json(send(get("/api/admin/chats/"+cid+"/messages"),manager,null),200)).size());
        assertEquals(0,integer(json(send(get("/api/admin/chats/unread"),manager,null),200),"unreadTotal",-1));
        verify(wx,never()).sendCustomerText(anyString(),anyString());
    }

    @Test void sendingWechatReplyCannotBeEndedOrHandedOffMidFlight()throws Exception{
        String cid=nativeEntry("yuyao",epoch());accept(cid);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(wx.sendCustomerText(anyString(),anyString())).thenAnswer(i->{entered.countDown();assertTrue(finish.await(10,TimeUnit.SECONDS));return Map.of("errcode",0);});
        var pool=Executors.newSingleThreadExecutor();try{
            var sending=pool.submit(()->reply(cid,"正在发送的回复",id()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(String action:List.of("end","release"))response(send(post("/api/admin/chats/"+cid+"/"+action),manager,null),409);
            finish.countDown();assertEquals("SENT",sending.get(10,TimeUnit.SECONDS).get("deliveryStatus"));
            json(send(post("/api/admin/chats/"+cid+"/end"),manager,null),200);
        }finally{finish.countDown();pool.shutdownNow();}
    }

    @Test void lifecycleMigrationPreservesHistoryAndEnforcesOneLiveConversation()throws Exception{
        String url="jdbc:h2:mem:yht_chat_upgrade_"+id()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").target("7").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()){
            sql.execute("INSERT INTO chat_conversation(id,openid,channel,store_id,display_name,status,last_preview,created_at,updated_at) VALUES('old-ended','wx-old','WEB','yuyao','原客户','RESOLVED','原内容','2026-11-01T10:00:00+08:00','2026-11-01T10:02:00+08:00')");
            sql.execute("INSERT INTO chat_message(id,conversation_id,direction,kind,content,delivery_status,sender_name,created_at) VALUES('old-message','old-ended','IN','text','原消息','RECEIVED','客户','2026-11-01T10:00:00+08:00')");
        }
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()){
            try(var r=sql.executeQuery("SELECT c.status,c.active_key,c.ended_at,m.content FROM chat_conversation c JOIN chat_message m ON m.conversation_id=c.id WHERE c.id='old-ended'")){assertTrue(r.next());assertEquals("RESOLVED",r.getString(1));assertNull(r.getString(2));assertEquals("2026-11-01T10:02:00+08:00",r.getString(3));assertEquals("原消息",r.getString(4));}
            String insert="INSERT INTO chat_conversation(id,openid,channel,store_id,display_name,status,last_preview,created_at,updated_at) VALUES('%s','wx-old','WEB','yuyao','原客户','OPEN','','2026-11-02','2026-11-02')";
            sql.execute(insert.formatted("new-live"));assertThrows(java.sql.SQLException.class,()->sql.execute(insert.formatted("duplicate-live")));
        }
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
