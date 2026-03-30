package com.dev.echodrop.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.util.DeviceIdHelper;
import com.dev.echodrop.util.MessageStorageCapManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single point of access for message data operations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Insert messages with SHA-256 deduplication</li>
 *   <li>Enforce storage cap (settings-driven; evict BULK first, then NORMAL, never ALERT)</li>
 *   <li>TTL cleanup (delete expired messages)</li>
 *   <li>Expose LiveData for reactive UI updates</li>
 * </ul>
 * </p>
 *
 * <p>All write operations are performed on a background executor thread.
 * Room's LiveData queries automatically run on background threads.</p>
 */
public class MessageRepo {

    /** Maximum number of messages stored locally. */
    public static final int STORAGE_CAP = 200;

    private final MessageDao dao;
    private final ExecutorService executor;
    private Context appContext;

    /**
     * Callback for insert operations to communicate result back to caller.
     */
    public interface InsertCallback {
        /** Called when insert succeeds. */
        void onInserted();

        /** Called when a duplicate is detected (same content hash). */
        void onDuplicate();
    }

    public MessageRepo(Context context) {
        this(AppDatabase.getInstance(context).messageDao());
        this.appContext = context.getApplicationContext();
    }

    /**
     * Constructor for dependency injection / testing.
     */
    public MessageRepo(MessageDao dao) {
        this(dao, Executors.newSingleThreadExecutor());
    }

    /**
     * Full constructor for dependency injection / testing.
     */
    public MessageRepo(MessageDao dao, ExecutorService executor) {
        this.dao = dao;
        this.executor = executor;
        this.appContext = null;
    }

    // ──────────────────── Read Operations ────────────────────

    /**
     * Returns all active (non-expired) messages as LiveData, ordered by priority then newest.
     */
    public LiveData<List<MessageEntity>> getActiveMessages() {
        return dao.getActiveMessages(System.currentTimeMillis());
    }

    /**
     * Returns saved, non-expired messages as LiveData.
     */
    public LiveData<List<MessageEntity>> getSavedMessages() {
        return dao.getSavedMessages(System.currentTimeMillis());
    }

    /**
     * Returns a reactive count of non-expired ALERT messages.
     */
    public LiveData<Integer> getAlertCount() {
        return dao.getAlertCount(System.currentTimeMillis());
    }

    /**
     * Returns a single message by ID as LiveData.
     */
    public LiveData<MessageEntity> getMessageById(String messageId) {
        return dao.getMessageById(messageId);
    }

    // ──────────────────── Write Operations ────────────────────

    /**
     * Insert a message with deduplication and storage cap enforcement.
     *
     * <p>Flow:
     * <ol>
     *   <li>Check if content hash already exists → callback.onDuplicate()</li>
     *   <li>Insert the message (IGNORE on conflict as safety net)</li>
    *   <li>Enforce storage cap after insert</li>
     *   <li>callback.onInserted()</li>
     * </ol>
     * </p>
     *
     * @param entity   The message to insert.
     * @param callback Callback for result notification (may be null).
     */
    public void insert(MessageEntity entity, InsertCallback callback) {
        executor.execute(() -> {
            if ((entity.getOrigin() == null || entity.getOrigin().isEmpty()) && appContext != null) {
                entity.setOrigin(DeviceIdHelper.getDeviceId(appContext));
            }
            if (entity.getTtlMs() <= 0L) {
                entity.setTtlMs(Math.max(0L, entity.getExpiresAt() - entity.getCreatedAt()));
            }

            // Dedup check
            MessageEntity existing = dao.findByContentHash(entity.getContentHash());
            if (existing != null) {
                if (callback != null) {
                    callback.onDuplicate();
                }
                return;
            }

            // Insert
            long rowId = dao.insert(entity);
            if (rowId == -1) {
                // Should not happen after hash check, but safety net
                if (callback != null) {
                    callback.onDuplicate();
                }
                return;
            }

            // Enforce storage cap
            enforceStorageCap();

            if (callback != null) {
                callback.onInserted();
            }
        });
    }

    /**
     * Insert without callback (fire-and-forget).
     */
    public void insert(MessageEntity entity) {
        insert(entity, null);
    }

    /**
     * Delete a message by ID.
     */
    public void deleteById(String messageId) {
        executor.execute(() -> dao.deleteById(messageId));
    }

    /**
     * Delete all messages from the given origin device.
     */
    public void deleteByOrigin(String originId) {
        executor.execute(() -> dao.deleteByOrigin(originId));
    }

    /**
     * Mark a message as read.
     */
    public void markAsRead(String messageId) {
        executor.execute(() -> dao.markAsRead(messageId));
    }

    /**
     * Toggle saved state for a specific message.
     */
    public void setSaved(String messageId, boolean saved) {
        executor.execute(() -> dao.setSaved(messageId, saved));
    }

    // ──────────────────── TTL Cleanup ────────────────────

    /**
     * Delete all expired messages. Safe to call from any thread.
     *
     * @return Number of expired messages deleted (available via callback).
     */
    public void cleanupExpired() {
        executor.execute(() -> dao.deleteExpired(System.currentTimeMillis()));
    }

    /**
     * Synchronous cleanup for use in Worker context.
     *
     * @return Number of rows deleted.
     */
    public int cleanupExpiredSync() {
        return dao.deleteExpired(System.currentTimeMillis());
    }

    // ──────────────────── Storage Cap ────────────────────

    /**
     * Enforces the 200-row storage cap.
     *
     * <p>Eviction order:
     * <ol>
     *   <li>Delete oldest BULK messages first</li>
     *   <li>If still over cap, delete oldest NORMAL messages</li>
     *   <li>ALERT messages are never deleted by cap enforcement</li>
     * </ol>
     * </p>
     *
     * <p>Must be called on the executor thread.</p>
     */
    private void enforceStorageCap() {
        if (appContext != null) {
            MessageStorageCapManager.enforce(dao, appContext);
            return;
        }

        // Fallback for test-only repo instances with no context.
        int total = dao.countAll();
        if (total <= STORAGE_CAP) return;

        int excess = total - STORAGE_CAP;

        // Phase 1: Delete BULK messages
        int bulkCount = dao.countByPriority("BULK");
        if (bulkCount > 0) {
            int toDelete = Math.min(excess, bulkCount);
            dao.deleteOldestBulk(toDelete);
            excess -= toDelete;
        }

        // Phase 2: Delete NORMAL messages if still over cap
        if (excess > 0) {
            int normalCount = dao.countByPriority("NORMAL");
            if (normalCount > 0) {
                int toDelete = Math.min(excess, normalCount);
                dao.deleteOldestNormal(toDelete);
            }
        }
        // ALERT messages are never evicted
    }

    /**
     * Checks if a message with the given content hash already exists.
     * Must be called on a background thread.
     *
     * @param contentHash The SHA-256 content hash to check.
     * @return true if a duplicate exists.
     */
    public boolean isDuplicateSync(String contentHash) {
        return dao.findByContentHash(contentHash) != null;
    }
}
