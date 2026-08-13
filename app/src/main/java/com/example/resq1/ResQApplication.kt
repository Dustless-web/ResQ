package com.example.resq1

import android.app.Application
import org.maplibre.android.MapLibre

/**
 * Global application class to ensure MapLibre is configured 
 * before any UI components are created.
 */
class ResQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize MapLibre Engine
        MapLibre.getInstance(this)
    }
}
