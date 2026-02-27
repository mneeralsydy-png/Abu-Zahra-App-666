package com.abuzahra.tracker

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChildWebInterface(private val mContext: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    @JavascriptInterface
    fun linkDevice(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. التحقق من الكود
                val doc = db.collection("linking_codes").document(code).get().await()
                if (!doc.exists()) {
                    sendResult("window.onLinkError('الكود غير صحيح')")
                    return@launch
                }

                val parentUid = doc.getString("parent_uid") ?: ""
                val deviceId = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ANDROID_ID)

                // 2. تسجيل دخول مجهول للطفل
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }

                // 3. تسجيل بيانات الجهاز
                val data = mapOf(
                    "device_id" to deviceId,
                    "last_seen" to System.currentTimeMillis(),
                    "battery_level" to 100
                )
                db.collection("parents").document(parentUid)
                    .collection("children").document(deviceId).set(data).await()

                // 4. حذف الكود
                db.collection("linking_codes").document(code).delete().await()

                // 5. حفظ البيانات محلياً
                SharedPrefsManager.saveData(mContext, parentUid, deviceId)
                sendResult("window.onLinkSuccess()")

            } catch (e: Exception) {
                sendResult("window.onLinkError('خطأ: ${e.message}')")
            }
        }
    }

    @JavascriptInterface
    fun requestPermission(type: String) {
        when (type) {
            "accessibility" -> mContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "usage" -> mContext.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "location" -> mContext.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "overlay" -> mContext.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @JavascriptInterface
    fun startServices() {
        (mContext as MainActivity).startWorker()
    }

    private fun sendResult(js: String) {
        CoroutineScope(Dispatchers.Main).launch {
            (mContext as MainActivity).webView.evaluateJavascript(js, null)
        }
    }
}
