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

    private static final int GREEN = Color.parseColor("#397454");
    private static final int GRAY = Color.parseColor("#778178");
    private static final int GRID = Color.parseColor("#E5EAE2");

    // Показания сборщика приходят примерно каждые 10 секунд.
    private static final long GAP_MS = 60_000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Point> points = Collections.emptyList();
    private String unit = "";

    private Double comfortMin;
    private Double comfortMax;

    public ClimateChartView(Context context) {
        super(context);
    }

    public void setComfort(Double min, Double max) {
        comfortMin = min;
        comfortMax = max;
        invalidate();
    }

    public void setSeries(
            List<Point> data,
            long periodStart,
            long periodEnd,
            String newUnit
    ) {
        points = new ArrayList<>(data);
        unit = newUnit;

        setContentDescription(
                "График: " + points.size()
                        + " измерений, единицы " + unit
        );

        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float pointX(
            long time,
            long first,
            long last,
            float left,
            float right
    ) {
        if (first == last) {
            return (left + right) / 2f;
        }

        return left
                + (float) ((double) (time - first) / (last - first))
                * (right - left);
    }

    private float pointY(
            double value,
            double min,
            double max,
            float top,
            float bottom
    ) {
        return bottom
                - (float) ((value - min) / (max - min))
                * (bottom - top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(
                10 * getResources().getDisplayMetrics().scaledDensity
        );
        paint.setColor(GRAY);

        if (points.isEmpty()) {
            paint.setTextAlign(Paint.Align.CENTER);

            canvas.drawText(
                    "Нет измерений за выбранный период",
                    getWidth() / 2f,
                    getHeight() / 2f,
                    paint
            );

            return;
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (Point point : points) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);
        }

        if (comfortMin != null && comfortMax != null) {
            min = Math.min(min, comfortMin);
            max = Math.max(max, comfortMax);
        }

        double padding = Math.max(0.5, (max - min) * 0.12);

        min -= padding;
        max += padding;

        Locale ru = new Locale("ru", "RU");

        float labelWidth = 0;

        for (int i = 0; i <= 4; i++) {
            String label = String.format(
                    ru,
                    "%.1f",
                    max - (max - min) * i / 4.0
            );

            labelWidth = Math.max(
                    labelWidth,
                    paint.measureText(label)
            );
        }

        float left = labelWidth + dp(8);
        float right = getWidth() - dp(2);
        float top = dp(22);
        float bottom = getHeight() - dp(38);

        if (right <= left || bottom <= top) {
            return;
        }

        if (comfortMin != null && comfortMax != null) {
            paint.setColor(Color.parseColor("#EFF4EB"));

            canvas.drawRect(
                    left,
                    pointY(comfortMax, min, max, top, bottom),
                    right,
                    pointY(comfortMin, min, max, top, bottom),
                    paint
            );
        }

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;

            paint.setColor(GRID);
            paint.setStrokeWidth(dp(1));

            canvas.drawLine(left, y, right, y, paint);

            paint.setColor(GRAY);
            paint.setTextAlign(Paint.Align.RIGHT);

            canvas.drawText(
                    String.format(
                            ru,
                            "%.1f",
                            max - (max - min) * i / 4.0
                    ),
                    left - dp(8),
                    y + dp(3),
                    paint
            );
        }

        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(unit, 0, dp(12), paint);

        long first = points.get(0).time;
        long last = points.get(points.size() - 1).time;

        String pattern = last - first > 86_400_000L
                ? "dd.MM HH:mm"
                : "HH:mm";

        SimpleDateFormat format = new SimpleDateFormat(pattern, ru);

        int ticks = right - left > dp(260)
                && last - first < 86_400_000L
                ? 4
                : 2;

        if (first == last) {
            ticks = 0;
        }

        for (int i = 0; i <= ticks; i++) {
            float x = ticks == 0
                    ? (left + right) / 2f
                    : left + (right - left) * i / ticks;

            long time = ticks == 0
                    ? first
                    : first + (last - first) * i / ticks;

            paint.setTextAlign(
                    ticks == 0
                            ? Paint.Align.CENTER
                            : i == 0
                            ? Paint.Align.LEFT
                            : i == ticks
                            ? Paint.Align.RIGHT
                            : Paint.Align.CENTER
            );

            canvas.drawText(
                    format.format(new Date(time)),
                    x,
                    bottom + dp(19),
                    paint
            );
        }

        int saved = canvas.save();

        canvas.clipRect(
                left - dp(3),
                top - dp(3),
                right + dp(3),
                bottom + dp(3)
        );

        int segmentStart = 0;

        for (int i = 1; i <= points.size(); i++) {
            if (i < points.size()
                    && points.get(i).time
                    - points.get(i - 1).time <= GAP_MS) {
                continue;
            }

            Point firstPoint = points.get(segmentStart);

            float firstX = pointX(
                    firstPoint.time, first, last, left, right
            );

            float firstY = pointY(
                    firstPoint.value, min, max, top, bottom
            );

            float lastX = firstX;
            float lastY = firstY;

            Path line = new Path();
            line.moveTo(firstX, firstY);

            for (int j = segmentStart + 1; j < i; j++) {
                Point point = points.get(j);

                lastX = pointX(
                        point.time, first, last, left, right
                );

                lastY = pointY(
                        point.value, min, max, top, bottom
                );

                line.lineTo(lastX, lastY);
            }

            if (i - segmentStart > 1) {
                Path area = new Path(line);

                area.lineTo(lastX, bottom);
                area.lineTo(firstX, bottom);
                area.close();

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor("#DDEADC"));

                canvas.drawPath(area, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.2f));
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(GREEN);

                canvas.drawPath(line, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(GREEN);

            if (i - segmentStart == 1) {
                canvas.drawCircle(
                        firstX, firstY, dp(2.5f), paint
                );
            }

            if (i == points.size()) {
                canvas.drawCircle(
                        lastX, lastY, dp(4), paint
                );

                paint.setColor(Color.WHITE);

                canvas.drawCircle(
                        lastX, lastY, dp(1.5f), paint
                );
            }

            segmentStart = i;
        }

        canvas.restoreToCount(saved);
        paint.setStyle(Paint.Style.FILL);
    }
}