package com.example.roomclimate.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

/** «Профиль» (макет, экран 12): имя, фамилия, почта → PATCH /auth/me. */
public class ProfileActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        SectionScreen s = SectionScreen.setUp(this, R.string.profile_title, R.string.profile_heading, R.string.profile_desc);
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
        EditText name = s.field(R.string.field_name, text, UserPrefs.getName(this));
        EditText surname = s.field(R.string.hint_surname, text, UserPrefs.getSurname(this));
        EditText email = s.field(R.string.hint_email_reg,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, UserPrefs.getEmail(this));
        s.button(R.string.save_changes, v -> {
            String n = name.getText().toString().trim();
            String sn = surname.getText().toString().trim();
            String e = email.getText().toString().trim();
            if (n.isEmpty() || sn.isEmpty()) {
                Toast.makeText(this, R.string.auth_fill_register, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!AuthValidation.isEmail(e)) {
                email.setError(getString(R.string.auth_bad_email));
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("name", n);
            body.addProperty("surname", sn);
            body.addProperty("email", e);
            ApiClient.get(this).updateProfile(body).enqueue(Api.call(this, user -> {
                UserPrefs.updateProfile(this, Api.str(user, "name"), Api.str(user, "surname"), Api.str(user, "email"));
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                finish();
            }));
        });
        s.note(getString(R.string.profile_note));
    }
}
