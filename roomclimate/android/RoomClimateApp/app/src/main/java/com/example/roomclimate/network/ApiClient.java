package com.example.roomclimate.network;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.example.roomclimate.ui.UserPrefs;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ApiClient {
    private static Retrofit retrofit;
    private static String currentBaseUrl;

    private ApiClient() {}

    public static synchronized ApiService get(Context context) {
        Context app = context.getApplicationContext();
        String baseUrl = ServerConfig.normalizeBaseUrl(ServerConfig.getBaseUrl(app));
        if (retrofit == null || !baseUrl.equals(currentBaseUrl)) {
            currentBaseUrl = baseUrl;
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor(chain -> authorize(app, chain));
            if ((app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // BASIC пишет в logcat только метод и URL, без тела (пароль туда не попадает).
                HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
                builder.addInterceptor(logging);
            }
            retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl + "/")
                    .client(builder.build())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit.create(ApiService.class);
    }

    /**
     * Подставляет токен сессии во все запросы к данным. Если сервер ответил 401
     * (токен истёк или сессия удалена), локальная сессия сбрасывается — экраны
     * отправят пользователя на вход.
     */
    private static Response authorize(Context app, okhttp3.Interceptor.Chain chain) throws java.io.IOException {
        Request request = chain.request();
        String path = request.url().encodedPath();
        boolean authEndpoint = path.endsWith("/auth/login") || path.endsWith("/auth/register");
        String token = UserPrefs.getToken(app);
        if (!authEndpoint && request.header("Authorization") == null && token != null && !token.isEmpty()) {
            request = request.newBuilder().header("Authorization", "Bearer " + token).build();
        }
        Response response = chain.proceed(request);
        if (response.code() == 401 && !authEndpoint && token != null && !token.isEmpty()) {
            UserPrefs.clearSession(app);
        }
        return response;
    }

    public static synchronized void reset() {
        retrofit = null;
        currentBaseUrl = null;
    }
}
