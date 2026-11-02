package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class CurrentTelemetryResponse {
    @SerializedName("device_id")
    public String deviceId;
    public String source;
    public Map<String, Reading> readings;

    public Reading reading(String type) {
        if (readings == null) {
            return null;
        }
        return readings.get(type);
    }
}
