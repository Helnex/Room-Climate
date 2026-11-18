package com.example.roomclimate.ui;

import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;

/**
 * Нижнее меню на экранах второго уровня «Устройств» (новое помещение, добавление и выбор
 * датчика, выбор помещения). Вид — как у BottomNav, но переход на вкладку закрывает всю
 * цепочку экранов под ней: иначе «Назад» с вкладки возвращал в «Добавление датчика».
 */
final class SubScreenNav {
    private SubScreenNav() {}

    static void bind(AppCompatActivity activity) {
        BottomNav.bind(activity, R.id.navDevices);
        tab(activity, R.id.navDashboard, DashboardActivity.class);
        tab(activity, R.id.navHistory, HistoryActivity.class);
        tab(activity, R.id.navDevices, DevicesActivity.class);
        tab(activity, R.id.navSettings, SettingsActivity.class);
        tab(activity, R.id.navAccount, AccountActivity.class);
    }

    private static void tab(AppCompatActivity activity, int id, Class<?> dest) {
        View item = activity.findViewById(id);
        if (item == null) {
            return;
        }
        item.setOnClickListener(v -> {
            activity.startActivity(new Intent(activity, dest)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            activity.overridePendingTransition(0, 0);
        });
    }
}
