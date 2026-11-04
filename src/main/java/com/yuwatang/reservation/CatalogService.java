package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
public class CatalogService {
    final Data d; CatalogService(Data d){this.d=d;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> collection(Map<String,Object> site,String key){return (List<Map<String,Object>>)site.getOrDefault(key,List.of());}
    Map<String,Object> site(){var row=d.one("SELECT * FROM site_config WHERE id='brand'");var site=d.object(row.get("publishedJson"));site.put("version",row.get("revision"));site.put("stores",d.rows("SELECT * FROM store WHERE enabled=TRUE ORDER BY name").stream().map(s->d.object(s.get("profileJson"))).toList());site.remove("assetLibrary");site.remove("sharedLookupEnabled");return site;}
    Map<String,Object> store(String id){return d.one("SELECT * FROM store WHERE id=?",id);}
    Map<String,Object> rules(String storeId){return d.object(store(storeId).get("rulesJson"));}
    Map<String,Object> service(String id){return collection(site(),"services").stream().filter(s->id.equals(str(s,"id"))&&bool(s,"enabled")).findFirst().orElseThrow(()->error(400,"该项目暂未开放预约。"));}
    List<Map<String,Object>> therapists(String storeId,boolean publicOnly){
        store(storeId);
        return therapistProfiles(d.rows("SELECT t.*,s.name store_name FROM therapist t JOIN store s ON s.id=t.store_id WHERE t.store_id=? ORDER BY t.name,t.id",storeId),publicOnly);
    }
    List<Map<String,Object>> allTherapists(){
        return therapistProfiles(d.rows("SELECT t.*,s.name store_name FROM therapist t JOIN store s ON s.id=t.store_id ORDER BY s.name,t.name,t.id"),false);
    }
    private List<Map<String,Object>> therapistProfiles(List<Map<String,Object>> rows,boolean publicOnly){
        return rows.stream()
            .filter(t->!publicOnly||bool(t,"enabled"))
            .map(t->{
                var profile=d.object(t.get("profileJson"));
                // Ownership and identity come from relational records, never editable profile JSON.
                for(String key:List.of("id","name","enabled","storeId"))profile.put(key,t.get(key));
                profile.put("storeName",str(t,"storeName"));
                return profile;
            }).filter(t->!publicOnly||bool(t,"showOnClient")).toList();
    }
    void bookingLock(){d.one("SELECT * FROM app_setting WHERE setting_key='bookingLock' FOR UPDATE");}
    int used(String store,String date,String time,String excluding){return d.count("SELECT COALESCE(SUM(headcount),0) FROM appointment WHERE store_id=? AND booking_date=? AND start_time=? AND status IN ('PENDING','CONFIRMED','ARRIVED') AND id<>?",store,date,time,excluding==null?"":excluding);}
    int capacity(String store,String date,String time,Map<String,Object> rules){if(d.strings(rules.get("closedDates")).contains(date))return 0;var override=d.rows("SELECT * FROM slot_override WHERE store_id=? AND booking_date=? AND start_time IN ('*',?) ORDER BY start_time",store,date,time);int cap=integer(rules,"capacity",4);for(var o:override){if(bool(o,"closed"))return 0;cap=integer(o,"capacity",cap);}return cap;}
    LocalDate date(String value,Map<String,Object> rules){try{LocalDate date=LocalDate.parse(value);LocalDate today=LocalDate.now(ZONE);if(date.isBefore(today)||date.isAfter(today.plusDays(integer(rules,"advanceDays",7))))throw error(400,"请选择开放预约范围内的日期。");return date;}catch(java.time.format.DateTimeParseException e){throw error(400,"日期格式不正确。");}}
    void checkSlot(String store,String date,String time,int headcount,String excluding){var s=store(store);var profile=d.object(s.get("profileJson"));if(!bool(s,"enabled")||!bool(profile,"onlineBooking"))throw error(400,"该门店请电话咨询预约。");var r=rules(store);LocalDate day=date(date,r);LocalTime start;try{start=LocalTime.parse(time);}catch(Exception e){throw error(400,"请选择预约开始时间。");}LocalTime open=LocalTime.parse(str(r,"opens")),close=LocalTime.parse(str(r,"closes"));if(start.isBefore(open)||!start.isBefore(close)||Duration.between(open,start).toMinutes()%integer(r,"interval",15)!=0)throw error(400,"该开始时段未开放。");if(day.atTime(start).isBefore(LocalDateTime.now(ZONE).plusMinutes(integer(r,"minLeadMinutes",30))))throw error(400,"该时段已过预约截止时间，请选择其他时段。");if(headcount<1||headcount>integer(r,"maxHeadcount",4))throw error(400,"预约人数超出本店限制。");int remaining=capacity(store,date,time,r)-used(store,date,time,excluding);if(remaining<headcount)throw error(409,"该时段剩余名额不足，请重新选择。");}
    Map<String,Object> slots(String store,String day){return slots(store,day,null);}
    // The caller must check appointment ownership before excluding its occupied seats.
    Map<String,Object> slots(String store,String day,String excluding){
        var s=store(store);var r=d.object(s.get("rulesJson"));LocalDate date=date(day,r);
        LocalTime open=LocalTime.parse(str(r,"opens")),close=LocalTime.parse(str(r,"closes"));
        var profile=d.object(s.get("profileJson"));var cutoff=LocalDateTime.now(ZONE).plusMinutes(integer(r,"minLeadMinutes",30));
        List<Map<String,Object>> slots=new ArrayList<>();
        // Integer minutes do not wrap to midnight when a final interval passes the closing time.
        for(int minute=open.toSecondOfDay()/60;minute<close.toSecondOfDay()/60;minute+=integer(r,"interval",15)){
            LocalTime time=LocalTime.ofSecondOfDay(minute*60L);String t=time.toString();
            int used=used(store,day,t,excluding),cap=capacity(store,day,t,r);
            slots.add(Map.of("time",t,"capacity",cap,"used",used,"remaining",Math.max(0,cap-used),"available",!date.atTime(time).isBefore(cutoff)&&cap>used&&bool(profile,"onlineBooking")&&bool(s,"enabled")));
        }
        return Map.of("date",day,"storeId",store,"slots",slots,"rules",r);
    }
    List<Map<String,Object>> slotOverrides(String store){store(store);return d.rows("SELECT booking_date,start_time,capacity,closed FROM slot_override WHERE store_id=? AND booking_date>=? ORDER BY booking_date,start_time",store,LocalDate.now(ZONE).toString());}
    @Transactional public Map<String,Object> saveRules(Admin a,String store,Map<String,Object> input){a.store(store);bookingLock();Map<String,Object> r=new LinkedHashMap<>();try{LocalTime o=LocalTime.parse(text(input,"opens",5,5)),c=LocalTime.parse(text(input,"closes",5,5));if(!o.isBefore(c))throw error(400,"营业结束时间必须晚于开始时间。");r.put("opens",o.toString());r.put("closes",c.toString());}catch(java.time.format.DateTimeParseException e){throw error(400,"营业时间格式应为 HH:mm。");}int interval=integer(input,"interval",15);if(!Set.of(15,30,60).contains(interval))throw error(400,"间隔仅支持15、30、60分钟。");r.put("interval",interval);String[] keys={"capacity","advanceDays","minLeadMinutes","cancelHours","maxHeadcount","phoneOpenLimit","remindMinutes"};int[] defaults={4,7,30,2,4,3,15},mins={1,1,0,0,1,1,1},maxs={100,90,1440,72,20,20,1440};for(int i=0;i<keys.length;i++){int n=integer(input,keys[i],defaults[i]);if(n<mins[i]||n>maxs[i])throw error(400,"规则数值超出范围："+keys[i]);r.put(keys[i],n);}var dates=d.strings(input.get("closedDates"));for(String v:dates)try{LocalDate.parse(v);}catch(DateTimeException e){throw error(400,"休店日期格式应为 YYYY-MM-DD。");}r.put("closedDates",dates);
        for(var b:d.rows("SELECT booking_date,start_time,SUM(headcount) used FROM appointment WHERE store_id=? AND booking_date>=? AND status IN ('PENDING','CONFIRMED','ARRIVED') GROUP BY booking_date,start_time",store,LocalDate.now(ZONE).toString())){LocalTime time=LocalTime.parse(str(b,"startTime")),open=LocalTime.parse(str(r,"opens")),close=LocalTime.parse(str(r,"closes"));if(dates.contains(str(b,"bookingDate"))||integer(b,"used",0)>capacity(store,str(b,"bookingDate"),str(b,"startTime"),r)||time.isBefore(open)||!time.isBefore(close)||Duration.between(open,time).toMinutes()%interval!=0)throw error(409,"新规则会影响已有预约，请先人工处理相关预约。");}
        d.update("UPDATE store SET rules_json=? WHERE id=?",d.stringify(r),store);d.audit(a,store,"rules.update",store,"更新时段规则");return r;
    }
    @Transactional public void slotOverride(Admin a,String store,Map<String,Object> body){
        a.store(store);bookingLock();var rules=rules(store);String date=text(body,"date",10,10);date(date,rules);String time=text(body,"time",1,5);
        if(!time.equals("*")){
            LocalTime start;try{start=LocalTime.parse(time);}catch(DateTimeException e){throw error(400,"开始时间应为 HH:mm，全天请填写 *。");}
            LocalTime open=LocalTime.parse(str(rules,"opens")),close=LocalTime.parse(str(rules,"closes"));
            if(!time.equals(start.toString())||start.isBefore(open)||!start.isBefore(close)||Duration.between(open,start).toMinutes()%integer(rules,"interval",15)!=0)throw error(400,"请选择本店已开放的开始时段，全天请填写 *。");
        }
        int cap=integer(body,"capacity",integer(rules,"capacity",4));if(cap<0||cap>100)throw error(400,"名额范围为0至100。");boolean closed=bool(body,"closed");
        List<Map<String,Object>> bookings=d.rows("SELECT start_time,SUM(headcount) used FROM appointment WHERE store_id=? AND booking_date=? AND status IN ('PENDING','CONFIRMED','ARRIVED') GROUP BY start_time",store,date);
        for(var b:bookings)if(time.equals("*")||time.equals(str(b,"startTime")))if((closed?0:cap)<integer(b,"used",0))throw error(409,"已有生效预约占用名额，请先人工改约或取消，不能静默关闭。");
        d.update("DELETE FROM slot_override WHERE store_id=? AND booking_date=? AND start_time=?",store,date,time);d.update("INSERT INTO slot_override VALUES(?,?,?,?,?)",store,date,time,cap,closed);d.audit(a,store,"slots.update",date+" "+time,"名额="+cap+",关闭="+closed);
    }
}
