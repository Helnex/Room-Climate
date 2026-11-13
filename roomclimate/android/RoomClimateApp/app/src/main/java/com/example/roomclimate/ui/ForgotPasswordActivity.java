package com.example.roomclimate.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.ServerDiscovery;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * «Забыли пароль?»: шаг 1 — email и «Получить код» (код приходит на почту, а на учебном
 * стенде без почты его выдаёт администратор); шаг 2 — код и новый пароль.
 * После смены пароля все старые входы закрываются, пользователь входит заново.
 */
public class ForgotPasswordActivity extends AppCompatActivity {
    public static final String EXTRA_EMAIL = "email";

    private EditText editEmail;
    private EditText editCode;
    private EditText editPassword;
    private Button btnSend;
    private Button btnReset;
    private View stepReset;
    private TextView tvInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);
        editEmail = findViewById(R.id.editForgotEmail);
        editCode = findViewById(R.id.editResetCode);
        editPassword = findViewById(R.id.editNewPassword);
        btnSend = findViewById(R.id.btnSendCode);
        btnReset = findViewById(R.id.btnResetPassword);
        stepReset = findViewById(R.id.stepReset);
        tvInfo = findViewById(R.id.tvForgotInfo);
        PasswordToggle.attach(editPassword, (ImageButton) findViewById(R.id.btnToggleNewPassword));

        String email = getIntent().getStringExtra(EXTRA_EMAIL);
        if (email != null) {
            editEmail.setText(email);
        }
        btnSend.setOnClickListener(v -> sendCode());
        btnReset.setOnClickListener(v -> resetPassword());
        findViewById(R.id.tvBackToLogin).setOnClickListener(v -> finish());
    }

    private void sendCode() {
        String email = editEmail.getText().toString().trim();
        if (!AuthValidation.isEmail(email)) {
            editEmail.setError(getString(R.string.auth_bad_email));
            return;
        }
        btnSend.setEnabled(false);
        // Сервер мог ещё не быть найден (экран открыт сразу со входа).
        ServerDiscovery.ensure(this, url -> {
            if (url == null) {
                btnSend.setEnabled(true);
                Toast.makeText(this, ServerDiscovery.lastFailureMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("email", email);
            ApiClient.get(this).forgotPassword(body).enqueue(new Callback<JsonObject>() {
                @Override
                public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                    btnSend.setEnabled(true);
                    if (!response.isSuccessful()) {
                        Toast.makeText(ForgotPasswordActivity.this,
                                ApiErrors.message(response, getString(R.string.forgot_failed)), Toast.LENGTH_LONG).show();
                        return;
                    }
                    btnSend.setText(R.string.btn_send_code_again);
                    tvInfo.setText(R.string.forgot_code_sent);
                    stepReset.setVisibility(View.VISIBLE);
                    editCode.requestFocus();
                }

                @Override
                public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                    btnSend.setEnabled(true);
                    Toast.makeText(ForgotPasswordActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void resetPassword() {
        String email = editEmail.getText().toString().trim();
        String code = editCode.getText().toString().trim();
        String password = editPassword.getText().toString();
        if (!code.matches("\\d{6}")) {
            editCode.setError(getString(R.string.reset_code_format));
            return;
        }
        if (password.length() < 6 || password.trim().isEmpty()) {
            editPassword.setError(getString(R.string.password_rules));
            return;
        }
        btnReset.setEnabled(false);
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("code", code);
        body.addProperty("password", password);
        ApiClient.get(this).resetPassword(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                btnReset.setEnabled(true);
                if (!response.isSuccessful()) {
                    Toast.makeText(ForgotPasswordActivity.this,
                            ApiErrors.message(response, getString(R.string.reset_failed)), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(ForgotPasswordActivity.this, R.string.reset_done, Toast.LENGTH_LONG).show();
                finish(); // назад на вход, email уже введён
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                btnReset.setEnabled(true);
                Toast.makeText(ForgotPasswordActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }
}
