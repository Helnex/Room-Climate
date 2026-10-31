package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class SettingsResponse {
    @SerializedName("device_id")
    public String deviceId;
    @SerializedName("temp_min")
    public double tempMin;
    @SerializedName("temp_max")
    public double tempMax;
    public boolean saved;
}
