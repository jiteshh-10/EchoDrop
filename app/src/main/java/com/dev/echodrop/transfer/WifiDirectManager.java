package com.dev.echodrop.transfer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
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
 * <p>Uses an explicit state machine to prevent overlapping P2P operations
 * that cause reason=2 (BUSY) failures on many OEMs:</p>
 * <pre>
 *   IDLE -> DISCOVERING -> CONNECTING -> CONNECTED -> COOLDOWN -> IDLE
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>Only call {@code discoverPeers()} from IDLE state</li>
 *   <li>Stop discovery before calling {@code connect()}</li>
 *   <li>Never issue concurrent discover/connect operations</li>
 *   <li>Exponential backoff on reason=2 failures</li>
 * </ul></p>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class WifiDirectManager {

    private static final String TAG = "ED:WifiP2P";

    // ---- State Machine ----

    /** Wi-Fi Direct orchestration states. */
    public enum P2pState {
        /** No P2P operation in progress; ready to discover. */
        IDLE,
        /** {@code discoverPeers()} called; waiting for peer results. */
        DISCOVERING,
        /** {@code connect()} called; waiting for group formation. */
        CONNECTING,
        /** Group formed; transfer in progress. */
        CONNECTED,
        /** Backing off after a failure or post-transfer disconnect. */
        COOLDOWN
    }

    private volatile P2pState state = P2pState.IDLE;

    // ---- Backoff ----

    /** Exponential backoff steps for reason=2 (BUSY) failures. */
    private static final long[] BACKOFF_STEPS_MS = { 2_000, 4_000, 8_000, 15_000 };
    /** Short cooldown after reason=0 (generic) failures. */
    private static final long CONNECT_FAIL_COOLDOWN_MS = 3_000;
    private int backoffIndex;
    private Runnable scheduledBackoffResume;

    // ---- Timeouts ----

    /** Discovery auto-expires after this long if no peers connect. */
    private static final long DISCOVERY_TIMEOUT_MS = 30_000;
    private Runnable scheduledDiscoveryTimeout;

    /** Connect attempt auto-expires after this long if no group forms. */
    private static final long CONNECT_TIMEOUT_MS = 45_000;
    private Runnable scheduledConnectTimeout;

    // ---- GO Address Retry ----

    /** Max retries when groupOwnerAddress is null after group formation. */
    private static final int MAX_GO_ADDR_RETRIES = 10;
    private static final long GO_ADDR_RETRY_DELAY_MS = 250;
    private int goAddrRetryCount;

    // ---- Debounce / Logging ----

    /** Debounce: minimum interval between processing PEERS_CHANGED broadcasts. */
    private static final long PEERS_DEBOUNCE_MS = 2_000;
    private long lastPeersCallbackMs;

    /** Tracks last logged peer count to suppress duplicate log lines. */
    private int lastLoggedPeerCount = -1;

    // ---- P2P Availability ----

    /** Whether Wi-Fi P2P is currently enabled (from system broadcast). */
    private volatile boolean p2pEnabled;

    // ---- Callback Interface ----

    /** Callback for Wi-Fi Direct connection events. */
    public interface ConnectionCallback {
        /** Called when a Wi-Fi Direct connection is established. */
        void onConnected(@NonNull InetAddress groupOwnerAddress, boolean isGroupOwner);

        /** Called when the Wi-Fi Direct connection is lost. */
        void onDisconnected();

        /** Called when peer discovery yields results. */
        void onPeersAvailable(@NonNull List<WifiP2pDevice> peers);

        /** Called when Wi-Fi P2P enabled state changes. */
        void onP2pStateChanged(boolean enabled);
    }

    // ---- Fields ----

    private final Context context;
    private final Handler handler;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;

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

    /** Returns the current P2P orchestration state. */
    @NonNull
    public P2pState getState() {
        return state;
    }

    /** Returns whether Wi-Fi P2P is enabled on this device. */
    public boolean isP2pEnabled() {
        return p2pEnabled;
    }

    // ---- Initialization ----

    /**
     * Initializes the Wi-Fi P2P manager and registers the broadcast receiver.
     * Idempotent: calling multiple times from onStartCommand is safe.
     */
    @SuppressLint("MissingPermission")
    public void initialize() {
        // Guard: skip if already initialized (prevents duplicate receivers)
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

    // ---- Discovery ----

    /**
     * Starts peer discovery. Only allowed from IDLE state.
     * If not IDLE, the request is silently ignored (state machine enforced).
     */
    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        if (manager == null || channel == null) {
            Timber.tag(TAG).w("ED:WIFI_DISCOVER_SKIP not_init");
            return;
        }
        if (!p2pEnabled) {
            Timber.tag(TAG).w("ED:WIFI_DISCOVER_SKIP p2p_disabled");
            return;
        }
        // State machine: only discover from IDLE
        if (state != P2pState.IDLE) {
            Timber.tag(TAG).d("ED:WIFI_DISCOVER_SKIP state=%s", state);
            return;
        }
        state = P2pState.DISCOVERING;

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Timber.tag(TAG).i("ED:WIFI_DISCOVER_START state=DISCOVERING");
                    // Reset backoff on successful discovery
                    backoffIndex = 0;
                    // Schedule discovery timeout so we don't stay stuck in DISCOVERING
                    scheduleDiscoveryTimeout();
                }

                @Override
                public void onFailure(final int reason) {
                    Timber.tag(TAG).e("ED:WIFI_DISCOVER_FAIL reason=%d", reason);
                    if (reason == 2) {
                        // BUSY: enter cooldown with exponential backoff
                        enterCooldown();
                    } else {
                        // Other failure: return to IDLE to allow retry
                        state = P2pState.IDLE;
                    }
                }
            });
        } catch (SecurityException e) {
            state = P2pState.IDLE;
            Timber.tag(TAG).e(e, "ED:WIFI_DISCOVER_PERM");
        }
    }

    /**
     * Stops peer discovery (if active) and returns to IDLE.
     */
    @SuppressLint("MissingPermission")
    public void stopDiscovery() {
        if (manager == null || channel == null) return;
        cancelDiscoveryTimeout();
        if (state == P2pState.DISCOVERING) {
            try {
                manager.stopPeerDiscovery(channel, null);
            } catch (SecurityException | IllegalArgumentException e) {
                Timber.tag(TAG).w(e, "ED:WIFI_DISCOVER_STOP_ERR");
            }
            state = P2pState.IDLE;
        }
    }

    // ---- Connect ----

    /**
     * Connects to a Wi-Fi Direct peer device.
     * Stops discovery first, then transitions to CONNECTING state.
     *
     * @param device the peer to connect to
     */
    @SuppressLint("MissingPermission")
    public void connect(@NonNull final WifiP2pDevice device) {
        if (manager == null || channel == null) {
            Timber.tag(TAG).w("ED:WIFI_CONNECT_SKIP not_init");
            return;
        }
        // State machine: only connect from DISCOVERING (got peers) or IDLE
        if (state != P2pState.DISCOVERING && state != P2pState.IDLE) {
            Timber.tag(TAG).d("ED:WIFI_CONNECT_SKIP state=%s", state);
            return;
        }

        // Cancel discovery timeout since we're transitioning
        cancelDiscoveryTimeout();

        // Stop discovery before connecting (prevents reason=2)
        if (state == P2pState.DISCOVERING) {
            try {
                manager.stopPeerDiscovery(channel, null);
            } catch (SecurityException | IllegalArgumentException e) {
                Timber.tag(TAG).w(e, "ED:WIFI_DISCOVER_STOP_ERR");
            }
        }
        state = P2pState.CONNECTING;

        final WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Timber.tag(TAG).i("ED:WIFI_CONNECT_INIT addr=%s state=CONNECTING",
                            device.deviceAddress);
                    // Schedule connect timeout so we don't stay stuck in CONNECTING
                    scheduleConnectTimeout();
                    // State will transition to CONNECTED in onConnectionInfoAvailable
                }

                @Override
                public void onFailure(final int reason) {
                    Timber.tag(TAG).e("ED:WIFI_CONNECT_FAIL addr=%s reason=%d",
                            device.deviceAddress, reason);
                    if (reason == 2) {
                        enterCooldown();
                    } else {
                        // Short cooldown for generic failures (reason=0)
                        enterShortCooldown();
                    }
                }
            });
        } catch (SecurityException e) {
            state = P2pState.IDLE;
            Timber.tag(TAG).e(e, "ED:WIFI_CONNECT_PERM");
        }
    }

    /** Returns whether a connect is currently in-flight (CONNECTING state). */
    public boolean isConnectingInProgress() {
        return state == P2pState.CONNECTING;
    }

    // ---- Disconnect ----

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
        // State transitions happen in onConnectionInfoAvailable (GROUP_DISSOLVED)
    }

    // ---- Backoff / Cooldown ----

    /**
     * Schedules a timeout that resets DISCOVERING -> IDLE if no connect happens.
     */
    private void scheduleDiscoveryTimeout() {
        cancelDiscoveryTimeout();
        scheduledDiscoveryTimeout = () -> {
            if (state == P2pState.DISCOVERING) {
                Timber.tag(TAG).i("ED:WIFI_DISCOVER_TIMEOUT %dms -> IDLE", DISCOVERY_TIMEOUT_MS);
                state = P2pState.IDLE;
            }
        };
        handler.postDelayed(scheduledDiscoveryTimeout, DISCOVERY_TIMEOUT_MS);
    }

    /** Cancels any pending discovery timeout. */
    private void cancelDiscoveryTimeout() {
        if (scheduledDiscoveryTimeout != null) {
            handler.removeCallbacks(scheduledDiscoveryTimeout);
            scheduledDiscoveryTimeout = null;
        }
    }

    /**
     * Schedules a timeout that resets CONNECTING -> IDLE if no group forms.
     */
    private void scheduleConnectTimeout() {
        cancelConnectTimeout();
        scheduledConnectTimeout = () -> {
            if (state == P2pState.CONNECTING) {
                Timber.tag(TAG).i("ED:WIFI_CONNECT_TIMEOUT %dms -> IDLE", CONNECT_TIMEOUT_MS);
                state = P2pState.IDLE;
                // Also try to remove any lingering group
                if (manager != null && channel != null) {
                    manager.removeGroup(channel, null);
                }
            }
        };
        handler.postDelayed(scheduledConnectTimeout, CONNECT_TIMEOUT_MS);
    }

    /** Cancels any pending connect timeout. */
    private void cancelConnectTimeout() {
        if (scheduledConnectTimeout != null) {
            handler.removeCallbacks(scheduledConnectTimeout);
            scheduledConnectTimeout = null;
        }
    }

    /**
     * Short cooldown for generic connect failures (reason=0).
     */
    private void enterShortCooldown() {
        state = P2pState.COOLDOWN;
        Timber.tag(TAG).i("ED:WIFI_SHORT_COOLDOWN %dms", CONNECT_FAIL_COOLDOWN_MS);
        if (scheduledBackoffResume != null) {
            handler.removeCallbacks(scheduledBackoffResume);
        }
        scheduledBackoffResume = () -> {
            if (state == P2pState.COOLDOWN) {
                state = P2pState.IDLE;
                Timber.tag(TAG).d("ED:WIFI_SHORT_COOLDOWN_DONE -> IDLE");
            }
        };
        handler.postDelayed(scheduledBackoffResume, CONNECT_FAIL_COOLDOWN_MS);
    }

    /**
     * Enters COOLDOWN state with exponential backoff, then returns to IDLE.
     */
    private void enterCooldown() {
        state = P2pState.COOLDOWN;
        final long delay = backoffIndex < BACKOFF_STEPS_MS.length
                ? BACKOFF_STEPS_MS[backoffIndex]
                : BACKOFF_STEPS_MS[BACKOFF_STEPS_MS.length - 1];
        backoffIndex = Math.min(backoffIndex + 1, BACKOFF_STEPS_MS.length - 1);
        Timber.tag(TAG).i("ED:WIFI_COOLDOWN backoff=%dms step=%d", delay, backoffIndex);

        // Cancel any previous scheduled resume
        if (scheduledBackoffResume != null) {
            handler.removeCallbacks(scheduledBackoffResume);
        }
        scheduledBackoffResume = () -> {
            if (state == P2pState.COOLDOWN) {
                state = P2pState.IDLE;
                Timber.tag(TAG).d("ED:WIFI_COOLDOWN_DONE -> IDLE");
            }
        };
        handler.postDelayed(scheduledBackoffResume, delay);
    }

    /**
     * Resets backoff counter (call after a successful transfer).
     */
    public void resetBackoff() {
        backoffIndex = 0;
    }

    // ---- Query Methods ----

    /** Returns whether the Wi-Fi P2P manager is initialized. */
    public boolean isInitialized() {
        return manager != null && channel != null;
    }

    /** Returns whether peer discovery or connecting is active. */
    public boolean isDiscovering() {
        return state == P2pState.DISCOVERING;
    }

    /** Returns the current list of discovered peers. */
    @NonNull
    public List<WifiP2pDevice> getCurrentPeers() {
        return new ArrayList<>(currentPeers);
    }

    // ---- Teardown ----

    /** Unregisters the broadcast receiver and releases resources. */
    public void teardown() {
        // Cancel any pending callbacks
        if (scheduledBackoffResume != null) {
            handler.removeCallbacks(scheduledBackoffResume);
            scheduledBackoffResume = null;
        }
        cancelDiscoveryTimeout();
        cancelConnectTimeout();
        if (state == P2pState.DISCOVERING) {
            stopDiscovery();
        }
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
        state = P2pState.IDLE;
        Timber.tag(TAG).i("ED:WIFI_TEARDOWN_OK");
    }

    // ---- Broadcast Handling ----

    @SuppressLint("MissingPermission")
    private void handleBroadcast(@NonNull final Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                final int wifiState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                final boolean wasEnabled = p2pEnabled;
                p2pEnabled = (wifiState == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                // Only log & notify on actual state change (prevents log spam)
                if (wasEnabled != p2pEnabled) {
                    if (p2pEnabled) {
                        Timber.tag(TAG).i("ED:WIFI_P2P_ENABLED");
                    } else {
                        Timber.tag(TAG).w("ED:WIFI_P2P_DISABLED");
                    }
                    if (callback != null) {
                        callback.onP2pStateChanged(p2pEnabled);
                    }
                }
                break;
            }

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                // Debounce: skip if last callback was < PEERS_DEBOUNCE_MS ago
                final long now = System.currentTimeMillis();
                if (now - lastPeersCallbackMs < PEERS_DEBOUNCE_MS) {
                    break;
                }
                lastPeersCallbackMs = now;
                if (manager != null && channel != null) {
                    try {
                        manager.requestPeers(channel, this::onPeersDiscovered);
                    } catch (SecurityException e) {
                        Timber.tag(TAG).e(e, "ED:WIFI_PEERS_PERM");
                    }
                }
                break;
            }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                if (manager != null && channel != null) {
                    manager.requestConnectionInfo(channel, this::onConnectionInfoAvailable);
                }
                break;

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                // Device status changed: no action needed for MVP
                break;

            default:
                break;
        }
    }

    private void onPeersDiscovered(@NonNull final WifiP2pDeviceList peerList) {
        currentPeers.clear();
        currentPeers.addAll(peerList.getDeviceList());
        final int count = currentPeers.size();
        // Only log when count actually changes to avoid log spam
        if (count != lastLoggedPeerCount) {
            Timber.tag(TAG).i("ED:WIFI_PEERS_FOUND count=%d state=%s", count, state);
            lastLoggedPeerCount = count;
        }
        if (callback != null) {
            callback.onPeersAvailable(getCurrentPeers());
        }
    }

    private void onConnectionInfoAvailable(@NonNull final WifiP2pInfo info) {
        if (info.groupFormed) {
            // Cancel connect timeout — group formed successfully
            cancelConnectTimeout();
            cancelDiscoveryTimeout();

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
            state = P2pState.CONNECTED;
            backoffIndex = 0; // Reset backoff on successful connection
            Timber.tag(TAG).i("ED:WIFI_CONNECTED go=%b addr=%s state=CONNECTED",
                    isGroupOwner, groupOwnerAddress);
            if (callback != null) {
                callback.onConnected(groupOwnerAddress, isGroupOwner);
            }
        } else {
            goAddrRetryCount = 0;
            cancelConnectTimeout();
            // Only fire callback if we were in an active state
            final boolean wasActive = (state == P2pState.CONNECTED || state == P2pState.CONNECTING);
            // Don't reset DISCOVERING to IDLE on stale GROUP_DISSOLVED
            if (state == P2pState.CONNECTED || state == P2pState.CONNECTING) {
                state = P2pState.IDLE;
            }
            // Leave COOLDOWN and DISCOVERING untouched
            Timber.tag(TAG).i("ED:WIFI_GROUP_DISSOLVED wasActive=%b state=%s", wasActive, state);
            if (wasActive && callback != null) {
                callback.onDisconnected();
            }
        }
    }
}
