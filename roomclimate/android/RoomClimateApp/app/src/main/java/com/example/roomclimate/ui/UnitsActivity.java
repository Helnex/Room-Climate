package com.example.roomclimate.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.widget.ClimateWidget;

/**
 * «Единицы измерения» (макет, экран 16): °C или °F для дашборда, виджетов и «Устройств».
 * Сервер и пороги хранят °C — пересчёт только при показе, пороги не меняются.
 */
public class UnitsActivity extends AppCompatActivity {
    private boolean fahrenheit;
    private View rowC;
    private View rowF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        SectionScreen s = SectionScreen.setUp(this, R.string.units_title, 0, R.string.units_desc);
        fahrenheit = AppPrefs.fahrenheit(this);
        s.group(R.string.label_temperature);
        rowC = Rows.add(s.card, R.drawable.ic_nav_sensor, getString(R.string.units_celsius),
                getString(R.string.units_example, "23,4 °C"), v -> select(false));
        rowF = Rows.add(s.card, R.drawable.ic_nav_sensor, getString(R.string.units_fahrenheit),
                getString(R.string.units_example, Units.format(Units.toFahrenheit(23.4)) + " °F"), v -> select(true));
        s.group(R.string.label_humidity);
        Rows.add(s.card, R.drawable.ic_drop, getString(R.string.units_percent), getString(R.string.units_percent_fixed), null);
        s.button(R.string.units_save, v -> {
            AppPrefs.setFahrenheit(this, fahrenheit);
            ClimateWidget.renderAll(this); // виджеты сразу в новых единицах
            Toast.makeText(this, R.string.units_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
        s.note(getString(R.string.units_note));
        render();
    }

    private void select(boolean f) {
        fahrenheit = f;
        render();
    }

    private void render() {
        mark(rowC, !fahrenheit);
        mark(rowF, fahrenheit);
    }

    private void mark(View row, boolean on) {
        ImageView chevron = row.findViewById(R.id.rowChevron);
        chevron.setImageResource(on ? R.drawable.ic_check : 0);
        chevron.setVisibility(View.VISIBLE);
        row.setSelected(on);
        row.setActivated(on);
        row.setBackgroundResource(on ? R.drawable.rc_chip : android.R.color.transparent);
    }
}
