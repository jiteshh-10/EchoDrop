package com.dev.echodrop.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * SharedPreferences-backed blocklist for remote device IDs.
 */
public final class BlockedDeviceStore {

    private static final String PREFS = "echodrop_prefs";
    private static final String KEY_BLOCKED_IDS = "blocked_device_ids";

    private BlockedDeviceStore() {}

    @NonNull
    public static Set<String> getBlockedIds(@NonNull Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final Set<String> stored = prefs.getStringSet(KEY_BLOCKED_IDS, Collections.emptySet());
        return new HashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    public static boolean isBlocked(@NonNull Context context, @NonNull String deviceId) {
        if (deviceId.trim().isEmpty()) return false;
        return getBlockedIds(context).contains(deviceId.trim());
    }

    public static boolean addBlockedId(@NonNull Context context, @NonNull String deviceId) {
        final String normalized = deviceId.trim();
        if (normalized.isEmpty()) return false;
        final Set<String> blocked = getBlockedIds(context);
        final boolean added = blocked.add(normalized);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED_IDS, blocked)
                .apply();
        return added;
    }

    public static boolean removeBlockedId(@NonNull Context context, @NonNull String deviceId) {
        final String normalized = deviceId.trim();
        if (normalized.isEmpty()) return false;
        final Set<String> blocked = getBlockedIds(context);
        final boolean removed = blocked.remove(normalized);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED_IDS, blocked)
                .apply();
        return removed;
    }
}
