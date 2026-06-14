package com.example.cfdremoteassist

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.Manifest
import android.os.Build
import com.example.cfdremoteassist.receivers.RemoteAssistDeviceAdminReceiver
import com.example.cfdremoteassist.services.LocationTrackingService
import com.example.cfdremoteassist.ui.theme.EUDRemoteAssistTheme
import com.example.cfdremoteassist.utils.ManagedConfigManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val configManager = ManagedConfigManager(this)
        
        // If we are Device Owner, auto-grant all standard runtime permissions (if enabled in MDM)
        if (configManager.isAutoGrantEnabled()) {
            autoGrantRuntimePermissions()
        }

        // Start background processes while splash screen is visible
        if (configManager.isRegistered()) {
            val intent = Intent(this, LocationTrackingService::class.java)
            startForegroundService(intent)
        }
        
        setContent {
            EUDRemoteAssistTheme {
                SplashScreenContent()
            }
        }

        // Stay for 5 seconds then go to MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 5000)
    }

    private fun autoGrantRuntimePermissions() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this, RemoteAssistDeviceAdminReceiver::class.java)

        // Check if we are the Device Owner or Profile Owner
        if (!dpm.isDeviceOwnerApp(packageName) && !dpm.isProfileOwnerApp(packageName)) {
            return
        }

        val runtimePermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.BODY_SENSORS
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        try {
            for (permission in runtimePermissions) {
                dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "Failed to auto-grant permissions: ${e.message}")
        }
    }
}

@Composable
fun SplashScreenContent() {
    Image(
        painter = painterResource(id = R.drawable.eudassistload),
        contentDescription = "Splash Screen",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}
