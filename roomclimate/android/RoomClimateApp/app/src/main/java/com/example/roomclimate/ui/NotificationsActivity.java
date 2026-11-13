package com.example.roomclimate.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.SettingsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.notify.ClimateMonitorService;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * «Уведомления» (макет, экран 21): общий выключатель (прежний «Уведомления о климате» из
 * «Аккаунта») и что именно присылать: температура, влажность, потеря и восстановление связи.
 */
public class NotificationsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        SectionScreen s = SectionScreen.setUp(this, R.string.notify_title, 0, R.string.notify_desc);
        SwitchCompat push = s.toggle(R.string.notify_push, getString(R.string.notify_push_sub),
                UserPrefs.alertsEnabled(this), null);
        s.group(R.string.notify_group_thresholds);
        SwitchCompat temp = s.toggle(R.string.label_temperature, getString(R.string.notify_threshold_sub),
                AppPrefs.notify(this, AppPrefs.TEMPERATURE), null);
        SwitchCompat hum = s.toggle(R.string.label_humidity, getString(R.string.notify_threshold_sub),
                AppPrefs.notify(this, AppPrefs.HUMIDITY), null);
        s.group(R.string.notify_group_sensor);
        SwitchCompat lost = s.toggle(R.string.notify_lost, getString(R.string.notify_lost_sub),
                AppPrefs.notify(this, AppPrefs.SENSOR_LOST), null);
        SwitchCompat back = s.toggle(R.string.notify_back, getString(R.string.notify_back_sub),
                AppPrefs.notify(this, AppPrefs.SENSOR_BACK), null);

        s.button(R.string.notify_save, v -> {
            UserPrefs.setAlertsEnabled(this, push.isChecked());
            AppPrefs.setNotify(this, AppPrefs.TEMPERATURE, temp.isChecked());
            AppPrefs.setNotify(this, AppPrefs.HUMIDITY, hum.isChecked());
            AppPrefs.setNotify(this, AppPrefs.SENSOR_LOST, lost.isChecked());
            AppPrefs.setNotify(this, AppPrefs.SENSOR_BACK, back.isChecked());
            if (push.isChecked()) {
                ClimateMonitorService.start(this);
            } else {
                ClimateMonitorService.stop(this);
            }
            Toast.makeText(this, R.string.notify_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        // Подсказка с текущими порогами основной платы, как в макете.
        s.note(getString(R.string.notify_note_generic));
        ApiClient.get(this).getSettings(ServerConfig.getDeviceId(this)).enqueue(new Callback<SettingsResponse>() {
            @Override
            public void onResponse(Call<SettingsResponse> call, Response<SettingsResponse> response) {
                SettingsResponse l = response.body();
                if (response.isSuccessful() && l != null) {
                    s.note(getString(R.string.notify_note, ServerConfig.getDeviceId(NotificationsActivity.this),
                            Units.temperature(NotificationsActivity.this, l.tempMin),
                            Units.temperature(NotificationsActivity.this, l.tempMax),
                            Math.round(l.humidityMinOrDefault()), Math.round(l.humidityMaxOrDefault())));
                }
            }

            @Override
            public void onFailure(Call<SettingsResponse> call, Throwable t) {
                // остаётся общая подсказка
            }
        });
    }
}
