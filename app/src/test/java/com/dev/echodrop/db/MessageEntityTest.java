package com.dev.echodrop.db;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link MessageEntity}.
 *
 * Tests cover:
 * - Factory method (create)
 * - SHA-256 content hash computation
 * - Dedup hash determinism
 * - Dedup hash sensitivity to text, scope, and hour bucket
 * - TTL progress calculation
 * - TTL formatting
 * - Expiry detection
 * - Enum helpers
 * - fromMessage conversion
 */
public class MessageEntityTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;
    private static final long FOUR_HOURS = 4 * ONE_HOUR;

    // ── Factory Method ────────────────────────────────────────────

    @Test
    public void create_generatesUniqueId() {
        MessageEntity a = MessageEntity.create("Hello", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        MessageEntity b = MessageEntity.create("Hello", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        assertNotEquals("Each create() should generate a unique ID", a.getId(), b.getId());
    }

    @Test
    public void create_setsAllFields() {
        MessageEntity entity = MessageEntity.create("Test message", MessageEntity.Scope.ZONE,
                MessageEntity.Priority.ALERT, NOW, NOW + FOUR_HOURS);

        assertEquals("Test message", entity.getText());
        assertEquals("ZONE", entity.getScope());
        assertEquals("ALERT", entity.getPriority());
        assertEquals(NOW, entity.getCreatedAt());
        assertEquals(NOW + FOUR_HOURS, entity.getExpiresAt());
        assertFalse(entity.isRead());
        assertNotNull(entity.getContentHash());
        assertFalse(entity.getContentHash().isEmpty());
    }

    @Test
    public void create_setsReadToFalse() {
        MessageEntity entity = MessageEntity.create("Read test", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        assertFalse("Newly created message should be unread", entity.isRead());
    }

    // ── SHA-256 Hash ──────────────────────────────────────────────

    @Test
    public void computeHash_isDeterministic() {
        String hash1 = MessageEntity.computeHash("Hello world", "LOCAL", NOW);
        String hash2 = MessageEntity.computeHash("Hello world", "LOCAL", NOW);
        assertEquals("Same input should produce same hash", hash1, hash2);
    }

    @Test
    public void computeHash_is64CharsHex() {
        String hash = MessageEntity.computeHash("Test", "ZONE", NOW);
        assertEquals("SHA-256 hex should be 64 chars", 64, hash.length());
        assertTrue("Hash should be lowercase hex", hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void computeHash_differentText_differentHash() {
        String hash1 = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        String hash2 = MessageEntity.computeHash("World", "LOCAL", NOW);
        assertNotEquals("Different text should produce different hash", hash1, hash2);
    }

    @Test
    public void computeHash_differentScope_differentHash() {
        String hash1 = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        String hash2 = MessageEntity.computeHash("Hello", "ZONE", NOW);
        assertNotEquals("Different scope should produce different hash", hash1, hash2);
    }

    @Test
    public void computeHash_sameHour_sameHash() {
        // Two times within the same hour bucket
        long hourStart = (NOW / ONE_HOUR) * ONE_HOUR;
        String hash1 = MessageEntity.computeHash("Hello", "LOCAL", hourStart);
        String hash2 = MessageEntity.computeHash("Hello", "LOCAL", hourStart + 30 * 60 * 1000L);
        assertEquals("Times in the same hour bucket should produce same hash", hash1, hash2);
    }

    @Test
    public void computeHash_differentHour_differentHash() {
        long hourStart = (NOW / ONE_HOUR) * ONE_HOUR;
        String hash1 = MessageEntity.computeHash("Hello", "LOCAL", hourStart);
        String hash2 = MessageEntity.computeHash("Hello", "LOCAL", hourStart + ONE_HOUR);
        assertNotEquals("Times in different hour buckets should produce different hash", hash1, hash2);
    }

    @Test
    public void computeHash_caseInsensitive() {
        String hash1 = MessageEntity.computeHash("Hello World", "LOCAL", NOW);
        String hash2 = MessageEntity.computeHash("hello world", "LOCAL", NOW);
        assertEquals("Hash should be case-insensitive for dedup", hash1, hash2);
    }

    @Test
    public void computeHash_trimmedInput() {
        String hash1 = MessageEntity.computeHash("  Hello  ", "LOCAL", NOW);
        String hash2 = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        assertEquals("Hash should trim input text", hash1, hash2);
    }

    // ── Enum Helpers ──────────────────────────────────────────────

    @Test
    public void getScopeEnum_local() {
        MessageEntity entity = createEntity("LOCAL", "NORMAL");
        assertEquals(MessageEntity.Scope.LOCAL, entity.getScopeEnum());
    }

    @Test
    public void getScopeEnum_zone() {
        MessageEntity entity = createEntity("ZONE", "NORMAL");
        assertEquals(MessageEntity.Scope.ZONE, entity.getScopeEnum());
    }

    @Test
    public void getScopeEnum_event() {
        MessageEntity entity = createEntity("EVENT", "NORMAL");
        assertEquals(MessageEntity.Scope.EVENT, entity.getScopeEnum());
    }

    @Test
    public void getPriorityEnum_alert() {
        MessageEntity entity = createEntity("LOCAL", "ALERT");
        assertEquals(MessageEntity.Priority.ALERT, entity.getPriorityEnum());
    }

    @Test
    public void getPriorityEnum_normal() {
        MessageEntity entity = createEntity("LOCAL", "NORMAL");
        assertEquals(MessageEntity.Priority.NORMAL, entity.getPriorityEnum());
    }

    @Test
    public void getPriorityEnum_bulk() {
        MessageEntity entity = createEntity("LOCAL", "BULK");
        assertEquals(MessageEntity.Priority.BULK, entity.getPriorityEnum());
    }

    // ── TTL Progress ──────────────────────────────────────────────

    @Test
    public void getTtlProgress_fullRemaining() {
        long created = System.currentTimeMillis();
        long expires = created + ONE_HOUR;
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        float progress = entity.getTtlProgress();
        // Should be very close to 1.0 (just created)
        assertTrue("Progress should be close to 1.0", progress > 0.95f);
    }

    @Test
    public void getTtlProgress_expired() {
        long created = NOW - (2 * ONE_HOUR);
        long expires = NOW - ONE_HOUR; // Already expired
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        assertEquals("Expired message progress should be 0", 0f, entity.getTtlProgress(), 0.01f);
    }

    @Test
    public void getTtlProgress_zeroDuration_returnsZero() {
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                NOW, NOW, false, "hash");
        assertEquals("Zero duration progress should be 0", 0f, entity.getTtlProgress(), 0.01f);
    }

    // ── TTL Formatting ────────────────────────────────────────────

    @Test
    public void formatTtlRemaining_hoursAndMinutes() {
        long created = System.currentTimeMillis();
        long expires = created + (3 * ONE_HOUR) + (30 * 60 * 1000L);
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        String formatted = entity.formatTtlRemaining();
        assertTrue("Should contain 'h' and 'm'", formatted.contains("h") && formatted.contains("m"));
    }

    @Test
    public void formatTtlRemaining_exactHours() {
        long created = System.currentTimeMillis();
        // A bit more than 2 hours to avoid rounding edge case
        long expires = created + (2 * ONE_HOUR) + 1000;
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        String formatted = entity.formatTtlRemaining();
        assertTrue("Should contain 'h'", formatted.contains("h"));
    }

    @Test
    public void formatTtlRemaining_minutesOnly() {
        long created = System.currentTimeMillis();
        long expires = created + (30 * 60 * 1000L);
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        String formatted = entity.formatTtlRemaining();
        assertTrue("Should end with 'm'", formatted.endsWith("m"));
        assertFalse("Should not contain 'h'", formatted.contains("h"));
    }

    @Test
    public void formatTtlRemaining_expired_returnsZeroMinutes() {
        long created = NOW - (2 * ONE_HOUR);
        long expires = NOW - ONE_HOUR;
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                created, expires, false, "hash");
        assertEquals("Expired message should show 0m", "0m", entity.formatTtlRemaining());
    }

    // ── Expiry Detection ──────────────────────────────────────────

    @Test
    public void isExpired_futureExpiry_returnsFalse() {
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                NOW, NOW + ONE_HOUR, false, "hash");
        assertFalse("Future expiry should not be expired", entity.isExpired());
    }

    @Test
    public void isExpired_pastExpiry_returnsTrue() {
        MessageEntity entity = new MessageEntity("id", "text", "LOCAL", "NORMAL",
                NOW - (2 * ONE_HOUR), NOW - ONE_HOUR, false, "hash");
        assertTrue("Past expiry should be expired", entity.isExpired());
    }

    // ── fromMessage Conversion ────────────────────────────────────

    @Test
    public void fromMessage_preservesAllFields() {
        com.dev.echodrop.models.Message msg = new com.dev.echodrop.models.Message(
                "msg-id", "Test text",
                com.dev.echodrop.models.Message.Scope.ZONE,
                com.dev.echodrop.models.Message.Priority.ALERT,
                NOW, NOW + ONE_HOUR, true);

        MessageEntity entity = MessageEntity.fromMessage(msg);

        assertEquals("msg-id", entity.getId());
        assertEquals("Test text", entity.getText());
        assertEquals("ZONE", entity.getScope());
        assertEquals("ALERT", entity.getPriority());
        assertEquals(NOW, entity.getCreatedAt());
        assertEquals(NOW + ONE_HOUR, entity.getExpiresAt());
        assertTrue(entity.isRead());
        assertNotNull(entity.getContentHash());
    }

    // ── Setters ───────────────────────────────────────────────────

    @Test
    public void setRead_updatesReadState() {
        MessageEntity entity = createEntity("LOCAL", "NORMAL");
        assertFalse(entity.isRead());
        entity.setRead(true);
        assertTrue(entity.isRead());
    }

    // ── Helper ────────────────────────────────────────────────────

    private MessageEntity createEntity(String scope, String priority) {
        return new MessageEntity("test-id", "Test message", scope, priority,
                NOW, NOW + ONE_HOUR, false,
                MessageEntity.computeHash("Test message", scope, NOW));
    }
}
