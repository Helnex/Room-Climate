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
}
