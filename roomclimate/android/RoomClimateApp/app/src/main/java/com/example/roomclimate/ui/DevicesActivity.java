package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Устройства» по макету Figma. Помещения (у каждого — плата из базы сервера) и датчики,
 * привязанные к платам. Платы и датчики заводятся в базу сервера миграциями, здесь их только
 * выбирают: «Добавить помещение» → свободная плата, «Добавить датчик» → свободный датчик.
 * Нажатие на помещение — показать его на дашборде, состояние платы, переименовать, удалить.
 */
public class DevicesActivity extends AppCompatActivity {
    private LinearLayout roomsList;
    private LinearLayout sensorsList;
    private TextView tvSummary;
    private TextView tvSummaryStatus;
    private JsonArray rooms = new JsonArray();
    private List<JsonObject> sensors = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_devices);
        BottomNav.bind(this, R.id.navDevices);
        roomsList = findViewById(R.id.roomsList);
        sensorsList = findViewById(R.id.sensorsList);
        tvSummary = findViewById(R.id.tvSummary);
        tvSummaryStatus = findViewById(R.id.tvSummaryStatus);

        View.OnClickListener addRoom = v -> startActivity(new Intent(this, NewRoomActivity.class));
        findViewById(R.id.btnAddRoom).setOnClickListener(addRoom);
        findViewById(R.id.btnAddRoomEmpty).setOnClickListener(addRoom);
        findViewById(R.id.btnAddSensor).setOnClickListener(v -> startActivity(new Intent(this, AddSensorActivity.class)));
        findViewById(R.id.btnBell).setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (roomsList != null) {
            load();
        }
    }

    private void load() {
        ApiClient.get(this).getRooms().enqueue(Api.call(this, roomsBody -> {
            rooms = Registry.array(roomsBody, "rooms");
            ApiClient.get(this).getRegistrySensors().enqueue(Api.call(this, sensorsBody -> {
                sensors.clear();
                for (JsonObject s : Registry.list(Registry.array(sensorsBody, "sensors"))) {
                    if ("mine".equals(Api.str(s, "status"))) {
                        sensors.add(s);
                    }
                }
                render();
            }));
        }));
    }

    // ---------- Отрисовка ----------

    private void render() {
        int boards = 0;
        for (JsonObject room : Registry.list(rooms)) {
            if (board(room) != null) {
                boards++;
            }
        }
        tvSummary.setText(getString(R.string.dev_summary,
                getResources().getQuantityString(R.plurals.count_rooms, rooms.size(), rooms.size()),
                getResources().getQuantityString(R.plurals.count_boards, boards, boards),
                getResources().getQuantityString(R.plurals.count_sensors, sensors.size(), sensors.size())));
        int offline = 0;
        for (JsonObject s : sensors) {
            if (!Registry.bool(s, "online")) {
                offline++;
            }
        }
        tvSummaryStatus.setVisibility(sensors.isEmpty() ? View.GONE : View.VISIBLE);
        tvSummaryStatus.setText(offline == 0 ? getString(R.string.dev_all_online) : getString(R.string.dev_some_offline, offline));
        tvSummaryStatus.setTextColor(ContextCompat.getColor(this, offline == 0 ? R.color.green_primary : R.color.gray_text));
        tvSummaryStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                offline == 0 ? R.drawable.rc_dot_small_green : R.drawable.rc_dot_small_gray, 0, 0, 0);

        boolean noRooms = rooms.size() == 0;
        findViewById(R.id.cardEmptyRooms).setVisibility(noRooms ? View.VISIBLE : View.GONE);
        findViewById(R.id.roomsBlock).setVisibility(noRooms ? View.GONE : View.VISIBLE);
        findViewById(R.id.cardEmptySensors).setVisibility(sensors.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvEmptySensors)).setText(
                noRooms ? R.string.dev_empty_sensors_no_rooms : R.string.dev_empty_sensors_text);
        findViewById(R.id.sensorsBlock).setVisibility(sensors.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.addSensorBlock).setVisibility(noRooms ? View.GONE : View.VISIBLE);

        renderRooms();
        renderSensors();
    }

    /** Плата помещения: зарегистрированная в базе сервера из devices[] ответа /rooms. */
    private static JsonObject board(JsonObject room) {
        return AddSensorActivity.boardOf(room);
    }

    private void renderRooms() {
        roomsList.removeAllViews();
        ((TextView) findViewById(R.id.tvRoomsCount)).setText(String.valueOf(rooms.size()));
        String active = ServerConfig.getDeviceId(this);
        for (JsonObject room : Registry.list(rooms)) {
            int id = room.get("id").getAsInt();
            String name = Api.str(room, "name");
            JsonObject board = board(room);
            String boardId = board == null ? null : Api.str(board, "device_id");
            int count = 0;
            for (JsonObject s : sensors) {
                if (Registry.roomId(s) == id) {
                    count++;
                }
            }
            StringBuilder sub = new StringBuilder(getResources().getQuantityString(R.plurals.count_sensors, count, count));
            if (board != null) {
                Double t = Registry.number(board, "temperature");
                Double h = Registry.number(board, "humidity");
                if (t != null) {
                    sub.append(" · ").append(Units.temperature(this, t));
                }
                if (h != null) {
                    sub.append(" · ").append(Math.round(h)).append(" %");
                }
                if (boardId.equals(active)) {
                    sub.append(" · ").append(getString(R.string.dev_on_dashboard));
                }
            }
            String title = getString(R.string.dev_room_title, name,
                    board == null ? getString(R.string.dev_room_no_board) : Api.str(board, "title"));
            boolean own = Registry.bool(room, "own");
            View row = addRow(roomsList, R.drawable.ic_door, title, sub, null, false,
                    v -> roomActions(room, board, own));
            if (own && board != null) {
                ImageButton add = row.findViewById(R.id.regAdd);
                add.setVisibility(View.VISIBLE);
                add.setContentDescription(getString(R.string.a11y_add_sensor_to, name));
                add.setOnClickListener(v -> startActivity(new Intent(this, AddSensorActivity.class)
                        .putExtra(AddSensorActivity.EXTRA_ROOM_ID, id)));
            }
        }
    }

    private void renderSensors() {
        sensorsList.removeAllViews();
        ((TextView) findViewById(R.id.tvSensorsCount)).setText(String.valueOf(sensors.size()));
        for (JsonObject s : sensors) {
            JsonObject room = Registry.obj(s, "room");
            JsonObject board = Registry.obj(s, "board");
            String sub = getString(R.string.dev_sensor_sub,
                    room == null ? getString(R.string.dev_sensor_no_room) : Api.str(room, "name"),
                    Api.str(s, "serial"), Api.str(s, "short_name"));
            boolean online = Registry.bool(s, "online");
            StringBuilder status = new StringBuilder(getString(online ? R.string.dev_online : R.string.dev_no_data));
            String values = Registry.values(this, s);
            if (!values.isEmpty()) {
                status.append(" · ").append(values);
            }
            if (board != null) {
                status.append(" · ").append(Registry.boardTitle(board));
            }
            View row = addRow(sensorsList, Registry.icon(Api.str(s, "type")), Api.str(s, "name"), sub,
                    status, online, v -> sensorActions(s));
            row.findViewById(R.id.regChevron).setVisibility(View.VISIBLE);
        }
    }

    private View addRow(LinearLayout parent, int icon, CharSequence title, CharSequence sub,
                        CharSequence status, boolean online, View.OnClickListener onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_registry_row, parent, false);
        ((ImageView) row.findViewById(R.id.regIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.regTitle)).setText(title);
        ((TextView) row.findViewById(R.id.regSub)).setText(sub);
        TextView st = row.findViewById(R.id.regStatus);
        if (status != null) {
            st.setVisibility(View.VISIBLE);
            st.setText(status);
            st.setTextColor(ContextCompat.getColor(this, online ? R.color.green_primary : R.color.gray_text));
            st.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    online ? R.drawable.rc_dot_small_green : R.drawable.rc_dot_small_gray, 0, 0, 0);
        }
        row.setOnClickListener(onClick);
        row.setContentDescription(title + ". " + sub + (status == null ? "" : ". " + status));
        parent.addView(row);
        return row;
    }

    // ---------- Действия с помещением ----------

    private void roomActions(JsonObject room, JsonObject board, boolean own) {
        int id = room.get("id").getAsInt();
        String name = Api.str(room, "name");
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (board != null) {
            String boardId = Api.str(board, "device_id");
            labels.add(getString(R.string.room_action_dashboard));
            actions.add(() -> {
                ServerConfig.save(this, ServerConfig.getBaseUrl(this), boardId);
                Toast.makeText(this, getString(R.string.dashboard_selected, name), Toast.LENGTH_SHORT).show();
                render();
            });
            labels.add(getString(R.string.room_action_status));
            actions.add(() -> showBoardStatus(boardId));
        }
        if (own) {
            if (board != null) {
                labels.add(getString(R.string.room_action_add_sensor));
                actions.add(() -> startActivity(new Intent(this, AddSensorActivity.class)
                        .putExtra(AddSensorActivity.EXTRA_ROOM_ID, id)));
            }
            labels.add(getString(R.string.room_rename));
            actions.add(() -> renameRoom(id, name));
            labels.add(getString(R.string.room_delete));
            actions.add(() -> deleteRoom(id, name));
        }
        if (labels.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showBoardStatus(String boardId) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad / 2, pad, 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(card)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        BoardStatusCard.load(this, card, boardId, () -> {
            if (dialog.isShowing()) {
                dialog.dismiss();
                new AlertDialog.Builder(this)
                        .setTitle(R.string.room_action_status)
                        .setMessage(R.string.room_status_missing)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    private void renameRoom(int id, String name) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.room_rename)
                .setView(input)
                .setPositiveButton(R.string.save_changes, (d, w) -> {
                    JsonObject body = new JsonObject();
                    body.addProperty("name", input.getText().toString().trim());
                    ApiClient.get(this).renameRoom(id, body).enqueue(Api.call(this, ok -> load()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteRoom(int id, String name) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.room_delete_title, name))
                .setMessage(R.string.room_delete_text)
                .setPositiveButton(R.string.room_delete, (d, w) ->
                        ApiClient.get(this).deleteRoom(id).enqueue(Api.call(this, ok -> load())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- Действия с датчиком ----------

    private void sensorActions(JsonObject s) {
        String serial = Api.str(s, "serial");
        String name = Api.str(s, "name");
        int roomId = Registry.roomId(s);
        JsonObject board = Registry.obj(s, "board");
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (roomId > 0) {
            labels.add(getString(R.string.sensor_action_rename));
            actions.add(() -> renameSensor(serial, name, roomId));
        }
        if (board != null && !"climate".equals(Api.str(s, "type"))) {
            // CO₂ / свет / движение — отдельная карточка на дашборде.
            labels.add(getString(R.string.sensor_action_dashboard));
            actions.add(() -> {
                for (JsonObject m : Registry.list(Registry.array(s, "metrics"))) {
                    Metrics.add(this, Api.str(board, "device_id"), Api.str(m, "code"));
                }
                Toast.makeText(this, getString(R.string.metric_added, name), Toast.LENGTH_SHORT).show();
            });
        }
        labels.add(getString(R.string.sensor_action_unbind));
        actions.add(() -> new AlertDialog.Builder(this)
                .setTitle(getString(R.string.sensor_unbind_title, name))
                .setMessage(R.string.sensor_unbind_text)
                .setPositiveButton(R.string.sensor_unbind_ok, (d, w) ->
                        ApiClient.get(this).unbindSensor(serial).enqueue(Api.call(this, ok -> load())))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void renameSensor(String serial, String name, int roomId) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_action_rename)
                .setView(input)
                .setPositiveButton(R.string.save_changes, (d, w) -> {
                    JsonObject body = new JsonObject();
                    body.addProperty("room_id", roomId);
                    body.addProperty("name", input.getText().toString().trim());
                    ApiClient.get(this).bindSensor(serial, body).enqueue(Api.call(this, ok -> load()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
