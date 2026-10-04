#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""转码类功能，全部走 ffmpeg。

**为什么不移植手机端的实现**：手机端这部分是
``TitleOverlayTranscoder``(1705 行) + ``MediaStitcher``(989 行)，
合计 2694 行，全部基于 Android 的 ``MediaCodec`` / ``MediaMuxer`` ——
那是**平台 API，不是算法**，PC 上根本没有对应物。硬移植等于重写一个
视频编解码器。ffmpeg 一行命令行就干完，所以这里只做命令拼装。

每个函数都先确认 ffmpeg 在不在；不在就抛 ``FfmpegMissing``，
由上层降级成「只给原片 + 一句说明」，**绝不静默产出一个坏文件**。
"""
import os
import shutil
import subprocess

# 微软雅黑，烧标题时需要中文字体；缺了 drawtext 会输出豆腐块
_FONT_CANDIDATES = (
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
)


class FfmpegMissing(Exception):
    pass


class MediaError(Exception):
    pass


def ffmpeg_path():
    return shutil.which("ffmpeg")


def have_ffmpeg():
    return ffmpeg_path() is not None


def font_path():
    for p in _FONT_CANDIDATES:
        if os.path.isfile(p):
            return p
    return None


def _run(args, timeout=1800):
    exe = ffmpeg_path()
    if not exe:
        raise FfmpegMissing("没找到 ffmpeg，这个功能需要它。装好后重开一次程序即可。")
    proc = subprocess.run([exe, "-hide_banner", "-loglevel", "error", "-y"] + args,
                          capture_output=True, timeout=timeout)
    if proc.returncode != 0:
        detail = None
        for enc in ("utf-8", "cp936", "cp1252"):
            try:
                detail = proc.stderr.decode(enc).strip()
                break
            except Exception:
                continue
        raise MediaError((detail or "ffmpeg 执行失败")[-400:])
    return proc


def _escape_drawtext(text):
    """drawtext 的 text 参数里 : ' \\ % , [ ] 都有语法含义，必须转义。

    顺序要紧：先转义反斜杠，否则会把后面加的转义符又转一遍。
    """
    out = text.replace("\\", "\\\\")
    for ch in (":", "'", "%", ",", "[", "]"):
        out = out.replace(ch, "\\" + ch)
    return out


def extract_audio(video_path, out_path):
    """抽音轨（视频号「仅保存音乐」）。

    视频号没有独立的音乐资源，音频就是视频本身的音轨；
    ``-c:a copy`` 直接搬 AAC 流，不重编码，秒级完成。
    """
    if not os.path.isfile(video_path):
        raise MediaError("源文件不存在")
    _run(["-i", video_path, "-vn", "-c:a", "copy", "-movflags", "+faststart", out_path])
    if not os.path.isfile(out_path) or os.path.getsize(out_path) == 0:
        raise MediaError("抽音轨后没有得到有效文件（可能原片没有音轨）")
    return out_path


def apply_cover(video_path, cover_path, out_path):
    """把封面写进 MP4。

    手机端是手写 ``covr`` box（``Mp4CoverInjector``，522 行纯字节操作）；
    ffmpeg 用 ``attached_pic`` 定位一张附加图，效果等价且不用自己维护 box 结构。
    ``-c copy`` 不重编码，所以和存原片一样快。
    """
    if not os.path.isfile(video_path):
        raise MediaError("源文件不存在")
    if not os.path.isfile(cover_path):
        raise MediaError("封面文件不存在")
    _run(["-i", video_path, "-i", cover_path,
          "-map", "0", "-map", "1", "-c", "copy",
          "-disposition:v:1", "attached_pic", "-movflags", "+faststart", out_path])
    return out_path


def burn_title(video_path, out_path, title, font_size=28, margin=40,
               box_opacity=0.45):
    """把标题烧进画面（对应手机端的 ``TitleOverlayTranscoder``）。

    这一步必然整段重编码，所以慢——手机端也是这么做的，
    只有当用户明确勾选「烧标题」时才走这条路。
    """
    if not os.path.isfile(video_path):
        raise MediaError("源文件不存在")
    font = font_path()
    if not font:
        raise MediaError("找不到中文字体，无法烧标题")

    # 字体路径也是 filter 参数，冒号和反斜杠同样要转义
    font_arg = font.replace("\\", "/").replace(":", "\\:")
    text = _escape_drawtext(title.strip())
    draw = (f"drawtext=fontfile='{font_arg}':text='{text}':"
            f"fontcolor=white:fontsize={font_size}:"
            f"box=1:boxcolor=black@{box_opacity}:boxborderw=12:"
            f"x=(w-text_w)/2:y=h-text_h-{margin}")
    _run(["-i", video_path, "-vf", draw,
          "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
          "-c:a", "copy", "-movflags", "+faststart", out_path])
    return out_path


def stitch_images(image_paths, out_path, seconds_per_image=2.0, size=None):
    """把多张图拼成一个视频（对应手机端的 ``MediaStitcher``）。

    图片尺寸可能各不相同，直接 concat 会失败，所以统一 scale + pad 到同一画布。
    """
    paths = [p for p in image_paths if os.path.isfile(p)]
    if not paths:
        raise MediaError("没有可用的图片")
    if len(paths) == 1:
        paths = paths * 2

    if size is None:
        size = (1080, 1920)
    w, h = size

    work = os.path.dirname(out_path) or "."
    list_path = os.path.join(work, "_stitch_list.txt")
    with open(list_path, "w", encoding="utf-8") as f:
        for p in paths:
            f.write("file '%s'\n" % p.replace("\\", "/").replace("'", "'\\''"))
            f.write("duration %.3f\n" % seconds_per_image)
        # concat 解复用器要求最后一项重复一次，否则最后一帧的 duration 会被丢掉
        f.write("file '%s'\n" % paths[-1].replace("\\", "/").replace("'", "'\\''"))

    vf = (f"scale={w}:{h}:force_original_aspect_ratio=decrease,"
          f"pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black,"
          f"format=yuv420p")
    try:
        _run(["-f", "concat", "-safe", "0", "-i", list_path,
              "-vf", vf, "-r", "30", "-c:v", "libx264", "-preset", "veryfast",
              "-crf", "20", "-movflags", "+faststart", out_path])
    finally:
        try:
            os.remove(list_path)
        except OSError:
            pass
    return out_path


def composite_stickers(image_path, overlays, sticker_paths, out_path):
    """把贴纸烧进图片（对应手机端的分层贴纸合成）。

    只在平台**没有**下发合成图时才会走到这里——多数情况下
    ``sticker_composite_urls`` 已经是平台做好的整图，直接存就行，不必自己画。

    ``overlays`` 是 ``StickerOverlay`` 列表，``sticker_paths`` 是与
    ``overlay.resource_url`` 对应的本地文件。旋转角度用的是度。
    """
    if not os.path.isfile(image_path):
        raise MediaError("源图片不存在")
    layers = [(ov, p) for ov, p in zip(overlays, sticker_paths)
              if p and os.path.isfile(p) and (ov.text or ov.resource_url)]
    if not layers:
        raise MediaError("没有可合成的贴纸")

    args = ["-i", image_path]
    for _, p in layers:
        args += ["-i", p]

    # 只对静态 png 资源做叠加；纯文字贴纸 ffmpeg 画不出来（不支持内联文字图层），
    # 直接跳过——少一层贴纸远好过整个保存失败。
    #
    # 缩放必须用 scale2ref，不能用 scale：贴纸要按**画布**宽度的比例缩放，
    # 而 scale 的表达式里只有自己的 iw/ih，拿不到主画面的 W（会直接报
    # "Undefined constant ... 'W/iw'"）。scale2ref 专门为「参照另一个输入来缩放」
    # 而存在，第一个输入被缩放、第二个是参照，表达式里用 main_w 指参照宽度。
    # 注意每个 label 只能被消费一次，所以参照流每步都要接住再传下去。
    parts = []
    bg = "0:v"
    last = None
    for n, (ov, _p) in enumerate(layers, start=1):
        source = f"{n}:v"
        scaled = None
        if ov.width_ratio and ov.width_ratio > 0:
            parts.append(
                f"[{source}][{bg}]scale2ref=w=main_w*{ov.width_ratio:.4f}"
                f":h=ow/mdar[sc{n}][bg{n}]")
            scaled, bg = f"sc{n}", f"bg{n}"
        else:
            scaled = source
        if ov.rotation and abs(ov.rotation) > 0.5:
            parts.append(f"[{scaled}]rotate={ov.rotation:.4f}*PI/180"
                         f":fillcolor=none:ow=rotw(iw):oh=roth(ih)[rot{n}]")
            scaled = f"rot{n}"
        # overlay 按左上角定位，而我们的坐标是中心点，所以要各减半个贴纸宽高
        parts.append(
            f"[{bg}][{scaled}]overlay="
            f"(W*{ov.center_x:.4f}-overlay_w/2):(H*{ov.center_y:.4f}-overlay_h/2)[o{n}]")
        bg = f"o{n}"
        last = f"o{n}"

    if last is None:
        raise MediaError("没有可合成的贴纸")
    filter_complex = ";".join(parts)
    _run([*args, "-filter_complex", filter_complex, "-map", f"[{last}]",
          "-frames:v", "1", out_path])
    return out_path


def info():
    """给界面显示的能力清单。"""
    exe = ffmpeg_path()
    return {
        "ffmpeg": exe,
        "available": exe is not None,
        "font": font_path(),
        "features": {
            "extract_audio": exe is not None,
            "burn_title": exe is not None and font_path() is not None,
            "apply_cover": exe is not None,
            "stitch_images": exe is not None,
            "composite_stickers": exe is not None,
        },
    }


if __name__ == "__main__":
    import json
    print(json.dumps(info(), ensure_ascii=False, indent=2))
    assert have_ffmpeg(), "本机应当装了 ffmpeg"
    assert font_path(), "本机应当有中文字体"
