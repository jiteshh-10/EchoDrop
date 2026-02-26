package com.dev.echodrop.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

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
 * <p>Updated in Iteration 7: uses {@code sendForForwarding} with hop count,
 * seen-by-ids loop prevention, and scope-based forwarding rules.</p>
 */
public class EchoService extends Service {

    private static final String TAG = "EchoService";
    private static final String CHANNEL_ID = "EchoDrop";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "echodrop_prefs";
    private static final String PREF_BG_ENABLED = "bg_enabled";

    private BleAdvertiser advertiser;
    private BleScanner scanner;
    private WifiDirectManager wifiDirectManager;
    private BundleReceiver bundleReceiver;
    private BundleSender bundleSender;
    private boolean transferInProgress;
    private boolean wifiDirectConnected;

    /** Listener for transfer state changes (used by UI for pulse animation). */
    private static TransferStateListener transferStateListener;

    /** Listener for peer count changes (used by UI for sync indicator). */
    private static PeerCountListener peerCountListener;

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

    /** Sets the global transfer state listener (from UI). */
    public static void setTransferStateListener(@Nullable final TransferStateListener listener) {
        transferStateListener = listener;
    }

    /** Sets the global peer count listener (from UI). */
    public static void setPeerCountListener(@Nullable final PeerCountListener listener) {
        peerCountListener = listener;
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

        // Wire up bundle receiver callbacks for transfer state
        bundleReceiver.setReceiveCallback(new BundleReceiver.ReceiveCallback() {
            @Override
            public void onReceiveComplete(final int insertedCount) {
                Log.i(TAG, "Transfer complete: " + insertedCount + " messages received");
            }

            @Override
            public void onReceiveFailed(@NonNull final String error) {
                Log.e(TAG, "Transfer failed: " + error);
            }

            @Override
            public void onTransferStarted() {
                transferInProgress = true;
                notifyTransferState(true);
            }

            @Override
            public void onTransferEnded() {
                transferInProgress = false;
                notifyTransferState(false);
            }
        });

        // --- BLE → Wi-Fi Direct → Transfer orchestration ---

        // When BLE scanner discovers peers, start Wi-Fi Direct peer discovery
        scanner.setPeerUpdateListener(peers -> {
            if (!peers.isEmpty() && !wifiDirectConnected) {
                Log.i(TAG, "BLE found " + peers.size() + " peers — starting Wi-Fi Direct discovery");
                wifiDirectManager.discoverPeers();
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
                Log.i(TAG, "Wi-Fi Direct connected — groupOwner=" + isGroupOwner
                        + " addr=" + groupOwnerAddress);

                if (!isGroupOwner) {
                    // Client side: send our messages to the group owner
                    sendAllMessages(groupOwnerAddress);
                }
                // Group owner side: BundleReceiver is already listening on port 9876
            }

            @Override
            public void onDisconnected() {
                wifiDirectConnected = false;
                Log.i(TAG, "Wi-Fi Direct disconnected");
            }

            @Override
            public void onPeersAvailable(@NonNull final List<android.net.wifi.p2p.WifiP2pDevice> peers) {
                Log.i(TAG, "Wi-Fi Direct found " + peers.size() + " peers");
                // Auto-connect to first available peer if not already connected
                if (!peers.isEmpty() && !wifiDirectConnected) {
                    final android.net.wifi.p2p.WifiP2pDevice peer = peers.get(0);
                    Log.i(TAG, "Auto-connecting to peer: " + peer.deviceName);
                    wifiDirectManager.connect(peer);
                }
            }
        });
    }

    /**
     * Forwards all eligible messages to the given peer address.
     * Uses hop count, seen-by-ids, and scope rules to filter.
     */
    private void sendAllMessages(@NonNull final InetAddress address) {
        final MessageDao dao = AppDatabase.getInstance(this).messageDao();
        final String localDeviceId = DeviceIdHelper.getDeviceId(this);
        new Thread(() -> {
            final List<MessageEntity> messages = dao.getActiveMessagesDirect(System.currentTimeMillis());
            if (messages.isEmpty()) {
                Log.i(TAG, "No messages to forward");
                return;
            }
            Log.i(TAG, "Forwarding " + messages.size() + " candidate messages to " + address);
            // Use forwarding-aware send: filters by hop limit, seen-by, scope
            // peerDeviceId is unknown here (Wi-Fi Direct doesn't expose it),
            // so we pass "" — the peer will dedup via content hash
            bundleSender.sendForForwarding(address, messages, localDeviceId, "",
                    true, new BundleSender.SendCallback() {
                @Override
                public void onSendComplete(final int count) {
                    Log.i(TAG, "Forwarded " + count + " messages successfully");
                    wifiDirectManager.disconnect();
                }

                @Override
                public void onSendFailed(@NonNull final String error) {
                    Log.e(TAG, "Forward failed: " + error);
                    wifiDirectManager.disconnect();
                }
            });
        }, "ForwardMessages").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        advertiser.start();
        scanner.start();
        wifiDirectManager.initialize();
        bundleReceiver.start();
        Log.i(TAG, "EchoService started — BLE + Wi-Fi Direct active");
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
        Log.i(TAG, "EchoService destroyed");
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
     * Checks whether the required BLE runtime permissions are granted.
     * On Android 12+ (API 31), BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, and
     * BLUETOOTH_SCAN must be granted before starting a foreground service
     * with type connectedDevice.
     */
    public static boolean hasBlePermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        // Pre-S: legacy BT permissions are normal (auto-granted)
        return true;
    }

    /** Returns the BLE permissions required on API 31+. */
    public static String[] getBlePermissions() {
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
            Log.w(TAG, "Cannot start EchoService — BLE runtime permissions not granted");
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
