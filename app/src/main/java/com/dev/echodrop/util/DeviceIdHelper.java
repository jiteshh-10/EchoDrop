package com.dev.echodrop.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * Provides a persistent, unique device identifier for loop prevention
 * in store–carry–forward message propagation.
 *
 * <p>The ID is a short 8-character hex string derived from a randomly
 * generated UUID, persisted in SharedPreferences. It is created once
 * on first access and reused across app restarts.</p>
 */
public final class DeviceIdHelper {

    private static final String PREFS_NAME = "echodrop_device";
    private static final String KEY_DEVICE_ID = "device_id";

    private DeviceIdHelper() { /* Utility class */ }

    /**
     * Returns the persistent device ID, creating it on first call.
     *
     * @param context application context
     * @return 8-character hex device ID
     */
    @NonNull
    public static String getDeviceId(@NonNull final Context context) {
        final SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = generateDeviceId();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }

    /**
     * Generates a new 8-character hex device ID from a random UUID.
     *
     * @return 8-character hex string
     */
    @NonNull
    static String generateDeviceId() {
        final String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid.substring(0, 8);
    }
}
