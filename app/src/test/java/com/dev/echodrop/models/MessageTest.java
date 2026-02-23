package com.dev.echodrop.models;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link Message} model class.
 * Tests cover:
 * - Construction with auto-generated UUID
 * - Construction with explicit ID
 * - Immutability (all getters return correct values)
 * - Enum values for Scope and Priority
 * - Edge cases (empty text, boundary timestamps)
 */
public class MessageTest {

    private static final String TEST_TEXT = "Test message content";
    private static final long TEST_CREATED = 1700000000000L;
    private static final long TEST_EXPIRES = 1700003600000L; // +1 hour

    // ── Construction ──────────────────────────────────────────────

    @Test
    public void constructor_autoUuid_generatesNonNullId() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertNotNull("Auto-generated ID must not be null", msg.getId());
        assertFalse("Auto-generated ID must not be empty", msg.getId().isEmpty());
    }

    @Test
    public void constructor_autoUuid_generatesUniqueIds() {
        Message msg1 = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        Message msg2 = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertNotEquals("Two messages should have different UUIDs", msg1.getId(), msg2.getId());
    }

    @Test
    public void constructor_explicitId_preservesId() {
        String customId = "custom-id-123";
        Message msg = new Message(customId, TEST_TEXT, Message.Scope.ZONE, Message.Priority.ALERT, TEST_CREATED, TEST_EXPIRES, true);
        assertEquals("Explicit ID must be preserved", customId, msg.getId());
    }

    // ── Getters (Immutability) ────────────────────────────────────

    @Test
    public void getText_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(TEST_TEXT, msg.getText());
    }

    @Test
    public void getScope_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.EVENT, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(Message.Scope.EVENT, msg.getScope());
    }

    @Test
    public void getPriority_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.ALERT, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(Message.Priority.ALERT, msg.getPriority());
    }

    @Test
    public void getCreatedAt_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(TEST_CREATED, msg.getCreatedAt());
    }

    @Test
    public void getExpiresAt_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(TEST_EXPIRES, msg.getExpiresAt());
    }

    @Test
    public void isRead_false_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertFalse(msg.isRead());
    }

    @Test
    public void isRead_true_returnsConstructorValue() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, true);
        assertTrue(msg.isRead());
    }

    // ── Enum Values ───────────────────────────────────────────────

    @Test
    public void scope_hasThreeValues() {
        Message.Scope[] values = Message.Scope.values();
        assertEquals("Scope enum must have exactly 3 values", 3, values.length);
    }

    @Test
    public void scope_containsExpectedValues() {
        assertNotNull(Message.Scope.valueOf("LOCAL"));
        assertNotNull(Message.Scope.valueOf("ZONE"));
        assertNotNull(Message.Scope.valueOf("EVENT"));
    }

    @Test
    public void priority_hasThreeValues() {
        Message.Priority[] values = Message.Priority.values();
        assertEquals("Priority enum must have exactly 3 values", 3, values.length);
    }

    @Test
    public void priority_containsExpectedValues() {
        assertNotNull(Message.Priority.valueOf("ALERT"));
        assertNotNull(Message.Priority.valueOf("NORMAL"));
        assertNotNull(Message.Priority.valueOf("BULK"));
    }

    // ── Edge Cases ────────────────────────────────────────────────

    @Test
    public void constructor_emptyText_isAllowed() {
        Message msg = new Message("", Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals("", msg.getText());
    }

    @Test
    public void constructor_zeroTimestamps_isAllowed() {
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, 0L, 0L, false);
        assertEquals(0L, msg.getCreatedAt());
        assertEquals(0L, msg.getExpiresAt());
    }

    @Test
    public void constructor_maxLengthText_isAllowed() {
        // 240 characters — the app's hard limit
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 240; i++) sb.append('A');
        String longText = sb.toString();
        Message msg = new Message(longText, Message.Scope.LOCAL, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
        assertEquals(240, msg.getText().length());
    }

    @Test
    public void constructor_allScopesPersist() {
        for (Message.Scope scope : Message.Scope.values()) {
            Message msg = new Message(TEST_TEXT, scope, Message.Priority.NORMAL, TEST_CREATED, TEST_EXPIRES, false);
            assertEquals(scope, msg.getScope());
        }
    }

    @Test
    public void constructor_allPrioritiesPersist() {
        for (Message.Priority priority : Message.Priority.values()) {
            Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, priority, TEST_CREATED, TEST_EXPIRES, false);
            assertEquals(priority, msg.getPriority());
        }
    }

    @Test
    public void ttl_calculationIsCorrect() {
        long created = System.currentTimeMillis();
        long ttl4h = 4 * 60 * 60 * 1000L;
        Message msg = new Message(TEST_TEXT, Message.Scope.LOCAL, Message.Priority.NORMAL, created, created + ttl4h, false);
        assertEquals("TTL should be 4 hours in millis", ttl4h, msg.getExpiresAt() - msg.getCreatedAt());
    }
}
