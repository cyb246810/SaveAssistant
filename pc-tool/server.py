#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
保存助手 · 电脑端传输工作台 (SaveAssistant PC Transfer Workbench)
纯标准库实现：本地 HTTP 服务 = UI 托管 + 局域网收文件 + SSE 实时推送。
启动后浏览器打开 http://127.0.0.1:<port>/ 即为工作台。
手机端（后续版本）连本机 <局域网IP>:<port> 调 /api/device/announce 配对、/api/upload 推文件。
"""
import os
import sys
import json
import time
import uuid
import threading
import mimetypes
import webbrowser
import shutil
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime

BASE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(BASE, "static")
ASSETS = os.path.join(BASE, "assets")
CONFIG_PATH = os.path.join(BASE, "config.json")

DEFAULT_CONFIG = {
    "port": 18765,
    "host": "0.0.0.0",
    "device_name": "我的电脑",
    "transfer_dir": os.path.join(os.path.dirname(BASE), "Received"),
    "auto_open_browser": True,
    "version": "1.0.3",
}

CATEGORY_LABELS = {
    "video": "视频",
    "image": "图片",
    "cover": "封面",
    "audio": "音频",
    "other": "其他",
}

# 文件名没有扩展名时按类别补一个，否则电脑上是一个双击打不开、无法播放的文件。
# 手机端已按 MIME 补过，这里是第二道防线（防止其它客户端再传进来无后缀的文件）。
EXT_BY_CATEGORY = {
    "video": ".mp4",
    "image": ".jpg",
    "cover": ".jpg",
    "audio": ".m4a",
    "other": "",
}

# ---------------------------------------------------------------------------
# 配置读写
# ---------------------------------------------------------------------------
def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            merged = dict(DEFAULT_CONFIG)
            merged.update(cfg)
            return merged
        except Exception:
            pass
    cfg = dict(DEFAULT_CONFIG)
    save_config(cfg)
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


CFG = load_config()
TRANSFER_DIR = CFG["transfer_dir"]

# ---------------------------------------------------------------------------
# 运行态（线程安全）
# ---------------------------------------------------------------------------
state_lock = threading.Lock()
devices = {}          # device_id -> {name, model, ip, last_seen, online}
records = []          # 接收到的文件记录（内存缓存，启动时会从磁盘扫描补齐）
subscribers = []      # SSE 订阅队列
sse_lock = threading.Lock()


def emit(event):
    with sse_lock:
        for q in list(subscribers):
            try:
                q.put(event)
            except Exception:
                pass


def local_ips():
    ips = []
    try:
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        import socket
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    if not ips:
        ips = ["127.0.0.1"]
    return ips


def free_space(path):
    try:
        total, used, free = shutil.disk_usage(path)
        return {"total": total, "used": used, "free": free}
    except Exception:
        return {"total": 0, "used": 0, "free": 0}


def secure_filename(name):
    if not name:
        return "file"
    name = os.path.basename(name.replace("\\", "/"))
    # 去掉可能的风险字符，保留中文与常用符号
    keep = []
    for ch in name:
        if ch in '<>:"/\\|?*':
            continue
        keep.append(ch)
    name = "".join(keep).strip()
    if not name:
        name = "file"
    return name


def ensure_transfer_dir():
    os.makedirs(TRANSFER_DIR, exist_ok=True)
    for cat in CATEGORY_LABELS:
        os.makedirs(os.path.join(TRANSFER_DIR, cat), exist_ok=True)


def scan_disk_records():
    """从磁盘扫描已接收文件，返回记录列表（新→旧）。"""
    out = []
    if not os.path.isdir(TRANSFER_DIR):
        return out
    for cat in CATEGORY_LABELS:
        d = os.path.join(TRANSFER_DIR, cat)
        if not os.path.isdir(d):
            continue
        for fn in os.listdir(d):
            fp = os.path.join(d, fn)
            if os.path.isfile(fp):
                st = os.stat(fp)
                out.append({
                    "id": uuid.uuid4().hex,
                    "name": fn,
                    "category": cat,
                    "category_label": CATEGORY_LABELS.get(cat, cat),
                    "size": st.st_size,
                    "device": "—",
                    "time": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
                    "path": fp,
                })
    out.sort(key=lambda r: os.path.getmtime(r["path"]), reverse=True)
    return out


def refresh_records():
    global records
    with state_lock:
        records = scan_disk_records()


# ---------------------------------------------------------------------------
# 设备心跳清理
# ---------------------------------------------------------------------------
def device_cleaner():
    while True:
        time.sleep(20)
        now = time.time()
        changed = False
        with state_lock:
            for did in list(devices.keys()):
                if now - devices[did].get("last_seen", 0) > 60:
                    devices[did]["online"] = False
                    changed = True
        if changed:
            emit({"type": "devices", "devices": device_snapshot()})


def device_snapshot():
    with state_lock:
        return [
            {
                "id": did,
                "name": d["name"],
                "model": d.get("model", ""),
                "ip": d.get("ip", ""),
                "online": d.get("online", False),
                "last_seen": d.get("last_seen", 0),
            }
            for did, d in devices.items()
        ]


# ---------------------------------------------------------------------------
# HTTP 处理器
# ---------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "SaveAssistantPC/" + CFG.get("version", "1.0.0")
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 静默日志，避免控制台刷屏

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _hdr(self, name):
        """读取请求头并按 UTF-8 还原（HTTP 头默认按 Latin-1 解码，中文需还原）。"""
        v = self.headers.get(name)
        if not v:
            return v
        try:
            return v.encode("latin-1").decode("utf-8")
        except Exception:
            return v

    def _send_file(self, path, ctype=None):
        if ctype is None:
            ctype, _ = mimetypes.guess_type(path)
            ctype = ctype or "application/octet-stream"
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self._cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            return self._send_file(os.path.join(STATIC, "index.html"), "text/html; charset=utf-8")
        if path == "/favicon.ico":
            return self._send_file(os.path.join(ASSETS, "app_icon.ico"), "image/x-icon")
        if path == "/logo.svg":
            return self._send_file(os.path.join(ASSETS, "logo.svg"), "image/svg+xml")

        if path == "/api/ping":
            return self._send_json(200, {"ok": True, "service": "SaveAssistantPC", "port": CFG["port"]})

        if path == "/api/config":
            return self._send_json(200, {
                "port": CFG["port"],
                "device_name": CFG["device_name"],
                "transfer_dir": TRANSFER_DIR,
                "version": CFG.get("version", "1.0.0"),
                "local_ips": local_ips(),
                "free_space": free_space(TRANSFER_DIR),
                "categories": CATEGORY_LABELS,
            })

        if path == "/api/status":
            with state_lock:
                total = sum(r["size"] for r in records)
                count = len(records)
            today = datetime.now().strftime("%Y-%m-%d")
            today_count = sum(1 for r in records if r["time"].startswith(today))
            return self._send_json(200, {
                "device_name": CFG["device_name"],
                "version": CFG.get("version", "1.0.0"),
                "port": CFG["port"],
                "transfer_dir": TRANSFER_DIR,
                "local_ips": local_ips(),
                "free_space": free_space(TRANSFER_DIR),
                "file_count": count,
                "file_total_size": total,
                "today_count": today_count,
                "devices": device_snapshot(),
            })

        if path == "/api/devices":
            return self._send_json(200, {"devices": device_snapshot()})

        if path == "/api/files":
            with state_lock:
                data = list(records)
            return self._send_json(200, {"files": data})

        if path == "/api/events":
            return self._handle_sse()

        if path == "/api/open-folder":
            try:
                os.startfile(TRANSFER_DIR)
                return self._send_json(200, {"ok": True})
            except Exception as e:
                return self._send_json(500, {"ok": False, "error": str(e)})

        if path == "/api/open-file":
            fp = qs.get("path", [""])[0]
            try:
                if fp and os.path.isfile(fp):
                    os.startfile(os.path.dirname(fp))
                else:
                    os.startfile(TRANSFER_DIR)
                return self._send_json(200, {"ok": True})
            except Exception as e:
                return self._send_json(500, {"ok": False, "error": str(e)})

        # 静态资源
        if path.startswith("/static/"):
            rel = path[len("/static/"):]
            fp = os.path.join(STATIC, rel)
            if os.path.isfile(fp):
                return self._send_file(fp)
            self.send_response(404)
            self.end_headers()
            return

        self.send_response(404)
        self.end_headers()

    def _handle_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        import queue
        q = queue.Queue()
        with sse_lock:
            subscribers.append(q)
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                try:
                    ev = q.get(timeout=15)
                    payload = json.dumps(ev, ensure_ascii=False)
                    self.wfile.write(("data: " + payload + "\n\n").encode("utf-8"))
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with sse_lock:
                if q in subscribers:
                    subscribers.remove(q)

    def do_POST(self):
        global TRANSFER_DIR
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/device/announce":
            try:
                length = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(length) if length else b"{}"
                data = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                data = {}
            name = data.get("name") or "手机"
            model = data.get("model") or ""
            ip = data.get("ip") or self.client_address[0]
            did = data.get("id") or ip
            with state_lock:
                devices[did] = {
                    "name": name, "model": model, "ip": ip,
                    "last_seen": time.time(), "online": True,
                }
            emit({"type": "devices", "devices": device_snapshot()})
            emit({"type": "toast", "level": "info",
                  "msg": f"设备已连接：{name}"})
            return self._send_json(200, {
                "ok": True,
                "port": CFG["port"],
                "device_name": CFG["device_name"],
                "transfer_dir": TRANSFER_DIR,
                "free_space": free_space(TRANSFER_DIR),
            })

        if path == "/api/upload":
            return self._handle_upload()

        if path == "/api/config":
            try:
                length = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(length) if length else b"{}"
                data = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                data = {}
            changed = False
            if "transfer_dir" in data and data["transfer_dir"]:
                new_dir = data["transfer_dir"]
                CFG["transfer_dir"] = new_dir
                TRANSFER_DIR = new_dir
                changed = True
            if "device_name" in data and data["device_name"]:
                CFG["device_name"] = data["device_name"]
                changed = True
            if changed:
                save_config(CFG)
                ensure_transfer_dir()
                refresh_records()
                emit({"type": "config", "config": {
                    "device_name": CFG["device_name"],
                    "transfer_dir": TRANSFER_DIR,
                }})
            return self._send_json(200, {"ok": True})

        if path == "/api/shutdown":
            self._send_json(200, {"ok": True})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return

        self.send_response(404)
        self.end_headers()

    def _handle_upload(self):
        # 元数据优先从 query 读（URL 百分号编码，天然支持中文，且不受客户端请求头编码限制）；
        # 老客户端仍走 X-SA-* 请求头，这里保留兼容。
        qs = parse_qs(urlparse(self.path).query)

        def pick(qkey, hkey):
            v = qs.get(qkey, [None])[0]
            if v not in (None, ""):
                return v
            return self._hdr(hkey)

        dev = pick("device", "X-SA-Device") or "未知设备"
        name = pick("name", "X-SA-Name") or f"file_{int(time.time()*1000)}"
        cat = pick("category", "X-SA-Category") or "other"
        if cat not in CATEGORY_LABELS:
            cat = "other"
        try:
            size_hint = int(pick("size", "X-SA-Size") or 0)
        except (TypeError, ValueError):
            size_hint = 0
        length = int(self.headers.get("Content-Length", "0") or 0)

        safe = secure_filename(name)
        if not os.path.splitext(safe)[1]:
            safe += EXT_BY_CATEGORY.get(cat, "")
        cat_dir = os.path.join(TRANSFER_DIR, cat)
        os.makedirs(cat_dir, exist_ok=True)
        dest = os.path.join(cat_dir, safe)
        # 避免覆盖：重名则加序号
        if os.path.exists(dest):
            base, ext = os.path.splitext(safe)
            i = 1
            while os.path.exists(os.path.join(cat_dir, f"{base}({i}){ext}")):
                i += 1
            dest = os.path.join(cat_dir, f"{base}({i}){ext}")

        started = time.time()
        rid = uuid.uuid4().hex
        received = 0
        emit({"type": "upload_started", "id": rid, "name": safe,
              "category": cat, "category_label": CATEGORY_LABELS[cat],
              "device": dev, "size": size_hint})
        try:
            with open(dest, "wb") as f:
                remaining = length
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    received += len(chunk)
                    remaining -= len(chunk)
            st = os.stat(dest)
            rec = {
                "id": rid,
                "name": os.path.basename(dest),
                "category": cat,
                "category_label": CATEGORY_LABELS[cat],
                "size": st.st_size,
                "device": dev,
                "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "path": dest,
            }
            with state_lock:
                records.insert(0, rec)
            emit({"type": "upload_completed", "record": rec})
            emit({"type": "toast", "level": "success",
                  "msg": f"已接收：{rec['name']}（{fmt_size(st.st_size)}）"})
            return self._send_json(200, {"ok": True, "saved": rec["name"],
                                         "size": st.st_size})
        except Exception as e:
            emit({"type": "upload_failed", "id": rid, "name": safe,
                  "error": str(e)})
            return self._send_json(500, {"ok": False, "error": str(e)})


class TransferServer(ThreadingHTTPServer):
    """禁用端口复用：已在运行时不重复起服务（Windows 默认 SO_REUSEADDR 会允许多实例同绑）。"""
    allow_reuse_address = False
    daemon_threads = True


def fmt_size(n):
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n/1024:.1f} KB"
    if n < 1024 * 1024 * 1024:
        return f"{n/1024/1024:.1f} MB"
    return f"{n/1024/1024/1024:.2f} GB"


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
def udp_discovery_server():
    """UDP 局域网发现：收到 SA_DISCOVER 就回一份自己的信息，手机端据此自动找到电脑。"""
    import socket
    port = int(CFG["port"])
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", port))
    except OSError as e:
        print(f"[保存助手] UDP 发现端口 {port} 绑定失败：{e}")
        return
    while True:
        try:
            data, addr = sock.recvfrom(2048)
            if data.strip() == b"SA_DISCOVER":
                resp = json.dumps({
                    "ok": True,
                    "device_name": CFG["device_name"],
                    "port": port,
                    "version": CFG.get("version", ""),
                }, ensure_ascii=False).encode("utf-8")
                sock.sendto(resp, addr)
        except Exception:
            pass


def main():
    ensure_transfer_dir()
    refresh_records()
    threading.Thread(target=device_cleaner, daemon=True).start()
    threading.Thread(target=udp_discovery_server, daemon=True).start()

    numeric_args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(numeric_args[0]) if numeric_args else CFG["port"]
    host = CFG["host"]
    url = f"http://127.0.0.1:{port}/"
    try:
        server = TransferServer((host, port), Handler)
    except OSError as e:
        # 端口已被占用：多半是服务已在运行，直接打开工作台即可
        print(f"[保存助手] 端口 {port} 已被占用（服务可能已在运行）：{e}")
        if CFG.get("auto_open_browser", True) and "--no-browser" not in sys.argv:
            try:
                webbrowser.open(url)
            except Exception:
                pass
        return

    print(f"[保存助手] 传输工作台已启动：{url}")
    print(f"[保存助手] 局域网接收目录：{TRANSFER_DIR}")
    print(f"[保存助手] 本机局域网 IP：{', '.join(local_ips())}  端口：{port}")
    if CFG.get("auto_open_browser", True) and "--no-browser" not in sys.argv:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("[保存助手] 服务已停止。")


if __name__ == "__main__":
    main()
