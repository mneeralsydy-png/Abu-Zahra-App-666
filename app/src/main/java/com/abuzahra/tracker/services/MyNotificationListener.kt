package com.abuzahra.tracker.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.firestore.FirebaseFirestore
import com.abuzahra.tracker.SharedPrefsManager

class MyNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val type = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
            "com.facebook.orca" -> "messenger"
            else -> "notification"
        }
        
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        val data = hashMapOf("type" to type, "title" to title, "body" to text, "timestamp" to System.currentTimeMillis())
        val collection = if (type == "notification") "notifications" else "social_logs"
        FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId).collection(collection).add(data)
    }
}
