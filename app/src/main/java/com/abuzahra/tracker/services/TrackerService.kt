package com.abuzahra.tracker.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.abuzahra.tracker.SharedPrefsManager
import android.graphics.Color

class TrackerService : Service() {
    private val CHANNEL_ID = "tracker_channel"
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tracker::Lock")
        wakeLock?.acquire(10*60*1000L)
        startLocationUpdates()
        startBatteryMonitor()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 15000; fastestInterval = 10000; priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { sendToFirebase(it) } }
            }, null)
    }

    private fun startBatteryMonitor() {
        Thread { while (true) { updateBattery(); Thread.sleep(60000) } }.start()
    }

    private fun updateBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId)
            .update("battery_level", level, "is_online", true)
    }

    private fun sendToFirebase(location: Location) {
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        val data = mapOf("location" to mapOf("lat" to location.latitude, "lng" to location.longitude), "last_seen" to System.currentTimeMillis())
        FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId).update(data)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tracker Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Active").setContentText("Protecting device...").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { wakeLock?.release(); super.onDestroy() }
}
