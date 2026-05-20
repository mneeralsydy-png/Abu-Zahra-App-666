package com.abuzahra.tracker

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * RootDetector - كشف صلاحيات Root على الجهاز
 * 
 * يدعم 3 مستويات:
 * - Level 1: بدون Root (أوامر عادية تعمل جزئياً)
 * - Level 2: مع Root (جميع الأوامر تعمل)
 * - Level 3: كشف تلقائي
 */
object RootDetector {

    private const val TAG = "RootDetector"

    enum class RootLevel(val level: Int, val description: String) {
        NO_ROOT(0, "بدون Root - أوامر محدودة"),
        HAS_ROOT(1, "مع Root - جميع الأوامر متاحة"),
        UNKNOWN(-1, "غير محدد")
    }

    private var cachedResult: RootLevel? = null
    private var checkTime: Long = 0
    private const val CACHE_DURATION = 30_000L // 30 seconds cache

    /**
     * فحص if الجهاز has root access
     * Uses multiple detection methods for reliability
     */
    fun isRooted(): Boolean {
        // Check cache first
        val now = System.currentTimeMillis()
        cachedResult?.let {
            if (now - checkTime < CACHE_DURATION) {
                return it == RootLevel.HAS_ROOT
            }
        }

        val result = checkRootMethods()
        cachedResult = result
        checkTime = now
        return result == RootLevel.HAS_ROOT
    }

    /**
     * Get root level
     */
    fun getRootLevel(): RootLevel {
        return if (isRooted()) RootLevel.HAS_ROOT else RootLevel.NO_ROOT
    }

    /**
     * Get root status info as formatted string
     */
    fun getRootStatusInfo(): String {
        val level = getRootLevel()
        val methods = mutableListOf<String>()

        // Method 1: Check su binary
        if (checkSuBinary()) methods.add("✅ su binary")
        else methods.add("❌ su binary")

        // Method 2: Try executing su -c id
        if (checkSuExecution()) methods.add("✅ su execution")
        else methods.add("❌ su execution")

        // Method 3: Check common root paths
        if (checkRootPaths()) methods.add("✅ Root paths")
        else methods.add("❌ Root paths")

        // Method 4: Check Magisk
        if (checkMagisk()) methods.add("✅ Magisk detected")
        else methods.add("❌ Magisk")

        return """
            🧪 <b>حالة Root</b>
            
            📊 المستوى: ${level.description}
            
            🔍 طرق الفحص:
            ${methods.joinToString("\n")}
            
            ${if (level == RootLevel.HAS_ROOT) "✅ جميع الأوامر المتقدمة متاحة" else "⚠️ بعض الأوامر تحتاج Root - اتبع دليل Magisk"}
        """.trimIndent()
    }

    /**
     * Execute a command with root privileges
     * @return Pair(success, output)
     */
    fun executeAsRoot(command: String): Pair<Boolean, String> {
        return try {
            if (!isRooted()) {
                return Pair(false, "الجهاز ليس لديه Root")
            }

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = StringBuilder()
            val errorOutput = StringBuilder()

            // Read output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            reader.close()

            // Read error
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.appendLine(line)
            }
            errorReader.close()

            process.waitFor()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                Log.d(TAG, "Root command succeeded: $command")
                Pair(true, output.toString().trim())
            } else {
                Log.w(TAG, "Root command failed (exit=$exitCode): $errorOutput")
                Pair(false, errorOutput.toString().trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root execution error: ${e.message}")
            Pair(false, "خطأ: ${e.message}")
        }
    }

    /**
     * Execute command without root (normal permissions)
     */
    fun executeNormal(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            reader.close()

            process.waitFor()
            Pair(process.exitValue() == 0, output.toString().trim())
        } catch (e: Exception) {
            Pair(false, "خطأ: ${e.message}")
        }
    }

    // ==================== Detection Methods ====================

    private fun checkRootMethods(): RootLevel {
        val checks = listOf(
            checkSuBinary(),
            checkSuExecution(),
            checkRootPaths(),
            checkMagisk()
        )
        return if (checks.any { it }) RootLevel.HAS_ROOT else RootLevel.NO_ROOT
    }

    private fun checkSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkSuExecution(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            reader.close()
            process.waitFor()
            output != null && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkRootPaths(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk",
            "/system/etc/init.d",
            "/system/bin/.ext/.su",
            "/data/data/com.noshufou.android.su",
            "/data/data/com.koushikdutta.superuser",
            "/data/data/eu.chainfire.supersu",
            "/data/data/com.topjohnwu.magisk",
            "/sbin/.magisk",
            "/cache/.disable_magisk"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkMagisk(): Boolean {
        return File("/data/data/com.topjohnwu.magisk").exists() ||
                File("/sbin/.magisk").exists() ||
                File("/system/etc/init.d").exists()
    }

    /**
     * Get device info including root status as JSON string
     */
    fun getRootInfoJson(): String {
        val level = getRootLevel()
        val hasMagisk = checkMagisk()
        val hasSu = checkSuBinary()

        return """{
            "ok": true,
            "root": ${level == RootLevel.HAS_ROOT},
            "root_level": ${level.level},
            "root_description": "${level.description}",
            "has_magisk": $hasMagisk,
            "has_su": $hasSu,
            "android": "${Build.VERSION.RELEASE}",
            "sdk": ${Build.VERSION.SDK_INT},
            "device": "${Build.DEVICE}",
            "model": "${Build.MODEL}"
        }""".replace("\n", "")
    }
}
