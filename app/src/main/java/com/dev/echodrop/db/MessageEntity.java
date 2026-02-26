package com.dev.echodrop.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Room entity representing a message stored in the local database.
 * Replaces the in-memory Message POJO from Iteration 0-1.
 *
 * <p>Deduplication uses a SHA-256 hash of (text + scope + created_at_hour)
 * to detect near-duplicate posts within the same clock hour.</p>
 *
 * <p>Storage cap: 200 rows maximum. Eviction order:
 * BULK first, then oldest NORMAL. ALERT messages are never evicted.</p>
 */
@Entity(
        tableName = "messages",
        indices = {
                @Index(value = "content_hash", unique = true),
                @Index(value = "expires_at"),
                @Index(value = "priority")
        }
)
public class MessageEntity {

    public enum Scope {
        LOCAL,
        ZONE,
        EVENT
    }

    public enum Priority {
        ALERT,
        NORMAL,
        BULK
    }

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id;

    @NonNull
    @ColumnInfo(name = "text")
    private String text;

    @NonNull
    @ColumnInfo(name = "scope")
    private String scope;

    @NonNull
    @ColumnInfo(name = "priority")
    private String priority;

    @ColumnInfo(name = "created_at")
    private long createdAt;

    @ColumnInfo(name = "expires_at")
    private long expiresAt;

    @ColumnInfo(name = "read")
    private boolean read;

    @NonNull
    @ColumnInfo(name = "content_hash")
    private String contentHash;

    /** Number of hops this message has traversed (0 = originated here). */
    @ColumnInfo(name = "hop_count", defaultValue = "0")
    private int hopCount;

    /** Comma-separated device IDs that have already seen this message (loop prevention). */
    @NonNull
    @ColumnInfo(name = "seen_by_ids", defaultValue = "")
    private String seenByIds;

    // ──────────────────── Constructors ────────────────────

    /** Maximum number of hops a message can travel before forwarding stops. */
    public static final int MAX_HOP_COUNT = 5;

    /**
     * Full constructor used by Room and tests.
     */
    public MessageEntity(@NonNull String id, @NonNull String text, @NonNull String scope,
                         @NonNull String priority, long createdAt, long expiresAt,
                         boolean read, @NonNull String contentHash) {
        this.id = id;
        this.text = text;
        this.scope = scope;
        this.priority = priority;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.read = read;
        this.contentHash = contentHash;
        this.hopCount = 0;
        this.seenByIds = "";
    }

    // ──────────────────── Factory Methods ────────────────────

    /**
     * Creates a new MessageEntity with auto-generated UUID and computed content hash.
     */
    public static MessageEntity create(@NonNull String text, @NonNull Scope scope,
                                       @NonNull Priority priority, long createdAt,
                                       long expiresAt) {
        String id = UUID.randomUUID().toString();
        String contentHash = computeHash(text, scope.name(), createdAt);
        return new MessageEntity(id, text, scope.name(), priority.name(),
                createdAt, expiresAt, false, contentHash);
    }

    /**
     * Creates a MessageEntity from a legacy Message POJO.
     */
    public static MessageEntity fromMessage(com.dev.echodrop.models.Message message) {
        String scopeName = message.getScope().name();
        String priorityName = message.getPriority().name();
        String contentHash = computeHash(message.getText(), scopeName, message.getCreatedAt());
        return new MessageEntity(message.getId(), message.getText(), scopeName, priorityName,
                message.getCreatedAt(), message.getExpiresAt(), message.isRead(), contentHash);
    }

    // ──────────────────── Dedup Hash ────────────────────

    /**
     * Computes a SHA-256 hash for deduplication.
     * Hash input: text + scope + created_at_hour (truncated to hour precision).
     *
     * @param text      The message body
     * @param scope     The scope name (LOCAL, ZONE, EVENT)
     * @param createdAt The creation timestamp in epoch millis
     * @return Hex-encoded SHA-256 hash
     */
    public static String computeHash(@NonNull String text, @NonNull String scope, long createdAt) {
        long hourBucket = createdAt / (60 * 60 * 1000L);
        String input = text.trim().toLowerCase() + "|" + scope + "|" + hourBucket;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ──────────────────── Enum Helpers ────────────────────

    public Scope getScopeEnum() {
        return Scope.valueOf(scope);
    }

    public Priority getPriorityEnum() {
        return Priority.valueOf(priority);
    }

    // ──────────────────── TTL Helpers ────────────────────

    /**
     * Returns the ratio of remaining time to total TTL (0.0 to 1.0).
     */
    public float getTtlProgress() {
        long now = System.currentTimeMillis();
        long totalDuration = expiresAt - createdAt;
        if (totalDuration <= 0) return 0f;
        long remaining = Math.max(0, expiresAt - now);
        return Math.min(1f, (float) remaining / totalDuration);
    }

    /**
     * Returns the remaining TTL formatted as "3h 24m" or "12m".
     */
    public String formatTtlRemaining() {
        long remaining = Math.max(0, expiresAt - System.currentTimeMillis());
        long hours = remaining / (60 * 60 * 1000L);
        long minutes = (remaining / (60 * 1000L)) - (hours * 60);
        if (hours > 0) {
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + "h";
        }
        return minutes + "m";
    }

    /**
     * Returns true if the message has expired.
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    // ──────────────────── Getters / Setters ────────────────────

    @NonNull
    public String getId() { return id; }

    public void setId(@NonNull String id) { this.id = id; }

    @NonNull
    public String getText() { return text; }

    public void setText(@NonNull String text) { this.text = text; }

    @NonNull
    public String getScope() { return scope; }

    public void setScope(@NonNull String scope) { this.scope = scope; }

    @NonNull
    public String getPriority() { return priority; }

    public void setPriority(@NonNull String priority) { this.priority = priority; }

    public long getCreatedAt() { return createdAt; }

    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getExpiresAt() { return expiresAt; }

    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRead() { return read; }

    public void setRead(boolean read) { this.read = read; }

    @NonNull
    public String getContentHash() { return contentHash; }

    public void setContentHash(@NonNull String contentHash) { this.contentHash = contentHash; }

    /** Returns the number of hops this message has traversed. */
    public int getHopCount() { return hopCount; }

    /** Sets the hop count. */
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    /** Returns the comma-separated list of device IDs that have seen this message. */
    @NonNull
    public String getSeenByIds() { return seenByIds; }

    /** Sets the seen-by device IDs list. */
    public void setSeenByIds(@NonNull String seenByIds) { this.seenByIds = seenByIds; }

    /** Returns true if this message has reached the maximum hop count. */
    public boolean isAtHopLimit() { return hopCount >= MAX_HOP_COUNT; }

    /** Returns true if the given device ID is in the seen-by list. */
    public boolean hasBeenSeenBy(@NonNull String deviceId) {
        if (deviceId.isEmpty()) return false;
        if (seenByIds == null || seenByIds.isEmpty()) return false;
        for (final String id : seenByIds.split(",")) {
            if (id.equals(deviceId)) return true;
        }
        return false;
    }

    /** Appends a device ID to the seen-by list. */
    public void addSeenBy(@NonNull String deviceId) {
        if (deviceId.isEmpty()) return;
        if (hasBeenSeenBy(deviceId)) return;
        if (seenByIds == null || seenByIds.isEmpty()) {
            seenByIds = deviceId;
        } else {
            seenByIds = seenByIds + "," + deviceId;
        }
    }
}
