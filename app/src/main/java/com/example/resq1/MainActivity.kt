package com.example.resq1

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.resq1.ui.theme.Resq1Theme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupBleMesh()
        } else {
            Toast.makeText(this, "Bluetooth & Location permissions required.", Toast.LENGTH_LONG).show()
        }
    }

    private var lastVolumeDownTime: Long = 0
    private val doublePressTimeout = 1000L // Increased to 1s for better reliability

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on while app is in foreground to ensure button capture
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkPermissions()

        setContent {
            Resq1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // This will be our new UI entry point
                    MainScreen(viewModel)
                }
            }
        }
    }

    private fun checkPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            setupBleMesh()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupBleMesh() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivity(enableBtIntent)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Permission denied to enable Bluetooth", Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.initBle(bluetoothAdapter!!)
        }
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothAdapter?.isEnabled == true) {
            viewModel.startBleMesh()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopBleMesh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val currentTime = System.currentTimeMillis()
            
            // Provide haptic feedback for every press to guide the user
            provideHapticFeedback(50L)

            if (currentTime - lastVolumeDownTime < doublePressTimeout) {
                // Double press detected
                triggerSos()
                lastVolumeDownTime = 0 // Reset to prevent triple-press triggering twice
                return true
            }
            lastVolumeDownTime = currentTime
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun triggerSos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            provideHapticFeedback(200L) // Longer vibration for SOS start
            viewModel.triggerPanicMode()
            Toast.makeText(this, "SOS VOICE ACTIVATED", Toast.LENGTH_SHORT).show()
        } else {
            checkPermissions()
        }
    }

    private fun provideHapticFeedback(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${ResQAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (enabled == 1) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(service, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
