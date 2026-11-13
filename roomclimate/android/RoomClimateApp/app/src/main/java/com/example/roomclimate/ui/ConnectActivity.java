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
        if (ServerDiscovery.isLocalUrl(url)) {
            connect(url, pin, false);
            return;
        }
        if (!url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            Toast.makeText(this, getString(R.string.connect_failed, url), Toast.LENGTH_LONG).show();
            openApp();
            return;
        }
        // Внешний адрес (Cloudflare Tunnel): ссылку может подсунуть любой сайт — спрашиваем.
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.connect_remote_title)
                .setMessage(getString(R.string.connect_remote_text, url))
                .setPositiveButton(R.string.connect_remote_ok, (d, w) -> connect(url, null, true))
                .setNegativeButton(android.R.string.cancel, (d, w) -> openApp())
                .setOnCancelListener(d -> openApp())
                .show();
    }

    private void connect(String url, String pin, boolean remoteConfirmed) {
        Toast.makeText(this, getString(R.string.connect_checking, url), Toast.LENGTH_SHORT).show();
        ServerDiscovery.useUrl(this, url, pin, remoteConfirmed, found -> {
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
