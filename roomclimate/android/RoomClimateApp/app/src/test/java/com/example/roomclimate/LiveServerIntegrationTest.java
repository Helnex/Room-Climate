package com.example.roomclimate;

import com.example.roomclimate.models.AuthResponse;
import com.example.roomclimate.models.CurrentTelemetryResponse;
import com.example.roomclimate.models.HealthResponse;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.models.SettingsResponse;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Бьёт в Docker API на localhost и проверяет, что модели приложения
 * разбирают живой JSON. Если сервер не запущен — тест падает.
 */
public class LiveServerIntegrationTest {
    private static final String BASE = "http://127.0.0.1:8000";
    private final Gson gson = new Gson();

    @Before
    public void seedTelemetry() throws Exception {
        String payload = "{"
                + "\"device_id\":\"pi-1\","
                + "\"readings\":["
                + "{\"type\":\"temperature\",\"value\":24.1,\"unit\":\"C\"},"
                + "{\"type\":\"humidity\",\"value\":41.0,\"unit\":\"%\"}"
                + "]}";
        HttpResult posted = request("POST", "/telemetry", payload);
        assertEquals("POST /telemetry", 200, posted.code);
    }

    @Test
    public void healthMatchesAppModel() throws Exception {
        HttpResult result = request("GET", "/health", null);
        assertEquals(200, result.code);
        HealthResponse health = gson.fromJson(result.body, HealthResponse.class);
        assertTrue(health.isOk());
    }

    @Test
    public void currentTelemetryMatchesAppModel() throws Exception {
        HttpResult result = request("GET", "/telemetry/current?device_id=pi-1", null);
        assertEquals(200, result.code);
        CurrentTelemetryResponse current = gson.fromJson(result.body, CurrentTelemetryResponse.class);
        assertEquals("pi-1", current.deviceId);
        assertNotNull(current.reading("temperature"));
        assertNotNull(current.reading("humidity"));
        assertEquals(24.1, current.reading("temperature").value, 0.01);
        assertEquals("C", current.reading("temperature").unit);
    }

    @Test
    public void historyMatchesAppModel() throws Exception {
        HttpResult result = request("GET", "/telemetry/history?device_id=pi-1", null);
        assertEquals(200, result.code);
        HistoryResponse history = gson.fromJson(result.body, HistoryResponse.class);
        assertTrue(history.count >= 2);
        assertNotNull(history.points);
        assertNotNull(history.points.get(0).type);
    }

    @Test
    public void settingsRoundTripMatchesAppModel() throws Exception {
        HttpResult put = request("PUT", "/settings?device_id=pi-1", "{\"temp_min\":18,\"temp_max\":26}");
        assertEquals(200, put.code);
        SettingsResponse saved = gson.fromJson(put.body, SettingsResponse.class);
        assertTrue(saved.saved);
        assertEquals(18.0, saved.tempMin, 0.01);

        HttpResult get = request("GET", "/settings?device_id=pi-1", null);
        SettingsResponse loaded = gson.fromJson(get.body, SettingsResponse.class);
        assertEquals(26.0, loaded.tempMax, 0.01);
    }

    @Test
    public void authRegisterLoginMatchesAppModel() throws Exception {
        String email = "android_" + System.currentTimeMillis() + "@mail.com";
        String registerJson = "{"
                + "\"name\":\"Ivan\","
                + "\"surname\":\"Vekov\","
                + "\"email\":\"" + email + "\","
                + "\"password\":\"secret12\""
                + "}";
        HttpResult registered = request("POST", "/auth/register", registerJson);
        assertEquals(200, registered.code);
        AuthResponse created = gson.fromJson(registered.body, AuthResponse.class);
        assertEquals(email, created.email);
        assertNotNull(created.token);
        assertEquals("Ivan", created.name);

        HttpResult logged = request("POST", "/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"secret12\"}");
        assertEquals(200, logged.code);
        AuthResponse session = gson.fromJson(logged.body, AuthResponse.class);
        assertNotNull(session.token);
        assertEquals("Vekov", session.surname);
    }

    private static HttpResult request(String method, String path, String jsonBody) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        if (jsonBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }
        }
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = "";
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                body = reader.lines().collect(Collectors.joining());
            }
        }
        connection.disconnect();
        return new HttpResult(code, body);
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
