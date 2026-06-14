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
import com.example.cfdremoteassist.services.LocationTrackingService
import com.example.cfdremoteassist.ui.theme.EUDRemoteAssistTheme
import com.example.cfdremoteassist.utils.ManagedConfigManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val configManager = ManagedConfigManager(this)
        
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
