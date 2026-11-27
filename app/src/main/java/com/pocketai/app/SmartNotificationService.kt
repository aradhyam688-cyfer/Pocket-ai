package com.pocketai.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class CapturedNotification(val app: String, val title: String, val text: String, val category: String, val time: Long)

class SmartNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { instance = null; super.onListenerDisconnected() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == "com.pocketai.app" || pkg == "android" || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val cat = when {
            pkg.contains("whatsapp") || pkg.contains("messaging") || pkg.contains("telegram") -> "Chats"
            (title + " " + text).contains(Regex("(?i)otp|urgent|alert|bank|payment|missed call")) -> "Urgent"
            else -> "Updates"
        }
        synchronized(recent) {
            recent.add(0, CapturedNotification(pkg.substringAfterLast("."), title, text, cat, sbn.postTime))
            if (recent.size > 30) recent.removeAt(recent.lastIndex)
        }
    }

    companion object {
        @Volatile var instance: SmartNotificationService? = null
        val recent = mutableListOf<CapturedNotification>()
        fun snapshot(): List<CapturedNotification> = synchronized(recent) { recent.toList() }
    }
}
