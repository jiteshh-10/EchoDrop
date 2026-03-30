package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for Iteration 8 chat bundle support in TransferProtocol.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Magic header bumped to ED08</li>
 *   <li>type and scopeId serialization round-trip</li>
 *   <li>Chat bundle fields survive writeSession/readSession</li>
 *   <li>Mixed broadcast + chat bundles in a session</li>
 * </ul>
 * </p>
 */
public class TransferProtocolChatTest {

    private static final long NOW = 1700000000000L;
    private static final long EXPIRES = NOW + 3600_000L;

    // ──────────────────── Helpers ────────────────────

    private MessageEntity createBroadcast(String id, String text) {
        String hash = MessageEntity.computeHash(text, "LOCAL", NOW);
        MessageEntity entity = new MessageEntity(id, text, "LOCAL", "NORMAL",
                NOW, EXPIRES, false, hash);
        // type defaults to BROADCAST, scopeId defaults to ""
        return entity;
    }

    private MessageEntity createChatBundle(String id, String cipherText, String chatCode) {
        MessageEntity bundle = MessageEntity.createChatBundle(cipherText, chatCode, NOW, EXPIRES);
        bundle.setId(id);
        return bundle;
    }

    // ──────────────────── Magic Header ────────────────────

    @Test
    public void magic_isED08() {
        byte[] expected = {'E', 'D', '0', '8'};
        assertEquals(expected.length, TransferProtocol.MAGIC.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("MAGIC byte " + i, expected[i], TransferProtocol.MAGIC[i]);
        }
    }

    // ──────────────────── Serialize/Deserialize with type/scopeId ────────────────────

    @Test
    public void serialize_broadcastMessage_preservesType() throws IOException {
        MessageEntity msg = createBroadcast("b1", "Hello world");

        byte[] data = TransferProtocol.serialize(msg);
        MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("BROADCAST", restored.getType());
        assertEquals("", restored.getScopeId());
        assertFalse(restored.isChatBundle());
    }

    @Test
    public void serialize_chatBundle_preservesTypeAndScopeId() throws IOException {
        MessageEntity bundle = createChatBundle("c1", "encryptedData", "ABCD5678");

        byte[] data = TransferProtocol.serialize(bundle);
        MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("CHAT", restored.getType());
        assertEquals("room:abcd5678", restored.getScopeId());
        assertTrue(restored.isChatBundle());
    }

    @Test
    public void serialize_chatBundle_preservesAllFields() throws IOException {
        MessageEntity bundle = createChatBundle("c2", "base64cipher==", "WXYZ1234");
        bundle.setHopCount(3);
        bundle.setSeenByIds("aa11bb22,cc33dd44");

        byte[] data = TransferProtocol.serialize(bundle);
        MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("c2", restored.getId());
        assertEquals("base64cipher==", restored.getText());
        assertEquals("LOCAL", restored.getScope());
        assertEquals("NORMAL", restored.getPriority());
        assertEquals(NOW, restored.getCreatedAt());
        assertEquals(EXPIRES, restored.getExpiresAt());
        assertEquals(3, restored.getHopCount());
        assertEquals("aa11bb22,cc33dd44", restored.getSeenByIds());
        assertEquals("CHAT", restored.getType());
        assertEquals("room:wxyz1234", restored.getScopeId());
    }

    // ──────────────────── Frame round-trip ────────────────────

    @Test
    public void writeFrame_readFrame_chatBundle() throws IOException {
        MessageEntity bundle = createChatBundle("f1", "cipher123", "MNPQ9876");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeFrame(baos, bundle);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        MessageEntity restored = TransferProtocol.readFrame(bais);

        assertEquals("CHAT", restored.getType());
        assertEquals("room:mnpq9876", restored.getScopeId());
        assertTrue(restored.isChatBundle());
    }

    // ──────────────────── Session with mixed types ────────────────────

    @Test
    public void writeSession_readSession_mixedTypes() throws IOException {
        MessageEntity broadcast = createBroadcast("s1", "Public message");
        MessageEntity chat = createChatBundle("s2", "encrypted", "CODE1234");
        MessageEntity alert = createBroadcast("s3", "Alert!");
        alert.setPriority("ALERT");

        List<MessageEntity> messages = Arrays.asList(broadcast, chat, alert);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, messages);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(3, restored.size());

        // Sorted by priority: ALERT first, then NORMAL (broadcast and chat)
        MessageEntity restoredAlert = restored.get(0);
        assertEquals("ALERT", restoredAlert.getPriority());
        assertEquals("BROADCAST", restoredAlert.getType());

        // The remaining two are NORMAL — one broadcast, one chat
        boolean foundBroadcast = false;
        boolean foundChat = false;
        for (int i = 1; i < restored.size(); i++) {
            MessageEntity m = restored.get(i);
            if ("BROADCAST".equals(m.getType())) {
                foundBroadcast = true;
                assertEquals("", m.getScopeId());
            } else if ("CHAT".equals(m.getType())) {
                foundChat = true;
                assertEquals("room:code1234", m.getScopeId());
            }
        }
        assertTrue("Should contain a broadcast message", foundBroadcast);
        assertTrue("Should contain a chat bundle", foundChat);
    }

    @Test
    public void writeSession_readSession_onlyChatBundles() throws IOException {
        MessageEntity chat1 = createChatBundle("c10", "enc1", "AAAA1111");
        MessageEntity chat2 = createChatBundle("c11", "enc2", "BBBB2222");

        List<MessageEntity> messages = Arrays.asList(chat1, chat2);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, messages);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(2, restored.size());
        for (MessageEntity m : restored) {
            assertTrue(m.isChatBundle());
            assertEquals("CHAT", m.getType());
        }
    }

    // ──────────────────── Backward compatibility ────────────────────

    @Test
    public void broadcastMessage_typeAndScopeId_survivesRoundTrip() throws IOException {
        // Ensure existing broadcast messages still work correctly
        // with the new type/scopeId fields
        MessageEntity msg = createBroadcast("compat1", "Legacy broadcast");
        msg.setHopCount(2);
        msg.setSeenByIds("dev1,dev2");

        byte[] data = TransferProtocol.serialize(msg);
        MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("compat1", restored.getId());
        assertEquals("Legacy broadcast", restored.getText());
        assertEquals("LOCAL", restored.getScope());
        assertEquals("NORMAL", restored.getPriority());
        assertEquals(2, restored.getHopCount());
        assertEquals("dev1,dev2", restored.getSeenByIds());
        assertEquals("BROADCAST", restored.getType());
        assertEquals("", restored.getScopeId());
        assertFalse(restored.isChatBundle());
    }
}
