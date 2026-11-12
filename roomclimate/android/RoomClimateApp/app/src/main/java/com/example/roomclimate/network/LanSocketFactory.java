package com.example.roomclimate.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;

import javax.net.SocketFactory;

/**
 * Сервер стоит в домашней/учебной Wi‑Fi (192.168.x.x, 10.x.x.x).
 * Если в этой Wi‑Fi нет интернета или телефон предпочитает мобильные данные,
 * Android отправляет запросы приложения через SIM-карту, и до компьютера они
 * не доходят (ошибка «failed to connect … after 15000ms»).
 * Эта фабрика привязывает сокеты к Wi‑Fi (или Ethernet), минуя мобильную сеть и VPN.
 */
public final class LanSocketFactory extends SocketFactory {
    private final ConnectivityManager connectivity;
    private final SocketFactory fallback = SocketFactory.getDefault();

    private LanSocketFactory(Context context) {
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    /** Фабрика для локального адреса сервера или null, если сервер в интернете. */
    public static SocketFactory forBaseUrl(Context context, String baseUrl) {
        return isLocalHost(hostOf(baseUrl)) ? new LanSocketFactory(context) : null;
    }

    static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static boolean isLocalHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith(".local") || h.equals("localhost")) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        return a == 10
                || (a == 192 && b == 168)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 169 && b == 254);
    }

    private Network localNetwork() {
        if (connectivity == null) {
            return null;
        }
        Network ethernet = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
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

    private Socket bind(Socket socket) throws IOException {
        Network network = localNetwork();
        if (network != null) {
            network.bindSocket(socket);
        }
        return socket;
    }

    @Override
    public Socket createSocket() throws IOException {
        return bind(fallback.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new java.net.InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new java.net.InetSocketAddress(localHost, localPort));
        socket.connect(new java.net.InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new java.net.InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new java.net.InetSocketAddress(localAddress, localPort));
        socket.connect(new java.net.InetSocketAddress(address, port));
        return socket;
    }
}
