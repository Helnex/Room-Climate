package com.example.roomclimate.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

/** «Новое помещение» (макет, экран 10): название → POST /rooms. */
public class NewRoomActivity extends AppCompatActivity {
    public static final String RESULT_ROOM_ID = "room_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_new_room);
        ScreenHeader.bindSub(this, R.string.room_new_title);
        EditText name = findViewById(R.id.editRoomName);
        Button create = findViewById(R.id.btnCreateRoom);
        create.setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError(getString(R.string.room_name_empty));
                return;
            }
            create.setEnabled(false);
            JsonObject body = new JsonObject();
            body.addProperty("name", value);
            ApiClient.get(this).createRoom(body).enqueue(Api.call(this, room -> {
                Toast.makeText(this, getString(R.string.room_created, value), Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, getIntent().putExtra(RESULT_ROOM_ID,
                        room.has("id") ? room.get("id").getAsInt() : 0));
                finish();
            }));
            create.postDelayed(() -> create.setEnabled(true), 1500);
        });
    }
}
