package com.cfd2474.eudremoteassist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.service.DeviceGatewayService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val config = ManagedConfigManager(context)
            if (config.isBootStartEnabled()) {
                Log.i(TAG, "Boot/wake event ($action) and boot start is enabled. Starting DeviceGatewayService.")
                val gatewayIntent = Intent(context, DeviceGatewayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(gatewayIntent)
                } else {
                    context.startService(gatewayIntent)
                }
            } else {
                Log.i(TAG, "Boot start is disabled via configuration.")
            }
        }
    }
}
