package com.example.cfdremoteassist.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.cfdremoteassist.services.LocationTrackingService
import com.example.cfdremoteassist.utils.ManagedConfigManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.i("BootReceiver", "Device boot detected")
            val configManager = ManagedConfigManager(context)
            
            // If the user has "Enable on Boot" toggled ON and the app is registered
            if (configManager.isRegistered() && configManager.isBootStartEnabled()) {
                Log.i("BootReceiver", "Starting LocationTrackingService after boot")
                val serviceIntent = Intent(context, LocationTrackingService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
