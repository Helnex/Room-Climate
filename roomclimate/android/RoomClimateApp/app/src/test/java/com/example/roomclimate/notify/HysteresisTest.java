package com.example.roomclimate.notify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HysteresisTest {
    private static final double MIN = 18;
    private static final double MAX = 26;

    @Test
    public void alarmHoldsNearThresholdAndClearsWithMargin() {
        ThresholdChecker.Status s = ThresholdChecker.checkWithHysteresis(26.2, MIN, MAX, null, 0.5);
        assertEquals(ThresholdChecker.Status.ABOVE, s);
        // Колебания у порога — тревога держится, нового уведомления не будет
        s = ThresholdChecker.checkWithHysteresis(25.9, MIN, MAX, s, 0.5);
        assertEquals(ThresholdChecker.Status.ABOVE, s);
        s = ThresholdChecker.checkWithHysteresis(26.1, MIN, MAX, s, 0.5);
        assertEquals(ThresholdChecker.Status.ABOVE, s);
        // Вернулась с запасом — снята
        s = ThresholdChecker.checkWithHysteresis(25.4, MIN, MAX, s, 0.5);
        assertEquals(ThresholdChecker.Status.OK, s);
    }

    @Test
    public void belowSideAndUnknown() {
        assertEquals(ThresholdChecker.Status.BELOW,
                ThresholdChecker.checkWithHysteresis(18.3, MIN, MAX, ThresholdChecker.Status.BELOW, 0.5));
        assertEquals(ThresholdChecker.Status.OK,
                ThresholdChecker.checkWithHysteresis(18.6, MIN, MAX, ThresholdChecker.Status.BELOW, 0.5));
        assertEquals(ThresholdChecker.Status.UNKNOWN,
                ThresholdChecker.checkWithHysteresis(null, MIN, MAX, ThresholdChecker.Status.ABOVE, 0.5));
        assertEquals(2.0, ThresholdChecker.marginFor("humidity"), 0.0);
        assertEquals(0.5, ThresholdChecker.marginFor("temperature"), 0.0);
    }
}
