package com.example.roomclimate.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ServerConfig;

import java.util.Locale;

public final class ThresholdNotifier {
    public static final String CHANNEL_ID = "climate_alerts";
    private static final int NOTIFICATION_ID = 1001;
    private static final String KEY_LAST_STATUS = "last_threshold_status";

    private ThresholdNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.notification_channel_desc));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    public static void notifyIfNeeded(Context context, double temperature, double min, double max) {
        ThresholdChecker.Status status = ThresholdChecker.check(temperature, min, max);
        String previous = ServerConfig.prefs(context).getString(KEY_LAST_STATUS, "");
        ServerConfig.prefs(context).edit().putString(KEY_LAST_STATUS, status.name()).apply();

        if (!ThresholdChecker.isAlarm(status) || status.name().equals(previous)) {
            return;
        }

        ensureChannel(context);
        String title = context.getString(R.string.notification_title);
        String body = status == ThresholdChecker.Status.ABOVE
                ? context.getString(R.string.notification_above, format(temperature), format(max))
                : context.getString(R.string.notification_below, format(temperature), format(min));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
            // Нет разрешения POST_NOTIFICATIONS — дашборд всё равно покажет статус.
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
