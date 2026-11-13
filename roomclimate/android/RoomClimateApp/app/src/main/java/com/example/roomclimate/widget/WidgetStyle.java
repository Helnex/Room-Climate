package com.example.roomclimate.widget;

import java.util.Locale;

/**
 * Оформление виджета без Android API (покрыто юнит-тестами): какой фон и какая подпись
 * соответствуют состоянию климата, как форматировать числа.
 *
 * Стиль — как у виджетов iOS: сплошной цветной фон по состоянию, крупная тонкая цифра
 * температуры, мелкие подписи белым.
 */
public final class WidgetStyle {
    /** Состояние виджета: определяет цвет фона, значок и подпись. */
    public enum State {
        COMFORT, HOT, COLD, HUMID, DRY, SILENT, NO_DATA, SIGNED_OUT
    }

    private WidgetStyle() {}

    /**
     * Состояние по ответу /telemetry/insights: молчащая плата важнее комфорта
     * (старые цифры не должны выглядеть «зелёными»).
     */
    public static State stateOf(boolean signedIn, boolean hasData, boolean online, String comfortCode) {
        if (!signedIn) {
            return State.SIGNED_OUT;
        }
        if (!hasData) {
            return State.NO_DATA;
        }
        if (!online) {
            return State.SILENT;
        }
        if (comfortCode == null) {
            return State.NO_DATA;
        }
        switch (comfortCode) {
            case "comfort":
                return State.COMFORT;
            case "hot":
                return State.HOT;
            case "cold":
                return State.COLD;
            case "humid":
                return State.HUMID;
            case "dry":
                return State.DRY;
            default:
                return State.NO_DATA;
        }
    }

    /** 23.44 -> "23,4°" (русская запятая, как в остальном приложении). */
    public static String temperature(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "—°";
        }
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',') + "°";
    }

    /** 41.6 -> "42%". */
    public static String humidity(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "—%";
        }
        return Math.round(value) + "%";
    }

    /** Сколько минут/часов молчит плата: 750 -> "12 мин", 7300 -> "2 ч". */
    public static String silentFor(Integer ageSeconds) {
        if (ageSeconds == null) {
            return "";
        }
        if (ageSeconds < 3600) {
            return Math.max(1, ageSeconds / 60) + " мин";
        }
        if (ageSeconds < 86400) {
            return ageSeconds / 3600 + " ч";
        }
        return ageSeconds / 86400 + " дн";
    }

    /** Широкий виджет помещается, если ячейка шире ~250dp (4 колонки на большинстве лаунчеров). */
    public static boolean isWide(int minWidthDp) {
        return minWidthDp >= 250;
    }
}
