package com.dev.echodrop.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.dev.echodrop.ble.GattServer;
import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.ChatRepo;
import com.dev.echodrop.transfer.WifiDirectManager;
import com.dev.echodrop.util.BlockedDeviceStore;
import com.dev.echodrop.util.DeviceIdHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that keeps BLE discovery and GATT transfer running.
 *
 * <p>Creates a persistent low-importance notification so the OS does not
 * kill the process. Starts BLE advertising, scanning, GATT server, and
 * Wi-Fi Direct state monitoring when launched; stops all when destroyed.</p>
 *
 * <p>All bundle transfer now happens over GATT characteristics. Wi-Fi Direct
 * is retained only for P2P state monitoring (some OEMs gate BLE on P2P).</p>
 */
public class EchoService extends Service {

    private static final String TAG = "ED:Service";
    private static final String CHANNEL_ID = "EchoDrop";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "echodrop_prefs";
    private static final String PREF_BG_ENABLED = "bg_enabled";

    /** Singleton guard: true while onStartCommand has run and service is alive. */
    private static volatile boolean isRunning;

    /** Returns whether the service is currently running. */
    public static boolean isRunning() { return isRunning; }

    /** Cooldown before allowing a new GATT session after one ends. */
    private static final long GATT_SESSION_COOLDOWN_MS = 5_000;

    /** Extended cooldown when last session exchanged zero bundles (already synced). */
    private static final long GATT_SYNCED_COOLDOWN_MS = 30_000;

    /** Exponential backoff steps (ms): 0 → 5s → 15s → 60s → 120s. */
    private static final long[] BACKOFF_STEPS_MS = { 5_000, 10_000, 20_000, 60_000, 120_000 };

    /** Phase 1: hard-disable Wi-Fi Direct runtime path. */
    private static final boolean WIFI_DIRECT_ENABLED = false;

    /** Phase 2 relay max hops. */
    private static final int PHASE2_MAX_HOPS = MessageEntity.MAX_HOP_COUNT;

    /** Intent action to stop the service from the notification "Quit" button. */
    public static final String ACTION_QUIT_SERVICE = "com.dev.echodrop.ACTION_QUIT";

    /** Current peer count for dynamic notification updates. */
    private volatile int currentPeerCount;

    private BleAdvertiser advertiser;
    private BleScanner scanner;
    private GattServer gattServer;
    private WifiDirectManager wifiDirectManager;
    private ChatRepo chatRepo;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Single-thread executor for DB operations. */
    private ExecutorService dbExecutor;

    /** Timestamp of the last completed GATT session. */
    private volatile long lastSessionEndMs;

    /** Whether the last completed session actually transferred any bundles. */
    private volatile boolean lastSessionHadInserts;

    /** Consecutive sessions that ended with zero transfers (drives backoff). */
    private volatile int consecutiveEmptySessions;

    /** Whether Bluetooth is currently ON. */
    private volatile boolean bluetoothEnabled;

    /** Whether Wi-Fi P2P is currently enabled. */
    private volatile boolean p2pEnabled;

    /** Receiver for BT ON/OFF transitions to pause/resume BLE stack safely. */
    private BroadcastReceiver bluetoothStateReceiver;

    // ──────────────────── UI Listeners ────────────────────

    private static volatile TransferStateListener transferStateListener;
    private static volatile PeerCountListener peerCountListener;
    private static volatile PrerequisiteListener prerequisiteListener;

    public interface TransferStateListener {
        void onTransferStateChanged(boolean inProgress);
    }

    public interface PeerCountListener {
        void onPeerCountChanged(int count);
    }

    public interface PrerequisiteListener {
        void onPrerequisiteChanged(boolean btOn, boolean p2pOn);
    }

    public static void setTransferStateListener(@Nullable TransferStateListener l) {
        transferStateListener = l;
    }

    public static void setPeerCountListener(@Nullable PeerCountListener l) {
        peerCountListener = l;
    }

    public static void setPrerequisiteListener(@Nullable PrerequisiteListener l) {
        prerequisiteListener = l;
    }

    // ══════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EchoDb");
            t.setDaemon(true);
            return t;
        });

        advertiser = new BleAdvertiser(this);
        scanner = new BleScanner(this);
        gattServer = new GattServer(this);
        if (getApplication() instanceof android.app.Application) {
            chatRepo = new ChatRepo((android.app.Application) getApplication());
        }
        wifiDirectManager = WIFI_DIRECT_ENABLED ? new WifiDirectManager(this) : null;
        if (!WIFI_DIRECT_ENABLED) {
            Timber.tag(TAG).i("ED:WIFI_DIRECT_DISABLED phase=1");
        }
        registerBluetoothStateReceiver();

        bluetoothEnabled = isBluetoothOn();

        // ── GATT transfer callback ──
        gattServer.setTransferCallback(new GattServer.TransferCallback() {

            @Override
            public void onBundleReceived(String bundleJson) {
                dbExecutor.execute(() -> {
                    try {
                        final MessageEntity entity = deserializeBundleJson(bundleJson);
                        if (entity == null) return;

                        // Skip expired bundles
                        if (entity.getExpiresAt() <= System.currentTimeMillis()) {
                            Timber.tag(TAG).d("ED:GATT_RECV_SKIP_EXPIRED id=%s", entity.getId());
                            return;
                        }

                        final String originId = entity.getOrigin() != null
                                ? entity.getOrigin().trim()
                                : "";
                        if (!originId.isEmpty() && BlockedDeviceStore.isBlocked(EchoService.this, originId)) {
                            Timber.tag(TAG).i("ED:GATT_RECV_SKIP_BLOCKED id=%s origin=%s",
                                    entity.getId(), originId);
                            return;
                        }

                        // Phase 2 relay gate: drop packets that already exceeded max hops.
                        if (entity.getHopCount() > PHASE2_MAX_HOPS) {
                            Timber.tag(TAG).d("ED:GATT_RECV_SKIP_HOP id=%s hops=%d max=%d",
                                    entity.getId(), entity.getHopCount(), PHASE2_MAX_HOPS);
                            return;
                        }

                        if (entity.getHopCount() >= PHASE2_MAX_HOPS) {
                            Timber.tag(TAG).d("ED:GATT_RECV_AT_HOP_LIMIT id=%s hops=%d",
                                    entity.getId(), entity.getHopCount());
                        }

                        Timber.tag(TAG).i("MESSAGE_RECEIVED id=%s", entity.getId());
                        Timber.tag(TAG).i("RELAY_RECEIVED id=%s hop=%d origin=%s",
                                entity.getId(), entity.getHopCount(), entity.getOrigin());

                        // Increment hop count and add our device ID to seenBy
                        entity.setHopCount(entity.getHopCount() + 1);
                        final String localId = DeviceIdHelper.getDeviceId(EchoService.this);
                        entity.addSeenBy(localId);
                        if (entity.getOrigin() == null || entity.getOrigin().isEmpty()) {
                            entity.setOrigin(localId);
                        }
                        if (entity.getTtlMs() <= 0L) {
                            entity.setTtlMs(Math.max(0L, entity.getExpiresAt() - entity.getCreatedAt()));
                        }

                        // CHAT bundles need chat-table processing for private chat UX.
                        if (entity.isChatBundle() && chatRepo != null) {
                            final boolean chatProcessed = chatRepo.processIncomingChatBundle(entity);
                            if (chatProcessed) {
                                Timber.tag(TAG).i("ED:GATT_RECV_CHAT code=%s", entity.getScopeId());
                            }
                        }

                        final MessageDao dao = AppDatabase.getInstance(EchoService.this).messageDao();
                        final long rowId = dao.insert(entity); // IGNORE on duplicate content_hash
                        if (rowId > 0) {
                            lastSessionHadInserts = true;
                            Timber.tag(TAG).i("DB_INSERT id=%s row=%d", entity.getId(), rowId);
                            Timber.tag(TAG).i("ED:GATT_RECV_INSERT id=%s hops=%d",
                                    entity.getId(), entity.getHopCount());
                        } else {
                            Timber.tag(TAG).d("ED:GATT_RECV_DUP id=%s", entity.getId());
                            Timber.tag(TAG).i("DEDUP_SKIPPED id=%s", entity.getId());
                        }
                    } catch (Exception e) {
                        Timber.tag(TAG).e(e, "ED:GATT_RECV_ERR");
                    }
                });
            }

            @Override
            public String getBundleJsonById(String bundleId) {
                try {
                    final Future<String> future = dbExecutor.submit(() -> {
                        final MessageDao dao = AppDatabase.getInstance(EchoService.this).messageDao();
                        final MessageEntity entity = dao.getMessageByIdSync(bundleId);
                        if (entity == null) return null;
                        if (entity.getExpiresAt() <= System.currentTimeMillis()) return null;
                        if (entity.getHopCount() >= PHASE2_MAX_HOPS) return null;
                        if (BlockedDeviceStore.isBlocked(EchoService.this, entity.getOrigin())) return null;
                        return serializeBundleJson(entity);
                    });
                    return future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Timber.tag(TAG).e(e, "ED:GATT_SERVE_BUNDLE_ERR id=%s", bundleId);
                    return null;
                }
            }

            @Override
            public List<String> getActiveBundleIds() {
                try {
                    final Future<List<String>> future = dbExecutor.submit(() -> {
                        final MessageDao dao = AppDatabase.getInstance(EchoService.this).messageDao();
                        final List<MessageEntity> active =
                                dao.getActiveMessagesDirect(System.currentTimeMillis());
                        final List<String> ids = new ArrayList<>(active.size());
                        for (MessageEntity m : active) {
                            if (m.getHopCount() >= PHASE2_MAX_HOPS) {
                                continue;
                            }
                            if (BlockedDeviceStore.isBlocked(EchoService.this, m.getOrigin())) {
                                continue;
                            }
                            ids.add(m.getId());
                        }
                        return ids;
                    });
                    return future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Timber.tag(TAG).e(e, "ED:GATT_ACTIVE_IDS_ERR");
                    return new ArrayList<>();
                }
            }

            @Override
            public void onSessionComplete(String deviceAddress) {
                Timber.tag(TAG).i("ED:GATT_SESSION_DONE addr=%s inserts=%b",
                        deviceAddress, lastSessionHadInserts);
                lastSessionEndMs = System.currentTimeMillis();
                if (lastSessionHadInserts) {
                    consecutiveEmptySessions = 0;
                } else {
                    consecutiveEmptySessions++;
                }
                notifyTransferState(false);

                // Refresh manifest after transfer so next session is accurate
                refreshGattManifest();
            }

            @Override
            public void onGattError(String deviceAddress, String message) {
                Timber.tag(TAG).w("ED:GATT_ERROR addr=%s msg=%s", deviceAddress, message);
                lastSessionEndMs = System.currentTimeMillis();
                consecutiveEmptySessions++;
                notifyTransferState(false);
            }
        });

        // ── BLE scanner → GATT connect ──
        scanner.setGattConnectRequester(device -> {
            if (!bluetoothEnabled) return;

            // Exponential backoff cooldown
            final long elapsed = System.currentTimeMillis() - lastSessionEndMs;
            final int backoffIdx = Math.min(consecutiveEmptySessions,
                    BACKOFF_STEPS_MS.length - 1);
            final long cooldown = BACKOFF_STEPS_MS[backoffIdx];
            if (elapsed < cooldown) {
                Timber.tag(TAG).d("ED:GATT_COOLDOWN addr=%s elapsed=%dms cooldown=%dms backoff=%d",
                        device.getAddress(), elapsed, cooldown, consecutiveEmptySessions);
                return;
            }

            Timber.tag(TAG).d("ED:GATT_CONNECT_REQUEST addr=%s", device.getAddress());
            lastSessionHadInserts = false; // Reset for next session
            notifyTransferState(true);
            gattServer.connectAndSync(device);
        });

        // ── BLE peer count tracking ──
        scanner.setPeerUpdateListener(peers -> {
            final boolean btNow = isBluetoothOn();
            if (btNow != bluetoothEnabled) {
                bluetoothEnabled = btNow;
                notifyPrerequisite();
            }

            Timber.tag(TAG).d("ED:BLE_PEERS count=%d", peers.size());
            if (bluetoothEnabled) {
                notifyPeerCount(peers.size());
            }
        });

        // ── Wi-Fi Direct state monitoring ──
        if (wifiDirectManager != null) {
            wifiDirectManager.setConnectionCallback(new WifiDirectManager.ConnectionCallback() {
                @Override
                public void onConnected(@NonNull java.net.InetAddress addr, boolean isGo) {
                    Timber.tag(TAG).i("ED:WIFI_CONNECTED (unexpected — GATT handles transfer)");
                }

                @Override
                public void onDisconnected() {
                    Timber.tag(TAG).d("ED:WIFI_DISCONNECTED");
                }

                @Override
                public void onPeersAvailable(@NonNull List<android.net.wifi.p2p.WifiP2pDevice> peers) {
                    // No-op
                }

                @Override
                public void onP2pStateChanged(boolean enabled) {
                    p2pEnabled = enabled;
                    Timber.tag(TAG).i("ED:P2P_STATE enabled=%b", enabled);
                    notifyPrerequisite();
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle "Quit EchoDrop" notification action
        if (intent != null && ACTION_QUIT_SERVICE.equals(intent.getAction())) {
            Timber.tag(TAG).i("ED:SERVICE_QUIT user requested stop");
            setBackgroundEnabled(this, false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        // Singleton guard: skip re-init if already running
        if (isRunning) {
            Timber.tag(TAG).d("ED:SERVICE_ALREADY_RUNNING");
            return START_STICKY;
        }
        isRunning = true;

        // Guard: don't start BLE/GATT without permissions
        if (!hasBlePermissions(this)) {
            Timber.tag(TAG).w("ED:SERVICE_START_SKIP_PERMS");
            return START_STICKY;
        }

        if (wifiDirectManager != null) {
            wifiDirectManager.initialize();
        }
        startBleStack();
        refreshGattManifest();

        bluetoothEnabled = isBluetoothOn();
        p2pEnabled = wifiDirectManager == null || wifiDirectManager.isP2pEnabled();
        notifyPrerequisite();

        Timber.tag(TAG).i("ED:SERVICE_START localId=%s bt=%b p2p=%b",
                DeviceIdHelper.getDeviceId(this), bluetoothEnabled, p2pEnabled);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
        stopBleStack();
        if (wifiDirectManager != null) {
            wifiDirectManager.teardown();
        }
        unregisterBluetoothStateReceiver();
        if (dbExecutor != null) dbExecutor.shutdownNow();
        Timber.tag(TAG).i("ED:SERVICE_STOP");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Timber.tag(TAG).w("ED:SERVICE_TASK_REMOVED");
        if (isBackgroundEnabled(this) && hasBlePermissions(this)) {
            startService(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  BUNDLE SERIALIZATION (JSON ↔ MessageEntity)
    // ══════════════════════════════════════════════════════════

    /**
     * Serializes a {@link MessageEntity} to a JSON string for GATT transfer.
     */
    static String serializeBundleJson(MessageEntity m) {
        try {
            final JSONObject o = new JSONObject();
            o.put("id", m.getId());
            o.put("text", m.getText());
            o.put("scope", m.getScope());
            o.put("priority", m.getPriority());
            o.put("createdAt", m.getCreatedAt());
            o.put("expiresAt", m.getExpiresAt());
            o.put("contentHash", m.getContentHash());
            o.put("type", m.getType());
            o.put("scopeId", m.getScopeId());
            o.put("senderAlias", m.getSenderAlias());
            o.put("seenByIds", m.getSeenByIds());
            o.put("hopCount", m.getHopCount());
            o.put("origin", m.getOrigin());
            o.put("ttl_ms", m.getTtlMs());

            // Phase 1 lightweight wire model aliases.
            o.put("timestamp", m.getCreatedAt());
            o.put("ttl", Math.max(0L, m.getExpiresAt() - m.getCreatedAt()));
            o.put("hop_count", m.getHopCount());

            if (!o.has("origin") || o.optString("origin", "").isEmpty()) {
                final String seenBy = m.getSeenByIds();
                final String origin = (seenBy == null || seenBy.trim().isEmpty())
                    ? "unknown"
                    : seenBy.split(",")[0].trim();
                o.put("origin", origin);
            }
            return o.toString();
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "ED:SERIALIZE_ERR id=%s", m.getId());
            return null;
        }
    }

    /**
     * Deserializes a JSON string received over GATT into a {@link MessageEntity}.
     * Returns null if the JSON is malformed or missing required fields.
     */
    static MessageEntity deserializeBundleJson(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            final String id = o.getString("id");
            final String text = o.getString("text");
            final String scope = o.optString("scope", MessageEntity.Scope.LOCAL.name());
            final String priority = o.optString("priority", MessageEntity.Priority.NORMAL.name());
            final long createdAt = o.has("createdAt")
                    ? o.getLong("createdAt")
                    : o.optLong("timestamp", System.currentTimeMillis());
            final long expiresAt;
            if (o.has("expiresAt")) {
                expiresAt = o.getLong("expiresAt");
            } else {
                final long ttlMs = Math.max(0L, o.optLong("ttl", 0L));
                expiresAt = createdAt + ttlMs;
            }
            final String contentHash = o.has("contentHash")
                    ? o.getString("contentHash")
                    : MessageEntity.computeHash(text, scope, createdAt);

            final MessageEntity entity = new MessageEntity(
                    id, text, scope, priority, createdAt, expiresAt, false, contentHash);

            entity.setType(o.optString("type", MessageEntity.TYPE_BROADCAST));
            entity.setScopeId(o.optString("scopeId", ""));
            entity.setSenderAlias(o.optString("senderAlias", ""));
            entity.setSeenByIds(o.optString("seenByIds", ""));
            entity.setHopCount(o.has("hopCount")
                    ? o.optInt("hopCount", 0)
                    : o.optInt("hop_count", 0));
            final String origin = o.optString("origin", "");
            entity.setOrigin(origin);
            entity.setTtlMs(o.has("ttl_ms")
                    ? Math.max(0L, o.optLong("ttl_ms", 0L))
                    : Math.max(0L, expiresAt - createdAt));

            return entity;
        } catch (Exception e) {
            Timber.tag(TAG).e(e, "ED:DESERIALIZE_ERR");
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  GATT MANIFEST REFRESH
    // ══════════════════════════════════════════════════════════

    /**
     * Refreshes the GATT manifest with current active message IDs from the database.
     * Also updates the BLE advertised manifest size.
     */
    private void refreshGattManifest() {
        dbExecutor.execute(() -> {
            try {
                final MessageDao dao = AppDatabase.getInstance(this).messageDao();
                final List<MessageEntity> active =
                        dao.getActiveMessagesDirect(System.currentTimeMillis());
                final ArrayList<String> ids = new ArrayList<>(active.size());
                for (MessageEntity m : active) {
                    if (m.getHopCount() >= PHASE2_MAX_HOPS) {
                        continue;
                    }
                    if (BlockedDeviceStore.isBlocked(this, m.getOrigin())) {
                        continue;
                    }
                    ids.add(m.getId());
                }
                gattServer.updateManifest(ids);
                advertiser.updateManifestSize(ids.size());
                Timber.tag(TAG).d("ED:GATT_MANIFEST_REFRESH count=%d", ids.size());
            } catch (Exception e) {
                Timber.tag(TAG).e(e, "ED:GATT_MANIFEST_REFRESH_ERR");
            }
        });
    }

    // ══════════════════════════════════════════════════════════
    //  ACCESSOR / GETTER METHODS
    // ══════════════════════════════════════════════════════════

    /** Returns the BLE scanner (for peer info). */
    @Nullable
    public BleScanner getScanner() { return scanner; }

    /** Returns the BLE advertiser. */
    @Nullable
    public BleAdvertiser getAdvertiser() { return advertiser; }

    /** Returns the Wi-Fi Direct manager. */
    @Nullable
    public WifiDirectManager getWifiDirectManager() { return wifiDirectManager; }

    /** Returns whether Bluetooth is currently ON. */
    public boolean isBluetoothEnabled() { return bluetoothEnabled; }

    /** Returns whether Wi-Fi P2P is currently enabled. */
    public boolean isP2pEnabled() { return p2pEnabled; }

    // ══════════════════════════════════════════════════════════
    //  UI NOTIFICATION HELPERS
    // ══════════════════════════════════════════════════════════

    private void notifyTransferState(boolean inProgress) {
        if (transferStateListener != null) {
            mainHandler.post(() -> {
                if (transferStateListener != null) {
                    transferStateListener.onTransferStateChanged(inProgress);
                }
            });
        }
    }

    private void notifyPeerCount(int count) {
        currentPeerCount = count;
        updateNotification();
        if (peerCountListener != null) {
            mainHandler.post(() -> {
                if (peerCountListener != null) {
                    peerCountListener.onPeerCountChanged(count);
                }
            });
        }
    }

    private void notifyPrerequisite() {
        if (prerequisiteListener != null) {
            final boolean bt = bluetoothEnabled;
            final boolean p2p = p2pEnabled;
            mainHandler.post(() -> {
                if (prerequisiteListener != null) {
                    prerequisiteListener.onPrerequisiteChanged(bt, p2p);
                }
            });
        }
    }

    // ══════════════════════════════════════════════════════════
    //  NOTIFICATION
    // ══════════════════════════════════════════════════════════

    private Notification buildNotification() {
        return buildNotification(currentPeerCount);
    }

    private Notification buildNotification(int peerCount) {
        final Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Intent quitIntent = new Intent(this, EchoService.class);
        quitIntent.setAction(ACTION_QUIT_SERVICE);
        final PendingIntent quitPending = PendingIntent.getService(
                this, 1, quitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final String contentText = peerCount == 1
                ? getString(R.string.service_notification_peers_one)
                : getString(R.string.service_notification_peers, peerCount);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .addAction(0, getString(R.string.service_notification_quit), quitPending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

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

    // ══════════════════════════════════════════════════════════
    //  BLUETOOTH STATE
    // ══════════════════════════════════════════════════════════

    private boolean isBluetoothOn() {
        final BluetoothManager btm =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btm == null) return false;
        final BluetoothAdapter adapter = btm.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private void startBleStack() {
        if (!hasBlePermissions(this)) {
            Timber.tag(TAG).w("ED:BLE_STACK_SKIP perms=denied");
            return;
        }
        if (!isBluetoothOn()) {
            Timber.tag(TAG).w("ED:BLE_STACK_SKIP bt_off=true");
            notifyPrerequisite();
            return;
        }
        if (advertiser != null && !advertiser.isRunning()) {
            advertiser.start();
        }
        if (scanner != null && !scanner.isRunning()) {
            scanner.start();
        }
        if (gattServer != null) {
            gattServer.startServer();
        }
    }

    private void stopBleStack() {
        if (advertiser != null) advertiser.stop();
        if (scanner != null) scanner.stop();
        if (gattServer != null) gattServer.stopServer();
    }

    private void registerBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) return;
        bluetoothStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    bluetoothEnabled = true;
                    Timber.tag(TAG).i("ED:BT_STATE_ON -> resume_ble_stack");
                    startBleStack();
                    refreshGattManifest();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    bluetoothEnabled = false;
                    Timber.tag(TAG).i("ED:BT_STATE_OFF -> pause_ble_stack");
                    stopBleStack();
                }
                notifyPrerequisite();
            }
        };
        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    private void unregisterBluetoothStateReceiver() {
        if (bluetoothStateReceiver == null) return;
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (Exception ignored) {
            // Receiver may already be unregistered on some OEM lifecycle paths.
        }
        bluetoothStateReceiver = null;
    }

    // ══════════════════════════════════════════════════════════
    //  STATIC HELPERS
    // ══════════════════════════════════════════════════════════

    public static boolean isBackgroundEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_BG_ENABLED, true);
    }

    public static void setBackgroundEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BG_ENABLED, enabled)
                .apply();
    }

    public static void syncServiceState(Context context) {
        if (isBackgroundEnabled(context)) {
            startService(context);
        } else {
            stopService(context);
        }
    }

    public static boolean hasBlePermissions(Context context) {
        final boolean locationGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final boolean granted = ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            return granted && locationGranted;
        }
        return locationGranted;
    }

    public static String[] getBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

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

    public static void stopService(Context context) {
        context.stopService(new Intent(context, EchoService.class));
    }
}
