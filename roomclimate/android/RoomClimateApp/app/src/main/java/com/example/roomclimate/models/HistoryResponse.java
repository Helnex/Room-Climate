package com.example.roomclimate.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class HistoryResponse {
    @SerializedName("device_id")
    public String deviceId;
    public int count;
    public List<HistoryPoint> points;
}
