package com.example.roomclimate.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.SizeF;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.example.roomclimate.R;
import com.example.roomclimate.models.HistoryPoint;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.models.InsightsResponse;
import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.example.roomclimate.ui.DashboardActivity;
import com.example.roomclimate.ui.MainActivity;
import com.example.roomclimate.ui.UserPrefs;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Виджет «Климат» на главном экране (стиль виджетов iOS).
 *
 * Три раскладки, лаунчер выбирает по размеру: маленькая 2×2, широкая 4×2 и большая 4×4
 * с графиком температуры за сутки. В списке виджетов два варианта: «Климат» (2×2)
 * и «Климат — большой» (ClimateWidgetLarge, сразу 4×4) — код у них общий.
 *
 * Обновляется: системой раз в 30 минут, по кнопке ⟳ и каждую минуту, пока работает
 * мониторинг (push()). Последнее состояние и график хранятся в настройках — виджет
 * рисуется сразу и без связи. Карточка в шторке (ShadeCard) берёт те же данные.
 */
public class ClimateWidget extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.example.roomclimate.widget.REFRESH";
    private static final String PREFS = "roomclimate_widget";
    private static final String KEY_INSIGHTS = "insights";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_CHART = "chart";
    private static final String KEY_CHART_AT = "chart_at";
    private static final long CHART_MAX_AGE_MS = 10 * 60_000L;
    private static final int CHART_POINTS = 96;
    private static final Gson GSON = new Gson();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        refresh(context, goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refresh(context, goAsync());
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        render(context, manager, id); // растянули — перерисовать в нужной раскладке
    }

    // ---------- Данные ----------

    /** Свежие данные от мониторинга/экрана «Устройства»: сохранить, перерисовать виджеты и шторку. */
    public static void push(Context context, InsightsResponse insights) {
        if (insights == null) {
            return;
        }
        Context app = context.getApplicationContext();
        prefs(app).edit()
                .putString(KEY_INSIGHTS, GSON.toJson(insights))
                .putString(KEY_DEVICE, ServerConfig.getDeviceId(app))
                .apply();
        renderAll(app);
        ShadeCard.refresh(app);
        if (System.currentTimeMillis() - prefs(app).getLong(KEY_CHART_AT, 0) > CHART_MAX_AGE_MS) {
            loadChart(app, null);
        }
    }

    private static void refresh(Context context, PendingResult pending) {
        Context app = context.getApplicationContext();
        if (!UserPrefs.isLoggedIn(app)) {
            renderAll(app);
            pending.finish();
            return;
        }
        // Два запроса (сводка и график); приёмнику дают ~10 с — ждём каждый не дольше 8 с.
        AtomicInteger left = new AtomicInteger(2);
        Runnable done = () -> {
            if (left.decrementAndGet() == 0) {
                renderAll(app);
                ShadeCard.refresh(app);
                pending.finish();
            }
        };
        Call<InsightsResponse> call = ApiClient.get(app).getInsights(ServerConfig.getDeviceId(app), 24);
        call.timeout().timeout(8, TimeUnit.SECONDS);
        call.enqueue(new Callback<InsightsResponse>() {
            @Override
            public void onResponse(@NonNull Call<InsightsResponse> c, @NonNull Response<InsightsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    prefs(app).edit()
                            .putString(KEY_INSIGHTS, GSON.toJson(response.body()))
                            .putString(KEY_DEVICE, ServerConfig.getDeviceId(app))
                            .apply();
                }
                done.run();
            }

            @Override
            public void onFailure(@NonNull Call<InsightsResponse> c, @NonNull Throwable t) {
                done.run(); // остаются последние сохранённые значения
            }
        });
        loadChart(app, done);
    }

    /** История температуры за сутки (сервер усредняет до CHART_POINTS точек) для графика. */
    private static void loadChart(Context app, Runnable done) {
        // UTC без смещения: «+» в адресе сервер прочитал бы как пробел. Строки времени на
        // сервере сравниваются как текст, и «…T10:00:00» корректно меньше «…T10:00:00+00:00».
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String from = iso.format(new Date(System.currentTimeMillis() - 24 * 3600_000L));
        Call<HistoryResponse> call = ApiClient.get(app)
                .getHistoryCompact(ServerConfig.getDeviceId(app), from, "temperature", CHART_POINTS);
        call.timeout().timeout(8, TimeUnit.SECONDS);
        call.enqueue(new Callback<HistoryResponse>() {
            @Override
            public void onResponse(@NonNull Call<HistoryResponse> c, @NonNull Response<HistoryResponse> response) {
                HistoryResponse body = response.body();
                if (response.isSuccessful() && body != null && body.points != null) {
                    StringBuilder sb = new StringBuilder();
                    for (HistoryPoint p : body.points) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(p.value);
                    }
                    prefs(app).edit()
                            .putString(KEY_CHART, sb.toString())
                            .putLong(KEY_CHART_AT, System.currentTimeMillis())
                            .apply();
                    if (done == null) {
                        renderAll(app);
                    }
                }
                if (done != null) {
                    done.run();
                }
            }

            @Override
            public void onFailure(@NonNull Call<HistoryResponse> c, @NonNull Throwable t) {
                if (done != null) {
                    done.run();
                }
            }
        });
    }

    /** Последние сохранённые данные основной платы (или null). Используется и шторкой. */
    static InsightsResponse cached(Context context) {
        SharedPreferences p = prefs(context);
        if (!ServerConfig.getDeviceId(context).equals(p.getString(KEY_DEVICE, ""))) {
            return null; // данные другой платы (сменили основную) не показываем
        }
        try {
            return GSON.fromJson(p.getString(KEY_INSIGHTS, null), InsightsResponse.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static WidgetStyle.State stateOf(Context context, InsightsResponse d) {
        return WidgetStyle.stateOf(UserPrefs.isLoggedIn(context), d != null && d.temperature != null,
                d != null && d.online, d == null || d.comfort == null ? null : d.comfort.code);
    }

    private static double[] chartValues(Context context) {
        String raw = prefs(context).getString(KEY_CHART, "");
        List<Double> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                values.add(Double.parseDouble(part));
            } catch (NumberFormatException ignored) {
                // пустая строка или мусор — пропускаем
            }
        }
        return WidgetStyle.downsample(values, CHART_POINTS);
    }

    /** При выходе из аккаунта — стереть данные виджетов и шторки и показать «Войдите». */
    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
        renderAll(context);
        ShadeCard.refresh(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Отрисовка ----------

    public static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Class<?> provider : new Class<?>[]{ClimateWidget.class, ClimateWidgetLarge.class}) {
            for (int id : manager.getAppWidgetIds(new ComponentName(context, provider))) {
                render(context, manager, id);
            }
        }
    }

    private enum Size { SMALL, WIDE, LARGE }

    private static void render(Context context, AppWidgetManager manager, int id) {
        InsightsResponse data = cached(context);
        WidgetStyle.State state = stateOf(context, data);
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: лаунчер сам выбирает раскладку по фактическому размеру.
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(110f, 110f), build(context, Size.SMALL, state, data));
            bySize.put(new SizeF(250f, 110f), build(context, Size.WIDE, state, data));
            bySize.put(new SizeF(250f, 250f), build(context, Size.LARGE, state, data));
            views = new RemoteViews(bySize);
        } else {
            Bundle options = manager.getAppWidgetOptions(id);
            int w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            Size size;
            if (w == 0) {
                // Размер ещё неизвестен (только что добавили) — по типу виджета.
                AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                boolean large = info != null && ClimateWidgetLarge.class.getName().equals(info.provider.getClassName());
                size = large ? Size.LARGE : Size.SMALL;
            } else {
                size = WidgetStyle.isLarge(w, h) ? Size.LARGE : WidgetStyle.isWide(w) ? Size.WIDE : Size.SMALL;
            }
            views = build(context, size, state, data);
        }
        manager.updateAppWidget(id, views);
    }

    private static RemoteViews build(Context context, Size size, WidgetStyle.State state, InsightsResponse d) {
        int layout = size == Size.LARGE ? R.layout.widget_large
                : size == Size.WIDE ? R.layout.widget_medium : R.layout.widget_small;
        RemoteViews rv = new RemoteViews(context.getPackageName(), layout);
        rv.setInt(android.R.id.background, "setBackgroundResource", background(state));

        String device = ServerConfig.getDeviceId(context);
        rv.setTextViewText(R.id.wTitle, size == Size.SMALL
                ? context.getString(R.string.widget_title_short)
                : context.getString(R.string.widget_title, device));

        boolean show = d != null && state != WidgetStyle.State.SIGNED_OUT;
        String temp = show ? WidgetStyle.temperature(d.temperature) : "—°";
        String humidity = show ? WidgetStyle.humidity(d.humidity) : "—%";
        rv.setTextViewText(R.id.wTemp, temp);

        String line = statusLine(context, state, d, humidity, size == Size.SMALL);
        rv.setTextViewText(R.id.wLine, line);
        rv.setContentDescription(android.R.id.background,
                context.getString(R.string.widget_a11y, device, temp, humidity, line));

        if (size == Size.SMALL) {
            boolean showDrop = state != WidgetStyle.State.SIGNED_OUT && state != WidgetStyle.State.NO_DATA
                    && state != WidgetStyle.State.SILENT;
            rv.setViewVisibility(R.id.wDropIcon, showDrop ? android.view.View.VISIBLE : android.view.View.GONE);
        } else {
            rv.setTextViewText(R.id.wUpdated, show ? updatedText(context, d, state) : "");
            InsightsResponse.Stats t = show && d.stats != null ? d.stats.get("temperature") : null;
            String range = t != null
                    ? context.getString(R.string.widget_range, WidgetStyle.temperature(t.min), WidgetStyle.temperature(t.max))
                    : "";
            rv.setTextViewText(R.id.wRange, range);
            String dew = show && d.dewPoint != null ? WidgetStyle.temperature(d.dewPoint) : "—°";
            if (size == Size.WIDE) {
                rv.setTextViewText(R.id.wHumidity, show ? context.getString(R.string.widget_humidity, humidity) : "");
                rv.setTextViewText(R.id.wDew, show && d.dewPoint != null ? context.getString(R.string.widget_dew, dew) : "");
            } else {
                rv.setTextViewText(R.id.wTileHumidity, tile(context.getString(R.string.widget_tile_humidity), humidity));
                rv.setTextViewText(R.id.wTileDew, tile(context.getString(R.string.widget_tile_dew), dew));
                String mold = show && d.moldRisk != null && d.moldRisk.label != null ? d.moldRisk.label : "—";
                rv.setTextViewText(R.id.wTileMold, tile(context.getString(R.string.widget_tile_mold), mold));
                String advice = show && d.advice != null && !d.advice.isEmpty() ? d.advice.get(0) : "";
                rv.setTextViewText(R.id.wAdvice, advice);
                double[] values = show ? chartValues(context) : new double[0];
                if (values.length >= 2) {
                    rv.setImageViewBitmap(R.id.wChart, WidgetChart.render(values, 640, 200));
                } else {
                    rv.setImageViewResource(R.id.wChart, android.R.color.transparent);
                }
            }
        }

        rv.setImageViewResource(R.id.wRefresh, icon(state));
        rv.setOnClickPendingIntent(android.R.id.background, openApp(context, state));
        rv.setOnClickPendingIntent(R.id.wRefresh, refreshIntent(context));
        return rv;
    }

    /** Подпись под температурой; в маленьком виджете впереди влажность. */
    static String statusLine(Context context, WidgetStyle.State state, InsightsResponse d, String humidity, boolean compact) {
        switch (state) {
            case SIGNED_OUT:
                return context.getString(R.string.widget_signed_out);
            case NO_DATA:
                return context.getString(R.string.widget_no_data);
            case SILENT:
                return context.getString(R.string.widget_silent, WidgetStyle.silentFor(d.ageSeconds));
            default:
                String label = d.comfort != null && d.comfort.label != null ? d.comfort.label : "";
                return compact ? humidity + "  ·  " + label : label;
        }
    }

    /** Плитка: мелкая подпись и крупное значение на второй строке. */
    private static CharSequence tile(String label, String value) {
        SpannableStringBuilder sb = new SpannableStringBuilder(label).append('\n');
        int start = sb.length();
        sb.append(value);
        sb.setSpan(new RelativeSizeSpan(1.35f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    static String updatedText(Context context, InsightsResponse d, WidgetStyle.State state) {
        String time = localTime(d.lastSeen);
        return state == WidgetStyle.State.SILENT ? time : context.getString(R.string.widget_online, time);
    }

    /** ISO-время сервера (UTC) -> «14:32» по часам телефона. */
    static String localTime(String iso) {
        if (iso == null) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            Date date = in.parse(iso.replace("Z", "+00:00"));
            return date == null ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        } catch (Exception e) {
            return "";
        }
    }

    private static int background(WidgetStyle.State state) {
        switch (state) {
            case COMFORT:
                return R.drawable.widget_bg_comfort;
            case HOT:
                return R.drawable.widget_bg_hot;
            case COLD:
                return R.drawable.widget_bg_cold;
            case HUMID:
                return R.drawable.widget_bg_humid;
            case DRY:
                return R.drawable.widget_bg_dry;
            default:
                return R.drawable.widget_bg_silent;
        }
    }

    /** Значок в углу: ⟳ в норме, предупреждение/снежинка/без сети по состоянию. */
    static int icon(WidgetStyle.State state) {
        switch (state) {
            case HOT:
            case HUMID:
            case DRY:
                return R.drawable.ic_widget_warning;
            case COLD:
                return R.drawable.ic_widget_cold;
            case SILENT:
                return R.drawable.ic_widget_offline;
            default:
                return R.drawable.ic_widget_refresh;
        }
    }

    static PendingIntent openApp(Context context, WidgetStyle.State state) {
        Class<?> target = state == WidgetStyle.State.SIGNED_OUT ? MainActivity.class : DashboardActivity.class;
        Intent intent = new Intent(context, target).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 7001, intent, flags());
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, ClimateWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 7002, intent, flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
