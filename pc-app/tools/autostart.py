#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""保存助手 · 电脑端 —— 开机自启 + 后台常驻 的安装/卸载/状态查询。

用法（都从命令行走，不依赖任何第三方库）：
    python tools/autostart.py install     装开机自启（登录后自动后台起服务）
    python tools/autostart.py uninstall   取消开机自启
    python tools/autostart.py status      看当前是否已装、服务是否在跑
    python tools/autostart.py start       立刻后台起服务（不开浏览器）
    python tools/autostart.py stop        停掉正在跑的服务

设计要点
--------
1. 自启项写在 **HKCU\\...\\Run**（当前用户），不写 HKLM —— 不需要管理员权限，
   也不会影响这台机器上的其他用户。
2. 启动的是 **pythonw.exe**，且带 `--background`。两个原因：
   - `pythonw` 无控制台 → 不会有空窗口，也没有「关窗口=关服务」这回事；
   - Windows 防火墙的入站允许规则是按可执行文件匹配的，之前已针对 pythonw.exe
     放行过，换别的宿主（比如 python.exe 或 cmd）会重新撞防火墙。
3. 全部用 Windows 原生机制（reg.exe / taskkill），不引第三方库。
"""
import os
import socket
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(BASE)
SERVER = os.path.join(APP, "server.py")
LOCK = os.path.join(APP, "server.lock")

PYW_CANDIDATES = [
    r"C:\Users\Lenovo\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe",
    r"C:\Users\Lenovo\AppData\Local\Programs\Python\Python311\pythonw.exe",
]

RUN_KEY = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "SaveAssistantTransfer"


def find_pythonw():
    for p in PYW_CANDIDATES:
        if os.path.isfile(p):
            return p
    # 退而求其次：用当前解释器同目录的 pythonw
    cand = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return cand if os.path.isfile(cand) else None


def run(cmd):
    """跑一条命令，返回 (returncode, stdout+stderr)。

    Windows 上 reg.exe / tasklist.exe 的输出是**系统 ANSI 代码页**（中文系统 = GBK），
    不是 UTF-8。用 text=True 会让 Python 按 UTF-8 解码并抛 UnicodeDecodeError，
    而且这个异常发生在 subprocess 的内部读线程里 —— 只打印一段吓人的堆栈，
    然后静默返回空结果，看起来像「命令没输出」。必须自己按 cp936 解。
    """
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=25)
        raw = (p.stdout or b"") + (p.stderr or b"")
        text = None
        for enc in ("cp936", "utf-8", "cp1252"):
            try:
                text = raw.decode(enc)
                break
            except Exception:
                continue
        if text is None:
            text = raw.decode("utf-8", "replace")
        return p.returncode, text
    except Exception as e:
        return -1, str(e)


def query_run_value():
    code, out = run(["reg", "query", RUN_KEY, "/v", VALUE_NAME])
    if code != 0:
        return None
    for line in out.splitlines():
        if VALUE_NAME in line:
            # 形如：    SaveAssistantTransfer    REG_SZ    "C:\...\pythonw.exe" "..."
            parts = line.split("REG_SZ", 1)
            if len(parts) == 2:
                return parts[1].strip()
    return None


def read_lock_pid():
    try:
        import json
        with open(LOCK, "r", encoding="utf-8") as f:
            d = json.load(f)
        return int(d.get("pid") or 0), int(d.get("port") or 0)
    except Exception:
        return 0, 0


def pid_alive(pid):
    """判断进程是否活着。

    不要用 `tasklist /FI "PID eq N"`：它的输出受系统代码页与版本差异影响，
    过滤不生效时会打印「信息: 没有运行的任务匹配指定标准」但退出码仍是 0，
    于是被误判成「进程活着」。也不要用 tasklist 的表格去 grep PID 数字
    （PID 会出现在内存占用列里，极易假阳性）。

    直接调 Win32 API 拿退出码，这是唯一可靠的方式。
    """
    if not pid or pid <= 0:
        return False
    if pid == os.getpid():
        return True
    try:
        import ctypes
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        k32 = ctypes.windll.kernel32
        h = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
        if not h:
            return False
        try:
            code = ctypes.c_ulong()
            if not k32.GetExitCodeProcess(h, ctypes.byref(code)):
                return False
            return code.value == STILL_ACTIVE
        finally:
            k32.CloseHandle(h)
    except Exception:
        return False


def do_install():
    pyw = find_pythonw()
    if not pyw:
        print("找不到 pythonw.exe，无法安装开机自启。")
        return 1
    if not os.path.isfile(SERVER):
        print(f"找不到服务脚本：{SERVER}")
        return 1
    # 命令行：pythonw.exe "server.py" --background
    # 注意 reg 里存的是**待执行的命令行**，路径带空格必须加引号。
    value = f'"{pyw}" "{SERVER}" --background'
    code, out = run(["reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                     "/d", value, "/f"])
    if code != 0:
        print("写入注册表失败：")
        print(out)
        return 1
    print("已安装开机自启。")
    print(f"  启动项：{VALUE_NAME}")
    print(f"  命令：  {value}")
    print(f"  位置：  {RUN_KEY}")
    print()
    print("下次登录 Windows 会自动在后台起服务，不弹窗口、不开浏览器。")
    print("现在就想起的话，跑：python tools/autostart.py start")
    return 0


def do_uninstall():
    code, out = run(["reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"])
    if code != 0:
        print("当前没有安装开机自启（或删除失败）。")
        return 0
    print("已取消开机自启。")
    print("注意：正在跑的服务不受影响，需要的话再跑 autostart.py stop 停掉。")
    return 0


def do_status():
    value = query_run_value()
    if value:
        print("开机自启：已安装")
        print(f"  命令：{value}")
    else:
        print("开机自启：未安装")

    # 判定「服务在不在」以**端口有没有人应答**为准，而不是只看锁文件里的 PID。
    # 锁文件可能因为历史版本/异常路径缺失，但服务只要活着就一定在 listen。
    port = config_port()
    serving = port_is_serving(port)
    pid, _ = read_lock_pid()
    pid_ok = pid_alive(pid)
    if serving:
        extra = f"PID {pid}，" if pid_ok else ""
        print(f"服务状态：正在运行（{extra}端口 {port}）")
    else:
        print("服务状态：未运行")
    return 0


def config_port():
    """从 config.json 读端口，读不到就用默认 18765。"""
    try:
        import json
        with open(os.path.join(APP, "config.json"), "r", encoding="utf-8") as f:
            return int(json.load(f).get("port") or 18765)
    except Exception:
        return 18765


def port_is_serving(port, timeout=1.5):
    """连一下端口看服务在不在 —— 与 server.py 里的判据保持一致。"""
    import urllib.request
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/" % int(port),
                                    timeout=timeout) as r:
            if r.status == 200:
                return True
    except Exception:
        pass
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", int(port)))
        return True
    except Exception:
        return False
    finally:
        s.close()


def do_start():
    """立刻后台起服务。已经跑着就什么都不做（单实例由 server.py 自己保证）。"""
    pid, port = read_lock_pid()
    if pid and pid_alive(pid):
        print(f"服务已在运行（PID {pid}，端口 {port}），无需重复启动。")
        return 0
    pyw = find_pythonw()
    if not pyw:
        print("找不到 pythonw.exe。")
        return 1
    # DETACHED_PROCESS + CREATE_NEW_PROCESS_GROUP：
    # 让服务完全脱离当前这个进程/控制台，本进程退出后它照样活着。
    DETACHED_PROCESS = 0x00000008
    CREATE_NEW_PROCESS_GROUP = 0x00000200
    try:
        subprocess.Popen([pyw, SERVER, "--background"],
                         cwd=APP, close_fds=True,
                         creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP)
    except Exception as e:
        print(f"启动失败：{e}")
        return 1
    print("已后台启动服务。用 autostart.py status 确认。")
    return 0


def pid_of_listening_port(port):
    """查出正在监听某个端口的进程 PID。

    不能只靠锁文件取 PID：锁文件可能因为历史上的异常退出路径而缺失，
    那个服务却仍占着端口 —— 这时 `stop` 会说「没有在运行」，用户就没法停它了。
    用 netstat 反查监听者才是可靠的（输出形如 ...:18765 ... LISTENING 12345）。
    """
    code, out = run(["netstat", "-ano", "-p", "TCP"])
    if code != 0:
        return 0
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 5:
            continue
        if parts[3] != "LISTENING":
            continue
        local = parts[1]
        if not local.endswith(":" + str(port)):
            continue
        try:
            return int(parts[4])
        except Exception:
            continue
    return 0


def do_stop():
    pid, _ = read_lock_pid()
    if not pid or not pid_alive(pid):
        # 锁文件没有／是死的 —— 直接按端口找真正在跑的那个
        pid = pid_of_listening_port(config_port())
    if not pid:
        print("服务当前没有在运行。")
        return 0
    code, out = run(["taskkill", "/PID", str(pid), "/F"])
    if code == 0:
        print(f"已停止服务（PID {pid}）。")
        # 顺手清掉可能残留的锁文件，避免下次 status 误报
        try:
            if os.path.exists(LOCK):
                os.remove(LOCK)
        except Exception:
            pass
        return 0
    print("停止失败：")
    print(out)
    return 1


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 0
    cmd = sys.argv[1].lower()
    table = {"install": do_install, "uninstall": do_uninstall,
             "status": do_status, "start": do_start, "stop": do_stop}
    if cmd not in table:
        print(f"未知命令：{cmd}\n")
        print(__doc__)
        return 2
    return table[cmd]()


if __name__ == "__main__":
    sys.exit(main())
