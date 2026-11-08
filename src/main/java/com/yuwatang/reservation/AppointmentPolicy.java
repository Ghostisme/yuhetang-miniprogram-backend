package com.yuwatang.reservation;

import java.time.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** The same server policy governs rendering, slot lookup and mutations. */
final class AppointmentPolicy {
    static LocalDateTime start(Map<String,Object> booking) {
        return LocalDate.parse(str(booking,"bookingDate")).atTime(LocalTime.parse(str(booking,"startTime")));
    }

    static Map<String,Object> actions(Map<String,Object> booking,Map<String,Object> rules,
                                      Map<String,Object> request,OffsetDateTime current) {
        var start=start(booking).atZone(ZONE).toOffsetDateTime();
        var deadline=start.minusHours(integer(rules,"cancelHours",2));
        boolean active=Set.of("PENDING","CONFIRMED").contains(str(booking,"status"));
        // Equality is already the cutoff, including cancelHours=0 at the start time.
        boolean before=active&&current.isBefore(deadline)&&current.isBefore(start);
        boolean pending=RescheduleRequests.pending(request,current);
        String reason=!active?"当前预约状态不能自助取消或改约。":!current.isBefore(start)?
            "已到预约时间，不能自助取消或改约，请联系门店。":!before?
            "已超过自助处理截止时间，请联系门店。":pending?
            "改约申请正在等待门店处理，原预约继续保留。":"";
        return Map.of("canCancel",before,"canReschedule",before&&!pending,
            "canWithdrawReschedule",active&&pending,"deadline",deadline.toString(),
            "serverTime",current.toString(),"reason",reason);
    }
}
