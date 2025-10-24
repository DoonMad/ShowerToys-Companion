package com.example.showertoyscompanion

import android.Manifest // For permissions
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity // Base class for activities using Jetpack Compose
import androidx.activity.compose.setContent // Used for setting Compose UI
import androidx.activity.result.contract.ActivityResultContracts // For requesting permissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.* // For mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.showertoyscompanion.ui.theme.ShowertoysCompanionTheme // Import your theme
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.padding // For Modifier.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract // ZXing scanner contract
import com.journeyapps.barcodescanner.ScanOptions // ZXing scanner options
import android.util.Log // For logging errors
import androidx.compose.runtime.getValue // For compose state delegation
import androidx.compose.runtime.setValue // For compose state delegation
import androidx.compose.runtime.remember
import android.content.Context

class MainActivity : ComponentActivity() {

    // Activity Result Launcher for requesting notification permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                println("Notification permission granted.")
                // --- FIX: Pass the stored IP address ---
                val storedIp = getStoredIp()
                if (storedIp.isNotEmpty()) {
                    startSyncService(storedIp) // Start service *after* getting permission
                } else {
                    println("Permission granted, but no IP stored. Scan QR code.")
                    // TODO: Maybe prompt user to scan
                }
            } else {
                println("Notification permission denied.")
                // TODO: Show a message to the user explaining why the permission is needed
            }
        }

    // State variable to track if the service is running (for UI updates)
    // We'll update this properly later
    private var isServiceRunning by mutableStateOf(false)

    private val scanQrCodeLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            if (result.contents == null) {
                println("QR Scan cancelled.")
                // Maybe show a message to the user
            } else {
                println("QR Scan successful: ${result.contents}")
                // --- Parse the result (EXPECTING URL like http://IP:PORT) ---
                try {
                    val urlString = result.contents
                    // Basic parsing: find IP between "//" and ":"
                    val ipAddress = urlString.substringAfter("://").substringBefore(":")
                    if (ipAddress.isNotEmpty()) {
                        // Store the IP (e.g., in SharedPreferences)
                        saveServerIp(ipAddress)
                        // Update UI state directly (will be updated via LaunchedEffect later)
                        // serverIp = ipAddress // Cannot directly modify compose state here
                        println("Extracted IP: $ipAddress")
                        // If service isn't running, try starting it with the new IP
                        if (!isServiceRunning) {
                            checkPermissionAndStartService(ipAddress)
                        } else {
                            // If service is already running, we might need to tell it to reconnect
                            // TODO: Send Intent to service to update IP and reconnect
                            println("Service already running, need to implement reconnect logic.")
                        }
                    } else {
                        println("Could not parse IP from QR code: $urlString")
                        // TODO: Show error to user
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing QR code", e)
                    println("Error parsing QR code: ${e.message}")
                    // TODO: Show error to user
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permission and start service
        val storedIp = getStoredIp()
        if (storedIp.isNotEmpty()) {
            checkPermissionAndStartService(storedIp)
        }

        // Set the UI content using Jetpack Compose
        setContent {
            // Remember values across recompositions
            var serverIp by remember { mutableStateOf("Not Connected") }
            var showConnectionError by remember { mutableStateOf(false) } // State for error message

            // Update IP when service status changes (Needs proper implementation later)
            // LaunchedEffect(isServiceRunning) { serverIp = if(isServiceRunning) getStoredIp() else "Not Connected" }

            ShowertoysCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp), // Add padding
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), // Add spacing
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ShowerToys Companion", style = MaterialTheme.typography.headlineSmall) // Larger title

                        // Display Connection Status
                        Text("Status: ${if (isServiceRunning) "Service Running" else "Service Stopped"}")
                        Text("PC Address: $serverIp") // Display the IP

                        // Scan Button
                        Button(onClick = {
                            // Launch the QR code scanner
                            scanQrCodeLauncher.launch(ScanOptions())
                        }) {
                            Text("Scan PC QR Code")
                        }

                        // Start/Stop Button (for testing)
                        Button(onClick = {
                            if (isServiceRunning) stopSyncService() else checkPermissionAndStartService(serverIp) // Pass IP if starting
                        }) {
                            Text(if (isServiceRunning) "Stop Sync Service" else "Start Sync Service")
                        }

                        // Display error message if connection failed
                        if (showConnectionError) {
                            Text("Error: Could not connect. Check IP/Firewall.", color = Color.Red)
                        }
                    }
                }
            }
        }
    }

    // --- Service Control Functions ---

    private fun checkPermissionAndStartService(ipAddress: String) { // Accept IP
        if (ipAddress.isEmpty() || ipAddress == "Not Connected") {
            println("Cannot start service without a valid IP address.")
            // TODO: Maybe prompt user to scan QR code
            return
        }else {
            // No permission needed on older versions, start service directly
            startSyncService(ipAddress)
        }
    }

    private fun saveServerIp(ipAddress: String) {
        val sharedPref = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("server_ip", ipAddress)
            apply()
        }
        // Manually update the compose state (crude, better with ViewModel)
        setContent { /* Recompose UI - This is inefficient, fix later */ }
    }

    private fun getStoredIp(): String {
        val sharedPref = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        return sharedPref.getString("server_ip", "") ?: ""
    }

    // Starts the ClipboardSyncService
    private fun startSyncService(ipAddress: String) { // Accept IP
        val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
            // --- Pass IP address to the service ---
            putExtra("SERVER_IP", ipAddress)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        println("MainActivity: Attempted to start service with IP: $ipAddress")
    }

    // Stops the ClipboardSyncService
    private fun stopSyncService() {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false // Update UI state (basic)
        println("MainActivity: Attempted to stop service.")
    }
}
