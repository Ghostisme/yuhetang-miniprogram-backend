package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController
class NotificationInbox {
    final Data d; final AuthService auth; final AdminService admin;
    NotificationInbox(Data d,AuthService auth,AdminService admin){this.d=d;this.auth=auth;this.admin=admin;}
    String scope(Admin a,String store){return admin.scope(a,store);}
    String where(String store){return " WHERE n.channel='INBOX' AND n.delivery_status='AVAILABLE'"+(store.isEmpty()?"":" AND n.store_id=?");}
    List<Object> args(Admin a,String store){var args=new ArrayList<Object>();args.add(a.id());if(!store.isEmpty())args.add(store);return args;}
    @GetMapping("/api/admin/inbox/summary") @Permit("notifications:read")
    Map<String,Object> summary(HttpServletRequest r,@RequestParam(defaultValue="")String storeId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="false")boolean unreadOnly){
        if(page<1||page>100000)throw error(400,"消息页码不正确。");
        var a=auth.admin(r);String store=scope(a,storeId);
        String join=" FROM notification_outbox n LEFT JOIN notification_read rd ON rd.notification_id=n.id AND rd.admin_user_id=?",where=where(store);
        var args=args(a,store);
        int unread=d.count("SELECT COUNT(*)"+join+where+" AND rd.notification_id IS NULL",args.toArray());
        if(unreadOnly)where+=" AND rd.notification_id IS NULL";
        int total=unreadOnly?unread:d.count("SELECT COUNT(*)"+join+where,args.toArray());
        // The timestamp is captured before reading the page: read-all must not consume later arrivals.
        String asOf=now();args.add(20);args.add((long)(page-1)*20);
        var items=d.rows("SELECT n.id,n.store_id,n.appointment_id,n.event_type,n.payload_json,n.created_at,rd.read_at"+join+where+" ORDER BY n.created_at DESC,n.id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var item:items){item.put("isRead",item.get("readAt")!=null);item.put("payload",d.object(item.remove("payloadJson")));item.put("eventLabel",NotificationService.EVENTS.getOrDefault(str(item,"eventType"),"预约通知"));}
        return Map.of("items",items,"total",total,"unreadTotal",unread,"page",page,"asOf",asOf);
    }
    @PostMapping("/api/admin/inbox/read") @Permit("notifications:read") @Transactional
    public Map<String,Object> read(HttpServletRequest r,@RequestBody Map<String,Object> in){
        var a=auth.admin(r);var ids=d.strings(in.get("ids")).stream().distinct().toList();
        if(ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(id->id.isBlank()||id.length()>64))throw error(400,"请选择需要标记的消息（最多100条）。");
        for(String id:ids){var row=d.one("SELECT store_id FROM notification_outbox WHERE id=? AND channel='INBOX' AND delivery_status='AVAILABLE'",id);a.store(str(row,"storeId"));}
        for(String id:ids)d.update("INSERT INTO notification_read(admin_user_id,notification_id,read_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE notification_id=notification_id",a.id(),id,now());
        return Map.of("saved",true);
    }
    @PostMapping("/api/admin/inbox/read-all") @Permit("notifications:read") @Transactional
    public Map<String,Object> readAll(HttpServletRequest r,@RequestBody Map<String,Object> in){
        var a=auth.admin(r);String store=scope(a,str(in,"storeId")),asOf=text(in,"asOf",1,40);
        try{if(OffsetDateTime.parse(asOf).isAfter(OffsetDateTime.now(ZONE)))throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw error(400,"请刷新消息后再标记已读。");}
        var args=new ArrayList<Object>();args.add(a.id());args.add(now());if(!store.isEmpty())args.add(store);args.add(asOf);
        d.update("INSERT INTO notification_read(admin_user_id,notification_id,read_at) SELECT ?,n.id,? FROM notification_outbox n"+where(store)+" AND n.created_at<=? ON DUPLICATE KEY UPDATE notification_id=notification_id",args.toArray());
        return Map.of("saved",true);
    }
}
