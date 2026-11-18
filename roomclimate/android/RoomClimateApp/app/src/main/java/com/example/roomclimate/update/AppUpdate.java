package com.example.roomclimate.update;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.pm.PackageInfoCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.ui.UpdateActivity;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Обновление приложения со своего сервера (без Google Play).
 *
 * Сервер отдаёт GET /app/latest — номер свежей версии; если он больше номера установленной,
 * приходит уведомление «Доступна новая версия» → UpdateActivity скачивает APK и открывает
 * системное окно установки (молча ставить приложения Android не даёт, одно нажатие нужно).
 * Проверка — при открытии дашборда и из фоновой службы не чаще раза в CHECK_EVERY_MS,
 * плюс вручную: «Настройки» → «Обновление приложения».
 */
public final class AppUpdate {
    public static final String CHANNEL_ID = "app_updates";
    public static final String EXTRA_LATEST = "latest_json";
    private static final int NOTIFICATION_ID = 4201;
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final String PREFS = "app_update";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_NOTIFIED = "notified_code";

    public interface Result {
        /** latest — описание более новой версии или null (новее нет / не опубликовано); error — текст ошибки сети. */
        void done(@Nullable JsonObject latest, @Nullable String error);
    }

    private AppUpdate() {}

    public static boolean isNewer(long latest, long current) {
        return latest > current;
    }

    public static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return PackageInfoCompat.getLongVersionCode(info);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    public static void check(Context context, Result result) {
        Context app = context.getApplicationContext();
        ApiClient.get(app).getLatestApp().enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                JsonObject body = response.body();
                if (!response.isSuccessful() || body == null || !body.has("version_code")) {
                    // 404 — новых версий ещё не публиковали: это не ошибка.
                    result.done(null, response.code() == 404 ? null
                            : ApiErrors.message(response, app.getString(R.string.request_failed)));
                    return;
                }
                long latest = body.get("version_code").getAsLong();
                result.done(isNewer(latest, currentVersionCode(app)) ? body : null, null);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                result.done(null, ApiErrors.network(t));
            }
        });
    }

    /** Фоновая проверка: не чаще раза в 6 часов, об одной версии напоминаем один раз. */
    public static void checkIfDue(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        check(context, (latest, error) -> {
            if (latest == null) {
                return;
            }
            long code = latest.get("version_code").getAsLong();
            if (prefs.getLong(KEY_NOTIFIED, 0) == code) {
                return;
            }
            if (notifyAvailable(context, latest)) {
                prefs.edit().putLong(KEY_NOTIFIED, code).apply();
            }
        });
    }

    private static boolean notifyAvailable(Context context, JsonObject latest) {
        Context app = context.getApplicationContext();
        NotificationManagerCompat manager = NotificationManagerCompat.from(app);
        if (!manager.areNotificationsEnabled()) {
            return false;  // напомним позже — уведомления выключены
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    app.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(app.getString(R.string.upd_channel_desc));
            app.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Intent open = new Intent(app, UpdateActivity.class)
                .putExtra(EXTRA_LATEST, latest.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(app, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String version = latest.has("version_name") ? latest.get("version_name").getAsString() : "";
        String changes = latest.has("changes") ? latest.get("changes").getAsString() : "";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_refresh)
                .setContentTitle(app.getString(R.string.upd_notification_title, version))
                .setContentText(changes.isEmpty() ? app.getString(R.string.upd_notification_text) : changes)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(changes.isEmpty() ? app.getString(R.string.upd_notification_text) : changes))
                .setContentIntent(pending)
                .addAction(R.drawable.ic_widget_refresh, app.getString(R.string.upd_button), pending)
                .setAutoCancel(true);
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (SecurityException e) {  // нет разрешения на уведомления (Android 13+)
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
