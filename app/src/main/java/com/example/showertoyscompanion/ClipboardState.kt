package com.example.showertoyscompanion // Use your actual package name

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple singleton object to hold the application's shared state.
 * For more complex apps, consider Dependency Injection (Hilt/Koin) and Repositories.
 */
object ClipboardState {
    // Holds the current connection status, defaults to DISCONNECTED
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow() // Expose as immutable StateFlow

    // Holds the last known/set server IP address
    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp = _serverIp.asStateFlow() // Expose as immutable StateFlow

    /** Updates the connection status. Called by ClipboardSyncService. */
    fun updateStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
        println("ClipboardState: Status updated to $status")
    }

    /** Updates the server IP. Called by MainViewModel/MainActivity. */
    fun updateServerIp(ip: String?) {
        _serverIp.value = ip
        println("ClipboardState: Server IP updated to $ip")
    }
}