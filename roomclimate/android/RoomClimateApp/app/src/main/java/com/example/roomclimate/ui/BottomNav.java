package com.example.roomclimate.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.example.roomclimate.R;

public final class BottomNav {
    private BottomNav() {}

    public static void bind(AppCompatActivity activity, int selectedId) {
        bindTab(activity, R.id.navDashboard, DashboardActivity.class, selectedId);
        bindTab(activity, R.id.navHistory, HistoryActivity.class, selectedId);
        bindTab(activity, R.id.navSettings, SettingsActivity.class, selectedId);
        bindTab(activity, R.id.navAccount, AccountActivity.class, selectedId);

        View devices = activity.findViewById(R.id.navDevices);
        if (devices != null) {
            devices.setOnClickListener(v ->
                    Toast.makeText(activity, R.string.devices_soon, Toast.LENGTH_SHORT).show());
        }
        highlight(activity, selectedId);
    }

    private static void bindTab(AppCompatActivity activity, int id, Class<?> dest, int selectedId) {
        View item = activity.findViewById(id);
        if (item == null || id == selectedId) {
            return;
        }
        item.setOnClickListener(v -> {
            activity.startActivity(new Intent(activity, dest));
            activity.finish();
        });
    }

    private static void highlight(AppCompatActivity activity, int selectedId) {
        int[] ids = {
                R.id.navDashboard, R.id.navHistory, R.id.navDevices, R.id.navSettings, R.id.navAccount
        };
        int selected = ContextCompat.getColor(activity, R.color.green_primary);
        int idle = ContextCompat.getColor(activity, R.color.gray_text);
        for (int id : ids) {
            View item = activity.findViewById(id);
            if (item instanceof ViewGroup) {
                styleItem((ViewGroup) item, id == selectedId, selected, idle);
            }
        }
    }

    private static void styleItem(ViewGroup item, boolean selected, int selectedColor, int idleColor) {
        int color = selected ? selectedColor : idleColor;
        for (int i = 0; i < item.getChildCount(); i++) {
            View child = item.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView icon = (ImageView) child;
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
                if (selected) {
                    icon.setBackgroundResource(R.drawable.bg_circle_green);
                } else {
                    icon.setBackground(null);
                }
            } else if (child instanceof TextView) {
                TextView label = (TextView) child;
                label.setTextColor(color);
                label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }
}
