package com.zgtools.videosaver;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音无水印视频解析核心
 *
 * 原理：请求抖音视频分享页HTML，从页面嵌入的JSON数据中
 * 提取无水印视频源地址（play_addr），直接下载原始文件。
 *
 * 注意：抖音接口会不定期更新，若解析失败说明页面结构已变更。
 */
public class DouyinParser {

    private static final String TAG = "DouyinParser";
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_HTML_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 30 * 1024 * 1024;
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
    /**
     * 抖音 CDN 的 Referer（2026-10 实测必需）。
     *
     * <p>{@code douyinvod.com} 现在会校验 Referer，缺失直接回<b>403</b>。
     * 实测：带 Referer → 206 正常；不带 → 403。UA 可以缺，Referer 不能缺。
     */
    private static final String MEDIA_REFERER = "https://www.douyin.com/";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * 进程内复用的浏览器标识（对应 Cookie 的 {@code s_v_web_id}）。
     * 详情接口缺它就会返回「HTTP 200 + 0 字节」，见 {@link #getOrCreateWebId()}。
     */
    private static volatile String webIdCache = null;
    /**
     * 进程内缓存的 ttwid。**这是详情接口能否拿到数据的唯一决定因素**，
     * 有效期很长（实测同一枚连发多次都有效），故缓存复用，不必每次都注册。
     */
    private static volatile String ttwidCache = null;
    /** ttwid 的缓存时刻，用来给缓存加 TTL，避免一枚用太久被抖音判失效。 */
    private static volatile long ttwidCachedAt = 0L;
    /**
     * ttwid 缓存有效期。服务端给的是 Max-Age=31536000（一年），但保守起见
     * 1 小时重新注册一次——多一次请求的代价远小于因 cookie 失效而 403。
     */
    private static final long TTWID_TTL_MS = 60L * 60L * 1000L;
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile(
            "https://(?:[A-Za-z0-9-]+\\.)*(?:douyin\\.com|iesdouyin\\.com)/[^\\s]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID_PATH_PATTERN = Pattern.compile(
            "/(?:video|note|aweme/detail|share/(?:video|slides))/(\\d{15,25})(?:[/?#]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID_QUERY_PATTERN = Pattern.compile(
            "(?:[?&]|&amp;)(?:modal_id|aweme_id|item_id)=(\\d{15,25})(?:[&#]|$)",
            Pattern.CASE_INSENSITIVE);

    public interface ParseCallback {
        void onSuccess(ParseResult result);
        void onError(String message);
    }

    public static final class ParseResult {
        /** 抖音作品的稳定 ID，用于防止同一媒体重复保存。 */
        public final String awemeId;
        public final String videoUrl;
        public final String title;
        /** 原始封面，用于随视频一并保存。 */
        public final String coverUrl;
        public final List<String> imageUrls;
        /** 与 imageUrls 顺序对齐、适合在选择界面显示的预览地址。 */
        public final List<String> imagePreviewUrls;
        public final List<String> liveVideoUrls;
        /** 与 imageUrls 对齐；有服务端合成图时优先用它保存贴纸的精确外观。 */
        public final List<String> stickerCompositeUrls;
        /** 与 imageUrls 对齐；服务端只下发分层贴纸时由客户端按坐标合成。 */
        public final List<List<StickerOverlay>> imageStickerOverlays;
        /** 原作品配乐播放地址；没有可下载配乐时为 null。 */
        public final String musicUrl;
        public final String musicTitle;

        ParseResult(String videoUrl, String title, String coverUrl, List<String> imageUrls,
                    List<String> imagePreviewUrls, List<String> liveVideoUrls) {
            this(null, videoUrl, title, coverUrl, imageUrls, imagePreviewUrls, liveVideoUrls, null, null);
        }

        private ParseResult(String awemeId, String videoUrl, String title, String coverUrl,
                            List<String> imageUrls, List<String> imagePreviewUrls,
                            List<String> liveVideoUrls, String musicUrl, String musicTitle) {
            this(awemeId, videoUrl, title, coverUrl, imageUrls, imagePreviewUrls,
                    liveVideoUrls, Collections.<String>emptyList(),
                    Collections.<List<StickerOverlay>>emptyList(), musicUrl, musicTitle);
        }

        private ParseResult(String awemeId, String videoUrl, String title, String coverUrl,
                            List<String> imageUrls, List<String> imagePreviewUrls,
                            List<String> liveVideoUrls, List<String> stickerCompositeUrls,
                            List<List<StickerOverlay>> imageStickerOverlays,
                            String musicUrl, String musicTitle) {
            this.awemeId = awemeId;
            this.videoUrl = videoUrl;
            this.title = title;
            this.coverUrl = coverUrl;
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
            ArrayList<String> alignedPreviewUrls = new ArrayList<>();
            for (int i = 0; i < imageUrls.size(); i++) {
                alignedPreviewUrls.add(i < imagePreviewUrls.size()
                        ? imagePreviewUrls.get(i) : imageUrls.get(i));
            }
            this.imagePreviewUrls = Collections.unmodifiableList(alignedPreviewUrls);
            ArrayList<String> alignedLiveUrls = new ArrayList<>();
            for (int i = 0; i < imageUrls.size(); i++) {
                alignedLiveUrls.add(i < liveVideoUrls.size() ? liveVideoUrls.get(i) : null);
            }
            this.liveVideoUrls = Collections.unmodifiableList(alignedLiveUrls);
            ArrayList<String> alignedCompositeUrls = new ArrayList<>();
            ArrayList<List<StickerOverlay>> alignedOverlays = new ArrayList<>();
            for (int i = 0; i < imageUrls.size(); i++) {
                alignedCompositeUrls.add(i < stickerCompositeUrls.size()
                        ? stickerCompositeUrls.get(i) : null);
                List<StickerOverlay> overlays = i < imageStickerOverlays.size()
                        ? imageStickerOverlays.get(i) : null;
                alignedOverlays.add(Collections.unmodifiableList(new ArrayList<>(overlays == null
                        ? Collections.<StickerOverlay>emptyList() : overlays)));
            }
            this.stickerCompositeUrls = Collections.unmodifiableList(alignedCompositeUrls);
            this.imageStickerOverlays = Collections.unmodifiableList(alignedOverlays);
            this.musicUrl = musicUrl;
            this.musicTitle = musicTitle;
        }

        ParseResult withAwemeId(String id) {
            return new ParseResult(id, videoUrl, title, coverUrl, imageUrls,
                    imagePreviewUrls, liveVideoUrls, stickerCompositeUrls,
                    imageStickerOverlays, musicUrl, musicTitle);
        }

        public boolean isImagePost() {
            return !imageUrls.isEmpty();
        }

        public int getLivePhotoCount() {
            int count = 0;
            for (String url : liveVideoUrls) if (url != null && !url.isEmpty()) count++;
            return count;
        }

        public boolean hasStickers() {
            for (int i = 0; i < imageUrls.size(); i++) {
                if (i < stickerCompositeUrls.size() && stickerCompositeUrls.get(i) != null
                        && !stickerCompositeUrls.get(i).isEmpty()) return true;
                if (i < imageStickerOverlays.size() && !imageStickerOverlays.get(i).isEmpty()) return true;
            }
            return false;
        }

        /** 平台只给出贴纸内容但省略精确坐标时，会使用兼容布局保留贴纸。 */
        public boolean hasEstimatedStickerLayout() {
            for (List<StickerOverlay> overlays : imageStickerOverlays) {
                for (StickerOverlay overlay : overlays) {
                    if (overlay.estimatedPosition) return true;
                }
            }
            return false;
        }
    }

    /** 一层可按原图归一化坐标绘制的贴纸。 */
    public static final class StickerOverlay {
        public final String resourceUrl;
        public final String text;
        public final float centerX;
        public final float centerY;
        public final float widthRatio;
        public final float heightRatio;
        public final float rotationDegrees;
        public final boolean estimatedPosition;

        StickerOverlay(String resourceUrl, String text, float centerX, float centerY,
                       float widthRatio, float heightRatio, float rotationDegrees) {
            this(resourceUrl, text, centerX, centerY, widthRatio, heightRatio,
                    rotationDegrees, false);
        }

        StickerOverlay(String resourceUrl, String text, float centerX, float centerY,
                       float widthRatio, float heightRatio, float rotationDegrees,
                       boolean estimatedPosition) {
            this.resourceUrl = resourceUrl;
            this.text = text;
            this.centerX = centerX;
            this.centerY = centerY;
            this.widthRatio = widthRatio;
            this.heightRatio = heightRatio;
            this.rotationDegrees = rotationDegrees;
            this.estimatedPosition = estimatedPosition;
        }
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onSuccess(String filePath);
        void onError(String message);
    }

    /**
     * 从分享链接中提取视频ID
     */
    public static String extractVideoId(String shareText) {
        if (shareText == null || shareText.isEmpty()) return null;
        String trimmed = shareText.trim();
        if (trimmed.matches("\\d{15,25}")) return trimmed;
        Matcher pathMatcher = VIDEO_ID_PATH_PATTERN.matcher(shareText);
        if (pathMatcher.find()) return pathMatcher.group(1);
        Matcher queryMatcher = VIDEO_ID_QUERY_PATTERN.matcher(shareText);
        if (queryMatcher.find()) return queryMatcher.group(1);
        return null;
    }

    private static String extractShareUrl(String shareText) {
        if (shareText == null) return null;
        Matcher matcher = SHARE_URL_PATTERN.matcher(shareText);
        if (!matcher.find()) return null;
        String url = matcher.group(0);
        while (!url.isEmpty() && "，。！？；：、,!?;:)]}》〉】\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static final class ResolvedShare {
        final String videoId;
        final String pageUrl;

        ResolvedShare(String videoId, String pageUrl) {
            this.videoId = videoId;
            this.pageUrl = pageUrl;
        }
    }

    private static ResolvedShare resolvedShare(String videoId, String candidateUrl) {
        // 保留短链最终跳转后的 /note/ 或 /video/ 页面。图文详情对 Referer 比较敏感，
        // 旧逻辑强制改成 share/video 会让部分图文只能拿到图片而拿不到 music。
        String pageUrl = candidateUrl;
        if (pageUrl == null || !isAllowedPageUrl(pageUrl)) {
            pageUrl = "https://www.iesdouyin.com/share/video/" + videoId + "/";
        }
        return new ResolvedShare(videoId, pageUrl);
    }

    private static boolean isIesShareUrl(String urlString) {
        if (urlString == null) return false;
        try {
            URL url = new URL(urlString);
            String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = url.getPath();
            return (host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com")) &&
                    (path.contains("/share/video/") || path.contains("/share/slides/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ResolvedShare resolveShare(String shareText) throws Exception {
        String directId = extractVideoId(shareText);
        String extractedUrl = extractShareUrl(shareText);
        if (directId != null) return resolvedShare(directId, extractedUrl);

        String currentUrl = extractedUrl;
        if (currentUrl == null) return null;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            if (!isAllowedPageUrl(currentUrl)) {
                throw new SecurityException("分享链接跳转到了不受支持的站点");
            }
            String idInUrl = extractVideoId(currentUrl);
            if (idInUrl != null) return resolvedShare(idInUrl, currentUrl);

            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA_MOBILE);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Referer", "https://www.douyin.com/");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try {
                int responseCode = conn.getResponseCode();
                if (isRedirect(responseCode)) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new Exception("短链跳转缺少目标地址");
                    }
                    currentUrl = new URL(url, location).toString();
                    continue;
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("短链请求失败，响应码: " + responseCode);
                }
                String html = readLimited(conn, MAX_HTML_BYTES);
                String idInHtml = extractVideoId(html);
                if (idInHtml != null) return resolvedShare(idInHtml, currentUrl);
                return null;
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("短链跳转次数过多");
    }

    private static String resolveVideoId(String shareText) throws Exception {
        ResolvedShare share = resolveShare(shareText);
        return share == null ? null : share.videoId;
    }

    /**
     * 异步解析视频
     */
    public static void parse(final String shareText, final ParseCallback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ResolvedShare share = resolveShare(shareText);
                    if (share == null) {
                        postError(handler, callback, "无法识别抖音链接，请粘贴完整的分享内容");
                        return;
                    }

                    ParseResult result = null;
                    Exception lastError = null;

                    // 首选 Web 详情接口。图文作品会额外尝试 note Referer：部分响应在 video Referer
                    // 下素材完整但 music 被裁掉，不能因为“图片已经解析成功”就停止补全元数据。
                    java.util.LinkedHashSet<String> apiReferers = new java.util.LinkedHashSet<>();
                    // 图文贴纸坐标也更常随 note 上下文返回，因此图文 Referer 放在首位。
                    apiReferers.add("https://www.douyin.com/note/" + share.videoId);
                    apiReferers.add(share.pageUrl);
                    apiReferers.add("https://www.douyin.com/video/" + share.videoId);
                    for (String apiReferer : apiReferers) {
                        if (result != null && result.musicUrl != null
                                && (!result.isImagePost() || result.hasStickers())) break;
                        try {
                            JSONObject detail = fetchAwemeDetail(share.videoId, apiReferer);
                            if (detail != null) result = mergeParseResults(result, parseVideoFromJson(detail));
                        } catch (Exception e) {
                            lastError = e;
                            Log.w(TAG, "签名详情接口失败，继续尝试其他来源", e);
                        }
                    }

                    String simpleShareUrl = "https://www.iesdouyin.com/share/video/" + share.videoId + "/";
                    String slidesShareUrl = "https://www.iesdouyin.com/share/slides/" + share.videoId +
                            "/?schema_type=37&is_slides=1&contains_video_type_clip=1";
                    String notePageUrl = "https://www.douyin.com/note/" + share.videoId;
                    String ixiguaShareUrl = "https://m.ixigua.com/douyin/share/video/" + share.videoId +
                            "?aweme_type=107&schema_type=1&utm_source=copy&utm_campaign=client_share" +
                            "&utm_medium=android&app=aweme";
                    java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
                    candidates.add(share.pageUrl);
                    candidates.add(notePageUrl);
                    candidates.add(slidesShareUrl);
                    candidates.add(simpleShareUrl);
                    candidates.add(ixiguaShareUrl);

                    int stickerMetadataPageAttempts = 0;
                    for (String candidate : candidates) {
                        boolean stickerOnlyAttempt = result != null && result.musicUrl != null
                                && result.isImagePost() && !result.hasStickers();
                        if (result != null && result.musicUrl != null && !stickerOnlyAttempt) break;
                        // 普通无贴纸图文无需把所有旧分享页都请求一遍；优先检查作品页和 note 页。
                        if (stickerOnlyAttempt && stickerMetadataPageAttempts >= 2) break;
                        try {
                            String html = fetchHtml(candidate);
                            if (html != null && !html.isEmpty()) {
                                result = mergeParseResults(result, extractVideoInfo(html));
                            }
                        } catch (Exception e) {
                            lastError = e;
                        }
                        if (stickerOnlyAttempt) stickerMetadataPageAttempts++;
                    }
                    if (result == null) {
                        String detail = lastError == null ? "分享页没有返回视频数据" : lastError.getMessage();
                        postError(handler, callback, "解析失败：" + detail);
                        return;
                    }

                    result = result.withAwemeId(share.videoId);

                    final ParseResult finalResult = result;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(finalResult);
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "解析异常", e);
                    postError(handler, callback, "解析异常: " + e.getMessage());
                }
            }
        }).start();
    }

    private static JSONObject fetchAwemeDetail(String awemeId, String sourcePageUrl) throws Exception {
        return fetchAwemeDetail(awemeId, sourcePageUrl, 0);
    }

    /**
     * @param retry 内部重试计数。空响应时换一枚 ttwid 重试，最多一次，避免无限递归。
     */
    private static JSONObject fetchAwemeDetail(String awemeId, String sourcePageUrl, int retry)
            throws Exception {
        String ttwid = getTtwid();
        String webId = getOrCreateWebId();
        String msToken = randomToken(107);
        String encodedMsToken = java.net.URLEncoder.encode(msToken, "UTF-8");
        // web_id 不是能否返回数据的决定因素（实测：不带 web_id 只给 ttwid 也能拿到 86KB 数据），
        // 但带上更接近真实浏览器请求。它对应 Cookie 里的 s_v_web_id。
        String query = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                "&pc_client_type=1&version_code=190500&version_name=19.5.0" +
                "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
                "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome" +
                "&browser_version=123.0.0.0&browser_online=true&engine_name=Blink" +
                "&engine_version=123.0.0.0&os_name=Windows&os_version=10" +
                "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10" +
                "&effective_type=4g&round_trip_time=100&aweme_id=" + awemeId +
                "&web_id=" + webId +
                "&msToken=" + encodedMsToken;
        String signature = DouyinSign.generate(query, UA_DESKTOP);
        String requestUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?" +
                query + "&a_bogus=" + java.net.URLEncoder.encode(signature, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(requestUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA_DESKTOP);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Origin", "https://www.douyin.com");
        conn.setRequestProperty("Sec-Fetch-Site", "same-origin");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Dest", "empty");
        boolean notePage = sourcePageUrl != null && sourcePageUrl.toLowerCase(java.util.Locale.ROOT).contains("/note/");
        conn.setRequestProperty("Referer", notePage
                ? "https://www.douyin.com/note/" + awemeId
                : "https://www.douyin.com/video/" + awemeId + "?previous_page=web_code_link");
        conn.setRequestProperty("Sec-CH-UA", "\"Google Chrome\";v=\"123\", \"Chromium\";v=\"123\"");
        conn.setRequestProperty("Sec-CH-UA-Mobile", "?0");
        conn.setRequestProperty("Sec-CH-UA-Platform", "\"Windows\"");
        // Cookie 里 s_v_web_id 与查询参数里的 web_id 必须一致。
            // 实测决定性因素还是 ttwid：它为空时接口只回 200 + 空体。
            String cookie = "s_v_web_id=" + webId
                    + (TextUtils.isEmpty(ttwid) ? "" : "; ttwid=" + ttwid);
            conn.setRequestProperty("Cookie", cookie);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 在抖音这里几乎总是「ttwid 不对/失效」，不是 IP 被封
                // （本机用同一个 IP 反复测都是 200）。换一枚 ttwid 重试。
                if (code == 403 && retry < 1) {
                    Log.w(TAG, "详情接口 403，重置 ttwid 后重试一次");
                    ttwidCache = null;
                    ttwidCachedAt = 0L;
                    return fetchAwemeDetail(awemeId, sourcePageUrl, retry + 1);
                }
                throw new Exception("详情接口响应码: " + code
                        + (code == 403 ? "（抖音风控，通常换网络环境或稍后重试即可）" : ""));
            }
            String body = readLimited(conn, MAX_HTML_BYTES);
            // 抖音对"不合规"的请求回的是 200 + 空体，不是 403。分清这两种情况，
            // 否则用户只会看到一句含糊的"没有返回视频数据"。
            if (body == null || body.trim().isEmpty()) {
                // 空体最常见的原因就是 ttwid 失效/没拿到。清缓存重试一次，
                // 重新注册往往就能拿到数据——比直接报错给用户有用得多。
                if (retry < 1 && !TextUtils.isEmpty(ttwid)) {
                    Log.w(TAG, "详情接口返回空内容，重置 ttwid 后重试一次");
                    ttwidCache = null;
                    ttwidCachedAt = 0L;
                    return fetchAwemeDetail(awemeId, sourcePageUrl, retry + 1);
                }
                ttwidCache = null;
                ttwidCachedAt = 0L;
                throw new Exception("抖音接口返回了空内容（HTTP 200 但无数据），"
                        + "且重试后依旧如此"
                        + (TextUtils.isEmpty(ttwid) ? "。注意：本次没能取到 ttwid 设备标识"
                                + "（Cookie 请求头缺失），这是最可能的原因"
                                : "。ttwid 已取到但仍被拦，说明 IP 段被风控")
                        + "。请换一个网络环境（Wi-Fi ↔ 流量切换）后重试");
            }
            JSONObject json = new JSONObject(body);
            if (json.optJSONObject("aweme_detail") == null) {
                String message = json.optString("status_msg", "详情接口没有返回视频数据");
                throw new Exception(message);
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    /**
 * 取一个可用的 {@code ttwid}（设备标识）。
     *
     * <p><b>这是抖音详情接口能否返回数据的唯一决定因素</b>（2026-10 实测）：
     * 缺 ttwid 时接口回<b>HTTP 200 + 响应体 0 字节</b>，有 ttwid 时回 86KB 完整 JSON。
     * 而 {@code web_id}、{@code a_bogus} 都不影响是否返回数据。
     *
     * <p>踩过的坑：原来只靠 {@link HttpURLConnection#getHeaderFields()} 读
     * {@code Set-Cookie}，在部分环境（桌面 JVM 实测、以及某些 Android 网络栈）下
     * **根本读不到**，于是 ttwid 恒为 null，接口永远返回空。
     * 现在改成三条路：Android 走系统 {@link CookieManager}（最可靠，它会真的存下cookie），
     * 再回退到读 header，最后用一次「响应体里的 redirect_url」兜底。
     *
     * <p>ttwid 有效期很长（实测同一枚连发多次都有效），故进程内缓存复用，不必每次注册。
     */
private static String getTtwid() {
        if (ttwidCache != null && !ttwidCache.isEmpty()
                && System.currentTimeMillis() - ttwidCachedAt < TTWID_TTL_MS) {
            return ttwidCache;
        }
        String ttwid = registerTtwid();
        ttwidCache = ttwid;
        ttwidCachedAt = System.currentTimeMillis();
        return ttwid;
    }

    private static String registerTtwid() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://ttwid.bytedance.com/ttwid/union/register/").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", UA_DESKTOP);
            conn.setRequestProperty("Content-Type", "application/json");
            // 跟随重定向时 Set-Cookie 会落到最后一个响应上，这里先关掉，
            // 保证下面读到的 header 就是下发 cookie 的那一条。
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            byte[] body = ("{\"region\":\"cn\",\"aid\":6383,\"need_t\":1," +
                    "\"service\":\"www.douyin.com\",\"migrate_priority\":0," +
                    "\"cb_url_protocol\":\"https\",\"domain\":\".douyin.com\"}")
                    .getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = conn.getOutputStream()) {
                output.write(body);
            }
            conn.getResponseCode();

            // 路1（最可靠）：直接读本次响应的 Set-Cookie —— 拿到的就是刚下发的、
            // 一定是新鲜的 cookie。实测 JVM 与 Android 都能正常读到。
            // 刻意**不把 CookieManager 放在前面**：它可能返回几天前的旧 cookie，
            // 一直复用会导致详情接口持续 403（本轮真机踩到）。
            for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String value : header.getValue()) {
                    Matcher matcher = Pattern.compile("(?:^|,\\s*)ttwid=([^;\\s]+)",
                            Pattern.CASE_INSENSITIVE).matcher(value);
                    if (matcher.find()) {
                        String v = URLDecoder.decode(matcher.group(1), "UTF-8");
                        if (!TextUtils.isEmpty(v)) return v;
                    }
                }
            }

            // 路2：系统 CookieManager（万一某些网络栈真的不返回 Set-Cookie 头）
            String fromManager = ttwidFromCookieManager();
            if (!TextUtils.isEmpty(fromManager)) return fromManager;

            // 路3：注册成功但拿不到 cookie。返回 null，调用方会给出可读的报错，
            // 不再像以前那样静默地让下游接口返回空内容。
            Log.w(TAG, "ttwid 注册成功但未取到 cookie，详情接口会返回空内容");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "获取 ttwid 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从系统 CookieManager 里取 ttwid。Android 上这是唯一可靠的方式。 */
    private static String ttwidFromCookieManager() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String cookie = cm.getCookie("https://ttwid.bytedance.com/");
            if (!TextUtils.isEmpty(cookie)) {
                Matcher m = Pattern.compile("ttwid=([^;\\s]+)").matcher(cookie);
                if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
            }
        } catch (Throwable ignored) {
            // 非 Android 环境（JVM 单测）会走到这里，返回 null 即可
        }
        return null;
    }

    /**
     * 取（或首次生成）浏览器标识 {@code web_id}，对应 Cookie 里的 {@code s_v_web_id}。
     *
     * <p><b>这是 2026-10 抖音解析恢复的关键</b>：{@code web_id} 缺失时
     * {@code /aweme/v1/web/aweme/detail/} 会返回 **HTTP 200 + 响应体 0 字节**，
     * 不报任何错。老铁看到的就是「下载一直 403 / 解析失败」，很难往参数缺了上想。
     *
     * <p>它不是安全令牌，抖音只要一个格式合法的 19 位数字即可，因此本地随机生成后
     * 进程内复用即可，无需持久化。
     */
private static String getOrCreateWebId() {
        if (webIdCache != null) return webIdCache;
        // 抖音的 web_id 是 19 位十进制数，前几位非 0
        StringBuilder sb = new StringBuilder(19);
        sb.append(SECURE_RANDOM.nextInt(9) + 1);
        while (sb.length() < 19) sb.append(SECURE_RANDOM.nextInt(10));
        webIdCache = sb.toString();
        return webIdCache;
    }

    private static String randomToken(int length) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789=";
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    /**
     * 获取页面HTML内容
     */
    private static String fetchHtml(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            if (!isAllowedPageUrl(currentUrl)) {
                throw new SecurityException("页面地址不在允许列表中");
            }

            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA_MOBILE);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Referer", "https://www.douyin.com/");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            if (isRedirect(responseCode)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("页面重定向缺少目标地址");
                }
                currentUrl = new URL(url, location).toString();
                continue;
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new Exception("页面请求失败，响应码: " + responseCode);
            }

            try {
                return readLimited(conn, MAX_HTML_BYTES);
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("页面重定向次数过多");
    }

    private static String readLimited(HttpURLConnection conn, int maxBytes) throws Exception {
        long contentLength = conn.getContentLengthLong();
        if (contentLength > maxBytes) throw new Exception("页面数据过大");
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalRead = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxBytes) throw new Exception("页面数据超过安全限制");
                output.write(buffer, 0, bytesRead);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308;
    }

    private static boolean isAllowedPageUrl(String urlString) {
        return isAllowedHttpsUrl(urlString, new String[]{"douyin.com", "iesdouyin.com", "ixigua.com"});
    }

    private static boolean isAllowedMediaUrl(String urlString) {
        return isAllowedHttpsUrl(urlString, new String[]{
                "douyin.com", "iesdouyin.com", "douyinvod.com", "douyinpic.com",
                "bytecdn.cn", "bytecdn.com", "zjcdn.com", "snssdk.com", "amemv.com",
                "byteimg.com", "bytedance.com", "volccdn.com", "pstatp.com", "ixigua.com",
                "douyinstatic.com"
        });
    }

    private static boolean isAllowedHttpsUrl(String urlString, String[] allowedDomains) {
        try {
            URL url = new URL(urlString);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
            if (url.getPort() != -1 && url.getPort() != 443) return false;
            String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
            for (String domain : allowedDomains) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 从HTML中提取视频无水印地址和标题
     * 尝试多种数据格式
     */
    private static ParseResult extractVideoInfo(String html) {
        MusicMedia htmlMusic = extractMusicFromHtml(html);

        // 方式1: 从 _ROUTER_DATA 提取
        ParseResult result = extractFromRouterData(html);
        if (result != null) return mergeMusic(result, htmlMusic);

        // 方式2: 从 RENDER_DATA (script标签) 提取
        result = extractFromRenderDataScript(html);
        if (result != null) return mergeMusic(result, htmlMusic);

        // 方式3: 从 window._RENDER_DATA 提取
        result = extractFromWindowRenderData(html);
        if (result != null) return mergeMusic(result, htmlMusic);

        // 方式4: 直接从HTML中正则匹配视频地址
        result = extractByRegex(html);
        if (result != null) return mergeMusic(result, htmlMusic);

        return null;
    }

    private static ParseResult extractFromRouterData(String html) {
        try {
            Pattern pattern = Pattern.compile("(?:window\\.)?_ROUTER_DATA\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                JSONObject json = new JSONObject(matcher.group(1));
                return parseVideoFromJson(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "从_ROUTER_DATA提取失败", e);
        }
        return null;
    }

    private static ParseResult extractFromRenderDataScript(String html) {
        try {
            Pattern pattern = Pattern.compile("<script[^>]*id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String decoded = URLDecoder.decode(matcher.group(1).trim(), "UTF-8");
                JSONObject json = new JSONObject(decoded);
                return parseVideoFromJson(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "从RENDER_DATA提取失败", e);
        }
        return null;
    }

    private static ParseResult extractFromWindowRenderData(String html) {
        try {
            Pattern pattern = Pattern.compile("window\\._RENDER_DATA\\s*=\\s*[\"'](.*?)[\"']\\s*;?", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String decoded = URLDecoder.decode(matcher.group(1), "UTF-8");
                JSONObject json = new JSONObject(decoded);
                return parseVideoFromJson(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "从window._RENDER_DATA提取失败", e);
        }
        return null;
    }

    /**
     * 从JSON对象中递归查找视频信息
     */
    private static ParseResult parseVideoFromJson(JSONObject json) {
        try {
            JSONObject data = json.optJSONObject("aweme_detail");
            if (data == null) {
                JSONObject candidate = json.optJSONObject("data");
                if (candidate != null && looksLikeAweme(candidate)) data = candidate;
            }
            if (data == null) data = findAwemeObjectRecursive(json);

            String title = data == null ? null : firstString(data, "desc", "title");
            if ((title == null || title.isEmpty()) && data != null) {
                JSONObject author = data.optJSONObject("author");
                if (author != null) title = firstString(author, "nickname", "name");
            }
            if (title == null || title.isEmpty()) title = findTitleRecursive(json);

            MusicMedia musicMedia = data == null ? MusicMedia.empty() : extractMusicMedia(data);
            ImageMedia imageMedia = data == null ? ImageMedia.empty() : extractImageMedia(data);
            if (!imageMedia.imageUrls.isEmpty()) {
                if (title == null || title.isEmpty()) title = "抖音图文_" + System.currentTimeMillis();
                return new ParseResult(null, null, title, null, imageMedia.imageUrls,
                        imageMedia.previewUrls, imageMedia.liveVideoUrls,
                        imageMedia.stickerCompositeUrls, imageMedia.stickerOverlays,
                        musicMedia.url, musicMedia.title);
            }

            String videoUrl = null;
            if (data != null) {
                JSONObject video = data.optJSONObject("video");
                if (video != null) videoUrl = getPlayUrl(video);
            }
            if (videoUrl == null) videoUrl = findVideoUrlRecursive(json);
            if (videoUrl != null) {
                if (title == null || title.isEmpty()) title = "抖音视频_" + System.currentTimeMillis();
                return new ParseResult(null, videoUrl, title, extractCoverUrl(data),
                        Collections.<String>emptyList(), Collections.<String>emptyList(),
                        Collections.<String>emptyList(), musicMedia.url, musicMedia.title);
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON解析失败", e);
        }
        return null;
    }

    private static ParseResult mergeParseResults(ParseResult primary, ParseResult secondary) {
        if (primary == null) return secondary;
        if (secondary == null) return primary;
        String musicUrl = primary.musicUrl;
        String musicTitle = primary.musicTitle;
        if ((musicUrl == null || musicUrl.isEmpty()) && secondary.musicUrl != null
                && !secondary.musicUrl.isEmpty()) {
            musicUrl = secondary.musicUrl;
            musicTitle = secondary.musicTitle;
        }
        List<String> compositeUrls = primary.stickerCompositeUrls;
        List<List<StickerOverlay>> overlays = primary.imageStickerOverlays;
        if (!primary.hasStickers() && secondary.hasStickers()
                && primary.imageUrls.size() == secondary.imageUrls.size()) {
            compositeUrls = secondary.stickerCompositeUrls;
            overlays = secondary.imageStickerOverlays;
        }
        return new ParseResult(primary.awemeId, primary.videoUrl, primary.title, primary.coverUrl,
                primary.imageUrls, primary.imagePreviewUrls, primary.liveVideoUrls,
                compositeUrls, overlays, musicUrl, musicTitle);
    }

    private static ParseResult mergeMusic(ParseResult result, MusicMedia music) {
        if (result == null || music == null || music.url == null || music.url.isEmpty() ||
                (result.musicUrl != null && !result.musicUrl.isEmpty())) return result;
        return new ParseResult(result.awemeId, result.videoUrl, result.title, result.coverUrl,
                result.imageUrls, result.imagePreviewUrls, result.liveVideoUrls,
                result.stickerCompositeUrls, result.imageStickerOverlays, music.url, music.title);
    }

    private static boolean looksLikeAweme(JSONObject json) {
        return (json.optJSONArray("images") != null || json.optJSONObject("video") != null) &&
                (json.has("aweme_id") || json.has("awemeId") || json.has("desc") || json.has("author"));
    }

    private static JSONObject findAwemeObjectRecursive(Object object) {
        if (object instanceof JSONObject) {
            JSONObject json = (JSONObject) object;
            if (looksLikeAweme(json)) return json;
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                try {
                    JSONObject found = findAwemeObjectRecursive(json.get(keys.next()));
                    if (found != null) return found;
                } catch (Exception ignored) {
                }
            }
        } else if (object instanceof JSONArray) {
            JSONArray array = (JSONArray) object;
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject found = findAwemeObjectRecursive(array.get(i));
                    if (found != null) return found;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static final class MusicMedia {
        final String url;
        final String title;

        MusicMedia(String url, String title) {
            this.url = url;
            this.title = title;
        }

        static MusicMedia empty() {
            return new MusicMedia(null, null);
        }
    }

    /**
     * 兼容抖音详情页/分享页多种 music 字段形态。
     * 只在明确的 music 节点中寻找播放地址，避免误把视频 play_url 当成音乐。
     */
    private static MusicMedia extractMusicMedia(JSONObject data) {
        if (data == null) return MusicMedia.empty();
        String[] keys = {"music", "music_info", "musicInfo", "music_detail", "musicDetail", "music_info_v2", "musicInfoV2"};
        for (String key : keys) {
            Object value = data.opt(key);
            MusicMedia media = extractMusicFromValue(value);
            if (media.url != null) return media;
        }
        MusicMedia nested = findMusicMediaRecursive(data, 0);
        return nested == null ? MusicMedia.empty() : nested;
    }

    private static MusicMedia findMusicMediaRecursive(Object node, int depth) {
        if (node == null || depth > 8) return null;
        if (node instanceof JSONObject) {
            JSONObject json = (JSONObject) node;
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.opt(key);
                String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("music") || lower.equals("music_info") || lower.equals("musicinfo") ||
                        lower.equals("music_detail") || lower.equals("musicdetail") ||
                        lower.equals("music_info_v2") || lower.equals("musicinfov2")) {
                    MusicMedia candidate = extractMusicFromValue(value);
                    if (candidate.url != null) return candidate;
                }
            }
            keys = json.keys();
            while (keys.hasNext()) {
                Object value = json.opt(keys.next());
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    MusicMedia found = findMusicMediaRecursive(value, depth + 1);
                    if (found != null && found.url != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                MusicMedia found = findMusicMediaRecursive(array.opt(i), depth + 1);
                if (found != null && found.url != null) return found;
            }
        }
        return null;
    }

    private static MusicMedia extractMusicFromValue(Object value) {
        if (!(value instanceof JSONObject)) return MusicMedia.empty();
        JSONObject music = (JSONObject) value;
        String title = firstString(music, "title", "music_name", "musicName", "name", "owner_nickname", "ownerNickname");
        String url = null;
        String[] playKeys = {"play_url", "playUrl", "play_addr", "playAddr", "audio_url", "audioUrl", "preview_url", "previewUrl"};
        for (String key : playKeys) {
            url = extractPlayableUrl(music.opt(key), 0);
            if (url != null) break;
        }
        // 少数响应把可播放 URL 直接放在 music.url/src 下。
        if (url == null) {
            String direct = normalizeMediaUrl(firstString(music, "url", "src"));
            if (direct != null && isAllowedMediaUrl(direct)) url = direct;
        }
        return new MusicMedia(url, title);
    }

    private static String extractPlayableUrl(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 5) return null;
        if (value instanceof String) {
            String url = normalizeMediaUrl((String) value);
            return url != null && isAllowedMediaUrl(url) ? url : null;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String url = extractPlayableUrl(array.opt(i), depth + 1);
                if (url != null) return url;
            }
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject json = (JSONObject) value;
            String[] preferred = {"url_list", "urlList", "urls", "play_url", "playUrl", "uri", "url", "src"};
            for (String key : preferred) {
                if (!json.has(key)) continue;
                String url = extractPlayableUrl(json.opt(key), depth + 1);
                if (url != null) return url;
            }
        }
        return null;
    }

    /**
     * 页面 JSON 结构再次变动时的最后兜底：抖音音乐直链通常包含 /obj/ies-music/
     * 并位于 douyinstatic.com 等官方 CDN。这里只接受 allowlist 内 HTTPS 地址。
     */
    private static MusicMedia extractMusicFromHtml(String html) {
        if (html == null || html.isEmpty()) return MusicMedia.empty();
        String normalized = html.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&");
        try {
            if (normalized.contains("%2F") || normalized.contains("%3A")) {
                normalized += "\n" + URLDecoder.decode(normalized, "UTF-8");
            }
        } catch (Exception ignored) {
        }
        Pattern[] patterns = new Pattern[]{
                Pattern.compile("https://[^\\\"'<>\\s]+douyinstatic\\.com/[^\\\"'<>\\s]*(?:ies-music|music)[^\\\"'<>\\s]*", Pattern.CASE_INSENSITIVE),
                Pattern.compile("https://[^\\\"'<>\\s]+(?:douyinpic\\.com|byteimg\\.com)/[^\\\"'<>\\s]*(?:ies-music|music)[^\\\"'<>\\s]*", Pattern.CASE_INSENSITIVE)
        };
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String url = normalizeMediaUrl(matcher.group(0));
                if (url != null && isAllowedMediaUrl(url)) return new MusicMedia(url, null);
            }
        }
        return MusicMedia.empty();
    }

    private static final class ImageMedia {
        final List<String> imageUrls;
        final List<String> previewUrls;
        final List<String> liveVideoUrls;
        final List<String> stickerCompositeUrls;
        final List<List<StickerOverlay>> stickerOverlays;

        ImageMedia(List<String> imageUrls, List<String> previewUrls, List<String> liveVideoUrls,
                   List<String> stickerCompositeUrls,
                   List<List<StickerOverlay>> stickerOverlays) {
            this.imageUrls = imageUrls;
            this.previewUrls = previewUrls;
            this.liveVideoUrls = liveVideoUrls;
            this.stickerCompositeUrls = stickerCompositeUrls;
            this.stickerOverlays = stickerOverlays;
        }

        static ImageMedia empty() {
            return new ImageMedia(Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<List<StickerOverlay>>emptyList());
        }
    }

    private static ImageMedia extractImageMedia(JSONObject data) {
        JSONArray images = data.optJSONArray("images");
        if (images == null || images.length() == 0) {
            images = data.optJSONArray("image_list");
        }
        if (images == null || images.length() == 0) {
            JSONObject post = firstObject(data, "image_post_info", "imagePostInfo");
            if (post != null) images = firstArray(post, "images", "image_list", "imageList");
        }
        if (images == null || images.length() == 0) return ImageMedia.empty();
        ArrayList<String> imageUrls = new ArrayList<>();
        ArrayList<String> previewUrls = new ArrayList<>();
        ArrayList<String> liveVideoUrls = new ArrayList<>();
        ArrayList<String> stickerCompositeUrls = new ArrayList<>();
        ArrayList<List<StickerOverlay>> stickerOverlays = new ArrayList<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;

            // 无水印静态原图：优先 images[].url_list。download_url_list 在当前接口中可能是带水印下载图。
            JSONArray cleanList = firstArray(image, "url_list", "urlList");
            if (cleanList == null) {
                JSONObject original = firstObject(image, "origin_image", "originImage", "original_image", "originalImage");
                if (original != null) cleanList = firstArray(original, "url_list", "urlList");
            }
            String imageUrl = chooseCleanImageUrl(cleanList);
            if (imageUrl == null) {
                String direct = normalizeMediaUrl(firstString(image, "origin_url", "originUrl", "url"));
                if (direct != null && isAllowedMediaUrl(direct) && !looksWatermarkedImageUrl(direct)) imageUrl = direct;
            }
            // 兼容旧接口：只有没有任何原图字段时才使用 download_url_list 兜底。
            JSONArray downloadList = firstArray(image, "download_url_list", "downloadUrlList");
            if (imageUrl == null) imageUrl = chooseCleanImageUrl(downloadList);

            // 预览可以用 display_image / 第一候选，避免影响实际保存的原图地址。
            String previewUrl = null;
            JSONObject display = firstObject(image, "display_image", "displayImage");
            JSONArray previewList = display == null ? null : firstArray(display, "url_list", "urlList");
            if (previewList != null) previewUrl = chooseAnyImageUrl(previewList, false);
            if (previewUrl == null && cleanList != null) previewUrl = chooseAnyImageUrl(cleanList, false);
            if (previewUrl == null) previewUrl = imageUrl;

            String liveVideoUrl = extractLiveVideoUrl(image);
            if (imageUrl == null && liveVideoUrl == null) continue;
            imageUrls.add(imageUrl == null ? "" : imageUrl);
            previewUrls.add(previewUrl == null ? (imageUrl == null ? "" : imageUrl) : previewUrl);
            liveVideoUrls.add(liveVideoUrl);
            stickerCompositeUrls.add(extractStickerCompositeUrl(image));
            stickerOverlays.add(extractStickerOverlays(image));
        }
        appendAwemeStickerOverlays(data, stickerOverlays);
        return new ImageMedia(imageUrls, previewUrls, liveVideoUrls,
                stickerCompositeUrls, stickerOverlays);
    }

    private static String extractStickerCompositeUrl(JSONObject image) {
        Object[] candidates = {
                image.opt("sticker_image"), image.opt("stickerImage"),
                image.opt("stickered_image"), image.opt("stickeredImage"),
                image.opt("composite_image"), image.opt("compositeImage"),
                image.opt("image_with_sticker"), image.opt("imageWithSticker"),
                image.opt("image_with_stickers"), image.opt("imageWithStickers"),
                image.opt("display_image_with_stickers"), image.opt("displayImageWithStickers")
        };
        for (Object candidate : candidates) {
            String url = extractImageUrlValue(candidate);
            if (url != null) return url;
        }
        return null;
    }

    private static List<StickerOverlay> extractStickerOverlays(JSONObject container) {
        ArrayList<StickerOverlay> result = new ArrayList<>();
        String[] arrayKeys = {
                "interaction_stickers", "interactionStickers", "stickers", "sticker_list",
                "stickerList", "text_sticker_list", "textStickerList", "text_stickers",
                "textStickers", "stickers_on_item", "stickersOnItem", "image_stickers",
                "imageStickers", "interaction_sticker_list", "interactionStickerList"
        };
        for (String key : arrayKeys) {
            JSONArray array = container.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject sticker = array.optJSONObject(i);
                addStickerIfUnique(result, parseStickerOverlayOrEstimate(sticker, i, array.length()));
            }
        }
        String[] objectKeys = {"sticker_info", "stickerInfo", "text_sticker", "textSticker",
                "sticker_data", "stickerData", "interaction_sticker", "interactionSticker"};
        for (String key : objectKeys) {
            addStickerIfUnique(result, parseStickerOverlayOrEstimate(
                    container.optJSONObject(key), 0, 1));
        }
        appendNestedStickerContainers(container, result, 0);
        return result;
    }

    private static void appendAwemeStickerOverlays(JSONObject data,
                                                    List<List<StickerOverlay>> imageOverlays) {
        if (imageOverlays.isEmpty()) return;
        appendStickerArraysFromContainer(data, imageOverlays, 0);
        JSONObject post = firstObject(data, "image_post_info", "imagePostInfo");
        if (post != null) appendStickerArraysFromContainer(post, imageOverlays, 0);
    }

    private static void appendStickerArraysFromContainer(JSONObject container,
                                                          List<List<StickerOverlay>> imageOverlays,
                                                          int defaultImageIndex) {
        String[] keys = {
                "interaction_stickers", "interactionStickers", "sticker_list", "stickerList",
                "text_sticker_list", "textStickerList", "text_stickers", "textStickers",
                "stickers_on_item", "stickersOnItem", "image_stickers", "imageStickers",
                "interaction_sticker_list", "interactionStickerList"
        };
        for (String key : keys) {
            JSONArray array = container.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject sticker = array.optJSONObject(i);
                StickerOverlay overlay = parseStickerOverlayOrEstimate(sticker, i, array.length());
                if (overlay == null) continue;
                int imageIndex = extractStickerImageIndex(sticker, defaultImageIndex,
                        imageOverlays.size());
                addStickerIfUnique(imageOverlays.get(imageIndex), overlay);
            }
        }
        appendNestedAwemeStickerContainers(container, imageOverlays, defaultImageIndex, 0);
    }

    private static StickerOverlay parseStickerOverlayOrEstimate(JSONObject sticker,
                                                                 int ordinal, int total) {
        StickerOverlay precise = parseStickerOverlay(sticker);
        if (precise != null) return precise;
        if (sticker == null) return null;
        String resourceUrl = findStickerResourceUrl(sticker, 0);
        String text = findStickerText(sticker, 0);
        if ((resourceUrl == null || resourceUrl.isEmpty()) && (text == null || text.isEmpty())) {
            return null;
        }
        int safeTotal = Math.max(1, total);
        float centerY = safeTotal == 1 ? 0.66f
                : 0.48f + 0.36f * Math.max(0, Math.min(ordinal, safeTotal - 1))
                / Math.max(1f, safeTotal - 1f);
        return new StickerOverlay(resourceUrl, text, 0.5f, centerY,
                resourceUrl == null ? 0.78f : 0.36f, 0f, 0f, true);
    }

    /**
     * 新旧接口会把贴纸放在不同层级，甚至把 track_info 再序列化成 JSON 字符串。
     * 从贴纸相关字段向下递归，避免只认固定的一层键名。
     */
    private static void appendNestedStickerContainers(Object value, List<StickerOverlay> result,
                                                       int depth) {
        if (value == null || value == JSONObject.NULL || depth > 5) return;
        if (value instanceof String) {
            Object decoded = decodeJsonValue((String) value);
            if (decoded != null) appendNestedStickerContainers(decoded, result, depth + 1);
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                appendNestedStickerContainers(array.opt(i), result, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object nested = object.opt(key);
            if (isStickerContainerKey(key)) {
                appendStickerValue(nested, result);
            }
            if (shouldDescendForStickerSearch(key, nested)) {
                appendNestedStickerContainers(nested, result, depth + 1);
            }
        }
    }

    private static void appendNestedAwemeStickerContainers(Object value,
                                                            List<List<StickerOverlay>> imageOverlays,
                                                            int fallbackImageIndex, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 5) return;
        if (value instanceof String) {
            Object decoded = decodeJsonValue((String) value);
            if (decoded != null) appendNestedAwemeStickerContainers(decoded, imageOverlays,
                    fallbackImageIndex, depth + 1);
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                appendNestedAwemeStickerContainers(array.opt(i), imageOverlays,
                        fallbackImageIndex, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object nested = object.opt(key);
            if (isStickerContainerKey(key)) {
                appendAwemeStickerValue(nested, imageOverlays, fallbackImageIndex);
            }
            if (shouldDescendForStickerSearch(key, nested)) {
                appendNestedAwemeStickerContainers(nested, imageOverlays,
                        fallbackImageIndex, depth + 1);
            }
        }
    }

    private static void appendStickerValue(Object value, List<StickerOverlay> result) {
        Object decoded = value instanceof String ? decodeJsonValue((String) value) : value;
        if (decoded instanceof JSONObject) {
            addStickerIfUnique(result, parseStickerOverlayOrEstimate((JSONObject) decoded, 0, 1));
        } else if (decoded instanceof JSONArray) {
            JSONArray array = (JSONArray) decoded;
            for (int i = 0; i < array.length(); i++) {
                addStickerIfUnique(result, parseStickerOverlayOrEstimate(
                        array.optJSONObject(i), i, array.length()));
            }
        }
    }

    private static void appendAwemeStickerValue(Object value,
                                                 List<List<StickerOverlay>> imageOverlays,
                                                 int fallbackImageIndex) {
        Object decoded = value instanceof String ? decodeJsonValue((String) value) : value;
        if (decoded instanceof JSONObject) {
            JSONObject sticker = (JSONObject) decoded;
            int imageIndex = extractStickerImageIndex(sticker, fallbackImageIndex,
                    imageOverlays.size());
            addStickerIfUnique(imageOverlays.get(imageIndex),
                    parseStickerOverlayOrEstimate(sticker, 0, 1));
        } else if (decoded instanceof JSONArray) {
            JSONArray array = (JSONArray) decoded;
            for (int i = 0; i < array.length(); i++) {
                JSONObject sticker = array.optJSONObject(i);
                if (sticker == null) continue;
                int imageIndex = extractStickerImageIndex(sticker, fallbackImageIndex,
                        imageOverlays.size());
                addStickerIfUnique(imageOverlays.get(imageIndex),
                        parseStickerOverlayOrEstimate(sticker, i, array.length()));
            }
        }
    }

    private static boolean isStickerContainerKey(String key) {
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("sticker")) return false;
        return !(lower.contains("url") || lower.contains("uri") || lower.contains("image")
                || lower.contains("icon") || lower.contains("resource")
                || lower.contains("cover"));
    }

    private static boolean shouldDescendForStickerSearch(String key, Object value) {
        if (!(value instanceof JSONObject) && !(value instanceof JSONArray)
                && !(value instanceof String)) return false;
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if ("images".equals(lower) || "image_list".equals(lower)
                || "imagelist".equals(lower)) return false;
        return lower.contains("sticker") || lower.contains("post") || lower.contains("item")
                || lower.contains("content") || lower.contains("data")
                || lower.contains("interaction") || lower.contains("extra")
                || lower.contains("info") || lower.contains("track")
                || lower.contains("position") || lower.contains("layout");
    }

    private static Object decodeJsonValue(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        try {
            if (text.startsWith("%7B") || text.startsWith("%7b")
                    || text.startsWith("%5B") || text.startsWith("%5b")) {
                text = URLDecoder.decode(text, "UTF-8");
            }
            if (text.startsWith("{")) return new JSONObject(text);
            if (text.startsWith("[")) return new JSONArray(text);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int extractStickerImageIndex(JSONObject sticker, int fallback, int imageCount) {
        if (sticker == null || imageCount <= 0) return 0;
        int index = firstInteger(sticker, -1,
                "image_index", "imageIndex", "photo_index", "photoIndex",
                "pic_index", "picIndex", "material_index", "materialIndex");
        if (index < 0) {
            int page = firstInteger(sticker, -1, "page_index", "pageIndex", "page");
            if (page > 0) index = page - 1;
            else if (page == 0) index = 0;
        }
        JSONObject position = findStickerPositionObject(sticker, 0);
        if (index < 0 && position != null) {
            index = firstInteger(position, -1,
                    "image_index", "imageIndex", "photo_index", "photoIndex",
                    "pic_index", "picIndex", "material_index", "materialIndex");
        }
        if (index < 0 || index >= imageCount) index = Math.max(0, Math.min(fallback, imageCount - 1));
        return index;
    }

    private static StickerOverlay parseStickerOverlay(JSONObject sticker) {
        if (sticker == null) return null;
        JSONObject position = findStickerPositionObject(sticker, 0);
        if (position == null) return null;

        double x = firstDouble(position, Double.NaN,
                "x", "center_x", "centerX", "position_x", "positionX", "x_axis", "xAxis",
                "pos_x", "posX", "translation_x", "translationX", "offset_x", "offsetX",
                "start_x", "startX", "start_pos_x", "startPosX", "origin_x", "originX",
                "x_pos", "xPos", "anchor_x", "anchorX", "location_x", "locationX");
        double y = firstDouble(position, Double.NaN,
                "y", "center_y", "centerY", "position_y", "positionY", "y_axis", "yAxis",
                "pos_y", "posY", "translation_y", "translationY", "offset_y", "offsetY",
                "start_y", "startY", "start_pos_y", "startPosY", "origin_y", "originY",
                "y_pos", "yPos", "anchor_y", "anchorY", "location_y", "locationY");
        double left = firstDouble(position, Double.NaN,
                "left", "left_x", "leftX", "min_x", "minX", "x1");
        double right = firstDouble(position, Double.NaN,
                "right", "right_x", "rightX", "max_x", "maxX", "x2");
        double top = firstDouble(position, Double.NaN,
                "top", "top_y", "topY", "min_y", "minY", "y1");
        double bottom = firstDouble(position, Double.NaN,
                "bottom", "bottom_y", "bottomY", "max_y", "maxY", "y2");
        if (Double.isNaN(x) && !Double.isNaN(left) && !Double.isNaN(right)) {
            x = (left + right) / 2d;
        }
        if (Double.isNaN(y) && !Double.isNaN(top) && !Double.isNaN(bottom)) {
            y = (top + bottom) / 2d;
        }
        if (Double.isNaN(x) || Double.isNaN(y)) return null;

        double canvasWidth = firstDouble(position, Double.NaN,
                "canvas_width", "canvasWidth", "canvas_w", "canvasW", "base_width", "baseWidth",
                "image_width", "imageWidth", "screen_width", "screenWidth", "video_width",
                "videoWidth", "material_width", "materialWidth");
        double canvasHeight = firstDouble(position, Double.NaN,
                "canvas_height", "canvasHeight", "canvas_h", "canvasH", "base_height", "baseHeight",
                "image_height", "imageHeight", "screen_height", "screenHeight", "video_height",
                "videoHeight", "material_height", "materialHeight");
        x = normalizeStickerCoordinate(x, canvasWidth);
        y = normalizeStickerCoordinate(y, canvasHeight);
        if (x < 0d || x > 1d || y < 0d || y > 1d) return null;

        double width = firstDouble(position, Double.NaN,
                "width", "w", "sticker_width", "stickerWidth", "relative_width", "relativeWidth",
                "start_width", "startWidth", "display_width", "displayWidth", "size_width",
                "sizeWidth", "rect_width", "rectWidth");
        double height = firstDouble(position, Double.NaN,
                "height", "h", "sticker_height", "stickerHeight", "relative_height", "relativeHeight",
                "start_height", "startHeight", "display_height", "displayHeight", "size_height",
                "sizeHeight", "rect_height", "rectHeight");
        if (Double.isNaN(width) && !Double.isNaN(left) && !Double.isNaN(right)) {
            width = Math.abs(right - left);
        }
        if (Double.isNaN(height) && !Double.isNaN(top) && !Double.isNaN(bottom)) {
            height = Math.abs(bottom - top);
        }
        float widthRatio = normalizeStickerSize(width, canvasWidth);
        float heightRatio = normalizeStickerSize(height, canvasHeight);
        double rotation = firstDouble(position, 0d,
                "rotation", "rotation_angle", "rotationAngle", "rotate", "angle",
                "start_rotation", "startRotation", "display_angle", "displayAngle",
                "rotate_angle", "rotateAngle");
        if (Math.abs(rotation) <= Math.PI * 2.1d && Math.abs(rotation) > 0.001d) {
            rotation = Math.toDegrees(rotation);
        }

        String resourceUrl = findStickerResourceUrl(sticker, 0);
        String text = findStickerText(sticker, 0);
        if ((resourceUrl == null || resourceUrl.isEmpty()) && (text == null || text.isEmpty())) return null;
        return new StickerOverlay(resourceUrl, text, (float) x, (float) y,
                widthRatio, heightRatio, (float) rotation);
    }

    private static JSONObject findStickerPositionObject(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 5) return null;
        if (value instanceof String) {
            Object decoded = decodeJsonValue((String) value);
            return decoded == null ? null : findStickerPositionObject(decoded, depth + 1);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findStickerPositionObject(array.opt(i), depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (hasAnyKey(object, "x", "center_x", "centerX", "position_x", "positionX",
                    "pos_x", "posX", "translation_x", "translationX", "offset_x", "offsetX",
                    "start_x", "startX", "start_pos_x", "startPosX", "origin_x", "originX",
                    "x_pos", "xPos", "anchor_x", "anchorX", "location_x", "locationX")
                    && hasAnyKey(object, "y", "center_y", "centerY", "position_y", "positionY",
                    "pos_y", "posY", "translation_y", "translationY", "offset_y", "offsetY",
                    "start_y", "startY", "start_pos_y", "startPosY", "origin_y", "originY",
                    "y_pos", "yPos", "anchor_y", "anchorY", "location_y", "locationY")) {
                return object;
            }
            if (hasAnyKey(object, "left", "left_x", "leftX", "min_x", "minX", "x1")
                    && hasAnyKey(object, "right", "right_x", "rightX", "max_x", "maxX", "x2")
                    && hasAnyKey(object, "top", "top_y", "topY", "min_y", "minY", "y1")
                    && hasAnyKey(object, "bottom", "bottom_y", "bottomY", "max_y", "maxY", "y2")) {
                return object;
            }
            String[] preferred = {
                    "position_info", "positionInfo", "track_info", "trackInfo",
                    "transform", "location", "position", "layout"
            };
            for (String key : preferred) {
                JSONObject found = findStickerPositionObject(object.opt(key), depth + 1);
                if (found != null) return found;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    JSONObject found = findStickerPositionObject(object.opt(key), depth + 1);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static boolean hasAnyKey(JSONObject object, String... keys) {
        for (String key : keys) if (object.has(key)) return true;
        return false;
    }

    private static double normalizeStickerCoordinate(double value, double canvasSize) {
        if (!Double.isNaN(canvasSize) && canvasSize > 1d && Math.abs(value) > 1d) {
            return value / canvasSize;
        }
        if (value >= 0d && value <= 1d) return value;
        if (value >= -1d && value < 0d) return (value + 1d) / 2d;
        if (value >= 0d && value <= 100d) return value / 100d;
        if (value >= 0d && value <= 1000d) return value / 1000d;
        return value;
    }

    private static float normalizeStickerSize(double value, double canvasSize) {
        if (Double.isNaN(value) || value <= 0d) return 0f;
        if (!Double.isNaN(canvasSize) && canvasSize > 1d && value > 1d) value /= canvasSize;
        else if (value > 1d && value <= 100d) value /= 100d;
        else if (value > 100d && value <= 1000d) value /= 1000d;
        return value > 0d && value <= 1d ? (float) value : 0f;
    }

    private static String findStickerResourceUrl(Object value, int depth) {
        if (!(value instanceof JSONObject) || depth > 5) return null;
        JSONObject object = (JSONObject) value;
        String[] preferred = {
                "sticker_image", "stickerImage", "sticker_url", "stickerUrl",
                "resource_url", "resourceUrl", "icon_url", "iconUrl", "image_url",
                "imageUrl", "image", "icon", "resource"
        };
        for (String key : preferred) {
            String url = extractImageUrlValue(object.opt(key));
            if (url != null) return url;
        }
        JSONArray names = object.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.contains("sticker") || lower.contains("resource") || lower.contains("icon"))) continue;
                Object nested = object.opt(key);
                String url = extractImageUrlValue(nested);
                if (url != null) return url;
                url = findStickerResourceUrl(nested, depth + 1);
                if (url != null) return url;
            }
        }
        return null;
    }

    private static String extractImageUrlValue(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof String) {
            String url = normalizeMediaUrl((String) value);
            return looksLikeStickerImageUrl(url) ? url : null;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String url = extractImageUrlValue(array.opt(i));
                if (url != null) return url;
            }
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray urls = firstArray(object, "url_list", "urlList", "urls");
            if (urls != null) {
                String url = chooseAnyImageUrl(urls, false);
                if (looksLikeStickerImageUrl(url)) return url;
            }
            String direct = normalizeMediaUrl(firstString(object, "url", "uri"));
            if (looksLikeStickerImageUrl(direct)) return direct;
        }
        return null;
    }

    private static boolean looksLikeStickerImageUrl(String url) {
        if (url == null || !isAllowedMediaUrl(url)) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("douyinpic") || lower.contains("byteimg") || lower.contains("tplv-")
                || lower.contains(".png") || lower.contains(".webp") || lower.contains(".jpg")
                || lower.contains(".jpeg") || lower.contains("image/");
    }

    private static String findStickerText(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 6) return null;
        if (value instanceof String) {
            Object decoded = decodeJsonValue((String) value);
            if (decoded != null) return findStickerText(decoded, depth + 1);
            String text = ((String) value).trim();
            return isUsableStickerText(text) && !looksLikeUrl(text) ? text : null;
        }
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < values.length(); i++) {
                String itemText = findStickerText(values.opt(i), depth + 1);
                if (!isUsableStickerText(itemText)) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(itemText.trim());
            }
            return joined.length() == 0 ? null : joined.toString();
        }
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;
        String text = firstPlainString(object, "sticker_text", "stickerText", "text", "content",
                "question", "title", "desc", "text_content", "textContent", "display_text",
                "displayText", "label_text", "labelText");
        if (isUsableStickerText(text)) return text.trim();
        String[] arrays = {"sticker_text", "stickerText", "text_list", "textList", "options"};
        for (String key : arrays) {
            JSONArray values = object.optJSONArray(key);
            if (values == null) continue;
            text = findStickerText(values, depth + 1);
            if (isUsableStickerText(text)) return text;
        }
        String[] nestedKeys = {"text_info", "textInfo", "text_sticker_info", "textStickerInfo",
                "poll_info", "pollInfo", "vote_info", "voteInfo", "content_info", "contentInfo"};
        for (String key : nestedKeys) {
            text = findStickerText(object.opt(key), depth + 1);
            if (isUsableStickerText(text)) return text;
        }
        JSONArray names = object.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.contains("text") || lower.contains("content")
                        || lower.contains("question") || lower.contains("label")
                        || lower.contains("option") || lower.contains("poll")
                        || lower.contains("vote"))) continue;
                text = findStickerText(object.opt(key), depth + 1);
                if (isUsableStickerText(text)) return text;
            }
        }
        return null;
    }

    private static String firstPlainString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String || value instanceof Number) {
                String text = String.valueOf(value);
                if (isUsableStickerText(text) && !looksLikeUrl(text)) return text;
            }
        }
        return null;
    }

    private static boolean looksLikeUrl(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isUsableStickerText(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= 500
                && !"null".equalsIgnoreCase(text.trim());
    }

    private static void addStickerIfUnique(List<StickerOverlay> list, StickerOverlay overlay) {
        if (overlay == null) return;
        for (StickerOverlay existing : list) {
            if (existing.estimatedPosition && overlay.estimatedPosition
                    && safeEquals(existing.resourceUrl, overlay.resourceUrl)
                    && safeEquals(existing.text, overlay.text)) return;
            if (safeEquals(existing.resourceUrl, overlay.resourceUrl)
                    && safeEquals(existing.text, overlay.text)
                    && Math.abs(existing.centerX - overlay.centerX) < 0.001f
                    && Math.abs(existing.centerY - overlay.centerY) < 0.001f) return;
        }
        list.add(overlay);
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int firstInteger(JSONObject object, int fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try { return Integer.parseInt((String) value); } catch (Exception ignored) { }
            }
        }
        return fallback;
    }

    private static double firstDouble(JSONObject object, double fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) {
                try { return Double.parseDouble((String) value); } catch (Exception ignored) { }
            }
        }
        return fallback;
    }

    private static String chooseCleanImageUrl(JSONArray urls) {
        if (urls == null || urls.length() == 0) return null;
        // 从后向前兼容旧 API（历史上最后一个常为原始 JPEG），同时过滤明显水印地址。
        for (int i = urls.length() - 1; i >= 0; i--) {
            String candidate = normalizeMediaUrl(urls.optString(i, null));
            if (candidate != null && isAllowedMediaUrl(candidate) && !looksWatermarkedImageUrl(candidate)) return candidate;
        }
        // url_list 自身通常是无水印候选；若没有明显干净项，仍保留可用地址。
        return chooseAnyImageUrl(urls, true);
    }

    private static String chooseAnyImageUrl(JSONArray urls, boolean fromEnd) {
        if (urls == null || urls.length() == 0) return null;
        if (fromEnd) {
            for (int i = urls.length() - 1; i >= 0; i--) {
                String candidate = normalizeMediaUrl(urls.optString(i, null));
                if (candidate != null && isAllowedMediaUrl(candidate)) return candidate;
            }
        } else {
            for (int i = 0; i < urls.length(); i++) {
                String candidate = normalizeMediaUrl(urls.optString(i, null));
                if (candidate != null && isAllowedMediaUrl(candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean looksWatermarkedImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("watermark=") || lower.contains("logo_name=") ||
                lower.contains("/mps/logo/") || lower.contains("watermark/1") ||
                lower.contains("watermark=1");
    }

    private static String extractCoverUrl(JSONObject data) {
        if (data == null) return null;
        JSONObject video = data.optJSONObject("video");
        if (video == null) return null;
        JSONObject cover = firstObject(video, "origin_cover", "originCover", "cover");
        if (cover == null) return null;
        JSONArray urls = firstArray(cover, "url_list", "urlList");
        if (urls == null || urls.length() == 0) return null;
        String url = normalizeMediaUrl(urls.optString(urls.length() - 1, null));
        return url != null && isAllowedMediaUrl(url) ? url : null;
    }

    private static String extractLiveVideoUrl(JSONObject image) {
        JSONObject video = image.optJSONObject("video");
        if (video != null) {
            String url = getPlayUrl(video);
            if (url != null && isAllowedMediaUrl(url)) return url;
        }

        String[] fields = {
                "animated_url_list", "gif_url_list", "live_url_list", "motion_url_list"
        };
        for (String field : fields) {
            JSONArray urls = image.optJSONArray(field);
            if (urls != null && urls.length() > 0) {
                String url = normalizeMediaUrl(urls.optString(0, null));
                if (url != null && isAllowedMediaUrl(url)) return url;
            }
        }
        String direct = firstString(image, "animated_url", "gif_url", "live_url", "motion_url");
        direct = normalizeMediaUrl(direct);
        return direct != null && isAllowedMediaUrl(direct) ? direct : null;
    }

    private static String getPlayUrl(JSONObject video) {
        try {
            // 官方详情接口的码率列表通常是真正的播放源，优先取最高码率。
            JSONArray bitRate = firstArray(video, "bit_rate", "bitRate", "bitRateList");
            if (bitRate != null && bitRate.length() > 0) {
                String bestUrl = null;
                long bestRate = -1;
                for (int i = 0; i < bitRate.length(); i++) {
                    JSONObject br = bitRate.optJSONObject(i);
                    if (br == null) continue;
                    // 跳过 H.265/HEVC：码率数字有时比 H.264 还高，纯按码率挑会选到它，
                    // 而不少机型硬解不了 H.265，存进相册后表现为"文件打不开"。
                    if (br.optInt("is_h265", 0) != 0 || br.optInt("isH265", 0) != 0) continue;
                    JSONObject pa = firstObject(br, "play_addr", "playAddr");
                    if (pa != null) {
                        JSONArray urlList = firstArray(pa, "url_list", "urlList");
                        if (urlList != null && urlList.length() > 0) {
                            long rate = Math.max(br.optLong("bit_rate", 0), br.optLong("bitRate", 0));
                            if (bestUrl == null || rate > bestRate) {
                                bestUrl = normalizePlayUrl(urlList.getString(0));
                                bestRate = rate;
                            }
                        }
                        String uri = firstString(pa, "uri", "video_id", "videoId");
                        if (bestUrl == null && uri != null && !uri.isEmpty()) bestUrl = buildPlayUrl(uri);
                    } else {
                        JSONArray playAddrs = firstArray(br, "playAddr");
                        if (playAddrs != null) {
                            for (int j = 0; j < playAddrs.length(); j++) {
                                JSONObject item = playAddrs.optJSONObject(j);
                                if (item != null) {
                                    String src = item.optString("src", null);
                                    if (src != null && !src.isEmpty()) return normalizePlayUrl(src);
                                }
                            }
                        }
                    }
                }
                if (bestUrl != null) return bestUrl;
            }

            // 分享页可能返回 snake_case 或 camelCase，两种都兼容。
            JSONObject playAddr = firstObject(video, "play_addr", "playAddr");
            if (playAddr != null) {
                JSONArray urlList = firstArray(playAddr, "url_list", "urlList");
                if (urlList != null && urlList.length() > 0) {
                    return normalizePlayUrl(urlList.getString(0));
                }
                String uri = firstString(playAddr, "uri", "video_id", "videoId");
                if (uri != null && !uri.isEmpty()) {
                    return buildPlayUrl(uri);
                }
            }

            // 部分实况数据把 playAddr 表示成 [{src: ...}] 数组。
            JSONArray playAddrItems = firstArray(video, "playAddr");
            if (playAddrItems != null) {
                String fallback = null;
                for (int i = 0; i < playAddrItems.length(); i++) {
                    JSONObject item = playAddrItems.optJSONObject(i);
                    if (item == null) continue;
                    String src = normalizePlayUrl(item.optString("src", null));
                    if (src == null) continue;
                    if (src.contains("v3-web")) return src;
                    if (fallback == null || src.contains("v26-web")) fallback = src;
                }
                if (fallback != null) return fallback;
            }

            JSONObject downloadAddr = firstObject(video, "download_addr", "downloadAddr");
            if (downloadAddr != null) {
                JSONArray urls = firstArray(downloadAddr, "url_list", "urlList");
                if (urls != null && urls.length() > 0) return normalizePlayUrl(urls.optString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "获取播放地址失败", e);
        }
        return null;
    }

    private static JSONObject firstObject(JSONObject json, String... keys) {
        for (String key : keys) {
            JSONObject value = json.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static JSONArray firstArray(JSONObject json, String... keys) {
        for (String key : keys) {
            JSONArray value = json.optJSONArray(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, null);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static String normalizePlayUrl(String url) {
        String normalized = normalizeMediaUrl(url);
        if (normalized == null) return null;
        return normalized.replace("/playwm/", "/play/")
                .replace("playwm", "play");
    }

    private static String normalizeMediaUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String normalized = url.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");
        if (normalized.startsWith("http://")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        return normalized;
    }

    private static String buildPlayUrl(String videoId) {
        try {
            return "https://aweme.snssdk.com/aweme/v1/play/?video_id=" +
                    java.net.URLEncoder.encode(videoId, "UTF-8") + "&ratio=1080p&line=0";
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 递归搜索JSON中的视频地址
     */
    private static String findVideoUrlRecursive(Object obj) {
        if (obj instanceof JSONObject) {
            JSONObject json = (JSONObject) obj;
            // 检查是否有播放地址
            if (json.has("play_addr") || json.has("playAddr") || json.has("bit_rate") || json.has("bitRate")) {
                String url = getPlayUrl(json);
                if (url != null) return url;
            }
            // 遍历所有key
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    String result = findVideoUrlRecursive(json.get(key));
                    if (result != null) return result;
                } catch (Exception ignored) {}
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    String result = findVideoUrlRecursive(arr.get(i));
                    if (result != null) return result;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String findTitleRecursive(Object obj) {
        if (obj instanceof JSONObject) {
            JSONObject json = (JSONObject) obj;
            if (json.has("video") || json.has("aweme_id") || json.has("awemeId")) {
                String title = firstString(json, "desc", "title");
                if (title != null) return title;
                JSONObject author = json.optJSONObject("author");
                if (author != null) {
                    String nickname = firstString(author, "nickname", "name");
                    if (nickname != null) return nickname;
                }
            }
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                try {
                    String title = findTitleRecursive(json.get(keys.next()));
                    if (title != null) return title;
                } catch (Exception ignored) {
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    String title = findTitleRecursive(arr.get(i));
                    if (title != null) return title;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 正则直接匹配视频地址
     */
    private static ParseResult extractByRegex(String html) {
        try {
            // 匹配无水印视频地址
            Pattern pattern = Pattern.compile("https://[^\"'\\s]+?/play/[^\"'\\s]+?\\.mp4[^\"'\\s]*");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String url = matcher.group(0).replace("playwm", "play");
                return new ParseResult(url, "抖音视频_" + System.currentTimeMillis(), null,
                        Collections.<String>emptyList(), Collections.<String>emptyList(),
                        Collections.<String>emptyList());
            }

            // 匹配任意视频地址
            pattern = Pattern.compile("https://[^\"'\\s]+?douyinpic[^\"'\\s]+?\\.mp4[^\"'\\s]*");
            matcher = pattern.matcher(html);
            if (matcher.find()) {
                String url = matcher.group(0).replace("playwm", "play");
                return new ParseResult(url, "抖音视频_" + System.currentTimeMillis(), null,
                        Collections.<String>emptyList(), Collections.<String>emptyList(),
                        Collections.<String>emptyList());
            }
        } catch (Exception e) {
            Log.w(TAG, "正则匹配失败", e);
        }
        return null;
    }

    private static void postError(Handler handler, final ParseCallback callback, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(msg);
            }
        });
    }

    public static byte[] downloadImageBytes(String imageUrl, DownloadCallback callback) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            downloadMediaToStream(imageUrl, output, 0, MAX_IMAGE_BYTES, callback, null, MEDIA_REFERER);
            return output.toByteArray();
        } catch (Exception e) {
            if (callback != null) callback.onError("下载异常: " + e.getMessage());
            return null;
        }
    }

/**
     * 将视频直接从网络写入目标流。maxBytes=0 表示不限制总大小，避免长视频先占用整块内存。
     *
     * <p><b>Referer 是必需的</b>（2026-10 实测）：抖音 CDN 现在校验 Referer，
     * 不带就回 <b>403</b>（带了就206 正常）。这与「UA 缺失」是两件事，
     * 实测无 UA 但有 Referer 也能下，所以关键是 Referer 本身。
     * 之前这里没传 referer，抖音下载整体挂掉而视频号正常——因为视频号那条
     * 显式传了 {@code MEDIA_REFERER}。
     */
public static long downloadVideoToStream(String videoUrl, OutputStream output,
                                             DownloadCallback callback) throws Exception {
        return downloadMediaToStream(videoUrl, output, 1, 0, callback, null, MEDIA_REFERER);
    }

    public static long downloadAudioToStream(String audioUrl, OutputStream output,
                                             DownloadCallback callback) throws Exception {
        return downloadMediaToStream(audioUrl, output, 2, 0, callback, null, MEDIA_REFERER);
    }

    /**
     * 从外部平台 CDN 直存视频（当前用于微信视频号 finder.video.qq.com）。
     * 白名单必须由调用方给出，保持"只允许已知 CDN"的安全边界。
     */
    public static long downloadExternalVideoToStream(String videoUrl, OutputStream output,
                                                    String[] allowedDomains, String referer,
                                                    DownloadCallback callback) throws Exception {
        return downloadMediaToStream(videoUrl, output, 1, 0, callback, allowedDomains, referer);
    }

    /** 下载任意允许域名的字节（当前用于视频号封面图）。 */
    public static byte[] downloadExternalImageBytes(String imageUrl, String[] allowedDomains,
                                                   String referer, DownloadCallback callback) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            downloadMediaToStream(imageUrl, output, 0, MAX_IMAGE_BYTES, callback,
                    allowedDomains, referer);
            return output.toByteArray();
        } catch (Exception e) {
            if (callback != null) callback.onError("下载异常: " + e.getMessage());
            return null;
        }
    }

    private static long downloadMediaToStream(String mediaUrl, OutputStream output,
                                              int mediaKind, long maxBytes,
                                              DownloadCallback callback) throws Exception {
        return downloadMediaToStream(mediaUrl, output, mediaKind, maxBytes, callback,
                null, "https://www.douyin.com/");
    }

    /**
     * 允许额外域名的下载。视频号视频来自 finder.video.qq.com，不在抖音 CDN 白名单内，
     * 需要由调用方显式传入白名单与 Referer——仍然只接受 https，且必须命中白名单，
     * 不会退化成"任意地址都能下"。
     */
    private static long downloadMediaToStream(String mediaUrl, OutputStream output,
                                              int mediaKind, long maxBytes,
                                              DownloadCallback callback,
                                              String[] extraDomains, String referer) throws Exception {
        if (output == null) throw new IllegalArgumentException("目标输出流为空");
        String currentUrl = mediaUrl;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            if (!isAllowedMediaUrl(currentUrl) &&
                    (extraDomains == null || !isAllowedHttpsUrl(currentUrl, extraDomains))) {
                throw new SecurityException("媒体地址不在允许列表中");
            }

            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA_MOBILE);
            boolean image = mediaKind == 0;
            boolean audio = mediaKind == 2;
            conn.setRequestProperty("Accept", image
                    ? "image/*,application/octet-stream;q=0.8"
                    : (audio ? "audio/*,video/mp4,application/octet-stream;q=0.8"
                    : "video/*,application/octet-stream;q=0.8"));
            // Referer 为 null 时也要给：抖音 CDN 缺 Referer 会回 403（实测）
            conn.setRequestProperty("Referer",
                    TextUtils.isEmpty(referer) ? MEDIA_REFERER : referer);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int responseCode = conn.getResponseCode();
            if (isRedirect(responseCode)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("下载重定向缺少目标地址");
                }
                currentUrl = new URL(url, location).toString();
                continue;
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new Exception("下载失败，响应码: " + responseCode);
            }

            String contentType = conn.getContentType();
            if (contentType != null) {
                String normalizedType = contentType.toLowerCase(java.util.Locale.ROOT);
                boolean accepted = image
                        ? normalizedType.startsWith("image/")
                        : (audio ? (normalizedType.startsWith("audio/") || normalizedType.startsWith("video/mp4"))
                        : normalizedType.startsWith("video/"));
                if (!accepted &&
                        !normalizedType.startsWith("application/octet-stream") &&
                        !normalizedType.startsWith("binary/octet-stream")) {
                    conn.disconnect();
                    throw new SecurityException(image
                            ? "服务器返回的不是图片文件"
                            : (audio ? "服务器返回的不是音频文件" : "服务器返回的不是视频文件"));
                }
            }

            long contentLength = conn.getContentLengthLong();
            if (maxBytes > 0 && contentLength > maxBytes) {
                conn.disconnect();
                throw new Exception("图片超过 30 MB 安全限制");
            }

            try (InputStream input = conn.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                long totalRead = 0;
                int lastPercent = -1;
                while ((bytesRead = input.read(buffer)) != -1) {
                    totalRead += bytesRead;
                    if (maxBytes > 0 && totalRead > maxBytes) {
                        throw new Exception("图片超过 30 MB 安全限制");
                    }
                    output.write(buffer, 0, bytesRead);
                    if (callback != null && contentLength > 0) {
                        int percent = (int) Math.min(100L, totalRead * 100L / contentLength);
                        if (percent != lastPercent) {
                            callback.onProgress(percent);
                            lastPercent = percent;
                        }
                    }
                }
                output.flush();
                if (callback != null) callback.onSuccess(null);
                return totalRead;
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("下载重定向次数过多");
    }
}
