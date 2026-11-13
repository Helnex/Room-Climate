package com.example.roomclimate;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Приложение целиком.
 *
 * Отступы от системных панелей для всех экранов. Приложение собрано под Android 15+
 * (targetSdk 37), где система рисует окно «от края до края»: под строкой состояния и
 * под панелью навигации. Без отступов заголовки наезжали на часы и значки сверху, а нижнее
 * меню — на полоску жестов. Здесь каждому экрану добавляется внутренний отступ ровно на
 * высоту системных панелей и выреза камеры (и клавиатуры — поля не прячутся под неё).
 * Одно место на всё приложение: макеты экранов менять не нужно.
 */
public class RoomClimateApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                // К этому моменту экран уже вызвал setContentView — контейнер окончательный.
                View content = activity.findViewById(android.R.id.content);
                if (content != null && content.getTag(R.id.insets_applied) == null) {
                    content.setTag(R.id.insets_applied, Boolean.TRUE);
                    applySystemBarPadding(content);
                }
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}
            @Override public void onActivityResumed(@NonNull Activity activity) {}
            @Override public void onActivityPaused(@NonNull Activity activity) {}
            @Override public void onActivityStopped(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }

    static void applySystemBarPadding(View content) {
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
