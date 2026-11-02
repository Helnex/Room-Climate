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

    private UserPrefs() {}

    public static void save(Context context, AuthResponse user) {
        prefs(context).edit()
                .putInt(KEY_ID, user.id)
                .putString(KEY_NAME, user.name == null ? "" : user.name)
                .putString(KEY_SURNAME, user.surname == null ? "" : user.surname)
                .putString(KEY_EMAIL, user.email == null ? "" : user.email)
                .putString(KEY_TOKEN, user.token == null ? "" : user.token)
                .apply();
    }

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
        return prefs(context).getString(KEY_TOKEN, "");
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
