package com.example.roomclimate.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.roomclimate.R;
import com.example.roomclimate.models.DevicesResponse;
import com.example.roomclimate.models.InsightsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.ServerConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Вкладка «Устройства» (раньше была заглушкой «скоро»):
 *  - список плат и статус «на связи / молчит N минут»;
 *  - комфорт, точка росы, риск плесени и советы по текущим показаниям;
 *  - min/max/среднее за сутки или неделю;
 *  - экспорт показаний в CSV (открывается в Excel) через «Поделиться».
 */
public class DevicesActivity extends AppCompatActivity {
    private TextView tvStatus;
    private LinearLayout devicesList;
    private TextView tvComfort;
    private TextView tvDetails;
    private TextView tvAdvice;
    private TextView tvStats;
    private Button btnExport;
    private int periodHours = 24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_devices);
        BottomNav.bind(this, R.id.navDevices);

        tvStatus = findViewById(R.id.tvDevicesStatus);
        devicesList = findViewById(R.id.devicesList);
        tvComfort = findViewById(R.id.tvComfort);
        tvDetails = findViewById(R.id.tvInsightsDetails);
        tvAdvice = findViewById(R.id.tvAdvice);
        tvStats = findViewById(R.id.tvStats);
        btnExport = findViewById(R.id.btnExport);

        findViewById(R.id.btnPeriodDay).setOnClickListener(v -> setPeriod(24));
        findViewById(R.id.btnPeriodWeek).setOnClickListener(v -> setPeriod(24 * 7));
        btnExport.setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnPush).setOnClickListener(v -> subscribePush());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvStatus != null) {
            loadDevices();
            loadInsights();
            loadAlerts();
        }
    }

    private void setPeriod(int hours) {
        periodHours = hours;
        loadInsights();
    }

    // ---------- Устройства ----------

    private void loadDevices() {
        tvStatus.setText(R.string.connection_checking_short);
        ApiClient.get(this).getDevices().enqueue(new Callback<DevicesResponse>() {
            @Override
            public void onResponse(@NonNull Call<DevicesResponse> call, @NonNull Response<DevicesResponse> response) {
                if (SessionGuard.handleUnauthorized(DevicesActivity.this, response)) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    tvStatus.setText(ApiErrors.message(response, getString(R.string.devices_load_failed)));
                    return;
                }
                showDevices(response.body().devices);
                if (response.headers().get(com.example.roomclimate.network.OfflineCache.HEADER) != null) {
                    tvStatus.setText(R.string.offline_data);
                }
            }

            @Override
            public void onFailure(@NonNull Call<DevicesResponse> call, @NonNull Throwable t) {
                tvStatus.setText(ApiErrors.network(t));
            }
        });
    }

    private void showDevices(List<DevicesResponse.Device> devices) {
        devicesList.removeAllViews();
        if (devices == null || devices.isEmpty()) {
            tvStatus.setText(R.string.devices_empty);
            return;
        }
        String active = ServerConfig.getDeviceId(this);
        tvStatus.setText(getString(R.string.devices_count, devices.size()));
        for (DevicesResponse.Device device : devices) {
            TextView card = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            card.setLayoutParams(lp);
            card.setBackgroundResource(R.drawable.bg_white_card);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setTextSize(15);
            boolean isActive = device.deviceId != null && device.deviceId.equals(active);
            String state = device.online
                    ? getString(R.string.device_online, ago(device.ageSeconds))
                    : getString(R.string.device_offline, ago(device.ageSeconds));
            String role = "owner".equals(device.role) ? getString(R.string.role_owner)
                    : "member".equals(device.role) ? getString(R.string.role_member)
                    : getString(R.string.role_unclaimed);
            card.setText(getString(R.string.device_row,
                    isActive ? "● " : "○ ", device.deviceId, state) + "\n" + role);
            card.setTextColor(ContextCompat.getColor(this,
                    device.online ? R.color.dark_text : R.color.alert_text));
            card.setContentDescription(getString(R.string.a11y_device_card, device.deviceId, state, role));
            card.setOnClickListener(v -> showDeviceActions(device));
            devicesList.addView(card);
        }
    }

    /** Действия с платой: сделать основной, закрепить за собой, дать доступ по email. */
    private void showDeviceActions(DevicesResponse.Device device) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        labels.add(getString(R.string.action_select_device));
        actions.add(() -> selectDevice(device.deviceId));
        if (device.role == null) {
            labels.add(getString(R.string.action_claim_device));
            actions.add(() -> claimDevice(device.deviceId));
        }
        if ("owner".equals(device.role)) {
            labels.add(getString(R.string.action_share_device));
            actions.add(() -> askShareEmail(device.deviceId));
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(device.deviceId)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void claimDevice(String deviceId) {
        ApiClient.get(this).claimDevice(deviceId).enqueue(simple(R.string.claim_done, () -> loadDevices()));
    }

    private void askShareEmail(String deviceId) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                | android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.share_email_hint);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_share_device)
                .setMessage(R.string.share_explain)
                .setView(input)
                .setPositiveButton(R.string.share_ok, (d, w) -> {
                    com.google.gson.JsonObject body = new com.google.gson.JsonObject();
                    body.addProperty("email", input.getText().toString().trim());
                    ApiClient.get(this).addMember(deviceId, body).enqueue(simple(R.string.share_done, null));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Обработчик ответа: тост об успехе или текст ошибки сервера. */
    private Callback<com.google.gson.JsonObject> simple(int okMessage, Runnable after) {
        return new Callback<com.google.gson.JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<com.google.gson.JsonObject> call,
                                   @NonNull Response<com.google.gson.JsonObject> response) {
                if (SessionGuard.handleUnauthorized(DevicesActivity.this, response)) {
                    return;
                }
                Toast.makeText(DevicesActivity.this, response.isSuccessful() ? getString(okMessage)
                        : ApiErrors.message(response, getString(R.string.devices_load_failed)), Toast.LENGTH_LONG).show();
                if (response.isSuccessful() && after != null) {
                    after.run();
                }
            }

            @Override
            public void onFailure(@NonNull Call<com.google.gson.JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(DevicesActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        };
    }

    // ---------- Журнал тревог и push ----------

    private void loadAlerts() {
        ApiClient.get(this).getAlerts(ServerConfig.getDeviceId(this), 10).enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<com.google.gson.JsonObject> call,
                                   @NonNull Response<com.google.gson.JsonObject> response) {
                TextView tv = findViewById(R.id.tvAlerts);
                if (!response.isSuccessful() || response.body() == null || !response.body().has("alerts")) {
                    tv.setText(R.string.alerts_unavailable);
                    return;
                }
                com.google.gson.JsonArray list = response.body().getAsJsonArray("alerts");
                if (list.size() == 0) {
                    tv.setText(R.string.alerts_empty);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (com.google.gson.JsonElement e : list) {
                    com.google.gson.JsonObject a = e.getAsJsonObject();
                    boolean open = !a.has("resolved_at") || a.get("resolved_at").isJsonNull();
                    sb.append(open ? "⚠ " : "✓ ")
                            .append(Formatters.time(a.get("created_at").getAsString()))
                            .append(" — ").append(a.get("message").getAsString()).append('\n');
                }
                tv.setText(sb.toString().trim());
            }

            @Override
            public void onFailure(@NonNull Call<com.google.gson.JsonObject> call, @NonNull Throwable t) {
                ((TextView) findViewById(R.id.tvAlerts)).setText(ApiErrors.network(t));
            }
        });
    }

    /** Подписка на push: открывает приложение ntfy с темой стенда (или страницу установки). */
    private void subscribePush() {
        ApiClient.get(this).getNotifyInfo().enqueue(new Callback<com.google.gson.JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<com.google.gson.JsonObject> call,
                                   @NonNull Response<com.google.gson.JsonObject> response) {
                com.google.gson.JsonObject info = response.body();
                if (!response.isSuccessful() || info == null || !info.has("enabled") || !info.get("enabled").getAsBoolean()) {
                    Toast.makeText(DevicesActivity.this, R.string.push_disabled, Toast.LENGTH_LONG).show();
                    return;
                }
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(info.get("app_link").getAsString()));
                try {
                    startActivity(open);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(DevicesActivity.this, getString(R.string.push_install_ntfy,
                            info.get("subscribe_url").getAsString()), Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=io.heckel.ntfy")));
                }
            }

            @Override
            public void onFailure(@NonNull Call<com.google.gson.JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(DevicesActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void selectDevice(String deviceId) {
        if (deviceId == null || deviceId.equals(ServerConfig.getDeviceId(this))) {
            return;
        }
        ServerConfig.save(this, ServerConfig.getBaseUrl(this), deviceId);
        Toast.makeText(this, getString(R.string.device_selected, deviceId), Toast.LENGTH_SHORT).show();
        loadDevices();
        loadInsights();
    }

    // ---------- Аналитика ----------

    private void loadInsights() {
        String deviceId = ServerConfig.getDeviceId(this);
        ApiClient.get(this).getInsights(deviceId, periodHours).enqueue(new Callback<InsightsResponse>() {
            @Override
            public void onResponse(@NonNull Call<InsightsResponse> call, @NonNull Response<InsightsResponse> response) {
                if (SessionGuard.handleUnauthorized(DevicesActivity.this, response)) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    tvComfort.setText(R.string.value_placeholder);
                    tvDetails.setText(ApiErrors.message(response, getString(R.string.devices_load_failed)));
                    return;
                }
                showInsights(response.body());
            }

            @Override
            public void onFailure(@NonNull Call<InsightsResponse> call, @NonNull Throwable t) {
                tvDetails.setText(ApiErrors.network(t));
            }
        });
    }

    private void showInsights(InsightsResponse body) {
        String comfort = body.comfort != null ? body.comfort.label : getString(R.string.value_placeholder);
        tvComfort.setText(comfort);
        boolean good = body.comfort != null && "comfort".equals(body.comfort.code);
        tvComfort.setTextColor(ContextCompat.getColor(this, good ? R.color.green_primary : R.color.alert_text));

        StringBuilder details = new StringBuilder();
        if (body.dewPoint != null) {
            details.append(getString(R.string.insight_dew_point, Formatters.number(body.dewPoint))).append('\n');
        }
        if (body.moldRisk != null) {
            details.append(getString(R.string.insight_mold, body.moldRisk.label)).append('\n');
        }
        details.append(body.online
                ? getString(R.string.device_online, ago(body.ageSeconds))
                : getString(R.string.device_offline, ago(body.ageSeconds)));
        tvDetails.setText(details.toString());

        StringBuilder tips = new StringBuilder();
        if (body.advice != null) {
            for (String tip : body.advice) {
                tips.append("• ").append(tip).append('\n');
            }
        }
        tvAdvice.setText(tips.toString().trim());

        StringBuilder stats = new StringBuilder(getString(
                periodHours <= 24 ? R.string.stats_title_day : R.string.stats_title_week));
        boolean any = false;
        if (body.stats != null) {
            for (Map.Entry<String, InsightsResponse.Stats> entry : body.stats.entrySet()) {
                InsightsResponse.Stats s = entry.getValue();
                if (s == null) {
                    continue;
                }
                any = true;
                String unit = "humidity".equals(entry.getKey()) ? "%" : "°C";
                stats.append('\n').append(getString(R.string.stats_row,
                        Formatters.sensor(this, entry.getKey()),
                        Formatters.number(s.min), Formatters.number(s.avg), Formatters.number(s.max), unit));
            }
        }
        if (!any) {
            stats.append('\n').append(getString(R.string.stats_empty));
        }
        tvStats.setText(stats.toString());
    }

    // ---------- Экспорт ----------

    private void exportCsv() {
        btnExport.setEnabled(false);
        String deviceId = ServerConfig.getDeviceId(this);
        ApiClient.get(this).exportCsv(deviceId, periodHours).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                btnExport.setEnabled(true);
                if (SessionGuard.handleUnauthorized(DevicesActivity.this, response)) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    Toast.makeText(DevicesActivity.this,
                            ApiErrors.message(response, getString(R.string.export_failed)), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    File file = saveExport(deviceId, response.body());
                    share(file);
                } catch (IOException e) {
                    Toast.makeText(DevicesActivity.this, R.string.export_failed, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                btnExport.setEnabled(true);
                Toast.makeText(DevicesActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    private File saveExport(String deviceId, ResponseBody body) throws IOException {
        File dir = new File(getCacheDir(), "exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("no dir");
        }
        String safe = deviceId.replaceAll("[^A-Za-z0-9_-]", "_");
        File file = new File(dir, "roomclimate_" + safe + "_" + (periodHours <= 24 ? "24h" : "7d") + ".csv");
        try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return file;
    }

    private void share(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.btn_export_csv)));
    }

    // ---------- Вспомогательное ----------

    private String ago(Integer seconds) {
        if (seconds == null) {
            return getString(R.string.ago_never);
        }
        if (seconds < 60) {
            return getString(R.string.ago_now);
        }
        if (seconds < 3600) {
            return getString(R.string.ago_minutes, seconds / 60);
        }
        if (seconds < 86400) {
            return getString(R.string.ago_hours, seconds / 3600);
        }
        return getString(R.string.ago_days, seconds / 86400);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
