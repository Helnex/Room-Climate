package com.example.roomclimate;

import com.example.roomclimate.models.CurrentTelemetryResponse;
import com.example.roomclimate.models.HealthResponse;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.models.SettingsResponse;
import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ApiModelsTest {
    private final Gson gson = new Gson();

    @Test
    public void parseCurrentTelemetryFromServerShape() {
        String json = "{"
                + "\"device_id\":\"pi-1\","
                + "\"source\":\"redis\","
                + "\"readings\":{"
                + "\"temperature\":{\"value\":24.1,\"unit\":\"C\",\"measured_at\":\"2026-10-31T12:28:42+00:00\",\"device_id\":\"pi-1\"},"
                + "\"humidity\":{\"value\":41.0,\"unit\":\"%\",\"measured_at\":\"2026-10-31T12:28:42+00:00\",\"device_id\":\"pi-1\"}"
                + "}}";
        CurrentTelemetryResponse body = gson.fromJson(json, CurrentTelemetryResponse.class);
        assertEquals("pi-1", body.deviceId);
        assertEquals("redis", body.source);
        assertNotNull(body.reading("temperature"));
        assertEquals(24.1, body.reading("temperature").value, 0.01);
        assertEquals("C", body.reading("temperature").unit);
        assertEquals(41.0, body.reading("humidity").value, 0.01);
    }

    @Test
    public void parseHistoryFromServerShape() {
        String json = "{"
                + "\"device_id\":\"pi-1\","
                + "\"count\":2,"
                + "\"points\":["
                + "{\"type\":\"temperature\",\"unit\":\"C\",\"value\":24.1,\"measured_at\":\"2026-10-31T12:28:42+00:00\"},"
                + "{\"type\":\"humidity\",\"unit\":\"%\",\"value\":41.0,\"measured_at\":\"2026-10-31T12:28:42+00:00\"}"
                + "]}";
        HistoryResponse body = gson.fromJson(json, HistoryResponse.class);
        assertEquals(2, body.count);
        assertEquals("temperature", body.points.get(0).type);
        assertEquals(24.1, body.points.get(0).value, 0.01);
    }

    @Test
    public void parseSettingsFromServerShape() {
        String json = "{\"device_id\":\"pi-1\",\"temp_min\":18.0,\"temp_max\":26.0,\"saved\":true}";
        SettingsResponse body = gson.fromJson(json, SettingsResponse.class);
        assertTrue(body.saved);
        assertEquals(18.0, body.tempMin, 0.01);
        assertEquals(26.0, body.tempMax, 0.01);
    }

    @Test
    public void parseHealthFromServerShape() {
        String json = "{\"status\":\"ok\",\"redis\":true,\"sqlite\":true}";
        HealthResponse body = gson.fromJson(json, HealthResponse.class);
        assertTrue(body.isOk());
    }
}
