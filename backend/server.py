from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request, UploadFile, File, BackgroundTasks
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import Optional, List
import uvicorn
import json
import os
import secrets
import time
import requests
import sqlite3
try:
    import psycopg2
    HAS_POSTGRES = True
except ImportError:
    HAS_POSTGRES = False

DATABASE_URL = os.environ.get("DATABASE_URL")


app = FastAPI()

ADMIN_USER = "FLAdmin"
ADMIN_PASS = "TNm5VqCferU6hKtW32upxWOae"
active_sessions = set()


@app.middleware("http")
async def auth_and_cache_middleware(request: Request, call_next):
    path = request.url.path
    
    # Protect all /api/ endpoints EXCEPT /api/provision, /api/enrollment-config, /api/auth/login, /api/auth/status
    public_paths = {
        "/api/provision",
        "/api/enrollment-config",
        "/api/auth/login",
        "/api/auth/status"
    }
    
    if path.startswith("/api/") and path not in public_paths:
        session_token = request.cookies.get("flshield_session")
        if not session_token or session_token not in active_sessions:
            return JSONResponse(
                status_code=401,
                content={"detail": "Not authenticated"}
            )
            
    response = await call_next(request)
    
    if path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
        
    return response


class Database:
    def __init__(self):
        self.is_postgres = False
        if DATABASE_URL and (DATABASE_URL.startswith("postgres://") or DATABASE_URL.startswith("postgresql://")):
            self.is_postgres = True
            self.db_url = DATABASE_URL
            if self.db_url.startswith("postgres://"):
                self.db_url = self.db_url.replace("postgres://", "postgresql://", 1)
            print("[Database] Using PostgreSQL persistent database")
        else:
            base_dir = os.path.dirname(os.path.abspath(__file__))
            self.db_url = os.path.join(base_dir, "flshield.db")
            print(f"[Database] Using SQLite local database ({self.db_url})")
        self.init_db()

    def get_connection(self):
        if self.is_postgres:
            if not HAS_POSTGRES:
                raise RuntimeError("psycopg2 is not installed but DATABASE_URL is set to PostgreSQL")
            return psycopg2.connect(self.db_url)
        else:
            return sqlite3.connect(self.db_url)

    def init_db(self):
        conn = self.get_connection()
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS config_settings (
                key VARCHAR(50) PRIMARY KEY,
                value TEXT
            )
        """)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS devices (
                device_id VARCHAR(100) PRIMARY KEY,
                device_key VARCHAR(100),
                name VARCHAR(100),
                blocked INT,
                allowed_apps TEXT,
                installed_apps TEXT,
                last_seen REAL,
                last_unlock_request_at REAL,
                last_block_request_at REAL,
                clear_data_password VARCHAR(100),
                created_at REAL,
                last_seen_at REAL
            )
        """)
        
        # Migrations to ensure columns created_at, last_seen, last_seen_at exist
        for col in ["created_at", "last_seen", "last_seen_at"]:
            try:
                cursor.execute(f"ALTER TABLE devices ADD COLUMN {col} REAL")
                conn.commit()
                print(f"[Database] Migration: Added column '{col}' to devices table")
            except Exception:
                pass
                
        conn.commit()
        conn.close()
        self.migrate_legacy_files()

    def migrate_legacy_files(self):
        base_dir = os.path.dirname(os.path.abspath(__file__))
        config_json_path = os.path.join(base_dir, "config.json")
        devices_json_path = os.path.join(base_dir, "devices.json")
        
        if not os.path.exists(config_json_path) and not os.path.exists(devices_json_path):
            return

        print("[Database] Legacy JSON files found. Checking if migration is needed...")
        
        try:
            config_rows = self._execute("SELECT COUNT(*) FROM config_settings", commit=False, fetchone=True)
            devices_rows = self._execute("SELECT COUNT(*) FROM devices", commit=False, fetchone=True)
            db_empty = (config_rows and config_rows[0] == 0) and (devices_rows and devices_rows[0] == 0)
        except Exception as e:
            print(f"⚠️ Error checking DB status for migration: {e}")
            return

        if not db_empty:
            print("[Database] SQL database already contains data. Skipping legacy file migration.")
            return

        print("[Database] SQL database is empty. Migrating legacy JSON files...")
        
        # 1. Migrate config
        if os.path.exists(config_json_path):
            try:
                with open(config_json_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                self.save_config(cfg)
                print(f"[Database] Migrated config.json successfully.")
            except Exception as e:
                print(f"⚠️ Error migrating config.json: {e}")

        # 2. Migrate devices
        if os.path.exists(devices_json_path):
            try:
                with open(devices_json_path, "r", encoding="utf-8") as f:
                    devices_data = json.load(f)
                devices_list = devices_data.get("devices", [])
                for d in devices_list:
                    self.save_device(d)
                print(f"[Database] Migrated {len(devices_list)} devices from devices.json successfully.")
            except Exception as e:
                print(f"⚠️ Error migrating devices.json: {e}")
                
        # Rename legacy files so they are not processed again
        try:
            if os.path.exists(config_json_path):
                os.rename(config_json_path, config_json_path + ".bak")
            if os.path.exists(devices_json_path):
                os.rename(devices_json_path, devices_json_path + ".bak")
            print("[Database] Renamed legacy JSON files to .bak")
        except Exception as e:
            print(f"⚠️ Error renaming legacy files: {e}")

    def _execute(self, query, params=(), commit=True, fetchall=False, fetchone=False):
        if self.is_postgres:
            query = query.replace("?", "%s")
        conn = self.get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(query, params)
            result = None
            if fetchall:
                result = cursor.fetchall()
            elif fetchone:
                result = cursor.fetchone()
            if commit:
                conn.commit()
            return result
        except Exception as e:
            print(f"⚠️ DB Error executing query: {query}. Error: {e}")
            raise e
        finally:
            conn.close()

    def get_config(self) -> Optional[dict]:
        row = self._execute("SELECT value FROM config_settings WHERE key = ?", ("global_config",), commit=False, fetchone=True)
        if row:
            return json.loads(row[0])
        return None

    def save_config(self, config: dict):
        val_str = json.dumps(config, ensure_ascii=False)
        self._execute("""
            INSERT INTO config_settings (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value
        """, ("global_config", val_str))

    def get_devices(self) -> list:
        rows = self._execute("""
            SELECT device_id, device_key, name, blocked, allowed_apps, installed_apps, 
                   last_seen, last_unlock_request_at, last_block_request_at, clear_data_password, 
                   created_at, last_seen_at
            FROM devices
        """, commit=False, fetchall=True)
        devices = []
        for r in rows:
            last_seen_val = r[6] if r[6] is not None else (r[11] if r[11] is not None else 0.0)
            created_at_val = r[10] if r[10] is not None else last_seen_val
            devices.append({
                "device_id": r[0],
                "device_key": r[1],
                "name": r[2] or "",
                "blocked": bool(r[3]),
                "allowed_apps": json.loads(r[4]) if r[4] else None,
                "installed_apps": json.loads(r[5]) if r[5] else [],
                "last_seen": last_seen_val,
                "last_seen_at": last_seen_val,
                "last_unlock_request_at": r[7],
                "last_block_request_at": r[8],
                "clear_data_password": r[9],
                "created_at": created_at_val
            })
        return devices

    def save_device(self, d: dict):
        allowed_apps_str = json.dumps(d.get("allowed_apps")) if d.get("allowed_apps") is not None else None
        installed_apps_str = json.dumps(d.get("installed_apps", []))
        last_seen_val = d.get("last_seen", d.get("last_seen_at", 0.0))
        created_at_val = d.get("created_at", last_seen_val)
        self._execute("""
            INSERT INTO devices (
                device_id, device_key, name, blocked, allowed_apps, installed_apps, 
                last_seen, last_unlock_request_at, last_block_request_at, clear_data_password, 
                created_at, last_seen_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_key = EXCLUDED.device_key,
                name = EXCLUDED.name,
                blocked = EXCLUDED.blocked,
                allowed_apps = EXCLUDED.allowed_apps,
                installed_apps = EXCLUDED.installed_apps,
                last_seen = EXCLUDED.last_seen,
                last_unlock_request_at = EXCLUDED.last_unlock_request_at,
                last_block_request_at = EXCLUDED.last_block_request_at,
                clear_data_password = EXCLUDED.clear_data_password,
                created_at = EXCLUDED.created_at,
                last_seen_at = EXCLUDED.last_seen_at
        """, (
            d["device_id"],
            d.get("device_key"),
            d.get("name", ""),
            1 if d.get("blocked", False) else 0,
            allowed_apps_str,
            installed_apps_str,
            last_seen_val,
            d.get("last_unlock_request_at"),
            d.get("last_block_request_at"),
            d.get("clear_data_password"),
            created_at_val,
            last_seen_val
        ))

    def delete_device(self, device_id: str):
        self._execute("DELETE FROM devices WHERE device_id = ?", (device_id,))

    def delete_all_devices(self):
        self._execute("DELETE FROM devices")

db = Database()

base_dir = os.path.dirname(os.path.abspath(__file__))
APKS_DIR = os.path.join(base_dir, "public", "apks")
if not os.path.exists(APKS_DIR):
    os.makedirs(APKS_DIR, exist_ok=True)



# ─── Config helpers ───────────────────────────────────────────────────────────

def load_config() -> dict:
    cfg = db.get_config()
    default_allowed_apps = [
        "com.motorola.camera3",
        "com.google.android.calculator",
        "com.whatsapp",
        "com.waze",
        "com.microsoft.powerbim",
        "com.microsoft.teams",
        "com.ionicframework.sfaLucema",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.google.android.deskclock",
        "com.document.pdf.scanner.free.all",
        "com.google.android.dialer",
        "com.google.android.apps.maps",
        "com.google.android.contacts"
    ]
    if cfg is None:
        cfg = {
            "master_password": "1234",
            "unlock_pattern": "012",
            "allowed_apps": default_allowed_apps,
            "block_gps": False,
            "block_datetime": False,
            "device_names": {},
            "always_blocked": ["com.android.settings", "com.android.providers.settings"],
            "always_allowed": ["com.example.applocker", "com.android.systemui", "com.android.launcher", "com.google.android.apps.nexuslauncher"],
            "clear_data_password": "5678",
            "data_off_password": "4321"
        }
        db.save_config(cfg)
    else:
        # Guarantee fallback keys are present
        if "device_names" not in cfg:
            cfg["device_names"] = {}
        if "always_blocked" not in cfg:
            cfg["always_blocked"] = ["com.android.settings", "com.android.providers.settings"]
        if "always_allowed" not in cfg:
            cfg["always_allowed"] = ["com.example.applocker", "com.android.systemui", "com.android.launcher", "com.google.android.apps.nexuslauncher"]
        if "clear_data_password" not in cfg:
            cfg["clear_data_password"] = "5678"
        if "unlock_pattern" not in cfg:
            cfg["unlock_pattern"] = "012"
        if "data_off_password" not in cfg:
            cfg["data_off_password"] = "4321"
    return cfg

def save_config(config: dict):
    db.save_config(config)

def load_devices_db() -> dict:
    return {"devices": db.get_devices()}

def save_devices_db(db_dict: dict):
    # Identify and apply deletions
    current_ids = {d["device_id"] for d in db_dict.get("devices", [])}
    stored_devices = db.get_devices()
    stored_ids = {d["device_id"] for d in stored_devices}
    
    deleted_ids = stored_ids - current_ids
    for d_id in deleted_ids:
        db.delete_device(d_id)
        
    # Apply updates/inserts
    for d in db_dict.get("devices", []):
        db.save_device(d)



def find_device_record(device_id: str, db: Optional[dict] = None) -> Optional[dict]:
    db = db or load_devices_db()
    for record in db.get("devices", []):
        if record.get("device_id") == device_id:
            return record
    return None


KNOWN_DEVICE_NAMES = {
    "moto_g54_5G_0cfccfab23d61ff5": "C1",
    "moto_g54_5G_094e6c4f5e7b83b4": "C2"
}

def register_device(device_id: str, device_key: Optional[str] = None, name: str = "", installed_apps: Optional[list] = None) -> dict:
    db = load_devices_db()
    device = find_device_record(device_id, db)
    
    fallback_name = KNOWN_DEVICE_NAMES.get(device_id, "")
    resolved_name = name or fallback_name

    if device is None:
        c2_device = find_device_record("moto_g54_5G_094e6c4f5e7b83b4", db)
        allowed_apps = None
        clear_data_password = None
        if c2_device:
            if c2_device.get("allowed_apps") is not None:
                allowed_apps = list(c2_device.get("allowed_apps"))
            if c2_device.get("clear_data_password"):
                clear_data_password = c2_device.get("clear_data_password")
        
        if allowed_apps is None:
            config = load_config()
            allowed_apps = list(config.get("allowed_apps", []))

        device = {
            "device_id": device_id,
            "device_key": device_key or secrets.token_urlsafe(24),
            "name": resolved_name,
            "created_at": time.time(),
            "last_seen": time.time(),
            "last_unlock_request_at": None,
            "blocked": False,
            "allowed_apps": allowed_apps,
            "installed_apps": installed_apps or []
        }
        if clear_data_password:
            device["clear_data_password"] = clear_data_password
        db["devices"].append(device)
    else:
        if device_key:
            if not device.get("device_key"):
                device["device_key"] = device_key
            elif device.get("device_key") != device_key:
                print(f"⚠️ Device key mismatch for {device_id}; keeping existing key")
        
        if name:
            device["name"] = name
        elif not device.get("name") and fallback_name:
            device["name"] = fallback_name
            
        device["last_seen"] = time.time()
        if "blocked" not in device:
            device["blocked"] = False
        if installed_apps is not None:
            device["installed_apps"] = installed_apps
    save_devices_db(db)
    return device


import re
import subprocess
from urllib.parse import urlparse, parse_qs

def get_aapt_path() -> Optional[str]:
    base_dir = r"C:\Users\Soporte\AppData\Local\Android\Sdk\build-tools"
    if os.path.exists(base_dir):
        versions = []
        for d in os.listdir(base_dir):
            d_path = os.path.join(base_dir, d)
            if os.path.isdir(d_path):
                aapt_exe = os.path.join(d_path, "aapt.exe")
                if os.path.exists(aapt_exe):
                    versions.append((d, aapt_exe))
        if versions:
            def version_key(v):
                try:
                    return [int(x) for x in v[0].split(".")]
                except Exception:
                    return [0]
            versions.sort(key=version_key)
            return versions[-1][1]
    return None

def get_apksigner_path() -> Optional[str]:
    import shutil
    if os.name == 'nt':
        base_dir = r"C:\Users\Soporte\AppData\Local\Android\Sdk\build-tools"
        if os.path.exists(base_dir):
            versions = []
            for d in os.listdir(base_dir):
                d_path = os.path.join(base_dir, d)
                if os.path.isdir(d_path):
                    apksigner_bat = os.path.join(d_path, "apksigner.bat")
                    if os.path.exists(apksigner_bat):
                        versions.append((d, apksigner_bat))
            if versions:
                def version_key(v):
                    try:
                        return [int(x) for x in v[0].split(".")]
                    except Exception:
                        return [0]
                versions.sort(key=version_key)
                return versions[-1][1]
    else:
        # Linux/macOS
        path = shutil.which("apksigner")
        if path:
            return path
        for p in ["/usr/bin/apksigner", "/usr/local/bin/apksigner"]:
            if os.path.exists(p):
                return p
    return None

def get_apk_signature_checksum(apk_filename: str) -> str:
    import subprocess, re, base64
    # Default fallback for debug signature
    default_checksum = "Z__nvKp4GOyEAYbfAFTkwTa3RE5Qm1KnoHaaILcWwu4"
    file_path = os.path.join(APKS_DIR, apk_filename)
    if not os.path.exists(file_path):
        return default_checksum
    
    apksigner_path = get_apksigner_path()
    if not apksigner_path:
        return default_checksum
        
    try:
        result = subprocess.run(
            [apksigner_path, "verify", "--print-certs", file_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        if result.returncode == 0:
            match = re.search(r"SHA-256 digest:\s+([a-fA-F0-9]+)", result.stdout)
            if match:
                sha_hex = match.group(1)
                sha_bytes = bytes.fromhex(sha_hex)
                base64_url = base64.urlsafe_b64encode(sha_bytes).decode('utf-8').rstrip('=')
                return base64_url
    except Exception as e:
        print(f"⚠️ Error running apksigner: {e}")
        
    return default_checksum

def get_local_ip() -> str:
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"

def get_request_base_url(request) -> str:
    headers = request.headers
    
    # 1. Get host
    host = headers.get("x-forwarded-host")
    if not host:
        host = headers.get("host")
    if not host:
        host = request.url.hostname or "localhost"
        if request.url.port:
            host = f"{host}:{request.url.port}"
            
    # 2. Get scheme/protocol
    proto = headers.get("x-forwarded-proto")
    if not proto:
        proto = "https" if request.url.scheme in ("https", "wss") else "http"
        
    # 3. If it's a public domain name (not localhost or raw IP), force https
    is_ip = re.match(r'^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$', host)
    is_localhost = "localhost" in host or "127.0.0.1" in host
    
    if not is_ip and not is_localhost:
        proto = "https"
        
    # 4. If the host is local (localhost/127.0.0.1), resolve to the local network IP
    if is_localhost and not headers.get("x-forwarded-host"):
        local_ip = get_local_ip()
        if ":" in host:
            port = host.split(":")[1]
            host = f"{local_ip}:{port}"
        else:
            host = local_ip
            
    return f"{proto}://{host}"

def extract_package_name(filename: str) -> str:
    # 1. Try to extract package name using aapt from the APK file if it exists
    file_path = os.path.join(APKS_DIR, filename)
    if os.path.exists(file_path):
        aapt_path = get_aapt_path()
        if aapt_path:
            try:
                # Try badging first
                result = subprocess.run(
                    [aapt_path, "dump", "badging", file_path],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace"
                )
                if result.returncode == 0:
                    match = re.search(r"package:\s+name='([^']+)'", result.stdout)
                    if match:
                        return match.group(1)
                
                # Try xmltree as fallback
                result = subprocess.run(
                    [aapt_path, "dump", "xmltree", file_path, "AndroidManifest.xml"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace"
                )
                for line in result.stdout.splitlines()[:50]:
                    match = re.search(r'package="([^"]+)"', line)
                    if match:
                        return match.group(1)
            except Exception as e:
                print(f"⚠️ Error executing aapt for {filename}: {e}")

    # 2. Fallback to filename-based string parsing if aapt fails or file doesn't exist
    name = filename
    if name.endswith(".apk"):
        name = name[:-4]
    elif name.endswith(".xapk"):
        name = name[:-5]
    for prefix in ["com.", "org.", "net.", "io.", "gov.", "edu."]:
        if prefix in name:
            idx = name.find(prefix)
            pkg_part = name[idx:]
            match = re.match(r'^([a-zA-Z0-9_]+\.[a-zA-Z0-9_\.]+)', pkg_part)
            if match:
                matched = match.group(1)
                segments = matched.split(".")
                clean_segments = []
                for seg in segments:
                    if seg.isdigit() or (seg.startswith('v') and seg[1:].isdigit()) or (len(seg) > 0 and seg[0].isdigit()):
                        break
                    if '_' in seg:
                        sub_seg = seg.split('_')[0]
                        clean_segments.append(sub_seg)
                        break
                    clean_segments.append(seg)
                return ".".join(clean_segments)
    pkg_part = name.split('_')[0].split('-')[0]
    return pkg_part

def get_apks_list(request_url_base: str) -> list:
    apks = {}
    if os.path.exists(APKS_DIR):
        for f in os.listdir(APKS_DIR):
            if f.endswith(".apk") or f.endswith(".xapk"):
                file_path = os.path.join(APKS_DIR, f)
                package_name = extract_package_name(f)
                if not package_name:
                    continue
                
                is_xapk = f.endswith(".xapk")
                if package_name in apks:
                    # Prioritize .xapk over .apk
                    existing_f, _ = apks[package_name]
                    if is_xapk:
                        apks[package_name] = (f, file_path)
                else:
                    apks[package_name] = (f, file_path)
                    
        out_list = []
        for pkg, (f, file_path) in apks.items():
            download_url = f"{request_url_base}/apks/{f}"
            out_list.append({
                "packageName": pkg,
                "url": download_url,
                "filename": f,
                "version": str(int(os.path.getmtime(file_path)))
            })
        return out_list
    return []


def send_udp_broadcast():
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.sendto(b'{"action":"sync"}', ("255.255.255.255", 50001))
        s.close()
        print("📢 Broadcast UDP sync sent on port 50001")
    except Exception as e:
        print(f"⚠️ Error sending UDP broadcast: {e}")


async def push_config_to_devices():
    config = load_config()
    db = load_devices_db()
    disconnected = []
    for device_id, info in list(active_devices.items()):
        device = find_device_record(device_id, db)
        ws = info["ws"]
        http_base_url = get_request_base_url(ws)
        apks = get_apks_list(http_base_url)

        global_clear_pass = config.get("clear_data_password", "5678")
        device_clear_pass = device.get("clear_data_password") if device else None
        clear_pass = device_clear_pass if device_clear_pass else global_clear_pass

        device_allowed_apps = device.get("allowed_apps") if device else None
        allowed_apps = device_allowed_apps if device_allowed_apps is not None else config.get("allowed_apps", [])

        payload = json.dumps({
            "action": "config_update",
            "allowedApps": allowed_apps,
            "deviceName": device.get("name", "") if device else "",
            "blockGps": config.get("block_gps", True),
            "blockDateTime": config.get("block_datetime", True),
            "localPassword": config.get("master_password", "1234"),
            "unlockPattern": config.get("unlock_pattern", "012"),
            "blocked": device.get("blocked", False) if device else False,
            "apks": apks,
            "clearDataPassword": clear_pass,
            "dataOffPassword": config.get("data_off_password", "4321")
        })
        print(f"DEBUG: Pushing payload to {device_id}: {payload}")
        try:
            await ws.send_text(payload)
        except Exception:
            disconnected.append(device_id)
    for d in disconnected:
        active_devices.pop(d, None)
    print(f"📢 Pushed config/apks update to {len(active_devices)} device(s)")
    send_udp_broadcast()

# ─── Hardcoded rules ─────────────────────────────────────────────────────────

ALWAYS_BLOCKED = [
    "com.android.vending",
    "com.google.android.packageinstaller",
    "com.android.packageinstaller",
    "com.android.settings",
    "com.samsung.android.settings",
    "com.miui.securitycenter",
    "com.xiaomi.misettings",
    "com.huawei.systemmanager",
    "com.coloros.safecenter",
    "com.oplus.safecenter",
    "com.google.android.gms",
]

ALWAYS_ALLOWED = [
    "com.example.applocker",
    "com.android.phone",
    "com.android.dialer",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.motorola.dialer",
    "com.motorola.dialer.primary",
    "com.motorola.launcher3",
    "com.motorola.messaging",
    "com.motorola.messaging.primary",
    "com.android.incallui",
    "com.android.mms",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.contacts",
    "com.google.android.contacts",
    "com.samsung.android.contacts",
    "com.android.systemui",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "com.swiftkey.swiftkeyapp",
    "com.touchtype.swiftkey",
    "com.android.inputmethod.latin",
    "com.aurora.store",
]

# ─── Connection store ─────────────────────────────────────────────────────────

# device_id -> {"ws": WebSocket, "connected_at": float}
active_devices: dict = {}

# Pending unlock requests: list of {device_id, timestamp}
pending_unlock_requests: list = []
# Pending remote block requests: list of {device_id, timestamp}
pending_block_requests: list = []

# ─── Models ───────────────────────────────────────────────────────────────────

class ConfigUpdate(BaseModel):
    master_password: Optional[str] = None
    allowed_apps: Optional[List[str]] = None
    block_gps: Optional[bool] = None
    block_datetime: Optional[bool] = None
    clear_data_password: Optional[str] = None
    unlock_pattern: Optional[str] = None
    data_off_password: Optional[str] = None

class UnlockDecision(BaseModel):
    device_id: str
    password: str
    approve: bool

class DeviceRename(BaseModel):
    device_id: str
    name: str

class BlockCommand(BaseModel):
    device_id: str

class DownloadApkUrlRequest(BaseModel):
    url: str

class DeviceClearDataPassword(BaseModel):
    device_id: str
    password: str

class DeviceAppToggle(BaseModel):
    device_id: str
    package_name: str
    allowed: bool


# ─── REST Endpoints ───────────────────────────────────────────────────────────

@app.get("/api/provision")
async def provision(
    request: Request,
    deviceId: str = "unknown",
    deviceKey: Optional[str] = None
):
    """Called by Android app on first launch to get full configuration."""
    config = load_config()
    device = register_device(deviceId, deviceKey)
    print(f"📱 Device provisioning: {deviceId} (key={device.get('device_key')})")

    request_url_base = get_request_base_url(request)
    ws_scheme = "wss" if request_url_base.startswith("https") else "ws"
    host_part = request_url_base.split("://")[1]
    server_ws_url = f"{ws_scheme}://{host_part}/ws"
    apks = get_apks_list(request_url_base)

    global_clear_pass = config.get("clear_data_password", "5678")
    device_clear_pass = device.get("clear_data_password") if device else None
    clear_pass = device_clear_pass if device_clear_pass else global_clear_pass

    device_allowed_apps = device.get("allowed_apps") if device else None
    allowed_apps = device_allowed_apps if device_allowed_apps is not None else config.get("allowed_apps", [])

    return {
        "status": "ok",
        "deviceId": deviceId,
        "deviceKey": device.get("device_key"),
        "deviceName": device.get("name", "") if device else "",
        "allowedApps": allowed_apps,
        "alwaysBlocked": ALWAYS_BLOCKED,
        "alwaysAllowed": ALWAYS_ALLOWED,
        "blockGps": config.get("block_gps", True),
        "blockDateTime": config.get("block_datetime", True),
        "localPassword": config.get("master_password", "1234"),
        "unlockPattern": config.get("unlock_pattern", "012"),
        "blocked": device.get("blocked", False),
        "serverWsUrl": server_ws_url,
        "apks": apks,
        "clearDataPassword": clear_pass,
        "dataOffPassword": config.get("data_off_password", "4321")
    }


@app.get("/api/enrollment-config")
async def get_enrollment_config(
    request: Request,
    wifi_ssid: Optional[str] = None,
    wifi_password: Optional[str] = None,
    wifi_security: Optional[str] = None
):
    """Generates the JSON payload for Android Enterprise QR code enrollment."""
    server_url = get_request_base_url(request)
    apk_filename = "com.example.applocker.apk"
    download_url = f"{server_url}/apks/{apk_filename}"
    signature_checksum = get_apk_signature_checksum(apk_filename)
    
    # Calculate SHA-256 package checksum of the APK file
    import hashlib, base64
    file_path = os.path.join(APKS_DIR, apk_filename)
    package_checksum = ""
    if os.path.exists(file_path):
        try:
            hasher = hashlib.sha256()
            with open(file_path, "rb") as f:
                for chunk in iter(lambda: f.read(4096), b""):
                    hasher.update(chunk)
            package_checksum = base64.urlsafe_b64encode(hasher.digest()).decode('utf-8').rstrip('=')
        except Exception as e:
            print(f"Error calculating APK file checksum: {e}")
            
    config = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.example.applocker/.AppLockerDeviceAdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": signature_checksum,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": download_url,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": package_checksum,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
            "server_url": server_url
        }
    }
    
    if wifi_ssid:
        config["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi_ssid
        if wifi_security:
            config["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = wifi_security
        else:
            config["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = "WPA" if wifi_password else "NONE"
            
        if wifi_password:
            config["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifi_password
            
    return {
        "server_url": server_url,
        "signature_checksum": signature_checksum,
        "package_checksum": package_checksum,
        "download_url": download_url,
        "qr_json": config
    }


@app.get("/api/config")
async def get_config():
    """Get current server config (without password)."""
    config = load_config()
    return {
        "allowed_apps": config.get("allowed_apps", []),
        "block_gps": config.get("block_gps", True),
        "block_datetime": config.get("block_datetime", True),
        "always_blocked": ALWAYS_BLOCKED,
        "always_allowed": ALWAYS_ALLOWED,
        "clear_data_password": config.get("clear_data_password", "5678"),
        "unlock_pattern": config.get("unlock_pattern", "012"),
        "data_off_password": config.get("data_off_password", "4321")
    }

@app.put("/api/config")
async def update_config(update: ConfigUpdate):
    """Update server config from web dashboard."""
    config = load_config()

    if update.master_password is not None:
        config["master_password"] = update.master_password
    if update.allowed_apps is not None:
        config["allowed_apps"] = update.allowed_apps
    if update.block_gps is not None:
        config["block_gps"] = update.block_gps
    if update.block_datetime is not None:
        config["block_datetime"] = update.block_datetime
    if update.clear_data_password is not None:
        config["clear_data_password"] = update.clear_data_password
    if update.unlock_pattern is not None:
        config["unlock_pattern"] = update.unlock_pattern
    if update.data_off_password is not None:
        config["data_off_password"] = update.data_off_password

    save_config(config)
    await push_config_to_devices()
    return {"success": True}


@app.get("/api/devices")
async def get_devices():
    """List known Android devices, their names, and pending unlock requests."""
    config = load_config()
    db = load_devices_db()
    device_names = {device["device_id"]: device.get("name", "") for device in db.get("devices", [])}

    devices = []
    for device in db.get("devices", []):
        info = active_devices.get(device["device_id"])
        devices.append({
            "id": device["device_id"],
            "name": device.get("name", ""),
            "status": "connected" if info else "offline",
            "blocked": device.get("blocked", False),
            "connected_at": info.get("connected_at", 0) if info else 0,
            "created_at": device.get("created_at", 0),
            "last_seen": device.get("last_seen", 0),
            "clear_data_password": device.get("clear_data_password", ""),
            "allowed_apps": device.get("allowed_apps"),
            "installed_apps": device.get("installed_apps", [])
        })

    for d_id, info in active_devices.items():
        if not find_device_record(d_id, db):
            devices.append({
                "id": d_id,
                "name": "",
                "status": "connected",
                "blocked": False,
                "connected_at": info.get("connected_at", 0),
                "created_at": 0,
                "last_seen": info.get("connected_at", 0),
            })

    return {
        "devices": devices,
        "pendingUnlocks": pending_unlock_requests,
        "pendingBlockRequests": pending_block_requests,
        "device_names": device_names
    }

@app.post("/api/devices/sync")
async def sync_devices():
    """Manually force all connected devices to sync configuration and APKs."""
    await push_config_to_devices()
    return {"success": True}

@app.post("/api/device/rename")

async def rename_device(rename: DeviceRename):
    """Rename a device (custom label for the dashboard)."""
    if rename.name.strip():
        register_device(rename.device_id, name=rename.name.strip())
    else:
        device_db = load_devices_db()
        device = find_device_record(rename.device_id, device_db)
        if device:
            device["name"] = ""
            save_devices_db(device_db)
    print(f"📛 Device '{rename.device_id}' renamed to '{rename.name.strip()}'")
    return {"success": True}

@app.post("/api/device/clear-data-password")
async def set_device_clear_data_password(cmd: DeviceClearDataPassword):
    db = load_devices_db()
    device = find_device_record(cmd.device_id, db)
    if not device:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no encontrado"})
    
    if cmd.password.strip():
        device["clear_data_password"] = cmd.password.strip()
    else:
        device.pop("clear_data_password", None)
    
    save_devices_db(db)
    await push_config_to_devices()
    return {"success": True}

@app.post("/api/device/app-toggle")
async def toggle_device_app(cmd: DeviceAppToggle):
    db = load_devices_db()
    device = find_device_record(cmd.device_id, db)
    if not device:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no encontrado"})
    
    config = load_config()
    if "allowed_apps" not in device or device["allowed_apps"] is None:
        device["allowed_apps"] = list(config.get("allowed_apps", []))
        
    allowed_list = device["allowed_apps"]
    if cmd.allowed:
        if cmd.package_name not in allowed_list:
            allowed_list.append(cmd.package_name)
    else:
        if cmd.package_name in allowed_list:
            allowed_list.remove(cmd.package_name)
            
    save_devices_db(db)
    await push_config_to_devices()
    return {"success": True, "allowed_apps": device["allowed_apps"]}

@app.post("/api/device/apps-reset")
async def reset_device_apps(cmd: BlockCommand):
    db = load_devices_db()
    device = find_device_record(cmd.device_id, db)
    if not device:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no encontrado"})
    
    device.pop("allowed_apps", None)
    save_devices_db(db)
    await push_config_to_devices()
    return {"success": True}

@app.delete("/api/device/{device_id}")
async def remove_device(device_id: str, pin: Optional[str] = None):
    """Disconnect and remove a device from the registry."""
    config = load_config()
    if pin != config.get("master_password", ""):
        return JSONResponse(status_code=401, content={"error": "PIN/Contraseña de administrador incorrecta"})

    info = active_devices.pop(device_id, None)
    if info:
        try:
            await info["ws"].close()
        except Exception:
            pass
            
    # Remove from devices.json database
    db = load_devices_db()
    db["devices"] = [d for d in db.get("devices", []) if d.get("device_id") != device_id]
    save_devices_db(db)

    # Remove from pending requests
    global pending_unlock_requests, pending_block_requests
    pending_unlock_requests = [r for r in pending_unlock_requests if r["device_id"] != device_id]
    pending_block_requests = [r for r in pending_block_requests if r["device_id"] != device_id]
    print(f"🗑️ Device removed: {device_id}")
    return {"success": True}

@app.delete("/api/devices/all")
async def clear_all_devices(pin: Optional[str] = None):
    """Disconnect and remove ALL devices."""
    config = load_config()
    if pin != config.get("master_password", ""):
        return JSONResponse(status_code=401, content={"error": "PIN/Contraseña de administrador incorrecta"})

    global pending_unlock_requests
    for d_id, info in list(active_devices.items()):
        try:
            await info["ws"].close()
        except Exception:
            pass
    active_devices.clear()
    pending_unlock_requests.clear()
    pending_block_requests.clear()

    # Clear devices.json database
    db = load_devices_db()
    db["devices"] = []
    save_devices_db(db)

    print("🧹 All devices cleared")
    return {"success": True}

@app.post("/api/unlock")
async def decide_unlock(decision: UnlockDecision):
    """Approve or deny a pending unlock request from the dashboard."""
    config = load_config()

    if decision.approve and decision.password != config.get("master_password", ""):
        return JSONResponse(status_code=401, content={"error": "Contraseña incorrecta"})

    global pending_unlock_requests
    pending_unlock_requests = [
        r for r in pending_unlock_requests if r["device_id"] != decision.device_id
    ]

    device_info = active_devices.get(decision.device_id)
    if not device_info:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no conectado"})

    action = "unlock_granted" if decision.approve else "unlock_denied"
    try:
        await device_info["ws"].send_text(json.dumps({"action": action}))
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

    return {"success": True, "action": action}

@app.post("/api/block")
async def send_block(command: BlockCommand):
    """Send a block command to a connected device."""
    global pending_block_requests
    device_info = active_devices.get(command.device_id)
    if not device_info:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no conectado"})

    device_db = load_devices_db()
    device = find_device_record(command.device_id, device_db)
    if device:
        device["blocked"] = True
        save_devices_db(device_db)

    pending_block_requests = [r for r in pending_block_requests if r["device_id"] != command.device_id]
    try:
        await device_info["ws"].send_text(json.dumps({"action": "block_granted"}))
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

    return {"success": True, "action": "block_granted"}

@app.post("/api/unblock")
async def send_unblock(command: BlockCommand):
    """Send an unblock command to a connected device."""
    global pending_block_requests
    device_info = active_devices.get(command.device_id)
    if not device_info:
        return JSONResponse(status_code=404, content={"error": "Dispositivo no conectado"})

    device_db = load_devices_db()
    device = find_device_record(command.device_id, device_db)
    if device:
        device["blocked"] = False
        save_devices_db(device_db)

    pending_block_requests = [r for r in pending_block_requests if r["device_id"] != command.device_id]
    try:
        await device_info["ws"].send_text(json.dumps({"action": "unblock"}))
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

    return {"success": True, "action": "unblock"}

# ─── WebSocket ────────────────────────────────────────────────────────────────

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    registered_id = None

    try:
        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
                msg_type = msg.get("type")

                if msg_type == "register":
                    registered_id = msg.get("deviceId", "unknown")
                    device_key = msg.get("deviceKey")
                    installed_apps = msg.get("installedApps")
                    device = register_device(registered_id, device_key, installed_apps=installed_apps)
                    active_devices[registered_id] = {
                        "ws": websocket,
                        "connected_at": time.time()
                    }
                    print(f"📱 Device registered: {registered_id} (key={device.get('device_key')})")

                    config = load_config()
                    http_base_url = get_request_base_url(websocket)
                    apks = get_apks_list(http_base_url)

                    global_clear_pass = config.get("clear_data_password", "5678")
                    device_clear_pass = device.get("clear_data_password") if device else None
                    clear_pass = device_clear_pass if device_clear_pass else global_clear_pass

                    device_allowed_apps = device.get("allowed_apps") if device else None
                    allowed_apps = device_allowed_apps if device_allowed_apps is not None else config.get("allowed_apps", [])

                    await websocket.send_text(json.dumps({
                        "type": "registered",
                        "deviceId": registered_id,
                        "deviceKey": device.get("device_key"),
                        "deviceName": device.get("name", "") if device else "",
                        "allowedApps": allowed_apps,
                        "blockGps": config.get("block_gps", True),
                        "blockDateTime": config.get("block_datetime", True),
                        "localPassword": config.get("master_password", "1234"),
                        "unlockPattern": config.get("unlock_pattern", "012"),
                        "blocked": device.get("blocked", False),
                        "apks": apks,
                        "clearDataPassword": clear_pass,
                        "dataOffPassword": config.get("data_off_password", "4321")
                    }))

                elif msg_type == "installed_apps_update":
                    device_id = msg.get("deviceId", registered_id)
                    device_key = msg.get("deviceKey")
                    installed_apps = msg.get("installedApps")
                    device = register_device(device_id, device_key, installed_apps=installed_apps)
                    print(f"📱 Installed apps updated for: {device_id} ({len(installed_apps)} apps)")


                elif msg_type == "unlock_request":
                    device_id = msg.get("deviceId", registered_id)
                    device_key = msg.get("deviceKey")
                    print(f"🔓 Unlock request from: {device_id}")

                    device_db = load_devices_db()
                    device = find_device_record(device_id, device_db)
                    if device and device_key and device.get("device_key") != device_key:
                        print(f"⚠️ Unlock request key mismatch for {device_id}")

                    if device:
                        device["last_unlock_request_at"] = time.time()
                        save_devices_db(device_db)

                    existing_ids = [r["device_id"] for r in pending_unlock_requests]
                    if device_id not in existing_ids:
                        pending_unlock_requests.append({
                            "device_id": device_id,
                            "timestamp": time.time()
                        })

                elif msg_type == "block_request":
                    device_id = msg.get("deviceId", registered_id)
                    device_key = msg.get("deviceKey")
                    print(f"🚫 Block request from: {device_id}")

                    device_db = load_devices_db()
                    device = find_device_record(device_id, device_db)
                    if device and device_key and device.get("device_key") != device_key:
                        print(f"⚠️ Block request key mismatch for {device_id}")

                    existing_ids = [r["device_id"] for r in pending_block_requests]
                    if device_id not in existing_ids:
                        pending_block_requests.append({
                            "device_id": device_id,
                            "timestamp": time.time()
                        })

            except json.JSONDecodeError:
                pass

    except WebSocketDisconnect:
        if registered_id:
            active_devices.pop(registered_id, None)
            print(f"📴 Device disconnected: {registered_id}")
    except Exception as e:
        print(f"⚠️ WebSocket error [{registered_id}]: {e}")
        if registered_id:
            active_devices.pop(registered_id, None)

@app.get("/api/apks/search")
async def search_apks(q: str):
    """Searches the Aptoide app directory for package details to import."""
    query = q.strip()
    if not query:
        return {"apps": []}
        
    api_url = f"https://ws75.aptoide.com/api/7/apps/search/query={requests.utils.quote(query)}"
    try:
        res = requests.get(api_url, timeout=10)
        if res.status_code == 200:
            data = res.json()
            items = data.get("datalist", {}).get("list", [])
            apps = []
            for item in items:
                apps.append({
                    "name": item.get("name"),
                    "packageName": item.get("package"),
                    "icon": item.get("icon"),
                    "version": item.get("file", {}).get("vername", ""),
                    "size": item.get("size", 0)
                })
            return {"apps": apps}
    except Exception as e:
        print(f"⚠️ Error searching Aptoide: {e}")
        
    return {"apps": []}

# ─── APK Management Endpoints ──────────────────────────────────────────────────

@app.post("/api/apks/download-url")
async def download_apk_url(payload: DownloadApkUrlRequest):
    url_or_pkg = payload.url.strip()
    if not url_or_pkg:
        return JSONResponse(status_code=400, content={"error": "La URL o el nombre del paquete no pueden estar vacíos."})
    
    # 1. Parse package name
    package_name = None
    parsed_url = urlparse(url_or_pkg)
    if parsed_url.scheme in ('http', 'https'):
        qs = parse_qs(parsed_url.query)
        if 'id' in qs:
            package_name = qs['id'][0]
        else:
            # Try to find package name pattern in URL path or query
            match = re.search(r'([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)', url_or_pkg)
            if match:
                package_name = match.group(1)
    else:
        # Check if it looks like a package name
        if re.match(r'^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$', url_or_pkg):
            package_name = url_or_pkg

    if not package_name:
        return JSONResponse(status_code=400, content={"error": "No se pudo extraer un nombre de paquete de Android válido."})
    
    print(f"📥 Intentando descargar el paquete: {package_name}")

    apk_filename = f"{package_name}.apk"
    file_path = os.path.join(APKS_DIR, apk_filename)
    download_success = False
    error_msg = ""

    # 2. Try APKPure first (covers official Play Store catalog)
    apkpure_url = f"https://d.apkpure.com/b/XAPK/{package_name}?version=latest"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    print(f"🔗 Intentando descargar desde APKPure: {apkpure_url}")
    try:
        res = requests.get(apkpure_url, headers=headers, stream=True, allow_redirects=True, timeout=30)
        if res.status_code == 200:
            temp_path = file_path + ".tmp"
            with open(temp_path, "wb") as f:
                for chunk in res.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
            
            import zipfile
            is_zip = zipfile.is_zipfile(temp_path)
            has_nested_apks = False
            if is_zip:
                try:
                    with zipfile.ZipFile(temp_path) as z:
                        apk_files = [name for name in z.namelist() if name.endswith(".apk")]
                        has_nested_apks = len(apk_files) > 0
                except Exception:
                    pass

            if is_zip and has_nested_apks:
                print(f"📦 El archivo descargado es un XAPK/ZIP, extrayendo el APK base...")
                with zipfile.ZipFile(temp_path) as z:
                    apk_files = [name for name in z.namelist() if name.endswith(".apk")]
                    base_apk = "base.apk" if "base.apk" in apk_files else max(apk_files, key=lambda name: z.getinfo(name).file_size)
                    with open(file_path, "wb") as out_f:
                        out_f.write(z.read(base_apk))
                    print(f"✅ APK base '{base_apk}' extraído con éxito como {apk_filename}")
                    download_success = True
                
                # Save the full XAPK file next to the extracted base APK
                xapk_path = file_path.replace(".apk", ".xapk")
                try:
                    if os.path.exists(xapk_path):
                        os.remove(xapk_path)
                    os.rename(temp_path, xapk_path)
                    print(f"✅ XAPK completo guardado como {os.path.basename(xapk_path)}")
                except Exception as e:
                    print(f"⚠️ Error al guardar XAPK: {e}")
                    try:
                        os.remove(temp_path)
                    except Exception:
                        pass
            else:
                if os.path.exists(file_path):
                    os.remove(file_path)
                os.rename(temp_path, file_path)
                print(f"✅ APK estándar descargada desde APKPure: {apk_filename}")
                download_success = True
        else:
            print(f"⚠️ APKPure devolvió código de estado {res.status_code}")
            error_msg = f"Código de estado APKPure: {res.status_code}"
    except Exception as e:
        print(f"⚠️ Error intentando descargar desde APKPure: {e}")
        error_msg = str(e)

    # 3. Fallback to Aptoide if APKPure failed
    if not download_success:
        print(f"🔄 Fallback: Intentando descargar desde Aptoide para: {package_name}")
        api_url = f"https://ws75.aptoide.com/api/7/app/get/package_name={package_name}"
        try:
            res = requests.get(api_url, timeout=15)
            if res.status_code == 200:
                data = res.json()
                download_path = data.get("nodes", {}).get("meta", {}).get("data", {}).get("file", {}).get("path")
                if download_path:
                    print(f"🔗 Descargando desde Aptoide: {download_path}")
                    with requests.get(download_path, stream=True, timeout=60) as r:
                        r.raise_for_status()
                        with open(file_path, "wb") as f:
                            for chunk in r.iter_content(chunk_size=8192):
                                if chunk:
                                     f.write(chunk)
                    print(f"✅ APK descargada desde Aptoide: {apk_filename}")
                    download_success = True
                else:
                    errors = data.get("errors", [])
                    err_msg = " - ".join([str(e.get("msg", "")) for e in errors if isinstance(e, dict)])
                    print(f"❌ No se encontró ruta en Aptoide. Detalle: {err_msg}")
                    error_msg = f"Aptoide error: {err_msg}"
            else:
                print(f"❌ Aptoide API devolvió código de estado {res.status_code}")
                error_msg = f"Aptoide API status: {res.status_code}"
        except Exception as e:
            print(f"⚠️ Error intentando descargar desde Aptoide: {e}")
            error_msg = str(e)

    if download_success:
        # 4. Notify connected devices
        await push_config_to_devices()
        return {"success": True, "packageName": package_name, "filename": apk_filename}
    else:
        return JSONResponse(status_code=500, content={"error": f"No se pudo descargar la APK. Detalle: {error_msg}"})


@app.get("/api/apks")
async def get_apks(request: Request):
    """List all available APKs for synchronization."""
    request_url_base = get_request_base_url(request)
    return {"apks": get_apks_list(request_url_base)}

@app.post("/api/apks/upload")
async def upload_apk(file: UploadFile = File(...)):
    """Upload an APK file to the sync folder."""
    if not file.filename.endswith(".apk"):
        return JSONResponse(status_code=400, content={"error": "Solo se permiten archivos .apk"})
    
    # Clean filename to prevent path traversal
    safe_filename = os.path.basename(file.filename)
    file_path = os.path.join(APKS_DIR, safe_filename)
    with open(file_path, "wb") as f:
        f.write(await file.read())
    print(f"📥 APK subida: {safe_filename}")
    await push_config_to_devices()
    return {"success": True, "filename": safe_filename}

@app.delete("/api/apks/{filename}")
async def delete_apk(filename: str):
    """Delete an APK or XAPK file from the sync folder."""
    safe_filename = os.path.basename(filename)
    base_name = safe_filename
    if base_name.endswith(".apk"):
        base_name = base_name[:-4]
    elif base_name.endswith(".xapk"):
        base_name = base_name[:-5]
        
    apk_path = os.path.join(APKS_DIR, f"{base_name}.apk")
    xapk_path = os.path.join(APKS_DIR, f"{base_name}.xapk")
    
    deleted = False
    if os.path.exists(apk_path):
        os.remove(apk_path)
        print(f"🗑️ APK eliminada: {base_name}.apk")
        deleted = True
    if os.path.exists(xapk_path):
        os.remove(xapk_path)
        print(f"🗑️ XAPK eliminada: {base_name}.xapk")
        deleted = True
        
    if deleted:
        await push_config_to_devices()
        return {"success": True}
        
    return JSONResponse(status_code=404, content={"error": f"Archivo no encontrado: {safe_filename}"})

import shutil
import subprocess
import threading

def get_adb_path() -> str:
    path = shutil.which("adb")
    if path:
        return path
    fallback = r"C:\Users\Soporte\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    if os.path.exists(fallback):
        return fallback
    return "adb"

adb_status = "idle"  # "idle" | "running" | "success" | "error"
adb_logs = []
adb_lock = threading.Lock()

def add_adb_log(msg: str):
    with adb_lock:
        print(f"[ADB Installer] {msg}")
        adb_logs.append(f"[{time.strftime('%H:%M:%S')}] {msg}")

def run_adb_cmd(args: list) -> tuple[int, str, str]:
    adb_path = get_adb_path()
    cmd = [adb_path] + args
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", timeout=60)
        return res.returncode, res.stdout, res.stderr
    except subprocess.TimeoutExpired:
        return -99, "", "Command timed out"
    except Exception as e:
        return -1, "", str(e)

def install_app_via_adb(device_id: str, apk_path: str) -> tuple[int, str, str]:
    xapk_path = apk_path.replace(".apk", ".xapk")
    import zipfile
    is_xapk = False
    target_zip = None
    
    if os.path.exists(xapk_path) and zipfile.is_zipfile(xapk_path):
        is_xapk = True
        target_zip = xapk_path
    elif os.path.exists(apk_path) and zipfile.is_zipfile(apk_path):
        try:
            with zipfile.ZipFile(apk_path) as z:
                apk_files = [name for name in z.namelist() if name.endswith(".apk")]
                if len(apk_files) > 0:
                    is_xapk = True
                    target_zip = apk_path
        except Exception:
            pass

    if is_xapk and target_zip:
        add_adb_log(f"Instalando como XAPK (múltiples splits) desde {os.path.basename(target_zip)}...")
        import tempfile
        temp_dir = tempfile.mkdtemp()
        try:
            apk_paths_to_install = []
            with zipfile.ZipFile(target_zip) as z:
                for name in z.namelist():
                    if name.endswith(".apk"):
                        extracted_path = z.extract(name, temp_dir)
                        apk_paths_to_install.append(extracted_path)
            
            if apk_paths_to_install:
                args = ["-s", device_id, "install-multiple", "-r", "-t", "-g"] + apk_paths_to_install
                code, out, err = run_adb_cmd(args)
                return code, out, err
            else:
                return -1, "", "No apk files found inside XAPK"
        except Exception as e:
            return -1, "", f"Error extracting XAPK: {str(e)}"
        finally:
            try:
                shutil.rmtree(temp_dir)
            except Exception:
                pass
    else:
        add_adb_log(f"Instalando como APK estándar: {os.path.basename(apk_path)}...")
        return run_adb_cmd(["-s", device_id, "install", "-r", "-t", "-g", apk_path])

class AdbProvisionRequest(BaseModel):
    device_id: str
    install_lucema: bool = True
    install_defaults: bool = True

def bg_provision_device(device_id: str, install_lucema: bool, install_defaults: bool):
    global adb_status
    with adb_lock:
        adb_status = "running"
        adb_logs.clear()
        
    add_adb_log(f"Iniciando aprovisionamiento para el dispositivo: {device_id}")
    
    code, out, err = run_adb_cmd(["-s", device_id, "get-state"])
    state = out.strip() if code == 0 else ""
    if state != "device":
        add_adb_log(f"Error: El dispositivo no está listo o no está autorizado. Estado actual: {state or 'desconectado'}. Error: {err}")
        with adb_lock:
            adb_status = "error"
        return
        
    flshield_apk_path = os.path.join(APKS_DIR, "com.example.applocker.apk")
    if not os.path.exists(flshield_apk_path):
        add_adb_log("Error: No se encontró com.example.applocker.apk en la carpeta de APKs. Por favor, sube el APK al panel.")
        with adb_lock:
            adb_status = "error"
        return

    add_adb_log("Desinstalando versiones anteriores de FLShield para evitar conflictos de firmas...")
    run_adb_cmd(["-s", device_id, "uninstall", "com.example.applocker"])

    add_adb_log(f"Instalando FLShield en el dispositivo...")
    code, out, err = install_app_via_adb(device_id, flshield_apk_path)
    if code != 0:
        add_adb_log(f"Error al instalar FLShield: {err or out}")
        with adb_lock:
            adb_status = "error"
        return
    add_adb_log("FLShield instalado correctamente.")

    add_adb_log("Estableciendo FLShield como Device Owner (Kiosco Completo)...")
    code, out, err = run_adb_cmd(["-s", device_id, "shell", "dpm", "set-device-owner", "com.example.applocker/.AppLockerDeviceAdminReceiver"])
    if code != 0:
        add_adb_log(f"Advertencia/Error al configurar Device Owner: {err or out}")
        add_adb_log("Nota: dpm set-device-owner requiere que no haya cuentas registradas en el dispositivo (ej. Google) ni perfiles de usuario adicionales.")
    else:
        add_adb_log("FLShield configurado como Device Owner con éxito.")

    if install_lucema:
        lucema_apk_path = os.path.join(APKS_DIR, "com.ionicframework.sfaLucema.apk")
        if os.path.exists(lucema_apk_path):
            add_adb_log("Desinstalando versiones anteriores de SFA Lucema para evitar conflictos...")
            run_adb_cmd(["-s", device_id, "uninstall", "com.ionicframework.sfaLucema"])

            add_adb_log(f"Instalando SFA Lucema...")
            code, out, err = install_app_via_adb(device_id, lucema_apk_path)
            if code != 0:
                add_adb_log(f"Error al instalar SFA Lucema: {err or out}")
            else:
                add_adb_log("SFA Lucema instalado correctamente.")
        else:
            add_adb_log("Advertencia: No se encontró com.ionicframework.sfaLucema.apk en la carpeta de APKs. Saltando instalación.")

    if install_defaults:
        add_adb_log("Buscando otras apps predeterminadas para instalar...")
        for file in os.listdir(APKS_DIR):
            if file.endswith(".apk") and file not in ["com.example.applocker.apk", "com.ionicframework.sfaLucema.apk", "SFA Lucema.apk", "SFA Lucema (1).apk"]:
                full_path = os.path.join(APKS_DIR, file)
                pkg_name = extract_package_name(file)
                if pkg_name:
                    add_adb_log(f"Desinstalando versión anterior de {pkg_name} para evitar conflictos...")
                    run_adb_cmd(["-s", device_id, "uninstall", pkg_name])

                add_adb_log(f"Instalando app adicional: {file}...")
                code, out, err = install_app_via_adb(device_id, full_path)
                if code != 0:
                    add_adb_log(f"Error al instalar {file}: {err or out}")
                else:
                    add_adb_log(f"{file} instalado correctamente.")

    add_adb_log("Iniciando FLShield en el dispositivo...")
    code, out, err = run_adb_cmd(["-s", device_id, "shell", "am", "start", "-n", "com.example.applocker/.MainActivity"])
    if code != 0:
        add_adb_log(f"Advertencia al iniciar la actividad: {err or out}")
    else:
        add_adb_log("Actividad principal de FLShield iniciada.")

    add_adb_log("Aprovisionamiento ADB finalizado con éxito! 🎉")
    with adb_lock:
        adb_status = "success"

@app.get("/api/adb/devices")
async def get_adb_devices():
    adb_path = get_adb_path()
    try:
        res = subprocess.run([adb_path, "devices", "-l"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", timeout=10)
        if res.returncode != 0:
            return JSONResponse(status_code=500, content={"error": f"Error al ejecutar ADB: {res.stderr}"})
        
        devices = []
        lines = res.stdout.strip().splitlines()
        for line in lines[1:]:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            device_id = parts[0]
            status = parts[1]
            
            model = "Dispositivo desconocido"
            for part in parts[2:]:
                if part.startswith("model:"):
                    model = part.split(":")[1].replace("_", " ")
                    break
                elif part.startswith("device:"):
                    model = part.split(":")[1].replace("_", " ")
            
            devices.append({
                "id": device_id,
                "status": status,
                "model": model
            })
        return {"devices": devices}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": f"Error interno: {str(e)}"})

@app.get("/api/adb/status")
async def get_adb_status():
    with adb_lock:
        return {
            "status": adb_status,
            "logs": adb_logs
        }

@app.post("/api/adb/provision")
async def start_adb_provision(req: AdbProvisionRequest, background_tasks: BackgroundTasks):
    global adb_status
    with adb_lock:
        if adb_status == "running":
            return JSONResponse(status_code=400, content={"error": "Ya hay un aprovisionamiento ADB en progreso."})
    
    background_tasks.add_task(
        bg_provision_device,
        req.device_id,
        req.install_lucema,
        req.install_defaults
    )
    return {"success": True}

# ─── Authentication endpoints ──────────────────────────────────────────────────

class LoginRequest(BaseModel):
    username: str
    password: str

@app.post("/api/auth/login")
async def login(credentials: LoginRequest):
    if credentials.username == ADMIN_USER and credentials.password == ADMIN_PASS:
        session_token = secrets.token_hex(32)
        active_sessions.add(session_token)
        response = JSONResponse(content={"success": True, "message": "Login successful"})
        response.set_cookie(
            key="flshield_session",
            value=session_token,
            httponly=True,
            max_age=30 * 24 * 3600,
            samesite="lax",
            secure=False
        )
        return response
    return JSONResponse(status_code=400, content={"success": False, "message": "Usuario o contraseña incorrectos"})

@app.get("/api/auth/status")
async def auth_status(request: Request):
    session_token = request.cookies.get("flshield_session")
    if session_token and session_token in active_sessions:
        return {"authenticated": True, "user": ADMIN_USER}
    return {"authenticated": False}

@app.post("/api/auth/logout")
async def logout():
    response = JSONResponse(content={"success": True, "message": "Logged out"})
    response.delete_cookie(key="flshield_session")
    return response

# ─── Static files (web dashboard) ────────────────────────────────────────────


app.mount("/", StaticFiles(directory=os.path.join(base_dir, "public"), html=True), name="static")

# ─── Entry point ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys, io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

    port = int(os.environ.get("PORT", 3000))
    print("=" * 55)
    print("  AppLocker Control Server v3")
    print(f"  Panel Web: http://localhost:{port}")
    print(f"  Provision URL Android: http://<TU_IP>:{port}")
    print("=" * 55)
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
