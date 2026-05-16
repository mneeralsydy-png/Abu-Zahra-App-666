package com.abuzahra.tracker

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * StatusWorker - عامل حالة الجهاز
 * يرسل حالة الجهاز (البطارية، الاتصال) إلى Firebase وسيرفر البوت
 */
class StatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "StatusWorker"
    }

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

            // تحديث Firebase
            FirebaseFirestore.getInstance()
                .collection("parents").document(parentUid)
                .collection("children").document(deviceId)
                .update(data).await()

            // إرسال نبض قلب لسيرفر البوت
            try {
                BotServerClient.sendHeartbeat(deviceId, "online", battery)
                SharedPrefsManager.setLastHeartbeat(applicationContext, System.currentTimeMillis())
                Log.d(TAG, "تم إرسال حالة الجهاز: البطارية $battery%")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إرسال نبض القلب: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحديث الحالة: ${e.message}", e)
            Result.retry()
        }
    }
}
