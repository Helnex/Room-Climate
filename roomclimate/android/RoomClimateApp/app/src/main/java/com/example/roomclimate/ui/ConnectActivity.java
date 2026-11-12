package com.example.roomclimate.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ServerDiscovery;

/**
 * Открывается по ссылке roomclimate://connect?url=http://IP:8000 со страницы сервера /connect
 * (QR-код на компьютере). Проверяет адрес, сохраняет его и открывает приложение.
 * Экрана нет — только короткое сообщение.
 */
public class ConnectActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() == null ? null : getIntent().getData();
        String url = data == null ? null : data.getQueryParameter("url");
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, R.string.connect_bad_link, Toast.LENGTH_LONG).show();
            openApp();
            return;
        }
        // pin — отпечаток ключа HTTPS-сервера из QR-кода (sha256/…); без него только http в LAN.
        String pin = data.getQueryParameter("pin");
        Toast.makeText(this, getString(R.string.connect_checking, url), Toast.LENGTH_SHORT).show();
        ServerDiscovery.useUrl(this, url, pin, found -> {
            Toast.makeText(this,
                    found != null ? getString(R.string.connect_saved, found) : getString(R.string.connect_failed, url),
                    Toast.LENGTH_LONG).show();
            openApp();
        });
    }

    private void openApp() {
        Intent intent = new Intent(this, UserPrefs.isLoggedIn(this) ? DashboardActivity.class : MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
