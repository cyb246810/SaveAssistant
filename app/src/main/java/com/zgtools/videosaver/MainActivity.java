package com.zgtools.videosaver;

import android.animation.AnimatorSet;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.util.Log;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils.TruncateAt;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.DialogInterface;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private EditText etLink;
    private Button btnParse;
    private Button btnDownload;
    private Button btnStitch;
    private Button btnSaveMusic;
    private TextView tvStatus;
    private TextView tvTitle;
    private TextView tvPhotoCount;
    private ProgressBar progressBar;
    private LinearLayout photoSelectionPanel;
    private LinearLayout photoCheckboxContainer;
    private CheckBox cbIncludeStickers;
    private CheckBox cbBurnTitle;
    /** 勾上才把封面做进视频。默认不勾——重编码整段视频很慢，不该强制。 */
    private CheckBox cbApplyCover;
    private LinearLayout usageCard;
    private ScrollView mainScroll;
    private TextView tvUsageBody;
    private Button btnUsageExpand;
    private boolean usageExpanded = false;
    private ValueAnimator usageHeightAnimator;
    private ViewTreeObserver.OnPreDrawListener usageBottomAnchorListener;
    private int usageAnchoredBottomOnScreen;
    private boolean usageAnchorReleasePending;
    private LinearLayout recentHistoryList;
    private FrameLayout recentHistoryExpandedClip;
    private LinearLayout recentHistoryExpandedList;
    private FrameLayout recentHistoryPeek;
    private LinearLayout recentHistoryPeekContent;
    private TextView tvRecentCount;
    private TextView tvRecentEmpty;
    private TextView tvRecentToggle;
    private boolean recentHistoryExpanded = false;
    private ValueAnimator recentCollapseAnimator;

    // 顶部模式切换（保存 / 传输）
    private TextView tabModeSave;
    private TextView tabModeTransfer;
    private View modeIndicator;
    private LinearLayout pageSave;
    private LinearLayout pageTransfer;
    private boolean transferMode = false;

    // 电脑传输（局域网）
    private EditText etPcAddr;
    private TextView tvPcStatus;
    private LinearLayout transferList;
    private TextView tvTransferEmpty;
    private TextView tvTransferProgress;
    private ProgressBar transferProgressBar;
    private Button btnTransferStart;
    /** 地址输入框 + 查找/连接按钮 + 说明：连上后整组收起，只留一行状态小字。 */
    private LinearLayout pcConnectControls;
    private Button btnTransferExpand;
    private volatile String pcHost = null;
    private volatile int pcPort = PcTransferClient.DEFAULT_PORT;
    private boolean pcConnected = false;
    private boolean isTransferring = false;
    private WifiManager.MulticastLock transferMulticastLock;
    private final List<TransferItem> transferItems = new ArrayList<>();
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor();

    private static final class TransferItem {
        final Uri uri;
        final String name;
        long size;
        final String category;
        /** 默认不勾选：列表可能很长，之前默认全选会让人误传一整个相册。 */
        boolean checked = false;

        TransferItem(Uri uri, String name, long size, String category) {
            this.uri = uri;
            this.name = name == null ? "未命名" : name;
            this.size = size;
            this.category = category;
        }
    }

    /** 折叠状态下渲染的条目数（其余收进「展开全部」）。 */
    private static final int TRANSFER_COLLAPSED_COUNT = 5;
    private boolean transferExpanded = false;

    private String currentVideoUrl = null;
    private String currentAwemeId = null;
    private String currentTitle = null;
    private String currentMusicUrl = null;
    private String currentMusicTitle = null;
    /** 当前解析结果是否来自微信视频号（视频号与抖音的 CDN、可用功能都不同）。 */
    private boolean currentIsChannels = false;
    private String currentChannelVideoUrl = null;
    /** 当前作品的封面直链（抖音与视频号都有）。平台上的封面是独立素材，随视频一并保存。 */
    private String currentCoverUrl = null;
    private String currentChannelAuthor = null;
    private String currentChannelDescription = null;
    /** 视频号登录态，来自用户粘贴的浏览器请求头；为空时视频号只能解析到封面。 */
    private java.util.Map<String, String> channelCredential = null;
    private final List<String> currentImageUrls = new ArrayList<>();
    private final List<String> currentImagePreviewUrls = new ArrayList<>();
    private final List<String> currentLiveVideoUrls = new ArrayList<>();
    private final List<String> currentStickerCompositeUrls = new ArrayList<>();
    private final List<List<DouyinParser.StickerOverlay>> currentImageStickerOverlays = new ArrayList<>();
    private final List<CheckBox> photoCheckBoxes = new ArrayList<>();
    private String lastAutoParsedText = null;
    private boolean isParsing = false;
    private boolean isDownloading = false;
    private AnimatorSet downloadRevealAnimator;
    private AnimatorSet stitchRevealAnimator;
    private AnimatorSet musicRevealAnimator;
    private int previewGeneration = 0;
    private int recentThumbnailGeneration = 0;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(3);
    // 保存到相册的子目录名（Android 10+ 显示为相册名）
    private static final String ALBUM_DIR = "保存助手";
    private static final String SAVED_MEDIA_PREFS = "saved_media_v1";
    private static final String CHANNEL_CREDENTIAL_PREFS = "channel_credential_v1";
    private static final String CHANNEL_CREDENTIAL_KEY = "headers_json";
    private static final String RECENT_HISTORY_PREFS = "recent_history_v1";
    private static final String RECENT_HISTORY_JSON = "records";
    private static final String RECENT_HISTORY_MIGRATED = "migrated_saved_media_v2_month";
    private static final long RECENT_HISTORY_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int RECENT_COLLAPSED_VISIBLE_COUNT = 2;

    private static final class RecentRecord {
        final String mediaKey;
        final String uri;
        final String title;
        final String typeLabel;
        final String mimeType;
        final long savedAt;

        RecentRecord(String mediaKey, String uri, String title, String typeLabel,
                     String mimeType, long savedAt) {
            this.mediaKey = mediaKey == null ? "" : mediaKey;
            this.uri = uri == null ? "" : uri;
            this.title = title == null ? "已保存内容" : title;
            this.typeLabel = typeLabel == null ? "媒体" : typeLabel;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.savedAt = savedAt;
        }
    }

    private static final class MediaMetadata {
        final String displayName;
        final String mimeType;
        final long savedAt;

        MediaMetadata(String displayName, String mimeType, long savedAt) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.savedAt = savedAt;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etLink = findViewById(R.id.et_link);
        btnParse = findViewById(R.id.btn_parse);
        btnDownload = findViewById(R.id.btn_download);
        btnStitch = findViewById(R.id.btn_stitch);
        btnSaveMusic = findViewById(R.id.btn_save_music);
        Button btnPaste = findViewById(R.id.btn_paste);
        Button btnClear = findViewById(R.id.btn_clear);
        tvStatus = findViewById(R.id.tv_status);
        tvTitle = findViewById(R.id.tv_title);
        tvPhotoCount = findViewById(R.id.tv_photo_count);
        progressBar = findViewById(R.id.progress_bar);
        photoSelectionPanel = findViewById(R.id.photo_selection_panel);
        photoCheckboxContainer = findViewById(R.id.photo_checkbox_container);
        cbIncludeStickers = findViewById(R.id.cb_include_stickers);
        cbBurnTitle = findViewById(R.id.cb_burn_title);
        cbApplyCover = findViewById(R.id.cb_apply_cover);
        mainScroll = findViewById(R.id.main_scroll);
        usageCard = findViewById(R.id.usage_card);
        tvUsageBody = findViewById(R.id.tv_usage_body);
        btnUsageExpand = findViewById(R.id.btn_usage_expand);
        recentHistoryList = findViewById(R.id.recent_history_list);
        recentHistoryExpandedClip = findViewById(R.id.recent_history_expanded_clip);
        recentHistoryExpandedList = findViewById(R.id.recent_history_expanded_list);
        recentHistoryPeek = findViewById(R.id.recent_history_peek);
        recentHistoryPeekContent = findViewById(R.id.recent_history_peek_content);
        tvRecentCount = findViewById(R.id.tv_recent_count);
        tvRecentEmpty = findViewById(R.id.tv_recent_empty);
        tvRecentToggle = findViewById(R.id.tv_recent_toggle);
        Button btnSelectAll = findViewById(R.id.btn_select_all);
        Button btnSelectNone = findViewById(R.id.btn_select_none);
        tabModeSave = findViewById(R.id.tab_save);
        tabModeTransfer = findViewById(R.id.tab_transfer);
        modeIndicator = findViewById(R.id.mode_indicator);
        pageSave = findViewById(R.id.page_save);
        pageTransfer = findViewById(R.id.page_transfer);

        installPremiumButtonMotion(btnDownload);
        installPremiumButtonMotion(btnStitch);
        installPremiumButtonMotion(btnSaveMusic);
        btnParse.setElevation(dp(8));
        btnDownload.setElevation(dp(1));
        btnStitch.setElevation(dp(1));
        btnSaveMusic.setElevation(dp(1));
        hideDownloadButton();
        hideStitchButton();
        hideSaveMusicButton();

        btnPaste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteLatestClipboard(); }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearCurrentInput(); }
        });

        btnParse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parseVideo(etLink.getText().toString().trim());
            }
        });

        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCurrent();
            }
        });

        btnStitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prepareStitchOptions();
            }
        });

        btnSaveMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadMusic();
            }
        });

        btnUsageExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleUsageSection();
            }
        });

        tvRecentToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecentHistorySection();
            }
        });

        btnSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllPhotosChecked(true);
            }
        });

        btnSelectNone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllPhotosChecked(false);
            }
        });

        setupModeSwitch();
        setupTransferPage();

        migrateExistingSavedMediaHistory();
        loadChannelCredential();
        renderRecentHistory();
    }

    // ------------------------------------------------------------------ 电脑传输（局域网）

    private void setupTransferPage() {
        etPcAddr = findViewById(R.id.et_pc_addr);
        tvPcStatus = findViewById(R.id.tv_pc_status);
        transferList = findViewById(R.id.transfer_list);
        tvTransferEmpty = findViewById(R.id.tv_transfer_empty);
        tvTransferProgress = findViewById(R.id.tv_transfer_progress);
        transferProgressBar = findViewById(R.id.transfer_progress);
        btnTransferStart = findViewById(R.id.btn_transfer_start);
        pcConnectControls = findViewById(R.id.pc_connect_controls);
        btnTransferExpand = findViewById(R.id.btn_transfer_expand);

        btnTransferExpand.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                transferExpanded = !transferExpanded;
                renderTransferList();
            }
        });

        findViewById(R.id.btn_pc_find).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { findPc(); }
        });
        findViewById(R.id.btn_pc_connect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connectPc(etPcAddr.getText().toString().trim());
            }
        });
        findViewById(R.id.btn_transfer_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshTransferList(); }
        });
        findViewById(R.id.btn_transfer_all).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAllTransferChecked(true); }
        });
        findViewById(R.id.btn_transfer_none).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAllTransferChecked(false); }
        });
        btnTransferStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTransfer(); }
        });

        transferList.post(new Runnable() {
            @Override public void run() { refreshTransferList(); }
        });
    }

    /** UDP 广播查找电脑，找到后自动连接。 */
    private void findPc() {
        tvPcStatus.setText(R.string.transfer_connecting);
        transferExecutor.execute(new Runnable() {
            @Override public void run() {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiManager.MulticastLock lock = null;
                try {
                    if (wifi != null) {
                        lock = wifi.createMulticastLock("SaveAssistantDiscovery");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                    final java.util.List<String[]> found = PcTransferClient.discover(pcPort, 2800);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (found == null || found.isEmpty()) {
                                tvPcStatus.setText("未发现电脑，请检查电脑端已启动、地址正确，且手机与电脑允许互相访问");
                                return;
                            }
                            String[] first = found.get(0);
                            String label = (first[1] == null || first[1].isEmpty()) ? first[0] : first[1];
                            etPcAddr.setText(first[0] + ":" + pcPort);
                            tvPcStatus.setText(getString(R.string.transfer_found_fmt, label + "（" + first[0] + "）"));
                            connectPc(first[0] + ":" + pcPort);
                        }
                    });
                } finally {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                }
            }
        });
    }

    /** 按 "IP" 或 "IP:端口" 连接电脑并联调配对。 */
    private void connectPc(final String addr) {
        final String cleaned = addr == null ? "" : addr.trim();
        if (cleaned.isEmpty()) {
            tvPcStatus.setText(R.string.transfer_not_found);
            return;
        }
        tvPcStatus.setText(R.string.transfer_connecting);
        transferExecutor.execute(new Runnable() {
            @Override public void run() {
                final String[] hp = parseHostPort(cleaned);
                try {
                    if (hp[0].isEmpty() || hp[0].contains(" ") || hp[0].contains("http://")
                            || hp[0].contains("https://")) {
                        throw new IllegalArgumentException("请输入电脑 IPv4 地址，可带端口，例如 192.168.1.20:18765");
                    }
                    int parsedPort = Integer.parseInt(hp[1]);
                    if (parsedPort < 1 || parsedPort > 65535) {
                        throw new IllegalArgumentException("端口范围应为 1-65535");
                    }
                    PcTransferClient.ping(hp[0], parsedPort, 4000);
                    final PcTransferClient.PcInfo info = PcTransferClient.announce(
                            hp[0], parsedPort, Build.MODEL,
                            Build.MANUFACTURER, localIpForDisplay(), 4000);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pcHost = hp[0];
                            pcPort = parsedPort;
                            pcConnected = true;
                            String label = info.deviceName.isEmpty() ? hp[0] : info.deviceName;
                            tvPcStatus.setText(getString(R.string.transfer_connected_fmt,
                                    label + "（" + hp[0] + "）"));
                            applyPcConnectionUi();
                            refreshTransferList();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pcConnected = false;
                            tvPcStatus.setText(getString(R.string.transfer_connect_failed_fmt,
                                    String.valueOf(e.getMessage())));
                            applyPcConnectionUi();
                        }
                    });
                }
            }
        });
    }

    /**
     * 连上后把「地址输入 + 查找 / 连接按钮 + 说明」整组收起，只留一行状态小字；
     * 断开或连接失败时再放出来。
     */
    private void applyPcConnectionUi() {
        if (pcConnectControls == null) return;
        pcConnectControls.setVisibility(pcConnected ? View.GONE : View.VISIBLE);
    }

    private static String[] parseHostPort(String s) {
        String host = s;
        int port = PcTransferClient.DEFAULT_PORT;
        int i = s.lastIndexOf(':');
        if (i > 0 && i < s.length() - 1) {
            String p = s.substring(i + 1);
            boolean allDigits = !p.isEmpty();
            for (int k = 0; k < p.length(); k++) {
                if (!Character.isDigit(p.charAt(k))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                host = s.substring(0, i);
                port = Integer.parseInt(p);
            }
        }
        return new String[]{host, String.valueOf(port)};
    }

    /** 取本机局域网 IPv4，仅用于告诉电脑"我是谁"。 */
    private static String localIpForDisplay() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface nif = ifs.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // 忽略，回退设备型号
        }
        return Build.MODEL;
    }

    private void refreshTransferList() {
        if (transferItems == null) return;
        transferItems.clear();
        transferExpanded = false;
        String[] proj = {"_id", "display_name", "_size", "mime_type"};
        collectMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, "video");
        collectMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "image");
        collectMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "audio");
        if (transferItems.isEmpty()) {
            // 兜底：应用自己的最近保存记录
            ArrayList<RecentRecord> records = getValidRecentRecords();
            for (RecentRecord r : records) {
                if (r == null || TextUtils.isEmpty(r.uri)) continue;
                String mime = r.mimeType == null ? "" : r.mimeType;
                String cat = mime.startsWith("video") ? "video"
                        : (mime.startsWith("audio") ? "audio" : "image");
                transferItems.add(new TransferItem(Uri.parse(r.uri),
                        ensureExtension(r.title, mime, cat), 0, cat));
            }
        }
        renderTransferList();
    }

    private void collectMedia(Uri base, String[] proj, String category) {
        Cursor c = null;
        try {
            c = getContentResolver().query(base, proj, null, null, "date_added DESC");
            if (c == null) return;
            int idIdx = c.getColumnIndex("_id");
            int nameIdx = c.getColumnIndex("display_name");
            int sizeIdx = c.getColumnIndex("_size");
            int mimeIdx = c.getColumnIndex("mime_type");
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                Uri u = ContentUris.withAppendedId(base, id);
                String name = nameIdx >= 0 ? c.getString(nameIdx) : ("media_" + id);
                String mime = mimeIdx >= 0 ? c.getString(mimeIdx) : null;
                long size = sizeIdx >= 0 ? c.getLong(sizeIdx) : 0;
                transferItems.add(new TransferItem(u, ensureExtension(name, mime, category),
                        size, category));
            }
        } catch (Exception e) {
            // 无媒体或无权限：忽略，走兜底
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 保证文件名带扩展名。
     *
     * <p>相册里不是所有媒体的 {@code display_name} 都带后缀（实测有一个抖音视频的
     * display_name 就是纯标题），传到电脑后会变成一个没有后缀、双击打不开的文件。
     * 这里按 MIME 补，拿不到 MIME 再按类别兜底。
     */
    private static String ensureExtension(String name, String mime, String category) {
        String n = (name == null || name.trim().isEmpty()) ? "未命名" : name.trim();
        int dot = n.lastIndexOf('.');
        int slash = n.lastIndexOf('/');
        // 点必须在最后一段里，且后面还有字符，才算「已有扩展名」
        if (dot > slash && dot < n.length() - 1 && dot != 0) {
            return n;
        }
        return n + extensionFor(mime, category);
    }

    private static String extensionFor(String mime, String category) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT).trim();
        if (m.equals("audio/mpeg") || m.equals("audio/mp3")) return ".mp3";
        // audio/mp4 的正确后缀是 .m4a，必须放在下面那条通用 "mp4" 规则之前，否则会被截走
        if (m.startsWith("audio/") && m.contains("mp4")) return ".m4a";
        if (m.contains("mp4")) return ".mp4";
        if (m.contains("matroska")) return ".mkv";
        if (m.contains("webm")) return ".webm";
        if (m.contains("3gpp")) return ".3gp";
        if (m.contains("quicktime")) return ".mov";
        if (m.contains("heic") || m.contains("heif")) return ".heic";
        if (m.contains("jpeg")) return ".jpg";
        if (m.contains("png")) return ".png";
        if (m.contains("gif")) return ".gif";
        if (m.contains("webp")) return ".webp";
        if (m.contains("bmp")) return ".bmp";
        if (m.contains("m4a")) return ".m4a";
        if (m.contains("aac")) return ".aac";
        if (m.contains("flac")) return ".flac";
        if (m.contains("wav")) return ".wav";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("mp3")) return ".mp3";
        switch (category == null ? "" : category) {
            case "video":
                return ".mp4";
            case "image":
                return ".jpg";
            case "audio":
                return ".m4a";
            default:
                return "";
        }
    }

    private void renderTransferList() {
        transferList.removeAllViews();
        tvTransferEmpty.setVisibility(transferItems.isEmpty() ? View.VISIBLE : View.GONE);

        final int total = transferItems.size();
        // 默认只渲染最新几项（列表按 date_added 倒序），其余折叠起来
        final int visibleCount = transferExpanded ? total : Math.min(total, TRANSFER_COLLAPSED_COUNT);

        for (int i = 0; i < visibleCount; i++) {
            final TransferItem item = transferItems.get(i);
            CheckBox cb = new CheckBox(this);
            String label = item.name;
            if (item.size > 0) label = label + "  ·  " + fmtSize(item.size);
            cb.setText(label);
            cb.setTextSize(13);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
            cb.setChecked(item.checked);
            cb.setPadding(dp(2), dp(6), dp(2), dp(6));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    item.checked = v;
                }
            });
            transferList.addView(cb);
        }

        if (total > TRANSFER_COLLAPSED_COUNT) {
            btnTransferExpand.setVisibility(View.VISIBLE);
            btnTransferExpand.setText(transferExpanded
                    ? getString(R.string.transfer_collapse_fmt, TRANSFER_COLLAPSED_COUNT)
                    : getString(R.string.transfer_expand_fmt, total));
        } else {
            btnTransferExpand.setVisibility(View.GONE);
        }
    }

    private void setAllTransferChecked(boolean checked) {
        for (TransferItem t : transferItems) t.checked = checked;
        renderTransferList();
    }

    private void startTransfer() {
        if (isTransferring) return;
        if (!pcConnected || pcHost == null) {
            Toast.makeText(this, R.string.transfer_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<TransferItem> selected = new ArrayList<>();
        for (TransferItem t : transferItems) if (t.checked) selected.add(t);
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.transfer_selected_none, Toast.LENGTH_SHORT).show();
            return;
        }

        isTransferring = true;
        transferProgressBar.setVisibility(View.VISIBLE);
        transferProgressBar.setProgress(0);
        tvTransferProgress.setVisibility(View.VISIBLE);

        transferExecutor.execute(new Runnable() {
            @Override public void run() {
                int ok = 0;
                int fail = 0;
                final int total = selected.size();
                for (int i = 0; i < total; i++) {
                    final TransferItem item = selected.get(i);
                    final int ordinal = i + 1;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tvTransferProgress.setText(getString(R.string.transfer_sending_fmt,
                                    item.name, ordinal, total));
                        }
                    });
                    java.io.InputStream in = null;
                    try {
                        long size = item.size;
                        if (size <= 0) {
                            size = queryMediaSize(item.uri);
                            item.size = size;
                        }
                        final long totalBytes = size;
                        in = getContentResolver().openInputStream(item.uri);
                        if (in == null) throw new Exception("无法读取文件");
                        PcTransferClient.upload(pcHost, pcPort, in, size, item.name,
                                item.category, Build.MODEL, 6000, 120000,
                                new PcTransferClient.Progress() {
                                    @Override public void onProgress(long sent, long all) {
                                        final int pct = totalBytes > 0
                                                ? (int) (sent * 100 / totalBytes) : 0;
                                        runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                transferProgressBar.setProgress(pct);
                                            }
                                        });
                                    }
                                });
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Exception ignored) { }
                        }
                    }
                }
                final int okFinal = ok;
                final int failFinal = fail;
                // 有失败时确认电脑是否还在线：真断了就把连接区放回来，让用户重连
                boolean lost = false;
                if (fail > 0 && pcHost != null) {
                    try {
                        PcTransferClient.ping(pcHost, pcPort, 3000);
                    } catch (Exception pe) {
                        lost = true;
                    }
                }
                final boolean lostFinal = lost;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        isTransferring = false;
                        transferProgressBar.setVisibility(View.GONE);
                        if (lostFinal) {
                            pcConnected = false;
                            tvPcStatus.setText(R.string.transfer_disconnected);
                        }
                        applyPcConnectionUi();
                        String msg = getString(R.string.transfer_all_done_fmt, okFinal, failFinal);
                        tvTransferProgress.setText(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private long queryMediaSize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"_size"}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            // 忽略
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private static String fmtSize(long n) {
        if (n <= 0) return "";
        if (n < 1024) return n + " B";
        if (n < 1048576) return (n / 1024) + " KB";
        if (n < 1073741824L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    // ------------------------------------------------------------------ 顶部模式切换（保存 / 传输）

    private void setupModeSwitch() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override public void onClick(View v) {
                setTransferMode(v == tabModeTransfer, true);
            }
        };
        tabModeSave.setOnClickListener(listener);
        tabModeTransfer.setOnClickListener(listener);
        // 首次布局完成后再对齐横线，避免测量宽度为 0
        tabModeSave.post(new Runnable() {
            @Override public void run() {
                setTransferMode(transferMode, false);
            }
        });
    }

    /** 切换「保存 / 传输」两个模式：选中文字变白、另一个变灰，横线滑动，页面显隐。 */
    private void setTransferMode(boolean transfer, boolean animate) {
        transferMode = transfer;
        int active = getColor(R.color.mode_active);
        int inactive = getColor(R.color.mode_inactive);
        tabModeSave.setTextColor(transfer ? inactive : active);
        tabModeTransfer.setTextColor(transfer ? active : inactive);
        pageSave.setVisibility(transfer ? View.GONE : View.VISIBLE);
        pageTransfer.setVisibility(transfer ? View.VISIBLE : View.GONE);
        // 切到传输页时同步一次连接区显隐（已连接就只留状态小字）
        if (transfer) {
            applyPcConnectionUi();
        }

        View target = transfer ? tabModeTransfer : tabModeSave;
        int w = target.getWidth();
        ViewGroup.LayoutParams lp = modeIndicator.getLayoutParams();
        if (w > 0 && lp.width != w) {
            lp.width = w;
            modeIndicator.setLayoutParams(lp);
        }
        float tx = target.getLeft();
        modeIndicator.animate().cancel();
        if (animate) {
            modeIndicator.animate().translationX(tx).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            modeIndicator.setTranslationX(tx);
        }
    }

    // ------------------------------------------------------------------ 视频号登录态

    /**
     * 读取本机保存的视频号登录态。
     *
     * <p>视频号视频地址只在登录态下下发，而应用无法代替用户登录，因此由用户从浏览器里
     * 复制一次请求（cURL 或 Cookie）贴进输入框，这里负责把它持久化到应用私有目录。
     * 凭据只用于元宝那一个接口，不会发往其它地方。
     */
    private void loadChannelCredential() {
        String json = getSharedPreferences(CHANNEL_CREDENTIAL_PREFS, MODE_PRIVATE)
                .getString(CHANNEL_CREDENTIAL_KEY, null);
        if (TextUtils.isEmpty(json)) {
            // 出厂默认值：内置一份凭据，装完即可直接用视频号，用户不必自己抓。
            // 用户之后在输入框里粘贴的新凭据会写进 SharedPreferences 覆盖它。
            json = BuiltInChannelCredential.JSON;
        }
        java.util.Map<String, String> parsed = ChannelCredential.fromJson(json);
        channelCredential = ChannelCredential.isUsable(parsed) ? parsed : null;
    }

    /** 判断粘进来的是不是浏览器请求（而不是作品链接）。 */
    private boolean looksLikeChannelCredential(String text) {
        if (TextUtils.isEmpty(text)) return false;
        if (text.contains("-H '") || text.contains("-H \"") || text.contains("--cookie")
                || text.contains("-b '")) {
            return true;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("curl ") || lower.contains("\ncurl ")) {
            return true;
        }
        // 一行式的 Cookie
        return lower.contains("hy_user=") && lower.contains("hy_token=");
    }

    /** 保存视频号登录态，并当场跑一次接口校验，避免"看起来存好了其实已失效"。 */
    private void saveChannelCredential(String raw) {
        final java.util.Map<String, String> parsed = ChannelCredential.parse(raw);
        final String problem = ChannelCredential.diagnose(parsed);
        if (problem != null) {
            setStatus(problem, R.color.color_error);
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            return;
        }

        isParsing = true;
        btnParse.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        setStatus("正在校验视频号登录态…", R.color.text_secondary);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String failure = WeixinChannelsParser.verifyCredential(parsed);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isParsing = false;
                        btnParse.setEnabled(true);
                        progressBar.setVisibility(View.GONE);
                        if (failure != null) {
                            setStatus("校验没通过：" + failure, R.color.color_error);
                            Toast.makeText(MainActivity.this,
                                    "校验没通过：" + failure, Toast.LENGTH_LONG).show();
                            return;
                        }
                        getSharedPreferences(CHANNEL_CREDENTIAL_PREFS, MODE_PRIVATE)
                                .edit()
                                .putString(CHANNEL_CREDENTIAL_KEY, ChannelCredential.toJson(parsed))
                                .apply();
                        channelCredential = parsed;
                        etLink.setText("");
                        lastAutoParsedText = null;
                        String message = "视频号登录态已保存，现在可以直接粘贴视频号链接解析原片";
                        setStatus(message, R.color.color_success);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "sph-credential").start();
    }

    /** 视频号作品解析。与抖音共用同一套结果区控件，不新增界面元素。 */
    private void parseChannels(String link) {
        isParsing = true;
        btnParse.setEnabled(false);
        hideDownloadButton();
        hideBurnTitleOption();
        hideApplyCoverOption();
        clearPhotoSelection();
        hideStitchButton();
        hideSaveMusicButton();
        currentIsChannels = true;
        currentVideoUrl = null;
        currentAwemeId = null;
        currentTitle = null;
        currentMusicUrl = null;
        currentMusicTitle = null;
        currentChannelVideoUrl = null;
        currentCoverUrl = null;
        currentChannelAuthor = null;
        currentChannelDescription = null;
        btnDownload.setText(R.string.btn_save);
        tvTitle.setText("");
        setStatus("正在解析视频号作品…", R.color.text_secondary);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        WeixinChannelsParser.parse(link, channelCredential,
                new WeixinChannelsParser.ParseCallback() {
            @Override
            public void onSuccess(WeixinChannelsParser.ParseResult result) {
                isParsing = false;
                btnParse.setEnabled(true);
                progressBar.setVisibility(View.GONE);

                currentChannelVideoUrl = result.videoUrl;
                currentCoverUrl = result.coverUrl;
                currentChannelAuthor = result.author;
                currentChannelDescription = result.description;
                // 去重键用 exportId，与抖音的 awemeId 同位
                currentAwemeId = !TextUtils.isEmpty(result.exportId)
                        ? result.exportId : result.shareId;
                currentVideoUrl = result.videoUrl;
                String shortTitle = channelShortTitle(result.description, result.author);
                currentTitle = shortTitle;

                String stats = channelStatsLine(result);
                if (!TextUtils.isEmpty(result.author)) {
                    tvTitle.setText(TextUtils.isEmpty(stats)
                            ? result.author
                            : result.author + " · " + stats);
                } else {
                    tvTitle.setText("");
                }

                btnDownload.setText(R.string.btn_save_video);
                // 视频号有标题就能烧标题，复用抖音那套开关
                if (!TextUtils.isEmpty(shortTitle)) showBurnTitleOption();
                if (!TextUtils.isEmpty(result.coverUrl)) showApplyCoverOption();
                showDownloadButtonAnimated();
                // 视频号没有独立音乐资源、单条作品也无需拼接，所以这两项始终不出现
                hideStitchButton();
                hideSaveMusicButton();

                String message = "视频号解析成功！" + (TextUtils.isEmpty(result.author)
                        ? "" : "@" + result.author + " ") + "可保存原片";
                setStatus(message, R.color.color_success);
                Toast.makeText(MainActivity.this, "视频号解析成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                isParsing = false;
                btnParse.setEnabled(true);
                hideDownloadButton();
                hideStitchButton();
                hideBurnTitleOption();
                hideApplyCoverOption();
                hideSaveMusicButton();
                progressBar.setVisibility(View.GONE);
                setStatus("解析失败：" + message, R.color.color_error);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 视频号文案动辄上百字，取第一行前 40 字当作品标题，用于文件名与烧录。 */
    private static String channelShortTitle(String description, String author) {
        if (TextUtils.isEmpty(description)) {
            return TextUtils.isEmpty(author) ? "视频号作品" : author;
        }
        String firstLine = description.split("\\r?\\n")[0].trim();
        if (firstLine.isEmpty()) firstLine = description.trim();
        if (firstLine.length() > 40) firstLine = firstLine.substring(0, 40);
        return firstLine;
    }

    private static String channelStatsLine(WeixinChannelsParser.ParseResult result) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(result.likeCount)) sb.append("赞 ").append(result.likeCount);
        if (!TextUtils.isEmpty(result.favCount)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("藏 ").append(result.favCount);
        }
        if (!TextUtils.isEmpty(result.commentCount)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("评 ").append(result.commentCount);
        }
        return sb.toString();
    }

    /** 封面下载：抖音封面在抖音图床，视频号封面在 finder.video.qq.com，各自走白名单。 */
    private byte[] downloadCoverBytes(String coverUrl) {
        if (TextUtils.isEmpty(coverUrl)) return null;
        if (currentIsChannels) {
            return DouyinParser.downloadExternalImageBytes(coverUrl,
                    WeixinChannelsParser.MEDIA_DOMAINS,
                    WeixinChannelsParser.MEDIA_REFERER, silentCallback());
        }
        return DouyinParser.downloadImageBytes(coverUrl, silentCallback());
    }

    /** 抖音与视频号的视频 CDN 不同，按当前平台选下载通道（两者都走 https 白名单）。 */
    private long downloadVideoForCurrentPlatform(String videoUrl, java.io.OutputStream output,
                                                 DouyinParser.DownloadCallback callback)
            throws Exception {
        if (currentIsChannels) {
            return DouyinParser.downloadExternalVideoToStream(videoUrl, output,
                    WeixinChannelsParser.MEDIA_DOMAINS,
                    WeixinChannelsParser.MEDIA_REFERER, callback);
        }
        return DouyinParser.downloadVideoToStream(videoUrl, output, callback);
    }

    private void pasteLatestClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板里没有可粘贴的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (value == null || value.toString().trim().isEmpty()) {
            Toast.makeText(this, "剪贴板里没有可粘贴的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = value.toString().trim();
        etLink.setText(text);
        etLink.setSelection(text.length());
    }

    private void clearCurrentInput() {
        etLink.setText("");
        lastAutoParsedText = null;
        currentVideoUrl = null;
        currentAwemeId = null;
        currentTitle = null;
        currentMusicUrl = null;
        currentMusicTitle = null;
        currentIsChannels = false;
        currentChannelVideoUrl = null;
        currentCoverUrl = null;
        currentChannelAuthor = null;
        currentChannelDescription = null;
        clearPhotoSelection();
        hideDownloadButton();
        hideStitchButton();
        hideBurnTitleOption();
        hideApplyCoverOption();
        hideSaveMusicButton();
        btnDownload.setText(R.string.btn_save);
        tvTitle.setText("");
        setStatus(getString(R.string.status_idle), R.color.text_secondary);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android 10+ 只允许前台应用读取剪贴板。稍等界面进入前台后再读取，
        // 同时也覆盖从抖音复制链接后切回本应用的场景。
        if (etLink != null) {
            etLink.postDelayed(new Runnable() {
                @Override
                public void run() {
                    autoParseClipboard();
                }
            }, 250);
        }
    }

    private void autoParseClipboard() {
        if (isParsing || isDownloading) return;
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;

        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty() || !looksLikeShareText(text) || text.equals(lastAutoParsedText)) return;

        lastAutoParsedText = text;
        etLink.setText(text);
        etLink.setSelection(text.length());
        parseVideo(text);
    }

    private boolean looksLikeDouyinShare(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("douyin.com/") || lower.matches("\\d{15,25}");
    }

    /** 自动解析只认作品分享文本；浏览器请求（登录态）必须由用户主动点解析才处理。 */
    private boolean looksLikeShareText(String text) {
        return looksLikeDouyinShare(text) || WeixinChannelsParser.isChannelsShare(text);
    }

    private void parseVideo(String link) {
        if (TextUtils.isEmpty(link)) {
            Toast.makeText(this, "请先粘贴分享链接", Toast.LENGTH_SHORT).show();
            return;
        }

        // 粘进来的是浏览器里的请求（cURL / Cookie）而不是作品链接时，存成视频号登录态
        if (looksLikeChannelCredential(link)) {
            saveChannelCredential(link);
            return;
        }
        // 视频号分享链接走独立链路（其余一律按抖音处理）
        if (WeixinChannelsParser.isChannelsShare(link)) {
            parseChannels(link);
            return;
        }

        currentIsChannels = false;
        isParsing = true;
        btnParse.setEnabled(false);
        hideDownloadButton();
        hideBurnTitleOption();
        hideApplyCoverOption();
        currentVideoUrl = null;
        currentAwemeId = null;
        currentTitle = null;
        currentMusicUrl = null;
        currentMusicTitle = null;
        clearPhotoSelection();
        hideStitchButton();
        hideSaveMusicButton();
        btnDownload.setText(R.string.btn_save);
        tvTitle.setText("");
        setStatus("正在解析…", R.color.text_secondary);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        DouyinParser.parse(link, new DouyinParser.ParseCallback() {
            @Override
            public void onSuccess(DouyinParser.ParseResult result) {
                currentVideoUrl = result.videoUrl;
                currentAwemeId = result.awemeId;
                currentTitle = result.title;
                currentMusicUrl = result.musicUrl;
                currentMusicTitle = result.musicTitle;
                // 抖音侧的 origin_cover 早已解析出来，这里接上，保存视频时一并存封面
                currentCoverUrl = result.coverUrl;
                currentImageUrls.clear();
                currentImageUrls.addAll(result.imageUrls);
                currentImagePreviewUrls.clear();
                currentImagePreviewUrls.addAll(result.imagePreviewUrls);
                currentLiveVideoUrls.clear();
                currentLiveVideoUrls.addAll(result.liveVideoUrls);
                currentStickerCompositeUrls.clear();
                currentStickerCompositeUrls.addAll(result.stickerCompositeUrls);
                currentImageStickerOverlays.clear();
                currentImageStickerOverlays.addAll(result.imageStickerOverlays);
                isParsing = false;
                btnParse.setEnabled(true);
                btnDownload.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                tvTitle.setText(getString(R.string.title_format, result.title));
                if (result.isImagePost()) {
                    showPhotoSelection(result.imageUrls.size(), result.liveVideoUrls,
                            result.imagePreviewUrls, result.hasStickers(),
                            result.hasEstimatedStickerLayout());
                    btnDownload.setText(R.string.btn_save_media);
                    boolean singleStillWithMusic = result.imageUrls.size() == 1
                            && result.getLivePhotoCount() == 0
                            && !TextUtils.isEmpty(currentMusicUrl);
                    if (result.imageUrls.size() > 1) {
                        btnStitch.setText(R.string.btn_stitch_video);
                        showStitchButton();
                    } else if (singleStillWithMusic) {
                        btnStitch.setText(R.string.btn_save_as_video);
                        showStitchButton();
                    } else {
                        hideStitchButton();
                    }
                    if (result.getLivePhotoCount() > 0) {
                        setStatus(TextUtils.isEmpty(currentMusicUrl)
                                ? "解析成功！实况图将保存为一条动态视频"
                                : "解析成功！已识别原作品音乐，可在拼接视频中加入", R.color.color_success);
                        Toast.makeText(MainActivity.this,
                                "解析到 " + result.imageUrls.size() + " 项，其中 " +
                                        result.getLivePhotoCount() + " 项实况", Toast.LENGTH_SHORT).show();
                    } else {
                        setStatus(TextUtils.isEmpty(currentMusicUrl)
                                ? "解析成功！请选择要保存的照片"
                                : (result.imageUrls.size() == 1
                                    ? "解析成功！已识别原作品音乐，可保存为视频"
                                    : "解析成功！已识别原作品音乐，可在拼接视频中加入"), R.color.color_success);
                        Toast.makeText(MainActivity.this,
                                "解析到 " + result.imageUrls.size() + " 张照片", Toast.LENGTH_SHORT).show();
                    }
                    if (result.hasEstimatedStickerLayout()) {
                        setStatus("解析成功！已找到贴纸内容；平台未给坐标，将按内容区兼容排版",
                                R.color.color_success);
                    }
                } else {
                    hideStitchButton();
                    btnDownload.setText(R.string.btn_save_video);
                    // 只有解析到标题的视频作品才提供“把标题加进视频”选项
                    if (!TextUtils.isEmpty(result.title)) showBurnTitleOption();
                    // 拿到封面才提供“把封面做进视频”选项
                    if (!TextUtils.isEmpty(result.coverUrl)) showApplyCoverOption();
                    setStatus("解析成功！点击下方按钮保存视频", R.color.color_success);
                    Toast.makeText(MainActivity.this, "视频解析成功", Toast.LENGTH_SHORT).show();
                }
                showDownloadButtonAnimated();
                if (btnStitch.getVisibility() == View.VISIBLE) showStitchButtonAnimated();
                // 解析到作品音乐时提供“保存完整音乐”（原文件直存，不重编码）
                if (!TextUtils.isEmpty(currentMusicUrl)) {
                    showSaveMusicButtonAnimated();
                } else {
                    hideSaveMusicButton();
                }
            }

            @Override
            public void onError(String message) {
                isParsing = false;
                btnParse.setEnabled(true);
                hideDownloadButton();
                hideStitchButton();
                hideBurnTitleOption();
                hideApplyCoverOption();
                hideSaveMusicButton();
                progressBar.setVisibility(View.GONE);
                setStatus("解析失败：" + message, R.color.color_error);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 显示“把标题加进视频画面”选项，每次解析成功后从默认未勾选开始。 */
    private void showBurnTitleOption() {
        if (cbBurnTitle == null) return;
        cbBurnTitle.setChecked(false);
        cbBurnTitle.setVisibility(View.VISIBLE);
    }

    private void hideBurnTitleOption() {
        if (cbBurnTitle == null) return;
        cbBurnTitle.setChecked(false);
        cbBurnTitle.setVisibility(View.GONE);
    }

    /** 显示「把封面做进视频」选项，每次解析成功后从默认未勾选开始。 */
    private void showApplyCoverOption() {
        if (cbApplyCover == null) return;
        cbApplyCover.setChecked(false);
        cbApplyCover.setVisibility(View.VISIBLE);
    }

    private void hideApplyCoverOption() {
        if (cbApplyCover == null) return;
        cbApplyCover.setChecked(false);
        cbApplyCover.setVisibility(View.GONE);
    }

    private void clearPhotoSelection() {
        currentImageUrls.clear();
        currentImagePreviewUrls.clear();
        currentLiveVideoUrls.clear();
        currentStickerCompositeUrls.clear();
        currentImageStickerOverlays.clear();
        previewGeneration++;
        photoCheckBoxes.clear();
        if (photoCheckboxContainer != null) photoCheckboxContainer.removeAllViews();
        if (cbIncludeStickers != null) {
            cbIncludeStickers.setChecked(true);
            cbIncludeStickers.setVisibility(View.GONE);
        }
        if (photoSelectionPanel != null) photoSelectionPanel.setVisibility(View.GONE);
    }

    private void showPhotoSelection(int count, List<String> liveVideoUrls, List<String> previewUrls,
                                    boolean hasStickers, boolean estimatedStickerLayout) {
        photoCheckboxContainer.removeAllViews();
        photoCheckBoxes.clear();
        tvPhotoCount.setText(getString(R.string.photo_count_format, count));
        cbIncludeStickers.setChecked(true);
        cbIncludeStickers.setText(estimatedStickerLayout
                ? R.string.include_stickers_compatible : R.string.include_stickers);
        cbIncludeStickers.setVisibility(hasStickers ? View.VISIBLE : View.GONE);
        for (int i = 0; i < count; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 5, 0, 5);
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackgroundResource(R.drawable.bg_input);
            preview.setContentDescription("照片 " + (i + 1) + " 预览");
            row.addView(preview, new LinearLayout.LayoutParams(dp(72), dp(72)));
            CheckBox checkBox = new CheckBox(this);
            String liveUrl = i < liveVideoUrls.size() ? liveVideoUrls.get(i) : null;
            checkBox.setText(getString(liveUrl == null || liveUrl.isEmpty()
                    ? R.string.photo_item_format : R.string.live_photo_item_format, i + 1));
            checkBox.setTextColor(getColor(R.color.text_primary));
            checkBox.setTextSize(14);
            checkBox.setChecked(true);
            checkBox.setPadding(4, 4, 4, 4);
            LinearLayout.LayoutParams checkLayout = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            checkLayout.setMargins(dp(8), 0, 0, 0);
            row.addView(checkBox, checkLayout);
            photoCheckboxContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            photoCheckBoxes.add(checkBox);
            String previewUrl = i < previewUrls.size() ? previewUrls.get(i) : null;
            preloadPreview(preview, previewUrl, previewGeneration);
        }
        photoSelectionPanel.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private TextView createPill(String text, int textSizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(textSizeSp);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(40));
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void applyDuplicateChoiceStyle(TextView skip, TextView overwrite, boolean overwriteSelected) {
        styleChoicePill(skip, !overwriteSelected, false);
        styleChoicePill(overwrite, overwriteSelected, true);
    }

    private void styleChoicePill(TextView pill, boolean selected, boolean accent) {
        int selectedFill = accent ? getColor(R.color.primary) : Color.rgb(45, 47, 52);
        int selectedStroke = accent ? getColor(R.color.primary) : Color.rgb(82, 85, 92);
        int fill = selected ? selectedFill : Color.rgb(23, 24, 27);
        int stroke = selected ? selectedStroke : Color.rgb(54, 56, 62);
        pill.setTextColor(selected ? Color.WHITE : Color.rgb(222, 224, 228));
        pill.setBackground(roundedBackground(fill, stroke, 12));
        pill.setAlpha(selected ? 1f : 0.92f);
    }

    private LinearLayout createDialogSurface() {
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(20), dp(20), dp(20), dp(18));
        surface.setBackground(roundedBackground(Color.rgb(14, 15, 17), Color.rgb(48, 50, 56), 22));
        return surface;
    }

    private TextView createDialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(6));
        return title;
    }

    private Button createDialogButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : getColor(R.color.text_primary));
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private void showModernDialog(Dialog dialog, View surface) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            int available = getResources().getDisplayMetrics().widthPixels - dp(28);
            params.width = Math.min(available, dp(480));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            params.dimAmount = 0.64f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        surface.setAlpha(0f);
        surface.setTranslationY(dp(12));
        surface.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(2.1f))
                .start();
    }

    private LinearLayout createToggleSettingRow(String label, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackground(roundedBackground(Color.rgb(20, 21, 24), Color.rgb(48, 50, 56), 13));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(getColor(R.color.text_primary));
        labelView.setTextSize(14);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = createPill(enabled ? "已开启" : "已关闭", 12);
        chip.setMinHeight(dp(34));
        chip.setClickable(false);
        chip.setFocusable(false);
        styleToggleChip(chip, enabled);
        row.addView(chip, new LinearLayout.LayoutParams(dp(72), dp(34)));
        row.setTag(chip);
        row.setClickable(true);
        return row;
    }

    private void styleToggleChip(TextView chip, boolean enabled) {
        chip.setText(enabled ? "已开启" : "已关闭");
        chip.setTextColor(enabled ? Color.WHITE : Color.rgb(208, 210, 214));
        chip.setBackground(roundedBackground(
                enabled ? getColor(R.color.primary) : Color.rgb(32, 33, 37),
                enabled ? getColor(R.color.primary) : Color.rgb(61, 63, 69), 11));
    }

    private void hideStitchButton() {
        if (btnStitch == null) return;
        if (stitchRevealAnimator != null) {
            stitchRevealAnimator.cancel();
            stitchRevealAnimator = null;
        }
        btnStitch.animate().cancel();
        btnStitch.setVisibility(View.GONE);
        btnStitch.setEnabled(false);
        resetActionTransform(btnStitch);
    }

    private void showStitchButton() {
        if (btnStitch == null) return;
        btnStitch.setVisibility(View.VISIBLE);
        btnStitch.setEnabled(true);
        btnStitch.setAlpha(0f);
    }

    private void hideDownloadButton() {
        if (btnDownload == null) return;
        if (downloadRevealAnimator != null) {
            downloadRevealAnimator.cancel();
            downloadRevealAnimator = null;
        }
        btnDownload.animate().cancel();
        btnDownload.setEnabled(false);
        btnDownload.setVisibility(View.GONE);
        resetActionTransform(btnDownload);
    }

    private void resetActionTransform(View view) {
        view.setAlpha(1f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private AnimatorSet buildPremiumReveal(final Button button, long delayMs) {
        button.animate().cancel();
        button.setVisibility(View.VISIBLE);
        button.setEnabled(true);

        // 从“解析作品”按钮后方向下滑出；全程单向运动，接近终点自然减速，不再回弹。
        float hiddenTop = btnParse.getBottom() - dp(38);
        float startY = hiddenTop - button.getTop();
        if (startY > -dp(52)) startY = -dp(52);

        button.setAlpha(0.12f);
        button.setTranslationY(startY);
        button.setScaleX(0.975f);
        button.setScaleY(0.975f);

        ObjectAnimator slide = ObjectAnimator.ofFloat(button, View.TRANSLATION_Y, startY, 0f);
        ObjectAnimator fade = ObjectAnimator.ofFloat(button, View.ALPHA, 0.12f, 1f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(button, View.SCALE_X, 0.975f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(button, View.SCALE_Y, 0.975f, 1f);

        AnimatorSet reveal = new AnimatorSet();
        reveal.playTogether(slide, fade, scaleX, scaleY);
        reveal.setDuration(390L);
        reveal.setStartDelay(delayMs);
        // 和“使用说明”折叠/展开保持同类的平滑减速手感：前段清晰移动，末段缓慢停稳。
        reveal.setInterpolator(new PathInterpolator(0.18f, 0.72f, 0.22f, 1f));
        return reveal;
    }

    private void showDownloadButtonAnimated() {
        if (downloadRevealAnimator != null) downloadRevealAnimator.cancel();
        btnDownload.setVisibility(View.VISIBLE);
        btnDownload.setEnabled(true);
        btnDownload.setAlpha(0f);
        btnDownload.post(new Runnable() {
            @Override public void run() {
                if (btnDownload.getVisibility() != View.VISIBLE) return;
                downloadRevealAnimator = buildPremiumReveal(btnDownload, 0L);
                downloadRevealAnimator.start();
            }
        });
    }

    private void showStitchButtonAnimated() {
        if (stitchRevealAnimator != null) stitchRevealAnimator.cancel();
        btnStitch.setVisibility(View.VISIBLE);
        btnStitch.setEnabled(true);
        btnStitch.setAlpha(0f);
        btnStitch.post(new Runnable() {
            @Override public void run() {
                if (btnStitch.getVisibility() != View.VISIBLE) return;
                // 第二个按钮稍微晚一点跟出来，但同样只做单向减速滑出。
                stitchRevealAnimator = buildPremiumReveal(btnStitch, 90L);
                stitchRevealAnimator.start();
            }
        });
    }

    /** 隐藏“保存完整音乐”，解析开始/失败/清空时调用。 */
    private void hideSaveMusicButton() {
        if (btnSaveMusic == null) return;
        if (musicRevealAnimator != null) {
            musicRevealAnimator.cancel();
            musicRevealAnimator = null;
        }
        btnSaveMusic.animate().cancel();
        btnSaveMusic.setVisibility(View.GONE);
        btnSaveMusic.setEnabled(false);
        resetActionTransform(btnSaveMusic);
    }

    private void showSaveMusicButtonAnimated() {
        if (musicRevealAnimator != null) musicRevealAnimator.cancel();
        btnSaveMusic.setVisibility(View.VISIBLE);
        btnSaveMusic.setEnabled(true);
        btnSaveMusic.setAlpha(0f);
        btnSaveMusic.post(new Runnable() {
            @Override public void run() {
                if (btnSaveMusic.getVisibility() != View.VISIBLE) return;
                // 排在“保存视频/拼接视频”之后，再晚一点跟出来。
                musicRevealAnimator = buildPremiumReveal(btnSaveMusic, 180L);
                musicRevealAnimator.start();
            }
        });
    }

    private void toggleUsageSection() {
        if (usageCard == null || tvUsageBody == null || btnUsageExpand == null) return;
        usageExpanded = !usageExpanded;
        animateUsageSection(usageExpanded);
        btnUsageExpand.setText(usageExpanded
                ? R.string.usage_collapse : R.string.usage_expand);
    }

    private void animateUsageSection(final boolean expanding) {
        if (usageHeightAnimator != null) usageHeightAnimator.cancel();
        stopUsageBottomAnchor();
        startUsageBottomAnchor();
        final int startBodyHeight = Math.max(1, tvUsageBody.getHeight());
        final int targetBodyHeight;
        final ViewGroup.LayoutParams bodyParams = tvUsageBody.getLayoutParams();

        if (expanding) {
            tvUsageBody.setMaxLines(Integer.MAX_VALUE);
            tvUsageBody.setEllipsize(null);
            int bodyWidth = Math.max(1, tvUsageBody.getWidth());
            tvUsageBody.measure(
                    View.MeasureSpec.makeMeasureSpec(bodyWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            targetBodyHeight = tvUsageBody.getMeasuredHeight();
        } else {
            targetBodyHeight = tvUsageBody.getLineHeight() * 2
                    + tvUsageBody.getCompoundPaddingTop()
                    + tvUsageBody.getCompoundPaddingBottom();
        }

        bodyParams.height = startBodyHeight;
        tvUsageBody.setLayoutParams(bodyParams);

        usageHeightAnimator = ValueAnimator.ofFloat(0f, 1f);
        usageHeightAnimator.setDuration(210L);
        usageHeightAnimator.setInterpolator(new PathInterpolator(0.22f, 0.72f, 0.22f, 1f));
        usageHeightAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (Float) animation.getAnimatedValue();
                bodyParams.height = Math.max(1, Math.round(startBodyHeight
                        + (targetBodyHeight - startBodyHeight) * progress));
                tvUsageBody.requestLayout();
            }
        });
        usageHeightAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled || usageExpanded != expanding) return;
                if (!expanding) {
                    tvUsageBody.setMaxLines(2);
                    tvUsageBody.setEllipsize(TruncateAt.END);
                }
                bodyParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                tvUsageBody.setLayoutParams(bodyParams);
                usageHeightAnimator = null;
                releaseUsageBottomAnchorAfterLayout();
            }
        });
        usageHeightAnimator.start();
    }

    /**
     * 展开和收起说明时锁住整张说明卡片的屏幕底边。卡片变高时 ScrollView
     * 同步向下滚动，新增内容便只会向上展开；收起时执行相反位移。
     */
    private void startUsageBottomAnchor() {
        if (mainScroll == null || usageCard == null || usageCard.getHeight() <= 0) return;
        usageAnchoredBottomOnScreen = getUsageBottomOnScreen();
        usageAnchorReleasePending = false;
        usageBottomAnchorListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (mainScroll == null || usageCard == null) return true;
                int delta = getUsageBottomOnScreen() - usageAnchoredBottomOnScreen;
                if (delta != 0) mainScroll.scrollBy(0, delta);
                if (usageAnchorReleasePending) stopUsageBottomAnchor();
                return true;
            }
        };
        mainScroll.getViewTreeObserver().addOnPreDrawListener(usageBottomAnchorListener);
    }

    private int getUsageBottomOnScreen() {
        int[] location = new int[2];
        usageCard.getLocationOnScreen(location);
        return location[1] + usageCard.getHeight();
    }

    private void releaseUsageBottomAnchorAfterLayout() {
        usageAnchorReleasePending = true;
        if (mainScroll != null) mainScroll.invalidate();
    }

    private void stopUsageBottomAnchor() {
        if (mainScroll != null && usageBottomAnchorListener != null) {
            ViewTreeObserver observer = mainScroll.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(usageBottomAnchorListener);
        }
        usageBottomAnchorListener = null;
        usageAnchorReleasePending = false;
    }

    private void toggleRecentHistorySection() {
        if (recentHistoryExpandedList == null || tvRecentToggle == null) return;
        recentHistoryExpanded = !recentHistoryExpanded;
        applyRecentHistoryExpansion(true);
    }

    private void applyRecentHistoryExpansion(boolean animate) {
        if (recentHistoryExpandedClip == null || recentHistoryExpandedList == null
                || recentHistoryPeek == null
                || tvRecentToggle == null) return;
        boolean hasHiddenRecords = recentHistoryExpandedList.getChildCount() > 0;
        if (recentCollapseAnimator != null) {
            recentCollapseAnimator.cancel();
            recentCollapseAnimator = null;
        }
        recentHistoryExpandedClip.animate().cancel();
        recentHistoryPeek.animate().cancel();
        if (!hasHiddenRecords) {
            recentHistoryExpanded = false;
            recentHistoryExpandedClip.setVisibility(View.GONE);
            recentHistoryPeek.setVisibility(View.GONE);
            tvRecentToggle.setVisibility(View.GONE);
            return;
        }

        tvRecentToggle.setVisibility(View.VISIBLE);
        tvRecentToggle.setText(recentHistoryExpanded
                ? getString(R.string.recent_collapse)
                : getString(R.string.recent_expand,
                        recentHistoryList.getChildCount() + recentHistoryExpandedList.getChildCount()));

        if (recentHistoryExpanded) {
            recentHistoryPeek.setVisibility(View.GONE);
            ViewGroup.LayoutParams peekParams = recentHistoryPeek.getLayoutParams();
            peekParams.height = dp(28);
            recentHistoryPeek.setLayoutParams(peekParams);
            ViewGroup.LayoutParams clipParams = recentHistoryExpandedClip.getLayoutParams();
            clipParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            recentHistoryExpandedClip.setLayoutParams(clipParams);
            if (recentHistoryExpandedClip.getVisibility() != View.VISIBLE) {
                recentHistoryExpandedClip.setVisibility(View.VISIBLE);
                recentHistoryExpandedClip.setAlpha(animate ? 0.18f : 1f);
                recentHistoryExpandedClip.setTranslationY(animate ? -dp(5) : 0f);
            }
            if (animate) {
                recentHistoryExpandedClip.animate().alpha(1f).translationY(0f)
                        .setDuration(170L)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            } else {
                recentHistoryExpandedClip.setAlpha(1f);
                recentHistoryExpandedClip.setTranslationY(0f);
            }
        } else if (animate && recentHistoryExpandedClip.getVisibility() == View.VISIBLE) {
            animateRecentHistoryCollapse();
        } else {
            ViewGroup.LayoutParams clipParams = recentHistoryExpandedClip.getLayoutParams();
            clipParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            recentHistoryExpandedClip.setLayoutParams(clipParams);
            recentHistoryExpandedClip.setVisibility(View.GONE);
            recentHistoryExpandedClip.setAlpha(1f);
            recentHistoryExpandedClip.setTranslationY(0f);
            showRecentHistoryPeek(animate);
        }
    }

    private void animateRecentHistoryCollapse() {
        final int startHeight = recentHistoryExpandedClip.getHeight();
        final int targetPeekHeight = dp(28);
        if (startHeight <= 0) {
            recentHistoryExpandedClip.setVisibility(View.GONE);
            showRecentHistoryPeek(false);
            return;
        }

        final ViewGroup.LayoutParams clipParams = recentHistoryExpandedClip.getLayoutParams();
        final ViewGroup.LayoutParams peekParams = recentHistoryPeek.getLayoutParams();
        clipParams.height = startHeight;
        recentHistoryExpandedClip.setLayoutParams(clipParams);
        peekParams.height = 0;
        recentHistoryPeek.setLayoutParams(peekParams);
        recentHistoryPeek.setVisibility(View.VISIBLE);
        recentHistoryPeek.setAlpha(0f);
        recentHistoryExpandedClip.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        recentCollapseAnimator = ValueAnimator.ofFloat(0f, 1f);
        recentCollapseAnimator.setDuration(210L);
        recentCollapseAnimator.setInterpolator(new PathInterpolator(0.24f, 0.72f, 0.22f, 1f));
        recentCollapseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (Float) animation.getAnimatedValue();
                clipParams.height = Math.max(0, Math.round(startHeight * (1f - progress)));
                peekParams.height = Math.max(0, Math.round(targetPeekHeight * progress));
                recentHistoryExpandedClip.setAlpha(1f - 0.58f * progress);
                recentHistoryPeek.setAlpha(progress);
                recentHistoryExpandedClip.setLayoutParams(clipParams);
                recentHistoryPeek.setLayoutParams(peekParams);
            }
        });
        recentCollapseAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator animation) {
                recentHistoryExpandedClip.setLayerType(View.LAYER_TYPE_NONE, null);
                if (cancelled || recentHistoryExpanded) return;
                clipParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                recentHistoryExpandedClip.setLayoutParams(clipParams);
                recentHistoryExpandedClip.setVisibility(View.GONE);
                recentHistoryExpandedClip.setAlpha(1f);
                recentHistoryExpandedClip.setTranslationY(0f);
                peekParams.height = targetPeekHeight;
                recentHistoryPeek.setLayoutParams(peekParams);
                recentHistoryPeek.setVisibility(View.VISIBLE);
                recentHistoryPeek.setAlpha(1f);
                recentCollapseAnimator = null;
            }
        });
        recentCollapseAnimator.start();
    }

    private void showRecentHistoryPeek(boolean animate) {
        recentHistoryPeek.animate().cancel();
        ViewGroup.LayoutParams peekParams = recentHistoryPeek.getLayoutParams();
        peekParams.height = dp(28);
        recentHistoryPeek.setLayoutParams(peekParams);
        recentHistoryPeek.setVisibility(View.VISIBLE);
        recentHistoryPeek.setTranslationY(0f);
        recentHistoryPeek.setAlpha(animate ? 0f : 1f);
        if (animate) {
            recentHistoryPeek.animate().alpha(1f).setDuration(120L).start();
        }
    }

    private ArrayList<RecentRecord> readRecentRecordsRaw() {
        ArrayList<RecentRecord> records = new ArrayList<>();
        String raw = getSharedPreferences(RECENT_HISTORY_PREFS, MODE_PRIVATE)
                .getString(RECENT_HISTORY_JSON, null);
        if (TextUtils.isEmpty(raw)) return records;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String uri = item.optString("uri", "");
                if (TextUtils.isEmpty(uri)) continue;
                records.add(new RecentRecord(
                        item.optString("key", ""),
                        uri,
                        item.optString("title", "已保存内容"),
                        item.optString("type", "媒体"),
                        item.optString("mime", ""),
                        item.optLong("time", 0L)));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    private void writeRecentRecords(List<RecentRecord> records) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < records.size(); i++) {
            RecentRecord record = records.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("key", record.mediaKey);
                item.put("uri", record.uri);
                item.put("title", record.title);
                item.put("type", record.typeLabel);
                item.put("mime", record.mimeType);
                item.put("time", record.savedAt);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        getSharedPreferences(RECENT_HISTORY_PREFS, MODE_PRIVATE)
                .edit().putString(RECENT_HISTORY_JSON, array.toString()).commit();
    }

    private ArrayList<RecentRecord> getValidRecentRecords() {
        ArrayList<RecentRecord> records = readRecentRecordsRaw();
        boolean changed = false;
        long cutoff = System.currentTimeMillis() - RECENT_HISTORY_RETENTION_MS;
        HashSet<String> seenUris = new HashSet<>();
        for (int i = 0; i < records.size();) {
            RecentRecord record = records.get(i);
            if (record.savedAt > 0 && record.savedAt < cutoff) {
                // 记录到期只从列表移除，相册文件和防重复标记都保留。
                records.remove(i);
                changed = true;
                continue;
            }
            if (TextUtils.isEmpty(record.uri) || seenUris.contains(record.uri)) {
                records.remove(i);
                changed = true;
                continue;
            }
            seenUris.add(record.uri);
            Uri uri = Uri.parse(record.uri);
            if (!mediaUriExists(uri)) {
                records.remove(i);
                removeSavedMediaReference(record);
                changed = true;
                continue;
            }
            i++;
        }
        if (changed) writeRecentRecords(records);
        return records;
    }

    private boolean mediaUriExists(Uri uri) {
        if (uri == null) return false;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private MediaMetadata queryMediaMetadata(Uri uri) {
        if (uri == null) return null;
        String[] projection = new String[]{
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED
        };
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            String displayName = nameIndex >= 0 ? cursor.getString(nameIndex) : "已保存内容";
            String mimeType = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
            long dateAdded = dateIndex >= 0 ? cursor.getLong(dateIndex) * 1000L : 0L;
            return new MediaMetadata(displayName, mimeType, dateAdded);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void migrateExistingSavedMediaHistory() {
        SharedPreferences history = getSharedPreferences(RECENT_HISTORY_PREFS, MODE_PRIVATE);
        if (history.getBoolean(RECENT_HISTORY_MIGRATED, false)) return;

        ArrayList<RecentRecord> records = getValidRecentRecords();
        HashSet<String> knownUris = new HashSet<>();
        for (RecentRecord record : records) knownUris.add(record.uri);

        Map<String, ?> savedMedia = getSharedPreferences(SAVED_MEDIA_PREFS, MODE_PRIVATE).getAll();
        long cutoff = System.currentTimeMillis() - RECENT_HISTORY_RETENTION_MS;
        for (Map.Entry<String, ?> entry : savedMedia.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String uriText = (String) entry.getValue();
            if (TextUtils.isEmpty(uriText) || knownUris.contains(uriText)) continue;
            MediaMetadata metadata = queryMediaMetadata(Uri.parse(uriText));
            if (metadata == null) continue;
            String typeLabel = historyTypeLabel(entry.getKey(), metadata.mimeType);
            long savedAt = metadata.savedAt > 0 ? metadata.savedAt : System.currentTimeMillis();
            if (savedAt < cutoff) continue;
            records.add(new RecentRecord(entry.getKey(), uriText,
                    readableDisplayName(metadata.displayName), typeLabel,
                    metadata.mimeType, savedAt));
            knownUris.add(uriText);
        }

        Collections.sort(records, new Comparator<RecentRecord>() {
            @Override
            public int compare(RecentRecord left, RecentRecord right) {
                return Long.compare(right.savedAt, left.savedAt);
            }
        });
        writeRecentRecords(records);
        history.edit().putBoolean(RECENT_HISTORY_MIGRATED, true).commit();
    }

    private String historyTypeLabel(String mediaKey, String mimeType) {
        if (!TextUtils.isEmpty(mediaKey) && mediaKey.contains(":live:")) return "实况视频";
        if (!TextUtils.isEmpty(mediaKey) && mediaKey.contains(":music:")) return "音乐";
        if (!TextUtils.isEmpty(mimeType) && mimeType.startsWith("image/")) return "照片";
        if (!TextUtils.isEmpty(mimeType) && mimeType.startsWith("audio/")) return "音乐";
        return "视频";
    }

    private String readableDisplayName(String displayName) {
        if (TextUtils.isEmpty(displayName)) return "已保存内容";
        String result = displayName.replaceFirst("\\.[^.]+$", "")
                .replaceFirst("_[0-9]{13}$", "");
        return safeRecentTitle(result, "已保存内容");
    }

    private String safeRecentTitle(String title, String fallback) {
        String value = TextUtils.isEmpty(title) ? fallback : title;
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.length() > 120) value = value.substring(0, 120) + "…";
        return TextUtils.isEmpty(value) ? "已保存内容" : value;
    }

    private synchronized void recordRecentSave(String mediaKey, Uri uri, String title,
                                               String typeLabel, String mimeType) {
        if (uri == null) return;
        String uriText = uri.toString();
        ArrayList<RecentRecord> records = readRecentRecordsRaw();
        long cutoff = System.currentTimeMillis() - RECENT_HISTORY_RETENTION_MS;
        for (int i = records.size() - 1; i >= 0; i--) {
            RecentRecord old = records.get(i);
            if ((old.savedAt > 0 && old.savedAt < cutoff)
                    || uriText.equals(old.uri) || (!TextUtils.isEmpty(mediaKey)
                    && mediaKey.equals(old.mediaKey))) {
                records.remove(i);
            }
        }
        records.add(0, new RecentRecord(mediaKey, uriText,
                safeRecentTitle(title, typeLabel), typeLabel, mimeType,
                System.currentTimeMillis()));
        writeRecentRecords(records);
        runOnUiThread(new Runnable() {
            @Override public void run() { renderRecentHistory(); }
        });
    }

    private void renderRecentHistory() {
        if (recentHistoryList == null || recentHistoryExpandedClip == null
                || recentHistoryExpandedList == null
                || recentHistoryPeek == null
                || recentHistoryPeekContent == null || tvRecentCount == null
                || tvRecentEmpty == null || tvRecentToggle == null) return;
        ArrayList<RecentRecord> records = getValidRecentRecords();
        if (records.size() <= RECENT_COLLAPSED_VISIBLE_COUNT) recentHistoryExpanded = false;
        final int thumbnailGeneration = ++recentThumbnailGeneration;
        tvRecentCount.setText(records.isEmpty()
                ? getString(R.string.recent_empty_summary)
                : getString(R.string.recent_count_format, records.size()));
        tvRecentEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        if (recentCollapseAnimator != null) {
            recentCollapseAnimator.cancel();
            recentCollapseAnimator = null;
        }
        recentHistoryExpandedClip.animate().cancel();
        recentHistoryPeek.animate().cancel();
        recentHistoryList.removeAllViews();
        recentHistoryExpandedList.removeAllViews();
        recentHistoryPeekContent.removeAllViews();

        int alwaysVisibleCount = Math.min(RECENT_COLLAPSED_VISIBLE_COUNT, records.size());
        for (int i = 0; i < alwaysVisibleCount; i++) {
            View row = createRecentHistoryRow(records.get(i), true, thumbnailGeneration);
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLayout.setMargins(0, 0, 0, dp(8));
            recentHistoryList.addView(row, rowLayout);
        }

        boolean hasHiddenRecords = records.size() > RECENT_COLLAPSED_VISIBLE_COUNT;
        if (hasHiddenRecords) {
            for (int i = RECENT_COLLAPSED_VISIBLE_COUNT; i < records.size(); i++) {
                View row = createRecentHistoryRow(records.get(i), true, thumbnailGeneration);
                LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLayout.setMargins(0, 0, 0, dp(8));
                recentHistoryExpandedList.addView(row, rowLayout);
            }
            View peekRow = createRecentHistoryRow(
                    records.get(RECENT_COLLAPSED_VISIBLE_COUNT), false, thumbnailGeneration);
            peekRow.setAlpha(0.58f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                peekRow.setRenderEffect(RenderEffect.createBlurEffect(
                        dp(3), dp(3), Shader.TileMode.CLAMP));
            }
            recentHistoryPeekContent.addView(peekRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        applyRecentHistoryExpansion(false);
    }

    private View createRecentHistoryRow(final RecentRecord record, boolean interactive,
                                        int thumbnailGeneration) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(8), dp(9));
        row.setBackground(roundedBackground(Color.rgb(20, 21, 24), Color.rgb(45, 47, 52), 14));

        FrameLayout thumbnailFrame = new FrameLayout(this);
        thumbnailFrame.setBackground(roundedBackground(
                Color.rgb(29, 30, 34), Color.rgb(54, 56, 62), 11));
        thumbnailFrame.setClipToOutline(true);

        TextView placeholder = new TextView(this);
        boolean isImage = record.mimeType.startsWith("image/")
                || "照片".equals(record.typeLabel);
        boolean isAudio = "音乐".equals(record.typeLabel)
                || record.mimeType.startsWith("audio/");
        placeholder.setText(isImage ? "图" : (isAudio ? "乐" : "影"));
        placeholder.setTextColor(Color.rgb(139, 142, 149));
        placeholder.setTextSize(13);
        placeholder.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        placeholder.setGravity(Gravity.CENTER);
        thumbnailFrame.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setContentDescription(record.typeLabel + "封面");
        thumbnailFrame.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        preloadRecentThumbnail(cover, Uri.parse(record.uri), thumbnailGeneration);
        row.addView(thumbnailFrame, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        detailsLayout.setMargins(dp(10), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(record.title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TruncateAt.END);
        details.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        meta.setText(record.typeLabel + " · " + formatRecentTime(record.savedAt));
        meta.setTextColor(getColor(R.color.text_secondary));
        meta.setTextSize(12);
        meta.setPadding(0, dp(3), 0, 0);
        details.addView(meta, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(details, detailsLayout);

        TextView open = createRecentAction("查看", getColor(R.color.accent_cyan), Color.rgb(48, 86, 90));
        LinearLayout.LayoutParams openLayout = new LinearLayout.LayoutParams(dp(42), dp(30));
        openLayout.setMargins(dp(6), 0, 0, 0);
        row.addView(open, openLayout);

        TextView manage = createRecentAction("管理", Color.rgb(232, 177, 183), Color.rgb(92, 55, 61));
        LinearLayout.LayoutParams manageLayout = new LinearLayout.LayoutParams(dp(42), dp(30));
        manageLayout.setMargins(dp(5), 0, 0, 0);
        row.addView(manage, manageLayout);

        if (interactive) {
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openRecentMedia(record); }
            });
            manage.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showRecentRecordManagement(record); }
            });
        } else {
            open.setClickable(false);
            open.setFocusable(false);
            manage.setClickable(false);
            manage.setFocusable(false);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        return row;
    }

    private void preloadRecentThumbnail(final ImageView target, final Uri uri,
                                        final int thumbnailGeneration) {
        if (target == null || uri == null) return;
        final String expectedUri = uri.toString();
        target.setTag(expectedUri);
        final int targetSize = dp(144);
        previewExecutor.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                try {
                    bitmap = getContentResolver().loadThumbnail(
                            uri, new Size(targetSize, targetSize), null);
                } catch (Exception ignored) {
                }
                final Bitmap loaded = bitmap;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (loaded == null || thumbnailGeneration != recentThumbnailGeneration
                                || !expectedUri.equals(target.getTag())) return;
                        target.setAlpha(0f);
                        target.setImageBitmap(loaded);
                        target.animate().alpha(1f).setDuration(160L).start();
                    }
                });
            }
        });
    }

    private TextView createRecentAction(String text, int textColor, int strokeColor) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(textColor);
        action.setTextSize(10);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setBackground(roundedBackground(Color.rgb(25, 26, 29), strokeColor, 9));
        action.setClickable(true);
        action.setFocusable(true);
        return action;
    }

    private String formatRecentTime(long savedAt) {
        long now = System.currentTimeMillis();
        long age = now - savedAt;
        if (savedAt <= 0 || age < 0) return "刚刚";
        if (age < 60_000L) return "刚刚";
        if (age < 3_600_000L) return (age / 60_000L) + " 分钟前";
        if (age < 86_400_000L) return (age / 3_600_000L) + " 小时前";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(savedAt));
    }

    private void openRecentMedia(RecentRecord record) {
        Uri uri = Uri.parse(record.uri);
        if (!mediaUriExists(uri)) {
            removeRecentRecordInternal(record, true);
            renderRecentHistory();
            Toast.makeText(this, "相册中已找不到这个文件，记录已移除", Toast.LENGTH_LONG).show();
            return;
        }
        String mime = !TextUtils.isEmpty(record.mimeType)
                ? record.mimeType
                : (record.typeLabel.contains("照片") ? "image/*"
                : (record.typeLabel.contains("音乐") ? "audio/*" : "video/*"));
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "手机上没有可查看此文件的应用", Toast.LENGTH_LONG).show();
        }
    }

    private void showRecentRecordManagement(final RecentRecord record) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout surface = createDialogSurface();
        surface.addView(createDialogTitle("管理保存记录"));

        TextView message = new TextView(this);
        message.setText("可以只从最近记录中移除，也可以同时删除相册文件。自动淘汰的旧记录不会删除相册文件。");
        message.setTextColor(Color.rgb(205, 207, 212));
        message.setTextSize(14);
        message.setLineSpacing(dp(2), 1.08f);
        message.setPadding(0, dp(2), 0, dp(14));
        surface.addView(message);

        Button deleteFile = createDialogButton("从相册删除", true);
        Button removeOnly = createDialogButton("仅移除这条记录", false);
        Button cancel = createDialogButton("取消", false);
        surface.addView(deleteFile, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        LinearLayout.LayoutParams secondaryLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        secondaryLayout.setMargins(0, dp(8), 0, 0);
        surface.addView(removeOnly, secondaryLayout);
        LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        cancelLayout.setMargins(0, dp(6), 0, 0);
        surface.addView(cancel, cancelLayout);

        deleteFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                deleteRecentMedia(record);
            }
        });
        removeOnly.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                removeRecentRecordInternal(record, false);
                renderRecentHistory();
                Toast.makeText(MainActivity.this, "已移除这条记录，相册文件仍保留", Toast.LENGTH_LONG).show();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        dialog.setContentView(surface);
        showModernDialog(dialog, surface);
    }

    private void deleteRecentMedia(final RecentRecord record) {
        Toast.makeText(this, "正在从相册删除…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String error = null;
                try {
                    Uri uri = Uri.parse(record.uri);
                    int deleted = getContentResolver().delete(uri, null, null);
                    success = deleted > 0 || !mediaUriExists(uri);
                    if (success) removeRecentRecordInternal(record, true);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final boolean finalSuccess = success;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        renderRecentHistory();
                        if (finalSuccess) {
                            Toast.makeText(MainActivity.this, "已从相册删除", Toast.LENGTH_LONG).show();
                        } else {
                            String message = TextUtils.isEmpty(finalError)
                                    ? "删除失败，请到相册中重试" : "删除失败：" + finalError;
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private synchronized void removeRecentRecordInternal(RecentRecord target, boolean clearDedup) {
        ArrayList<RecentRecord> records = readRecentRecordsRaw();
        for (int i = records.size() - 1; i >= 0; i--) {
            RecentRecord record = records.get(i);
            if (target.uri.equals(record.uri)) records.remove(i);
        }
        writeRecentRecords(records);
        if (clearDedup) removeSavedMediaReference(target);
    }

    private void removeSavedMediaReference(RecentRecord record) {
        SharedPreferences preferences = getSharedPreferences(SAVED_MEDIA_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (!TextUtils.isEmpty(record.mediaKey)) editor.remove(record.mediaKey);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof String && record.uri.equals(entry.getValue())) {
                editor.remove(entry.getKey());
            }
        }
        editor.commit();
    }

    private void installPremiumButtonMotion(final Button button) {
        if (button == null) return;
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!v.isEnabled()) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel();
                    v.animate().scaleX(0.982f).scaleY(0.982f).alpha(0.90f)
                            .setDuration(85L).setInterpolator(new DecelerateInterpolator()).start();
                } else if (event.getAction() == MotionEvent.ACTION_UP ||
                        event.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(170L)
                            .setInterpolator(new PathInterpolator(0.18f, 0.82f, 0.24f, 1f)).start();
                }
                return false;
            }
        });
    }

    private void preloadPreview(final ImageView imageView, final String url, final int generation) {
        if (url == null || url.isEmpty()) return;
        imageView.setTag(url);
        previewExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(getCacheDir(), "photo_previews");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
                    if (!file.exists() || file.length() == 0) {
                        byte[] data = DouyinParser.downloadImageBytes(url, silentCallback());
                        if (data == null || data.length == 0) return;
                        try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); }
                    }
                    final Bitmap bitmap = decodePreview(file);
                    if (bitmap == null) return;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation == previewGeneration && url.equals(imageView.getTag())) {
                                imageView.setImageBitmap(bitmap);
                            }
                        }
                    });
                } catch (Exception ignored) { }
            }
        });
    }

    private Bitmap decodePreview(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 180 || bounds.outHeight / sample > 180) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void setAllPhotosChecked(boolean checked) {
        for (CheckBox checkBox : photoCheckBoxes) checkBox.setChecked(checked);
    }

    private void downloadCurrent() {
        if (!currentImageUrls.isEmpty()) {
            downloadSelectedPhotos();
        } else if (currentVideoUrl != null) {
            doDownload();
        } else {
            Toast.makeText(this, "请先解析作品", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadSelectedPhotos() {
        final ArrayList<Integer> selectedIndexes = getSelectedIndexes();
        if (selectedIndexes.isEmpty()) {
            Toast.makeText(this, "请至少选择一项内容", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean includeStickers = isStickerSavingEnabled();
        final LinkedHashMap<Integer, Uri> duplicateImageUris = new LinkedHashMap<>();
        for (Integer selectedIndex : selectedIndexes) {
            if (selectedIndex == null || selectedIndex < 0 || selectedIndex >= currentImageUrls.size()) continue;
            int index = selectedIndex;
            String liveUrl = index < currentLiveVideoUrls.size() ? currentLiveVideoUrls.get(index) : null;
            // 这里只弹“重复照片”选择框；实况/视频仍沿用原有防重复逻辑。
            if (!TextUtils.isEmpty(liveUrl)) continue;
            String imageUrl = currentImageUrls.get(index);
            String mediaKey = buildMediaKey(currentAwemeId,
                    includeStickers && hasStickersForImage(index) ? "image_sticker" : "image",
                    index, imageUrl);
            Uri existingUri = getSavedMediaUri(mediaKey);
            if (existingUri != null) duplicateImageUris.put(index, existingUri);
        }

        if (!duplicateImageUris.isEmpty()) {
            showDuplicateImageDecisionDialog(selectedIndexes, duplicateImageUris);
        } else {
            startSelectedPhotosDownload(selectedIndexes, new HashSet<Integer>());
        }
    }

    private void showDuplicateImageDecisionDialog(final ArrayList<Integer> selectedIndexes,
                                                   final LinkedHashMap<Integer, Uri> duplicateImageUris) {
        final LinkedHashMap<Integer, Boolean> overwriteChoices = new LinkedHashMap<>();
        final LinkedHashMap<Integer, TextView> skipPills = new LinkedHashMap<>();
        final LinkedHashMap<Integer, TextView> overwritePills = new LinkedHashMap<>();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout surface = createDialogSurface();

        surface.addView(createDialogTitle("发现重复照片"));

        TextView hint = new TextView(this);
        hint.setText("检测到 " + duplicateImageUris.size() + " 张照片已存在于相册。每张只能选择一种处理方式，默认跳过。" );
        hint.setTextColor(Color.rgb(205, 207, 212));
        hint.setTextSize(14);
        hint.setLineSpacing(dp(2), 1.08f);
        hint.setPadding(0, 0, 0, dp(14));
        surface.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.END);
        quickActions.setPadding(0, 0, 0, dp(12));
        TextView allSkip = createPill("全部跳过", 12);
        TextView allOverwrite = createPill("全部覆盖", 12);
        styleChoicePill(allSkip, false, false);
        styleChoicePill(allOverwrite, false, true);
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        quickLp.setMargins(dp(7), 0, 0, 0);
        quickActions.addView(allSkip, quickLp);
        quickActions.addView(allOverwrite, quickLp);
        surface.addView(quickActions);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        for (Map.Entry<Integer, Uri> entry : duplicateImageUris.entrySet()) {
            final int index = entry.getKey();
            overwriteChoices.put(index, false);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(roundedBackground(Color.rgb(20, 21, 24), Color.rgb(45, 47, 52), 14));

            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundResource(R.drawable.bg_input);
            cover.setClipToOutline(true);
            cover.setContentDescription("重复照片 " + (index + 1) + " 封面");
            row.addView(cover, new LinearLayout.LayoutParams(dp(76), dp(76)));

            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            controlsLp.setMargins(dp(12), 0, 0, 0);

            TextView label = new TextView(this);
            label.setText("照片 " + (index + 1));
            label.setTextColor(Color.WHITE);
            label.setTextSize(15);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            controls.addView(label);

            TextView sub = new TextView(this);
            sub.setText("相册中已有此照片");
            sub.setTextColor(Color.rgb(190, 192, 198));
            sub.setTextSize(12);
            sub.setPadding(0, dp(2), 0, dp(8));
            controls.addView(sub);

            LinearLayout choices = new LinearLayout(this);
            choices.setOrientation(LinearLayout.HORIZONTAL);
            final TextView skip = createPill("跳过", 13);
            final TextView overwrite = createPill("覆盖", 13);
            applyDuplicateChoiceStyle(skip, overwrite, false);
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            LinearLayout.LayoutParams overwriteLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            overwriteLp.setMargins(dp(8), 0, 0, 0);
            choices.addView(skip, choiceLp);
            choices.addView(overwrite, overwriteLp);
            controls.addView(choices);

            skipPills.put(index, skip);
            overwritePills.put(index, overwrite);

            skip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    overwriteChoices.put(index, false);
                    applyDuplicateChoiceStyle(skip, overwrite, false);
                }
            });
            overwrite.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    overwriteChoices.put(index, true);
                    applyDuplicateChoiceStyle(skip, overwrite, true);
                }
            });

            row.addView(controls, controlsLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(9));
            list.addView(row, rowLp);

            boolean loadedSavedCover = false;
            try {
                Bitmap savedCover = getContentResolver().loadThumbnail(entry.getValue(),
                        new android.util.Size(dp(152), dp(152)), null);
                if (savedCover != null) {
                    cover.setImageBitmap(savedCover);
                    loadedSavedCover = true;
                }
            } catch (Exception ignored) {
            }
            if (!loadedSavedCover) {
                String previewUrl = index < currentImagePreviewUrls.size()
                        ? currentImagePreviewUrls.get(index) : null;
                if (TextUtils.isEmpty(previewUrl) && index < currentImageUrls.size()) {
                    previewUrl = currentImageUrls.get(index);
                }
                preloadPreview(cover, previewUrl, previewGeneration);
            }
        }

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(Math.min(390,
                96 + duplicateImageUris.size() * 104)));
        surface.addView(scroll, scrollLp);

        allSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Integer index : overwriteChoices.keySet()) {
                    overwriteChoices.put(index, false);
                    applyDuplicateChoiceStyle(skipPills.get(index), overwritePills.get(index), false);
                }
            }
        });
        allOverwrite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Integer index : overwriteChoices.keySet()) {
                    overwriteChoices.put(index, true);
                    applyDuplicateChoiceStyle(skipPills.get(index), overwritePills.get(index), true);
                }
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button cancel = createDialogButton("取消", false);
        Button confirm = createDialogButton("继续保存", true);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        confirmLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(cancel, actionLp);
        actions.addView(confirm, confirmLp);
        surface.addView(actions);

        dialog.setContentView(surface);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                HashSet<Integer> overwriteIndexes = new HashSet<>();
                for (Map.Entry<Integer, Boolean> entry : overwriteChoices.entrySet()) {
                    if (Boolean.TRUE.equals(entry.getValue())) overwriteIndexes.add(entry.getKey());
                }
                dialog.dismiss();
                startSelectedPhotosDownload(selectedIndexes, overwriteIndexes);
            }
        });
        showModernDialog(dialog, surface);
    }

    private void startSelectedPhotosDownload(final ArrayList<Integer> selectedIndexes,
                                             final Set<Integer> overwriteIndexes) {
        setDownloadControlsEnabled(false);
        isDownloading = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        setStatus("正在保存所选内容… 0/" + selectedIndexes.size(), R.color.text_secondary);
        final String title = currentTitle != null ? currentTitle : "douyin_photos";
        final String awemeId = currentAwemeId;
        final ArrayList<String> imageUrls = new ArrayList<>(currentImageUrls);
        final ArrayList<String> liveVideoUrls = new ArrayList<>(currentLiveVideoUrls);
        final ArrayList<String> stickerCompositeUrls = new ArrayList<>(currentStickerCompositeUrls);
        final ArrayList<List<DouyinParser.StickerOverlay>> stickerOverlays =
                new ArrayList<>(currentImageStickerOverlays);
        final boolean includeStickers = isStickerSavingEnabled();

        new Thread(new Runnable() {
            @Override
            public void run() {
                int savedFiles = 0;
                int overwrittenFiles = 0;
                int failedFiles = 0;
                int skippedFiles = 0;
                int savedLiveItems = 0;
                for (int position = 0; position < selectedIndexes.size(); position++) {
                    int index = selectedIndexes.get(position);
                    int number = index + 1;
                    String imageUrl = index < imageUrls.size() ? imageUrls.get(index) : null;
                    String liveUrl = index < liveVideoUrls.size() ? liveVideoUrls.get(index) : null;
                    boolean isLive = liveUrl != null && !liveUrl.isEmpty();
                    String mediaUrl = isLive ? liveUrl : imageUrl;
                    boolean saveWithStickers = !isLive && includeStickers
                            && hasStickersForImage(index, stickerCompositeUrls, stickerOverlays);
                    String mediaKey = buildMediaKey(awemeId,
                            isLive ? "live" : (saveWithStickers ? "image_sticker" : "image"),
                            index, mediaUrl);
                    Uri existingUri = getSavedMediaUri(mediaKey);
                    boolean shouldOverwriteImage = !isLive && existingUri != null && overwriteIndexes.contains(index);

                    if (existingUri != null && !shouldOverwriteImage) {
                        skippedFiles++;
                        updateBatchProgress(position + 1, selectedIndexes.size(),
                                "已跳过重复内容 " + number);
                        continue;
                    }

                    updateBatchProgress(position, selectedIndexes.size(),
                            shouldOverwriteImage
                                    ? "正在覆盖照片 " + number
                                    : (!isLive ? "正在保存照片 " + number : "正在保存实况照片 " + number));

                    if (!isLive && imageUrl != null && !imageUrl.isEmpty()) {
                        byte[] imageData = downloadPhotoBytes(imageUrl, index, saveWithStickers,
                                stickerCompositeUrls, stickerOverlays);
                        if (imageData != null && imageData.length > 0) {
                            try {
                                Uri savedUri;
                                if (shouldOverwriteImage) {
                                    overwriteImageInGallery(existingUri, imageData);
                                    savedUri = existingUri;
                                    overwrittenFiles++;
                                } else {
                                    savedUri = saveImageToGallery(imageData, title, "_照片" + number);
                                    savedFiles++;
                                }
                                markMediaSaved(mediaKey, savedUri);
                                recordRecentSave(mediaKey, savedUri,
                                        title + " · 照片 " + number,
                                        "照片", detectImageMimeType(imageData));
                            } catch (Exception e) {
                                failedFiles++;
                            }
                        } else {
                            failedFiles++;
                        }
                    }

                    if (isLive) {
                        try {
                            Uri savedUri = saveVideoToGallery(liveUrl, title,
                                    "_实况" + number, silentCallback());
                            markMediaSaved(mediaKey, savedUri);
                            recordRecentSave(mediaKey, savedUri,
                                    title + " · 实况 " + number,
                                    "实况视频", "video/mp4");
                            savedFiles++;
                            savedLiveItems++;
                        } catch (Exception e) {
                            failedFiles++;
                        }
                    }
                    updateBatchProgress(position + 1, selectedIndexes.size(), "正在处理");
                }

                final int finalSavedFiles = savedFiles;
                final int finalOverwrittenFiles = overwrittenFiles;
                final int finalFailedFiles = failedFiles;
                final int finalSkippedFiles = skippedFiles;
                final int finalSavedLiveItems = savedLiveItems;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setDownloadControlsEnabled(true);
                        isDownloading = false;
                        progressBar.setVisibility(View.GONE);
                        if (finalSavedFiles > 0 || finalOverwrittenFiles > 0 || finalSkippedFiles > 0) {
                            String message = finalSavedFiles > 0
                                    ? "已保存 " + finalSavedFiles + " 个新文件到相册「" + ALBUM_DIR + "」"
                                    : "重复内容已处理";
                            if (finalOverwrittenFiles > 0) message += "，覆盖 " + finalOverwrittenFiles + " 张照片";
                            if (finalSavedLiveItems > 0) message += "，含 " + finalSavedLiveItems + " 个实况视频";
                            if (finalSkippedFiles > 0) message += "，跳过 " + finalSkippedFiles + " 个重复项";
                            if (finalFailedFiles > 0) message += "，失败 " + finalFailedFiles + " 个";
                            setStatus(message, R.color.color_success);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        } else {
                            setStatus("内容保存失败，请稍后重试", R.color.color_error);
                            Toast.makeText(MainActivity.this, "内容保存失败", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private boolean isStickerSavingEnabled() {
        return cbIncludeStickers != null && cbIncludeStickers.getVisibility() == View.VISIBLE
                && cbIncludeStickers.isChecked();
    }

    private boolean hasStickersForImage(int index) {
        return hasStickersForImage(index, currentStickerCompositeUrls, currentImageStickerOverlays);
    }

    private boolean hasStickersForImage(int index, List<String> compositeUrls,
                                        List<List<DouyinParser.StickerOverlay>> overlays) {
        if (index >= 0 && index < compositeUrls.size() && !TextUtils.isEmpty(compositeUrls.get(index))) {
            return true;
        }
        return index >= 0 && index < overlays.size() && overlays.get(index) != null
                && !overlays.get(index).isEmpty();
    }

    private byte[] downloadPhotoBytes(String imageUrl, int index, boolean includeStickers,
                                      List<String> compositeUrls,
                                      List<List<DouyinParser.StickerOverlay>> overlays) {
        boolean compositeWasRequired = false;
        if (includeStickers && index >= 0 && index < compositeUrls.size()) {
            String compositeUrl = compositeUrls.get(index);
            if (!TextUtils.isEmpty(compositeUrl)) {
                compositeWasRequired = true;
                byte[] composite = DouyinParser.downloadImageBytes(compositeUrl, silentCallback());
                if (composite != null && composite.length > 0) return composite;
            }
        }

        byte[] original = DouyinParser.downloadImageBytes(imageUrl, silentCallback());
        if (original == null || original.length == 0 || !includeStickers) return original;
        if (index < 0 || index >= overlays.size() || overlays.get(index) == null
                || overlays.get(index).isEmpty()) return compositeWasRequired ? null : original;
        return composeStickerImage(original, overlays.get(index));
    }

    private byte[] composeStickerImage(byte[] original,
                                       List<DouyinParser.StickerOverlay> overlays) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        Bitmap bitmap = BitmapFactory.decodeByteArray(original, 0, original.length, options);
        if (bitmap == null || !bitmap.isMutable()) return null;
        Canvas canvas = new Canvas(bitmap);
        Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        try {
            for (DouyinParser.StickerOverlay overlay : overlays) {
                if (!TextUtils.isEmpty(overlay.resourceUrl)) {
                    byte[] stickerData = DouyinParser.downloadImageBytes(
                            overlay.resourceUrl, silentCallback());
                    if (stickerData == null || stickerData.length == 0) return null;
                    Bitmap sticker = BitmapFactory.decodeByteArray(stickerData, 0, stickerData.length);
                    if (sticker == null) return null;
                    drawStickerBitmap(canvas, bitmap, sticker, overlay, imagePaint);
                    sticker.recycle();
                }
                if (!TextUtils.isEmpty(overlay.text)) {
                    drawStickerText(canvas, bitmap, overlay);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null;
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private void drawStickerBitmap(Canvas canvas, Bitmap base, Bitmap sticker,
                                   DouyinParser.StickerOverlay overlay, Paint paint) {
        float centerX = overlay.centerX * base.getWidth();
        float centerY = overlay.centerY * base.getHeight();
        float targetWidth = overlay.widthRatio > 0f
                ? overlay.widthRatio * base.getWidth()
                : Math.min(base.getWidth() * 0.36f, sticker.getWidth());
        float targetHeight = overlay.heightRatio > 0f
                ? overlay.heightRatio * base.getHeight()
                : targetWidth * sticker.getHeight() / Math.max(1f, sticker.getWidth());
        if (overlay.widthRatio <= 0f && overlay.heightRatio > 0f) {
            targetWidth = targetHeight * sticker.getWidth() / Math.max(1f, sticker.getHeight());
        }
        RectF destination = new RectF(centerX - targetWidth / 2f, centerY - targetHeight / 2f,
                centerX + targetWidth / 2f, centerY + targetHeight / 2f);
        canvas.save();
        canvas.rotate(overlay.rotationDegrees, centerX, centerY);
        canvas.drawBitmap(sticker, null, destination, paint);
        canvas.restore();
    }

    private void drawStickerText(Canvas canvas, Bitmap base,
                                 DouyinParser.StickerOverlay overlay) {
        float centerX = overlay.centerX * base.getWidth();
        float centerY = overlay.centerY * base.getHeight();
        float textSize = Math.max(24f, base.getWidth() * 0.037f);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(Color.argb(220, 43, 47, 51));
        Paint bulletPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bulletPaint.setColor(Color.WHITE);

        float horizontalPadding = textSize * 0.62f;
        float verticalPadding = textSize * 0.34f;
        float lineGap = textSize * 0.36f;
        float maximumWidth = overlay.widthRatio > 0f
                ? Math.min(base.getWidth() * 0.90f, overlay.widthRatio * base.getWidth())
                : base.getWidth() * 0.78f;
        ArrayList<String> lines = new ArrayList<>();
        for (String paragraph : overlay.text.split("\\n")) {
            lines.addAll(wrapStickerText(paragraph, textPaint,
                    Math.max(textSize * 3f, maximumWidth - horizontalPadding * 2f)));
        }
        if (lines.isEmpty()) return;

        float lineHeight = textPaint.getFontMetrics().bottom - textPaint.getFontMetrics().top
                + verticalPadding * 2f;
        float totalHeight = lines.size() * lineHeight + (lines.size() - 1) * lineGap;
        float top = centerY - totalHeight / 2f;
        canvas.save();
        canvas.rotate(overlay.rotationDegrees, centerX, centerY);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float measured = textPaint.measureText(line);
            float boxWidth = overlay.widthRatio > 0f
                    ? maximumWidth : Math.min(maximumWidth, measured + horizontalPadding * 2f);
            float left = Math.max(textSize * 1.2f, centerX - boxWidth / 2f);
            if (left + boxWidth > base.getWidth() - textSize * 0.6f) {
                left = base.getWidth() - textSize * 0.6f - boxWidth;
            }
            float rowTop = top + i * (lineHeight + lineGap);
            RectF bubble = new RectF(left, rowTop, left + boxWidth, rowTop + lineHeight);
            canvas.drawRoundRect(bubble, lineHeight / 2f, lineHeight / 2f, backgroundPaint);
            float bulletRadius = Math.max(4f, textSize * 0.12f);
            canvas.drawCircle(left - textSize * 0.52f, rowTop + lineHeight / 2f,
                    bulletRadius, bulletPaint);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = rowTop + (lineHeight - metrics.bottom + metrics.top) / 2f
                    - metrics.top;
            canvas.drawText(line, left + (boxWidth - measured) / 2f, baseline, textPaint);
        }
        canvas.restore();
    }

    private List<String> wrapStickerText(String text, Paint paint, float maxWidth) {
        ArrayList<String> lines = new ArrayList<>();
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return lines;
        int start = 0;
        while (start < value.length()) {
            int count = paint.breakText(value, start, value.length(), true, maxWidth, null);
            if (count <= 0) count = 1;
            int end = Math.min(value.length(), start + count);
            lines.add(value.substring(start, end).trim());
            start = end;
        }
        return lines;
    }

    private void overwriteImageInGallery(Uri uri, byte[] data) throws Exception {
        if (uri == null) throw new Exception("重复照片位置无效");
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new Exception("无法覆盖原照片");
            output.write(data);
            output.flush();
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.MIME_TYPE, detectImageMimeType(data));
        try { getContentResolver().update(uri, values, null, null); } catch (Exception ignored) {}
    }

    private String detectImageMimeType(byte[] data) {
        if (data != null && data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 0x50 &&
                data[2] == 0x4e && data[3] == 0x47) return "image/png";
        if (data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' &&
                data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' &&
                data[10] == 'B' && data[11] == 'P') return "image/webp";
        return "image/jpeg";
    }

    private ArrayList<Integer> getSelectedIndexes() {
        ArrayList<Integer> selected = new ArrayList<>();
        for (int i = 0; i < photoCheckBoxes.size() && i < currentImageUrls.size(); i++) {
            if (photoCheckBoxes.get(i).isChecked()) selected.add(i);
        }
        return selected;
    }

    private void prepareStitchOptions() {
        if (isDownloading || isParsing) return;
        final ArrayList<Integer> selected = getSelectedIndexes();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一项内容", Toast.LENGTH_SHORT).show();
            return;
        }

        final ArrayList<String> videosToProbe = new ArrayList<>();
        for (Integer index : selected) {
            if (index != null && index >= 0 && index < currentLiveVideoUrls.size()) {
                String url = currentLiveVideoUrls.get(index);
                if (!TextUtils.isEmpty(url)) videosToProbe.add(url);
            }
        }
        final boolean saveSinglePhotoAsVideo = isSingleStillSelection(selected)
                && !TextUtils.isEmpty(currentMusicUrl);
        if (saveSinglePhotoAsVideo) {
            showStitchOptionsDialog(selected, false, true);
            return;
        }
        if (videosToProbe.isEmpty()) {
            showStitchOptionsDialog(selected, false, false);
            return;
        }

        btnStitch.setEnabled(false);
        setStatus("正在检测视频原声…", R.color.text_secondary);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean hasAudio = false;
                for (String url : videosToProbe) {
                    if (MediaStitcher.remoteVideoHasAudio(url)) {
                        hasAudio = true;
                        break;
                    }
                }
                final boolean finalHasAudio = hasAudio;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnStitch.setEnabled(true);
                        setStatus(TextUtils.isEmpty(currentMusicUrl)
                                ? "已准备拼接设置"
                                : "已识别原作品音乐，可选择加入", R.color.text_secondary);
                        showStitchOptionsDialog(selected, finalHasAudio, false);
                    }
                });
            }
        }).start();
    }

    private void showStitchOptionsDialog(final ArrayList<Integer> selected, boolean hasOriginalAudio, final boolean saveSinglePhotoAsVideo) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout surface = createDialogSurface();
        surface.addView(createDialogTitle(saveSinglePhotoAsVideo ? "保存为视频" : "拼接视频"));

        TextView intro = new TextView(this);
        intro.setText(saveSinglePhotoAsVideo ? "设置照片显示时间，原作品音乐会自动加入" : "设置照片停留时间和声音选项");
        intro.setTextColor(Color.rgb(197, 199, 204));
        intro.setTextSize(13);
        intro.setPadding(0, 0, 0, dp(14));
        surface.addView(intro);

        TextView durationLabel = new TextView(this);
        durationLabel.setText(saveSinglePhotoAsVideo ? "照片显示时间（秒）" : "单张照片显示时间（秒）");
        durationLabel.setTextColor(getColor(R.color.text_primary));
        durationLabel.setTextSize(14);
        durationLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        durationLabel.setPadding(0, 0, 0, dp(7));
        surface.addView(durationLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText durationInput = new EditText(this);
        final float savedDuration = getSharedPreferences("stitch_settings", MODE_PRIVATE)
                .getFloat("photo_duration", 2.0f);
        durationInput.setText(trimFloat(savedDuration));
        durationInput.setTextColor(getColor(R.color.text_primary));
        durationInput.setHint("自定义 0.5–10 秒");
        durationInput.setHintTextColor(Color.rgb(130, 132, 138));
        durationInput.setSelectAllOnFocus(true);
        durationInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        durationInput.setSingleLine(true);
        durationInput.setTextSize(15);
        durationInput.setPadding(dp(14), 0, dp(14), 0);
        durationInput.setBackgroundResource(R.drawable.bg_input);

        TextView quickLabel = new TextView(this);
        quickLabel.setText("快捷时长");
        quickLabel.setTextColor(Color.rgb(188, 190, 196));
        quickLabel.setTextSize(12);
        quickLabel.setPadding(0, 0, 0, dp(7));
        surface.addView(quickLabel);

        final float[] presetValues = new float[] {1f, 2f, 3f, 5f};
        final TextView[] presetPills = new TextView[presetValues.length];
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < presetValues.length; i++) {
            final float value = presetValues[i];
            TextView pill = createPill(trimFloat(value) + " 秒", 12);
            pill.setMinHeight(dp(38));
            styleDurationPreset(pill, Math.abs(savedDuration - value) < 0.001f);
            final int presetIndex = i;
            pill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    durationInput.setText(trimFloat(value));
                    durationInput.setSelection(durationInput.getText().length());
                    for (int j = 0; j < presetPills.length; j++) {
                        if (presetPills[j] != null) styleDurationPreset(presetPills[j], j == presetIndex);
                    }
                }
            });
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i > 0) pillLp.setMargins(dp(7), 0, 0, 0);
            presetRow.addView(pill, pillLp);
            presetPills[i] = pill;
        }
        LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        presetLp.setMargins(0, 0, 0, dp(10));
        surface.addView(presetRow, presetLp);

        TextView customLabel = new TextView(this);
        customLabel.setText("自定义时长");
        customLabel.setTextColor(Color.rgb(188, 190, 196));
        customLabel.setTextSize(12);
        customLabel.setPadding(0, 0, 0, dp(7));
        surface.addView(customLabel);

        LinearLayout.LayoutParams durationLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        durationLp.setMargins(0, 0, 0, dp(12));
        surface.addView(durationInput, durationLp);
        durationInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                float entered = -1f;
                try { entered = Float.parseFloat(editable.toString().trim()); } catch (Exception ignored) {}
                for (int i = 0; i < presetValues.length; i++) {
                    styleDurationPreset(presetPills[i], Math.abs(entered - presetValues[i]) < 0.001f);
                }
            }
        });

        final boolean[] musicEnabled = new boolean[] {!TextUtils.isEmpty(currentMusicUrl)};
        final boolean[] originalEnabled = new boolean[] {hasOriginalAudio};

        final LinearLayout musicRow = createToggleSettingRow(
                TextUtils.isEmpty(currentMusicTitle) ? "加入原作品音乐" : "原作品音乐 · " + currentMusicTitle,
                musicEnabled[0]);
        final TextView musicChip = (TextView) musicRow.getTag();
        musicRow.setVisibility(TextUtils.isEmpty(currentMusicUrl) ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(9));
        surface.addView(musicRow, rowLp);
        if (saveSinglePhotoAsVideo) {
            musicEnabled[0] = true;
            musicChip.setText("自动加入");
            musicChip.setTextColor(Color.WHITE);
            musicChip.setBackground(roundedBackground(getColor(R.color.primary), getColor(R.color.primary), 11));
            musicRow.setClickable(false);
            musicRow.setAlpha(1f);
        } else {
            musicRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    musicEnabled[0] = !musicEnabled[0];
                    styleToggleChip(musicChip, musicEnabled[0]);
                }
            });
        }

        final LinearLayout originalRow = createToggleSettingRow("保留视频原声", originalEnabled[0]);
        final TextView originalChip = (TextView) originalRow.getTag();
        originalRow.setVisibility(!saveSinglePhotoAsVideo && hasOriginalAudio ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams originalLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        originalLp.setMargins(0, 0, 0, dp(9));
        surface.addView(originalRow, originalLp);
        originalRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                originalEnabled[0] = !originalEnabled[0];
                styleToggleChip(originalChip, originalEnabled[0]);
            }
        });

        TextView note = new TextView(this);
        note.setText(saveSinglePhotoAsVideo
                ? "照片会按设置时长生成视频，并自动加入原作品音乐。"
                : "照片按所选顺序停留；实况/视频按原时长播放。音乐比成片短时会自动循环。");
        note.setTextColor(Color.rgb(180, 182, 188));
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1.06f);
        note.setPadding(0, dp(2), 0, dp(12));
        surface.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = createDialogButton("取消", false);
        Button startButton = createDialogButton(saveSinglePhotoAsVideo ? "保存视频" : "开始拼接", true);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        startLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(cancel, actionLp);
        actions.addView(startButton, startLp);
        surface.addView(actions);

        dialog.setContentView(surface);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float duration = 2.0f;
                try { duration = Float.parseFloat(durationInput.getText().toString().trim()); }
                catch (Exception ignored) {}
                duration = Math.max(0.5f, Math.min(10.0f, duration));
                getSharedPreferences("stitch_settings", MODE_PRIVATE).edit()
                        .putFloat("photo_duration", duration).apply();
                dialog.dismiss();
                startStitch(selected, duration,
                        saveSinglePhotoAsVideo || (musicRow.getVisibility() == View.VISIBLE && musicEnabled[0]),
                        !saveSinglePhotoAsVideo && originalRow.getVisibility() == View.VISIBLE && originalEnabled[0]);
            }
        });
        showModernDialog(dialog, surface);
    }

    private void styleDurationPreset(TextView pill, boolean selected) {
        if (pill == null) return;
        pill.setTextColor(selected ? Color.WHITE : Color.rgb(218, 220, 225));
        pill.setBackground(roundedBackground(
                selected ? getColor(R.color.primary) : Color.rgb(25, 26, 30),
                selected ? getColor(R.color.primary) : Color.rgb(57, 59, 65), 11));
    }

    private boolean isSingleStillSelection(List<Integer> selected) {
        if (selected == null || selected.size() != 1) return false;
        Integer index = selected.get(0);
        if (index == null || index < 0 || index >= currentImageUrls.size()) return false;
        String liveUrl = index < currentLiveVideoUrls.size() ? currentLiveVideoUrls.get(index) : null;
        return TextUtils.isEmpty(liveUrl);
    }

    private String trimFloat(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) return String.valueOf(Math.round(value));
        return String.valueOf(value);
    }

    private void startStitch(ArrayList<Integer> selected, float photoDuration,
                             boolean includeMusic, boolean preserveOriginalAudio) {
        final ArrayList<MediaStitcher.SourceItem> items = new ArrayList<>();
        for (Integer index : selected) {
            if (index == null || index < 0 || index >= currentImageUrls.size()) continue;
            String imageUrl = currentImageUrls.get(index);
            String videoUrl = index < currentLiveVideoUrls.size() ? currentLiveVideoUrls.get(index) : null;
            items.add(new MediaStitcher.SourceItem(imageUrl, videoUrl, index));
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可拼接的内容", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean saveSinglePhotoAsVideo = isSingleStillSelection(selected);
        final MediaStitcher.Options options = new MediaStitcher.Options();
        options.photoDurationSeconds = photoDuration;
        options.includeMusic = includeMusic;
        options.preserveOriginalAudio = preserveOriginalAudio;
        options.musicUrl = currentMusicUrl;
        final String title = currentTitle != null ? currentTitle : "douyin_stitch";

        setDownloadControlsEnabled(false);
        isDownloading = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        setStatus("正在准备拼接…", R.color.text_secondary);

        new Thread(new Runnable() {
            @Override public void run() {
                MediaStitcher.StitchResult result = null;
                try {
                    result = MediaStitcher.stitch(MainActivity.this, items, options,
                            new MediaStitcher.ProgressCallback() {
                                @Override public void onProgress(final int percent, final String message) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            progressBar.setProgress(percent);
                                            setStatus(message + "… " + percent + "%", R.color.text_secondary);
                                        }
                                    });
                                }
                            });
                    final Uri savedUri = saveLocalVideoToGallery(result.outputFile, title,
                            saveSinglePhotoAsVideo ? "_照片视频" : "_拼接");
                    recordRecentSave("", savedUri, title,
                            saveSinglePhotoAsVideo ? "照片视频" : "拼接视频", "video/mp4");
                    final MediaStitcher.StitchResult finalResult = result;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            String message = (saveSinglePhotoAsVideo ? "视频已保存到相册「" : "拼接视频已保存到相册「")
                                    + ALBUM_DIR + "」";
                            if (finalResult.warning != null && !finalResult.warning.isEmpty()) message += "；" + finalResult.warning;
                            setStatus(message, R.color.color_success);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            String prefix = saveSinglePhotoAsVideo ? "保存视频失败：" : "拼接失败：";
                            setStatus(prefix + e.getMessage(), R.color.color_error);
                            Toast.makeText(MainActivity.this, prefix + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (result != null && result.outputFile != null) deleteRecursively(result.outputFile.getParentFile());
                }
            }
        }).start();
    }

    private Uri saveLocalVideoToGallery(File source, String title, String suffix) throws Exception {
        String fileName = sanitizeFileName(title) + suffix + "_" + System.currentTimeMillis() + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + ALBUM_DIR);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("创建拼接视频条目失败");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("打开拼接视频输出流失败");
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }

    private DouyinParser.DownloadCallback silentCallback() {
        return new DouyinParser.DownloadCallback() {
            @Override
            public void onProgress(int percent) {}

            @Override
            public void onSuccess(String filePath) {}

            @Override
            public void onError(String message) {}
        };
    }

    private void updateBatchProgress(final int completed, final int total, final String label) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setProgress(total == 0 ? 0 : (completed * 100 / total));
                setStatus(label + "… " + completed + "/" + total, R.color.text_secondary);
            }
        });
    }

    private String buildMediaKey(String awemeId, String mediaType, int index, String mediaUrl) {
        String identity = !TextUtils.isEmpty(awemeId)
                ? awemeId
                : "url_" + Integer.toHexString(mediaUrl == null ? 0 : mediaUrl.hashCode());
        return "saved:" + identity + ":" + mediaType + ":" + index;
    }

    private Uri getSavedMediaUri(String mediaKey) {
        SharedPreferences preferences = getSharedPreferences(SAVED_MEDIA_PREFS, MODE_PRIVATE);
        String value = preferences.getString(mediaKey, null);
        if (TextUtils.isEmpty(value)) return null;
        Uri uri = Uri.parse(value);
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return uri;
        } catch (Exception ignored) {
        }
        // 用户若已从相册删除文件，旧记录随即失效，允许重新保存。
        preferences.edit().remove(mediaKey).commit();
        return null;
    }

    private void markMediaSaved(String mediaKey, Uri uri) {
        if (uri == null) return;
        getSharedPreferences(SAVED_MEDIA_PREFS, MODE_PRIVATE)
                .edit().putString(mediaKey, uri.toString()).commit();
    }

    private Uri saveVideoToGallery(String videoUrl, String title, String suffix,
                                   DouyinParser.DownloadCallback callback) throws Exception {
        String fileName = sanitizeFileName(title) + suffix + "_" +
                System.currentTimeMillis() + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/" + ALBUM_DIR);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("创建视频条目失败");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("打开视频输出流失败");
                long bytesWritten = downloadVideoForCurrentPlatform(videoUrl, output, callback);
                if (bytesWritten <= 0) throw new Exception("服务器返回的视频数据为空");
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    private Uri saveImageToGallery(byte[] data, String title, String suffix) throws Exception {
        String mimeType = "image/jpeg";
        String extension = ".jpg";
        if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 0x50 &&
                data[2] == 0x4e && data[3] == 0x47) {
            mimeType = "image/png";
            extension = ".png";
        } else if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' &&
                data[2] == 'F' && data[3] == 'F' && data[8] == 'W' &&
                data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            mimeType = "image/webp";
            extension = ".webp";
        }

        String fileName = sanitizeFileName(title) + suffix + "_" +
                System.currentTimeMillis() + extension;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/" + ALBUM_DIR);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("创建照片条目失败");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("打开照片输出流失败");
                output.write(data);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    private void setDownloadControlsEnabled(boolean enabled) {
        btnDownload.setEnabled(enabled);
        if (btnStitch != null && btnStitch.getVisibility() == View.VISIBLE) btnStitch.setEnabled(enabled);
        if (btnSaveMusic != null && btnSaveMusic.getVisibility() == View.VISIBLE) {
            btnSaveMusic.setEnabled(enabled);
        }
        btnParse.setEnabled(enabled);
        photoSelectionPanel.setEnabled(enabled);
        if (cbIncludeStickers != null) cbIncludeStickers.setEnabled(enabled);
        if (cbBurnTitle != null) cbBurnTitle.setEnabled(enabled);
        for (CheckBox checkBox : photoCheckBoxes) checkBox.setEnabled(enabled);
    }

    /** 视频保存结果：相册地址 + 标题是否真的烧进了画面。 */
    private static final class VideoSaveResult {
        final Uri uri;
        final boolean titleBurned;
        final boolean coverApplied;
        /** 各阶段耗时摘要，拼进完成提示里，方便定位"慢在哪一段"。 */
        final String perfNote;

        VideoSaveResult(Uri uri, boolean titleBurned) {
            this(uri, titleBurned, false, null);
        }

        VideoSaveResult(Uri uri, boolean titleBurned, boolean coverApplied) {
            this(uri, titleBurned, coverApplied, null);
        }

        VideoSaveResult(Uri uri, boolean titleBurned, boolean coverApplied, String perfNote) {
            this.uri = uri;
            this.titleBurned = titleBurned;
            this.coverApplied = coverApplied;
            this.perfNote = perfNote;
        }
    }

    private void doDownload() {
        final String videoUrl = currentVideoUrl;
        final String awemeId = currentAwemeId;
        final boolean burnTitle = cbBurnTitle != null
                && cbBurnTitle.getVisibility() == View.VISIBLE
                && cbBurnTitle.isChecked()
                && !TextUtils.isEmpty(currentTitle);
        // 封面要整段重编码，很慢，所以只在用户明确勾选时才做（默认不勾 = 恢复秒存）
        final boolean applyCover = cbApplyCover != null
                && cbApplyCover.getVisibility() == View.VISIBLE
                && cbApplyCover.isChecked()
                && !TextUtils.isEmpty(currentCoverUrl);
        // 带标题版、带封面版各自单独记录：这样"用旧版本存过的视频"重新保存时不会被
        // 去重挡住，能重新拿到一份封面已做进画面的版本（旧的可以自行删除）。
        final String mediaKind = burnTitle ? "video_title"
                : (applyCover ? "video_cover" : "video");
        final String mediaKey = buildMediaKey(awemeId, mediaKind, 0, videoUrl);
        if (getSavedMediaUri(mediaKey) != null) {
            String message = "这个视频已经保存过了，已防止重复保存";
            setStatus(message, R.color.color_success);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        btnDownload.setEnabled(false);
        btnParse.setEnabled(false);
        if (cbBurnTitle != null) cbBurnTitle.setEnabled(false);
        if (cbApplyCover != null) cbApplyCover.setEnabled(false);
        isDownloading = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        setStatus("正在下载… 0%", R.color.text_secondary);

        final String title = currentTitle != null ? currentTitle : "douyin_video";
        // 最近保存里区分平台，视频号与抖音的记录一眼能分开
        final boolean channels = currentIsChannels;
        // 封面是平台上的独立素材（作者发布时单独设的那张），与视频分开存
        final String coverUrl = currentCoverUrl;
        // 烧标题时去掉 #话题标签，只保留真正的标题文字
        final String titleForBurn = stripHashtagTopics(title);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final DouyinParser.DownloadCallback callback = new DouyinParser.DownloadCallback() {
                    @Override
                    public void onProgress(final int percent) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                progressBar.setProgress(percent);
                                setStatus("正在下载… " + percent + "%", R.color.text_secondary);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(String filePath) {}

                    @Override
                    public void onError(String message) {}
                };

                try {
                    // 封面是平台上的独立素材。只写封面时走"改元数据"这条路，不动像素、秒级完成；
                    // 只有要烧标题（那必然要整段重编码）时才把封面解码成位图、顺带铺进画面。
                    final byte[] coverBytes = applyCover ? downloadCoverBytes(coverUrl) : null;
                    final Bitmap coverBitmap = (applyCover && burnTitle && coverBytes != null)
                            ? decodeCoverBitmap(coverBytes) : null;
                    final VideoSaveResult result;
                    try {
                        if (burnTitle) {
                            // 烧标题必然整段重编码，封面一起烧进画面，不额外多花时间
                            result = saveVideoWithEffects(videoUrl, title,
                                    titleForBurn, coverBitmap);
                        } else if (coverBytes != null) {
                            // 只写封面：把封面塞进 MP4 元数据（covr），跟存原片一样快
                            result = saveVideoWithCoverMetadata(videoUrl, title, coverBytes);
                        } else {
                            result = new VideoSaveResult(
                                    saveVideoToGallery(videoUrl, title, "", callback), false);
                        }
                    } finally {
                        if (coverBitmap != null && !coverBitmap.isRecycled()) {
                            coverBitmap.recycle();
                        }
                    }
                    markMediaSaved(mediaKey, result.uri);
                    recordRecentSave(mediaKey, result.uri,
                            result.titleBurned ? titleForBurn : title,
                            channels ? (result.titleBurned ? "视频号·带标题" : "视频号")
                                    : (result.titleBurned ? "视频·带标题" : "视频"),
                            "video/mp4");


                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnDownload.setEnabled(true);
                            btnParse.setEnabled(true);
                            if (cbBurnTitle != null) cbBurnTitle.setEnabled(true);
                            if (cbApplyCover != null) cbApplyCover.setEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            String message;
                            int messageColor = R.color.color_success;
                            if (result.titleBurned) {
                                message = "已保存带标题的视频到相册「" + ALBUM_DIR + "」";
                            } else if (burnTitle) {
                                message = "该视频无法添加标题，已保存原视频到相册「" + ALBUM_DIR + "」";
                                messageColor = R.color.color_error;
                            } else {
                                message = "已保存 1 个视频到相册「" + ALBUM_DIR + "」";
                            }
                            if (result.coverApplied) message += "，封面已做进视频";
                            if (TitleOverlayTranscoder.lastRunFellBackToSoftware()) {
                                // 软编解码慢一个数量级，明确告诉用户，别让他以为是正常速度
                                message += "（你的设备用的是软件编解码器，这一步会明显慢）";
                            }
                            if (result.perfNote != null) message += result.perfNote;
                            Log.i("MainActivity", "save timing: " + result.perfNote
                                    + " | " + TitleOverlayTranscoder.lastRunCodecSummary()
                                    + " | " + TitleOverlayTranscoder.lastRunStageSummary());
                            setStatus(message, messageColor);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnDownload.setEnabled(true);
                            btnParse.setEnabled(true);
                            if (cbBurnTitle != null) cbBurnTitle.setEnabled(true);
                            if (cbApplyCover != null) cbApplyCover.setEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            setStatus("保存失败：" + e.getMessage(), R.color.color_error);
                            Toast.makeText(MainActivity.this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 把封面字节解码成位图。
     *
     * <p>只有"要把封面烧进画面"才需要这一步（那种情况反正要整段重编码）。
     * 只写元数据时用原始字节就够了，不必解码、也不占内存。
     *
     * <p>封面只是锦上添花：格式不认识、内存不够，一律返回 null，
     * 让保存退回"不加封面"，绝不因为封面把整个保存搞失败。
     *
     * @return 解码好的封面位图；调用方负责 recycle
     */
    private Bitmap decodeCoverBitmap(byte[] coverBytes) {
        if (coverBytes == null || coverBytes.length == 0) return null;
        try {
            return BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.length);
        } catch (Exception e) {
            Log.w("MainActivity", "cover decode failed, ignored: " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            Log.w("MainActivity", "cover decode OOM, ignored");
            return null;
        }
    }

    /**
     * 拼一段"慢在哪"的耗时摘要。
     *
     * <p>用户反馈保存慢、又说没出现软件编解码提示 —— 说明硬件编解码是正常的，
     * 那慢必在别处。与其继续猜，不如把下载/转码/写相册三段的实际耗时直接报出来。
     *
     * @param transcodeDone 转码结束时刻；小于 0 表示走了"转码失败退回原片"的分支
     */
    private static String buildPerfNote(long start, long downloadDone, long transcodeDone,
                                        long allDone) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "（下载 %.1fs",
                (downloadDone - start) / 1000.0));
        if (transcodeDone > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "／转码 %.1fs",
                    (transcodeDone - downloadDone) / 1000.0));
        } else {
            sb.append("／转码失败已退回原片");
        }
        sb.append(String.format(java.util.Locale.ROOT, "／写入 %.1fs",
                (allDone - Math.max(transcodeDone, downloadDone)) / 1000.0));
        String gl = TitleOverlayTranscoder.suspectGlRenderer();
        if (gl != null) sb.append("；GL 疑似软件渲染 ").append(gl);
        sb.append("）");
        return sb.toString();
    }

    private void postSaveProgress(final String label, final int percent) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setProgress(percent);
                setStatus(label + "… " + percent + "%", R.color.text_secondary);
            }
        });
    }

    /**
     * 下载视频 → 把封面写进 MP4 元数据 → 写入相册。
     *
     * <p>与烧标题那条路完全不同：这里**一个像素都不动**，只是把封面图按 Apple ilst
     * 规范封进 moov → udta → meta → ilst → covr，同时把 mdat 的 chunk offset
     * 按位移量修正好，保证严格播放器仍能按帧索引。耗时就是"下载 + 一次顺序拷贝"，
     * 和存原片基本一样快（对比：老办法整段重编码实测 175 秒）。
     *
     * <p>相册/播放器认不认这帧封面由对方决定，容器层面是标准写法（ffmpeg 能读出一条
     * mjpeg 封面流）。认不出来也只是没有封面，视频本身完全不受影响；注入失败同样
     * 安全退回存原片。
     *
     * @param coverBytes 封面原始字节（JPEG/PNG），由调用方下载
     */
    private VideoSaveResult saveVideoWithCoverMetadata(String videoUrl, String displayTitle,
                                                       byte[] coverBytes)
            throws Exception {
        File workDir = new File(getCacheDir(), "video_cover_" + System.currentTimeMillis());
        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new Exception("无法创建缓存目录");
        }
        File source = new File(workDir, "source.mp4");
        File output = new File(workDir, "covered.mp4");
        final long timeStart = System.currentTimeMillis();
        long timeAfterDownload = timeStart;
        try {
            postSaveProgress("正在下载视频", 0);
            try (FileOutputStream download = new FileOutputStream(source)) {
                long bytes = downloadVideoForCurrentPlatform(videoUrl, download,
                        new DouyinParser.DownloadCallback() {
                            @Override
                            public void onProgress(int percent) {
                                postSaveProgress("正在下载视频", percent);
                            }

                            @Override
                            public void onSuccess(String filePath) {}

                            @Override
                            public void onError(String message) {}
                        });
                if (bytes <= 0) throw new Exception("服务器返回的视频数据为空");
            }
            timeAfterDownload = System.currentTimeMillis();

            postSaveProgress("正在写入封面", 0);
            boolean injected;
            try {
                injected = Mp4CoverInjector.injectCover(source, output, coverBytes);
            } catch (Throwable t) {
                Log.w("MainActivity", "cover metadata inject failed, fallback to plain save", t);
                injected = false;
            }
            long timeAfterInject = System.currentTimeMillis();
            postSaveProgress("正在写入封面", 100);

            File toSave = injected ? output : source;
            Uri finalUri = insertDownloadedVideoIntoGallery(toSave, displayTitle, "正在写入相册");
            return new VideoSaveResult(finalUri, false, injected,
                    buildPerfNote(timeStart, timeAfterDownload, timeAfterInject,
                            System.currentTimeMillis()));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 下载视频 → 把标题烧进画面左下角 → 写入相册。
     * 每个真实步骤（下载 / 添加标题 / 合并音轨 / 写入相册）独立提示 0–100%。
     * 烧录失败时退回保存原视频，返回 titleBurned=false，保证用户仍能拿到内容。
     */
    /**
     * 下载视频并做画面处理（打标题 / 把封面铺在开头），再写入相册。
     *
     * <p>两种处理都要整段重编码，所以合并成一次转码完成。转码失败会退回"直接存原片"，
     * 绝不因为画面处理失败而丢掉视频。
     *
     * @param displayTitle  写进相册与最近保存用的标题
     * @param titleToBurn   烧进画面的文字；null 表示不打标题
     * @param cover         封面位图；null 表示不铺封面。调用方负责回收
     */
    private VideoSaveResult saveVideoWithEffects(String videoUrl, String displayTitle,
                                                 String titleToBurn, Bitmap cover)
            throws Exception {
        File workDir = new File(getCacheDir(), "video_fx_" + System.currentTimeMillis());
        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new Exception("无法创建缓存目录");
        }
        File source = new File(workDir, "source.mp4");
        File output = new File(workDir, "processed.mp4");
        final boolean hasTitle = !TextUtils.isEmpty(titleToBurn);
        final boolean hasCover = cover != null && !cover.isRecycled();
        final long timeStart = System.currentTimeMillis();
        long timeAfterDownload = timeStart;
        long timeAfterTranscode = timeStart;
        try {
            postSaveProgress("正在下载视频", 0);
            try (FileOutputStream download = new FileOutputStream(source)) {
                long bytes = downloadVideoForCurrentPlatform(videoUrl, download,
                        new DouyinParser.DownloadCallback() {
                            @Override
                            public void onProgress(int percent) {
                                postSaveProgress("正在下载视频", percent);
                            }

                            @Override
                            public void onSuccess(String filePath) {}

                            @Override
                            public void onError(String message) {}
                        });
                if (bytes <= 0) throw new Exception("服务器返回的视频数据为空");
            }
            timeAfterDownload = System.currentTimeMillis();

            final String processingLabel = hasTitle && hasCover ? "正在处理画面"
                    : (hasTitle ? "正在添加标题" : "正在添加封面");
            postSaveProgress(processingLabel, 0);
            try {
                TitleOverlayTranscoder.ProgressCallback transcoderCallback =
                        new TitleOverlayTranscoder.ProgressCallback() {
                            @Override
                            public void onProgress(int stage, int percent) {
                                String label;
                                if (stage == TitleOverlayTranscoder.STAGE_MERGE) {
                                    label = "正在合并音轨";
                                } else if (stage == TitleOverlayTranscoder.STAGE_AUDIO) {
                                    label = "正在调整音量";
                                } else {
                                    label = processingLabel;
                                }
                                postSaveProgress(label, percent);
                            }
                        };
                if (hasTitle && hasCover) {
                    TitleOverlayTranscoder.overlayWithCover(source, output,
                            titleToBurn, cover, transcoderCallback);
                } else if (hasTitle) {
                    TitleOverlayTranscoder.overlay(source, output,
                            titleToBurn, transcoderCallback);
                } else {
                    TitleOverlayTranscoder.applyCover(source, output, cover, transcoderCallback);
                }
            } catch (Exception effectError) {
                Log.w("MainActivity", "video effects failed, fallback to plain save", effectError);
                output.delete();
                Uri fallbackUri =
                        insertDownloadedVideoIntoGallery(source, displayTitle, "正在写入相册");
                return new VideoSaveResult(fallbackUri, false, false,
                        buildPerfNote(timeStart, timeAfterDownload, -1L,
                                System.currentTimeMillis()));
            }
            timeAfterTranscode = System.currentTimeMillis();

            Uri finalUri = insertDownloadedVideoIntoGallery(output, displayTitle, "正在写入相册");
            return new VideoSaveResult(finalUri, hasTitle, hasCover,
                    buildPerfNote(timeStart, timeAfterDownload, timeAfterTranscode,
                            System.currentTimeMillis()));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** 把本地已生成的视频文件写入相册（IS_PENDING → 拷贝 → 转正），拷贝进度独立提示。 */
    private Uri insertDownloadedVideoIntoGallery(File file, String title, String progressLabel)
            throws Exception {
        // 空文件守卫：宁可直接失败让调用方回退，也不要在相册留下一个 0 字节、播不了的条目
        if (file == null || !file.isFile() || file.length() == 0L) {
            throw new Exception("待写入相册的视频文件为空");
        }
        String fileName = sanitizeFileName(title) + "_" + System.currentTimeMillis() + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/" + ALBUM_DIR);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("创建视频条目失败");
        try {
            long totalBytes = file.length();
            postSaveProgress(progressLabel, 0);
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("打开视频输出流失败");
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[128 * 1024];
                    long written = 0L;
                    int read;
                    int lastPercent = -1;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        written += read;
                        int percent = totalBytes <= 0L ? 100
                                : (int) Math.min(100L, written * 100L / totalBytes);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            postSaveProgress(progressLabel, percent);
                        }
                    }
                }
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    /**
     * 保存作品的完整音乐。
     * <p>
     * 关键设计：**直接下载抖音 CDN 上的原始音乐文件并原样落盘，不做任何重编码**，
     * 因此得到的就是源文件本身，不存在代际损失（这是能做到的“最保真”）。
     * 文件格式、扩展名与 MIME 通过文件头字节判定，不靠 URL 猜。
     * 标题用解析到的原音乐名（musicTitle）。
     */
    private void downloadMusic() {
        final String musicUrl = currentMusicUrl;
        if (TextUtils.isEmpty(musicUrl)) {
            Toast.makeText(this, R.string.music_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final String musicTitle = TextUtils.isEmpty(currentMusicTitle)
                ? getString(R.string.music_unknown_title) : currentMusicTitle;
        final String mediaKey = buildMediaKey(currentAwemeId, "music", 0, musicUrl);
        if (getSavedMediaUri(mediaKey) != null) {
            setStatus(getString(R.string.music_duplicate), R.color.color_success);
            Toast.makeText(this, R.string.music_duplicate, Toast.LENGTH_LONG).show();
            return;
        }

        setDownloadControlsEnabled(false);
        isDownloading = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        postSaveProgress("正在下载音乐", 0);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final File workDir = new File(getCacheDir(), "music_" + System.currentTimeMillis());
                try {
                    if (!workDir.mkdirs() && !workDir.isDirectory()) {
                        throw new Exception("无法创建缓存目录");
                    }
                    File raw = new File(workDir, "music.bin");
                    long bytes;
                    try (FileOutputStream download = new FileOutputStream(raw)) {
                        bytes = DouyinParser.downloadAudioToStream(musicUrl, download,
                                new DouyinParser.DownloadCallback() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postSaveProgress("正在下载音乐", percent);
                                    }

                                    @Override
                                    public void onSuccess(String filePath) { }

                                    @Override
                                    public void onError(String message) { }
                                });
                    }
                    if (bytes <= 0L || raw.length() == 0L) {
                        throw new Exception("服务器返回的音乐数据为空");
                    }

                    final String[] format = resolveAudioFormat(raw, musicUrl);
                    final long durationMs = readAudioDurationMs(raw);
                    final Uri uri = insertAudioIntoLibrary(raw, musicTitle, format[0], format[1],
                            "正在写入音乐库");
                    markMediaSaved(mediaKey, uri);
                    recordRecentSave(mediaKey, uri, musicTitle, "音乐", format[1]);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            String duration = formatDuration(durationMs);
                            String message = "已保存《" + musicTitle + "》"
                                    + (duration.isEmpty() ? "" : "（时长 " + duration + "）");
                            setStatus(message + "  ·  " + format[0].toUpperCase(Locale.ROOT)
                                    + " 原格式未重编码，已存入音乐库「" + ALBUM_DIR + "」",
                                    R.color.color_success);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            setStatus("音乐保存失败：" + e.getMessage(), R.color.color_error);
                            Toast.makeText(MainActivity.this,
                                    "音乐保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    deleteRecursively(workDir);
                }
            }
        }).start();
    }

    /** 把本地原始音乐文件写入系统音乐库（IS_PENDING → 拷贝 → 转正），拷贝进度独立提示。 */
    private Uri insertAudioIntoLibrary(File file, String title, String extension, String mime,
                                       String progressLabel) throws Exception {
        if (file == null || !file.isFile() || file.length() == 0L) {
            throw new Exception("待写入音乐库的文件为空");
        }
        String fileName = sanitizeFileName(title) + "_" + System.currentTimeMillis()
                + "." + extension;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/" + ALBUM_DIR);
        // 标记为音乐，否则系统媒体库与音乐 App 不会收录
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("创建音乐条目失败");
        try {
            long totalBytes = file.length();
            postSaveProgress(progressLabel, 0);
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("打开音乐输出流失败");
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[128 * 1024];
                    long written = 0L;
                    int read;
                    int lastPercent = -1;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        written += read;
                        int percent = totalBytes <= 0L ? 100
                                : (int) Math.min(100L, written * 100L / totalBytes);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            postSaveProgress(progressLabel, percent);
                        }
                    }
                }
            }
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    /**
     * 判定音频格式，返回 {扩展名, MIME}。优先用文件头字节（最可靠），
     * 其次回退到直链后缀，最后按抖音音乐的主流格式 mp3 兜底。
     */
    private static String[] resolveAudioFormat(File file, String sourceUrl) {
        String[] detected = detectAudioFormat(file);
        if (detected != null) return detected;
        String fromUrl = extensionFromUrl(sourceUrl);
        if (fromUrl != null) {
            if ("m4a".equals(fromUrl) || "mp4".equals(fromUrl)) {
                return new String[]{"m4a", "audio/mp4"};
            }
            if ("aac".equals(fromUrl)) return new String[]{"aac", "audio/aac"};
            if ("flac".equals(fromUrl)) return new String[]{"flac", "audio/flac"};
            if ("wav".equals(fromUrl)) return new String[]{"wav", "audio/wav"};
            if ("ogg".equals(fromUrl)) return new String[]{"ogg", "audio/ogg"};
            if ("mp3".equals(fromUrl)) return new String[]{"mp3", "audio/mpeg"};
        }
        return new String[]{"mp3", "audio/mpeg"};
    }

    /** 依据文件头字节识别音频容器/编码；识别不出返回 null。 */
    private static String[] detectAudioFormat(File file) {
        byte[] head = new byte[16];
        int read;
        try (FileInputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (Exception e) {
            return null;
        }
        if (read >= 4) {
            String tag = new String(head, 0, 4, StandardCharsets.ISO_8859_1);
            if (tag.startsWith("ID3")) return new String[]{"mp3", "audio/mpeg"};
            if ("fLaC".equals(tag)) return new String[]{"flac", "audio/flac"};
            if ("OggS".equals(tag)) return new String[]{"ogg", "audio/ogg"};
            if ("RIFF".equals(tag)) return new String[]{"wav", "audio/wav"};
            if (read >= 8 && "ftyp".equals(new String(head, 4, 4, StandardCharsets.ISO_8859_1))) {
                // MP4 容器（音频轨通常是 AAC）：抖音的 m4a 与视频原声都是这个
                return new String[]{"m4a", "audio/mp4"};
            }
        }
        if (read >= 2 && (head[0] & 0xff) == 0xff) {
            int second = head[1] & 0xff;
            if ((second & 0xf6) == 0xf0) return new String[]{"aac", "audio/aac"};  // ADTS AAC
            if ((second & 0xe0) == 0xe0 && ((second >> 1) & 0x03) != 0) {
                return new String[]{"mp3", "audio/mpeg"};                          // 无 ID3 头的裸 MP3
            }
        }
        return null;
    }

    private static String extensionFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return null;
            String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            return extension.matches("[a-z0-9]{1,5}") ? extension : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读本地音频时长（毫秒）。用于让用户确认拿到的是完整曲目而不是平台预览片段。 */
    private static long readAudioDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String formatDuration(long durationMs) {
        if (durationMs <= 0L) return "";
        long totalSeconds = (durationMs + 500L) / 1000L;
        return String.format(Locale.CHINA, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private void setStatus(String text, int colorRes) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(colorRes));
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "douyin_video";
        // 移除文件名中的非法字符
        return name.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
    }

    /**
     * 去掉标题里的 #话题标签（如 “#日常 #vlog”，兼容全角＃）。
     * 全部被删时回退为“抖音视频”。
     * <p>
     * 保持 v1.10.18 的正则不变：{@code [＃#][^\s＃#]+[＃#]?} 能把抖音真实使用的
     * “空格分隔”（#日常 #vlog）和“井号闭合”（#话题#）两种形式都完整吃掉。
     * 曾试过改成 {@code [＃#]+[^\s＃#]+} 以清理 “#日常#vlog” 形式的残渣，实测会让
     * “标题文字#话题#” 残留 “#”、“全角＃话题＃结尾” 多吃掉 “结尾”，属净倒退，已回退。
     */
    private static String stripHashtagTopics(String title) {
        if (title == null || title.trim().isEmpty()) return "douyin_video";
        String stripped = title
                .replaceAll("[＃#][^\\s＃#]+[＃#]?", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped.isEmpty() ? "抖音视频" : stripped;
    }

}
