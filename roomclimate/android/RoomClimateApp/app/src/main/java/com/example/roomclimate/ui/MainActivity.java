package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.AuthRequest;
import com.example.roomclimate.models.AuthResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.AppPermissions;
import com.example.roomclimate.network.ServerDiscovery;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private EditText editEmail;
    private EditText editPassword;
    private Button btnLogin;
    private TextView tvServerStatus;
    private boolean serverReady;
    private boolean loginQueued;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (UserPrefs.isLoggedIn(this)) {
            openDashboard();
            return;
        }

        setContentView(R.layout.activity_main);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        Button btnGoToRegister = findViewById(R.id.btnGoToRegister);

        AppPermissions.request(this);
        discoverServer();

        btnLogin.setOnClickListener(v -> attemptLogin());
        btnGoToRegister.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, RegisterActivity.class)));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AppPermissions.REQUEST_CODE && !serverReady) {
            discoverServer();
        }
    }

    private void discoverServer() {
        tvServerStatus.setText(R.string.server_searching);
        tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_text));
        ServerDiscovery.ensure(this, url -> {
            if (url == null) {
                serverReady = false;
                tvServerStatus.setText(R.string.server_not_found);
                tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.alert_text));
                if (loginQueued) {
                    loginQueued = false;
                    setLoading(false);
                }
                return;
            }
            serverReady = true;
            tvServerStatus.setText(R.string.server_ready);
            tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.green_primary));
            if (loginQueued) {
                loginQueued = false;
                attemptLogin();
            }
        });
    }

    private void attemptLogin() {
        String email = editEmail.getText().toString().trim();
        // Пароль не обрезаем: пробелы — допустимые символы пароля.
        String password = editPassword.getText().toString();
        // Проверяем поля до поиска сервера: раньше при пустых полях кнопка навсегда
        // оставалась в состоянии «Подождите…».
        if (email.isEmpty() || password.isEmpty()) {
            setLoading(false);
            Toast.makeText(this, R.string.auth_fill_login, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serverReady) {
            loginQueued = true;
            setLoading(true);
            discoverServer();
            return;
        }

        setLoading(true);
        ApiClient.get(this).login(AuthRequest.login(email, password)).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(@NonNull Call<AuthResponse> call,
                                   @NonNull Response<AuthResponse> response) {
                setLoading(false);
                if (!response.isSuccessful() || response.body() == null || response.body().token == null) {
                    Toast.makeText(
                            MainActivity.this,
                            ApiErrors.message(response, getString(R.string.auth_login_failed)),
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                UserPrefs.save(MainActivity.this, response.body());
                openDashboard();
            }

            @Override
            public void onFailure(@NonNull Call<AuthResponse> call, @NonNull Throwable t) {
                setLoading(false);
                Toast.makeText(MainActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        btnLogin.setText(loading ? R.string.auth_loading : R.string.btn_login);
    }
}
