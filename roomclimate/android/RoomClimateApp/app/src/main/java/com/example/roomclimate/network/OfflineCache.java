package com.example.roomclimate.network;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Офлайн-режим: последний успешный ответ каждого GET-запроса (текущие показания, история,
 * пороги, устройства) сохраняется во внутренний кэш. Если сервер недоступен, экран получает
 * последние данные вместо ошибки — с заголовком X-Offline-Cache и временем в самих данных.
 *
 * Работает для всех экранов сразу (включая графики), без изменения их кода.
 * Кэш в cacheDir (недоступен другим приложениям) и очищается при выходе из аккаунта.
 */
public final class OfflineCache implements Interceptor {
    public static final String HEADER = "X-Offline-Cache";
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    private final File dir;

    public OfflineCache(Context context) {
        this.dir = new File(context.getApplicationContext().getCacheDir(), "offline");
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        boolean cacheable = "GET".equals(request.method()) && !request.url().encodedPath().endsWith(".csv")
                && !request.url().encodedPath().endsWith("/health");
        if (!cacheable) {
            return chain.proceed(request);
        }
        File file = new File(dir, key(request));
        try {
            Response response = chain.proceed(request);
            if (response.isSuccessful()) {
                ResponseBody body = response.body();
                if (body != null && body.contentLength() <= MAX_BODY_BYTES) {
                    byte[] bytes = body.bytes();
                    save(file, bytes);
                    MediaType type = body.contentType();
                    return response.newBuilder().body(ResponseBody.create(bytes, type)).build();
                }
            }
            return response;
        } catch (IOException networkError) {
            if (!file.exists()) {
                throw networkError;
            }
            byte[] bytes = read(file);
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK (offline)")
                    .header(HEADER, String.valueOf(file.lastModified()))
                    .body(ResponseBody.create(bytes, MediaType.get("application/json; charset=utf-8")))
                    .build();
        }
    }

    private static byte[] read(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void save(File file, byte[] bytes) {
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }

    /** Имя файла — хэш URL (без токена: он в заголовке, а кэш и так очищается при выходе). */
    static String key(Request request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(request.url().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(request.url().toString().hashCode());
        }
    }

    public static void clear(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), "offline");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
