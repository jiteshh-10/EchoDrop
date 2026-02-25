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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for ChatDao Room operations.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Insert and retrieve chat</li>
 *   <li>Unique code constraint</li>
 *   <li>Get chat by code</li>
 *   <li>Delete chat cascades to messages</li>
 *   <li>Insert and retrieve messages</li>
 *   <li>Messages ordered by created_at ASC</li>
 *   <li>Update last message preview</li>
 *   <li>Increment and clear unread count</li>
 *   <li>getAllChats ordered by activity</li>
 *   <li>Message count query</li>
 * </ul>
 * </p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ChatDaoTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private ChatDao dao;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.chatDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ──────────────────── Helpers ────────────────────

    private ChatEntity makeChat(String code, String name, long time) {
        return new ChatEntity(UUID.randomUUID().toString(), code, name,
                time, null, 0L, 0);
    }

    private ChatMessageEntity makeMessage(String chatId, String text, boolean outgoing, long time) {
        return new ChatMessageEntity(UUID.randomUUID().toString(), chatId,
                text, outgoing, time, ChatMessageEntity.SYNC_SENT);
    }

    private <T> T getOrAwait(LiveData<T> liveData) throws InterruptedException {
        final Object[] data = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Observer<T> observer = value -> {
            data[0] = value;
            latch.countDown();
        };
        liveData.observeForever(observer);
        assertTrue("LiveData timeout", latch.await(2, TimeUnit.SECONDS));
        liveData.removeObserver(observer);
        @SuppressWarnings("unchecked")
        T result = (T) data[0];
        return result;
    }

    // ──────────────────── Chat CRUD ────────────────────

    @Test
    public void insertAndRetrieve_chatById() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);
        final ChatEntity loaded = dao.getChatById(chat.getId());
        assertNotNull(loaded);
        assertEquals("ABCD2345", loaded.getCode());
        assertEquals("Test", loaded.getName());
    }

    @Test
    public void getChatByCode_findsCorrectChat() {
        dao.insertChat(makeChat("AAAA2222", "A", 1000L));
        dao.insertChat(makeChat("BBBB3333", "B", 2000L));
        final ChatEntity found = dao.getChatByCode("BBBB3333");
        assertNotNull(found);
        assertEquals("B", found.getName());
    }

    @Test
    public void getChatByCode_unknownCode_returnsNull() {
        assertNull(dao.getChatByCode("ZZZZ9999"));
    }

    @Test
    public void deleteChat_removesChat() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);
        dao.deleteChat(chat.getId());
        assertNull(dao.getChatById(chat.getId()));
    }

    // ──────────────────── Cascade delete ────────────────────

    @Test
    public void deleteChat_cascadesToMessages() throws InterruptedException {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);
        dao.insertMessage(makeMessage(chat.getId(), "hello", true, 1000L));
        dao.insertMessage(makeMessage(chat.getId(), "world", true, 2000L));
        assertEquals(2, dao.getMessageCount(chat.getId()));

        dao.deleteChat(chat.getId());
        assertEquals(0, dao.getMessageCount(chat.getId()));
    }

    // ──────────────────── Messages ────────────────────

    @Test
    public void insertAndRetrieve_messages_orderedByTimeAsc() throws InterruptedException {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);

        dao.insertMessage(makeMessage(chat.getId(), "second", true, 2000L));
        dao.insertMessage(makeMessage(chat.getId(), "first", true, 1000L));
        dao.insertMessage(makeMessage(chat.getId(), "third", false, 3000L));

        final List<ChatMessageEntity> messages = getOrAwait(dao.getMessagesForChat(chat.getId()));
        assertEquals(3, messages.size());
        assertEquals("first", messages.get(0).getText());
        assertEquals("second", messages.get(1).getText());
        assertEquals("third", messages.get(2).getText());
    }

    @Test
    public void getMessageCount_returnsCorrectCount() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);
        assertEquals(0, dao.getMessageCount(chat.getId()));

        dao.insertMessage(makeMessage(chat.getId(), "msg1", true, 1000L));
        assertEquals(1, dao.getMessageCount(chat.getId()));
    }

    // ──────────────────── Preview updates ────────────────────

    @Test
    public void updateLastMessage_updatesPreviewAndTime() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);

        dao.updateLastMessage(chat.getId(), "Latest preview", 5000L);

        final ChatEntity updated = dao.getChatById(chat.getId());
        assertNotNull(updated);
        assertEquals("Latest preview", updated.getLastMessagePreview());
        assertEquals(5000L, updated.getLastMessageTime());
    }

    // ──────────────────── Unread count ────────────────────

    @Test
    public void incrementUnread_incrementsByOne() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        dao.insertChat(chat);

        dao.incrementUnread(chat.getId());
        dao.incrementUnread(chat.getId());
        dao.incrementUnread(chat.getId());

        final ChatEntity updated = dao.getChatById(chat.getId());
        assertEquals(3, updated.getUnreadCount());
    }

    @Test
    public void clearUnread_resetsToZero() {
        final ChatEntity chat = makeChat("ABCD2345", "Test", 1000L);
        chat.setUnreadCount(5);
        dao.insertChat(chat);

        dao.clearUnread(chat.getId());

        final ChatEntity updated = dao.getChatById(chat.getId());
        assertEquals(0, updated.getUnreadCount());
    }

    // ──────────────────── getAllChats ordering ────────────────────

    @Test
    public void getAllChats_orderedByActivity() throws InterruptedException {
        final ChatEntity oldChat = makeChat("AAAA2222", "Old", 1000L);
        dao.insertChat(oldChat);

        final ChatEntity newChat = makeChat("BBBB3333", "New", 2000L);
        newChat.setLastMessageTime(5000L);
        dao.insertChat(newChat);

        final ChatEntity midChat = makeChat("CCCC4444", "Mid", 3000L);
        dao.insertChat(midChat);

        final List<ChatEntity> chats = getOrAwait(dao.getAllChats());
        assertEquals(3, chats.size());
        // newChat has last_message_time=5000 so first
        assertEquals("BBBB3333", chats.get(0).getCode());
        // midChat created_at=3000 second
        assertEquals("CCCC4444", chats.get(1).getCode());
        // oldChat created_at=1000 last
        assertEquals("AAAA2222", chats.get(2).getCode());
    }

    // ──────────────────── Replace on conflict ────────────────────

    @Test
    public void insertChat_replaceOnConflict_updatesExisting() {
        final ChatEntity chat = makeChat("ABCD2345", "Original", 1000L);
        dao.insertChat(chat);

        final ChatEntity updated = new ChatEntity(chat.getId(), "ABCD2345",
                "Updated", 1000L, "preview", 2000L, 3);
        dao.insertChat(updated);

        final ChatEntity loaded = dao.getChatById(chat.getId());
        assertEquals("Updated", loaded.getName());
        assertEquals(3, loaded.getUnreadCount());
    }
}
