package com.example.roomclimate.network;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.example.roomclimate.R;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ищет компьютер с Docker-сервером.
 *
 * Порядок проверки:
 *  1. сохранённый адрес;
 *  2. http://127.0.0.1:8000 — телефон по USB с «adb reverse tcp:8000 tcp:8000»
 *     (работает при любом VPN на телефоне и компьютере);
 *  3. шлюзы локальных сетей (компьютер раздаёт интернет телефону);
 *  4. перебор подсетей Wi‑Fi и точки доступа телефона (телефон раздаёт интернет компьютеру).
 *
 * Эмулятор сразу ходит на 10.0.2.2.
 */
public final class ServerDiscovery {
    public interface Callback {
        void onResult(String baseUrl);
    }

    public static final int PORT = 8000;
    public static final String USB_URL = "http://127.0.0.1:" + PORT;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final List<Callback> WAITERS = new ArrayList<>();
    private static String cachedUrl;
    private static boolean searching;
    private static volatile int lastFailure = R.string.server_not_found;

    private ServerDiscovery() {}

    public static void ensure(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        boolean start;
        synchronized (LOCK) {
            if (cachedUrl != null) {
                deliver(callback, cachedUrl);
                return;
            }
            WAITERS.add(callback);
            start = !searching;
            searching = true;
        }
        if (!start) {
            return;
        }
        new Thread(() -> {
            String found = resolve(app);
            List<Callback> copy;
            synchronized (LOCK) {
                if (found != null) {
                    cachedUrl = found;
                }
                searching = false;
                copy = new ArrayList<>(WAITERS);
                WAITERS.clear();
            }
            for (Callback waiter : copy) {
                deliver(waiter, found);
            }
        }, "server-discovery").start();
    }

    /**
     * Проверить конкретный адрес (из QR-кода страницы /connect) и, если сервер отвечает,
     * сохранить его. Callback получает адрес или null.
     */
    /** Адрес в локальной сети (частный IP, 127.x, *.local)? */
    public static boolean isLocalUrl(String rawUrl) {
        String host = LocalNet.hostOf(ServerConfig.normalizeBaseUrl(rawUrl)).toLowerCase(java.util.Locale.ROOT);
        return LocalNet.isPrivate(host) || host.startsWith("127.") || host.endsWith(".local");
    }

    /**
     * Проверить конкретный адрес (из QR-кода страницы /connect) и, если сервер отвечает,
     * сохранить его. Callback получает адрес или null.
     *
     * Локальный адрес: http, либо https с отпечатком ключа (самоподписанный сертификат стенда).
     * Внешний адрес (Cloudflare Tunnel): только https с обычной проверкой сертификата и только
     * если пользователь подтвердил его в диалоге (remoteConfirmed) — ссылку roomclimate://
     * может открыть любой сайт, и без подтверждения он подменил бы сервер.
     */
    public static void useUrl(Context context, String rawUrl, String pin, boolean remoteConfirmed, Callback callback) {
        Context app = context.getApplicationContext();
        String url = ServerConfig.normalizeBaseUrl(rawUrl);
        new Thread(() -> {
            String ok = null;
            boolean https = url.startsWith("https://");
            boolean local = isLocalUrl(url);
            boolean pinned = https && local;
            boolean allowed = local
                    ? ServerConfig.isAllowedBaseUrl(url) && (!https || TlsPinning.isValidPin(pin))
                    : https && remoteConfirmed;
            if (allowed) {
                List<LocalLinks.Link> links = LocalLinks.collect(app);
                if (health(links, LocalLinks.vpnActive(app), url, pinned ? pin : null, 5000)) {
                    ServerConfig.saveWithPin(app, url, pinned ? pin : "");
                    ok = remember(app, url);
                    synchronized (LOCK) {
                        cachedUrl = ok;
                    }
                }
            }
            deliver(callback, ok);
        }, "server-connect").start();
    }

    /** Сбросить найденный адрес (например, сменилась сеть) и искать заново. */
    public static void forget() {
        synchronized (LOCK) {
            cachedUrl = null;
        }
    }

    /** Текст-подсказка, почему сервер не найден (ресурс строки). */
    public static int lastFailureMessage() {
        return lastFailure;
    }

    private static void deliver(Callback callback, String url) {
        MAIN.post(() -> callback.onResult(url));
    }

    private static String resolve(Context context) {
        if (isEmulator()) {
            ServerConfig.saveBaseUrl(context, ServerConfig.DEFAULT_BASE_URL);
            return ServerConfig.DEFAULT_BASE_URL;
        }

        List<LocalLinks.Link> links = LocalLinks.collect(context);
        boolean vpn = LocalLinks.vpnActive(context);

        // Быстрые кандидаты: сохранённый адрес, USB, шлюзы.
        Set<String> quick = new LinkedHashSet<>();
        String saved = ServerConfig.getBaseUrl(context);
        final String savedUrl = saved == null ? "" : ServerConfig.normalizeBaseUrl(saved);
        final String savedPin = ServerConfig.getPin(context);
        if (saved != null && !saved.contains("10.0.2.2")) {
            quick.add(ServerConfig.normalizeBaseUrl(saved));
        }
        quick.add(USB_URL);
        for (LocalLinks.Link link : links) {
            if (link.gateway != null) {
                quick.add("http://" + link.gateway + ":" + PORT);
            }
        }
        for (String url : quick) {
            if (health(links, vpn, url, url.equals(savedUrl) ? savedPin : null, 700)) {
                return remember(context, url);
            }
        }

        if (links.isEmpty()) {
            lastFailure = vpn ? R.string.server_not_found_vpn : R.string.server_not_found_no_lan;
            return null;
        }

        // Перебор подсетей всех локальных интерфейсов, включая точку доступа.
        Set<String> targets = new LinkedHashSet<>();
        for (LocalLinks.Link link : links) {
            for (String ip : LocalNet.scanTargets(link.address, link.prefix, link.gateway)) {
                targets.add("http://" + ip + ":" + PORT);
            }
        }
        targets.removeAll(quick);

        AtomicReference<String> found = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (String url : targets) {
            pool.execute(() -> {
                if (found.get() == null && health(links, vpn, url, null, 450)) {
                    found.compareAndSet(null, url);
                }
            });
        }
        pool.shutdown();
        try {
            long deadline = System.currentTimeMillis() + 12_000;
            while (found.get() == null && !pool.isTerminated() && System.currentTimeMillis() < deadline) {
                pool.awaitTermination(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        String url = found.get();
        if (url != null) {
            return remember(context, url);
        }
        lastFailure = vpn ? R.string.server_not_found_vpn : R.string.server_not_found;
        return null;
    }

    private static String remember(Context context, String url) {
        ServerConfig.saveBaseUrl(context, url);
        lastFailure = R.string.server_not_found;
        return url;
    }

    /**
     * GET /health. Сначала через «свою» сеть (Wi‑Fi привязкой, раздачу — системной
     * маршрутизацией). Если на телефоне VPN и привязанный запрос не прошёл —
     * повторяем без привязки: часть VPN пропускает локальную сеть только так.
     */
    static boolean health(List<LocalLinks.Link> links, boolean vpn, String baseUrl, String pin, int timeoutMs) {
        String host = LocalNet.hostOf(baseUrl);
        LocalLinks.Link link = null;
        for (LocalLinks.Link candidate : links) {
            if (candidate.contains(host)) {
                link = candidate;
                break;
            }
        }
        if (probe(link, baseUrl, pin, timeoutMs)) {
            return true;
        }
        return vpn && link != null && link.network != null && probe(null, baseUrl, pin, timeoutMs);
    }

    private static boolean probe(LocalLinks.Link link, String baseUrl, String pin, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(ServerConfig.normalizeBaseUrl(baseUrl) + "/health");
            connection = LocalLinks.open(link, url);
            TlsPinning.apply(connection, pin);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs + 300);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) {
                return false;
            }
            byte[] buffer = new byte[128];
            int read = connection.getInputStream().read(buffer);
            if (read <= 0) {
                return false;
            }
            String body = new String(buffer, 0, read);
            return body.contains("\"status\"");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE;
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT;
        return fingerprint.startsWith("generic")
                || fingerprint.contains("emulator")
                || fingerprint.contains("sdk_gphone")
                || model.contains("Emulator")
                || model.contains("Android SDK built for")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || product.contains("sdk");
    }
}
