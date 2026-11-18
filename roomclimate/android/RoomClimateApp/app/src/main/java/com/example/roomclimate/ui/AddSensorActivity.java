package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.google.gson.JsonObject;

/**
 * «Добавление датчика» (макет). Датчик выбирается из базы сервера (его туда заносят миграцией),
 * показатель берётся из записи датчика, помещение — из своих помещений с платой.
 * Сохранение — POST /registry/sensors/{serial}/bind: датчик привязывается к плате помещения,
 * и показания этой платы по его показателям становятся показаниями датчика.
 */
public class AddSensorActivity extends AppCompatActivity {
    /** Помещение заранее (кнопка «+» у помещения в «Устройствах»). */
    public static final String EXTRA_ROOM_ID = "room_id";

    private EditText editName;
    private TextView tvNote;
    private JsonObject sensor;
    private JsonObject room;
    private JsonObject board;
    private int presetRoom;

    private final ActivityResultLauncher<Intent> pickSensor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    loadSensors(result.getData().getStringExtra(SensorPickActivity.EXTRA_SERIAL));
                }
            });

    private final ActivityResultLauncher<Intent> pickRoom = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    loadRooms(result.getData().getIntExtra(RoomPickActivity.EXTRA_ROOM_ID, -1));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_add_sensor);
        ScreenHeader.bindSub(this, R.string.as_title);
        BottomNav.bind(this, R.id.navDevices);
        editName = findViewById(R.id.editSensorName);
        tvNote = findViewById(R.id.tvAddNote);
        presetRoom = getIntent().getIntExtra(EXTRA_ROOM_ID, -1);

        findViewById(R.id.sensorCard).setOnClickListener(v -> pickSensor.launch(
                new Intent(this, SensorPickActivity.class)
                        .putExtra(SensorPickActivity.EXTRA_SERIAL, sensor == null ? null : Api.str(sensor, "serial"))));
        findViewById(R.id.roomCard).setOnClickListener(v -> openRoomPicker());
        Button add = findViewById(R.id.btnAddSensor);
        add.setOnClickListener(v -> save(add));
        loadSensors(null);
        loadRooms(presetRoom);
    }

    // ---------- Датчик ----------

    private void loadSensors(String serial) {
        ApiClient.get(this).getRegistrySensors().enqueue(Api.call(this, body -> {
            JsonObject found = null;
            for (JsonObject s : Registry.list(Registry.array(body, "sensors"))) {
                if (!"free".equals(Api.str(s, "status"))) {
                    continue;
                }
                if (serial == null ? found == null : serial.equals(Api.str(s, "serial"))) {
                    found = s;
                }
            }
            setSensor(found);
        }));
    }

    private void setSensor(JsonObject s) {
        // Подставленное по умолчанию название меняем вместе с датчиком, своё — не трогаем.
        String typed = editName.getText().toString().trim();
        boolean auto = typed.isEmpty() || (sensor != null && typed.equals(Api.str(sensor, "default_name")));
        sensor = s;
        TextView title = findViewById(R.id.tvSensorTitle);
        TextView state = findViewById(R.id.tvSensorState);
        TextView pickText = findViewById(R.id.tvSensorPick);
        TextView metric = findViewById(R.id.tvSensorMetric);
        if (s == null) {
            title.setText(R.string.as_none);
            state.setText(R.string.as_no_free);
            pickText.setText(R.string.as_pick);
            metric.setText(R.string.value_placeholder);
        } else {
            title.setText(Api.str(s, "type_name"));
            state.setText(getString(R.string.as_serial_state, Api.str(s, "serial"), getString(R.string.sensor_free)));
            pickText.setText(R.string.as_pick_other);
            metric.setText(Registry.metricLabel(this, s));
            if (auto) {
                editName.setText(Api.str(s, "default_name"));
            }
        }
        updateNote();
    }

    // ---------- Помещение ----------

    /** Плата помещения из ответа /rooms (зарегистрированная в базе), или null. */
    static JsonObject boardOf(JsonObject room) {
        for (JsonObject d : Registry.list(Registry.array(room, "devices"))) {
            if (d.has("serial") && !d.get("serial").isJsonNull()) {
                return d;
            }
        }
        return null;
    }

    private void loadRooms(int select) {
        ApiClient.get(this).getRooms().enqueue(Api.call(this, body -> {
            JsonObject chosen = null;
            JsonObject first = null;
            for (JsonObject r : Registry.list(Registry.array(body, "rooms"))) {
                if (!Registry.bool(r, "own") || boardOf(r) == null) {
                    continue;
                }
                if (first == null) {
                    first = r;
                }
                if (r.get("id").getAsInt() == select) {
                    chosen = r;
                }
            }
            if (chosen == null && room == null) {
                chosen = first;
            }
            if (chosen != null) {
                setRoom(chosen);
            }
        }));
    }

    private void setRoom(JsonObject r) {
        room = r;
        board = boardOf(r);
        ((TextView) findViewById(R.id.tvRoomName)).setText(getString(R.string.as_room_value, Api.str(r, "name")));
        TextView tvBoard = findViewById(R.id.tvRoomBoard);
        tvBoard.setVisibility(View.VISIBLE);
        tvBoard.setText(Api.str(board, "title"));
        updateNote();
    }

    private void openRoomPicker() {
        Intent intent = new Intent(this, RoomPickActivity.class)
                .putExtra(RoomPickActivity.EXTRA_ROOM_ID, room == null ? -1 : room.get("id").getAsInt());
        if (sensor != null) {
            String name = editName.getText().toString().trim();
            intent.putExtra(RoomPickActivity.EXTRA_SENSOR_TITLE, getString(R.string.pr_sensor,
                    name.isEmpty() ? Api.str(sensor, "default_name") : name, Api.str(sensor, "serial")));
            intent.putExtra(RoomPickActivity.EXTRA_SENSOR_SUB, getString(R.string.pr_sensor_sub,
                    Registry.metricLabel(this, sensor)));
        }
        pickRoom.launch(intent);
    }

    private void updateNote() {
        if (sensor != null && board != null) {
            tvNote.setText(getString(R.string.as_note, Api.str(sensor, "serial"), Api.str(board, "serial")));
        } else {
            tvNote.setText(R.string.as_note_empty);
        }
    }

    // ---------- Сохранение ----------

    private void save(Button add) {
        if (sensor == null) {
            Toast.makeText(this, R.string.as_need_sensor, Toast.LENGTH_LONG).show();
            return;
        }
        if (room == null || board == null) {
            Toast.makeText(this, R.string.as_need_room, Toast.LENGTH_LONG).show();
            openRoomPicker();
            return;
        }
        String name = editName.getText().toString().trim();
        if (name.isEmpty()) {
            name = Api.str(sensor, "default_name");
        }
        String finalName = name;
        JsonObject body = new JsonObject();
        body.addProperty("room_id", room.get("id").getAsInt());
        body.addProperty("name", name);
        add.setEnabled(false);
        add.postDelayed(() -> add.setEnabled(true), 1500);
        String boardId = Api.str(board, "device_id");
        String type = Api.str(sensor, "type");
        ApiClient.get(this).bindSensor(Api.str(sensor, "serial"), body).enqueue(Api.call(this, ok -> {
            if ("climate".equals(type)) {
                // Температура и влажность — основное на дашборде: показываем это помещение.
                ServerConfig.save(this, ServerConfig.getBaseUrl(this), boardId);
            } else {
                // CO₂ / свет / движение — отдельная карточка на дашборде.
                for (JsonObject m : Registry.list(Registry.array(sensor, "metrics"))) {
                    Metrics.add(this, boardId, Api.str(m, "code"));
                }
            }
            Toast.makeText(this, getString(R.string.as_done, finalName), Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        }));
    }
}
