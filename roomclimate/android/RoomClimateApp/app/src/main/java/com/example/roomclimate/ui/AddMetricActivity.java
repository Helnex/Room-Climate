package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Добавить метрику» (макет, экраны 8/13). Температура и влажность основной платы на дашборде
 * всегда. Углекислый газ, освещённость и движение (доп. датчики сборщика) можно вынести на
 * дашборд отдельной карточкой: экран показывает те, что плата помещения уже присылает или
 * для которых она настроена («Добавить датчик» → показатель). Нажатие — добавить; нажатие на
 * уже добавленную — убрать.
 */
public class AddMetricActivity extends AppCompatActivity {
    private TextView tvRoom;
    private LinearLayout list;
    private LinearLayout available;
    private View availableCard;
    private View noneBlock;
    private JsonArray rooms = new JsonArray();
    private JsonArray unassigned = new JsonArray();
    private int selected = -1; // индекс помещения; -1 — платы без помещения

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_add_metric);
        ScreenHeader.bindSub(this, R.string.add_metric);
        tvRoom = findViewById(R.id.tvMetricRoom);
        list = findViewById(R.id.metricList);
        available = findViewById(R.id.metricAvailableList);
        availableCard = findViewById(R.id.metricAvailableCard);
        noneBlock = findViewById(R.id.metricNoneBlock);
        tvRoom.setOnClickListener(v -> pickRoom());
        findViewById(R.id.btnMetricAddSensor).setOnClickListener(v ->
                startActivity(new Intent(this, AddSensorActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) {
            ApiClient.get(this).getRooms().enqueue(Api.call(this, body -> {
                rooms = body.has("rooms") ? body.getAsJsonArray("rooms") : new JsonArray();
                unassigned = body.has("unassigned") ? body.getAsJsonArray("unassigned") : new JsonArray();
                if (selected >= rooms.size()) {
                    selected = -1;
                }
                if (selected == -1 && rooms.size() > 0) {
                    selected = 0;
                }
                render();
            }));
        }
    }

    private void pickRoom() {
        List<String> labels = new ArrayList<>();
        for (JsonElement e : rooms) {
            labels.add(e.getAsJsonObject().get("name").getAsString());
        }
        labels.add(getString(R.string.sensor_room_none));
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_room_pick)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    selected = which < rooms.size() ? which : -1;
                    render();
                })
                .show();
    }

    private void render() {
        JsonArray devices;
        String roomName;
        if (selected >= 0) {
            JsonObject room = rooms.get(selected).getAsJsonObject();
            devices = room.getAsJsonArray("devices");
            roomName = room.get("name").getAsString();
        } else {
            devices = unassigned;
            roomName = getString(R.string.sensor_room_none);
        }
        tvRoom.setText(roomName);
        ((TextView) findViewById(R.id.tvMetricNone)).setText(getString(R.string.metric_none_text_room, roomName));
        list.removeAllViews();
        available.removeAllViews();
        for (JsonElement e : devices) {
            JsonObject d = e.getAsJsonObject();
            String deviceId = Api.str(d, "device_id");
            String board = Api.str(d, "name");
            String source = getString(R.string.metric_added_from, board);
            JsonObject readings = d.has("readings") && d.get("readings").isJsonObject()
                    ? d.getAsJsonObject("readings") : new JsonObject();
            // Температура и влажность — у платы с DHT22 (тип climate или уже присланные значения).
            if ("climate".equals(Api.str(d, "kind")) || readings.has("temperature")) {
                Rows.add(list, R.drawable.ic_nav_sensor, getString(R.string.metric_temperature), source, null);
                Rows.add(list, R.drawable.ic_drop, getString(R.string.metric_humidity), source, null);
            }
            for (String type : Metrics.EXTRA) {
                boolean sends = readings.has(type) && !readings.get(type).isJsonNull();
                boolean configured = type.equals(Api.str(d, "kind"));
                if (!sends && !configured) {
                    continue;
                }
                String label = Metrics.label(this, type);
                Double value = sends ? readings.get(type).getAsDouble() : null;
                if (Metrics.isOnDashboard(this, deviceId, type)) {
                    String sub = getString(R.string.metric_on_board, Metrics.value(this, type, value), board);
                    Rows.add(list, Metrics.icon(type), label, sub, v -> confirmRemove(deviceId, type, label));
                } else {
                    String sub = sends
                            ? getString(R.string.metric_tap_add, getString(R.string.metric_on_board, Metrics.value(this, type, value), board))
                            : getString(R.string.metric_waiting, board);
                    Rows.add(available, Metrics.icon(type), label, sub, v -> {
                        Metrics.add(this, deviceId, type);
                        Toast.makeText(this, getString(R.string.metric_added, label), Toast.LENGTH_SHORT).show();
                        render();
                    });
                }
            }
        }
        if (devices.size() == 0) {
            Rows.add(list, R.drawable.ic_add, getString(R.string.metric_empty_room), null, v ->
                    startActivity(new Intent(this, AddSensorActivity.class)));
        }
        boolean any = available.getChildCount() > 0;
        availableCard.setVisibility(any ? View.VISIBLE : View.GONE);
        noneBlock.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private void confirmRemove(String deviceId, String type, String label) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.metric_remove_title, label))
                .setPositiveButton(R.string.metric_remove_ok, (d, w) -> {
                    Metrics.remove(this, Metrics.entry(deviceId, type));
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
