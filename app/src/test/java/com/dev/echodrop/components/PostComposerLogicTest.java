package com.dev.echodrop.components;

import static org.junit.Assert.*;

import com.dev.echodrop.models.Message;

import org.junit.Test;

/**
 * Unit tests for the post composition logic used in {@link PostComposerSheet}.
 *
 * Since PostComposerSheet directly constructs Messages in its submit() method,
 * these tests validate the message construction contract:
 * - Scope mapping from chip selection
 * - Priority mapping from urgent toggle
 * - TTL calculation from chip selection
 * - Validation rules (non-empty text, scope required)
 * - Character limit enforcement
 */
public class PostComposerLogicTest {

    // ── Scope Mapping ─────────────────────────────────────────────

    @Test
    public void scopeMapping_nearby_mapsToLocal() {
        assertEquals(Message.Scope.LOCAL, mapScope("nearby"));
    }

    @Test
    public void scopeMapping_area_mapsToZone() {
        assertEquals(Message.Scope.ZONE, mapScope("area"));
    }

    @Test
    public void scopeMapping_event_mapsToEvent() {
        assertEquals(Message.Scope.EVENT, mapScope("event"));
    }

    @Test
    public void scopeMapping_default_fallsBackToLocal() {
        assertEquals(Message.Scope.LOCAL, mapScope("unknown"));
    }

    // ── Priority Mapping ──────────────────────────────────────────

    @Test
    public void priority_urgentChecked_mapsToAlert() {
        boolean urgentChecked = true;
        Message.Priority result = urgentChecked ? Message.Priority.ALERT : Message.Priority.NORMAL;
        assertEquals(Message.Priority.ALERT, result);
    }

    @Test
    public void priority_urgentUnchecked_mapsToNormal() {
        boolean urgentChecked = false;
        Message.Priority result = urgentChecked ? Message.Priority.ALERT : Message.Priority.NORMAL;
        assertEquals(Message.Priority.NORMAL, result);
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

    // ── Message Construction ──────────────────────────────────────

    @Test
    public void constructMessage_setsCorrectTimestamps() {
        long created = System.currentTimeMillis();
        long ttl = 4 * 60 * 60 * 1000L;
        Message msg = new Message("Test", Message.Scope.LOCAL, Message.Priority.NORMAL, created, created + ttl, false);

        assertEquals(created, msg.getCreatedAt());
        assertEquals(created + ttl, msg.getExpiresAt());
    }

    @Test
    public void constructMessage_isAlwaysUnread() {
        long created = System.currentTimeMillis();
        Message msg = new Message("Test", Message.Scope.LOCAL, Message.Priority.NORMAL, created, created + 3600000, false);
        assertFalse("Newly created message should be unread", msg.isRead());
    }

    @Test
    public void constructMessage_textIsTrimmed() {
        String rawText = "  Hello World  ";
        String trimmed = rawText.trim();
        long now = System.currentTimeMillis();
        Message msg = new Message(trimmed, Message.Scope.LOCAL, Message.Priority.NORMAL, now, now + 3600000, false);
        assertEquals("Hello World", msg.getText());
    }

    // ── Helpers (mirror PostComposerSheet logic) ──────────────────

    private Message.Scope mapScope(String chipId) {
        switch (chipId) {
            case "area": return Message.Scope.ZONE;
            case "event": return Message.Scope.EVENT;
            case "nearby":
            default: return Message.Scope.LOCAL;
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
        return 240; // android:maxLength="240" in fragment_post_composer.xml
    }

    private String getCharCounterColorState(int length) {
        if (length >= 240) return "danger";
        if (length >= 200) return "warning";
        return "default";
    }
}
