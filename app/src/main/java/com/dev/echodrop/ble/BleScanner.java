package com.dev.echodrop.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.dev.echodrop.util.DeviceIdHelper;

import timber.log.Timber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic BLE scanner that discovers nearby EchoDrop peers.
 *
 * <p>Duty cycle: 12 s scan, 3 s pause — favors lower end-to-end sync latency
 * in close-range sessions while still restarting scans periodically for OEM
 * stability.
 * with battery life. Detected peers are stored in a thread-safe map
 * keyed by device ID (4-byte int from the BLE payload).</p>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class BleScanner {

    private static final String TAG = "ED:BleScan";

    /** Scan window duration in milliseconds. */
    public static final long SCAN_DURATION_MS = 12_000;

    /** Pause between scans in milliseconds. */
    public static final long SCAN_PAUSE_MS = 3_000;

    /** Peer record timeout: 2 minutes without a re-detection removes the peer. */
    private static final long PEER_TIMEOUT_MS = 120_000;

    /** Minimum interval between GATT connect attempts to the same peer. */
    private static final long GATT_RETRY_INTERVAL_MS = 6_000;

    /** Minimum gap before reconnecting only because manifest changed. */
    private static final long MANIFEST_CHANGE_RETRY_MS = 1_500;

    /** Minimum interval between repetitive peer-found logs for the same peer. */
    private static final long PEER_FOUND_LOG_INTERVAL_MS = 15_000;

    /** Immediate-sync requests only target peers seen recently. */
    private static final long IMMEDIATE_SYNC_PEER_STALE_MS = 20_000;

    private final Context context;
    private final Handler handler;
    private final int localDeviceId;
    private BluetoothLeScanner bleScanner;
    private volatile boolean running;

    /** Thread-safe map of device_id → PeerInfo. */
    private final Map<Integer, PeerInfo> peers = new ConcurrentHashMap<>();

    private PeerUpdateListener peerListener;
    private GattConnectRequester gattConnectRequester;

    /** Records a discovered peer. */
    public static class PeerInfo {
        public final int deviceId;
        public int manifestSize;
        public int rssi;
        public long lastSeenMs;
        public long lastGattAttemptMs;
        public long lastFoundLogMs;
        @NonNull
        public String address;
        @androidx.annotation.Nullable
        public BluetoothDevice device;

        public PeerInfo(int deviceId, int manifestSize, int rssi,
                        @NonNull BluetoothDevice device) {
            this.deviceId = deviceId;
            this.manifestSize = manifestSize;
            this.rssi = rssi;
            this.lastSeenMs = System.currentTimeMillis();
            this.address = device.getAddress();
            this.device = device;
            this.lastFoundLogMs = 0L;
        }

        public PeerInfo(int deviceId, int manifestSize, int rssi) {
            this.deviceId = deviceId;
            this.manifestSize = manifestSize;
            this.rssi = rssi;
            this.lastSeenMs = System.currentTimeMillis();
            this.lastFoundLogMs = 0L;
            this.address = "";
            this.device = null;
        }
    }

    /** Listener for peer list changes. */
    public interface PeerUpdateListener {
        void onPeersUpdated(List<PeerInfo> currentPeers);
    }

    /** Listener for triggering GATT manifest exchange on peer discovery updates. */
    public interface GattConnectRequester {
        /**
         * @param urgent true for new peers or manifest changes where lower-latency
         *               connect attempts are preferred.
         */
        void onPeerFoundForGatt(BluetoothDevice device, boolean urgent);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                processScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Timber.tag(TAG).e("ED:BLE_SCAN_FAIL error=%d", errorCode);
        }
    };

    private final Runnable scanCycle = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            startScan();
            handler.postDelayed(stopAndPause, SCAN_DURATION_MS);
        }
    };

    private final Runnable stopAndPause = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            stopScan();
            pruneStale();
            handler.postDelayed(scanCycle, SCAN_PAUSE_MS);
        }
    };

    public BleScanner(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.localDeviceId = parseLocalDeviceId(this.context);
    }

    /** Starts the periodic scan cycle. */
    public void start() {
        if (running) return;
        running = true;
        handler.post(scanCycle);
        Timber.tag(TAG).i("ED:BLE_SCAN_START duty=12s/3s mode=LOW_LATENCY");
    }

    /** Stops the periodic scan cycle and clears pending callbacks. */
    public void stop() {
        running = false;
        handler.removeCallbacks(scanCycle);
        handler.removeCallbacks(stopAndPause);
        stopScan();
        Timber.tag(TAG).i("ED:BLE_SCAN_STOP");
    }

    /** Returns whether the scanner is currently active. */
    public boolean isRunning() {
        return running;
    }

    /** Sets a listener to receive peer list updates after each scan cycle. */
    public void setPeerUpdateListener(PeerUpdateListener listener) {
        this.peerListener = listener;
    }

    /** Sets a requester to trigger GATT manifest exchange when a new peer is found. */
    public void setGattConnectRequester(GattConnectRequester requester) {
        this.gattConnectRequester = requester;
    }

    /** Returns an unmodifiable snapshot of current peers. */
    public List<PeerInfo> getPeers() {
        return Collections.unmodifiableList(new ArrayList<>(peers.values()));
    }

    /**
     * Immediately attempts GATT sync to all recently seen peers.
     * Used after new bundle inserts to reduce relay latency.
     */
    public void requestImmediateSync() {
        if (!running || gattConnectRequester == null) return;
        final long now = System.currentTimeMillis();
        for (final PeerInfo peer : peers.values()) {
            if ((now - peer.lastSeenMs) > IMMEDIATE_SYNC_PEER_STALE_MS) continue;
            if (peer.device == null) continue;
            peer.lastGattAttemptMs = now;
            gattConnectRequester.onPeerFoundForGatt(peer.device, true);
        }
    }

    /**
     * Immediately attempts GATT sync to one known address, if present.
     */
    public void requestImmediateSyncForAddress(@NonNull String address) {
        if (!running || gattConnectRequester == null || address.trim().isEmpty()) return;
        final long now = System.currentTimeMillis();
        for (final PeerInfo peer : peers.values()) {
            if (!address.equalsIgnoreCase(peer.address)) continue;
            if (peer.device == null) return;
            peer.lastGattAttemptMs = now;
            gattConnectRequester.onPeerFoundForGatt(peer.device, true);
            return;
        }
    }

    /** Returns the number of currently known peers. */
    public int getPeerCount() {
        return peers.size();
    }

    /** Clears all known peers. */
    public void clearPeers() {
        peers.clear();
    }

    private void startScan() {
        final BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Timber.tag(TAG).w("ED:BLE_SCAN_SKIP bt_off=true");
            return;
        }

        try {
            bleScanner = adapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                Timber.tag(TAG).w("ED:BLE_SCAN_SKIP scanner=null");
                return;
            }

            final ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            // Use an unfiltered scan for better OEM compatibility; we still parse only
            // EchoDrop service data in processScanResult.
            bleScanner.startScan(new ArrayList<>(), settings, scanCallback);
        } catch (SecurityException e) {
            Timber.tag(TAG).e(e, "ED:BLE_SCAN_PERM missing permissions");
        }
    }

    private void stopScan() {
        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException e) {
                Timber.tag(TAG).w(e, "ED:BLE_SCAN_STOP_ERR");
            }
        }
        notifyPeerListener();
    }

    private void processScanResult(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return;

        final byte[] serviceData = result.getScanRecord()
                .getServiceData(new ParcelUuid(BleAdvertiser.SERVICE_UUID));
        if (serviceData == null || serviceData.length < 6) return;

        try {
            final int[] parsed = BleAdvertiser.parsePayload(serviceData);
            final int deviceId = parsed[0];
            final int manifestSize = parsed[1];
            final int rssi = result.getRssi();

            // Ignore our own advertisement to prevent self-connect churn.
            if (deviceId == localDeviceId) {
                return;
            }

            final PeerInfo existing = peers.get(deviceId);
            final boolean isNew = (existing == null);
            final long now = System.currentTimeMillis();
            final int oldManifestSize = existing != null ? existing.manifestSize : -1;

            final PeerInfo peer;
            if (isNew) {
                peer = new PeerInfo(deviceId, manifestSize, rssi, result.getDevice());
                peers.put(deviceId, peer);
            } else {
                peer = existing;
                peer.lastSeenMs = now;
                peer.manifestSize = manifestSize;
                peer.rssi = rssi;
                peer.address = result.getDevice().getAddress();
                peer.device = result.getDevice();
            }

            final boolean manifestChanged = !isNew && oldManifestSize != manifestSize;
            final boolean shouldLogFound = isNew
                    || manifestChanged
                    || (now - peer.lastFoundLogMs >= PEER_FOUND_LOG_INTERVAL_MS);
            if (shouldLogFound) {
                peer.lastFoundLogMs = now;
                Timber.tag(TAG).d("ED:BLE_PEER_FOUND id=0x%08X manifest=%dB rssi=%d new=%b",
                        deviceId, manifestSize, rssi, isNew);
            }

            if (isNew || manifestChanged) {
                notifyPeerListener();
            }

            // Trigger faster sync when peer manifest changes, with a short safety gap.
            final long sinceLastAttempt = now - peer.lastGattAttemptMs;
            final boolean retryReady = !isNew && sinceLastAttempt >= GATT_RETRY_INTERVAL_MS;
            final boolean manifestRetryReady = manifestChanged
                    && sinceLastAttempt >= MANIFEST_CHANGE_RETRY_MS;
            if ((isNew || manifestRetryReady || retryReady) && gattConnectRequester != null) {
                peer.lastGattAttemptMs = now;
                final boolean urgent = isNew || manifestChanged;
                gattConnectRequester.onPeerFoundForGatt(result.getDevice(), urgent);
            }

            notifyPeerListener();
        } catch (IllegalArgumentException e) {
            Timber.tag(TAG).w(e, "ED:BLE_SCAN_PARSE invalid payload");
        }
    }

    private static int parseLocalDeviceId(Context context) {
        try {
            return (int) Long.parseLong(DeviceIdHelper.getDeviceId(context), 16);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Removes peers not seen for more than {@link #PEER_TIMEOUT_MS}. */
    @VisibleForTesting
    void pruneStale() {
        final long now = System.currentTimeMillis();
        peers.entrySet().removeIf(entry ->
                (now - entry.getValue().lastSeenMs) > PEER_TIMEOUT_MS);
    }

    private void notifyPeerListener() {
        if (peerListener != null) {
            peerListener.onPeersUpdated(getPeers());
        }
    }

    private BluetoothAdapter getBluetoothAdapter() {
        final BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }
}
