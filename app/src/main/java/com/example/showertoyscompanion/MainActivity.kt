package com.example.showertoyscompanion // Use your actual package name

// Android Core & Lifecycle Imports
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast // For user feedback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels // For by viewModels()
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle // Recommended for observing flows

// Jetpack Compose UI Imports
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator // For connecting state
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.* // For Composable, remember, getValue, etc.
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// App Specific Imports
import com.example.showertoyscompanion.ui.theme.ShowertoysCompanionTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// Assuming ConnectionStatus, ClipboardState, MainViewModel are defined in their files

class MainActivity : ComponentActivity() {

    // Get a reference to the ViewModel, scoped to this Activity's lifecycle
    private val viewModel: MainViewModel by viewModels()

    // --- Permission Launcher ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                println("Notification permission granted.")
                // Permission granted, now try to start/connect using the ViewModel's current IP
                viewModel.serverIp.value?.let { ip ->
                    startOrReconnectService(ip) // Trigger service start via Intent
                } ?: run {
                    showToast("IP address not set. Scan QR code.")
                }
            } else {
                println("Notification permission denied.")
                ClipboardState.updateStatus(ConnectionStatus.ERROR) // Update state
                showToast("Notification permission denied. Service cannot run.")
            }
        }

    // --- QR Code Scanner Launcher ---
    private val scanQrCodeLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents == null) {
                println("QR Scan cancelled.")
                showToast("QR Scan Cancelled")
            } else {
                println("QR Scan successful: $contents")
                try {
                    // Assuming URL format like http://IP:PORT/ or ws://IP:PORT/
                    val ipAddress = contents.substringAfter("://").substringBefore(":")
                    if (ipAddress.isNotEmpty()) {
                        println("Extracted IP: $ipAddress")
                        viewModel.updateServerIp(ipAddress) // Update ViewModel/State
                        saveServerIp(ipAddress)             // Save locally
                        checkPermissionAndStartOrReconnectService(ipAddress) // Start/Reconnect
                    } else {
                        val errorMsg = "Could not parse IP from QR code: $contents"
                        println(errorMsg)
                        ClipboardState.updateStatus(ConnectionStatus.ERROR)
                        showToast(errorMsg)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Error parsing QR code: ${e.message}"
                    Log.e("MainActivity", errorMsg, e)
                    println(errorMsg)
                    ClipboardState.updateStatus(ConnectionStatus.ERROR)
                    showToast(errorMsg)
                }
            }
        }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load stored IP and update ViewModel state initially
        val storedIp = getStoredIp()
        if (storedIp.isNotEmpty()) {
            viewModel.updateServerIp(storedIp)
        } else {
            viewModel.updateServerIp(null) // Ensure state is null if nothing stored
        }

        setContent {
            // Observe state flows directly from the ViewModel using lifecycle-aware collection
            val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
            val serverIp by viewModel.serverIp.collectAsStateWithLifecycle()

            // Derived state for button logic/text
            val isServicePotentiallyRunning = connectionStatus != ConnectionStatus.DISCONNECTED

            // Effect to attempt initial connection if IP exists and state is Disconnected
            // This runs once when the composable enters the composition or if key values change
            LaunchedEffect(serverIp, connectionStatus) {
                // Only attempt auto-connect if we have an IP and are currently disconnected
                val currentIp = serverIp
                if (!currentIp.isNullOrEmpty() && connectionStatus == ConnectionStatus.DISCONNECTED) {
                    println("Attempting initial service start with IP: $currentIp")
                    // Use the local, non-null copy 'currentIp' here
                    checkPermissionAndStartOrReconnectService(currentIp)
                }
            }

            ShowertoysCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass observed state and event handlers down to the Composable UI
                    MainScreen(
                        connectionStatus = connectionStatus,
                        serverIp = serverIp,
                        onScanClick = { launchQrScanner() }, // Call method to launch scanner
                        onToggleServiceClick = { // Toggle start/stop logic
                            if (isServicePotentiallyRunning) {
                                stopSyncService() // Send intent to stop
                            } else {
                                // Start service only if we have an IP
                                serverIp?.let { ip ->
                                    checkPermissionAndStartOrReconnectService(ip)
                                } ?: run {
                                    println("Cannot start: No IP Address. Scan QR Code.")
                                    showToast("Scan PC QR code first")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // --- UI Composable ---
    @Composable
    fun MainScreen(
        connectionStatus: ConnectionStatus,
        serverIp: String?,
        onScanClick: () -> Unit,
        onToggleServiceClick: () -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ShowerToys Companion", style = MaterialTheme.typography.headlineLarge) // Use Large headline
            Spacer(modifier = Modifier.height(24.dp)) // Add more space

            // Display connection status text and color
            val statusText: String
            val statusColor: Color
            when (connectionStatus) {
                ConnectionStatus.DISCONNECTED -> {
                    statusText = "Disconnected"
                    statusColor = MaterialTheme.colorScheme.onSurfaceVariant // Default text color
                }
                ConnectionStatus.CONNECTING -> {
                    statusText = "Connecting..."
                    statusColor = Color(0xFFFFA500) // Orange color
                }
                ConnectionStatus.CONNECTED -> {
                    statusText = "Connected"
                    statusColor = Color(0xFF008000) // Green color
                }
                ConnectionStatus.ERROR -> {
                    statusText = "Connection Error"
                    statusColor = MaterialTheme.colorScheme.error // Theme's error color (usually red)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: ")
                Text(statusText, color = statusColor, style = MaterialTheme.typography.titleMedium)
                if (connectionStatus == ConnectionStatus.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(start = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Text("PC Address: ${serverIp ?: "Not Set"}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Scan Button
            Button(onClick = onScanClick) {
                Text("Scan PC QR Code")
            }

            // Connect/Disconnect Button
            Button(
                onClick = onToggleServiceClick,
                // Enable button only if an IP address has been set
                enabled = !serverIp.isNullOrEmpty() && connectionStatus != ConnectionStatus.CONNECTING // Disable while connecting
            ) {
                // Change button text based on current connection status
                val buttonText = when (connectionStatus) {
                    ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> "Connect"
                    ConnectionStatus.CONNECTING -> "Connecting..."
                    ConnectionStatus.CONNECTED -> "Disconnect"
                }
                Text(buttonText)
            }

            // Show more helpful error message text if status is ERROR
            if (connectionStatus == ConnectionStatus.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Connection Failed.\nCheck PC server is running, Wi-Fi is the same, and IP is correct.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // --- Helper Functions ---

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan QR code from ShowerToys")
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setOrientationLocked(false) // Keep unlocked
//            setBeepEnabled(false)
        }
        scanQrCodeLauncher.launch(options)
    }

    // Checks permission, then calls startOrReconnectService
    private fun checkPermissionAndStartOrReconnectService(ipAddress: String) {
        if (ipAddress.isEmpty()) {
            println("Cannot start/reconnect: IP Address is empty.")
            showToast("IP Address is invalid.")
            return
        }

        val actionToSendIntent = { startOrReconnectService(ipAddress) } // Lambda to execute

        // Check for Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> {
                    actionToSendIntent() // Permission OK
                }
                // Optional: Add rationale handling if desired
                // shouldShowRequestPermissionRationale(...) -> { ... }
                else -> {
                    // Request permission directly
                    println("Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            actionToSendIntent() // No permission needed on older versions
        }
    }

    // Helper to actually send the intent to the service
    private fun startOrReconnectService(ipAddress: String) {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_CONNECT // Service handles if it's start or reconnect
            putExtra(ClipboardSyncService.EXTRA_IP_ADDRESS, ipAddress)
        }
        // Use startForegroundService for reliability, especially on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        println("MainActivity: Sent ACTION_CONNECT intent with IP: $ipAddress")
        // Let the service update the connection status via ClipboardState
    }


    // Function to stop the service
    private fun stopSyncService() {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        stopService(serviceIntent)
        println("MainActivity: Sent stopService intent.")
        // The service's onDestroy should update ClipboardState status to DISCONNECTED
    }

    // --- SharedPreferences (Keep for now) ---
    private fun saveServerIp(ipAddress: String) {
        getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)?.edit()?.apply {
            putString("server_ip", ipAddress)
            apply()
        }
    }

    private fun getStoredIp(): String {
        val sharedPref = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        return sharedPref.getString("server_ip", "") ?: ""
    }

    // --- Utility for showing Toast messages ---
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}