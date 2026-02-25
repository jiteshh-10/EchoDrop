package com.dev.echodrop.transfer;

import android.util.Log;

import androidx.annotation.NonNull;

import com.dev.echodrop.db.MessageEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends message bundles to a remote peer over Wi-Fi Direct TCP.
 *
 * <p>Connects to the group owner's server socket on {@link TransferProtocol#PORT},
 * writes a framed session of serialized {@link MessageEntity} objects, then closes
 * the connection. Messages are sorted by priority before sending (ALERT first).</p>
 *
 * <p>Runs on a background executor thread; never blocks the main thread.</p>
 */
public class BundleSender {

    private static final String TAG = "BundleSender";

    /** Timeout for establishing the TCP connection (ms). */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Callback for send completion. */
    public interface SendCallback {
        /** Called when all messages are sent successfully. */
        void onSendComplete(int count);

        /** Called when the send fails. */
        void onSendFailed(@NonNull String error);
    }

    private final ExecutorService executor;

    public BundleSender() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "BundleSender");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Constructor for testing with a custom executor.
     */
    BundleSender(@NonNull final ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Sends the given messages to a peer at the specified address.
     * Only sends messages that have not yet expired.
     *
     * @param address  the peer's IP address (group owner)
     * @param messages the messages to send
     * @param callback result callback (may be null)
     */
    public void send(@NonNull final InetAddress address,
                     @NonNull final List<MessageEntity> messages,
                     @NonNull final SendCallback callback) {
        executor.execute(() -> {
            // Filter expired messages
            final long now = System.currentTimeMillis();
            final List<MessageEntity> valid = new ArrayList<>();
            for (final MessageEntity msg : messages) {
                if (msg.getExpiresAt() > now) {
                    valid.add(msg);
                }
            }

            if (valid.isEmpty()) {
                Log.i(TAG, "No non-expired messages to send");
                callback.onSendComplete(0);
                return;
            }

            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(address, TransferProtocol.PORT),
                        CONNECT_TIMEOUT_MS);

                final OutputStream out = socket.getOutputStream();
                TransferProtocol.writeSession(out, valid);
                out.flush();

                Log.i(TAG, "Sent " + valid.size() + " messages to " + address);
                callback.onSendComplete(valid.size());
            } catch (IOException e) {
                Log.e(TAG, "Send failed: " + e.getMessage(), e);
                callback.onSendFailed(e.getMessage() != null ? e.getMessage() : "Unknown error");
            } finally {
                closeQuietly(socket);
            }
        });
    }

    /** Shuts down the executor. */
    public void shutdown() {
        executor.shutdownNow();
    }

    private static void closeQuietly(final Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket", e);
            }
        }
    }
}
