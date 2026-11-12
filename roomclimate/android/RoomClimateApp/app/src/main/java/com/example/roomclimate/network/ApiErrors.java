package com.example.roomclimate.network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import okhttp3.ResponseBody;
import retrofit2.Response;

public final class ApiErrors {
    private ApiErrors() {}

    public static String message(Response<?> response, String fallback) {
        ResponseBody errorBody = response.errorBody();
        String generic = fallback + " (" + response.code() + ")";
        if (errorBody == null) {
            return generic;
        }
        try {
            String raw = errorBody.string();
            if (raw.isEmpty()) {
                return generic;
            }
            JSONObject json = new JSONObject(raw);
            Object detail = json.opt("detail");
            if (detail instanceof String && !((String) detail).isEmpty()) {
                return (String) detail;
            }
            if (detail instanceof JSONArray) {
                // Старый формат FastAPI: список ошибок валидации. Сырой JSON пользователю не показываем.
                JSONArray list = (JSONArray) detail;
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    String msg = item == null ? null : item.optString("msg", null);
                    if (msg != null && !msg.isEmpty()) {
                        if (out.length() > 0) {
                            out.append("; ");
                        }
                        out.append(msg);
                    }
                }
                if (out.length() > 0) {
                    return fallback + ": " + out;
                }
            }
            return generic;
        } catch (Exception ignored) {
            return generic;
        }
    }

    /** Текст сетевой ошибки по-русски (исключения OkHttp приходят с английскими сообщениями). */
    public static String network(Throwable t) {
        if (t instanceof SocketTimeoutException) {
            return "Сервер не отвечает. Проверьте, что он запущен и телефон в той же Wi‑Fi";
        }
        if (t instanceof UnknownHostException) {
            return "Не удалось найти сервер по указанному адресу";
        }
        if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
            return "Не удалось подключиться к серверу. Проверьте адрес и что сервер запущен";
        }
        if (t instanceof IOException) {
            return "Нет связи с сервером";
        }
        return "Ошибка сети";
    }
}
