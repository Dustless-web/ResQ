package com.example.resq1

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*

import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.ParcelUuid
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.resq1.data.AppDatabase
import com.example.resq1.data.Message
import com.example.resq1.data.MediaType
import com.example.resq1.network.LocationHelper
import com.example.resq1.network.TriageAI
import kotlinx.coroutines.Job
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
    private var messageJob: Job? = null
    private val serviceUuid = ParcelUuid.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val manufacturerId = 0x1234 
    private val receivedMessageHashes = mutableSetOf<String>()
    
    // Unified Identity
    private val localMeshId: Int = Random().nextInt()

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

    // Wi-Fi Bridge State
    private val wifiP2pManager: WifiP2pManager? by lazy { application.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var wifiChannel: WifiP2pManager.Channel? = null
    var isTransferring by mutableStateOf(false)
    var transferProgress by mutableStateOf("")

    private val speechRecognizer: SpeechRecognizer by lazy {
        SpeechRecognizer.createSpeechRecognizer(application)
    }

    val recentPeers = derivedStateOf {
        messages.asSequence()
            .filter { it.sender != userName && it.lat != null && it.lon != null }
            .groupBy { it.sender }
            .map { it.value.last() }
            .toList()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.getManufacturerSpecificData(manufacturerId) ?: return
            
            if (data.size < 5) return
            val type = data[0].toInt()
            val meshId = ((data[1].toInt() and 0xFF) shl 24) or
                         ((data[2].toInt() and 0xFF) shl 16) or
                         ((data[3].toInt() and 0xFF) shl 8) or
                         (data[4].toInt() and 0xFF)

            if (meshId == localMeshId) return

            val node = MeshNode(
                address = "NODE-${Integer.toHexString(meshId).uppercase()}",
                lastSeen = System.currentTimeMillis(),
                rssi = result.rssi
            )
            activeNodes[meshId.toString()] = node

            if (data.size > 5 && (type == 0x01 || type == 0x02)) {
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
        wifiChannel = wifiP2pManager?.initialize(application, application.mainLooper, null)
        
        viewModelScope.launch {
            messageDao.getAllHashes().collect { hashes ->
                receivedMessageHashes.addAll(hashes)
            }
        }
        
        viewModelScope.launch {
            ResQAccessibilityService.panicTriggerFlow.collectLatest {
                triggerPanicMode()
            }
        }
        
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
            messageDao.getMessagesByRoom(currentRoom).collect { list ->
                messages = list
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
        
        val filter = ScanFilter.Builder().build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            scanner?.startScan(listOf(filter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            meshStatus = "Permission Error"
        }

        startHeartbeat()
    }

    private fun startHeartbeat() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        
        val payload = ByteArray(5)
        payload[0] = 0x00 
        payload[1] = (localMeshId shr 24).toByte()
        payload[2] = (localMeshId shr 16).toByte()
        payload[3] = (localMeshId shr 8).toByte()
        payload[4] = localMeshId.toByte()

        val data = AdvertiseData.Builder()
            .addManufacturerData(manufacturerId, payload)
            .setIncludeTxPowerLevel(false)
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

    fun sendMessage(content: String, isSos: Boolean = false, mediaType: MediaType = MediaType.TEXT, fileUri: String? = null) {
        val location = locationHelper.getLastKnownLocation()
        myLocation = location?.let { it.latitude to it.longitude }
        val timestamp = System.currentTimeMillis()
        val hash = generateHash(content, location?.latitude, location?.longitude)
        
        val localMsg = Message(
            sender = userName,
            content = content,
            timestamp = timestamp,
            lat = location?.latitude,
            lon = location?.longitude,
            roomName = currentRoom,
            messageHash = hash,
            isSos = isSos,
            mediaType = mediaType,
            fileUri = fileUri
        )
        receivedMessageHashes.add(hash)
        viewModelScope.launch { messageDao.insert(localMsg) }
        broadcastMessage(localMsg)
    }

    private fun generateHash(content: String, lat: Double?, lon: Double?): String {
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
        
        val location = locationHelper.getLastKnownLocation()
        val timestamp = System.currentTimeMillis()
        val content = "[SOS] $text"
        val hash = generateHash(content, location?.latitude, location?.longitude)

        val sosMsg = Message(
            sender = userName,
            content = content,
            timestamp = timestamp,
            lat = location?.latitude,
            lon = location?.longitude,
            roomName = "EMERGENCY",
            messageHash = hash,
            isSos = true
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
            .setIncludeTxPowerLevel(false)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d("ResQMesh", "BLE Broadcast SUCCESS")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e("ResQMesh", "BLE Broadcast FAILED: $errorCode")
                }
            })
        } catch (e: SecurityException) { }
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

    fun requestHighSpeedLink(messageId: Int) {
        isTransferring = true
        transferProgress = "Searching for peer..."
        
        try {
            wifiP2pManager?.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    transferProgress = "Bridge Ready. Tap 'Accept' on both phones."
                }
                override fun onFailure(reason: Int) {
                    isTransferring = false
                    panicStatus = "Bridge Failed: $reason"
                }
            })
        } catch (e: SecurityException) {
            isTransferring = false
            panicStatus = "Permission Denied"
        }
    }

    // --- Optimized Binary Encoding ---
    // Structure: [Type(1b) | MeshID(4b) | RoomID+Media(1b) | Lat(4b) | Lon(4b) | Content(6b)] = 20 bytes
    
    private fun encodeMessage(msg: Message): ByteArray {
        val contentBytes = msg.content.take(6).toByteArray()
        val data = ByteArray(14 + contentBytes.size)
        data[0] = if (msg.isSos) 0x02.toByte() else 0x01.toByte()
        
        data[1] = (localMeshId shr 24).toByte()
        data[2] = (localMeshId shr 16).toByte()
        data[3] = (localMeshId shr 8).toByte()
        data[4] = localMeshId.toByte()

        val roomId = when(msg.roomName) {
            "MEDICAL" -> 1
            "SUPPLY" -> 2
            else -> 0
        }
        val mediaFlag = when(msg.mediaType) {
            MediaType.IMAGE -> 1
            MediaType.VIDEO -> 2
            else -> 0
        }
        data[5] = ((roomId shl 4) or mediaFlag).toByte()

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
        
        val combinedByte = data[5].toInt()
        val roomId = (combinedByte shr 4) and 0x0F
        val mediaFlag = combinedByte and 0x0F
        
        val roomName = when(roomId) {
            1 -> "MEDICAL"
            2 -> "SUPPLY"
            else -> "GENERAL"
        }
        
        val mediaType = when(mediaFlag) {
            1 -> MediaType.IMAGE
            2 -> MediaType.VIDEO
            else -> MediaType.TEXT
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
            isSos = (type == 0x02),
            mediaType = mediaType
        )
    }
}
