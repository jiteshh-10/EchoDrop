package com.dev.echodrop.transfer;

import androidx.annotation.NonNull;

import com.dev.echodrop.db.MessageEntity;

import timber.log.Timber;

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
 * <p>Updated in Iteration 7: adds {@link #sendForForwarding} which filters messages
 * by hop limit, scope rules, and seen-by-ids before forwarding. Each forwarded
 * message gets its hop count incremented and the local device ID appended to
 * seen_by_ids.</p>
 *
 * <p>Runs on a background executor thread; never blocks the main thread.</p>
 */
public class BundleSender {

    private static final String TAG = "ED:Sender";

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
                Timber.tag(TAG).i("ED:SEND_SKIP no_valid_messages");
                callback.onSendComplete(0);
                return;
            }

            sendToSocket(address, valid, callback);
        });
    }

    /**
     * Filters and forwards messages for multi-hop DTN propagation.
     *
     * <p>Filtering rules:
     * <ul>
     *   <li>Expired messages are excluded</li>
     *   <li>Messages at or above {@link MessageEntity#MAX_HOP_COUNT} are excluded</li>
     *   <li>Messages already seen by the target device (in seen_by_ids) are excluded</li>
     *   <li>LOCAL-scope messages are only forwarded to immediate BLE peers (same session)</li>
     * </ul>
     * </p>
     *
     * <p>For each forwarded message, hop_count is incremented and the local
     * device ID is appended to seen_by_ids.</p>
     *
     * @param address        the peer's IP address
     * @param messages       all local messages to consider
     * @param localDeviceId  this device's persistent hex ID
     * @param peerDeviceId   the target peer's device ID (for seen-by filtering)
     * @param isBleSession   true if the peer is in the current BLE session (for LOCAL scope)
     * @param callback       result callback
     */
    public void sendForForwarding(@NonNull final InetAddress address,
                                  @NonNull final List<MessageEntity> messages,
                                  @NonNull final String localDeviceId,
                                  @NonNull final String peerDeviceId,
                                  final boolean isBleSession,
                                  @NonNull final SendCallback callback) {
        executor.execute(() -> {
            final long now = System.currentTimeMillis();
            final List<MessageEntity> forwardable = new ArrayList<>();

            for (final MessageEntity msg : messages) {
                // Skip expired
                if (msg.getExpiresAt() <= now) continue;

                // Skip messages at hop limit
                if (msg.isAtHopLimit()) continue;

                // Skip messages already seen by target peer
                if (msg.hasBeenSeenBy(peerDeviceId)) continue;

                // LOCAL scope: only forward within same BLE session (direct proximity)
                if ("LOCAL".equals(msg.getScope()) && !isBleSession) continue;

                // Prepare forwarded copy with incremented hop and updated seen list
                final MessageEntity copy = new MessageEntity(
                        msg.getId(), msg.getText(), msg.getScope(), msg.getPriority(),
                        msg.getCreatedAt(), msg.getExpiresAt(), false, msg.getContentHash());
                copy.setHopCount(msg.getHopCount() + 1);
                copy.setSeenByIds(msg.getSeenByIds());
                copy.addSeenBy(localDeviceId);
                copy.setType(msg.getType());
                copy.setScopeId(msg.getScopeId());

                forwardable.add(copy);
            }

            if (forwardable.isEmpty()) {
                Timber.tag(TAG).i("ED:FWD_SKIP peer=%s no_forwardable", peerDeviceId);
                callback.onSendComplete(0);
                return;
            }

            Timber.tag(TAG).i("ED:FWD_START peer=%s count=%d addr=%s", peerDeviceId, forwardable.size(), address);
            sendToSocket(address, forwardable, callback);
        });
    }

    /** Shuts down the executor. */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Opens a TCP socket and sends the given messages via TransferProtocol.
     *
     * @param address  target address
     * @param messages messages to send (already filtered)
     * @param callback result callback
     */
    private void sendToSocket(@NonNull final InetAddress address,
                              @NonNull final List<MessageEntity> messages,
                              @NonNull final SendCallback callback) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(address, TransferProtocol.PORT),
                    CONNECT_TIMEOUT_MS);

            final OutputStream out = socket.getOutputStream();
            TransferProtocol.writeSession(out, messages);
            out.flush();

            Timber.tag(TAG).i("ED:SEND_OK count=%d addr=%s", messages.size(), address);
            callback.onSendComplete(messages.size());
        } catch (IOException e) {
            Timber.tag(TAG).e(e, "ED:SEND_FAIL addr=%s", address);
            callback.onSendFailed(e.getMessage() != null ? e.getMessage() : "Unknown error");
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(final Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Timber.tag("ED:Sender").w(e, "ED:SEND_CLOSE_ERR");
            }
        }
    }
}
