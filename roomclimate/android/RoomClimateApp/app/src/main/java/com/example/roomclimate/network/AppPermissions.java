package com.example.roomclimate.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class AppPermissions {
    public static final int REQUEST_CODE = 42;
    private static final int LOCAL_NETWORK_SDK = 37;

    private AppPermissions() {}

    public static boolean hasLocalNetwork(Context context) {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void request(AppCompatActivity activity) {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    needed.toArray(new String[0]),
                    REQUEST_CODE
            );
        }
    }
}
