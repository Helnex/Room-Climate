package com.example.roomclimate.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Чистая логика IPv4-подсетей без Android API (покрыта юнит-тестами).
 *
 * Нужна, чтобы найти сервер в трёх раскладах:
 *  - телефон и компьютер в одной Wi‑Fi;
 *  - телефон раздаёт интернет, компьютер подключён к нему (у телефона нет Wi‑Fi-сети,
 *    есть только интерфейс точки доступа: ap0 / swlan0 / wlan1 …);
 *  - компьютер раздаёт интернет, телефон подключён к нему (сервер = шлюз).
 */
public final class LocalNet {
    private LocalNet() {}

    /** IPv4 в виде int или -1, если строка не IPv4-адрес. */
    public static long toLong(String ip) {
        if (ip == null) {
            return -1;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return -1;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (octet < 0 || octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    public static String toIp(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    private static long mask(int prefix) {
        if (prefix <= 0) {
            return 0;
        }
        if (prefix >= 32) {
            return 0xFFFFFFFFL;
        }
        return (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    /** Лежит ли ip в подсети address/prefix. */
    public static boolean inSubnet(String ip, String address, int prefix) {
        long a = toLong(ip);
        long b = toLong(address);
        if (a < 0 || b < 0 || prefix < 0 || prefix > 32) {
            return false;
        }
        long m = mask(prefix);
        return (a & m) == (b & m);
    }

    /** Частный (LAN) адрес: 10/8, 172.16/12, 192.168/16, 169.254/16. */
    public static boolean isPrivate(String ip) {
        long v = toLong(ip);
        if (v < 0) {
            return false;
        }
        return inSubnet(ip, "10.0.0.0", 8)
                || inSubnet(ip, "172.16.0.0", 12)
                || inSubnet(ip, "192.168.0.0", 16)
                || inSubnet(ip, "169.254.0.0", 16);
    }

    /**
     * Интерфейс, через который телефон раздаёт интернет (точка доступа, USB- или
     * Bluetooth-модем). Такие интерфейсы Android не показывает как Network,
     * поэтому их приходится искать по имени.
     */
    public static boolean isTetherInterface(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("ap")          // ap0, ap_br_wlan2 (Samsung, Pixel)
                || n.startsWith("swlan")   // swlan0 (Xiaomi, Qualcomm)
                || n.startsWith("softap")
                || n.startsWith("wlan1")   // второй Wi‑Fi-интерфейс = точка доступа
                || n.startsWith("wlan2")
                || n.startsWith("wigig")
                || n.startsWith("rndis")   // USB-модем
                || n.startsWith("usb")
                || n.startsWith("ncm")
                || n.startsWith("bt-pan")  // Bluetooth-модем
                || n.startsWith("bnep");
    }

    /** Туннели VPN, сотовые и служебные интерфейсы — в них сервер не ищем. */
    public static boolean isIgnoredInterface(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("lo")
                || n.startsWith("tun")
                || n.startsWith("tap")
                || n.startsWith("ppp")
                || n.startsWith("ipsec")
                || n.startsWith("wg")
                || n.startsWith("clat")
                || n.startsWith("v4-")
                || n.startsWith("rmnet")
                || n.startsWith("ccmni")
                || n.startsWith("dummy")
                || n.startsWith("p2p");
    }

    /**
     * Адреса для перебора: подсеть интерфейса, но не шире /24 вокруг своего адреса
     * (у точки доступа бывает /24, у Wi‑Fi иногда /16 — 65 тысяч адресов не перебрать).
     * Свой адрес и адреса сети/широковещания пропускаются, шлюз идёт первым.
     */
    public static List<String> scanTargets(String ownIp, int prefix, String gateway) {
        List<String> result = new ArrayList<>();
        long own = toLong(ownIp);
        if (own < 0) {
            return result;
        }
        int p = Math.max(prefix, 24);
        if (p > 30) {
            p = 30;
        }
        long m = mask(p);
        long network = own & m;
        long broadcast = network | (~m & 0xFFFFFFFFL);
        long gw = toLong(gateway);
        if (gw >= 0 && gw != own && (gw & m) == network) {
            result.add(toIp(gw));
        }
        for (long host = network + 1; host < broadcast; host++) {
            if (host != own && host != gw) {
                result.add(toIp(host));
            }
        }
        return result;
    }

    /** Хост из URL вида http://host:port/... или пустая строка. */
    public static String hostOf(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl.trim()).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
