package com.dev.echodrop.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Room DAO for private chat CRUD operations.
 */
@Dao
public interface ChatDao {

    // ──────────────────── Chat CRUD ────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChat(ChatEntity chat);

    @Update
    void updateChat(ChatEntity chat);

    @Query("DELETE FROM chats WHERE id = :chatId")
    void deleteChat(String chatId);

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    ChatEntity getChatById(String chatId);

    @Query("SELECT * FROM chats WHERE code = :code LIMIT 1")
    ChatEntity getChatByCode(String code);

    /** All chats ordered by most recent activity descending. */
    @Query("SELECT * FROM chats ORDER BY " +
            "CASE WHEN last_message_time > 0 THEN last_message_time ELSE created_at END DESC")
    LiveData<List<ChatEntity>> getAllChats();

    // ──────────────────── Message CRUD ────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessage(ChatMessageEntity message);

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    void deleteMessage(String messageId);

    /** Messages for a chat, oldest first. */
    @Query("SELECT * FROM chat_messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    LiveData<List<ChatMessageEntity>> getMessagesForChat(String chatId);

    @Query("SELECT COUNT(*) FROM chat_messages WHERE chat_id = :chatId")
    int getMessageCount(String chatId);

    // ──────────────────── Preview helpers ────────────────────

    @Query("UPDATE chats SET last_message_preview = :preview, " +
            "last_message_time = :time WHERE id = :chatId")
    void updateLastMessage(String chatId, String preview, long time);

    @Query("UPDATE chats SET unread_count = unread_count + 1 WHERE id = :chatId")
    void incrementUnread(String chatId);

    @Query("UPDATE chats SET unread_count = 0 WHERE id = :chatId")
    void clearUnread(String chatId);
}
