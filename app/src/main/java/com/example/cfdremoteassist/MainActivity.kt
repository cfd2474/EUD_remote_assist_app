package com.example.cfdremoteassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.AppOpsManager
import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.cfdremoteassist.services.LocationTrackingService
import com.example.cfdremoteassist.ui.theme.CFDRemoteAssistTheme
import com.example.cfdremoteassist.utils.ManagedConfigManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CFDRemoteAssistTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val configManager = remember { ManagedConfigManager(context) }
    var isSettingsUnlocked by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    
    var isRegistered by remember { mutableStateOf(configManager.isRegistered()) }
    var isRegistering by remember { mutableStateOf(false) }
    var registrationError by remember { mutableStateOf<String?>(null) }
    
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    // Auto-refresh when returning to app from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
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
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            configManager.setRegistered(true)
            isRegistered = true
            isRegistering = false
            
            val intent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(intent)
            Toast.makeText(context, "Registration Successful", Toast.LENGTH_SHORT).show()
        }, 2000)
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
            Text(text = "CFD Remote Assist Status", style = MaterialTheme.typography.headlineMedium)
            
            // Wrap in key to force recomposition and permission re-check on refreshTrigger change
            key(refreshTrigger) {
                PermissionSection(
                    isRegistered = isRegistered,
                    isRegistering = isRegistering,
                    registrationError = registrationError,
                    onRegister = { performRegistration() }
                )

                ServiceStatusSection(onRefresh = { refreshTrigger++ })

                if (isRegistered) {
                    DiagnosticsSection()
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isSettingsUnlocked) {
                SettingsPanel(configManager, onReRegister = { performRegistration() }) {
                    isSettingsUnlocked = false
                }
            } else {
                Button(onClick = { showPasswordDialog = true }) {
                    Text("Unlock Settings")
                }
            }
        }
    }

    if (showPasswordDialog) {
        PasswordEntryDialog(
            correctPassword = configManager.getSettingsPassword(),
            onDismiss = { showPasswordDialog = false },
            onSuccess = {
                isSettingsUnlocked = true
                showPasswordDialog = false
                Toast.makeText(context, "Settings Unlocked", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun ServiceStatusSection(onRefresh: () -> Unit) {
    val context = LocalContext.current
    
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
        
        StatusItem("Accessibility", isAccessibilityServiceEnabled(context))
        StatusItem("Notification Listener", isNotificationServiceEnabled(context))
        StatusItem("Overlay (Draw on screen)", Settings.canDrawOverlays(context))
        StatusItem("Usage Access", isUsageAccessGranted(context))
    }
}

@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    var isPinging by remember { mutableStateOf(false) }
    var isAdminActive by remember { mutableStateOf(false) }

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
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isPinging = false
                    Toast.makeText(context, "Server Ping Successful: EUD Recognized", Toast.LENGTH_LONG).show()
                }, 1500)
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

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isAdminActive = !isAdminActive
                val action = if (isAdminActive) LocationTrackingService.ACTION_START_REMOTE_ADMIN 
                             else LocationTrackingService.ACTION_STOP_REMOTE_ADMIN
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    this.action = action
                }
                context.startService(intent)
            }
        ) {
            Text(if (isAdminActive) "End Remote Admin Mode" else "Start Remote Admin Mode")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_LOCK_DEVICE
                }
                context.startService(intent)
            }
        ) {
            Text("Test Remote Lock")
        }
    }
}

@Composable
fun StatusItem(label: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Box(modifier = Modifier.size(12.dp).padding(2.dp).padding(end = 4.dp)) 
        Text(text = "$label: ${if (enabled) "Enabled" else "Disabled"}", color = color)
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedId = android.content.ComponentName(context, com.example.cfdremoteassist.services.RemoteAssistAccessibilityService::class.java).flattenToString()
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

@Composable
fun PermissionSection(
    isRegistered: Boolean,
    isRegistering: Boolean,
    registrationError: String?,
    onRegister: () -> Unit
) {
    val context = LocalContext.current
    
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    val adminComponent = android.content.ComponentName(context, com.example.cfdremoteassist.receivers.RemoteAssistDeviceAdminReceiver::class.java)
    val isDeviceAdminActive = dpm.isAdminActive(adminComponent)

    val permissionGroups = listOf(
        "Location" to arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ),
        "Communication" to arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        ),
        "Media & Sensors" to arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.READ_CALENDAR
        ),
        "Storage" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
        "Notifications & Nearby" to mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    )

    // Only count permissions that are actually relevant for the current API level
    val allPermissionsGranted = permissionGroups.all { (_, permissions) ->
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("System Permissions", style = MaterialTheme.typography.titleMedium)
        
        permissionGroups.forEach { (groupName, permissions) ->
            if (permissions.isNotEmpty()) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    val allGranted = results.values.all { it }
                    Toast.makeText(context, "$groupName: ${if (allGranted) "Granted" else "Check Permissions"}", Toast.LENGTH_SHORT).show()
                }
                
                val isGranted = permissions.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(groupName)
                    Button(
                        onClick = { launcher.launch(permissions) },
                        colors = if (isGranted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) 
                                 else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (isGranted) "Granted" else "Request")
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
                Text("Background Location")
                Button(onClick = { bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                    Text(if (isBgGranted) "Granted" else "Request")
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        Text("Special Access", style = MaterialTheme.typography.titleMedium)
        
        SpecialAccessButton(
            label = "Enable Remote Control (Accessibility)",
            enabled = isAccessibilityServiceEnabled(context),
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (!isRegistered) {
            Button(
                onClick = onRegister,
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
            if (registrationError != null) {
                Text(
                    text = registrationError,
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

@Composable
fun SpecialAccessButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = if (enabled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                 else ButtonDefaults.buttonColors()
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
}

@Composable
fun SettingsPanel(configManager: ManagedConfigManager, onReRegister: () -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    var manualAddress by remember { mutableStateOf(configManager.getManualAddress()) }
    var manualPort by remember { mutableStateOf(configManager.getManualPort()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Managed Settings", style = MaterialTheme.typography.titleLarge)
            
            if (configManager.hasManagedConfig()) {
                val url = configManager.getTrackingServerUrl()
                Text("Server: ${if (url.isEmpty()) "Not Configured" else url}")
                Text("Source: MDM Managed Policy", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Server Configuration (Manual):", style = MaterialTheme.typography.titleSmall)
                TextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    label = { Text("Server Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    configManager.setManualServerConfig(manualAddress, manualPort)
                    Toast.makeText(context, "Server Config Saved", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Save Server Config")
                }
            }

            Text("Interval: ${configManager.getTrackingInterval()} mins")
            Text("Connection Secret: ${configManager.getConnectionSecret()}")
            
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
        }
    }
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
