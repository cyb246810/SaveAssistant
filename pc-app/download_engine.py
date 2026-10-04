#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载编排：把「解析」和「保存」接起来，屏蔽两个平台的差异。

上层（HTTP 接口 / 界面）只需要认识两件事：

- ``parse(url, credential)`` → ``Job``，里面是一份**扁平的可勾选条目清单**
- ``save(job, item_ids, options, dest_root, progress)`` → 逐条保存并回报进度

两个平台解析出来的结构完全不同（抖音是 video/images/live/music/cover，
视频号是 video/picUrls 且音乐要从视频里抽），但在这一层统一成同一种条目，
界面就不用管平台差异了。
"""
import os
import re
import time
import uuid

import dy_parse
import media_ff
import wx_parse

# 条目种类 → 中文标签，界面直接用
KIND_LABELS = {
    "video": "视频",
    "image": "图片",
    "live": "实况",
    "music": "配乐",
    "cover": "封面",
}

# 各平台媒体 CDN 白名单（下载时的安全边界）
WX_EXTRA_DOMAINS = wx_parse.MEDIA_DOMAINS


class DownloadError(Exception):
    pass


def _safe_name(name, fallback="未命名"):
    """文件名净化：去掉路径分隔符与 Windows 保留字符，并限制长度。

    标题来自第三方页面，直接当文件名用会被 `../` 之类写穿目录。
    """
    if not name:
        return fallback
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", str(name))
    name = name.replace("\r", " ").replace("\n", " ").strip(" .")
    name = re.sub(r"\s+", " ", name)
    if not name:
        return fallback
    return name[:80]


def _unique_path(path):
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    for i in range(1, 1000):
        candidate = f"{base}_{i}{ext}"
        if not os.path.exists(candidate):
            return candidate
    return f"{base}_{int(time.time())}{ext}"


# 各条目种类落到哪个子目录。**必须**与 server.py 的 CATEGORY_LABELS 对齐：
# 磁盘扫描只扫这几个子目录，写错位置就会「保存成功但列表里看不到」。
_KIND_DIR = {
    "video": "video",
    "live": "video",
    "image": "image",
    "cover": "cover",
    "music": "audio",
}


def _dest_dir(dest_root, kind):
    d = os.path.join(dest_root, _KIND_DIR.get(kind, "other"))
    os.makedirs(d, exist_ok=True)
    return d


class Item:
    """一个可勾选、可保存的条目。"""

    def __init__(self, item_id, kind, url, label=None, index=None,
                 preview_url=None, has_stickers=False, live=False):
        self.id = item_id
        self.kind = kind
        self.url = url
        self.label = label or KIND_LABELS.get(kind, kind)
        self.index = index
        self.preview_url = preview_url or url
        self.has_stickers = has_stickers
        self.live = live

    def to_dict(self):
        return {
            "id": self.id,
            "kind": self.kind,
            "label": self.label,
            "url": self.url,
            "preview_url": self.preview_url,
            "index": self.index,
            "has_stickers": self.has_stickers,
            "live": self.live,
        }


class Job:
    def __init__(self, platform, title, author, items, raw=None):
        self.id = uuid.uuid4().hex[:12]
        self.platform = platform
        self.title = title or ""
        self.author = author or ""
        self.items = items
        self.raw = raw or {}
        self.created = time.time()

    def to_dict(self):
        return {
            "job_id": self.id,
            "platform": self.platform,
            "title": self.title,
            "author": self.author,
            "items": [i.to_dict() for i in self.items],
            "created": self.created,
        }


# ---------------------------------------------------------------------------
# 解析
# ---------------------------------------------------------------------------
def detect_platform(url):
    if wx_parse.is_channels_share(url):
        return "channels"
    if dy_parse.extract_video_id(url) or re.search(
            r"(?:douyin|iesdouyin)\.com", url or "", re.IGNORECASE):
        return "douyin"
    # 认不出平台、但本身是个 http(s) 地址 → 当直链处理。
    # 这样「直链下载」不走第二套代码，省掉一条并行维护的路径。
    if re.match(r"https?://\S+", (url or "").strip(), re.IGNORECASE):
        return "direct"
    return None


def parse(url, credential=None):
    """解析链接，返回 Job。失败抛 DownloadError。"""
    if not url or not url.strip():
        raise DownloadError("请先粘贴链接")
    text = url.strip()

    platform = detect_platform(text)
    if platform is None:
        raise DownloadError("没认出这个链接。支持抖音/西瓜/微信视频号分享链接，"
                            "以及能直接访问的视频直链。")

    if platform == "direct":
        return _job_from_direct(text)

    if platform == "channels":
        try:
            result = wx_parse.parse(text, credential)
        except wx_parse.ParseError as e:
            raise DownloadError(str(e))
        return _job_from_channels(result)

    try:
        result = dy_parse.parse(text)
    except dy_parse.ParseError as e:
        raise DownloadError(str(e))
    return _job_from_douyin(result)


def _job_from_direct(url):
    """直链：当成一个视频条目，标题取 URL 最后一段。"""
    from urllib.parse import unquote, urlparse
    name = unquote(os.path.basename(urlparse(url).path or "")) or "直链下载"
    name = _safe_name(os.path.splitext(name)[0] or "直链下载")
    return Job("direct", name, "", [Item("video", "video", url, "视频（直链）")],
               raw={"direct": True})


def _job_from_douyin(result):
    items = []
    title = (result.title or "").strip()

    if result.video_url:
        items.append(Item("video", "video", result.video_url, "视频（无水印）"))
    if result.cover_url:
        items.append(Item("cover", "cover", result.cover_url, "封面图"))

    for i, url in enumerate(result.image_urls):
        overlays = result.sticker_overlays[i] if i < len(result.sticker_overlays) else []
        has_stickers = bool(overlays) or bool(
            i < len(result.sticker_composite_urls) and result.sticker_composite_urls[i])
        items.append(Item(f"image:{i}", "image", url, f"图片 {i + 1}",
                          index=i, preview_url=(
                              result.image_preview_urls[i]
                              if i < len(result.image_preview_urls) else url),
                          has_stickers=has_stickers))
    for i, url in enumerate(result.live_video_urls):
        if url:
            items.append(Item(f"live:{i}", "live", url, f"实况视频 {i + 1}", index=i, live=True))

    if result.music_url:
        music_label = "配乐" + (f"（{result.music_title}）" if result.music_title else "")
        items.append(Item("music", "music", result.music_url, music_label))

    return Job("douyin", title, "", items, raw={
        "aweme_id": result.aweme_id,
        "is_image_post": result.is_image_post,
        "sticker_overlays": [
            [{"resource_url": o.resource_url, "text": o.text,
              "center_x": o.center_x, "center_y": o.center_y,
              "width_ratio": o.width_ratio, "rotation": o.rotation,
              "estimated": o.estimated} for o in ol]
            for ol in result.sticker_overlays
        ],
        "sticker_composite_urls": result.sticker_composite_urls,
        "cover_url": result.cover_url,
    })


def _job_from_channels(result):
    items = []
    if result.has_video():
        items.append(Item("video", "video", result.video_url, "视频"))
        # 视频号没有独立音乐资源，音频就是视频的音轨，所以配乐是「从已存的视频里抽」
        items.append(Item("music", "music", result.video_url, "仅保存音乐（抽音轨）"))
    if result.cover_url:
        items.append(Item("cover", "cover", result.cover_url, "封面图"))
    for i, url in enumerate(result.pic_urls):
        items.append(Item(f"image:{i}", "image", url, f"图片 {i + 1}", index=i))

    title = (result.description or "").strip()
    return Job("channels", title, result.author, items, raw={
        "share_id": result.share_id,
        "cover_url": result.cover_url,
        "like_count": result.like_count,
    })


# ---------------------------------------------------------------------------
# 保存
# ---------------------------------------------------------------------------
def save(job, item_ids, options, dest_root, progress=None):
    """保存选中的条目。返回逐条结果列表。

    ``options``:
      - ``burn_title``          把标题烧进视频（需 ffmpeg，必然重编码，慢）
      - ``apply_cover``         把封面写进视频元数据（需 ffmpeg，秒级）
      - ``composite_stickers``  把贴纸烧进图片（需 ffmpeg）
      - ``stitch_images``       把选中的图片拼成一个视频（需 ffmpeg）

    单条失败**不中断**其余条目：用户勾了 5 张图，第 3 张挂了不该连累后 2 张。
    """
    options = options or {}
    selected = [i for i in job.items if i.id in set(item_ids or [])]
    if not selected:
        raise DownloadError("没有勾选任何内容")

    ctx = _Ctx(job, dest_root, selected[0].url if job.platform == "direct" else None)
    reported = {"done": 0, "total": len(selected)}
    results = []

    def report(label, percent):
        if progress:
            progress(reported["done"], reported["total"], label, percent)

    video_path = None
    cover_url = job.raw.get("cover_url")

    for item in selected:
        label = item.label
        report(label, 0)
        try:
            if item.kind == "video":
                path = _save_video(ctx, item, options, report, cover_url)
                video_path = path
            elif item.kind == "music":
                path = _save_music(ctx, item, video_path, report)
            elif item.kind == "cover":
                path = _save_cover(ctx, item)
            elif item.kind == "image":
                path = _save_image(ctx, job, item, options, report)
            elif item.kind == "live":
                path = _save_live(ctx, item)
            else:
                raise DownloadError(f"不认识的条目类型：{item.kind}")

            results.append({"id": item.id, "ok": True, "path": path,
                            "name": os.path.basename(path),
                            "size": os.path.getsize(path) if os.path.isfile(path) else 0})
        except Exception as e:
            results.append({"id": item.id, "ok": False, "error": str(e)})
        reported["done"] += 1
        report(label, 100)

    # 拼图：图片都存好之后再合成，这样即使合成失败，原图还在
    if options.get("stitch_images") and job.raw.get("is_image_post"):
        image_paths = [r["path"] for r in results
                       if r["ok"] and r["id"].startswith("image:")]
        if len(image_paths) >= 2:
            try:
                out = _unique_path(os.path.join(_dest_dir(dest_root, "video"),
                                                ctx.base_name + "_拼图.mp4"))
                media_ff.stitch_images(image_paths, out)
                results.append({"id": "stitch", "ok": True, "path": out,
                                "name": os.path.basename(out),
                                "size": os.path.getsize(out)})
            except Exception as e:
                results.append({"id": "stitch", "ok": False, "error": str(e)})

    return {"job_id": job.id, "results": results}


class _Ctx:
    """一次保存任务的公共上下文。

    这些值（额外放行的 CDN、Referer、是否信任用户给的地址、目标目录、基础文件名）
    每个条目都要用，之前是靠一长串参数往下透传，加一个就得到处改签名、还容易漏传。
    """

    __slots__ = ("job", "dest_root", "extra", "referer", "trusted", "base_name")

    def __init__(self, job, dest_root, direct_url):
        self.job = job
        self.dest_root = dest_root
        self.base_name = _safe_name(
            job.title or job.raw.get("aweme_id") or "保存助手下载")

        is_wx = job.platform == "channels"
        self.extra = WX_EXTRA_DOMAINS if is_wx else None
        # 直链是用户自己粘进来的地址，不是页面内容给的 —— 白名单防的是后者，
        # 所以这里整体放行（仍要求 http/https，不允许 file:// 之类）。
        self.trusted = job.platform == "direct"
        if is_wx:
            self.referer = wx_parse.MEDIA_REFERER
        elif direct_url:
            from urllib.parse import urlparse
            p = urlparse(direct_url)
            self.referer = f"{p.scheme}://{p.netloc}/" if p.scheme else None
        else:
            self.referer = "https://www.douyin.com/"

    def download(self, url, dest, kind_code, progress=None):
        """kind_code: 0=图片 1=视频 2=音频"""
        return dy_parse.download_to_file(
            url, dest, kind_code, extra_domains=self.extra,
            referer=self.referer, progress=progress, trusted=self.trusted)

    def dir_for(self, kind):
        return _dest_dir(self.dest_root, kind)


def _save_video(ctx, item, options, report, cover_url):
    vdir = ctx.dir_for("video")
    tmp_path = _unique_path(os.path.join(vdir, ctx.base_name + ".mp4"))
    ctx.download(item.url, tmp_path, 1, lambda p: report(item.label, p))

    apply_cover = bool(options.get("apply_cover")) and bool(cover_url)
    burn_title = bool(options.get("burn_title"))

    if not (apply_cover or burn_title):
        return tmp_path

    if not media_ff.have_ffmpeg():
        # 明确告知降级，不静默产出「看起来加工过其实没有」的文件
        raise DownloadError("已保存原片；但勾选了封面/标题加工，而本机没有 ffmpeg，"
                            "这一步没做。装好 ffmpeg 后重试，原片在：" + tmp_path)

    cover_path = None
    if apply_cover:
        try:
            # 临时封面写在接收目录**根下**：磁盘扫描只扫分类子目录，
            # 万一半路崩了也不会留一张脏图混进「图片」里。
            cover_path = os.path.join(ctx.dest_root, f"_cover_{uuid.uuid4().hex[:8]}.jpg")
            ctx.download(cover_url, cover_path, 0)
        except Exception:
            cover_path = None

    out = _unique_path(os.path.join(vdir, ctx.base_name + "_加工.mp4"))
    try:
        if burn_title:
            media_ff.burn_title(tmp_path, out, options.get("title_text") or ctx.base_name)
        else:
            media_ff.apply_cover(tmp_path, cover_path, out)
        os.remove(tmp_path)
        return out
    except Exception:
        # 加工失败就保留原片，绝不让用户两头落空
        if os.path.exists(out):
            try:
                os.remove(out)
            except OSError:
                pass
        raise
    finally:
        if cover_path and os.path.exists(cover_path):
            try:
                os.remove(cover_path)
            except OSError:
                pass


def _save_music(ctx, item, video_path, report):
    adir = ctx.dir_for("audio")
    if ctx.job.platform == "channels":
        # 视频号的「音乐」就是视频音轨，必须先把视频拿到手再抽
        borrowed = None
        if video_path is None:
            if not media_ff.have_ffmpeg():
                raise DownloadError("视频号保存音乐需要 ffmpeg 从视频里抽音轨，"
                                    "本机没找到 ffmpeg")
            src = _unique_path(os.path.join(ctx.dir_for("video"),
                                            ctx.base_name + "_源片.mp4"))
            ctx.download(item.url, src, 1, lambda p: report(item.label, p))
            borrowed = src
        else:
            src = video_path
        try:
            out = _unique_path(os.path.join(adir, ctx.base_name + ".m4a"))
            media_ff.extract_audio(src, out)
            return out
        finally:
            # 用户没勾视频、只是顺手抽个音轨的话，这个源片是我们自己下的，
            # 抽完就清掉 —— 别在他目录里留一个他没要的视频。
            if borrowed and os.path.exists(borrowed):
                try:
                    os.remove(borrowed)
                except OSError:
                    pass
    out = _unique_path(os.path.join(adir, ctx.base_name + "_配乐.m4a"))
    ctx.download(item.url, out, 2, lambda p: report(item.label, p))
    return out


def _save_cover(ctx, item):
    out = _unique_path(os.path.join(ctx.dir_for("cover"), ctx.base_name + "_封面.jpg"))
    ctx.download(item.url, out, 0)
    return out


def _save_live(ctx, item):
    out = _unique_path(os.path.join(ctx.dir_for("video"),
                                    f"{ctx.base_name}_实况{item.index + 1}.mp4"))
    ctx.download(item.url, out, 1)
    return out


def _save_image(ctx, job, item, options, report):
    index = item.index or 0
    out = _unique_path(os.path.join(ctx.dir_for("image"),
                                    f"{ctx.base_name}_{index + 1}.jpg"))

    # 平台若已下发合成好的整图（贴纸已经烧进去了），直接用那张，省一次叠加
    composite = None
    composite_list = job.raw.get("sticker_composite_urls") or []
    if item.has_stickers and index < len(composite_list):
        composite = composite_list[index]

    ctx.download(composite or item.url, out, 0, lambda p: report(item.label, p))

    if not (options.get("composite_stickers") and item.has_stickers and not composite):
        return out

    overlays_data = job.raw.get("sticker_overlays") or []
    overlays_raw = overlays_data[index] if index < len(overlays_data) else []
    if not overlays_raw or not media_ff.have_ffmpeg():
        return out

    overlays, sticker_files = [], []
    for n, od in enumerate(overlays_raw):
        if not od.get("resource_url"):
            continue
        overlays.append(dy_parse.StickerOverlay(
            od["resource_url"], od.get("text"), od["center_x"], od["center_y"],
            od.get("width_ratio") or 0.3, 0.0, od.get("rotation") or 0.0))
        try:
            # 贴纸临时文件也放在根下（不参与分类扫描）
            sp = os.path.join(ctx.dest_root, f"_sticker_{job.id}_{index}_{n}.png")
            ctx.download(od["resource_url"], sp, 0)
            sticker_files.append(sp)
        except Exception:
            sticker_files.append(None)

    if not overlays or not any(sticker_files):
        return out
    merged = out[:-4] + "_带贴纸.jpg"
    try:
        media_ff.composite_stickers(out, overlays, sticker_files, merged)
        os.replace(merged, out)
    except Exception:
        # 贴纸只是锦上添花，合成失败就保留没贴纸的版本
        if os.path.exists(merged):
            try:
                os.remove(merged)
            except OSError:
                pass
    finally:
        for sp in sticker_files:
            if sp and os.path.exists(sp):
                try:
                    os.remove(sp)
                except OSError:
                    pass
    return out


if __name__ == "__main__":

    # 自检：只验不联网的纯逻辑（平台识别 / 文件名净化 / 去重）
    assert detect_platform("https://v.douyin.com/abc/") == "douyin"
    assert detect_platform("https://weixin.qq.com/sph/abc") == "channels"
    assert detect_platform("https://www.douyin.com/video/7300000000000000001") == "douyin"
    assert detect_platform("https://example.com/x.mp4") == "direct"
    assert detect_platform("这不是链接") is None
    assert detect_platform("") is None

    assert _safe_name('a/b\\c:d*e?f"g<h>i|j') == "a_b_c_d_e_f_g_h_i_j"
    assert _safe_name("") == "未命名"
    assert _safe_name("  ") == "未命名"
    assert len(_safe_name("很" * 500)) == 80

    # 真正要守住的安全属性：净化后不可能还是一个能跳出目录的路径。
    # （`strip(" .")` 会顺手吃掉开头的点，那是额外的加成，不是这条断言的重点。）
    for evil in ("../../etc/passwd", "..\\..\\Windows\\System32\\x",
                 "/etc/shadow", "C:\\Windows\\notepad.exe", "....//....//x"):
        cleaned = _safe_name(evil)
        assert "/" not in cleaned and "\\" not in cleaned, evil
        assert not cleaned.startswith("."), evil

    print("download_engine 自检通过")
