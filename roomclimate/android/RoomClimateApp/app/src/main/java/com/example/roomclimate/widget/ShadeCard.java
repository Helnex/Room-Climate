package com.example.roomclimate.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Typeface;
import android.service.notification.StatusBarNotification;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.InsightsResponse;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.notify.ThresholdNotifier;

/**
 * Карточка климата в шторке уведомлений.
 *
 * Это постоянное уведомление фонового мониторинга (без него Android не даёт проверять
 * пороги в фоне): вместо текста «мониторинг включён» в нём живой климат. Свёрнутая —
 * температура, влажность и состояние в строку; развёрнутая — плитки и совет.
 * Цвет карточки — по состоянию, как у виджета (colorized разрешён уведомлениям
 * foreground-сервиса). Данные те же, что у виджета (ClimateWidget.cached), обновляются
 * каждую минуту вместе с ним.
 */
public final class ShadeCard {
    /** Тот же id, что у уведомления ClimateMonitorService — карточка и есть это уведомление. */
    public static final int NOTIFICATION_ID = 42;

    private ShadeCard() {}

    public static Notification build(Context context) {
        InsightsResponse d = ClimateWidget.cached(context);
        WidgetStyle.State state = ClimateWidget.stateOf(context, d);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, ThresholdNotifier.MONITOR_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(ClimateWidget.openApp(context, state));

        boolean hasClimate = d != null && state != WidgetStyle.State.SIGNED_OUT && state != WidgetStyle.State.NO_DATA;
        if (!hasClimate) {
            // Данных ещё нет — прежний вид «мониторинг включён».
            return b.setContentTitle(context.getString(R.string.monitor_title))
                    .setContentText(context.getString(R.string.monitor_text))
                    .build();
        }

        String temp = WidgetStyle.temperature(d.temperature);
        String humidity = WidgetStyle.humidity(d.humidity);
        String line = ClimateWidget.statusLine(context, state, d, humidity, true);
        String time = ClimateWidget.localTime(d.lastSeen);
        int color = WidgetStyle.colorOf(state);
        int bg = backgroundOf(state);

        RemoteViews small = new RemoteViews(context.getPackageName(), R.layout.notif_climate_small);
        small.setInt(R.id.nRoot, "setBackgroundResource", bg);
        small.setTextViewText(R.id.nTemp, temp);
        small.setTextViewText(R.id.nLine, line);
        small.setTextViewText(R.id.nTime, time);

        RemoteViews big = new RemoteViews(context.getPackageName(), R.layout.notif_climate_big);
        big.setInt(R.id.nRoot, "setBackgroundResource", bg);
        big.setTextViewText(R.id.nTemp, temp);
        big.setTextViewText(R.id.nLine, ClimateWidget.statusLine(context, state, d, humidity, false));
        big.setTextViewText(R.id.nTime, time);
        big.setTextViewText(R.id.nTileHumidity, tile(context.getString(R.string.widget_tile_humidity), humidity));
        big.setTextViewText(R.id.nTileDew, tile(context.getString(R.string.widget_tile_dew),
                d.dewPoint != null ? WidgetStyle.temperature(d.dewPoint) : "—°"));
        InsightsResponse.Stats t = d.stats != null ? d.stats.get("temperature") : null;
        big.setTextViewText(R.id.nTileRange, tile(context.getString(R.string.widget_tile_range),
                t != null ? WidgetStyle.temperature(t.min) + "…" + WidgetStyle.temperature(t.max) : "—"));
        big.setTextViewText(R.id.nAdvice, d.advice != null && !d.advice.isEmpty() ? d.advice.get(0) : "");

        return b.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setColor(color)
                .setColorized(true)
                // Текст для экранного диктора, умных часов и лаунчеров без поддержки своих видов.
                .setContentTitle(temp + " · " + line)
                .setContentText(context.getString(R.string.widget_title, ServerConfig.getDeviceId(context)))
                .build();
    }

    /** Перерисовать карточку, если мониторинг сейчас работает (уведомление показано). */
    public static void refresh(Context context) {
        if (!isShown(context)) {
            return;
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context));
        } catch (SecurityException ignored) {
            // нет разрешения на уведомления — карточки нет
        }
    }

    private static boolean isShown(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return false;
        }
        for (StatusBarNotification n : nm.getActiveNotifications()) {
            if (n.getId() == NOTIFICATION_ID) {
                return true;
            }
        }
        return false;
    }

    private static CharSequence tile(String label, String value) {
        SpannableStringBuilder sb = new SpannableStringBuilder(label).append('\n');
        int start = sb.length();
        sb.append(value);
        sb.setSpan(new RelativeSizeSpan(1.3f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static int backgroundOf(WidgetStyle.State state) {
        switch (state) {
            case COMFORT:
                return R.drawable.notif_bg_comfort;
            case HOT:
                return R.drawable.notif_bg_hot;
            case COLD:
                return R.drawable.notif_bg_cold;
            case HUMID:
                return R.drawable.notif_bg_humid;
            case DRY:
                return R.drawable.notif_bg_dry;
            default:
                return R.drawable.notif_bg_silent;
        }
    }
}
