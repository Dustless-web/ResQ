package com.example.resq1.mesh

import com.google.gson.Gson
import java.util.UUID

enum class PacketType {
    HELLO,
    HELLO_ACK,
    TEXT,
    PHOTO_START,
    PHOTO_CHUNK,
    PHOTO_COMPLETE,
    DELIVERY_ACK
}

data class MeshPacket(
    val version: Int = 1,
    val packetId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String,
    val type: PacketType,
    var ttl: Int = 5,
    val payload: String, // Base64 if binary, or JSON string
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): MeshPacket = Gson().fromJson(json, MeshPacket::class.java)
    }
}
