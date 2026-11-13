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
        assertEquals("19…24°", WidgetStyle.shortRange(19.1, 24.04));
        assertEquals("—%", WidgetStyle.humidity(Double.NaN));
    }

    @Test
    public void chartDownsampleAndProjection() {
        java.util.List<Double> values = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            values.add(20.0 + (i % 10));
        }
        values.add(null);
        values.add(Double.NaN);
        double[] small = WidgetStyle.downsample(values, 96);
        assertEquals(96, small.length);
        assertEquals(3, WidgetStyle.downsample(java.util.Arrays.asList(1.0, null, 2.0, 3.0), 96).length);

        // min внизу, max вверху, всё внутри отступов
        float[] xy = WidgetStyle.project(new double[]{20, 25, 22}, 300, 100, 10);
        assertEquals(10f, xy[0], 0.01f);       // первая точка у левого края
        assertEquals(290f, xy[4], 0.01f);      // последняя у правого
        assertEquals(90f, xy[1], 0.01f);       // 20° — минимум, внизу
        assertEquals(10f, xy[3], 0.01f);       // 25° — максимум, вверху
        // Почти ровный ряд не растягивается на всю высоту
        float[] flat = WidgetStyle.project(new double[]{22.0, 22.1}, 300, 100, 10);
        assertTrue(Math.abs(flat[1] - flat[3]) < 20f);
    }

    @Test
    public void largeSizeAndColors() {
        assertTrue(WidgetStyle.isLarge(280, 260));
        assertFalse(WidgetStyle.isLarge(280, 120));
        assertFalse(WidgetStyle.isLarge(140, 300));
        assertEquals(0xFF2FA36B, WidgetStyle.colorOf(WidgetStyle.State.COMFORT));
        assertEquals(0xFF6E6E76, WidgetStyle.colorOf(WidgetStyle.State.SILENT));
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
