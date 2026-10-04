#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抖音无水印解析（``DouyinParser.java`` 的 Python 移植）。

策略与手机端完全一致，按以下顺序逐条尝试，谁先出内容用谁：

1. **Web 详情接口**（`/aweme/v1/web/aweme/detail/`，需要 a_bogus 签名）
2. **分享页 HTML**，依次尝试四种嵌入格式
   `_ROUTER_DATA` → `<script id="RENDER_DATA">` → `window._RENDER_DATA` → 正则兜底

为什么不砍掉任何一条：抖音的接口和页面结构几个月就换一次，手机端正是靠这几条
互相兜底才一直能用。少留一条，就多一种「线上突然解不了」的故障形态。

**与手机端的差异（刻意为之）**：
- 不移植 `SimpleHttps`（254 行）：Java 的 `HttpURLConnection` 会**静默丢弃 `Origin`
  这个受限请求头**，所以视频号那边不得不自己写一套。Python 的 `urllib` 没有这个限制，
  直接设 header 就行。
- 不手写 SM3：标准库 `hashlib` 自带（见 `dy_sign.py`）。
"""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

import dy_sign

UA_MOBILE = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
             "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")

MAX_REDIRECTS = 10
MAX_HTML_BYTES = 5 * 1024 * 1024

_SHARE_URL_RE = re.compile(
    r"https://(?:[A-Za-z0-9-]+\.)*(?:douyin\.com|iesdouyin\.com)/[^\s]+", re.IGNORECASE)
_ID_PATH_RE = re.compile(
    r"/(?:video|note|aweme/detail|share/(?:video|slides))/(\d{15,25})(?:[/?#]|$)", re.IGNORECASE)
_ID_QUERY_RE = re.compile(
    r"(?:[?&]|&amp;)(?:modal_id|aweme_id|item_id)=(\d{15,25})(?:[&#]|$)", re.IGNORECASE)

_PAGE_DOMAINS = ("douyin.com", "iesdouyin.com", "ixigua.com")
_MEDIA_DOMAINS = (
    "douyin.com", "iesdouyin.com", "douyinvod.com", "douyinpic.com",
    "bytecdn.cn", "bytecdn.com", "zjcdn.com", "snssdk.com", "amemv.com",
    "byteimg.com", "bytedance.com", "volccdn.com", "pstatp.com", "ixigua.com",
    "douyinstatic.com",
)


class ParseError(Exception):
    pass


# ---------------------------------------------------------------------------
# URL 工具
# ---------------------------------------------------------------------------
def is_allowed_https(url, allowed_domains):
    """只放行 https、默认端口、且命中域名白名单的地址。

    这是安全边界：解析出来的地址来自第三方页面，不校验就等于让远端的页面
    内容决定我们往哪儿发请求（乃至写盘）。**不要为了"兼容"放宽它。**
    """
    try:
        p = urllib.parse.urlparse(url)
    except Exception:
        return False
    if p.scheme.lower() != "https":
        return False
    if p.port not in (None, 443):
        return False
    host = (p.hostname or "").lower()
    return any(host == d or host.endswith("." + d) for d in allowed_domains)


def is_allowed_page_url(url):
    return is_allowed_https(url, _PAGE_DOMAINS)


def is_allowed_media_url(url):
    return is_allowed_https(url, _MEDIA_DOMAINS)


def normalize_media_url(url):
    if not url:
        return None
    out = url.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")
    if out.startswith("http://"):
        out = "https://" + out[len("http://"):]
    return out


def normalize_play_url(url):
    normalized = normalize_media_url(url)
    if normalized is None:
        return None
    return normalized.replace("/playwm/", "/play/").replace("playwm", "play")


def build_play_url(video_id):
    return ("https://aweme.snssdk.com/aweme/v1/play/?video_id="
            + urllib.parse.quote(video_id, safe="") + "&ratio=1080p&line=0")


# ---------------------------------------------------------------------------
# JSON 取值工具（对应 Java 的 optJSONObject / optString 等）
# ---------------------------------------------------------------------------
def _obj(node, *keys):
    for k in keys:
        v = node.get(k) if isinstance(node, dict) else None
        if isinstance(v, dict):
            return v
    return None


def _arr(node, *keys):
    for k in keys:
        v = node.get(k) if isinstance(node, dict) else None
        if isinstance(v, list):
            return v
    return None


def _str(node, *keys):
    """第一个非空字符串。Java 的 optString(key, null) 不区分数字，这里也接受数字。"""
    for k in keys:
        v = node.get(k) if isinstance(node, dict) else None
        if isinstance(v, str) and v != "":
            return v
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            return str(v)
    return None


def _num(node, fallback, *keys):
    for k in keys:
        v = node.get(k) if isinstance(node, dict) else None
        if isinstance(v, bool):
            continue
        if isinstance(v, (int, float)):
            return v
        if isinstance(v, str):
            try:
                return float(v)
            except ValueError:
                pass
    return fallback


# ---------------------------------------------------------------------------
# 链接识别与短链跳转
# ---------------------------------------------------------------------------
def extract_video_id(text):
    if not text:
        return None
    stripped = text.strip()
    if re.fullmatch(r"\d{15,25}", stripped):
        return stripped
    m = _ID_PATH_RE.search(text)
    if m:
        return m.group(1)
    m = _ID_QUERY_RE.search(text)
    if m:
        return m.group(1)
    return None


def _extract_share_url(text):
    if not text:
        return None
    m = _SHARE_URL_RE.search(text)
    if not m:
        return None
    url = m.group(0)
    trailing = "，。！？；：、,!?;:)]}》〉】\"'"
    while url and url[-1] in trailing:
        url = url[:-1]
    return url


def resolve_share(share_text):
    """把分享文本解析成 (video_id, page_url)。返回 None 表示认不出来。"""
    direct_id = extract_video_id(share_text)
    extracted = _extract_share_url(share_text)

    if direct_id:
        page = extracted if (extracted and is_allowed_page_url(extracted)) else \
            "https://www.iesdouyin.com/share/video/" + direct_id + "/"
        return direct_id, page

    if not extracted:
        return None

    current = extracted
    for _ in range(MAX_REDIRECTS + 1):
        if not is_allowed_page_url(current):
            raise ParseError("分享链接跳转到了不受支持的站点")
        found = extract_video_id(current)
        if found:
            return found, current
        status, location, html = _fetch_once(current, UA_MOBILE, "https://www.douyin.com/")
        if status in (301, 302, 303, 307, 308):
            if not location:
                raise ParseError("短链跳转缺少目标地址")
            current = urllib.parse.urljoin(current, location)
            continue
        if status != 200:
            raise ParseError(f"短链请求失败，响应码: {status}")
        found = extract_video_id(html)
        if found:
            return found, current
        return None
    raise ParseError("短链跳转次数过多")


def _fetch_once(url, ua, referer, max_bytes=MAX_HTML_BYTES, timeout=15, extra=None):
    """单次请求，不自动跟随跳转（要自己看 Location 做跳转白名单校验）。"""
    headers = {
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": referer,
    }
    if extra:
        headers.update(extra)

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **kw):
            return None

    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(urllib.request.Request(url, headers=headers), timeout=timeout) as r:
            return r.status, r.headers.get("Location"), _read_limited(r, max_bytes)
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location") if e.headers else None
        return e.code, loc, _read_limited(e, max_bytes)


def _read_limited(resp, max_bytes):
    try:
        raw = resp.read(max_bytes + 1)
    except Exception:
        return ""
    if len(raw) > max_bytes:
        raw = raw[:max_bytes]
    return raw.decode("utf-8", "replace")


def fetch_html(url):
    current = url
    for _ in range(MAX_REDIRECTS + 1):
        if not is_allowed_page_url(current):
            raise ParseError("页面地址不在允许列表中")
        status, location, html = _fetch_once(current, UA_MOBILE, "https://www.douyin.com/")
        if status in (301, 302, 303, 307, 308):
            if not location:
                raise ParseError("页面重定向缺少目标地址")
            current = urllib.parse.urljoin(current, location)
            continue
        if status != 200:
            raise ParseError(f"页面请求失败，响应码: {status}")
        return html
    raise ParseError("页面重定向次数过多")


# ---------------------------------------------------------------------------
# 详情接口（需要 a_bogus 签名）
# ---------------------------------------------------------------------------
def _get_ttwid():
    body = ('{"region":"cn","aid":6383,"need_t":1,"service":"www.douyin.com",'
            '"migrate_priority":0,"cb_url_protocol":"https","domain":".douyin.com"}')
    req = urllib.request.Request(
        "https://ttwid.bytedance.com/ttwid/union/register/",
        data=body.encode("utf-8"),
        headers={"User-Agent": UA_DESKTOP, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            for k, v in r.getheaders():
                if k.lower() == "set-cookie" and "ttwid=" in v:
                    # 原值形如 `1%7Cxxx`，必须解码成 `1|xxx` 再回传，否则被判无效
                    return urllib.parse.unquote(v.split("ttwid=", 1)[1].split(";", 1)[0])
    except Exception:
        pass
    return None


def _random_ms_token(length=107):
    import random
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789="
    return "".join(random.choice(alphabet) for _ in range(length))


def fetch_aweme_detail(aweme_id, referer):
    query = ("device_platform=webapp&aid=6383&channel=channel_pc_web"
             "&pc_client_type=1&version_code=190500&version_name=19.5.0"
             "&cookie_enabled=true&screen_width=1920&screen_height=1080"
             "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome"
             "&browser_version=123.0.0.0&browser_online=true&engine_name=Blink"
             "&engine_version=123.0.0.0&os_name=Windows&os_version=10"
             "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10"
             "&effective_type=4g&round_trip_time=100&aweme_id=" + aweme_id +
             "&msToken=" + urllib.parse.quote(_random_ms_token()))
    signature = dy_sign.generate(query, UA_DESKTOP)
    url = ("https://www.douyin.com/aweme/v1/web/aweme/detail/?" + query +
           "&a_bogus=" + urllib.parse.quote(signature))

    note_page = referer and "/note/" in referer.lower()
    headers = {
        "User-Agent": UA_DESKTOP,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Origin": "https://www.douyin.com",
        "Referer": ("https://www.douyin.com/note/" + aweme_id) if note_page
                   else ("https://www.douyin.com/video/" + aweme_id + "?previous_page=web_code_link"),
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
        "Sec-CH-UA": '"Google Chrome";v="123", "Chromium";v="123"',
        "Sec-CH-UA-Mobile": "?0",
        "Sec-CH-UA-Platform": '"Windows"',
    }
    ttwid = _get_ttwid()
    if ttwid:
        headers["Cookie"] = "ttwid=" + ttwid

    try:
        with urllib.request.urlopen(
                urllib.request.Request(url, headers=headers), timeout=20) as r:
            raw = r.read(MAX_HTML_BYTES).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        detail = _read_limited(e, 4096).strip()
        raise ParseError(f"详情接口响应码 {e.code}"
                         + (f"：{detail[:120]}" if detail else ""))
    try:
        data = json.loads(raw)
    except Exception:
        raise ParseError("详情接口返回的不是 JSON")
    if not isinstance(data.get("aweme_detail"), dict):
        raise ParseError(str(data.get("status_msg") or "详情接口没有返回视频数据"))
    return data


# ---------------------------------------------------------------------------
# 结果容器
# ---------------------------------------------------------------------------
class StickerOverlay:
    __slots__ = ("resource_url", "text", "center_x", "center_y",
                 "width_ratio", "height_ratio", "rotation", "estimated")

    def __init__(self, resource_url, text, cx, cy, wr, hr, rotation, estimated=False):
        self.resource_url = resource_url
        self.text = text
        self.center_x = cx
        self.center_y = cy
        self.width_ratio = wr
        self.height_ratio = hr
        self.rotation = rotation
        self.estimated = estimated


class ParseResult:
    """一个作品解析出来的全部可保存素材。

    视频与图文互斥（`video_url` 或 `image_urls` 只有一边有内容）；
    配乐、封面在两种形态下都可能存在。
    """

    def __init__(self):
        self.aweme_id = None
        self.video_url = None
        self.title = None
        self.cover_url = None
        self.image_urls = []
        self.image_preview_urls = []
        self.live_video_urls = []
        self.sticker_composite_urls = []
        self.sticker_overlays = []      # 与 image_urls 对齐：list[list[StickerOverlay]]
        self.music_url = None
        self.music_title = None

    @property
    def is_image_post(self):
        return bool(self.image_urls)

    def to_dict(self):
        return {
            "aweme_id": self.aweme_id,
            "title": self.title,
            "video_url": self.video_url,
            "cover_url": self.cover_url,
            "image_urls": self.image_urls,
            "image_preview_urls": self.image_preview_urls,
            "live_video_urls": self.live_video_urls,
            "sticker_composite_urls": self.sticker_composite_urls,
            "music_url": self.music_url,
            "music_title": self.music_title,
            "is_image_post": self.is_image_post,
        }


# ---------------------------------------------------------------------------
# 从 HTML 提取
# ---------------------------------------------------------------------------
def extract_video_info(html):
    """四种嵌入格式依次尝试，与手机端同序。"""
    if not html:
        return None
    html_music = _extract_music_from_html(html)

    for extractor in (_from_router_data, _from_render_data_script,
                      _from_window_render_data, _by_regex):
        result = extractor(html)
        if result is not None:
            if result.music_url is None and html_music[0]:
                result.music_url, result.music_title = html_music
            return result
    return None


def _from_router_data(html):
    m = re.search(r"(?:window\.)?_ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", html, re.DOTALL)
    if not m:
        return None
    try:
        return _parse_video_from_json(json.loads(m.group(1)))
    except Exception:
        return None


def _from_render_data_script(html):
    m = re.search(r"<script[^>]*id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>", html, re.DOTALL)
    if not m:
        return None
    try:
        return _parse_video_from_json(json.loads(urllib.parse.unquote(m.group(1).strip())))
    except Exception:
        return None


def _from_window_render_data(html):
    m = re.search(r"window\._RENDER_DATA\s*=\s*[\"'](.*?)[\"']\s*;?", html, re.DOTALL)
    if not m:
        return None
    try:
        return _parse_video_from_json(json.loads(urllib.parse.unquote(m.group(1))))
    except Exception:
        return None


def _by_regex(html):
    for pattern in (r"https://[^\"'\s]+?/play/[^\"'\s]+?\.mp4[^\"'\s]*",
                    r"https://[^\"'\s]+?douyinpic[^\"'\s]+?\.mp4[^\"'\s]*"):
        m = re.search(pattern, html)
        if m:
            result = ParseResult()
            result.video_url = m.group(0).replace("playwm", "play")
            result.title = "抖音视频"
            return result
    return None


def _looks_like_aweme(node):
    if not isinstance(node, dict):
        return False
    has_media = isinstance(node.get("images"), list) or isinstance(node.get("video"), dict)
    has_meta = any(k in node for k in ("aweme_id", "awemeId", "desc", "author"))
    return has_media and has_meta


def _find_aweme_recursive(node, depth=0):
    if depth > 12:
        return None
    if isinstance(node, dict):
        if _looks_like_aweme(node):
            return node
        for v in node.values():
            found = _find_aweme_recursive(v, depth + 1)
            if found is not None:
                return found
    elif isinstance(node, list):
        for v in node:
            found = _find_aweme_recursive(v, depth + 1)
            if found is not None:
                return found
    return None


def _find_title_recursive(node, depth=0):
    if depth > 12:
        return None
    if isinstance(node, dict):
        if any(k in node for k in ("video", "aweme_id", "awemeId")):
            title = _str(node, "desc", "title")
            if title:
                return title
            author = node.get("author")
            if isinstance(author, dict):
                nickname = _str(author, "nickname", "name")
                if nickname:
                    return nickname
        for v in node.values():
            title = _find_title_recursive(v, depth + 1)
            if title:
                return title
    elif isinstance(node, list):
        for v in node:
            title = _find_title_recursive(v, depth + 1)
            if title:
                return title
    return None


def _parse_video_from_json(root):
    data = root.get("aweme_detail") if isinstance(root, dict) else None
    if not isinstance(data, dict):
        candidate = root.get("data") if isinstance(root, dict) else None
        if _looks_like_aweme(candidate):
            data = candidate
    if not isinstance(data, dict):
        data = _find_aweme_recursive(root)

    title = _str(data, "desc", "title") if isinstance(data, dict) else None
    if not title and isinstance(data, dict):
        author = data.get("author")
        if isinstance(author, dict):
            title = _str(author, "nickname", "name")
    if not title:
        title = _find_title_recursive(root)

    music = _extract_music_media(data) if isinstance(data, dict) else (None, None)
    images = _extract_image_media(data) if isinstance(data, dict) else None

    if images and images["image_urls"]:
        result = ParseResult()
        result.title = title or "抖音图文"
        result.image_urls = images["image_urls"]
        result.image_preview_urls = images["preview_urls"]
        result.live_video_urls = images["live_video_urls"]
        result.sticker_composite_urls = images["sticker_composite_urls"]
        result.sticker_overlays = images["sticker_overlays"]
        result.music_url, result.music_title = music
        return result

    video_url = None
    if isinstance(data, dict):
        video = data.get("video")
        if isinstance(video, dict):
            video_url = get_play_url(video)
    if not video_url:
        video_url = _find_video_url_recursive(root)

    if video_url:
        result = ParseResult()
        result.video_url = video_url
        result.title = title or "抖音视频"
        result.cover_url = _extract_cover_url(data)
        result.music_url, result.music_title = music
        return result
    return None


# ---------------------------------------------------------------------------
# 播放地址
# ---------------------------------------------------------------------------
def get_play_url(video):
    """从 video 节点里挑播放地址。

    优先级：码率列表(取最高码率) → play_addr → playAddr 数组 → download_addr。
    码率列表是官方详情接口真正给的播放源，优先用它。
    """
    if not isinstance(video, dict):
        return None

    bit_rate = _arr(video, "bit_rate", "bitRate", "bitRateList")
    if bit_rate:
        best_url, best_rate = None, -1
        for br in bit_rate:
            if not isinstance(br, dict):
                continue
            pa = _obj(br, "play_addr", "playAddr")
            if pa is not None:
                url_list = _arr(pa, "url_list", "urlList")
                if url_list:
                    rate = max(int(_num(br, 0, "bit_rate", "bitRate")), 0)
                    if best_url is None or rate > best_rate:
                        best_url = normalize_play_url(url_list[0])
                        best_rate = rate
                uri = _str(pa, "uri", "video_id", "videoId")
                if best_url is None and uri:
                    best_url = build_play_url(uri)
            else:
                for item in (_arr(br, "playAddr") or []):
                    if isinstance(item, dict) and item.get("src"):
                        return normalize_play_url(item["src"])
        if best_url:
            return best_url

    play_addr = _obj(video, "play_addr", "playAddr")
    if play_addr is not None:
        url_list = _arr(play_addr, "url_list", "urlList")
        if url_list:
            return normalize_play_url(url_list[0])
        uri = _str(play_addr, "uri", "video_id", "videoId")
        if uri:
            return build_play_url(uri)

    # 少数实况数据把 playAddr 表示成 [{src: ...}]
    items = _arr(video, "playAddr")
    if items:
        fallback = None
        for item in items:
            if not isinstance(item, dict):
                continue
            src = normalize_play_url(item.get("src"))
            if not src:
                continue
            if "v3-web" in src:
                return src
            if fallback is None or "v26-web" in src:
                fallback = src
        if fallback:
            return fallback

    download_addr = _obj(video, "download_addr", "downloadAddr")
    if download_addr is not None:
        urls = _arr(download_addr, "url_list", "urlList")
        if urls:
            return normalize_play_url(urls[0])
    return None


def _find_video_url_recursive(node, depth=0):
    if depth > 14:
        return None
    if isinstance(node, dict):
        if any(k in node for k in ("play_addr", "playAddr", "bit_rate", "bitRate")):
            url = get_play_url(node)
            if url:
                return url
        for v in node.values():
            url = _find_video_url_recursive(v, depth + 1)
            if url:
                return url
    elif isinstance(node, list):
        for v in node:
            url = _find_video_url_recursive(v, depth + 1)
            if url:
                return url
    return None


def _extract_cover_url(data):
    if not isinstance(data, dict):
        return None
    video = data.get("video")
    if not isinstance(video, dict):
        return None
    cover = _obj(video, "origin_cover", "originCover", "cover")
    if cover is None:
        return None
    urls = _arr(cover, "url_list", "urlList")
    if not urls:
        return None
    url = normalize_media_url(urls[-1])
    return url if (url and is_allowed_media_url(url)) else None


# ---------------------------------------------------------------------------
# 配乐
# ---------------------------------------------------------------------------
_MUSIC_KEYS = ("music", "music_info", "musicInfo", "music_detail",
               "musicDetail", "music_info_v2", "musicInfoV2")
_MUSIC_PLAY_KEYS = ("play_url", "playUrl", "play_addr", "playAddr",
                    "audio_url", "audioUrl", "preview_url", "previewUrl")


def _extract_music_media(data):
    if not isinstance(data, dict):
        return None, None
    for key in _MUSIC_KEYS:
        if key in data:
            url, title = _music_from_value(data[key])
            if url:
                return url, title
    found = _find_music_recursive(data, 0)
    return found if found else (None, None)


def _find_music_recursive(node, depth):
    """只在明确的 music 节点里找，避免把视频 play_url 误当成配乐。"""
    if node is None or depth > 8:
        return None
    if isinstance(node, dict):
        for key, value in node.items():
            if key.lower() in ("music", "music_info", "musicinfo", "music_detail",
                               "musicdetail", "music_info_v2", "musicinfov2"):
                url, title = _music_from_value(value)
                if url:
                    return url, title
        for value in node.values():
            if isinstance(value, (dict, list)):
                found = _find_music_recursive(value, depth + 1)
                if found and found[0]:
                    return found
    elif isinstance(node, list):
        for value in node:
            found = _find_music_recursive(value, depth + 1)
            if found and found[0]:
                return found
    return None


def _music_from_value(value):
    if not isinstance(value, dict):
        return None, None
    title = _str(value, "title", "music_name", "musicName", "name",
                 "owner_nickname", "ownerNickname")
    for key in _MUSIC_PLAY_KEYS:
        if key in value:
            url = _extract_playable_url(value[key], 0)
            if url:
                return url, title
    direct = normalize_media_url(_str(value, "url", "src"))
    if direct and is_allowed_media_url(direct):
        return direct, title
    return None, title


def _extract_playable_url(value, depth):
    if value is None or depth > 5:
        return None
    if isinstance(value, str):
        url = normalize_media_url(value)
        return url if (url and is_allowed_media_url(url)) else None
    if isinstance(value, list):
        for item in value:
            url = _extract_playable_url(item, depth + 1)
            if url:
                return url
        return None
    if isinstance(value, dict):
        for key in ("url_list", "urlList", "urls", "play_url", "playUrl",
                    "uri", "url", "src"):
            if key in value:
                url = _extract_playable_url(value[key], depth + 1)
                if url:
                    return url
    return None


def _extract_music_from_html(html):
    """页面 JSON 结构又变了之后的最低兜底：音乐直链一般落在 douyinstatic 等 CDN。"""
    if not html:
        return None, None
    normalized = html.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")
    if "%2F" in normalized or "%3A" in normalized:
        normalized += "\n" + urllib.parse.unquote(normalized)
    patterns = (
        r"https://[^\"'<>\s]+douyinstatic\.com/[^\"'<>\s]*(?:ies-music|music)[^\"'<>\s]*",
        r"https://[^\"'<>\s]+(?:douyinpic\.com|byteimg\.com)/[^\"'<>\s]*(?:ies-music|music)[^\"'<>\s]*",
    )
    for pattern in patterns:
        for m in re.finditer(pattern, normalized, re.IGNORECASE):
            url = normalize_media_url(m.group(0))
            if url and is_allowed_media_url(url):
                return url, None
    return None, None


# ---------------------------------------------------------------------------
# 图集 / 实况 / 贴纸
# ---------------------------------------------------------------------------
def _looks_watermarked(url):
    if not url:
        return False
    low = url.lower()
    return any(k in low for k in ("watermark=", "logo_name=", "/mps/logo/",
                                  "watermark/1", "watermark=1"))


def _choose_image_url(urls, from_end=False, skip_watermark=False):
    if not urls:
        return None
    ordered = reversed(urls) if from_end else urls
    for candidate in ordered:
        url = normalize_media_url(candidate if isinstance(candidate, str) else None)
        if not url or not is_allowed_media_url(url):
            continue
        if skip_watermark and _looks_watermarked(url):
            continue
        return url
    return None


def _looks_like_sticker_image(url):
    if not url or not is_allowed_media_url(url):
        return False
    low = url.lower()
    return any(k in low for k in ("douyinpic", "byteimg", "tplv-", ".png", ".webp",
                                  ".jpg", ".jpeg", "image/"))


def _image_url_value(value):
    if value is None:
        return None
    if isinstance(value, str):
        url = normalize_media_url(value)
        return url if _looks_like_sticker_image(url) else None
    if isinstance(value, list):
        for item in value:
            url = _image_url_value(item)
            if url:
                return url
        return None
    if isinstance(value, dict):
        urls = _arr(value, "url_list", "urlList", "urls")
        if urls:
            url = _choose_image_url([u for u in urls if isinstance(u, str)])
            if _looks_like_sticker_image(url):
                return url
        direct = normalize_media_url(_str(value, "url", "uri"))
        if _looks_like_sticker_image(direct):
            return direct
    return None


def _extract_live_video_url(image):
    video = image.get("video")
    if isinstance(video, dict):
        url = get_play_url(video)
        if url and is_allowed_media_url(url):
            return url
    for field in ("animated_url_list", "gif_url_list", "live_url_list", "motion_url_list"):
        urls = _arr(image, field)
        if urls:
            url = normalize_media_url(urls[0] if isinstance(urls[0], str) else None)
            if url and is_allowed_media_url(url):
                return url
    direct = normalize_media_url(_str(image, "animated_url", "gif_url", "live_url", "motion_url"))
    return direct if (direct and is_allowed_media_url(direct)) else None


def _extract_sticker_composite_url(image):
    for key in ("sticker_image", "stickerImage", "stickered_image", "stickeredImage",
                "composite_image", "compositeImage", "image_with_sticker",
                "imageWithSticker", "image_with_stickers", "imageWithStickers",
                "display_image_with_stickers", "displayImageWithStickers"):
        if key in image:
            url = _image_url_value(image[key])
            if url:
                return url
    return None


def _usable_sticker_text(text):
    if not text:
        return False
    t = text.strip()
    return bool(t) and len(t) <= 500 and t.lower() != "null"


def _looks_like_url(value):
    if not value:
        return False
    low = value.strip().lower()
    return low.startswith("http://") or low.startswith("https://")


def _decode_json_value(raw):
    if not raw:
        return None
    text = raw.strip()
    try:
        if text[:3].lower() in ("%7b", "%5b"):
            text = urllib.parse.unquote(text)
        if text.startswith("{") or text.startswith("["):
            return json.loads(text)
    except Exception:
        pass
    return None


def _find_sticker_resource_url(node, depth=0):
    if not isinstance(node, dict) or depth > 5:
        return None
    for key in ("sticker_image", "stickerImage", "sticker_url", "stickerUrl",
                "resource_url", "resourceUrl", "icon_url", "iconUrl",
                "image_url", "imageUrl", "image", "icon", "resource"):
        if key in node:
            url = _image_url_value(node[key])
            if url:
                return url
    for key, value in node.items():
        low = key.lower()
        if not any(s in low for s in ("sticker", "resource", "icon")):
            continue
        url = _image_url_value(value)
        if url:
            return url
        url = _find_sticker_resource_url(value, depth + 1)
        if url:
            return url
    return None


def _find_sticker_text(node, depth=0):
    if node is None or depth > 6:
        return None
    if isinstance(node, str):
        decoded = _decode_json_value(node)
        if decoded is not None:
            return _find_sticker_text(decoded, depth + 1)
        text = node.strip()
        return text if (_usable_sticker_text(text) and not _looks_like_url(text)) else None
    if isinstance(node, list):
        parts = []
        for item in node:
            t = _find_sticker_text(item, depth + 1)
            if _usable_sticker_text(t):
                parts.append(t.strip())
        return "\n".join(parts) if parts else None
    if not isinstance(node, dict):
        return None
    for key in ("sticker_text", "stickerText", "text", "content", "question", "title",
                "desc", "text_content", "textContent", "display_text", "displayText",
                "label_text", "labelText"):
        value = node.get(key)
        if isinstance(value, (str, int, float)) and not isinstance(value, bool):
            text = str(value)
            if _usable_sticker_text(text) and not _looks_like_url(text):
                return text
    for key in ("sticker_text", "stickerText", "text_list", "textList", "options"):
        values = node.get(key)
        if isinstance(values, list):
            text = _find_sticker_text(values, depth + 1)
            if _usable_sticker_text(text):
                return text
    for key in ("text_info", "textInfo", "text_sticker_info", "textStickerInfo",
                "poll_info", "pollInfo", "vote_info", "voteInfo",
                "content_info", "contentInfo"):
        text = _find_sticker_text(node.get(key), depth + 1)
        if _usable_sticker_text(text):
            return text
    for key, value in node.items():
        low = key.lower()
        if not any(s in low for s in ("text", "content", "question", "label",
                                      "option", "poll", "vote")):
            continue
        text = _find_sticker_text(value, depth + 1)
        if _usable_sticker_text(text):
            return text
    return None


_POS_X_KEYS = ("x", "center_x", "centerX", "position_x", "positionX", "x_axis", "xAxis",
               "pos_x", "posX", "translation_x", "translationX", "offset_x", "offsetX",
               "start_x", "startX", "start_pos_x", "startPosX", "origin_x", "originX",
               "x_pos", "xPos", "anchor_x", "anchorX", "location_x", "locationX")
_POS_Y_KEYS = ("y", "center_y", "centerY", "position_y", "positionY", "y_axis", "yAxis",
               "pos_y", "posY", "translation_y", "translationY", "offset_y", "offsetY",
               "start_y", "startY", "start_pos_y", "startPosY", "origin_y", "originY",
               "y_pos", "yPos", "anchor_y", "anchorY", "location_y", "locationY")


def _find_sticker_position(node, depth=0):
    """贴纸的坐标可能埋在好几层里，甚至被序列化成 JSON 字符串。逐层挖。"""
    if node is None or depth > 5:
        return None
    if isinstance(node, str):
        decoded = _decode_json_value(node)
        return _find_sticker_position(decoded, depth + 1) if decoded is not None else None
    if isinstance(node, list):
        for item in node:
            found = _find_sticker_position(item, depth + 1)
            if found is not None:
                return found
        return None
    if not isinstance(node, dict):
        return None
    if any(k in node for k in _POS_X_KEYS) and any(k in node for k in _POS_Y_KEYS):
        return node
    if all(any(k in node for k in g) for g in (
            ("left", "left_x", "leftX", "min_x", "minX", "x1"),
            ("right", "right_x", "rightX", "max_x", "maxX", "x2"),
            ("top", "top_y", "topY", "min_y", "minY", "y1"),
            ("bottom", "bottom_y", "bottomY", "max_y", "maxY", "y2"))):
        return node
    for key in ("position_info", "positionInfo", "track_info", "trackInfo",
                "transform", "location", "position", "layout"):
        found = _find_sticker_position(node.get(key), depth + 1)
        if found is not None:
            return found
    for value in node.values():
        found = _find_sticker_position(value, depth + 1)
        if found is not None:
            return found
    return None


def _normalize_coordinate(value, canvas):
    if canvas is not None and canvas > 1 and abs(value) > 1:
        return value / canvas
    if 0 <= value <= 1:
        return value
    if -1 <= value < 0:
        return (value + 1) / 2
    if 0 <= value <= 100:
        return value / 100
    if 0 <= value <= 1000:
        return value / 1000
    return value


def _normalize_size(value, canvas):
    if value is None or value <= 0:
        return 0.0
    if canvas is not None and canvas > 1 and value > 1:
        value /= canvas
    elif 1 < value <= 100:
        value /= 100
    elif 100 < value <= 1000:
        value /= 1000
    return value if 0 < value <= 1 else 0.0


def _sticker_overlay(sticker):
    """精确坐标贴纸。取不到坐标返回 None（由调用方走估算布局）。"""
    if not isinstance(sticker, dict):
        return None
    pos = _find_sticker_position(sticker)
    if pos is None:
        return None

    nan = float("nan")
    x = _num(pos, nan, *_POS_X_KEYS)
    y = _num(pos, nan, *_POS_Y_KEYS)
    left = _num(pos, nan, "left", "left_x", "leftX", "min_x", "minX", "x1")
    right = _num(pos, nan, "right", "right_x", "rightX", "max_x", "maxX", "x2")
    top = _num(pos, nan, "top", "top_y", "topY", "min_y", "minY", "y1")
    bottom = _num(pos, nan, "bottom", "bottom_y", "bottomY", "max_y", "maxY", "y2")
    if x != x and left == left and right == right:
        x = (left + right) / 2
    if y != y and top == top and bottom == bottom:
        y = (top + bottom) / 2
    if x != x or y != y:
        return None

    cw = _num(pos, nan, "canvas_width", "canvasWidth", "canvas_w", "canvasW",
              "base_width", "baseWidth", "image_width", "imageWidth", "screen_width",
              "screenWidth", "video_width", "videoWidth", "material_width", "materialWidth")
    ch = _num(pos, nan, "canvas_height", "canvasHeight", "canvas_h", "canvasH",
              "base_height", "baseHeight", "image_height", "imageHeight", "screen_height",
              "screenHeight", "video_height", "videoHeight", "material_height", "materialHeight")
    x = _normalize_coordinate(x, None if cw != cw else cw)
    y = _normalize_coordinate(y, None if ch != ch else ch)
    if not (0 <= x <= 1 and 0 <= y <= 1):
        return None

    width = _num(pos, nan, "width", "w", "sticker_width", "stickerWidth",
                 "relative_width", "relativeWidth", "start_width", "startWidth",
                 "display_width", "displayWidth", "size_width", "sizeWidth",
                 "rect_width", "rectWidth")
    height = _num(pos, nan, "height", "h", "sticker_height", "stickerHeight",
                  "relative_height", "relativeHeight", "start_height", "startHeight",
                  "display_height", "displayHeight", "size_height", "sizeHeight",
                  "rect_height", "rectHeight")
    if width != width and left == left and right == right:
        width = abs(right - left)
    if height != height and top == top and bottom == bottom:
        height = abs(bottom - top)
    wr = _normalize_size(None if width != width else width, None if cw != cw else cw)
    hr = _normalize_size(None if height != height else height, None if ch != ch else ch)

    rotation = _num(pos, 0.0, "rotation", "rotation_angle", "rotationAngle", "rotate",
                    "angle", "start_rotation", "startRotation", "display_angle",
                    "displayAngle", "rotate_angle", "rotateAngle")
    if 0.001 < abs(rotation) <= 3.14159265 * 2.1:
        import math
        rotation = math.degrees(rotation)

    resource = _find_sticker_resource_url(sticker)
    text = _find_sticker_text(sticker)
    if not resource and not text:
        return None
    return StickerOverlay(resource, text, x, y, wr, hr, rotation)


def _sticker_overlay_or_estimate(sticker, ordinal, total):
    """拿不到精确坐标时给一个「保守但可见」的估算位置。

    宁可位置不准也别丢内容 —— 用户至少知道这里有个贴纸。
    `estimated=True` 让上层能提示这是估算布局。
    """
    precise = _sticker_overlay(sticker)
    if precise is not None:
        return precise
    if not isinstance(sticker, dict):
        return None
    resource = _find_sticker_resource_url(sticker)
    text = _find_sticker_text(sticker)
    if not resource and not text:
        return None
    safe_total = max(1, total)
    if safe_total == 1:
        cy = 0.66
    else:
        cy = 0.48 + 0.36 * max(0, min(ordinal, safe_total - 1)) / max(1.0, safe_total - 1.0)
    return StickerOverlay(resource, text, 0.5, cy,
                          0.78 if resource is None else 0.36, 0.0, 0.0, estimated=True)


def _add_sticker_unique(items, overlay):
    if overlay is None:
        return
    for existing in items:
        if (existing.resource_url == overlay.resource_url
                and existing.text == overlay.text
                and abs(existing.center_x - overlay.center_x) < 0.001
                and abs(existing.center_y - overlay.center_y) < 0.001):
            return
    items.append(overlay)


_STICKER_ARRAY_KEYS = (
    "interaction_stickers", "interactionStickers", "stickers", "sticker_list",
    "stickerList", "text_sticker_list", "textStickerList", "text_stickers",
    "textStickers", "stickers_on_item", "stickersOnItem", "image_stickers",
    "imageStickers", "interaction_sticker_list", "interactionStickerList",
)
_STICKER_OBJECT_KEYS = ("sticker_info", "stickerInfo", "text_sticker", "textSticker",
                        "sticker_data", "stickerData", "interaction_sticker",
                        "interactionSticker")


def _is_sticker_container_key(key):
    low = (key or "").lower()
    if "sticker" not in low:
        return False
    return not any(s in low for s in ("url", "uri", "image", "icon", "resource", "cover"))


def _should_descend_for_stickers(key, value):
    if not isinstance(value, (dict, list, str)):
        return False
    low = (key or "").lower()
    if low in ("images", "image_list", "imagelist"):
        return False
    return any(s in low for s in ("sticker", "post", "item", "content", "data",
                                  "interaction", "extra", "info", "track",
                                  "position", "layout"))


def _extract_sticker_overlays(container):
    if not isinstance(container, dict):
        return []
    result = []
    for key in _STICKER_ARRAY_KEYS:
        array = container.get(key)
        if not isinstance(array, list):
            continue
        for i, sticker in enumerate(array):
            _add_sticker_unique(result, _sticker_overlay_or_estimate(sticker, i, len(array)))
    for key in _STICKER_OBJECT_KEYS:
        _add_sticker_unique(result, _sticker_overlay_or_estimate(container.get(key), 0, 1))
    _append_nested_stickers(container, result, 0)
    return result


def _append_nested_stickers(node, result, depth):
    if node is None or depth > 5:
        return
    if isinstance(node, str):
        decoded = _decode_json_value(node)
        if decoded is not None:
            _append_nested_stickers(decoded, result, depth + 1)
        return
    if isinstance(node, list):
        for item in node:
            _append_nested_stickers(item, result, depth + 1)
        return
    if not isinstance(node, dict):
        return
    for key, value in node.items():
        if _is_sticker_container_key(key):
            decoded = _decode_json_value(value) if isinstance(value, str) else value
            if isinstance(decoded, dict):
                _add_sticker_unique(result, _sticker_overlay_or_estimate(decoded, 0, 1))
            elif isinstance(decoded, list):
                for i, item in enumerate(decoded):
                    _add_sticker_unique(result,
                                        _sticker_overlay_or_estimate(item, i, len(decoded)))
        if _should_descend_for_stickers(key, value):
            _append_nested_stickers(value, result, depth + 1)


def _sticker_image_index(sticker, fallback, image_count):
    if not isinstance(sticker, dict) or image_count <= 0:
        return 0
    index = -1
    for key in ("image_index", "imageIndex", "photo_index", "photoIndex",
                "pic_index", "picIndex", "material_index", "materialIndex"):
        value = _num(sticker, None, key)
        if value is not None:
            index = int(value)
            break
    if index < 0:
        page = _num(sticker, None, "page_index", "pageIndex", "page")
        if page is not None:
            index = int(page) - 1 if page > 0 else 0
    if index < 0:
        pos = _find_sticker_position(sticker)
        if isinstance(pos, dict):
            for key in ("image_index", "imageIndex", "photo_index", "photoIndex",
                        "pic_index", "picIndex", "material_index", "materialIndex"):
                value = _num(pos, None, key)
                if value is not None:
                    index = int(value)
                    break
    if index < 0 or index >= image_count:
        index = max(0, min(fallback, image_count - 1))
    return index


def _append_aweme_stickers(data, image_overlays):
    """作品级贴纸（挂在整个作品上，不在单张图里），按 image_index 归到对应图。"""
    if not image_overlays or not isinstance(data, dict):
        return
    _append_sticker_arrays(data, image_overlays, 0)
    post = _obj(data, "image_post_info", "imagePostInfo")
    if post is not None:
        _append_sticker_arrays(post, image_overlays, 0)


def _append_sticker_arrays(container, image_overlays, fallback_index, depth=0):
    if not isinstance(container, dict) or depth > 5:
        return
    for key in _STICKER_ARRAY_KEYS:
        array = container.get(key)
        if not isinstance(array, list):
            continue
        for i, sticker in enumerate(array):
            overlay = _sticker_overlay_or_estimate(sticker, i, len(array))
            if overlay is None:
                continue
            idx = _sticker_image_index(sticker, fallback_index, len(image_overlays))
            _add_sticker_unique(image_overlays[idx], overlay)
    for key, value in container.items():
        if _is_sticker_container_key(key):
            decoded = _decode_json_value(value) if isinstance(value, str) else value
            for item in ([decoded] if isinstance(decoded, dict)
                         else (decoded if isinstance(decoded, list) else [])):
                idx = _sticker_image_index(item, fallback_index, len(image_overlays))
                _add_sticker_unique(image_overlays[idx],
                                    _sticker_overlay_or_estimate(item, 0, 1))
        if _should_descend_for_stickers(key, value):
            _append_sticker_arrays(value, image_overlays, fallback_index, depth + 1)


def _extract_image_media(data):
    if not isinstance(data, dict):
        return None
    images = data.get("images")
    if not isinstance(images, list) or not images:
        images = data.get("image_list")
    if not isinstance(images, list) or not images:
        post = _obj(data, "image_post_info", "imagePostInfo")
        if post is not None:
            images = _arr(post, "images", "image_list", "imageList")
    if not isinstance(images, list) or not images:
        return None

    image_urls, preview_urls, live_urls = [], [], []
    composite_urls, overlays = [], []
    for image in images:
        if not isinstance(image, dict):
            continue
        # 无水印原图优先 url_list；download_url_list 在当前接口里可能是带水印的
        clean = _arr(image, "url_list", "urlList")
        if clean is None:
            original = _obj(image, "origin_image", "originImage",
                            "original_image", "originalImage")
            if original is not None:
                clean = _arr(original, "url_list", "urlList")
        image_url = _choose_image_url(clean, from_end=True, skip_watermark=True) if clean else None
        if image_url is None:
            direct = normalize_media_url(_str(image, "origin_url", "originUrl", "url"))
            if direct and is_allowed_media_url(direct) and not _looks_watermarked(direct):
                image_url = direct
        if image_url is None:
            download_list = _arr(image, "download_url_list", "downloadUrlList")
            image_url = _choose_image_url(download_list, from_end=True,
                                          skip_watermark=True) if download_list else None

        preview = None
        display = _obj(image, "display_image", "displayImage")
        if display is not None:
            preview_list = _arr(display, "url_list", "urlList")
            if preview_list:
                preview = _choose_image_url([u for u in preview_list if isinstance(u, str)])
        if preview is None and clean:
            preview = _choose_image_url([u for u in clean if isinstance(u, str)])
        if preview is None:
            preview = image_url

        live = _extract_live_video_url(image)
        if image_url is None and live is None:
            continue
        image_urls.append(image_url or "")
        preview_urls.append(preview or image_url or "")
        live_urls.append(live)
        composite_urls.append(_extract_sticker_composite_url(image))
        overlays.append(_extract_sticker_overlays(image))

    _append_aweme_stickers(data, overlays)
    return {
        "image_urls": image_urls,
        "preview_urls": preview_urls,
        "live_video_urls": live_urls,
        "sticker_composite_urls": composite_urls,
        "sticker_overlays": overlays,
    }


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def resolve_video_id(share_text):
    resolved = resolve_share(share_text)
    return resolved[0] if resolved else None


def parse(share_text):
    """解析分享文本，返回 ParseResult。失败抛 ParseError。"""
    share = resolve_share(share_text)
    if share is None:
        raise ParseError("无法识别抖音链接，请粘贴完整的分享内容")
    aweme_id, page_url = share

    result = None
    last_error = None

    referers = []
    for ref in ("https://www.douyin.com/note/" + aweme_id,
                page_url,
                "https://www.douyin.com/video/" + aweme_id):
        if ref not in referers:
            referers.append(ref)
    for referer in referers:
        # 已经拿到图文且配乐也有时，不必再把所有 referer 试一遍
        if result is not None and result.music_url and not result.is_image_post:
            break
        try:
            detail = fetch_aweme_detail(aweme_id, referer)
            parsed = _parse_video_from_json(detail)
            result = _merge_results(result, parsed)
        except Exception as e:
            last_error = e

    candidates = []
    for url in (page_url,
                "https://www.douyin.com/note/" + aweme_id,
                "https://www.iesdouyin.com/share/slides/" + aweme_id +
                "/?schema_type=37&is_slides=1&contains_video_type_clip=1",
                "https://www.iesdouyin.com/share/video/" + aweme_id + "/",
                "https://m.ixigua.com/douyin/share/video/" + aweme_id +
                "?aweme_type=107&schema_type=1&utm_source=copy&utm_campaign=client_share"
                "&utm_medium=android&app=aweme"):
        if url not in candidates:
            candidates.append(url)

    for candidate in candidates:
        if result is not None and result.music_url and not result.is_image_post:
            break
        try:
            html = fetch_html(candidate)
            if html:
                result = _merge_results(result, extract_video_info(html))
        except Exception as e:
            last_error = e

    if result is None:
        detail = str(last_error) if last_error else "分享页没有返回视频数据"
        raise ParseError("解析失败：" + detail)

    result.aweme_id = aweme_id
    return result


def _merge_results(primary, secondary):
    if primary is None:
        return secondary
    if secondary is None:
        return primary
    music_url, music_title = primary.music_url, primary.music_title
    if not music_url and secondary.music_url:
        music_url, music_title = secondary.music_url, secondary.music_title

    composite = primary.sticker_composite_urls
    overlays = primary.sticker_overlays
    primary_has_stickers = any(ol for ol in overlays)
    if not primary_has_stickers and len(primary.image_urls) == len(secondary.image_urls):
        composite = secondary.sticker_composite_urls
        overlays = secondary.sticker_overlays

    merged = ParseResult()
    merged.aweme_id = primary.aweme_id
    merged.video_url = primary.video_url
    merged.title = primary.title
    merged.cover_url = primary.cover_url
    merged.image_urls = primary.image_urls
    merged.image_preview_urls = primary.image_preview_urls
    merged.live_video_urls = primary.live_video_urls
    merged.sticker_composite_urls = composite
    merged.sticker_overlays = overlays
    merged.music_url = music_url
    merged.music_title = music_title
    return merged


# ---------------------------------------------------------------------------
# 媒体下载
# ---------------------------------------------------------------------------
def download_to_file(media_url, dest_path, media_kind, extra_domains=None,
                     referer="https://www.douyin.com/", progress=None,
                     max_bytes=0, trusted=False):
    """下载媒体到 dest_path（先写 .part 再原子改名，避免留下半截文件）。

    ``media_kind``: 0=图片 1=视频 2=音频，只用于选 Accept 与校验返回类型。

    ``trusted``: 地址是**用户自己粘进来的**（直链下载）时置真，跳过域名白名单。
    白名单防的是「第三方页面的内容决定我们去请求哪儿」，用户主动给的地址不属于
    这个威胁模型；但 http/https 这个最低要求仍然保留，不允许 file:// 之类。

    返回写入字节数。
    """
    if media_kind == 0:
        accept = "image/*,application/octet-stream;q=0.8"
    elif media_kind == 2:
        accept = "audio/*,video/mp4,application/octet-stream;q=0.8"
    else:
        accept = "video/*,application/octet-stream;q=0.8"

    part_path = dest_path + ".part"
    written = 0
    current = media_url
    try:
        for _ in range(MAX_REDIRECTS + 1):
            if trusted:
                if not re.match(r"https?://", current or "", re.IGNORECASE):
                    raise ParseError("只支持 http/https 地址")
            elif not is_allowed_media_url(current) and not (
                    extra_domains and is_allowed_https(current, extra_domains)):
                raise ParseError("媒体地址不在允许列表中")

            headers = {
                "User-Agent": UA_MOBILE,
                "Accept": accept,
                "Referer": referer,
                "Accept-Encoding": "identity",
            }
            class _NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *a, **kw):
                    return None

            opener = urllib.request.build_opener(_NoRedirect)
            try:
                resp = opener.open(urllib.request.Request(current, headers=headers), timeout=60)
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 303, 307, 308):
                    location = e.headers.get("Location") if e.headers else None
                    if not location:
                        raise ParseError("下载重定向缺少目标地址")
                    current = urllib.parse.urljoin(current, location)
                    continue
                raise ParseError(f"下载失败，响应码: {e.code}")

            with resp:
                ctype = (resp.headers.get("Content-Type") or "").lower()
                if ctype and not any(ctype.startswith(p) for p in (
                        "image/", "audio/", "video/", "application/octet-stream",
                        "binary/octet-stream")):
                    if media_kind == 0:
                        raise ParseError("服务器返回的不是图片文件")
                    if media_kind == 2:
                        raise ParseError("服务器返回的不是音频文件")
                    raise ParseError("服务器返回的不是视频文件")

                total = int(resp.headers.get("Content-Length") or 0)
                if max_bytes and total > max_bytes:
                    raise ParseError(f"文件超过 {max_bytes // 1024 // 1024} MB 限制")

                with open(part_path, "wb") as f:
                    last_percent = -1
                    while True:
                        chunk = resp.read(64 * 1024)
                        if not chunk:
                            break
                        written += len(chunk)
                        if max_bytes and written > max_bytes:
                            raise ParseError(f"文件超过 {max_bytes // 1024 // 1024} MB 限制")
                        f.write(chunk)
                        if progress and total > 0:
                            percent = min(100, written * 100 // total)
                            if percent != last_percent:
                                progress(percent)
                                last_percent = percent
            import os
            os.replace(part_path, dest_path)
            return written
        raise ParseError("下载重定向次数过多")
    except Exception:
        import os
        try:
            os.remove(part_path)
        except OSError:
            pass
        raise


if __name__ == "__main__":
    # 自检：不联网，只验纯逻辑。重点在**域名白名单** ——
    # 解析出来的地址来自第三方页面，白名单一旦失效就等于让页面内容决定我们请求哪儿。
    assert extract_video_id("7300000000000000001") == "7300000000000000001"
    assert extract_video_id("https://www.douyin.com/video/7300000000000000001") == "7300000000000000001"
    assert extract_video_id("https://www.douyin.com/?modal_id=7300000000000000001") == "7300000000000000001"
    assert extract_video_id("https://www.douyin.com/video/123") is None
    assert extract_video_id("没有链接") is None

    assert is_allowed_media_url("https://v3-web.douyinvod.com/x.mp4")
    assert is_allowed_media_url("https://a.b.douyinpic.com/x.jpg")
    assert not is_allowed_media_url("http://v3-web.douyinvod.com/x.mp4")
    assert not is_allowed_media_url("https://evil.example.com/x.mp4")
    # 域名后缀伪装：notdouyin.com.evil.com 的 host 并不以 .douyin.com 结尾
    assert not is_allowed_media_url("https://notdouyin.com.evil.com/x.mp4")

    assert normalize_play_url("https://x/playwm/a.mp4") == "https://x/play/a.mp4"
    assert normalize_media_url("http://x/a.jpg") == "https://x/a.jpg"
    assert normalize_media_url("https://x/a?b=1&amp;c=2") == "https://x/a?b=1&c=2"

    # 码率列表要取最高码率，不是第一个
    assert get_play_url({"bit_rate": [
        {"bit_rate": 500, "play_addr": {"url_list": ["https://v.douyinvod.com/low.mp4"]}},
        {"bit_rate": 9000, "play_addr": {"url_list": ["https://v.douyinvod.com/high.mp4"]}},
    ]}) == "https://v.douyinvod.com/high.mp4"
    assert get_play_url({}) is None

    # 四种嵌入格式里最常用的一种；顺带验坏 JSON 不会抛异常
    import json as _json
    html = ('<script>window._ROUTER_DATA = ' + _json.dumps({"aweme_detail": {
        "desc": "标题A",
        "video": {"play_addr": {"url_list": ["https://v.douyinvod.com/real.mp4"]},
                  "origin_cover": {"url_list": ["https://p.douyinpic.com/c.jpg"]}},
    }}, ensure_ascii=False) + "</script>")
    result = extract_video_info(html)
    assert result and result.video_url == "https://v.douyinvod.com/real.mp4"
    assert result.title == "标题A"
    assert result.cover_url == "https://p.douyinpic.com/c.jpg"
    assert extract_video_info('<script>window._ROUTER_DATA = {坏 json}</script>') is None
    assert extract_video_info("") is None

    print("dy_parse 自检通过")
