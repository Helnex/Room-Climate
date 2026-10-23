package com.example.roomclimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;

public class AccountActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        // Находим наши TextView
        TextView tvFullName = findViewById(R.id.tvFullName);
        TextView tvEmail = findViewById(R.id.tvEmail);
        TextView tvAvatar = findViewById(R.id.tvAvatar);
        TextView tvLogout = findViewById(R.id.tvLogout);

        // Получаем данные, которые передал экран Регистрации
        Intent intent = getIntent();
        String name = intent.getStringExtra("USER_NAME");
        String surname = intent.getStringExtra("USER_SURNAME");
        String email = intent.getStringExtra("USER_EMAIL");

        // Если данные есть (пришли с регистрации) — подставляем их
        if (name != null && surname != null) {
            tvFullName.setText(name + " " + surname);

            // Делаем инициалы для аватарки (например, "ДМ")
            String initials = "";
            if (!name.isEmpty()) initials += name.substring(0, 1).toUpperCase();
            if (!surname.isEmpty()) initials += surname.substring(0, 1).toUpperCase();
            tvAvatar.setText(initials);
        }
        if (email != null) {
            tvEmail.setText(email);
        }

        // Кнопка "Выйти" (возвращает на экран Входа)
        tvLogout.setOnClickListener(v -> {
            Intent logoutIntent = new Intent(AccountActivity.this, MainActivity.class);
            // Очищаем стек, чтобы нельзя было вернуться назад кнопкой "Назад"
            logoutIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(logoutIntent);
            finish();
        });
    }
}
