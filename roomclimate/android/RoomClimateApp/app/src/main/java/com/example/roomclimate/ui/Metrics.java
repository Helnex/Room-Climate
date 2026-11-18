package com.example.roomclimate.ui;

import android.content.Context;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ServerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Дополнительные показатели платы — углекислый газ, освещённость, движение (сборщик шлёт их
 * при EXTRA_SENSORS) — и какие из них пользователь вынес на дашборд («Добавить метрику»).
 * Температура и влажность основной платы на дашборде всегда, их здесь нет.
 *
 * Метрика на дашборде — пара «плата|показатель»: CO₂ может стоять на другой плате.
 */
public final class Metrics {
    public static final String CO2 = "co2";
    public static final String LIGHT = "light";
    public static final String MOTION = "motion";
    /** Показатели, которые можно добавить на дашборд, в порядке показа. */
    public static final List<String> EXTRA = Arrays.asList(CO2, LIGHT, MOTION);

    private static final String KEY = "dashboard_metrics";

    private Metrics() {}

    public static boolean isExtra(String type) {
        return EXTRA.contains(type);
    }

    public static String label(Context context, String type) {
        switch (type) {
            case CO2:
                return context.getString(R.string.metric_co2);
            case LIGHT:
                return context.getString(R.string.metric_light);
            case MOTION:
                return context.getString(R.string.metric_motion);
            default:
                return Formatters.sensor(context, type);
        }
    }

    /** Подпись единицы для статистики: «ppm», «лк»; у движения единицы нет. */
    public static String unit(String type) {
        switch (type) {
            case CO2:
                return "ppm";
            case LIGHT:
                return "лк";
            default:
                return "";
        }
    }

    public static int icon(String type) {
        switch (type) {
            case CO2:
                return R.drawable.ic_co2;
            case LIGHT:
                return R.drawable.ic_light;
            case MOTION:
                return R.drawable.ic_motion;
            default:
                return R.drawable.ic_nav_sensor;
        }
    }

    /** «640 ppm», «320 лк», «Есть движение». */
    public static String value(Context context, String type, Double value) {
        if (value == null || value.isNaN()) {
            return context.getString(R.string.value_placeholder);
        }
        switch (type) {
            case CO2:
                return context.getString(R.string.metric_co2_value, Math.round(value));
            case LIGHT:
                return context.getString(R.string.metric_light_value, Math.round(value));
            case MOTION:
                return context.getString(value >= 0.5 ? R.string.metric_motion_yes : R.string.metric_motion_no);
            default:
                return String.format(Locale.getDefault(), "%.1f", value);
        }
    }

    // ---------- Что вынесено на дашборд ----------

    public static String entry(String deviceId, String type) {
        return deviceId + "|" + type;
    }

    public static String deviceOf(String entry) {
        int bar = entry.indexOf('|');
        return bar < 0 ? "" : entry.substring(0, bar);
    }

    public static String typeOf(String entry) {
        int bar = entry.indexOf('|');
        return bar < 0 ? entry : entry.substring(bar + 1);
    }

    public static List<String> onDashboard(Context context) {
        String raw = ServerConfig.prefs(context).getString(KEY, "");
        List<String> list = new ArrayList<>();
        for (String item : raw.split("\n")) {
            if (!item.isEmpty() && isExtra(typeOf(item)) && !deviceOf(item).isEmpty()) {
                list.add(item);
            }
        }
        return list;
    }

    public static boolean isOnDashboard(Context context, String deviceId, String type) {
        return onDashboard(context).contains(entry(deviceId, type));
    }

    public static void add(Context context, String deviceId, String type) {
        Set<String> set = new LinkedHashSet<>(onDashboard(context));
        set.add(entry(deviceId, type));
        save(context, set);
    }

    public static void remove(Context context, String entry) {
        Set<String> set = new LinkedHashSet<>(onDashboard(context));
        set.remove(entry);
        save(context, set);
    }

    /** Убрать карточки платы по этим показателям (датчик отвязали от платы). */
    public static void removeAll(Context context, String deviceId, java.util.Collection<String> types) {
        Set<String> set = new LinkedHashSet<>(onDashboard(context));
        for (String type : types) {
            set.remove(entry(deviceId, type));
        }
        save(context, set);
    }

    /** Другой аккаунт — свои карточки на дашборде, чужие не показываем. */
    public static void clear(Context context) {
        ServerConfig.prefs(context).edit().remove(KEY).apply();
    }

    private static void save(Context context, Set<String> set) {
        ServerConfig.prefs(context).edit().putString(KEY, android.text.TextUtils.join("\n", set)).apply();
    }
}
