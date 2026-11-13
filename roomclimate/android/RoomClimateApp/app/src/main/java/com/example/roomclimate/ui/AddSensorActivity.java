package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.models.DevicesResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.ServerConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * «Добавление датчика» (макет, экраны 15/20/25). Плата выбирается из найденных сервером
 * (она сама присылает показания) или вводится номер вручную; затем название, показатель и
 * помещение → POST /devices/{id}/setup (плата закрепляется за пользователем).
 */
public class AddSensorActivity extends AppCompatActivity {
    public static final String EXTRA_DEVICE_ID = "device_id";
    private static final String ID_PATTERN = "^[A-Za-z0-9_.\\-]{1,64}$";

    private TextView tvBoardTitle;
    private TextView tvBoardState;
    private EditText editName;
    private TextView tvRoom;
    private String deviceId;
    private Integer roomId;
    private final List<String> roomNames = new ArrayList<>();
    private final List<Integer> roomIds = new ArrayList<>();

    private final ActivityResultLauncher<Intent> newRoom = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    int id = result.getData().getIntExtra(NewRoomActivity.RESULT_ROOM_ID, 0);
                    loadRooms(id > 0 ? id : null);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_add_sensor);
        ScreenHeader.bindSub(this, R.string.sensor_add_title);
        tvBoardTitle = findViewById(R.id.tvBoardTitle);
        tvBoardState = findViewById(R.id.tvBoardState);
        editName = findViewById(R.id.editSensorName);
        tvRoom = findViewById(R.id.tvSensorRoom);

        findViewById(R.id.boardCard).setOnClickListener(v -> pickBoard());
        findViewById(R.id.tvSensorKind).setOnClickListener(v -> pickKind());
        tvRoom.setOnClickListener(v -> pickRoom());
        findViewById(R.id.btnAddSensor).setOnClickListener(v -> save());

        String preset = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        if (preset != null) {
            setBoard(preset, getString(R.string.sensor_board_found));
        } else {
            tvBoardState.setText(R.string.sensor_board_hint);
        }
        loadRooms(null);
    }

    private void setBoard(String id, String state) {
        deviceId = id;
        tvBoardTitle.setText(getString(R.string.sensor_board_title, id));
        tvBoardState.setText(state);
        if (editName.getText().length() == 0) {
            editName.setText(getString(R.string.sensor_name, id));
        }
    }

    // ---------- Плата ----------

    private void pickBoard() {
        ApiClient.get(this).getDevices().enqueue(new Callback<DevicesResponse>() {
            @Override
            public void onResponse(@NonNull Call<DevicesResponse> call, @NonNull Response<DevicesResponse> response) {
                List<String> labels = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                DevicesResponse body = response.body();
                if (response.isSuccessful() && body != null && body.devices != null) {
                    for (DevicesResponse.Device d : body.devices) {
                        // Только свои и ещё никем не закреплённые (чужие сервер и не покажет).
                        ids.add(d.deviceId);
                        labels.add(d.deviceId + " · " + (d.online ? getString(R.string.sensor_ready) : getString(R.string.sensor_offline_short)));
                    }
                }
                labels.add(getString(R.string.sensor_enter_id));
                new AlertDialog.Builder(AddSensorActivity.this)
                        .setTitle(R.string.sensor_board_pick)
                        .setItems(labels.toArray(new String[0]), (d, which) -> {
                            if (which < ids.size()) {
                                setBoard(ids.get(which), labels.get(which).substring(ids.get(which).length() + 3));
                            } else {
                                enterBoardId();
                            }
                        })
                        .show();
            }

            @Override
            public void onFailure(@NonNull Call<DevicesResponse> call, @NonNull Throwable t) {
                Toast.makeText(AddSensorActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void enterBoardId() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.sensor_enter_id_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_enter_id)
                .setMessage(R.string.sensor_enter_id_text)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (id.matches(ID_PATTERN)) {
                        setBoard(id, getString(R.string.sensor_waiting_data));
                    } else {
                        Toast.makeText(this, R.string.sensor_bad_id, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- Показатель ----------

    private void pickKind() {
        // Наша плата измеряет температуру и влажность; остальные показатели из макета —
        // для датчиков, которых пока нет в стенде.
        String[] kinds = {
                getString(R.string.kind_climate),
                getString(R.string.kind_co2_soon),
                getString(R.string.kind_light_soon),
                getString(R.string.kind_motion_soon),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_metric_label)
                .setItems(kinds, (d, which) -> {
                    if (which != 0) {
                        Toast.makeText(this, R.string.kind_not_available, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // ---------- Помещение ----------

    private void loadRooms(Integer select) {
        ApiClient.get(this).getRooms().enqueue(Api.call(this, body -> {
            roomNames.clear();
            roomIds.clear();
            JsonArray rooms = body.has("rooms") ? body.getAsJsonArray("rooms") : new JsonArray();
            for (JsonElement e : rooms) {
                JsonObject r = e.getAsJsonObject();
                if (r.get("own").getAsBoolean()) {
                    roomIds.add(r.get("id").getAsInt());
                    roomNames.add(r.get("name").getAsString());
                }
            }
            if (select != null && roomIds.contains(select)) {
                setRoom(select, roomNames.get(roomIds.indexOf(select)));
            } else if (roomId == null && !roomIds.isEmpty()) {
                setRoom(roomIds.get(0), roomNames.get(0));
            }
        }));
    }

    private void setRoom(Integer id, String name) {
        roomId = id;
        tvRoom.setText(name);
    }

    private void pickRoom() {
        List<String> labels = new ArrayList<>(roomNames);
        labels.add(getString(R.string.sensor_room_none));
        labels.add(getString(R.string.room_add));
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_room_pick)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < roomIds.size()) {
                        setRoom(roomIds.get(which), roomNames.get(which));
                    } else if (which == roomIds.size()) {
                        setRoom(null, getString(R.string.sensor_room_none));
                    } else {
                        newRoom.launch(new Intent(this, NewRoomActivity.class));
                    }
                })
                .show();
    }

    // ---------- Сохранение ----------

    private void save() {
        if (deviceId == null) {
            Toast.makeText(this, R.string.sensor_pick_board_first, Toast.LENGTH_LONG).show();
            pickBoard();
            return;
        }
        String name = editName.getText().toString().trim();
        if (name.isEmpty()) {
            editName.setError(getString(R.string.room_name_empty));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("kind", "climate");
        if (roomId != null) {
            body.addProperty("room_id", roomId);
        }
        ApiClient.get(this).setupDevice(deviceId, body).enqueue(Api.call(this, ok -> {
            // Новый датчик сразу становится основной платой дашборда.
            ServerConfig.save(this, ServerConfig.getBaseUrl(this), deviceId);
            Toast.makeText(this, getString(R.string.sensor_added, name), Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        }));
    }
}
