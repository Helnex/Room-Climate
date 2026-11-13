package com.example.roomclimate.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки из макета, которые хранятся на телефоне: о чём уведомлять (экран «Уведомления»)
 * и в каких единицах показывать температуру (экран «Единицы измерения»).
 * Общий выключатель уведомлений — прежний UserPrefs.alertsEnabled (он же в «Аккаунте»).
 */
public final class AppPrefs {
    public static final String TEMPERATURE = "temperature";
    public static final String HUMIDITY = "humidity";
    public static final String SENSOR_LOST = "sensor_lost";
    public static final String SENSOR_BACK = "sensor_back";
    private static final String PREFS = "roomclimate_app";
    private static final String KEY_FAHRENHEIT = "units_fahrenheit";

    private AppPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Уведомлять ли о событии (temperature / humidity / sensor_lost / sensor_back). */
    public static boolean notify(Context context, String event) {
        return prefs(context).getBoolean("notify_" + event, true);
    }

    public static void setNotify(Context context, String event, boolean on) {
        prefs(context).edit().putBoolean("notify_" + event, on).apply();
    }

    public static boolean fahrenheit(Context context) {
        return prefs(context).getBoolean(KEY_FAHRENHEIT, false);
    }

    public static void setFahrenheit(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_FAHRENHEIT, on).apply();
    }
}
