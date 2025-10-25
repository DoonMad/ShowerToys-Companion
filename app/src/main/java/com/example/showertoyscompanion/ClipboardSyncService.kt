package com.example.showertoyscompanion // Use your actual package name

// Android Core Imports
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.* // For ContextCompat, ClipboardManager, ClipData
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// Coroutines
import kotlinx.coroutines.* // Import coroutines base
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// OkHttp (Networking)
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class ClipboardSyncService : Service() {

    private val notificationId = 1
    private val channelId = "ClipboardSyncChannel"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job()) // Scope for background tasks

    // Networking
    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null

    // Reconnection Logic
    private var reconnectionJob: Job? = null // Holds the coroutine job for retrying
    private val RECONNECT_DELAY_MS = 5000L // Delay before retrying (5 seconds)

    // Clipboard Manager (needed for writing)
    private lateinit var clipboardManager: ClipboardManager

    // --- Phone-to-PC functionality is disabled due to Android 10+ background restrictions ---
    /*
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handlePhoneClipboardChange()
    }
    */

    // --- WebSocket Listener ---
    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            this@ClipboardSyncService.webSocket = webSocket
            ClipboardState.updateStatus(ConnectionStatus.CONNECTED) // Update global state
            reconnectionJob?.cancel() // Cancel any pending reconnect attempts on success
            println("WebSocket: Connection Opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)
            println("WebSocket: Message Received: $text")

            // Prevent feedback loops: only set if text is different
            if (clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() != text) {
                val clip = ClipData.newPlainText("PC Sync", text)
                try {
                    clipboardManager.setPrimaryClip(clip)
                    println("ClipboardSyncService: Updated phone clipboard.")
                } catch (e: Exception) {
                    // Catch potential SecurityExceptions, although foreground service *should* allow writes
                    println("ClipboardSyncService: Error setting clipboard: ${e.message}")
                }
            } else {
                println("ClipboardSyncService: Received text matches current clipboard, ignoring.")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            super.onMessage(webSocket, bytes)
            println("WebSocket: Binary Message Received (Ignoring)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosing(webSocket, code, reason)
            println("WebSocket: Closing: $code / $reason")
            this@ClipboardSyncService.webSocket = null
            ClipboardState.updateStatus(ConnectionStatus.DISCONNECTED)
            // Schedule reconnect if closure wasn't intentional (e.g., service stopping)
            if (code != 1000 && code != 1001) { // 1000=Normal, 1001=Going Away
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            super.onFailure(webSocket, t, response)
            println("WebSocket: Failure: ${t.message}")
            this@ClipboardSyncService.webSocket = null
            ClipboardState.updateStatus(ConnectionStatus.ERROR)
            // Schedule reconnect on failure
            scheduleReconnect()
        }
    } // End of webSocketListener

    // --- Service Lifecycle Methods ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, createNotification()) // Promote to foreground

        // Initialize OkHttp Client
        okHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive
            .build()

        // Get ClipboardManager
        clipboardManager = ContextCompat.getSystemService(this, ClipboardManager::class.java)
            ?: throw IllegalStateException("ClipboardManager not available.")

        // Disabling listener due to background restrictions
        // clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        println("ClipboardSyncService: Service Created.")
        // Note: Connection attempt moved to onStartCommand to ensure IP is available
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("ClipboardSyncService: onStartCommand received.")

        // Handle specific action (e.g., connect/reconnect with a new IP)
        if (intent?.action == ACTION_CONNECT) {
            val newIp = intent.getStringExtra(EXTRA_IP_ADDRESS)
            if (!newIp.isNullOrEmpty()) {
                println("Received CONNECT action with IP: $newIp")
                ClipboardState.updateServerIp(newIp) // Update global IP state
                // Trigger connection logic (will close old if needed)
                connectWebSocket()
            } else {
                println("CONNECT action received without IP.")
            }
        } else if (webSocket == null && !ClipboardState.serverIp.value.isNullOrEmpty()) {
            // If service restarted or started without specific action, try connecting if we have an IP
            println("Service started/restarted, attempting connection with stored IP.")
            connectWebSocket()
        }

        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not using binding
    }

    override fun onDestroy() {
        super.onDestroy()
        println("ClipboardSyncService: Service Destroying...")
        reconnectionJob?.cancel() // Cancel any pending reconnect attempts
        serviceScope.cancel() // Cancel all service coroutines
        webSocket?.close(1000, "Service shutting down") // Close WebSocket
        // Note: OkHttp dispatcher shutdown might happen automatically or could be added back if needed
        // okHttpClient.dispatcher.executorService.shutdown()

        // Disabling listener due to background restrictions
        // clipboardManager.removePrimaryClipChangedListener(clipboardListener)

        ClipboardState.updateStatus(ConnectionStatus.DISCONNECTED) // Update global state
        println("ClipboardSyncService: Service Destroyed.")
    }

    // --- Phone-to-PC functionality is disabled ---
    /*
    private fun handlePhoneClipboardChange() { ... }
    */

    // --- Connection Logic ---

    /**
     * Attempts to establish a WebSocket connection.
     * Closes any existing connection and cancels pending reconnects first.
     */
    private fun connectWebSocket() {
        // Cancel any previous/pending automatic reconnect attempts
        reconnectionJob?.cancel()

        // Close existing socket if it exists
        if (webSocket != null) {
            println("WebSocket: Closing existing connection before new attempt.")
            webSocket?.close(1001, "New connection requested") // 1001 = Going Away
            webSocket = null
        }

        // Get the current IP from the shared state
        val serverIp = ClipboardState.serverIp.value
        if (serverIp.isNullOrEmpty()) {
            println("WebSocket: Connection failed, no IP address set.")
            ClipboardState.updateStatus(ConnectionStatus.ERROR) // Update state
            // Optionally schedule a retry even if IP is missing, in case it gets set later?
            // scheduleReconnect()
            return
        }

        val serverUrl = "ws://$serverIp:$SERVER_PORT"
        println("WebSocket: Attempting to connect to $serverUrl")
        ClipboardState.updateStatus(ConnectionStatus.CONNECTING) // Update state

        val request = Request.Builder().url(serverUrl).build()
        // OkHttp initiates connection attempt asynchronously
        okHttpClient.newWebSocket(request, webSocketListener)
    }

    // --- Reconnection Logic ---

    /**
     * Schedules a reconnection attempt after a delay.
     * Ensures only one reconnection attempt is scheduled at a time.
     */
    private fun scheduleReconnect() {
        // Only schedule if a reconnect isn't already in progress/scheduled
        if (reconnectionJob?.isActive == true) {
            println("WebSocket: Reconnect already scheduled.")
            return
        }
        // Don't try to reconnect if IP is missing
        if (ClipboardState.serverIp.value.isNullOrEmpty()) {
            println("WebSocket: Cannot schedule reconnect, IP address is missing.")
            ClipboardState.updateStatus(ConnectionStatus.ERROR)
            return
        }

        reconnectionJob = serviceScope.launch {
            try {
                println("WebSocket: Scheduling reconnect in ${RECONNECT_DELAY_MS / 1000} seconds...")
                ClipboardState.updateStatus(ConnectionStatus.CONNECTING) // Show "connecting" during wait
                delay(RECONNECT_DELAY_MS) // Wait
                println("WebSocket: Reconnect delay finished, attempting connection...")
                connectWebSocket() // Try connecting again
            } catch (e: CancellationException) {
                println("WebSocket: Reconnection cancelled.")
                // Expected if service stops or a new connection is manually requested
            } catch (e: Exception) {
                println("WebSocket: Error during reconnection scheduling: ${e.message}")
                ClipboardState.updateStatus(ConnectionStatus.ERROR)
                // Optionally schedule another retry here? Be careful of loops.
            }
        }
    }


    // --- Notification Helpers --- (createNotificationChannel, createNotification remain the same)
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // O = API 26
            val serviceChannel = NotificationChannel(
                channelId,
                "Clipboard Sync Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration by default
            ).apply {
                description = "Channel for ShowerToys background sync service" // Add description
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)


        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ShowerToys Sync Active")
            .setContentText("Clipboard sync from PC is active.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this drawable exists
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true) // Make notification less intrusive if desired
            .build()
    }


    // --- Companion Object --- (Constants for Intent Actions/Extras)
    companion object {
        const val ACTION_CONNECT = "com.example.showertoyscompanion.CONNECT"
        const val EXTRA_IP_ADDRESS = "com.example.showertoyscompanion.IP_ADDRESS"
        private const val SERVER_PORT = 8081 // WebSocket Port
    }
}

// --- Add ConnectionStatus enum and ClipboardState object (usually in a separate file) ---
// If not already defined elsewhere (e.g., ClipboardState.kt)

/*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// Simple singleton object to hold shared state (replace with ViewModel/DI later for robustness)
object ClipboardState {
    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp = _serverIp.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status = _status.asStateFlow()

    fun updateServerIp(ip: String?) {
        _serverIp.value = ip
    }

    fun updateStatus(newStatus: ConnectionStatus) {
        _status.value = newStatus
    }
}
*/