package com.yuwatang.reservation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.yuwatang.reservation.Data.*;

@Component
class RescheduleMaintenance {
    final Data d;final BookingService bookings;
    RescheduleMaintenance(Data d,BookingService bookings){this.d=d;this.bookings=bookings;}
    @Scheduled(fixedDelay=60000,initialDelay=45000) public void expire() {
        // Each request is rechecked under the same store lock used for changes and approval.
        for(var row:d.rows("SELECT appointment_id FROM appointment_reschedule WHERE status='PENDING' AND expires_at<=? ORDER BY expires_at LIMIT 200",now()))
            bookings.expireReschedule(str(row,"appointmentId"));
    }
}
