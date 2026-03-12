package com.niwantha.hutchturbo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TurboService : Service() {
    private lateinit var turboManager: TurboManager
    private val CHANNEL_ID = "TurboServiceChannel"
    private val NOTIFICATION_ID = 1

    companion object {
        var isRunning = false
        var lastOutput = ""
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        turboManager = TurboManager(this) { output ->
            lastOutput = output
            updateNotification(output)
            // Broadcast to activity if it's listening
            val intent = Intent("TurboUpdate")
            intent.putExtra("output", output)
            intent.setPackage(packageName) // Explicit broadcast for reliability
            sendBroadcast(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val interval = intent?.getLongExtra("interval", 1000L) ?: 1000L
        
        val notification = createNotification("Starting Turbo...")
        startForeground(NOTIFICATION_ID, notification)
        
        turboManager.start(interval)
        isRunning = true
        
        return START_NOT_STICKY
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Parse content for high-quality notification UI
        var displayContent = content
        var title = "Hutch Turbo Active"
        
        if (content.contains("Download Speed") || content.contains("Adapter")) {
            val lines = content.lines()
            val adapter = lines.find { it.contains("Adapter") }?.split(":")?.getOrNull(1)?.trim() ?: "Scanning..."
            val dl = lines.find { it.contains("Download Speed") }?.split(":")?.getOrNull(1)?.trim() ?: "0.00 KB/s"
            val ul = lines.find { it.contains("Upload Speed") }?.split(":")?.getOrNull(1)?.trim() ?: "0.00 KB/s"
            
            // Format: "Adapter: WiFi | DL: 100 KB/s | UL: 10 KB/s"
            displayContent = "DL: $dl  •  UL: $ul"
            title = "Turbo Active ($adapter)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(displayContent)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Turbo Booster Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        turboManager.stop()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
