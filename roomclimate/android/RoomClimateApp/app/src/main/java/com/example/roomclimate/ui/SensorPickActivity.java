package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Выбор датчика» (макет): все датчики из базы сервера, выбрать можно только свободный.
 * Вид, показатель и единицы — из записи сервера. Возвращает EXTRA_SERIAL.
 */
public class SensorPickActivity extends AppCompatActivity {
    public static final String EXTRA_SERIAL = "serial";

    private ChoiceList list;
    private Button pick;
    private final List<JsonObject> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        setContentView(R.layout.activity_pick);
        ScreenHeader.bindSub(this, R.string.ps_title);
        SubScreenNav.bind(this);
        ((TextView) findViewById(R.id.tvPickDesc)).setText(R.string.ps_desc);
        ((TextView) findViewById(R.id.tvPickNote)).setText(R.string.ps_note);
        pick = findViewById(R.id.btnPick);
        list = new ChoiceList(findViewById(R.id.pickList), i -> updateButton());
        pick.setOnClickListener(v -> {
            int i = list.selected();
            if (list.isEnabled(i)) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_SERIAL, Api.str(items.get(i), "serial")));
                finish();
            }
        });
        updateButton();
        load(getIntent().getStringExtra(EXTRA_SERIAL));
    }

    private void load(String current) {
        ApiClient.get(this).getRegistrySensors().enqueue(Api.call(this, body -> {
            items.clear();
            list.clear();
            int free = 0;
            int select = -1;
            for (JsonObject s : Registry.list(Registry.array(body, "sensors"))) {
                String status = Api.str(s, "status");
                String serial = Api.str(s, "serial");
                String sub;
                if ("free".equals(status)) {
                    free++;
                    sub = getString(R.string.ps_free_sub, serial, unitOf(s));
                } else if ("mine".equals(status)) {
                    JsonObject room = Registry.obj(s, "room");
                    sub = getString(R.string.ps_mine_sub, serial,
                            room == null ? getString(R.string.dev_sensor_no_room) : Api.str(room, "name"),
                            Registry.boardTitle(Registry.obj(s, "board")));
                } else {
                    sub = getString(R.string.ps_taken_sub, serial);
                }
                if (serial.equals(current) && "free".equals(status)) {
                    select = items.size();
                }
                items.add(s);
                list.add(Api.str(s, "type_name"), sub, "free".equals(status));
            }
            ((TextView) findViewById(R.id.tvPickGroup)).setText(getString(R.string.ps_counts, items.size(), free));
            list.select(select >= 0 ? select : list.firstEnabled());
            updateButton();
        }));
    }

    /** «лк», «ppm», «°C, %». */
    private String unitOf(JsonObject sensor) {
        StringBuilder out = new StringBuilder();
        for (JsonObject m : Registry.list(Registry.array(sensor, "metrics"))) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append("temperature".equals(Api.str(m, "code")) ? Units.symbol(this) : Api.str(m, "unit"));
        }
        return out.toString();
    }

    private void updateButton() {
        int i = list.selected();
        boolean ok = list.isEnabled(i);
        pick.setEnabled(ok);
        pick.setAlpha(ok ? 1f : 0.5f);
        pick.setText(ok ? getString(R.string.ps_button, Api.str(items.get(i), "serial")) : getString(R.string.ps_button_none));
    }
}
