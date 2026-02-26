package com.dev.echodrop.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for TransferProtocol serialization, framing, and checksum.
 */
public class TransferProtocolTest {

    // ──────────────────── Helpers ────────────────────

    private MessageEntity createMessage(String id, String text, String scope,
                                        String priority, long createdAt, long expiresAt) {
        final String hash = MessageEntity.computeHash(text, scope, createdAt);
        return new MessageEntity(id, text, scope, priority, createdAt, expiresAt, false, hash);
    }

    private MessageEntity sampleMessage() {
        return createMessage("test-uuid-1", "Hello World", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
    }

    // ──────────────────── Serialize / Deserialize ────────────────────

    @Test
    public void serialize_deserialize_roundTrip() throws IOException {
        final MessageEntity original = sampleMessage();
        final byte[] data = TransferProtocol.serialize(original);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getText(), restored.getText());
        assertEquals(original.getScope(), restored.getScope());
        assertEquals(original.getPriority(), restored.getPriority());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(original.getExpiresAt(), restored.getExpiresAt());
        assertEquals(original.getContentHash(), restored.getContentHash());
    }

    @Test
    public void serialize_emptyText_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-empty", "", "ZONE", "BULK",
                1700000000000L, 1700003600000L);
        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("", restored.getText());
        assertEquals("ZONE", restored.getScope());
    }

    @Test
    public void serialize_unicodeText_roundTrips() throws IOException {
        final String unicode = "Привет мир 🌍 こんにちは";
        final MessageEntity msg = createMessage("id-unicode", unicode, "EVENT", "ALERT",
                1700000000000L, 1700003600000L);
        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(unicode, restored.getText());
    }

    @Test
    public void serialize_longText_roundTrips() throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 240; i++) sb.append('X');
        final MessageEntity msg = createMessage("id-long", sb.toString(), "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(240, restored.getText().length());
    }

    @Test
    public void serialize_producesNonEmptyPayload() throws IOException {
        final byte[] data = TransferProtocol.serialize(sampleMessage());
        assertTrue(data.length > 0);
    }

    // ──────────────────── Frame I/O ────────────────────

    @Test
    public void writeFrame_readFrame_roundTrip() throws IOException {
        final MessageEntity original = sampleMessage();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeFrame(baos, original);

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final MessageEntity restored = TransferProtocol.readFrame(bais);

        assertNotNull(restored);
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getText(), restored.getText());
    }

    @Test
    public void readFrame_emptyStream_returnsNull() throws IOException {
        final ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        final MessageEntity result = TransferProtocol.readFrame(bais);
        assertNull(result);
    }

    @Test(expected = IOException.class)
    public void readFrame_invalidFrameSize_throws() throws IOException {
        // Write a frame size of -1 (invalid)
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        dos.writeInt(-1);
        dos.flush();

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        TransferProtocol.readFrame(bais);
    }

    @Test(expected = IOException.class)
    public void readFrame_oversizedFrame_throws() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        dos.writeInt(TransferProtocol.MAX_FRAME_SIZE + 1);
        dos.flush();

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        TransferProtocol.readFrame(bais);
    }

    // ──────────────────── Session I/O ────────────────────

    @Test
    public void writeSession_readSession_roundTrip() throws IOException {
        final List<MessageEntity> messages = Arrays.asList(
                createMessage("id-1", "Message one", "LOCAL", "NORMAL",
                        1700000000000L, 1700003600000L),
                createMessage("id-2", "Urgent alert", "ZONE", "ALERT",
                        1700000001000L, 1700003601000L),
                createMessage("id-3", "Bulk data", "EVENT", "BULK",
                        1700000002000L, 1700003602000L)
        );

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, messages);

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(3, restored.size());
    }

    @Test
    public void writeSession_prioritySorted_alertFirst() throws IOException {
        final List<MessageEntity> messages = Arrays.asList(
                createMessage("bulk", "Bulk", "LOCAL", "BULK",
                        1700000000000L, 1700003600000L),
                createMessage("normal", "Normal", "LOCAL", "NORMAL",
                        1700000001000L, 1700003601000L),
                createMessage("alert", "Alert", "LOCAL", "ALERT",
                        1700000002000L, 1700003602000L)
        );

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, messages);

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(3, restored.size());
        assertEquals("ALERT", restored.get(0).getPriority());
        assertEquals("NORMAL", restored.get(1).getPriority());
        assertEquals("BULK", restored.get(2).getPriority());
    }

    @Test
    public void writeSession_emptyList_roundTrips() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, Collections.emptyList());

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertTrue(restored.isEmpty());
    }

    @Test(expected = IOException.class)
    public void readSession_invalidMagic_throws() throws IOException {
        final byte[] bad = {'X', 'X', 'X', 'X', 0, 0, 0, 0};
        final ByteArrayInputStream bais = new ByteArrayInputStream(bad);
        TransferProtocol.readSession(bais);
    }

    @Test(expected = IOException.class)
    public void readSession_negativeCount_throws() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        dos.write(TransferProtocol.MAGIC);
        dos.writeInt(-5);
        dos.flush();

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        TransferProtocol.readSession(bais);
    }

    // ──────────────────── Checksum Validation ────────────────────

    @Test
    public void validateChecksum_validMessage_returnsTrue() {
        final MessageEntity msg = createMessage("id-valid", "Hello", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        assertTrue(TransferProtocol.validateChecksum(msg));
    }

    @Test
    public void validateChecksum_tamperedText_returnsFalse() {
        final MessageEntity msg = createMessage("id-tamper", "Hello", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        // Tamper with the text after hash was computed
        msg.setText("Tampered");
        assertFalse(TransferProtocol.validateChecksum(msg));
    }

    @Test
    public void validateChecksum_tamperedScope_returnsFalse() {
        final MessageEntity msg = createMessage("id-scope", "Hello", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setScope("ZONE");
        assertFalse(TransferProtocol.validateChecksum(msg));
    }

    // ──────────────────── Transfer Checksum ────────────────────

    @Test
    public void computeTransferChecksum_deterministic() {
        final MessageEntity msg = sampleMessage();
        final String hash1 = TransferProtocol.computeTransferChecksum(msg);
        final String hash2 = TransferProtocol.computeTransferChecksum(msg);
        assertEquals(hash1, hash2);
    }

    @Test
    public void computeTransferChecksum_differentMessages_differentHash() {
        final MessageEntity msg1 = createMessage("id-1", "Hello", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        final MessageEntity msg2 = createMessage("id-2", "World", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        final String hash1 = TransferProtocol.computeTransferChecksum(msg1);
        final String hash2 = TransferProtocol.computeTransferChecksum(msg2);
        assertFalse(hash1.equals(hash2));
    }

    @Test
    public void computeTransferChecksum_isHexEncoded() {
        final String hash = TransferProtocol.computeTransferChecksum(sampleMessage());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    // ──────────────────── Priority Ordinal ────────────────────

    @Test
    public void priorityOrdinal_correctOrder() {
        assertEquals(0, TransferProtocol.priorityOrdinal("ALERT"));
        assertEquals(1, TransferProtocol.priorityOrdinal("NORMAL"));
        assertEquals(2, TransferProtocol.priorityOrdinal("BULK"));
    }

    @Test
    public void priorityOrdinal_caseInsensitive() {
        assertEquals(0, TransferProtocol.priorityOrdinal("alert"));
        assertEquals(1, TransferProtocol.priorityOrdinal("normal"));
        assertEquals(2, TransferProtocol.priorityOrdinal("bulk"));
    }

    @Test
    public void priorityOrdinal_unknownDefaultsToNormal() {
        assertEquals(1, TransferProtocol.priorityOrdinal("UNKNOWN"));
    }

    // ──────────────────── Multi-frame ────────────────────

    @Test
    public void multipleFrames_allRestore() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            final MessageEntity msg = createMessage("id-" + i, "Message " + i,
                    "LOCAL", "NORMAL", 1700000000000L + i, 1700003600000L + i);
            TransferProtocol.writeFrame(baos, msg);
        }

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> results = new ArrayList<>();
        MessageEntity frame;
        while ((frame = TransferProtocol.readFrame(bais)) != null) {
            results.add(frame);
        }

        assertEquals(10, results.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("id-" + i, results.get(i).getId());
        }
    }

    // ──────────────────── Constants ────────────────────

    @Test
    public void port_is9876() {
        assertEquals(9876, TransferProtocol.PORT);
    }

    @Test
    public void magic_isED08() {
        assertArrayEquals(new byte[]{'E', 'D', '0', '8'}, TransferProtocol.MAGIC);
    }

    @Test
    public void maxFrameSize_is512KB() {
        assertEquals(512 * 1024, TransferProtocol.MAX_FRAME_SIZE);
    }

    // ──────────────────── Deserialized field integrity ────────────────────

    @Test
    public void deserialize_readFieldIsAlwaysFalse() throws IOException {
        // The read field is always false after deserialization
        final MessageEntity msg = sampleMessage();
        msg.setRead(true);  // Sender marked as read
        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        // Read state is not transferred
        assertFalse(restored.isRead());
    }

    @Test
    public void session_preservesAllFields() throws IOException {
        final MessageEntity original = createMessage(
                "full-uuid-test", "Full field test", "EVENT", "ALERT",
                1700000099999L, 1700099999999L);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, Collections.singletonList(original));

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(1, restored.size());
        final MessageEntity r = restored.get(0);
        assertEquals("full-uuid-test", r.getId());
        assertEquals("Full field test", r.getText());
        assertEquals("EVENT", r.getScope());
        assertEquals("ALERT", r.getPriority());
        assertEquals(1700000099999L, r.getCreatedAt());
        assertEquals(1700099999999L, r.getExpiresAt());
        assertEquals(original.getContentHash(), r.getContentHash());
    }
}
