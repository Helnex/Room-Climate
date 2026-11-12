package com.example.roomclimate.network;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * Сервер стоит в локальной сети (192.168.x.x, 10.x.x.x …). Без этой фабрики Android
 * может отправить запросы через мобильный интернет или VPN, и до компьютера они не дойдут
 * («failed to connect … after 15000ms»).
 *
 * Сокет привязывается к той сети, где лежит адрес сервера:
 *  - Wi‑Fi / Ethernet — явной привязкой (мимо мобильных данных и VPN, если VPN это разрешает);
 *  - точка доступа / USB‑модем телефона — без привязки: Android сам маршрутизирует
 *    подсеть раздачи (привязка к Wi‑Fi в этом случае как раз ломала соединение).
 */
public final class LanSocketFactory extends SocketFactory {
    private final Context context;
    private final String host;
    private final SocketFactory fallback = SocketFactory.getDefault();

    private LanSocketFactory(Context context, String host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    /** Фабрика для локального адреса сервера или null, если сервер в интернете / на 127.0.0.1. */
    public static SocketFactory forBaseUrl(Context context, String baseUrl) {
        String host = hostOf(baseUrl);
        return isLocalHost(host) ? new LanSocketFactory(context, host) : null;
    }

    static String hostOf(String baseUrl) {
        return LocalNet.hostOf(baseUrl == null ? "" : baseUrl);
    }

    static boolean isLocalHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith(".local")) {
            return true;
        }
        return LocalNet.isPrivate(h);
    }

    private Socket bind(Socket socket) {
        LocalLinks.bind(LocalLinks.find(context, host), socket);
        return socket;
    }

    @Override
    public Socket createSocket() throws IOException {
        return bind(fallback.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }
}
