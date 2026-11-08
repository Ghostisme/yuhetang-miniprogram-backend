package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_wechat_menu_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-wechat-menu-test-data","app.jwt-secret=isolated-wechat-menu-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=wxce054e18303efb8a","app.wechat.secret=",
    "app.wechat.service-app-id=wxf9ad4dfd7684097a","app.wechat.service-secret=fixture-service-secret",
    "app.wechat.customer-template=","app.wechat.service-template=","app.sms.secret-id=","app.sms.secret-key="
})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatMenuIntegrationTest {
    @Autowired MockMvc mvc;@Autowired Data d;@Autowired AuthService auth;@Autowired WechatMenuService service;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @MockitoBean WechatMenuGateway gateway;
    String hq,branch;static final String BASE="/api/admin/wechat-menu";
    @BeforeAll void noJobs(){scheduler.destroy();}
    @BeforeEach void resetFixture(){
        reset(gateway);
        d.update("UPDATE wechat_service_menu SET revision=0,draft_json=?,saved_at='',published_json=NULL,published_at=NULL,backup_json=NULL,backup_at=NULL WHERE id='default'",d.stringify(Map.of("button",List.of())));
        for(String permission:List.of("wechat-menu:read","wechat-menu:write","wechat-menu:publish")){
            d.update("DELETE FROM role_permission WHERE role_id IN ('hq','manager') AND permission_code=?",permission);
            d.update("INSERT INTO role_permission VALUES('hq',?)",permission);
        }
        d.update("DELETE FROM audit_log WHERE action LIKE 'wechat-menu.%'");
        hq=auth.token("hq","ADMIN",0);branch=auth.token("yuyao-manager","ADMIN",0);
    }
    Map<String,Object> request(MockHttpServletRequestBuilder req,String token,Object body,int status)throws Exception{
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(body!=null)req.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(req).andReturn().getResponse();String value=response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status,response.getStatus(),value);return d.object(value);
    }
    Map<String,Object> menu(String path){return Map.of("button",List.of(Map.of("name","在线预约","type","miniprogram","appid","wxce054e18303efb8a","pagepath",path,"url","https://www.yuhetangtcm.com")));}
    Map<String,Object> oldMenu(){return Map.of("button",List.of(Map.of("name","旧官网","type","view","url","https://example.com/old")));}
    Map<String,Object> save(Map<String,Object> menu,int version)throws Exception{return request(put(BASE+"/draft"),hq,Map.of("revision",version,"menu",menu),200);}
    void online(Map<String,Object> menu){when(gateway.read()).thenReturn(Map.of("menu",menu,"exists",true,"conditionalCount",1));}
    Map<String,Object> row(){return d.one("SELECT * FROM wechat_service_menu WHERE id='default'");}

    @Test void loadAndDraftSaveNeverCallWechatAndDoNotExposeCredentials()throws Exception{
        var state=request(get(BASE),hq,null,200);assertEquals("wxce054e18303efb8a",state.get("miniAppId"));assertTrue(bool(state,"credentialsConfigured"));assertFalse(d.stringify(state).contains("fixture-service-secret"));
        var next=save(menu("pages/booking/index"),0);assertEquals(1,integer(next,"revision",0));assertEquals(menu("pages/booking/index"),next.get("draft"));assertEquals("",next.get("publishedAt"));assertNull(row().get("backupJson"));verifyNoInteractions(gateway);
    }
    @Test void accessRequiresLoginHeadquartersAndExplicitReadWritePublishPermissions()throws Exception{
        request(get(BASE),null,null,401);request(get(BASE),branch,null,403);
        d.update("INSERT INTO role_permission VALUES('manager','wechat-menu:read')");request(get(BASE),branch,null,403);
        d.update("DELETE FROM role_permission WHERE role_id='hq' AND permission_code IN ('wechat-menu:write','wechat-menu:publish')");
        request(get(BASE),hq,null,200);request(put(BASE+"/draft"),hq,Map.of("revision",0,"menu",menu("pages/home/index")),403);request(post(BASE+"/publish"),hq,Map.of("revision",0),403);
        var invalid=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->RbacPolicy.normalize(d,List.of("wechat-menu:publish"),"STORE"));assertEquals(400,invalid.getStatusCode().value());verifyNoInteractions(gateway);
    }
    @Test void staleRevisionsCannotOverwriteDraftOrPublishIt()throws Exception{
        save(menu("pages/home/index"),0);request(put(BASE+"/draft"),hq,Map.of("revision",0,"menu",oldMenu()),409);request(post(BASE+"/publish"),hq,Map.of("revision",0),409);assertEquals(menu("pages/home/index"),d.object(row().get("draftJson")));verifyNoInteractions(gateway);
    }
    @Test void invalidStructureLinksAndUtf8NamesAreRejectedBeforeAnyWechatCall()throws Exception{
        var button=new LinkedHashMap<>(Map.<String,Object>of("name","在线预约","type","miniprogram","appid","wxce054e18303efb8a","pagepath","pages/home/index","url","https://example.com"));
        for(var invalid:List.of(Map.entry("name","一级菜单名称过长"),Map.entry("appid","not-a-mini-app"),Map.entry("pagepath","https://example.com/page"),Map.entry("url","javascript:alert(1)"))){var next=new LinkedHashMap<>(button);next.put(invalid.getKey(),invalid.getValue());request(put(BASE+"/draft"),hq,Map.of("revision",0,"menu",Map.of("button",List.of(next))),400);}
        request(put(BASE+"/draft"),hq,Map.of("revision",0,"menu",Map.of("button",Collections.nCopies(4,button))),400);
        var nested=Map.of("name","菜单","sub_button",List.of(Map.of("name","子菜单","sub_button",List.of(button))));request(put(BASE+"/draft"),hq,Map.of("revision",0,"menu",Map.of("button",List.of(nested))),400);verifyNoInteractions(gateway);
    }
    @Test void incompleteDraftsCanBeSavedButCannotBePublished()throws Exception{
        save(menu(""),0);request(post(BASE+"/publish"),hq,Map.of("revision",1),400);verifyNoInteractions(gateway);
        save(Map.of("button",List.of(Map.of("name","菜单","sub_button",List.of()))),1);request(post(BASE+"/publish"),hq,Map.of("revision",2),400);verifyNoInteractions(gateway);
    }
    @Test void publicationBacksUpDefaultMenuAndRestoreOnlyChangesTheDraft()throws Exception{
        var nextMenu=menu("pages/booking/index");save(nextMenu,0);online(oldMenu());
        var result=request(post(BASE+"/publish"),hq,Map.of("revision",1),200);assertTrue(bool(result,"accepted"));assertTrue(bool(result,"hasBackup"));assertEquals(1,integer(result,"conditionalCount",0));assertFalse(str(result,"publishedAt").isBlank());
        var order=inOrder(gateway);order.verify(gateway).read();order.verify(gateway).publish(nextMenu);
        assertEquals(oldMenu(),d.object(d.object(row().get("backupJson")).get("menu")));assertEquals(nextMenu,d.object(row().get("publishedJson")));
        clearInvocations(gateway);var restored=request(post(BASE+"/restore-draft"),hq,Map.of("revision",1),200);assertEquals(oldMenu(),restored.get("draft"));assertEquals(2,integer(restored,"revision",0));assertEquals(nextMenu,d.object(row().get("publishedJson")));verifyNoInteractions(gateway);
    }
    @Test void failedPublicationPreservesBackupDraftAndLastSuccessWithoutClaimingSuccess()throws Exception{
        save(menu("pages/home/index"),0);online(oldMenu());doThrow(error(502,"微信接口响应超时，发布结果尚未确认。")).when(gateway).publish(anyMap());
        request(post(BASE+"/publish"),hq,Map.of("revision",1),502);var after=row();assertNull(after.get("publishedAt"));assertNull(after.get("publishedJson"));assertNotNull(after.get("backupJson"));assertEquals(menu("pages/home/index"),d.object(after.get("draftJson")));assertEquals(1,d.count("SELECT COUNT(*) FROM audit_log WHERE action='wechat-menu.publish-failed'"));
    }
    @Test void backupFailurePreventsThePublicationRequest()throws Exception{
        save(menu("pages/home/index"),0);when(gateway.read()).thenThrow(error(502,"无法读取微信菜单。"));request(post(BASE+"/publish"),hq,Map.of("revision",1),502);verify(gateway,never()).publish(anyMap());assertNull(row().get("backupJson"));assertNull(row().get("publishedAt"));
    }
    @Test void remoteReadPreservesOldEventsAndNestedMenusWithoutSavingThem()throws Exception{
        var old=Map.<String,Object>of("button",List.of(Map.of("name","服务","sub_button",List.of(Map.of("name","联系我们","type","click","key","contact-old","sub_button",List.of())))),"menuid",123);
        online(old);var result=request(get(BASE+"/remote"),hq,null,200);assertEquals(1,integer(result,"conditionalCount",0));assertFalse(d.stringify(result.get("menu")).contains("menuid"));assertTrue(d.stringify(result).contains("contact-old"));assertEquals(0,integer(row(),"revision",-1));verify(gateway,never()).publish(anyMap());
    }
}
