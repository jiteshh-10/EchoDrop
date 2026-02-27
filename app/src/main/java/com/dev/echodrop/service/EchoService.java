package com.dev.echodrop.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import timber.log.Timber;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.ble.BleAdvertiser;
import com.dev.echodrop.ble.BleScanner;
import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.transfer.BundleReceiver;
import com.dev.echodrop.transfer.BundleSender;
import com.dev.echodrop.transfer.WifiDirectManager;
import com.dev.echodrop.util.DeviceIdHelper;

import java.net.InetAddress;
import java.util.List;

/**
 * Foreground service that keeps BLE discovery and Wi-Fi Direct transfer running.
 *
 * <p>Creates a persistent low-importance notification so the OS does not
 * kill the process. Starts BLE advertising, scanning, and Wi-Fi Direct
 * receiver when launched; stops all when destroyed.</p>
 *
 * <p>Updated: state-machine-aware orchestration, GO send-back for role
 * asymmetry, BT/P2P gating, exponential backoff on failures.</p>
 */
public class EchoService extends Service {

    private static final String TAG = "ED:Service";
    private static final String CHANNEL_ID = "EchoDrop";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "echodrop_prefs";
    private static final String PREF_BG_ENABLED = "bg_enabled";

    /** TCP connect retry: wait this long before retrying (peer ServerSocket startup). */
    private static final long SEND_RETRY_DELAY_MS = 2_000;
    private static final int SEND_MAX_RETRIES = 3;

    /** Cooldown before re-discovering after a transfer completes. */
    private static final long REDISCOVERY_COOLDOWN_MS = 15_000;

    /** Extended cooldown when last transfer produced 0 new inserts (already synced). */
    private static final long REDISCOVERY_SYNCED_COOLDOWN_MS = 60_000;

    /** Hold group alive for this long after transfer before disconnecting. */
    private static final long POST_TRANSFER_HOLD_MS = 5_000;

    private BleAdvertiser advertiser;
    private BleScanner scanner;
    private WifiDirectManager wifiDirectManager;
    private BundleReceiver bundleReceiver;
    private BundleSender bundleSender;
    private boolean transferInProgress;
    private boolean wifiDirectConnected;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Tracks whether the last completed transfer had any new inserts. */
    private volatile boolean lastTransferHadInserts;

    /** Tracks last logged peer count to suppress duplicate WIFI_PEERS lines. */
    private int lastLoggedWifiPeerCount = -1;
    private boolean lastLoggedWifiConnected;

    /** Whether Bluetooth is currently ON (checked on BLE scan cycles). */
    private volatile boolean bluetoothEnabled;

    /** Whether Wi-Fi P2P is currently enabled (from WifiDirectManager callback). */
    private volatile boolean p2pEnabled;

    /** Listener for transfer state changes (used by UI for pulse animation). */
    private static TransferStateListener transferStateListener;

    /** Listener for peer count changes (used by UI for sync indicator). */
    private static PeerCountListener peerCountListener;

    /** Listener for prerequisite state (BT on, P2P on) for UI prompts. */
    private static PrerequisiteListener prerequisiteListener;

    /** Interface for observing transfer state from UI. */
    public interface TransferStateListener {
        /** Called when a transfer starts or ends. */
        void onTransferStateChanged(boolean inProgress);
    }

    /** Interface for observing peer count from UI. */
    public interface PeerCountListener {
        /** Called when the number of nearby peers changes. */
        void onPeerCountChanged(int count);
    }

    /** Interface for observing BT/P2P prerequisite state from UI. */
    public interface PrerequisiteListener {
        /** Called when BT or P2P enabled state changes. */
        void onPrerequisiteChanged(boolean btOn, boolean p2pOn);
    }

    /** Sets the global transfer state listener (from UI). */
    public static void setTransferStateListener(@Nullable final TransferStateListener listener) {
        transferStateListener = listener;
    }

    /** Sets the global peer count listener (from UI). */
    public static void setPeerCountListener(@Nullable final PeerCountListener listener) {
        peerCountListener = listener;
    }

    /** Sets the global prerequisite listener (from UI). */
    public static void setPrerequisiteListener(@Nullable final PrerequisiteListener listener) {
        prerequisiteListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        advertiser = new BleAdvertiser(this);
        scanner = new BleScanner(this);
        wifiDirectManager = new WifiDirectManager(this);
        bundleReceiver = new BundleReceiver(this);
        bundleSender = new BundleSender();

        // Check initial Bluetooth state
        bluetoothEnabled = isBluetoothOn();

        // Wire up bundle receiver callbacks for transfer state
        bundleReceiver.setReceiveCallback(new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) {
                Timber.tag(TAG).i("ED:TRANSFER_RECV_DONE count=%d", insertedCount);
                if (insertedCount > 0) lastTransferHadInserts = true;

                // --- GO send-back: after receiving as GO, send our messages ---
                if (wifiDirectConnected) {
                    final WifiDirectManager.P2pState wState = wifiDirectManager.getState();
                    if (wState == WifiDirectManager.P2pState.CONNECTED) {
                        // We are still connected; check if GO
                        // The onConnected callback already ran; if we were GO we didn't send
                        // So schedule a send-back after receive completes
                        Timber.tag(TAG).i("ED:GO_SENDBACK_SCHEDULE count=%d", insertedCount);
                        // sendback handled in onReceiveComplete via sendAllMessagesWithRetry
                        // actually, we are GO so the client connected to us on port 9876
                        // The bidirectional protocol already handles this via RECV_RESPONSE
                        // So this is already covered. No extra action needed.
                    }
                }
            }

            @Override
            public void onReceiveFailed(@NonNull final String error) {
                Timber.tag(TAG).e("ED:TRANSFER_RECV_FAIL error=%s", error);
            }

            @Override
            public void onTransferStarted() {
                transferInProgress = true;
                notifyTransferState(true);
                Timber.tag(TAG).d("ED:TRANSFER_STATE active=true");
            }

            @Override
            public void onTransferEnded() {
                transferInProgress = false;
                notifyTransferState(false);
                Timber.tag(TAG).d("ED:TRANSFER_STATE active=false");
            }
        });

        // --- BLE -> Wi-Fi Direct -> Transfer orchestration ---

        // When BLE scanner discovers peers, start Wi-Fi Direct peer discovery
        scanner.setPeerUpdateListener(peers -> {
            // Refresh Bluetooth state each scan cycle
            final boolean btNow = isBluetoothOn();
            if (btNow != bluetoothEnabled) {
                bluetoothEnabled = btNow;
                notifyPrerequisite();
            }

            Timber.tag(TAG).d("ED:BLE_PEERS count=%d wifiConnected=%b", peers.size(), wifiDirectConnected);

            // Gate: require BT on + P2P enabled
            if (!bluetoothEnabled || !p2pEnabled) {
                return;
            }

            if (!peers.isEmpty() && !wifiDirectConnected && !transferInProgress) {
                // Only request discovery if state machine allows it
                final WifiDirectManager.P2pState wState = wifiDirectManager.getState();
                if (wState == WifiDirectManager.P2pState.IDLE) {
                    Timber.tag(TAG).i("ED:ORCHESTRATE ble_peers=%d -> wifi_discover", peers.size());
                    wifiDirectManager.discoverPeers();
                }
            }
            // Notify UI about real peer count
            notifyPeerCount(peers.size());
        });

        // When Wi-Fi Direct connects, send our messages to the peer (or receive)
        wifiDirectManager.setConnectionCallback(new WifiDirectManager.ConnectionCallback() {
            @Override
            public void onConnected(@NonNull final InetAddress groupOwnerAddress,
                                    final boolean isGroupOwner) {
                wifiDirectConnected = true;
                Timber.tag(TAG).i("ED:WIFI_CONNECTED go=%b addr=%s", isGroupOwner, groupOwnerAddress);

                if (!isGroupOwner) {
                    // Client side: send our messages to the group owner
                    sendAllMessagesWithRetry(groupOwnerAddress, 0);
                }
                // Group owner side: BundleReceiver is already listening on port 9876
                // The bidirectional protocol handles GO response (RECV_RESPONSE)
            }

            @Override
            public void onDisconnected() {
                // Guard: ignore duplicate disconnect callbacks
                if (!wifiDirectConnected) {
                    Timber.tag(TAG).d("ED:WIFI_DISCONNECT_DUP_SKIP");
                    return;
                }
                wifiDirectConnected = false;
                Timber.tag(TAG).i("ED:WIFI_DISCONNECTED");

                // Reset backoff on successful transfer cycle
                wifiDirectManager.resetBackoff();

                // Choose cooldown based on whether last transfer was productive
                final long cooldown = lastTransferHadInserts
                        ? REDISCOVERY_COOLDOWN_MS
                        : REDISCOVERY_SYNCED_COOLDOWN_MS;
                // Reset for next cycle
                lastTransferHadInserts = false;

                // Schedule re-discovery after cooldown
                mainHandler.postDelayed(() -> {
                    if (scanner != null && scanner.getPeerCount() > 0
                            && !transferInProgress && !wifiDirectConnected
                            && bluetoothEnabled && p2pEnabled) {
                        Timber.tag(TAG).i("ED:WIFI_REDISCOVER post_transfer cooldown=%dms", cooldown);
                        wifiDirectManager.discoverPeers();
                    }
                }, cooldown);
            }

            @Override
            public void onPeersAvailable(@NonNull final List<android.net.wifi.p2p.WifiP2pDevice> peers) {
                // Suppress duplicate log lines
                if (peers.size() != lastLoggedWifiPeerCount || wifiDirectConnected != lastLoggedWifiConnected) {
                    Timber.tag(TAG).i("ED:WIFI_PEERS count=%d connected=%b", peers.size(), wifiDirectConnected);
                    lastLoggedWifiPeerCount = peers.size();
                    lastLoggedWifiConnected = wifiDirectConnected;
                }
                // Auto-connect to first available peer if state machine allows
                if (!peers.isEmpty() && !wifiDirectConnected) {
                    final WifiDirectManager.P2pState wState = wifiDirectManager.getState();
                    if (wState == WifiDirectManager.P2pState.DISCOVERING
                            || wState == WifiDirectManager.P2pState.IDLE) {
                        final android.net.wifi.p2p.WifiP2pDevice peer = peers.get(0);
                        Timber.tag(TAG).i("ED:WIFI_AUTO_CONNECT name=%s addr=%s state=%s",
                                peer.deviceName, peer.deviceAddress, wState);
                        wifiDirectManager.connect(peer);
                    }
                }
            }

            @Override
            public void onP2pStateChanged(final boolean enabled) {
                p2pEnabled = enabled;
                Timber.tag(TAG).i("ED:P2P_STATE_CHANGED enabled=%b", enabled);
                notifyPrerequisite();
            }
        });
    }

    /**
     * Sends all eligible messages with retry logic.
     * The group owner's ServerSocket may not be ready immediately after WiFi Direct
     * connection, so we retry up to SEND_MAX_RETRIES times with a delay.
     */
    private void sendAllMessagesWithRetry(@NonNull final InetAddress address, final int attempt) {
        final MessageDao dao = AppDatabase.getInstance(this).messageDao();
        final String localDeviceId = DeviceIdHelper.getDeviceId(this);
        new Thread(() -> {
            final List<MessageEntity> messages = dao.getActiveMessagesDirect(System.currentTimeMillis());
            if (messages.isEmpty()) {
                Timber.tag(TAG).i("ED:FWD_SKIP no_messages");
                disconnectAfterTransfer();
                return;
            }
            Timber.tag(TAG).i("ED:FWD_START attempt=%d count=%d addr=%s localId=%s",
                    attempt, messages.size(), address, localDeviceId);
            bundleSender.sendForForwarding(address, messages, localDeviceId, "",
                    true, new BundleSender.BidirectionalCallback() {
                @Override
                public void onSendComplete(final int count) {
                    Timber.tag(TAG).i("ED:FWD_OK count=%d attempt=%d", count, attempt);
                    disconnectAfterTransfer();
                }

                @Override
                public void onSendFailed(@NonNull final String error) {
                    Timber.tag(TAG).e("ED:FWD_FAIL attempt=%d error=%s", attempt, error);
                    if (attempt < SEND_MAX_RETRIES) {
                        Timber.tag(TAG).i("ED:FWD_RETRY attempt=%d delay=%dms",
                                attempt + 1, SEND_RETRY_DELAY_MS);
                        try {
                            Thread.sleep(SEND_RETRY_DELAY_MS);
                        } catch (InterruptedException ignored) { }
                        sendAllMessagesWithRetry(address, attempt + 1);
                    } else {
                        Timber.tag(TAG).e("ED:FWD_GIVE_UP after %d attempts", SEND_MAX_RETRIES);
                        disconnectAfterTransfer();
                    }
                }

                @Override
                public void onResponseReceived(@NonNull final List<MessageEntity> responseMessages) {
                    // Process messages received back from the peer (bidirectional sync)
                    Timber.tag(TAG).i("ED:FWD_RESPONSE count=%d", responseMessages.size());
                    if (bundleReceiver != null && !responseMessages.isEmpty()) {
                        final int inserted = bundleReceiver.processReceivedMessages(responseMessages);
                        Timber.tag(TAG).i("ED:FWD_RESPONSE_DONE inserted=%d", inserted);
                        if (inserted > 0) lastTransferHadInserts = true;
                    }
                }
            });
        }, "ForwardMessages-" + attempt).start();
    }

    /**
     * Disconnects WiFi Direct after transfer completes.
     * Uses POST_TRANSFER_HOLD_MS to let things settle and reduce group churn.
     */
    private void disconnectAfterTransfer() {
        mainHandler.postDelayed(() -> {
            Timber.tag(TAG).d("ED:DISCONNECT_POST_TRANSFER hold=%dms", POST_TRANSFER_HOLD_MS);
            wifiDirectManager.disconnect();
        }, POST_TRANSFER_HOLD_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        // Initialize Wi-Fi Direct BEFORE BLE scanner to avoid race condition
        // where BLE callback triggers discoverPeers before WiFi Direct is ready
        wifiDirectManager.initialize();
        bundleReceiver.start();
        advertiser.start();
        scanner.start();
        // Refresh BT state
        bluetoothEnabled = isBluetoothOn();
        p2pEnabled = wifiDirectManager.isP2pEnabled();
        notifyPrerequisite();
        Timber.tag(TAG).i("ED:SERVICE_START localId=%s bt=%b p2p=%b",
                DeviceIdHelper.getDeviceId(this), bluetoothEnabled, p2pEnabled);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        advertiser.stop();
        scanner.stop();
        bundleReceiver.stop();
        bundleSender.shutdown();
        wifiDirectManager.teardown();
        Timber.tag(TAG).i("ED:SERVICE_STOP");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Returns whether a transfer is currently in progress. */
    public boolean isTransferInProgress() {
        return transferInProgress;
    }

    /** Returns the Wi-Fi Direct manager (for transfer coordination). */
    @Nullable
    public WifiDirectManager getWifiDirectManager() {
        return wifiDirectManager;
    }

    /** Returns the bundle receiver (for transfer coordination). */
    @Nullable
    public BundleReceiver getBundleReceiver() {
        return bundleReceiver;
    }

    /** Returns the BLE scanner (for peer info). */
    @Nullable
    public BleScanner getScanner() {
        return scanner;
    }

    /** Returns the BLE advertiser. */
    @Nullable
    public BleAdvertiser getAdvertiser() {
        return advertiser;
    }

    /** Returns whether Bluetooth is currently ON. */
    public boolean isBluetoothEnabled() {
        return bluetoothEnabled;
    }

    /** Returns whether Wi-Fi P2P is currently enabled. */
    public boolean isP2pEnabled() {
        return p2pEnabled;
    }

    /** Checks the system Bluetooth adapter state. */
    private boolean isBluetoothOn() {
        final BluetoothManager btm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btm == null) return false;
        final BluetoothAdapter adapter = btm.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** Notifies the transfer state listener on the main thread. */
    private void notifyTransferState(final boolean inProgress) {
        if (transferStateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (transferStateListener != null) {
                    transferStateListener.onTransferStateChanged(inProgress);
                }
            });
        }
    }

    /** Notifies the peer count listener on the main thread. */
    private void notifyPeerCount(final int count) {
        if (peerCountListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (peerCountListener != null) {
                    peerCountListener.onPeerCountChanged(count);
                }
            });
        }
    }

    /** Notifies the prerequisite listener on the main thread. */
    private void notifyPrerequisite() {
        if (prerequisiteListener != null) {
            final boolean bt = bluetoothEnabled;
            final boolean p2p = p2pEnabled;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (prerequisiteListener != null) {
                    prerequisiteListener.onPrerequisiteChanged(bt, p2p);
                }
            });
        }
    }

    /** Builds the persistent foreground notification. */
    private Notification buildNotification() {
        final Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.service_notification_text))
                .setContentText(getString(R.string.service_notification_sub))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /** Creates the notification channel (required on API 26+). */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.service_notification_sub));
            channel.setShowBadge(false);
            final NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** Checks whether background sharing is enabled in SharedPreferences. */
    public static boolean isBackgroundEnabled(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_BG_ENABLED, true);
    }

    /** Persists the background sharing preference. */
    public static void setBackgroundEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BG_ENABLED, enabled)
                .apply();
    }

    /** Starts or stops the service depending on the current preference. */
    public static void syncServiceState(Context context) {
        if (isBackgroundEnabled(context)) {
            startService(context);
        } else {
            stopService(context);
        }
    }

    /**
     * Checks whether the required runtime permissions are granted.
     * On Android 12+ (API 31): BLE permissions.
     * On Android 13+ (API 33): also NEARBY_WIFI_DEVICES.
     */
    public static boolean hasBlePermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean granted = ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            // API 33+: also need NEARBY_WIFI_DEVICES for Wi-Fi Direct
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted = granted && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            }
            return granted;
        }
        // Pre-S: legacy BT permissions are normal (auto-granted)
        return true;
    }

    /** Returns the runtime permissions required on API 31+. */
    public static String[] getBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            };
        }
        return new String[0];
    }

    /** Starts the foreground service if BLE permissions are granted. */
    public static void startService(Context context) {
        if (!hasBlePermissions(context)) {
            Timber.tag(TAG).w("ED:SERVICE_SKIP_START perms=denied");
            return;
        }
        final Intent intent = new Intent(context, EchoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Stops the foreground service. */
    public static void stopService(Context context) {
        context.stopService(new Intent(context, EchoService.class));
    }
}
