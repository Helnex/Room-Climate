package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.notify.ClimateMonitorService;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AccountActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        if (!UserPrefs.isLoggedIn(this)) {
            goLogin();
            return;
        }

        TextView tvFullName = findViewById(R.id.tvFullName);
        TextView tvEmail = findViewById(R.id.tvEmail);
        TextView tvAvatar = findViewById(R.id.tvAvatar);
        TextView tvLogout = findViewById(R.id.tvLogout);

        String name = UserPrefs.getName(this);
        String surname = UserPrefs.getSurname(this);
        String email = UserPrefs.getEmail(this);

        tvFullName.setText(getString(R.string.full_name, name, surname).trim());
        tvEmail.setText(email);

        tvAvatar.setText(Formatters.initials(name, surname));

        tvLogout.setOnClickListener(v -> logout());
        findViewById(R.id.tvDeleteAccount).setOnClickListener(v -> confirmDeleteAccount());
        Switch switchAlerts = findViewById(R.id.switchClimateAlerts);
        switchAlerts.setChecked(UserPrefs.alertsEnabled(this));
        switchAlerts.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            UserPrefs.setAlertsEnabled(AccountActivity.this, checked);
            if (checked) {
                ClimateMonitorService.start(AccountActivity.this);
            } else {
                ClimateMonitorService.stop(AccountActivity.this);
            }
        });
        BottomNav.bind(this, R.id.navAccount);
    }

    private void logout() {
        String token = UserPrefs.getToken(this);
        if (token == null || token.isEmpty()) {
            clearAndGoLogin();
            return;
        }
        ApiClient.get(this).logout("Bearer " + token).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                clearAndGoLogin();
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                Toast.makeText(AccountActivity.this, R.string.auth_logout_local, Toast.LENGTH_SHORT).show();
                clearAndGoLogin();
            }
        });
    }

    /** Удаление аккаунта и персональных данных на сервере (DELETE /auth/me) с подтверждением. */
    private void confirmDeleteAccount() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_account_title)
                .setMessage(R.string.delete_account_text)
                .setPositiveButton(R.string.delete_account_ok, (d, w) ->
                        ApiClient.get(this).deleteAccount().enqueue(new Callback<com.google.gson.JsonObject>() {
                            @Override
                            public void onResponse(@NonNull Call<com.google.gson.JsonObject> call,
                                                   @NonNull Response<com.google.gson.JsonObject> response) {
                                if (response.isSuccessful()) {
                                    Toast.makeText(AccountActivity.this, R.string.delete_account_done, Toast.LENGTH_LONG).show();
                                    clearAndGoLogin();
                                } else {
                                    Toast.makeText(AccountActivity.this,
                                            com.example.roomclimate.network.ApiErrors.message(response,
                                                    getString(R.string.delete_account_title)), Toast.LENGTH_LONG).show();
                                }
                            }

                            @Override
                            public void onFailure(@NonNull Call<com.google.gson.JsonObject> call, @NonNull Throwable t) {
                                Toast.makeText(AccountActivity.this,
                                        com.example.roomclimate.network.ApiErrors.network(t), Toast.LENGTH_LONG).show();
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearAndGoLogin() {
        ClimateMonitorService.stop(this);
        UserPrefs.clear(this);
        goLogin();
    }

    private void goLogin() {
        Intent logoutIntent = new Intent(AccountActivity.this, MainActivity.class);
        logoutIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(logoutIntent);
        finish();
    }
}
