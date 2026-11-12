package com.example.roomclimate.ui;

import android.content.Context;

import com.example.roomclimate.R;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Вывод значений для русского интерфейса: «24,1 °C» вместо «24.1 C»,
 * местное время «12.11.2026 10:02:43» вместо «2026-11-12T05:02:43+00:00»,
 * «Температура» вместо «temperature».
 */
public final class Formatters {
    private static final String SERVER_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";
    private static final String DISPLAY_PATTERN = "dd.MM.yyyy HH:mm:ss";

    private Formatters() {}

    public static String unit(String raw) {
        if (raw == null) {
            return "";
        }
        String unit = raw.trim();
        if (unit.equalsIgnoreCase("C") || unit.equalsIgnoreCase("°C") || unit.equalsIgnoreCase("celsius")) {
            return "°C";
        }
        return unit;
    }

    public static String number(double value) {
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    public static String sensor(Context context, String type) {
        if ("temperature".equals(type)) {
            return context.getString(R.string.label_temperature);
        }
        if ("humidity".equals(type)) {
            return context.getString(R.string.label_humidity);
        }
        return type == null ? "" : type;
    }

    /** ISO-время сервера (UTC) → местное время телефона. При ошибке разбора возвращает исходную строку. */
    public static String time(String iso) {
        return time(iso, TimeZone.getDefault());
    }

    static String time(String iso, TimeZone zone) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat(SERVER_PATTERN, Locale.US);
            Date date = in.parse(iso);
            if (date == null) {
                return iso;
            }
            SimpleDateFormat out = new SimpleDateFormat(DISPLAY_PATTERN, Locale.getDefault());
            out.setTimeZone(zone);
            return out.format(date);
        } catch (ParseException | IllegalArgumentException e) {
            return iso;
        }
    }

    public static String initials(String name, String surname) {
        StringBuilder out = new StringBuilder();
        appendFirst(out, name);
        appendFirst(out, surname);
        return out.toString();
    }

    private static void appendFirst(StringBuilder out, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int end = trimmed.offsetByCodePoints(0, 1);
        out.append(trimmed.substring(0, end).toUpperCase(Locale.getDefault()));
    }
}
