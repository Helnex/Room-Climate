package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.notify.ClimateMonitorService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

        TextView tvLogout = findViewById(R.id.tvLogout);

        bindProfile();

        tvLogout.setOnClickListener(v -> logout());
        findViewById(R.id.btnHeaderBell).setOnClickListener(v ->
                startActivity(new Intent(this, NotificationsActivity.class)));
        // Строки из макета, которые раньше никуда не вели: теперь открывают свои экраны.
        findViewById(R.id.tvEditProfile).setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        findViewById(R.id.rowFamily).setOnClickListener(v -> startActivity(new Intent(this, FamilyActivity.class)));
        findViewById(R.id.rowSecurity).setOnClickListener(v -> startActivity(new Intent(this, SecurityActivity.class)));
        findViewById(R.id.rowHelp).setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.cardMyHome).setOnClickListener(v -> startActivity(new Intent(this, DevicesActivity.class)));
        findViewById(R.id.tvDeleteAccount).setOnClickListener(v -> confirmDeleteAccount());
        Switch switchAlerts = findViewById(R.id.switchClimateAlerts);
        switchAlerts.setChecked(UserPrefs.alertsEnabled(this));
        switchAlerts.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            if (button.getTag() != null) {
                return; // синхронизация из onResume, а не нажатие
            }
            UserPrefs.setAlertsEnabled(AccountActivity.this, checked);
            bindAlertsSubtitle(checked);
            if (checked) {
                ClimateMonitorService.start(AccountActivity.this);
            } else {
                ClimateMonitorService.stop(AccountActivity.this);
            }
        });
        BottomNav.bind(this, R.id.navAccount);
    }

    private void bindProfile() {
        String name = UserPrefs.getName(this);
        String surname = UserPrefs.getSurname(this);
        boolean empty = name.trim().isEmpty() && surname.trim().isEmpty();
        TextView tvFullName = findViewById(R.id.tvFullName);
        TextView tvEmail = findViewById(R.id.tvEmail);
        TextView tvAvatar = findViewById(R.id.tvAvatar);
        ImageView emptyAvatar = findViewById(R.id.ivAvatarEmpty);
        if (empty) {
            tvFullName.setText(R.string.profile_placeholder_name);
            tvEmail.setText(R.string.profile_placeholder_email);
            tvAvatar.setVisibility(View.GONE);
            emptyAvatar.setVisibility(View.VISIBLE);
        } else {
            tvFullName.setText(getString(R.string.full_name, name, surname).trim());
            tvEmail.setText(UserPrefs.getEmail(this));
            tvAvatar.setText(Formatters.initials(name, surname));
            tvAvatar.setVisibility(View.VISIBLE);
            emptyAvatar.setVisibility(View.GONE);
        }
    }

    private void bindAlertsSubtitle(boolean enabled) {
        TextView desc = findViewById(R.id.tvAlertsDesc);
        if (desc != null) {
            desc.setText(enabled ? R.string.climate_notifications_desc : R.string.climate_notifications_off);
        }
    }

    private void loadSummary() {
        ApiClient.get(this).getRooms().enqueue(Api.call(this, rooms ->
                ApiClient.get(this).getFamily().enqueue(Api.call(this, family -> showSummary(rooms, family)))));
    }

    private void showSummary(JsonObject rooms, JsonObject family) {
        int roomCount = rooms.has("rooms") ? rooms.getAsJsonArray("rooms").size() : 0;
        int sensors = 0;
        if (rooms.has("rooms")) {
            for (JsonElement room : rooms.getAsJsonArray("rooms")) {
                JsonArray devices = room.getAsJsonObject().getAsJsonArray("devices");
                if (devices != null) {
                    sensors += devices.size();
                }
            }
        }
        if (rooms.has("unassigned")) {
            sensors += rooms.getAsJsonArray("unassigned").size();
        }
        int people = family.has("people") ? family.getAsJsonArray("people").size() : 1;
        String mode = Api.str(family, "mode");
        if (mode.isEmpty()) {
            mode = UserPrefs.usageMode(this);
        }
        boolean warehouse = "warehouse".equals(mode);
        boolean member = "member".equals(Api.str(family, "role"));
        TextView title = findViewById(R.id.tvHomeTitle);
        TextView desc = findViewById(R.id.tvHomeDesc);
        title.setText(warehouse ? R.string.my_warehouses : R.string.my_home);
        String peoplePart = people <= 1 ? "только вы" : count(people, "участник", "участника", "участников");
        String placePart = roomCount == 0
                ? "0 помещений"
                : (warehouse
                ? count(roomCount, "склад", "склада", "складов")
                : count(roomCount, "комната", "комнаты", "комнат"));
        String sensorPart = sensorLine(sensors);
        desc.setText(placePart + " · " + sensorPart + " · " + peoplePart);
        TextView access = findViewById(R.id.tvAccessDesc);
        if (member) {
            access.setText(warehouse ? R.string.access_member_warehouse : R.string.access_member_home);
        } else if (people <= 1) {
            access.setText(warehouse ? R.string.access_empty_warehouse : R.string.access_empty_home);
        } else {
            access.setText(getString(R.string.access_people, people - 1));
        }
    }

    private static String sensorLine(int n) {
        if (n == 0) {
            return "0 датчиков";
        }
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod10 == 1 && mod100 != 11) {
            return n + " базовый датчик";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 11 || mod100 > 14)) {
            return n + " базовых датчика";
        }
        return n + " базовых датчиков";
    }

    private static String count(int n, String one, String few, String many) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return n + " " + many;
        }
        if (mod10 == 1) {
            return n + " " + one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return n + " " + few;
        }
        return n + " " + many;
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

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из «Профиля» — показать новое имя и почту.
        if (UserPrefs.isLoggedIn(this)) {
            bindProfile();
            loadSummary();
        }
        bindAlertsSubtitle(UserPrefs.alertsEnabled(this));
        // Выключатель мог смениться в «Настройки → Уведомления»: раньше здесь оставалось старое
        // положение, и следующее нажатие делало обратное ожидаемому.
        Switch switchAlerts = findViewById(R.id.switchClimateAlerts);
        if (switchAlerts != null && switchAlerts.isChecked() != UserPrefs.alertsEnabled(this)) {
            switchAlerts.setTag(Boolean.TRUE);
            switchAlerts.setChecked(UserPrefs.alertsEnabled(this));
            switchAlerts.setTag(null);
        }
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
                                                    getString(R.string.request_failed)), Toast.LENGTH_LONG).show();
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
