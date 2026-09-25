#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把安卓端成品 PNG 图标拼成 Windows 多分辨率 .ico（纯标准库，无外部依赖）。

用法：
    python build_icon.py [安卓工程 res 目录]

默认按仓库布局推断：<repo>/app/src/main/res。
"""
import struct
import os
import sys

APP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(APP_DIR)

_default_res = os.path.join(REPO, "app", "src", "main", "res")
ROOT = sys.argv[1] if len(sys.argv) > 1 else _default_res
ASSETS = os.path.join(APP_DIR, "assets")
os.makedirs(ASSETS, exist_ok=True)

if not os.path.isdir(ROOT):
    raise SystemExit("找不到安卓 res 目录：%s\n请把它作为第一个参数传入。" % ROOT)

# size -> 源 PNG
SRC = {
    48: os.path.join(ROOT, "mipmap-mdpi", "ic_launcher.png"),
    96: os.path.join(ROOT, "mipmap-xhdpi", "ic_launcher.png"),
    144: os.path.join(ROOT, "mipmap-xxhdpi", "ic_launcher.png"),
    192: os.path.join(ROOT, "mipmap-xxxhdpi", "ic_launcher.png"),
}

OUT = os.path.join(ASSETS, "app_icon.ico")

images = []
for size in sorted(SRC):
    data = open(SRC[size], "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{SRC[size]} 不是合法 PNG"
    images.append((size, data))

# ICONDIR
icondir = struct.pack("<HHH", 0, 1, len(images))
offset = 6 + 16 * len(images)
dir_entries = b""
for size, data in images:
    w = size if size < 256 else 0
    h = size if size < 256 else 0
    # width, height, colorCount, reserved, planes, bitCount, bytesInRes, imageOffset
    dir_entries += struct.pack("<BBBBHHII", w, h, 0, 0, 1, 32, len(data), offset)
    offset += len(data)

body = b"".join(data for _, data in images)
with open(OUT, "wb") as f:
    f.write(icondir + dir_entries + body)

print(f"OK 写出 {OUT}  包含分辨率: {[s for s, _ in images]}  大小 {os.path.getsize(OUT)} 字节")
