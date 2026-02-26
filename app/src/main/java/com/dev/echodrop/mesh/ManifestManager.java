package com.dev.echodrop.mesh;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageEntity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and parses the compact manifest exchanged between EchoDrop peers.
 *
 * <p>A manifest is a list of tuples describing messages available on this device.
 * Each entry is serialized in a compact binary format (not JSON) to fit within
 * BLE GATT characteristic payloads (~512 bytes max).</p>
 *
 * <h3>Binary format per entry (28 bytes):</h3>
 * <pre>
 *   bundle_id   : 16 bytes (UUID, big-endian)
 *   checksum    :  4 bytes (first 4 bytes of SHA-256 of message text)
 *   priority    :  1 byte  (0=ALERT, 1=NORMAL, 2=BULK)
 *   reserved    :  3 bytes (padding for alignment)
 *   expires_at  :  4 bytes (Unix epoch seconds, big-endian)
 * </pre>
 *
 * <h3>Wire envelope:</h3>
 * <pre>
 *   version     :  1 byte  (currently 0x01)
 *   entry_count :  2 bytes (big-endian unsigned short)
 *   entries     :  entry_count × 28 bytes
 * </pre>
 */
public class ManifestManager {

    private static final String TAG = "ManifestManager";

    /** Current wire-format version. */
    public static final byte VERSION = 0x01;

    /** Size of a single entry in bytes. */
    public static final int ENTRY_SIZE = 28;

    /** Size of the envelope header (version + entry_count). */
    public static final int HEADER_SIZE = 3;

    /** Maximum entries to include in one manifest (to stay within BLE limits). */
    public static final int MAX_ENTRIES = 18;

    /**
     * A single manifest entry representing a message.
     */
    public static class ManifestEntry {
        public final String bundleId;
        public final byte[] checksum;
        public final int priority;
        public final long expiresAtSec;

        public ManifestEntry(String bundleId, byte[] checksum, int priority, long expiresAtSec) {
            this.bundleId = bundleId;
            this.checksum = checksum;
            this.priority = priority;
            this.expiresAtSec = expiresAtSec;
        }

        /** Convenience: priority enum ordinal from string. */
        public static int priorityFromString(String p) {
            if (p == null) return 1;
            switch (p.toUpperCase()) {
                case "ALERT": return 0;
                case "NORMAL": return 1;
                case "BULK": return 2;
                default: return 1;
            }
        }

        /** Convenience: priority string from ordinal. */
        public static String priorityToString(int ordinal) {
            switch (ordinal) {
                case 0: return "ALERT";
                case 2: return "BULK";
                default: return "NORMAL";
            }
        }
    }

    private final Context context;

    public ManifestManager(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Builds a manifest from all active (non-expired) messages in the database.
     *
     * <p>Runs on the calling thread — call from a background thread.</p>
     *
     * @return compact binary manifest, or empty manifest (header only) if no messages
     */
    public byte[] buildManifest() {
        final List<MessageEntity> messages = AppDatabase.getInstance(context)
                .messageDao()
                .getActiveMessagesDirect();
        return buildManifestFromMessages(messages);
    }

    /**
     * Builds a manifest from the given message list.
     *
     * @param messages list of messages to include
     * @return compact binary manifest
     */
    @VisibleForTesting
    public byte[] buildManifestFromMessages(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return buildEmptyManifest();
        }

        final int count = Math.min(messages.size(), MAX_ENTRIES);
        final List<ManifestEntry> entries = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final MessageEntity msg = messages.get(i);
            entries.add(toEntry(msg));
        }

        return serialize(entries);
    }

    /**
     * Serializes a list of entries into the compact binary wire format.
     *
     * @param entries list of manifest entries
     * @return binary payload
     */
    @VisibleForTesting
    public byte[] serialize(List<ManifestEntry> entries) {
        final int count = Math.min(entries.size(), MAX_ENTRIES);
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + count * ENTRY_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header
        buffer.put(VERSION);
        buffer.putShort((short) count);

        // Entries
        for (int i = 0; i < count; i++) {
            final ManifestEntry entry = entries.get(i);
            writeEntry(buffer, entry);
        }

        return buffer.array();
    }

    /**
     * Parses a compact binary manifest into a list of entries.
     *
     * @param data binary manifest payload
     * @return list of entries (may be empty if header-only)
     * @throws IllegalArgumentException if the data is malformed
     */
    public static List<ManifestEntry> parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Manifest too short (need at least " + HEADER_SIZE + " bytes)");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        final byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported manifest version: " + version);
        }

        final int count = buffer.getShort() & 0xFFFF;
        final int expectedSize = HEADER_SIZE + count * ENTRY_SIZE;
        if (data.length < expectedSize) {
            throw new IllegalArgumentException(
                    "Manifest truncated: expected " + expectedSize + " bytes, got " + data.length);
        }

        final List<ManifestEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(readEntry(buffer));
        }
        return entries;
    }

    /** Returns the number of entries in a manifest without fully parsing it. */
    public static int peekEntryCount(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) return 0;
        final ByteBuffer buffer = ByteBuffer.wrap(data, 1, 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getShort() & 0xFFFF;
    }

    /**
     * Returns the total byte size of the given manifest data.
     * Returns 0 for null/empty data.
     */
    public static int manifestSizeBytes(byte[] data) {
        return data != null ? data.length : 0;
    }

    private byte[] buildEmptyManifest() {
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(VERSION);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    private ManifestEntry toEntry(MessageEntity msg) {
        final byte[] checksum = computeChecksum(msg.getText());
        final int priority = ManifestEntry.priorityFromString(msg.getPriority());
        final long expiresAtSec = msg.getExpiresAt() / 1000; // ms → s
        return new ManifestEntry(msg.getId(), checksum, priority, expiresAtSec);
    }

    private void writeEntry(ByteBuffer buffer, ManifestEntry entry) {
        // bundle_id: 16 bytes from UUID
        final byte[] uuidBytes = uuidToBytes(entry.bundleId);
        buffer.put(uuidBytes, 0, 16);

        // checksum: 4 bytes
        buffer.put(entry.checksum, 0, 4);

        // priority: 1 byte
        buffer.put((byte) entry.priority);

        // reserved: 3 bytes
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.put((byte) 0);

        // expires_at: 4 bytes (epoch seconds)
        buffer.putInt((int) entry.expiresAtSec);
    }

    private static ManifestEntry readEntry(ByteBuffer buffer) {
        // bundle_id: 16 bytes → UUID string
        final byte[] uuidBytes = new byte[16];
        buffer.get(uuidBytes);
        final String bundleId = bytesToUuid(uuidBytes);

        // checksum: 4 bytes
        final byte[] checksum = new byte[4];
        buffer.get(checksum);

        // priority: 1 byte
        final int priority = buffer.get() & 0xFF;

        // reserved: 3 bytes (skip)
        buffer.get();
        buffer.get();
        buffer.get();

        // expires_at: 4 bytes
        final long expiresAtSec = buffer.getInt() & 0xFFFFFFFFL;

        return new ManifestEntry(bundleId, checksum, priority, expiresAtSec);
    }

    /**
     * Computes a 4-byte content checksum from message text (first 4 bytes of SHA-256).
     */
    @VisibleForTesting
    static byte[] computeChecksum(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final byte[] truncated = new byte[4];
            System.arraycopy(hash, 0, truncated, 0, 4);
            return truncated;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on Android
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Converts a UUID string to a 16-byte big-endian array. */
    @VisibleForTesting
    static byte[] uuidToBytes(String uuidStr) {
        try {
            final java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
            final ByteBuffer buffer = ByteBuffer.allocate(16);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            return buffer.array();
        } catch (IllegalArgumentException e) {
            // If not a valid UUID, hash the string and use first 16 bytes
            return computePaddedId(uuidStr);
        }
    }

    /** Converts a 16-byte big-endian array back to a UUID string. */
    @VisibleForTesting
    static String bytesToUuid(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        final long msb = buffer.getLong();
        final long lsb = buffer.getLong();
        return new java.util.UUID(msb, lsb).toString();
    }

    /** Pads/truncates a non-UUID string ID to exactly 16 bytes. */
    private static byte[] computePaddedId(String id) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(id.getBytes(StandardCharsets.UTF_8));
            final byte[] result = new byte[16];
            System.arraycopy(hash, 0, result, 0, 16);
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
