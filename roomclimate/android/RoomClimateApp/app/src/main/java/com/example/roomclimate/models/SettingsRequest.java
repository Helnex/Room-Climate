package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class SettingsRequest {
    @SerializedName("temp_min")
    public double tempMin;
    @SerializedName("temp_max")
    public double tempMax;

    public SettingsRequest() {}

    public SettingsRequest(double tempMin, double tempMax) {
        this.tempMin = tempMin;
        this.tempMax = tempMax;
    }
}
