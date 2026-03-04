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
import androidx.core.app.NotificationCompat
import com.abuzahra.tracker.MainActivity
import com.abuzahra.tracker.R
import com.abuzahra.tracker.SharedPrefsManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainTrackerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        acquireWakeLock()
        startLocationUpdates()
        startBatteryMonitor()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "tracker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "System Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        
        // استخدام أيقونة النظام لتجنب الخطأ
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Protection Active")
            .setContentText("Service is running in background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) // <--- مصحح
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateLocationInFirebase(it) }
            }
        }
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun updateLocationInFirebase(location: Location) {
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        val data = mapOf("location" to mapOf("lat" to location.latitude, "lng" to location.longitude), "last_seen" to System.currentTimeMillis())
        FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId).update(data)
    }

    private fun startBatteryMonitor() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val parentId = SharedPrefsManager.getParentUid(this@MainTrackerService) ?: return@launch
                val deviceId = SharedPrefsManager.getDeviceId(this@MainTrackerService) ?: return@launch
                val data = mapOf("battery_level" to batteryLevel, "is_online" to true)
                FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId).update(data)
                DataSyncWorker.startImmediate(this@MainTrackerService)
                delay(60000)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChildApp::TrackerWakeLock")
        wakeLock?.acquire(10*60*1000L)
    }
    
    override fun onDestroy() { wakeLock?.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
