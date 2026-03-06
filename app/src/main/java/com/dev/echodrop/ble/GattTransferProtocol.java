package com.dev.echodrop.ble;

import java.util.UUID;

/**
 * Constants for the GATT-based bundle transfer protocol.
 *
 * <p>Packet format: [1 byte type][2 bytes payload length (big-endian)][N bytes payload]
 *
 * <p>For payloads larger than {@link #MAX_PAYLOAD_PER_CHUNK}, the sender splits
 * across multiple GATT writes using the {@link #CONTINUATION_FLAG} ORed onto the
 * type byte. The final chunk has the flag cleared.</p>
 */
public final class GattTransferProtocol {

    private GattTransferProtocol() {} // Utility class

    /** Characteristic UUID for bundle transfer data. */
    public static final UUID TRANSFER_CHAR_UUID =
            UUID.fromString("ed000003-0000-1000-8000-00805f9b34fb");

    // ──────────────────── Packet Types ────────────────────

    /** Client pushes a full bundle JSON to the server. */
    public static final byte TYPE_BUNDLE_PUSH       = 0x01;

    /** Client requests a bundle by ID; server prepares it for a subsequent read. */
    public static final byte TYPE_BUNDLE_REQUEST    = 0x02;

    /** Acknowledgement (reserved for future flow-control). */
    public static final byte TYPE_BUNDLE_ACK        = 0x03;

    /** Signals the end of the transfer session. */
    public static final byte TYPE_TRANSFER_COMPLETE = 0x04;

    /** OR this onto the type byte to indicate more chunks follow. */
    public static final byte CONTINUATION_FLAG      = (byte) 0x80;

    // ──────────────────── Sizes ────────────────────

    /** Header: 1 byte type + 2 bytes payload length. */
    public static final int HEADER_SIZE = 3;

    /** Maximum bytes per GATT write (fits within negotiated MTU 517). */
    public static final int MAX_CHUNK_SIZE = 512;

    /** Maximum payload bytes per chunk: MAX_CHUNK_SIZE − HEADER_SIZE. */
    public static final int MAX_PAYLOAD_PER_CHUNK = MAX_CHUNK_SIZE - HEADER_SIZE;

    // ──────────────────── Timeouts ────────────────────

    /** Overall session timeout: disconnect if idle for this long. */
    public static final long SESSION_TIMEOUT_MS = 15_000;

    /** How long to wait for a BUNDLE_ACK after writing (reserved). */
    public static final long ACK_TIMEOUT_MS = 5_000;

    // ──────────────────── Forwarding ────────────────────

    /** Maximum hop count; bundles beyond this are not relayed. */
    public static final int MAX_HOPS = 7;
}
