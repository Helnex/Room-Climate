package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * «Безопасность» (макет, экран 22): сменить пароль, история входов (и «выйти на других
 * устройствах»), история измерений — экспорт на экране «Устройства».
 */
public class SecurityActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        SectionScreen s = SectionScreen.setUp(this, R.string.security_title, R.string.security_heading, R.string.security_desc);
        Rows.add(s.card, R.drawable.ic_shield, R.string.security_password, R.string.security_password_sub, v -> changePassword());
        Rows.add(s.card, R.drawable.ic_calendar, R.string.security_sessions, R.string.security_sessions_sub, v -> showSessions());
        Rows.add(s.card, R.drawable.ic_nav_chart, R.string.security_history, R.string.security_history_sub,
                v -> startActivity(new Intent(this, DevicesActivity.class)));
        s.note(getString(R.string.security_note));
    }

    private void changePassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText old = new EditText(this);
        old.setHint(R.string.security_old_password);
        old.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText fresh = new EditText(this);
        fresh.setHint(R.string.hint_new_password);
        fresh.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(old);
        box.addView(fresh);
        new AlertDialog.Builder(this)
                .setTitle(R.string.security_password)
                .setMessage(R.string.security_password_text)
                .setView(box)
                .setPositiveButton(R.string.btn_reset_password, (d, w) -> {
                    String next = fresh.getText().toString();
                    if (next.length() < 6 || next.trim().isEmpty()) {
                        Toast.makeText(this, R.string.password_rules, Toast.LENGTH_LONG).show();
                        return;
                    }
                    JsonObject body = new JsonObject();
                    body.addProperty("old_password", old.getText().toString());
                    body.addProperty("new_password", next);
                    ApiClient.get(this).changePassword(body).enqueue(Api.call(this, ok ->
                            Toast.makeText(this, Api.str(ok, "detail"), Toast.LENGTH_LONG).show()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSessions() {
        ApiClient.get(this).getSessions().enqueue(Api.call(this, body -> {
            StringBuilder sb = new StringBuilder();
            int others = 0;
            for (JsonElement e : body.getAsJsonArray("sessions")) {
                JsonObject s = e.getAsJsonObject();
                boolean current = s.get("current").getAsBoolean();
                if (!current) {
                    others++;
                }
                sb.append(current ? "● " : "○ ")
                        .append(Formatters.time(Api.str(s, "created_at")))
                        .append('\n').append(Api.str(s, "client"));
                String ip = Api.str(s, "ip");
                if (!ip.isEmpty()) {
                    sb.append(" · ").append(ip);
                }
                if (current) {
                    sb.append(" · ").append(getString(R.string.security_this_device));
                }
                sb.append("\n\n");
            }
            AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.security_sessions)
                    .setMessage(sb.toString().trim())
                    .setNegativeButton(android.R.string.ok, null);
            if (others > 0) {
                dialog.setPositiveButton(R.string.security_logout_others, (d, w) ->
                        ApiClient.get(this).logoutOthers().enqueue(Api.call(this, ok ->
                                Toast.makeText(this, R.string.security_logged_out_others, Toast.LENGTH_LONG).show())));
            }
            dialog.show();
        }));
    }
}
