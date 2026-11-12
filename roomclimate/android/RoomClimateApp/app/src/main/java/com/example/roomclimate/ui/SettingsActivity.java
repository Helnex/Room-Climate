package com.example.roomclimate.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.HealthResponse;
import com.example.roomclimate.models.SettingsRequest;
import com.example.roomclimate.models.SettingsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.AppPermissions;
import com.example.roomclimate.network.ServerConfig;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SettingsActivity extends AppCompatActivity {
    private EditText editBaseUrl;
    private EditText editDeviceId;
    private EditText editTempMin;
    private EditText editTempMax;
    private EditText editHumidityMin;
    private EditText editHumidityMax;
    private TextView tvHealth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_settings);

        editBaseUrl = findViewById(R.id.editBaseUrl);
        editDeviceId = findViewById(R.id.editDeviceId);
        editTempMin = findViewById(R.id.editTempMin);
        editTempMax = findViewById(R.id.editTempMax);
        editHumidityMin = findViewById(R.id.editHumidityMin);
        editHumidityMax = findViewById(R.id.editHumidityMax);
        tvHealth = findViewById(R.id.tvHealth);
        Button btnSaveConnection = findViewById(R.id.btnSaveConnection);
        Button btnTestConnection = findViewById(R.id.btnTestConnection);
        Button btnSaveThresholds = findViewById(R.id.btnSaveThresholds);

        editBaseUrl.setText(ServerConfig.getBaseUrl(this));
        editDeviceId.setText(ServerConfig.getDeviceId(this));

        btnSaveConnection.setOnClickListener(v -> saveConnection());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSaveThresholds.setOnClickListener(v -> saveThresholds());

        BottomNav.bind(this, R.id.navSettings);
        AppPermissions.request(this);
        loadThresholds();
        testConnection();
    }

    private void saveConnection() {
        String url = editBaseUrl.getText().toString().trim();
        String deviceId = editDeviceId.getText().toString().trim();
        if (url.isEmpty() || deviceId.isEmpty()) {
            Toast.makeText(this, R.string.settings_fill_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ServerConfig.isAllowedBaseUrl(url)) {
            Toast.makeText(this, R.string.settings_url_not_allowed, Toast.LENGTH_LONG).show();
            return;
        }
        ServerConfig.save(this, url, deviceId);
        Toast.makeText(this, R.string.settings_connection_saved, Toast.LENGTH_SHORT).show();
        testConnection();
        loadThresholds();
    }

    private void testConnection() {
        saveConnectionSilently();
        tvHealth.setText(R.string.connection_checking_short);
        tvHealth.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        ApiClient.get(this).getHealth().enqueue(new Callback<HealthResponse>() {
            @Override
            public void onResponse(@NonNull Call<HealthResponse> call,
                                   @NonNull Response<HealthResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isOk()) {
                    tvHealth.setText(getString(R.string.health_ok, ServerConfig.getBaseUrl(SettingsActivity.this)));
                    tvHealth.setTextColor(ContextCompat.getColor(SettingsActivity.this, R.color.green_primary));
                } else {
                    tvHealth.setText(getString(R.string.health_bad, response.code()));
                    tvHealth.setTextColor(ContextCompat.getColor(SettingsActivity.this, R.color.alert_text));
                }
            }

            @Override
            public void onFailure(@NonNull Call<HealthResponse> call, @NonNull Throwable t) {
                tvHealth.setText(ApiErrors.network(t));
                tvHealth.setTextColor(ContextCompat.getColor(SettingsActivity.this, R.color.alert_text));
            }
        });
    }

    private void saveConnectionSilently() {
        String url = editBaseUrl.getText().toString().trim();
        String deviceId = editDeviceId.getText().toString().trim();
        // Небезопасный адрес (HTTP в интернет) молча не сохраняем: туда ушли бы токен и пароль.
        if (!url.isEmpty() && !deviceId.isEmpty() && ServerConfig.isAllowedBaseUrl(url)) {
            ServerConfig.save(this, url, deviceId);
        }
    }

    private void loadThresholds() {
        ApiClient.get(this).getSettings(ServerConfig.getDeviceId(this))
                .enqueue(new Callback<SettingsResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<SettingsResponse> call,
                                           @NonNull Response<SettingsResponse> response) {
                        if (SessionGuard.handleUnauthorized(SettingsActivity.this, response)) {
                            return;
                        }
                        if (response.isSuccessful() && response.body() != null) {
                            editTempMin.setText(String.valueOf(response.body().tempMin));
                            editTempMax.setText(String.valueOf(response.body().tempMax));
                            editHumidityMin.setText(String.valueOf(response.body().humidityMinOrDefault()));
                            editHumidityMax.setText(String.valueOf(response.body().humidityMaxOrDefault()));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<SettingsResponse> call, @NonNull Throwable t) {
                        Toast.makeText(SettingsActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void saveThresholds() {
        saveConnectionSilently();
        double min;
        double max;
        double humidityMin;
        double humidityMax;
        try {
            min = Double.parseDouble(editTempMin.getText().toString().trim().replace(',', '.'));
            max = Double.parseDouble(editTempMax.getText().toString().trim().replace(',', '.'));
            humidityMin = Double.parseDouble(editHumidityMin.getText().toString().trim().replace(',', '.'));
            humidityMax = Double.parseDouble(editHumidityMax.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.settings_bad_numbers, Toast.LENGTH_SHORT).show();
            return;
        }
        if (min >= max || humidityMin >= humidityMax) {
            Toast.makeText(this, R.string.settings_min_max, Toast.LENGTH_SHORT).show();
            return;
        }
        ApiClient.get(this)
                .putSettings(
                        ServerConfig.getDeviceId(this),
                        new SettingsRequest(min, max, humidityMin, humidityMax)
                )
                .enqueue(new Callback<SettingsResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<SettingsResponse> call,
                                           @NonNull Response<SettingsResponse> response) {
                        if (SessionGuard.handleUnauthorized(SettingsActivity.this, response)) {
                            return;
                        }
                        if (response.isSuccessful() && response.body() != null && response.body().saved) {
                            Toast.makeText(SettingsActivity.this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(
                                    SettingsActivity.this,
                                    ApiErrors.message(response, getString(R.string.settings_save_failed)),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<SettingsResponse> call, @NonNull Throwable t) {
                        Toast.makeText(SettingsActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
