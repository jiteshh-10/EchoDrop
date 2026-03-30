package com.dev.echodrop.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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
        final Set<String> raw = stored == null ? Collections.emptySet() : stored;
        final Set<String> normalized = new HashSet<>();
        final String localId = normalizeId(DeviceIdHelper.getDeviceId(context));
        for (String id : raw) {
            final String normalizedId = normalizeId(id);
            if (normalizedId.isEmpty()) {
                continue;
            }
            if (normalizedId.equals(localId)) {
                continue;
            }
            normalized.add(normalizedId);
        }
        return normalized;
    }

    public static boolean isBlocked(@NonNull Context context, @NonNull String deviceId) {
        final String normalized = normalizeId(deviceId);
        if (normalized.isEmpty()) return false;
        final String localId = normalizeId(DeviceIdHelper.getDeviceId(context));
        if (normalized.equals(localId)) return false;
        return getBlockedIds(context).contains(normalized);
    }

    public static boolean addBlockedId(@NonNull Context context, @NonNull String deviceId) {
        final String normalized = normalizeId(deviceId);
        if (normalized.isEmpty()) return false;
        final String localId = normalizeId(DeviceIdHelper.getDeviceId(context));
        if (normalized.equals(localId)) return false;
        final Set<String> blocked = getBlockedIds(context);
        final boolean added = blocked.add(normalized);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED_IDS, blocked)
                .apply();
        return added;
    }

    public static boolean removeBlockedId(@NonNull Context context, @NonNull String deviceId) {
        final String normalized = normalizeId(deviceId);
        if (normalized.isEmpty()) return false;
        final Set<String> blocked = getBlockedIds(context);
        final boolean removed = blocked.remove(normalized);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED_IDS, blocked)
                .apply();
        return removed;
    }

    @NonNull
    private static String normalizeId(@NonNull String deviceId) {
        return deviceId.trim().toLowerCase(Locale.US);
    }
}
