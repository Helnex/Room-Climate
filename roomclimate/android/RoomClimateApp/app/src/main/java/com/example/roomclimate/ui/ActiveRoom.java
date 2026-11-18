package com.example.roomclimate.ui;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.example.roomclimate.network.ApiClient;
import com.example.roomclimate.network.ServerConfig;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Какое помещение показывает дашборд. Плата дашборда хранится на телефоне (ServerConfig), но
 * показывать можно только плату из своих (или общих) помещений: иначе после смены аккаунта
 * новый пользователь видел показания чужой платы. Если запомненная плата не его — берётся
 * первая из его помещений; помещений с платой нет — дашборд просит добавить помещение.
 * Заодно запоминает понятные названия плат («Плата № 0002») для подписей.
 */
final class ActiveRoom {
    interface Result {
        /** room == null && known — у пользователя нет плат; known == false — сервер недоступен. */
        void done(String deviceId, String room, String boardTitle, boolean known);
    }

    private static final String KEY_TITLE = "board_title_";

    private ActiveRoom() {}

    static void resolve(Activity activity, Result result) {
        ApiClient.get(activity).getRooms().enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (activity.isFinishing()) {
                    return;
                }
                JsonObject body = response.body();
                if (!response.isSuccessful() || body == null) {
                    result.done(ServerConfig.getDeviceId(activity), null, null, false);
                    return;
                }
                pick(activity, body, result);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (!activity.isFinishing()) {
                    result.done(ServerConfig.getDeviceId(activity), null, null, false);
                }
            }
        });
    }

    private static void pick(Activity activity, JsonObject body, Result result) {
        String active = ServerConfig.getDeviceId(activity);
        String firstId = null;
        String firstRoom = null;
        String firstTitle = null;
        for (JsonObject room : Registry.list(Registry.array(body, "rooms"))) {
            for (JsonObject d : Registry.list(Registry.array(room, "devices"))) {
                String id = Api.str(d, "device_id");
                String title = Registry.nb(Api.str(d, "title"));
                remember(activity, id, title);
                if (id.equals(active)) {
                    result.done(id, Api.str(room, "name"), title, true);
                    return;
                }
                if (firstId == null) {
                    firstId = id;
                    firstRoom = Api.str(room, "name");
                    firstTitle = title;
                }
            }
        }
        // Плата без помещения (помещение удалили) на дашборд не попадает: показываем
        // только помещения, иначе после удаления всех помещений висели бы старые показания.
        if (firstId == null) {
            result.done(null, null, null, true);
            return;
        }
        ServerConfig.save(activity, ServerConfig.getBaseUrl(activity), firstId);
        result.done(firstId, firstRoom, firstTitle, true);
    }

    static void remember(Context context, String deviceId, String title) {
        if (deviceId != null && !deviceId.isEmpty() && title != null && !title.isEmpty()) {
            ServerConfig.prefs(context).edit().putString(KEY_TITLE + deviceId, title).apply();
        }
    }

    /** «Плата № 0002» вместо pi-2, если название уже приходило с сервера. */
    static String title(Context context, String deviceId) {
        return ServerConfig.prefs(context).getString(KEY_TITLE + deviceId, deviceId);
    }
}
