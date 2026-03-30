package com.dev.echodrop.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for MessageEntity.
 *
 * <p>Provides CRUD operations and specialized queries for:
 * - Live inbox feed (non-expired messages, ordered by creation time)
 * - TTL cleanup (delete expired messages)
 * - Deduplication (find by content hash)
 * - Storage cap enforcement (count + eviction queries)
 * - Single message lookup by ID</p>
 */
@Dao
public interface MessageDao {

    // ──────────────────── Live Queries ────────────────────

    /**
     * Returns all non-expired BROADCAST messages ordered by priority then creation time.
     * ALERT (0) → NORMAL (1) → BULK (2), newest first within each tier.
     * Excludes CHAT bundles (encrypted ciphertext not suitable for public feed).
     */
    @Query("SELECT * FROM messages WHERE expires_at > :now AND type != 'CHAT' " +
            "ORDER BY CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC, " +
            "created_at DESC")
    LiveData<List<MessageEntity>> getActiveMessages(long now);

    /**
     * Returns ALL non-expired messages (including CHAT bundles) for DTN forwarding.
     */
    @Query("SELECT * FROM messages WHERE expires_at > :now " +
            "ORDER BY CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC, " +
            "created_at DESC")
    LiveData<List<MessageEntity>> getAllActiveMessages(long now);

    /**
     * Returns count of non-expired ALERT messages.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE priority = 'ALERT' AND expires_at > :now")
    LiveData<Integer> getAlertCount(long now);

    /**
     * Synchronous query returning all non-expired messages, ordered by priority
     * then creation time. Used by ManifestManager for manifest building.
     */
    @Query("SELECT * FROM messages WHERE expires_at > :now " +
            "ORDER BY CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC, " +
            "created_at DESC")
    List<MessageEntity> getActiveMessagesDirect(long now);

    /**
     * Synchronous query returning all non-expired messages (uses current time).
     * Convenience wrapper for ManifestManager.
     */
    @Query("SELECT * FROM messages WHERE expires_at > " +
            "(strftime('%s','now') * 1000) " +
            "ORDER BY CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC, " +
            "created_at DESC")
    List<MessageEntity> getActiveMessagesDirect();

    /**
     * Returns all messages regardless of expiry, ordered by creation time (newest first).
     * Used internally for storage cap calculations.
     */
    @Query("SELECT * FROM messages ORDER BY created_at DESC")
    List<MessageEntity> getAllMessagesSync();

    /**
     * Lookup a single message by its ID.
     */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    LiveData<MessageEntity> getMessageById(String messageId);

    /**
     * Synchronous single message lookup.
     */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    MessageEntity getMessageByIdSync(String messageId);

    // ──────────────────── Insert / Update / Delete ────────────────────

    /**
     * Insert a message. Ignores if a row with the same content_hash already exists.
     *
     * @return Row ID if inserted, -1 if ignored (duplicate hash).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(MessageEntity message);

    /**
     * Update an existing message (e.g., mark as read).
     */
    @Update
    void update(MessageEntity message);

    /**
     * Delete a specific message.
     */
    @Delete
    void delete(MessageEntity message);

    /**
     * Delete a message by its ID.
     */
    @Query("DELETE FROM messages WHERE id = :messageId")
    void deleteById(String messageId);

    // ──────────────────── TTL Cleanup ────────────────────

    /**
     * Delete all messages whose TTL has expired.
     *
     * @param now Current time in epoch millis.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM messages WHERE expires_at <= :now")
    int deleteExpired(long now);

    // ──────────────────── Deduplication ────────────────────

    /**
     * Find a message by its content hash for dedup checking.
     *
     * @param hash SHA-256 content hash
     * @return The existing message or null
     */
    @Query("SELECT * FROM messages WHERE content_hash = :hash LIMIT 1")
    MessageEntity findByContentHash(String hash);

    // ──────────────────── Storage Cap ────────────────────

    /**
     * Total count of all messages in the database.
     */
    @Query("SELECT COUNT(*) FROM messages")
    int countAll();

    /**
     * Delete the oldest BULK messages.
     * Used during storage cap enforcement.
     *
     * @param limit Number of BULK messages to delete.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM messages WHERE id IN " +
            "(SELECT id FROM messages WHERE priority = 'BULK' ORDER BY created_at ASC LIMIT :limit)")
    int deleteOldestBulk(int limit);

    /**
     * Delete the oldest NORMAL messages.
     * Used during storage cap enforcement after all BULK are removed.
     *
     * @param limit Number of NORMAL messages to delete.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM messages WHERE id IN " +
            "(SELECT id FROM messages WHERE priority = 'NORMAL' ORDER BY created_at ASC LIMIT :limit)")
    int deleteOldestNormal(int limit);

    /**
     * Count messages by priority.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE priority = :priority")
    int countByPriority(String priority);

    /**
     * Approximate storage used by cached bundles in bytes.
     * Includes text payload + key metadata with a fixed per-row overhead.
     */
    @Query("SELECT IFNULL(SUM(" +
            "LENGTH(text) + " +
            "LENGTH(content_hash) + " +
            "IFNULL(LENGTH(scope_id), 0) + " +
            "IFNULL(LENGTH(origin), 0) + " +
            "IFNULL(LENGTH(seen_by_ids), 0) + " +
            "IFNULL(LENGTH(sender_alias), 0)" +
            "), 0) + (COUNT(*) * 64) FROM messages")
    long estimateStorageBytes();

    /**
     * Mark a message as read.
     */
    @Query("UPDATE messages SET read = 1 WHERE id = :messageId")
    void markAsRead(String messageId);
}
