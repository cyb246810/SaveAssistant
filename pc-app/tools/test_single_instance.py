#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""保存助手 · 电脑端 —— 单实例 / 后台常驻 的判定逻辑测试。

这些是纯逻辑测试，不真的起服务、不碰网络，可在任何机器上跑：
    python tools/test_single_instance.py
"""
import atexit
import importlib.util
import json
import os
import shutil
import sys
import tempfile

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(APP, "server.py")

# 用独立的模块实例加载，避免污染真实运行环境
spec = importlib.util.spec_from_file_location("sa_server_test", SERVER)
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

# ---------------------------------------------------------------------------
# 全程把日志写到临时文件 —— 否则用例会往**真实的** server.log 里插行
# ---------------------------------------------------------------------------
# server.py 的 LOG_PATH 是「按 server.py 自己所在目录」算的，而这里 import 的
# 就是真实那份。于是：
#   - instance_already_running() 清理陈锁时会写「发现残留锁文件…已清理」
#   - fatal_dialog 在后台分支会写「[background] 不弹窗，仅记录：测试错误」
# 这些行混进用户的服务日志里，排查问题时看到只会更糊涂。
# 在**模块级**改一次，比每个用例各自重定向更稳 —— 以后新加的用例自动受保护。
_LOG_TMP = tempfile.mkdtemp(prefix="sa_test_log_")
srv.LOG_PATH = os.path.join(_LOG_TMP, "server.log")
atexit.register(shutil.rmtree, _LOG_TMP, True)

PASS = 0
FAIL = 0

# 这些测试跑的时候，本机**可能真的有一个服务在跑**（用户日常就在用）。
# 那样端口探测会命中真实服务，把「陈锁」测试带偏 —— 所以除了专门测端口的那几条，
# 其余用例一律把端口探测钉死为 False，让判定只走锁文件这条分支。
_REAL_PORT_PROBE = srv.port_is_serving


def hermetic(fn):
    """把 port_is_serving 钉成 False，隔离真实环境。"""
    def wrapped():
        srv.port_is_serving = lambda p, timeout=1.0: False
        try:
            fn()
        finally:
            srv.port_is_serving = _REAL_PORT_PROBE
    return wrapped


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def test_pid_is_alive():
    print("[pid_is_alive]")
    check("当前进程算活着", srv.pid_is_alive(os.getpid()) is True)
    check("0 不算活着", srv.pid_is_alive(0) is False)
    check("负数不算活着", srv.pid_is_alive(-5) is False)
    # 999999 几乎不可能存在
    check("不存在的 PID 不算活着", srv.pid_is_alive(999999) is False)


def test_lock_roundtrip():
    print("[锁文件读写]")
    with tempfile.TemporaryDirectory() as td:
        orig = srv.LOCK_PATH
        srv.LOCK_PATH = os.path.join(td, "server.lock")
        try:
            srv.write_lock(18765)
            check("写完能读回", os.path.exists(srv.LOCK_PATH))
            pid, port = srv.read_lock()
            check("PID 是当前进程", pid == os.getpid(), f"got {pid}")
            check("端口正确", port == 18765, f"got {port}")

            # 读不到 / 损坏时不能抛异常
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                f.write("{ this is not json")
            pid2, port2 = srv.read_lock()
            check("损坏的锁文件返回 (None, None)", pid2 is None and port2 is None,
                  f"got {(pid2, port2)}")

            os.remove(srv.LOCK_PATH)
            pid3, port3 = srv.read_lock()
            check("锁文件不存在返回 (None, None)", pid3 is None and port3 is None,
                  f"got {(pid3, port3)}")
        finally:
            srv.LOCK_PATH = orig


@hermetic
def test_stale_lock_cleanup():
    print("[陈锁自愈]")
    with tempfile.TemporaryDirectory() as td:
        orig = srv.LOCK_PATH
        srv.LOCK_PATH = os.path.join(td, "server.lock")
        try:
            # 一个一定不存在的 PID：服务若崩过、锁没清干净，绝不能再启动失败
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                json.dump({"pid": 999999, "port": 18765}, f)
            running = srv.instance_already_running()
            check("陈锁不被当成「已在运行」", running == 0, f"got {running}")
            check("陈锁被自动清理", not os.path.exists(srv.LOCK_PATH))
        finally:
            srv.LOCK_PATH = orig


def test_port_probe_is_ground_truth():
    """**最重要的一条**：锁文件没了，也不能误判成「服务没在跑」。

    这里踩过真坑：锁文件是「谁退出谁删」的，而「端口被占 → 打开工作台 → return」
    这条路径也会 clear_lock，把真正在跑的实例的记录抹掉。之后 status 报「未运行」，
    用户以为服务挂了 —— 所以判定必须以端口应答为准。
    """
    print("[端口探测优先于锁文件]")
    orig_serving = srv.port_is_serving
    orig_lock = srv.LOCK_PATH
    with tempfile.TemporaryDirectory() as td:
        srv.LOCK_PATH = os.path.join(td, "server.lock")  # 故意不存在
        try:
            srv.port_is_serving = lambda p, timeout=1.0: True
            check("锁文件缺失但端口有服务 → 判定为在运行",
                  srv.instance_already_running() == int(srv.CFG["port"]),
                  f"got {srv.instance_already_running()}")

            srv.port_is_serving = lambda p, timeout=1.0: False
            check("锁文件缺失且端口无服务 → 判定为未运行",
                  srv.instance_already_running() == 0)
        finally:
            srv.port_is_serving = orig_serving
            srv.LOCK_PATH = orig_lock


def test_lock_does_not_override_live_port():
    """锁文件里是死 PID，但端口确实有服务 —— 仍应判为在运行。"""
    print("[死锁 + 活端口]")
    orig_serving = srv.port_is_serving
    orig_lock = srv.LOCK_PATH
    with tempfile.TemporaryDirectory() as td:
        srv.LOCK_PATH = os.path.join(td, "server.lock")
        try:
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                json.dump({"pid": 999999, "port": 18765}, f)
            srv.port_is_serving = lambda p, timeout=1.0: True
            check("锁里是死 PID 但端口在服务 → 仍判为在运行",
                  srv.instance_already_running() > 0)
        finally:
            srv.port_is_serving = orig_serving
            srv.LOCK_PATH = orig_lock


def test_live_lock_detected():
    """锁文件里是活 PID，且端口也有人应答 —— 两个条件齐了才判「在运行」。

    注意这里必须**同时**满足：新逻辑以端口为准，光有活 PID 是不够的
    （PID 会被系统复用，一个「活着的无关进程」不能证明服务在跑）。
    这也正是下面那条「活锁不会被误删」要分开断言的原因。
    """
    print("[活锁识别]")
    with tempfile.TemporaryDirectory() as td:
        orig = srv.LOCK_PATH
        orig_serving = srv.port_is_serving
        srv.LOCK_PATH = os.path.join(td, "server.lock")
        try:
            # 当前进程一定活着 —— 用它冒充「正在跑的服务」
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                json.dump({"pid": os.getpid(), "port": 12345}, f)

            srv.port_is_serving = lambda p, timeout=1.0: True
            running = srv.instance_already_running()
            check("活锁 + 端口在服务 → 判为在运行", running == 12345, f"got {running}")
            check("活锁不会被误删", os.path.exists(srv.LOCK_PATH))

            # 只有活 PID、端口没人应答 → 不该判成在运行（不能只信 PID）
            srv.port_is_serving = lambda p, timeout=1.0: False
            check("只有活 PID、端口无服务 → 判为未运行",
                  srv.instance_already_running() == 0)
        finally:
            srv.port_is_serving = orig_serving
            srv.LOCK_PATH = orig


@hermetic
def test_clear_lock_only_own():
    print("[clear_lock 只清自己的]")
    with tempfile.TemporaryDirectory() as td:
        orig = srv.LOCK_PATH
        srv.LOCK_PATH = os.path.join(td, "server.lock")
        try:
            # 别人的锁：不能删
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                json.dump({"pid": 999999, "port": 18765}, f)
            srv.clear_lock()
            check("不删别人的锁", os.path.exists(srv.LOCK_PATH))

            # 自己的锁：要删
            with open(srv.LOCK_PATH, "w", encoding="utf-8") as f:
                json.dump({"pid": os.getpid(), "port": 18765}, f)
            srv.clear_lock()
            check("删掉自己的锁", not os.path.exists(srv.LOCK_PATH))
        finally:
            srv.LOCK_PATH = orig


def test_background_flag_semantics():
    print("[--background 语义]")
    # 后台模式必须不弹窗 —— 否则登录后一个错误框会一直堵在桌面上
    orig_argv = sys.argv[:]
    orig_dialog = srv.fatal_dialog
    called = {"dialog": False}

    def fake_msgbox(*a, **k):
        called["dialog"] = True
        return 0

    try:
        import ctypes
        real = ctypes.windll.user32.MessageBoxW
        ctypes.windll.user32.MessageBoxW = fake_msgbox
        try:
            # 日志已经在模块级指向临时文件了，这里不用再单独重定向
            sys.argv = ["server.py", "--background"]
            srv.fatal_dialog("测试错误")
            check("后台模式不弹窗", called["dialog"] is False)

            sys.argv = ["server.py"]
            srv.fatal_dialog("测试错误")
            check("前台模式会弹窗", called["dialog"] is True)
        finally:
            ctypes.windll.user32.MessageBoxW = real
    finally:
        sys.argv = orig_argv
        srv.fatal_dialog = orig_dialog


def main():
    print("=" * 58)
    print("保存助手 · 电脑端 单实例/后台常驻 逻辑测试")
    print("=" * 58)
    test_pid_is_alive()
    test_lock_roundtrip()
    test_stale_lock_cleanup()
    test_port_probe_is_ground_truth()
    test_lock_does_not_override_live_port()
    test_live_lock_detected()
    test_clear_lock_only_own()
    test_background_flag_semantics()
    print("-" * 58)
    print(f"通过 {PASS}，失败 {FAIL}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
