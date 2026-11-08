package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs real migrations, production bootstrap, authentication and XLSX imports in a disposable DB.
 * This fixture calls these services directly; it does not relax ProductionChecks or validate MySQL.
 */
class ProductionBootstrapTest {
    static final String PASSWORD="Production-test-only-password";
    static final String RESULT_MAPPING="{\"name1\":\"customerName\",\"date2\":\"time\",\"thing3\":\"serviceName\",\"thing4\":\"storeName\",\"phrase5\":\"status\"}";
    static final String REMINDER_MAPPING="{\"thing31\":\"customerName\",\"time2\":\"time\",\"thing7\":\"serviceName\",\"thing8\":\"storeName\",\"phrase11\":\"status\"}";
    Data d;AuthService auth;Bootstrap bootstrap;CatalogService catalog;AdminService admin;FilesApi files;
    TransactionTemplate tx;

    @BeforeEach void setUp()throws Exception{
        var source=new DriverManagerDataSource("jdbc:h2:mem:clean_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        d=new Data(new JdbcTemplate(source),new ObjectMapper());
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        auth=new AuthService(d,"production","production-bootstrap-test-secret-more-than-32-characters","target/unused-clean-bootstrap",manager);
        auth.init();bootstrap=new Bootstrap(d,auth,PASSWORD,RESULT_MAPPING,REMINDER_MAPPING);
        catalog=new CatalogService(d,new BookingLocks(d));
        admin=new AdminService(d,auth,catalog,new BookingService(d,catalog,null));
        files=new FilesApi(d,auth,admin,catalog,"target/unused-clean-bootstrap");
        start(bootstrap);
    }
    void start(Bootstrap instance){tx.executeWithoutResult(status->{try{instance.run();}catch(Exception e){throw new IllegalStateException(e);}});}
    MockHttpServletRequest request(){
        var request=new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer "+auth.login(Map.of("username","admin","password",PASSWORD)).get("token"));
        return request;
    }
    Admin hq(){return auth.admin(request());}
    Map<String,Object> imported(String kind,boolean commit,List<List<String>> rows){
        return tx.execute(status->{try{return files.importData(request(),kind,commit,sheet(kind,rows));}catch(IOException e){throw new UncheckedIOException(e);}});
    }
    MockMultipartFile sheet(String kind,List<List<String>> rows)throws IOException{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("资料");var headers=FilesApi.HEADERS.get(kind);var head=sheet.createRow(0);
            for(int i=0;i<headers.size();i++)head.createCell(i).setCellValue(headers.get(i));
            for(int i=0;i<rows.size();i++)for(int j=0;j<rows.get(i).size();j++){
                var row=sheet.getRow(i+1);if(row==null)row=sheet.createRow(i+1);
                row.createCell(j).setCellValue(rows.get(i).get(j));
            }
            book.write(out);return new MockMultipartFile("file","import.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }
    List<String> store(String name){return List.of("real-store",name,"静安区","真实门店地址","021-12345678","10:00–20:00","");}

    @Test void productionStartsWithNoBusinessRowsAndAWorkingHeadquartersLogin(){
        assertEquals(1,d.count("SELECT COUNT(*) FROM app_user"));assertEquals(3,d.count("SELECT COUNT(*) FROM app_role"));
        assertTrue(hq().hq());assertTrue(hq().permissions().containsAll(List.of("imports:write","content:write","content:publish","chat:reply")));
        var site=d.object(d.one("SELECT published_json FROM site_config WHERE id='brand'").get("publishedJson"));
        for(String key:List.of("services","stores","products","galleryGroups","assetLibrary"))assertTrue(catalog.collection(site,key).isEmpty(),key);
        assertEquals("",str(site,"brandStory"));assertEquals("",str(d.object(site.get("hero")),"image"));
        Set<String> systemTables=Set.of("flyway_schema_history","permission","app_role","role_permission","app_user","user_role","site_config","app_setting","booking_guard","job_lease","wechat_service_menu");
        for(var row:d.rows("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")){
            String table=str(row,"tableName");if(!systemTables.contains(table))assertEquals(0,d.count("SELECT COUNT(*) FROM "+table),table);
        }
        assertEquals(RESULT_MAPPING,d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerTemplateMapping'").get("settingValue"));
        assertEquals(REMINDER_MAPPING,d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerReminderTemplateMapping'").get("settingValue"));
        assertTrue(catalog.collection(catalog.site(),"stores").isEmpty());
    }

    @Test void emptyBusinessContentCanBeSavedAsDraftBeforeServicesAreImported(){
        var draft=admin.content(hq());draft.put("brandStory","总部逐步维护的真实品牌介绍");
        tx.executeWithoutResult(status->admin.saveContent(hq(),draft,false));
        assertEquals("总部逐步维护的真实品牌介绍",str(admin.content(hq()),"brandStory"));
        assertEquals("",str(catalog.site(),"brandStory"));
        var error=assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(status->admin.saveContent(hq(),admin.content(hq()),true)));
        assertEquals(400,error.getStatusCode().value());
    }

    @Test void storePreviewIsReadOnlyAndCommitCreatesThenUpdatesWithoutOpeningBookings(){
        var preview=imported("stores",false,List.of(store("真实一店")));
        assertEquals(true,preview.get("valid"));assertEquals(0,preview.get("imported"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM store"));assertEquals(0,d.count("SELECT COUNT(*) FROM audit_log"));
        assertEquals(1,imported("stores",true,List.of(store("真实一店"))).get("imported"));
        var profile=d.object(catalog.store("real-store").get("profileJson"));
        assertTrue(bool(profile,"enabled"));assertFalse(bool(profile,"onlineBooking"));
        assertTrue(d.strings(profile.get("gallery")).isEmpty());assertEquals(4,integer(catalog.rules("real-store"),"capacity",0));
        profile.put("onlineBooking",true);tx.executeWithoutResult(status->admin.saveStore(hq(),"real-store",profile));
        imported("stores",true,List.of(store("真实门店改名")));
        assertEquals(1,d.count("SELECT COUNT(*) FROM store"));
        assertEquals("真实门店改名",str(catalog.store("real-store"),"name"));
        assertTrue(bool(d.object(catalog.store("real-store").get("profileJson")),"onlineBooking"));
        assertEquals(1,catalog.collection(catalog.site(),"stores").size());
    }

    @Test void invalidStoreBatchCannotPartiallyCreateBusinessData(){
        var bad=new ArrayList<>(store("错误电话"));bad.set(0,"bad-store");bad.set(4,"错误电话");
        var rows=List.of(store("真实一店"),bad);
        var preview=imported("stores",false,rows);assertEquals(false,preview.get("valid"));
        assertThrows(ResponseStatusException.class,()->imported("stores",true,rows));
        assertEquals(0,d.count("SELECT COUNT(*) FROM store"));assertEquals(0,d.count("SELECT COUNT(*) FROM audit_log"));
        assertEquals(false,imported("stores",false,List.of(store("重复一店"),store("重复二店"))).get("valid"));
        var badId=new ArrayList<>(store("无效编号"));badId.set(0,"../invalid");
        assertEquals(false,imported("stores",false,List.of(badId)).get("valid"));
    }

    @Test void freshInstallationSupportsManualStoreServiceTherapistAndCustomerImports(){
        imported("stores",true,List.of(store("真实一店")));
        imported("services",true,List.of(List.of("real-service","真实养护项目","项目简介","养护","60分钟","","真实项目介绍","true")));
        assertTrue(catalog.collection(catalog.site(),"services").isEmpty());
        tx.executeWithoutResult(status->admin.saveContent(hq(),admin.content(hq()),true));
        assertEquals("真实养护项目",str(catalog.service("real-service"),"name"));
        imported("therapists",true,List.of(List.of("real-therapist","真实调理师","real-store","调理师","养护","real-service","true","true","false")));
        imported("members",true,List.of(List.of("真实客户","13900001111","real-store","true","普通会员","client-001")));
        assertEquals(1,d.count("SELECT COUNT(*) FROM therapist"));assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM store_customer"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM appointment"));assertEquals(1,d.count("SELECT COUNT(*) FROM app_user"));
    }

    @Test void restartKeepsImportedDataAdministratorChangesAndEditedTemplateMappings(){
        imported("stores",true,List.of(store("重启保留门店")));
        String changed=auth.passwords.encode("Changed-administrator-password");
        d.update("UPDATE app_user SET password_hash=? WHERE id='hq'",changed);
        d.update("UPDATE app_setting SET setting_value=? WHERE setting_key='customerTemplateMapping'","{\"thing1\":\"storeName\"}");
        var draft=admin.content(new Admin("hq","总部管理员","",Set.of("hq"),Set.of("content:write"),true));
        draft.put("brandStory","保留已录入资料");
        tx.executeWithoutResult(status->admin.saveContent(new Admin("hq","总部管理员","",Set.of("hq"),Set.of("content:write"),true),draft,false));
        String previous=d.stringify(d.one("SELECT * FROM site_config WHERE id='brand'"));
        start(bootstrap);
        assertEquals(1,d.count("SELECT COUNT(*) FROM store"));assertEquals(1,d.count("SELECT COUNT(*) FROM app_user"));
        assertEquals(changed,d.one("SELECT password_hash FROM app_user WHERE id='hq'").get("passwordHash"));
        assertEquals(previous,d.stringify(d.one("SELECT * FROM site_config WHERE id='brand'")));
        assertEquals("{\"thing1\":\"storeName\"}",d.one("SELECT setting_value FROM app_setting WHERE setting_key='customerTemplateMapping'").get("settingValue"));
    }

    @Test void localDevelopmentSeedRemainsAvailableInItsOwnDatabase()throws Exception{
        var localAuth=new AuthService(d,"local","local-fixture-secret-more-than-32-characters","target/unused-clean-bootstrap",tx.getTransactionManager());
        var seed=new Bootstrap(d,localAuth,PASSWORD,"","");
        var site=seed.initialSite();assertFalse(catalog.collection(site,"services").isEmpty());
        assertFalse(catalog.collection(site,"assetLibrary").isEmpty());
        // Exercise the same initialisation on this disposable fixture, never on the local file DB.
        d.update("DELETE FROM user_role");d.update("DELETE FROM app_user");d.update("DELETE FROM role_permission");d.update("DELETE FROM app_role");
        d.update("DELETE FROM site_config");d.update("DELETE FROM app_setting WHERE setting_key IN ('bookingLock','sharedLookupEnabled')");
        start(seed);assertEquals(9,d.count("SELECT COUNT(*) FROM store"));
        assertEquals(27,d.count("SELECT COUNT(*) FROM therapist"));assertEquals(4,d.count("SELECT COUNT(*) FROM app_user"));
    }
}
