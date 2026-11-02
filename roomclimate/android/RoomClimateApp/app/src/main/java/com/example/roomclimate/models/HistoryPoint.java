package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

public class HistoryPoint {
    public String type;
    public String unit;
    public double value;
    @SerializedName("measured_at")
    public String measuredAt;
}
