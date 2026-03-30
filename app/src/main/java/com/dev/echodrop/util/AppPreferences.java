package com.dev.echodrop.util;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Central app-level user preferences used across screens and services.
 */
public final class AppPreferences {

    private static final String PREFS_NAME = "echodrop_user_settings";

    private static final String KEY_MESSAGE_ALERTS_ENABLED = "message_alerts_enabled";
    private static final String KEY_STORAGE_CAP_MB = "storage_cap_mb";

    public static final int DEFAULT_STORAGE_CAP_MB = 60;
    public static final int MIN_STORAGE_CAP_MB = 20;
    public static final int MAX_STORAGE_CAP_MB = 200;
    public static final int STORAGE_CAP_STEP_MB = 10;

    private AppPreferences() {
        // No instances.
    }

    public static boolean isMessageAlertsEnabled(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MESSAGE_ALERTS_ENABLED, true);
    }

    public static void setMessageAlertsEnabled(@NonNull Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MESSAGE_ALERTS_ENABLED, enabled)
                .apply();
    }

    public static int getStorageCapMb(@NonNull Context context) {
        final int stored = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_STORAGE_CAP_MB, DEFAULT_STORAGE_CAP_MB);
        return normalizeStorageCap(stored);
    }

    public static void setStorageCapMb(@NonNull Context context, int capMb) {
        final int normalized = normalizeStorageCap(capMb);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STORAGE_CAP_MB, normalized)
                .apply();
    }

    public static long getStorageCapBytes(@NonNull Context context) {
        return getStorageCapMb(context) * 1024L * 1024L;
    }

    private static int normalizeStorageCap(int candidate) {
        final int clamped = Math.max(MIN_STORAGE_CAP_MB, Math.min(MAX_STORAGE_CAP_MB, candidate));
        final int offset = clamped - MIN_STORAGE_CAP_MB;
        final int steps = Math.round(offset / (float) STORAGE_CAP_STEP_MB);
        return MIN_STORAGE_CAP_MB + (steps * STORAGE_CAP_STEP_MB);
    }
}
