package com.example.roomclimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class RegisterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        TextView tvGoToLogin = findViewById(R.id.tvGoToLogin);

        // Сюда мы добавили ID для полей ввода в activity_register.xml
        // (Их нужно будет туда добавить, я написал ниже)
        EditText editName = findViewById(R.id.editName);
        EditText editSurname = findViewById(R.id.editSurname);
        EditText editEmailReg = findViewById(R.id.editEmailReg);
        Button btnCreateAccount = findViewById(R.id.btnCreateAccount);

        // Возврат на экран Входа
        tvGoToLogin.setOnClickListener(v -> finish());

        // Нажатие на "Создать аккаунт"
        btnCreateAccount.setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            String surname = editSurname.getText().toString().trim();
            String email = editEmailReg.getText().toString().trim();

            if (name.isEmpty() || surname.isEmpty() || email.isEmpty()) {
                Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show();
                return;
            }

            // Переходим на экран Аккаунта и ПЕРЕДАЕМ данные
            Intent intent = new Intent(RegisterActivity.this, AccountActivity.class);
            intent.putExtra("USER_NAME", name);
            intent.putExtra("USER_SURNAME", surname);
            intent.putExtra("USER_EMAIL", email);
            startActivity(intent);

            // Закрываем экран регистрации, чтобы при нажатии "Назад"
            // мы вернулись на экран Входа, а не снова на Регистрацию
            finish();
        });
    }
}