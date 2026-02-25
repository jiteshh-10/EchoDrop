package com.dev.echodrop.db;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for Iteration 3 priority-aware DAO queries.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Priority sort order: ALERT → NORMAL → BULK</li>
 *   <li>Within same priority tier, newest first</li>
 *   <li>getAlertCount returns correct reactive count</li>
 *   <li>Expired messages excluded from getAlertCount</li>
 * </ul>
 * </p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class PriorityDaoTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private MessageDao dao;

    private static final long NOW = System.currentTimeMillis();
    private static final long FUTURE = NOW + 4 * 60 * 60 * 1000L;
    private static final long PAST = NOW - 1000L;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.messageDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- Helper ----

    private MessageEntity createEntity(String id, String text,
                                       MessageEntity.Priority priority, long createdAt) {
        return MessageEntity.create(text, MessageEntity.Scope.LOCAL, priority, createdAt, FUTURE);
    }

    private <T> T getOrAwait(LiveData<T> liveData) throws InterruptedException {
        final Object[] data = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Observer<T> observer = value -> {
            data[0] = value;
            latch.countDown();
        };
        liveData.observeForever(observer);
        latch.await(2, TimeUnit.SECONDS);
        liveData.removeObserver(observer);
        @SuppressWarnings("unchecked")
        T result = (T) data[0];
        return result;
    }

    // ---- Priority Sort Tests ----

    @Test
    public void alertAppearsBeforeNormal() throws InterruptedException {
        final MessageEntity normal = MessageEntity.create("normal msg",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + 100, FUTURE);
        normal.setId("n1");
        normal.setContentHash("hash_n1");
        dao.insert(normal);

        final MessageEntity alert = MessageEntity.create("alert msg",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        alert.setId("a1");
        alert.setContentHash("hash_a1");
        dao.insert(alert);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("ALERT", result.get(0).getPriority());
        assertEquals("NORMAL", result.get(1).getPriority());
    }

    @Test
    public void alertBeforeNormalBeforeBulk() throws InterruptedException {
        final MessageEntity bulk = MessageEntity.create("bulk msg",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.BULK, NOW + 200, FUTURE);
        bulk.setId("b1");
        bulk.setContentHash("hash_b1");
        dao.insert(bulk);

        final MessageEntity normal = MessageEntity.create("normal msg",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + 100, FUTURE);
        normal.setId("n1");
        normal.setContentHash("hash_n1");
        dao.insert(normal);

        final MessageEntity alert = MessageEntity.create("alert msg",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        alert.setId("a1");
        alert.setContentHash("hash_a1");
        dao.insert(alert);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertEquals(3, result.size());
        assertEquals("ALERT", result.get(0).getPriority());
        assertEquals("NORMAL", result.get(1).getPriority());
        assertEquals("BULK", result.get(2).getPriority());
    }

    @Test
    public void samePriorityOrderedByNewestFirst() throws InterruptedException {
        final MessageEntity older = MessageEntity.create("older",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        older.setId("old");
        older.setContentHash("hash_old");
        dao.insert(older);

        final MessageEntity newer = MessageEntity.create("newer",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + 5000, FUTURE);
        newer.setId("new");
        newer.setContentHash("hash_new");
        dao.insert(newer);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertEquals(2, result.size());
        assertEquals("new", result.get(0).getId());
        assertEquals("old", result.get(1).getId());
    }

    @Test
    public void alertNewerThanNormalStillFirst() throws InterruptedException {
        // NORMAL created at time 1000, ALERT created at time 500  — ALERT still first
        final MessageEntity normal = MessageEntity.create("normal",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + 1000, FUTURE);
        normal.setId("n1");
        normal.setContentHash("hash_n1");
        dao.insert(normal);

        final MessageEntity alert = MessageEntity.create("alert",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW + 500, FUTURE);
        alert.setId("a1");
        alert.setContentHash("hash_a1");
        dao.insert(alert);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertEquals("ALERT", result.get(0).getPriority());
    }

    @Test
    public void multipleAlertsOrderedByNewest() throws InterruptedException {
        final MessageEntity alert1 = MessageEntity.create("alert old",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        alert1.setId("a_old");
        alert1.setContentHash("hash_ao");
        dao.insert(alert1);

        final MessageEntity alert2 = MessageEntity.create("alert new",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW + 5000, FUTURE);
        alert2.setId("a_new");
        alert2.setContentHash("hash_an");
        dao.insert(alert2);

        final MessageEntity normal = MessageEntity.create("normal",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + 10000, FUTURE);
        normal.setId("n1");
        normal.setContentHash("hash_n1");
        dao.insert(normal);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertEquals(3, result.size());
        assertEquals("a_new", result.get(0).getId());
        assertEquals("a_old", result.get(1).getId());
        assertEquals("n1", result.get(2).getId());
    }

    @Test
    public void expiredMessagesExcludedFromActiveList() throws InterruptedException {
        final MessageEntity expired = MessageEntity.create("expired",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW - 10000, PAST);
        expired.setId("exp");
        expired.setContentHash("hash_exp");
        dao.insert(expired);

        final MessageEntity active = MessageEntity.create("active",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        active.setId("act");
        active.setContentHash("hash_act");
        dao.insert(active);

        List<MessageEntity> result = getOrAwait(dao.getActiveMessages(NOW));
        assertEquals(1, result.size());
        assertEquals("act", result.get(0).getId());
    }

    // ---- Alert Count Tests ----

    @Test
    public void alertCountReturnsZeroWhenEmpty() throws InterruptedException {
        Integer count = getOrAwait(dao.getAlertCount(NOW));
        assertNotNull(count);
        assertEquals(0, (int) count);
    }

    @Test
    public void alertCountReturnsCorrectCount() throws InterruptedException {
        final MessageEntity a1 = MessageEntity.create("alert1",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        a1.setId("a1");
        a1.setContentHash("hash_a1");
        dao.insert(a1);

        final MessageEntity a2 = MessageEntity.create("alert2",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        a2.setId("a2");
        a2.setContentHash("hash_a2");
        dao.insert(a2);

        final MessageEntity n1 = MessageEntity.create("normal1",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        n1.setId("n1");
        n1.setContentHash("hash_n1");
        dao.insert(n1);

        Integer count = getOrAwait(dao.getAlertCount(NOW));
        assertNotNull(count);
        assertEquals(2, (int) count);
    }

    @Test
    public void alertCountExcludesExpired() throws InterruptedException {
        final MessageEntity expiredAlert = MessageEntity.create("expired alert",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW - 10000, PAST);
        expiredAlert.setId("ea");
        expiredAlert.setContentHash("hash_ea");
        dao.insert(expiredAlert);

        final MessageEntity activeAlert = MessageEntity.create("active alert",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        activeAlert.setId("aa");
        activeAlert.setContentHash("hash_aa");
        dao.insert(activeAlert);

        Integer count = getOrAwait(dao.getAlertCount(NOW));
        assertNotNull(count);
        assertEquals(1, (int) count);
    }

    @Test
    public void alertCountExcludesNormalAndBulk() throws InterruptedException {
        final MessageEntity normal = MessageEntity.create("normal",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        normal.setId("n1");
        normal.setContentHash("hash_n1");
        dao.insert(normal);

        final MessageEntity bulk = MessageEntity.create("bulk",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.BULK, NOW, FUTURE);
        bulk.setId("b1");
        bulk.setContentHash("hash_b1");
        dao.insert(bulk);

        Integer count = getOrAwait(dao.getAlertCount(NOW));
        assertNotNull(count);
        assertEquals(0, (int) count);
    }

    // ---- Retention Policy Tests ----

    @Test
    public void deleteOldestBulkPreservesAlertAndNormal() {
        final MessageEntity alert = MessageEntity.create("alert",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        alert.setId("a");
        alert.setContentHash("hash_a");
        dao.insert(alert);

        final MessageEntity normal = MessageEntity.create("normal",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        normal.setId("n");
        normal.setContentHash("hash_n");
        dao.insert(normal);

        final MessageEntity bulk = MessageEntity.create("bulk",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.BULK, NOW, FUTURE);
        bulk.setId("b");
        bulk.setContentHash("hash_b");
        dao.insert(bulk);

        int deleted = dao.deleteOldestBulk(10);
        assertEquals(1, deleted);
        assertEquals(2, dao.countAll());
        assertNotNull(dao.getMessageByIdSync("a"));
        assertNotNull(dao.getMessageByIdSync("n"));
        assertNull(dao.getMessageByIdSync("b"));
    }

    @Test
    public void deleteOldestNormalPreservesAlert() {
        final MessageEntity alert = MessageEntity.create("alert",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, FUTURE);
        alert.setId("a");
        alert.setContentHash("hash_a");
        dao.insert(alert);

        final MessageEntity normal = MessageEntity.create("normal",
                MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, FUTURE);
        normal.setId("n");
        normal.setContentHash("hash_n");
        dao.insert(normal);

        int deleted = dao.deleteOldestNormal(10);
        assertEquals(1, deleted);
        assertEquals(1, dao.countAll());
        assertNotNull(dao.getMessageByIdSync("a"));
    }

    @Test
    public void bulkEvictedBeforeNormal() {
        // Insert 2 BULK + 2 NORMAL
        for (int i = 0; i < 2; i++) {
            final MessageEntity b = MessageEntity.create("bulk " + i,
                    MessageEntity.Scope.LOCAL, MessageEntity.Priority.BULK, NOW + i, FUTURE);
            b.setId("b" + i);
            b.setContentHash("hash_b" + i);
            dao.insert(b);
        }
        for (int i = 0; i < 2; i++) {
            final MessageEntity n = MessageEntity.create("normal " + i,
                    MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW + i, FUTURE);
            n.setId("n" + i);
            n.setContentHash("hash_n" + i);
            dao.insert(n);
        }

        assertEquals(4, dao.countAll());
        // Delete 2 BULK
        int bulkDeleted = dao.deleteOldestBulk(2);
        assertEquals(2, bulkDeleted);
        assertEquals(2, dao.countAll());
        // Both NORMAL still present
        assertNotNull(dao.getMessageByIdSync("n0"));
        assertNotNull(dao.getMessageByIdSync("n1"));
    }
}
