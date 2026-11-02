package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.models.AuthRequest;
import com.example.roomclimate.models.AuthResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.network.AppPermissions;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RegisterActivity extends AppCompatActivity {
    private EditText editName;
    private EditText editSurname;
    private EditText editEmailReg;
    private EditText editPasswordReg;
    private Button btnCreateAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        TextView tvGoToLogin = findViewById(R.id.tvGoToLogin);
        editName = findViewById(R.id.editName);
        editSurname = findViewById(R.id.editSurname);
        editEmailReg = findViewById(R.id.editEmailReg);
        editPasswordReg = findViewById(R.id.editPasswordReg);
        btnCreateAccount = findViewById(R.id.btnCreateAccount);

        AppPermissions.request(this);
        tvGoToLogin.setOnClickListener(v -> finish());
        btnCreateAccount.setOnClickListener(v -> attemptRegister());
    }

    private void attemptRegister() {
        String name = editName.getText().toString().trim();
        String surname = editSurname.getText().toString().trim();
        String email = editEmailReg.getText().toString().trim();
        String password = editPasswordReg.getText().toString().trim();

        if (name.isEmpty() || surname.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.auth_fill_register, Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, R.string.auth_password_short, Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        ApiClient.get(this)
                .register(AuthRequest.register(name, surname, email, password))
                .enqueue(new Callback<AuthResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<AuthResponse> call,
                                           @NonNull Response<AuthResponse> response) {
                        setLoading(false);
                        if (!response.isSuccessful() || response.body() == null
                                || response.body().token == null) {
                            Toast.makeText(
                                    RegisterActivity.this,
                                    ApiErrors.message(response, getString(R.string.auth_register_failed)),
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }
                        UserPrefs.save(RegisterActivity.this, response.body());
                        Intent intent = new Intent(RegisterActivity.this, DashboardActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }

                    @Override
                    public void onFailure(@NonNull Call<AuthResponse> call, @NonNull Throwable t) {
                        setLoading(false);
                        Toast.makeText(RegisterActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean loading) {
        btnCreateAccount.setEnabled(!loading);
        btnCreateAccount.setText(loading ? R.string.auth_loading : R.string.btn_create_account);
    }
}
