package com.dev.echodrop.ble;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link BleAdvertiser} payload building and parsing.
 *
 * Tests cover:
 * - Payload structure (6 bytes: 4 device_id + 2 manifest_size)
 * - Big-endian byte order
 * - Round-trip (build → parse)
 * - Manifest size range (0, max 0xFFFF)
 * - Payload parse error handling
 * - Device ID getter/setter
 * - Service UUID constant
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BleAdvertiserTest {

    private BleAdvertiser advertiser;

    @Before
    public void setUp() {
        // Create with null context since we only test payload methods
        // The advertiser won't do actual BLE ops in unit tests
        advertiser = new BleAdvertiser(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
    }

    // ── Payload Structure ─────────────────────────────────

    @Test
    public void buildPayload_is6Bytes() {
        final byte[] payload = advertiser.buildPayload();
        assertEquals(6, payload.length);
    }

    @Test
    public void buildPayload_containsDeviceIdFirst4Bytes() {
        advertiser.setDeviceId(0x12345678);
        final byte[] payload = advertiser.buildPayload();

        final ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0x12345678, buf.getInt());
    }

    @Test
    public void buildPayload_containsManifestSizeLast2Bytes() {
        advertiser.setDeviceId(0);
        advertiser.updateManifestSize(1024);

        final byte[] payload = advertiser.buildPayload();
        final ByteBuffer buf = ByteBuffer.wrap(payload, 4, 2);
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(1024, buf.getShort() & 0xFFFF);
    }

    @Test
    public void buildPayload_bigEndianOrder() {
        advertiser.setDeviceId(0x00000001);
        final byte[] payload = advertiser.buildPayload();

        // Big-endian: most significant byte first
        assertEquals(0, payload[0]);
        assertEquals(0, payload[1]);
        assertEquals(0, payload[2]);
        assertEquals(1, payload[3]);
    }

    // ── Round-Trip ────────────────────────────────────────

    @Test
    public void parsePayload_roundTrip() {
        advertiser.setDeviceId(42);
        advertiser.updateManifestSize(512);

        final byte[] payload = advertiser.buildPayload();
        final int[] parsed = BleAdvertiser.parsePayload(payload);

        assertEquals(42, parsed[0]);
        assertEquals(512, parsed[1]);
    }

    @Test
    public void parsePayload_maxManifestSize() {
        advertiser.setDeviceId(0);
        advertiser.updateManifestSize(0xFFFF);

        final byte[] payload = advertiser.buildPayload();
        final int[] parsed = BleAdvertiser.parsePayload(payload);

        assertEquals(0xFFFF, parsed[1]);
    }

    @Test
    public void parsePayload_zeroValues() {
        advertiser.setDeviceId(0);
        advertiser.updateManifestSize(0);

        final byte[] payload = advertiser.buildPayload();
        final int[] parsed = BleAdvertiser.parsePayload(payload);

        assertEquals(0, parsed[0]);
        assertEquals(0, parsed[1]);
    }

    @Test
    public void parsePayload_negativeDeviceId() {
        advertiser.setDeviceId(-1);

        final byte[] payload = advertiser.buildPayload();
        final int[] parsed = BleAdvertiser.parsePayload(payload);

        assertEquals(-1, parsed[0]);
    }

    // ── Error Handling ────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void parsePayload_nullThrows() {
        BleAdvertiser.parsePayload(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parsePayload_tooShortThrows() {
        BleAdvertiser.parsePayload(new byte[]{0x01, 0x02});
    }

    // ── Service UUID ──────────────────────────────────────

    @Test
    public void serviceUuid_isNotNull() {
        assertNotNull(BleAdvertiser.SERVICE_UUID);
    }

    @Test
    public void serviceUuid_isEchoDropUuid() {
        assertEquals(UUID.fromString("ed000001-0000-1000-8000-00805f9b34fb"),
                BleAdvertiser.SERVICE_UUID);
    }

    // ── Device ID ─────────────────────────────────────────

    @Test
    public void setDeviceId_getDeviceId() {
        advertiser.setDeviceId(99);
        assertEquals(99, advertiser.getDeviceId());
    }

    // ── Manifest Size ─────────────────────────────────────

    @Test
    public void getManifestSize_afterUpdate() {
        advertiser.updateManifestSize(256);
        assertEquals(256, advertiser.getManifestSize());
    }

    @Test
    public void updateManifestSize_clampedTo0xFFFF() {
        advertiser.updateManifestSize(100_000);
        assertEquals(0xFFFF, advertiser.getManifestSize());
    }

    // ── Initial State ─────────────────────────────────────

    @Test
    public void isRunning_initiallyFalse() {
        assertFalse(advertiser.isRunning());
    }
}
