package com.abuzahra.tracker.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.abuzahra.tracker.SharedPrefsManager
import android.os.Bundle

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val messageBody = if (bigText.isNotEmpty()) bigText else text

        // ignore empty
        if (messageBody.isEmpty() && title.isEmpty()) return

        // تحديد النوع
        val type = when (packageName) {
            "com.whatsapp" -> "whatsapp"
            "com.facebook.orca" -> "messenger"
            "com.instagram.android" -> "instagram"
            else -> "notification"
        }

        // إرسال لل Firebase
        sendToFirebase(type, packageName, title, messageBody)
    }

    private fun sendToFirebase(type: String, packageName: String, title: String, body: String) {
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        
        val data = hashMapOf(
            "type" to type,
            "app_name" to getAppName(packageName),
            "title" to title,
            "body" to body,
            "timestamp" to System.currentTimeMillis()
        )

        // نختار المجموعة بناءً على النوع
        val collectionName = if (type == "notification") "notifications" else "social_logs"

        FirebaseFirestore.getInstance()
            .collection("parents").document(parentId)
            .collection("children").document(deviceId)
            .collection(collectionName).add(data)
            .addOnFailureListener { Log.e("NotifListener", "Failed to save", it) }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
            "com.facebook.orca" -> "Messenger"
            "com.instagram.android" -> "Instagram"
            else -> packageName
        }
    }
}
