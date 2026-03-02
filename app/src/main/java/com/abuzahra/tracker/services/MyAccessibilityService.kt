package com.abuzahra.tracker.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.abuzahra.tracker.SharedPrefsManager
import com.google.firebase.firestore.FirebaseFirestore

class MyAccessibilityService : AccessibilityService() {

    private val db = FirebaseFirestore.getInstance()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // التحقق من صحة الحدث
        if (event == null) return

        // 1. التحقق مما إذا كان الجهاز مربوطاً (لمنع الانهيار)
        val parentId = SharedPrefsManager.getParentUid(this)
        val deviceId = SharedPrefsManager.getDeviceId(this)

        if (parentId.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
            // الجهاز غير مربوط، لا تقم بأي شيء لتجنب الخطأ
            return
        }

        // 2. عند تغيير النافذة (فتح تطبيق جديد)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            
            if (!packageName.isNullOrEmpty() && packageName != "android") {
                // 3. إرسال اسم التطبيق الحالي إلى Firebase
                updateCurrentApp(parentId, deviceId, packageName)
            }
        }
    }

    private fun updateCurrentApp(parentId: String, deviceId: String, packageName: String) {
        val data = mapOf(
            "current_app" to packageName,
            "last_seen" to System.currentTimeMillis()
        )

        db.collection("parents").document(parentId)
            .collection("children").document(deviceId)
            .update(data)
            .addOnSuccessListener {
                Log.d("AccessibilityService", "Updated active app: $packageName")
            }
            .addOnFailureListener { e ->
                Log.e("AccessibilityService", "Failed to update app", e)
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
