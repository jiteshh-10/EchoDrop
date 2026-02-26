package com.dev.echodrop.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
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

    private static final String TAG = "BundleReceiver";
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
            Log.w(TAG, "Already running");
            return;
        }

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(TransferProtocol.PORT);
                serverSocket.setReuseAddress(true);
                Log.i(TAG, "Listening on port " + TransferProtocol.PORT);

                while (running.get()) {
                    try {
                        final Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Accept failed: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to start server: " + e.getMessage(), e);
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
                Log.w(TAG, "Error closing server socket", e);
            }
        }
        Log.i(TAG, "Receiver stopped");
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

            for (final MessageEntity entity : messages) {
                // Skip expired messages
                if (entity.getExpiresAt() <= now) {
                    Log.d(TAG, "Skipping expired message: " + entity.getId());
                    continue;
                }

                // Validate checksum (only for non-chat bundles; chat bundles
                // use ciphertext which changes checksum semantics)
                if (!entity.isChatBundle() && !TransferProtocol.validateChecksum(entity)) {
                    Log.w(TAG, "Checksum mismatch for message: " + entity.getId());
                    continue;
                }

                // Dedup check
                if (repo.isDuplicateSync(entity.getContentHash())) {
                    Log.d(TAG, "Duplicate message skipped: " + entity.getId());
                    continue;
                }

                // Stamp this device into the seen-by list
                entity.addSeenBy(localDeviceId);

                // Insert into messages table (for DTN forwarding)
                final MessageDao dao = AppDatabase.getInstance(context).messageDao();
                final long rowId = dao.insert(entity);
                if (rowId != -1) {
                    insertedCount++;
                    Log.i(TAG, "Inserted message: " + entity.getId()
                            + " (hop=" + entity.getHopCount()
                            + ", type=" + entity.getType() + ")");
                }

                // Process chat bundles: decrypt and display if member (Iteration 8)
                if (entity.isChatBundle() && chatRepo != null) {
                    final boolean processed = chatRepo.processIncomingChatBundle(entity);
                    if (processed) {
                        chatCount++;
                        Log.i(TAG, "Chat bundle processed for code: " + entity.getScopeId());
                    }
                }
            }

            if (insertedCount > 0) {
                showNotification(messages, insertedCount);
            }

            final int finalCount = insertedCount;
            callback.onReceiveComplete(finalCount);
            Log.i(TAG, "Transfer session complete: " + insertedCount + " new messages"
                    + (chatCount > 0 ? " (" + chatCount + " chat)" : ""));

        } catch (IOException e) {
            Log.e(TAG, "Transfer failed: " + e.getMessage(), e);
            callback.onReceiveFailed(e.getMessage() != null ? e.getMessage() : "Unknown error");
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing client socket", e);
            }
            callback.onTransferEnded();
        }
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
