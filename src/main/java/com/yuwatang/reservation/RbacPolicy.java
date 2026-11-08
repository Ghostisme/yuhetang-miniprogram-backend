package com.yuwatang.reservation;

import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Permission dependencies and scope rules shared by role validation and the editor. */
final class RbacPolicy {
    private RbacPolicy() {}
    static final Set<String> HEADQUARTERS_ONLY=Set.of("content:write","content:publish","roles:write",
        "chat:route","customers:master","appointments:export","customers:export","imports:write","integrations:read",
        "wechat-menu:read","wechat-menu:write","wechat-menu:publish");
    static final Map<String,List<String>> REQUIRES=Map.ofEntries(
        Map.entry("wechat-menu:write",List.of("wechat-menu:read")),
        Map.entry("wechat-menu:publish",List.of("wechat-menu:read")),
        Map.entry("services:write",List.of("services:read")),
        Map.entry("content:write",List.of("content:read")),
        Map.entry("content:publish",List.of("content:read")),
        Map.entry("assets:write",List.of("assets:read")),
        Map.entry("chat:reply",List.of("chat:read")),
        Map.entry("chat:route",List.of("chat:read")),
        Map.entry("appointments:write",List.of("appointments:read")),
        Map.entry("appointments:review",List.of("appointments:read")),
        Map.entry("appointments:status",List.of("appointments:read")),
        Map.entry("appointments:phone",List.of("appointments:read")),
        Map.entry("appointments:export",List.of("appointments:read")),
        Map.entry("customers:write",List.of("customers:read")),
        Map.entry("customers:master",List.of("customers:read")),
        Map.entry("customers:shared",List.of("customers:read")),
        Map.entry("customers:export",List.of("customers:read")),
        Map.entry("therapists:write",List.of("therapists:read")),
        Map.entry("users:write",List.of("users:read","roles:read")),
        Map.entry("roles:write",List.of("roles:read")),
        Map.entry("notifications:write",List.of("notifications:read")));

    static Set<String> normalize(Data d,List<String> requested,String scope) {
        Set<String> result=new LinkedHashSet<>(requested);
        for(String code:requested) {
            if(d.count("SELECT COUNT(*) FROM permission WHERE code=?",code)!=1)throw error(400,"权限标识不存在。");
        }
        boolean added;
        do {
            added=false;
            for(String code:List.copyOf(result))added|=result.addAll(REQUIRES.getOrDefault(code,List.of()));
        } while(added);
        if(scope.equals("STORE")&&result.stream().anyMatch(HEADQUARTERS_ONLY::contains))
            throw error(400,"门店角色不能配置总部专属权限，请使用总部范围角色。");
        return result;
    }

    static List<Map<String,Object>> catalog(Data d) {
        return d.rows("SELECT * FROM permission ORDER BY module,code").stream().peek(p->{
            String code=str(p,"code");
            p.put("headquartersOnly",HEADQUARTERS_ONLY.contains(code));
            p.put("requires",REQUIRES.getOrDefault(code,List.of()));
        }).toList();
    }
}
