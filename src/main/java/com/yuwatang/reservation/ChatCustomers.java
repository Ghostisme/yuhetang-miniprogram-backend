package com.yuwatang.reservation;

import java.util.*;
import org.springframework.stereotype.Service;
import static com.yuwatang.reservation.Data.*;

/** A chat identity is established by this mini program's phone authorization, never SessionFrom. */
@Service
class ChatCustomers {
    static final String REQUIRED="请先登录并完善姓名、授权手机号后再联系客服。";
    final Data d;
    ChatCustomers(Data d){this.d=d;}
    Map<String,Object> find(String openid){
        var rows=d.rows("SELECT a.display_name,a.phone_verified_at,a.profile_consent_at,p.id profile_id,p.phone,p.member_verified,p.member_grade FROM customer_identity i JOIN customer_account a ON a.identity_id=i.id JOIN customer_profile p ON p.id=a.customer_profile_id WHERE i.openid=?",openid);
        if(rows.isEmpty())return Map.of();
        var row=rows.get(0);
        if(str(row,"displayName").isBlank()||str(row,"phone").isBlank()||str(row,"phoneVerifiedAt").isBlank()||str(row,"profileConsentAt").isBlank())return Map.of();
        return row;
    }
    String require(String identity){
        String openid=str(d.one("SELECT openid FROM customer_identity WHERE id=?",identity),"openid");
        requireOpenid(openid);return openid;
    }
    void requireOpenid(String openid){if(find(openid).isEmpty())throw error(403,REQUIRED);}
    Map<String,Object> view(String openid){
        var row=find(openid);if(row.isEmpty())return Map.of("profileComplete",false,"phoneVerified",false);
        var out=new LinkedHashMap<String,Object>();
        out.put("profileComplete",true);out.put("phoneVerified",true);out.put("name",str(row,"displayName"));out.put("phone",str(row,"phone"));
        out.put("profileId",str(row,"profileId"));out.put("memberVerified",bool(row,"memberVerified"));
        out.put("memberStatus",bool(row,"memberVerified")?"MEMBER":"NON_MEMBER");out.put("memberGrade",str(row,"memberGrade"));
        out.put("sources",CustomerProfiles.sources(d,str(row,"profileId")));return out;
    }
}
