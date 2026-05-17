package com.abuzahra.tracker.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.abuzahra.tracker.LocalStorageManager
import com.abuzahra.tracker.SharedPrefsManager

class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val deviceId = SharedPrefsManager.getDeviceId(this)
        if (deviceId.isNullOrEmpty()) return

        // عند تغيير النافذة (فتح تطبيق جديد)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()

            if (!packageName.isNullOrEmpty() && packageName != "android") {
                try {
                    // حفظ التطبيق النشط محلياً فقط (بدون Firebase)
                    val appData = mapOf(
                        "type" to "app_usage",
                        "package" to packageName,
                        "timestamp" to System.currentTimeMillis()
                    )
                    LocalStorageManager.storeData(this, "active_app", appData)
                    Log.d("AccessibilityService", "التطبيق النشط: $packageName")
                } catch (e: Exception) {
                    Log.e("AccessibilityService", "خطأ: ${e.message}")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d("AccessibilityService", "Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AccessibilityService", "Service Connected Successfully")
    }
}
