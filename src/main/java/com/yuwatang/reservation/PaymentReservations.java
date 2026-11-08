package com.yuwatang.reservation;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.yuwatang.reservation.Data.*;

/** Receiving-store quotes and settlement attribution; no money moves in reservation mode. */
@Service
class PaymentReservations {
    final Data d;final PaymentPort port;
    static final Set<String> CHANNELS=Set.of("UNSELECTED","MEMBER_BALANCE","WECHAT_PAY");
    PaymentReservations(Data d,PaymentPort port){this.d=d;this.port=port;}

    void initialize(Map<String,Object> appointment){
        var member=d.one("SELECT * FROM appointment_membership WHERE appointment_id=?",appointment.get("id"));
        d.update("INSERT INTO reservation_payment(id,appointment_id,service_store_id,member_store_id,amount_fen,currency,channel,status,settlement_status,version,created_at,updated_at) VALUES(?,?,?,?,NULL,'CNY','UNSELECTED','UNPRICED','NOT_STARTED',1,?,?)",
            id(),appointment.get("id"),appointment.get("storeId"),member.get("memberStoreId"),now(),now());
    }
    Map<String,Object> get(String appointment){return view(d.one("SELECT * FROM reservation_payment WHERE appointment_id=?",appointment));}
    Map<String,Map<String,Object>> views(List<Object> ids){
        if(ids.isEmpty())return Map.of();Map<String,Map<String,Object>> result=new HashMap<>();
        for(var row:d.rows("SELECT * FROM reservation_payment WHERE appointment_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray()))result.put(str(row,"appointmentId"),view(row));
        return result;
    }
    Map<String,Object> view(Map<String,Object> row){
        var result=new LinkedHashMap<>(row);result.put("paymentEnabled",false);result.put("canPay",false);
        result.put("mode","RESERVATION_ONLY");return result;
    }
    void event(Map<String,Object> payment,String type,String actor,String key,String hash,Map<String,Object> detail){
        d.update("INSERT INTO reservation_payment_event(id,payment_id,event_type,version,actor_id,idempotency_key,request_hash,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            id(),payment.get("id"),type,payment.get("version"),actor,key,hash,d.stringify(detail),now());
    }
    static void key(String key){if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))throw error(400,"缺少有效的结算重复提交保护标识。");}
    Map<String,Object> memberForBalance(Map<String,Object> appointment){
        var member=d.one("SELECT * FROM appointment_membership WHERE appointment_id=?",appointment.get("id"));
        if(!bool(member,"memberVerified")||str(member,"memberStoreId").isBlank()||str(member,"customerProfileId").isBlank())
            throw error(409,"会员身份与归属门店尚未核实，不能登记会员储值结算。");
        var profiles=d.rows("SELECT * FROM customer_profile WHERE id=?",member.get("customerProfileId"));
        if(profiles.isEmpty()||!bool(profiles.get(0),"memberVerified")||!str(member,"memberStoreId").equals(str(profiles.get(0),"originalStoreId")))
            throw error(409,"会员档案或归属门店已变更，请由总部核实后重新处理结算。");
        return member;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> reserve(Admin actor,String appointment,Map<String,Object> input,String key){
        actor.require("payments:write");key(key);
        CustomerProfiles.checkFields(input,Set.of("amountFen","channel","version","appointmentVersion"));
        var initial=d.one("SELECT * FROM appointment WHERE id=?",appointment);actor.store(str(initial,"storeId"));
        new BookingLocks(d).acquire("store:"+str(initial,"storeId"),"phone:"+str(initial,"phone"));
        var booking=d.one("SELECT * FROM appointment WHERE id=?",appointment);
        var old=d.one("SELECT * FROM reservation_payment WHERE appointment_id=?",appointment);
        String hash=CustomerProfiles.digest(d.stringify(new TreeMap<>(input)));
        var previous=d.rows("SELECT request_hash FROM reservation_payment_event WHERE payment_id=? AND actor_id=? AND idempotency_key=?",old.get("id"),actor.id(),key);
        if(!previous.isEmpty()){
            if(!hash.equals(str(previous.get(0),"requestHash")))throw error(409,"同一结算提交标识的内容已变化，请刷新后重试。");
            return view(old);
        }
        if(Set.of("CANCELLED","NO_SHOW").contains(str(booking,"status"))||str(old,"status").equals("CLOSED"))throw error(409,"预约已取消或未到店，不能重新登记结算。");
        if(integer(input,"version",-1)!=integer(old,"version",0)||integer(input,"appointmentVersion",-1)!=integer(booking,"version",0))throw error(409,"预约或结算资料已更新，请刷新后重试。");
        String channel=text(input,"channel",1,24);if(!CHANNELS.contains(channel))throw error(400,"结算方式不正确。");
        Long amount=null;
        if(input.get("amountFen")!=null){
            String value=str(input,"amountFen");if(!value.matches("[1-9][0-9]{0,8}"))throw error(400,"结算金额须为正整数分，且不超过999999999分。");amount=Long.parseLong(value);
        }
        String settlement="NOT_STARTED";
        if(channel.equals("MEMBER_BALANCE")&&bool(memberForBalance(booking),"crossStore"))settlement="RULES_PENDING";
        d.update("UPDATE reservation_payment SET amount_fen=?,channel=?,status=?,settlement_status=?,version=version+1,updated_at=? WHERE id=?",amount,channel,amount==null?"UNPRICED":"RESERVED",settlement,now(),old.get("id"));
        var updated=d.one("SELECT * FROM reservation_payment WHERE id=?",old.get("id"));
        var detail=new LinkedHashMap<String,Object>();detail.put("amountFen",amount);detail.put("channel",channel);detail.put("appointmentVersion",booking.get("version"));
        event(updated,"RESERVATION_UPDATED",actor.id(),key,hash,detail);
        d.audit(actor,str(booking,"storeId"),"payment.reserve",appointment,"登记结算意向；未支付、未扣储值、未跨店划账");
        return view(updated);
    }

    void appointmentChanged(Map<String,Object> before,Map<String,Object> after){
        boolean close=Set.of("CANCELLED","NO_SHOW").contains(str(after,"status"));
        boolean reprice=integer(before,"headcount",1)!=integer(after,"headcount",1);
        if(!close&&!reprice)return;
        var rows=d.rows("SELECT * FROM reservation_payment WHERE appointment_id=?",after.get("id"));if(rows.isEmpty())return;
        var old=rows.get(0);if(str(old,"status").equals("CLOSED"))return;
        if(close)d.update("UPDATE reservation_payment SET status='CLOSED',settlement_status='NOT_STARTED',version=version+1,updated_at=? WHERE id=?",now(),old.get("id"));
        else d.update("UPDATE reservation_payment SET amount_fen=NULL,status='UNPRICED',version=version+1,updated_at=? WHERE id=?",now(),old.get("id"));
        event(d.one("SELECT * FROM reservation_payment WHERE id=?",old.get("id")),close?"APPOINTMENT_CLOSED":"REPRICE_REQUIRED","SYSTEM",null,null,Map.of("appointmentVersion",after.get("version")));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> initiate(String identity,String appointment,Map<String,Object> input,String key){
        key(key);CustomerProfiles.checkFields(input,Set.of("version"));
        var initial=d.one("SELECT * FROM appointment WHERE id=? AND customer_id=?",appointment,identity);
        new BookingLocks(d).acquire("store:"+str(initial,"storeId"),"phone:"+str(initial,"phone"));
        var booking=d.one("SELECT * FROM appointment WHERE id=? AND customer_id=?",appointment,identity);
        var payment=d.one("SELECT * FROM reservation_payment WHERE appointment_id=?",appointment);
        if(!Set.of("CONFIRMED","ARRIVED","COMPLETED").contains(str(booking,"status"))||!str(payment,"status").equals("RESERVED"))throw error(409,"请先由门店确认预约及结算金额。");
        if(integer(input,"version",-1)!=integer(payment,"version",0))throw error(409,"结算资料已更新，请刷新。");
        if(str(payment,"channel").equals("UNSELECTED"))throw error(409,"尚未确认结算方式。");
        var member=d.one("SELECT * FROM appointment_membership WHERE appointment_id=?",appointment);
        if(str(payment,"channel").equals("MEMBER_BALANCE"))member=memberForBalance(booking);
        return port.initiate(new PaymentPort.Request(str(payment,"id"),appointment,identity,((Number)payment.get("amountFen")).longValue(),"CNY",str(payment,"channel"),str(member,"customerProfileId"),str(payment,"memberStoreId"),str(payment,"serviceStoreId"),key));
    }
}
