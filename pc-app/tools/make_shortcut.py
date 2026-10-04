#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 ctypes 直接调 Windows IShellLink 创建桌面 .lnk 快捷方式（带安卓图标）。
关键：用 ILCreateFromPathW 生成目标 PIDL，再 SetIDList 写死目标——
      避免 SetPath 在本环境下不生成 LinkInfo 导致快捷方式空壳。"""
import ctypes
import struct
from ctypes import (windll, wintypes, c_void_p, c_wchar_p, byref, POINTER,
                    c_int, c_ulong, c_ubyte, c_ushort, Structure, create_unicode_buffer)

ole32 = windll.ole32
shell32 = windll.shell32


class GUID(Structure):
    _fields_ = [("Data1", c_ulong), ("Data2", c_ushort), ("Data3", c_ushort), ("Data4", c_ubyte * 8)]


def sg(s):
    g = GUID()
    ole32.CLSIDFromString(c_wchar_p(s), byref(g))
    return g


CLSID_ShellLink = sg("{00021401-0000-0000-C000-000000000046}")
IID_IShellLinkW = sg("{000214F9-0000-0000-C000-000000000046}")
IID_IPersistFile = sg("{0000010B-0000-0000-C000-000000000046}")


def mm(p, i, rt, args):
    """取 COM 接口第 i 个 vtable 方法。p 是指向 vtable 指针的指针。"""
    vb = ctypes.cast(c_void_p(p), POINTER(c_void_p))[0]
    vt = ctypes.cast(vb, POINTER(c_void_p))
    f = vt[i]
    a = f.value if hasattr(f, "value") else int(f)
    return ctypes.WINFUNCTYPE(rt, c_void_p, *args)(a)


PYW = r"C:\Users\Lenovo\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe"
SCRIPT = r"C:\Users\Lenovo\Documents\SaveAssistantPC\app\server.py"
WD = r"C:\Users\Lenovo\Documents\SaveAssistantPC\app"
ICON = r"C:\Users\Lenovo\Documents\SaveAssistantPC\app\assets\app_icon.ico"
DESC = "保存助手 · 电脑端传输工作台（局域网内传，不经过外网）"

buf = create_unicode_buffer(260)
shell32.SHGetFolderPathW(0, 0x0000, None, 0, buf)
desktop = buf.value or r"C:\Users\Lenovo\Desktop"
LNK = desktop + r"\保存助手 传输工作台.lnk"

ole32.CoInitialize(None)

# 关键：用 Shell 自己解析出目标文件的 PIDL
shell32.ILCreateFromPathW.restype = c_void_p
shell32.ILCreateFromPathW.argtypes = [c_wchar_p]
pidl = shell32.ILCreateFromPathW(PYW)
if not pidl:
    raise SystemExit("ILCreateFromPathW 失败，目标不存在？ " + PYW)
print("目标 PIDL 生成成功")

ppv = c_void_p()
if ole32.CoCreateInstance(byref(CLSID_ShellLink), None, 1, byref(IID_IShellLinkW), byref(ppv)) != 0:
    raise ctypes.WinError()
sl = ppv.value

SetIDList = mm(sl, 5, ctypes.c_long, [c_void_p])
SetArguments = mm(sl, 11, ctypes.c_long, [c_wchar_p])
SetWorkingDirectory = mm(sl, 9, ctypes.c_long, [c_wchar_p])
SetDescription = mm(sl, 7, ctypes.c_long, [c_wchar_p])
SetIconLocation = mm(sl, 17, ctypes.c_long, [c_wchar_p, c_int])
SetShowCmd = mm(sl, 15, ctypes.c_long, [c_int])

hrs = {
    "SetIDList": SetIDList(sl, pidl),
    "SetArguments": SetArguments(sl, '"' + SCRIPT + '"'),
    "SetWorkingDirectory": SetWorkingDirectory(sl, WD),
    "SetDescription": SetDescription(sl, DESC),
    "SetIconLocation": SetIconLocation(sl, ICON, 0),
    "SetShowCmd": SetShowCmd(sl, 7),  # SW_SHOWMINNOACTIVE
}
print("调用结果:", {k: v for k, v in hrs.items()})

ppf = c_void_p()
QI = mm(sl, 0, ctypes.c_long, [POINTER(GUID), POINTER(c_void_p)])
if QI(sl, byref(IID_IPersistFile), byref(ppf)) != 0:
    raise ctypes.WinError()
pf = ppf.value
Save = mm(pf, 6, ctypes.c_long, [c_wchar_p, wintypes.BOOL])
hr = Save(pf, LNK, 1)
print("Save hr =", hr)

# ---- 自检：解析刚生成的 lnk ----
data = open(LNK, "rb").read()
lf = struct.unpack_from("<I", data, 0x14)[0]
print("文件大小 =", len(data), "LinkFlags =", hex(lf),
      "HasIDList =", bool(lf & 0x2), "HasLinkInfo =", bool(lf & 0x1))
# 读回实际目标
GetPath = mm(sl, 3, ctypes.c_long, [c_wchar_p, c_int, c_void_p, c_ulong])
pb = create_unicode_buffer(260)
GetPath(sl, pb, 260, None, 0)
print("读回目标 =", repr(pb.value))
if pb.value.endswith("pythonw.exe") and (lf & 0x2):
    print("OK 快捷方式有效：" + LNK)
else:
    print("WARN 快捷方式可能无效")
