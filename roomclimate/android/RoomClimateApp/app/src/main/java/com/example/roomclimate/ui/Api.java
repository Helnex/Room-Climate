package com.example.roomclimate.ui;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiErrors;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Короткие обработчики ответов для новых экранов: успех — действие, ошибка — тост с текстом
 * сервера (по-русски) или сетевой ошибкой; 401 — на вход (SessionGuard).
 */
final class Api {
    interface Ok {
        void run(JsonObject body);
    }

    private Api() {}

    static Callback<JsonObject> call(Activity activity, Ok ok) {
        return new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (activity.isFinishing() || SessionGuard.handleUnauthorized(activity, response)) {
                    return;
                }
                if (response.isSuccessful()) {
                    ok.run(response.body() == null ? new JsonObject() : response.body());
                } else {
                    Toast.makeText(activity, ApiErrors.message(response, activity.getString(R.string.request_failed)),
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                }
            }
        };
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
