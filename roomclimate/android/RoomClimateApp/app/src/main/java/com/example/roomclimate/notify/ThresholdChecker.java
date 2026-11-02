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

    public static boolean isAlarm(Status status) {
        return status == Status.BELOW || status == Status.ABOVE;
    }
}
