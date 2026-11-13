package com.example.roomclimate.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.example.roomclimate.R;
import com.example.roomclimate.models.InsightsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.ui.DashboardActivity;
import com.example.roomclimate.ui.MainActivity;
import com.example.roomclimate.ui.UserPrefs;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Виджет «Климат» на главном экране: температура, влажность и состояние основной платы.
 *
 * Обновляется:
 *  - системой раз в 30 минут (onUpdate);
 *  - по кнопке ⟳ на виджете;
 *  - каждую минуту, пока работает мониторинг (SensorWatch вызывает push()).
 * Последнее состояние хранится в настройках — виджет рисуется сразу и без связи.
 */
public class ClimateWidget extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.example.roomclimate.widget.REFRESH";
    private static final String PREFS = "roomclimate_widget";
    private static final String KEY_INSIGHTS = "insights";
    private static final String KEY_DEVICE = "device";
    private static final Gson GSON = new Gson();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        refresh(context, goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refresh(context, goAsync());
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        // Пользователь растянул виджет — перерисовать в нужной раскладке.
        render(context, manager, id);
    }

    // ---------- Данные ----------

    /** Свежие данные от мониторинга/экрана «Устройства»: сохранить и перерисовать все виджеты. */
    public static void push(Context context, InsightsResponse insights) {
        if (insights == null) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_INSIGHTS, GSON.toJson(insights))
                .putString(KEY_DEVICE, ServerConfig.getDeviceId(context))
                .apply();
        renderAll(context);
    }

    private static void refresh(Context context, PendingResult pending) {
        Context app = context.getApplicationContext();
        if (!UserPrefs.isLoggedIn(app)) {
            renderAll(app);
            pending.finish();
            return;
        }
        Call<InsightsResponse> call = ApiClient.get(app).getInsights(ServerConfig.getDeviceId(app), 24);
        // Приёмнику дают ~10 с; не ждём сеть дольше 8 с.
        call.timeout().timeout(8, TimeUnit.SECONDS);
        call.enqueue(new Callback<InsightsResponse>() {
            @Override
            public void onResponse(@NonNull Call<InsightsResponse> c, @NonNull Response<InsightsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    push(app, response.body());
                } else {
                    renderAll(app);
                }
                pending.finish();
            }

            @Override
            public void onFailure(@NonNull Call<InsightsResponse> c, @NonNull Throwable t) {
                renderAll(app); // остаются последние сохранённые значения
                pending.finish();
            }
        });
    }

    private static InsightsResponse cached(Context context) {
        SharedPreferences p = prefs(context);
        // Данные другой платы (сменили основную) не показываем.
        if (!ServerConfig.getDeviceId(context).equals(p.getString(KEY_DEVICE, ""))) {
            return null;
        }
        try {
            return GSON.fromJson(p.getString(KEY_INSIGHTS, null), InsightsResponse.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** При выходе из аккаунта — стереть данные виджета и показать «Войдите». */
    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
        renderAll(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Отрисовка ----------

    public static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ClimateWidget.class));
        for (int id : ids) {
            render(context, manager, id);
        }
    }

    private static void render(Context context, AppWidgetManager manager, int id) {
        InsightsResponse data = cached(context);
        boolean signedIn = UserPrefs.isLoggedIn(context);
        boolean hasData = data != null && data.temperature != null;
        WidgetStyle.State state = WidgetStyle.stateOf(signedIn, hasData,
                data != null && data.online, data == null || data.comfort == null ? null : data.comfort.code);

        RemoteViews small = build(context, false, state, data);
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: лаунчер сам выбирает раскладку по размеру виджета.
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(110f, 110f), small);
            bySize.put(new SizeF(250f, 110f), build(context, true, state, data));
            views = new RemoteViews(bySize);
        } else {
            Bundle options = manager.getAppWidgetOptions(id);
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
            views = WidgetStyle.isWide(minWidth) ? build(context, true, state, data) : small;
        }
        manager.updateAppWidget(id, views);
    }

    private static RemoteViews build(Context context, boolean wide, WidgetStyle.State state, InsightsResponse d) {
        RemoteViews rv = new RemoteViews(context.getPackageName(),
                wide ? R.layout.widget_medium : R.layout.widget_small);
        rv.setInt(android.R.id.background, "setBackgroundResource", background(state));

        String device = ServerConfig.getDeviceId(context);
        rv.setTextViewText(R.id.wTitle, wide
                ? context.getString(R.string.widget_title, device)
                : context.getString(R.string.widget_title_short));

        String temp = d == null ? "—°" : WidgetStyle.temperature(d.temperature);
        String humidity = d == null ? "—%" : WidgetStyle.humidity(d.humidity);
        rv.setTextViewText(R.id.wTemp, state == WidgetStyle.State.SIGNED_OUT ? "—°" : temp);

        String line;
        switch (state) {
            case SIGNED_OUT:
                line = context.getString(R.string.widget_signed_out);
                break;
            case NO_DATA:
                line = context.getString(R.string.widget_no_data);
                break;
            case SILENT:
                line = context.getString(R.string.widget_silent, WidgetStyle.silentFor(d.ageSeconds));
                break;
            default:
                String label = d.comfort != null && d.comfort.label != null ? d.comfort.label : "";
                line = wide ? label : humidity + "  ·  " + label;
        }
        rv.setTextViewText(R.id.wLine, line);
        rv.setContentDescription(android.R.id.background,
                context.getString(R.string.widget_a11y, device, temp, humidity, line));

        if (!wide) {
            boolean showDrop = state != WidgetStyle.State.SIGNED_OUT && state != WidgetStyle.State.NO_DATA
                    && state != WidgetStyle.State.SILENT;
            rv.setViewVisibility(R.id.wDropIcon, showDrop ? android.view.View.VISIBLE : android.view.View.GONE);
        } else {
            boolean show = d != null && state != WidgetStyle.State.SIGNED_OUT;
            rv.setTextViewText(R.id.wHumidity, show ? context.getString(R.string.widget_humidity, humidity) : "");
            rv.setTextViewText(R.id.wDew, show && d.dewPoint != null
                    ? context.getString(R.string.widget_dew, WidgetStyle.temperature(d.dewPoint)) : "");
            InsightsResponse.Stats t = show && d.stats != null ? d.stats.get("temperature") : null;
            rv.setTextViewText(R.id.wRange, t != null
                    ? context.getString(R.string.widget_range, WidgetStyle.temperature(t.min), WidgetStyle.temperature(t.max))
                    : "");
            rv.setTextViewText(R.id.wUpdated, show ? updatedText(context, d, state) : "");
        }

        rv.setImageViewResource(R.id.wRefresh, icon(state));
        rv.setOnClickPendingIntent(android.R.id.background, openApp(context, state));
        rv.setOnClickPendingIntent(R.id.wRefresh, refreshIntent(context));
        return rv;
    }

    private static String updatedText(Context context, InsightsResponse d, WidgetStyle.State state) {
        String time = localTime(d.lastSeen);
        return state == WidgetStyle.State.SILENT
                ? time
                : context.getString(R.string.widget_online, time);
    }

    /** ISO-время сервера (UTC) -> «14:32» по часам телефона. */
    static String localTime(String iso) {
        if (iso == null) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            Date date = in.parse(iso.replace("Z", "+00:00"));
            return date == null ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        } catch (Exception e) {
            return "";
        }
    }

    private static int background(WidgetStyle.State state) {
        switch (state) {
            case COMFORT:
                return R.drawable.widget_bg_comfort;
            case HOT:
                return R.drawable.widget_bg_hot;
            case COLD:
                return R.drawable.widget_bg_cold;
            case HUMID:
                return R.drawable.widget_bg_humid;
            case DRY:
                return R.drawable.widget_bg_dry;
            default:
                return R.drawable.widget_bg_silent;
        }
    }

    /** Значок в углу: ⟳ (обновить) в норме, предупреждение/снежинка/без сети по состоянию. */
    private static int icon(WidgetStyle.State state) {
        switch (state) {
            case HOT:
            case HUMID:
            case DRY:
                return R.drawable.ic_widget_warning;
            case COLD:
                return R.drawable.ic_widget_cold;
            case SILENT:
                return R.drawable.ic_widget_offline;
            default:
                return R.drawable.ic_widget_refresh;
        }
    }

    private static PendingIntent openApp(Context context, WidgetStyle.State state) {
        Class<?> target = state == WidgetStyle.State.SIGNED_OUT ? MainActivity.class : DashboardActivity.class;
        Intent intent = new Intent(context, target).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 7001, intent, flags());
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, ClimateWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 7002, intent, flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
