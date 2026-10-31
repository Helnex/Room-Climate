package com.example.roomclimate.models;

public class HealthResponse {
    public String status;
    public boolean redis;
    public boolean sqlite;

    public boolean isOk() {
        return "ok".equals(status) && redis && sqlite;
    }
}
