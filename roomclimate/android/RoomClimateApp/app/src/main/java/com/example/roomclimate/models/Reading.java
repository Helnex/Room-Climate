package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class Reading {
    public double value;
    public String unit;
    @SerializedName("measured_at")
    public String measuredAt;
    @SerializedName("device_id")
    public String deviceId;
}
