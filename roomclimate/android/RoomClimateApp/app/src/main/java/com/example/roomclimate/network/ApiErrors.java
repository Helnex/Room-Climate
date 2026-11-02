package com.example.roomclimate.network;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Response;

public final class ApiErrors {
    private ApiErrors() {}

    public static String message(Response<?> response, String fallback) {
        ResponseBody errorBody = response.errorBody();
        if (errorBody == null) {
            return fallback + " (" + response.code() + ")";
        }
        try {
            String raw = errorBody.string();
            JSONObject json = new JSONObject(raw);
            if (json.has("detail")) {
                Object detail = json.get("detail");
                if (detail instanceof String) {
                    return (String) detail;
                }
            }
            return raw.isEmpty() ? fallback + " (" + response.code() + ")" : raw;
        } catch (Exception ignored) {
            return fallback + " (" + response.code() + ")";
        }
    }

    public static String network(Throwable t) {
        if (t instanceof IOException) {
            return "Нет связи с сервером: " + t.getMessage();
        }
        return t.getMessage() == null ? "Ошибка сети" : t.getMessage();
    }
}
