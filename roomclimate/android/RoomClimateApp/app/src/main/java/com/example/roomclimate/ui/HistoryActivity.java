package com.example.roomclimate.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.roomclimate.R;
import com.example.roomclimate.models.HistoryPoint;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.models.SettingsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.AppPermissions;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HistoryActivity extends AppCompatActivity {

    private static final Locale RU = new Locale("ru", "RU");

    private static final int BG = 0xFFF4F6F2;
    private static final int DARK = 0xFF22352C;
    private static final int GRAY = 0xFF778178;
    private static final int GREEN = 0xFF397454;
    private static final int SOFT = 0xFFE5EEDF;
    private static final int ERROR = 0xFFAA4137;

    private final List<Call<?>> calls = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private final TextView[] periodButtons = new TextView[4];
    private final TextView[] statValues = new TextView[3];

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout controls;
    private LinearLayout card;
    private LinearLayout summary;
    private LinearLayout empty;

    private TextView room;
    private TextView dates;
    private TextView metric;
    private TextView title;
    private TextView value;
    private TextView badge;
    private TextView status;
    private TextView emptyTitle;
    private TextView emptyText;
    private TextView emptyAction;

    private TextView emptyHint;
    private TextView legend;

    private ClimateChartView chart;

    private boolean active;
    private boolean humidity;

    private int period = 1;
    private int generation;

    private long customStart;
    private long customEnd;

    private String selectedDevice = "";

    private Double lower;
    private Double upper;

    private static final class Source {
        final String id;
        final String label;

        Source(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (SessionGuard.requireLogin(this)) {
            return;
        }

        if (state != null) {
            period = state.getInt("period", 1);
            humidity = state.getBoolean("humidity");
            customStart = state.getLong("from");
            customEnd = state.getLong("to");
            selectedDevice = state.getString("device", "");

            if (period == 3 && customEnd <= customStart) {
                period = 1;
            }
        }

        buildScreen();

        BottomNav.bind(this, R.id.navHistory);

        androidx.fragment.app.Fragment restored =
                getSupportFragmentManager()
                        .findFragmentByTag("history_dates");

        if (restored instanceof MaterialDatePicker) {
            @SuppressWarnings("unchecked")
            MaterialDatePicker<Pair<Long, Long>> existing =
                    (MaterialDatePicker<Pair<Long, Long>>) restored;

            bindPicker(existing);
        }
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(
            LinearLayout parent,
            View child,
            int topMargin
    ) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private TextView action(String label, Runnable action) {
        TextView view = text(label, 14, GREEN);

        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(background(0xFFEFF4EB, 12));
        view.setOnClickListener(v -> action.run());

        return view;
    }

    private void buildScreen() {
        root = column();
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        content = column();
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(content);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headerText = column();

        add(headerText, text("КЛИМАТ ПОМЕЩЕНИЯ", 12, GRAY), 0);
        add(headerText, text("Графики", 28, DARK), 4);

        header.addView(
                headerText,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        ImageButton notifications = new ImageButton(this);
        notifications.setImageResource(R.drawable.ic_bell);
        notifications.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        notifications.setPadding(dp(11), dp(11), dp(11), dp(11));
        notifications.setColorFilter(DARK);
        notifications.setContentDescription("Уведомления");
        notifications.setBackground(background(Color.WHITE, 100));
        notifications.setOnClickListener(v ->
                startActivity(new Intent(this, NotificationsActivity.class))
        );

        header.addView(
                notifications,
                new LinearLayout.LayoutParams(dp(42), dp(42))
        );

        add(content, header, 0);

        room = text("Загружаем помещения…", 15, DARK);
        room.setMinHeight(dp(48));
        room.setGravity(Gravity.CENTER_VERTICAL);
        room.setOnClickListener(v -> chooseSource());

        add(content, room, 8);

        controls = column();
        add(content, controls, 10);

        add(controls, text("Период", 12, GRAY), 0);

        LinearLayout periods = new LinearLayout(this);
        periods.setPadding(dp(4), dp(4), dp(4), dp(4));
        periods.setBackground(background(Color.WHITE, 12));

        String[] labels = {
                "1 час", "24 часа", "Неделя", "Свой"
        };

        for (int i = 0; i < labels.length; i++) {
            final int selected = i;

            TextView button = text(labels[i], 12, GRAY);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(40));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, -2, 1);

            if (i > 0) {
                params.leftMargin = dp(4);
            }

            periods.addView(button, params);
            periodButtons[i] = button;

            button.setOnClickListener(v -> {
                if (selected == 3) {
                    openDates();
                } else {
                    period = selected;
                    paintPeriods();
                    loadHistory();
                }
            });
        }

        add(controls, periods, 8);

        LinearLayout metricLabels = new LinearLayout(this);
        metricLabels.setGravity(Gravity.CENTER_VERTICAL);

        metricLabels.addView(
                text("Показатель", 12, GRAY),
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        dates = text("", 11, GRAY);
        dates.setGravity(Gravity.END);
        metricLabels.addView(
                dates,
                new LinearLayout.LayoutParams(-2, -2)
        );

        add(controls, metricLabels, 12);

        metric = action("", this::chooseMetric);
        metric.setGravity(Gravity.CENTER_VERTICAL);
        metric.setTextColor(DARK);
        metric.setBackground(background(Color.WHITE, 12));
        metric.setPadding(dp(16), dp(10), dp(16), dp(10));
        metric.setCompoundDrawablePadding(dp(10));

        add(controls, metric, 8);

        add(controls, text("Показатель", 12, GRAY), 12);

        metric = action("", this::chooseMetric);
        metric.setGravity(Gravity.CENTER_VERTICAL);
        metric.setTextColor(DARK);
        metric.setBackground(background(Color.WHITE, 12));

        add(controls, metric, 8);

        card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(background(Color.WHITE, 20));

        add(content, card, 16);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = column();

        title = text("", 16, DARK);
        title.setTypeface(null, Typeface.BOLD);
        add(names, title, 0);

        value = text("—", 27, DARK);
        value.setTypeface(null, Typeface.BOLD);
        add(names, value, 4);

        heading.addView(
                names,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        badge = text("", 11, GREEN);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(background(0xFFEFF4EB, 8));

        heading.addView(badge);
        add(card, heading, 0);

        chart = new ClimateChartView(this);

        LinearLayout.LayoutParams chartParams =
                new LinearLayout.LayoutParams(-1, dp(180));

        chartParams.topMargin = dp(12);
        card.addView(chart, chartParams);

        legend = text("", 11, GRAY);
        legend.setCompoundDrawablePadding(dp(6));

        GradientDrawable comfortMarker = background(0xFFEFF4EB, 2);
        comfortMarker.setStroke(dp(1), 0xFFE5EAE2);
        comfortMarker.setBounds(0, 0, dp(9), dp(9));
        legend.setCompoundDrawablesRelative(comfortMarker, null, null, null);

        add(card, legend, 12);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE5EAE2);

        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(-1, dp(1));

        dividerParams.topMargin = dp(16);
        card.addView(divider, dividerParams);

        LinearLayout statistics = new LinearLayout(this);

        String[] statLabels = {
                "Минимум", "Среднее", "Максимум"
        };

        for (int i = 0; i < statLabels.length; i++) {
            LinearLayout box = column();

            add(box, text(statLabels[i], 10, GRAY), 0);

            statValues[i] = text("—", 15, DARK);
            statValues[i].setTypeface(null, Typeface.BOLD);

            add(box, statValues[i], 5);

            statistics.addView(
                    box,
                    new LinearLayout.LayoutParams(0, -2, 1)
            );
        }

        add(card, statistics, 12);

        summary = column();
        summary.setPadding(dp(16), dp(16), dp(16), dp(16));
        summary.setBackground(background(SOFT, 20));

        add(content, summary, 16);

        empty = column();
        empty.setPadding(dp(24), dp(32), dp(24), dp(32));
        empty.setBackground(background(Color.WHITE, 20));

        emptyTitle = text("", 20, DARK);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setTypeface(null, Typeface.BOLD);

        add(empty, emptyTitle, 0);

        emptyText = text("", 13, 0xFF5C6D60);
        emptyText.setGravity(Gravity.CENTER);

        add(empty, emptyText, 12);

        emptyAction = action("", () -> {});
        add(empty, emptyAction, 16);

        add(content, empty, 18);

        emptyHint = text(
                "Здесь будет история температуры, влажности "
                        + "и других показателей вашего дома.",
                12,
                0xFF5C6D60
        );
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setLineSpacing(dp(3), 1f);
        emptyHint.setVisibility(View.GONE);
        add(content, emptyHint, 16);

        status = text("", 12, GRAY);
        add(content, status, 16);

        getLayoutInflater().inflate(
                R.layout.include_bottom_nav,
                root,
                true
        );

        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                            );

                    view.setPadding(
                            bars.left,
                            bars.top,
                            bars.right,
                            bars.bottom
                    );

                    return insets;
                }
        );

        paintPeriods();
        paintMetric();

        setDataVisible(false);
        empty.setVisibility(View.GONE);
    }

    private void paintPeriods() {
        for (int i = 0; i < periodButtons.length; i++) {
            boolean selected = i == period;

            periodButtons[i].setBackground(
                    background(selected ? SOFT : Color.WHITE, 8)
            );

            periodButtons[i].setTextColor(
                    selected ? GREEN : GRAY
            );

            periodButtons[i].setTypeface(
                    null,
                    selected ? Typeface.BOLD : Typeface.NORMAL
            );
        }
    }

    private void paintMetric() {
        metric.setText(
                humidity
                        ? "Влажность, %"
                        : "Температура, " + Units.symbol(this)
        );

        android.graphics.drawable.Drawable icon =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        this,
                        humidity ? R.drawable.ic_drop : R.drawable.ic_nav_sensor
                );

        android.graphics.drawable.Drawable arrow =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        this,
                        R.drawable.ic_chevron_down
                );

        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(GREEN);
            icon.setBounds(0, 0, dp(18), dp(18));
        }

        if (arrow != null) {
            arrow = arrow.mutate();
            arrow.setTint(DARK);
            arrow.setBounds(0, 0, dp(16), dp(16));
        }

        metric.setCompoundDrawablesRelative(icon, null, arrow, null);
        title.setText(humidity ? "Влажность" : "Температура");
    }

    private void chooseMetric() {
        new AlertDialog.Builder(this)
                .setTitle("Показатель")
                .setItems(
                        new String[]{"Температура", "Влажность"},
                        (dialog, which) -> {
                            humidity = which == 1;
                            paintMetric();
                            loadHistory();
                        }
                )
                .show();
    }

    private void chooseSource() {
        if (sources.isEmpty()) {
            return;
        }

        String[] labels = new String[sources.size()];

        for (int i = 0; i < labels.length; i++) {
            labels[i] = sources.get(i).label;
        }

        new AlertDialog.Builder(this)
                .setTitle("Помещение и датчик")
                .setItems(labels, (dialog, which) -> {
                    Source source = sources.get(which);

                    selectedDevice = source.id;
                    room.setText(source.label + "   ▾");

                    loadHistory();
                })
                .show();
    }

    private void openDates() {
        if (getSupportFragmentManager()
                .findFragmentByTag("history_dates") != null) {
            return;
        }

        CalendarConstraints constraints =
                new CalendarConstraints.Builder()
                        .setEnd(
                                MaterialDatePicker
                                        .todayInUtcMilliseconds()
                        )
                        .setValidator(
                                DateValidatorPointBackward.now()
                        )
                        .build();

        MaterialDatePicker.Builder<Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTheme(
                                R.style.Theme_RoomClimate_HistoryCalendar
                        )
                        .setTitleText(
                                "Выберите начало и конец периода"
                        )
                        .setPositiveButtonText("Показать")
                        .setNegativeButtonText("Отмена")
                        .setCalendarConstraints(constraints);

        if (customEnd > customStart) {
            builder.setSelection(
                    new Pair<>(
                            calendarDay(customStart),
                            calendarDay(customEnd - 1)
                    )
            );
        }

        MaterialDatePicker<Pair<Long, Long>> picker =
                builder.build();

        bindPicker(picker);

        picker.show(
                getSupportFragmentManager(),
                "history_dates"
        );
    }

    private void bindPicker(
            MaterialDatePicker<Pair<Long, Long>> picker
    ) {
        picker.clearOnPositiveButtonClickListeners();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection.first == null
                    || selection.second == null) {
                return;
            }

            customStart = localDay(selection.first, false);
            customEnd = localDay(selection.second, true);

            period = 3;

            paintPeriods();
            loadHistory();
        });
    }

    private long localDay(long utcDay, boolean nextDay) {
        Calendar utc = Calendar.getInstance(
                TimeZone.getTimeZone("UTC")
        );

        utc.setTimeInMillis(utcDay);

        Calendar local = Calendar.getInstance();
        local.clear();

        local.set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH)
        );

        if (nextDay) {
            local.add(Calendar.DAY_OF_MONTH, 1);
        }

        return local.getTimeInMillis();
    }

    private long calendarDay(long localTime) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(localTime);

        Calendar utc = Calendar.getInstance(
                TimeZone.getTimeZone("UTC")
        );

        utc.clear();

        utc.set(
                local.get(Calendar.YEAR),
                local.get(Calendar.MONTH),
                local.get(Calendar.DAY_OF_MONTH)
        );

        return utc.getTimeInMillis();
    }

    private void cancelRequests() {
        generation++;

        for (Call<?> call : calls) {
            call.cancel();
        }

        calls.clear();
    }

    private boolean current(int requestGeneration) {
        return active
                && generation == requestGeneration
                && !isFinishing()
                && !isDestroyed();
    }

    private void setDataVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;

        card.setVisibility(visibility);
        summary.setVisibility(visibility);
    }

    private void showMessage(
            String heading,
            String description,
            String actionLabel,
            Runnable action
    ) {
        setDataVisible(false);

        emptyHint.setVisibility(View.GONE);
        emptyHint.setOnClickListener(null);
        emptyHint.setClickable(false);
        emptyHint.setMinHeight(0);
        emptyHint.setTextColor(0xFF5C6D60);
        emptyHint.setText(
                "Здесь будет история температуры, влажности "
                        + "и других показателей вашего дома."
        );
        empty.setVisibility(View.VISIBLE);
        emptyTitle.setText(heading);
        emptyText.setText(description);
        emptyAction.setText(actionLabel);

        emptyAction.setOnClickListener(v -> action.run());
    }

    private void showError(String description) {
        status.setText("");

        // Если список уже загрузился, а упал только запрос истории,
        // не предлагаем заново добавлять помещение.
        if (controls.getVisibility() == View.VISIBLE) {
            showMessage(
                    "Не удалось загрузить данные",
                    description,
                    "Попробовать снова",
                    this::loadHistory
            );
            return;
        }

        room.setVisibility(View.GONE);

        showMessage(
                "Не удалось загрузить помещения",
                "Попробуйте обновить список или добавьте новое помещение.",
                "+ Добавить помещение",
                this::openDevices
        );

        emptyHint.setText("Попробовать снова");
        emptyHint.setTextColor(GREEN);
        emptyHint.setMinHeight(dp(48));
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setOnClickListener(v -> loadSources());
        emptyHint.setVisibility(View.VISIBLE);
    }

    private void openDevices() {
        startActivity(new Intent(this, DevicesActivity.class));
        finish();
    }
    private void loadSources() {
        if (!active) {
            return;
        }

        cancelRequests();

        final int requestGeneration = generation;

        if (AppPermissions.blocksServer(this)) {
            showError(
                    "Разрешите приложению доступ к локальной сети "
                            + "в настройках телефона."
            );

            return;
        }

        status.setTextColor(GRAY);
        status.setText("Загружаем помещения…");

        setDataVisible(false);

        empty.setVisibility(View.GONE);
        emptyHint.setVisibility(View.GONE);
        room.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);

        Call<JsonObject> call = ApiClient.get(this).getRooms();
        calls.add(call);

        call.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(
                    @NonNull Call<JsonObject> call,
                    @NonNull Response<JsonObject> response
            ) {
                if (!current(requestGeneration)) {
                    return;
                }

                if (SessionGuard.handleUnauthorized(
                        HistoryActivity.this,
                        response
                )) {
                    return;
                }

                if (!response.isSuccessful()
                        || response.body() == null) {
                    showError(
                            "Не удалось получить список помещений. "
                                    + "Попробуйте ещё раз."
                    );

                    return;
                }

                sources.clear();

                JsonArray rooms = array(
                        response.body(),
                        "rooms"
                );

                JsonArray unassigned = array(
                        response.body(),
                        "unassigned"
                );

                for (JsonElement element : rooms) {
                    JsonObject item = element.getAsJsonObject();

                    collectSources(
                            array(item, "devices"),
                            string(item, "name")
                    );
                }

                collectSources(unassigned, "Без помещения");

                if (sources.isEmpty()) {
                    room.setText("");
                    status.setText("");

                    if (rooms.size() == 0 && unassigned.size() == 0) {
                        showMessage(
                                "Пока нет измерений",
                                "Графики появятся после добавления помещения "
                                        + "и подключения первого датчика.",
                                "+ Добавить помещение",
                                HistoryActivity.this::openDevices
                        );

                        emptyHint.setVisibility(View.VISIBLE);
                    } else {
                        showMessage(
                                "Пока нет измерений климата",
                                "Подключите датчик температуры "
                                        + "и влажности к вашему помещению.",
                                "+ Добавить датчик",
                                () -> startActivity(
                                        new Intent(
                                                HistoryActivity.this,
                                                AddSensorActivity.class
                                        )
                                )
                        );
                    }

                    return;
                }

                Source chosen = sources.get(0);

                for (Source source : sources) {
                    if (source.id.equals(selectedDevice)) {
                        chosen = source;
                        break;
                    }
                }

                selectedDevice = chosen.id;
                room.setText(chosen.label + "   ▾");
                room.setVisibility(View.VISIBLE);

                controls.setVisibility(View.VISIBLE);

                loadHistory();
            }

            @Override
            public void onFailure(
                    @NonNull Call<JsonObject> call,
                    @NonNull Throwable error
            ) {
                if (current(requestGeneration)
                        && !call.isCanceled()) {
                    showError(
                            "Проверьте подключение "
                                    + "и попробуйте ещё раз."
                    );
                }
            }
        });
    }

    private static JsonArray array(
            JsonObject object,
            String key
    ) {
        return object.has(key)
                && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String string(
            JsonObject object,
            String key
    ) {
        return object.has(key)
                && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : "";
    }

    private void collectSources(
            JsonArray devices,
            String roomName
    ) {
        int index = 0;

        for (JsonElement element : devices) {
            index++;

            JsonObject device = element.getAsJsonObject();

            JsonObject readings =
                    device.has("readings")
                            && device.get("readings").isJsonObject()
                            ? device.getAsJsonObject("readings")
                            : new JsonObject();

            boolean climate =
                    "climate".equals(string(device, "kind"))
                            || readings.has("temperature")
                            || readings.has("humidity");

            if (!climate) {
                continue;
            }

            String id = string(device, "device_id");

            if (id.isEmpty()) {
                continue;
            }

            String label = roomName;

            if (devices.size() > 1) {
                label += " · Датчик " + index;
            }

            sources.add(new Source(id, label));
        }
    }

    private String utc(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                Locale.US
        );

        format.setTimeZone(TimeZone.getTimeZone("UTC"));

        return format.format(new Date(timestamp)) + "+00:00";
    }

    private Long parseTimestamp(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.replaceFirst(
                "\\.([0-9]{3})[0-9]+",
                ".$1"
        );

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SXXX"
        };

        for (String pattern : patterns) {
            SimpleDateFormat format =
                    new SimpleDateFormat(pattern, Locale.US);

            format.setLenient(false);

            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(normalized, position);

            if (date != null
                    && position.getIndex() == normalized.length()) {
                return date.getTime();
            }
        }

        return null;
    }

    private void loadHistory() {
        if (!active || selectedDevice.isEmpty()) {
            return;
        }

        cancelRequests();

        final int requestGeneration = generation;

        paintMetric();

        empty.setVisibility(View.GONE);
        setDataVisible(false);

        status.setTextColor(GRAY);
        status.setText("Загружаем историю…");

        final boolean selectedHumidity = humidity;

        final String type = selectedHumidity
                ? "humidity"
                : "temperature";

        final String unit = selectedHumidity
                ? "%"
                : Units.symbol(this);

        long now = System.currentTimeMillis() / 1000L * 1000L;

        final long end = period == 3
                ? Math.min(customEnd, now)
                : now;

        final long start = period == 3
                ? customStart
                : end - (
                period == 0
                        ? 3_600_000L
                        : period == 1
                        ? 86_400_000L
                        : 604_800_000L
        );

        SimpleDateFormat labelFormat =
                new SimpleDateFormat(
                        period == 3
                                ? "dd.MM.yyyy"
                                : "dd.MM HH:mm",
                        RU
                );

        dates.setText(
                labelFormat.format(new Date(start))
                        + " — "
                        + labelFormat.format(
                        new Date(
                                period == 3
                                        ? customEnd - 1
                                        : end
                        )
                )
        );

        lower = null;
        upper = null;

        chart.setComfort(null, null);

        Call<SettingsResponse> call =
                ApiClient.get(this).getSettings(selectedDevice);

        calls.add(call);

        call.enqueue(new Callback<SettingsResponse>() {
            @Override
            public void onResponse(
                    @NonNull Call<SettingsResponse> call,
                    @NonNull Response<SettingsResponse> response
            ) {
                if (!current(requestGeneration)) {
                    return;
                }

                if (SessionGuard.handleUnauthorized(
                        HistoryActivity.this,
                        response
                )) {
                    return;
                }

                if (response.isSuccessful()
                        && response.body() != null) {
                    SettingsResponse settings = response.body();

                    if (selectedHumidity) {
                        lower = settings.humidityMinOrDefault();
                        upper = settings.humidityMaxOrDefault();
                    } else {
                        lower = Units.shown(
                                HistoryActivity.this,
                                settings.tempMin
                        );

                        upper = Units.shown(
                                HistoryActivity.this,
                                settings.tempMax
                        );
                    }

                    if (!Double.isFinite(lower)
                            || !Double.isFinite(upper)
                            || lower > upper) {
                        lower = null;
                        upper = null;
                    }
                }

                fetchHistory(
                        requestGeneration,
                        type,
                        unit,
                        selectedHumidity,
                        start,
                        end
                );
            }

            @Override
            public void onFailure(
                    @NonNull Call<SettingsResponse> call,
                    @NonNull Throwable error
            ) {
                if (current(requestGeneration)
                        && !call.isCanceled()) {
                    fetchHistory(
                            requestGeneration,
                            type,
                            unit,
                            selectedHumidity,
                            start,
                            end
                    );
                }
            }
        });
    }

    private void fetchHistory(
            int requestGeneration,
            String type,
            String unit,
            boolean selectedHumidity,
            long start,
            long end
    ) {
        Call<HistoryResponse> call =
                ApiClient.get(this).getHistory(
                        selectedDevice,
                        utc(start),
                        utc(end),
                        type
                );

        calls.add(call);

        final boolean fahrenheit =
                !selectedHumidity && "°F".equals(unit);

        call.enqueue(new Callback<HistoryResponse>() {
            @Override
            public void onResponse(
                    @NonNull Call<HistoryResponse> call,
                    @NonNull Response<HistoryResponse> response
            ) {
                if (!current(requestGeneration)) {
                    return;
                }

                if (SessionGuard.handleUnauthorized(
                        HistoryActivity.this,
                        response
                )) {
                    return;
                }

                if (!response.isSuccessful()
                        || response.body() == null) {
                    showError(
                            "Не удалось загрузить историю. "
                                    + "Попробуйте ещё раз."
                    );

                    return;
                }

                HistoryResponse body = response.body();

                worker.execute(() -> {
                    List<ClimateChartView.Point> points =
                            new ArrayList<>();

                    if (body.points != null) {
                        for (HistoryPoint point : body.points) {
                            if (Thread.currentThread()
                                    .isInterrupted()) {
                                return;
                            }

                            if (point == null
                                    || !type.equals(point.type)
                                    || !Double.isFinite(point.value)) {
                                continue;
                            }

                            Long time = parseTimestamp(
                                    point.measuredAt
                            );

                            if (time != null
                                    && time >= start
                                    && time <= end) {
                                points.add(
                                        new ClimateChartView.Point(
                                                time,
                                                fahrenheit
                                                        ? Units.toFahrenheit(
                                                        point.value
                                                )
                                                        : point.value
                                        )
                                );
                            }
                        }
                    }

                    points.sort(
                            Comparator.comparingLong(
                                    point -> point.time
                            )
                    );

                    List<ClimateChartView.Point> unique =
                            new ArrayList<>();

                    for (ClimateChartView.Point point : points) {
                        int size = unique.size();

                        if (size > 0
                                && unique.get(size - 1).time
                                == point.time) {
                            unique.set(size - 1, point);
                        } else {
                            unique.add(point);
                        }
                    }

                    runOnUiThread(() -> {
                        if (current(requestGeneration)) {
                            renderHistory(
                                    unique,
                                    start,
                                    end,
                                    unit
                            );
                        }
                    });
                });
            }

            @Override
            public void onFailure(
                    @NonNull Call<HistoryResponse> call,
                    @NonNull Throwable error
            ) {
                if (current(requestGeneration)
                        && !call.isCanceled()) {
                    showError(
                            "Не удалось получить измерения. "
                                    + "Проверьте подключение "
                                    + "и попробуйте ещё раз."
                    );
                }
            }
        });
    }

    private void renderHistory(
            List<ClimateChartView.Point> points,
            long start,
            long end,
            String unit
    ) {
        status.setText("");
        status.setTextColor(GRAY);

        if (points.isEmpty()) {
            showMessage(
                    "Измерений пока нет",
                    "За выбранный период измерений нет. "
                            + "Выберите другой период.",
                    "Выбрать даты",
                    this::openDates
            );

            return;
        }

        empty.setVisibility(View.GONE);
        setDataVisible(true);

        chart.setComfort(lower, upper);
        chart.setSeries(points, start, end, unit);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double mean = 0;

        int count = 0;
        int inRange = 0;

        for (ClimateChartView.Point point : points) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);

            count++;
            mean += (point.value - mean) / count;

            if (lower != null
                    && upper != null
                    && point.value >= lower
                    && point.value <= upper) {
                inRange++;
            }
        }

        ClimateChartView.Point last =
                points.get(points.size() - 1);

        value.setText(number(last.value, unit));

        statValues[0].setText(number(min, unit));
        statValues[1].setText(number(mean, unit));
        statValues[2].setText(number(max, unit));

        boolean known = lower != null && upper != null;

        boolean ok = known
                && last.value >= lower
                && last.value <= upper;

        badge.setText(
                !known
                        ? "Нет порогов"
                        : ok
                        ? "В норме"
                        : last.value < lower
                        ? "Ниже нормы"
                        : "Выше нормы"
        );

        badge.setTextColor(
                !known ? GRAY : ok ? GREEN : ERROR
        );

        legend.setVisibility(
                known ? View.VISIBLE : View.GONE
        );
        if (known) {
            legend.setText(
                    String.format(
                            RU,
                            "Комфорт: %.1f–%.1f %s",
                            lower,
                            upper,
                            unit
                    )
            );
        }

        summary.removeAllViews();

        TextView summaryTitle = text(
                known
                        ? "Измерения за период"
                        : "История измерений",
                14,
                DARK
        );

        summaryTitle.setTypeface(null, Typeface.BOLD);
        add(summary, summaryTitle, 0);

        String summaryDescription = known
                ? String.format(
                RU,
                "%.0f%% полученных измерений — "
                        + "в пределах нормы",
                100.0 * inRange / points.size()
        )
                : "Настройте пороги, чтобы видеть "
                + "комфортный диапазон.";

        add(
                summary,
                text(summaryDescription, 12, GREEN),
                5
        );

        SimpleDateFormat format =
                new SimpleDateFormat("dd.MM HH:mm", RU);

        status.setText(
                "Последнее измерение в периоде: "
                        + format.format(new Date(last.time))
                        + "\nГрафик — от первого до последнего "
                        + "измерения. Пустые участки — перерывы в данных."
        );
    }

    private String number(double value, String unit) {
        return String.format(RU, "%.1f %s", value, unit);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (root != null) {
            active = true;
            loadSources();
        }
    }

    @Override
    protected void onPause() {
        active = false;
        cancelRequests();

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(
            @NonNull Bundle outState
    ) {
        outState.putInt("period", period);
        outState.putBoolean("humidity", humidity);
        outState.putLong("from", customStart);
        outState.putLong("to", customEnd);
        outState.putString("device", selectedDevice);

        super.onSaveInstanceState(outState);
    }
}