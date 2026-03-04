package com.abuzahra.tracker

import android.content.Context
import android.content.Intent // <--- هذا هو السطر الناقص
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.abuzahra.tracker.services.MainTrackerService
import com.abuzahra.tracker.services.CallRecorderService
import com.abuzahra.tracker.services.DataSyncWorker
import android.os.Build

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
                val data = mapOf("device_id" to deviceId, "last_seen" to System.currentTimeMillis(), "app_name" to "Child Device")

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
        // 1. خدمة التتبع
        val intent = Intent(mContext, MainTrackerService::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mContext.startForegroundService(intent) else mContext.startService(intent)
        
        // 2. خدمة تسجيل المكالمات
        val recIntent = Intent(mContext, CallRecorderService::class.java)
        recIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mContext.startForegroundService(recIntent) else mContext.startService(recIntent)
        
        DataSyncWorker.startImmediate(mContext)
    }

    private fun sendResult(js: String) { 
        CoroutineScope(Dispatchers.Main).launch { (mContext as MainActivity).webView.evaluateJavascript(js, null) } 
    }
}
