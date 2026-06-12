package com.example.cfdremoteassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    
    var showRegistrationDialog by remember { mutableStateOf(!configManager.isRegistered()) }
    var isRegistering by remember { mutableStateOf(false) }
    var registrationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "CFD Remote Assist Status", style = MaterialTheme.typography.headlineMedium)
            
            PermissionSection()

            ServiceStatusSection()

            Spacer(modifier = Modifier.weight(1f))

            if (isSettingsUnlocked) {
                SettingsPanel(configManager) {
                    isSettingsUnlocked = false
                }
            } else {
                Button(onClick = { showPasswordDialog = true }) {
                    Text("Unlock Settings")
                }
            }
        }
    }

    if (showRegistrationDialog) {
        RegistrationDialog(
            serverUrl = configManager.getTrackingServerUrl(),
            isLoading = isRegistering,
            errorMessage = registrationError,
            onRegister = {
                isRegistering = true
                registrationError = null
                
                // Simulate network registration call
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val success = false // Simulate a failure to test error handling
                    
                    if (success) {
                        configManager.setRegistered(true)
                        showRegistrationDialog = false
                        isRegistering = false
                        val intent = Intent(context, LocationTrackingService::class.java)
                        context.startForegroundService(intent)
                    } else {
                        isRegistering = false
                        registrationError = "Could not connect to management server. Please check connection and try again."
                    }
                }, 2000)
            }
        )
    }

    if (showPasswordDialog) {
        PasswordEntryDialog(
            correctPassword = configManager.getSettingsPassword(),
            onDismiss = { showPasswordDialog = false },
            onSuccess = {
                isSettingsUnlocked = true
                showPasswordDialog = false
            }
        )
    }
}

@Composable
fun ServiceStatusSection() {
    val context = LocalContext.current
    
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text("Service Status:", style = MaterialTheme.typography.titleMedium)
        StatusItem("Accessibility", isAccessibilityServiceEnabled(context))
        StatusItem("Notification Listener", isNotificationServiceEnabled(context))
        StatusItem("Overlay (Draw on screen)", Settings.canDrawOverlays(context))
    }
}

@Composable
fun StatusItem(label: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Box(modifier = Modifier.size(12.dp).padding(2.dp).padding(end = 4.dp)) // Placeholder for dot
        Text(text = "$label: ${if (enabled) "Enabled" else "Disabled"}", color = color)
    }
}

fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = android.content.ComponentName(context, com.example.cfdremoteassist.services.RemoteAssistAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabledServices?.contains(expectedComponentName.flattenToString()) == true
}

fun isNotificationServiceEnabled(context: android.content.Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(pkgName) == true
}

@Composable
fun PermissionSection() {
    val context = LocalContext.current
    
    val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
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
        "Notifications & Nearby" to arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("System Permissions", style = MaterialTheme.typography.titleMedium)
        
        permissionGroups.forEach { (groupName, permissions) ->
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

        // Background location must be requested separately on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgLocationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            val isBgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            
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
        
        Button(modifier = Modifier.fillMaxWidth(), onClick = { 
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("Enable Remote Control (Accessibility)")
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) {
            Text("Enable Notification Access")
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("Enable Overlay (Draw on screen)")
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            context.startActivity(intent)
        }) {
            Text("Enable Usage Stats")
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for remote locking and device management.")
            }
            context.startActivity(intent)
        }) {
            Text(if (isDeviceAdminActive) "Device Admin: Active" else "Enable Device Admin (For Locking)")
        }
    }
}

@Composable
fun SettingsPanel(configManager: ManagedConfigManager, onLock: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Managed Settings", style = MaterialTheme.typography.titleLarge)
            Text("Server: ${configManager.getTrackingServerUrl()}")
            Text("Interval: ${configManager.getTrackingInterval()} mins")
            Text("Connection Secret: ${configManager.getConnectionSecret()}")
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(onClick = {
                val intent = Intent(context, LocationTrackingService::class.java)
                context.startForegroundService(intent)
            }) {
                Text("Start Tracking Service")
            }

            Button(onClick = {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_TRIGGER_PING
                }
                context.startService(intent)
            }) {
                Text("Test Audible Ping")
            }

            Button(onClick = {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_REQUEST_LOCATION
                }
                context.startService(intent)
            }) {
                Text("Test Immediate Location")
            }

            var isAdminActive by remember { mutableStateOf(false) }
            Button(onClick = {
                isAdminActive = !isAdminActive
                val action = if (isAdminActive) LocationTrackingService.ACTION_START_REMOTE_ADMIN 
                             else LocationTrackingService.ACTION_STOP_REMOTE_ADMIN
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    this.action = action
                }
                context.startService(intent)
            }) {
                Text(if (isAdminActive) "End Remote Admin Mode" else "Start Remote Admin Mode")
            }

            Button(onClick = {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_LOCK_DEVICE
                }
                context.startService(intent)
            }) {
                Text("Test Remote Lock")
            }

            Button(onClick = onLock) {
                Text("Lock Settings")
            }
        }
    }
}

@Composable
fun RegistrationDialog(
    serverUrl: String, 
    isLoading: Boolean, 
    errorMessage: String?, 
    onRegister: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { }, // Force registration
        title = { Text("Registration Required") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("This device needs to be registered with the management server:")
                Text(
                    text = serverUrl, 
                    style = MaterialTheme.typography.bodyLarge, 
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    Text("Registering...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRegister,
                enabled = !isLoading
            ) {
                Text("Register with Server")
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