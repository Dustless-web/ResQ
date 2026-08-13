package com.example.resq1.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["messageHash"], unique = true)]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val lat: Double?,
    val lon: Double?,
    val roomName: String = "GENERAL",
    val messageHash: String,
    val rssi: Int = 0,
    val isSos: Boolean = false
)
