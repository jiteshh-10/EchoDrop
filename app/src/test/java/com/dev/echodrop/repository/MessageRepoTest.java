package com.dev.echodrop.repository;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link MessageRepo}.
 *
 * Uses Mockito to mock the DAO for isolated repository testing.
 *
 * Tests cover:
 * - Insert with callback
 * - Dedup detection (duplicate content hash)
 * - Storage cap enforcement (200 rows)
 * - BULK eviction first, then NORMAL, never ALERT
 * - Insert without callback (fire-and-forget)
 * - Delete by ID
 * - Mark as read
 * - Cleanup expired
 * - Synchronous cleanup
 * - isDuplicateSync
 */
public class MessageRepoTest {

    private MessageDao mockDao;
    private MessageRepo repo;
    private ExecutorService testExecutor;

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    @Before
    public void setUp() {
        mockDao = mock(MessageDao.class);
        // Use a single-thread executor that we can wait on
        testExecutor = Executors.newSingleThreadExecutor();
        repo = new MessageRepo(mockDao, testExecutor);
    }

    // ── Insert with Dedup ─────────────────────────────────────────

    @Test
    public void insert_noDuplicate_callsInsert() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(1);

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] inserted = {false};

        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() {
                inserted[0] = true;
                latch.countDown();
            }

            @Override
            public void onDuplicate() {
                latch.countDown();
            }
        });

        assertTrue("Callback should have been called", latch.await(2, TimeUnit.SECONDS));
        assertTrue("onInserted should be called", inserted[0]);
        verify(mockDao).insert(entity);
    }

    @Test
    public void insert_duplicateHash_callsOnDuplicate() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        MessageEntity existing = createEntity("test-0", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(existing);

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] duplicate = {false};

        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() {
                latch.countDown();
            }

            @Override
            public void onDuplicate() {
                duplicate[0] = true;
                latch.countDown();
            }
        });

        assertTrue("Callback should have been called", latch.await(2, TimeUnit.SECONDS));
        assertTrue("onDuplicate should be called", duplicate[0]);
        verify(mockDao, never()).insert(any());
    }

    @Test
    public void insert_rowIdNegativeOne_callsOnDuplicate() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(-1L); // IGNORE returned -1

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] duplicate = {false};

        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() {
                latch.countDown();
            }

            @Override
            public void onDuplicate() {
                duplicate[0] = true;
                latch.countDown();
            }
        });

        assertTrue("Callback should have been called", latch.await(2, TimeUnit.SECONDS));
        assertTrue("onDuplicate should be called for row -1", duplicate[0]);
    }

    // ── Storage Cap ───────────────────────────────────────────────

    @Test
    public void insert_underCap_noEviction() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(100); // Under 200 cap

        CountDownLatch latch = new CountDownLatch(1);
        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() { latch.countDown(); }
            @Override
            public void onDuplicate() { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(mockDao, never()).deleteOldestBulk(anyInt());
        verify(mockDao, never()).deleteOldestNormal(anyInt());
    }

    @Test
    public void insert_overCap_deletesBulkFirst() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(205); // 5 over cap
        when(mockDao.countByPriority("BULK")).thenReturn(10);
        when(mockDao.deleteOldestBulk(5)).thenReturn(5);

        CountDownLatch latch = new CountDownLatch(1);
        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() { latch.countDown(); }
            @Override
            public void onDuplicate() { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(mockDao).deleteOldestBulk(5);
        verify(mockDao, never()).deleteOldestNormal(anyInt());
    }

    @Test
    public void insert_overCap_noBulk_deletesNormal() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(203); // 3 over cap
        when(mockDao.countByPriority("BULK")).thenReturn(0);
        when(mockDao.countByPriority("NORMAL")).thenReturn(50);
        when(mockDao.deleteOldestNormal(3)).thenReturn(3);

        CountDownLatch latch = new CountDownLatch(1);
        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() { latch.countDown(); }
            @Override
            public void onDuplicate() { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(mockDao).deleteOldestNormal(3);
    }

    @Test
    public void insert_overCap_partialBulk_deletesRemainingNormal() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(210); // 10 over cap
        when(mockDao.countByPriority("BULK")).thenReturn(3); // Only 3 BULK available
        when(mockDao.deleteOldestBulk(3)).thenReturn(3);
        when(mockDao.countByPriority("NORMAL")).thenReturn(50);
        when(mockDao.deleteOldestNormal(7)).thenReturn(7); // 10 - 3 = 7 NORMAL to delete

        CountDownLatch latch = new CountDownLatch(1);
        repo.insert(entity, new MessageRepo.InsertCallback() {
            @Override
            public void onInserted() { latch.countDown(); }
            @Override
            public void onDuplicate() { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(mockDao).deleteOldestBulk(3);
        verify(mockDao).deleteOldestNormal(7);
    }

    @Test
    public void storageCap_is200() {
        assertEquals(200, MessageRepo.STORAGE_CAP);
    }

    // ── Delete ────────────────────────────────────────────────────

    @Test
    public void deleteById_delegatesToDao() throws Exception {
        repo.deleteById("msg-123");
        // Wait for executor
        testExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        verify(mockDao).deleteById("msg-123");
    }

    // ── Mark As Read ──────────────────────────────────────────────

    @Test
    public void markAsRead_delegatesToDao() throws Exception {
        repo.markAsRead("msg-456");
        testExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        verify(mockDao).markAsRead("msg-456");
    }

    // ── Cleanup ───────────────────────────────────────────────────

    @Test
    public void cleanupExpiredSync_delegatesToDao() {
        when(mockDao.deleteExpired(anyLong())).thenReturn(5);
        int deleted = repo.cleanupExpiredSync();
        assertEquals(5, deleted);
        verify(mockDao).deleteExpired(anyLong());
    }

    // ── isDuplicateSync ───────────────────────────────────────────

    @Test
    public void isDuplicateSync_exists_returnsTrue() {
        when(mockDao.findByContentHash("abc123")).thenReturn(createEntity("id", "text"));
        assertTrue(repo.isDuplicateSync("abc123"));
    }

    @Test
    public void isDuplicateSync_notExists_returnsFalse() {
        when(mockDao.findByContentHash("xyz789")).thenReturn(null);
        assertFalse(repo.isDuplicateSync("xyz789"));
    }

    // ── Insert without callback ───────────────────────────────────

    @Test
    public void insert_noCallback_doesNotThrow() throws Exception {
        MessageEntity entity = createEntity("test-1", "Hello");
        when(mockDao.findByContentHash(anyString())).thenReturn(null);
        when(mockDao.insert(any())).thenReturn(1L);
        when(mockDao.countAll()).thenReturn(1);

        repo.insert(entity); // No callback, should not throw
        testExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        verify(mockDao).insert(entity);
    }

    // ── Helper ────────────────────────────────────────────────────

    private MessageEntity createEntity(String id, String text) {
        String hash = MessageEntity.computeHash(text, "LOCAL", NOW);
        return new MessageEntity(id, text, "LOCAL", "NORMAL", NOW, NOW + ONE_HOUR, false, hash);
    }
}
