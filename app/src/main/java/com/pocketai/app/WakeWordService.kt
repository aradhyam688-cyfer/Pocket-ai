package com.pocketai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class WakeWordService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chId = "pocketai_wake"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "PocketAI Wake Listener", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, chId)
            .setContentTitle("PocketAI Wake Mode")
            .setContentText("Listening for \"Ultron\"...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
