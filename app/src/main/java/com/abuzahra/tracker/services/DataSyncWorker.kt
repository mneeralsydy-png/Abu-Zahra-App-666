package com.abuzahra.tracker.services

import com.abuzahra.tracker.SharedPrefsManager // <--- هذا السطر مهم جداً
// ... باقي الاستيرادات
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class DataSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            // استخدام applicationContext لتجنب مشاكل الـ Context
            val parentId = SharedPrefsManager.getParentUid(applicationContext) ?: return Result.failure()
            val deviceId = SharedPrefsManager.getDeviceId(applicationContext) ?: return Result.failure()

            // 1. رفع البطارية
            val battery = getBatteryLevel()
            
            // 2. رفع الموقع
            val locationMap = getLastLocation()
            
            // 3. رفع السجلات
            val calls = getCallLogs()
            val sms = getSmsLogs()

            // تحديث البيانات في Firestore
            val data = hashMapOf<String, Any>(
                "battery_level" to battery,
                "last_seen" to System.currentTimeMillis(),
                "is_online" to true
            )
            
            if (locationMap != null) {
                data["location"] = locationMap
            }

            val db = FirebaseFirestore.getInstance()
            val deviceRef = db.collection("parents").document(parentId).collection("children").document(deviceId)
            
            // تحديث البيانات الأساسية
            deviceRef.update(data).await()
            
            // إضافة السجلات
            calls.forEach { call -> deviceRef.collection("calls").add(call) }
            sms.forEach { s -> deviceRef.collection("sms").add(s) }

            Result.success()
        } catch (e: Exception) {
            Log.e("DataSyncWorker", "Error syncing data", e)
            Result.retry()
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private suspend fun getLastLocation(): Map<String, Double>? {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val client = LocationServices.getFusedLocationProviderClient(applicationContext)
            val loc: Location? = client.lastLocation.await()
            if (loc != null) {
                return mapOf("lat" to loc.latitude, "lng" to loc.longitude)
            }
        }
        return null
    }

    private fun getCallLogs(): List<Map<String, Any>> {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        
        val calls = mutableListOf<Map<String, Any>>()
        val cursor = applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, "${CallLog.Calls.DATE} DESC LIMIT 20"
        )
        
        cursor?.use {
            val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

            while (it.moveToNext()) {
                calls.add(mapOf(
                    "phone" to it.getString(numberIndex),
                    "type" to it.getInt(typeIndex),
                    "timestamp" to it.getLong(dateIndex),
                    "duration" to it.getString(durationIndex)
                ))
            }
        }
        return calls
    }

    private fun getSmsLogs(): List<Map<String, Any>> {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        
        val msgs = mutableListOf<Map<String, Any>>()
        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null, null, null, "${Telephony.Sms.DATE} DESC LIMIT 20"
        )

        cursor?.use {
            val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
            val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                msgs.add(mapOf(
                    "phone" to it.getString(addressIndex),
                    "body" to it.getString(bodyIndex),
                    "timestamp" to it.getLong(dateIndex),
                    "type" to it.getInt(typeIndex)
                ))
            }
        }
        return msgs
    }

    companion object {
        fun start(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "data_sync_work",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
