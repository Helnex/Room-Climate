package com.example.roomclimate.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClimateChartView extends View {

    public static final class Point {
        public final long time;
        public final double value;

        public Point(long time, double value) {
            this.time = time;
            this.value = value;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Point> points = Collections.emptyList();

    private long periodStart;
    private long periodEnd = 1;

    private String unit = "";

    public ClimateChartView(Context context) {
        super(context);
        setMinimumHeight(dp(250));
    }

    public void setSeries(
            List<Point> newPoints,
            long start,
            long end,
            String newUnit
    ) {
        points = new ArrayList<>(newPoints);
        periodStart = start;
        periodEnd = Math.max(start + 1, end);
        unit = newUnit;

        setContentDescription(
                "График: " + points.size() + " измерений, единицы " + unit
        );

        invalidate();
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(dp(1));
        paint.setTextSize(
                12 * getResources().getDisplayMetrics().scaledDensity
        );
        paint.setColor(Color.rgb(125, 132, 125));

        if (points.isEmpty()) {
            paint.setTextAlign(Paint.Align.CENTER);

            canvas.drawText(
                    "Нет данных для графика",
                    getWidth() / 2f,
                    getHeight() / 2f,
                    paint
            );

            return;
        }

        double min = points.get(0).value;
        double max = min;

        for (Point point : points) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);
        }

        double padding = Math.max((max - min) * 0.15, 0.5);

        min -= padding;
        max += padding;

        float left = dp(54);
        float right = getWidth() - dp(16);
        float top = dp(28);
        float bottom = getHeight() - dp(44);

        if (right <= left || bottom <= top) {
            return;
        }

        Locale locale = new Locale("ru", "RU");

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;

            paint.setColor(Color.rgb(227, 232, 224));
            paint.setStrokeWidth(dp(1));

            canvas.drawLine(left, y, right, y, paint);

            double axisValue = max - (max - min) * i / 4.0;

            paint.setColor(Color.rgb(125, 132, 125));
            paint.setTextAlign(Paint.Align.RIGHT);

            canvas.drawText(
                    String.format(locale, "%.1f", axisValue),
                    left - dp(8),
                    y + dp(4),
                    paint
            );
        }

        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(unit, left, top - dp(10), paint);

        String timePattern =
                periodEnd - periodStart > 2 * 86_400_000L
                        ? "dd.MM"
                        : "HH:mm";

        SimpleDateFormat timeFormat =
                new SimpleDateFormat(timePattern, locale);

        for (int i = 0; i <= 2; i++) {
            float x = left + (right - left) * i / 2f;

            long time = periodStart
                    + (periodEnd - periodStart) * i / 2;

            paint.setTextAlign(
                    i == 0
                            ? Paint.Align.LEFT
                            : i == 2
                            ? Paint.Align.RIGHT
                            : Paint.Align.CENTER
            );

            canvas.drawText(
                    timeFormat.format(new Date(time)),
                    x,
                    bottom + dp(24),
                    paint
            );
        }

        Path line = new Path();

        float firstX = 0;
        float lastX = 0;
        float lastY = 0;

        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);

            float x = left
                    + (float) (
                    (double) (point.time - periodStart)
                            / (periodEnd - periodStart)
            ) * (right - left);

            float y = bottom
                    - (float) (
                    (point.value - min) / (max - min)
            ) * (bottom - top);

            if (i == 0) {
                line.moveTo(x, y);
                firstX = x;
            } else {
                line.lineTo(x, y);
            }

            lastX = x;
            lastY = y;
        }

        int savedState = canvas.save();

        canvas.clipRect(left, top, right, bottom);

        Path area = new Path(line);
        area.lineTo(lastX, bottom);
        area.lineTo(firstX, bottom);
        area.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(222, 235, 218));

        canvas.drawPath(area, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(Color.rgb(60, 116, 88));

        canvas.drawPath(line, paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(lastX, lastY, dp(4), paint);

        canvas.restoreToCount(savedState);
    }
}