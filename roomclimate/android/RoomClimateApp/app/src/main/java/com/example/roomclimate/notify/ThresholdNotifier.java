package com.example.roomclimate.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.ui.DashboardActivity;
import com.example.roomclimate.ui.Formatters;

public final class ThresholdNotifier {
    public static final String CHANNEL_ID = "climate_alerts";
    public static final String MONITOR_CHANNEL_ID = "climate_monitor";

    private ThresholdNotifier() {}

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription(context.getString(R.string.notification_channel_desc));
        manager.createNotificationChannel(alerts);

        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL_ID,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        monitor.setDescription(context.getString(R.string.monitor_channel_desc));
        manager.createNotificationChannel(monitor);
    }

    public static void notifyIfNeeded(
            Context context,
            String metricKey,
            String label,
            double value,
            double min,
            double max,
            String unit,
            String measuredAt
    ) {
        // Каждое новое измерение за порогом — отдельное уведомление.
        // Повтор того же замера (опрос чаще, чем плата) не дублируется.
        ThresholdChecker.Status status = ThresholdChecker.check(value, min, max);
        String stampKey = "alert_stamp_" + metricKey;
        String previousStamp = ServerConfig.prefs(context).getString(stampKey, "");
        String stamp = measuredAt == null ? "" : measuredAt;
        if (!ThresholdChecker.isAlarm(status)) {
            ServerConfig.prefs(context).edit().remove(stampKey).apply();
            return;
        }
        if (stamp.isEmpty() || stamp.equals(previousStamp)) {
            return;
        }
        if (!com.example.roomclimate.ui.AppPrefs.notify(context, metricKey)) {
            return;
        }
        ServerConfig.prefs(context).edit().putString(stampKey, stamp).apply();

        ensureChannels(context);
        String shownUnit = Formatters.unit(unit);
        if ("temperature".equals(metricKey) && "°C".equals(shownUnit)) {
            // Как на дашборде: в единицах из «Единицы измерения» (проверка порогов — в °C выше).
            value = com.example.roomclimate.ui.Units.shown(context, value);
            min = com.example.roomclimate.ui.Units.shown(context, min);
            max = com.example.roomclimate.ui.Units.shown(context, max);
            shownUnit = com.example.roomclimate.ui.Units.symbol(context);
        }
        String body = status == ThresholdChecker.Status.ABOVE
                ? context.getString(
                R.string.notification_above,
                label,
                format(value),
                shownUnit,
                format(max)
        )
                : context.getString(
                R.string.notification_below,
                label,
                format(value),
                shownUnit,
                format(min)
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notification_title, label))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context, metricKey.hashCode()));

        try {
            NotificationManagerCompat.from(context).notify(notificationId(metricKey, stamp), builder.build());
        } catch (SecurityException ignored) {
            // Нет разрешения POST_NOTIFICATIONS — дашборд всё равно покажет статус.
        }
    }

    public static PendingIntent openAppIntent(Context context, int requestCode) {
        Intent open = new Intent(context, DashboardActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCode, open, flags);
    }

    private static int notificationId(String metricKey, String stamp) {
        return 1000 + Math.abs((metricKey + stamp).hashCode() % 10000);
    }

    private static String format(double value) {
        return Formatters.number(value);
    }
}
