package com.dev.echodrop.viewmodels;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;

import com.dev.echodrop.models.Message;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link MessageViewModel}.
 * Uses {@link InstantTaskExecutorRule} to execute LiveData operations synchronously.
 *
 * Tests cover:
 * - Seed data initialization (3 messages)
 * - Seed message properties (scope, priority, TTL)
 * - addMessage inserts at position 0
 * - LiveData observation contract
 * - Multiple sequential additions
 * - Ordering guarantees (newest first)
 */
public class MessageViewModelTest {

    /**
     * Swaps the background executor used by Architecture Components with one
     * that executes each task synchronously. Required for LiveData.setValue()
     * to propagate immediately in unit tests.
     */
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private MessageViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new MessageViewModel();
    }

    // ── Seed Data ─────────────────────────────────────────────────

    @Test
    public void initialization_seedsThreeMessages() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull("Messages LiveData must have a value after construction", messages);
        assertEquals("Seed data must contain exactly 3 messages", 3, messages.size());
    }

    @Test
    public void seedData_firstMessage_isAlertLocal() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message first = messages.get(0);
        assertEquals(Message.Scope.LOCAL, first.getScope());
        assertEquals(Message.Priority.ALERT, first.getPriority());
        assertFalse(first.isRead());
        assertTrue("First seed message text should mention road", first.getText().toLowerCase().contains("road"));
    }

    @Test
    public void seedData_secondMessage_isNormalZone() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message second = messages.get(1);
        assertEquals(Message.Scope.ZONE, second.getScope());
        assertEquals(Message.Priority.NORMAL, second.getPriority());
        assertFalse(second.isRead());
    }

    @Test
    public void seedData_thirdMessage_isNormalEvent() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message third = messages.get(2);
        assertEquals(Message.Scope.EVENT, third.getScope());
        assertEquals(Message.Priority.NORMAL, third.getPriority());
        assertFalse(third.isRead());
    }

    @Test
    public void seedData_allMessagesHaveValidIds() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        for (Message msg : messages) {
            assertNotNull("Message ID must not be null", msg.getId());
            assertFalse("Message ID must not be empty", msg.getId().isEmpty());
        }
    }

    @Test
    public void seedData_allMessagesHaveUniqueIds() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        String id0 = messages.get(0).getId();
        String id1 = messages.get(1).getId();
        String id2 = messages.get(2).getId();
        assertNotEquals("IDs 0 and 1 must differ", id0, id1);
        assertNotEquals("IDs 1 and 2 must differ", id1, id2);
        assertNotEquals("IDs 0 and 2 must differ", id0, id2);
    }

    @Test
    public void seedData_allMessagesAreUnread() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        for (Message msg : messages) {
            assertFalse("Seed messages should be unread", msg.isRead());
        }
    }

    @Test
    public void seedData_allMessagesHaveValidTimestamps() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        for (Message msg : messages) {
            assertTrue("createdAt must be positive", msg.getCreatedAt() > 0);
            assertTrue("expiresAt must be after createdAt", msg.getExpiresAt() > msg.getCreatedAt());
        }
    }

    @Test
    public void seedData_firstMessageTtl_isOneHour() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message first = messages.get(0);
        long ttl = first.getExpiresAt() - first.getCreatedAt();
        assertEquals("First message TTL should be 1 hour", 60 * 60 * 1000L, ttl);
    }

    @Test
    public void seedData_secondMessageTtl_isFourHours() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message second = messages.get(1);
        long ttl = second.getExpiresAt() - second.getCreatedAt();
        assertEquals("Second message TTL should be 4 hours", 4 * 60 * 60 * 1000L, ttl);
    }

    @Test
    public void seedData_thirdMessageTtl_isTwelveHours() {
        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        Message third = messages.get(2);
        long ttl = third.getExpiresAt() - third.getCreatedAt();
        assertEquals("Third message TTL should be 12 hours", 12 * 60 * 60 * 1000L, ttl);
    }

    // ── addMessage ────────────────────────────────────────────────

    @Test
    public void addMessage_insertsAtPositionZero() {
        long now = System.currentTimeMillis();
        Message newMsg = new Message("New post", Message.Scope.LOCAL, Message.Priority.NORMAL, now, now + 3600000, false);

        viewModel.addMessage(newMsg);

        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        assertEquals("After adding, total should be 4", 4, messages.size());
        assertEquals("New message should be at index 0", newMsg.getId(), messages.get(0).getId());
    }

    @Test
    public void addMessage_preservesExistingSeedData() {
        List<Message> before = viewModel.getMessages().getValue();
        assertNotNull(before);
        String secondId = before.get(1).getId();

        long now = System.currentTimeMillis();
        Message newMsg = new Message("New post", Message.Scope.LOCAL, Message.Priority.NORMAL, now, now + 3600000, false);
        viewModel.addMessage(newMsg);

        List<Message> after = viewModel.getMessages().getValue();
        assertNotNull(after);
        // Original seed messages should be shifted by 1
        assertEquals("First seed message should now be at index 1", before.get(0).getId(), after.get(1).getId());
        assertEquals("Second seed message should now be at index 2", before.get(1).getId(), after.get(2).getId());
        assertEquals("Third seed message should now be at index 3", before.get(2).getId(), after.get(3).getId());
    }

    @Test
    public void addMessage_multipleAdds_newestFirst() {
        long now = System.currentTimeMillis();
        Message msg1 = new Message("First addition", Message.Scope.LOCAL, Message.Priority.NORMAL, now, now + 3600000, false);
        Message msg2 = new Message("Second addition", Message.Scope.ZONE, Message.Priority.ALERT, now, now + 3600000, false);
        Message msg3 = new Message("Third addition", Message.Scope.EVENT, Message.Priority.BULK, now, now + 3600000, false);

        viewModel.addMessage(msg1);
        viewModel.addMessage(msg2);
        viewModel.addMessage(msg3);

        List<Message> messages = viewModel.getMessages().getValue();
        assertNotNull(messages);
        assertEquals("Total should be 6 (3 seed + 3 added)", 6, messages.size());
        assertEquals("Most recently added should be at 0", msg3.getId(), messages.get(0).getId());
        assertEquals("Second addition should be at 1", msg2.getId(), messages.get(1).getId());
        assertEquals("First addition should be at 2", msg1.getId(), messages.get(2).getId());
    }

    // ── LiveData Contract ─────────────────────────────────────────

    @Test
    public void getMessages_returnsLiveData() {
        LiveData<List<Message>> liveData = viewModel.getMessages();
        assertNotNull("getMessages() must not return null", liveData);
    }

    @Test
    public void getMessages_returnsSameInstance() {
        LiveData<List<Message>> first = viewModel.getMessages();
        LiveData<List<Message>> second = viewModel.getMessages();
        assertSame("getMessages() should return the same LiveData instance", first, second);
    }

    @Test
    public void addMessage_triggersLiveDataUpdate() {
        final int[] callCount = {0};
        viewModel.getMessages().observeForever(messages -> callCount[0]++);

        long now = System.currentTimeMillis();
        Message newMsg = new Message("Trigger test", Message.Scope.LOCAL, Message.Priority.NORMAL, now, now + 3600000, false);
        viewModel.addMessage(newMsg);

        // callCount[0] should be at least 2: initial seed setValue + addMessage setValue
        assertTrue("Observer should be called at least twice (seed + add)", callCount[0] >= 2);
    }
}
