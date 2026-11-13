package com.example.roomclimate.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.roomclimate.models.AuthResponse;

public final class UserPrefs {
    private static final String PREFS = "roomclimate_user";
    private static final String KEY_ID = "user_id";
    private static final String KEY_NAME = "name";
    private static final String KEY_SURNAME = "surname";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ALERTS = "alerts_enabled";

    private UserPrefs() {}

    public static void save(Context context, AuthResponse user) {
        prefs(context).edit()
                .putInt(KEY_ID, user.id)
                .putString(KEY_NAME, user.name == null ? "" : user.name)
                .putString(KEY_SURNAME, user.surname == null ? "" : user.surname)
                .putString(KEY_EMAIL, user.email == null ? "" : user.email)
                .putString(KEY_TOKEN, encryptOrEmpty(user.token))
                .apply();
        cachedToken = null;
    }

    /** Токен хранится зашифрованным ключом Android Keystore (см. TokenCrypto). */
    private static String encryptOrEmpty(String token) {
        String plain = token == null ? "" : token;
        String encrypted = TokenCrypto.encrypt(plain);
        // Keystore сломан на прошивке (редко) — храним как раньше, чтобы вход не ломался.
        return encrypted == null ? plain : encrypted;
    }

    // Расшифровка через Keystore небыстрая, а токен нужен каждому запросу — держим в памяти.
    private static volatile String cachedToken;

    public static boolean isLoggedIn(Context context) {
        String token = getToken(context);
        return token != null && !token.isEmpty();
    }

    public static int getId(Context context) {
        return prefs(context).getInt(KEY_ID, 0);
    }

    public static String getName(Context context) {
        return prefs(context).getString(KEY_NAME, "");
    }

    public static String getSurname(Context context) {
        return prefs(context).getString(KEY_SURNAME, "");
    }

    public static String getEmail(Context context) {
        return prefs(context).getString(KEY_EMAIL, "");
    }

    public static String getToken(Context context) {
        String cached = cachedToken;
        if (cached != null) {
            return cached;
        }
        String stored = prefs(context).getString(KEY_TOKEN, "");
        String token = TokenCrypto.decrypt(stored);
        if (!token.isEmpty() && !TokenCrypto.isEncrypted(stored)) {
            // Миграция: токен из старой версии лежал открытым текстом — шифруем.
            prefs(context).edit().putString(KEY_TOKEN, encryptOrEmpty(token)).apply();
        }
        cachedToken = token;
        return token;
    }

    public static boolean alertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ALERTS, true);
    }

    public static void setAlertsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ALERTS, enabled).apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
        cachedToken = null;
        com.example.roomclimate.network.OfflineCache.clear(context);
        com.example.roomclimate.widget.ClimateWidget.clear(context); // виджет: «Войдите»
    }

    /** Сбрасывает только данные сессии (токен и профиль), настройки уведомлений остаются. */
    public static void clearSession(Context context) {
        // Кэш офлайн-данных принадлежит пользователю — при выходе удаляем.
        com.example.roomclimate.network.OfflineCache.clear(context);
        prefs(context).edit()
                .remove(KEY_ID)
                .remove(KEY_NAME)
                .remove(KEY_SURNAME)
                .remove(KEY_EMAIL)
                .remove(KEY_TOKEN)
                .commit();
        cachedToken = null;
        // Только после удаления токена: виджет проверяет вход и иначе снова закэшировал бы токен.
        com.example.roomclimate.widget.ClimateWidget.clear(context); // виджет: «Войдите»
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
