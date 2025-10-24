package com.example.showertoyscompanion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object ClipboardState {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp = _serverIp.asStateFlow()

    fun updateStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun updateServerIp(ip: String?) {
        _serverIp.value = ip
    }
}
