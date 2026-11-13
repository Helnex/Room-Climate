package com.example.roomclimate.ui;

import android.content.Context;

import java.util.Locale;

/**
 * Температура в выбранных единицах (экран «Единицы измерения»). Сервер и пороги хранят °C,
 * перевод — только при показе: °F = °C × 9/5 + 32.
 */
public final class Units {
    private Units() {}

    public static double toFahrenheit(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }

    /** «23,4 °C» или «74,1 °F»; null → «—». */
    public static String temperature(Context context, Double celsius) {
        if (celsius == null || celsius.isNaN()) {
            return "—";
        }
        boolean f = AppPrefs.fahrenheit(context);
        return format(f ? toFahrenheit(celsius) : celsius) + (f ? " °F" : " °C");
    }

    /** Только символ единицы: «°C» / «°F». */
    public static String symbol(Context context) {
        return AppPrefs.fahrenheit(context) ? "°F" : "°C";
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }
}
