package com.abuzahra.tracker

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChildWebInterface(private val mContext: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    @JavascriptInterface
    fun linkDevice(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val doc = db.collection("linking_codes").document(code).get().await()
                if (!doc.exists()) { sendResult("window.onLinkError('الكود غير صحيح')"); return@launch }
                
                val parentUid = doc.getString("parent_uid") ?: ""
                val deviceId = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ANDROID_ID)
                
                if (auth.currentUser == null) auth.signInAnonymously().await()
                
                val data = mapOf("device_id" to deviceId, "last_seen" to System.currentTimeMillis(), "battery_level" to 100)
                db.collection("parents").document(parentUid).collection("children").document(deviceId).set(data).await()
                db.collection("linking_codes").document(code).delete().await()
                
                SharedPrefsManager.saveData(mContext, parentUid, deviceId)
                sendResult("window.onLinkSuccess()")
            } catch (e: Exception) { sendResult("window.onLinkError('خطأ: ${e.message}')") }
        }
    }

    @JavascriptInterface
    fun requestPermission(type: String) {
        when (type) {
            "accessibility" -> mContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "usage" -> mContext.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "location" -> mContext.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "overlay" -> mContext.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${mContext.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "notification" -> mContext.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "admin" -> {
                val devicePolicyManager = mContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val compName = ComponentName(mContext, MyDeviceAdminReceiver::class.java)
                if (!devicePolicyManager.isAdminActive(compName)) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    // الإصلاح هنا: إضافة as Parcelable
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName as Parcelable)
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لحماية التطبيق")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    mContext.startActivity(intent)
                }
            }
            "contacts" -> requestRuntimePermission(Manifest.permission.READ_CONTACTS)
            "sms" -> requestRuntimePermission(Manifest.permission.READ_SMS)
            "camera" -> requestRuntimePermission(Manifest.permission.CAMERA)
            "microphone" -> requestRuntimePermission(Manifest.permission.RECORD_AUDIO)
            "storage" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestRuntimePermission(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    requestRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
    
    private fun requestRuntimePermission(permission: String) {
        if (mContext is MainActivity) {
            if (ContextCompat.checkSelfPermission(mContext, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(mContext, arrayOf(permission), 102)
            }
        }
    }

    @JavascriptInterface
    fun startServices() { (mContext as MainActivity).startWorker() }

    private fun sendResult(js: String) { CoroutineScope(Dispatchers.Main).launch { (mContext as MainActivity).webView.evaluateJavascript(js, null) } }
}
