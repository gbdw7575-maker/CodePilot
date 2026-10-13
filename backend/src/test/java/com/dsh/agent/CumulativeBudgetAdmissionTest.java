package com.dsh.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CumulativeBudgetAdmissionTest {
    @Test void ampleBudgetPreservesConfiguredOutput() {
        assertEquals(4096, CumulativeBudgetAdmission.outputLimit(1000, 4096, 100000));
    }
    @Test void capsOutputAndKeepsInputUnchanged() {
        assertEquals(936, CumulativeBudgetAdmission.outputLimit(1000, 4096, 2000));
        assertEquals(2000, CumulativeBudgetAdmission.reservation(1000, 936));
    }
    @Test void exactMinimumIsAdmittedOneLessIsRejected() {
        assertEquals(256, CumulativeBudgetAdmission.outputLimit(1000, 4096, 1320));
        assertThrows(BudgetAdmissionException.class,
                () -> CumulativeBudgetAdmission.outputLimit(1000, 4096, 1319));
    }
    @Test void configuredSmallOutputIsNotRaisedTo256() {
        assertEquals(32, CumulativeBudgetAdmission.outputLimit(1000, 32, 1096));
        assertThrows(BudgetAdmissionException.class,
                () -> CumulativeBudgetAdmission.outputLimit(1000, 32, 1095));
    }
    @Test void marginIsFivePercentAndArithmeticDoesNotWrap() {
        assertEquals(1000, CumulativeBudgetAdmission.headroom(20000));
        assertEquals(301, CumulativeBudgetAdmission.outputLimit(20000, 4096, 21301));
        assertThrows(BudgetAdmissionException.class,
                () -> CumulativeBudgetAdmission.outputLimit(Integer.MAX_VALUE, 4096, Integer.MAX_VALUE));
        assertThrows(BudgetAdmissionException.class,
                () -> CumulativeBudgetAdmission.reservation(Integer.MAX_VALUE, 4096));
    }
    @Test void inverseInputLimitFitsAllRoundingBoundaries() {
        for (int balance : new int[]{320, 321, 1320, 1321, 21001, Integer.MAX_VALUE}) {
            int input = CumulativeBudgetAdmission.inputLimit(balance, 256);
            if (input == 0) continue;
            assertTrue(CumulativeBudgetAdmission.inputCost(input) + 256 <= balance);
            assertTrue(CumulativeBudgetAdmission.inputCost(input + 1) + 256 > balance);
        }
    }
    @Test void invalidEstimatesAreNotTreatedAsFreeRequests() {
        assertThrows(IllegalArgumentException.class, () -> CumulativeBudgetAdmission.inputCost(0));
        assertThrows(IllegalArgumentException.class, () -> CumulativeBudgetAdmission.minimumOutput(0));
    }
}
