package com.example.roomclimate.notify;

public final class ThresholdChecker {
    public enum Status {
        BELOW,
        OK,
        ABOVE,
        UNKNOWN
    }

    private ThresholdChecker() {}

    public static Status check(Double temperature, Double min, Double max) {
        if (temperature == null || min == null || max == null) {
            return Status.UNKNOWN;
        }
        if (temperature < min) {
            return Status.BELOW;
        }
        if (temperature > max) {
            return Status.ABOVE;
        }
        return Status.OK;
    }

    /**
     * Проверка с гистерезисом: если уже была тревога, она снимается только когда значение
     * вернулось в норму с запасом margin. Иначе при колебаниях у порога (25.9 → 26.1 → 25.9)
     * уведомления сыпались бы одно за другим.
     */
    public static Status checkWithHysteresis(Double value, Double min, Double max, Status previous, double margin) {
        Status raw = check(value, min, max);
        if (raw != Status.OK || previous == null) {
            return raw;
        }
        if (previous == Status.ABOVE && value > max - margin) {
            return Status.ABOVE;
        }
        if (previous == Status.BELOW && value < min + margin) {
            return Status.BELOW;
        }
        return Status.OK;
    }

    public static double marginFor(String metricKey) {
        return "humidity".equals(metricKey) ? 2.0 : 0.5;
    }

    public static boolean isAlarm(Status status) {
        return status == Status.BELOW || status == Status.ABOVE;
    }
}
