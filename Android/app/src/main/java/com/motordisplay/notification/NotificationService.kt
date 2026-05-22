package com.motordisplay.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.motordisplay.AppSingleton

class NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: "Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val appName = sbn.packageName.substringAfterLast('.')

        val category = sbn.notification.category ?: ""
        if (category == "call") {
            AppSingleton.bleManager?.sendCall(appName, title)
        } else {
            if (text.isNotBlank()) {
                AppSingleton.bleManager?.sendNotification(appName, title, text)
            }
        }
    }
}