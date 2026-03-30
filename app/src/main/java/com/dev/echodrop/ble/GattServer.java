package com.dev.echodrop.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import timber.log.Timber;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GATT server + client for manifest exchange AND bundle transfer.
 *
 * <h3>Protocol (client-initiated):</h3>
 * <ol>
 *   <li>Client connects, requests MTU 517, discovers services</li>
 *   <li>Client writes its manifest payload to {@link #MANIFEST_CHAR_UUID}</li>
 *   <li>Client reads the remote manifest from {@link #MANIFEST_CHAR_UUID}</li>
 *   <li>Bidirectional diff: {@code remoteNeedsFromMe} + {@code iNeedFromRemote}</li>
 *   <li>Client pushes bundles via TRANSFER_CHAR (TYPE_BUNDLE_PUSH) for remoteNeedsFromMe</li>
 *   <li>Client requests bundles via TRANSFER_CHAR (TYPE_BUNDLE_REQUEST),
 *       then reads the response for iNeedFromRemote</li>
 *   <li>Client writes TYPE_TRANSFER_COMPLETE, disconnects</li>
 * </ol>
 *
 * <h3>Server side:</h3>
 * <ul>
 *   <li>MANIFEST_CHAR READ → returns local manifest payload (JSON or compact binary)</li>
 *   <li>MANIFEST_CHAR WRITE → no-op (acknowledgement only)</li>
 *   <li>TRANSFER_CHAR WRITE (BUNDLE_PUSH) → calls {@code onBundleReceived}</li>
 *   <li>TRANSFER_CHAR WRITE (BUNDLE_REQUEST) → looks up bundle, prepares read response</li>
 *   <li>TRANSFER_CHAR READ → returns prepared bundle response</li>
 *   <li>TRANSFER_CHAR WRITE (TRANSFER_COMPLETE) → calls {@code onSessionComplete}</li>
 * </ul>
 */
public class GattServer {

    private static final String TAG = "ED:GATT";

    /** Same service UUID as BleAdvertiser for discovery. */
    public static final UUID SERVICE_UUID = BleAdvertiser.SERVICE_UUID;

    /** Characteristic UUID for manifest exchange. */
    public static final UUID MANIFEST_CHAR_UUID =
            UUID.fromString("ed000002-0000-1000-8000-00805f9b34fb");

        /** Compact binary manifest version marker. */
        private static final byte MANIFEST_BINARY_V1 = 0x01;

        /** Binary manifest header: version (1) + count (2). */
        private static final int MANIFEST_BINARY_HEADER_SIZE = 3;

        /** Binary manifest ID width (UUID bytes). */
        private static final int MANIFEST_BINARY_ID_BYTES = 16;

        /** Keep payload within a conservative characteristic write size budget. */
        private static final int MANIFEST_WRITE_BUDGET_BYTES = 512;

        /** Max IDs that fit in compact binary payload under the write budget. */
        private static final int MAX_BINARY_MANIFEST_IDS =
            (MANIFEST_WRITE_BUDGET_BYTES - MANIFEST_BINARY_HEADER_SIZE) / MANIFEST_BINARY_ID_BYTES;

    private final Context context;
    private final Handler handler;
    private BluetoothGattServer gattServer;

    /** Local manifest: set of message IDs we have. */
    private volatile Set<String> localManifest = new HashSet<>();

    /** Devices currently being GATT-connected (prevents duplicates). */
    private final Set<String> connectingDevices = ConcurrentHashMap.newKeySet();

    /** Callback for transfer events. */
    private TransferCallback transferCallback;

    /** Server-side: accumulation buffer for chunked writes per device address. */
    private final ConcurrentHashMap<String, ByteArrayOutputStream> chunkBuffers =
            new ConcurrentHashMap<>();

    /** Server-side: prepared response bytes for BUNDLE_REQUEST reads. */
    private final ConcurrentHashMap<String, byte[]> requestResponses =
            new ConcurrentHashMap<>();

    // ──────────────────── Callback Interface ────────────────────

    /** Transfer event callback implemented by EchoService. */
    public interface TransferCallback {
        /** Called when a full bundle JSON is received (server-side BUNDLE_PUSH). */
        void onBundleReceived(String bundleJson);

        /** Returns the bundle JSON for the given ID, or null if not found / expired. */
        String getBundleJsonById(String bundleId);

        /** Returns the current list of active (non-expired) bundle IDs. */
        List<String> getActiveBundleIds();

        /** Called when a transfer session completes normally. */
        void onSessionComplete(String deviceAddress);

        /** Called on GATT error (timeout, connection failure, etc.). */
        void onGattError(String deviceAddress, String message);
    }

    public GattServer(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Sets the transfer callback to receive GATT events. */
    public void setTransferCallback(TransferCallback callback) {
        this.transferCallback = callback;
    }

    /** Updates the local manifest with the given message IDs. */
    public void updateManifest(List<String> messageIds) {
        localManifest = new HashSet<>(messageIds);
        Timber.tag(TAG).d("ED:GATT_MANIFEST_UPDATE count=%d", messageIds.size());
    }

    // ══════════════════════════════════════════════════════════
    //  SERVER
    // ══════════════════════════════════════════════════════════

    /** Starts the GATT server exposing manifest + transfer characteristics. */
    @SuppressLint("MissingPermission")
    public void startServer() {
        // Idempotent: skip if already open
        if (gattServer != null) {
            Timber.tag(TAG).d("ED:GATT_SERVER_ALREADY_OPEN");
            return;
        }

        final BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager == null) {
            Timber.tag(TAG).e("ED:GATT_SERVER_FAIL bt_manager=null");
            return;
        }

        try {
            gattServer = btManager.openGattServer(context, serverCallback);
            if (gattServer == null) {
                Timber.tag(TAG).e("ED:GATT_SERVER_FAIL server=null");
                return;
            }

            final BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            final BluetoothGattCharacteristic manifestChar = new BluetoothGattCharacteristic(
                    MANIFEST_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ
                            | BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ
                            | BluetoothGattCharacteristic.PERMISSION_WRITE);

            final BluetoothGattCharacteristic transferChar = new BluetoothGattCharacteristic(
                    GattTransferProtocol.TRANSFER_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ
                            | BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ
                            | BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(manifestChar);
            service.addCharacteristic(transferChar);
            gattServer.addService(service);
            Timber.tag(TAG).i("ED:GATT_SERVER_START");
        } catch (SecurityException e) {
            Timber.tag(TAG).e(e, "ED:GATT_SERVER_PERM");
        }
    }

    /** Stops the GATT server and releases resources. */
    @SuppressLint("MissingPermission")
    public void stopServer() {
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (Exception e) {
                Timber.tag(TAG).w(e, "ED:GATT_SERVER_CLOSE_ERR");
            }
            gattServer = null;
            Timber.tag(TAG).i("ED:GATT_SERVER_STOP");
        }
        chunkBuffers.clear();
        requestResponses.clear();
    }

    // ── Server callback ──

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                 int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            final UUID charUuid = characteristic.getUuid();

            if (MANIFEST_CHAR_UUID.equals(charUuid)) {
                final byte[] manifest = buildManifestPayload();
                sendReadResponse(device, requestId, offset, manifest);
                Timber.tag(TAG).d("ED:GATT_SRV_READ_MANIFEST addr=%s offset=%d len=%d",
                        device.getAddress(), offset, manifest.length);

            } else if (GattTransferProtocol.TRANSFER_CHAR_UUID.equals(charUuid)) {
                // Return prepared response from a previous BUNDLE_REQUEST write
                final byte[] response = requestResponses.remove(device.getAddress());
                if (response != null && response.length > 0) {
                    sendReadResponse(device, requestId, offset, response);
                    Timber.tag(TAG).d("ED:GATT_SRV_READ_TRANSFER addr=%s offset=%d len=%d",
                            device.getAddress(), offset, response.length);
                } else {
                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
                }

            } else {
                gattServer.sendResponse(device, requestId,
                        BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            final UUID charUuid = characteristic.getUuid();

            if (MANIFEST_CHAR_UUID.equals(charUuid)) {
                // Client wrote its manifest — just acknowledge
                Timber.tag(TAG).d("ED:GATT_SRV_WRITE_MANIFEST addr=%s len=%d",
                        device.getAddress(), value != null ? value.length : 0);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, value);
                }

            } else if (GattTransferProtocol.TRANSFER_CHAR_UUID.equals(charUuid)) {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, value);
                }
                if (value != null && value.length >= GattTransferProtocol.HEADER_SIZE) {
                    handleServerTransferWrite(device, value);
                }

            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                final String addr = device.getAddress();
                chunkBuffers.remove(addr);
                requestResponses.remove(addr);
                Timber.tag(TAG).d("ED:GATT_SRV_PEER_DISCONNECTED addr=%s", addr);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void sendReadResponse(BluetoothDevice device, int requestId,
                                   int offset, byte[] data) {
        if (offset >= data.length) {
            gattServer.sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
        } else {
            final int remaining = data.length - offset;
            final int chunkLen = Math.min(remaining, GattTransferProtocol.MAX_CHUNK_SIZE);
            final byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, offset, chunk, 0, chunk.length);
            gattServer.sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, chunk);
        }
    }

    /** Processes a write to TRANSFER_CHAR on the server side. */
    private void handleServerTransferWrite(BluetoothDevice device, byte[] value) {
        final String addr = device.getAddress();
        final byte rawType = value[0];
        final boolean continuation =
                (rawType & GattTransferProtocol.CONTINUATION_FLAG) != 0;
        final byte type =
                (byte) (rawType & ~GattTransferProtocol.CONTINUATION_FLAG);
        final int payloadLen =
                ((value[1] & 0xFF) << 8) | (value[2] & 0xFF);

        final int available = value.length - GattTransferProtocol.HEADER_SIZE;
        final int copyLen = Math.min(payloadLen, available);
        final byte[] payload = new byte[copyLen];
        if (copyLen > 0) {
            System.arraycopy(value, GattTransferProtocol.HEADER_SIZE, payload, 0, copyLen);
        }

        if (continuation) {
            ByteArrayOutputStream buf = chunkBuffers.get(addr);
            if (buf == null) {
                buf = new ByteArrayOutputStream();
                chunkBuffers.put(addr, buf);
            }
            buf.write(payload, 0, payload.length);
            return;
        }

        // Final or only chunk
        byte[] fullPayload;
        final ByteArrayOutputStream buf = chunkBuffers.remove(addr);
        if (buf != null) {
            buf.write(payload, 0, payload.length);
            fullPayload = buf.toByteArray();
        } else {
            fullPayload = payload;
        }

        dispatchServerPacket(addr, type, fullPayload);
    }

    /** Dispatches a fully-assembled server-side packet. */
    private void dispatchServerPacket(String addr, byte type, byte[] payload) {
        switch (type) {
            case GattTransferProtocol.TYPE_BUNDLE_PUSH: {
                final String json = new String(payload, StandardCharsets.UTF_8);
                Timber.tag(TAG).i("ED:GATT_SRV_BUNDLE_PUSH addr=%s len=%d", addr, json.length());
                if (transferCallback != null) {
                    transferCallback.onBundleReceived(json);
                }
                break;
            }

            case GattTransferProtocol.TYPE_BUNDLE_REQUEST: {
                final String bundleId = new String(payload, StandardCharsets.UTF_8);
                Timber.tag(TAG).d("ED:GATT_SRV_BUNDLE_REQUEST addr=%s id=%s", addr, bundleId);
                if (transferCallback != null) {
                    final String bundleJson = transferCallback.getBundleJsonById(bundleId);
                    if (bundleJson != null) {
                        final byte[] respPayload =
                                bundleJson.getBytes(StandardCharsets.UTF_8);
                        requestResponses.put(addr,
                                buildPacket(GattTransferProtocol.TYPE_BUNDLE_PUSH, respPayload));
                    } else {
                        // Empty response — client will see zero-length read
                        requestResponses.put(addr, new byte[0]);
                    }
                }
                break;
            }

            case GattTransferProtocol.TYPE_BUNDLE_ACK:
                Timber.tag(TAG).d("ED:GATT_SRV_ACK addr=%s", addr);
                break;

            case GattTransferProtocol.TYPE_TRANSFER_COMPLETE:
                Timber.tag(TAG).i("ED:GATT_SRV_TRANSFER_COMPLETE addr=%s", addr);
                if (transferCallback != null) {
                    transferCallback.onSessionComplete(addr);
                }
                break;

            default:
                Timber.tag(TAG).w("ED:GATT_SRV_UNKNOWN type=0x%02X addr=%s", type, addr);
                break;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CLIENT
    // ══════════════════════════════════════════════════════════

    /**
     * Connects to a remote peer's GATT server, exchanges manifests,
     * computes a bidirectional diff, transfers bundles, then disconnects.
     *
     * <p>Deduplicated: if a connection to the same address is already in flight
     * the call is silently ignored.</p>
     */
    @SuppressLint("MissingPermission")
    public void connectAndSync(BluetoothDevice device) {
        final String addr = device.getAddress();
        if (connectingDevices.contains(addr)) {
            Timber.tag(TAG).d("ED:GATT_DUP_SKIP addr=%s", addr);
            return;
        }
        connectingDevices.add(addr);

        // Session timeout watchdog
        final Runnable timeoutRunnable = () -> {
            if (connectingDevices.remove(addr)) {
                Timber.tag(TAG).w("ED:GATT_SESSION_TIMEOUT addr=%s", addr);
                if (transferCallback != null) {
                    transferCallback.onGattError(addr, "session_timeout");
                }
            }
        };
        handler.postDelayed(timeoutRunnable, GattTransferProtocol.SESSION_TIMEOUT_MS);

        try {
            device.connectGatt(context, false,
                    new ClientGattCallback(addr, timeoutRunnable),
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            handler.removeCallbacks(timeoutRunnable);
            connectingDevices.remove(addr);
            Timber.tag(TAG).e(e, "ED:GATT_CLIENT_PERM addr=%s", addr);
            if (transferCallback != null) {
                transferCallback.onGattError(addr, "security_exception");
            }
        }
    }

    /** Returns whether a GATT exchange is in progress for the given address. */
    public boolean isConnecting(String deviceAddress) {
        return connectingDevices.contains(deviceAddress);
    }

    /** Returns the number of active GATT connections/exchanges. */
    public int getConnectingCount() {
        return connectingDevices.size();
    }

    // ── Client GATT callback (inner class for clarity) ──

    @SuppressLint("MissingPermission")
    private class ClientGattCallback extends BluetoothGattCallback {

        private final String addr;
        private final Runnable timeoutRunnable;

        // Diff results
        private List<String> remoteNeedsFromMe;
        private List<String> iNeedFromRemote;
        private int pushIdx;
        private int reqIdx;

        /** Chunk queue for a single write operation. */
        private final Queue<byte[]> chunkQueue = new LinkedList<>();

        /** True when the last write was TYPE_BUNDLE_REQUEST (need to read the response). */
        private boolean awaitingRequestResponse;

        /** Prevents re-entering advanceTransfer after TRANSFER_COMPLETE is sent. */
        private boolean transferCompleted;

        /** Guards against double discoverServices calls. */
        private volatile boolean serviceDiscoveryStarted;

        /** Fallback runnable if onMtuChanged never fires (Realme). */
        private Runnable mtuFallback;

        /** MTU fallback delay in milliseconds. */
        private static final long MTU_FALLBACK_MS = 2_000;

        /** Negotiated ATT MTU (default 23 if onMtuChanged never fires). */
        private int negotiatedMtu = 23;

        /** Accumulation buffer for offset-based manifest long reads. */
        private final ByteArrayOutputStream manifestBuffer = new ByteArrayOutputStream();

        ClientGattCallback(String addr, Runnable timeoutRunnable) {
            this.addr = addr;
            this.timeoutRunnable = timeoutRunnable;
        }

        // ── Connection lifecycle ──

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.tag(TAG).i("ED:GATT_CLI_CONNECTED addr=%s status=%d", addr, status);
                Timber.tag(TAG).i("GATT_CONNECTED addr=%s", addr);
                serviceDiscoveryStarted = false;
                gatt.requestMtu(517);
                // Fallback: discover services if onMtuChanged never fires (Realme)
                mtuFallback = () -> {
                    if (!serviceDiscoveryStarted) {
                        Timber.tag(TAG).w("ED:GATT_MTU_FALLBACK addr=%s -> discoverServices", addr);
                        serviceDiscoveryStarted = true;
                        gatt.discoverServices();
                    }
                };
                handler.postDelayed(mtuFallback, MTU_FALLBACK_MS);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (mtuFallback != null) handler.removeCallbacks(mtuFallback);
                handler.removeCallbacks(timeoutRunnable);
                connectingDevices.remove(addr);

                if (!sessionEnded && transferCallback != null) {
                    transferCallback.onGattError(addr, "unexpected_disconnect_status_" + status);
                }

                gatt.close();
                Timber.tag(TAG).i("ED:GATT_CLI_DISCONNECTED addr=%s status=%d", addr, status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Timber.tag(TAG).i("ED:GATT_MTU addr=%s mtu=%d status=%d", addr, mtu, status);
            negotiatedMtu = mtu;
            if (mtuFallback != null) handler.removeCallbacks(mtuFallback);
            if (!serviceDiscoveryStarted) {
                serviceDiscoveryStarted = true;
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            serviceDiscoveryStarted = true;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).w("ED:GATT_DISCOVER_FAIL addr=%s status=%d", addr, status);
                finishWithError(gatt, "service_discovery_failed");
                return;
            }
            final BluetoothGattService svc = gatt.getService(SERVICE_UUID);
            if (svc == null || svc.getCharacteristic(MANIFEST_CHAR_UUID) == null) {
                Timber.tag(TAG).w("ED:GATT_NO_SERVICE addr=%s", addr);
                finishWithError(gatt, "no_service");
                return;
            }
            Timber.tag(TAG).i("ED:GATT_SERVICES_OK addr=%s", addr);

            // Step 1: Write our manifest
            final BluetoothGattCharacteristic mc = svc.getCharacteristic(MANIFEST_CHAR_UUID);
            mc.setValue(buildManifestPayload());
            if (!startWrite(gatt, mc, "manifest")) {
                return;
            }
        }

        // ── Characteristic callbacks ──

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic ch, int status) {
            if (sessionEnded) return;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).w("ED:GATT_WRITE_FAIL addr=%s uuid=%s status=%d",
                        addr, ch.getUuid(), status);
                finishWithError(gatt, "write_failed");
                return;
            }

            if (MANIFEST_CHAR_UUID.equals(ch.getUuid())) {
                // Manifest written → read remote manifest
                Timber.tag(TAG).d("ED:GATT_MANIFEST_WRITE_OK addr=%s", addr);
                if (!startRead(gatt, ch, "manifest")) {
                    return;
                }
                return;
            }

            if (GattTransferProtocol.TRANSFER_CHAR_UUID.equals(ch.getUuid())) {
                // Chunk was written — send next chunk if available
                if (!chunkQueue.isEmpty()) {
                    ch.setValue(chunkQueue.poll());
                    if (!startWrite(gatt, ch, "transfer_chunk")) {
                        return;
                    }
                    return;
                }

                // All chunks for this packet sent
                if (awaitingRequestResponse) {
                    // We just finished writing a BUNDLE_REQUEST → read the response
                    if (!startRead(gatt, ch, "transfer_response")) {
                        return;
                    }
                } else if (transferCompleted) {
                    // TRANSFER_COMPLETE write confirmed — finish the session
                    finishSession(gatt);
                } else {
                    // BUNDLE_PUSH completed → advance
                    advanceTransfer(gatt);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic ch, int status) {
            if (sessionEnded) return;

            if (MANIFEST_CHAR_UUID.equals(ch.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS && ch.getValue() != null) {
                    final byte[] chunk = ch.getValue();
                    manifestBuffer.write(chunk, 0, chunk.length);

                    // ATT read response max payload = MTU - 1.
                    // If chunk fills that capacity, there may be more data.
                    final int attMaxPayload = Math.max(0,
                            Math.min(negotiatedMtu - 1, GattTransferProtocol.MAX_CHUNK_SIZE));
                    if (chunk.length >= attMaxPayload && attMaxPayload > 0) {
                        // Issue a long read with the accumulated offset
                        Timber.tag(TAG).d("ED:GATT_MANIFEST_PART addr=%s offset=%d len=%d",
                                addr, manifestBuffer.size(), chunk.length);
                        // readCharacteristic triggers the server with offset=0,
                        // but we need to pass the offset. Use the deprecated
                        // readCharacteristic; the server handles offset in
                        // onCharacteristicReadRequest. Android BLE stack handles
                        // Read Blob Request automatically for long reads.
                        // We call readCharacteristic again; the BLE stack
                        // internally sends ATT Read Blob Request with offset.
                        if (!startRead(gatt, ch, "manifest_blob")) {
                            return;
                        }
                        return;
                    }

                    // Manifest fully received
                    final byte[] fullManifest = manifestBuffer.toByteArray();
                    Timber.tag(TAG).d("ED:GATT_MANIFEST_READ_OK addr=%s total_len=%d",
                            addr, fullManifest.length);
                    onRemoteManifestReceived(gatt, fullManifest);
                } else {
                    Timber.tag(TAG).w("ED:GATT_MANIFEST_READ_FAIL addr=%s status=%d", addr, status);
                    finishWithError(gatt, "manifest_read_failed");
                }
                return;
            }

            if (GattTransferProtocol.TRANSFER_CHAR_UUID.equals(ch.getUuid())) {
                // This is the response to a BUNDLE_REQUEST
                if (status == BluetoothGatt.GATT_SUCCESS && ch.getValue() != null
                        && ch.getValue().length > 0) {
                    processRequestResponse(ch.getValue());
                }
                awaitingRequestResponse = false;
                reqIdx++;
                advanceTransfer(gatt);
            }
        }

        // ── Manifest diff ──

        private void onRemoteManifestReceived(BluetoothGatt gatt, byte[] data) {
            try {
                final Set<String> remoteIds = parseRemoteManifest(data);

                // Bidirectional diff
                iNeedFromRemote = new ArrayList<>();
                remoteNeedsFromMe = new ArrayList<>();

                for (String id : remoteIds) {
                    if (!localManifest.contains(id)) {
                        iNeedFromRemote.add(id);
                    }
                }
                for (String id : localManifest) {
                    if (!remoteIds.contains(id)) {
                        remoteNeedsFromMe.add(id);
                    }
                }

                Timber.tag(TAG).i(
                        "ED:GATT_DIFF addr=%s remote=%d local=%d iNeed=%d theyNeed=%d",
                        addr, remoteIds.size(), localManifest.size(),
                        iNeedFromRemote.size(), remoteNeedsFromMe.size());
                Timber.tag(TAG).i("MANIFEST_EXCHANGED addr=%s remote=%d local=%d",
                    addr, remoteIds.size(), localManifest.size());

                if (!iNeedFromRemote.isEmpty() || !remoteNeedsFromMe.isEmpty()) {
                    Timber.tag(TAG).i("RELAY_TRIGGERED addr=%s missing=%d send=%d",
                            addr, iNeedFromRemote.size(), remoteNeedsFromMe.size());
                }

                if (iNeedFromRemote.isEmpty() && remoteNeedsFromMe.isEmpty()) {
                    Timber.tag(TAG).d("ED:GATT_ALREADY_SYNCED addr=%s", addr);
                    pushIdx = 0;
                    reqIdx = 0;
                    advanceTransfer(gatt);
                    return;
                }

                pushIdx = 0;
                reqIdx = 0;
                advanceTransfer(gatt);

            } catch (Exception e) {
                Timber.tag(TAG).e(e, "ED:GATT_MANIFEST_PARSE_ERR addr=%s", addr);
                finishWithError(gatt, "manifest_parse_error");
            }
        }

        // ── Transfer state machine ──

        /**
         * Advances the transfer:
         * Phase 1 — push bundles the remote needs from us
         * Phase 2 — request bundles we need from the remote
         * Phase 3 — send TRANSFER_COMPLETE and disconnect
         */
        private void advanceTransfer(BluetoothGatt gatt) {
            if (sessionEnded || transferCompleted) return;

            final BluetoothGattService svc = gatt.getService(SERVICE_UUID);
            if (svc == null) {
                finishWithError(gatt, "service_lost");
                return;
            }
            final BluetoothGattCharacteristic tc =
                    svc.getCharacteristic(GattTransferProtocol.TRANSFER_CHAR_UUID);
            if (tc == null) {
                finishWithError(gatt, "transfer_char_missing");
                return;
            }

            // Phase 1: push
            while (pushIdx < remoteNeedsFromMe.size()) {
                final String id = remoteNeedsFromMe.get(pushIdx);
                pushIdx++;
                if (transferCallback != null) {
                    final String bundleJson = transferCallback.getBundleJsonById(id);
                    if (bundleJson != null) {
                        Timber.tag(TAG).i("MESSAGE_SENT id=%s to=%s", id, addr);
                        Timber.tag(TAG).i("RELAY_SENT id=%s to=%s", id, addr);
                        enqueueAndSend(gatt, tc,
                                GattTransferProtocol.TYPE_BUNDLE_PUSH,
                                bundleJson.getBytes(StandardCharsets.UTF_8));
                        awaitingRequestResponse = false;
                        return; // onCharacteristicWrite will continue
                    }
                }
                // Bundle gone (expired or deleted) — skip
            }

            // Phase 2: request
            if (reqIdx < iNeedFromRemote.size()) {
                final String id = iNeedFromRemote.get(reqIdx);
                enqueueAndSend(gatt, tc,
                        GattTransferProtocol.TYPE_BUNDLE_REQUEST,
                        id.getBytes(StandardCharsets.UTF_8));
                awaitingRequestResponse = true;
                return; // onCharacteristicWrite → read → onCharacteristicRead continues
            }

            // Phase 3: complete — set flag BEFORE sending to prevent re-entry
            transferCompleted = true;
            Timber.tag(TAG).i("ED:GATT_TRANSFER_COMPLETE addr=%s pushed=%d pulled=%d",
                    addr, pushIdx, reqIdx);
            enqueueAndSend(gatt, tc,
                    GattTransferProtocol.TYPE_TRANSFER_COMPLETE, new byte[0]);
            awaitingRequestResponse = false;
        }

        // ── Chunk queue for multi-chunk writes ──

        private void enqueueAndSend(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic tc,
                                     byte type, byte[] payload) {
            chunkQueue.clear();

            if (payload.length <= GattTransferProtocol.MAX_PAYLOAD_PER_CHUNK) {
                // Single-chunk packet
                final byte[] pkt = buildPacket(type, payload);
                tc.setValue(pkt);
                if (!startWrite(gatt, tc, "transfer_single_chunk")) {
                    return;
                }
            } else {
                // Multi-chunk
                int offset = 0;
                while (offset < payload.length) {
                    final int remaining = payload.length - offset;
                    final int chunkPayloadLen =
                            Math.min(remaining, GattTransferProtocol.MAX_PAYLOAD_PER_CHUNK);
                    final boolean isLast = (offset + chunkPayloadLen >= payload.length);

                    final byte chunkType = isLast
                            ? type
                            : (byte) (type | GattTransferProtocol.CONTINUATION_FLAG);

                    final byte[] chunkPayload = new byte[chunkPayloadLen];
                    System.arraycopy(payload, offset, chunkPayload, 0, chunkPayloadLen);
                    chunkQueue.add(buildPacket(chunkType, chunkPayload));

                    offset += chunkPayloadLen;
                }
                // Send the first chunk immediately
                tc.setValue(chunkQueue.poll());
                if (!startWrite(gatt, tc, "transfer_first_chunk")) {
                    return;
                }
            }
        }

        // ── Process BUNDLE_REQUEST response ──

        private void processRequestResponse(byte[] data) {
            // data is the raw packet from the server: [type][len][payload]
            if (data.length < GattTransferProtocol.HEADER_SIZE) return;
            final byte type =
                    (byte) (data[0] & ~GattTransferProtocol.CONTINUATION_FLAG);
            if (type != GattTransferProtocol.TYPE_BUNDLE_PUSH) return;

            final int payloadLen = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
            final int available = data.length - GattTransferProtocol.HEADER_SIZE;
            final int copyLen = Math.min(payloadLen, available);
            if (copyLen <= 0) return;

            final String json = new String(data, GattTransferProtocol.HEADER_SIZE,
                    copyLen, StandardCharsets.UTF_8);
            Timber.tag(TAG).d("ED:GATT_CLI_BUNDLE_RECV addr=%s len=%d", addr, json.length());
            if (transferCallback != null) {
                transferCallback.onBundleReceived(json);
            }
        }

        // ── Session end helpers ──

        private boolean sessionEnded;

        private void finishSession(BluetoothGatt gatt) {
            if (sessionEnded) return;
            sessionEnded = true;
            handler.removeCallbacks(timeoutRunnable);
            connectingDevices.remove(addr);
            if (transferCallback != null) {
                transferCallback.onSessionComplete(addr);
            }
            gatt.disconnect();
        }

        private void finishWithError(BluetoothGatt gatt, String msg) {
            if (sessionEnded) return;
            sessionEnded = true;
            handler.removeCallbacks(timeoutRunnable);
            connectingDevices.remove(addr);
            if (transferCallback != null) {
                transferCallback.onGattError(addr, msg);
            }
            gatt.disconnect();
        }

        private boolean startWrite(BluetoothGatt gatt,
                                   BluetoothGattCharacteristic characteristic,
                                   String operation) {
            final boolean started = gatt.writeCharacteristic(characteristic);
            if (!started) {
                Timber.tag(TAG).w("ED:GATT_WRITE_START_FAIL addr=%s op=%s uuid=%s",
                        addr, operation, characteristic.getUuid());
                finishWithError(gatt, operation + "_write_start_failed");
            }
            return started;
        }

        private boolean startRead(BluetoothGatt gatt,
                                  BluetoothGattCharacteristic characteristic,
                                  String operation) {
            final boolean started = gatt.readCharacteristic(characteristic);
            if (!started) {
                Timber.tag(TAG).w("ED:GATT_READ_START_FAIL addr=%s op=%s uuid=%s",
                        addr, operation, characteristic.getUuid());
                finishWithError(gatt, operation + "_read_start_failed");
            }
            return started;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  COMMON HELPERS
    // ══════════════════════════════════════════════════════════

    /**
     * Builds a manifest payload for GATT exchange.
     * Uses JSON while it fits in the write budget, otherwise switches to compact binary.
     */
    private byte[] buildManifestPayload() {
        final byte[] json = buildManifestJsonPayload();
        if (json.length <= MANIFEST_WRITE_BUDGET_BYTES) {
            return json;
        }

        final byte[] compact = buildManifestBinaryPayload();
        Timber.tag(TAG).w("ED:GATT_MANIFEST_FMT format=binary local=%d json_len=%d bin_len=%d",
                localManifest.size(), json.length, compact.length);
        return compact;
    }

    /** Builds manifest JSON bytes: {"have":["id1","id2",...]} */
    private byte[] buildManifestJsonPayload() {
        try {
            final JSONObject obj = new JSONObject();
            final JSONArray arr = new JSONArray();
            for (String id : localManifest) {
                arr.put(id);
            }
            obj.put("have", arr);
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "ED:GATT_MANIFEST_BUILD_ERR");
            return "{\"have\":[]}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Builds compact binary manifest payload with UUID-packed IDs. */
    private byte[] buildManifestBinaryPayload() {
        final List<String> ids = new ArrayList<>(localManifest);
        Collections.sort(ids);

        final int count = Math.min(ids.size(), MAX_BINARY_MANIFEST_IDS);
        if (ids.size() > count) {
            Timber.tag(TAG).w("ED:GATT_MANIFEST_TRUNCATED total=%d sent=%d",
                    ids.size(), count);
        }

        final ByteBuffer buffer = ByteBuffer.allocate(
                MANIFEST_BINARY_HEADER_SIZE + (count * MANIFEST_BINARY_ID_BYTES));
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(MANIFEST_BINARY_V1);
        buffer.putShort((short) count);
        for (int i = 0; i < count; i++) {
            buffer.put(idToBytes(ids.get(i)));
        }
        return buffer.array();
    }

    /** Parses remote manifest in JSON (legacy) or compact binary format. */
    private Set<String> parseRemoteManifest(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            return new HashSet<>();
        }

        if (looksLikeJsonManifest(data)) {
            final String json = new String(data, StandardCharsets.UTF_8);
            final JSONObject obj = new JSONObject(json);
            final JSONArray arr = obj.getJSONArray("have");
            final Set<String> ids = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                ids.add(arr.getString(i));
            }
            return ids;
        }

        return parseBinaryManifest(data);
    }

    private Set<String> parseBinaryManifest(byte[] data) {
        if (data.length < MANIFEST_BINARY_HEADER_SIZE) {
            throw new IllegalArgumentException("Manifest binary too short");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);
        final byte version = buffer.get();
        if (version != MANIFEST_BINARY_V1) {
            throw new IllegalArgumentException("Unsupported manifest binary version=" + version);
        }

        final int count = buffer.getShort() & 0xFFFF;
        final int expected = MANIFEST_BINARY_HEADER_SIZE + (count * MANIFEST_BINARY_ID_BYTES);
        if (data.length < expected) {
            throw new IllegalArgumentException(
                    "Manifest binary truncated expected=" + expected + " got=" + data.length);
        }

        final Set<String> ids = new HashSet<>(count);
        final byte[] raw = new byte[MANIFEST_BINARY_ID_BYTES];
        for (int i = 0; i < count; i++) {
            buffer.get(raw);
            ids.add(bytesToUuid(raw));
        }
        return ids;
    }

    private boolean looksLikeJsonManifest(byte[] data) {
        int idx = 0;
        while (idx < data.length) {
            final byte b = data[idx];
            if (!Character.isWhitespace((char) b)) {
                return b == '{' || b == '[';
            }
            idx++;
        }
        return false;
    }

    private static byte[] idToBytes(String id) {
        try {
            final UUID uuid = UUID.fromString(id);
            final ByteBuffer buffer = ByteBuffer.allocate(MANIFEST_BINARY_ID_BYTES);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            return buffer.array();
        } catch (Exception ignored) {
            return hash16(id);
        }
    }

    private static String bytesToUuid(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        final long msb = buffer.getLong();
        final long lsb = buffer.getLong();
        return new UUID(msb, lsb).toString();
    }

    private static byte[] hash16(String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] full = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final byte[] out = new byte[MANIFEST_BINARY_ID_BYTES];
            System.arraycopy(full, 0, out, 0, MANIFEST_BINARY_ID_BYTES);
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 missing", e);
        }
    }

    /**
     * Builds a transfer protocol packet: [type (1 byte)][length (2 bytes BE)][payload].
     */
    static byte[] buildPacket(byte type, byte[] payload) {
        final ByteBuffer buf = ByteBuffer.allocate(
                GattTransferProtocol.HEADER_SIZE + payload.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(type);
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }
}
