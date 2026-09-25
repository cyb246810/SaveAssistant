package com.zgtools.videosaver;

import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频号解析所需的「登录态」凭据。
 *
 * <p>视频号的视频地址只在带登录态时下发，应用无法自己去登录，因此由用户从浏览器里
 * 复制一次请求（cURL 或 Cookie）贴进来。这里负责把用户粘贴的任意形式归一化成一组
 * 请求头，并以 JSON 形式持久化（由调用方存进私有 SharedPreferences）。
 *
 * <p>只做归一化与校验，不发起任何网络请求，也不会上传任何内容。
 */
final class ChannelCredential {

    /** 目标站点：腾讯元宝。视频号解析的第 3 步走它的接口。 */
    static final String YUANBAO_HOST = "yuanbao.tencent.com";

    /** 这些是浏览器自己会带、我们不需要回放的噪声头。 */
    private static final String[] NOISE_HEADERS = {
            "host", "connection", "content-length", "content-type", "accept-encoding",
            "priority", "accept", "accept-language", "origin", "referer", "user-agent",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "cookie2"
    };

    /** 判定"看起来像凭据"的门槛：必须有 cookie 或是 cURL 形态。 */
    private static final Pattern CURL_HEADER = Pattern.compile("-H\\s+'([^:']+):\\s*([^']*)'");
    private static final Pattern CURL_HEADER_DQ = Pattern.compile("-H\\s+\"([^\":]+):\\s*([^\"]*)\"");
    private static final Pattern CURL_COOKIE = Pattern.compile("(?:^|\\s)(?:-b|--cookie)\\s+'([^']*)'");
    private static final Pattern CURL_COOKIE_DQ = Pattern.compile("(?:^|\\s)(?:-b|--cookie)\\s+\"([^\"]*)\"");
    private static final Pattern COOKIE_LINE = Pattern.compile("^\\s*cookie\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private ChannelCredential() {}

    /**
     * 把用户粘贴的内容解析成请求头表。解析不出 cookie 时返回空表。
     *
     * <p>支持三种形态：
     * <ol>
     *   <li>浏览器「复制为 cURL」的整段命令（最常见，信息最全）</li>
     *   <li>`Cookie: xxx` 一行</li>
     *   <li>直接就是 cookie 的 `k=v; k=v` 值</li>
     * </ol>
     */
    static Map<String, String> parse(String rawInput) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (TextUtils.isEmpty(rawInput)) return headers;
        String raw = rawInput.trim();

        // 形态 1：cURL。单引号优先（Chrome/Edge 的 bash 格式），再兜双引号与无引号。
        collect(headers, CURL_HEADER, raw);
        collect(headers, CURL_HEADER_DQ, raw);
        if (headers.isEmpty()) {
            for (String line : raw.split("\\r?\\n")) {
                Matcher m = Pattern.compile("^\\s*-H\\s+([A-Za-z0-9_\\-]+):\\s*(.+?)\\s*\\\\?$")
                        .matcher(line);
                if (m.find()) {
                    put(headers, m.group(1), stripQuotes(m.group(2)));
                }
            }
        }

        // cookie 可能以 -b / --cookie 出现，也可能在 -H 'cookie: ...' 里（上面已收）
        if (!headers.containsKey("cookie")) {
            String ck = firstGroup(CURL_COOKIE, raw);
            if (ck == null) ck = firstGroup(CURL_COOKIE_DQ, raw);
            if (ck != null) put(headers, "cookie", ck);
        }

        // 形态 2：Cookie: 一行
        if (!headers.containsKey("cookie")) {
            Matcher m = COOKIE_LINE.matcher(raw);
            if (m.find()) put(headers, "cookie", m.group(1));
        }

        // 形态 3：直接粘 cookie 值
        if (!headers.containsKey("cookie")
                && raw.contains("=") && !raw.contains("\n") && raw.length() > 20) {
            put(headers, "cookie", raw);
        }

        // 丢掉噪声头，保持回放最小化
        for (String noise : NOISE_HEADERS) {
            headers.remove(noise);
        }
        return headers;
    }

    /** 凭据是否可用于视频号解析（必须有 cookie，且 cookie 里得有登录票据）。 */
    static boolean isUsable(Map<String, String> headers) {
        String cookie = headers.get("cookie");
        if (TextUtils.isEmpty(cookie)) return false;
        return cookie.contains("hy_user=") && cookie.contains("hy_token=");
    }

    /** 给用户看的诊断信息：缺什么就说缺什么。 */
    static String diagnose(Map<String, String> headers) {
        String cookie = headers.get("cookie");
        if (TextUtils.isEmpty(cookie)) {
            return "没找到 Cookie。请点「怎么获取」按步骤复制一次完整请求。";
        }
        boolean user = cookie.contains("hy_user=");
        boolean token = cookie.contains("hy_token=");
        if (!user || !token) {
            return "Cookie 不完整（缺少 " + (!user ? "hy_user" : "hy_token")
                    + "）。请确认是登录元宝后、从 yuanbao.tencent.com 的 api 请求上复制的。";
        }
        return null;
    }

    /** 请求头表 -> JSON，用于持久化。 */
    static String toJson(Map<String, String> headers) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }
        } catch (Exception ignored) {
        }
        return json.toString();
    }

    /** JSON -> 请求头表。 */
    static Map<String, String> fromJson(String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (TextUtils.isEmpty(json)) return headers;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key.toLowerCase(Locale.ROOT), obj.optString(key, ""));
            }
        } catch (Exception ignored) {
        }
        return headers;
    }

    private static void collect(Map<String, String> target, Pattern pattern, String raw) {
        Matcher m = pattern.matcher(raw);
        while (m.find()) {
            put(target, m.group(1), m.group(2));
        }
    }

    private static String firstGroup(Pattern pattern, String raw) {
        Matcher m = pattern.matcher(raw);
        if (!m.find()) return null;
        // cURL 里 cookie 值可能换行，折叠空白
        return m.group(1).replaceAll("\\s+", " ").trim();
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (TextUtils.isEmpty(key)) return;
        String k = key.trim().toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) return;
        target.put(k, v);
    }

    private static String stripQuotes(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("\\")) t = t.substring(0, t.length() - 1).trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                t = t.substring(1, t.length() - 1);
            }
        }
        return t;
    }
}
