package com.yuwatang.reservation;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared database locks: all application instances use the same ordered stripes. */
@Component
final class BookingLocks {
    private static final int STRIPES = 256;
    private final Data d;
    BookingLocks(Data d) { this.d = d; }

    void acquire(String... resources) { acquire(Arrays.asList(resources)); }

    void acquire(Collection<String> resources) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Booking guards require an active transaction");
        // A stable order prevents deadlocks when a request touches multiple stores/phones.
        // Collisions only serialize extra work; they cannot bypass a booking invariant.
        resources.stream().filter(Objects::nonNull).filter(s -> !s.isBlank())
            .map(s -> Math.floorMod(s.hashCode(), STRIPES)).distinct().sorted()
            .forEach(stripe -> d.one("SELECT id FROM booking_guard WHERE id=? FOR UPDATE", stripe));
    }
}
