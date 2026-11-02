package com.example.roomclimate;

import com.example.roomclimate.notify.ThresholdChecker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThresholdCheckerTest {
    @Test
    public void insideRangeIsOk() {
        assertEquals(ThresholdChecker.Status.OK, ThresholdChecker.check(22.0, 18.0, 26.0));
        assertFalse(ThresholdChecker.isAlarm(ThresholdChecker.Status.OK));
    }

    @Test
    public void belowMinIsAlarm() {
        assertEquals(ThresholdChecker.Status.BELOW, ThresholdChecker.check(10.0, 18.0, 26.0));
        assertTrue(ThresholdChecker.isAlarm(ThresholdChecker.Status.BELOW));
    }

    @Test
    public void aboveMaxIsAlarm() {
        assertEquals(ThresholdChecker.Status.ABOVE, ThresholdChecker.check(30.0, 18.0, 26.0));
        assertTrue(ThresholdChecker.isAlarm(ThresholdChecker.Status.ABOVE));
    }

    @Test
    public void missingValuesAreUnknown() {
        assertEquals(ThresholdChecker.Status.UNKNOWN, ThresholdChecker.check(null, 18.0, 26.0));
        assertEquals(ThresholdChecker.Status.UNKNOWN, ThresholdChecker.check(22.0, null, 26.0));
    }
}
