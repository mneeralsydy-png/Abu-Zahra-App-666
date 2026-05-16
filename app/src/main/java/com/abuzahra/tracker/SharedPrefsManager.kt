package com.abuzahra.tracker

import android.content.Context

/**
 * SharedPrefsManager - إدارة التفضيلات المشتركة
 * يحفظ بيانات الربط بين الجهاز والولي + حالة تسجيل السيرفر
 */
object SharedPrefsManager {
    private const val PREFS_NAME = "child_prefs"

    // ==================== ==================== ====================
    //          بيانات الربط (Firebase Linking Data)
    // ==================== ==================== ====================

    /**
     * حفظ بيانات الربط (معرف الولي + معرف الجهاز)
     */
    fun saveData(ctx: Context, parentUid: String, deviceId: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("parent_uid", parentUid)
            putString("device_id", deviceId)
            apply()
        }
    }

    /**
     * الحصول على معرف الولي
     */
    fun getParentUid(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("parent_uid", null)

    /**
     * الحصول على معرف الجهاز
     */
    fun getDeviceId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("device_id", null)

    // ==================== ==================== ====================
    //          بيانات سيرفر البوت (Bot Server Data)
    // ==================== ==================== ====================

    /**
     * حفظ كود الربط المستخدم
     */
    fun setLinkCode(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("link_code", code)
            apply()
        }
    }

    /**
     * الحصول على كود الربط المستخدم
     */
    fun getLinkCode(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("link_code", null)

    /**
     * تعيين حالة تسجيل الجهاز في سيرفر البوت
     */
    fun setBotRegistered(ctx: Context, registered: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("bot_registered", registered)
            apply()
        }
    }

    /**
     * التحقق مما إذا كان الجهاز مسجلاً في سيرفر البوت
     */
    fun isBotRegistered(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("bot_registered", false)

    /**
     * حفظ توكن الجهاز من السيرفر
     */
    fun setBotToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("bot_token", token)
            apply()
        }
    }

    /**
     * الحصول على توكن الجهاز
     */
    fun getBotToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("bot_token", null)

    /**
     * تعيين طابع زمني لآخر نبض قلب ناجح
     */
    fun setLastHeartbeat(ctx: Context, timestamp: Long) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong("last_heartbeat", timestamp)
            apply()
        }
    }

    /**
     * الحصول على طابع زمني لآخر نبض قلب
     */
    fun getLastHeartbeat(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_heartbeat", 0)

    /**
     * تعيين طابع زمني لآخر تفحص ناجح للأوامر
     */
    fun setLastCommandCheck(ctx: Context, timestamp: Long) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong("last_command_check", timestamp)
            apply()
        }
    }

    /**
     * الحصول على طابع زمني لآخر تفحص للأوامر
     */
    fun getLastCommandCheck(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_command_check", 0)

    // ==================== ==================== ====================
    //          استمرارية الجلسة (Session Persistence)
    // ==================== ==================== ====================

    /**
     * حفظ علامة اكتمال التفعيل مع الطابع الزمني
     * يُستدعى عند اكتمال جميع مراحل الأذونات لأول مرة
     */
    fun markSetupCompleted(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("setup_completed", true)
            putLong("setup_completed_at", System.currentTimeMillis())
            apply()
        }
    }

    /**
     * التحقق مما إذا كان التفعيل قد اكتمل سابقاً
     * يُستخدم في onCreate لتخطي مراحل الأذونات
     */
    fun isSetupCompleted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("setup_completed", false)

    /**
     * الحصول على طابع زمني لوقت اكتمال التفعيل
     */
    fun getSetupCompletedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("setup_completed_at", 0)

    /**
     * إعادة ضبط حالة التفعيل (للاستخدام عند الحاجة لإعادة الإعداد)
     */
    fun resetSetupCompleted(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("setup_completed", false)
            remove("setup_completed_at")
            apply()
        }
    }
}
