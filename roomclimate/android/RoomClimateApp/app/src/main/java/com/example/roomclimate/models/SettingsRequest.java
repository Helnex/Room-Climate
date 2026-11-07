package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class SettingsRequest {
    @SerializedName("temp_min")
    public double tempMin;
    @SerializedName("temp_max")
    public double tempMax;
    @SerializedName("humidity_min")
    public double humidityMin;
    @SerializedName("humidity_max")
    public double humidityMax;

    public SettingsRequest() {}

    public SettingsRequest(double tempMin, double tempMax, double humidityMin, double humidityMax) {
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.humidityMin = humidityMin;
        this.humidityMax = humidityMax;
    }
}
