package com.example.showertoyscompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class ClipboardSyncService : Service() {

    private val notificationId = 1
    private val channelId = "ClipboardSyncChannel"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null
    private lateinit var clipboardManager: ClipboardManager

    // --- Phone-to-PC functionality is disabled due to Android 10+ background restrictions ---
    /*
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handlePhoneClipboardChange()
    }
    */

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            this@ClipboardSyncService.webSocket = webSocket
            ClipboardState.updateStatus(ConnectionStatus.CONNECTED)
            println("WebSocket: Connection Opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)
            println("WebSocket: Message Received: $text")

            // To prevent feedback loops, only set the clip if it's different from the current one.
            if (clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() != text) {
                val clip = ClipData.newPlainText("PC Sync", text)
                clipboardManager.setPrimaryClip(clip)
                println("ClipboardSyncService: Updated phone clipboard.")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosing(webSocket, code, reason)
            this@ClipboardSyncService.webSocket = null
            ClipboardState.updateStatus(ConnectionStatus.DISCONNECTED)
            println("WebSocket: Closing: $code / $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            super.onFailure(webSocket, t, response)
            this@ClipboardSyncService.webSocket = null
            ClipboardState.updateStatus(ConnectionStatus.ERROR)
            println("WebSocket: Failure: ${t.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, createNotification())

        okHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Disabling listener due to background restrictions
        // clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        println("ClipboardSyncService: Service Created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT) {
            val newIp = intent.getStringExtra(EXTRA_IP_ADDRESS)
            if (newIp != null) {
                ClipboardState.updateServerIp(newIp)
                connectWebSocket()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(1000, "Service shutting down")
        okHttpClient.dispatcher.executorService.shutdown()
        // Disabling listener due to background restrictions
        // clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        ClipboardState.updateStatus(ConnectionStatus.DISCONNECTED)
        println("ClipboardSyncService: Service Destroyed.")
    }

    // --- Phone-to-PC functionality is disabled due to Android 10+ background restrictions ---
    /*
    private fun handlePhoneClipboardChange() {
        if (clipboardManager.hasPrimaryClip() && clipboardManager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && webSocket != null) {
                    println("ClipboardSyncService: Phone clipboard changed, sending to PC: $text")
                    webSocket?.send(text)
                }
            }
        }
    }
    */

    private fun connectWebSocket() {
        webSocket?.close(1001, "New connection requested")
        webSocket = null

        val serverIp = ClipboardState.serverIp.value
        if (serverIp == null) {
            println("WebSocket: Connection failed, no IP address set.")
            ClipboardState.updateStatus(ConnectionStatus.ERROR)
            return
        }

        val serverUrl = "ws://$serverIp:$SERVER_PORT"
        println("WebSocket: Attempting to connect to $serverUrl")
        ClipboardState.updateStatus(ConnectionStatus.CONNECTING)

        val request = Request.Builder().url(serverUrl).build()
        okHttpClient.newWebSocket(request, webSocketListener)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            "Clipboard Sync Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ShowerToys Sync Active")
            .setContentText("Clipboard sync from PC is active.") // Updated text
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.showertoyscompanion.CONNECT"
        const val EXTRA_IP_ADDRESS = "com.example.showertoyscompanion.IP_ADDRESS"
        private const val SERVER_PORT = 8081
    }
}
