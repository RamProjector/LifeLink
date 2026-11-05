package com.lifelink.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["ownerId", "conversationId", "messageId"],
)
data class ChatMessageEntity(
    val ownerId: String,
    val conversationId: String,
    val messageId: String,
    val senderId: String,
    val body: String,
    val createdAt: String,
)

@Entity(
    tableName = "saved_chat_messages",
    primaryKeys = ["ownerId", "conversationId", "messageId"],
)
data class SavedChatMessageEntity(
    val ownerId: String,
    val conversationId: String,
    val messageId: String,
    val senderId: String,
    val body: String,
    val messageCreatedAt: String,
    val savedAt: String,
)

@Dao
interface ChatCacheDao {
    @Query("SELECT * FROM chat_messages WHERE ownerId = :ownerId AND conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun messages(ownerId: String, conversationId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM saved_chat_messages WHERE ownerId = :ownerId AND conversationId = :conversationId ORDER BY savedAt DESC")
    suspend fun savedMessages(ownerId: String, conversationId: String): List<SavedChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedMessage(message: SavedChatMessageEntity)

    @Query("DELETE FROM saved_chat_messages WHERE ownerId = :ownerId AND conversationId = :conversationId")
    suspend fun clearSavedMessages(ownerId: String, conversationId: String)

    @Query("DELETE FROM saved_chat_messages WHERE ownerId = :ownerId AND conversationId = :conversationId AND messageId = :messageId")
    suspend fun deleteSavedMessage(ownerId: String, conversationId: String, messageId: String)

    @Query("DELETE FROM chat_messages WHERE ownerId = :ownerId")
    suspend fun clearMessages(ownerId: String)

    @Query("DELETE FROM saved_chat_messages WHERE ownerId = :ownerId")
    suspend fun clearSavedMessages(ownerId: String)
}
