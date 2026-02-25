package com.dev.echodrop.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Room entity representing a private chat session.
 *
 * <p>Each chat is identified by a unique 8-character alphanumeric code
 * (visually unambiguous characters only). The code is used to derive
 * the AES-256-GCM encryption key via PBKDF2.</p>
 */
@Entity(
        tableName = "chats",
        indices = {
                @Index(value = "code", unique = true)
        }
)
public class ChatEntity {

    /** Characters excluded: O, 0, I, 1 for visual clarity. */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id;

    @NonNull
    @ColumnInfo(name = "code")
    private String code;

    @Nullable
    @ColumnInfo(name = "name")
    private String name;

    @ColumnInfo(name = "created_at")
    private long createdAt;

    @Nullable
    @ColumnInfo(name = "last_message_preview")
    private String lastMessagePreview;

    @ColumnInfo(name = "last_message_time")
    private long lastMessageTime;

    @ColumnInfo(name = "unread_count")
    private int unreadCount;

    // ──────────────────── Constructor ────────────────────

    /** Full constructor used by Room and tests. */
    public ChatEntity(@NonNull String id, @NonNull String code, @Nullable String name,
                      long createdAt, @Nullable String lastMessagePreview,
                      long lastMessageTime, int unreadCount) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.createdAt = createdAt;
        this.lastMessagePreview = lastMessagePreview;
        this.lastMessageTime = lastMessageTime;
        this.unreadCount = unreadCount;
    }

    // ──────────────────── Factory ────────────────────

    /** Creates a new chat with a generated UUID and the given code. */
    public static ChatEntity create(@NonNull String code, @Nullable String name) {
        final String id = UUID.randomUUID().toString();
        return new ChatEntity(id, code, name, System.currentTimeMillis(),
                null, 0L, 0);
    }

    /** Generates a random 8-character code using unambiguous characters. */
    public static String generateCode() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** Formats an 8-char code as XXXX-XXXX for display. */
    public static String formatCode(@NonNull String rawCode) {
        if (rawCode.length() != CODE_LENGTH) return rawCode;
        return rawCode.substring(0, 4) + "-" + rawCode.substring(4);
    }

    /** Strips formatting dash from a displayed code. */
    public static String stripCode(@NonNull String formatted) {
        return formatted.replace("-", "").toUpperCase().trim();
    }

    /** Returns the display name — chat name if set, otherwise formatted code. */
    @NonNull
    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return formatCode(code);
    }

    /** Returns the first character for the avatar initial. */
    public char getInitial() {
        if (name != null && !name.trim().isEmpty()) {
            return Character.toUpperCase(name.trim().charAt(0));
        }
        return code.charAt(0);
    }

    // ──────────────────── Getters / Setters ────────────────────

    @NonNull
    public String getId() { return id; }

    public void setId(@NonNull String id) { this.id = id; }

    @NonNull
    public String getCode() { return code; }

    public void setCode(@NonNull String code) { this.code = code; }

    @Nullable
    public String getName() { return name; }

    public void setName(@Nullable String name) { this.name = name; }

    public long getCreatedAt() { return createdAt; }

    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @Nullable
    public String getLastMessagePreview() { return lastMessagePreview; }

    public void setLastMessagePreview(@Nullable String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public long getLastMessageTime() { return lastMessageTime; }

    public void setLastMessageTime(long lastMessageTime) { this.lastMessageTime = lastMessageTime; }

    public int getUnreadCount() { return unreadCount; }

    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }
}
