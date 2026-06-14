package com.example.cfdremoteassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.AppOpsManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import com.example.cfdremoteassist.receivers.RemoteAssistDeviceAdminReceiver
import com.example.cfdremoteassist.remote.RemoteSessionManager
import com.example.cfdremoteassist.services.LocationTrackingService
import com.example.cfdremoteassist.services.ScreenShareService
import com.example.cfdremoteassist.ui.theme.EUDRemoteAssistTheme
import com.example.cfdremoteassist.utils.ManagedConfigManager
import com.example.cfdremoteassist.utils.NetworkManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Wake up screen and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        enableEdgeToEdge()
        setContent {
            EUDRemoteAssistTheme {
                MainScreen(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Wake up and show over lock screen again for the new intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        // This will trigger the LaunchedEffect(refreshTrigger) if it's a share request
        if (intent.action == "TRIGGER_SCREEN_SHARE") {
            // Force a recomposition to process the new intent action
            recreate()
        }
    }


    fun getPhoneNumber(context: Context): String? {
        try {
            val telephonyManager = context.getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            
            // 1. Try TelephonyManager.line1Number (Requires READ_PHONE_STATE or READ_PHONE_NUMBERS)
            @Suppress("MissingPermission")
            var number = telephonyManager.line1Number
            
            // 2. If null, try SubscriptionManager (Modern way for multi-SIM)
            if (number.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = context.getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                @Suppress("MissingPermission")
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptions.isNullOrEmpty()) {
                    for (info in activeSubscriptions) {
                        // API 33+ uses getPhoneNumber
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                @Suppress("MissingPermission")
                                val n = subscriptionManager.getPhoneNumber(info.subscriptionId)
                                if (n.isNotEmpty()) {
                                    number = n
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                        
                        // Fallback to deprecated number property
                        @Suppress("DEPRECATION")
                        val n = info.number
                        if (!n.isNullOrEmpty()) {
                            number = n
                            break
                        }
                    }
                }
            }

            if (!number.isNullOrEmpty()) {
                Log.i("MainActivity", "Phone number acquired successfully")
                return number
            } else {
                Log.d("MainActivity", "Phone number is null or empty after all attempts")
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "Phone number acquisition failed: ${e.message}")
        }
        return null
    }
}

@Composable
fun MainScreen(activity: MainActivity) {
    val context = LocalContext.current
    val configManager = remember { ManagedConfigManager(context) }
    val networkManager = remember { NetworkManager.getInstance(context, configManager) }
    var isSettingsUnlocked by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    
    var isRegistered by remember { mutableStateOf(configManager.isRegistered()) }
    var isRegistering by remember { mutableStateOf(false) }
    var registrationError by remember { mutableStateOf<String?>(null) }
    
    var showRestrictedDialog by remember { mutableStateOf(false) }
    var isRestricted by remember { mutableStateOf(isRestrictedSettingsActive(context)) }
    
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val intent = Intent(context, ScreenShareService::class.java).apply {
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_DATA, result.data)
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start screen share: ${e.message}")
                Toast.makeText(context, "Failed to start screen share: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Auto-refresh when returning to app from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Refresh tracking connection on app launch
    LaunchedEffect(Unit) {
        if (configManager.isRegistered()) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(intent)
        }
    }

    // Auto-trigger screen share if requested by service
    LaunchedEffect(refreshTrigger) {
        val intent = (context as? Activity)?.intent
        if (intent?.action == "TRIGGER_SCREEN_SHARE") {
            intent.action = null 
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Force full screen sharing on Android 14+
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            
            screenCaptureLauncher.launch(captureIntent)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
                isRegistered = configManager.isRegistered()
                isRestricted = isRestrictedSettingsActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun performRegistration() {
        val url = configManager.getTrackingServerUrl()
        if (url.isEmpty()) {
            registrationError = "Server configuration missing. Please unlock Settings and enter server details."
            return
        }

        isRegistering = true
        registrationError = null
        
        val deviceInfo = mutableMapOf<String, String>()
        deviceInfo["uid"] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        deviceInfo["model"] = Build.MODEL
        deviceInfo["app_version"] = "1.2.1"
        
        val agency = configManager.getAgency()
        if (agency.isNotEmpty()) {
            deviceInfo["agency"] = agency
        }
        
        val phoneNumber = activity.getPhoneNumber(context)
        if (phoneNumber != null) {
            deviceInfo["phone_number"] = phoneNumber
        }

        try {
            deviceInfo["device_name"] = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        } catch (e: Exception) {}

        networkManager.register(deviceInfo) { success, error ->
            isRegistering = false
            if (success) {
                isRegistered = true
                val intent = Intent(context, LocationTrackingService::class.java)
                context.startForegroundService(intent)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Registration Successful", Toast.LENGTH_SHORT).show()
                }
            } else {
                registrationError = error ?: "Registration failed"
            }
        }
    }

    // Permission groupings for status check
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val commsPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }.toTypedArray()
    val mediaPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.READ_CALENDAR).apply {
        val hasHeartRate = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE)
        if (hasHeartRate) {
            add(Manifest.permission.BODY_SENSORS)
        }
    }.toTypedArray()
    val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val nearbyPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    val allGroups = listOf(
        "Location" to locationPermissions,
        "Communication" to commsPermissions,
        "Media & Sensors" to mediaPermissions,
        "Storage" to storagePermissions,
        "Notifications & Nearby" to nearbyPermissions
    )

    val allPermissionsGranted = allGroups.all { (_, permissions) ->
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "EUD Remote Assist Status", style = MaterialTheme.typography.headlineMedium)
            
            key(refreshTrigger, isRegistered) {
                PermissionSection(
                    configManager = configManager,
                    isRegistered = isRegistered,
                    isRegistering = isRegistering,
                    registrationError = registrationError,
                    isRestricted = isRestricted,
                    onFixRestricted = { showRestrictedDialog = true },
                    onRegister = { performRegistration() }
                )

                ServiceStatusSection(onRefresh = { refreshTrigger++ })

                if (isRegistered) {
                    DiagnosticsSection(networkManager)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isSettingsUnlocked) {
                SettingsPanel(configManager, onReRegister = { performRegistration() }) {
                    isSettingsUnlocked = false
                }
            } else {
                Button(onClick = { 
                    if (configManager.isPasswordSet()) {
                        showPasswordDialog = true 
                    } else {
                        showSetPasswordDialog = true
                    }
                }) {
                    Text("Server Configuration Settings")
                }
            }

            if (!isRegistered) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = { performRegistration() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRegistering && allPermissionsGranted,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    if (isRegistering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onTertiary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Registering...")
                    } else {
                        Text("Register with Management Server")
                    }
                }
                if (!allPermissionsGranted) {
                    Text(
                        "Note: All system permissions above must be granted before registration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                registrationError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                Text(
                    "Device Registered", 
                    color = MaterialTheme.colorScheme.primary, 
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (showPasswordDialog) {
        val password = configManager.getSettingsPassword()
        if (password != null) {
            PasswordEntryDialog(
                correctPassword = password,
                onDismiss = { showPasswordDialog = false },
                onSuccess = {
                    isSettingsUnlocked = true
                    showPasswordDialog = false
                    Toast.makeText(context, "Settings Unlocked", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            showPasswordDialog = false
            showSetPasswordDialog = true
        }
    }

    if (showSetPasswordDialog) {
        SetPasswordDialog(
            onDismiss = { showSetPasswordDialog = false },
            onSuccess = { newPassword ->
                configManager.setManualPassword(newPassword)
                showSetPasswordDialog = false
                isSettingsUnlocked = true
                Toast.makeText(context, "Password Set & Settings Unlocked", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showRestrictedDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictedDialog = false },
            title = { Text("How to enable Remote Control") },
            text = {
                Text(
                    "Android security requires you to 'Allow restricted settings' before this app can start remote control.\n\n" +
                    "1. Click the button below to open App Info.\n" +
                    "2. Tap the three dots (⋮) in the top-right corner.\n" +
                    "3. Select 'Allow restricted settings'.\n" +
                    "4. Verify your identity if prompted.\n" +
                    "5. Return to this app to enable Remote Control."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestrictedDialog = false
                    
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ServiceStatusSection(onRefresh: () -> Unit) {
    val context = LocalContext.current
    
    // Heartbeat timer
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val lastHeartbeat = RemoteSessionManager.lastHeartbeatReceivedAt
    val secondsAgo = if (lastHeartbeat > 0) (currentTime - lastHeartbeat) / 1000 else -1
    val heartbeatText = if (secondsAgo < 0) "Never" else "${secondsAgo}s ago"
    val isHeartbeatHealthy = secondsAgo in 0..60

    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Service Status:", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRefresh) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        
        StatusItem("Last Heartbeat", isHeartbeatHealthy, heartbeatText)
        StatusItem("Accessibility", isAccessibilityServiceEnabled(context))
        StatusItem("Notification Listener", isNotificationServiceEnabled(context))
        StatusItem("Overlay (Draw on screen)", Settings.canDrawOverlays(context))
        StatusItem("Usage Access", isUsageAccessGranted(context))
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        StatusItem("Battery Optimization Ignored", powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
}

@Composable
fun DiagnosticsSection(networkManager: NetworkManager) {
    val context = LocalContext.current
    var isPinging by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Device Diagnostics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
        
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = Intent(context, LocationTrackingService::class.java)
                context.startForegroundService(intent)
                Toast.makeText(context, "Connection Refreshed", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text("Refresh Tracking Connection")
        }

        Button(
            onClick = {
                isPinging = true
                val uid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                networkManager.ping(uid) { success, error ->
                    isPinging = false
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (success) {
                            Toast.makeText(context, "Server Ping Successful: EUD Recognized", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, error ?: "Server Ping Failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isPinging
        ) {
            if (isPinging) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pinging Server...")
            } else {
                Text("Ping Management Server")
            }
        }
    }
}

@Composable
fun StatusItem(label: String, enabled: Boolean, statusText: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Box(modifier = Modifier.size(12.dp).padding(2.dp).padding(end = 4.dp)) 
        val displayStatus = statusText ?: if (enabled) "Enabled" else "Disabled"
        Text(text = "$label: $displayStatus", color = color)
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedId = ComponentName(context, com.example.cfdremoteassist.services.RemoteAssistAccessibilityService::class.java).flattenToString()
    val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(settingValue)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedId, ignoreCase = true)) {
            return true
        }
    }
    return false
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(pkgName) == true
}

fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isRestrictedSettingsActive(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return try {
        // AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS is 119
        val mode = appOps.noteOpNoThrow(
            "android:access_restricted_settings",
            android.os.Process.myUid(),
            context.packageName,
            null,
            null
        )
        mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
    } catch (e: Exception) {
        // If we can't check, assume restricted if accessibility is off on Android 13+
        !isAccessibilityServiceEnabled(context)
    }
}

@Composable
fun PermissionSection(
    configManager: ManagedConfigManager,
    isRegistered: Boolean,
    isRegistering: Boolean,
    registrationError: String?,
    isRestricted: Boolean,
    onFixRestricted: () -> Unit,
    onRegister: () -> Unit
) {
    val context = LocalContext.current
    var showRemoteControlGuidance by remember { mutableStateOf(false) }
    
    if (showRemoteControlGuidance) {
        AlertDialog(
            onDismissRequest = { showRemoteControlGuidance = false },
            title = { Text("Remote Control Setup") },
            text = {
                Text(
                    "1. Click 'Installed / Downloaded Apps'\n" +
                    "2. Click 'Remote Assist Remote Control'\n" +
                    "3. Toggle the switch to ON\n\n" +
                    "If the next screen shows 'Settings Restricted' or access is denied, please return and use the 'ALLOW RESTRICTED SETTINGS' button below first, then proceed to Enable Remote Control."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRemoteControlGuidance = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Proceed to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteControlGuidance = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    val adminComponent = ComponentName(context, RemoteAssistDeviceAdminReceiver::class.java)
    val isDeviceAdminActive = dpm.isAdminActive(adminComponent)

    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val commsPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }.toTypedArray()
    val mediaPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.READ_CALENDAR).apply {
        val hasHeartRate = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE)
        if (hasHeartRate) {
            add(Manifest.permission.BODY_SENSORS)
        }
    }.toTypedArray()
    
    val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    val nearbyPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    val allPermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            Toast.makeText(context, "All System Permissions: Granted", Toast.LENGTH_SHORT).show()
        } else {
            val deniedNames = denied.joinToString { it.substringAfterLast(".") }
            Toast.makeText(context, "Some Permissions Denied: $deniedNames", Toast.LENGTH_LONG).show()
        }
    }

    val allGroups = listOf(
        "Location" to locationPermissions,
        "Communication" to commsPermissions,
        "Media & Sensors" to mediaPermissions,
        "Storage" to storagePermissions,
        "Notifications & Nearby" to nearbyPermissions
    )

    val allPermissionsGranted = allGroups.all { (_, permissions) ->
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Required Permissions", style = MaterialTheme.typography.titleMedium)

        // 1. Remote Control (Accessibility)
        SpecialAccessButton(
            label = "Enable Remote Control (Accessibility)",
            enabled = isAccessibilityServiceEnabled(context),
            onClick = { 
                if (isAccessibilityServiceEnabled(context)) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    showRemoteControlGuidance = true 
                }
            }
        )

        // 2. Restricted Settings Prompt
        if (isRestricted && !isAccessibilityServiceEnabled(context)) {
            Button(
                onClick = onFixRestricted,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("ALLOW RESTRICTED SETTINGS")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // 3. Required System Permissions (Grant All row)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSystemPermissions = remember {
                val list = mutableListOf<String>()
                allGroups.forEach { list.addAll(it.second) }
                list.toTypedArray()
            }
            val isAllGranted = allSystemPermissions.all { 
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
            }

            Text("System Permissions", style = MaterialTheme.typography.titleSmall)
            
            Button(
                onClick = { allPermissionsLauncher.launch(allSystemPermissions) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAllGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
                    contentColor = Color.White
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isAllGranted) "Granted" else "Grant All")
                    if (isAllGranted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        
        allGroups.forEach { (groupName, permissions) ->
            if (permissions.isNotEmpty()) {
                val isGranted = permissions.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                }

                val deniedPermissions = permissions.filter { 
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED 
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val color = if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
                        Text(text = groupName, color = color)
                        if (!isGranted && deniedPermissions.isNotEmpty()) {
                            Text(
                                text = "Missing: ${deniedPermissions.joinToString { it.substringAfterLast(".") }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (isGranted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Granted",
                            tint = Color(0xFF2E7D32)
                        )
                    }
                }
            }
        }

        // Background location separately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isBgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val bgLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val color = if (isBgGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
                Text("Background Location", color = color)
                if (!isBgGranted) {
                    Button(
                        onClick = { bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Request")
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Granted",
                        tint = Color(0xFF2E7D32)
                    )
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // 4. Remaining Special Access
        SpecialAccessButton(
            label = "Disable Battery Optimization",
            enabled = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName),
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        SpecialAccessButton(
            label = "Enable Notification Access",
            enabled = isNotificationServiceEnabled(context),
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )

        SpecialAccessButton(
            label = "Enable Overlay (Draw on screen)",
            enabled = Settings.canDrawOverlays(context),
            onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
                }
            }
        )

        SpecialAccessButton(
            label = "Enable Usage Stats",
            enabled = isUsageAccessGranted(context),
            onClick = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
            }
        )

        SpecialAccessButton(
            label = "Enable Device Admin (For Locking)",
            enabled = isDeviceAdminActive,
            onClick = {
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for remote locking and device management.")
                }
                context.startActivity(intent)
            }
        )

        var isBootEnabled by remember { mutableStateOf(configManager.isBootStartEnabled()) }
        SpecialAccessButton(
            label = "Enable on Boot",
            enabled = isBootEnabled,
            onClick = {
                isBootEnabled = !isBootEnabled
                configManager.setBootStartEnabled(isBootEnabled)
                Toast.makeText(context, if (isBootEnabled) "Will start on boot" else "Will NOT start on boot", Toast.LENGTH_SHORT).show()
            }
        )
    }
}


@Composable
fun SpecialAccessButton(label: String, enabled: Boolean, hint: String? = null, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                contentColor = Color.White
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label)
                if (enabled) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = "Granted")
                }
            }
        }
        if (hint != null && !enabled) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun SettingsPanel(configManager: ManagedConfigManager, onReRegister: () -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    var manualUrl by remember { mutableStateOf(configManager.getTrackingServerUrl()) }
    var manualAgency by remember { mutableStateOf(configManager.getAgency()) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }

    // Sync state if MDM policy changes while panel is open
    val isUrlManaged = configManager.isServerUrlManaged()
    val isAgencyManaged = configManager.isAgencyManaged()
    
    LaunchedEffect(isUrlManaged, isAgencyManaged) {
        if (isUrlManaged) manualUrl = configManager.getTrackingServerUrl()
        if (isAgencyManaged) manualAgency = configManager.getAgency()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Managed Settings", style = MaterialTheme.typography.titleLarge)
            
            val isUrlManaged = configManager.isServerUrlManaged()
            val isAgencyManaged = configManager.isAgencyManaged()

            Text("Server Configuration:", style = MaterialTheme.typography.titleSmall)
            
            TextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("Registration Server URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isUrlManaged,
                supportingText = {
                    if (isUrlManaged) Text("Value forced by MDM policy", color = MaterialTheme.colorScheme.primary)
                }
            )
            
            TextField(
                value = manualAgency,
                onValueChange = { manualAgency = it },
                label = { Text("Agency Name") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAgencyManaged,
                supportingText = {
                    if (isAgencyManaged) Text("Value forced by MDM policy", color = MaterialTheme.colorScheme.primary)
                }
            )

            if (!isUrlManaged || !isAgencyManaged) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!isUrlManaged) configManager.setManualServerUrl(manualUrl)
                        if (!isAgencyManaged) configManager.setManualAgency(manualAgency)
                        Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
                        onLock()
                    }
                ) {
                    Text("Save Config")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Policy Details:", style = MaterialTheme.typography.titleSmall)
            Text("Interval: ${configManager.getTrackingInterval()} mins")
            Text("Connection Secret: ${configManager.getConnectionSecret()}")
            
            val isPassManaged = configManager.isPasswordManaged()
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showChangePasswordDialog = true },
                enabled = !isPassManaged
            ) {
                Text(if (isPassManaged) "Password forced by MDM policy" else "Change Settings Password")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val intent = Intent(context, LocationTrackingService::class.java)
                context.startForegroundService(intent)
                Toast.makeText(context, "Service Restarted", Toast.LENGTH_SHORT).show()
            }) {
                Text("Force Service Restart")
            }

            Button(modifier = Modifier.fillMaxWidth(), onClick = onReRegister) {
                Text("Re-Register Device")
            }

            Button(modifier = Modifier.fillMaxWidth(), onClick = onLock) {
                Text("Lock Settings")
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    val intent = Intent(context, LocationTrackingService::class.java).apply {
                        action = ScreenShareService.ACTION_STOP
                    }
                    context.stopService(intent)
                    context.stopService(Intent(context, LocationTrackingService::class.java))
                    context.stopService(Intent(context, ScreenShareService::class.java))
                    context.stopService(Intent(context, com.example.cfdremoteassist.services.OverlayService::class.java))
                    (context as? Activity)?.finishAffinity()
                    System.exit(0)
                }
            ) {
                Text("Force Quit App", color = MaterialTheme.colorScheme.onError)
            }
        }
    }

    if (showChangePasswordDialog) {
        SetPasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSuccess = { newPassword ->
                configManager.setManualPassword(newPassword)
                showChangePasswordDialog = false
                Toast.makeText(context, "Password Updated", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun SetPasswordDialog(onDismiss: () -> Unit, onSuccess: (String) -> Unit) {
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set New Settings Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This password will be required to unlock managed settings.")
                TextField(
                    value = pass1,
                    onValueChange = { pass1 = it; error = null },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("New Password") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = pass2,
                    onValueChange = { pass2 = it; error = null },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Confirm Password") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pass1.isEmpty()) {
                    error = "Password cannot be empty"
                } else if (pass1 != pass2) {
                    error = "Passwords do not match"
                } else {
                    onSuccess(pass1)
                }
            }) {
                Text("Save Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PasswordEntryDialog(correctPassword: String, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Settings Password") },
        text = {
            Column {
                TextField(
                    value = password,
                    onValueChange = { password = it; error = false },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") },
                    isError = error
                )
                if (error) {
                    Text("Incorrect password", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (password == correctPassword) {
                    onSuccess()
                } else {
                    error = true
                }
            }) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
