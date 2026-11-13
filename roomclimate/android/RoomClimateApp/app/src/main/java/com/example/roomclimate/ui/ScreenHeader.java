package com.example.roomclimate.ui;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.example.roomclimate.R;

/** Шапка новых экранов второго уровня (include_sub_header): кнопка «назад» и заголовок. */
public final class ScreenHeader {
    private ScreenHeader() {}

    /** Экран второго уровня (include_sub_header): заголовок и кнопка «назад». */
    public static void bindSub(Activity activity, int titleRes) {
        TextView title = activity.findViewById(R.id.tvScreenTitle);
        if (title != null) {
            title.setText(titleRes);
        }
        View back = activity.findViewById(R.id.btnBack);
        if (back != null) {
            back.setOnClickListener(v -> activity.finish());
        }
    }
}
