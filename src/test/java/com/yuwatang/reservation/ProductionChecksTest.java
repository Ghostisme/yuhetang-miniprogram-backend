package com.yuwatang.reservation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionChecksTest {
    @Test void compatibleMysqlDriverDoesNotEnableProductionH2() {
        assertDoesNotThrow(() -> new ProductionChecks("production", "jdbc:mariadb://127.0.0.1/business").verify());
        assertDoesNotThrow(() -> new ProductionChecks("production", "jdbc:mysql://127.0.0.1/business").verify());
        assertThrows(IllegalStateException.class, () -> new ProductionChecks("production", "jdbc:h2:file:../data/reservation").verify());
        assertThrows(IllegalStateException.class, () -> new ProductionChecks("production", "jdbc:h2:mem:test").verify());
    }
}
