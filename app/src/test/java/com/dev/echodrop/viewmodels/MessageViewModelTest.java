package com.dev.echodrop.viewmodels;

import static org.junit.Assert.*;

import android.app.Application;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.MessageRepo;

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
 * Unit tests for {@link MessageViewModel} (Iteration 2).
 *
 * <p>Uses Robolectric + in-memory Room database.
 * The ViewModel now extends AndroidViewModel and delegates to {@link MessageRepo}.</p>
 *
 * Tests cover:
 * - Initial state (empty — no seed data)
 * - addMessage inserts into Room
 * - addMessage with callback — onInserted
 * - addMessage duplicate — onDuplicate callback
 * - deleteMessage removes from Room
 * - cleanupExpired removes expired messages
 * - LiveData contract
 * - getRepo returns non-null
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class MessageViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private MessageViewModel viewModel;
    private AppDatabase db;
    private Application app;

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        AppDatabase.setInstance(db);
        viewModel = new MessageViewModel(app);
    }

    @After
    public void tearDown() {
        db.close();
        AppDatabase.destroyInstance();
    }

    // ── Initial State ─────────────────────────────────────────────

    @Test
    public void initialization_hasNoSeedData() {
        LiveData<List<MessageEntity>> liveData = viewModel.getMessages();
        List<MessageEntity> messages = liveData.getValue();
        // Room LiveData may be null initially or empty
        assertTrue("Should start empty (no seed data)",
                messages == null || messages.isEmpty());
    }

    // ── addMessage ────────────────────────────────────────────────

    @Test
    public void addMessage_insertsIntoRoom() throws InterruptedException {
        MessageEntity entity = MessageEntity.create("Test msg", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);

        CountDownLatch latch = new CountDownLatch(1);
        viewModel.addMessage(entity, new MessageRepo.InsertCallback() {
            @Override public void onInserted() { latch.countDown(); }
            @Override public void onDuplicate() { fail("Should not be duplicate"); }
        });
        latch.await(2, TimeUnit.SECONDS);

        MessageEntity found = db.messageDao().getMessageByIdSync(entity.getId());
        assertNotNull("Message should be in Room", found);
        assertEquals("Test msg", found.getText());
    }

    @Test
    public void addMessage_fireAndForget_insertsIntoRoom() throws InterruptedException {
        MessageEntity entity = MessageEntity.create("Fire and forget", MessageEntity.Scope.ZONE, MessageEntity.Priority.ALERT, NOW, NOW + ONE_HOUR);
        viewModel.addMessage(entity);

        // Give async executor time to complete
        Thread.sleep(500);

        MessageEntity found = db.messageDao().getMessageByIdSync(entity.getId());
        assertNotNull("Fire-and-forget insert should succeed", found);
    }

    @Test
    public void addMessage_duplicate_callsOnDuplicate() throws InterruptedException {
        MessageEntity entity1 = MessageEntity.create("Duplicate test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        // Insert first
        CountDownLatch latch1 = new CountDownLatch(1);
        viewModel.addMessage(entity1, new MessageRepo.InsertCallback() {
            @Override public void onInserted() { latch1.countDown(); }
            @Override public void onDuplicate() { fail("First insert should succeed"); }
        });
        latch1.await(2, TimeUnit.SECONDS);

        // Insert duplicate (same text+scope+hour → same hash)
        MessageEntity entity2 = MessageEntity.create("Duplicate test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.ALERT, NOW, NOW + ONE_HOUR);
        CountDownLatch latch2 = new CountDownLatch(1);
        final boolean[] wasDuplicate = {false};
        viewModel.addMessage(entity2, new MessageRepo.InsertCallback() {
            @Override public void onInserted() { latch2.countDown(); }
            @Override public void onDuplicate() { wasDuplicate[0] = true; latch2.countDown(); }
        });
        latch2.await(2, TimeUnit.SECONDS);

        assertTrue("Second insert with same hash should be duplicate", wasDuplicate[0]);
    }

    // ── deleteMessage ─────────────────────────────────────────────

    @Test
    public void deleteMessage_removesFromRoom() throws InterruptedException {
        MessageEntity entity = MessageEntity.create("To delete", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        CountDownLatch latch = new CountDownLatch(1);
        viewModel.addMessage(entity, new MessageRepo.InsertCallback() {
            @Override public void onInserted() { latch.countDown(); }
            @Override public void onDuplicate() { latch.countDown(); }
        });
        latch.await(2, TimeUnit.SECONDS);

        viewModel.deleteMessage(entity.getId());
        Thread.sleep(500);

        MessageEntity found = db.messageDao().getMessageByIdSync(entity.getId());
        assertNull("Deleted message should not be in Room", found);
    }

    // ── cleanupExpired ────────────────────────────────────────────

    @Test
    public void cleanupExpired_removesExpiredMessages() throws InterruptedException {
        // Insert an already-expired message directly via DAO
        String hash = MessageEntity.computeHash("Expired", "LOCAL", NOW - ONE_HOUR);
        MessageEntity expired = new MessageEntity("exp-id", "Expired", "LOCAL", "NORMAL",
                NOW - ONE_HOUR, NOW - 1000, false, hash);
        db.messageDao().insert(expired);

        // Insert a valid message
        MessageEntity valid = MessageEntity.create("Still valid", MessageEntity.Scope.ZONE, MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        db.messageDao().insert(valid);

        viewModel.cleanupExpired();
        Thread.sleep(500);

        assertNull("Expired message should be cleaned up",
                db.messageDao().getMessageByIdSync("exp-id"));
        assertNotNull("Valid message should remain",
                db.messageDao().getMessageByIdSync(valid.getId()));
    }

    // ── LiveData Contract ─────────────────────────────────────────

    @Test
    public void getMessages_returnsLiveData() {
        LiveData<List<MessageEntity>> liveData = viewModel.getMessages();
        assertNotNull("getMessages() must not return null", liveData);
    }

    @Test
    public void getMessages_returnsSameInstance() {
        LiveData<List<MessageEntity>> first = viewModel.getMessages();
        LiveData<List<MessageEntity>> second = viewModel.getMessages();
        assertSame("getMessages() should return the same LiveData instance", first, second);
    }

    // ── getRepo ───────────────────────────────────────────────────

    @Test
    public void getRepo_returnsNonNull() {
        assertNotNull("getRepo() should return a MessageRepo", viewModel.getRepo());
    }

    // ── Multiple Inserts ──────────────────────────────────────────

    @Test
    public void multipleInserts_allPersisted() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            MessageEntity entity = MessageEntity.create("Message " + i, MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL,
                    NOW + i * 1000, NOW + ONE_HOUR + i * 1000);
            viewModel.addMessage(entity);
        }
        Thread.sleep(1000);

        int count = db.messageDao().countAll();
        assertEquals("All 5 messages should be persisted", 5, count);
    }
}
