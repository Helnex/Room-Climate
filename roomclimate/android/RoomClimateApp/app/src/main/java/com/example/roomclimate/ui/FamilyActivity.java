package com.example.roomclimate.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * «Семейный доступ» (макет, экран 17): кто видит дом — владелец и участники.
 * Приглашение даёт доступ сразу ко всем платам владельца (поверх доступа по email к одной
 * плате на экране «Устройства»). Нажатие на участника — убрать доступ.
 */
public class FamilyActivity extends AppCompatActivity {
    private SectionScreen screen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        screen = SectionScreen.setUp(this, R.string.family_title, R.string.family_heading, R.string.family_desc);
        screen.button(R.string.family_invite, v -> invite());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen != null) {
            load();
        }
    }

    private void load() {
        ApiClient.get(this).getFamily().enqueue(Api.call(this, body -> {
            screen.card.removeAllViews();
            for (JsonElement e : body.getAsJsonArray("people")) {
                JsonObject p = e.getAsJsonObject();
                boolean owner = "owner".equals(Api.str(p, "role"));
                String name = (Api.str(p, "name") + " " + Api.str(p, "surname")).trim();
                String role = getString(owner ? R.string.family_owner : R.string.family_member);
                String email = Api.str(p, "email");
                Rows.add(screen.card, owner ? R.drawable.ic_nav_person : R.drawable.ic_people,
                        name.isEmpty() ? email : name, role + " · " + email,
                        owner ? null : v -> confirmRemove(name.isEmpty() ? email : name, email));
            }
            int devices = body.has("devices") ? body.get("devices").getAsInt() : 0;
            screen.note(devices == 0 ? getString(R.string.family_no_devices)
                    : getResources().getQuantityString(R.plurals.family_devices, devices, devices));
        }));
    }

    private void invite() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint(R.string.share_email_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.family_invite)
                .setMessage(R.string.family_invite_text)
                .setView(input)
                .setPositiveButton(R.string.share_ok, (d, w) -> {
                    JsonObject body = new JsonObject();
                    body.addProperty("email", input.getText().toString().trim());
                    ApiClient.get(this).inviteFamily(body).enqueue(Api.call(this, ok -> {
                        Toast.makeText(this, R.string.share_done, Toast.LENGTH_SHORT).show();
                        load();
                    }));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(String who, String email) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.family_remove_title, who))
                .setMessage(R.string.family_remove_text)
                .setPositiveButton(R.string.family_remove_ok, (d, w) ->
                        ApiClient.get(this).removeFamily(email).enqueue(Api.call(this, ok -> load())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
