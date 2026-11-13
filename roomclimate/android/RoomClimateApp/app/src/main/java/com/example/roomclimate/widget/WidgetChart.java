package com.example.roomclimate.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Мини-график температуры за сутки для большого виджета.
 * Виджеты (RemoteViews) не умеют рисовать сами, поэтому график — картинка:
 * белая сглаженная линия, полупрозрачная заливка под ней и точка «сейчас», как в виджете
 * погоды iOS. Размер картинки ограничен, чтобы уложиться в лимит RemoteViews.
 */
final class WidgetChart {
    private WidgetChart() {}

    static Bitmap render(double[] values, int widthPx, int heightPx) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        if (values.length < 2) {
            return bitmap;
        }
        Canvas canvas = new Canvas(bitmap);
        float pad = heightPx * 0.12f;
        float[] xy = WidgetStyle.project(values, widthPx, heightPx, pad);

        Path line = new Path();
        line.moveTo(xy[0], xy[1]);
        for (int i = 1; i < values.length; i++) {
            // Сглаживание: кривая через середины соседних отрезков.
            float px = xy[2 * (i - 1)];
            float py = xy[2 * (i - 1) + 1];
            float cx = xy[2 * i];
            float cy = xy[2 * i + 1];
            line.quadTo(px, py, (px + cx) / 2, (py + cy) / 2);
        }
        float lastX = xy[xy.length - 2];
        float lastY = xy[xy.length - 1];
        line.lineTo(lastX, lastY);

        Path fill = new Path(line);
        fill.lineTo(lastX, heightPx);
        fill.lineTo(xy[0], heightPx);
        fill.close();
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(46, 255, 255, 255));
        canvas.drawPath(fill, fillPaint);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(3f, heightPx / 38f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        canvas.drawPath(line, stroke);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.WHITE);
        canvas.drawCircle(lastX, lastY, stroke.getStrokeWidth() * 1.6f, dot);
        return bitmap;
    }
}
