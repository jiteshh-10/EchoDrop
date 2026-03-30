package com.dev.echodrop.util;

import androidx.annotation.NonNull;

import com.dev.echodrop.db.MessageEntity;

import java.util.Locale;

/**
 * Helpers for canonical scope-id generation and display formatting.
 */
public final class ScopeLabelCodec {

    private ScopeLabelCodec() {
    }

    @NonNull
    public static String buildCanonicalScopeId(@NonNull MessageEntity.Scope scope, @NonNull String rawLabel) {
        switch (scope) {
            case LOCAL:
                return "local:nearby";
            case ZONE:
                return "zone:" + fallbackSlug(canonicalize(rawLabel));
            case EVENT:
            default:
                return "event:" + fallbackSlug(canonicalize(rawLabel));
        }
    }

    public static boolean requiresCustomLabel(@NonNull MessageEntity.Scope scope) {
        return scope != MessageEntity.Scope.LOCAL;
    }

    @NonNull
    public static String canonicalize(@NonNull String raw) {
        String out = raw.trim().toLowerCase(Locale.US);
        out = out.replaceAll("[^a-z0-9]+", "-");
        out = out.replaceAll("-+", "-");
        out = out.replaceAll("^-|-$", "");
        if (out.length() > 40) {
            out = out.substring(0, 40).replaceAll("-+$", "");
        }
        return out;
    }

    @NonNull
    public static String toDisplayTag(@NonNull MessageEntity entity) {
        String scopeId = entity.getScopeId();
        if (scopeId == null || scopeId.trim().isEmpty()) {
            scopeId = fallbackScopeId(entity.getScope());
        }
        return "#" + scopeId.toLowerCase(Locale.US);
    }

    @NonNull
    private static String fallbackSlug(@NonNull String slug) {
        return slug.isEmpty() ? "general" : slug;
    }

    @NonNull
    private static String fallbackScopeId(@NonNull String scope) {
        if (MessageEntity.Scope.ZONE.name().equals(scope)) {
            return "zone:general";
        }
        if (MessageEntity.Scope.EVENT.name().equals(scope)) {
            return "event:general";
        }
        return "local:nearby";
    }
}