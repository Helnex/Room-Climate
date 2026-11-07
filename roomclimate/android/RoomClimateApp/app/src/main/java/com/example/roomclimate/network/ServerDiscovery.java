package com.example.roomclimate.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Эмулятор ходит на 10.0.2.2. Телефон сам ищет компьютер с Docker в своей Wi‑Fi,
 * запросы идут мимо VPN.
 */
public final class ServerDiscovery {
    public interface Callback {
        void onResult(String baseUrl);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final List<Callback> WAITERS = new ArrayList<>();
    private static String cachedUrl;
    private static boolean searching;

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

    private static void deliver(Callback callback, String url) {
        MAIN.post(() -> callback.onResult(url));
    }

    private static String resolve(Context context) {
        if (isEmulator()) {
            ServerConfig.saveBaseUrl(context, ServerConfig.DEFAULT_BASE_URL);
            return ServerConfig.DEFAULT_BASE_URL;
        }

        Network wifi = wifiNetwork(context);
        if (wifi == null) {
            return null;
        }

        String saved = ServerConfig.getBaseUrl(context);
        if (saved != null && !saved.contains("10.0.2.2") && health(wifi, saved)) {
            return saved;
        }

        String localIp = ipv4(context, wifi);
        if (localIp == null) {
            return null;
        }
        int dot = localIp.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String prefix = localIp.substring(0, dot + 1);
        AtomicReference<String> found = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int host = 1; host <= 254; host++) {
            String ip = prefix + host;
            if (ip.equals(localIp)) {
                continue;
            }
            pool.execute(() -> {
                if (found.get() != null) {
                    return;
                }
                String url = "http://" + ip + ":8000";
                if (health(wifi, url)) {
                    found.compareAndSet(null, url);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        String url = found.get();
        if (url != null) {
            ServerConfig.saveBaseUrl(context, url);
        }
        return url;
    }

    private static Network wifiNetwork(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return null;
        }
        Network ethernet = null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernet = network;
            }
        }
        return ethernet;
    }

    private static String ipv4(Context context, Network network) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getLinkProperties(network) == null) {
            return null;
        }
        for (LinkAddress address : cm.getLinkProperties(network).getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress()) {
                return address.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private static boolean health(Network network, String baseUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(ServerConfig.normalizeBaseUrl(baseUrl) + "/health");
            connection = (HttpURLConnection) network.openConnection(url);
            connection.setConnectTimeout(400);
            connection.setReadTimeout(400);
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
