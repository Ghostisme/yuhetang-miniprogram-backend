package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
public class BookingService {
    final Data d;final CatalogService catalog;final NotificationService notifications;
    static final Set<String> ACTIVE=Set.of("PENDING","CONFIRMED","ARRIVED");
    static final Map<String,String> LABELS=Map.of("PENDING","待门店确认","CONFIRMED","门店已确认","ARRIVED","已到店","COMPLETED","已完成","CANCELLED","已取消","NO_SHOW","未到店");
    BookingService(Data d,CatalogService c,NotificationService n){this.d=d;catalog=c;notifications=n;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> create(Map<String,Object> in,String customer,Admin actor,String key){
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))throw error(400,"缺少有效的重复提交保护标识。");catalog.bookingLock();
        String owner=actor==null?"customer:"+customer:"admin:"+actor.id();String hash=hash(in);var previous=d.rows("SELECT * FROM appointment WHERE idempotency_owner=? AND idempotency_key=?",owner,key);if(!previous.isEmpty()){if(!hash.equals(str(previous.get(0),"requestHash")))throw error(409,"同一提交标识的内容发生变化，请刷新后重试。");return view(previous.get(0),actor!=null);}
        String store=text(in,"storeId",1,64),service=text(in,"serviceId",1,64);if(actor!=null)actor.store(store);var srv=catalog.service(service);var storeProfile=d.object(catalog.store(store).get("profileJson"));if(d.strings(storeProfile.get("disabledServiceIds")).contains(service))throw error(400,"本门店暂未开放该项目。");
        String date=text(in,"date",10,10),time=text(in,"startTime",5,5);int headcount=integer(in,"headcount",1);catalog.checkSlot(store,date,time,headcount,null);
        String name=text(in,"name",1,40),phone=text(in,"phone",11,11);if(!phone.matches("1[3-9]\\d{9}"))throw error(400,"请填写正确的11位手机号码。");if(actor==null&&!bool(in,"privacyConsent"))throw error(400,"请阅读并同意预约隐私说明。");
        var rules=catalog.rules(store);if(d.count("SELECT COUNT(*) FROM appointment WHERE phone=? AND status IN ('PENDING','CONFIRMED','ARRIVED')",phone)>=integer(rules,"phoneOpenLimit",3)&&!(actor!=null&&bool(in,"overridePhoneLimit")))throw error(409,"该联系电话已有多笔未完成预约，请联系门店协助处理。");
        String intent=text(in,"therapistIntentId",0,64);String intentName=intent.isEmpty()?"门店安排":therapist(intent,store,service,true);
        String member=claim(in,"memberClaim"),visited=claim(in,"visitedClaim"),remark=text(in,"remark",0,500);boolean sensitive=bool(in,"sensitiveConsent");
        String id="YHT-"+UUID.randomUUID().toString().substring(0,12).replace("-","").toUpperCase(Locale.ROOT),now=now(),status=actor==null?"PENDING":"CONFIRMED",source=actor==null?"MINIPROGRAM":text(in,"source",1,24);if(!Set.of("MINIPROGRAM","PHONE","WECHAT","DIANPING","WALKIN").contains(source))throw error(400,"预约来源不正确。");
        d.update("INSERT INTO appointment(id,customer_id,store_id,service_id,service_name,customer_name,phone,booking_date,start_time,headcount,therapist_intent_id,therapist_intent_name,member_claim,visited_claim,remark,sensitive_consent,status,source,version,created_at,updated_at,idempotency_owner,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,customer,store,service,str(srv,"name"),name,phone,date,time,headcount,intent,intentName,member,visited,remark,sensitive,status,source,1,now,now,owner,key,hash);
        history(id,status,actor==null?"申请已提交，等待门店人工确认；具体接待以门店确认为准。":"前台代约并确认",actor==null?"客户本人":actor.name());
        customerRelation(store,name,phone,source);var record=get(id);if(actor!=null)d.audit(actor,store,"appointment.create",id,"前台代约来源="+source);event(record,"appointment.created");return view(record,actor!=null);
    }
    String claim(Map<String,Object> in,String key){String value=text(in,key,1,12);if(!Set.of("YES","NO","UNKNOWN").contains(value))throw error(400,"请选择是否会员/是否来过门店。");return value;}
    String therapist(String id,String store,String service,boolean intent){
        var matches=d.rows("SELECT * FROM therapist WHERE id=? AND store_id=? AND enabled=TRUE",id,store);
        if(matches.isEmpty())throw error(400,"该调理师不属于当前门店或已停用，请重新选择本店调理师。");
        var t=matches.get(0);var p=d.object(t.get("profileJson"));
        if(intent&&(!bool(p,"showOnClient")||bool(p,"resting")))throw error(400,"该调理师暂不接收意向，请选择门店安排。");
        if(!d.strings(p.get("serviceIds")).contains(service))throw error(400,"该调理师不在所选项目资料范围内。");
        return str(t,"name");
    }
    void customerRelation(String store,String name,String phone,String source){var list=d.rows("SELECT * FROM customer_profile WHERE phone=?",phone);String pid;if(list.isEmpty()){pid=id();d.update("INSERT INTO customer_profile VALUES(?,?,?,?,?,?,?,?,?)",pid,phone,name,false,"",store,"",null,now());}else pid=str(list.get(0),"id");if(d.count("SELECT COUNT(*) FROM store_customer WHERE store_id=? AND customer_profile_id=?",store,pid)==0)d.update("INSERT INTO store_customer VALUES(?,?,?,?,?)",store,pid,"",source,null);}
    Map<String,Object> get(String id){return d.one("SELECT * FROM appointment WHERE id=?",id);}
    void history(String id,String status,String note,String operator){d.update("INSERT INTO appointment_history VALUES(?,?,?,?,?,?)",id(),id,status,note,operator,now());}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> change(String id,Map<String,Object> in,String customer,Admin actor){
        catalog.bookingLock();var b=get(id);String store=str(b,"storeId");if(actor!=null)actor.store(store);else if(customer==null||!customer.equals(b.get("customerId")))throw error(404,"预约不存在。");
        int version=integer(in,"version",-1);if(version!=integer(b,"version",0))throw error(409,"预约已被更新，请刷新后再操作。");String action=text(in,"action",1,30),status=str(b,"status"),next=status,note=text(in,"note",0,500);String operator=actor==null?"客户本人":actor.name();
        if(actor==null){if(!Set.of("cancel","reschedule").contains(action))throw error(403,"客户不能执行该操作。");if(!Set.of("PENDING","CONFIRMED").contains(status))throw error(409,"该状态不能自助改约或取消，请联系门店。");LocalDateTime start=LocalDate.parse(str(b,"bookingDate")).atTime(LocalTime.parse(str(b,"startTime")));if(LocalDateTime.now(ZONE).plusHours(integer(catalog.rules(store),"cancelHours",2)).isAfter(start))throw error(409,"已超过自助处理截止时间，请联系门店。");}
        else actor.require(Set.of("confirm","reject").contains(action)?"appointments:review":Set.of("arrive","complete","noshow").contains(action)?"appointments:status":"appointments:write");
        switch(action){
            case "confirm"->{requireStatus(status,"PENDING");next="CONFIRMED";}
            case "reject"->{requireStatus(status,"PENDING");if(note.isBlank())throw error(400,"请填写无法接待的原因，便于客户理解。");next="CANCELLED";}
            case "cancel"->{if(!Set.of("PENDING","CONFIRMED","ARRIVED").contains(status))throw error(409,"该预约已结束，不能重复取消。");if(note.isBlank())note=actor==null?"客户取消预约":"门店取消预约";next="CANCELLED";}
            case "arrive"->{requireStatus(status,"CONFIRMED");next="ARRIVED";}
            case "complete"->{requireStatus(status,"ARRIVED");next="COMPLETED";}
            case "noshow"->{requireStatus(status,"CONFIRMED");if(LocalDateTime.now(ZONE).isBefore(LocalDate.parse(str(b,"bookingDate")).atTime(LocalTime.parse(str(b,"startTime"))).plusMinutes(15)))throw error(409,"未超过迟到保留时间，请先联系客户。");next="NO_SHOW";}
            case "assign"->{if(!ACTIVE.contains(status))throw error(409,"已结束预约不可重新分配。");String tid=text(in,"assignedTherapistId",1,64);String name=therapist(tid,store,str(b,"serviceId"),false);d.update("UPDATE appointment SET assigned_therapist_id=? WHERE id=?",tid,id);note="分配调理师："+name+(note.isBlank()?"":"；"+note);}
            case "note"->{if(note.isBlank())throw error(400,"请填写跟进记录。");}
            case "reschedule"->{if(!Set.of("PENDING","CONFIRMED").contains(status))throw error(409,"该预约不能改约。");String date=text(in,"date",10,10),time=text(in,"startTime",5,5);int count=integer(in,"headcount",integer(b,"headcount",1));catalog.checkSlot(store,date,time,count,id);d.update("UPDATE appointment SET booking_date=?,start_time=?,headcount=?,assigned_therapist_id=NULL WHERE id=?",date,time,count,id);next="PENDING";note="改约至 "+date+" "+time+"，等待门店重新确认"+(note.isBlank()?"":"；"+note);}
            default->throw error(400,"不支持的预约操作。");
        }
        if(d.update("UPDATE appointment SET status=?,version=version+1,updated_at=?,cancel_reason=? WHERE id=? AND version=?",next,now(),next.equals("CANCELLED")?note:b.get("cancelReason"),id,version)!=1)throw error(409,"预约已更新，请刷新。");history(id,next,note.isBlank()?LABELS.get(next):note,operator);var updated=get(id);if(actor!=null)d.audit(actor,store,"appointment."+action,id,"版本="+(version+1));if(!Set.of("note","assign").contains(action))event(updated,"appointment."+action);else if(action.equals("assign"))pmsEvent(updated,"appointment.assign");return view(updated,actor!=null);
    }
    static void requireStatus(String actual,String expected){if(!actual.equals(expected))throw error(409,"当前状态不支持该操作，请刷新后重试。");}
    void event(Map<String,Object> b,String type){notifications.enqueue(b,type);pmsEvent(b,type);}
    void pmsEvent(Map<String,Object> b,String type){Map<String,Object> payload=new LinkedHashMap<>();for(String k:List.of("id","storeId","serviceId","bookingDate","startTime","headcount","status","version","assignedTherapistId","createdAt","updatedAt"))payload.put(k,b.get(k));d.update("INSERT INTO pms_outbox VALUES(?,?,?,?,?,?,?,?,?)",id(),b.get("id"),b.get("version"),type,1,b.get("storeId"),d.stringify(payload),now(),false);}
    Map<String,Object> view(Map<String,Object> b,boolean admin){var v=new LinkedHashMap<>(b);v.remove("idempotencyOwner");v.remove("idempotencyKey");v.remove("requestHash");v.remove("customerId");v.remove("sensitiveConsent");v.put("statusLabel",LABELS.getOrDefault(str(b,"status"),str(b,"status")));v.put("storeName",catalog.store(str(b,"storeId")).get("name"));String assigned=str(b,"assignedTherapistId");v.put("assignedTherapistName",assigned.isEmpty()?"待门店分配":str(d.one("SELECT name FROM therapist WHERE id=?",assigned),"name"));if(admin){v.put("phone",mask(str(b,"phone")));v.put("history",d.rows("SELECT * FROM appointment_history WHERE appointment_id=? ORDER BY created_at DESC,id DESC",b.get("id")));}else{v.remove("remark");v.put("message",str(b,"status").equals("PENDING")?"预约申请已提交，等待门店人工确认。当前尚未预约成功，请以门店确认结果为准。":"请留意预约状态，如需协助请联系门店。");}return v;}
    List<Map<String,Object>> mine(String customer){return d.rows("SELECT * FROM appointment WHERE customer_id=? ORDER BY created_at DESC LIMIT 100",customer).stream().map(x->view(x,false)).toList();}
    static String mask(String p){return p.length()==11?p.substring(0,3)+"****"+p.substring(7):"";}
    String hash(Map<String,Object> input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d.stringify(new TreeMap<>(input)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
