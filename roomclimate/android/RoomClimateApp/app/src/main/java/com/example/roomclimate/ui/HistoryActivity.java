package com.example.roomclimate.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.models.HistoryPoint;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HistoryActivity extends AppCompatActivity {

    private static final Locale RU = new Locale("ru", "RU");

    private static final int GREEN = Color.rgb(60, 116, 88);
    private static final int DARK = Color.rgb(36, 53, 46);
    private static final int GRAY = Color.rgb(125, 132, 125);
    private static final int ERROR = Color.rgb(170, 65, 55);

    private Spinner metricSpinner;
    private Spinner periodSpinner;

    private TextView periodLabel;
    private TextView latestValue;
    private TextView statistics;
    private TextView status;

    private ClimateChartView chart;

    private Call<HistoryResponse> pendingCall;

    private int requestGeneration;
    private boolean screenActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createScreen();

        if (savedInstanceState != null) {
            metricSpinner.setSelection(
                    savedInstanceState.getInt("metric", 0)
            );

            periodSpinner.setSelection(
                    savedInstanceState.getInt("period", 0)
            );
        }

        AdapterView.OnItemSelectedListener listener =
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        if (screenActive) {
                            loadHistory();
                        }
                    }

                    @Override
                    public void onNothingSelected(
                            AdapterView<?> parent
                    ) {
                    }
                };

        metricSpinner.setOnItemSelectedListener(listener);
        periodSpinner.setOnItemSelectedListener(listener);

        BottomNav.bind(this, R.id.navHistory);
    }

    private void createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 241));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(24));

        scroll.addView(
                content,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        content.addView(text("КЛИМАТ ПОМЕЩЕНИЯ", 12, GRAY));
        content.addView(text("Графики", 32, DARK));

        addWithTopMargin(
                content,
                text("Период", 14, GRAY),
                20
        );

        periodSpinner = spinner(
                new String[]{
                        "24 часа",
                        "7 дней",
                        "30 дней"
                }
        );

        addWithTopMargin(content, periodSpinner, 6);

        periodLabel = text("", 12, GRAY);
        addWithTopMargin(content, periodLabel, 8);

        addWithTopMargin(
                content,
                text("Показатель", 14, GRAY),
                16
        );

        metricSpinner = spinner(
                new String[]{
                        "Температура, °C",
                        "Влажность, %"
                }
        );

        addWithTopMargin(content, metricSpinner, 6);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundedBackground(Color.WHITE));

        addWithTopMargin(content, card, 20);

        card.addView(
                text("Последнее измерение в периоде", 13, GRAY)
        );

        latestValue = text("—", 32, DARK);
        latestValue.setTypeface(null, Typeface.BOLD);

        addWithTopMargin(card, latestValue, 6);

        chart = new ClimateChartView(this);

        card.addView(
                chart,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(260)
                )
        );

        statistics = text("", 16, DARK);
        statistics.setLineSpacing(dp(6), 1f);

        addWithTopMargin(card, statistics, 12);

        TextView explanation = text(
                "Среднее рассчитано по полученным измерениям, "
                        + "без взвешивания по времени.",
                12,
                GRAY
        );

        addWithTopMargin(card, explanation, 12);

        status = text("", 13, GRAY);
        addWithTopMargin(content, status, 16);

        Button reload = new Button(this);
        reload.setText("Обновить");
        reload.setTextSize(16);
        reload.setTextColor(GREEN);
        reload.setAllCaps(false);
        reload.setOnClickListener(v -> loadHistory());

        addWithTopMargin(content, reload, 12);

        getLayoutInflater().inflate(
                R.layout.include_bottom_nav,
                root,
                true
        );

        setContentView(root);
    }

    private TextView text(
            String value,
            float size,
            int color
    ) {
        TextView view = new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        view.setLayoutParams(
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        return view;
    }

    private Spinner spinner(String[] options) {
        Spinner spinner = new Spinner(this);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                options
        );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spinner.setAdapter(adapter);
        spinner.setBackground(roundedBackground(Color.WHITE));
        spinner.setMinimumHeight(dp(56));
        spinner.setPadding(dp(12), 0, dp(12), 0);

        return spinner;
    }

    private GradientDrawable roundedBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(dp(22));

        return drawable;
    }

    private void addWithTopMargin(
            LinearLayout parent,
            View child,
            int marginDp
    ) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = dp(marginDp);

        parent.addView(child, params);
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onResume() {
        super.onResume();

        screenActive = true;
        loadHistory();
    }

    @Override
    protected void onPause() {
        screenActive = false;
        requestGeneration++;

        if (pendingCall != null) {
            pendingCall.cancel();
        }

        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(
                "metric",
                metricSpinner.getSelectedItemPosition()
        );

        outState.putInt(
                "period",
                periodSpinner.getSelectedItemPosition()
        );

        super.onSaveInstanceState(outState);
    }

    private int selectedDays() {
        int position = periodSpinner.getSelectedItemPosition();

        if (position == 1) {
            return 7;
        }

        if (position == 2) {
            return 30;
        }

        return 1;
    }

    private String toUtc(long timestamp) {
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

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
        };

        for (String pattern : patterns) {
            SimpleDateFormat format =
                    new SimpleDateFormat(pattern, Locale.US);

            format.setLenient(false);

            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(raw, position);

            if (date != null && position.getIndex() == raw.length()) {
                return date.getTime();
            }
        }

        return null;
    }

    private void loadHistory() {
        if (!screenActive) {
            return;
        }

        final int generation = ++requestGeneration;

        if (pendingCall != null) {
            pendingCall.cancel();
        }

        boolean humidity =
                metricSpinner.getSelectedItemPosition() == 1;

        final String type =
                humidity ? "humidity" : "temperature";

        final String unit = humidity ? "%" : "°C";

        final long end =
                System.currentTimeMillis() / 1000L * 1000L;

        final long start =
                end - selectedDays() * 86_400_000L;

        SimpleDateFormat labelFormat =
                new SimpleDateFormat("dd.MM.yyyy HH:mm", RU);

        periodLabel.setText(
                labelFormat.format(new Date(start))
                        + " — "
                        + labelFormat.format(new Date(end))
        );

        clearData();

        status.setTextColor(GRAY);
        status.setText("Загружаем историю…");

        pendingCall = ApiClient.get(this).getHistory(
                ServerConfig.getDeviceId(this),
                toUtc(start),
                toUtc(end),
                type
        );

        pendingCall.enqueue(new Callback<HistoryResponse>() {

            @Override
            public void onResponse(
                    @NonNull Call<HistoryResponse> call,
                    @NonNull Response<HistoryResponse> response
            ) {
                if (!isCurrentRequest(generation)) {
                    return;
                }

                if (!response.isSuccessful()
                        || response.body() == null) {

                    showError(
                            "Не удалось загрузить историю измерений. "
                                    + "Попробуйте ещё раз позже."
                    );

                    return;
                }

                showHistory(
                        response.body(),
                        type,
                        unit,
                        start,
                        end
                );
            }

            @Override
            public void onFailure(
                    @NonNull Call<HistoryResponse> call,
                    @NonNull Throwable error
            ) {
                if (call.isCanceled()
                        || !isCurrentRequest(generation)) {
                    return;
                }

                showError(
                        "Не удалось подключиться к серверу. "
                                + "Проверьте подключение к сети и попробуйте ещё раз."
                );
            }
        });
    }

    private boolean isCurrentRequest(int generation) {
        return screenActive
                && generation == requestGeneration
                && !isFinishing()
                && !isDestroyed();
    }

    private void showHistory(
            HistoryResponse body,
            String type,
            String unit,
            long start,
            long end
    ) {
        List<ClimateChartView.Point> points =
                new ArrayList<>();

        int skipped = 0;

        if (body.points != null) {
            for (HistoryPoint point : body.points) {
                if (point == null || !type.equals(point.type)) {
                    continue;
                }

                Long time = parseTimestamp(point.measuredAt);

                if (time == null
                        || Double.isNaN(point.value)
                        || Double.isInfinite(point.value)) {

                    skipped++;
                    continue;
                }

                if (time >= start && time <= end) {
                    points.add(
                            new ClimateChartView.Point(
                                    time,
                                    point.value
                            )
                    );
                }
            }
        }

        Collections.sort(
                points,
                Comparator.comparingLong(point -> point.time)
        );

        chart.setSeries(points, start, end, unit);

        if (points.isEmpty()) {
            status.setTextColor(GRAY);

            status.setText(
                    skipped > 0
                            ? "Не удалось отобразить измерения. Попробуйте обновить данные."
                            : "За выбранный период измерений нет. Выберите другой период."
            );

            return;
        }

        double min = points.get(0).value;
        double max = min;
        double mean = 0;

        int count = 0;

        for (ClimateChartView.Point point : points) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);

            count++;
            mean += (point.value - mean) / count;
        }

        ClimateChartView.Point last =
                points.get(points.size() - 1);

        latestValue.setText(
                String.format(RU, "%.1f %s", last.value, unit)
        );

        statistics.setText(
                String.format(
                        RU,
                        "Минимум: %.1f %s\n"
                                + "Среднее: %.1f %s\n"
                                + "Максимум: %.1f %s",
                        min,
                        unit,
                        mean,
                        unit,
                        max,
                        unit
                )
        );

        status.setTextColor(GREEN);

        status.setText(
                skipped > 0
                        ? "Некоторые измерения не удалось отобразить."
                        : "Данные обновлены."
        );
    }

    private void clearData() {
        latestValue.setText("—");
        statistics.setText("");

        chart.setSeries(
                Collections.emptyList(),
                0,
                1,
                ""
        );
    }

    private void showError(String message) {
        clearData();

        status.setTextColor(ERROR);
        status.setText(message);
    }
}