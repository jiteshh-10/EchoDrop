package com.dev.echodrop.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.dev.echodrop.db.MessageEntity;

import timber.log.Timber;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire protocol for transferring message bundles over TCP (Wi-Fi Direct).
 *
 * <h3>Frame format:</h3>
 * <pre>
 *   [4-byte payload length (big-endian)] [payload bytes]
 * </pre>
 *
 * <h3>Payload structure (serialized MessageEntity):</h3>
 * <pre>
 *   id_len      : 2 bytes (string length)
 *   id          : UTF-8 bytes
 *   text_len    : 2 bytes
 *   text        : UTF-8 bytes
 *   scope_len   : 2 bytes
 *   scope       : UTF-8 bytes
 *   priority_len: 2 bytes
 *   priority    : UTF-8 bytes
 *   created_at  : 8 bytes (long)
 *   expires_at  : 8 bytes (long)
 *   hash_len    : 2 bytes
 *   content_hash: UTF-8 bytes
 *   hop_count   : 4 bytes (int)
 *   seen_by_ids : 2 bytes len + UTF-8 bytes
 *   type        : 2 bytes len + UTF-8 bytes (BROADCAST or CHAT)
 *   scope_id    : 2 bytes len + UTF-8 bytes (empty or chat_code)
 * </pre>
 *
 * <p>Messages are ordered by priority (ALERT first) before sending.</p>
 */
public final class TransferProtocol {

    private static final String TAG = "ED:Protocol";

    /** TCP port used for Wi-Fi Direct transfers. */
    public static final int PORT = 9876;

    /** Maximum frame size (512 KB) to prevent OOM from malformed data. */
    public static final int MAX_FRAME_SIZE = 512 * 1024;

    /** Magic header bytes written at start of each transfer session. */
    static final byte[] MAGIC = {'E', 'D', '0', '8'};

    private TransferProtocol() { /* Utility class */ }

    // ──────────────────── Serialization ────────────────────

    /**
     * Serializes a MessageEntity into a byte array payload.
     *
     * @param entity the message to serialize
     * @return byte array payload
     * @throws IOException if serialization fails
     */
    @NonNull
    public static byte[] serialize(@NonNull final MessageEntity entity) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        final DataOutputStream out = new DataOutputStream(baos);

        writeString(out, entity.getId());
        writeString(out, entity.getText());
        writeString(out, entity.getScope());
        writeString(out, entity.getPriority());
        out.writeLong(entity.getCreatedAt());
        out.writeLong(entity.getExpiresAt());
        writeString(out, entity.getContentHash());
        out.writeInt(entity.getHopCount());
        writeString(out, entity.getSeenByIds());
        writeString(out, entity.getType());
        writeString(out, entity.getScopeId());

        out.flush();
        return baos.toByteArray();
    }

    /**
     * Deserializes a byte array payload back into a MessageEntity.
     *
     * @param data the payload bytes
     * @return deserialized MessageEntity
     * @throws IOException if the data is malformed
     */
    @NonNull
    public static MessageEntity deserialize(@NonNull final byte[] data) throws IOException {
        final java.io.DataInputStream in =
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));

        final String id = readString(in);
        final String text = readString(in);
        final String scope = readString(in);
        final String priority = readString(in);
        final long createdAt = in.readLong();
        final long expiresAt = in.readLong();
        final String contentHash = readString(in);
        final int hopCount = in.readInt();
        final String seenByIds = readString(in);
        final String type = readString(in);
        final String scopeId = readString(in);

        final MessageEntity entity = new MessageEntity(id, text, scope, priority,
                createdAt, expiresAt, false, contentHash);
        entity.setHopCount(hopCount);
        entity.setSeenByIds(seenByIds);
        entity.setType(type);
        entity.setScopeId(scopeId);
        return entity;
    }

    // ──────────────────── Framed I/O ────────────────────

    /**
     * Writes a single framed message to the output stream.
     * Frame = [4-byte length] [payload]
     *
     * @param out    the output stream
     * @param entity the message to write
     * @throws IOException on write failure
     */
    public static void writeFrame(@NonNull final OutputStream out,
                                  @NonNull final MessageEntity entity) throws IOException {
        final byte[] payload = serialize(entity);
        final DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    /**
     * Reads a single framed message from the input stream.
     *
     * @param in the input stream
     * @return deserialized MessageEntity, or null on EOF
     * @throws IOException on read failure or malformed frame
     */
    @Nullable
    public static MessageEntity readFrame(@NonNull final InputStream in) throws IOException {
        final DataInputStream dis = new DataInputStream(in);

        final int length;
        try {
            length = dis.readInt();
        } catch (java.io.EOFException e) {
            return null; // Clean EOF — no more frames
        }

        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid frame size: " + length);
        }

        final byte[] payload = new byte[length];
        dis.readFully(payload);
        return deserialize(payload);
    }

    /**
     * Writes a session header (magic bytes) followed by all messages as frames.
     * Messages are sorted: ALERT → NORMAL → BULK.
     *
     * @param out      the output stream
     * @param messages list of messages to send
     * @throws IOException on write failure
     */
    public static void writeSession(@NonNull final OutputStream out,
                                    @NonNull final List<MessageEntity> messages) throws IOException {
        final DataOutputStream dos = new DataOutputStream(out);
        dos.write(MAGIC);
        dos.writeInt(messages.size());

        // Sort by priority: ALERT(0) → NORMAL(1) → BULK(2)
        final List<MessageEntity> sorted = new ArrayList<>(messages);
        sorted.sort((a, b) -> priorityOrdinal(a.getPriority()) - priorityOrdinal(b.getPriority()));

        for (final MessageEntity entity : sorted) {
            writeFrame(out, entity);
        }
        dos.flush();
    }

    /**
     * Reads a session header and all following frames.
     *
     * @param in the input stream
     * @return list of received MessageEntity objects
     * @throws IOException on read failure or invalid session
     */
    @NonNull
    public static List<MessageEntity> readSession(@NonNull final InputStream in) throws IOException {
        final DataInputStream dis = new DataInputStream(in);

        // Verify magic header
        final byte[] magic = new byte[MAGIC.length];
        dis.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Invalid session magic header");
            }
        }

        final int count = dis.readInt();
        if (count < 0 || count > 1000) {
            throw new IOException("Invalid message count: " + count);
        }

        final List<MessageEntity> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final MessageEntity entity = readFrame(in);
            if (entity == null) {
                Timber.tag(TAG).w("ED:PROTO_EOF_EARLY read=%d expected=%d", i, count);
                break;
            }
            result.add(entity);
        }
        return result;
    }

    // ──────────────────── Checksum Validation ────────────────────

    /**
     * Validates a received message by recomputing its content hash.
     * Verification uses SHA-256(text + scope + created_at_hour).
     *
     * @param entity the received message
     * @return true if the checksum matches
     */
    public static boolean validateChecksum(@NonNull final MessageEntity entity) {
        final String recomputed = MessageEntity.computeHash(
                entity.getText(), entity.getScope(), entity.getCreatedAt());
        return recomputed.equals(entity.getContentHash());
    }

    /**
     * Computes the transfer checksum as specified in the iteration spec:
     * SHA-256(text + scope + scope_id + created_at).
     *
     * @param entity the message to checksum
     * @return hex-encoded SHA-256 hash
     */
    @NonNull
    @VisibleForTesting
    public static String computeTransferChecksum(@NonNull final MessageEntity entity) {
        final String input = entity.getText() + entity.getScope()
                + entity.getId() + entity.getCreatedAt();
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ──────────────────── Helpers ────────────────────

    private static void writeString(@NonNull final DataOutputStream out,
                                    @NonNull final String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    @NonNull
    private static String readString(@NonNull final DataInputStream in) throws IOException {
        final int len = in.readUnsignedShort();
        if (len < 0 || len > MAX_FRAME_SIZE) {
            throw new IOException("Invalid string length: " + len);
        }
        final byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Maps priority string to ordinal for sorting. */
    @VisibleForTesting
    static int priorityOrdinal(@NonNull final String priority) {
        switch (priority.toUpperCase()) {
            case "ALERT": return 0;
            case "NORMAL": return 1;
            case "BULK": return 2;
            default: return 1;
        }
    }
}
