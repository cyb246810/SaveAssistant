package com.zgtools.videosaver;

/**
 * 内置的视频号登录态 —— <b>公开仓库里的占位实现，不含任何真实凭据</b>。
 *
 * <p>视频号（微信视频号）的视频地址只在登录态下下发。本类原本用于内置一份「出厂默认」
 * 凭据，让用户装完即可使用视频号。**公开发布版必须留空**，否则等于把账号会话票据公开。
 *
 * <p>真实使用时有两种方式：
 * <ol>
 *   <li>用户自己在应用内粘贴一次（推荐，凭据只存在设备本地的 SharedPreferences）；</li>
 *   <li>自行构建私有版本时，把 {@link #JSON} 替换成自己抓取的请求头 JSON。</li>
 * </ol>
 *
 * <p>{@link ChannelCredential#isUsable} 要求 cookie 同时含 {@code hy_user=} 与
 * {@code hy_token=}，因此这里的空值会被判定为「不可用」，应用会正常提示用户去粘贴，
 * 不会崩，也不影响抖音相关功能。
 */
public final class BuiltInChannelCredential {

    /** 请求头 JSON，键名与 {@link ChannelCredential#fromJson} 期望的一致。公开版故意留空。 */
    public static final String JSON = "{}";

    private BuiltInChannelCredential() {
    }
}
