package com.example.roomclimate.network;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Базовый URL и device_id хранятся в настройках приложения.
 * Эмулятор на том же ПК, что Docker: http://10.0.2.2:8000
 * Телефон в той же Wi‑Fi: http://IP_компьютера:8000
 */
public final class ServerConfig {
    public static final String PREFS = "roomclimate";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String DEFAULT_BASE_URL = "http://10.0.2.2:8000";
    public static final String DEFAULT_DEVICE_ID = "pi-1";

    private ServerConfig() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(Context context) {
        return prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public static String getDeviceId(Context context) {
        return prefs(context).getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID);
    }

    public static void save(Context context, String baseUrl, String deviceId) {
        prefs(context).edit()
                .putString(KEY_BASE_URL, normalizeBaseUrl(baseUrl))
                .putString(KEY_DEVICE_ID, deviceId.trim())
                .apply();
        ApiClient.reset();
    }

    public static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
