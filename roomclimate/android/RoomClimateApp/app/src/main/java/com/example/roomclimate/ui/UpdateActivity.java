package com.example.roomclimate.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ApiErrors;
import com.example.roomclimate.update.AppUpdate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * «Обновление приложения»: какая версия стоит, есть ли новее на сервере, кнопка «Обновить» —
 * скачать APK (с проверкой контрольной суммы) и открыть системное окно установки.
 * Открывается из уведомления о новой версии или из «Настроек».
 */
public class UpdateActivity extends AppCompatActivity {
    private SectionScreen screen;
    private JsonObject latest;
    /** Скачанный APK ждёт, пока пользователь разрешит установку из этого приложения. */
    private File pendingInstall;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screen = SectionScreen.setUp(this, R.string.upd_title, 0, R.string.upd_checking);
        screen.card.setVisibility(View.GONE);  // полей нет — пустая карточка шаблона не нужна
        screen.note(getString(R.string.upd_current, AppUpdate.currentVersionName(this)));
        String fromNotification = getIntent().getStringExtra(AppUpdate.EXTRA_LATEST);
        if (fromNotification != null) {
            try {
                show(JsonParser.parseString(fromNotification).getAsJsonObject());
            } catch (RuntimeException e) {
                check();
            }
        } else {
            check();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall != null && canInstall()) {
            File file = pendingInstall;
            pendingInstall = null;
            install(file);
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void check() {
        desc(getString(R.string.upd_checking));
        screen.primary.setVisibility(View.GONE);
        AppUpdate.check(this, (found, error) -> {
            if (isFinishing()) {
                return;
            }
            if (found != null) {
                show(found);
            } else if (error != null) {
                desc(getString(R.string.upd_error, error));
                screen.button(R.string.upd_retry, v -> check());
            } else {
                desc(getString(R.string.upd_up_to_date, AppUpdate.currentVersionName(this)));
                screen.button(R.string.upd_check_again, v -> check());
            }
        });
    }

    private void show(JsonObject info) {
        latest = info;
        String version = str(info, "version_name");
        String changes = str(info, "changes");
        TextView heading = findViewById(R.id.tvSectionHeading);
        heading.setText(getString(R.string.upd_available, version));
        heading.setVisibility(View.VISIBLE);
        desc(changes.isEmpty() ? getString(R.string.upd_notification_text) : changes);
        long size = info.has("size") ? info.get("size").getAsLong() : 0;
        screen.note(getString(R.string.upd_note, AppUpdate.currentVersionName(this),
                size > 0 ? Formatter.formatShortFileSize(this, size) : "—"));
        screen.button(R.string.upd_button, v -> download());
    }

    // ---------- Скачивание ----------

    private void download() {
        screen.primary.setEnabled(false);
        screen.primary.setText(getString(R.string.upd_downloading, 0));
        String expected = str(latest, "sha256");
        io.execute(() -> {
            try {
                Response<ResponseBody> response = ApiClient.get(this).downloadApk().execute();
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    fail(ApiErrors.message(response, getString(R.string.request_failed)));
                    return;
                }
                File dir = new File(getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("no dir");
                }
                File file = new File(dir, "roomclimate.apk");
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long total = body.contentLength();
                long done = 0;
                int lastPercent = -1;
                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        sha.update(buffer, 0, read);
                        done += read;
                        int percent = total > 0 ? (int) (done * 100 / total) : 0;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            runOnUiThread(() -> screen.primary.setText(getString(R.string.upd_downloading, percent)));
                        }
                    }
                }
                String actual = hex(sha.digest());
                if (!expected.isEmpty() && !expected.equalsIgnoreCase(actual)) {
                    // Файл повреждён или подменён по дороге — не ставим.
                    file.delete();
                    fail(getString(R.string.upd_bad_checksum));
                    return;
                }
                runOnUiThread(() -> {
                    screen.primary.setEnabled(true);
                    screen.primary.setText(R.string.upd_install);
                    screen.primary.setOnClickListener(v -> install(file));
                    install(file);
                });
            } catch (Exception e) {  // нет сети, сервер перезапускается, нет места
                fail(ApiErrors.network(e));
            }
        });
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.upd_failed)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            screen.primary.setEnabled(true);
            screen.primary.setText(R.string.upd_button);
        });
    }

    // ---------- Установка ----------

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void install(File file) {
        if (!canInstall()) {
            // Android 8+: ставить приложения можно только из разрешённых источников — один раз.
            pendingInstall = file;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.upd_permission_title)
                    .setMessage(R.string.upd_permission_text)
                    .setPositiveButton(R.string.upd_permission_open, (d, w) -> startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ---------- Вспомогательное ----------

    private void desc(String text) {
        ((TextView) findViewById(R.id.tvSectionDesc)).setText(text);
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }
}
