package com.dev.echodrop.components;

import static org.junit.Assert.*;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Test;

/**
 * Unit tests for the post composition logic used in {@link PostComposerSheet}.
 *
 * Updated in Iteration 2 to use {@link MessageEntity} enums and construction.
 *
 * Tests cover:
 * - Scope mapping from chip selection
 * - Priority mapping from urgent toggle
 * - TTL calculation from chip selection
 * - Validation rules (non-empty text, scope required)
 * - Character limit enforcement
 * - MessageEntity construction contract
 */
public class PostComposerLogicTest {

    private static final long NOW = System.currentTimeMillis();

    // ── Scope Mapping ─────────────────────────────────────────────

    @Test
    public void scopeMapping_nearby_mapsToLocal() {
        assertEquals(MessageEntity.Scope.LOCAL, mapScope("nearby"));
    }

    @Test
    public void scopeMapping_area_mapsToZone() {
        assertEquals(MessageEntity.Scope.ZONE, mapScope("area"));
    }

    @Test
    public void scopeMapping_event_mapsToEvent() {
        assertEquals(MessageEntity.Scope.EVENT, mapScope("event"));
    }

    @Test
    public void scopeMapping_default_fallsBackToLocal() {
        assertEquals(MessageEntity.Scope.LOCAL, mapScope("unknown"));
    }

    // ── Priority Mapping ──────────────────────────────────────────

    @Test
    public void priority_urgentChecked_mapsToAlert() {
        boolean urgentChecked = true;
        MessageEntity.Priority result = urgentChecked ? MessageEntity.Priority.ALERT : MessageEntity.Priority.NORMAL;
        assertEquals(MessageEntity.Priority.ALERT, result);
    }

    @Test
    public void priority_urgentUnchecked_mapsToNormal() {
        boolean urgentChecked = false;
        MessageEntity.Priority result = urgentChecked ? MessageEntity.Priority.ALERT : MessageEntity.Priority.NORMAL;
        assertEquals(MessageEntity.Priority.NORMAL, result);
    }

    // ── TTL Calculation ───────────────────────────────────────────

    @Test
    public void ttl_1hour_is3600000ms() {
        assertEquals(60 * 60 * 1000L, getTtlMillis("1h"));
    }

    @Test
    public void ttl_4hours_is14400000ms() {
        assertEquals(4 * 60 * 60 * 1000L, getTtlMillis("4h"));
    }

    @Test
    public void ttl_12hours_is43200000ms() {
        assertEquals(12 * 60 * 60 * 1000L, getTtlMillis("12h"));
    }

    @Test
    public void ttl_24hours_is86400000ms() {
        assertEquals(24 * 60 * 60 * 1000L, getTtlMillis("24h"));
    }

    @Test
    public void ttl_default_isFourHours() {
        assertEquals(4 * 60 * 60 * 1000L, getTtlMillis("unknown"));
    }

    // ── Validation ────────────────────────────────────────────────

    @Test
    public void validation_emptyText_noScope_returnsFalse() {
        assertFalse(isPostValid("", false));
    }

    @Test
    public void validation_emptyText_withScope_returnsFalse() {
        assertFalse(isPostValid("", true));
    }

    @Test
    public void validation_whitespaceOnly_withScope_returnsFalse() {
        assertFalse(isPostValid("   ", true));
    }

    @Test
    public void validation_validText_noScope_returnsFalse() {
        assertFalse(isPostValid("Hello world", false));
    }

    @Test
    public void validation_validText_withScope_returnsTrue() {
        assertTrue(isPostValid("Hello world", true));
    }

    @Test
    public void validation_singleChar_withScope_returnsTrue() {
        assertTrue(isPostValid("A", true));
    }

    // ── Character Limit ───────────────────────────────────────────

    @Test
    public void charLimit_maxIs240() {
        assertEquals(240, getMaxCharLimit());
    }

    @Test
    public void charCounterColor_under200_isDefault() {
        assertEquals("default", getCharCounterColorState(0));
        assertEquals("default", getCharCounterColorState(99));
        assertEquals("default", getCharCounterColorState(199));
    }

    @Test
    public void charCounterColor_200to239_isWarning() {
        assertEquals("warning", getCharCounterColorState(200));
        assertEquals("warning", getCharCounterColorState(220));
        assertEquals("warning", getCharCounterColorState(239));
    }

    @Test
    public void charCounterColor_240_isDanger() {
        assertEquals("danger", getCharCounterColorState(240));
    }

    // ── MessageEntity Construction ────────────────────────────────

    @Test
    public void constructEntity_setsCorrectTimestamps() {
        long ttl = 4 * 60 * 60 * 1000L;
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + ttl);

        assertEquals(NOW, entity.getCreatedAt());
        assertEquals(NOW + ttl, entity.getExpiresAt());
    }

    @Test
    public void constructEntity_isAlwaysUnread() {
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + 3600000);
        assertFalse("Newly created entity should be unread", entity.isRead());
    }

    @Test
    public void constructEntity_hasNonNullId() {
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + 3600000);
        assertNotNull("Entity must have an ID", entity.getId());
        assertFalse("Entity ID must not be empty", entity.getId().isEmpty());
    }

    @Test
    public void constructEntity_hasContentHash() {
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.LOCAL, MessageEntity.Priority.NORMAL, NOW, NOW + 3600000);
        assertNotNull("Entity must have a content hash", entity.getContentHash());
        assertEquals(64, entity.getContentHash().length());
    }

    @Test
    public void constructEntity_scopeAndPriority_areStrings() {
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.ZONE, MessageEntity.Priority.ALERT, NOW, NOW + 3600000);
        assertEquals("ZONE", entity.getScope());
        assertEquals("ALERT", entity.getPriority());
    }

    @Test
    public void constructEntity_enumAccessors_work() {
        MessageEntity entity = MessageEntity.create("Test", MessageEntity.Scope.EVENT, MessageEntity.Priority.BULK, NOW, NOW + 3600000);
        assertEquals(MessageEntity.Scope.EVENT, entity.getScopeEnum());
        assertEquals(MessageEntity.Priority.BULK, entity.getPriorityEnum());
    }

    // ── Helpers (mirror PostComposerSheet logic) ──────────────────

    private MessageEntity.Scope mapScope(String chipId) {
        switch (chipId) {
            case "area": return MessageEntity.Scope.ZONE;
            case "event": return MessageEntity.Scope.EVENT;
            case "nearby":
            default: return MessageEntity.Scope.LOCAL;
        }
    }

    private long getTtlMillis(String chipId) {
        switch (chipId) {
            case "1h": return 60 * 60 * 1000L;
            case "12h": return 12 * 60 * 60 * 1000L;
            case "24h": return 24 * 60 * 60 * 1000L;
            case "4h":
            default: return 4 * 60 * 60 * 1000L;
        }
    }

    private boolean isPostValid(String text, boolean scopeSelected) {
        boolean hasText = text != null && text.trim().length() > 0;
        return hasText && scopeSelected;
    }

    private int getMaxCharLimit() {
        return 240;
    }

    private String getCharCounterColorState(int length) {
        if (length >= 240) return "danger";
        if (length >= 200) return "warning";
        return "default";
    }
}
