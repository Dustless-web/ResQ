package com.example.resq1.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE roomName = :roomName ORDER BY timestamp ASC")
    fun getMessagesByRoom(roomName: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message)

    @Query("SELECT messageHash FROM messages")
    fun getAllHashes(): Flow<List<String>>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
