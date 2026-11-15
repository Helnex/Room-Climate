package com.example.roomclimate.ui;

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
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.AppPermissions;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.notify.ClimateMonitorService;
import com.example.roomclimate.notify.ThresholdChecker;

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
    private Double lastHumidityMin;
    private Double lastHumidityMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
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
        findViewById(R.id.btnAddMetric).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AddMetricActivity.class)));
        BottomNav.bind(this, R.id.navDashboard);
        AppPermissions.request(this);
        ClimateMonitorService.start(this);
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

        if (AppPermissions.blocksServer(this)) {
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
                if (SessionGuard.handleUnauthorized(DashboardActivity.this, response)) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null) {
                    lastMin = response.body().tempMin;
                    lastMax = response.body().tempMax;
                    lastHumidityMin = response.body().humidityMinOrDefault();
                    lastHumidityMax = response.body().humidityMaxOrDefault();
                    tvThresholds.setText(getString(
                            R.string.thresholds_range,
                            Units.temperature(DashboardActivity.this, lastMin),
                            Units.temperature(DashboardActivity.this, lastMax),
                            lastHumidityMin,
                            lastHumidityMax
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
                if (SessionGuard.handleUnauthorized(DashboardActivity.this, response)) {
                    return;
                }
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
                showError(ApiErrors.network(t));
                // Сменилась сеть (дом ↔ мобильный интернет) — ищем сервер заново и сразу обновляем.
                com.example.roomclimate.network.ServerDiscovery.recover(DashboardActivity.this, url -> {
                    if (url != null && !isFinishing()) {
                        refresh();
                    }
                });
            }
        });
    }

    private void showCurrent(CurrentTelemetryResponse body) {
        Reading temperature = body.reading("temperature");
        Reading humidity = body.reading("humidity");

        if (temperature != null) {
            // Единицы — как выбрано в «Настройки → Единицы измерения» (°C / °F).
            tvTemperature.setText(Units.temperature(this, temperature.value));
            tvUpdated.setText(getString(R.string.updated_at, Formatters.time(temperature.measuredAt)));
            applyThreshold(temperature.value);
        } else {
            tvTemperature.setText(R.string.value_placeholder);
            tvStatus.setText(R.string.status_no_temperature);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        }

        if (humidity != null) {
            tvHumidity.setText(getString(R.string.value_with_unit, humidity.value, Formatters.unit(humidity.unit)));
            applyHumidityThreshold(humidity.value, temperature);
        } else {
            tvHumidity.setText(R.string.value_placeholder);
        }

        tvConnection.setText(getString(
                R.string.connection_ok,
                body.source == null ? "api" : body.source,
                ServerConfig.getBaseUrl(this)
        ));
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.green_primary));
        renderExtras(body);
    }

    /**
     * Карточка метрик из «Добавить метрику» (CO₂, освещённость, движение). Показания основной
     * платы берутся из уже полученного ответа, других плат — отдельным запросом на каждую.
     */
    private void renderExtras(CurrentTelemetryResponse mainBody) {
        android.widget.LinearLayout box = findViewById(R.id.extraMetrics);
        java.util.List<String> entries = Metrics.onDashboard(this);
        box.removeAllViews();
        box.setVisibility(entries.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        String mainId = ServerConfig.getDeviceId(this);
        java.util.Map<String, java.util.List<Object[]>> others = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            String deviceId = Metrics.deviceOf(entry);
            String type = Metrics.typeOf(entry);
            android.view.View row = Rows.add(box, Metrics.icon(type), Metrics.label(this, type),
                    getString(R.string.value_placeholder),
                    v -> startActivity(new android.content.Intent(this, AddMetricActivity.class)));
            TextView sub = row.findViewById(R.id.rowSubtitle);
            if (deviceId.equals(mainId)) {
                showExtra(sub, type, mainBody == null ? null : mainBody.reading(type), deviceId, true);
            } else {
                others.computeIfAbsent(deviceId, k -> new java.util.ArrayList<>()).add(new Object[]{sub, type});
            }
        }
        for (java.util.Map.Entry<String, java.util.List<Object[]>> board : others.entrySet()) {
            ApiClient.get(this).getCurrent(board.getKey()).enqueue(new Callback<CurrentTelemetryResponse>() {
                @Override
                public void onResponse(@NonNull Call<CurrentTelemetryResponse> call,
                                       @NonNull Response<CurrentTelemetryResponse> response) {
                    CurrentTelemetryResponse b = response.isSuccessful() ? response.body() : null;
                    for (Object[] item : board.getValue()) {
                        String type = (String) item[1];
                        showExtra((TextView) item[0], type, b == null ? null : b.reading(type), board.getKey(), false);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<CurrentTelemetryResponse> call, @NonNull Throwable t) {
                    // остаётся «—»
                }
            });
        }
    }

    private void showExtra(TextView sub, String type, Reading reading, String deviceId, boolean main) {
        String value = Metrics.value(this, type, reading == null ? null : reading.value);
        sub.setText(main ? value : getString(R.string.metric_on_board, value, deviceId));
    }

    private void applyThreshold(double temperature) {
        ThresholdChecker.Status status = ThresholdChecker.check(temperature, lastMin, lastMax);
        int color;
        String text;
        switch (status) {
            case ABOVE:
                color = R.color.alert_text;
                text = getString(R.string.status_above, Units.temperature(this, lastMax));
                break;
            case BELOW:
                color = R.color.alert_text;
                text = getString(R.string.status_below, Units.temperature(this, lastMin));
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
    }

    private void applyHumidityThreshold(double humidity, Reading temperature) {
        ThresholdChecker.Status humidityStatus = ThresholdChecker.check(humidity, lastHumidityMin, lastHumidityMax);
        if (!ThresholdChecker.isAlarm(humidityStatus)) {
            return;
        }
        ThresholdChecker.Status temperatureStatus = temperature == null
                ? ThresholdChecker.Status.UNKNOWN
                : ThresholdChecker.check(temperature.value, lastMin, lastMax);
        if (ThresholdChecker.isAlarm(temperatureStatus)) {
            return;
        }
        String text = humidityStatus == ThresholdChecker.Status.ABOVE
                ? getString(R.string.status_humidity_above, lastHumidityMax)
                : getString(R.string.status_humidity_below, lastHumidityMin);
        tvStatus.setText(text);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
    }

    private void showEmpty() {
        tvTemperature.setText(R.string.value_placeholder);
        tvHumidity.setText(R.string.value_placeholder);
        tvStatus.setText(R.string.status_no_data);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        tvUpdated.setText(R.string.updated_never);
        tvConnection.setText(getString(R.string.connection_no_data, ServerConfig.getBaseUrl(this)));
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        renderExtras(null);
    }

    private void showError(String message) {
        tvConnection.setText(message);
        tvConnection.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
        tvStatus.setText(R.string.status_offline);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
    }
}
