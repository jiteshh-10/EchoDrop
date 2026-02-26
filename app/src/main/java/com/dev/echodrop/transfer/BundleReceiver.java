package com.dev.echodrop.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import timber.log.Timber;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.ChatRepo;
import com.dev.echodrop.repository.MessageRepo;
import com.dev.echodrop.util.DeviceIdHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receives message bundles from remote peers over Wi-Fi Direct TCP.
 *
 * <p>Runs a {@link ServerSocket} on {@link TransferProtocol#PORT} that accepts
 * incoming connections, reads framed message sessions, validates checksums,
 * deduplicates against the local database, and inserts new messages.</p>
 *
 * <p>When new messages arrive and the app is in the background, a notification
 * is posted on the "New Messages" channel.</p>
 *
 * <p>Lifecycle is managed by {@link com.dev.echodrop.service.EchoService}.</p>
 */
public class BundleReceiver {

    private static final String TAG = "ED:Receiver";
    private static final String CHANNEL_ID = "new_messages";
    private static final int NOTIFICATION_ID_BASE = 2000;

    /** Callback for receive events. */
    public interface ReceiveCallback {
        /** Called when a transfer session is complete. */
        void onReceiveComplete(int insertedCount);

        /** Called when the receive fails. */
        void onReceiveFailed(@NonNull String error);

        /** Called when a transfer is starting (for UI pulse). */
        void onTransferStarted();

        /** Called when a transfer has ended (for UI pulse). */
        void onTransferEnded();
    }

    private final Context context;
    private final MessageRepo repo;
    private final ChatRepo chatRepo;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    @NonNull
    private ReceiveCallback callback = new NoOpCallback();

    public BundleReceiver(@NonNull final Context context) {
        this.context = context.getApplicationContext();
        this.repo = new MessageRepo(context);
        this.chatRepo = new ChatRepo(((android.app.Application) context.getApplicationContext()));
        this.executor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "BundleReceiver");
            t.setDaemon(true);
            return t;
        });
        createNotificationChannel();
    }

    /**
     * Constructor for testing with a custom repo and executor.
     */
    BundleReceiver(@NonNull final Context context,
                   @NonNull final MessageRepo repo,
                   @NonNull final ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.repo = repo;
        this.chatRepo = null;
        this.executor = executor;
        createNotificationChannel();
    }

    /**
     * Constructor for testing with custom repos and executor.
     */
    BundleReceiver(@NonNull final Context context,
                   @NonNull final MessageRepo repo,
                   @NonNull final ChatRepo chatRepo,
                   @NonNull final ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.repo = repo;
        this.chatRepo = chatRepo;
        this.executor = executor;
        createNotificationChannel();
    }

    /** Returns the ChatRepo for sync event registration. */
    @Nullable
    public ChatRepo getChatRepo() {
        return chatRepo;
    }

    /** Sets the callback for receive events. */
    public void setReceiveCallback(@NonNull final ReceiveCallback callback) {
        this.callback = callback;
    }

    /**
     * Starts listening for incoming bundle transfers on the server socket.
     * Runs on a background thread; returns immediately.
     */
    public void start() {
        if (running.getAndSet(true)) {
            Timber.tag(TAG).w("ED:RECV_SKIP already_running");
            return;
        }

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(TransferProtocol.PORT);
                serverSocket.setReuseAddress(true);
                Timber.tag(TAG).i("ED:RECV_LISTEN port=%d", TransferProtocol.PORT);

                while (running.get()) {
                    try {
                        final Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running.get()) {
                            Timber.tag(TAG).e(e, "ED:RECV_ACCEPT_FAIL");
                        }
                    }
                }
            } catch (IOException e) {
                Timber.tag(TAG).e(e, "ED:RECV_START_FAIL");
                running.set(false);
            }
        });
    }

    /** Stops the server socket and shuts down. */
    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Timber.tag(TAG).w(e, "ED:RECV_SERVER_CLOSE_ERR");
            }
        }
        Timber.tag(TAG).i("ED:RECV_STOP");
    }

    /** Returns whether the receiver is currently running. */
    public boolean isRunning() {
        return running.get();
    }

    // ──────────────────── Client Handling ────────────────────

    private void handleClient(@NonNull final Socket client) {
        callback.onTransferStarted();
        int insertedCount = 0;
        int chatCount = 0;
        final String localDeviceId = DeviceIdHelper.getDeviceId(context);

        try {
            final InputStream in = client.getInputStream();
            final List<MessageEntity> messages = TransferProtocol.readSession(in);
            final long now = System.currentTimeMillis();

            // Collect hashes of received messages for response filtering
            final Set<String> receivedHashes = new HashSet<>();
            for (final MessageEntity m : messages) {
                receivedHashes.add(m.getContentHash());
            }

            // Process each received message
            for (final MessageEntity entity : messages) {
                if (processOneMessage(entity, localDeviceId, now)) {
                    insertedCount++;
                    if (entity.isChatBundle() && chatRepo != null) {
                        final boolean processed = chatRepo.processIncomingChatBundle(entity);
                        if (processed) {
                            chatCount++;
                            Timber.tag(TAG).i("ED:RECV_CHAT code=%s", entity.getScopeId());
                        }
                    }
                }
            }

            // ── Bidirectional response: send our messages back ──
            try {
                final OutputStream out = client.getOutputStream();
                final MessageDao responseDao = AppDatabase.getInstance(context).messageDao();
                final List<MessageEntity> localMessages = responseDao.getActiveMessagesDirect(now);

                final List<MessageEntity> response = new ArrayList<>();
                for (final MessageEntity local : localMessages) {
                    if (local.isAtHopLimit()) continue;
                    if (local.getExpiresAt() <= now) continue;
                    // Don't send back what the peer just sent us
                    if (receivedHashes.contains(local.getContentHash())) continue;

                    // Create forwarding copy
                    final MessageEntity copy = new MessageEntity(
                            local.getId(), local.getText(), local.getScope(), local.getPriority(),
                            local.getCreatedAt(), local.getExpiresAt(), false, local.getContentHash());
                    copy.setHopCount(local.getHopCount() + 1);
                    copy.setSeenByIds(local.getSeenByIds());
                    copy.addSeenBy(localDeviceId);
                    copy.setType(local.getType());
                    copy.setScopeId(local.getScopeId());
                    response.add(copy);
                }

                TransferProtocol.writeSession(out, response);
                out.flush();
                Timber.tag(TAG).i("ED:RECV_RESPONSE sent=%d", response.size());
            } catch (IOException e) {
                Timber.tag(TAG).w(e, "ED:RECV_RESPONSE_FAIL");
            }

            if (insertedCount > 0) {
                showNotification(messages, insertedCount);
            }

            final int finalCount = insertedCount;
            callback.onReceiveComplete(finalCount);
            Timber.tag(TAG).i("ED:RECV_DONE inserted=%d chat=%d", insertedCount, chatCount);

        } catch (IOException e) {
            Timber.tag(TAG).e(e, "ED:RECV_FAIL");
            callback.onReceiveFailed(e.getMessage() != null ? e.getMessage() : "Unknown error");
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                Timber.tag(TAG).w(e, "ED:RECV_CLIENT_CLOSE_ERR");
            }
            callback.onTransferEnded();
        }
    }

    /**
     * Processes a single received message: validates, deduplicates, and inserts.
     *
     * @return true if the message was inserted (new message)
     */
    private boolean processOneMessage(@NonNull final MessageEntity entity,
                                      @NonNull final String localDeviceId,
                                      final long now) {
        // Skip expired messages
        if (entity.getExpiresAt() <= now) {
            Timber.tag(TAG).d("ED:RECV_SKIP_EXPIRED id=%s", entity.getId());
            return false;
        }

        // Validate checksum (only for non-chat bundles)
        if (!entity.isChatBundle() && !TransferProtocol.validateChecksum(entity)) {
            Timber.tag(TAG).w("ED:RECV_CHECKSUM_FAIL id=%s", entity.getId());
            return false;
        }

        // Dedup check
        if (repo.isDuplicateSync(entity.getContentHash())) {
            Timber.tag(TAG).d("ED:RECV_DEDUP id=%s", entity.getId());
            return false;
        }

        // Stamp this device into the seen-by list
        entity.addSeenBy(localDeviceId);

        // Insert into messages table (for DTN forwarding)
        final MessageDao dao = AppDatabase.getInstance(context).messageDao();
        final long rowId = dao.insert(entity);
        if (rowId != -1) {
            Timber.tag(TAG).i("ED:RECV_INSERT id=%s hop=%d type=%s",
                    entity.getId(), entity.getHopCount(), entity.getType());
            return true;
        }
        return false;
    }

    /**
     * Processes a list of received messages (used for bidirectional response
     * messages received by the sender side).
     *
     * @param messages messages received from the peer's response
     * @return number of messages inserted
     */
    public int processReceivedMessages(@NonNull final List<MessageEntity> messages) {
        final String localDeviceId = DeviceIdHelper.getDeviceId(context);
        final long now = System.currentTimeMillis();
        int insertedCount = 0;

        for (final MessageEntity entity : messages) {
            if (processOneMessage(entity, localDeviceId, now)) {
                insertedCount++;
                if (entity.isChatBundle() && chatRepo != null) {
                    chatRepo.processIncomingChatBundle(entity);
                }
            }
        }

        Timber.tag(TAG).i("ED:PROCESS_RESPONSE inserted=%d total=%d", insertedCount, messages.size());
        return insertedCount;
    }

    // ──────────────────── Notifications ────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.transfer_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.transfer_notification_title));
            final NotificationManager manager =
                    context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** Shows a notification for newly arrived messages. */
    private void showNotification(@NonNull final List<MessageEntity> messages,
                                  final int insertedCount) {
        final Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Use the first message text as notification body (max 60 chars)
        String body = "";
        for (final MessageEntity msg : messages) {
            if (!msg.getText().isEmpty()) {
                body = msg.getText();
                if (body.length() > 60) {
                    body = body.substring(0, 60) + "…";
                }
                break;
            }
        }

        final Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.transfer_notification_title))
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build();

        final NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_BASE + (int) (System.currentTimeMillis() % 1000),
                    notification);
        }
    }

    /** No-op implementation for default callback. */
    private static class NoOpCallback implements ReceiveCallback {
        @Override
        public void onReceiveComplete(final int insertedCount) { }

        @Override
        public void onReceiveFailed(@NonNull final String error) { }

        @Override
        public void onTransferStarted() { }

        @Override
        public void onTransferEnded() { }
    }
}
