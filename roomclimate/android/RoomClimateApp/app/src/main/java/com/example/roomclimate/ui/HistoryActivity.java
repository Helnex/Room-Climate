package com.example.roomclimate.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.models.HistoryPoint;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HistoryActivity extends AppCompatActivity {
    private TextView tvSummary;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        tvSummary = findViewById(R.id.tvHistorySummary);
        ListView list = findViewById(R.id.listHistory);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);

        findViewById(R.id.btnReloadHistory).setOnClickListener(v -> loadHistory());
        BottomNav.bind(this, R.id.navHistory);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        tvSummary.setText(R.string.history_loading);
        String deviceId = ServerConfig.getDeviceId(this);
        ApiClient.get(this).getHistory(deviceId, null, null, null).enqueue(new Callback<HistoryResponse>() {
            @Override
            public void onResponse(@NonNull Call<HistoryResponse> call,
                                   @NonNull Response<HistoryResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    tvSummary.setText(getString(R.string.connection_http_error, response.code()));
                    return;
                }
                showHistory(response.body());
            }

            @Override
            public void onFailure(@NonNull Call<HistoryResponse> call, @NonNull Throwable t) {
                tvSummary.setText(getString(R.string.connection_failed, t.getMessage()));
                Toast.makeText(HistoryActivity.this, t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showHistory(HistoryResponse body) {
        rows.clear();
        List<HistoryPoint> points = body.points;
        if (points != null) {
            for (int i = points.size() - 1; i >= 0; i--) {
                HistoryPoint point = points.get(i);
                rows.add(String.format(
                        Locale.US,
                        "%s  %s  %.1f %s",
                        point.measuredAt,
                        point.type,
                        point.value,
                        point.unit
                ));
            }
        }
        adapter.notifyDataSetChanged();
        tvSummary.setText(getString(R.string.history_count, body.count, ServerConfig.getDeviceId(this)));
    }
}
