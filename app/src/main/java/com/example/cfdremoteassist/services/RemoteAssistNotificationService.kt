package com.example.cfdremoteassist.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RemoteAssistNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val packageName = it.packageName
            val tickerText = it.notification.tickerText
            val extras = it.notification.extras
            val title = extras.getString("android.title")
            val text = extras.getCharSequence("android.text")
            
            Log.d("NotificationService", "Notification from $packageName: $title - $text")
        }
    }

}