package com.example.showertoyscompanion

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for MainActivity.
 * Acts as a bridge between the UI (MainActivity) and the shared state (ClipboardState).
 */
class MainViewModel : ViewModel() {

    // Expose the connection status directly from ClipboardState
    val connectionStatus: StateFlow<ConnectionStatus> = ClipboardState.connectionStatus

    // Expose the server IP directly from ClipboardState
    val serverIp: StateFlow<String?> = ClipboardState.serverIp

    /**
     * Called by MainActivity when a new IP is scanned or loaded.
     * Delegates the update to the central ClipboardState.
     */
    fun updateServerIp(ip: String?) { // Allow nullable IP
        ClipboardState.updateServerIp(ip)
    }

    // Note: In a more complex app, the ViewModel might also:
    // - Load the initial IP from SharedPreferences itself.
    // - Hold UI-specific state derived from the connectionStatus/serverIp.
    // - Launch coroutines for specific UI-related tasks.
}