package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Выбор помещения» (макет): свои помещения с платами. Датчик привязывается к плате
 * выбранного помещения. Отсюда же можно создать помещение. Возвращает EXTRA_ROOM_ID.
 */
public class RoomPickActivity extends AppCompatActivity {
    public static final String EXTRA_ROOM_ID = "room_id";
    public static final String EXTRA_SENSOR_TITLE = "sensor_title";
    public static final String EXTRA_SENSOR_SUB = "sensor_sub";

    private ChoiceList list;
    private Button pick;
    private final List<JsonObject> items = new ArrayList<>();

    private final ActivityResultLauncher<Intent> newRoom = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    load(result.getData().getIntExtra(NewRoomActivity.RESULT_ROOM_ID, -1));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_pick);
        ScreenHeader.bindSub(this, R.string.pr_title);
        SubScreenNav.bind(this);
        ((TextView) findViewById(R.id.tvPickDesc)).setText(R.string.pr_desc);
        ((TextView) findViewById(R.id.tvPickNote)).setText(R.string.pr_note);
        String title = getIntent().getStringExtra(EXTRA_SENSOR_TITLE);
        if (title != null) {
            findViewById(R.id.pickSensorCard).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.tvPickSensor)).setText(title);
            ((TextView) findViewById(R.id.tvPickSensorSub)).setText(getIntent().getStringExtra(EXTRA_SENSOR_SUB));
        }
        Button extra = findViewById(R.id.btnPickExtra);
        extra.setVisibility(View.VISIBLE);
        extra.setOnClickListener(v -> newRoom.launch(new Intent(this, NewRoomActivity.class)));
        pick = findViewById(R.id.btnPick);
        list = new ChoiceList(findViewById(R.id.pickList), i -> updateButton());
        pick.setOnClickListener(v -> {
            int i = list.selected();
            if (list.isEnabled(i)) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_ROOM_ID, items.get(i).get("id").getAsInt()));
                finish();
            }
        });
        updateButton();
        load(getIntent().getIntExtra(EXTRA_ROOM_ID, -1));
    }

    private void load(int current) {
        ApiClient.get(this).getRooms().enqueue(Api.call(this, body -> {
            items.clear();
            list.clear();
            int select = -1;
            for (JsonObject room : Registry.list(Registry.array(body, "rooms"))) {
                if (!Registry.bool(room, "own")) {
                    continue;
                }
                JsonObject board = AddSensorActivity.boardOf(room);
                if (room.get("id").getAsInt() == current && board != null) {
                    select = items.size();
                }
                items.add(room);
                list.add(Api.str(room, "name"),
                        board == null ? getString(R.string.pr_no_board) : Registry.nb(Api.str(board, "title")), board != null);
            }
            ((TextView) findViewById(R.id.tvPickGroup)).setText(getString(R.string.pr_group, items.size()));
            list.select(select >= 0 ? select : list.firstEnabled());
            updateButton();
        }));
    }

    private void updateButton() {
        int i = list.selected();
        boolean ok = list.isEnabled(i);
        pick.setEnabled(ok);
        pick.setAlpha(ok ? 1f : 0.5f);
        pick.setText(ok ? getString(R.string.pr_button, Api.str(items.get(i), "name")) : getString(R.string.pr_button_none));
    }
}
