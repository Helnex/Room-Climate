package com.example.roomclimate.ui;

import android.content.Context;

import com.example.roomclimate.R;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Платы и датчики из базы сервера (GET /registry/boards, /registry/sensors): разбор ответа
 * и подписи для экранов. Виды датчиков, показатели и единицы приходят с сервера — новый
 * датчик, заведённый миграцией, показывается без изменений в приложении.
 */
final class Registry {
    private Registry() {}

    static JsonArray array(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    static Double number(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : null;
    }

    static int roomId(JsonObject sensorOrBoard) {
        JsonObject room = obj(sensorOrBoard, "room");
        return room == null || !room.has("id") ? -1 : room.get("id").getAsInt();
    }

    static List<JsonObject> list(JsonArray items) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : items) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    static int icon(String sensorType) {
        switch (sensorType) {
            case "light":
                return R.drawable.ic_light;
            case "co2":
                return R.drawable.ic_co2;
            case "motion":
                return R.drawable.ic_motion;
            default:
                return R.drawable.ic_wifi;
        }
    }

    /** «Освещённость, лк»; у датчика с несколькими показателями — «Температура и влажность, °C / %». */
    static String metricLabel(Context context, JsonObject sensor) {
        JsonArray metrics = array(sensor, "metrics");
        if (metrics.size() == 1) {
            JsonObject m = metrics.get(0).getAsJsonObject();
            return context.getString(R.string.metric_with_unit, Api.str(m, "name"), unit(context, m));
        }
        StringBuilder units = new StringBuilder();
        for (JsonElement e : metrics) {
            if (units.length() > 0) {
                units.append(" / ");
            }
            units.append(unit(context, e.getAsJsonObject()));
        }
        return units.length() == 0 ? Api.str(sensor, "type_name")
                : context.getString(R.string.metric_with_unit, Api.str(sensor, "type_name"), units);
    }

    /** Единица с учётом выбранных °C / °F. */
    private static String unit(Context context, JsonObject metric) {
        return "temperature".equals(Api.str(metric, "code")) ? Units.symbol(context) : Api.str(metric, "unit");
    }

    /** Текущие значения датчика: «23,4 °C · 48 %», «320 лк». Пусто, если показаний нет. */
    static String values(Context context, JsonObject sensor) {
        StringBuilder out = new StringBuilder();
        for (JsonElement e : array(sensor, "metrics")) {
            JsonObject m = e.getAsJsonObject();
            Double v = number(m, "value");
            if (v == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(value(context, Api.str(m, "code"), Api.str(m, "unit"), v));
        }
        return out.toString();
    }

    static String value(Context context, String code, String unit, double v) {
        switch (code) {
            case "temperature":
                return Units.temperature(context, v);
            case "humidity":
                return Math.round(v) + " %";
            case Metrics.CO2:
            case Metrics.LIGHT:
            case Metrics.MOTION:
                return Metrics.value(context, code, v);
            default:
                return String.format(Locale.getDefault(), "%.1f %s", v, unit).trim();
        }
    }

    static String boardTitle(JsonObject board) {
        return board == null ? "" : nb(Api.str(board, "title"));
    }

    /** «Плата № 0002» без переноса строки посередине. */
    static String nb(String text) {
        return text == null ? "" : text.replace("№ ", "№\u00A0").replace("Плата ", "Плата\u00A0");
    }
}
