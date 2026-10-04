#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""再建一个桌面快捷方式：「保存助手 服务管理」，双击打开一个带菜单的控制台。

有的用户想自己看看服务在不在、想手动停掉。给一个直白的入口，
比让人去记命令行强。复用 make_shortcut.py 里那套 ctypes/IShellLink 的做法。
"""
import ctypes
import os
import struct
from ctypes import (windll, wintypes, c_void_p, c_wchar_p, byref, POINTER,
                    c_int, c_ulong, c_ubyte, c_ushort, Structure, create_unicode_buffer)

ole32 = windll.ole32
shell32 = windll.shell32

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(BASE)


class GUID(Structure):
    _fields_ = [("Data1", c_ulong), ("Data2", c_ushort), ("Data3", c_ushort),
                ("Data4", c_ubyte * 8)]


def sg(s):
    g = GUID()
    ole32.CLSIDFromString(c_wchar_p(s), byref(g))
    return g


CLSID_ShellLink = sg("{00021401-0000-0000-C000-000000000046}")
IID_IShellLinkW = sg("{000214F9-0000-0000-C000-000000000046}")
IID_IPersistFile = sg("{0000010B-0000-0000-C000-000000000046}")


def mm(p, i, rt, args):
    vb = ctypes.cast(c_void_p(p), POINTER(c_void_p))[0]
    vt = ctypes.cast(vb, POINTER(c_void_p))
    f = vt[i]
    a = f.value if hasattr(f, "value") else int(f)
    return ctypes.WINFUNCTYPE(rt, c_void_p, *args)(a)


CMD = r"C:\Windows\System32\cmd.exe"
ICON = os.path.join(APP, "assets", "app_icon.ico")
MANAGE_BAT = os.path.join(APP, "manage.bat")
DESC = "保存助手 · 服务管理（查看状态 / 启动 / 停止）"

if not os.path.isfile(MANAGE_BAT):
    print(f"警告：{MANAGE_BAT} 还不存在，快捷方式指向它也没用")

buf = create_unicode_buffer(260)
shell32.SHGetFolderPathW(0, 0x0000, None, 0, buf)
desktop = buf.value or r"C:\Users\Lenovo\Desktop"
LNK = os.path.join(desktop, "保存助手 服务管理.lnk")

ole32.CoInitialize(None)
shell32.ILCreateFromPathW.restype = c_void_p
shell32.ILCreateFromPathW.argtypes = [c_wchar_p]
pidl = shell32.ILCreateFromPathW(CMD)
if not pidl:
    raise SystemExit("ILCreateFromPathW 失败：" + CMD)

ppv = c_void_p()
if ole32.CoCreateInstance(byref(CLSID_ShellLink), None, 1,
                          byref(IID_IShellLinkW), byref(ppv)) != 0:
    raise ctypes.WinError()
sl = ppv.value

SetIDList = mm(sl, 5, ctypes.c_long, [c_void_p])
SetArguments = mm(sl, 11, ctypes.c_long, [c_wchar_p])
SetWorkingDirectory = mm(sl, 9, ctypes.c_long, [c_wchar_p])
SetDescription = mm(sl, 7, ctypes.c_long, [c_wchar_p])
SetIconLocation = mm(sl, 17, ctypes.c_long, [c_wchar_p, c_int])
SetShowCmd = mm(sl, 15, ctypes.c_long, [c_int])

SetIDList(sl, pidl)
# /k 让窗口留着，用户可以反复操作；编码切到 65001 保证中文不乱码
SetArguments(sl, f'/k chcp 65001 >nul & "{MANAGE_BAT}"')
SetWorkingDirectory(sl, APP)
SetDescription(sl, DESC)
SetIconLocation(sl, ICON, 0)
SetShowCmd(sl, 1)  # SW_SHOWNORMAL

ppf = c_void_p()
QI = mm(sl, 0, ctypes.c_long, [POINTER(GUID), POINTER(c_void_p)])
if QI(sl, byref(IID_IPersistFile), byref(ppf)) != 0:
    raise ctypes.WinError()
pf = ppf.value
Save = mm(pf, 6, ctypes.c_long, [c_wchar_p, wintypes.BOOL])
hr = Save(pf, LNK, 1)

data = open(LNK, "rb").read()
lf = struct.unpack_from("<I", data, 0x14)[0]
print("Save hr =", hr, "大小 =", len(data), "HasIDList =", bool(lf & 0x2))
print("OK 已生成：" + LNK)
