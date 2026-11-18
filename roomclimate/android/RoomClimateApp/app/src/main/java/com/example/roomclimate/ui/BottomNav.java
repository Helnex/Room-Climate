package com.example.roomclimate.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;

import com.example.roomclimate.R;

public final class BottomNav {

    private static final int GREEN = 0xFF397454;
    private static final int GRAY = 0xFF778178;
    private static final int ACTIVE_BG = 0xFFE5EEDF;

    private BottomNav() {
    }

    public static void bind(AppCompatActivity activity, int selectedId) {
        View bar = activity.findViewById(R.id.bottomNav);

        if (bar != null) {
            bar.setBackgroundColor(Color.WHITE);
            bar.setPadding(
                    dp(bar, 8),
                    dp(bar, 9),
                    dp(bar, 8),
                    dp(bar, 8)
            );

            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params != null) {
                params.height = dp(bar, 66);
                bar.setLayoutParams(params);
            }
        }

        bindTab(activity, R.id.navDashboard, selectedId,
                R.drawable.ic_nav_home, DashboardActivity.class);
        bindTab(activity, R.id.navHistory, selectedId,
                R.drawable.ic_nav_chart, HistoryActivity.class);
        bindTab(activity, R.id.navDevices, selectedId,
                R.drawable.ic_nav_sensor, DevicesActivity.class);
        bindTab(activity, R.id.navSettings, selectedId,
                R.drawable.ic_nav_tune, SettingsActivity.class);
        bindTab(activity, R.id.navAccount, selectedId,
                R.drawable.ic_nav_person, AccountActivity.class);
    }

    private static void bindTab(
            AppCompatActivity activity,
            int id,
            int selectedId,
            int iconRes,
            Class<?> target
    ) {
        View view = activity.findViewById(id);

        if (!(view instanceof ViewGroup)) {
            return;
        }

        ViewGroup tab = (ViewGroup) view;
        boolean selected = id == selectedId;
        int color = selected ? GREEN : GRAY;

        tab.setSelected(selected);

        if (selected) {
            tab.setOnClickListener(null);
        } else {
            tab.setOnClickListener(v -> {
                activity.startActivity(new Intent(activity, target));
                activity.finish();
            });
        }

        for (int i = 0; i < tab.getChildCount(); i++) {
            View child = tab.getChildAt(i);

            if (child instanceof ImageView) {
                ImageView icon = (ImageView) child;

                icon.setLayoutParams(new LinearLayout.LayoutParams(
                        dp(icon, 48),
                        dp(icon, 30)
                ));
                icon.setPadding(
                        dp(icon, 13),
                        dp(icon, 4),
                        dp(icon, 13),
                        dp(icon, 4)
                );
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageResource(iconRes);
                ImageViewCompat.setImageTintList(
                        icon,
                        ColorStateList.valueOf(color)
                );

                if (selected) {
                    GradientDrawable pill = new GradientDrawable();
                    pill.setColor(ACTIVE_BG);
                    pill.setCornerRadius(dp(icon, 15));
                    icon.setBackground(pill);
                } else {
                    icon.setBackground(null);
                }
            } else if (child instanceof TextView) {
                TextView label = (TextView) child;
                label.setTextSize(10);
                label.setTextColor(color);
                label.setTypeface(
                        null,
                        selected ? Typeface.BOLD : Typeface.NORMAL
                );
            }
        }
    }

    private static int dp(View view, int value) {
        return Math.round(
                value * view.getResources().getDisplayMetrics().density
        );
    }
}