package com.example.resq1

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A professional background listener that intercepts Volume Down double-clicks
 * even when the screen is off or the app is closed.
 */
class ResQAccessibilityService : AccessibilityService() {

    private var lastVolumeDownTime: Long = 0
    private val doublePressTimeout = 1000L

    companion object {
        val panicTriggerFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ResQEmergency", "Accessibility Service Connected")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            provideHapticFeedback(50L)

            if (currentTime - lastVolumeDownTime < doublePressTimeout) {
                // Tactical Double Press Triggered
                Log.d("ResQEmergency", "TACTICAL GESTURE DETECTED IN BACKGROUND")
                provideHapticFeedback(200L)
                
                // Trigger the UI/Logic
                panicTriggerFlow.tryEmit(Unit)

                // Try to bring the app to foreground if needed
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)

                lastVolumeDownTime = 0
                return true // Consume the event
            }
            lastVolumeDownTime = currentTime
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun provideHapticFeedback(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
