package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class SettingsResponse {
    @SerializedName("device_id")
    public String deviceId;
    @SerializedName("temp_min")
    public double tempMin;
    @SerializedName("temp_max")
    public double tempMax;
    @SerializedName("humidity_min")
    public Double humidityMin;
    @SerializedName("humidity_max")
    public Double humidityMax;
    public boolean saved;

    public double humidityMinOrDefault() {
        return humidityMin == null ? 30.0 : humidityMin;
    }

    public double humidityMaxOrDefault() {
        return humidityMax == null ? 70.0 : humidityMax;
    }
}
