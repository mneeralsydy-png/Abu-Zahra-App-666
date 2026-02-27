package com.abuzahra.tracker

import android.content.Context
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class StatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val parentUid = SharedPrefsManager.getParentUid(applicationContext)
            val deviceId = SharedPrefsManager.getDeviceId(applicationContext)
            
            if (parentUid == null || deviceId == null) return Result.failure()

            val battery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val data = mapOf(
                "battery_level" to battery,
                "last_seen" to System.currentTimeMillis()
            )

            FirebaseFirestore.getInstance()
                .collection("parents").document(parentUid)
                .collection("children").document(deviceId)
                .update(data).await()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
