package com.dev.echodrop.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.VisibleForTesting;

import timber.log.Timber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic BLE scanner that discovers nearby EchoDrop peers.
 *
 * <p>Duty cycle: 10 s scan, 20 s pause — balances discovery speed
 * with battery life. Detected peers are stored in a thread-safe map
 * keyed by device ID (4-byte int from the BLE payload).</p>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class BleScanner {

    private static final String TAG = "ED:BleScan";

    /** Scan window duration in milliseconds. */
    public static final long SCAN_DURATION_MS = 10_000;

    /** Pause between scans in milliseconds. */
    public static final long SCAN_PAUSE_MS = 20_000;

    /** Peer record timeout: 2 minutes without a re-detection removes the peer. */
    private static final long PEER_TIMEOUT_MS = 120_000;

    private final Context context;
    private final Handler handler;
    private BluetoothLeScanner bleScanner;
    private boolean running;

    /** Thread-safe map of device_id → PeerInfo. */
    private final Map<Integer, PeerInfo> peers = new ConcurrentHashMap<>();

    private PeerUpdateListener peerListener;
    private GattConnectRequester gattConnectRequester;

    /** Records a discovered peer. */
    public static class PeerInfo {
        public final int deviceId;
        public final int manifestSize;
        public final int rssi;
        public long lastSeenMs;

        public PeerInfo(int deviceId, int manifestSize, int rssi) {
            this.deviceId = deviceId;
            this.manifestSize = manifestSize;
            this.rssi = rssi;
            this.lastSeenMs = System.currentTimeMillis();
        }
    }

    /** Listener for peer list changes. */
    public interface PeerUpdateListener {
        void onPeersUpdated(List<PeerInfo> currentPeers);
    }

    /** Listener for triggering GATT manifest exchange on new peer discovery. */
    public interface GattConnectRequester {
        void onPeerFoundForGatt(BluetoothDevice device);
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
    }

    /** Starts the periodic scan cycle. */
    public void start() {
        if (running) return;
        running = true;
        handler.post(scanCycle);
        Timber.tag(TAG).i("ED:BLE_SCAN_START duty=10s/20s mode=LOW_LATENCY");
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

            final ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleAdvertiser.SERVICE_UUID))
                    .build();

            final ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            final List<ScanFilter> filters = new ArrayList<>();
            filters.add(filter);
            bleScanner.startScan(filters, settings, scanCallback);
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

            // Only trigger GATT for newly discovered peers (not already in map)
            final boolean isNew = !peers.containsKey(deviceId);
            final PeerInfo peer = new PeerInfo(deviceId, manifestSize, rssi);
            peers.put(deviceId, peer);
            Timber.tag(TAG).d("ED:BLE_PEER_FOUND id=0x%08X manifest=%dB rssi=%d new=%b", deviceId, manifestSize, rssi, isNew);

            // Trigger GATT manifest exchange for new peers
            if (isNew && gattConnectRequester != null) {
                gattConnectRequester.onPeerFoundForGatt(result.getDevice());
            }
        } catch (IllegalArgumentException e) {
            Timber.tag(TAG).w(e, "ED:BLE_SCAN_PARSE invalid payload");
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
