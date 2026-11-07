package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_rbac_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "app.mode=local","app.data-dir=target/isolated-rbac-test-data",
    "app.jwt-secret=isolated-rbac-tests-only-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired Bootstrap bootstrap; @Autowired CatalogService catalog;
    Map<String,List<String>> originalRoles=new LinkedHashMap<>();
    Map<String,Object> originalSite;
    String hq;

    @BeforeAll void rememberDefaults(){
        for(String role:List.of("hq","manager","frontdesk"))
            originalRoles.put(role,d.rows("SELECT permission_code FROM role_permission WHERE role_id=?",role).stream().map(p->str(p,"permissionCode")).toList());
        originalSite=d.one("SELECT * FROM site_config WHERE id='brand'");
    }
    @BeforeEach void reset(){catalog.invalidateSite();
        for(var role:originalRoles.entrySet()){
            d.update("DELETE FROM role_permission WHERE role_id=?",role.getKey());
            for(String permission:role.getValue())d.update("INSERT INTO role_permission VALUES(?,?)",role.getKey(),permission);
        }
        d.update("DELETE FROM store_service");
        d.update("UPDATE site_config SET revision=?,draft_json=?,published_json=? WHERE id='brand'",originalSite.get("revision"),originalSite.get("draftJson"),originalSite.get("publishedJson"));
        hq=token("hq");
    }
    String token(String user){return auth.token(user,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",user),"tokenVersion",0));}
    ResultActions send(MockHttpServletRequestBuilder req,String token,Object input)throws Exception{
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(input!=null)req.contentType("application/json").content(d.stringify(input));
        return mvc.perform(req);
    }
    String body(ResultActions result,int status)throws Exception{
        var response=result.andReturn().getResponse();String body=response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status,response.getStatus(),body);return body;
    }
    Map<String,Object> json(ResultActions result,int status)throws Exception{return d.object(body(result,status));}
    List<Map<String,Object>> list(ResultActions result)throws Exception{
        return d.json.readValue(body(result,200),new com.fasterxml.jackson.core.type.TypeReference<>(){});
    }
    Map<String,Object> role(String id,String scope,List<String> permissions){return Map.of("id",id,"name","测试岗位 "+id,"dataScope",scope,"permissions",permissions);}
    void configure(String id,String...permissions)throws Exception{json(send(put("/api/admin/roles"),hq,role(id,"STORE",List.of(permissions))),200);}
    String newRole(String scope,String...permissions)throws Exception{
        String name="rbac-"+id();var input=new LinkedHashMap<>(role("",scope,List.of(permissions)));input.put("name",name);
        json(send(put("/api/admin/roles"),hq,input),200);
        return str(d.one("SELECT id FROM app_role WHERE name=?",name),"id");
    }
    String newUser(String store,String...roles)throws Exception{
        String username="rbac-"+id().substring(0,12);
        json(send(put("/api/admin/users"),hq,Map.of("id","","username",username,"displayName","权限测试岗位","password","Yht@9800dev","storeId",store,"enabled",true,"roleIds",List.of(roles))),200);
        return str(d.one("SELECT id FROM app_user WHERE username=?",username),"id");
    }

    @Test void headquartersCanReconfigureBuiltinStoreRolesAndEnforceChangesAcrossStores()throws Exception{
        String oldYuyao=token("yuyao-manager"),oldBai=token("bai-manager");
        configure("manager","services:read");
        json(send(get("/api/admin/me"),oldYuyao,null),401);
        json(send(get("/api/admin/me"),oldBai,null),401);
        String manager=token("yuyao-manager");
        var services=list(send(get("/api/admin/stores/yuyao/services"),manager,null));
        assertFalse(services.isEmpty());
        json(send(put("/api/admin/stores/yuyao/services/moxa"),manager,services.get(0)),403);
        json(send(get("/api/admin/stores/bai-le-men/services"),manager,null),404);
        json(send(put("/api/admin/roles"),manager,role("frontdesk","STORE",List.of("services:write"))),403);

        configure("manager","services:write"); // Server includes the required read permission.
        manager=token("yuyao-manager");
        var me=json(send(get("/api/admin/me"),manager,null),200);
        assertTrue(d.strings(me.get("permissions")).containsAll(List.of("services:read","services:write")));
        var item=new LinkedHashMap<>(list(send(get("/api/admin/stores/yuyao/services"),manager,null)).get(0));
        String service=str(item,"id");String otherName=str(catalog.storeServices("bai-le-men",false).stream().filter(s->service.equals(str(s,"id"))).findFirst().orElseThrow(),"name");
        item.put("name","余姚独立维护验证");
        json(send(put("/api/admin/stores/yuyao/services/"+service),manager,item),200);
        assertTrue(catalog.storeServices("yuyao",true).stream().anyMatch(s->"余姚独立维护验证".equals(s.get("name"))));
        assertEquals(otherName,str(catalog.storeServices("bai-le-men",false).stream().filter(s->service.equals(str(s,"id"))).findFirst().orElseThrow(),"name"));
    }

    @Test void revokingAllFrontdeskPermissionsPersistsAcrossBootstrapAndBlocksDirectCalls()throws Exception{
        String old=token("yuyao-frontdesk");configure("frontdesk");
        json(send(get("/api/admin/me"),old,null),401);
        bootstrap.run(); // Restart initialization must not restore revoked defaults.
        String fresh=token("yuyao-frontdesk");
        assertTrue(d.strings(json(send(get("/api/admin/me"),fresh,null),200).get("permissions")).isEmpty());
        for(String path:List.of("/api/admin/dashboard","/api/admin/content","/api/admin/stores/yuyao/services","/api/admin/appointments","/api/admin/chats"))
            json(send(get(path),fresh,null),403);
        json(send(put("/api/admin/stores/yuyao/services"),fresh,Map.of("items",List.of())),403);
        assertEquals(1,list(send(get("/api/admin/stores"),fresh,null)).size());
    }

    @Test void headquartersOnlyPermissionsAndBuiltinRecoveryRoleCannotBeEscalated()throws Exception{
        for(String permission:RbacPolicy.HEADQUARTERS_ONLY)
            json(send(put("/api/admin/roles"),hq,role("frontdesk","STORE",List.of(permission))),400);
        json(send(put("/api/admin/roles"),hq,role("hq","GLOBAL",List.of())),400);
        json(send(put("/api/admin/roles"),hq,role("manager","GLOBAL",List.of("services:read"))),400);
        json(send(put("/api/admin/roles"),hq,role("manager","STORE",List.of("unknown:write"))),400);
        // Scope remains a separate enforcement boundary even for a legacy malformed role.
        d.update("INSERT INTO role_permission VALUES('manager','roles:write')");
        json(send(put("/api/admin/roles"),token("yuyao-manager"),role("frontdesk","STORE",List.of())),403);
        assertEquals(originalRoles.get("hq").size(),d.count("SELECT COUNT(*) FROM role_permission WHERE role_id='hq'"));
    }

    @Test void storeBrandPreviewCannotReadHeadquartersDraftOrEditGlobalContent()throws Exception{
        var draft=json(send(get("/api/admin/content"),hq,null),200);
        String publishedName=str(catalog.site(),"brandName");draft.put("brandName","尚未发布的总部品牌");
        var saved=json(send(put("/api/admin/content/draft"),hq,draft),200);
        configure("frontdesk","content:read");String desk=token("yuyao-frontdesk");
        assertEquals(publishedName,json(send(get("/api/admin/content"),desk,null),200).get("brandName"));
        json(send(put("/api/admin/content/draft"),desk,saved),403);
        json(send(put("/api/admin/content/publish"),desk,saved),403);
        json(send(get("/api/admin/stores/yuyao/services"),desk,null),403);
    }

    @Test void headquartersPublishingAndEditingCanBeAssignedToSeparateRoles()throws Exception{
        String editor=newUser("",newRole("GLOBAL","content:write"));
        String publisher=newUser("",newRole("GLOBAL","content:publish"));
        var draft=json(send(get("/api/admin/content"),token(editor),null),200);draft.put("brandName","总部已审核草稿");
        var saved=json(send(put("/api/admin/content/draft"),token(editor),draft),200);
        json(send(put("/api/admin/content/publish"),token(editor),saved),403);
        var forged=new LinkedHashMap<>(saved);forged.put("brandName","试图绕过编辑权限");
        json(send(put("/api/admin/content/draft"),token(publisher),forged),403);
        var published=json(send(put("/api/admin/content/publish"),token(publisher),forged),200);
        assertEquals("总部已审核草稿",published.get("brandName"));
        assertEquals("总部已审核草稿",catalog.site().get("brandName"));
        json(send(put("/api/admin/content/publish"),token(publisher),saved),409);
    }

    @Test void customStoreRolesUseUnionOfPermissionsAndKeepStoreScopeAfterRevocation()throws Exception{
        String first=newRole("STORE","services:write"),second=newRole("STORE","services:read");
        String user=newUser("yuyao",first,second),old=token(user);
        configure(first);json(send(get("/api/admin/me"),old,null),401);
        String fresh=token(user);var me=json(send(get("/api/admin/me"),fresh,null),200);
        assertFalse(bool(me,"hq"));assertEquals(List.of("services:read"),d.strings(me.get("permissions")));
        list(send(get("/api/admin/stores/yuyao/services"),fresh,null));
        json(send(get("/api/admin/stores/bai-le-men/services"),fresh,null),404);
        json(send(put("/api/admin/stores/yuyao/services"),fresh,Map.of("items",List.of())),403);
        json(send(put("/api/admin/roles"),hq,role(second,"GLOBAL",List.of("services:read"))),409);
    }

    @Test void permissionCatalogExplainsContentModuleScopeAndDependencies()throws Exception{
        var all=list(send(get("/api/admin/permissions"),hq,null));
        var write=all.stream().filter(p->"services:write".equals(p.get("code"))).findFirst().orElseThrow();
        assertEquals("品牌与内容",write.get("module"));assertEquals(List.of("services:read"),write.get("requires"));assertFalse(bool(write,"headquartersOnly"));
        var publish=all.stream().filter(p->"content:publish".equals(p.get("code"))).findFirst().orElseThrow();
        assertTrue(bool(publish,"headquartersOnly"));assertEquals(List.of("content:read"),publish.get("requires"));
    }
}
