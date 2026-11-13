package com.example.roomclimate.widget;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WidgetStyleTest {
    @Test
    public void stateFollowsComfortButSilenceWins() {
        assertEquals(WidgetStyle.State.COMFORT, WidgetStyle.stateOf(true, true, true, "comfort"));
        assertEquals(WidgetStyle.State.HOT, WidgetStyle.stateOf(true, true, true, "hot"));
        assertEquals(WidgetStyle.State.COLD, WidgetStyle.stateOf(true, true, true, "cold"));
        assertEquals(WidgetStyle.State.HUMID, WidgetStyle.stateOf(true, true, true, "humid"));
        assertEquals(WidgetStyle.State.DRY, WidgetStyle.stateOf(true, true, true, "dry"));
        // Плата молчит — серый, даже если последние цифры были комфортными
        assertEquals(WidgetStyle.State.SILENT, WidgetStyle.stateOf(true, true, false, "comfort"));
        assertEquals(WidgetStyle.State.NO_DATA, WidgetStyle.stateOf(true, false, false, null));
        assertEquals(WidgetStyle.State.NO_DATA, WidgetStyle.stateOf(true, true, true, "unknown"));
        assertEquals(WidgetStyle.State.SIGNED_OUT, WidgetStyle.stateOf(false, true, true, "comfort"));
    }

    @Test
    public void numbersAreRussianStyle() {
        assertEquals("23,4°", WidgetStyle.temperature(23.44));
        assertEquals("-2,0°", WidgetStyle.temperature(-2.0));
        assertEquals("—°", WidgetStyle.temperature(null));
        assertEquals("42%", WidgetStyle.humidity(41.6));
        assertEquals("—%", WidgetStyle.humidity(Double.NaN));
    }

    @Test
    public void silentDurationAndSize() {
        assertEquals("12 мин", WidgetStyle.silentFor(750));
        assertEquals("1 мин", WidgetStyle.silentFor(20));
        assertEquals("2 ч", WidgetStyle.silentFor(7300));
        assertEquals("3 дн", WidgetStyle.silentFor(3 * 86400 + 5));
        assertEquals("", WidgetStyle.silentFor(null));
        assertTrue(WidgetStyle.isWide(280));
        assertFalse(WidgetStyle.isWide(140));
    }
}
