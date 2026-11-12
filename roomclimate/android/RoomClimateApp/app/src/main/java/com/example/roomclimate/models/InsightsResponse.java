package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/** Ответ GET /telemetry/insights: комфорт, точка росы, риск плесени, сводка за период. */
public class InsightsResponse {
    @SerializedName("device_id")
    public String deviceId;
    @SerializedName("last_seen")
    public String lastSeen;
    @SerializedName("age_seconds")
    public Integer ageSeconds;
    public boolean online;
    @SerializedName("stale_after_seconds")
    public int staleAfterSeconds;
    public Double temperature;
    public Double humidity;
    @SerializedName("dew_point")
    public Double dewPoint;
    public Level comfort;
    @SerializedName("mold_risk")
    public Level moldRisk;
    public List<String> advice;
    public Map<String, String> thresholds;
    @SerializedName("period_hours")
    public int periodHours;
    public Map<String, Stats> stats;

    public static class Level {
        public String code;
        public String label;
    }

    public static class Stats {
        public double min;
        public double max;
        public double avg;
        public int count;
    }
}
