package com.dev.echodrop.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for Iteration 7 multi-hop fields on {@link MessageEntity}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>hop_count default value and getter/setter</li>
 *   <li>seen_by_ids default value and getter/setter</li>
 *   <li>MAX_HOP_COUNT constant</li>
 *   <li>isAtHopLimit() boundary conditions</li>
 *   <li>hasBeenSeenBy() matching and non-matching</li>
 *   <li>addSeenBy() append, dedup, and chaining</li>
 * </ul>
 * </p>
 */
public class MessageEntityHopTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    private MessageEntity createEntity() {
        return new MessageEntity("test-id", "Test text", "LOCAL", "NORMAL",
                NOW, NOW + ONE_HOUR, false,
                MessageEntity.computeHash("Test text", "LOCAL", NOW));
    }

    // ── MAX_HOP_COUNT ─────────────────────────────────────────────

    @Test
    public void maxHopCount_isFive() {
        assertEquals(5, MessageEntity.MAX_HOP_COUNT);
    }

    // ── Default Values ────────────────────────────────────────────

    @Test
    public void hopCount_defaultIsZero() {
        MessageEntity entity = createEntity();
        assertEquals(0, entity.getHopCount());
    }

    @Test
    public void seenByIds_defaultIsEmpty() {
        MessageEntity entity = createEntity();
        assertEquals("", entity.getSeenByIds());
    }

    // ── Hop Count Getter/Setter ───────────────────────────────────

    @Test
    public void setHopCount_updatesValue() {
        MessageEntity entity = createEntity();
        entity.setHopCount(3);
        assertEquals(3, entity.getHopCount());
    }

    @Test
    public void setHopCount_toMaxValue() {
        MessageEntity entity = createEntity();
        entity.setHopCount(MessageEntity.MAX_HOP_COUNT);
        assertEquals(MessageEntity.MAX_HOP_COUNT, entity.getHopCount());
    }

    // ── Seen-By Getter/Setter ─────────────────────────────────────

    @Test
    public void setSeenByIds_updatesValue() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123,def456");
        assertEquals("abc123,def456", entity.getSeenByIds());
    }

    // ── isAtHopLimit ──────────────────────────────────────────────

    @Test
    public void isAtHopLimit_belowLimit_returnsFalse() {
        MessageEntity entity = createEntity();
        entity.setHopCount(0);
        assertFalse(entity.isAtHopLimit());
    }

    @Test
    public void isAtHopLimit_oneBelowLimit_returnsFalse() {
        MessageEntity entity = createEntity();
        entity.setHopCount(MessageEntity.MAX_HOP_COUNT - 1);
        assertFalse(entity.isAtHopLimit());
    }

    @Test
    public void isAtHopLimit_atLimit_returnsTrue() {
        MessageEntity entity = createEntity();
        entity.setHopCount(MessageEntity.MAX_HOP_COUNT);
        assertTrue(entity.isAtHopLimit());
    }

    @Test
    public void isAtHopLimit_aboveLimit_returnsTrue() {
        MessageEntity entity = createEntity();
        entity.setHopCount(MessageEntity.MAX_HOP_COUNT + 1);
        assertTrue(entity.isAtHopLimit());
    }

    // ── hasBeenSeenBy ─────────────────────────────────────────────

    @Test
    public void hasBeenSeenBy_emptyList_returnsFalse() {
        MessageEntity entity = createEntity();
        assertFalse(entity.hasBeenSeenBy("abc123"));
    }

    @Test
    public void hasBeenSeenBy_presentId_returnsTrue() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123,def456");
        assertTrue(entity.hasBeenSeenBy("abc123"));
        assertTrue(entity.hasBeenSeenBy("def456"));
    }

    @Test
    public void hasBeenSeenBy_absentId_returnsFalse() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123,def456");
        assertFalse(entity.hasBeenSeenBy("xyz789"));
    }

    @Test
    public void hasBeenSeenBy_singleEntry_returnsTrue() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123");
        assertTrue(entity.hasBeenSeenBy("abc123"));
    }

    @Test
    public void hasBeenSeenBy_emptyDeviceId_returnsFalse() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123");
        assertFalse(entity.hasBeenSeenBy(""));
    }

    @Test
    public void hasBeenSeenBy_nullSeenByIds_returnsFalse() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds(null);
        assertFalse(entity.hasBeenSeenBy("abc123"));
    }

    // ── addSeenBy ─────────────────────────────────────────────────

    @Test
    public void addSeenBy_toEmpty_setsId() {
        MessageEntity entity = createEntity();
        entity.addSeenBy("abc123");
        assertEquals("abc123", entity.getSeenByIds());
    }

    @Test
    public void addSeenBy_appends_withComma() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123");
        entity.addSeenBy("def456");
        assertEquals("abc123,def456", entity.getSeenByIds());
    }

    @Test
    public void addSeenBy_doesNotDuplicate() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123");
        entity.addSeenBy("abc123");
        assertEquals("abc123", entity.getSeenByIds());
    }

    @Test
    public void addSeenBy_multipleChained() {
        MessageEntity entity = createEntity();
        entity.addSeenBy("aaa");
        entity.addSeenBy("bbb");
        entity.addSeenBy("ccc");
        assertEquals("aaa,bbb,ccc", entity.getSeenByIds());
    }

    @Test
    public void addSeenBy_emptyId_doesNotChange() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds("abc123");
        entity.addSeenBy("");
        assertEquals("abc123", entity.getSeenByIds());
    }

    @Test
    public void addSeenBy_nullSeenByIds_handledGracefully() {
        MessageEntity entity = createEntity();
        entity.setSeenByIds(null);
        entity.addSeenBy("abc123");
        assertEquals("abc123", entity.getSeenByIds());
    }

    // ── create() factory includes hop defaults ────────────────────

    @Test
    public void create_setsHopCountZero() {
        MessageEntity entity = MessageEntity.create("Hello", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        assertEquals(0, entity.getHopCount());
    }

    @Test
    public void create_setsSeenByIdsEmpty() {
        MessageEntity entity = MessageEntity.create("Hello", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.NORMAL, NOW, NOW + ONE_HOUR);
        assertEquals("", entity.getSeenByIds());
    }
}
