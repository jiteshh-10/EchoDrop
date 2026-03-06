package com.dev.echodrop.transfer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import timber.log.Timber;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Wi-Fi Direct P2P state monitor.
 *
 * <p>This class no longer actively initiates Wi-Fi Direct connections for data
 * transfer. All bundle transfer happens over GATT (see {@link com.dev.echodrop.ble.GattServer}).
 * WifiDirectManager exists only to:</p>
 * <ul>
 *   <li>Track whether Wi-Fi P2P is enabled (system prerequisite for BLE on some OEMs)</li>
 *   <li>Clean up stale P2P groups on init</li>
 *   <li>Report P2P state changes to the UI via {@link ConnectionCallback}</li>
 * </ul>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class WifiDirectManager {

    private static final String TAG = "ED:WifiP2P";

    /** Whether Wi-Fi P2P is currently enabled (from system broadcast). */
    private volatile boolean p2pEnabled;

    /** Callback for P2P state events. */
    public interface ConnectionCallback {
        /** Called when a Wi-Fi Direct connection is established (legacy — unlikely). */
        void onConnected(@NonNull InetAddress groupOwnerAddress, boolean isGroupOwner);

        /** Called when the Wi-Fi Direct connection is lost. */
        void onDisconnected();

        /** Called when peer discovery yields results (no-op — BLE handles discovery). */
        void onPeersAvailable(@NonNull List<WifiP2pDevice> peers);

        /** Called when Wi-Fi P2P enabled state changes. */
        void onP2pStateChanged(boolean enabled);
    }

    private final Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;

    @Nullable
    private ConnectionCallback callback;

    public WifiDirectManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /** Sets the callback to receive P2P state events. */
    public void setConnectionCallback(@Nullable ConnectionCallback callback) {
        this.callback = callback;
    }

    /** Returns whether Wi-Fi P2P is enabled on this device. */
    public boolean isP2pEnabled() {
        return p2pEnabled;
    }

    // ──────────────────── Initialization ────────────────────

    /**
     * Initializes the Wi-Fi P2P manager and registers the broadcast receiver.
     * Removes any stale P2P group from a previous session.
     * Idempotent: calling multiple times is safe.
     */
    @SuppressLint("MissingPermission")
    public void initialize() {
        if (manager != null && channel != null && receiverRegistered) {
            Timber.tag(TAG).d("ED:WIFI_INIT_SKIP already_init");
            return;
        }

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Timber.tag(TAG).e("ED:WIFI_INIT_FAIL p2p_not_supported");
            return;
        }

        channel = manager.initialize(context, Looper.getMainLooper(), null);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                handleBroadcast(intent);
            }
        };

        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        // Clean up stale groups left over from a previous crash / session
        cleanupStaleGroup();

        Timber.tag(TAG).i("ED:WIFI_INIT_OK");
    }

    /** Tries to remove any lingering P2P group. */
    @SuppressLint("MissingPermission")
    private void cleanupStaleGroup() {
        if (manager == null || channel == null) return;
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Timber.tag(TAG).d("ED:WIFI_STALE_GROUP_REMOVED");
                }

                @Override
                public void onFailure(int reason) {
                    // Expected when there is no lingering group
                    Timber.tag(TAG).d("ED:WIFI_NO_STALE_GROUP reason=%d", reason);
                }
            });
        } catch (Exception e) {
            Timber.tag(TAG).d("ED:WIFI_STALE_GROUP_SKIP");
        }
    }

    // ──────────────────── Disconnect (manual cleanup) ────────────────────

    /** Removes the current P2P group if one exists. */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (manager == null || channel == null) return;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Timber.tag(TAG).d("ED:WIFI_DISCONNECT_OK");
            }

            @Override
            public void onFailure(int reason) {
                Timber.tag(TAG).d("ED:WIFI_DISCONNECT_FAIL reason=%d", reason);
            }
        });
    }

    // ──────────────────── Query ────────────────────

    /** Returns whether the manager has been initialized. */
    public boolean isInitialized() {
        return manager != null && channel != null;
    }

    /** Returns an empty peer list (BLE handles discovery). */
    @NonNull
    public List<WifiP2pDevice> getCurrentPeers() {
        return new ArrayList<>();
    }

    // ──────────────────── Teardown ────────────────────

    /** Unregisters the broadcast receiver and releases resources. */
    public void teardown() {
        if (receiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                Timber.tag(TAG).w(e, "ED:WIFI_TEARDOWN receiver already unregistered");
            }
            receiverRegistered = false;
        }
        if (channel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel.close();
        }
        channel = null;
        manager = null;
        Timber.tag(TAG).i("ED:WIFI_TEARDOWN_OK");
    }

    // ──────────────────── Broadcast Handling ────────────────────

    private void handleBroadcast(@NonNull Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                final int wifiState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                final boolean wasEnabled = p2pEnabled;
                p2pEnabled = (wifiState == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                if (wasEnabled != p2pEnabled) {
                    Timber.tag(TAG).i("ED:WIFI_P2P_%s", p2pEnabled ? "ENABLED" : "DISABLED");
                    if (callback != null) {
                        callback.onP2pStateChanged(p2pEnabled);
                    }
                }
                break;
            }

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                // No-op — BLE + GATT handles discovery
                break;

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                if (manager != null && channel != null) {
                    manager.requestConnectionInfo(channel, this::onConnectionInfoAvailable);
                }
                break;

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                break;

            default:
                break;
        }
    }

    @SuppressLint("MissingPermission")
    private void onConnectionInfoAvailable(@NonNull WifiP2pInfo info) {
        if (info.groupFormed) {
            Timber.tag(TAG).i("ED:WIFI_GROUP_FORMED go=%b addr=%s (unexpected — GATT handles transfer)",
                    info.isGroupOwner,
                    info.groupOwnerAddress != null ? info.groupOwnerAddress : "null");
            if (info.groupOwnerAddress != null && callback != null) {
                callback.onConnected(info.groupOwnerAddress, info.isGroupOwner);
            }
        } else {
            Timber.tag(TAG).d("ED:WIFI_GROUP_DISSOLVED");
            if (callback != null) {
                callback.onDisconnected();
            }
        }
    }
}
