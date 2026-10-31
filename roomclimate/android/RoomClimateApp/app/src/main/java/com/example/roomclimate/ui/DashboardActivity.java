package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.CurrentTelemetryResponse;
import com.example.roomclimate.models.Reading;
import com.example.roomclimate.models.SettingsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.AppPermissions;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.notify.ThresholdChecker;
import com.example.roomclimate.notify.ThresholdNotifier;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {
    private static final long POLL_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::refresh;

    private TextView tvTemperature;
    private TextView tvHumidity;
    private TextView tvStatus;
    private TextView tvUpdated;
    private TextView tvConnection;
    private TextView tvThresholds;

    private Double lastMin;
    private Double lastMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!UserPrefs.isLoggedIn(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_dashboard);

        tvTemperature = findViewById(R.id.tvTemperature);
        tvHumidity = findViewById(R.id.tvHumidity);
        tvStatus = findViewById(R.id.tvStatus);
        tvUpdated = findViewById(R.id.tvUpdated);
        tvConnection = findViewById(R.id.tvConnection);
        tvThresholds = findViewById(R.id.tvThresholds);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());
        BottomNav.bind(this, R.id.navDashboard);
        ThresholdNotifier.ensureChannel(this);
        AppPermissions.request(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AppPermissions.REQUEST_CODE) {
            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, POLL_MS);

        if (!AppPermissions.hasLocalNetwork(this)) {
            tvConnection.setText(R.string.local_network_needed);
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
            return;
        }

        String deviceId = ServerConfig.getDeviceId(this);
        tvConnection.setText(getString(R.string.connection_checking, ServerConfig.getBaseUrl(this)));

        ApiClient.get(this).getSettings(deviceId).enqueue(new Callback<SettingsResponse>() {
            @Override
            public void onResponse(@NonNull Call<SettingsResponse> call,
                                   @NonNull Response<SettingsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    lastMin = response.body().tempMin;
                    lastMax = response.body().tempMax;
                    tvThresholds.setText(getString(
                            R.string.thresholds_range,
                            lastMin,
                            lastMax
                    ));
                }
                loadCurrent(deviceId);
            }

            @Override
            public void onFailure(@NonNull Call<SettingsResponse> call, @NonNull Throwable t) {
                loadCurrent(deviceId);
            }
        });
    }

    private void loadCurrent(String deviceId) {
        ApiClient.get(this).getCurrent(deviceId).enqueue(new Callback<CurrentTelemetryResponse>() {
            @Override
            public void onResponse(@NonNull Call<CurrentTelemetryResponse> call,
                                   @NonNull Response<CurrentTelemetryResponse> response) {
                if (response.code() == 404) {
                    showEmpty();
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    showError(getString(R.string.connection_http_error, response.code()));
                    return;
                }
                showCurrent(response.body());
            }

            @Override
            public void onFailure(@NonNull Call<CurrentTelemetryResponse> call, @NonNull Throwable t) {
                showError(getString(R.string.connection_failed, t.getMessage()));
            }
        });
    }

    private void showCurrent(CurrentTelemetryResponse body) {
        Reading temperature = body.reading("temperature");
        Reading humidity = body.reading("humidity");

        if (temperature != null) {
            tvTemperature.setText(getString(R.string.value_with_unit, temperature.value, temperature.unit));
            tvUpdated.setText(getString(R.string.updated_at, temperature.measuredAt));
            applyThreshold(temperature.value);
        } else {
            tvTemperature.setText(R.string.value_placeholder);
            tvStatus.setText(R.string.status_no_temperature);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        }

        if (humidity != null) {
            tvHumidity.setText(getString(R.string.value_with_unit, humidity.value, humidity.unit));
        } else {
            tvHumidity.setText(R.string.value_placeholder);
        }

        tvConnection.setText(getString(
                R.string.connection_ok,
                body.source == null ? "api" : body.source,
                ServerConfig.getBaseUrl(this)
        ));
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.green_primary));
    }

    private void applyThreshold(double temperature) {
        ThresholdChecker.Status status = ThresholdChecker.check(temperature, lastMin, lastMax);
        int color;
        String text;
        switch (status) {
            case ABOVE:
                color = R.color.alert_text;
                text = getString(R.string.status_above, lastMax);
                break;
            case BELOW:
                color = R.color.alert_text;
                text = getString(R.string.status_below, lastMin);
                break;
            case OK:
                color = R.color.green_primary;
                text = getString(R.string.status_ok);
                break;
            default:
                color = R.color.gray_text;
                text = getString(R.string.status_unknown);
                break;
        }
        tvStatus.setText(text);
        tvStatus.setTextColor(ContextCompat.getColor(this, color));
        if (lastMin != null && lastMax != null) {
            ThresholdNotifier.notifyIfNeeded(this, temperature, lastMin, lastMax);
        }
    }

    private void showEmpty() {
        tvTemperature.setText(R.string.value_placeholder);
        tvHumidity.setText(R.string.value_placeholder);
        tvStatus.setText(R.string.status_no_data);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        tvUpdated.setText(R.string.updated_never);
        tvConnection.setText(getString(R.string.connection_no_data, ServerConfig.getBaseUrl(this)));
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
    }

    private void showError(String message) {
        tvConnection.setText(message);
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
        tvStatus.setText(R.string.status_offline);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
    }
}
