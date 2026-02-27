package com.abuzahra.tracker

import android.content.Context

object SharedPrefsManager {
    private const val PREFS = "child_prefs"

    fun saveData(ctx: Context, parentUid: String, deviceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("parent_uid", parentUid)
            putString("device_id", deviceId)
            apply()
        }
    }

    fun getParentUid(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("parent_uid", null)
    }

    fun getDeviceId(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device_id", null)
    }
}
