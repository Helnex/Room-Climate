package com.example.roomclimate.notify;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.InsightsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Уведомление «Датчик молчит»: плата перестала присылать показания (нет питания, Wi‑Fi,
 * сбой датчика). Для склада это важнее выхода за порог — без данных порог не сработает.
 * Возраст показаний считает сервер (/telemetry/insights), поэтому часы телефона не влияют.
 * Уведомляем один раз при пропадании и один раз при возвращении.
 */
public final class SensorWatch {
    private static final long CHECK_EVERY_MS = 60_000L;
    private static final String KEY_SILENT = "sensor_silent_";
    private static long lastCheck;

    private SensorWatch() {}

    public static synchronized void check(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_EVERY_MS) {
            return;
        }
        lastCheck = now;
        Context app = context.getApplicationContext();
        String deviceId = ServerConfig.getDeviceId(app);
        // 24 ч — тот же ответ подходит и виджету (диапазон за сутки).
        ApiClient.get(app).getInsights(deviceId, 24).enqueue(new Callback<InsightsResponse>() {
            @Override
            public void onResponse(@NonNull Call<InsightsResponse> call,
                                   @NonNull Response<InsightsResponse> response) {
                InsightsResponse body = response.isSuccessful() ? response.body() : null;
                if (body != null && body.ageSeconds != null) {
                    update(app, deviceId, body.online, body.ageSeconds);
                }
                if (body != null) {
                    com.example.roomclimate.widget.ClimateWidget.push(app, body);
                }
            }

            @Override
            public void onFailure(@NonNull Call<InsightsResponse> call, @NonNull Throwable t) {
                // Нет связи с сервером — это не «молчит датчик», ничего не делаем.
            }
        });
    }

    private static void update(Context context, String deviceId, boolean online, int ageSeconds) {
        String key = KEY_SILENT + deviceId;
        boolean wasSilent = ServerConfig.prefs(context).getBoolean(key, false);
        if (online == !wasSilent) {
            return;
        }
        ServerConfig.prefs(context).edit().putBoolean(key, !online).apply();
        String text = online
                ? context.getString(R.string.sensor_back_text, deviceId)
                : context.getString(R.string.sensor_silent_text, deviceId, minutes(context, ageSeconds));
        ThresholdNotifier.ensureChannels(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ThresholdNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(online ? context.getString(R.string.app_name)
                        : context.getString(R.string.sensor_silent_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(online ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(ThresholdNotifier.openAppIntent(context, 2000));
        try {
            NotificationManagerCompat.from(context).notify(2000 + Math.abs(deviceId.hashCode() % 1000), builder.build());
        } catch (SecurityException ignored) {
            // Нет разрешения на уведомления — статус виден на экране «Устройства».
        }
    }

    private static String minutes(Context context, int seconds) {
        if (seconds < 3600) {
            return context.getString(R.string.duration_minutes, Math.max(1, seconds / 60));
        }
        return context.getString(R.string.duration_hours, seconds / 3600);
    }
}
