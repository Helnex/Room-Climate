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

    /** Короткий диапазон для плитки: 19.1 и 24.04 -> "19…24°" (в узкую плитку целиком). */
    public static String shortRange(double min, double max) {
        return Math.round(min) + "…" + Math.round(max) + "°";
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

    /** Большой виджет (с графиком) — если ещё и высота от ~220dp (4 ряда). */
    public static boolean isLarge(int minWidthDp, int minHeightDp) {
        return isWide(minWidthDp) && minHeightDp >= 220;
    }

    /** Цвет состояния (ARGB) — тот же, что у фонов виджета; для карточки в шторке. */
    public static int colorOf(State state) {
        switch (state) {
            case COMFORT:
                return 0xFF2FA36B;
            case HOT:
                return 0xFFEC7A2C;
            case COLD:
                return 0xFF2F7FD6;
            case HUMID:
                return 0xFF5B57D6;
            case DRY:
                return 0xFFC98A12;
            default:
                return 0xFF6E6E76;
        }
    }

    /**
     * Прореживание ряда для мини-графика: не больше max точек (среднее по корзинам).
     * Пропуски (null/NaN) отбрасываются.
     */
    public static double[] downsample(java.util.List<Double> values, int max) {
        java.util.List<Double> clean = new java.util.ArrayList<>();
        if (values != null) {
            for (Double v : values) {
                if (v != null && !v.isNaN() && !v.isInfinite()) {
                    clean.add(v);
                }
            }
        }
        int n = clean.size();
        if (n <= max) {
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = clean.get(i);
            }
            return out;
        }
        double[] out = new double[max];
        for (int b = 0; b < max; b++) {
            int from = (int) ((long) b * n / max);
            int to = (int) ((long) (b + 1) * n / max);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += clean.get(i);
            }
            out[b] = sum / Math.max(1, to - from);
        }
        return out;
    }

    /** Точки ряда в координатах картинки w×h с отступом pad: x по индексу, y — min снизу, max сверху. */
    public static float[] project(double[] values, float w, float h, float pad) {
        float[] xy = new float[values.length * 2];
        if (values.length == 0) {
            return xy;
        }
        double min = values[0];
        double max = values[0];
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double span = Math.max(0.5, max - min); // ровная линия не «скачет» на шуме 0,1°
        double mid = (max + min) / 2;
        for (int i = 0; i < values.length; i++) {
            float x = values.length == 1 ? w / 2 : pad + (w - 2 * pad) * i / (values.length - 1);
            float y = (float) (h / 2 - (values[i] - mid) / span * (h - 2 * pad));
            xy[2 * i] = x;
            xy[2 * i + 1] = y;
        }
        return xy;
    }
}
