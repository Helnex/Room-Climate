package com.example.roomclimate.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.example.roomclimate.R;
import com.example.roomclimate.notify.ClimateMonitorService;

import retrofit2.Response;

/** Возвращает пользователя на экран входа, если сессии нет или сервер её отклонил. */
public final class SessionGuard {
    private SessionGuard() {}

    /** @return true, если сессии нет и экран уже закрыт. */
    public static boolean requireLogin(Activity activity) {
        if (UserPrefs.isLoggedIn(activity)) {
            return false;
        }
        goToLogin(activity);
        return true;
    }

    /** @return true, если сервер ответил 401 и пользователь отправлен на вход. */
    public static boolean handleUnauthorized(Activity activity, Response<?> response) {
        if (response.code() != 401) {
            return false;
        }
        UserPrefs.clearSession(activity);
        Toast.makeText(activity, R.string.auth_session_expired, Toast.LENGTH_LONG).show();
        goToLogin(activity);
        return true;
    }

    private static void goToLogin(Activity activity) {
        if (activity.isFinishing()) {
            return;
        }
        ClimateMonitorService.stop(activity);
        Intent intent = new Intent(activity, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
