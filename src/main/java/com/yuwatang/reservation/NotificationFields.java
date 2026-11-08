package com.yuwatang.reservation;

import java.util.*;

/** Template values are booking summaries; phone numbers are limited to staff messages. */
final class NotificationFields {
    static final Set<String> COMMON=Set.of("storeName","serviceName","customerName","time","status","appointmentId","event","eventLabel");
    static boolean valid(String key,Map<String,Object> mapping){
        return !mapping.isEmpty()&&mapping.entrySet().stream().allMatch(e->
            e.getKey().matches("(?:[a-z_]+[0-9]+|first|remark)")&&
            (COMMON.contains(Objects.toString(e.getValue(),""))||key.equals("serviceTemplateMapping")&&Set.of("customerPhone","staffNotice").contains(Objects.toString(e.getValue(),""))));
    }
}
