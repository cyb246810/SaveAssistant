package com.zgtools.videosaver;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信视频号（sph）解析。
 *
 * <p>视频号没有公开网页端，视频地址只在登录态下下发。完整链路为四步：
 * <ol>
 *   <li>分享短链 {@code weixin.qq.com/sph/<ID>} → 取出 &lt;ID&gt;（必要时要跟着 301 跳一跳）</li>
 *   <li>视频号预览接口（匿名，无需登录）→ 拿作者/文案/互动数/封面 + {@code dynamicExportId}</li>
 *   <li>元宝接口（<b>需要用户登录态</b>）→ 用 {@code dynamicExportId} 换回带 token 与 eid 的 h5_url</li>
 *   <li>视频号预览接口（用 token+eid）→ 拿到 {@code feedInfo.videoUrl}，即明文 MP4 直链</li>
 * </ol>
 *
 * <p>第 3 步之所以绕元宝，是因为视频号自己那套接口对匿名请求只给封面，
 * 必须借一个已登录的租户换取临时令牌。实测该接口<b>不校验</b> X-Uskey 等签名头，
 * 有 Cookie 即可，因此这里只回放用户贴进来的请求头。
 *
 * <p>视频号没有独立的音乐资源（音频就是视频本身的音轨），所以本类不提供音乐字段。
 */
final class WeixinChannelsParser {

    private static final String TAG = "WeixinChannels";

    /** 视频号视频/封面所在 CDN，用于下载白名单。 */
    static final String[] MEDIA_DOMAINS = {"finder.video.qq.com", "video.qq.com"};
    static final String MEDIA_REFERER = "https://channels.weixin.qq.com/";

    private static final String PREVIEW_API =
            "https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info";
    private static final String PREVIEW_PAGE =
            "https://channels.weixin.qq.com/finder-preview/pages/sph";
    private static final String YUANBAO_FEED_API =
            "https://yuanbao.tencent.com/api/getopenfeedinfo";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final int MAX_HTML_BYTES = 512 * 1024;

    /** 实测该 UA 在预览接口与元宝接口上都能通。 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private static final Pattern SPH_URL = Pattern.compile(
            "https?://weixin\\.qq\\.com/sph/([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPH_ID_QUERY = Pattern.compile(
            "[?&]id=([A-Za-z0-9_\\-]{4,64})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_WEIXIN_URL = Pattern.compile(
            "https?://[A-Za-z0-9.\\-]*(?:weixin\\.qq\\.com|channels\\.weixin\\.qq\\.com)/[^\\s\"'<>]*",
            Pattern.CASE_INSENSITIVE);

    private WeixinChannelsParser() {}

    public interface ParseCallback {
        void onSuccess(ParseResult result);
        void onError(String message);
    }

    public static final class ParseResult {
        public String shareId;
        public String dynamicExportId;
        public String exportId;
        public String author;
        public String authorIcon;
        public String description;
        public String coverUrl;
        public String likeCount;
        public String favCount;
        public String forwardCount;
        public String commentCount;
        public long createTime;
        /** 明文 MP4 直链，可直接下载落盘。 */
        public String videoUrl;
        public final List<String> picUrls = new ArrayList<>();

        public boolean hasVideo() {
            return !TextUtils.isEmpty(videoUrl);
        }
    }

    // ---------------------------------------------------------------- 链接识别

    /** 是否是视频号分享内容（短链或落地页都算）。 */
    static boolean isChannelsShare(String text) {
        if (TextUtils.isEmpty(text)) return false;
        if (SPH_URL.matcher(text).find()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("channels.weixin.qq.com/") || lower.contains("weixin.qq.com/sph");
    }

    static String extractShareUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = ANY_WEIXIN_URL.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    /** 从分享文本里取出 sph ID；取不到返回 null（需要跟跳转）。 */
    static String extractShareId(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String trimmed = text.trim();
        if (trimmed.matches("[A-Za-z0-9_\\-]{6,32}")) return trimmed;
        Matcher m = SPH_URL.matcher(text);
        if (m.find()) return m.group(1);
        Matcher q = SPH_ID_QUERY.matcher(text);
        if (q.find()) return q.group(1);
        return null;
    }

    // ---------------------------------------------------------------- 主流程

    /** 异步解析。credential 可为空——为空时第 3 步必然失败，会给出明确提示。 */
    static void parse(final String shareText, final Map<String, String> credential,
                      final ParseCallback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ParseResult result = parseSync(shareText, credential);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(result);
                        }
                    });
                } catch (final Exception e) {
                    final String message = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(message);
                        }
                    });
                }
            }
        }, "sph-parse").start();
    }

    private static ParseResult parseSync(String shareText, Map<String, String> credential)
            throws Exception {
        String shareId = resolveShareId(shareText);
        if (TextUtils.isEmpty(shareId)) {
            throw new Exception("没认出视频号链接，请粘贴完整的分享内容");
        }

        ParseResult result = new ParseResult();
        result.shareId = shareId;

        // ② 匿名预览接口：拿元数据 + dynamicExportId
        JSONObject preview = fetchPreviewInfo(
                "{\"baseReq\":{\"generalToken\":\"\"},\"shortUri\":\"" + jsonEscape(shareId) + "\"}");

        JSONObject data = preview.optJSONObject("data");
        if (data == null) throw new Exception("视频号接口没有返回数据");
        JSONObject errMsg = data.optJSONObject("errMsg");
        if (errMsg != null && errMsg.optInt("type", 0) != 0) {
            String title = errMsg.optString("title", "");
            throw new Exception(TextUtils.isEmpty(title) ? "视频号返回了错误状态" : title);
        }

        JSONObject authorInfo = data.optJSONObject("authorInfo");
        if (authorInfo != null) {
            result.author = authorInfo.optString("nickname", "");
            result.authorIcon = authorInfo.optString("headImgUrl", "");
        }
        JSONObject feedInfo = data.optJSONObject("feedInfo");
        applyFeedInfo(result, feedInfo);
        JSONObject sceneInfo = data.optJSONObject("sceneInfo");
        if (sceneInfo != null) {
            result.dynamicExportId = sceneInfo.optString("dynamicExportId", "");
        }
        if (TextUtils.isEmpty(result.dynamicExportId)) {
            throw new Exception("拿不到作品的 exportId，链接可能已过期");
        }

        // ③ 元宝接口：换回带 token/eid 的 h5_url
        if (credential == null || credential.isEmpty()) {
            throw new Exception("视频号视频需要登录态，请先按「使用说明」粘贴一次浏览器里的请求");
        }
        String h5Url = exchangeExportIdForH5Url(result.dynamicExportId, credential);
        String token = queryParam(h5Url, "token");
        String eid = queryParam(h5Url, "eid");
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(eid)) {
            throw new Exception("登录态已失效，请重新粘贴一次浏览器里的请求");
        }
        result.exportId = eid;

        // ④ 用 token+eid 换真实视频地址
        JSONObject detail = fetchPreviewInfo(
                "{\"baseReq\":{\"generalToken\":\"" + jsonEscape(token) + "\"},"
                        + "\"exportId\":\"" + jsonEscape(eid) + "\"}");
        JSONObject detailData = detail.optJSONObject("data");
        if (detailData == null) throw new Exception("视频号没有返回作品详情");
        applyFeedInfo(result, detailData.optJSONObject("feedInfo"));

        if (!result.hasVideo() && result.picUrls.isEmpty()) {
            throw new Exception("这个作品没有可保存的视频或图片");
        }
        return result;
    }

    /** 把 feedInfo 里的字段读进结果对象；多次调用时后到的非空值覆盖。 */
    private static void applyFeedInfo(ParseResult result, JSONObject feedInfo) {
        if (feedInfo == null) return;
        String description = feedInfo.optString("description", "");
        if (!TextUtils.isEmpty(description)) result.description = description;
        String cover = feedInfo.optString("coverUrl", "");
        if (!TextUtils.isEmpty(cover)) result.coverUrl = cover;
        String like = feedInfo.optString("likeCountFmt", "");
        if (!TextUtils.isEmpty(like)) result.likeCount = like;
        String fav = feedInfo.optString("favCountFmt", "");
        if (!TextUtils.isEmpty(fav)) result.favCount = fav;
        String forward = feedInfo.optString("forwardCountFmt", "");
        if (!TextUtils.isEmpty(forward)) result.forwardCount = forward;
        String comment = feedInfo.optString("commentCountFmt", "");
        if (!TextUtils.isEmpty(comment)) result.commentCount = comment;
        long created = feedInfo.optLong("createtime", 0L);
        if (created > 0) result.createTime = created;

        String video = feedInfo.optString("videoUrl", "");
        if (TextUtils.isEmpty(video)) {
            JSONObject h264 = feedInfo.optJSONObject("h264VideoInfo");
            if (h264 != null) video = h264.optString("videoUrl", "");
        }
        if (TextUtils.isEmpty(video)) {
            JSONObject h265 = feedInfo.optJSONObject("h265VideoInfo");
            if (h265 != null) video = h265.optString("videoUrl", "");
        }
        if (!TextUtils.isEmpty(video)) result.videoUrl = video;

        JSONArray pics = feedInfo.optJSONArray("picInfo");
        if (pics != null) {
            for (int i = 0; i < pics.length(); i++) {
                JSONObject pic = pics.optJSONObject(i);
                if (pic == null) continue;
                String url = pic.optString("url", "");
                if (TextUtils.isEmpty(url)) url = pic.optString("picUrl", "");
                if (!TextUtils.isEmpty(url) && !result.picUrls.contains(url)) {
                    result.picUrls.add(url);
                }
            }
        }
    }

    /** 分享短链可能只是跳转壳，跟着 301 走到落地页再取 ID。 */
    private static String resolveShareId(String shareText) throws Exception {
        String direct = extractShareId(shareText);
        if (!TextUtils.isEmpty(direct)) return direct;

        String url = extractShareUrl(shareText);
        if (TextUtils.isEmpty(url)) return null;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (TextUtils.isEmpty(location)) return null;
                    url = new URL(new URL(url), location).toString();
                    String id = extractShareId(url);
                    if (!TextUtils.isEmpty(id)) return id;
                    continue;
                }
                String body = readLimited(conn, MAX_HTML_BYTES);
                return extractShareId(body);
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * 调视频号预览接口。注意该接口正常返回 200/201，两者都接受。
     *
     * <p>这个接口要求同时带 {@code Origin} 与 {@code Referer}，缺一个就返回
     * {@code permission verification failed}。所以必须走 {@link SimpleHttps}
     * 自己写请求头，不能用会丢弃 Origin 的 HttpURLConnection。
     */
    private static JSONObject fetchPreviewInfo(String body) throws Exception {
        String rid = String.format(Locale.ROOT, "%08x-%08x",
                new Random().nextInt(), System.currentTimeMillis() & 0xffffffffL);
        String url = PREVIEW_API + "?_rid=" + rid
                + "&_pageUrl=" + URLEncoder.encode(PREVIEW_PAGE, "UTF-8");
        SimpleHttps.Response response = SimpleHttps.post(url, body, new String[][]{
                {"Content-Type", "application/json"},
                {"Origin", "https://channels.weixin.qq.com"},
                {"Referer", PREVIEW_PAGE},
                {"Accept", "application/json, text/plain, */*"}
        });
        if (response.status != 200 && response.status != 201) {
            throw new Exception("视频号接口返回 " + response.status
                    + (response.body.isEmpty()
                    ? "" : "：" + SimpleHttps.brief(response.body, 160)));
        }
        return new JSONObject(response.body);
    }

    /** 元宝接口：exportId -> h5_url。h5_url 的 query 里带着 token 与 eid。 */
    private static String exchangeExportIdForH5Url(String dynamicExportId,
                                                   Map<String, String> credential)
            throws Exception {
        JSONObject payload = new JSONObject();
        JSONArray ids = new JSONArray();
        ids.put(dynamicExportId);
        payload.put("exportIds", ids);

        List<String[]> headers = new ArrayList<>();
        for (Map.Entry<String, String> e : credential.entrySet()) {
            String key = e.getKey();
            // 这两个由我们自己控制，避免回放浏览器里那份把鉴权搅乱
            if ("content-length".equals(key) || "content-type".equals(key)) continue;
            headers.add(new String[]{key, e.getValue()});
        }
        headers.add(new String[]{"Content-Type", "application/json"});
        headers.add(new String[]{"Origin", "https://yuanbao.tencent.com"});
        headers.add(new String[]{"Referer", "https://yuanbao.tencent.com/chat/naQivTmsDa"});

        SimpleHttps.Response response = SimpleHttps.post(
                YUANBAO_FEED_API, payload.toString(), headers.toArray(new String[0][]));
        if (response.status != 200) {
            throw new Exception("登录态校验失败（接口返回 " + response.status
                    + "），请重新粘贴一次浏览器里的请求");
        }
        JSONObject json = new JSONObject(response.body);
        if (json.optInt("code", -1) != 0) {
            String message = json.optString("message", "");
            throw new Exception("登录态校验失败" + (TextUtils.isEmpty(message)
                    ? "" : "（" + message + "）") + "，请重新粘贴一次");
        }
        JSONObject data = json.optJSONObject("data");
        JSONArray feed = data == null ? null : data.optJSONArray("feed");
        if (feed == null || feed.length() == 0) {
            throw new Exception("登录态可能已过期，换不到作品信息");
        }
        JSONObject first = feed.optJSONObject(0);
        String h5Url = first == null ? "" : first.optString("h5_url", "");
        if (TextUtils.isEmpty(h5Url)) throw new Exception("登录态返回的内容不完整");
        return h5Url;
    }

    /**
     * 校验一份凭据是否真的能用。用于「保存后立即验证」，避免用户以为存好了其实已失效。
     * 用一个已知公开作品走完整链路，任一步失败都返回失败原因。
     */
    static String verifyCredential(Map<String, String> credential) {
        String diagnose = ChannelCredential.diagnose(credential);
        if (diagnose != null) return diagnose;
        try {
            String h5 = exchangeExportIdForH5Url(VERIFY_PROBE_EXPORT_ID, credential);
            if (TextUtils.isEmpty(queryParam(h5, "token"))) {
                return "登录态返回的内容不完整，请重新复制一次";
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 校验用探针：任意一个可公开访问的视频号作品的 dynamicExportId 形式。 */
    private static final String VERIFY_PROBE_EXPORT_ID =
            "export/UzFfBgAAxM6HQFIGWkOfjszT4DCsIpbtLwUsGcdQ_cf_ERF9uQt1CouS6A";

    // ---------------------------------------------------------------- HTTP 工具

    private static String readLimited(HttpURLConnection conn, int maxBytes) throws Exception {
        try (InputStream input = conn.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > maxBytes) break;
            }
            return output.toString("UTF-8");
        }
    }

    private static String brief(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String queryParam(String url, String name) {
        if (TextUtils.isEmpty(url)) return null;
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&]*)").matcher(url);
        if (!m.find()) return null;
        try {
            return java.net.URLDecoder.decode(m.group(1), "UTF-8");
        } catch (Exception e) {
            return m.group(1);
        }
    }

    private static String jsonEscape(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
