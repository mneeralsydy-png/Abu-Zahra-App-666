package com.abuzahra.tracker

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * StatusWorker - عامل حالة الجهاز
 * الإصدار الجديد: يعمل محلياً فقط بدون Firebase
 */
class StatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "StatusWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val deviceId = SharedPrefsManager.getDeviceId(applicationContext)
            if (deviceId == null) return Result.failure()

            val battery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            SharedPrefsManager.setLastHeartbeat(applicationContext, System.currentTimeMillis())
            Log.d(TAG, "حالة الجهاز: البطارية $battery%")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحديث الحالة: ${e.message}", e)
            Result.retry()
        }
    }
}
