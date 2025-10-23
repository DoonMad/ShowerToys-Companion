package com.example.showertoyscompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import android.content.* // For ContextCompat and ClipboardManager
import android.os.Build
import androidx.core.content.ContextCompat // For getting system services compatibly

class ClipboardSyncService : Service() {

    // Unique ID for the foreground service notification
    private val notificationId = 1

    // Unique ID for the notification channel (required for Android 8+)
    private val channelId = "ClipboardSyncChannel"

    // Coroutine scope for managing background tasks within the service
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // OkHttp client for making network requests
    private lateinit var okHttpClient: OkHttpClient

    // The active WebSocket connection (nullable, as it might not be connected)
    private var webSocket: WebSocket? = null

    // WebSocket Listener implementation
    private val webSocketListener =
        object : WebSocketListener() {
            // Called when the WebSocket connection is successfully opened
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                this@ClipboardSyncService.webSocket = webSocket // Store the active connection
                println("WebSocket: Connection Opened")
                // TODO: Maybe send a "hello" message or request initial clipboard?
            }

            // Called when a text message is received from the server (PC clipboard update)
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                println("WebSocket: Message Received: $text")

                val clipboard = ContextCompat.getSystemService(
                    this@ClipboardSyncService, // Need the service context
                    ClipboardManager::class.java
                )

                // Create a "ClipData" object containing the text
                // The label ("PC Sync") is for user reference, not functional
                val clip = ClipData.newPlainText("PC Sync", text)

                // Set the clipboard content
                clipboard?.setPrimaryClip(clip)
                println("ClipboardSyncService: Updated phone clipboard.")
            }

            // Called when a binary message is received (we don't expect these)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                println("WebSocket: Binary Message Received (Ignoring)")
            }

            // Called when the WebSocket is closing gracefully
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                println("WebSocket: Closing: $code / $reason")
                this@ClipboardSyncService.webSocket = null // Clear the connection reference
            }

            // Called when the connection fails (network error, server down, etc.)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                println("WebSocket: Failure: ${t.message}")
                this@ClipboardSyncService.webSocket = null // Clear the connection reference
                // TODO: Implement reconnection logic (e.g., try again after a delay)
            }
        }

    // Called when the service is first created
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel() // Set up the notification channel
        startForeground(notificationId, createNotification()) // Start as foreground

        okHttpClient =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS) // Send pings to keep connection alive
                .build()

        // --- Start Connection Attempt ---
        connectWebSocket() // Call our new function

        println("ClipboardSyncService: Service Created and started in foreground.")
    }

    // Called when the service is started (e.g., by the main activity)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We want the service to continue running until it is explicitly stopped
        // START_STICKY tells the system to restart the service if it gets killed
        return START_STICKY
    }

    // Required method for bound services, but we're not using binding.
    // Return null as we don't intend clients to bind to this service directly.
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Called when the service is being destroyed
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Cancel all coroutines started by this service

        webSocket?.close(1000, "Service shutting down") // 1000 is the code for normal closure
        okHttpClient.dispatcher.executorService.shutdown()

        println("ClipboardSyncService: Service Destroyed.")
    }



    private fun connectWebSocket() {
        if (webSocket != null) {
            println("WebSocket: Already connected or connecting.")
            return
        }

        // Construct the WebSocket URL (ws:// for non-secure)
        val serverUrl = "ws://$serverIpAddress:$serverPort"
        println("WebSocket: Attempting to connect to $serverUrl")

        // Build the request
        val request = Request.Builder().url(serverUrl).build()

        // Tell OkHttp to create a new WebSocket with our request and listener
        // OkHttp handles the connection attempt in the background automatically
        okHttpClient.newWebSocket(request, webSocketListener)
    }

    // --- Notification Helper Functions ---

    // Creates the notification channel required on Android 8+
    private fun createNotificationChannel() {
        // NotificationChannel is only available on API 26+
        val serviceChannel =
            NotificationChannel(
                channelId,
                "Clipboard Sync Service Channel", // Name visible in system settings
                NotificationManager.IMPORTANCE_DEFAULT // Low importance prevents sound/vibration
            )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    // Creates the persistent notification for the foreground service
    private fun createNotification(): Notification {
        // Intent to launch MainActivity when the notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE // Required for Android 12+
            )

        // Build the notification
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ShowerToys Sync Active")
            .setContentText("Clipboard sync service is running.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use default app icon for now
            .setContentIntent(pendingIntent) // Action when tapped
            .setOngoing(true) // Makes the notification persistent
            .build()
    }

    companion object {
        // IP address and port of the PC server (needs to be configurable later)
        private const val serverIpAddress =
            "192.168.1.10" // Placeholder - GET THIS FROM QR CODE LATER
        private const val serverPort = 8081 // Use the same port as the C++ server (8081)
    }
}
