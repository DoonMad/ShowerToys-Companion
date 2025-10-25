package com.example.showertoyscompanion

/**
 * Represents the possible states of the WebSocket connection.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}