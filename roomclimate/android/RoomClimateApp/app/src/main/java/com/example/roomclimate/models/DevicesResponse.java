package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Ответ GET /devices: список плат и когда они последний раз присылали показания. */
public class DevicesResponse {
    public int count;
    public List<Device> devices;
    @SerializedName("stale_after_seconds")
    public int staleAfterSeconds;

    public static class Device {
        @SerializedName("device_id")
        public String deviceId;
        public int sensors;
        @SerializedName("last_seen")
        public String lastSeen;
        @SerializedName("age_seconds")
        public Integer ageSeconds;
        public boolean online;
    }
}
