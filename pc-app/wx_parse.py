#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""微信视频号解析（``ChannelCredential.java`` + ``WeixinChannelsParser.java`` 的移植）。

视频号没有公开网页端，视频地址只在登录态下下发。完整链路四步：

1. 分享短链 ``weixin.qq.com/sph/<ID>`` → 取 ``<ID>``（必要时要跟 301）
2. 视频号预览接口（匿名）→ 作者/文案/互动数/封面 + ``dynamicExportId``
3. **元宝接口（需要用户的登录态）** → 用 ``dynamicExportId`` 换带回 token 与 eid 的 h5_url
4. 视频号预览接口（带 token+eid）→ ``feedInfo.videoUrl``，即明文 MP4 直链

第 3 步之所以绕元宝：视频号自己的接口对匿名请求只给封面，必须借一个已登录的租户
换临时令牌。实测该接口**不校验** X-Uskey 等签名头，有 Cookie 即可，所以这里只回放
用户贴进来的请求头。

**与手机端的差异**：不移植 ``SimpleHttps``。Java 的 ``HttpURLConnection`` 会静默
丢弃受限的 ``Origin`` 头，预览接口缺了它直接返回「permission verification failed」，
所以手机端不得不自带一套 HTTP 栈。Python 的 ``urllib`` 没有这个限制。
"""
import json
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request

MEDIA_DOMAINS = ("finder.video.qq.com", "video.qq.com")
MEDIA_REFERER = "https://channels.weixin.qq.com/"

_PREVIEW_API = "https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info"
_PREVIEW_PAGE = "https://channels.weixin.qq.com/finder-preview/pages/sph"
_YUANBAO_FEED_API = "https://yuanbao.tencent.com/api/getopenfeedinfo"

_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")

CONNECT_TIMEOUT = 15
READ_TIMEOUT = 20
MAX_HTML_BYTES = 512 * 1024

_SPH_URL = re.compile(r"https?://weixin\.qq\.com/sph/([A-Za-z0-9_\-]+)", re.IGNORECASE)
_SPH_ID_QUERY = re.compile(r"[?&]id=([A-Za-z0-9_\-]{4,64})", re.IGNORECASE)
_ANY_WEIXIN_URL = re.compile(
    r"https?://[A-Za-z0-9.\-]*(?:weixin\.qq\.com|channels\.weixin\.qq\.com)/[^\s\"'<>]*",
    re.IGNORECASE)

# 校验用探针：任意一个可公开访问的视频号作品的 dynamicExportId 形式，
# 用来判断用户贴的登录态到底还能不能用。
VERIFY_PROBE_EXPORT_ID = "export/UzFfBgAAxM6HQFIGWkOfjszT4DCsIpbtLwUsGcdQ_cf_ERF9uQt1CouS6A"


class ParseError(Exception):
    pass


# ---------------------------------------------------------------------------
# 链接识别
# ---------------------------------------------------------------------------
def is_channels_share(text):
    if not text:
        return False
    if _SPH_URL.search(text):
        return True
    low = text.lower()
    return "channels.weixin.qq.com/" in low or "weixin.qq.com/sph" in low


def extract_share_url(text):
    if not text:
        return None
    m = _ANY_WEIXIN_URL.search(text)
    return m.group(0) if m else None


def extract_share_id(text):
    if not text:
        return None
    trimmed = text.strip()
    if re.fullmatch(r"[A-Za-z0-9_\-]{6,32}", trimmed):
        return trimmed
    m = _SPH_URL.search(text)
    if m:
        return m.group(1)
    m = _SPH_ID_QUERY.search(text)
    if m:
        return m.group(1)
    return None


def resolve_share_id(share_text):
    direct = extract_share_id(share_text)
    if direct:
        return direct
    url = extract_share_url(share_text)
    if not url:
        return None
    for _ in range(5):
        try:
            class _NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *a, **kw):
                    return None

            opener = urllib.request.build_opener(_NoRedirect)
            resp = opener.open(urllib.request.Request(
                url, headers={"User-Agent": _UA}), timeout=CONNECT_TIMEOUT)
            with resp:
                sid = extract_share_id(url)
                if sid:
                    return sid
                body = resp.read(MAX_HTML_BYTES).decode("utf-8", "replace")
                return extract_share_id(body)
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                location = e.headers.get("Location") if e.headers else None
                if not location:
                    return None
                url = urllib.parse.urljoin(url, location)
                sid = extract_share_id(url)
                if sid:
                    return sid
                continue
            # 非跳转错误（如 403）仍尝试从 URL 里取 ID
            return extract_share_id(url)
        except Exception:
            return extract_share_id(url)
    return None


# ---------------------------------------------------------------------------
# 凭据（用户从浏览器复制的一次请求）
# ---------------------------------------------------------------------------
_NOISE_HEADERS = {
    "host", "connection", "content-length", "content-type", "accept-encoding",
    "priority", "accept", "accept-language", "origin", "referer", "user-agent",
    "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
    "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "cookie2",
}

_CURL_HEADER = re.compile(r"-H\s+'([^:']+):\s*([^']*)'")
_CURL_HEADER_DQ = re.compile(r'-H\s+"([^":]+):\s*([^"]*)"')
_CURL_COOKIE = re.compile(r"(?:^|\s)(?:-b|--cookie)\s+'([^']*)'")
_CURL_COOKIE_DQ = re.compile(r'(?:^|\s)(?:-b|--cookie)\s+"([^"]*)"')
_COOKIE_LINE = re.compile(r"^\s*cookie\s*:\s*(.+)$", re.IGNORECASE | re.MULTILINE)


def parse_credential(raw_input):
    """把用户粘贴的内容归一化成请求头表。

    支持三种形态：① 浏览器「复制为 cURL」整段命令（最常见、信息最全）
    ② `Cookie: xxx` 一行 ③ 直接就是 `k=v; k=v` 的 cookie 值。
    """
    headers = {}
    if not raw_input:
        return headers
    raw = raw_input.strip()

    for pattern in (_CURL_HEADER, _CURL_HEADER_DQ):
        for name, value in pattern.findall(raw):
            _put(headers, name, value)
    if not headers:
        for line in raw.splitlines():
            m = re.match(r"^\s*-H\s+([A-Za-z0-9_\-]+):\s*(.+?)\s*\\?$", line)
            if m:
                _put(headers, m.group(1), _strip_quotes(m.group(2)))

    if "cookie" not in headers:
        ck = _first_group(_CURL_COOKIE, raw) or _first_group(_CURL_COOKIE_DQ, raw)
        if ck:
            _put(headers, "cookie", ck)

    if "cookie" not in headers:
        m = _COOKIE_LINE.search(raw)
        if m:
            _put(headers, "cookie", m.group(1))

    if "cookie" not in headers and "=" in raw and "\n" not in raw and len(raw) > 20:
        _put(headers, "cookie", raw)

    for noise in _NOISE_HEADERS:
        headers.pop(noise, None)
    return headers


def _put(headers, name, value):
    headers[(name or "").strip().lower()] = (value or "").strip()


def _strip_quotes(text):
    t = (text or "").strip()
    if len(t) >= 2 and t[0] == t[-1] and t[0] in "\"'":
        return t[1:-1]
    return t


def _first_group(pattern, text):
    m = pattern.search(text)
    return m.group(1) if m else None


def credential_usable(headers):
    cookie = (headers or {}).get("cookie") or ""
    return "hy_user=" in cookie and "hy_token=" in cookie


def diagnose_credential(headers):
    """给用户看的诊断：缺什么就说什么。返回 None 表示没问题。"""
    cookie = (headers or {}).get("cookie") or ""
    if not cookie:
        return "没找到 Cookie。请点「怎么获取」按步骤复制一次完整请求。"
    has_user = "hy_user=" in cookie
    has_token = "hy_token=" in cookie
    if not has_user or not has_token:
        missing = "hy_user" if not has_user else "hy_token"
        return (f"Cookie 不完整（缺少 {missing}）。请确认是登录元宝后、"
                "从 yuanbao.tencent.com 的 api 请求上复制的。")
    return None


# ---------------------------------------------------------------------------
# 解析
# ---------------------------------------------------------------------------
class ChannelsResult:
    def __init__(self):
        self.share_id = None
        self.dynamic_export_id = None
        self.export_id = None
        self.author = ""
        self.author_icon = ""
        self.description = ""
        self.cover_url = ""
        self.like_count = ""
        self.fav_count = ""
        self.forward_count = ""
        self.comment_count = ""
        self.create_time = 0
        self.video_url = ""
        self.pic_urls = []

    def has_video(self):
        return bool(self.video_url)

    def to_dict(self):
        return {
            "share_id": self.share_id,
            "author": self.author,
            "author_icon": self.author_icon,
            "description": self.description,
            "cover_url": self.cover_url,
            "like_count": self.like_count,
            "fav_count": self.fav_count,
            "forward_count": self.forward_count,
            "comment_count": self.comment_count,
            "create_time": self.create_time,
            "video_url": self.video_url,
            "pic_urls": self.pic_urls,
            "has_video": self.has_video(),
        }


def _post_json(url, body, headers, timeout=READ_TIMEOUT):
    data = body.encode("utf-8") if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(MAX_HTML_BYTES).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read(MAX_HTML_BYTES).decode("utf-8", "replace")


def _fetch_preview_info(body):
    """调视频号预览接口。正常返回 200 或 201，两者都接受。

    这个接口要求**同时**带 Origin 与 Referer，缺一个就返回
    `permission verification failed` —— 这也正是手机端不能用
    HttpURLConnection、必须自带 SimpleHttps 的原因。
    """
    rid = "%08x-%08x" % (random.getrandbits(32),
                         int(time.time() * 1000) & 0xFFFFFFFF)
    url = (_PREVIEW_API + "?_rid=" + rid
           + "&_pageUrl=" + urllib.parse.quote(_PREVIEW_PAGE, safe=""))
    status, text = _post_json(url, body, {
        "Content-Type": "application/json",
        "Origin": "https://channels.weixin.qq.com",
        "Referer": _PREVIEW_PAGE,
        "Accept": "application/json, text/plain, */*",
        "User-Agent": _UA,
    })
    if status not in (200, 201):
        raise ParseError(f"视频号接口返回 {status}"
                         + (f"：{_brief(text, 160)}" if text else ""))
    try:
        return json.loads(text)
    except Exception:
        raise ParseError("视频号接口返回的不是 JSON")


def _brief(text, limit=160):
    t = re.sub(r"\s+", " ", text or "").strip()
    return t[:limit] + "…" if len(t) > limit else t


def exchange_export_id_for_h5_url(dynamic_export_id, credential):
    """元宝接口：exportId → h5_url（query 里带 token 与 eid）。"""
    payload = json.dumps({"exportIds": [dynamic_export_id]}, ensure_ascii=False)
    headers = {}
    for key, value in (credential or {}).items():
        # 这两个由我们自己控制，回放浏览器里那份会把鉴权搅乱
        if key in ("content-length", "content-type"):
            continue
        headers[key] = value
    headers["Content-Type"] = "application/json"
    headers["Origin"] = "https://yuanbao.tencent.com"
    headers["Referer"] = "https://yuanbao.tencent.com/chat/naQivTmsDa"
    headers.setdefault("User-Agent", _UA)

    status, text = _post_json(_YUANBAO_FEED_API, payload, headers)
    if status != 200:
        raise ParseError(f"登录态校验失败（接口返回 {status}），请重新粘贴一次浏览器里的请求")
    try:
        data = json.loads(text)
    except Exception:
        raise ParseError("登录态接口返回的不是 JSON")
    if int(data.get("code", -1)) != 0:
        message = data.get("message") or ""
        raise ParseError("登录态校验失败"
                         + (f"（{message}）" if message else "") + "，请重新粘贴一次")
    feed = ((data.get("data") or {}).get("feed")) or []
    if not feed:
        raise ParseError("登录态可能已过期，换不到作品信息")
    h5_url = feed[0].get("h5_url") or ""
    if not h5_url:
        raise ParseError("登录态返回的内容不完整")
    return h5_url


def _query_param(url, name):
    if not url:
        return None
    m = re.search(r"[?&]" + re.escape(name) + r"=([^&]*)", url)
    if not m:
        return None
    return urllib.parse.unquote(m.group(1))


def verify_credential(credential):
    """校验一份凭据是否真能用，返回 None 表示可用，否则返回原因。

    用已知公开作品走完整链路，避免用户以为存好了其实早就失效了。
    """
    diagnose = diagnose_credential(credential)
    if diagnose:
        return diagnose
    try:
        h5 = exchange_export_id_for_h5_url(VERIFY_PROBE_EXPORT_ID, credential)
        if not _query_param(h5, "token"):
            return "登录态返回的内容不完整，请重新复制一次"
        return None
    except Exception as e:
        return str(e)


def _apply_feed_info(result, feed_info):
    """把 feedInfo 读进结果；多次调用时后到的非空值覆盖。"""
    if not isinstance(feed_info, dict):
        return
    for src, attr in (("description", "description"), ("coverUrl", "cover_url"),
                      ("likeCountFmt", "like_count"), ("favCountFmt", "fav_count"),
                      ("forwardCountFmt", "forward_count"), ("commentCountFmt", "comment_count")):
        value = feed_info.get(src)
        if value:
            setattr(result, attr, value)
    created = feed_info.get("createtime") or 0
    if isinstance(created, (int, float)) and created > 0:
        result.create_time = int(created)

    video = feed_info.get("videoUrl") or ""
    if not video:
        h264 = feed_info.get("h264VideoInfo")
        if isinstance(h264, dict):
            video = h264.get("videoUrl") or ""
    if not video:
        h265 = feed_info.get("h265VideoInfo")
        if isinstance(h265, dict):
            video = h265.get("videoUrl") or ""
    if video:
        result.video_url = video

    for pic in (feed_info.get("picInfo") or []):
        if not isinstance(pic, dict):
            continue
        url = pic.get("url") or pic.get("picUrl") or ""
        if url and url not in result.pic_urls:
            result.pic_urls.append(url)


def parse(share_text, credential=None):
    """解析视频号分享内容。credential 为空时第 3 步必然失败，会给出明确提示。"""
    share_id = resolve_share_id(share_text)
    if not share_id:
        raise ParseError("没认出视频号链接，请粘贴完整的分享内容")

    result = ChannelsResult()
    result.share_id = share_id

    preview = _fetch_preview_info(
        '{"baseReq":{"generalToken":""},"shortUri":"' + _json_escape(share_id) + '"}')
    data = preview.get("data")
    if not isinstance(data, dict):
        raise ParseError("视频号接口没有返回数据")
    err = data.get("errMsg")
    if isinstance(err, dict) and int(err.get("type", 0) or 0) != 0:
        title = err.get("title") or ""
        raise ParseError(title or "视频号返回了错误状态")

    author = data.get("authorInfo")
    if isinstance(author, dict):
        result.author = author.get("nickname") or ""
        result.author_icon = author.get("headImgUrl") or ""
    _apply_feed_info(result, data.get("feedInfo"))
    scene = data.get("sceneInfo")
    if isinstance(scene, dict):
        result.dynamic_export_id = scene.get("dynamicExportId") or ""
    if not result.dynamic_export_id:
        raise ParseError("拿不到作品的 exportId，链接可能已过期")

    if not credential:
        raise ParseError("视频号视频需要登录态，请先在「登录态」里粘贴一次浏览器中的请求")

    h5_url = exchange_export_id_for_h5_url(result.dynamic_export_id, credential)
    token = _query_param(h5_url, "token")
    eid = _query_param(h5_url, "eid")
    if not token or not eid:
        raise ParseError("登录态已失效，请重新粘贴一次浏览器里的请求")
    result.export_id = eid

    detail = _fetch_preview_info(
        '{"baseReq":{"generalToken":"' + _json_escape(token) + '"},'
        '"exportId":"' + _json_escape(eid) + '"}')
    detail_data = detail.get("data")
    if not isinstance(detail_data, dict):
        raise ParseError("视频号没有返回作品详情")
    _apply_feed_info(result, detail_data.get("feedInfo"))

    if not result.has_video() and not result.pic_urls:
        raise ParseError("这个作品没有可保存的视频或图片")
    return result


def _json_escape(raw):
    if not raw:
        return ""
    return (raw.replace("\\", "\\\\").replace('"', '\\"')
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
