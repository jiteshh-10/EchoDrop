package com.dev.echodrop.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for Iteration 8 chat bundle fields on {@link MessageEntity}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>type field default value and getter/setter</li>
 *   <li>scopeId field default value and getter/setter</li>
 *   <li>TYPE_BROADCAST and TYPE_CHAT constants</li>
 *   <li>isChatBundle() helper</li>
 *   <li>createChatBundle() factory method</li>
 * </ul>
 * </p>
 */
public class MessageEntityChatTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    private MessageEntity createEntity() {
        return new MessageEntity("test-id", "Test text", "LOCAL", "NORMAL",
                NOW, NOW + ONE_HOUR, false,
                MessageEntity.computeHash("Test text", "LOCAL", NOW));
    }

    // ── Constants ─────────────────────────────────────────────

    @Test
    public void typeBroadcast_isBROADCAST() {
        assertEquals("BROADCAST", MessageEntity.TYPE_BROADCAST);
    }

    @Test
    public void typeChat_isCHAT() {
        assertEquals("CHAT", MessageEntity.TYPE_CHAT);
    }

    // ── Default Values ────────────────────────────────────────

    @Test
    public void type_defaultIsBroadcast() {
        MessageEntity entity = createEntity();
        assertEquals("BROADCAST", entity.getType());
    }

    @Test
    public void scopeId_defaultIsEmpty() {
        MessageEntity entity = createEntity();
        assertEquals("", entity.getScopeId());
    }

    @Test
    public void isChatBundle_defaultIsFalse() {
        MessageEntity entity = createEntity();
        assertFalse(entity.isChatBundle());
    }

    // ── Getter/Setter ─────────────────────────────────────────

    @Test
    public void setType_updatesValue() {
        MessageEntity entity = createEntity();
        entity.setType("CHAT");
        assertEquals("CHAT", entity.getType());
    }

    @Test
    public void setScopeId_updatesValue() {
        MessageEntity entity = createEntity();
        entity.setScopeId("ABCD1234");
        assertEquals("ABCD1234", entity.getScopeId());
    }

    @Test
    public void isChatBundle_trueWhenTypeIsChat() {
        MessageEntity entity = createEntity();
        entity.setType(MessageEntity.TYPE_CHAT);
        assertTrue(entity.isChatBundle());
    }

    @Test
    public void isChatBundle_falseWhenTypeIsBroadcast() {
        MessageEntity entity = createEntity();
        entity.setType(MessageEntity.TYPE_BROADCAST);
        assertFalse(entity.isChatBundle());
    }

    @Test
    public void isChatBundle_falseForArbitraryType() {
        MessageEntity entity = createEntity();
        entity.setType("UNKNOWN");
        assertFalse(entity.isChatBundle());
    }

    // ── createChatBundle Factory ──────────────────────────────

    @Test
    public void createChatBundle_setsTypeToCHAT() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals(MessageEntity.TYPE_CHAT, bundle.getType());
    }

    @Test
    public void createChatBundle_setsScopeIdToChatCode() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals("ABCD1234", bundle.getScopeId());
    }

    @Test
    public void createChatBundle_scopeIsLOCAL() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals("LOCAL", bundle.getScope());
    }

    @Test
    public void createChatBundle_priorityIsNORMAL() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals("NORMAL", bundle.getPriority());
    }

    @Test
    public void createChatBundle_textIsCiphertext() {
        String cipher = "encrypted-base64-content";
        MessageEntity bundle = MessageEntity.createChatBundle(
                cipher, "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals(cipher, bundle.getText());
    }

    @Test
    public void createChatBundle_hasValidId() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertNotNull(bundle.getId());
        assertFalse(bundle.getId().isEmpty());
    }

    @Test
    public void createChatBundle_hasContentHash() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertNotNull(bundle.getContentHash());
        assertFalse(bundle.getContentHash().isEmpty());
    }

    @Test
    public void createChatBundle_timestamps() {
        long expires = NOW + ONE_HOUR;
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, expires);
        assertEquals(NOW, bundle.getCreatedAt());
        assertEquals(expires, bundle.getExpiresAt());
    }

    @Test
    public void createChatBundle_hopCountIsZero() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals(0, bundle.getHopCount());
    }

    @Test
    public void createChatBundle_seenByIdsEmpty() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertEquals("", bundle.getSeenByIds());
    }

    @Test
    public void createChatBundle_isChatBundleTrue() {
        MessageEntity bundle = MessageEntity.createChatBundle(
                "ciphertext123", "ABCD1234", NOW, NOW + ONE_HOUR);
        assertTrue(bundle.isChatBundle());
    }

    // ── Type preservation through copy ────────────────────────

    @Test
    public void chatFields_preservedAfterCopy() {
        MessageEntity original = createEntity();
        original.setType(MessageEntity.TYPE_CHAT);
        original.setScopeId("WXYZ5678");

        // Simulate the copy done in BundleSender.sendForForwarding
        MessageEntity copy = new MessageEntity(
                original.getId(), original.getText(), original.getScope(),
                original.getPriority(), original.getCreatedAt(),
                original.getExpiresAt(), false, original.getContentHash());
        copy.setType(original.getType());
        copy.setScopeId(original.getScopeId());
        copy.setHopCount(original.getHopCount() + 1);

        assertEquals(MessageEntity.TYPE_CHAT, copy.getType());
        assertEquals("WXYZ5678", copy.getScopeId());
        assertTrue(copy.isChatBundle());
    }
}
