package com.example.roomclimate.network;

import android.util.Base64;

import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS к серверу стенда с самоподписанным сертификатом.
 *
 * У локального сервера нет домена и сертификата от удостоверяющего центра, поэтому обычная
 * проверка цепочки невозможна. Вместо неё — «прибитый» ключ (pinning, как OkHttp
 * CertificatePinner): принимается только сертификат, чей SubjectPublicKeyInfo даёт SHA-256,
 * переданный через QR-код страницы /connect. Чужой сервер (подмена в Wi‑Fi, MITM) не пройдёт,
 * даже если подставит сертификат от настоящего центра.
 */
public final class TlsPinning {
    private TlsPinning() {}

    /** Формат "sha256/<base64 44 символа>". */
    public static boolean isValidPin(String pin) {
        return pin != null && pin.matches("^sha256/[A-Za-z0-9+/]{43}=$");
    }

    public static String pinOf(X509Certificate cert) throws Exception {
        byte[] spki = cert.getPublicKey().getEncoded();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    public static final class PinnedTrustManager implements X509TrustManager {
        private final String pin;

        public PinnedTrustManager(String pin) {
            this.pin = pin;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Пустая цепочка сертификатов");
            }
            try {
                if (!MessageDigest.isEqual(pin.getBytes(), pinOf(chain[0]).getBytes())) {
                    throw new CertificateException("Ключ сервера не совпадает с сохранённым (возможна подмена)");
                }
            } catch (CertificateException e) {
                throw e;
            } catch (Exception e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Клиентские сертификаты не используются");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public static SSLSocketFactory socketFactory(X509TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Имя хоста не проверяем: у сервера в Wi‑Fi нет имени, а подлинность уже доказана ключом
     * в PinnedTrustManager (без совпадения pin соединение не установится).
     */
    public static final HostnameVerifier PINNED_HOSTS = (hostname, session) -> true;

    /** Для HttpURLConnection (поиск сервера, проверка связи). */
    public static void apply(HttpURLConnection connection, String pin) {
        if (connection instanceof HttpsURLConnection && isValidPin(pin)) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(socketFactory(new PinnedTrustManager(pin)));
            https.setHostnameVerifier(PINNED_HOSTS);
        }
    }
}
