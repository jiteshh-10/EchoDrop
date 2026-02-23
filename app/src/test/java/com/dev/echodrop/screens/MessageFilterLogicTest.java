package com.dev.echodrop.screens;

import static org.junit.Assert.*;

import com.dev.echodrop.models.Message;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for the message filtering logic used in {@link HomeInboxFragment}.
 *
 * Since the filtering logic is embedded in the fragment, these tests extract
 * and validate the algorithm independently — a pure-Java unit test that does
 * not require Android framework or Robolectric.
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

    private List<Message> allMessages;
    private static final long NOW = System.currentTimeMillis();
    private static final long EXPIRES = NOW + 3600000L;

    @Before
    public void setUp() {
        allMessages = new ArrayList<>();
        allMessages.add(createMessage("1", "Road closure alert", Message.Scope.LOCAL, Message.Priority.ALERT));
        allMessages.add(createMessage("2", "Study group forming", Message.Scope.ZONE, Message.Priority.NORMAL));
        allMessages.add(createMessage("3", "Campus event tonight", Message.Scope.EVENT, Message.Priority.NORMAL));
        allMessages.add(createMessage("4", "Emergency road block", Message.Scope.LOCAL, Message.Priority.ALERT));
        allMessages.add(createMessage("5", "Library closing early", Message.Scope.ZONE, Message.Priority.BULK));
    }

    // ── ALL tab ───────────────────────────────────────────────────

    @Test
    public void allTab_noQuery_returnsAllMessages() {
        List<Message> result = applyFilter("ALL", "");
        assertEquals(5, result.size());
    }

    @Test
    public void allTab_withQuery_filtersbyText() {
        List<Message> result = applyFilter("ALL", "road");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(m -> m.getText().toLowerCase().contains("road")));
    }

    @Test
    public void allTab_caseInsensitiveSearch() {
        List<Message> result = applyFilter("ALL", "CAMPUS");
        assertEquals(1, result.size());
        assertEquals("3", result.get(0).getId());
    }

    @Test
    public void allTab_noMatch_returnsEmpty() {
        List<Message> result = applyFilter("ALL", "zzzznonexistent");
        assertEquals(0, result.size());
    }

    // ── ALERTS tab ────────────────────────────────────────────────

    @Test
    public void alertsTab_noQuery_returnsOnlyAlerts() {
        List<Message> result = applyFilter("ALERTS", "");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(m -> m.getPriority() == Message.Priority.ALERT));
    }

    @Test
    public void alertsTab_withQuery_filtersAlertsByText() {
        List<Message> result = applyFilter("ALERTS", "closure");
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).getId());
    }

    @Test
    public void alertsTab_queryMatchesNonAlert_returnsEmpty() {
        // "Study group" is NORMAL priority, so ALERTS tab should not show it
        List<Message> result = applyFilter("ALERTS", "study");
        assertEquals(0, result.size());
    }

    // ── CHATS tab ─────────────────────────────────────────────────

    @Test
    public void chatsTab_alwaysReturnsEmpty() {
        List<Message> result = applyFilter("CHATS", "");
        assertEquals("CHATS tab is placeholder — should be empty", 0, result.size());
    }

    @Test
    public void chatsTab_withQuery_stillReturnsEmpty() {
        List<Message> result = applyFilter("CHATS", "road");
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
        allMessages.add(createMessage("x", "Normal msg", Message.Scope.LOCAL, Message.Priority.NORMAL));
        allMessages.add(createMessage("y", "Bulk msg", Message.Scope.LOCAL, Message.Priority.BULK));
        assertEquals(0, countAlerts());
    }

    // ── Search Edge Cases ─────────────────────────────────────────

    @Test
    public void search_whitespaceOnly_treatedAsEmpty() {
        List<Message> result = applyFilter("ALL", "   ");
        assertEquals("Whitespace-only query should return all", 5, result.size());
    }

    @Test
    public void search_partialMatch() {
        List<Message> result = applyFilter("ALL", "clos");
        // Matches "Road closure alert" and "Library closing early"
        assertEquals(2, result.size());
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Mirrors the applyFilters() logic from HomeInboxFragment.
     * This is a faithful extraction of the production filter algorithm.
     */
    private List<Message> applyFilter(String tab, String query) {
        List<Message> filtered = new ArrayList<>();
        String normalized = query.toLowerCase(Locale.US).trim();
        for (Message message : allMessages) {
            if (tab.equals("ALERTS") && message.getPriority() != Message.Priority.ALERT) {
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
        for (Message message : allMessages) {
            if (message.getPriority() == Message.Priority.ALERT) {
                count++;
            }
        }
        return count;
    }

    private Message createMessage(String id, String text, Message.Scope scope, Message.Priority priority) {
        return new Message(id, text, scope, priority, NOW, EXPIRES, false);
    }
}
