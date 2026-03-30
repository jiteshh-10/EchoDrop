package com.dev.echodrop.util;

import androidx.annotation.NonNull;

import com.dev.echodrop.db.ChatEntity;

import java.util.Locale;

/**
 * Encodes and decodes room identifiers carried in MessageEntity.scopeId.
 */
public final class RoomCodeCodec {

    private static final String PREFIX = "room:";

    private RoomCodeCodec() {
    }

    @NonNull
    public static String toScopeIdFromRawCode(@NonNull String rawCode) {
        String normalized = normalizeRawCode(rawCode);
        if (normalized.isEmpty()) {
            return "";
        }
        return PREFIX + normalized.toLowerCase(Locale.US);
    }

    @NonNull
    public static String extractRawCode(@NonNull String scopeId) {
        String value = scopeId.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            value = value.substring(PREFIX.length());
        }
        return normalizeRawCode(value);
    }

    @NonNull
    private static String normalizeRawCode(@NonNull String input) {
        String code = ChatEntity.stripCode(input);
        if (code.length() != 8) {
            return "";
        }
        return code;
    }
}