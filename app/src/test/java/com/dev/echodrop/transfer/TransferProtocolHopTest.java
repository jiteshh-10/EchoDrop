package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for Iteration 7 multi-hop fields in {@link TransferProtocol}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>hop_count round-trip through serialize/deserialize</li>
 *   <li>seen_by_ids round-trip through serialize/deserialize</li>
 *   <li>Session-level round-trip preserving hop fields</li>
 *   <li>Default values (zero hop, empty seen_by) survive the wire</li>
 *   <li>Multiple hops with long seen_by chain</li>
 * </ul>
 * </p>
 */
public class TransferProtocolHopTest {

    private MessageEntity createMessage(String id, String text, String scope,
                                        String priority, long createdAt, long expiresAt) {
        final String hash = MessageEntity.computeHash(text, scope, createdAt);
        return new MessageEntity(id, text, scope, priority, createdAt, expiresAt, false, hash);
    }

    // ── Serialize / Deserialize hop fields ────────────────────────

    @Test
    public void serialize_defaultHopFields_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-hop-0", "Hello", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        // defaults: hopCount=0, seenByIds=""

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(0, restored.getHopCount());
        assertEquals("", restored.getSeenByIds());
    }

    @Test
    public void serialize_nonZeroHopCount_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-hop-3", "Test", "ZONE", "ALERT",
                1700000000000L, 1700003600000L);
        msg.setHopCount(3);

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(3, restored.getHopCount());
    }

    @Test
    public void serialize_seenByIds_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-seen", "Test", "EVENT", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setSeenByIds("aabbccdd,11223344");

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("aabbccdd,11223344", restored.getSeenByIds());
    }

    @Test
    public void serialize_bothHopFields_roundTrip() throws IOException {
        final MessageEntity msg = createMessage("id-both", "Both fields", "LOCAL", "BULK",
                1700000000000L, 1700003600000L);
        msg.setHopCount(5);
        msg.setSeenByIds("aa,bb,cc,dd,ee");

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(5, restored.getHopCount());
        assertEquals("aa,bb,cc,dd,ee", restored.getSeenByIds());
    }

    @Test
    public void serialize_maxHopCount_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-max", "Max", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setHopCount(MessageEntity.MAX_HOP_COUNT);
        msg.setSeenByIds("a1,b2,c3,d4,e5");

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(MessageEntity.MAX_HOP_COUNT, restored.getHopCount());
    }

    // ── Session round-trip with hop fields ────────────────────────

    @Test
    public void session_preservesHopFields() throws IOException {
        final MessageEntity msg = createMessage("id-session", "Session hop", "ZONE", "ALERT",
                1700000000000L, 1700003600000L);
        msg.setHopCount(2);
        msg.setSeenByIds("device1,device2");

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeSession(baos, Collections.singletonList(msg));

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final List<MessageEntity> restored = TransferProtocol.readSession(bais);

        assertEquals(1, restored.size());
        assertEquals(2, restored.get(0).getHopCount());
        assertEquals("device1,device2", restored.get(0).getSeenByIds());
    }

    @Test
    public void frame_preservesHopFields() throws IOException {
        final MessageEntity msg = createMessage("id-frame", "Frame hop", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setHopCount(4);
        msg.setSeenByIds("a,b,c,d");

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TransferProtocol.writeFrame(baos, msg);

        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final MessageEntity restored = TransferProtocol.readFrame(bais);

        assertEquals(4, restored.getHopCount());
        assertEquals("a,b,c,d", restored.getSeenByIds());
    }

    // ── Empty seen_by_ids edge case ───────────────────────────────

    @Test
    public void serialize_emptySeenByIds_roundTrips() throws IOException {
        final MessageEntity msg = createMessage("id-empty-seen", "Test", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setSeenByIds("");

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals("", restored.getSeenByIds());
    }

    // ── Long seen_by chain ────────────────────────────────────────

    @Test
    public void serialize_longSeenByChain_roundTrips() throws IOException {
        // Simulate 5 hops with 8-char device IDs
        final String longChain = "aabbccdd,11223344,55667788,99aabbcc,ddeeff00";
        final MessageEntity msg = createMessage("id-long-chain", "Long chain", "LOCAL", "NORMAL",
                1700000000000L, 1700003600000L);
        msg.setHopCount(5);
        msg.setSeenByIds(longChain);

        final byte[] data = TransferProtocol.serialize(msg);
        final MessageEntity restored = TransferProtocol.deserialize(data);

        assertEquals(longChain, restored.getSeenByIds());
        assertEquals(5, restored.getHopCount());
    }
}
