package com.dev.echodrop.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for GATT packet helpers and server-side dispatch behavior.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class GattServerDispatchTest {

    private GattServer gattServer;

    @Before
    public void setUp() {
        gattServer = new GattServer(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void buildPacket_containsTypeLengthAndPayload() {
        final byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);

        final byte[] packet = GattServer.buildPacket(
                GattTransferProtocol.TYPE_BUNDLE_PUSH, payload);

        assertEquals(GattTransferProtocol.HEADER_SIZE + payload.length, packet.length);
        assertEquals(GattTransferProtocol.TYPE_BUNDLE_PUSH, packet[0]);
        assertEquals(0, packet[1]);
        assertEquals(3, packet[2]);
        assertArrayEquals(payload, new byte[]{packet[3], packet[4], packet[5]});
    }

    @Test
    public void dispatchServerPacket_bundlePush_callsCallback() throws Exception {
        final List<String> received = new ArrayList<>();
        gattServer.setTransferCallback(new TestTransferCallback(received));

        final Method method = GattServer.class.getDeclaredMethod(
                "dispatchServerPacket", String.class, byte.class, byte[].class);
        method.setAccessible(true);

        final String json = "{\"id\":\"m1\"}";
        method.invoke(gattServer, "AA:BB:CC:DD:EE:FF",
                GattTransferProtocol.TYPE_BUNDLE_PUSH,
                json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, received.size());
        assertEquals(json, received.get(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void dispatchServerPacket_bundleRequest_preparesBundleResponse() throws Exception {
        gattServer.setTransferCallback(new TestTransferCallback(new ArrayList<>()));

        final Method dispatch = GattServer.class.getDeclaredMethod(
                "dispatchServerPacket", String.class, byte.class, byte[].class);
        dispatch.setAccessible(true);

        dispatch.invoke(gattServer, "11:22:33:44:55:66",
                GattTransferProtocol.TYPE_BUNDLE_REQUEST,
                "bundle-42".getBytes(StandardCharsets.UTF_8));

        final Field responsesField = GattServer.class.getDeclaredField("requestResponses");
        responsesField.setAccessible(true);
        final Map<String, byte[]> responses = (Map<String, byte[]>) responsesField.get(gattServer);

        assertTrue(responses.containsKey("11:22:33:44:55:66"));
        final byte[] packet = responses.get("11:22:33:44:55:66");
        assertNotNull(packet);
        assertEquals(GattTransferProtocol.TYPE_BUNDLE_PUSH, packet[0]);
    }

    @Test
    public void dispatchServerPacket_transferComplete_callsSessionComplete() throws Exception {
        final TestTransferCallback callback = new TestTransferCallback(new ArrayList<>());
        gattServer.setTransferCallback(callback);

        final Method method = GattServer.class.getDeclaredMethod(
                "dispatchServerPacket", String.class, byte.class, byte[].class);
        method.setAccessible(true);

        method.invoke(gattServer, "22:33:44:55:66:77",
                GattTransferProtocol.TYPE_TRANSFER_COMPLETE,
                new byte[0]);

        assertEquals("22:33:44:55:66:77", callback.completedAddress);
    }

    private static final class TestTransferCallback implements GattServer.TransferCallback {
        private final List<String> received;
        private String completedAddress;

        private TestTransferCallback(List<String> received) {
            this.received = received;
        }

        @Override
        public void onBundleReceived(String bundleJson) {
            received.add(bundleJson);
        }

        @Override
        public String getBundleJsonById(String bundleId) {
            return "{\"id\":\"" + bundleId + "\"}";
        }

        @Override
        public List<String> getActiveBundleIds() {
            return new ArrayList<>();
        }

        @Override
        public void onSessionComplete(String deviceAddress) {
            completedAddress = deviceAddress;
        }

        @Override
        public void onGattError(String deviceAddress, String message) {
            // Not used in these tests.
        }
    }
}
