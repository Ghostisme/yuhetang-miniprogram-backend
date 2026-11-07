package com.yuwatang.reservation;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static com.yuwatang.reservation.Data.*;

/** Standalone, synthetic load fixture. It cannot connect to the business database. */
public class CapacityLoadFixture {
    static final String DATABASE="jdbc:mysql://127.0.0.1:13379/yuwatang_capacity_load_fixture?allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&serverTimezone=Asia/Shanghai";
    public static void main(String[] args)throws Exception {
        if(args.length<1)throw new IllegalArgumentException("Usage: CapacityLoadFixture /absolute/private/metadata.json [attach]");
        Path output=Path.of(args[0]).toAbsolutePath();boolean attach=args.length>1&&args[1].equals("attach");int port=attach?18893:18892;
        List<String> options=new ArrayList<>(List.of(
            "--server.address=127.0.0.1","--server.port="+port,"--app.mode=local",
            "--spring.datasource.url="+DATABASE,"--spring.datasource.username=capacity_fixture","--spring.datasource.password=isolated-capacity-only-password",
            "--app.data-dir="+output.getParent().resolve("capacity-runtime"),
            "--app.jwt-secret=capacity-load-fixture-secret-never-use-in-production",
            "--app.admin-password=capacity-load-initial-password",
            "--app.wechat.app-id=","--app.wechat.secret=","--app.wechat.service-app-id=","--app.wechat.service-secret=",
            "--app.wechat.customer-template=","--app.wechat.service-template=","--app.sms.secret-id=","--app.sms.secret-key=",
            "--app.sms.app-id=","--app.sms.staff-template=","--app.sms.customer-template=",
            "--management.endpoints.web.exposure.include=health,prometheus"));
        var context=SpringApplication.run(ReservationApplication.class,options.toArray(String[]::new));
        context.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
        Data d=context.getBean(Data.class);AuthService auth=context.getBean(AuthService.class);
        if(!attach){
            if(d.count("SELECT COUNT(*) FROM appointment")!=0||d.count("SELECT COUNT(*) FROM customer_identity")!=0)
                throw new IllegalStateException("Seed requires the dedicated empty fixture database; no existing rows are deleted.");
            seed(d);
        }
        List<String> customers=new ArrayList<>(),admins=new ArrayList<>();
        for(int i=0;i<50000;i++)customers.add(auth.token("capacity-c"+i,"CUSTOMER",0));
        for(int i=0;i<300;i++)admins.add(auth.token("capacity-a"+i,"ADMIN",0));
        var stores=d.rows("SELECT * FROM store WHERE enabled=TRUE ORDER BY id").stream()
            .filter(s->bool(d.object(s.get("profileJson")),"onlineBooking")).map(s->str(s,"id")).toList();
        Map<String,Object> meta=new LinkedHashMap<>();meta.put("baseUrl","http://127.0.0.1:"+port);
        meta.put("synthetic",true);meta.put("database","127.0.0.1:13379/yuwatang_capacity_load_fixture");
        meta.put("customers",customers);meta.put("admins",admins);meta.put("stores",stores);
        meta.put("day",LocalDate.now(ZONE).plusDays(1).toString());meta.put("fixtureCustomers",50000);meta.put("fixtureAppointments",100000);
        Files.createDirectories(output.getParent());Files.writeString(output,d.stringify(meta));
        System.out.println("CAPACITY_FIXTURE_READY port="+port+" customers=50000 appointments=100000");
    }
    static void seed(Data d){
        var stores=d.rows("SELECT id FROM store ORDER BY id").stream().map(s->str(s,"id")).toList();
        String now=now();String hash=str(d.one("SELECT password_hash FROM app_user WHERE username='admin'"),"passwordHash");
        for(int start=0;start<50000;start+=1000){
            List<Object[]> identities=new ArrayList<>(),profiles=new ArrayList<>(),relations=new ArrayList<>();
            for(int i=start;i<start+1000;i++){
                String store=stores.get(i%stores.size()),phone="138"+String.format("%08d",i);
                identities.add(new Object[]{"capacity-c"+i,"capacity-openid-"+i,true,now});
                profiles.add(new Object[]{"capacity-p"+i,phone,"合成客户"+i,false,"",store,"",null,now});
                relations.add(new Object[]{store,"capacity-p"+i,"","MINIPROGRAM",null});
            }
            d.jdbc.batchUpdate("INSERT INTO customer_identity VALUES(?,?,?,?)",identities);
            d.jdbc.batchUpdate("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",profiles);
            d.jdbc.batchUpdate("INSERT INTO store_customer VALUES(?,?,?,?,?)",relations);
        }
        for(int start=0;start<100000;start+=1000){
            List<Object[]> bookings=new ArrayList<>(),history=new ArrayList<>();
            for(int i=start;i<start+1000;i++){
                String bid="capacity-b"+i,created=OffsetDateTime.now(ZONE).minusDays(i%30).toString();
                bookings.add(new Object[]{bid,"capacity-c"+(i%50000),stores.get(i%stores.size()),"moxa","合成项目","合成客户"+(i%50000),"138"+String.format("%08d",i%50000),LocalDate.now(ZONE).minusDays(1+i%30).toString(),"10:00",1,"","门店安排","UNKNOWN","UNKNOWN","",false,"COMPLETED","MINIPROGRAM",1,created,created,"customer:capacity-c"+(i%50000),"capacity-seed-"+i,"seed"});
                history.add(new Object[]{"capacity-h"+i,bid,"COMPLETED","合成压测数据","容量测试",created});
            }
            d.jdbc.batchUpdate("INSERT INTO appointment(id,customer_id,store_id,service_id,service_name,customer_name,phone,booking_date,start_time,headcount,therapist_intent_id,therapist_intent_name,member_claim,visited_claim,remark,sensitive_consent,status,source,version,created_at,updated_at,idempotency_owner,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",bookings);
            d.jdbc.batchUpdate("INSERT INTO appointment_history VALUES(?,?,?,?,?,?)",history);
        }
        List<Object[]> users=new ArrayList<>(),roles=new ArrayList<>();
        for(int i=0;i<300;i++){users.add(new Object[]{"capacity-a"+i,"capacity_admin_"+i,"合成管理员"+i,hash,null,true,0,now});roles.add(new Object[]{"capacity-a"+i,"hq"});}
        d.jdbc.batchUpdate("INSERT INTO app_user VALUES(?,?,?,?,?,?,?,?)",users);d.jdbc.batchUpdate("INSERT INTO user_role VALUES(?,?)",roles);
    }
}
