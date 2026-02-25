package com.dev.echodrop.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

/**
 * Room entity representing a single message within a private chat.
 *
 * <p>The {@link #text} field stores AES-256-GCM ciphertext encoded as
 * Base64. Decryption is performed on read using a key derived from
 * the parent chat's code via PBKDF2.</p>
 */
@Entity(
        tableName = "chat_messages",
        foreignKeys = @ForeignKey(
                entity = ChatEntity.class,
                parentColumns = "id",
                childColumns = "chat_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index(value = "chat_id")
        }
)
public class ChatMessageEntity {

    /** Sync states. */
    public static final int SYNC_PENDING = 0;
    public static final int SYNC_SENT    = 1;
    public static final int SYNC_SYNCED  = 2;

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id;

    @NonNull
    @ColumnInfo(name = "chat_id")
    private String chatId;

    /** Ciphertext encoded as Base64. Never plaintext. */
    @NonNull
    @ColumnInfo(name = "text")
    private String text;

    @ColumnInfo(name = "is_outgoing")
    private boolean outgoing;

    @ColumnInfo(name = "created_at")
    private long createdAt;

    /** 0 = PENDING, 1 = SENT (locally saved), 2 = SYNCED (confirmed by peer). */
    @ColumnInfo(name = "sync_state")
    private int syncState;

    // ──────────────────── Constructors ────────────────────

    /** Full constructor used by Room. */
    public ChatMessageEntity(@NonNull String id, @NonNull String chatId,
                             @NonNull String text, boolean outgoing,
                             long createdAt, int syncState) {
        this.id = id;
        this.chatId = chatId;
        this.text = text;
        this.outgoing = outgoing;
        this.createdAt = createdAt;
        this.syncState = syncState;
    }

    // ──────────────────── Factory ────────────────────

    /**
     * Creates a new outgoing message with encrypted text.
     *
     * @param chatId      parent chat ID
     * @param cipherText  Base64-encoded ciphertext
     * @return new message entity (sync state = SENT for local-only)
     */
    public static ChatMessageEntity createOutgoing(@NonNull String chatId,
                                                    @NonNull String cipherText) {
        return new ChatMessageEntity(
                UUID.randomUUID().toString(),
                chatId,
                cipherText,
                true,
                System.currentTimeMillis(),
                SYNC_SENT
        );
    }

    // ──────────────────── Getters / Setters ────────────────────

    @NonNull
    public String getId() { return id; }

    public void setId(@NonNull String id) { this.id = id; }

    @NonNull
    public String getChatId() { return chatId; }

    public void setChatId(@NonNull String chatId) { this.chatId = chatId; }

    @NonNull
    public String getText() { return text; }

    public void setText(@NonNull String text) { this.text = text; }

    public boolean isOutgoing() { return outgoing; }

    public void setOutgoing(boolean outgoing) { this.outgoing = outgoing; }

    public long getCreatedAt() { return createdAt; }

    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public int getSyncState() { return syncState; }

    public void setSyncState(int syncState) { this.syncState = syncState; }
}
