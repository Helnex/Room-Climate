package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;

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

        tvFullName.setText((name + " " + surname).trim());
        tvEmail.setText(email);

        String initials = "";
        if (!name.isEmpty()) {
            initials += name.substring(0, 1).toUpperCase();
        }
        if (!surname.isEmpty()) {
            initials += surname.substring(0, 1).toUpperCase();
        }
        tvAvatar.setText(initials);

        tvLogout.setOnClickListener(v -> logout());
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

    private void clearAndGoLogin() {
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
