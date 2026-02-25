package com.dev.echodrop.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Manages BLE advertising for EchoDrop mesh discovery.
 *
 * <p>Advertises a compact payload containing:</p>
 * <ul>
 *   <li>App UUID (16 bytes) — constant service identifier</li>
 *   <li>Device ID (4 bytes) — truncated unique device identifier</li>
 *   <li>Manifest size (2 bytes) — current manifest byte count</li>
 * </ul>
 *
 * <p>Uses LOW_POWER advertise mode to conserve battery. The advertiser
 * can be started and stopped by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class BleAdvertiser {

    private static final String TAG = "BleAdvertiser";

    /** EchoDrop custom service UUID for BLE discovery. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("ed000001-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothLeAdvertiser advertiser;
    private boolean running;

    private int deviceId;
    private int manifestSize;

    private final AdvertiseCallback callback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Advertising started");
            running = true;
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertising failed with error: " + errorCode);
            running = false;
        }
    };

    public BleAdvertiser(Context context) {
        this.context = context.getApplicationContext();
        this.deviceId = generateDeviceId();
        this.manifestSize = 0;
    }

    /** Starts BLE advertising with the current payload. */
    public void start() {
        if (running) return;

        final BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or not enabled");
            return;
        }

        if (!adapter.isMultipleAdvertisementSupported()) {
            Log.w(TAG, "BLE advertising not supported on this device");
            return;
        }

        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.w(TAG, "BluetoothLeAdvertiser not available");
                return;
            }

            final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .build();

            final AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .addServiceData(new ParcelUuid(SERVICE_UUID), buildPayload())
                    .build();

            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing BLE permissions", e);
        }
    }

    /** Stops BLE advertising. */
    public void stop() {
        if (!running || advertiser == null) return;
        try {
            advertiser.stopAdvertising(callback);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Error stopping advertising", e);
        }
        running = false;
        Log.i(TAG, "Advertising stopped");
    }

    /** Updates the manifest size in the advertised payload and restarts if running. */
    public void updateManifestSize(int bytes) {
        this.manifestSize = Math.min(bytes, 0xFFFF);
        if (running) {
            stop();
            start();
        }
    }

    /** Returns whether the advertiser is currently running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Builds the compact BLE service-data payload.
     *
     * <p>Format: device_id (4 bytes, big-endian) + manifest_size (2 bytes, big-endian)</p>
     *
     * @return 6-byte payload
     */
    @VisibleForTesting
    public byte[] buildPayload() {
        final ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(deviceId);
        buffer.putShort((short) manifestSize);
        return buffer.array();
    }

    /**
     * Parses a BLE service-data payload into device ID and manifest size.
     *
     * @param payload 6-byte payload from {@link #buildPayload()}
     * @return int array: [deviceId, manifestSize]
     * @throws IllegalArgumentException if payload is not 6 bytes
     */
    public static int[] parsePayload(byte[] payload) {
        if (payload == null || payload.length < 6) {
            throw new IllegalArgumentException("Payload must be at least 6 bytes");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        final int deviceId = buffer.getInt();
        final int manifestSize = buffer.getShort() & 0xFFFF;
        return new int[]{deviceId, manifestSize};
    }

    /** Generates a stable 4-byte device ID from the Bluetooth MAC or a random fallback. */
    private int generateDeviceId() {
        try {
            final BluetoothAdapter adapter = getBluetoothAdapter();
            if (adapter != null) {
                final String address = adapter.getAddress();
                if (address != null && !address.equals("02:00:00:00:00:00")) {
                    return address.hashCode();
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read BT address, using random ID");
        }
        return UUID.randomUUID().hashCode();
    }

    private BluetoothAdapter getBluetoothAdapter() {
        final BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    /** Returns the device ID used in BLE payloads. */
    public int getDeviceId() {
        return deviceId;
    }

    /** Sets the device ID (for testing). */
    @VisibleForTesting
    public void setDeviceId(int id) {
        this.deviceId = id;
    }

    /** Returns the current manifest size. */
    public int getManifestSize() {
        return manifestSize;
    }
}
