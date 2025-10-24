package com.example.showertoyscompanion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.showertoyscompanion.ui.theme.ShowertoysCompanionTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    // --- State Management ---
    // These states will drive the UI and recompose it when they change.
    private var serverIp by mutableStateOf("")
    private var isServiceRunning by mutableStateOf(false) // You'll need a way to check this accurately.
    private var showConnectionError by mutableStateOf(false)


    // --- ActivityResultLaunchers ---

    // Launcher for Notification Permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                println("Notification permission granted.")
                if (serverIp.isNotEmpty()) {
                    startSyncService(serverIp)
                }
            } else {
                println("Notification permission denied.")
                // TODO: Show a message explaining why the permission is crucial for the service.
            }
        }

    // Launcher for QR Code Scanner
    private val scanQrCodeLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            if (result.contents == null) {
                println("QR Scan cancelled.")
            } else {
                println("QR Scan successful: ${result.contents}")
                try {
                    val urlString = result.contents
                    val ipAddress = urlString.substringAfter("://").substringBefore(":")
                    if (ipAddress.isNotEmpty()) {
                        println("Extracted IP: $ipAddress")
                        saveServerIp(ipAddress) // Save the IP
                        serverIp = ipAddress     // Update the UI state
                        checkPermissionAndStartService(ipAddress) // Attempt to start the service with the new IP
                    } else {
                        println("Could not parse IP from QR code: $urlString")
                        showConnectionError = true
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing QR code", e)
                    showConnectionError = true
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On startup, load the stored IP and check permission to start the service.
        val storedIp = getStoredIp()
        if (storedIp.isNotEmpty()) {
            serverIp = storedIp
            checkPermissionAndStartService(storedIp)
        }

        setContent {
            ShowertoysCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass the state variables into the Composable
                    MainScreen(
                        isServiceRunning = isServiceRunning,
                        serverIp = serverIp,
                        showConnectionError = showConnectionError,
                        onScanClick = { launchQrScanner() },
                        onToggleServiceClick = {
                            if (isServiceRunning) {
                                stopSyncService()
                            } else {
                                checkPermissionAndStartService(serverIp)
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
        isServiceRunning: Boolean,
        serverIp: String,
        showConnectionError: Boolean,
        onScanClick: () -> Unit,
        onToggleServiceClick: () -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ShowerToys Companion", style = MaterialTheme.typography.headlineSmall)

            Text("Status: ${if (isServiceRunning) "Service Running" else "Service Stopped"}")
            Text("PC Address: ${if (serverIp.isNotEmpty()) serverIp else "Not Set"}")

            Button(onClick = onScanClick) {
                Text("Scan PC QR Code")
            }

            Button(
                onClick = onToggleServiceClick,
                // Disable start button if no IP is set
                enabled = isServiceRunning || serverIp.isNotEmpty()
            ) {
                Text(if (isServiceRunning) "Stop Sync Service" else "Start Sync Service")
            }

            if (showConnectionError) {
                Text("Error: Invalid QR code or connection failed.", color = Color.Red)
            }
        }
    }

    // --- Logic Functions ---

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan QR code from ShowerToys")
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            // FIX 1: setOrientationLocked(false) allows the scanner to use portrait mode.
            setOrientationLocked(false)
        }
        // FIX 2: Pass the configured 'options' object, not a new empty one.
        scanQrCodeLauncher.launch(options)
    }

    private fun checkPermissionAndStartService(ipAddress: String) {
        if (ipAddress.isEmpty()) {
            println("Cannot start service without a valid IP address.")
            return
        }

        // The foreground service permission is only needed for API 34+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    startSyncService(ipAddress)
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // TODO: Explain to the user why you need the permission.
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No special permission needed on older versions, start service directly.
            startSyncService(ipAddress)
        }
    }

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

    private fun startSyncService(ipAddress: String) {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
            putExtra("SERVER_IP", ipAddress)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        showConnectionError = false // Hide any previous errors on successful start
        println("MainActivity: Attempted to start service with IP: $ipAddress")
    }

    private fun stopSyncService() {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        println("MainActivity: Attempted to stop service.")
    }
}