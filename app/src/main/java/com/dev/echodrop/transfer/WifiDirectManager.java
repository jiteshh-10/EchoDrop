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
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import timber.log.Timber;

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

    private static final String TAG = "ED:WifiP2P";

    /** Max retries when groupOwnerAddress is null after group formation. */
    private static final int MAX_GO_ADDR_RETRIES = 3;
    private static final long GO_ADDR_RETRY_DELAY_MS = 500;
    private int goAddrRetryCount;

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
    private final Handler handler;
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
        this.handler = new Handler(Looper.getMainLooper());
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
            Timber.tag(TAG).e("ED:WIFI_INIT_FAIL p2p_not_supported");
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
        Timber.tag(TAG).i("ED:WIFI_INIT_OK");
    }

    /** Starts peer discovery. */
    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        if (manager == null || channel == null) {
            Timber.tag(TAG).w("ED:WIFI_DISCOVER_SKIP not_init");
            return;
        }
        // Don't guard on 'discovering' — system may have timed out discovery
        // silently; always re-initiate when BLE triggers.

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discovering = true;
                    Timber.tag(TAG).i("ED:WIFI_DISCOVER_START");
                }

                @Override
                public void onFailure(final int reason) {
                    discovering = false;
                    Timber.tag(TAG).e("ED:WIFI_DISCOVER_FAIL reason=%d", reason);
                }
            });
        } catch (SecurityException e) {
            Timber.tag(TAG).e(e, "ED:WIFI_DISCOVER_PERM");
        }
    }

    /** Stops peer discovery. */
    @SuppressLint("MissingPermission")
    public void stopDiscovery() {
        if (manager == null || channel == null || !discovering) return;
        try {
            manager.stopPeerDiscovery(channel, null);
        } catch (SecurityException | IllegalArgumentException e) {
            Timber.tag(TAG).w(e, "ED:WIFI_DISCOVER_STOP_ERR");
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
            Timber.tag(TAG).w("ED:WIFI_CONNECT_SKIP not_init");
            return;
        }

        final WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Timber.tag(TAG).i("ED:WIFI_CONNECT_INIT addr=%s", device.deviceAddress);
                }

                @Override
                public void onFailure(final int reason) {
                    Timber.tag(TAG).e("ED:WIFI_CONNECT_FAIL addr=%s reason=%d", device.deviceAddress, reason);
                }
            });
        } catch (SecurityException e) {
            Timber.tag(TAG).e(e, "ED:WIFI_CONNECT_PERM");
        }
    }

    /** Disconnects from the current Wi-Fi Direct group. */
    public void disconnect() {
        if (manager == null || channel == null) return;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Timber.tag(TAG).i("ED:WIFI_DISCONNECT_OK");
            }

            @Override
            public void onFailure(final int reason) {
                Timber.tag(TAG).w("ED:WIFI_DISCONNECT_FAIL reason=%d", reason);
            }
        });
    }

    /** Returns whether the Wi-Fi P2P manager is initialized. */
    public boolean isInitialized() {
        return manager != null && channel != null;
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

    @SuppressLint("MissingPermission")
    private void handleBroadcast(@NonNull final Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                final int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Timber.tag(TAG).w("ED:WIFI_P2P_DISABLED");
                }
                break;

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                if (manager != null && channel != null) {
                    try {
                        manager.requestPeers(channel, this::onPeersDiscovered);
                    } catch (SecurityException e) {
                        Timber.tag(TAG).e(e, "ED:WIFI_PEERS_PERM");
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
        Timber.tag(TAG).i("ED:WIFI_PEERS_FOUND count=%d", currentPeers.size());
        if (callback != null) {
            callback.onPeersAvailable(getCurrentPeers());
        }
    }

    private void onConnectionInfoAvailable(@NonNull final WifiP2pInfo info) {
        if (info.groupFormed) {
            final InetAddress groupOwnerAddress = info.groupOwnerAddress;
            final boolean isGroupOwner = info.isGroupOwner;

            if (groupOwnerAddress == null) {
                // Retry: groupOwnerAddress can be null transiently
                if (goAddrRetryCount < MAX_GO_ADDR_RETRIES) {
                    goAddrRetryCount++;
                    Timber.tag(TAG).w("ED:WIFI_GO_ADDR_NULL retry=%d/%d",
                            goAddrRetryCount, MAX_GO_ADDR_RETRIES);
                    handler.postDelayed(() -> {
                        if (manager != null && channel != null) {
                            manager.requestConnectionInfo(channel,
                                    this::onConnectionInfoAvailable);
                        }
                    }, GO_ADDR_RETRY_DELAY_MS);
                    return;
                } else {
                    Timber.tag(TAG).e("ED:WIFI_GO_ADDR_NULL_GIVE_UP retries=%d", goAddrRetryCount);
                    goAddrRetryCount = 0;
                    disconnect();
                    return;
                }
            }

            goAddrRetryCount = 0;
            Timber.tag(TAG).i("ED:WIFI_CONNECTED go=%b addr=%s", isGroupOwner, groupOwnerAddress);
            if (callback != null) {
                callback.onConnected(groupOwnerAddress, isGroupOwner);
            }
        } else {
            goAddrRetryCount = 0;
            Timber.tag(TAG).i("ED:WIFI_GROUP_DISSOLVED");
            if (callback != null) {
                callback.onDisconnected();
            }
        }
    }
}
