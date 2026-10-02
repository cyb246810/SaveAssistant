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
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
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
    /**
     * 扫描可传输用的线程池：**刻意与 {@link #transferExecutor} 分开**。
     * 传输是单线程串行队列，如果扫描排在同一条队列上，传输途中切页刷新
     * 会被排在所有上传任务后面，看起来像「刷新没反应」。
     */
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    /** 已有一次扫描在跑：切页/连点会重复触发，挡掉排队的那几次。 */
    private final java.util.concurrent.atomic.AtomicBoolean scanRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
    /** 传输列表的缩略图缓存：key 是媒体 Uri 字符串。 */
    private final android.util.LruCache<String, Bitmap> transferThumbCache =
            new android.util.LruCache<>(80);
    /** 单张缩略图的像素边长（dp 转 px 后使用）。 */
    private static final int TRANSFER_THUMB_DP = 48;

    private String currentVideoUrl = null;
    private String currentAwemeId = null;
    private String currentTitle = null;
    private String currentMusicUrl = null;
    private String currentMusicTitle = null;
    /**
     * 「仅保存音乐」当前的工作模式：true = 没有独立音乐资源（视频号），
     * 需要先存原片再把音轨抽出来。
     */
    private boolean currentMusicViaExtraction = false;
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

    /**
     * 记住电脑连接的偏好文件。
     *
     * 存两份：
     * - {@link #PC_PREFS_LAST} 是「最后一次成功连接的地址」，**不因连接失败而清除** ——
     *   每次开机都拿它去直连（先 ping 再 announce），命中就不用再广播搜索了；
     * - 其余键是「最后一次搜索发现的候选列表」，只在**直连失败之后**当作备选挨个试。
     *   之所以要分开存：手机换网后 IP 会变，但**广播搜索本身在部分路由器上会被拦**，
     *   能把上次搜到的几个候选留着，就多一层不依赖广播的兜底。
     */
    private static final String PC_PREFS = "pc_connection_v1";
    private static final String PC_PREFS_LAST = "last_addr";
    /** 上一次搜索发现的候选地址，JSON 数组字符串，形如 ["192.168.1.20:18765", …]。 */
    private static final String PC_PREFS_DISCOVERED = "discovered_addrs";
    private static final String PC_PREFS_DISCOVERED_AT = "discovered_at";
    /** 候选列表最多留几个：只作兜底，留太多会让「直连失败」后白等很久。 */
    private static final int PC_DISCOVERED_MAX = 6;
    /** 候选列表的保鲜期。太久之前的候选基本是换过网络的残留，试也是白试。 */
    private static final long PC_DISCOVERED_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    /** 自动连接互斥：传输页可能因切页/回前台被多次触发，只允许一次自动流程在跑。 */
    private final java.util.concurrent.atomic.AtomicBoolean autoConnectRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 本次「进入传输页」是否已经跑过自动连接。
     * 只在用户**主动点「连接」/「查找电脑」**时复位、或进程重启时复位；
     * 刻意不在切页时复位 —— 否则来回切两次页就重复搜索了，正是用户不想要的。
     */
    private boolean autoConnectDoneThisSession = false;

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
            @Override public void onClick(View v) {
                // 用户主动要求重新找：清掉「本会话已自动连过」的标记，允许再搜索一轮。
                autoConnectDoneThisSession = true;
                findPc();
            }
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

    /** UDP 广播查找电脑，找到后自动连接。用户手动点「查找电脑」走这条。 */
    private void findPc() {
        startPcConnect(false);
    }

    /**
     * 进入传输页时的自动连接：**先用上次的地址直连，直连不成才去搜**。
     *
     * 顺序刻意如此 —— 广播搜索要等 2.8 秒（多网卡还要多等），而查 IP 缓存是零成本的：
     * 家里/公司的 WiFi 一个月也不会变一次 IP，绝大多数情况下用户根本没有等待感。
     * 只有确实连不上（换网、电脑重启换了 IP、电脑没开）才退化成搜索。
     */
    private void autoConnectPc() {
        startPcConnect(true);
    }

    /**
     * 电脑连接的统一入口。
     *
     * @param auto true = 进入传输页自动触发；false = 用户手动点「查找电脑」。
     */
    private void startPcConnect(final boolean auto) {
        if (pcConnected) return;                       // 已经连着，不折腾
        if (!autoConnectRunning.compareAndSet(false, true)) return;  // 已有一次在跑

        tvPcStatus.setText(auto ? R.string.transfer_auto_connecting
                : R.string.transfer_connecting);
        // 自动流程期间把「查找 / 连接」按钮禁掉，避免用户等不及手动再点一次、
        // 两个流程抢同一个单线程 executor 反而更慢。
        setPcConnectButtonsEnabled(false);

                transferExecutor.execute(new Runnable() {
            @Override public void run() {
                String result = null;   // null = 成功；否则是失败原因
                boolean connected = false;
                try {
                    if (auto) {
                        result = tryCachedAddresses();
                        if (result == null) { connected = true; return; }
                    }
                    result = doDiscoverAndConnect(auto);
                    connected = (result == null);
                } finally {
                    final String finalResult = result;
                    final boolean finalConnected = connected;
                    autoConnectRunning.set(false);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setPcConnectButtonsEnabled(true);
                            if (finalResult != null) {
                                tvPcStatus.setText(getString(
                                        R.string.transfer_connect_failed_fmt, finalResult));
                                applyPcConnectionUi();
                            } else if (!finalConnected) {
                                // 自动模式下没搜到电脑：**必须把「正在自动连接…」这句话收掉**，
                                // 否则它会一直挂在那里，看起来像卡死了。
                                // 这里刻意不用红色报错——电脑没开机是很常见的情况，
                                // 把输入区展开让用户自己填地址就够了。
                                tvPcStatus.setText(R.string.transfer_auto_idle);
                                applyPcConnectionUi();
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * 按「上次成功的地址 → 上次搜索到的候选」依次试探。
     *
     * @return null 表示某个地址连通并已自报家门；否则返回最后一条失败原因。
     */
    private String tryCachedAddresses() {
        final java.util.List<String> candidates = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences(PC_PREFS, MODE_PRIVATE);
        String last = prefs.getString(PC_PREFS_LAST, "");
        if (last != null && !last.trim().isEmpty()) {
            candidates.add(last.trim());
        }
        for (String addr : readDiscoveredAddrs(prefs)) {
            if (!candidates.contains(addr)) {
                candidates.add(addr);
            }
        }
        if (candidates.isEmpty()) {
            return "没有可用的历史地址";   // 首次使用：直接走搜索
        }

        String lastError = null;
        for (final String addr : candidates) {
            final String[] hp = parseHostPort(addr);
            if (hp[0].isEmpty()) continue;
            try {
                final int parsedPort = Integer.parseInt(hp[1]);
                PcTransferClient.ping(hp[0], parsedPort, 2000);
                final PcTransferClient.PcInfo info = PcTransferClient.announce(
                        hp[0], parsedPort, Build.MODEL, Build.MANUFACTURER,
                        localIpForDisplay(), 3000);
                final String host = hp[0];
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        etPcAddr.setText(host + ":" + parsedPort);
                        markPcConnected(host, parsedPort, info);
                    }
                });
                return null;
            } catch (Exception e) {
                lastError = PcTransferClient.friendlyError(e);
            }
        }
        return lastError;
    }

    /**
     * 广播搜索并连接第一个应答的电脑。
     *
     * @return null 表示成功（或在自动模式下「搜不到」已被当作正常结果，静默留着输入区）；
     *         否则返回要展示的失败原因。
     */
    private String doDiscoverAndConnect(final boolean auto) {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("SaveAssistantDiscovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            final java.util.List<String[]> found = PcTransferClient.discover(pcPort, 2800);
            if (found == null || found.isEmpty()) {
                // 自动模式下搜不到电脑是**很常见**的（电脑没开机 / 不在同一 WiFi），
                // 这时保持输入区展开让用户能手动填地址就够了，不需要红字报错吓人。
                return auto ? null : getString(R.string.transfer_not_found);
            }
            final String[] first = found.get(0);
            final String label = (first[1] == null || first[1].isEmpty()) ? first[0] : first[1];
            final String host = first[0];
            final int port = pcPort;
            try {
                PcTransferClient.ping(host, port, 3000);
                final PcTransferClient.PcInfo info = PcTransferClient.announce(
                        host, port, Build.MODEL, Build.MANUFACTURER, localIpForDisplay(), 3000);
                saveDiscoveredAddrs(found, port);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        etPcAddr.setText(host + ":" + port);
                        tvPcStatus.setText(getString(R.string.transfer_found_fmt,
                                label + "（" + host + "）"));
                        markPcConnected(host, port, info);
                    }
                });
                return null;
            } catch (Exception e) {
                return PcTransferClient.friendlyError(e);
            }
        } finally {
            if (lock != null && lock.isHeld()) {
                lock.release();
            }
        }
    }

    /** 连接成功后的统一收尾：记状态、记地址、收起连接区、拉可传输列表。 */
    private void markPcConnected(String host, int port, PcTransferClient.PcInfo info) {
        pcHost = host;
        pcPort = port;
        pcConnected = true;
        autoConnectDoneThisSession = true;
        String label = (info == null || info.deviceName.isEmpty()) ? host : info.deviceName;
        tvPcStatus.setText(getString(R.string.transfer_connected_fmt,
                label + "（" + host + "）"));
        applyPcConnectionUi();
        // 只记「连成功过」的地址 —— 记下连不上的地址，下次开机白等一轮超时。
        getSharedPreferences(PC_PREFS, MODE_PRIVATE).edit()
                .putString(PC_PREFS_LAST, host + ":" + port)
                .apply();
        refreshTransferList();
    }

    /** 广播搜索到的候选列表落盘（作直连失败后的备选）。 */
    private void saveDiscoveredAddrs(java.util.List<String[]> found, int port) {
        org.json.JSONArray arr = new org.json.JSONArray();
        int n = 0;
        for (String[] item : found) {
            if (item == null || item.length == 0 || item[0] == null || item[0].isEmpty()) continue;
            if (n++ >= PC_DISCOVERED_MAX) break;
            arr.put(item[0] + ":" + port);
        }
        if (arr.length() == 0) return;
        getSharedPreferences(PC_PREFS, MODE_PRIVATE).edit()
                .putString(PC_PREFS_DISCOVERED, arr.toString())
                .putLong(PC_PREFS_DISCOVERED_AT, System.currentTimeMillis())
                .apply();
    }

    /** 读取候选列表；过期（超过 {@link #PC_DISCOVERED_TTL_MS}）就当作没有。 */
    private List<String> readDiscoveredAddrs(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        long at = prefs.getLong(PC_PREFS_DISCOVERED_AT, 0L);
        if (at <= 0 || System.currentTimeMillis() - at > PC_DISCOVERED_TTL_MS) {
            return out;
        }
        String raw = prefs.getString(PC_PREFS_DISCOVERED, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {
            // 存坏了就当没有，不该因为一条脏缓存让自动连接整个失效。
        }
        return out;
    }

    /** 自动连接流程进行中禁用两个按钮，防止用户重复触发。 */
    private void setPcConnectButtonsEnabled(boolean enabled) {
        View find = findViewById(R.id.btn_pc_find);
        View connect = findViewById(R.id.btn_pc_connect);
        if (find != null) find.setEnabled(enabled);
        if (connect != null) connect.setEnabled(enabled);
    }

    /** 按 "IP" 或 "IP:端口" 连接电脑并联调配对。用户手动点「连接」走这条。 */
    private void connectPc(final String addr) {
        final String cleaned = addr == null ? "" : addr.trim();
        if (cleaned.isEmpty()) {
            tvPcStatus.setText(R.string.transfer_not_found);
            return;
        }
        autoConnectDoneThisSession = true;   // 用户自己出手了，别再自动跑一遍
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
                    final int port = parsedPort;
                    final String host = hp[0];
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            markPcConnected(host, port, info);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pcConnected = false;
                            tvPcStatus.setText(getString(R.string.transfer_connect_failed_fmt,
                                    PcTransferClient.friendlyError(e)));
                            applyPcConnectionUi();
                        }
                    });
                }
            }
        });
    }

    /** 自动连接已排队未执行：防重入（页面连点、onResume 与切页同时触发）。 */
    private boolean autoConnectingPc = false;

    /**
     * 进入传输页时自动连接电脑（用上次的地址直连，连不上才搜索）。
     *
     * 只在**本次进入应用后第一次**进入传输页时跑；连上之后就一直复用那条连接，
     * 除非中途断开（那时 {@code pcConnected} 会被置假，下次进页面会自动重来一轮）。
     * 用户也可以随时手动点「查找电脑」强制重新搜索。
     */
    private void maybeAutoConnectPc() {
        if (pcConnected) return;
        if (autoConnectDoneThisSession) return;
        if (autoConnectRunning.get()) return;
        if (autoConnectingPc) return;
        if (etPcAddr == null || tvPcStatus == null) return;
        autoConnectDoneThisSession = true;
        autoConnectingPc = true;
        // 等布局与「最近保存」的扫描先落地：刚进应用时后台正在扫相册，
        // 这时抢网会跟扫描抢 IO，反而让「已连接」来得更慢。
        tvPcStatus.postDelayed(new Runnable() {
            @Override public void run() {
                autoConnectingPc = false;
                if (transferMode && !pcConnected) {
                    autoConnectPc();
                }
            }
        }, 260L);
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

    /**
     * 重新扫描可传输内容。
     *
     * 扫描要查三张表 + 逐条 open 校验，**不能放在主线程**（相册大时会卡住界面），
     * 所以走 {@link #scanExecutor}，扫完回主线程渲染。
     * 连点/切页会重复触发，用 {@link #scanRunning} 挡掉排队的那几次。
     */
    private void refreshTransferList() {
        if (transferItems == null) return;
        if (!scanRunning.compareAndSet(false, true)) return;
        scanExecutor.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<TransferItem> scanned = new ArrayList<>();
                String[] proj = {"_id", "display_name", "_size", "mime_type"};
                collectMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, "video", scanned);
                collectMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "image", scanned);
                collectMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "audio", scanned);
                if (scanned.isEmpty()) {
                    // 兜底：应用自己的最近保存记录
                    for (RecentRecord r : getValidRecentRecords()) {
                        if (r == null || TextUtils.isEmpty(r.uri)) continue;
                        String mime = r.mimeType == null ? "" : r.mimeType;
                        String cat = mime.startsWith("video") ? "video"
                                : (mime.startsWith("audio") ? "audio" : "image");
                        scanned.add(new TransferItem(Uri.parse(r.uri),
                                ensureExtension(r.title, mime, cat), 0, cat));
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        scanRunning.set(false);
                        transferItems.clear();
                        transferItems.addAll(scanned);
                        transferExpanded = false;
                        renderTransferList();
                    }
                });
            }
        });
    }

    private void collectMedia(Uri base, String[] proj, String category,
                              ArrayList<TransferItem> out) {
        Cursor c = queryMediaSafe(base, proj);
        if (c == null) return;
        try {
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
                // 查得到不等于拿得到：MediaStore 偶尔留有「索引还在、文件已没了」的
                // 僵尸条目（外部删除、SD 卡拔出、清理软件）。真去 open 一下确认，
                // 免得用户等下点传输时才发现「文件已不存在」。
                if (!mediaUriReadable(u)) continue;
                out.add(new TransferItem(u, ensureExtension(name, mime, category),
                        size, category));
            }
        } catch (Exception e) {
            // 无媒体或无权限：忽略，走兜底
        } finally {
            c.close();
        }
    }

    /**
     * 带「剔除已删除 / 半成品」条件的媒体查询，**并在老设备上自动降级**。
     *
     * <p>要过滤掉两类不该出现在传输列表里的条目：
     * <ul>
     *   <li>{@code is_pending=1}：应用自己保存媒体时是「IS_PENDING=1 → 拷贝 → 0」，
     *       半成品不能传；</li>
     *   <li>{@code is_trashed=1}（API 30+）：进了「最近删除」的条目在 MediaStore 里
     *       仍可查到，但用户以为已经删了，不该再出现在待传列表。</li>
     * </ul>
     *
     * <p><b>为什么必须降级而不能只靠 try/catch</b>：{@code is_pending} 这个列在
     * API 29 上**只加到了 Video / Images / Downloads 表，Audio 表并没有**。
     * 如果把带 {@code is_pending=0} 的 selection 直接发给 Audio 表，
     * 老设备会抛 {@code IllegalArgumentException: Invalid column is_pending} ——
     * 而外层那个 {@code catch (Exception)} 会**把整个音频列表静默吃掉**，
     * 表现为「音乐莫名不见了」，极难排查。所以这里失败就退回不带条件的查询，
     * 宁可多列几条（后面还有 {@code mediaUriReadable} 兜底），也不能整类消失。
     */
    private Cursor queryMediaSafe(Uri base, String[] proj) {
        String selection = MediaStore.MediaColumns.IS_PENDING + "=0";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            selection += " AND " + MediaStore.MediaColumns.IS_TRASHED + "=0";
        }
        try {
            Cursor c = getContentResolver().query(base, proj, selection, null,
                    "date_added DESC");
            if (c != null) return c;
        } catch (Exception ignored) {
            // 落到下面降级：这台设备/这张表不认这些列。
        }
        try {
            return getContentResolver().query(base, proj, null, null, "date_added DESC");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 该媒体是否**真的读得到**（不只是索引里还在）。
     *
     * {@code openFileDescriptor} 是唯一可靠的判据：MediaStore 的查询结果可能来自
     * 尚未刷新的索引，也可能文件已被外部删除 / 存储被卸载。
     * 只对极少数条目失败，开销可以接受。
     */
    private boolean mediaUriReadable(Uri uri) {
        if (uri == null) return false;
        try (android.content.res.AssetFileDescriptor afd =
                     getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd != null;
        } catch (Exception e) {
            return false;
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

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(6), dp(2), dp(6));

            final CheckBox cb = new CheckBox(this);
            cb.setButtonTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
            cb.setChecked(item.checked);
            cb.setPadding(0, 0, 0, 0);
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView thumb = new ImageView(this);
            int thumbPx = dp(TRANSFER_THUMB_DP);
            LinearLayout.LayoutParams thumbLp =
                    new LinearLayout.LayoutParams(thumbPx, thumbPx);
            thumbLp.setMargins(dp(2), 0, dp(10), 0);
            thumb.setLayoutParams(thumbLp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bindTransferThumb(thumb, item);
            row.addView(thumb);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(item.name);
            name.setTextSize(13);
            name.setTextColor(getColor(R.color.text_primary));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(name);
            if (item.size > 0) {
                TextView sub = new TextView(this);
                sub.setText(fmtSize(item.size));
                sub.setTextSize(11);
                sub.setTextColor(getColor(R.color.text_secondary));
                texts.addView(sub);
            }
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 点整行也能勾选：行比复选框大，手指不容易点空
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.setChecked(!cb.isChecked());
                }
            });
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    item.checked = v;
                }
            });
            transferList.addView(row);
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

    /**
     * 给一行的缩略图占位并异步加载。
     *
     * <p>用系统的 {@code loadThumbnail} 而不是自己解码原图：视频首帧由 MediaStore 缓存，
     * 又快又不占内存。加载放在 previewExecutor 上，回来时确认那一行还在展示同一个文件才贴图，
     * 避免列表滚动/重建后贴错行。
     */
    private void bindTransferThumb(final ImageView view, final TransferItem item) {
        final String key = item.uri.toString();
        Bitmap cached = transferThumbCache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(transferThumbPlaceholder(item.category));
        view.setTag(key);
        final int px = dp(TRANSFER_THUMB_DP);
        previewExecutor.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp;
                try {
                    bmp = getContentResolver().loadThumbnail(item.uri,
                            new Size(px, px), null);
                } catch (Exception e) {
                    bmp = null;   // 音频没有缩略图，或文件已被删除
                }
                if (bmp == null) return;
                transferThumbCache.put(key, bmp);
                final Bitmap result = bmp;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (key.equals(view.getTag())) {
                            view.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    /** 拿不到缩略图时的占位底色（按类别区分，避免一片死灰）。 */
    private android.graphics.drawable.Drawable transferThumbPlaceholder(String category) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(6));
        int color;
        switch (category == null ? "" : category) {
            case "video":
                color = 0xFF2C1A20;
                break;
            case "image":
                color = 0xFF1A2430;
                break;
            case "audio":
                color = 0xFF182A26;
                break;
            default:
                color = 0xFF24262B;
        }
        d.setColor(color);
        return d;
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
                int skipped = 0;
                final int total = selected.size();
                // 用数组当可变容器，方便在匿名内部类里读取（lambda/内部类要求 final）
                final String[] firstError = new String[1];
                // 传输过程中才发现已被删除的条目，结束后从列表里摘掉
                final List<TransferItem> goneItems = new ArrayList<>();
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
                        final PcTransferClient.Progress progress = new PcTransferClient.Progress() {
                            @Override public void onProgress(long sent, long all) {
                                final int pct = totalBytes > 0
                                        ? (int) (sent * 100 / totalBytes) : 0;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        transferProgressBar.setProgress(pct);
                                    }
                                });
                            }
                        };
                        // 大文件对网络抖动特别敏感，失败自动重开输入流重试一次。
                        // 注意每次重试都必须重新 openInputStream：流已经被读过一截，回不去。
                        final int maxAttempts = 2;
                        for (int attempt = 1; ; attempt++) {
                            if (in != null) {
                                try { in.close(); } catch (Exception ignored) { }
                                in = null;
                            }
                            in = getContentResolver().openInputStream(item.uri);
                            if (in == null) throw new Exception("无法读取文件");
                            try {
                                PcTransferClient.upload(pcHost, pcPort, in, size, item.name,
                                        item.category, Build.MODEL, 8000, 600000, progress);
                                break;
                            } catch (Exception uploadError) {
                                if (attempt >= maxAttempts) throw uploadError;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        transferProgressBar.setProgress(0);
                                        tvTransferProgress.setText(getString(
                                                R.string.transfer_retrying_fmt, item.name));
                                    }
                                });
                            }
                        }
                        ok++;
                    } catch (Exception e) {
                        // 文件在相册里被删了：这不算「传输失败」，而是这条记录已经作废。
                        // 直接从列表移除并计入「跳过」，免得用户反复看到同一个不存在的文件。
                        if (!mediaUriExists(item.uri)) {
                            skipped++;
                            goneItems.add(item);
                        } else {
                            fail++;
                            // 以前这里把异常整个吞掉，用户只看得到「失败 1 个」却不知道原因，
                            // 结果就是谁也没法定位。现在把第一条失败原因留下来显示。
                            if (firstError[0] == null) {
                                firstError[0] = PcTransferClient.friendlyError(e);
                            }
                        }
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Exception ignored) { }
                        }
                    }
                }
                final int okFinal = ok;
                final int failFinal = fail;
                final int skippedFinal = skipped;
                final String errFinal = firstError[0];
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
                        // 已删除的条目直接摘掉，下一次打开列表不会还挂着
                        if (!goneItems.isEmpty()) {
                            transferItems.removeAll(goneItems);
                            renderTransferList();
                        }
                        String msg = getString(R.string.transfer_all_done_fmt, okFinal, failFinal);
                        if (skippedFinal > 0) {
                            msg = msg + "\n" + getString(R.string.transfer_skipped_gone_fmt,
                                    skippedFinal);
                        }
                        if (failFinal > 0 && errFinal != null) {
                            msg = msg + "\n" + getString(R.string.transfer_first_error_fmt, errFinal);
                        }
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
            maybeAutoConnectPc();
            // 每次进传输页都重新扫一遍：用户往往是在相册里删完东西才切过来的，
            // 自动重扫能让列表与相册保持一致，不需要再手动点「刷新」。
            refreshTransferList();
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
        currentMusicViaExtraction = false;
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
                // 视频号没有独立音乐资源（音频只存在于视频轨里），单条作品也无需拼接：
                // 拼接按钮始终不出现；但「仅保存音乐」照给——走「存原片 → 抽出音轨」这条路，
                // 抽出来的就是原音轨本身，不重编码，与视频里那条一模一样。
                hideStitchButton();
                if (!TextUtils.isEmpty(result.videoUrl)) {
                    currentMusicViaExtraction = true;
                    currentMusicTitle = shortTitle;
                    showSaveMusicButtonAnimated();
                } else {
                    hideSaveMusicButton();
                }

                String message = "视频号解析成功！" + (TextUtils.isEmpty(result.author)
                        ? "" : "@" + result.author + " ") + "可保存原片，也可仅保存音乐";
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
        currentMusicViaExtraction = false;
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
        // 用户很可能是在相册里直接删掉作品的。回到应用时重新校验一遍记录：
        // getValidRecentRecords() 会比对相册、把失效的记录与防重复标记一起清掉，
        // 然后刷新「最近保存」与「可传输内容」——这样记录会跟着相册同步消失，
        // 传输时也就不会再撞见「文件已不存在」。
        previewExecutor.execute(new Runnable() {
            @Override
            public void run() {
                getValidRecentRecords();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        renderRecentHistory();
                        if (transferMode) {
                            refreshTransferList();
                        }
                    }
                });
            }
        });
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
        currentMusicViaExtraction = false;
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

    /** 包名里出现这些词就当成相册（小写比对）。
     *  覆盖主流机型的相册包名：AOSP/小米 gallery、华为 photos、三星 gallery3d 等。
     *  **刻意不含 "media"** —— 那个词太泛，容易命中一些媒体工具而不是相册。 */
    private static final String[] GALLERY_PACKAGE_HINTS = {
            "gallery", "photos", "album", "picture"
    };

    /**
     * 打开一条已保存的媒体。
     *
     * <p>要的是「进系统相册」。但 {@code ACTION_VIEW} 只给一个通配类型
     * （{@code video/*}）时，相册和文件管理器都匹配得上，系统会弹选择框；
     * 用户一旦点了「文件管理」并勾上「始终」，以后就再也进不了相册 ——
     * 「查看打开的总是文件里的那个播放器」就是这么来的。
     *
     * <p>所以这里**主动挑出相册**再 {@code setPackage} 定向打开。挑不出来、
     * 或相册拒收这一条，就退回普通的 {@code ACTION_VIEW}。
     * <b>最差也只是恢复到以前的行为</b>，不会因为识别不准反而打不开。
     */
    private void openRecentMedia(RecentRecord record) {
        Uri uri = Uri.parse(record.uri);
        if (!mediaUriExists(uri)) {
            removeRecentRecordInternal(record, true);
            renderRecentHistory();
            Toast.makeText(this, "相册中已找不到这个文件，记录已移除", Toast.LENGTH_LONG).show();
            return;
        }
        String mime = resolveRecentMime(record);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // 音频没有「相册」可言，直接交给系统音乐/播放器
        if (!mime.startsWith("audio/")) {
            String gallery = findGalleryPackage();
            if (gallery != null) {
                Intent targeted = new Intent(intent);
                targeted.setPackage(gallery);
                try {
                    startActivity(targeted);
                    return;
                } catch (Exception ignored) {
                    // 个别机型的相册不认从别的应用来的条目，退回通用方式就好
                }
            }
        }

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "手机上没有可查看此文件的应用", Toast.LENGTH_LONG).show();
        }
    }

    /** 记录里的 MIME。历史记录可能是旧版本写的、没有这个字段，按类型标签兜底。 */
    private String resolveRecentMime(RecentRecord record) {
        if (record == null) return "video/*";
        if (!TextUtils.isEmpty(record.mimeType)) return record.mimeType;
        if (record.typeLabel != null && record.typeLabel.contains("照片")) return "image/*";
        if (record.typeLabel != null && record.typeLabel.contains("音乐")) return "audio/*";
        return "video/*";
    }

    /**
     * 找系统相册的包名；找不到返回 null（调用方会退回通用 ACTION_VIEW）。
     *
     * <p>判据是「**同时**能处理 {@code image/*} 和 {@code video/*} 的应用」：
     * 相册是唯一同时认这两类的；单纯视频播放器只认 video，文件管理器一般
     * 不会为图片注册 ACTION_VIEW。取交集能一次性把两者都排除掉。
     *
     * <p>候选多于一个时：先看哪个是「图片的默认打开方式」（多数机型上就是相册），
     * 再看包名里像不像相册，都不像就按包名排序取第一个 ——
     * **这里刻意不弹选择框**，弹了就等于没解决问题。
     */
    private String findGalleryPackage() {
        PackageManager pm = getPackageManager();
        Set<String> candidates = new HashSet<>(viewerPackages(pm, "image/*"));
        candidates.retainAll(viewerPackages(pm, "video/*"));
        if (candidates.isEmpty()) return null;

        String preferred = null;
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setType("image/*");
            probe.addCategory(Intent.CATEGORY_DEFAULT);
            ResolveInfo resolved = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null) {
                preferred = resolved.activityInfo.packageName;
            }
        } catch (Exception ignored) {
            // 查询失败不致命，"默认应用"这一档跳过即可
        }
        return pickGalleryPackage(candidates, preferred);
    }

    /**
     * 从候选里挑一个相册包名；挑不出来返回 null。
     *
     * <p><b>纯函数</b>，不碰 PackageManager —— 「挑谁」这套优先级是最容易改坏、
     * 又最难在真机上发现的部分（挑错了只是打开别的应用，不会报错），
     * 所以单拎出来让 JVM 单测能锁住。
     *
     * <p>优先级：① 包名里带 gallery/photos/album… 的 —— 这是「系统相册」最直接的
     * 信号；② 候选里那位「图片的默认打开方式」；③ 按包名排序取第一个。
     *
     * <p>**为什么包名特征排在「默认应用」前面**：默认应用只是间接线索。
     * 用户完全可能把某个文件管理器设成图片默认，照它走就等于没修这个问题；
     * 而包名里带 gallery/photos 的，基本就是系统相册本身。
     *
     * <p>**刻意不弹选择框** —— 弹了就等于没解决问题。
     */
    static String pickGalleryPackage(Set<String> candidates, String preferredPackage) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        for (String hint : GALLERY_PACKAGE_HINTS) {
            for (String pkg : sorted) {
                if (pkg.toLowerCase(Locale.ROOT).contains(hint)) return pkg;
            }
        }
        if (preferredPackage != null && candidates.contains(preferredPackage)) {
            return preferredPackage;
        }
        return sorted.get(0);
    }

    /** 能处理某个 MIME 的 ACTION_VIEW 的包名集合。 */
    private static Set<String> viewerPackages(PackageManager pm, String mime) {
        Set<String> out = new HashSet<>();
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setType(mime);
            probe.addCategory(Intent.CATEGORY_DEFAULT);
            List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
            if (resolved != null) {
                for (ResolveInfo info : resolved) {
                    if (info != null && info.activityInfo != null
                            && !TextUtils.isEmpty(info.activityInfo.packageName)) {
                        out.add(info.activityInfo.packageName);
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到就当没有候选，调用方会退回通用方式
        }
        return out;
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
     * 视频号专用：没有独立音乐地址时，先存原片再把音轨抽出来。
     *
     * <p>刻意**复用「保存视频」那条已验证的链路**（{@link #saveVideoToGallery} + 平台分发下载），
     * 而不是另写一套网络代码——视频号的 CDN 白名单、Referer、重定向都已在那边处理妥当。
     * 抽完音轨后，视频原片按用户自己的选择保留或删除（弹窗里选），不作主张。
     */
    private void extractMusicFromCurrentVideo() {
        final String videoUrl = TextUtils.isEmpty(currentChannelVideoUrl)
                ? currentVideoUrl : currentChannelVideoUrl;
        if (TextUtils.isEmpty(videoUrl)) {
            Toast.makeText(this, R.string.music_none, Toast.LENGTH_SHORT).show();
            return;
        }
        // 同一个作品抽出来的音乐是同一份，用视频地址做去重键
        final String mediaKey = buildMediaKey(currentAwemeId, "music_extract", 0, videoUrl);
        if (getSavedMediaUri(mediaKey) != null) {
            setStatus(getString(R.string.music_duplicate), R.color.color_success);
            Toast.makeText(this, R.string.music_duplicate, Toast.LENGTH_LONG).show();
            return;
        }

        final String rawTitle = TextUtils.isEmpty(currentMusicTitle)
                ? currentTitle : currentMusicTitle;
        final String musicTitle = TextUtils.isEmpty(rawTitle)
                ? getString(R.string.music_unknown_title) : rawTitle;

        setDownloadControlsEnabled(false);
        isDownloading = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        setStatus("正在保存原片… 0%", R.color.text_secondary);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final File workDir = new File(getCacheDir(),
                        "music_extract_" + System.currentTimeMillis());
                Uri videoUri = null;
                File audio = null;
                long durationUs = 0L;
                try {
                    if (!workDir.mkdirs() && !workDir.isDirectory()) {
                        throw new Exception("无法创建缓存目录");
                    }
                    // 第一步：把原片拿到手（复用已在用的平台下载链路）
                    videoUri = saveVideoToGallery(videoUrl, musicTitle, "_原片",
                            new DouyinParser.DownloadCallback() {
                                @Override
                                public void onProgress(final int percent) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            progressBar.setProgress(percent);
                                            setStatus("正在保存原片… " + percent + "%",
                                                    R.color.text_secondary);
                                        }
                                    });
                                }

                                @Override public void onSuccess(String filePath) { }
                                @Override public void onError(String message) { }
                            });

                    // 第二步：把音轨原样抽出来（不重编码）
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progressBar.setProgress(100);
                            setStatus("正在提取音轨（不重编码）…", R.color.text_secondary);
                        }
                    });
                    AudioExtractResult extracted = extractAudioFromVideoUri(videoUri, workDir);
                    audio = extracted.file;
                    // 时长以容器里读到的为准（比「最后一帧时间戳」准），
                    // 抽轨自己报的那个只作兜底
                    durationUs = readAudioDurationUs(audio);
                    if (durationUs <= 0L) durationUs = extracted.durationUs;
                    // 扩展名与 MIME 用抽轨时就知道的真值，不再靠文件头猜
                    final String[] format = new String[]{extracted.extension, extracted.mime};
                    final long bitrate = readAudioBitrate(audio);
                    final long durationMs = durationUs > 0L ? durationUs / 1000L
                            : readAudioDurationMs(audio);

                    final Uri audioUri = insertAudioIntoLibrary(audio, musicTitle,
                            format[0], format[1], "正在写入音乐库");
                    markMediaSaved(mediaKey, audioUri);
                    recordRecentSave(mediaKey, audioUri, musicTitle, "音乐", format[1]);

                    final Uri savedVideo = videoUri;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);

                            String duration = formatDuration(durationMs);
                            StringBuilder quality = new StringBuilder();
                            quality.append(format[0].toUpperCase(Locale.ROOT));
                            if (bitrate > 0) {
                                quality.append(" · ").append(bitrate / 1000L).append(" kbps");
                            }
                            quality.append(" · 从视频原音轨直提，未重编码");

                            String message = "已保存《" + musicTitle + "》"
                                    + (duration.isEmpty() ? "" : "（" + duration + "）")
                                    + "：" + quality + "；" + getString(R.string.music_extract_note);
                            setStatus(message + "  ·  已存入音乐库「" + ALBUM_DIR + "」",
                                    R.color.color_success);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            askKeepExtractedVideo(savedVideo, musicTitle);
                        }
                    });
                } catch (final Exception e) {
                    // 提取失败时不留半成品：把刚存下的原片也撤掉，避免相册里多出无意义的文件
                    if (videoUri != null) {
                        try { getContentResolver().delete(videoUri, null, null); }
                        catch (Exception ignored) { }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setDownloadControlsEnabled(true);
                            isDownloading = false;
                            progressBar.setVisibility(View.GONE);
                            String reason = e.getMessage() == null ? "未知原因" : e.getMessage();
                            // 视频安静音是常见情况，把原因说清楚，别让人以为是软件坏了
                            setStatus("音乐提取失败：" + reason, R.color.color_error);
                            Toast.makeText(MainActivity.this,
                                    "音乐提取失败：" + reason, Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    deleteRecursively(workDir);
                }
            }
        }).start();
    }

    /** 抽完音轨后问一句原片留不留——默认留下，因为用户可能本来也想存这条作品。 */
    private void askKeepExtractedVideo(final Uri videoUri, final String musicTitle) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.music_extract_done_title)
                .setMessage(getString(R.string.music_extract_keep_msg, musicTitle))
                .setPositiveButton(R.string.music_extract_keep, null)
                .setNegativeButton(R.string.music_extract_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            getContentResolver().delete(videoUri, null, null);
                            Toast.makeText(MainActivity.this,
                                    R.string.music_extract_deleted, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this,
                                    "删除原片失败，可在相册里手动删除", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    /** 读音频时长（微秒），用于从容器里确认抽出的音轨是完整的。 */
    private static long readAudioDurationUs(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")
                        && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { extractor.release(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    /**
     * 「仅保存音乐」——一个按钮，两条路，对抖音与微信视频号都成立。
     *
     * <p><b>抖音</b>：作品用的那首曲子有独立音频地址，直接下 CDN 上的原始音乐文件并原样落盘，
     * 不做任何重编码，拿到的就是源文件本身。
     *
     * <p><b>微信视频号</b>：平台不提供独立音乐资源（音频只存在于视频轨里），
     * 于是先把原片存进相册，再用 {@link MediaExtractor}+{@link MediaMuxer} 把音轨
     * **原样抽出来**封装成 .m4a —— 同样不解码、不重编码，抽出来就是视频里那条音轨本身。
     *
     * <p>两条路的文件格式、扩展名与 MIME 一律靠文件头字节判定，不猜 URL。
     */
    private void downloadMusic() {
        if (currentMusicViaExtraction) {
            extractMusicFromCurrentVideo();
            return;
        }
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
                    final long bitrate = readAudioBitrate(raw);
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

                            StringBuilder quality = new StringBuilder();
                            quality.append(format[0].toUpperCase(Locale.ROOT));
                            if (bitrate > 0) {
                                quality.append(" · ").append(bitrate / 1000L).append(" kbps");
                            }
                            quality.append(" · 原文件直存，未重编码");

                            // 把「实际拿到的音质」明确讲出来：抖音音源本身没有无损，
                            // 万一只下发到试听片段也要说清，否则用户会以为保存坏了。
                            String note;
                            if (isLosslessFormat(format[0])) {
                                note = getString(R.string.music_lossless_ok);
                            } else if (durationMs > 0L && durationMs <= 35000L) {
                                note = getString(R.string.music_preview_warn_fmt,
                                        duration.isEmpty() ? "很短" : duration);
                            } else {
                                note = getString(R.string.music_lossy_note);
                            }

                            String message = "已保存《" + musicTitle + "》"
                                    + (duration.isEmpty() ? "" : "（" + duration + "）")
                                    + "：" + quality + "；" + note;
                            setStatus(message + "  ·  已存入音乐库「" + ALBUM_DIR + "」",
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

    /**
     * 读音频的平均码率（bps）；读不到返回 0。
     *
     * <p>用于把「实际拿到的音质」直接报给用户 —— 抖音音源本身不提供无损
     * （官方管线是 AAC-LC，高质量档位约 128 kbps 封顶），把码率说清楚，
     * 用户才不会以为存下来的是无损。
     */
    private static long readAudioBitrate(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (value == null || value.isEmpty()) return 0L;
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    /** 这个格式是不是无损容器（用于给用户一个明确的「是无损」结论）。 */
    private static boolean isLosslessFormat(String extension) {
        if (extension == null) return false;
        String e = extension.toLowerCase(Locale.ROOT);
        return "flac".equals(e) || "wav".equals(e) || "ape".equals(e) || "alac".equals(e);
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

    /**
     * 抽音轨的结果：文件本身，以及**它真正的**扩展名与 MIME。
     *
     * <p>为什么必须带回 MIME：原先扩展名/MIME 靠文件头猜，而 MediaMuxer 有多种容器
     * 输出（M4A / 3GP 都以 {@code ftyp} 开头，光看文件头分不出来）。
     * 猜错的话 Music App 会因为 MIME 不对而不收录，用户看到的就是「存了但音乐里没有」。
     */
    private static final class AudioExtractResult {
        final File file;
        final String extension;
        final String mime;
        final long durationUs;

        AudioExtractResult(File file, String extension, String mime, long durationUs) {
            this.file = file;
            this.extension = extension;
            this.mime = mime;
            this.durationUs = durationUs;
        }
    }

    /**
     * 把本地视频文件里的音轨**原样抽出来**，封装成独立音频文件（用于视频号这类
     * 没有独立音乐资源的作品）。
     *
     * <p>关键设计：走 {@link MediaExtractor} + {@link MediaMuxer} 直接把压缩帧搬进新容器，
     * **不解码、不重编码**。所以抽出来的音频和视频里那条音轨逐字节同级，不存在任何代际
     * 损失——这已经是「从视频里拿音乐」能做到的最保真方式（再往上只有平台没提供的无损源）。
     *
     * <p><b>容器会降级</b>：MP4 容器只认 AAC（{@code audio/mp4a-latm}），
     * 碰上 AMR-NB/WB 这类音轨 {@link MediaMuxer#addTrack} 会直接抛异常——
     * 而视频号的作品并不保证音轨是 AAC。失败时自动改用 3GP 容器（支持 AMR）再试一次。
     *
     * @return 抽出的音轨结果（文件 + 真实扩展名/MIME）；彻底失败时抛异常，由调用方提示用户。
     */
    private static AudioExtractResult extractAudioTrack(File video, File workDir) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());

            int audioTrack = -1;
            MediaFormat audioFormat = null;
            StringBuilder allTracks = new StringBuilder();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                allTracks.append(mime == null ? "?" : mime).append(' ');
                if (audioTrack < 0 && mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                }
            }
            if (audioTrack < 0) {
                // 视频号大量作品本身就是静音的，把真实轨道列出来，用户才知道不是软件坏了
                throw new Exception("这条作品没有音轨（视频里只有：" + allTracks.toString().trim()
                        + "），无法提取音乐");
            }
            final String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(audioTrack);

            // 先试 MP4；它装不下这条音轨时（如 AMR）再退到 3GP。
            Exception mp4Error = null;
            try {
                return muxAudioTrack(extractor, audioFormat, new File(workDir, "audio.m4a"),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4");
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // addTrack 拒绝该编码 —— 换容器还有救，别直接把这轮判死刑
                mp4Error = e;
            }
            try {
                return muxAudioTrack(extractor, audioFormat, new File(workDir, "audio.3gp"),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, "3gp", "audio/3gpp");
            } catch (Exception e3gp) {
                throw new Exception("音轨是 " + audioMime + "，既装不进 MP4 也装不进 3GP 容器"
                        + "（MP4 报错：" + shortMessage(mp4Error) + "；3GP 报错："
                        + shortMessage(e3gp) + "）");
            }
        } finally {
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    /** 抽异常里的可读消息，避免把整串堆栈甩给用户。 */
    private static String shortMessage(Throwable t) {
        if (t == null) return "无";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    /**
     * 把 extractor 当前选中的音轨搬进指定容器。**不重编码**，只复制压缩帧。
     */
    private static AudioExtractResult muxAudioTrack(MediaExtractor extractor,
                                                     MediaFormat audioFormat, File output,
                                                     int outputFormat, String extension,
                                                     String mime) throws Exception {
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            muxer = new MediaMuxer(output.getAbsolutePath(), outputFormat);
            int outTrack = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            int maxInput = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? (int) Math.max(256 * 1024L, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    : 1024 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastTimeUs = 0L;
            long samples = 0L;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0) break;
                int sampleFlags = extractor.getSampleFlags();
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = sampleTimeUs;
                info.flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(outTrack, buffer, info);
                lastTimeUs = Math.max(lastTimeUs, sampleTimeUs);
                samples++;
                extractor.advance();
            }
            // 用**采样点个数**判空，而不是时间戳：音轨第一帧的时间戳正常就是 0，
            // 拿它判空会把「只有开头几帧」误判成「一条数据都没有」。
            if (samples <= 0L) throw new Exception("音轨里没有可提取的音频数据");
            return new AudioExtractResult(output, extension, mime, lastTimeUs);
        } finally {
            if (muxer != null) {
                // 没 start() 就 stop() 会抛 IllegalStateException；此时文件是废的，直接删
                if (started) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
                if (!started) {
                    try { output.delete(); } catch (Exception ignored) { }
                }
            }
        }
    }

    /**
     * 从已落盘的视频 Uri 里抽出音轨。视频先拷到缓存目录——MediaExtractor 只认文件路径，
     * 而 MediaStore 给的是 content:// Uri。
     */
    private AudioExtractResult extractAudioFromVideoUri(Uri videoUri, File workDir) throws Exception {
        File local = new File(workDir, "source.mp4");
        InputStream in = getContentResolver().openInputStream(videoUri);
        if (in == null) throw new Exception("无法读取刚保存的视频");
        try {
            try (FileOutputStream out = new FileOutputStream(local)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        if (local.length() == 0L) throw new Exception("刚保存的视频读取为空");
        AudioExtractResult result = extractAudioTrack(local, workDir);
        // 抽出来的是个 0 字节文件时，写进音乐库只是个打不开的条目——早失败早说清
        if (!result.file.isFile() || result.file.length() == 0L) {
            throw new Exception("抽出的音频文件为空，未写入音乐库");
        }
        return result;
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
