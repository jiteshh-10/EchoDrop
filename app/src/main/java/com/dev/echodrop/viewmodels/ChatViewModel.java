package com.dev.echodrop.viewmodels;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.dev.echodrop.db.ChatEntity;
import com.dev.echodrop.db.ChatMessageEntity;
import com.dev.echodrop.repository.ChatRepo;

import java.util.List;

/**
 * ViewModel for private chat operations.
 *
 * <p>Wraps {@link ChatRepo} and exposes reactive data for the chat list
 * and individual conversation views.</p>
 */
public class ChatViewModel extends AndroidViewModel {

    private final ChatRepo repo;

    /** Reactive list of all chats (ordered by last activity). */
    private final LiveData<List<ChatEntity>> chats;

    /** Currently active conversation chat ID — set by the conversation fragment. */
    private final MutableLiveData<String> activeChatId = new MutableLiveData<>();

    public ChatViewModel(@NonNull Application application) {
        super(application);
        this.repo = new ChatRepo(application);
        this.chats = repo.getChats();
    }

    // ──────────────────── Chat list ────────────────────

    @NonNull
    public LiveData<List<ChatEntity>> getChats() {
        return chats;
    }

    public void createChat(@NonNull String code, @Nullable String name) {
        repo.createChat(code, name);
    }

    public void joinChat(@NonNull String code, @Nullable ChatRepo.JoinCallback callback) {
        repo.joinChat(code, callback);
    }

    public void deleteChat(@NonNull String chatId) {
        repo.deleteChat(chatId);
    }

    // ──────────────────── Conversation ────────────────────

    /**
     * Returns messages LiveData for the given chat.
     * Called by the conversation fragment.
     */
    @NonNull
    public LiveData<List<ChatMessageEntity>> getMessages(@NonNull String chatId) {
        return repo.getMessages(chatId);
    }

    public void sendMessage(@NonNull String chatId,
                            @NonNull String plaintext,
                            @NonNull String chatCode) {
        repo.sendMessage(chatId, plaintext, chatCode);
    }

    public void clearUnread(@NonNull String chatId) {
        repo.clearUnread(chatId);
    }

    /** Exposes the active chat ID for shared state between fragments. */
    @NonNull
    public MutableLiveData<String> getActiveChatId() {
        return activeChatId;
    }

    @NonNull
    public ChatRepo getRepo() {
        return repo;
    }
}
