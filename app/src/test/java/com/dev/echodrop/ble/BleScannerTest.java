package com.dev.echodrop.ble;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link BleScanner}.
 *
 * Tests cover:
 * - Initial state (not running, no peers)
 * - Peer count operations
 * - Clear peers
 * - Duty cycle constants
 * - Peer info construction
 * - Stale peer pruning
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BleScannerTest {

    private BleScanner scanner;

    @Before
    public void setUp() {
        scanner = new BleScanner(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
    }

    // ── Initial State ─────────────────────────────────────

    @Test
    public void isRunning_initiallyFalse() {
        assertFalse(scanner.isRunning());
    }

    @Test
    public void getPeerCount_initiallyZero() {
        assertEquals(0, scanner.getPeerCount());
    }

    @Test
    public void getPeers_initiallyEmpty() {
        assertTrue(scanner.getPeers().isEmpty());
    }

    // ── Duty Cycle Constants ──────────────────────────────

    @Test
    public void scanDuration_is10Seconds() {
        assertEquals(10_000L, BleScanner.SCAN_DURATION_MS);
    }

    @Test
    public void scanPause_is3Seconds() {
        assertEquals(3_000L, BleScanner.SCAN_PAUSE_MS);
    }

    // ── PeerInfo ──────────────────────────────────────────

    @Test
    public void peerInfo_constructsCorrectly() {
        final BleScanner.PeerInfo peer = new BleScanner.PeerInfo(42, 512, -70);
        assertEquals(42, peer.deviceId);
        assertEquals(512, peer.manifestSize);
        assertEquals(-70, peer.rssi);
        assertTrue(peer.lastSeenMs > 0);
    }

    @Test
    public void peerInfo_lastSeenIsCurrentTime() {
        final long before = System.currentTimeMillis();
        final BleScanner.PeerInfo peer = new BleScanner.PeerInfo(1, 0, -50);
        final long after = System.currentTimeMillis();

        assertTrue(peer.lastSeenMs >= before);
        assertTrue(peer.lastSeenMs <= after);
    }

    // ── Clear Peers ───────────────────────────────────────

    @Test
    public void clearPeers_emptiesMap() {
        // getPeers is unmodifiable but clearPeers should work
        scanner.clearPeers();
        assertEquals(0, scanner.getPeerCount());
    }

    // ── Peer List Immutability ────────────────────────────

    @Test
    public void getPeers_returnsUnmodifiableList() {
        final List<BleScanner.PeerInfo> peers = scanner.getPeers();
        try {
            peers.add(new BleScanner.PeerInfo(1, 0, 0));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected
        }
    }
}
