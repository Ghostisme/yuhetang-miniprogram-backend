package com.yuwatang.reservation;

import java.util.*;
import org.springframework.stereotype.Service;
import static com.yuwatang.reservation.Data.*;

/** Membership is brand-wide; appointments and private branch notes stay scoped to the receiving store. */
@Service
class MemberBookings {
    final Data d;
    MemberBookings(Data d){this.d=d;}

    void snapshot(String appointment,String identity,Admin actor,String phone,String store){
        List<Map<String,Object>> profiles;
        if(actor==null) profiles=d.rows("SELECT cp.* FROM customer_profile cp JOIN customer_account ca ON ca.customer_profile_id=cp.id WHERE ca.identity_id=? AND ca.phone_verified_at IS NOT NULL AND cp.phone=?",identity,phone);
        else profiles=d.rows("SELECT * FROM customer_profile WHERE phone=?",phone);
        var profile=profiles.isEmpty()?Map.<String,Object>of():profiles.get(0);
        boolean verified=bool(profile,"memberVerified");
        String home=verified?str(profile,"originalStoreId"):"";
        if(!home.isBlank()&&d.count("SELECT COUNT(*) FROM store WHERE id=?",home)==0)home="";
        d.update("INSERT INTO appointment_membership(appointment_id,customer_profile_id,member_verified,member_grade,member_store_id,service_store_id,cross_store,verification_method,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            appointment,profile.isEmpty()?null:profile.get("id"),verified,verified?str(profile,"memberGrade"):"",home.isBlank()?null:home,store,
            verified&&!home.isBlank()&&!home.equals(store),profile.isEmpty()?"NONE":actor==null?"WECHAT_PHONE":"STAFF_RECORD",now());
    }

    Map<String,Map<String,Object>> views(List<Object> ids,Map<String,String> stores){
        if(ids.isEmpty())return Map.of();
        Map<String,Map<String,Object>> result=new HashMap<>();
        for(var row:d.rows("SELECT * FROM appointment_membership WHERE appointment_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray())){
            String home=str(row,"memberStoreId");
            var view=new LinkedHashMap<String,Object>();
            for(String key:List.of("memberVerified","memberGrade","crossStore","verificationMethod"))view.put(key,row.get(key));
            view.put("memberStoreId",home);view.put("memberStoreName",stores.getOrDefault(home,""));
            view.put("serviceStoreId",row.get("serviceStoreId"));
            view.put("status",str(row,"verificationMethod").equals("LEGACY_UNKNOWN")?"LEGACY_UNKNOWN":!bool(row,"memberVerified")?"NON_MEMBER":home.isBlank()?"ORIGIN_PENDING":"MEMBER");
            result.put(str(row,"appointmentId"),view);
        }
        return result;
    }
}
