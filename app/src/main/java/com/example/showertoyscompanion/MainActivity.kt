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
import androidx.activity.viewModels
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

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                val ip = viewModel.serverIp.value
                if (ip != null) {
                    startSyncService(ip)
                }
            } else {
                println("Notification permission denied.")
            }
        }

    private val scanQrCodeLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            if (result.contents == null) {
                println("QR Scan cancelled.")
            } else {
                try {
                    val ipAddress = result.contents.substringAfter("://").substringBefore(":")
                    if (ipAddress.isNotEmpty()) {
                        viewModel.updateServerIp(ipAddress)
                        saveServerIp(ipAddress)
                        checkPermissionAndStartService(ipAddress)
                    } else {
                        println("Could not parse IP from QR code: ${result.contents}")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing QR code", e)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storedIp = getStoredIp()
        if (storedIp.isNotEmpty()) {
            viewModel.updateServerIp(storedIp)
            checkPermissionAndStartService(storedIp)
        }

        setContent {
            val connectionStatus by viewModel.connectionStatus.collectAsState()
            val serverIp by viewModel.serverIp.collectAsState()

            ShowertoysCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        connectionStatus = connectionStatus,
                        serverIp = serverIp,
                        onScanClick = { launchQrScanner() },
                        onToggleServiceClick = {
                            val ip = serverIp
                            if (ip != null) {
                                if (connectionStatus == ConnectionStatus.CONNECTED) {
                                    stopSyncService()
                                } else {
                                    checkPermissionAndStartService(ip)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

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
            Text("ShowerToys Companion", style = MaterialTheme.typography.headlineSmall)

            Text("Status: ${connectionStatus.name}")
            Text("PC Address: ${serverIp ?: "Not Set"}")

            Button(onClick = onScanClick) {
                Text("Scan PC QR Code")
            }

            Button(
                onClick = onToggleServiceClick,
                enabled = serverIp != null
            ) {
                Text(if (connectionStatus == ConnectionStatus.CONNECTED) "Disconnect" else "Connect")
            }

            if (connectionStatus == ConnectionStatus.ERROR) {
                Text("Error: Connection Failed", color = Color.Red)
            }
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan QR code from ShowerToys")
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setOrientationLocked(false)
        }
        scanQrCodeLauncher.launch(options)
    }

    private fun checkPermissionAndStartService(ipAddress: String) {
        if (ipAddress.isEmpty()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startSyncService(ipAddress)
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
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
            action = ClipboardSyncService.ACTION_CONNECT
            putExtra(ClipboardSyncService.EXTRA_IP_ADDRESS, ipAddress)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopSyncService() {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        stopService(serviceIntent)
    }
}
