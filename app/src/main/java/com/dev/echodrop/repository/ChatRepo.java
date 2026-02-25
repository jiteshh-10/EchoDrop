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

    private final ChatDao chatDao;
    private final ExecutorService executor;

    public ChatRepo(@NonNull Application application) {
        this.chatDao = AppDatabase.getInstance(application).chatDao();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /** Constructor for testing with a custom DAO. */
    public ChatRepo(@NonNull ChatDao chatDao) {
        this.chatDao = chatDao;
        this.executor = Executors.newSingleThreadExecutor();
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
     * Encrypts and stores a message in the given chat.
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
        });
    }

    // ──────────────────── Unread ────────────────────

    public void clearUnread(@NonNull String chatId) {
        executor.execute(() -> chatDao.clearUnread(chatId));
    }
}
