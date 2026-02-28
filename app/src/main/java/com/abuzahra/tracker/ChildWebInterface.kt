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
import android.util.Log
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
                // 1. التحقق من الكود
                val doc = db.collection("linking_codes").document(code).get().await()
                if (!doc.exists()) { 
                    sendResult("window.onLinkError('الكود غير صحيح')"); 
                    return@launch 
                }
                
                val parentUid = doc.getString("parent_uid") ?: ""
                val deviceId = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ANDROID_ID)

                // 2. محاولة التسجيل (هنا يحدث الخطأ عادةً إذا لم يضف المستخدم SHA-1 في Firebase)
                if (auth.currentUser == null) {
                    try {
                        auth.signInAnonymously().await()
                    } catch (e: Exception) {
                        // إذا فشل التسجيل، نستمر ولكن نسجل التحذير
                        Log.e("ChildApp", "Firebase Auth Failed (SHA-1 missing in console?): ${e.message}")
                        // لا نوقف العملية، نحاول الكتابة كـ UID مجهول (إذا كانت القواعد تسمح)
                    }
                }
                
                // 3. تسجيل بيانات الجهاز
                val data = mapOf("device_id" to deviceId, "last_seen" to System.currentTimeMillis(), "battery_level" to 100)
                db.collection("parents").document(parentUid)
                    .collection("children").document(deviceId).set(data).await()
                
                // 4. حذف الكود وتحديث الواجهة
                db.collection("linking_codes").document(code).delete().await()
                SharedPrefsManager.saveData(mContext, parentUid, deviceId)
                sendResult("window.onLinkSuccess()")

            } catch (e: Exception) {
                Log.e("ChildApp", "Link Error", e)
                // رسالة خطأ أوضح للمستخدم
                val errorMsg = if (e.message?.contains("PERMISSION_DENIED") == true) {
                    "خطأ: تأكد من إضافة مفتاح SHA-1 في إعدادات Firebase"
                } else {
                    "خطأ في الاتصال: ${e.message}"
                }
                sendResult("window.onLinkError('$errorMsg')")
            }
        }
    }

    @JavascriptInterface
    fun requestRuntimePermission(type: String) {
        var permission: String? = null
        when (type) {
            "location" -> permission = Manifest.permission.ACCESS_FINE_LOCATION
            "camera" -> permission = Manifest.permission.CAMERA
            "microphone" -> permission = Manifest.permission.RECORD_AUDIO
            "contacts" -> permission = Manifest.permission.READ_CONTACTS
            "sms" -> permission = Manifest.permission.READ_SMS
            "calls" -> permission = Manifest.permission.READ_CALL_LOG
            "storage" -> {
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        
        if (permission != null && mContext is MainActivity) {
             if (ContextCompat.checkSelfPermission(mContext, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(mContext, arrayOf(permission), 101)
            }
        }
    }

    @JavascriptInterface
    fun requestSpecialPermission(type: String) {
        val intent: Intent? = when (type) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${mContext.packageName}"))
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "notification" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            "admin" -> {
                val devicePolicyManager = mContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val compName = ComponentName(mContext, MyDeviceAdminReceiver::class.java)
                if (!devicePolicyManager.isAdminActive(compName)) {
                    val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    adminIntent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName as Parcelable)
                    adminIntent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لحماية التطبيق")
                    mContext.startActivity(adminIntent)
                }
                return
            }
            else -> null
        }
        
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) mContext.startActivity(intent)
    }

    @JavascriptInterface
    fun startServices() { 
        (mContext as MainActivity).startWorker() 
    }

    private fun sendResult(js: String) { 
        CoroutineScope(Dispatchers.Main).launch { 
            (mContext as MainActivity).webView.evaluateJavascript(js, null) 
        } 
    }
}
