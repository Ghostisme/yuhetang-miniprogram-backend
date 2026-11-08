package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the upgrade of existing records, not only an empty schema. */
class CustomerProfileMigrationTest {
    @Test void upgradesExistingLoginAndMultipleSourcesWithoutRewritingMembershipOrStoreNotes(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:customer_upgrade_"+id()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(source).target("8").load().migrate();
        var d=new Data(new JdbcTemplate(source),new ObjectMapper());String time="2026-11-01T10:00:00+08:00";
        d.update("INSERT INTO store VALUES('old-store','原门店',TRUE,'{}','{}')");
        d.update("INSERT INTO customer_identity VALUES('old-identity','old-openid',FALSE,?)",time);
        d.update("INSERT INTO customer_profile VALUES('old-profile','13900004001','原始会员',TRUE,'金卡','old-store','原共享备注','old-external',?)",time);
        d.update("INSERT INTO store_customer VALUES('old-store','old-profile','原私有跟进','PHONE',NULL)");
        var appointment=new LinkedHashMap<String,Object>();
        appointment.putAll(Map.of("id","old-booking","store_id","old-store","service_id","original-item","service_name","原项目","customer_name","原预约人","phone","13900004001","booking_date","2026-11-01","start_time","10:00","headcount",1,"member_claim","unknown"));
        appointment.putAll(Map.of("visited_claim","unknown","remark","原备注","sensitive_consent",false,"status","COMPLETED","source","MINIPROGRAM","version",1,"created_at",time,"updated_at",time,"idempotency_owner","old-owner","idempotency_key","old-key"));
        appointment.put("request_hash","original-hash");
        d.update("INSERT INTO appointment("+String.join(",",appointment.keySet())+") VALUES("+String.join(",",Collections.nCopies(appointment.size(),"?"))+")",appointment.values().toArray());
        d.update("INSERT INTO dianping_store_mapping VALUES('old-shop','原点评店铺','old-store',1,?)",time);
        d.update("INSERT INTO dianping_customer_link VALUES('old-shop','old-dp-customer','old-profile')");
        var before=d.one("SELECT * FROM customer_profile");var note=d.one("SELECT * FROM store_customer");var booking=d.one("SELECT * FROM appointment");
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(before,d.one("SELECT * FROM customer_profile"));assertEquals(note,d.one("SELECT * FROM store_customer"));assertEquals(booking,d.one("SELECT * FROM appointment"));
        var account=d.one("SELECT * FROM customer_account");assertEquals("old-identity",account.get("identityId"));assertNull(account.get("customerProfileId"));assertNull(account.get("phoneVerifiedAt"));
        assertEquals(time,account.get("firstLoginAt"));assertEquals(time,account.get("lastLoginAt"));
        assertEquals(Set.of("PHONE","DIANPING","MINIPROGRAM"),new HashSet<>(CustomerProfiles.sources(d,"old-profile")));
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(3,d.count("SELECT COUNT(*) FROM customer_source"));assertEquals(1,d.count("SELECT COUNT(*) FROM customer_account"));
    }
}
