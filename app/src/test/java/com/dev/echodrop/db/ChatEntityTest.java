package com.dev.echodrop.db;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for ChatEntity and ChatMessageEntity.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Code generation: correct length, valid characters</li>
 *   <li>Code formatting: XXXX-XXXX</li>
 *   <li>Code stripping: removes dashes, uppercases</li>
 *   <li>Entity factory: sets defaults</li>
 *   <li>Display name logic: name if set, code if not</li>
 *   <li>Initial character derivation</li>
 *   <li>ChatMessageEntity factory: outgoing defaults</li>
 *   <li>Sync state constants</li>
 *   <li>Code uniqueness (statistical)</li>
 * </ul>
 * </p>
 */
public class ChatEntityTest {

    private static final String VALID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    // ──────────────────── Code generation ────────────────────

    @Test
    public void generateCode_returns8Characters() {
        final String code = ChatEntity.generateCode();
        assertEquals("Code should be 8 characters", 8, code.length());
    }

    @Test
    public void generateCode_usesOnlyValidCharacters() {
        for (int i = 0; i < 50; i++) {
            final String code = ChatEntity.generateCode();
            for (char c : code.toCharArray()) {
                assertTrue("Character '" + c + "' is not in allowed set",
                        VALID_CHARS.indexOf(c) >= 0);
            }
        }
    }

    @Test
    public void generateCode_excludesAmbiguousCharacters() {
        for (int i = 0; i < 50; i++) {
            final String code = ChatEntity.generateCode();
            assertFalse("Should not contain O", code.contains("O"));
            assertFalse("Should not contain 0", code.contains("0"));
            assertFalse("Should not contain I", code.contains("I"));
            assertFalse("Should not contain 1", code.contains("1"));
        }
    }

    @Test
    public void generateCode_codesAreStatisticallyUnique() {
        final Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(ChatEntity.generateCode());
        }
        assertTrue("100 codes should have very few collisions", codes.size() > 90);
    }

    // ──────────────────── Code formatting ────────────────────

    @Test
    public void formatCode_insertsHyphen() {
        assertEquals("ABCD-EFGH", ChatEntity.formatCode("ABCDEFGH"));
    }

    @Test
    public void formatCode_shortCode_returnsUnchanged() {
        assertEquals("ABC", ChatEntity.formatCode("ABC"));
    }

    @Test
    public void stripCode_removesDash() {
        assertEquals("ABCDEFGH", ChatEntity.stripCode("ABCD-EFGH"));
    }

    @Test
    public void stripCode_uppercasesAndTrims() {
        assertEquals("ABCDEFGH", ChatEntity.stripCode(" abcd-efgh "));
    }

    // ──────────────────── Entity factory ────────────────────

    @Test
    public void create_setsdefaults() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", "Study Group");
        assertNotNull(chat.getId());
        assertFalse(chat.getId().isEmpty());
        assertEquals("ABCD2345", chat.getCode());
        assertEquals("Study Group", chat.getName());
        assertTrue("createdAt should be recent", chat.getCreatedAt() > 0);
        assertNull(chat.getLastMessagePreview());
        assertEquals(0, chat.getLastMessageTime());
        assertEquals(0, chat.getUnreadCount());
    }

    @Test
    public void create_nullName_allowed() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", null);
        assertNull(chat.getName());
    }

    @Test
    public void create_generatesUniqueIds() {
        final ChatEntity c1 = ChatEntity.create("ABCD2345", null);
        final ChatEntity c2 = ChatEntity.create("WXYZ6789", null);
        assertNotEquals(c1.getId(), c2.getId());
    }

    // ──────────────────── Display name ────────────────────

    @Test
    public void getDisplayName_withName_returnsName() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", "Study Group");
        assertEquals("Study Group", chat.getDisplayName());
    }

    @Test
    public void getDisplayName_withoutName_returnsFormattedCode() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", null);
        assertEquals("ABCD-2345", chat.getDisplayName());
    }

    @Test
    public void getDisplayName_emptyName_returnsFormattedCode() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", "  ");
        assertEquals("ABCD-2345", chat.getDisplayName());
    }

    // ──────────────────── Initial character ────────────────────

    @Test
    public void getInitial_withName_returnsFirstCharUppercase() {
        final ChatEntity chat = ChatEntity.create("ABCD2345", "study");
        assertEquals('S', chat.getInitial());
    }

    @Test
    public void getInitial_withoutName_returnsFirstCodeChar() {
        final ChatEntity chat = ChatEntity.create("XBCD2345", null);
        assertEquals('X', chat.getInitial());
    }

    // ──────────────────── ChatMessageEntity ────────────────────

    @Test
    public void createOutgoing_setsCorrectDefaults() {
        final ChatMessageEntity msg = ChatMessageEntity.createOutgoing("chat1", "cipher==");
        assertNotNull(msg.getId());
        assertEquals("chat1", msg.getChatId());
        assertEquals("cipher==", msg.getText());
        assertTrue(msg.isOutgoing());
        assertTrue(msg.getCreatedAt() > 0);
        assertEquals(ChatMessageEntity.SYNC_SENT, msg.getSyncState());
    }

    @Test
    public void createOutgoing_generatesUniqueIds() {
        final ChatMessageEntity m1 = ChatMessageEntity.createOutgoing("c1", "a");
        final ChatMessageEntity m2 = ChatMessageEntity.createOutgoing("c1", "b");
        assertNotEquals(m1.getId(), m2.getId());
    }

    @Test
    public void syncStateConstants_haveCorrectValues() {
        assertEquals(0, ChatMessageEntity.SYNC_PENDING);
        assertEquals(1, ChatMessageEntity.SYNC_SENT);
        assertEquals(2, ChatMessageEntity.SYNC_SYNCED);
    }
}
