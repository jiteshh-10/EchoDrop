package com.dev.echodrop.viewmodels;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.MessageRepo;

import java.util.List;

/**
 * ViewModel that exposes message data from Room via {@link MessageRepo}.
 *
 * <p>Replaced the in-memory seed approach from Iteration 0-1.
 * All data now flows from Room → LiveData → UI.</p>
 *
 * <p>Uses AndroidViewModel to access Application context for database init.</p>
 */
public class MessageViewModel extends AndroidViewModel {

    private final MessageRepo repo;
    private final LiveData<List<MessageEntity>> messages;
    private final LiveData<List<MessageEntity>> savedMessages;
    private final LiveData<Integer> alertCount;

    public MessageViewModel(@NonNull Application application) {
        super(application);
        repo = new MessageRepo(application);
        messages = repo.getActiveMessages();
        savedMessages = repo.getSavedMessages();
        alertCount = repo.getAlertCount();
    }

    /**
     * Constructor for testing with a custom repo.
     */
    MessageViewModel(@NonNull Application application, @NonNull MessageRepo repo) {
        super(application);
        this.repo = repo;
        this.messages = repo.getActiveMessages();
        this.savedMessages = repo.getSavedMessages();
        this.alertCount = repo.getAlertCount();
    }

    /**
     * Returns LiveData of all active (non-expired) messages.
     * Ordered by priority (ALERT first), then newest.
     */
    public LiveData<List<MessageEntity>> getMessages() {
        return messages;
    }

    /**
     * Returns LiveData of saved, non-expired messages.
     */
    public LiveData<List<MessageEntity>> getSavedMessages() {
        return savedMessages;
    }

    /**
     * Returns reactive count of non-expired ALERT messages.
     */
    public LiveData<Integer> getAlertCount() {
        return alertCount;
    }

    /**
     * Returns the underlying repository for direct access.
     */
    public MessageRepo getRepo() {
        return repo;
    }

    /**
     * Insert a message with deduplication and storage cap enforcement.
     *
     * @param entity   The message entity to insert.
     * @param callback Callback for insert result.
     */
    public void addMessage(MessageEntity entity, MessageRepo.InsertCallback callback) {
        repo.insert(entity, callback);
    }

    /**
     * Insert without callback (fire-and-forget).
     */
    public void addMessage(MessageEntity entity) {
        repo.insert(entity);
    }

    /**
     * Delete a message by ID.
     */
    public void deleteMessage(String messageId) {
        repo.deleteById(messageId);
    }

    /**
     * Trigger TTL cleanup.
     */
    public void cleanupExpired() {
        repo.cleanupExpired();
    }
}
