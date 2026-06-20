package com.cfd2474.eudremoteassist

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.network.NetworkManager
import com.cfd2474.eudremoteassist.service.DeviceGatewayService
import com.cfd2474.eudremoteassist.service.RemoteAssistAccessibilityService
import com.cfd2474.eudremoteassist.service.ScreenShareService

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_REQUEST_PROJECTION = "com.cfd2474.eudremoteassist.ACTION_REQUEST_PROJECTION"
    }

    private lateinit var config: ManagedConfigManager
    private lateinit var networkManager: NetworkManager
    private val isAdminActiveState = mutableStateOf(false)
    private val isRegisteredState = mutableStateOf(false)
    private val isAccessibilityActiveState = mutableStateOf(false)
    private val isOverlayActiveState = mutableStateOf(false)
    private val isBatteryIgnoringState = mutableStateOf(false)
    private val missingPermissionsState = mutableStateOf<List<String>>(emptyList())
    private val isBootStartEnabledState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }
        Log.i(TAG, "Permissions callback: fine=$fineGranted, coarse=$coarseGranted, notification=$notificationGranted")
        requestBackgroundLocationPermissionIfNeeded()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "MediaProjection permission granted. Starting ScreenShareService.")
            val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        } else {
            Log.w(TAG, "MediaProjection permission denied by user")
            Toast.makeText(this, "Screen capture permission is required for remote assist.", Toast.LENGTH_LONG).show()
        }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val qrText = result.data?.getStringExtra("QR_RESULT")
            if (qrText != null) {
                try {
                    val json = org.json.JSONObject(qrText)
                    val serverUrl = json.optString("tracking_server_url", json.optString("server_url", ""))
                    val token = json.optString("enrollment_token", json.optString("token", ""))
                    val tlsPin = json.optString("tls_pin_hash", json.optString("tls_pin", ""))
                    
                    Log.i(TAG, "QR Parsed: serverUrl='$serverUrl', token='$token', tlsPin='$tlsPin'")
                    Log.i(TAG, "QR Raw Text: $qrText")

                    if (serverUrl.isNotEmpty()) {
                        val formattedUrl = config.formatServerUrl(serverUrl)
                        config.setTrackingServerUrl(formattedUrl)
                        config.setEnrollmentToken(token)
                        if (tlsPin.isNotEmpty()) {
                            config.setTlsPinHash(tlsPin)
                        }
                        
                        Toast.makeText(this, "QR Scanned. Registering...", Toast.LENGTH_SHORT).show()
                        if (missingPermissionsState.value.isNotEmpty()) {
                            requestInitialPermissions()
                        } else {
                            registerDeviceFlow(this)
                        }
                    } else {
                        Toast.makeText(this, "Invalid QR code format: $qrText", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to parse QR code: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val bgLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Background location permission result: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")

        config = ManagedConfigManager(this)
        networkManager = NetworkManager.getInstance(this, config)
        isRegisteredState.value = !config.getConnectionSecret().isNullOrBlank()

        // Always start DeviceGatewayService on launch
        val gatewayIntent = Intent(this, DeviceGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(gatewayIntent)
        } else {
            startService(gatewayIntent)
        }

        updatePermissionStates()
        if (config.getConnectionSecret().isNullOrBlank() && !config.getTrackingServerUrl().isNullOrBlank()) {
            registerDeviceFlow(this)
        }
        isBootStartEnabledState.value = config.isBootStartEnabled()

        setContent {
            AppTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    showSplash = false
                }

                if (showSplash) {
                    SplashScreen()
                } else {
                    MainScreen()
                }
            }
        }

        handleIntent(intent)
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        Log.i(TAG, "isAccessibilityServiceEnabled: enabledServices='$enabledServices', expected='$expectedComponentName'")

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            Log.d(TAG, "Checking service component: '$componentNameString' -> unflattened: '$enabledComponent'")
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun updatePermissionStates() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(this, com.cfd2474.eudremoteassist.receiver.DeviceAdminReceiver::class.java)
        isAdminActiveState.value = dpm.isAdminActive(adminComponent)
        isRegisteredState.value = !config.getConnectionSecret().isNullOrBlank()
        isAccessibilityActiveState.value = isAccessibilityServiceEnabled(this, com.cfd2474.eudremoteassist.service.RemoteAssistAccessibilityService::class.java)
        isOverlayActiveState.value = Settings.canDrawOverlays(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        isBatteryIgnoringState.value = powerManager.isIgnoringBatteryOptimizations(packageName)
        updateMissingPermissions()
        isBootStartEnabledState.value = config.isBootStartEnabled()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            Log.i(TAG, "ACTION_REQUEST_PROJECTION received. Triggering consent dialog.")
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                mediaProjectionManager.createScreenCaptureIntent(config)
            } else {
                mediaProjectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        }
    }

    private fun requestInitialPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateMissingPermissions() {
        val list = mutableListOf<String>()
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                list.add(permission)
            }
        }
        missingPermissionsState.value = list
    }

    private fun requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgLocationGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!bgLocationGranted) {
                // Request background location permission separately
                bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    // Compose UI Components with beautiful rich aesthetics
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val isRegistered = isRegisteredState.value
        val isAdminActive = isAdminActiveState.value
        val isAccessibilityActive = isAccessibilityActiveState.value
        val isOverlayActive = isOverlayActiveState.value
        val isBatteryIgnoring = isBatteryIgnoringState.value
        val missingPermissions = missingPermissionsState.value
        val isBootStartEnabled = isBootStartEnabledState.value

        var isWebsocketConnected by remember { mutableStateOf(networkManager.isWebSocketConnected()) }

        var showAccessibilitySetupDialog by remember { mutableStateOf(false) }
        var showRestrictedSettingsDialog by remember { mutableStateOf(false) }
        var showMissingPermissionsDialog by remember { mutableStateOf(false) }
        var showMdmOverrideDialog by remember { mutableStateOf(false) }

        var timeSinceLastHeartbeat by remember { mutableStateOf("N/A") }

        // Periodically update connection status and heartbeat counter
        LaunchedEffect(Unit) {
            while (true) {
                isWebsocketConnected = networkManager.isWebSocketConnected()
                val lastTime = networkManager.getLastHeartbeatTime()
                if (isWebsocketConnected && lastTime > 0L) {
                    val seconds = (System.currentTimeMillis() - lastTime) / 1000L
                    timeSinceLastHeartbeat = "${seconds}s ago"
                } else {
                    timeSinceLastHeartbeat = "N/A"
                }
                kotlinx.coroutines.delay(1000)
            }
        }



        // Remote Control Setup Dialog
        if (showAccessibilitySetupDialog) {
            AlertDialog(
                onDismissRequest = { showAccessibilitySetupDialog = false },
                title = { Text("Remote Control Setup", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. Click installed / downloaded apps", color = Color.LightGray, fontSize = 14.sp)
                        Text("2. Click EUD Remote Assist Control. If greyed out, click it anyways until a warning \"App was denied access\" appears. If this happens, Proceed to \"Allow Restricted Settings\" button", color = Color.LightGray, fontSize = 14.sp)
                        Text("3. Toggle the top switch to \"ON\"", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("If you encountered restricted settings warning, proceed to the \"Allow Restricted Settings\" before proceeding here.", color = Color(0xFFF87171), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAccessibilitySetupDialog = false
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Proceed", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAccessibilitySetupDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        // Restricted Settings Instructions Dialog
        if (showRestrictedSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showRestrictedSettingsDialog = false },
                title = { Text("How to enable Remote Control", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android security requires you to 'Allow Restricted Settings' before this app can start remote control.", color = Color.LightGray, fontSize = 14.sp)
                        Text("1. Click the button below to open app info.", color = Color.LightGray, fontSize = 14.sp)
                        Text("2. Tap the three dots in the top-right corner.", color = Color.LightGray, fontSize = 14.sp)
                        Text("3. Select 'Allow Restricted Settings'", color = Color.LightGray, fontSize = 14.sp)
                        Text("4. Verify your identity if prompted", color = Color.LightGray, fontSize = 14.sp)
                        Text("5. Return to Enable Remote Control button", color = Color.LightGray, fontSize = 14.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRestrictedSettingsDialog = false
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Go to settings", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestrictedSettingsDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        if (showMissingPermissionsDialog) {
            val missingList = mutableListOf<String>()
            if (missingPermissions.isNotEmpty()) missingList.add("Basic Permissions")
            if (!isAccessibilityActive) missingList.add("Remote Control")
            if (!isOverlayActive) missingList.add("System Overlays")
            if (!isBatteryIgnoring) missingList.add("Battery Optimization")
            if (!isAdminActive) missingList.add("Device Administrator")
            if (!isBootStartEnabled) missingList.add("Launch on Reboot")

            AlertDialog(
                onDismissRequest = { showMissingPermissionsDialog = false },
                title = { Text("Permissions Required", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To register this device with the portal, you must first enable all required controls:", color = Color.LightGray)
                        missingList.forEach { permissionName ->
                            Text("• $permissionName", color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Would you like to go to the Permission Setup screen to enable them?", color = Color.LightGray)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showMissingPermissionsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Go to Setup", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMissingPermissionsDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF0F172A), // Slate 900
                                    Color(0xFF1E293B)  // Slate 800
                                )
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Header
                        Text(
                            text = "EUD Remote Assist",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8), // Light Blue
                                fontFamily = FontFamily.SansSerif
                            ),
                            textAlign = TextAlign.Center
                        )



                        // Info Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Device Information",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )

                                HorizontalDivider(color = Color(0xFF334155))

                                InfoRow(label = "Device UID:", value = networkManager.getDeviceUid())
                                InfoRow(
                                    label = "Registration Status:",
                                    value = if (isRegistered) "Registered" else "Not Registered",
                                    valueColor = if (isRegistered) Color(0xFF4ADE80) else Color(0xFFF87171)
                                )
                                InfoRow(
                                    label = "WebSocket Status:",
                                    value = if (isWebsocketConnected) "Connected" else "Disconnected",
                                    valueColor = if (isWebsocketConnected) Color(0xFF4ADE80) else Color(0xFFF87171)
                                )
                                InfoRow(
                                    label = "Last Heartbeat:",
                                    value = timeSinceLastHeartbeat,
                                    valueColor = if (isWebsocketConnected && timeSinceLastHeartbeat != "N/A") Color(0xFF4ADE80) else Color(0xFFF87171)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        if (config.getConnectionSecret().isNullOrBlank()) {
                                            Toast.makeText(context, "Device must be registered first.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Refreshing connection...", Toast.LENGTH_SHORT).show()
                                            networkManager.disconnectWebSocket()
                                            val friendlyDeviceName = try {
                                                Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
                                            } catch (e: Exception) {
                                                Build.MODEL
                                            }
                                            networkManager.registerDevice(
                                                deviceName = friendlyDeviceName,
                                                model = Build.MODEL,
                                                appVersion = BuildInfo.VERSION_NAME,
                                                agency = config.getAgency()
                                            ) { success, error ->
                                                runOnUiThread {
                                                    if (success) {
                                                        isRegisteredState.value = true
                                                        networkManager.disconnectWebSocket()
                                                        networkManager.connectWebSocket()
                                                        Toast.makeText(context, "Connection Refreshed!", Toast.LENGTH_SHORT).show()
                                                        
                                                        // Trigger immediate telemetry sending
                                                        val telemetryIntent = Intent(context, com.cfd2474.eudremoteassist.service.DeviceGatewayService::class.java).apply {
                                                            action = com.cfd2474.eudremoteassist.service.DeviceGatewayService.ACTION_SEND_TELEMETRY
                                                        }
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                            context.startForegroundService(telemetryIntent)
                                                        } else {
                                                            context.startService(telemetryIntent)
                                                        }
                                                    } else {
                                                        networkManager.connectWebSocket()
                                                        Toast.makeText(context, "Refresh Failed: $error. Reconnecting...", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF0EA5E9)
                                    )
                                ) {
                                    Text("Refresh Connection", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Permission Shortcuts Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Permission Controls",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )

                                HorizontalDivider(color = Color(0xFF334155))

                                 val allGranted = missingPermissions.isEmpty()

                                 Button(
                                     onClick = {
                                         requestInitialPermissions()
                                     },
                                     enabled = !allGranted,
                                     modifier = Modifier.fillMaxWidth(),
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = Color(0xFFB91C1C), // Red when enabled (clickable, not all granted)
                                         disabledContainerColor = Color(0xFF15803D), // Green when disabled (all granted)
                                         disabledContentColor = Color.White
                                     )
                                 ) {
                                     Text(
                                         text = if (allGranted) "Basic Permissions: Granted" else "Enable Basic Permissions",
                                         color = Color.White
                                     )
                                 }

                                 if (!allGranted) {
                                     val userFriendlyNames = missingPermissions.map { perm ->
                                         when (perm) {
                                             Manifest.permission.ACCESS_FINE_LOCATION,
                                             Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
                                             Manifest.permission.READ_PHONE_STATE -> "Phone State"
                                             Manifest.permission.READ_PHONE_NUMBERS -> "Phone Numbers"
                                             Manifest.permission.CALL_PHONE -> "Phone Call"
                                             Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                                             else -> perm.substringAfterLast('.')
                                         }
                                     }.distinct().sorted()
                                     Text(
                                         text = "Missing permissions: " + userFriendlyNames.joinToString(", "),
                                         color = Color(0xFFF87171),
                                         fontSize = 13.sp,
                                         modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                                     )
                                 }

                                 Button(
                                     onClick = {
                                         config.setBootStartEnabled(true)
                                         isBootStartEnabledState.value = true
                                     },
                                     enabled = !isBootStartEnabled,
                                     modifier = Modifier.fillMaxWidth(),
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = Color(0xFFB91C1C), // Red when enabled (clickable)
                                         disabledContainerColor = Color(0xFF15803D), // Green when disabled (not clickable)
                                         disabledContentColor = Color.White
                                     )
                                 ) {
                                     Text(
                                         text = if (isBootStartEnabled) "Launch on Reboot: Enabled" else "Enable Launch on Reboot",
                                         color = Color.White
                                     )
                                 }

                                Button(
                                    onClick = {
                                        if (!isAccessibilityActive) {
                                            showAccessibilitySetupDialog = true
                                        } else {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isAccessibilityActive) Color(0xFF15803D) else Color(0xFFB91C1C)
                                    )
                                ) {
                                    Text(
                                        text = "Enable Remote Control",
                                        color = Color.White
                                    )
                                }

                                if (!isAccessibilityActive) {
                                    Button(
                                        onClick = {
                                            showRestrictedSettingsDialog = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFEF4444) // Light red
                                        )
                                    ) {
                                        Text("Allow Restricted Settings", color = Color.White)
                                    }
                                }

                                Button(
                                     onClick = {
                                         val intent = Intent(
                                             Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                             Uri.parse("package:${context.packageName}")
                                         )
                                         context.startActivity(intent)
                                     },
                                     enabled = !isOverlayActive,
                                     modifier = Modifier.fillMaxWidth(),
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = Color(0xFFB91C1C), // Red when enabled (clickable)
                                         disabledContainerColor = Color(0xFF15803D), // Green when disabled (not clickable)
                                         disabledContentColor = Color.White
                                     )
                                 ) {
                                     Text(
                                         text = if (isOverlayActive) "System Overlays: Enabled" else "Enable System Overlays",
                                         color = Color.White
                                     )
                                 }

                                 Button(
                                     onClick = {
                                         val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                             data = Uri.parse("package:${context.packageName}")
                                         }
                                         context.startActivity(intent)
                                     },
                                     enabled = !isBatteryIgnoring,
                                     modifier = Modifier.fillMaxWidth(),
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = Color(0xFFB91C1C), // Red when enabled (clickable)
                                         disabledContainerColor = Color(0xFF15803D), // Green when disabled (not clickable)
                                         disabledContentColor = Color.White
                                     )
                                 ) {
                                     Text(
                                         text = if (isBatteryIgnoring) "Battery Optimization: Disabled" else "Disable Battery Optimization",
                                         color = Color.White
                                     )
                                 }

                                val adminComponent = ComponentName(context, com.cfd2474.eudremoteassist.receiver.DeviceAdminReceiver::class.java)
                                Button(
                                    onClick = {
                                        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enables remote lock and admin capabilities.")
                                        }
                                        context.startActivity(intent)
                                    },
                                    enabled = !isAdminActive,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFB91C1C), // Red when enabled (clickable)
                                        disabledContainerColor = Color(0xFF15803D), // Green when disabled (not clickable)
                                        disabledContentColor = Color.White
                                    )
                                ) {
                                    Text(
                                        text = if (isAdminActive) "Device Administrator: Enabled" else "Enable Device Administrator",
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        val mdmManaged = config.isMdmManaged()

                        if (mdmManaged) {
                            Button(
                                onClick = {
                                    config.setMdmOverride(false)
                                    registerDeviceFlow(context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0284C7) // Sky 600
                                )
                            ) {
                                Text("MDM Register", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Button(
                            onClick = {
                                if (mdmManaged && !config.isMdmOverridden()) {
                                    showMdmOverrideDialog = true
                                } else {
                                    config.setMdmOverride(true)
                                    val intent = android.content.Intent(context, com.cfd2474.eudremoteassist.QrScannerActivity::class.java)
                                    qrScannerLauncher.launch(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981) // Emerald 500
                            )
                        ) {
                            Text("Scan QR to Enroll", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        // MDM Override Dialog
                        if (showMdmOverrideDialog) {
                            var showOverrideButton by remember { mutableStateOf(false) }

                            LaunchedEffect(showMdmOverrideDialog) {
                                showOverrideButton = false
                                kotlinx.coroutines.delay(30000)
                                showOverrideButton = true
                            }

                            AlertDialog(
                                onDismissRequest = { showMdmOverrideDialog = false },
                                title = { Text("MDM Managed Device", color = Color.White, fontWeight = FontWeight.Bold) },
                                text = {
                                    Text("This device is currently configured using MDM values.", color = Color.LightGray)
                                },
                                confirmButton = {
                                    if (showOverrideButton) {
                                        Button(
                                            onClick = {
                                                showMdmOverrideDialog = false
                                                config.setMdmOverride(true)
                                                val intent = android.content.Intent(context, com.cfd2474.eudremoteassist.QrScannerActivity::class.java)
                                                qrScannerLauncher.launch(intent)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                                        ) {
                                            Text("Continue to QR Registration", color = Color.White)
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showMdmOverrideDialog = false }) {
                                        Text("Dismiss", color = Color.Gray)
                                    }
                                },
                                containerColor = Color(0xFF1E293B)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "v${BuildInfo.VERSION_NAME}",
                            color = Color.Gray.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

    @Composable
    fun InfoRow(label: String, value: String, valueColor: Color = Color.LightGray) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color.Gray, fontSize = 14.sp)
            Text(text = value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val basicGranted = missingPermissionsState.value.isEmpty()
        val isAccessibilityActive = isAccessibilityActiveState.value
        val isOverlayActive = isOverlayActiveState.value
        val isBatteryIgnoring = isBatteryIgnoringState.value
        val isAdminActive = isAdminActiveState.value
        val isBootStartEnabled = isBootStartEnabledState.value
        Log.i(TAG, "checkAllPermissionsGranted: basicGranted=$basicGranted, isAccessibilityActive=$isAccessibilityActive, isOverlayActive=$isOverlayActive, isBatteryIgnoring=$isBatteryIgnoring, isAdminActive=$isAdminActive, isBootStartEnabled=$isBootStartEnabled")
        return basicGranted && isAccessibilityActive && isOverlayActive && isBatteryIgnoring && isAdminActive && isBootStartEnabled
    }

    private fun registerDeviceFlow(context: Context) {
        if (!checkAllPermissionsGranted()) {
            Toast.makeText(context, "Registration rejected: Missing required permission controls.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Aborted device registration due to missing permissions.")
            return
        }

        Log.i(TAG, "Starting clean de-registration before registering...")
        config.clearConnectionSecret()
        isRegisteredState.value = false
        networkManager.disconnectWebSocket()

        Toast.makeText(context, "Registering device...", Toast.LENGTH_SHORT).show()
        val friendlyDeviceName = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        } catch (e: Exception) {
            Build.MODEL
        }
        networkManager.registerDevice(
            deviceName = friendlyDeviceName,
            model = Build.MODEL,
            appVersion = BuildInfo.VERSION_NAME,
            agency = config.getAgency()
        ) { success, error ->
            runOnUiThread {
                if (success) {
                    isRegisteredState.value = true
                    Toast.makeText(context, "Registration Succeeded!", Toast.LENGTH_SHORT).show()
                    // Restart websocket to connect with secret
                    networkManager.disconnectWebSocket()
                    networkManager.connectWebSocket()

                    // Trigger immediate telemetry sending
                    val telemetryIntent = Intent(context, com.cfd2474.eudremoteassist.service.DeviceGatewayService::class.java).apply {
                        action = com.cfd2474.eudremoteassist.service.DeviceGatewayService.ACTION_SEND_TELEMETRY
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(telemetryIntent)
                    } else {
                        context.startService(telemetryIntent)
                    }
                } else {
                    Toast.makeText(context, "Registration Failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    fun SplashScreen() {
        Image(
            painter = painterResource(id = R.drawable.splash_screen),
            contentDescription = "Splash Screen",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }

    @Composable
    fun AppTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B)
            ),
            content = content
        )
    }
}
