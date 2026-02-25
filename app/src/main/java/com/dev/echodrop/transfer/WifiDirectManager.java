package com.dev.echodrop.transfer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages Wi-Fi Direct (P2P) connections for payload transfer.
 *
 * <p>Handles peer discovery, group formation, and connection lifecycle.
 * Once a connection is established the group owner address is reported
 * via {@link ConnectionCallback} so that {@link BundleSender} or
 * {@link BundleReceiver} can begin the TCP transfer.</p>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class WifiDirectManager {

    private static final String TAG = "WifiDirectManager";

    /** Callback for Wi-Fi Direct connection events. */
    public interface ConnectionCallback {
        /** Called when a Wi-Fi Direct connection is established. */
        void onConnected(@NonNull InetAddress groupOwnerAddress, boolean isGroupOwner);

        /** Called when the Wi-Fi Direct connection is lost. */
        void onDisconnected();

        /** Called when peer discovery yields results. */
        void onPeersAvailable(@NonNull List<WifiP2pDevice> peers);
    }

    private final Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private boolean discovering;

    @Nullable
    private ConnectionCallback callback;

    /** The currently discovered peers. */
    private final List<WifiP2pDevice> currentPeers = new ArrayList<>();

    public WifiDirectManager(@NonNull final Context context) {
        this.context = context.getApplicationContext();
    }

    /** Sets the callback to receive connection events. */
    public void setConnectionCallback(@Nullable final ConnectionCallback callback) {
        this.callback = callback;
    }

    /**
     * Initializes the Wi-Fi P2P manager and registers the broadcast receiver.
     * Must be called before any other method.
     */
    @SuppressLint("MissingPermission")
    public void initialize() {
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Wi-Fi P2P not supported on this device");
            return;
        }

        channel = manager.initialize(context, Looper.getMainLooper(), null);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context ctx, final Intent intent) {
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
        Log.i(TAG, "Wi-Fi Direct initialized");
    }

    /** Starts peer discovery. */
    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        if (manager == null || channel == null) {
            Log.w(TAG, "Not initialized");
            return;
        }
        if (discovering) return;

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discovering = true;
                    Log.i(TAG, "Peer discovery started");
                }

                @Override
                public void onFailure(final int reason) {
                    discovering = false;
                    Log.e(TAG, "Peer discovery failed: " + reason);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Missing Wi-Fi Direct permission", e);
        }
    }

    /** Stops peer discovery. */
    @SuppressLint("MissingPermission")
    public void stopDiscovery() {
        if (manager == null || channel == null || !discovering) return;
        try {
            manager.stopPeerDiscovery(channel, null);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Error stopping discovery", e);
        }
        discovering = false;
    }

    /**
     * Connects to a Wi-Fi Direct peer device.
     *
     * @param device the peer to connect to
     */
    @SuppressLint("MissingPermission")
    public void connect(@NonNull final WifiP2pDevice device) {
        if (manager == null || channel == null) {
            Log.w(TAG, "Not initialized");
            return;
        }

        final WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Connection initiated to " + device.deviceAddress);
                }

                @Override
                public void onFailure(final int reason) {
                    Log.e(TAG, "Connection failed to " + device.deviceAddress + " reason: " + reason);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission for connect", e);
        }
    }

    /** Disconnects from the current Wi-Fi Direct group. */
    public void disconnect() {
        if (manager == null || channel == null) return;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Disconnected from group");
            }

            @Override
            public void onFailure(final int reason) {
                Log.w(TAG, "Disconnect failed: " + reason);
            }
        });
    }

    /** Returns whether peer discovery is active. */
    public boolean isDiscovering() {
        return discovering;
    }

    /** Returns the current list of discovered peers. */
    @NonNull
    public List<WifiP2pDevice> getCurrentPeers() {
        return new ArrayList<>(currentPeers);
    }

    /** Unregisters the broadcast receiver and releases resources. */
    public void teardown() {
        stopDiscovery();
        if (receiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver already unregistered", e);
            }
            receiverRegistered = false;
        }
        if (channel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel.close();
        }
        channel = null;
        manager = null;
        Log.i(TAG, "Wi-Fi Direct torn down");
    }

    // ──────────────────── Broadcast Handling ────────────────────

    @SuppressLint("MissingPermission")
    private void handleBroadcast(@NonNull final Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                final int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.w(TAG, "Wi-Fi P2P is disabled");
                }
                break;

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                if (manager != null && channel != null) {
                    try {
                        manager.requestPeers(channel, this::onPeersDiscovered);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Missing permission for requestPeers", e);
                    }
                }
                break;

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                if (manager != null && channel != null) {
                    manager.requestConnectionInfo(channel, this::onConnectionInfoAvailable);
                }
                break;

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                // Device status changed — no action needed for MVP
                break;

            default:
                break;
        }
    }

    private void onPeersDiscovered(@NonNull final WifiP2pDeviceList peerList) {
        currentPeers.clear();
        currentPeers.addAll(peerList.getDeviceList());
        Log.i(TAG, "Discovered " + currentPeers.size() + " peers");
        if (callback != null) {
            callback.onPeersAvailable(getCurrentPeers());
        }
    }

    private void onConnectionInfoAvailable(@NonNull final WifiP2pInfo info) {
        if (info.groupFormed) {
            final InetAddress groupOwnerAddress = info.groupOwnerAddress;
            final boolean isGroupOwner = info.isGroupOwner;
            Log.i(TAG, "Connected — group owner: " + isGroupOwner
                    + " addr: " + groupOwnerAddress);
            if (callback != null && groupOwnerAddress != null) {
                callback.onConnected(groupOwnerAddress, isGroupOwner);
            }
        } else {
            Log.i(TAG, "Group dissolved");
            if (callback != null) {
                callback.onDisconnected();
            }
        }
    }
}
