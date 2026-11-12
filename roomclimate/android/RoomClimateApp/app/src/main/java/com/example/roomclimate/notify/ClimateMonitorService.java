package com.example.roomclimate.notify;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.CurrentTelemetryResponse;
import com.example.roomclimate.models.Reading;
import com.example.roomclimate.models.SettingsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.AppPermissions;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.ui.UserPrefs;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Пока пользователь в аккаунте, раз в 15 секунд читает текущие показания
 * и показывает уведомление, если температура или влажность вышли за порог.
 * Постоянное тихое уведомление нужно Android, чтобы проверка шла в фоне.
 */
public class ClimateMonitorService extends Service {
    private static final int MONITOR_ID = 42;
    private static final long POLL_MS = 15_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::poll;
    private boolean polling;

    public static void start(Context context) {
        if (!UserPrefs.isLoggedIn(context) || !UserPrefs.alertsEnabled(context)) {
            return;
        }
        Intent intent = new Intent(context, ClimateMonitorService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ClimateMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ThresholdNotifier.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification ongoing = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        MONITOR_ID,
                        ongoing,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(MONITOR_ID, ongoing);
            }
        } catch (RuntimeException ex) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        polling = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        if (!UserPrefs.isLoggedIn(this) || !UserPrefs.alertsEnabled(this)) {
            stopSelf();
            return;
        }
        if (polling) {
            return;
        }
        if (!AppPermissions.hasLocalNetwork(this)) {
            schedule();
            return;
        }
        polling = true;
        String deviceId = ServerConfig.getDeviceId(this);
        ApiClient.get(this).getSettings(deviceId).enqueue(new Callback<SettingsResponse>() {
            @Override
            public void onResponse(@NonNull Call<SettingsResponse> call,
                                   @NonNull Response<SettingsResponse> response) {
                if (response.code() == 401) {
                    // Сессия истекла: ApiClient уже сбросил токен, фоновую проверку прекращаем.
                    stopSelf();
                    return;
                }
                SettingsResponse settings = response.isSuccessful() ? response.body() : null;
                if (settings == null) {
                    finishPoll();
                    return;
                }
                loadCurrent(deviceId, settings);
            }

            @Override
            public void onFailure(@NonNull Call<SettingsResponse> call, @NonNull Throwable t) {
                finishPoll();
            }
        });
    }

    private void loadCurrent(String deviceId, SettingsResponse settings) {
        ApiClient.get(this).getCurrent(deviceId).enqueue(new Callback<CurrentTelemetryResponse>() {
            @Override
            public void onResponse(@NonNull Call<CurrentTelemetryResponse> call,
                                   @NonNull Response<CurrentTelemetryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    check(response.body(), settings);
                }
                SensorWatch.check(ClimateMonitorService.this);
                finishPoll();
            }

            @Override
            public void onFailure(@NonNull Call<CurrentTelemetryResponse> call, @NonNull Throwable t) {
                finishPoll();
            }
        });
    }

    private void check(CurrentTelemetryResponse body, SettingsResponse settings) {
        Reading temperature = body.reading("temperature");
        if (temperature != null) {
            ThresholdNotifier.notifyIfNeeded(
                    this,
                    "temperature",
                    getString(R.string.label_temperature),
                    temperature.value,
                    settings.tempMin,
                    settings.tempMax,
                    temperature.unit == null ? "°C" : temperature.unit
            );
        }
        Reading humidity = body.reading("humidity");
        if (humidity != null) {
            ThresholdNotifier.notifyIfNeeded(
                    this,
                    "humidity",
                    getString(R.string.label_humidity),
                    humidity.value,
                    settings.humidityMinOrDefault(),
                    settings.humidityMaxOrDefault(),
                    humidity.unit == null ? "%" : humidity.unit
            );
        }
    }

    private void finishPoll() {
        polling = false;
        schedule();
    }

    private void schedule() {
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, POLL_MS);
    }

    private Notification buildOngoing() {
        return new NotificationCompat.Builder(this, ThresholdNotifier.MONITOR_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.monitor_title))
                .setContentText(getString(R.string.monitor_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(ThresholdNotifier.openAppIntent(this, 1))
                .build();
    }
}
