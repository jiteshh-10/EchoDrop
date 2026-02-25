package com.dev.echodrop.mesh;

import static org.junit.Assert.*;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for {@link ManifestManager}.
 *
 * Tests cover:
 * - Empty manifest generation and parsing
 * - Single-entry round-trip (serialize → parse)
 * - Multi-entry round-trip
 * - Max entries cap enforcement
 * - Priority encoding (ALERT=0, NORMAL=1, BULK=2)
 * - UUID ↔ byte[] conversion
 * - Checksum determinism
 * - peekEntryCount utility
 * - manifestSizeBytes utility
 * - Malformed input rejection
 * - Version validation
 * - Wire format size correctness
 */
public class ManifestManagerTest {

    private static final long NOW_MS = System.currentTimeMillis();
    private static final long ONE_HOUR_MS = 3600_000L;

    private List<ManifestManager.ManifestEntry> sampleEntries;

    @Before
    public void setUp() {
        sampleEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sampleEntries.add(new ManifestManager.ManifestEntry(
                    UUID.randomUUID().toString(),
                    ManifestManager.computeChecksum("message-" + i),
                    i % 3,
                    (NOW_MS + i * ONE_HOUR_MS) / 1000
            ));
        }
    }

    // ── Empty Manifest ────────────────────────────────────

    @Test
    public void emptyManifest_hasHeaderOnly() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.buildManifestFromMessages(null);

        assertEquals(ManifestManager.HEADER_SIZE, data.length);
        assertEquals(ManifestManager.VERSION, data[0]);
        assertEquals(0, ManifestManager.peekEntryCount(data));
    }

    @Test
    public void emptyManifest_parsesToEmptyList() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.buildManifestFromMessages(new ArrayList<>());

        final List<ManifestManager.ManifestEntry> result = ManifestManager.parse(data);
        assertTrue(result.isEmpty());
    }

    // ── Serialize / Parse Round-Trip ──────────────────────

    @Test
    public void singleEntry_roundTrip() {
        final ManifestManager manager = new ManifestManager(null);
        final String uuid = UUID.randomUUID().toString();
        final byte[] checksum = ManifestManager.computeChecksum("hello world");
        final long expiresSec = NOW_MS / 1000 + 3600;

        final ManifestManager.ManifestEntry entry =
                new ManifestManager.ManifestEntry(uuid, checksum, 0, expiresSec);

        final byte[] data = manager.serialize(Arrays.asList(entry));
        final List<ManifestManager.ManifestEntry> parsed = ManifestManager.parse(data);

        assertEquals(1, parsed.size());
        assertEquals(uuid, parsed.get(0).bundleId);
        assertArrayEquals(checksum, parsed.get(0).checksum);
        assertEquals(0, parsed.get(0).priority);
        assertEquals(expiresSec, parsed.get(0).expiresAtSec);
    }

    @Test
    public void multiEntry_roundTrip() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.serialize(sampleEntries);
        final List<ManifestManager.ManifestEntry> parsed = ManifestManager.parse(data);

        assertEquals(sampleEntries.size(), parsed.size());
        for (int i = 0; i < sampleEntries.size(); i++) {
            assertEquals(sampleEntries.get(i).bundleId, parsed.get(i).bundleId);
            assertArrayEquals(sampleEntries.get(i).checksum, parsed.get(i).checksum);
            assertEquals(sampleEntries.get(i).priority, parsed.get(i).priority);
            assertEquals(sampleEntries.get(i).expiresAtSec, parsed.get(i).expiresAtSec);
        }
    }

    // ── Wire Format Size ──────────────────────────────────

    @Test
    public void wireFormat_sizeIsCorrect() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.serialize(sampleEntries);

        final int expected = ManifestManager.HEADER_SIZE + sampleEntries.size() * ManifestManager.ENTRY_SIZE;
        assertEquals(expected, data.length);
    }

    @Test
    public void wireFormat_headerVersionByte() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.serialize(sampleEntries);
        assertEquals(ManifestManager.VERSION, data[0]);
    }

    @Test
    public void wireFormat_entryCountInHeader() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.serialize(sampleEntries);

        final ByteBuffer buf = ByteBuffer.wrap(data, 1, 2);
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(sampleEntries.size(), buf.getShort() & 0xFFFF);
    }

    // ── Max Entries Cap ───────────────────────────────────

    @Test
    public void maxEntries_capsAtLimit() {
        final ManifestManager manager = new ManifestManager(null);
        final List<ManifestManager.ManifestEntry> large = new ArrayList<>();
        for (int i = 0; i < ManifestManager.MAX_ENTRIES + 10; i++) {
            large.add(new ManifestManager.ManifestEntry(
                    UUID.randomUUID().toString(),
                    ManifestManager.computeChecksum("msg-" + i),
                    1,
                    NOW_MS / 1000
            ));
        }

        final byte[] data = manager.serialize(large);
        final List<ManifestManager.ManifestEntry> parsed = ManifestManager.parse(data);
        assertEquals(ManifestManager.MAX_ENTRIES, parsed.size());
    }

    // ── Priority Encoding ─────────────────────────────────

    @Test
    public void priorityFromString_alert() {
        assertEquals(0, ManifestManager.ManifestEntry.priorityFromString("ALERT"));
    }

    @Test
    public void priorityFromString_normal() {
        assertEquals(1, ManifestManager.ManifestEntry.priorityFromString("NORMAL"));
    }

    @Test
    public void priorityFromString_bulk() {
        assertEquals(2, ManifestManager.ManifestEntry.priorityFromString("BULK"));
    }

    @Test
    public void priorityFromString_null_defaultsNormal() {
        assertEquals(1, ManifestManager.ManifestEntry.priorityFromString(null));
    }

    @Test
    public void priorityFromString_caseInsensitive() {
        assertEquals(0, ManifestManager.ManifestEntry.priorityFromString("alert"));
    }

    @Test
    public void priorityToString_roundTrip() {
        assertEquals("ALERT", ManifestManager.ManifestEntry.priorityToString(0));
        assertEquals("NORMAL", ManifestManager.ManifestEntry.priorityToString(1));
        assertEquals("BULK", ManifestManager.ManifestEntry.priorityToString(2));
    }

    // ── UUID ↔ Bytes Conversion ───────────────────────────

    @Test
    public void uuidToBytes_andBack() {
        final String uuid = UUID.randomUUID().toString();
        final byte[] bytes = ManifestManager.uuidToBytes(uuid);
        assertEquals(16, bytes.length);

        final String back = ManifestManager.bytesToUuid(bytes);
        assertEquals(uuid, back);
    }

    @Test
    public void uuidToBytes_nonUuid_fallsBackToHash() {
        // Non-UUID string should still produce 16 bytes
        final byte[] bytes = ManifestManager.uuidToBytes("not-a-uuid");
        assertEquals(16, bytes.length);
    }

    // ── Checksum Determinism ──────────────────────────────

    @Test
    public void checksum_isDeterministic() {
        final byte[] a = ManifestManager.computeChecksum("test message");
        final byte[] b = ManifestManager.computeChecksum("test message");
        assertArrayEquals(a, b);
    }

    @Test
    public void checksum_isFourBytes() {
        final byte[] cs = ManifestManager.computeChecksum("hello");
        assertEquals(4, cs.length);
    }

    @Test
    public void checksum_differsByContent() {
        final byte[] a = ManifestManager.computeChecksum("alpha");
        final byte[] b = ManifestManager.computeChecksum("bravo");
        assertFalse(Arrays.equals(a, b));
    }

    // ── peekEntryCount ────────────────────────────────────

    @Test
    public void peekEntryCount_returnsCorrectCount() {
        final ManifestManager manager = new ManifestManager(null);
        final byte[] data = manager.serialize(sampleEntries);
        assertEquals(sampleEntries.size(), ManifestManager.peekEntryCount(data));
    }

    @Test
    public void peekEntryCount_nullReturnsZero() {
        assertEquals(0, ManifestManager.peekEntryCount(null));
    }

    @Test
    public void peekEntryCount_tooShortReturnsZero() {
        assertEquals(0, ManifestManager.peekEntryCount(new byte[]{0x01}));
    }

    // ── manifestSizeBytes ─────────────────────────────────

    @Test
    public void manifestSizeBytes_returnsLength() {
        final byte[] data = new byte[100];
        assertEquals(100, ManifestManager.manifestSizeBytes(data));
    }

    @Test
    public void manifestSizeBytes_nullReturnsZero() {
        assertEquals(0, ManifestManager.manifestSizeBytes(null));
    }

    // ── Error Cases ───────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void parse_nullThrows() {
        ManifestManager.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_tooShortThrows() {
        ManifestManager.parse(new byte[]{0x01});
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_wrongVersionThrows() {
        final byte[] bad = new byte[]{0x02, 0x00, 0x00};
        ManifestManager.parse(bad);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_truncatedDataThrows() {
        // Header says 1 entry but no entry data follows
        final byte[] truncated = new byte[]{0x01, 0x00, 0x01};
        ManifestManager.parse(truncated);
    }

    // ── buildManifestFromMessages ─────────────────────────

    @Test
    public void buildManifestFromMessages_withRealEntities() {
        final ManifestManager manager = new ManifestManager(null);
        final List<MessageEntity> messages = new ArrayList<>();

        final MessageEntity msg = MessageEntity.create(
                "Test alert", MessageEntity.Scope.LOCAL,
                MessageEntity.Priority.ALERT,
                NOW_MS, NOW_MS + ONE_HOUR_MS);
        messages.add(msg);

        final byte[] manifest = manager.buildManifestFromMessages(messages);
        assertNotNull(manifest);
        assertTrue(manifest.length > ManifestManager.HEADER_SIZE);

        final List<ManifestManager.ManifestEntry> parsed = ManifestManager.parse(manifest);
        assertEquals(1, parsed.size());
        assertEquals(0, parsed.get(0).priority); // ALERT
        assertEquals(msg.getId(), parsed.get(0).bundleId);
    }
}
