package com.example.roomclimate.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Новое помещение» (макет): название + плата из базы сервера → POST /rooms {name, board_id}.
 * Свободные платы можно выбрать, занятые показаны для справки. Платы заводятся миграциями.
 */
public class NewRoomActivity extends AppCompatActivity {
    public static final String RESULT_ROOM_ID = "room_id";

    private EditText name;
    private Button create;
    private TextView note;
    private ChoiceList boards;
    private final List<JsonObject> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_new_room);
        ScreenHeader.bindSub(this, R.string.room_new_title);
        BottomNav.bind(this, R.id.navDevices);
        name = findViewById(R.id.editRoomName);
        create = findViewById(R.id.btnCreateRoom);
        note = findViewById(R.id.tvCreateNote);
        boards = new ChoiceList(findViewById(R.id.boardsList), i -> updateNote());
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                updateNote();
            }
        });
        create.setOnClickListener(v -> save());
        ((TextView) findViewById(R.id.tvBoardsGroup)).setText(getString(R.string.nr_boards_group, 0));
        loadBoards();
    }

    private void loadBoards() {
        ApiClient.get(this).getRegistryBoards().enqueue(Api.call(this, body -> {
            items.clear();
            boards.clear();
            for (JsonObject b : Registry.list(Registry.array(body, "boards"))) {
                items.add(b);
                String status = Api.str(b, "status");
                JsonObject room = Registry.obj(b, "room");
                String sub;
                if (Registry.bool(b, "selectable")) {
                    sub = getString("mine".equals(status) ? R.string.board_mine_free : R.string.board_free);
                } else if (room != null) {
                    sub = getString(R.string.board_taken_room, Api.str(room, "name"));
                } else {
                    sub = getString(R.string.board_taken);
                }
                boards.add(Api.str(b, "title"), sub, Registry.bool(b, "selectable"));
            }
            ((TextView) findViewById(R.id.tvBoardsGroup)).setText(getString(R.string.nr_boards_group, items.size()));
            int first = boards.firstEnabled();
            if (first >= 0) {
                boards.select(first);
            }
            ((TextView) findViewById(R.id.tvBoardsNote)).setText(first < 0 ? R.string.nr_no_free : R.string.nr_taken_note);
            updateNote();
        }));
    }

    private JsonObject selectedBoard() {
        int i = boards.selected();
        return boards.isEnabled(i) ? items.get(i) : null;
    }

    private void updateNote() {
        JsonObject board = selectedBoard();
        String value = name.getText().toString().trim();
        create.setEnabled(board != null);
        create.setAlpha(board != null ? 1f : 0.5f);
        if (board == null) {
            note.setText(R.string.nr_pick_board);
        } else if (value.isEmpty()) {
            note.setText(R.string.nr_note_no_name);
        } else {
            note.setText(getString(R.string.nr_note, Api.str(board, "title"), value));
        }
    }

    private void save() {
        String value = name.getText().toString().trim();
        if (value.isEmpty()) {
            name.setError(getString(R.string.room_name_empty));
            return;
        }
        JsonObject board = selectedBoard();
        if (board == null) {
            Toast.makeText(this, R.string.nr_pick_board, Toast.LENGTH_LONG).show();
            return;
        }
        create.setEnabled(false);
        JsonObject body = new JsonObject();
        body.addProperty("name", value);
        body.addProperty("board_id", Api.str(board, "device_id"));
        ApiClient.get(this).createRoom(body).enqueue(Api.call(this, room -> {
            Toast.makeText(this, getString(R.string.nr_created, value), Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK, getIntent().putExtra(RESULT_ROOM_ID,
                    room.has("id") ? room.get("id").getAsInt() : 0));
            finish();
        }));
        create.postDelayed(() -> create.setEnabled(selectedBoard() != null), 1500);
    }
}
