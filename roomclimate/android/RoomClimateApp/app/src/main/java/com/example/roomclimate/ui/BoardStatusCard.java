package com.example.roomclimate.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Карточка «Raspberry Pi» на экране «Устройства»: жива ли плата и что с ней — по «пульсу»,
 * который сборщик шлёт раз в минуту (GET /devices/{id}/status). Плата ещё ни разу не
 * присылала состояние (старый сборщик) — карточка скрыта.
 */
public final class BoardStatusCard {
    /** Пульс раз в 60 с: дольше 3 минут тишины — плата, скорее всего, выключена или без сети. */
    private static final int OFFLINE_AFTER_SEC = 180;

    private BoardStatusCard() {}

    public static void load(Activity activity, LinearLayout card) {
        if (card == null) {
            return;
        }
        String deviceId = ServerConfig.getDeviceId(activity);
        ApiClient.get(activity).getBoardStatus(deviceId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (activity.isFinishing() || !response.isSuccessful() || response.body() == null) {
                    card.setVisibility(View.GONE);
                    return;
                }
                show(activity, card, deviceId, response.body());
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                card.setVisibility(View.GONE);
            }
        });
    }

    static void show(Activity activity, LinearLayout card, String deviceId, JsonObject s) {
        card.removeAllViews();
        card.setVisibility(View.VISIBLE);
        TextView title = new TextView(activity);
        title.setTextAppearance(R.style.RC_Text_Section);
        title.setText(activity.getString(R.string.board_title, deviceId));
        card.addView(title);

        int age = num(s, "age_seconds") == null ? Integer.MAX_VALUE : num(s, "age_seconds").intValue();
        boolean online = age <= OFFLINE_AFTER_SEC;
        Rows.add(card, R.drawable.ic_check_circle,
                activity.getString(online ? R.string.board_online : R.string.board_offline),
                activity.getString(R.string.board_seen, agoText(activity, age)), null);

        Double cpu = num(s, "cpu_temp");
        if (cpu != null) {
            Rows.add(card, R.drawable.ic_warning_outline, activity.getString(R.string.board_cpu),
                    activity.getString(cpu >= 70 ? R.string.board_cpu_hot : R.string.board_cpu_value,
                            String.format(Locale.getDefault(), "%.1f", cpu)), null);
        }
        Double signal = num(s, "wifi_signal");
        String ssid = Api.str(s, "wifi_ssid");
        if (signal != null || !ssid.isEmpty()) {
            String quality = signal == null ? "" : activity.getString(signal >= -60 ? R.string.board_wifi_good
                    : signal >= -70 ? R.string.board_wifi_ok : R.string.board_wifi_bad, Math.round(signal));
            String sub = ssid.isEmpty() ? quality : quality.isEmpty() ? ssid : ssid + " · " + quality;
            String ip = Api.str(s, "ip");
            Rows.add(card, R.drawable.ic_wifi, activity.getString(R.string.board_wifi),
                    ip.isEmpty() ? sub : sub + " · " + ip, null);
        }
        Double uptime = num(s, "uptime_sec");
        if (uptime != null) {
            Rows.add(card, R.drawable.ic_calendar, activity.getString(R.string.board_uptime),
                    duration(activity, uptime.longValue()), null);
        }
        Double buffered = num(s, "buffered");
        if (buffered != null && buffered > 0) {
            Rows.add(card, R.drawable.ic_widget_offline, activity.getString(R.string.board_buffer),
                    activity.getString(R.string.board_buffer_value, buffered.intValue()), null);
        }
        StringBuilder sensors = new StringBuilder();
        if (s.has("sensors") && s.get("sensors").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("sensors")) {
                if (sensors.length() > 0) {
                    sensors.append(", ");
                }
                sensors.append(e.getAsString());
            }
        }
        String version = Api.str(s, "version");
        if (sensors.length() > 0 || !version.isEmpty()) {
            String sub = version.isEmpty() ? sensors.toString()
                    : activity.getString(R.string.board_version, version) + (sensors.length() > 0 ? " · " + sensors : "");
            Rows.add(card, R.drawable.ic_nav_sensor, activity.getString(R.string.board_collector), sub, null);
        }
        if (s.has("alarm") && s.get("alarm").getAsBoolean()) {
            Rows.add(card, R.drawable.ic_bell, activity.getString(R.string.board_alarm),
                    activity.getString(R.string.board_alarm_sub), null);
        }
    }

    private static Double num(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String agoText(Activity a, int seconds) {
        if (seconds == Integer.MAX_VALUE) {
            return a.getString(R.string.ago_never);
        }
        if (seconds < 60) {
            return a.getString(R.string.ago_now);
        }
        if (seconds < 3600) {
            return a.getString(R.string.ago_minutes, seconds / 60);
        }
        if (seconds < 86400) {
            return a.getString(R.string.ago_hours, seconds / 3600);
        }
        return a.getString(R.string.ago_days, seconds / 86400);
    }

    static String duration(Activity a, long seconds) {
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        if (days > 0) {
            return a.getString(R.string.board_uptime_days, days, hours);
        }
        if (hours > 0) {
            return a.getString(R.string.board_uptime_hours, hours, minutes);
        }
        return a.getString(R.string.board_uptime_minutes, minutes);
    }
}
