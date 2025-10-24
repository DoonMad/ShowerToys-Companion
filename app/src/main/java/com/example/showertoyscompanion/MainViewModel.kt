package com.example.showertoyscompanion

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {

    // Expose the connection status from the central state holder
    val connectionStatus: StateFlow<ConnectionStatus> = ClipboardState.connectionStatus

    // Expose the server IP from the central state holder
    val serverIp: StateFlow<String?> = ClipboardState.serverIp

    // Function to update the server IP (delegates to the central state holder)
    fun updateServerIp(ip: String) {
        ClipboardState.updateServerIp(ip)
    }
}
