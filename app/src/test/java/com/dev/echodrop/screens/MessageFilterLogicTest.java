package com.dev.echodrop.screens;

import static org.junit.Assert.*;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for the message filtering logic used in {@link HomeInboxFragment}.
 *
 * Updated in Iteration 2 to use {@link MessageEntity} instead of Message POJO.
 *
 * Tests cover:
 * - ALL tab shows all messages
 * - ALERTS tab shows only ALERT priority messages
 * - CHATS tab shows empty list (placeholder)
 * - Search filters by text (case-insensitive)
 * - Combined tab + search filtering
 * - Empty search query shows all
 * - Alert count badge calculation
 */
public class MessageFilterLogicTest {

    private List<MessageEntity> allMessages;
    private static final long NOW = System.currentTimeMillis();
    private static final long EXPIRES = NOW + 3600000L;

    @Before
    public void setUp() {
        allMessages = new ArrayList<>();
        allMessages.add(createEntity("1", "Road closure alert", "LOCAL", "ALERT"));
        allMessages.add(createEntity("2", "Study group forming", "ZONE", "NORMAL"));
        allMessages.add(createEntity("3", "Campus event tonight", "EVENT", "NORMAL"));
        allMessages.add(createEntity("4", "Emergency road block", "LOCAL", "ALERT"));
        allMessages.add(createEntity("5", "Library closing early", "ZONE", "BULK"));
    }

    // ── ALL tab ───────────────────────────────────────────────────

    @Test
    public void allTab_noQuery_returnsAllMessages() {
        List<MessageEntity> result = applyFilter("ALL", "");
        assertEquals(5, result.size());
    }

    @Test
    public void allTab_withQuery_filtersbyText() {
        List<MessageEntity> result = applyFilter("ALL", "road");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(m -> m.getText().toLowerCase().contains("road")));
    }

    @Test
    public void allTab_caseInsensitiveSearch() {
        List<MessageEntity> result = applyFilter("ALL", "CAMPUS");
        assertEquals(1, result.size());
        assertEquals("3", result.get(0).getId());
    }

    @Test
    public void allTab_noMatch_returnsEmpty() {
        List<MessageEntity> result = applyFilter("ALL", "zzzznonexistent");
        assertEquals(0, result.size());
    }

    // ── ALERTS tab ────────────────────────────────────────────────

    @Test
    public void alertsTab_noQuery_returnsOnlyAlerts() {
        List<MessageEntity> result = applyFilter("ALERTS", "");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(m -> m.getPriorityEnum() == MessageEntity.Priority.ALERT));
    }

    @Test
    public void alertsTab_withQuery_filtersAlertsByText() {
        List<MessageEntity> result = applyFilter("ALERTS", "closure");
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).getId());
    }

    @Test
    public void alertsTab_queryMatchesNonAlert_returnsEmpty() {
        // "Study group" is NORMAL priority, so ALERTS tab should not show it
        List<MessageEntity> result = applyFilter("ALERTS", "study");
        assertEquals(0, result.size());
    }

    // ── CHATS tab ─────────────────────────────────────────────────

    @Test
    public void chatsTab_alwaysReturnsEmpty() {
        List<MessageEntity> result = applyFilter("CHATS", "");
        assertEquals("CHATS tab is placeholder — should be empty", 0, result.size());
    }

    @Test
    public void chatsTab_withQuery_stillReturnsEmpty() {
        List<MessageEntity> result = applyFilter("CHATS", "road");
        assertEquals(0, result.size());
    }

    // ── Alert Count Badge ─────────────────────────────────────────

    @Test
    public void alertCount_countsAllAlertMessages() {
        int alertCount = countAlerts();
        assertEquals(2, alertCount);
    }

    @Test
    public void alertCount_emptyList_returnsZero() {
        allMessages.clear();
        assertEquals(0, countAlerts());
    }

    @Test
    public void alertCount_noAlerts_returnsZero() {
        allMessages.clear();
        allMessages.add(createEntity("x", "Normal msg", "LOCAL", "NORMAL"));
        allMessages.add(createEntity("y", "Bulk msg", "LOCAL", "BULK"));
        assertEquals(0, countAlerts());
    }

    // ── Search Edge Cases ─────────────────────────────────────────

    @Test
    public void search_whitespaceOnly_treatedAsEmpty() {
        List<MessageEntity> result = applyFilter("ALL", "   ");
        assertEquals("Whitespace-only query should return all", 5, result.size());
    }

    @Test
    public void search_partialMatch() {
        List<MessageEntity> result = applyFilter("ALL", "clos");
        // Matches "Road closure alert" and "Library closing early"
        assertEquals(2, result.size());
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Mirrors the applyFilters() logic from HomeInboxFragment.
     * This is a faithful extraction of the production filter algorithm.
     */
    private List<MessageEntity> applyFilter(String tab, String query) {
        List<MessageEntity> filtered = new ArrayList<>();
        String normalized = query.toLowerCase(Locale.US).trim();
        for (MessageEntity message : allMessages) {
            if (tab.equals("ALERTS") && message.getPriorityEnum() != MessageEntity.Priority.ALERT) {
                continue;
            }
            if (tab.equals("CHATS")) {
                continue; // placeholder
            }
            if (!normalized.isEmpty() && !message.getText().toLowerCase(Locale.US).contains(normalized)) {
                continue;
            }
            filtered.add(message);
        }
        return filtered;
    }

    private int countAlerts() {
        int count = 0;
        for (MessageEntity message : allMessages) {
            if (message.getPriorityEnum() == MessageEntity.Priority.ALERT) {
                count++;
            }
        }
        return count;
    }

    private MessageEntity createEntity(String id, String text, String scope, String priority) {
        String hash = MessageEntity.computeHash(text, scope, NOW);
        return new MessageEntity(id, text, scope, priority, NOW, EXPIRES, false, hash);
    }
}
