package com.dev.echodrop.repository;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.dev.echodrop.crypto.ChatCrypto;
import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.ChatDao;
import com.dev.echodrop.db.ChatEntity;
import com.dev.echodrop.db.ChatMessageEntity;
import com.dev.echodrop.db.MessageDao;
import com.dev.echodrop.db.MessageEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * Repository for private‐chat operations.
 *
 * <p>Handles chat CRUD, message encryption/decryption, and preview updates.
 * All write operations run on a background executor.</p>
 */
public class ChatRepo {

    /** Default TTL for chat bundles: 24 hours. */
    private static final long CHAT_BUNDLE_TTL_MS = 24 * 60 * 60 * 1000L;

    private final ChatDao chatDao;
    private final MessageDao messageDao;
    private final ExecutorService executor;

    /** Listener for sync events (used by UI to show "Last synced X ago"). */
    private volatile SyncEventListener syncEventListener;

    /** Interface for sync event notifications. */
    public interface SyncEventListener {
        /** Called when an incoming chat sync event completes. */
        void onChatSynced(String chatId, long timestamp);
    }

    public ChatRepo(@NonNull Application application) {
        this.chatDao = AppDatabase.getInstance(application).chatDao();
        this.messageDao = AppDatabase.getInstance(application).messageDao();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** Constructor for testing with a custom DAO. */
    public ChatRepo(@NonNull ChatDao chatDao) {
        this.chatDao = chatDao;
        this.messageDao = null;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** Constructor for testing with custom DAOs. */
    public ChatRepo(@NonNull ChatDao chatDao, @NonNull MessageDao messageDao) {
        this.chatDao = chatDao;
        this.messageDao = messageDao;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** Sets the sync event listener. */
    public void setSyncEventListener(@Nullable SyncEventListener listener) {
        this.syncEventListener = listener;
    }

    // ──────────────────── Chat ops ────────────────────

    /**
     * Creates a new chat with a generated or supplied code.
     *
     * @param code the 8-char chat code (raw, no dash).
     * @param name optional human-friendly name.
     */
    public void createChat(@NonNull String code, @Nullable String name) {
        executor.execute(() -> {
            final ChatEntity existing = chatDao.getChatByCode(code);
            if (existing != null) return; // code collision — no-op
            chatDao.insertChat(ChatEntity.create(code, name));
        });
    }

    /**
     * Joins an existing chat by code. Creates a local {@link ChatEntity}
     * entry if none exists for the given code.
     *
     * @param code the 8-char code shared by the chat creator.
     * @return {@code true} if the chat already existed locally.
     */
    public void joinChat(@NonNull String code, @Nullable JoinCallback callback) {
        executor.execute(() -> {
            ChatEntity existing = chatDao.getChatByCode(code);
            if (existing != null) {
                if (callback != null) callback.onResult(existing, true);
                return;
            }
            final ChatEntity chat = ChatEntity.create(code, null);
            chatDao.insertChat(chat);
            if (callback != null) callback.onResult(chat, false);
        });
    }

    /** Callback for join result. */
    public interface JoinCallback {
        void onResult(@NonNull ChatEntity chat, boolean alreadyExisted);
    }

    /** Deletes a chat and all its messages (cascading FK). */
    public void deleteChat(@NonNull String chatId) {
        executor.execute(() -> chatDao.deleteChat(chatId));
    }

    // ──────────────────── Queries ────────────────────

    /** Reactive list of all chats, ordered by most recent activity. */
    @NonNull
    public LiveData<List<ChatEntity>> getChats() {
        return chatDao.getAllChats();
    }

    /** Reactive list of messages for a chat, oldest first. */
    @NonNull
    public LiveData<List<ChatMessageEntity>> getMessages(@NonNull String chatId) {
        return chatDao.getMessagesForChat(chatId);
    }

    /** Synchronous lookup by code. Run on background thread only. */
    @Nullable
    public ChatEntity getChatByCode(@NonNull String code) {
        return chatDao.getChatByCode(code);
    }

    /** Synchronous lookup by ID. */
    @Nullable
    public ChatEntity getChatById(@NonNull String chatId) {
        return chatDao.getChatById(chatId);
    }

    // ──────────────────── Send message ────────────────────

    /**
     * Encrypts and stores a message in the given chat, and creates a DTN
     * bundle (MessageEntity with type=CHAT) for proximity-based sync.
     *
     * @param chatId    the chat to add the message to.
     * @param plaintext the human-readable message text.
     * @param chatCode  the 8-char code used to derive the encryption key.
     */
    public void sendMessage(@NonNull String chatId,
                            @NonNull String plaintext,
                            @NonNull String chatCode) {
        executor.execute(() -> {
            final SecretKey key = ChatCrypto.deriveKey(chatCode);
            final String cipherText = ChatCrypto.encrypt(plaintext, key);

            final ChatMessageEntity message =
                    ChatMessageEntity.createOutgoing(chatId, cipherText);
            chatDao.insertMessage(message);

            // Update chat list preview with plaintext snippet (max 50 chars)
            final String preview = plaintext.length() > 50
                    ? plaintext.substring(0, 50) + "…"
                    : plaintext;
            chatDao.updateLastMessage(chatId, preview, message.getCreatedAt());

            // Create a DTN bundle for mesh propagation (Iteration 8)
            if (messageDao != null) {
                final long now = message.getCreatedAt();
                final MessageEntity bundle = MessageEntity.createChatBundle(
                        cipherText, chatCode, now, now + CHAT_BUNDLE_TTL_MS);
                messageDao.insert(bundle);
            }
        });
    }

    // ──────────────────── DTN Chat Sync (Iteration 8) ────────────────────

    /**
     * Processes an incoming chat bundle received via DTN.
     *
     * <p>If the user is a member of the chat (has a ChatEntity with matching code):
     * <ul>
     *   <li>Decrypts the ciphertext using the chat code key</li>
     *   <li>Inserts as an incoming ChatMessageEntity</li>
     *   <li>Updates the chat preview and unread count</li>
     *   <li>Marks outgoing messages as SYNCED (peer confirmed)</li>
     * </ul>
     * If not a member, the bundle stays in the messages table for DTN forwarding.</p>
     *
     * @param entity the received MessageEntity with type=CHAT
     * @return true if the bundle was processed (user is a member), false otherwise
     */
    public boolean processIncomingChatBundle(@NonNull MessageEntity entity) {
        if (!entity.isChatBundle()) return false;

        final String chatCode = entity.getScopeId();
        if (chatCode == null || chatCode.isEmpty()) return false;

        final ChatEntity chat = chatDao.getChatByCode(chatCode);
        if (chat == null) {
            // Not a member — bundle stays in messages table for DTN forwarding
            return false;
        }

        // Check for duplicate chat message (based on bundle ID)
        if (chatDao.chatMessageExists(entity.getId()) > 0) {
            return true; // Already processed
        }

        // Decrypt and insert as incoming chat message
        try {
            final SecretKey key = ChatCrypto.deriveKey(chatCode);
            final String plaintext = ChatCrypto.decrypt(entity.getText(), key);

            final ChatMessageEntity chatMsg = new ChatMessageEntity(
                    entity.getId(),
                    chat.getId(),
                    entity.getText(), // Store encrypted text (consistent with existing pattern)
                    false, // incoming
                    entity.getCreatedAt(),
                    ChatMessageEntity.SYNC_SYNCED
            );
            chatDao.insertMessage(chatMsg);

            // Update preview with decrypted snippet
            final String preview = plaintext.length() > 50
                    ? plaintext.substring(0, 50) + "…"
                    : plaintext;
            chatDao.updateLastMessage(chat.getId(), preview, entity.getCreatedAt());
            chatDao.incrementUnread(chat.getId());

            // Mark our outgoing messages as SYNCED (peer received them)
            chatDao.markOutgoingSynced(chat.getId());

            // Notify sync listener
            final SyncEventListener listener = syncEventListener;
            if (listener != null) {
                listener.onChatSynced(chat.getId(), System.currentTimeMillis());
            }

            return true;
        } catch (Exception e) {
            // Decryption failed — treat as non-member to be safe
            return false;
        }
    }

    // ──────────────────── Unread ────────────────────

    public void clearUnread(@NonNull String chatId) {
        executor.execute(() -> chatDao.clearUnread(chatId));
    }
}
