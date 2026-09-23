package com.example.resq1.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.*

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private val serviceUuid = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
    
    val packetFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val discoveredPeers = MutableSharedFlow<BluetoothDevice>(extraBufferCapacity = 10)

    private var gattServer: BluetoothGattServer? = null
    private val connectedGattClients = mutableMapOf<String, BluetoothGatt>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            discoveredPeers.tryEmit(result.device)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val json = String(value)
            Log.d("BleManager", "Received data from ${device.address}")
            packetFlow.tryEmit(json)
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
        
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d("BleManager", "Server connection state change: ${device.address} -> $newState")
        }
    }

    fun start() {
        if (adapter == null) return
        startAdvertising()
        startScanning()
        startGattServer()
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d("BleManager", "Scanning started")
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d("BleManager", "Advertising started")
            }
        })
    }

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
        Log.d("BleManager", "GATT Server started")
    }

    fun sendData(device: BluetoothDevice, data: String) {
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BleManager", "Connected to ${device.address}, discovering services")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BleManager", "Disconnected from ${device.address}")
                    connectedGattClients.remove(device.address)
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(serviceUuid)
                    val characteristic = service?.getCharacteristic(charUuid)
                    if (characteristic != null) {
                        characteristic.value = data.toByteArray()
                        gatt.writeCharacteristic(characteristic)
                        Log.d("BleManager", "Data sent to ${device.address}")
                    }
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.d("BleManager", "Write complete to ${device.address}: $status")
                // We could keep it connected for a while or disconnect immediately
                // For flooding, we might want to stay connected to active peers
            }
        })
        connectedGattClients[device.address] = gatt
    }
    
    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gattServer?.close()
        connectedGattClients.values.forEach { it.close() }
        connectedGattClients.clear()
    }
}
