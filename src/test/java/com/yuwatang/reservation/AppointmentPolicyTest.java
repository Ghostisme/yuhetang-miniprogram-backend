package com.yuwatang.reservation;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yuwatang.reservation.Data.*;
import static org.junit.jupiter.api.Assertions.*;

class AppointmentPolicyTest {
    final Map<String,Object> booking=Map.of("bookingDate","2026-11-09","startTime","10:00","status","CONFIRMED");
    Map<String,Object> actions(String at,int hours){return AppointmentPolicy.actions(booking,Map.of("cancelHours",hours),Map.of(),OffsetDateTime.parse(at));}
    @Test void cutoffIsStrictAndDoesNotDependOnServerDefaultTimezone(){
        assertTrue(bool(actions("2026-11-09T07:59:59+08:00",2),"canCancel"));
        assertFalse(bool(actions("2026-11-09T08:00:00+08:00",2),"canCancel"));
        assertFalse(bool(actions("2026-11-09T00:00:00Z",2),"canReschedule"));
        assertFalse(bool(actions("2026-11-09T09:59:59+08:00",2),"canCancel"));
    }
    @Test void zeroHourRuleNeverAllowsCancellationAtOrAfterStart(){
        assertTrue(bool(actions("2026-11-09T09:59:59+08:00",0),"canCancel"));
        assertFalse(bool(actions("2026-11-09T10:00:00+08:00",0),"canCancel"));
        assertFalse(bool(actions("2026-11-09T10:00:01+08:00",0),"canReschedule"));
    }
    @Test void endedAndArrivedAppointmentsNeverOfferSelfService(){
        for(String status:List.of("ARRIVED","COMPLETED","CANCELLED","NO_SHOW")){
            var b=new HashMap<>(booking);b.put("status",status);
            var actions=AppointmentPolicy.actions(b,Map.of("cancelHours",0),Map.of(),OffsetDateTime.parse("2026-11-09T07:00:00+08:00"));
            assertFalse(bool(actions,"canCancel"));assertFalse(bool(actions,"canReschedule"));
        }
    }
    @Test void pendingRequestCanBeWithdrawnWithoutReleasingOriginalEvenAfterSelfServiceCutoff(){
        var request=Map.<String,Object>of("status","PENDING","expiresAt","2026-11-09T10:00:00+08:00");
        var actions=AppointmentPolicy.actions(booking,Map.of("cancelHours",2),request,OffsetDateTime.parse("2026-11-09T09:00:00+08:00"));
        assertFalse(bool(actions,"canCancel"));assertFalse(bool(actions,"canReschedule"));assertTrue(bool(actions,"canWithdrawReschedule"));
        actions=AppointmentPolicy.actions(booking,Map.of("cancelHours",2),request,OffsetDateTime.parse("2026-11-09T10:00:00+08:00"));
        assertFalse(bool(actions,"canWithdrawReschedule"));
    }
}
