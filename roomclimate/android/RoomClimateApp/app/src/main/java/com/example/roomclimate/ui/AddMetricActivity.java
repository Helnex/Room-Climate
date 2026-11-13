package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * «Добавить метрику» (макет, экраны 8/13). Плата стенда измеряет температуру и влажность —
 * они уже на дашборде, поэтому экран показывает их и предлагает подключить новый датчик
 * (состояние «Новых метрик пока нет» из макета).
 */
public class AddMetricActivity extends AppCompatActivity {
    private TextView tvRoom;
    private LinearLayout list;
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
        for (JsonElement e : devices) {
            JsonObject d = e.getAsJsonObject();
            String source = getString(R.string.metric_added_from, Api.str(d, "name"));
            Rows.add(list, R.drawable.ic_nav_sensor, getString(R.string.metric_temperature), source, null);
            Rows.add(list, R.drawable.ic_drop, getString(R.string.metric_humidity), source, null);
        }
        if (devices.size() == 0) {
            Rows.add(list, R.drawable.ic_add, getString(R.string.metric_empty_room), null, v ->
                    startActivity(new Intent(this, AddSensorActivity.class)));
        }
    }
}
