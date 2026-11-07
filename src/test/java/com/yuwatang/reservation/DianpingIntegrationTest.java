package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_dianping_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-dianping-test-data","app.jwt-secret=isolated-dianping-secret-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template=",
    "app.sms.secret-id=","app.sms.secret-key="
})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DianpingIntegrationTest {
    @Autowired MockMvc mvc;@Autowired Data d;@Autowired AuthService auth;@Autowired CatalogService catalog;
    @Autowired DianpingPort port;@Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    String hq,manager,frontdesk,other;
    @BeforeAll void noExternalDispatch(){scheduler.destroy();}
    @BeforeEach void reset(){
        for(String table:List.of("dianping_order","dianping_import_batch","dianping_customer_link","dianping_service_mapping","dianping_store_mapping","notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile","store_service"))d.update("DELETE FROM "+table);
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));
        hq=token("hq");manager=token("yuyao-manager");frontdesk=token("yuyao-frontdesk");other=token("bai-manager");
    }
    String token(String id){return auth.token(id,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",id),"tokenVersion",0));}
    Map<String,Object> request(MockHttpServletRequestBuilder request,String token,Object body,int status)throws Exception{
        if(token!=null)request.header("Authorization","Bearer "+token);if(body!=null)request.contentType("application/json").content(d.stringify(body));
        var response=mvc.perform(request).andReturn().getResponse();String result=response.getContentAsString(StandardCharsets.UTF_8);assertEquals(status,response.getStatus(),result);return d.object(result);
    }
    void mappings()throws Exception{mapping("shop-yuyao","yuyao","moxa");}
    void mapping(String shop,String store,String service)throws Exception{
        request(put("/api/admin/dianping/shops"),hq,Map.of("externalShopId",shop,"externalShopName","点评测试门店","storeId",store,"revision",0),200);
        request(put("/api/admin/dianping/products"),hq,Map.of("externalShopId",shop,"externalProductId","product-1","externalProductName","点评艾灸套餐","serviceId",service,"revision",0),200);
    }
    Map<String,Object> customer(String phone){return new LinkedHashMap<>(Map.of("externalShopId","shop-yuyao","externalCustomerId","customer-1","name","点评测试客户","phone",phone));}
    Map<String,Object> order(String id){
        Map<String,Object> row=new LinkedHashMap<>();row.put("externalShopId","shop-yuyao");row.put("externalOrderId",id);row.put("externalLineId","");row.put("externalProductId","product-1");row.put("name","点评订单客户");row.put("phone","13900003501");row.put("status","已付款");row.put("amountYuan","128.50");row.put("headcount","1");row.put("bookingDate","");row.put("startTime","");row.put("createdAt","2026-01-01 10:00:00");row.put("updatedAt","2026-01-01 10:00:00");return row;
    }
    byte[] workbook(String kind,List<Map<String,Object>> rows,boolean formula)throws Exception{
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var sheet=book.createSheet("资料");var headers=DianpingService.headers(DianpingService.kind(kind));var first=sheet.createRow(0);for(int c=0;c<headers.size();c++)first.createCell(c).setCellValue(headers.get(c));
            int n=1;for(var values:rows){var row=sheet.createRow(n++);for(int c=0;c<headers.size();c++)row.createCell(c).setCellValue(str(values,headers.get(c)));}
            if(formula)sheet.getRow(1).getCell(0).setCellFormula("1+1");book.write(bytes);return bytes.toByteArray();
        }
    }
    Map<String,Object> upload(String kind,List<Map<String,Object>> rows,boolean commit,String token,int status)throws Exception{
        return request(multipart("/api/admin/dianping/imports/"+kind).file(new MockMultipartFile("file","dianping.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(kind,rows,false))).param("commit",Boolean.toString(commit)),token,null,status);
    }
    Map<String,Object> storedOrder(){return d.one("SELECT * FROM dianping_order");}
    Map<String,Object> conversion(Map<String,Object> order){return new LinkedHashMap<>(Map.of("revision",order.get("revision"),"date",LocalDate.now(ZONE).plusDays(1).toString(),"startTime","10:00","headcount",1));}
    Map<String,Object> convert(Map<String,Object> row,String token,Map<String,Object> body,int status)throws Exception{return request(post("/api/admin/dianping/orders/"+row.get("id")+"/appointment"),token,body,status);}

    @Test void templateAndConfigurationEnforceHeadquartersRbac()throws Exception{
        request(get("/api/admin/dianping/config"),null,null,401);request(get("/api/admin/dianping/config"),manager,null,403);
        request(put("/api/admin/dianping/shops"),manager,Map.of("externalShopId","shop","storeId","yuyao","externalShopName","x","revision",0),403);
        upload("customers",List.of(customer("13900003501")),false,manager,403);
        var response=mvc.perform(get("/api/admin/dianping/imports/orders/template").header("Authorization","Bearer "+hq)).andReturn().getResponse();assertEquals(200,response.getStatus());
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))){assertEquals(DianpingService.ORDER_HEADERS.size(),book.getSheetAt(0).getRow(0).getPhysicalNumberOfCells());}
        var before=d.rows("SELECT * FROM role_permission ORDER BY role_id,permission_code");mappings();request(get("/api/admin/dianping/config"),hq,null,200);assertEquals(before,d.rows("SELECT * FROM role_permission ORDER BY role_id,permission_code"));
        var revoked=new Admin("hq","总部",null,Set.of("hq"),Set.of(),true);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->port.ingest(revoked,DianpingPort.Kind.CUSTOMERS,List.of(),true,new DianpingPort.Source("adapter","")));
    }
    @Test void mappingUsesStoreSpecificItemsAndOptimisticRevision()throws Exception{
        var actor=new Admin("test","测试","yuyao",Set.of("manager"),Set.of("services:write"),false);
        var local=catalog.saveStoreService(actor,"yuyao","",Map.of("name","本店独立项目","enabled",true));
        mapping("shop-yuyao","yuyao",str(local,"id"));
        request(put("/api/admin/dianping/products"),hq,Map.of("externalShopId","shop-yuyao","externalProductId","wrong-store","externalProductName","x","serviceId","svc-no-such-item","revision",0),400);
        request(put("/api/admin/dianping/shops"),hq,Map.of("externalShopId","shop-yuyao","externalShopName","改名","storeId","yuyao","revision",0),409);
        request(put("/api/admin/dianping/shops"),hq,Map.of("externalShopId","shop-yuyao","externalShopName","跨店","storeId","bai-le-men","revision",1),409);
        upload("orders",List.of(order("DP-CUSTOM")),true,hq,200);assertEquals(local.get("id"),storedOrder().get("serviceId"));
        request(put("/api/admin/dianping/products"),hq,Map.of("externalShopId","shop-yuyao","externalProductId","product-1","externalProductName","改绑","serviceId","moxa","revision",1),409);
    }
    @Test void customerImportsPreserveVerifiedMembershipPrivateNotesAndWechatIdentity()throws Exception{
        mappings();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)","existing","13900003501","原客户",true,"金卡","yuyao","共享说明","old-system",now());
        d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)","yuyao","existing","保留的门店私有跟进","PHONE",null);
        var profile=d.one("SELECT * FROM customer_profile WHERE id='existing'");var relation=d.one("SELECT * FROM store_customer WHERE customer_profile_id='existing'");int identities=d.count("SELECT COUNT(*) FROM customer_identity");
        var preview=upload("customers",List.of(customer("13900003501")),false,hq,200);assertTrue(bool(preview,"valid"));assertEquals(0,d.count("SELECT COUNT(*) FROM dianping_customer_link"));
        var first=upload("customers",List.of(customer("13900003501")),true,hq,200);assertEquals(1,integer(first,"created",0));
        var second=upload("customers",List.of(customer("13900003501")),true,hq,200);assertEquals(1,integer(second,"skipped",0));assertEquals(0,integer(second,"imported",-1));assertTrue(bool(second,"committed"));
        assertEquals(profile,d.one("SELECT * FROM customer_profile WHERE id='existing'"));assertEquals(relation,d.one("SELECT * FROM store_customer WHERE customer_profile_id='existing'"));assertEquals(identities,d.count("SELECT COUNT(*) FROM customer_identity"));
        var changed=customer("13900003502");var invalid=upload("customers",List.of(changed),false,hq,200);assertFalse(bool(invalid,"valid"));upload("customers",List.of(changed),true,hq,400);assertEquals(1,d.count("SELECT COUNT(*) FROM customer_profile"));
    }
    @Test void importedCustomersAppearInTheirStoreOnly()throws Exception{
        mappings();upload("customers",List.of(customer("13900003501")),true,hq,200);
        assertEquals("DIANPING",d.one("SELECT * FROM store_customer").get("source"));assertEquals(1,integer(request(get("/api/admin/customers"),manager,null,200),"total",0));assertEquals(0,integer(request(get("/api/admin/customers"),other,null,200),"total",-1));
    }
    @Test void orderPreviewIsReadOnlyAndFailedBatchWritesNothing()throws Exception{
        mappings();var valid=order("DP-1");var invalid=order("DP-2");invalid.put("externalProductId","unmapped");
        var preview=upload("orders",List.of(valid,invalid),false,hq,200);assertFalse(bool(preview,"valid"));upload("orders",List.of(valid,invalid),true,hq,400);
        for(String table:List.of("dianping_order","dianping_import_batch","customer_profile","appointment","notification_outbox"))assertEquals(0,d.count("SELECT COUNT(*) FROM "+table));
        var result=upload("orders",List.of(valid),false,hq,200);assertTrue(bool(result,"valid"));assertFalse(d.stringify(result).contains("13900003501"));assertEquals(0,d.count("SELECT COUNT(*) FROM dianping_order"));
    }
    @Test void historicalOrdersAreArchivedWithoutOccupyingSlotsOrSendingNotifications()throws Exception{
        mappings();var row=order("DP-HISTORY");row.put("bookingDate","2025-01-01");row.put("startTime","14:30");row.put("status","已核销");row.put("phone","139****3501");
        upload("orders",List.of(row),true,hq,200);assertEquals(12850L,((Number)storedOrder().get("amountFen")).longValue());assertEquals("REDEEMED",storedOrder().get("orderStatus"));assertEquals(0,d.count("SELECT COUNT(*) FROM customer_profile"));
        for(String table:List.of("appointment","notification_outbox","pms_outbox"))assertEquals(0,d.count("SELECT COUNT(*) FROM "+table));convert(storedOrder(),manager,conversion(storedOrder()),409);
    }
    @Test void orderIdsAndLinesAreIdempotentAndUpdatesRequireNewerPlatformTimestamp()throws Exception{
        mappings();var first=order("DP-1");var second=order("DP-1");second.put("externalLineId","line-2");upload("orders",List.of(first,second),true,hq,200);
        var repeated=upload("orders",List.of(first,second),true,hq,200);assertEquals(2,integer(repeated,"skipped",0));assertEquals(2,d.count("SELECT COUNT(*) FROM dianping_order"));
        var updated=order("DP-1");updated.put("status","REFUNDED");upload("orders",List.of(updated),true,hq,400);updated.put("updatedAt","2026-01-02 10:00:00");
        var result=upload("orders",List.of(updated),true,hq,200);assertEquals(1,integer(result,"updated",0));assertEquals("REFUNDED",d.one("SELECT * FROM dianping_order WHERE external_line_id='main'").get("orderStatus"));upload("orders",List.of(first),true,hq,400);
        upload("orders",List.of(first,first),true,hq,400);
    }
    @Test void localItemRenameDoesNotBreakFileIdempotency()throws Exception{
        mappings();upload("orders",List.of(order("DP-1")),true,hq,200);
        var profile=new LinkedHashMap<>(catalog.storeServices("yuyao",false).stream().filter(s->str(s,"id").equals("moxa")).findFirst().orElseThrow());profile.put("name","改名后的本店艾灸");catalog.saveStoreService(new Admin("hq","总部",null,Set.of("hq"),Set.of(),true),"yuyao","moxa",profile);
        var result=upload("orders",List.of(order("DP-1")),true,hq,200);assertEquals(1,integer(result,"skipped",0));
    }
    @Test void ordersAndConversionEnforceStoreScopeAndMaskContactData()throws Exception{
        mappings();mapping("shop-bailemen","bai-le-men","moxa");var second=order("DP-OTHER");second.put("externalShopId","shop-bailemen");upload("orders",List.of(order("DP-LOCAL"),second),true,hq,200);
        var list=request(get("/api/admin/dianping/orders"),manager,null,200);assertEquals(1,integer(list,"total",0));assertFalse(d.stringify(list).contains("13900003501"));assertFalse(d.stringify(list).contains("payloadJson"));request(get("/api/admin/dianping/orders").param("storeId","bai-le-men"),manager,null,404);
        var otherRow=d.one("SELECT * FROM dianping_order WHERE store_id='bai-le-men'");convert(otherRow,manager,conversion(otherRow),404);request(get("/api/admin/dianping/orders/"+otherRow.get("id")+"/slots").param("date",LocalDate.now(ZONE).plusDays(1).toString()),manager,null,404);
        var localRow=d.one("SELECT * FROM dianping_order WHERE store_id='yuyao'");var slots=request(get("/api/admin/dianping/orders/"+localRow.get("id")+"/slots").param("date",LocalDate.now(ZONE).plusDays(1).toString()),manager,null,200);assertEquals("yuyao",slots.get("storeId"));assertFalse(((List<?>)slots.get("slots")).isEmpty());
    }
    @Test void explicitConfirmationUsesExistingBookingRulesAndIsIdempotentAcrossOperators()throws Exception{
        mappings();upload("orders",List.of(order("DP-1")),true,hq,200);var order=storedOrder();var input=conversion(order);input.put("storeId","bai-le-men");input.put("serviceId","foot");
        var created=convert(order,frontdesk,input,200);assertEquals("DIANPING",created.get("source"));assertEquals("yuyao",created.get("storeId"));assertEquals("moxa",created.get("serviceId"));assertEquals("CONFIRMED",created.get("status"));
        var repeated=convert(order,manager,input,200);assertEquals(created.get("id"),repeated.get("id"));assertEquals(1,d.count("SELECT COUNT(*) FROM appointment"));assertNull(d.one("SELECT * FROM appointment").get("customerId"));assertTrue(d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='INBOX'")>0);
    }
    @Test void conversionFailsOnCapacityDisabledItemsStaleRevisionAndMissingFullPhone()throws Exception{
        mappings();var row=order("DP-1");row.put("phone","139****3501");upload("orders",List.of(row),true,hq,200);var order=storedOrder();var input=conversion(order);convert(order,manager,input,400);
        input.put("phone","13900003501");input.put("revision",0);convert(order,manager,input,409);input.put("revision",order.get("revision"));
        d.update("INSERT INTO slot_override VALUES(?,?,?,?,?)","yuyao",input.get("date"),"10:00",0,true);convert(order,manager,input,409);assertEquals(0,d.count("SELECT COUNT(*) FROM appointment"));assertNull(storedOrder().get("appointmentId"));
        d.update("DELETE FROM slot_override");var profile=new LinkedHashMap<>(catalog.storeServices("yuyao",false).stream().filter(s->str(s,"id").equals("moxa")).findFirst().orElseThrow());profile.put("enabled",false);catalog.saveStoreService(new Admin("hq","总部",null,Set.of("hq"),Set.of(),true),"yuyao","moxa",profile);convert(order,manager,input,400);
    }
    @Test void platformUpdatesFlagLinkedAppointmentsForReviewWithoutCancellingThem()throws Exception{
        mappings();upload("orders",List.of(order("DP-1")),true,hq,200);var row=storedOrder();var booking=convert(row,manager,conversion(row),200);var before=d.one("SELECT * FROM appointment WHERE id=?",booking.get("id"));
        var update=order("DP-1");update.put("status","REFUNDED");update.put("updatedAt","2026-01-02 10:00:00");upload("orders",List.of(update),true,hq,200);assertTrue(bool(storedOrder(),"needsReview"));assertEquals(before,d.one("SELECT * FROM appointment WHERE id=?",booking.get("id")));
        request(post("/api/admin/dianping/orders/"+row.get("id")+"/reviewed"),manager,Map.of("revision",1),409);request(post("/api/admin/dianping/orders/"+row.get("id")+"/reviewed"),manager,Map.of("revision",storedOrder().get("revision")),200);assertFalse(bool(storedOrder(),"needsReview"));assertEquals(before,d.one("SELECT * FROM appointment WHERE id=?",booking.get("id")));
    }
    @Test void invalidAmountsDatesPhonesAndWorkbookContentCannotBeImported()throws Exception{
        mappings();
        for(var pair:List.of(Map.entry("amountYuan","1.001"),Map.entry("amountYuan","-1"),Map.entry("headcount","0"),Map.entry("phone","not-phone"),Map.entry("updatedAt","2026-02-30 12:00:00"),Map.entry("bookingDate","2026-01-01"),Map.entry("status","COMPLETED"))){var row=order("DP-1");row.put(pair.getKey(),pair.getValue());assertFalse(bool(upload("orders",List.of(row),false,hq,200),"valid"));}
        request(multipart("/api/admin/dianping/imports/orders").file(new MockMultipartFile("file","bad.xlsx","application/octet-stream","invalid".getBytes(StandardCharsets.UTF_8))),hq,null,400);
        request(multipart("/api/admin/dianping/imports/orders").file(new MockMultipartFile("file","formula.xlsx","application/octet-stream",workbook("orders",List.of(order("DP-1")),true))),hq,null,400);
        upload("orders",List.of(),true,hq,400);assertEquals(0,d.count("SELECT COUNT(*) FROM dianping_order"));
    }
}
