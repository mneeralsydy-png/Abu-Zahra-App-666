package com.abuzahra.tracker.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.abuzahra.tracker.SharedPrefsManager
import kotlinx.coroutines.tasks.await

class DataSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val parentId = SharedPrefsManager.getParentUid(applicationContext) ?: return Result.failure()
        val deviceId = SharedPrefsManager.getDeviceId(applicationContext) ?: return Result.failure()
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection("parents").document(parentId).collection("children").document(deviceId)

        // Calls
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            val cursor = applicationContext.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC LIMIT 20")
            cursor?.use {
                while (it.moveToNext()) {
                    val call = mapOf(
                        "phone" to it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                        "type" to it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE)),
                        "timestamp" to it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    )
                    ref.collection("calls").add(call).await()
                }
            }
        }
        // SMS
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            val cursor = applicationContext.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} DESC LIMIT 20")
            cursor?.use {
                while (it.moveToNext()) {
                    val sms = mapOf(
                        "phone" to it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)),
                        "body" to it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)),
                        "timestamp" to it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    )
                    ref.collection("sms").add(sms).await()
                }
            }
        }
        return Result.success()
    }
    companion object {
        fun startImmediate(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<DataSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("data_sync_immediate", ExistingWorkPolicy.REPLACE, workRequest)
        }
    }
}
