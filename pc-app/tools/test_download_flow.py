#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""电脑端「解析 → 勾选 → 保存」全链路回归测试。

在**临时目录**里起一个独立实例（临时接收目录 + 空闲端口），
不碰用户正在用的服务和 D:\\保存助手下载，所以随时可以放心跑。

跑法：
    python tools\\test_download_flow.py
"""
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY = sys.executable

PASS = FAIL = 0
FAILURES = []


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"  FAIL  {name}  {extra}")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def post_json(base, path, payload, timeout=120):
    raw = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(base + path, data=raw,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}


def get_json(base, path, timeout=30):
    with urllib.request.urlopen(base + path, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def get_bytes(base, path, timeout=30):
    with urllib.request.urlopen(base + path, timeout=timeout) as r:
        return r.status, r.headers.get("Content-Type"), r.read()


def _server_version():
    """从 server.py 的 DEFAULT_CONFIG 里读出期望版本号。

    不要在测试里写死版本 —— 写死的话每次发版都会假失败一次，
    久而久之就没人认真看这条断言了。
    """
    with open(os.path.join(APP, "server.py"), encoding="utf-8") as f:
        for line in f:
            if '"version"' in line:
                return line.split('"version"')[1].split('"')[1]
    raise RuntimeError("在 server.py 里找不到 version")


def wait_until(pred, timeout=20, interval=0.4):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if pred():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


class SseCollector:
    """后台收 SSE 事件，供断言用。

    幽灵记录那个 bug（upload_completed 漏了 id）本质上是个**协议约定**问题，
    只有真连一次事件流才验得到，光看代码是看不出来的。
    """

    def __init__(self, base):
        self.base = base
        self.events = []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        try:
            with urllib.request.urlopen(self.base + "/api/events", timeout=120) as r:
                while not self.stop.is_set():
                    line = r.readline()
                    if not line:
                        break
                    text = line.decode("utf-8", "replace").strip()
                    if text.startswith("data: "):
                        try:
                            self.events.append(json.loads(text[6:]))
                        except Exception:
                            pass
        except Exception:
            pass

    def __enter__(self):
        self.thread.start()
        time.sleep(0.6)          # 等连接建立，别让早期事件漏掉
        return self

    def __exit__(self, *a):
        self.stop.set()

    def wait_for(self, pred, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for ev in list(self.events):
                if pred(ev):
                    return ev
            time.sleep(0.2)
        return None


class FileServer:
    """给「直链」用例提供素材。"""

    def __init__(self, root):
        self.root = root
        handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(
            *a, directory=root, **kw)
        self.port = free_port()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *a):
        self.httpd.shutdown()
        self.httpd.server_close()

    def url(self, name):
        return f"http://127.0.0.1:{self.port}/{name}"


def wait_ready(base, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + "/api/ping", timeout=1) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.25)
    return False


def main():
    workdir = tempfile.mkdtemp(prefix="sa_dlflow_")
    app_copy = os.path.join(workdir, "app")
    recv = os.path.join(workdir, "recv")
    assets = os.path.join(workdir, "assets")
    os.makedirs(recv)
    os.makedirs(assets)

    # 只复制运行需要的东西（源码 + 静态资源），不带上用户的数据
    shutil.copytree(APP, app_copy, ignore=shutil.ignore_patterns(
        "__pycache__", "*.log", "*.lock", "tools"))

    port = free_port()
    with open(os.path.join(app_copy, "config.json"), "w", encoding="utf-8") as f:
        json.dump({"port": port, "host": "127.0.0.1", "device_name": "测试机",
                   "transfer_dir": recv, "auto_open_browser": False}, f,
                  ensure_ascii=False)

    base = f"http://127.0.0.1:{port}"
    proc = subprocess.Popen([PY, os.path.join(app_copy, "server.py"), str(port),
                             "--no-browser", "--no-dialog"],
                            cwd=app_copy, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    try:
        if not wait_ready(base):
            print("服务没起来，测试中止")
            return 2

        # 造素材
        ff = shutil.which("ffmpeg")
        if ff:
            subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y",
                            "-f", "lavfi", "-i", "color=c=blue:s=160x120:d=1",
                            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                            "-c:v", "libx264", "-c:a", "aac", "-shortest",
                            os.path.join(assets, "clip.mp4")], check=True)
            subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y",
                            "-f", "lavfi", "-i", "color=c=red:s=120x90:d=1",
                            "-frames:v", "1", os.path.join(assets, "pic.jpg")],
                           check=True)

        print("\n[1] 配置接口")
        _, cfg = get_json(base, "/api/config")
        # 从 server.py 读期望版本，别把版本号写死在测试里 ——
        # 写死的话每次发版都会假失败一次。
        expected = _server_version()
        check(f"版本与代码一致（{expected}）", cfg.get("version") == expected,
              cfg.get("version"))
        check("返回 ffmpeg 能力", isinstance(cfg.get("ffmpeg"), dict))
        check("返回凭据状态", isinstance(cfg.get("credential"), dict))

        print("\n[2] 缩略图代理的安全边界")
        st, ctype, data = get_bytes(base, "/api/thumb?url=https://evil.example.com/x.jpg")
        check("非白名单域名不给图", ctype == "image/gif" and len(data) < 200, ctype)
        st, ctype, data = get_bytes(base, "/api/thumb?url=")
        check("空 url 返回占位图", ctype == "image/gif")

        print("\n[3] 解析拒绝垃圾输入")
        st, r = post_json(base, "/api/parse", {"url": ""})
        check("空链接 400", st == 400 and not r.get("ok"), r)
        st, r = post_json(base, "/api/parse", {"url": "这不是链接"})
        check("非链接 400", st == 400 and not r.get("ok"), r)
        st, r = post_json(base, "/api/parse", {"url": "ftp://x.com/a.mp4"})
        check("非 http 协议 400", st == 400 and not r.get("ok"), r)

        print("\n[4] 凭据校验")
        st, r = post_json(base, "/api/credential", {"raw": "随便一段没有 cookie 的文字"})
        check("无 cookie 被拒", st == 400 and not r.get("ok"), r)
        st, r = post_json(base, "/api/credential", {"raw": "Cookie: foo=1; bar=2"})
        check("缺 hy_user/hy_token 被拒", st == 400 and not r.get("ok"), r)

        print("\n[5] 保存接口的前置校验")
        st, r = post_json(base, "/api/save", {"job_id": "不存在的", "items": ["video"]})
        check("job 不存在 400", st == 400 and not r.get("ok"), r)

        if not ff:
            print("\n(本机没有 ffmpeg，跳过直链保存用例)")
        else:
            with FileServer(assets) as fs:
                print("\n[6] 直链：解析")
                st, job = post_json(base, "/api/parse", {"url": fs.url("clip.mp4")})
                check("解析成功", st == 200 and job.get("ok"), job)
                check("识别为直链", job.get("platform") == "direct", job.get("platform"))
                check("产出一个视频条目",
                      len(job.get("items", [])) == 1
                      and job["items"][0]["kind"] == "video", job.get("items"))

                print("\n[7] 直链：保存，并核对落盘字节")
                st, r = post_json(base, "/api/save",
                                  {"job_id": job["job_id"], "items": ["video"], "options": {}})
                check("提交成功", st == 200 and r.get("ok"), r)

                deadline = time.time() + 40
                saved = None
                while time.time() < deadline:
                    _, cfg2 = get_json(base, "/api/status")
                    if cfg2.get("file_count", 0) > 0:
                        saved = cfg2
                        break
                    time.sleep(0.5)
                check("文件已落盘", bool(saved), "等待超时")
                if saved:
                    _, files = get_json(base, "/api/downloads")
                    names = [f["name"] for f in files.get("files", [])]
                    check("记录里有保存的文件", any(n.endswith(".mp4") for n in names), names)
                    src_size = os.path.getsize(os.path.join(assets, "clip.mp4"))
                    dst = [f for f in files["files"] if f["name"].endswith(".mp4")][0]
                    check("字节数与源文件一致", dst["size"] == src_size,
                          f'{dst["size"]} vs {src_size}')

                print("\n[8] 直链 + 烧标题（真实走 ffmpeg 重编码）")
                # 单独用一份素材：上一步已经存过 clip.mp4，共用会让
                # 「原片是否被清理」这条断言被上一步的残留干扰。
                shutil.copy(os.path.join(assets, "clip.mp4"),
                            os.path.join(assets, "burn.mp4"))
                st, job2 = post_json(base, "/api/parse", {"url": fs.url("burn.mp4")})
                st, r = post_json(base, "/api/save",
                                  {"job_id": job2["job_id"], "items": ["video"],
                                   "options": {"burn_title": True,
                                               "title_text": "测试标题 ABC"}})
                check("提交成功", st == 200 and r.get("ok"), r)
                # 重编码比直接下载慢，给足时间
                vdir = os.path.join(recv, "video")
                deadline = time.time() + 90
                produced = []
                while time.time() < deadline:
                    produced = [n for n in os.listdir(vdir) if "burn_加工" in n]
                    if produced:
                        break
                    time.sleep(1)
                check("产出加工版文件", bool(produced), os.listdir(vdir))
                if produced:
                    size = os.path.getsize(os.path.join(vdir, produced[0]))
                    check("加工版非空且比原片大（重编码过）",
                          size > os.path.getsize(os.path.join(assets, "burn.mp4")),
                          f"{size}")
                    check("原片已清理（只留加工版）",
                          "burn.mp4" not in os.listdir(vdir), os.listdir(vdir))

        print("\n[9] 上传完成事件必须带 id（否则前端多出一条幽灵记录）")
        with SseCollector(base) as sse:
            payload = b"hello-upload" * 200
            q = urllib.parse.urlencode({"device": "测试手机", "name": "幽灵测试.mp4",
                                        "category": "video", "size": str(len(payload))})
            req = urllib.request.Request(
                base + "/api/upload?" + q, data=payload,
                headers={"Content-Type": "application/octet-stream"}, method="POST")
            with urllib.request.urlopen(req, timeout=30) as r:
                check("上传返回 200", r.status == 200)

            started = sse.wait_for(lambda e: e.get("type") == "upload_started")
            completed = sse.wait_for(lambda e: e.get("type") == "upload_completed")

        check("收到 upload_started", started is not None)
        check("收到 upload_completed", completed is not None)
        check("completed 事件带 id", bool(completed and completed.get("id")), completed)
        check("两个事件的 id 一致",
              bool(started and completed and started.get("id") == completed.get("id")),
              f'{started and started.get("id")} vs {completed and completed.get("id")}')

        # 定期重扫最容易踩的坑：磁盘扫描不知道文件是谁传的，只会给「—」。
        # 不做合并的话，设备名会在下一次重扫（5 秒）后无声消失。
        time.sleep(9)
        device_kept = wait_until(lambda: any(
            f["name"] == "幽灵测试.mp4" and f.get("device") == "测试手机"
            for f in get_json(base, "/api/files")[1]["files"]), 10)
        check("重扫后设备名仍在（没被扫成「—」）", device_kept,
              [f.get("device") for f in get_json(base, "/api/files")[1]["files"][:3]])

        print("\n[10] 磁盘同步：文件被删掉后记录要跟着消失")
        probe = os.path.join(recv, "video", "会被删掉.mp4")
        with open(probe, "wb") as f:
            f.write(b"x" * 1234)
        check("新文件被扫到", wait_until(
            lambda: any(f["name"] == "会被删掉.mp4"
                        for f in get_json(base, "/api/files")[1]["files"]), 20))

        with SseCollector(base) as sse:
            os.remove(probe)
            removed = wait_until(lambda: not any(
                f["name"] == "会被删掉.mp4"
                for f in get_json(base, "/api/files")[1]["files"]), 25)
            broadcast = sse.wait_for(lambda e: e.get("type") == "files", 25)
        check("删掉后记录消失", removed, "重扫没生效")
        check("广播了 files 事件", broadcast is not None)

        print("\n[11] 页面元素齐全")
        with urllib.request.urlopen(base + "/") as r:
            html = r.read().decode("utf-8")
        for element in ("btnParse", "btnSave", "btnSelAll", "optCover", "optTitle",
                        "optSticker", "optStitch", "btnSaveCred", "credRaw",
                        "itemList", "credStatus", "ffmpegWarn"):
            check("页面含 " + element, f'id="{element}"' in html)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        # 失败时把服务端日志打出来：save 是后台线程跑的，失败原因只在日志里，
        # 不看日志就只能对着「文件没落盘」干瞪眼。
        if FAIL:
            log_path = os.path.join(app_copy, "server.log")
            if os.path.isfile(log_path):
                print("\n--- 服务端日志 ---")
                with open(log_path, encoding="utf-8", errors="replace") as f:
                    print(f.read()[-3000:])
        shutil.rmtree(workdir, ignore_errors=True)

    print(f"\n通过 {PASS}，失败 {FAIL}")
    if FAILURES:
        print("失败项：" + "、".join(FAILURES))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
