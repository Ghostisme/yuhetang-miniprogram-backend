package com.yuwatang.reservation;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_contract_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "app.mode=local","app.data-dir=target/isolated-test-data",
    "app.jwt-secret=isolated-tests-only-secret-2026-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.wechat.customer-template=","app.wechat.service-template="
})
@AutoConfigureMockMvc
class ReservationIntegrationTest {
    @Autowired MockMvc mvc; @Autowired Data d; @Autowired AuthService auth;
    @Autowired BookingService bookings; @Autowired CatalogService catalog;
    @Autowired NotificationService notifications; @Autowired PmsPort pms;
    String customer, customerToken, hq, manager, otherStore;
    final String tomorrow=LocalDate.now(ZONE).plusDays(1).toString();

    @BeforeEach void reset(){
        for(String table:List.of("notification_outbox","pms_outbox","appointment_history","appointment","slot_override","store_customer","customer_profile","subscription_grant","service_binding","notification_recipient"))d.update("DELETE FROM "+table);
        d.update("UPDATE store SET rules_json=?",d.stringify(Bootstrap.defaultRules()));
        d.update("UPDATE app_setting SET setting_value='false' WHERE setting_key='sharedLookupEnabled'");
        String openid="integration-"+id();var session=auth.customerSession(openid,true);customerToken=str(session,"token");
        customer=str(d.one("SELECT id FROM customer_identity WHERE openid=?",openid),"id");
        hq=adminToken("hq");manager=adminToken("yuyao-manager");otherStore=adminToken("bai-manager");
    }
    String adminToken(String id){return auth.token(id,"ADMIN",integer(d.one("SELECT token_version FROM app_user WHERE id=?",id),"tokenVersion",0));}
    Map<String,Object> input(String time,String phone){var in=new LinkedHashMap<String,Object>();in.put("storeId","yuyao");in.put("serviceId","moxa");in.put("date",tomorrow);in.put("startTime",time);in.put("headcount",1);in.put("name","集成测试客户");in.put("phone",phone);in.put("therapistIntentId","yuyao-t1");in.put("memberClaim","YES");in.put("visitedClaim","NO");in.put("remark","希望由林老师接待");in.put("privacyConsent",true);in.put("sensitiveConsent",false);return in;}
    ResultActions send(MockHttpServletRequestBuilder req,String token,Object body)throws Exception{if(token!=null)req.header("Authorization","Bearer "+token);if(body!=null)req.contentType("application/json").content(d.stringify(body));return mvc.perform(req);}
    Map<String,Object> json(ResultActions result,int code)throws Exception{var r=result.andReturn().getResponse();assertEquals(code,r.getStatus(),r.getContentAsString());return d.object(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    Map<String,Object> create(String time,String phone)throws Exception{return json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,input(time,phone)),200);}
    Map<String,Object> change(String id,int v,String action,String token,Map<String,Object> extra)throws Exception{var body=new LinkedHashMap<String,Object>(extra);body.put("action",action);body.put("version",v);return json(send(patch("/api/admin/appointments/"+id),token,body),200);}
    List<Map<String,Object>> therapistList(String token,String query)throws Exception{
        var response=send(get("/api/admin/therapists"+query),token,null).andReturn().getResponse();
        String body=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertEquals(200,response.getStatus(),body);
        return d.json.readValue(body,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
    }

    @Test void customerToStoreConfirmationAndActualAssignment()throws Exception{
        var b=create("10:00","13900001001");String id=str(b,"id");
        assertEquals("PENDING",b.get("status"));assertTrue(str(b,"message").contains("尚未预约成功"));
        assertEquals("YES",b.get("memberClaim"));assertEquals("yuyao-t1",b.get("therapistIntentId"));
        assertFalse(bool(d.one("SELECT * FROM customer_profile WHERE phone='13900001001'"),"memberVerified"));
        assertEquals(1,catalog.used("yuyao",tomorrow,"10:00",null));
        json(send(get("/api/admin/appointments/"+id),otherStore,null),404);
        var confirmed=change(id,1,"confirm",manager,Map.of());assertEquals("CONFIRMED",confirmed.get("status"));
        var assigned=change(id,2,"assign",manager,Map.of("assignedTherapistId","yuyao-t2","note","内部接待说明"));assertEquals("yuyao-t2",assigned.get("assignedTherapistId"));
        var own=json(send(get("/api/me/appointments/"+id),customerToken,null),200);
        assertEquals("CONFIRMED",own.get("status"));assertFalse(d.stringify(own).contains("内部接待说明"));
        assertTrue(str(own,"assignedTherapistName").contains("陈老师"));
        change(id,3,"arrive",manager,Map.of());change(id,4,"complete",manager,Map.of());
        assertEquals(0,catalog.used("yuyao",tomorrow,"10:00",null));
        assertEquals(1,d.count("SELECT COUNT(*) FROM pms_outbox WHERE event_type='appointment.assign'"));
    }

    @Test void headquartersListsAllTherapistsWhileBranchDefaultsToItsOwnStore()throws Exception{
        var expected=d.rows("SELECT id FROM therapist").stream().map(t->str(t,"id")).collect(java.util.stream.Collectors.toSet());
        for(String query:List.of("","?storeId=")){
            var all=therapistList(hq,query);
            assertEquals(expected,all.stream().map(t->str(t,"id")).collect(java.util.stream.Collectors.toSet()));
            assertTrue(all.stream().anyMatch(t->"shanghai".equals(t.get("storeId"))));
            assertTrue(all.stream().anyMatch(t->"bai-le-men".equals(t.get("storeId"))));
            for(var teacher:all)assertEquals(catalog.store(str(teacher,"storeId")).get("name"),teacher.get("storeName"));
            var own=therapistList(manager,query);assertFalse(own.isEmpty());
            assertTrue(own.stream().allMatch(t->"yuyao".equals(t.get("storeId"))));
            var bai=therapistList(otherStore,query);assertFalse(bai.isEmpty());
            assertTrue(bai.stream().allMatch(t->"bai-le-men".equals(t.get("storeId"))));
        }
        var filtered=therapistList(hq,"?storeId=shanghai");assertFalse(filtered.isEmpty());
        assertTrue(filtered.stream().allMatch(t->"shanghai".equals(t.get("storeId"))));
        json(send(get("/api/admin/therapists?storeId=bai-le-men"),manager,null),404);
        json(send(get("/api/admin/therapists?storeId=shanghai"),otherStore,null),404);
    }

    @Test void editingShanghaiTherapistPreservesHeadquartersAndOtherStores()throws Exception{
        var original=new LinkedHashMap<>(catalog.therapists("shanghai",false).stream().filter(t->"shanghai-t1".equals(t.get("id"))).findFirst().orElseThrow());
        var storeIds=d.rows("SELECT id FROM store ORDER BY id");
        var teacherIds=therapistList(hq,"").stream().map(t->str(t,"id")).collect(java.util.stream.Collectors.toSet());
        try{
            var edited=new LinkedHashMap<>(original);edited.put("name","上海资料修改测试");edited.put("originalStoreId","shanghai");
            json(send(put("/api/admin/therapists"),hq,edited),200);
            var all=therapistList(hq,"?storeId=");
            assertEquals(teacherIds,all.stream().map(t->str(t,"id")).collect(java.util.stream.Collectors.toSet()));
            assertTrue(all.stream().anyMatch(t->"bai-le-men".equals(t.get("storeId"))));
            assertTrue(all.stream().anyMatch(t->"shanghai-t1".equals(t.get("id"))&&"上海资料修改测试".equals(t.get("name"))));
            assertEquals(storeIds,d.rows("SELECT id FROM store ORDER BY id"));
            assertTrue(bool(json(send(get("/api/admin/me"),hq,null),200),"hq"));
        }finally{json(send(put("/api/admin/therapists"),hq,original),200);}
    }

    @Test void therapistOwnershipIsVisibleAndCrossStoreIntentAndAssignmentAreRejected()throws Exception{
        for(String store:List.of("yuyao","shanghai","changde")){
            var teachers=catalog.therapists(store,true);assertFalse(teachers.isEmpty());
            for(var teacher:teachers){
                assertEquals(store,teacher.get("storeId"));
                assertEquals(catalog.store(store).get("name"),teacher.get("storeName"));
            }
        }
        // A Shanghai teacher cannot be requested for an appointment at Changde Road.
        var wrong=input("10:00","13900001012");wrong.put("storeId","changde");wrong.put("therapistIntentId","shanghai-t1");
        var rejected=json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,wrong),400);
        assertTrue(d.stringify(rejected).contains("不属于当前门店"));
        assertEquals(0,d.count("SELECT COUNT(*) FROM appointment"));

        var b=create("10:00","13900001012");String bookingId=str(b,"id");
        // Both store managers and headquarters must respect the appointment's store.
        for(String token:List.of(manager,hq)){
            json(send(patch("/api/admin/appointments/"+bookingId),token,Map.of("action","assign","version",1,"assignedTherapistId","shanghai-t1")),400);
            var unchanged=bookings.get(bookingId);
            assertEquals(1,integer(unchanged,"version",0));assertNull(unchanged.get("assignedTherapistId"));
            assertEquals("PENDING",unchanged.get("status"));
        }
        json(send(get("/api/admin/therapists?storeId=shanghai"),manager,null),404);
        var assigned=change(bookingId,1,"assign",manager,Map.of("assignedTherapistId","yuyao-t1"));
        assertEquals("yuyao-t1",assigned.get("assignedTherapistId"));

        var teacher=new LinkedHashMap<>(catalog.therapists("shanghai",false).get(0));
        String teacherId=str(teacher,"id");teacher.put("storeId","changde");
        json(send(put("/api/admin/therapists"),hq,teacher),409);
        assertEquals("shanghai",d.one("SELECT store_id FROM therapist WHERE id=?",teacherId).get("storeId"));
    }

    @Test void headquartersCanTransferTherapistWithoutMovingExistingIntentBookings()throws Exception{
        var b=create("10:00","13900001013");String bookingId=str(b,"id"),teacherId="yuyao-t1";
        var teacher=new LinkedHashMap<>(catalog.therapists("yuyao",false).stream().filter(t->teacherId.equals(t.get("id"))).findFirst().orElseThrow());
        teacher.put("originalStoreId","yuyao");teacher.put("storeId","shanghai");
        try{
            json(send(put("/api/admin/therapists"),manager,teacher),404);
            json(send(put("/api/admin/therapists"),adminToken("yuyao-frontdesk"),teacher),403);
            json(send(put("/api/admin/therapists"),hq,teacher),200);
            assertFalse(catalog.therapists("yuyao",true).stream().anyMatch(t->teacherId.equals(t.get("id"))));
            var transferred=catalog.therapists("shanghai",true).stream().filter(t->teacherId.equals(t.get("id"))).findFirst().orElseThrow();
            assertEquals(catalog.store("shanghai").get("name"),transferred.get("storeName"));
            var unchanged=bookings.get(bookingId);
            assertEquals("yuyao",unchanged.get("storeId"));assertEquals("PENDING",unchanged.get("status"));
            assertEquals(teacherId,unchanged.get("therapistIntentId"));assertEquals(b.get("therapistIntentName"),unchanged.get("therapistIntentName"));
            assertEquals(1,integer(unchanged,"version",0));assertNull(unchanged.get("assignedTherapistId"));
            assertFalse(d.object(d.one("SELECT profile_json FROM therapist WHERE id=?",teacherId).get("profileJson")).containsKey("originalStoreId"));
            // A stale form must not silently move the person back to the previous store.
            teacher.put("storeId","yuyao");
            assertTrue(d.stringify(json(send(put("/api/admin/therapists"),hq,teacher),409)).contains("已被调整"));
            assertEquals("shanghai",d.one("SELECT store_id FROM therapist WHERE id=?",teacherId).get("storeId"));
            json(send(patch("/api/admin/appointments/"+bookingId),hq,Map.of("action","assign","version",1,"assignedTherapistId",teacherId)),400);
            json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,input("11:00","13900001014")),400);
            var newStore=input("11:00","13900001014");newStore.put("storeId","shanghai");
            assertEquals("shanghai",json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,newStore),200).get("storeId"));
        }finally{d.update("UPDATE therapist SET store_id='yuyao' WHERE id=?",teacherId);}
    }

    @Test void therapistTransferRequiresResolvingActiveAssignmentsButAllowsCompletedHistory()throws Exception{
        var b=create("10:00","13900001015");String bookingId=str(b,"id"),teacherId="yuyao-t1";
        var teacher=new LinkedHashMap<>(catalog.therapists("yuyao",false).stream().filter(t->teacherId.equals(t.get("id"))).findFirst().orElseThrow());
        teacher.put("originalStoreId","yuyao");teacher.put("storeId","changde");
        try{
            change(bookingId,1,"assign",manager,Map.of("assignedTherapistId",teacherId));
            int version=2;
            for(String status:List.of("PENDING","CONFIRMED","ARRIVED")){
                if(status.equals("CONFIRMED"))change(bookingId,version++,"confirm",manager,Map.of());
                if(status.equals("ARRIVED"))change(bookingId,version++,"arrive",manager,Map.of());
                assertTrue(d.stringify(json(send(put("/api/admin/therapists"),hq,teacher),409)).contains("1笔未结束预约"));
                assertEquals("yuyao",d.one("SELECT store_id FROM therapist WHERE id=?",teacherId).get("storeId"));
            }
            change(bookingId,version++,"assign",manager,Map.of("assignedTherapistId","yuyao-t2"));
            json(send(put("/api/admin/therapists"),hq,teacher),200);
            assertEquals("yuyao",bookings.get(bookingId).get("storeId"));
            assertEquals("yuyao-t2",bookings.get(bookingId).get("assignedTherapistId"));
            teacher.put("originalStoreId","changde");teacher.put("storeId","yuyao");
            json(send(put("/api/admin/therapists"),hq,teacher),200);
            change(bookingId,version++,"assign",manager,Map.of("assignedTherapistId",teacherId));
            change(bookingId,version,"complete",manager,Map.of());
            teacher.put("originalStoreId","yuyao");teacher.put("storeId","changde");
            json(send(put("/api/admin/therapists"),hq,teacher),200);
            assertEquals("COMPLETED",bookings.get(bookingId).get("status"));
            assertEquals("yuyao",bookings.get(bookingId).get("storeId"));
            assertEquals(teacherId,bookings.get(bookingId).get("assignedTherapistId"));
        }finally{d.update("UPDATE therapist SET store_id='yuyao' WHERE id=?",teacherId);}
    }

    @Test void idempotentSubmissionCannotDuplicateOrMutate()throws Exception{
        String key=id();var in=input("10:15","13900001002");
        var a=json(send(post("/api/appointments").header("Idempotency-Key",key),customerToken,in),200);
        var b=json(send(post("/api/appointments").header("Idempotency-Key",key),customerToken,in),200);assertEquals(a.get("id"),b.get("id"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM appointment"));in.put("headcount",2);
        json(send(post("/api/appointments").header("Idempotency-Key",key),customerToken,in),409);
    }

    @Test void concurrentRequestsDoNotOversellSharedStartSlot()throws Exception{
        var a=input("10:30","13900001003");a.put("headcount",3);var b=input("10:30","13900001004");b.put("headcount",3);b.put("serviceId","massage");
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<Boolean>> work=new ArrayList<>();
            for(var in:List.of(a,b))work.add(pool.submit(()->{start.await();try{bookings.create(in,customer,null,id());return true;}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}}));
            start.countDown();int ok=0;for(var f:work)if(f.get(15,TimeUnit.SECONDS))ok++;
            assertEquals(1,ok);assertEquals(3,catalog.used("yuyao",tomorrow,"10:30",null));
        }finally{pool.shutdownNow();}
    }

    @Test void rescheduleIsAtomicAndReturnsToManualReview()throws Exception{
        var b=create("11:00","13900001005");String id=str(b,"id");change(id,1,"confirm",manager,Map.of());change(id,2,"assign",manager,Map.of("assignedTherapistId","yuyao-t1"));
        var full=input("11:15","13900001006");full.put("headcount",4);bookings.create(full,customer,null,id());
        json(send(patch("/api/me/appointments/"+id),customerToken,Map.of("version",3,"action","reschedule","date",tomorrow,"startTime","11:15")),409);
        assertEquals("11:00",bookings.get(id).get("startTime"));assertEquals(3,integer(bookings.get(id),"version",0));assertEquals("yuyao-t1",bookings.get(id).get("assignedTherapistId"));
        var next=json(send(patch("/api/me/appointments/"+id),customerToken,Map.of("version",3,"action","reschedule","date",tomorrow,"startTime","11:30")),200);
        assertEquals("PENDING",next.get("status"));assertNull(next.get("assignedTherapistId"));assertEquals(0,catalog.used("yuyao",tomorrow,"11:00",null));assertEquals(1,catalog.used("yuyao",tomorrow,"11:30",null));
        json(send(patch("/api/admin/appointments/"+id),manager,Map.of("action","confirm","version",3)),409);
    }

    @Test void cancellationReleasesQuotaAndNeverAuthenticatesByPhone()throws Exception{
        var b=create("12:00","13900001007");String id=str(b,"id");
        String stranger=str(auth.customerSession("stranger-"+id(),true),"token");
        json(send(get("/api/me/appointments/"+id),stranger,null),404);
        json(send(patch("/api/me/appointments/"+id),stranger,Map.of("action","cancel","version",1)),404);
        json(send(patch("/api/me/appointments/"+id),customerToken,Map.of("action","cancel","version",1)),200);
        assertEquals(0,catalog.used("yuyao",tomorrow,"12:00",null));
        var another=input("12:15","13900001007");bookings.create(another,str(auth.decoder.decode(stranger).getClaims(),"sub"),null,id());
        var mine=send(get("/api/me/appointments"),customerToken,null).andReturn().getResponse();assertEquals(200,mine.getStatus());assertEquals(1,d.json.readTree(mine.getContentAsString()).size());
    }

    @Test void serverRbacAndStoreBoundaryCannotBeBypassed()throws Exception{
        json(send(get("/api/admin/stores/yuyao/rules"),otherStore,null),404);
        json(send(get("/api/admin/appointments?storeId=yuyao"),otherStore,null),404);
        json(send(put("/api/admin/roles"),manager,Map.of()),403);
        json(send(get("/api/admin/users"),adminToken("yuyao-frontdesk"),null),403);
        json(send(put("/api/admin/users"),manager,Map.of("username","escape","displayName","越权","storeId","bai-le-men","roleIds",List.of("frontdesk"),"enabled",true,"password","Testing@12345")),404);
        json(send(post("/api/admin/customers/shared-lookup"),hq,Map.of("phone","13900001007")),403);
    }

    @Test void customRoleChangesRevokeExistingSession()throws Exception{
        String suffix=id().substring(0,8),roleName="只读预约-"+suffix,username="reader-"+suffix;
        json(send(put("/api/admin/roles"),hq,Map.of("name",roleName,"dataScope","STORE","permissions",List.of("appointments:read"))),200);
        String role=str(d.one("SELECT id FROM app_role WHERE name=?",roleName),"id");
        json(send(put("/api/admin/users"),hq,Map.of("username",username,"displayName","只读测试","storeId","yuyao","roleIds",List.of(role),"enabled",true,"password","Testing@12345")),200);
        String uid=str(d.one("SELECT id FROM app_user WHERE username=?",username),"id"),token=adminToken(uid);
        json(send(get("/api/admin/appointments"),token,null),200);json(send(get("/api/admin/dashboard"),token,null),403);
        json(send(put("/api/admin/roles"),hq,Map.of("id",role,"name",roleName,"dataScope","STORE","permissions",List.of())),200);
        json(send(get("/api/admin/appointments"),token,null),401);json(send(get("/api/admin/appointments"),adminToken(uid),null),403);
    }

    @Test void closingBookedSlotFailsRatherThanCancellingCustomers()throws Exception{
        create("13:00","13900001008");
        json(send(put("/api/admin/stores/yuyao/slots"),manager,Map.of("date",tomorrow,"time","*","capacity",0,"closed",true)),409);
        assertEquals("PENDING",d.one("SELECT status FROM appointment").get("status"));
        json(send(put("/api/admin/stores/yuyao/slots"),manager,Map.of("date",tomorrow,"time","14:00","capacity",0,"closed",true)),200);
        var in=input("14:00","13900001009");json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,in),409);
    }

    @Test void invalidInitialImportIsAllOrNothingAndTemplateIsDownloadable()throws Exception{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("资料");var headers=FilesApi.HEADERS.get("members");var h=sheet.createRow(0);for(int i=0;i<headers.size();i++)h.createCell(i).setCellValue(headers.get(i));
            String[][] values={{"导入一","13900001010","yuyao","true","会员","ID-1"},{"导入二","invalid","yuyao","false","","ID-2"}};
            for(int i=0;i<2;i++){var row=sheet.createRow(i+1);for(int j=0;j<values[i].length;j++)row.createCell(j).setCellValue(values[i][j]);}book.write(out);
            var file=new MockMultipartFile("file","members.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
            var preview=json(send(multipart("/api/admin/imports/members").file(file),hq,null),200);assertEquals(false,preview.get("valid"));
            json(send(multipart("/api/admin/imports/members?commit=true").file(file),hq,null),400);assertEquals(0,d.count("SELECT COUNT(*) FROM customer_profile"));
        }
        var result=send(get("/api/admin/imports/members/template"),hq,null).andReturn().getResponse();assertEquals(200,result.getStatus());assertTrue(result.getContentAsByteArray().length>1000);
    }

    @Test void lateClosingTimeGeneratesFiniteSameDaySlots(){
        var rules=Bootstrap.defaultRules();rules.put("opens","09:00");rules.put("closes","23:59");rules.put("interval",60);
        d.update("UPDATE store SET rules_json=? WHERE id='yuyao'",d.stringify(rules));
        var result=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->catalog.slots("yuyao",tomorrow));
        var slots=catalog.collection(result,"slots");assertEquals(15,slots.size());assertEquals("23:00",slots.get(14).get("time"));
    }

    @Test void rescheduleSlotsCreditOnlyTheOwnedBookingAndRetainStoreBoundaries()throws Exception{
        var in=input("10:00","13900002001");in.put("headcount",4);
        var b=json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,in),200);String bid=str(b,"id");
        var publicSlots=json(send(get("/api/stores/yuyao/slots?date="+tomorrow),null,null),200);
        var publicTime=catalog.collection(publicSlots,"slots").stream().filter(s->"10:00".equals(s.get("time"))).findFirst().orElseThrow();
        assertFalse(bool(publicTime,"available"));assertEquals(0,integer(publicTime,"remaining",-1));
        for(String path:List.of("/api/me/appointments/"+bid+"/slots","/api/admin/appointments/"+bid+"/slots")){
            var result=json(send(get(path+"?date="+tomorrow),path.contains("/admin/")?manager:customerToken,null),200);
            var own=catalog.collection(result,"slots").stream().filter(s->"10:00".equals(s.get("time"))).findFirst().orElseThrow();
            assertTrue(bool(own,"available"));assertEquals(4,integer(own,"remaining",0));
        }
        json(send(get("/api/admin/appointments/"+bid+"/slots?date="+tomorrow),otherStore,null),404);
        String stranger=str(auth.customerSession("slot-stranger-"+id(),true),"token");
        json(send(get("/api/me/appointments/"+bid+"/slots?date="+tomorrow),stranger,null),404);
        json(send(get("/api/me/appointments/"+bid+"/slots?date="+tomorrow),null,null),401);
        json(send(put("/api/admin/stores/yuyao/slots"),manager,Map.of("date",tomorrow,"time","11:00","closed",true,"capacity",0)),200);
        var result=json(send(get("/api/me/appointments/"+bid+"/slots?date="+tomorrow),customerToken,null),200);
        assertFalse(bool(catalog.collection(result,"slots").stream().filter(s->"11:00".equals(s.get("time"))).findFirst().orElseThrow(),"available"));
        json(send(patch("/api/me/appointments/"+bid),customerToken,Map.of("version",1,"action","reschedule","date",tomorrow,"startTime","10:00")),200);
        assertEquals(4,catalog.used("yuyao",tomorrow,"10:00",null));
    }

    @Test void malformedDatesAndNonexistentOverrideTimesReturnValidationErrors()throws Exception{
        var rules=Bootstrap.defaultRules();rules.put("closedDates",List.of("2026-99-99"));
        json(send(put("/api/admin/stores/yuyao/rules"),manager,rules),400);
        json(send(get("/api/admin/appointments?from=wrong-date"),manager,null),400);
        for(String time:List.of("oops","10:07","23:59"))json(send(put("/api/admin/stores/yuyao/slots"),manager,Map.of("date",tomorrow,"time",time,"capacity",2)),400);
        assertEquals(0,d.count("SELECT COUNT(*) FROM slot_override"));
        json(send(put("/api/admin/stores/yuyao/slots"),manager,Map.of("date",tomorrow,"time","10:00","capacity",2)),200);
        var response=send(get("/api/admin/stores/yuyao/slots"),manager,null).andReturn().getResponse();assertEquals(200,response.getStatus());
        assertEquals(1,d.json.readTree(response.getContentAsString()).size());
        json(send(get("/api/admin/stores/yuyao/slots"),otherStore,null),404);
    }

    @Test void notificationConfigurationRequiresAnExistingExplicitStore()throws Exception{
        json(send(get("/api/admin/notifications/config?storeId=missing-store"),hq,null),404);
        json(send(delete("/api/admin/notifications/binding/missing-store"),hq,null),404);
        json(send(get("/api/admin/notifications/config?storeId=yuyao"),otherStore,null),404);
        json(send(get("/api/admin/notifications/config?storeId=yuyao"),manager,null),200);
        create("15:00","13900002002");
        var other=input("15:00","13900002003");other.put("storeId","bai-le-men");other.put("therapistIntentId","");
        json(send(post("/api/appointments").header("Idempotency-Key",id()),customerToken,other),200);
        var all=json(send(get("/api/admin/notifications"),hq,null),200);
        assertEquals(Set.of("yuyao","bai-le-men"),new HashSet<>(catalog.collection(all,"items").stream().map(v->str(v,"storeId")).toList()));
        var own=json(send(get("/api/admin/notifications"),manager,null),200);
        assertTrue(catalog.collection(own,"items").stream().allMatch(v->"yuyao".equals(v.get("storeId"))));
    }

    MockMultipartFile teacherImport(String store)throws Exception{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("资料");var headers=FilesApi.HEADERS.get("therapists");var h=sheet.createRow(0);
            for(int i=0;i<headers.size();i++)h.createCell(i).setCellValue(headers.get(i));
            var row=sheet.createRow(1);String[] values={"yuyao-t1","林老师",store,"高级调理师","资料更新","moxa","true","true","false"};
            for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);book.write(out);
            return new MockMultipartFile("file","teachers.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }

    @Test void teacherImportPreservesExistingPhotoAndPrechecksCrossStoreMoves()throws Exception{
        var original=d.one("SELECT * FROM therapist WHERE id='yuyao-t1'");var profile=d.object(original.get("profileJson"));profile.put("image","/uploads/preserved-photo.jpg");
        d.update("UPDATE therapist SET profile_json=? WHERE id='yuyao-t1'",d.stringify(profile));
        try{
            var preview=json(send(multipart("/api/admin/imports/therapists").file(teacherImport("bai-le-men")),hq,null),200);assertFalse(bool(preview,"valid"));
            json(send(multipart("/api/admin/imports/therapists?commit=true").file(teacherImport("bai-le-men")),hq,null),400);
            assertEquals("yuyao",d.one("SELECT store_id FROM therapist WHERE id='yuyao-t1'").get("storeId"));
            json(send(multipart("/api/admin/imports/therapists?commit=true").file(teacherImport("yuyao")),hq,null),200);
            var updated=d.object(d.one("SELECT profile_json FROM therapist WHERE id='yuyao-t1'").get("profileJson"));
            assertEquals("/uploads/preserved-photo.jpg",updated.get("image"));assertEquals("资料更新",updated.get("specialty"));
            assertFalse(bool(updated,"sample"));
        }finally{d.update("UPDATE therapist SET name=?,profile_json=?,enabled=? WHERE id='yuyao-t1'",original.get("name"),original.get("profileJson"),original.get("enabled"));}
    }

    @Test void notificationConditionsAreVisibleAndPmsOnlyExportsWhitelistedEvents()throws Exception{
        var b=create("15:00","13900001011");change(str(b,"id"),1,"confirm",manager,Map.of());notifications.dispatch();
        assertTrue(d.count("SELECT COUNT(*) FROM notification_outbox WHERE delivery_status='BLOCKED'")>=2);
        assertEquals(0,d.count("SELECT COUNT(*) FROM notification_outbox WHERE delivery_status='ACCEPTED'"));
        assertTrue(d.count("SELECT COUNT(*) FROM notification_outbox WHERE channel='INBOX' AND delivery_status='AVAILABLE'")>=1);
        var events=pms.pending(100);assertFalse(events.isEmpty());String serialized=d.stringify(events);assertFalse(serialized.contains("13900001011"));assertFalse(serialized.contains("希望由林老师"));assertTrue(serialized.contains("schemaVersion"));
        json(send(get("/api/integrations/pms/v1/events"),null,null),404);
        List<String> ids=events.stream().map(e->str(e,"id")).toList();pms.acknowledge(ids);pms.acknowledge(ids);assertTrue(pms.pending(100).isEmpty());
    }
}
