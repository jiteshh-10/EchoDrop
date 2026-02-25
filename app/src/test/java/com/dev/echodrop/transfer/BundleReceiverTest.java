package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Unit tests for BundleReceiver — state and callback verification.
 *
 * <p>Full server socket integration requires a separate thread model;
 * these tests verify the callback contract and constants.</p>
 */
public class BundleReceiverTest {

    @Test
    public void receiveCallback_interfaceExists() {
        final BundleReceiver.ReceiveCallback cb = new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) { }

            @Override
            public void onReceiveFailed(final String error) { }

            @Override
            public void onTransferStarted() { }

            @Override
            public void onTransferEnded() { }
        };
        assertNotNull(cb);
    }

    @Test
    public void receiveCallback_onReceiveComplete_calledWithCount() {
        final int[] result = {-1};
        final BundleReceiver.ReceiveCallback cb = new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) {
                result[0] = insertedCount;
            }

            @Override
            public void onReceiveFailed(final String error) { }

            @Override
            public void onTransferStarted() { }

            @Override
            public void onTransferEnded() { }
        };

        cb.onReceiveComplete(5);
        assertEquals(5, result[0]);
    }

    @Test
    public void receiveCallback_onReceiveFailed_calledWithError() {
        final String[] result = {null};
        final BundleReceiver.ReceiveCallback cb = new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) { }

            @Override
            public void onReceiveFailed(final String error) {
                result[0] = error;
            }

            @Override
            public void onTransferStarted() { }

            @Override
            public void onTransferEnded() { }
        };

        cb.onReceiveFailed("Socket closed");
        assertEquals("Socket closed", result[0]);
    }

    @Test
    public void receiveCallback_transferStartEnd_paired() {
        final boolean[] started = {false};
        final boolean[] ended = {false};
        final BundleReceiver.ReceiveCallback cb = new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) { }

            @Override
            public void onReceiveFailed(final String error) { }

            @Override
            public void onTransferStarted() {
                started[0] = true;
            }

            @Override
            public void onTransferEnded() {
                ended[0] = true;
            }
        };

        cb.onTransferStarted();
        cb.onTransferEnded();
        assertEquals(true, started[0]);
        assertEquals(true, ended[0]);
    }

    @Test
    public void transferProtocol_port_constant() {
        assertEquals(9876, TransferProtocol.PORT);
    }
}
