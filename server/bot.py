#!/usr/bin/env python3
"""
أبو زهرة - بوت إدارة الخادم
Web dashboard + REST API for device management.
Does NOT poll getUpdates — the Android app handles Telegram communication directly.
"""

import asyncio
import json
import os
import sys
import time
import logging
from datetime import datetime, timezone
from pathlib import Path

import aiohttp
from aiohttp import web

# ── Configuration ──────────────────────────────────────────────────────────────
BOT_TOKEN = os.environ.get("BOT_TOKEN", "8898830696:AAGhrsmavkljSpF8d9SUw1XbM5syh4nzGF4")
ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "7344776596"))
SERVER_PORT = int(os.environ.get("SERVER_PORT", "8443"))
DATA_DIR = Path(__file__).parent / "data"
DEVICES_FILE = DATA_DIR / "devices.json"
LOG_FILE = DATA_DIR / "bot_events.json"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("abu-zahra-bot")

# ── Helpers ────────────────────────────────────────────────────────────────────

def _ensure_data_dir():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not DEVICES_FILE.exists():
        DEVICES_FILE.write_text(json.dumps([], ensure_ascii=False, indent=2))
    if not LOG_FILE.exists():
        LOG_FILE.write_text(json.dumps([], ensure_ascii=False, indent=2))


def _load_json(path: Path):
    try:
        return json.loads(path.read_text())
    except Exception:
        return [] if path == DEVICES_FILE else []


def _save_json(path: Path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2))


def _append_log(event: str, details: dict = None):
    logs = _load_json(LOG_FILE)
    logs.append({
        "time": datetime.now(timezone.utc).isoformat(),
        "event": event,
        "details": details or {},
    })
    # Keep last 500 events
    if len(logs) > 500:
        logs = logs[-500:]
    _save_json(LOG_FILE, logs)


def _ts():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _uptime():
    return int(time.time() - START_TIME)


# ── Telegram API helpers (fire-and-forget, no polling) ─────────────────────────

_tg_session: aiohttp.ClientSession | None = None


def _get_tg_session() -> aiohttp.ClientSession:
    global _tg_session
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15))
    return _tg_session


async def _tg_request(method: str, payload: dict):
    """Send a single Telegram Bot API request."""
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/{method}"
    try:
        session = _get_tg_session()
        async with session.post(url, json=payload) as resp:
            data = await resp.json()
            if data.get("ok"):
                log.info("TG %s OK", method)
            else:
                log.warning("TG %s error: %s", method, data)
            return data
    except Exception as exc:
        log.error("TG %s failed: %s", method, exc)
        return None


async def send_admin_message(text: str):
    """Send a text message to the admin chat."""
    await _tg_request("sendMessage", {
        "chat_id": ADMIN_CHAT_ID,
        "text": text,
        "parse_mode": "HTML",
    })


async def get_me():
    """Get bot info."""
    return await _tg_request("getMe", {})


# ── Web Routes ─────────────────────────────────────────────────────────────────

HTML_DASHBOARD = r"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>أبو زهرة - لوحة التحكم</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:#0f0f1a;color:#e0e0e0;min-height:100vh;direction:rtl}
.header{background:linear-gradient(135deg,#1a1a2e,#16213e);padding:24px 32px;display:flex;justify-content:space-between;align-items:center;border-bottom:2px solid #0f3460}
.header h1{font-size:1.6rem;color:#e94560}
.header .status{display:flex;align-items:center;gap:8px;font-size:.9rem}
.dot{width:12px;height:12px;border-radius:50%;display:inline-block}
.dot.green{background:#00c853;box-shadow:0 0 8px #00c853}
.dot.red{background:#ff1744;box-shadow:0 0 8px #ff1744}
.container{max-width:1200px;margin:0 auto;padding:24px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:20px;margin-bottom:28px}
.card{background:#1a1a2e;border:1px solid #0f3460;border-radius:12px;padding:22px}
.card h3{font-size:1rem;color:#e94560;margin-bottom:12px}
.card .val{font-size:2rem;font-weight:700;color:#fff}
.card .sub{font-size:.8rem;color:#888;margin-top:6px}
table{width:100%;border-collapse:collapse;background:#1a1a2e;border-radius:12px;overflow:hidden;border:1px solid #0f3460}
th,td{text-align:right;padding:12px 16px;border-bottom:1px solid #16213e}
th{background:#16213e;color:#e94560;font-size:.85rem}
td{font-size:.9rem}
.badge{display:inline-block;padding:3px 10px;border-radius:20px;font-size:.75rem;font-weight:600}
.badge.on{background:#00c85333;color:#00c853}
.badge.off{background:#ff174433;color:#ff1744}
.btn{padding:8px 18px;border:none;border-radius:8px;cursor:pointer;font-size:.85rem;font-family:inherit;transition:all .2s}
.btn-danger{background:#e94560;color:#fff}
.btn-danger:hover{background:#c0392b}
.btn-primary{background:#0f3460;color:#fff}
.btn-primary:hover{background:#1a5276}
.btn-sm{padding:4px 12px;font-size:.78rem}
.log-box{background:#1a1a2e;border:1px solid #0f3460;border-radius:12px;padding:18px;max-height:320px;overflow-y:auto;font-family:'Courier New',monospace;font-size:.8rem;line-height:1.7}
.log-box .log-ok{color:#00c853}
.log-box .log-warn{color:#ffc107}
.log-box .log-err{color:#ff1744}
.log-box .log-info{color:#64b5f6}
.section-title{font-size:1.1rem;color:#e94560;margin:28px 0 14px;border-right:3px solid #e94560;padding-right:12px}
.empty{text-align:center;color:#666;padding:40px;font-size:.95rem}
.api-box{background:#1a1a2e;border:1px solid #0f3460;border-radius:12px;padding:18px;margin-top:20px}
.api-box code{background:#0f3460;padding:2px 8px;border-radius:4px;font-size:.82rem;color:#64b5f6}
.api-box pre{background:#0f0f1a;padding:14px;border-radius:8px;margin-top:10px;overflow-x:auto;font-size:.8rem;color:#aaa}
footer{text-align:center;padding:20px;color:#555;font-size:.8rem;margin-top:30px}
</style>
</head>
<body>
<div class="header">
  <h1>&#x1F9D1;&#x200D;&#x1F9B0; أبو زهرة - لوحة التحكم</h1>
  <div class="status">
    <span class="dot green" id="statusDot"></span>
    <span id="statusText">متصل</span>
  </div>
</div>

<div class="container">
  <div class="cards" id="statsCards">
    <div class="card"><h3>&#x23F1; وقت التشغيل</h3><div class="val" id="uptime">--</div><div class="sub">بالثواني</div></div>
    <div class="card"><h3>&#x1F4F1; الأجهزة المسجلة</h3><div class="val" id="deviceCount">0</div><div class="sub">جهاز</div></div>
    <div class="card"><h3>&#x1F4AC; الرسائل المرسلة</h3><div class="val" id="msgCount">0</div><div class="sub">رسالة عبر API</div></div>
    <div class="card"><h3>&#x1F4CA; طلبات API</h3><div class="val" id="apiHits">0</div><div class="sub">طلب</div></div>
  </div>

  <div class="section-title">&#x1F4F1; الأجهزة</div>
  <table id="devicesTable">
    <thead><tr><th>المعرف</th><th>الاسم</th><th>الحالة</th><th>آخر نشاط</th><th>إجراء</th></tr></thead>
    <tbody id="devicesBody"></tbody>
  </table>
  <div class="empty" id="noDevices" style="display:none">لا توجد أجهزة مسجلة حالياً</div>

  <div class="section-title">&#x1F4DD; سجل الأحداث</div>
  <div class="log-box" id="logBox"><span class="log-info">جارٍ التحميل...</span></div>

  <div class="section-title">&#x1F527; واجهة برمجة التطبيقات</div>
  <div class="api-box">
    <p>نقاط النهاية المتاحة:</p>
    <pre>
GET  /api/status          — حالة البوت
GET  /api/devices         — قائمة الأجهزة
POST /api/devices         — تسجيل جهاز جديد
POST /api/devices/{id}/toggle — تبديل حالة الجهاز
DELETE /api/devices/{id}  — حذف جهاز
POST /api/send            — إرسال رسالة للتليجرام
GET  /api/logs            — سجل الأحداث
    </pre>
  </div>
</div>

<footer>أبو زهرة &copy; 2025 — لوحة تحكم الخادم</footer>

<script>
const API = window.location.origin;
let apiHits = 0;

async function refresh(){
  apiHits++;
  try{
    const [s,d,l] = await Promise.all([
      fetch(API+'/api/status').then(r=>r.json()),
      fetch(API+'/api/devices').then(r=>r.json()),
      fetch(API+'/api/logs').then(r=>r.json()),
    ]);
    // Stats
    document.getElementById('uptime').textContent = s.uptime + ' ثانية';
    document.getElementById('deviceCount').textContent = d.length;
    document.getElementById('msgCount').textContent = s.messages_sent;
    document.getElementById('apiHits').textContent = apiHits;
    // Devices
    const body = document.getElementById('devicesBody');
    const noDev = document.getElementById('noDevices');
    if(!d.length){
      body.innerHTML='';
      noDev.style.display='block';
    } else {
      noDev.style.display='none';
      body.innerHTML = d.map(dev => `<tr>
        <td><code>${dev.id}</code></td>
        <td>${dev.name||'—'}</td>
        <td><span class="badge ${dev.active?'on':'off'}">${dev.active?'نشط':'متوقف'}</span></td>
        <td>${dev.last_seen||'—'}</td>
        <td>
          <button class="btn btn-sm btn-primary" onclick="toggleDevice('${dev.id}')">تبديل</button>
          <button class="btn btn-sm btn-danger" onclick="removeDevice('${dev.id}')">حذف</button>
        </td>
      </tr>`).join('');
    }
    // Logs
    const logBox = document.getElementById('logBox');
    if(l.length){
      logBox.innerHTML = l.slice(-30).reverse().map(e => {
        const cls = e.event.includes('خطأ')||e.event.includes('ERROR')?'log-err':
                    e.event.includes('تحذير')||e.event.includes('WARN')?'log-warn':'log-info';
        return `<div class="${cls}">[${e.time}] ${e.event} ${e.details?JSON.stringify(e.details):''}</div>`;
      }).join('');
    } else {
      logBox.innerHTML='<span class="log-info">لا توجد أحداث بعد</span>';
    }
  }catch(e){
    console.error(e);
  }
}

function toggleDevice(id){
  fetch(API+'/api/devices/'+id+'/toggle',{method:'POST',headers:{'Content-Type':'application/json'}})
    .then(()=>refresh());
}
function removeDevice(id){
  if(!confirm('هل أنت متأكد من حذف هذا الجهاز؟')) return;
  fetch(API+'/api/devices/'+id,{method:'DELETE'}).then(()=>refresh());
}

refresh();
setInterval(refresh, 5000);
</script>
</body>
</html>
"""

START_TIME = time.time()
messages_sent = 0


# ── API Handlers ───────────────────────────────────────────────────────────────

async def handle_index(request: web.Request) -> web.Response:
    return web.Response(text=HTML_DASHBOARD, content_type="text/html", charset="utf-8")


async def handle_status(request: web.Request) -> web.Response:
    return web.json_response({
        "bot_username": "Abu_Zahra_Bot",
        "status": "running",
        "uptime": _uptime(),
        "messages_sent": messages_sent,
        "server_time": _ts(),
        "port": SERVER_PORT,
        "mode": "web_dashboard",
        "note": "التطبيق يتعامل مع تحديثات التليجرام مباشرة",
    })


async def handle_devices(request: web.Request) -> web.Response:
    devices = _load_json(DEVICES_FILE)
    return web.json_response(devices)


async def handle_add_device(request: web.Request) -> web.Response:
    devices = _load_json(DEVICES_FILE)
    body = await request.json()
    device = {
        "id": body.get("id", f"device_{len(devices)+1}"),
        "name": body.get("name", "جهاز جديد"),
        "active": body.get("active", True),
        "last_seen": _ts(),
        "extra": body.get("extra", {}),
    }
    # Update if exists
    for i, d in enumerate(devices):
        if d["id"] == device["id"]:
            device["last_seen"] = _ts()
            devices[i] = device
            _save_json(DEVICES_FILE, devices)
            _append_log("تحديث جهاز", {"id": device["id"]})
            return web.json_response({"ok": True, "device": device})
    devices.append(device)
    _save_json(DEVICES_FILE, devices)
    _append_log("تسجيل جهاز جديد", {"id": device["id"], "name": device["name"]})
    return web.json_response({"ok": True, "device": device}, status=201)


async def handle_toggle_device(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    devices = _load_json(DEVICES_FILE)
    for d in devices:
        if d["id"] == device_id:
            d["active"] = not d["active"]
            d["last_seen"] = _ts()
            _save_json(DEVICES_FILE, devices)
            _append_log("تبديل حالة الجهاز", {"id": device_id, "active": d["active"]})
            return web.json_response({"ok": True, "device": d})
    return web.json_response({"ok": False, "error": "الجهاز غير موجود"}, status=404)


async def handle_delete_device(request: web.Request) -> web.Response:
    device_id = request.match_info["device_id"]
    devices = _load_json(DEVICES_FILE)
    new_devices = [d for d in devices if d["id"] != device_id]
    if len(new_devices) == len(devices):
        return web.json_response({"ok": False, "error": "الجهاز غير موجود"}, status=404)
    _save_json(DEVICES_FILE, new_devices)
    _append_log("حذف جهاز", {"id": device_id})
    return web.json_response({"ok": True})


async def handle_send(request: web.Request) -> web.Response:
    """Send a Telegram message via the API (fire-and-forget, no getUpdates conflict)."""
    global messages_sent
    body = await request.json()
    chat_id = body.get("chat_id", ADMIN_CHAT_ID)
    text = body.get("text", "")
    if not text:
        return web.json_response({"ok": False, "error": "النص مطلوب"}, status=400)
    result = await _tg_request("sendMessage", {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": body.get("parse_mode", "HTML"),
    })
    if result and result.get("ok"):
        messages_sent += 1
        _append_log("إرسال رسالة", {"chat_id": chat_id, "chars": len(text)})
        return web.json_response({"ok": True, "message_id": result.get("result", {}).get("message_id")})
    return web.json_response({"ok": False, "error": "فشل الإرسال"}, status=500)


async def handle_logs(request: web.Request) -> web.Response:
    logs = _load_json(LOG_FILE)
    limit = int(request.query.get("limit", "50"))
    return web.json_response(logs[-limit:])


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({"alive": True, "ts": _ts()})


# ── App factory & startup ──────────────────────────────────────────────────────

def create_app() -> web.Application:
    app = web.Application()
    app.router.add_get("/", handle_index)
    app.router.add_get("/health", handle_health)
    # API
    app.router.add_get("/api/status", handle_status)
    app.router.add_get("/api/devices", handle_devices)
    app.router.add_post("/api/devices", handle_add_device)
    app.router.add_post("/api/devices/{device_id}/toggle", handle_toggle_device)
    app.router.add_delete("/api/devices/{device_id}", handle_delete_device)
    app.router.add_post("/api/send", handle_send)
    app.router.add_get("/api/logs", handle_logs)
    return app


async def on_startup(app: web.Application):
    _ensure_data_dir()
    _append_log("تشغيل الخادم", {"port": SERVER_PORT})
    # Verify bot token
    me = await get_me()
    if me and me.get("ok"):
        bot_info = me["result"]
        log.info("Bot: @%s (%s)", bot_info.get("username"), bot_info.get("first_name"))
        _append_log("تم التحقق من البوت", {"username": bot_info.get("username")})
        # Send startup notification to admin
        await send_admin_message(
            f"🟢 <b>الخادم يعمل</b>\n"
            f"⚡ المنفذ: <code>{SERVER_PORT}</code>\n"
            f"🕐 الوقت: <code>{_ts()}</code>\n"
            f"📍 الوضع: لوحة تحكم ويب\n"
            f"📝 ملاحظة: التطبيق يتعامل مع الرسائل مباشرة"
        )
    else:
        log.error("Failed to verify bot token!")


async def on_cleanup(app: web.Application):
    _append_log("إيقاف الخادم", {})
    log.info("Server shutting down.")


async def _run():
    global _tg_session
    app = create_app()
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", SERVER_PORT)
    await site.start()
    log.info("Server running on http://0.0.0.0:%d", SERVER_PORT)
    _append_log("الخادم جاهز", {"port": SERVER_PORT})

    # Block forever until cancelled
    try:
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        pass
    finally:
        # Clean up TG session
        if _tg_session and not _tg_session.closed:
            await _tg_session.close()
        await runner.cleanup()


def main():
    try:
        asyncio.run(_run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
