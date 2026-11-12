package com.example.roomclimate.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Все локальные сети телефона, где может стоять компьютер с Docker.
 *
 * 1. Wi‑Fi / Ethernet — обычные сети Android (Network). Сокеты привязываем к ним,
 *    чтобы запросы не ушли в мобильный интернет или VPN.
 * 2. Точка доступа / USB‑модем телефона — Android не отдаёт их как Network.
 *    Берём из java.net.NetworkInterface и ходим без привязки: система маршрутизирует
 *    подсеть раздачи через свою таблицу local_network, мимо мобильных данных.
 *
 * Если на телефоне VPN без исключения локальной сети, привязка к Wi‑Fi может быть
 * запрещена (EPERM) — тогда пробуем без привязки.
 */
public final class LocalLinks {
    public static final class Link {
        public final String iface;
        public final String address;
        public final int prefix;
        public final String gateway;
        /** null — интерфейс раздачи (точка доступа, USB/Bluetooth-модем). */
        public final Network network;

        Link(String iface, String address, int prefix, String gateway, Network network) {
            this.iface = iface;
            this.address = address;
            this.prefix = prefix;
            this.gateway = gateway;
            this.network = network;
        }

        public boolean isTether() {
            return network == null;
        }

        public boolean contains(String ip) {
            return LocalNet.inSubnet(ip, address, prefix);
        }

        @Override
        public String toString() {
            return iface + " " + address + "/" + prefix + (isTether() ? " (раздача)" : "");
        }
    }

    private LocalLinks() {}

    public static List<Link> collect(Context context) {
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ConnectivityManager cm = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                LinkProperties lp = cm.getLinkProperties(network);
                if (caps == null || lp == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                boolean local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_USB);
                if (!local) {
                    continue;
                }
                String gateway = null;
                for (RouteInfo route : lp.getRoutes()) {
                    InetAddress gw = route.getGateway();
                    if (route.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                        gateway = gw.getHostAddress();
                        break;
                    }
                }
                for (LinkAddress address : lp.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress()) {
                        String ip = address.getAddress().getHostAddress();
                        links.add(new Link(lp.getInterfaceName(), ip, address.getPrefixLength(), gateway, network));
                        seen.add(ip);
                    }
                }
            }
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || LocalNet.isIgnoredInterface(ni.getName())) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (seen.contains(ip) || !LocalNet.isPrivate(ip)) {
                        continue;
                    }
                    // Интерфейс с частным адресом, которого нет среди сетей Android, —
                    // это раздача (имя проверяем мягко: у прошивок оно разное).
                    if (LocalNet.isTetherInterface(ni.getName()) || ni.getName().startsWith("wlan")) {
                        links.add(new Link(ni.getName(), ip, ia.getNetworkPrefixLength(), null, null));
                        seen.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
            // На части прошивок перечисление интерфейсов запрещено — остаются сети Android.
        }
        return links;
    }

    public static Link find(Context context, String ip) {
        for (Link link : collect(context)) {
            if (link.contains(ip)) {
                return link;
            }
        }
        return null;
    }

    public static boolean vpnActive(Context context) {
        ConnectivityManager cm = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Привязывает сокет к Wi‑Fi/Ethernet, где лежит адрес. Для раздачи и при запрете
     * со стороны VPN оставляет сокет без привязки. Никогда не бросает исключение.
     */
    public static void bind(Link link, Socket socket) {
        if (link == null || link.network == null) {
            return;
        }
        try {
            link.network.bindSocket(socket);
        } catch (IOException | RuntimeException ignored) {
            // VPN запретил выбирать другую сеть — идём как получится (через системную маршрутизацию).
        }
    }

    /** HTTP-соединение к url через нужную сеть (без системного прокси). */
    public static HttpURLConnection open(Link link, URL url) throws IOException {
        if (link != null && link.network != null) {
            try {
                return (HttpURLConnection) link.network.openConnection(url, Proxy.NO_PROXY);
            } catch (IOException | RuntimeException ignored) {
                // См. bind(): пробуем без привязки.
            }
        }
        return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
    }
}
