package com.yuwatang.reservation;

import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Requests never occupy a new slot or replace the original appointment until approval. */
final class RescheduleRequests {
    final Data d;final CatalogService catalog;
    static final Map<String,String> LABELS=Map.of("PENDING","待门店处理","APPROVED","改约已确认",
        "REJECTED","改约未通过","WITHDRAWN","客户已撤回","EXPIRED","改约已失效","CLOSED","申请已关闭");
    RescheduleRequests(Data d,CatalogService catalog){this.d=d;this.catalog=catalog;}

    Map<String,Object> latest(String appointment) {
        var rows=d.rows("SELECT * FROM appointment_reschedule WHERE appointment_id=? ORDER BY request_version DESC LIMIT 1",appointment);
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    static boolean pending(Map<String,Object> request,OffsetDateTime current) {
        return "PENDING".equals(str(request,"status"))&&current.isBefore(OffsetDateTime.parse(str(request,"expiresAt")));
    }
    static Map<String,Object> view(Map<String,Object> request,OffsetDateTime current) {
        if(request.isEmpty())return Map.of();
        var result=new LinkedHashMap<>(request);
        if("PENDING".equals(str(result,"status"))&&!pending(request,current)) {
            result.put("status","EXPIRED");result.put("resolutionNote","门店未在处理期限内确认，原预约保持不变。");
        }
        result.put("statusLabel",LABELS.get(str(result,"status")));
        result.put("canReview",pending(request,current));
        return result;
    }
    void create(Map<String,Object> booking,Map<String,Object> input) {
        var current=OffsetDateTime.now(ZONE);var previous=latest(str(booking,"id"));
        if(pending(previous,current))throw error(409,"已有改约申请待处理，请等待门店处理或先撤回申请。");
        String date=text(input,"date",10,10),time=text(input,"startTime",5,5);
        int count=integer(input,"headcount",integer(booking,"headcount",1));
        if(date.equals(str(booking,"bookingDate"))&&time.equals(str(booking,"startTime"))&&count==integer(booking,"headcount",1))
            throw error(400,"请选择与原预约不同的时间或人数。");
        catalog.checkSlot(str(booking,"storeId"),date,time,count,str(booking,"id"));
        var target=LocalDate.parse(date).atTime(LocalTime.parse(time));
        var expires=target.minusMinutes(integer(catalog.rules(str(booking,"storeId")),"minLeadMinutes",30));
        if(AppointmentPolicy.start(booking).isBefore(expires))expires=AppointmentPolicy.start(booking);
        if(!current.toLocalDateTime().isBefore(expires))throw error(409,"新时段已超过改约处理期限，请选择更晚时段。");
        close(booking,"EXPIRED","门店未在处理期限内确认，原预约保持不变。");
        d.update("INSERT INTO appointment_reschedule(id,appointment_id,request_version,status,original_date,original_time,original_headcount,original_status,requested_date,requested_time,requested_headcount,request_note,resolution_note,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id(),booking.get("id"),integer(booking,"version",0)+1,"PENDING",booking.get("bookingDate"),booking.get("startTime"),booking.get("headcount"),booking.get("status"),
            date,time,count,text(input,"note",0,500),"",now(),expires.atZone(ZONE).toOffsetDateTime().toString());
    }
    Map<String,Object> requirePending(Map<String,Object> booking) {
        var request=latest(str(booking,"id"));
        if(!pending(request,OffsetDateTime.now(ZONE)))throw error(409,"改约申请已处理或已失效，请刷新后查看；原预约不会自动取消。");
        if(!Set.of("PENDING","CONFIRMED").contains(str(booking,"status")))throw error(409,"原预约状态已变更，不能继续处理改约。");
        return request;
    }
    void close(Map<String,Object> booking,String status,String reason) {
        d.update("UPDATE appointment_reschedule SET status=?,resolution_note=?,resolved_at=? WHERE appointment_id=? AND status='PENDING'",status,reason,now(),booking.get("id"));
    }
}
