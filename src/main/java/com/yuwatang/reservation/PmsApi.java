package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Stable boundary for a later PMS adapter. Events omit contact and health data. */
interface PmsPort {
    int SCHEMA_VERSION=1;
    List<Map<String,Object>> pending(int limit);
    void acknowledge(List<String> eventIds);
}
@Service class DatabasePmsPort implements PmsPort {
    final Data d;DatabasePmsPort(Data d){this.d=d;}
    public List<Map<String,Object>> pending(int limit){return d.rows("SELECT * FROM pms_outbox WHERE acknowledged=FALSE ORDER BY created_at,id LIMIT ?",Math.min(100,Math.max(1,limit))).stream().peek(e->e.put("payload",d.object(e.remove("payloadJson")))).toList();}
    public void acknowledge(List<String> ids){for(String id:ids)d.update("UPDATE pms_outbox SET acknowledged=TRUE WHERE id=?",id);}
}
@RestController class PmsApi {
    final boolean enabled;final String key;final PmsPort port;final Data d;
    PmsApi(Data d,PmsPort port,@Value("${app.pms.enabled}")boolean enabled,@Value("${app.pms.key}")String key){this.d=d;this.port=port;this.enabled=enabled;this.key=key;if(enabled&&key.length()<32)throw new IllegalStateException("PMS_API_KEY must contain at least 32 characters.");}
    void auth(String input){if(!enabled)throw error(404,"扩展接口尚未启用。");if(input==null||!MessageDigest.isEqual(input.getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))throw error(401,"PMS接口凭证无效。");}
    @GetMapping("/api/integrations/pms/v1/events")Map<String,Object> events(@RequestHeader(value="X-PMS-Key",required=false)String input,@RequestParam(defaultValue="50")int limit){auth(input);return Map.of("schemaVersion",1,"events",port.pending(limit));}
    @PostMapping("/api/integrations/pms/v1/ack")Map<String,Object> ack(@RequestHeader(value="X-PMS-Key",required=false)String input,@RequestBody Map<String,Object> in){auth(input);var ids=d.strings(in.get("eventIds"));if(ids.size()>100)throw error(400,"单次最多确认100条。");port.acknowledge(ids);return Map.of("acknowledged",true);}
    @GetMapping("/api/admin/integrations") @Permit("integrations:read")Map<String,Object> status(HttpServletRequest r){AdminService.hq((Admin)r.getAttribute("admin"));return Map.of("pms",Map.of("enabled",enabled,"schemaVersion",1,"pending",d.count("SELECT COUNT(*) FROM pms_outbox WHERE acknowledged=FALSE"),"port","PmsPort","description","预约事件与版本化数据协议已预留，财务、收银、会员余额等未接入"));}
}
