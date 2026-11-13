package com.example.roomclimate.network;

import com.example.roomclimate.models.AuthRequest;
import com.example.roomclimate.models.AuthResponse;
import com.example.roomclimate.models.CurrentTelemetryResponse;
import com.example.roomclimate.models.HealthResponse;
import com.example.roomclimate.models.HistoryResponse;
import com.example.roomclimate.models.SettingsRequest;
import com.example.roomclimate.models.SettingsResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Query;

public interface ApiService {
    @GET("health")
    Call<HealthResponse> getHealth();

    @POST("auth/register")
    Call<AuthResponse> register(@Body AuthRequest body);

    @POST("auth/login")
    Call<AuthResponse> login(@Body AuthRequest body);

    @GET("auth/me")
    Call<AuthResponse> me(@Header("Authorization") String authorization);

    @POST("auth/logout")
    Call<Void> logout(@Header("Authorization") String authorization);

    @GET("telemetry/current")
    Call<CurrentTelemetryResponse> getCurrent(@Query("device_id") String deviceId);

    @GET("telemetry/history")
    Call<HistoryResponse> getHistory(
            @Query("device_id") String deviceId,
            @Query("from") String from,
            @Query("to") String to,
            @Query("type") String type
    );

    @GET("settings")
    Call<SettingsResponse> getSettings(@Query("device_id") String deviceId);

    @PUT("settings")
    Call<SettingsResponse> putSettings(
            @Query("device_id") String deviceId,
            @Body SettingsRequest body
    );

    // ---- Устройства, аналитика, экспорт (экран «Устройства») ----

    @GET("devices")
    Call<com.example.roomclimate.models.DevicesResponse> getDevices();

    @GET("telemetry/insights")
    Call<com.example.roomclimate.models.InsightsResponse> getInsights(
            @Query("device_id") String deviceId,
            @Query("hours") int hours
    );

    @GET("telemetry/export.csv")
    Call<okhttp3.ResponseBody> exportCsv(
            @Query("device_id") String deviceId,
            @Query("hours") int hours
    );

    /** История с ограничением числа точек (сервер усредняет) — для мини-графика виджета. */
    @GET("telemetry/history")
    Call<HistoryResponse> getHistoryCompact(
            @Query("device_id") String deviceId,
            @Query("from") String from,
            @Query("type") String type,
            @Query("max_points") int maxPoints
    );
    // ---- Восстановление пароля по коду ----

    @POST("auth/password/forgot")
    Call<com.google.gson.JsonObject> forgotPassword(@Body com.google.gson.JsonObject body);

    @POST("auth/password/reset")
    Call<com.google.gson.JsonObject> resetPassword(@Body com.google.gson.JsonObject body);

    // ---- Владельцы, тревоги, push, аккаунт ----

    @POST("devices/{device_id}/claim")
    Call<com.google.gson.JsonObject> claimDevice(@retrofit2.http.Path("device_id") String deviceId);

    @POST("devices/{device_id}/members")
    Call<com.google.gson.JsonObject> addMember(
            @retrofit2.http.Path("device_id") String deviceId,
            @Body com.google.gson.JsonObject body
    );

    @GET("alerts")
    Call<com.google.gson.JsonObject> getAlerts(@Query("device_id") String deviceId, @Query("limit") int limit);

    @GET("notify/info")
    Call<com.google.gson.JsonObject> getNotifyInfo();

    @retrofit2.http.DELETE("auth/me")
    Call<com.google.gson.JsonObject> deleteAccount();

    // ---- «Мой дом» из макета: помещения, датчики, профиль, семья, безопасность, помощь ----

    @GET("rooms")
    Call<com.google.gson.JsonObject> getRooms();

    @POST("rooms")
    Call<com.google.gson.JsonObject> createRoom(@Body com.google.gson.JsonObject body);

    @retrofit2.http.PATCH("rooms/{room_id}")
    Call<com.google.gson.JsonObject> renameRoom(@retrofit2.http.Path("room_id") int roomId,
                                                @Body com.google.gson.JsonObject body);

    @retrofit2.http.DELETE("rooms/{room_id}")
    Call<com.google.gson.JsonObject> deleteRoom(@retrofit2.http.Path("room_id") int roomId);

    @POST("devices/{device_id}/setup")
    Call<com.google.gson.JsonObject> setupDevice(@retrofit2.http.Path("device_id") String deviceId,
                                                 @Body com.google.gson.JsonObject body);

    @retrofit2.http.PATCH("auth/me")
    Call<com.google.gson.JsonObject> updateProfile(@Body com.google.gson.JsonObject body);

    @POST("auth/password/change")
    Call<com.google.gson.JsonObject> changePassword(@Body com.google.gson.JsonObject body);

    @GET("auth/sessions")
    Call<com.google.gson.JsonObject> getSessions();

    @retrofit2.http.DELETE("auth/sessions/others")
    Call<com.google.gson.JsonObject> logoutOthers();

    @GET("family")
    Call<com.google.gson.JsonObject> getFamily();

    @POST("family")
    Call<com.google.gson.JsonObject> inviteFamily(@Body com.google.gson.JsonObject body);

    @retrofit2.http.DELETE("family")
    Call<com.google.gson.JsonObject> removeFamily(@Query("email") String email);

    @POST("support")
    Call<com.google.gson.JsonObject> sendSupport(@Body com.google.gson.JsonObject body);
}
