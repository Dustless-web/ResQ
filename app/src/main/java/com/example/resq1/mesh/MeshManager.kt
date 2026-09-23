package com.example.resq1.mesh

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.example.resq1.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.*

class MeshManager(private val context: Context) {

    private val bleManager = BleManager(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Persistent Identity
    private val prefs = context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)
    val localNodeId: String = prefs.getString("node_id", null) ?: run {
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("node_id", newId).apply()
        newId
    }

    // Peer Registry: nodeId -> BluetoothDevice
    private val activePeers = mutableMapOf<String, BluetoothDevice>()
    private val peerNames = mutableMapOf<String, String>()
    
    // Duplicate Prevention Cache
    private val processedPackets = Collections.synchronizedSet(mutableSetOf<String>())
    
    // Flows for ViewModel
    val incomingMessages = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 10)
    val peerListFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 5)

    init {
        scope.launch {
            bleManager.packetFlow.collect { json ->
                try {
                    val packet = MeshPacket.fromJson(json)
                    handleIncomingPacket(packet)
                } catch (e: Exception) {
                    Log.e("MeshManager", "Failed to parse packet", e)
                }
            }
        }

        scope.launch {
            bleManager.discoveredPeers.collect { device ->
                // When we discover a device, we could send a HELLO packet if not already known
                // For now, we'll just keep track of it
                Log.d("MeshManager", "Discovered device: ${device.address}")
                // In a real mesh, we'd perform a handshake here
            }
        }
    }

    fun start() {
        bleManager.start()
    }

    fun stop() {
        bleManager.stop()
    }

    private fun handleIncomingPacket(packet: MeshPacket) {
        if (processedPackets.contains(packet.packetId)) return
        processedPackets.add(packet.packetId)

        if (packet.destinationId == localNodeId || packet.destinationId == "ALL") {
            // Deliver to UI
            scope.launch { incomingMessages.emit(packet) }
            
            // If it's a HELLO, register the peer
            if (packet.type == PacketType.HELLO) {
                // peerNames[packet.sourceId] = packet.payload
                // activePeers[packet.sourceId] = ... we need the device handle
            }
        }

        // Forward if TTL allows
        if (packet.ttl > 0) {
            packet.ttl--
            forwardPacket(packet)
        }
    }

    fun broadcastText(text: String) {
        val packet = MeshPacket(
            sourceId = localNodeId,
            destinationId = "ALL",
            type = PacketType.TEXT,
            payload = text
        )
        processedPackets.add(packet.packetId)
        forwardPacket(packet)
    }

    private fun forwardPacket(packet: MeshPacket) {
        val json = packet.toJson()
        activePeers.values.forEach { device ->
            bleManager.sendData(device, json)
        }
    }
    
    // Temporary helper to add peer (until automated handshake is complete)
    fun registerPeer(nodeId: String, device: BluetoothDevice) {
        activePeers[nodeId] = device
        scope.launch {
            peerListFlow.emit(activePeers.keys.toList())
        }
    }
}
