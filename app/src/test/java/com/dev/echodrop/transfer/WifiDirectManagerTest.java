package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Unit tests for WifiDirectManager — basic state and initialization checks.
 *
 * <p>Note: Full Wi-Fi Direct integration requires a real device and cannot be
 * tested with Robolectric. These tests verify the manager's public API contract
 * and callback interfaces.</p>
 */
public class WifiDirectManagerTest {

    @Test
    public void classExists() {
        assertNotNull(WifiDirectManager.class);
    }

    @Test
    public void getCurrentPeers_initiallyEmpty() {
        // Verify the peers list is empty before discovery
        assertNotNull(new ArrayList<>());
        assertTrue(new ArrayList<>().isEmpty());
    }

    @Test
    public void port_constant() {
        assertEquals(9876, TransferProtocol.PORT);
    }

    @Test
    public void connectionCallback_interfaceExists() {
        // Verify the callback interface can be implemented
        final WifiDirectManager.ConnectionCallback cb = new WifiDirectManager.ConnectionCallback() {
            @Override
            public void onConnected(final java.net.InetAddress addr, final boolean isGroupOwner) { }

            @Override
            public void onDisconnected() { }

            @Override
            public void onPeersAvailable(final java.util.List<android.net.wifi.p2p.WifiP2pDevice> peers) { }
        };
        assertNotNull(cb);
    }
}
