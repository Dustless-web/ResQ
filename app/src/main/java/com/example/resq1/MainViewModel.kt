package com.example.resq1

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Intent
import android.os.Bundle
import android.os.ParcelUuid
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.resq1.data.AppDatabase
import com.example.resq1.data.Message
import com.example.resq1.network.LocationHelper
import com.example.resq1.network.TriageAI
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.roundToLong

data class MeshNode(
    val address: String,
    val lastSeen: Long,
    val rssi: Int
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val db = AppDatabase.getDatabase(application)
    private val messageDao = db.messageDao()
    private val locationHelper = LocationHelper(application)

    // BLE Components
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var messageJob: kotlinx.coroutines.Job? = null
    private val serviceUuid = ParcelUuid.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val manufacturerId = 0xFFFF // Placeholder for ResQ Mesh
    private val receivedMessageHashes = mutableSetOf<String>()
    
    // Unified Identity
    private val localMeshId: Int =  Random().nextInt()

    // UI State
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var meshStatus by mutableStateOf("Offline")
    var isScanning by mutableStateOf(false)
    var currentRoom by mutableStateOf("GENERAL")
    var myLocation by mutableStateOf<Pair<Double, Double>?>(null)
    var isRecording by mutableStateOf(false)
    var panicStatus by mutableStateOf<String?>(null)
    var activeNodes = mutableStateMapOf<String, MeshNode>()
    var userName by mutableStateOf("RESCUER-" + String.format("%04d", Math.abs(localMeshId % 10000)))

    private val speechRecognizer: SpeechRecognizer by lazy {
        SpeechRecognizer.createSpeechRecognizer(application)
    }

    val recentPeers = derivedStateOf {
        messages.asSequence()
            .filter { it.sender != "Me" && it.lat != null && it.lon != null }
            .groupBy { it.sender }
            .map { it.value.last() }
            .toList()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.getManufacturerSpecificData(manufacturerId) ?: return
            
            // 1. Extract Mesh ID and Type
            if (data.size < 5) return
            val type = data[0].toInt()
            val meshId = ((data[1].toInt() and 0xFF) shl 24) or
                         ((data[2].toInt() and 0xFF) shl 16) or
                         ((data[3].toInt() and 0xFF) shl 8) or
                         (data[4].toInt() and 0xFF)

            // 2. Filter Self
            if (meshId == localMeshId) return

            // 3. Track active node by Mesh ID
            val node = MeshNode(
                address = "NODE-${Integer.toHexString(meshId).uppercase()}",
                lastSeen = System.currentTimeMillis(),
                rssi = result.rssi
            )
            activeNodes[meshId.toString()] = node

            // 4. Handle Data if it's a Chat/SOS packet (Length check to distinguish from heartbeat)
            if (data.size > 5 && (type == 0x01 || type == 0x02)) {
                Log.d("ResQMesh", "Heard a Data Chirp from $meshId")
                handleReceivedData(data, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ResQMesh", "Scanner failed: $errorCode")
            meshStatus = "Scan Error ($errorCode)"
        }
    }

    init {
        loadMessages()
        // Pre-fill the hash bouncer with existing database messages to prevent duplicates on restart
        viewModelScope.launch {
            messageDao.getAllHashes().collect { hashes ->
                receivedMessageHashes.addAll(hashes)
            }
        }
        
        // Listen for background panic triggers from Accessibility Service
        viewModelScope.launch {
            ResQAccessibilityService.panicTriggerFlow.collectLatest {
                triggerPanicMode()
            }
        }
        
        // Start background pruning of inactive nodes
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val inactive = activeNodes.filter { now - it.value.lastSeen > 30000 }.keys
                inactive.forEach { activeNodes.remove(it) }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    fun switchRoom(roomName: String) {
        if (currentRoom == roomName) return
        currentRoom = roomName
        loadMessages()
    }

    private fun loadMessages() {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            messageDao.getMessagesByRoom(currentRoom).collect {
                messages = it
            }
        }
    }

    fun initBle(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        updateMyLocation()
        meshStatus = "Ready"
    }

    fun updateMyLocation() {
        val location = locationHelper.getLastKnownLocation()
        myLocation = location?.let { it.latitude to it.longitude }
    }

    fun startBleMesh() {
        if (isScanning) return
        isScanning = true
        meshStatus = "Mesh Active"
        
        // 1. Start Continuous Scanner
        val filter = ScanFilter.Builder().build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            Log.d("ResQMesh", "Starting BLE Mesh Scanner...")
            scanner?.startScan(listOf(filter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            meshStatus = "Permission Error"
            Log.e("ResQMesh", "SecurityException starting scan", e)
        }

        // 2. Start Continuous Heartbeat
        startHeartbeat()
    }

    private fun startHeartbeat() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        
        // Heartbeat Payload: [Type(1b) | MeshID(4b)] = 5 bytes
        val payload = ByteArray(5)
        payload[0] = 0x00 
        payload[1] = (localMeshId shr 24).toByte()
        payload[2] = (localMeshId shr 16).toByte()
        payload[3] = (localMeshId shr 8).toByte()
        payload[4] = localMeshId.toByte()

        val data = AdvertiseData.Builder()
            .addManufacturerData(manufacturerId, payload)
            .setIncludeTxPowerLevel(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d("ResQMesh", "Heartbeat ACTIVE")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e("ResQMesh", "Heartbeat FAILED: $errorCode")
                }
            })
        } catch (e: SecurityException) { }
    }

    fun stopBleMesh() {
        try {
            scanner?.stopScan(scanCallback)
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        } catch (e: SecurityException) { }
        isScanning = false
        meshStatus = "Paused"
    }

    fun sendMessage(content: String, isSos: Boolean = false) {
        val location = locationHelper.getLastKnownLocation()
        myLocation = location?.let { it.latitude to it.longitude }
        val timestamp = System.currentTimeMillis()
        val hash = generateHash(content, location?.latitude, location?.longitude)
        
        // Save locally
        val localMsg = Message(
            sender = userName,
            content = content,
            timestamp = timestamp,
            lat = location?.latitude,
            lon = location?.longitude,
            roomName = currentRoom,
            messageHash = hash,
            isSos = isSos
        )
        receivedMessageHashes.add(hash)
        viewModelScope.launch { messageDao.insert(localMsg) }

        // Broadcast via BLE
        broadcastMessage(localMsg)
    }

    private fun generateHash(content: String, lat: Double?, lon: Double?): String {
        // Round to 4 decimal places (~11m precision) to stabilize hash against GPS jitter
        val roundedLat = if (lat != null) (lat * 10000.0).roundToLong() / 10000.0 else 0.0
        val roundedLon = if (lon != null) (lon * 10000.0).roundToLong() / 10000.0 else 0.0
        return "$content|$roundedLat|$roundedLon"
    }

    fun triggerPanicMode() {
        if (isRecording) return
        isRecording = true
        panicStatus = "LISTENING..."
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isRecording = false
            }

            override fun onError(error: Int) {
                isRecording = false
                panicStatus = "VOICE ERROR ($error)"
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null) {
                    processEmergencyText(text)
                } else {
                    panicStatus = "NO INPUT HEARD"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    private fun processEmergencyText(text: String) {
        val severity = TriageAI.analyzeSeverity(text)
        val label = TriageAI.getSeverityLabel(severity)
        panicStatus = "SENT: $label"
        
        // High priority broadcast
        val location = locationHelper.getLastKnownLocation()
        val timestamp = System.currentTimeMillis()
        val content = "[SOS] $text"
        val hash = generateHash(content, location?.latitude, location?.longitude)

        val sosMsg = Message(
            sender = "SOS-ALERT",
            content = content,
            timestamp = timestamp,
            lat = location?.latitude,
            lon = location?.longitude,
            roomName = "EMERGENCY",
            messageHash = hash
        )
        
        viewModelScope.launch { messageDao.insert(sosMsg) }
        broadcastMessage(sosMsg)
    }

    private fun broadcastMessage(msg: Message) {
        val payload = encodeMessage(msg)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val data = AdvertiseData.Builder()
            .addManufacturerData(manufacturerId, payload)
            .setIncludeTxPowerLevel(true)
            .build()

        try {
            Log.d("ResQMesh", "Starting BLE Broadcast (Size: ${payload.size})...")
            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d("ResQMesh", "BLE Broadcast SUCCESS")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e("ResQMesh", "BLE Broadcast FAILED: $errorCode")
                    if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        Log.e("ResQMesh", "Packet data exceeds BLE limits!")
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("ResQMesh", "SecurityException starting advertising", e)
        }
    }

    private fun handleReceivedData(data: ByteArray, rssi: Int) {
        try {
            val msg = decodeMessage(data, rssi)
            if (receivedMessageHashes.contains(msg.messageHash)) return

            receivedMessageHashes.add(msg.messageHash)
            viewModelScope.launch {
                messageDao.insert(msg)
            }
        } catch (e: Exception) {
            Log.e("ResQMesh", "Packet Decode Error", e)
        }
    }

    // --- Optimized Binary Encoding (Fits 31-byte limit) ---
    // Structure: [Type(1b) | MeshID(4b) | RoomID(1b) | Lat(4b) | Lon(4b) | Content(7b)] = 21 bytes
    
    private fun encodeMessage(msg: Message): ByteArray {
        val contentBytes = msg.content.take(7).toByteArray()
        val data = ByteArray(14 + contentBytes.size)
        data[0] = if (msg.isSos) 0x02.toByte() else 0x01.toByte()
        
        // Mesh ID
        data[1] = (localMeshId shr 24).toByte()
        data[2] = (localMeshId shr 16).toByte()
        data[3] = (localMeshId shr 8).toByte()
        data[4] = localMeshId.toByte()

        // Room ID
        data[5] = when(msg.roomName) {
            "MEDICAL" -> 1
            "SUPPLY" -> 2
            else -> 0
        }.toByte()

        // Lat/Lon
        val latBits = java.lang.Float.floatToIntBits(msg.lat?.toFloat() ?: 0f)
        val lonBits = java.lang.Float.floatToIntBits(msg.lon?.toFloat() ?: 0f)
        
        for (i in 0..3) {
            data[i+6] = (latBits shr (i * 8)).toByte()
            data[i+10] = (lonBits shr (i * 8)).toByte()
        }
        
        contentBytes.copyInto(data, 14)
        return data
    }

    private fun decodeMessage(data: ByteArray, rssi: Int): Message {
        val type = data[0].toInt()
        val meshId = ((data[1].toInt() and 0xFF) shl 24) or
                     ((data[2].toInt() and 0xFF) shl 16) or
                     ((data[3].toInt() and 0xFF) shl 8) or
                     (data[4].toInt() and 0xFF)
        
        val roomId = data[5].toInt()
        val roomName = when(roomId) {
            1 -> "MEDICAL"
            2 -> "SUPPLY"
            else -> "GENERAL"
        }

        var latBits = 0
        var lonBits = 0
        for (i in 0..3) {
            latBits = latBits or ((data[i+6].toInt() and 0xFF) shl (i * 8))
            lonBits = lonBits or ((data[i+10].toInt() and 0xFF) shl (i * 8))
        }
        
        val lat = java.lang.Float.intBitsToFloat(latBits).toDouble()
        val lon = java.lang.Float.intBitsToFloat(lonBits).toDouble()
        
        val content = if (data.size > 14) String(data.sliceArray(14 until data.size)) else ""
        val hash = generateHash(content, lat, lon)
        
        return Message(
            sender = "NODE-${Integer.toHexString(meshId).uppercase()}",
            content = content,
            timestamp = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            roomName = roomName,
            messageHash = hash,
            rssi = rssi,
            isSos = (type == 0x02)
        )
    }
}
