package com.zgtools.videoeditor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EditorActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 2101;
    private static final int THUMBNAIL_COUNT = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Bitmap> currentThumbnails = new ArrayList<>();

    private Button btnSelectVideo;
    private Button btnPreview;
    private Button btnSaveClip;
    private View editorContent;
    private TextureVideoView videoView;
    private ImageView imageVideoPreview;
    private TextView tvVideoPlaceholder;
    private TextView tvVideoInfo;
    private TextView tvPlayhead;
    private TextView tvStartTime;
    private TextView tvEndTime;
    private TextView tvClipDuration;
    private TextView tvStatus;
    private ProgressBar progressExport;
    private TrimRangeView trimRangeView;

    private Uri selectedVideoUri;
    private long videoDurationMs;
    private long trimStartMs;
    private long trimEndMs;
    private boolean previewing;
    private boolean exporting;
    private int loadGeneration;
    private long pendingSeekMs;

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (videoView == null || selectedVideoUri == null) return;
            int current = Math.max(0, videoView.getCurrentPosition());
            tvPlayhead.setText(formatTime(current));
            updateTimelinePlayhead(current);
            if (previewing && current >= trimEndMs - 40L) {
                videoView.pause();
                safeSeekTo(trimStartMs);
                setPreviewing(false);
                showStillPreviewFor(trimStartMs);
                return;
            }
            if (videoView.isPlaying() || previewing) {
                mainHandler.postDelayed(this, 80L);
            }
        }
    };

    private final Runnable delayedRangeSeek = new Runnable() {
        @Override
        public void run() {
            safeSeekTo(pendingSeekMs);
            tvPlayhead.setText(formatTime(pendingSeekMs));
            updateTimelinePlayhead(pendingSeekMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        btnSelectVideo = findViewById(R.id.btn_select_video);
        btnPreview = findViewById(R.id.btn_preview);
        btnSaveClip = findViewById(R.id.btn_save_clip);
        editorContent = findViewById(R.id.editor_content);
        videoView = findViewById(R.id.video_view);
        imageVideoPreview = findViewById(R.id.image_video_preview);
        tvVideoPlaceholder = findViewById(R.id.tv_video_placeholder);
        tvVideoInfo = findViewById(R.id.tv_video_info);
        tvPlayhead = findViewById(R.id.tv_playhead);
        tvStartTime = findViewById(R.id.tv_start_time);
        tvEndTime = findViewById(R.id.tv_end_time);
        tvClipDuration = findViewById(R.id.tv_clip_duration);
        tvStatus = findViewById(R.id.tv_status);
        progressExport = findViewById(R.id.progress_export);
        trimRangeView = findViewById(R.id.trim_range);

        installButtonMotion(btnSelectVideo);
        installButtonMotion(btnPreview);
        installButtonMotion(btnSaveClip);

        videoView.setOnPreparedListener(mp -> {
            safeSeekTo(trimStartMs);
            if (!currentThumbnails.isEmpty()) {
                tvVideoPlaceholder.setVisibility(View.GONE);
            }
        });
        videoView.setOnFirstFrameListener(this::hideStillPreview);
        videoView.setOnCompletionListener(mp -> {
            setPreviewing(false);
            showStillPreviewFor(trimStartMs);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            setPreviewing(false);
            showStillPreviewFor(trimStartMs);
            setStatus("实时预览失败，请重新选择视频", R.color.color_error);
            Toast.makeText(EditorActivity.this, "这个视频暂时无法实时预览",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        btnSelectVideo.setOnClickListener(v -> chooseVideo());
        btnPreview.setOnClickListener(v -> toggleClipPreview());
        btnSaveClip.setOnClickListener(v -> exportSelectedClip());
        imageVideoPreview.setOnClickListener(v -> toggleClipPreview());
        videoView.setOnClickListener(v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                setPreviewing(false);
            } else if (selectedVideoUri != null) {
                if (videoView.hasRenderedFirstFrame()) hideStillPreview();
                mainHandler.removeCallbacks(playheadUpdater);
                videoView.start();
                mainHandler.post(playheadUpdater);
            }
        });

        trimRangeView.setOnRangeChangeListener((startFraction, endFraction, activeHandle,
                                                fromUser) -> {
            if (videoDurationMs <= 0L) return;
            trimStartMs = Math.max(0L, Math.round(videoDurationMs * startFraction));
            trimEndMs = Math.min(videoDurationMs, Math.round(videoDurationMs * endFraction));
            if (trimEndMs <= trimStartMs) trimEndMs = Math.min(videoDurationMs, trimStartMs + 500L);
            updateRangeLabels();
            if (fromUser && !exporting) {
                videoView.pause();
                setPreviewing(false);
                pendingSeekMs = activeHandle == TrimRangeView.HANDLE_START
                        ? trimStartMs : Math.max(trimStartMs, trimEndMs - 100L);
                showStillPreviewFor(pendingSeekMs);
                mainHandler.removeCallbacks(delayedRangeSeek);
                mainHandler.postDelayed(delayedRangeSeek, 45L);
            }
        });
    }

    private void chooseVideo() {
        if (exporting) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        loadVideo(uri);
    }

    private void loadVideo(Uri uri) {
        final int generation = ++loadGeneration;
        selectedVideoUri = null;
        videoView.stopPlayback();
        setPreviewing(false);
        recycleCurrentThumbnails();
        editorContent.setVisibility(View.GONE);
        btnSelectVideo.setEnabled(false);
        setStatus("正在读取视频和生成时间轴…", R.color.text_secondary);

        worker.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            ArrayList<Bitmap> thumbnails = new ArrayList<>();
            try {
                retriever.setDataSource(EditorActivity.this, uri);
                long duration = parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (duration < 500L) throw new Exception("视频太短，无法剪辑");
                int width = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rotation = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (rotation == 90 || rotation == 270) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
                for (int i = 0; i < THUMBNAIL_COUNT; i++) {
                    long timeUs = Math.max(0L, duration * 1000L * i
                            / Math.max(1, THUMBNAIL_COUNT - 1));
                    int thumbWidth = width >= height ? 480 : 280;
                    int thumbHeight = width >= height ? 280 : 480;
                    Bitmap frame = extractThumbnail(retriever, timeUs, thumbWidth, thumbHeight);
                    if (frame != null) thumbnails.add(frame);
                }
                String displayName = queryDisplayName(uri);
                long fileSize = queryFileSize(uri);
                String mime = getContentResolver().getType(uri);
                String info = buildVideoInfo(displayName, duration, width, height, fileSize, mime);
                long finalDuration = duration;
                mainHandler.post(() -> applyLoadedVideo(generation, uri, finalDuration, info,
                        thumbnails));
            } catch (Exception error) {
                recycleBitmaps(thumbnails);
                mainHandler.post(() -> {
                    if (generation != loadGeneration) return;
                    btnSelectVideo.setEnabled(true);
                    setStatus("读取失败：" + friendlyMessage(error), R.color.color_error);
                    Toast.makeText(EditorActivity.this, friendlyMessage(error),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void applyLoadedVideo(int generation, Uri uri, long duration, String info,
                                  ArrayList<Bitmap> thumbnails) {
        if (generation != loadGeneration || isFinishing()) {
            recycleBitmaps(thumbnails);
            return;
        }
        selectedVideoUri = uri;
        videoDurationMs = duration;
        trimStartMs = 0L;
        trimEndMs = duration;
        currentThumbnails.addAll(thumbnails);
        trimRangeView.setDurationMs(duration);
        trimRangeView.setThumbnails(currentThumbnails);
        trimRangeView.setRange(0f, 1f);
        trimRangeView.setPlayheadFraction(0f);
        tvVideoInfo.setText(info);
        tvPlayhead.setText(formatTime(0L));
        showStillPreviewFor(0L);
        tvVideoPlaceholder.setVisibility(currentThumbnails.isEmpty() ? View.VISIBLE : View.GONE);
        videoView.setVideoURI(uri);
        btnSelectVideo.setText(R.string.change_video);
        btnSelectVideo.setEnabled(true);
        editorContent.setVisibility(View.VISIBLE);
        editorContent.setAlpha(0f);
        editorContent.setTranslationY(dp(18));
        editorContent.animate().alpha(1f).translationY(0f).setDuration(320L)
                .setInterpolator(new DecelerateInterpolator()).start();
        setStatus("视频已就绪，拖动时间轴选择片段", R.color.color_success);
        updateRangeLabels();
    }

    private void toggleClipPreview() {
        if (selectedVideoUri == null || exporting) return;
        if (previewing || videoView.isPlaying()) {
            videoView.pause();
            setPreviewing(false);
            return;
        }
        safeSeekTo(trimStartMs);
        if (videoView.hasRenderedFirstFrame()) hideStillPreview();
        setPreviewing(true);
        videoView.start();
        mainHandler.removeCallbacks(playheadUpdater);
        mainHandler.post(playheadUpdater);
    }

    private void setPreviewing(boolean value) {
        previewing = value;
        if (btnPreview != null) btnPreview.setText(value ? R.string.stop_preview : R.string.preview_clip);
        if (!value) mainHandler.removeCallbacks(playheadUpdater);
    }

    private void showStillPreviewFor(long positionMs) {
        if (imageVideoPreview == null || currentThumbnails.isEmpty()) return;
        float fraction = videoDurationMs <= 0L ? 0f
                : Math.max(0f, Math.min(1f, positionMs / (float) videoDurationMs));
        int index = Math.max(0, Math.min(currentThumbnails.size() - 1,
                Math.round(fraction * (currentThumbnails.size() - 1))));
        Bitmap frame = currentThumbnails.get(index);
        if (frame == null || frame.isRecycled()) return;
        imageVideoPreview.setImageBitmap(frame);
        imageVideoPreview.setVisibility(View.VISIBLE);
        tvVideoPlaceholder.setVisibility(View.GONE);
        updateTimelinePlayhead(positionMs);
    }

    private void updateTimelinePlayhead(long positionMs) {
        if (trimRangeView == null) return;
        float fraction = videoDurationMs <= 0L ? 0f
                : Math.max(0f, Math.min(1f, positionMs / (float) videoDurationMs));
        trimRangeView.setPlayheadFraction(fraction);
    }

    private void hideStillPreview() {
        if (imageVideoPreview != null) imageVideoPreview.setVisibility(View.GONE);
        if (tvVideoPlaceholder != null) tvVideoPlaceholder.setVisibility(View.GONE);
    }

    private void exportSelectedClip() {
        if (selectedVideoUri == null || exporting) return;
        if (trimEndMs - trimStartMs < 500L) {
            Toast.makeText(this, "所选片段至少需要 0.5 秒", Toast.LENGTH_SHORT).show();
            return;
        }
        videoView.pause();
        setPreviewing(false);
        setExporting(true);
        final Uri input = selectedVideoUri;
        final long start = trimStartMs;
        final long end = trimEndMs;
        worker.execute(() -> {
            try {
                MediaTrimmer.Result result = MediaTrimmer.trim(EditorActivity.this, input,
                        start, end, percent -> mainHandler.post(() -> {
                            progressExport.setProgress(percent, true);
                            setStatus("正在保存剪辑… " + percent + "%", R.color.text_secondary);
                        }));
                mainHandler.post(() -> {
                    setExporting(false);
                    long keyframeAdvance = Math.max(0L, result.requestedStartMs - result.actualStartMs);
                    String message = keyframeAdvance >= 200L
                            ? "保存成功；为保持原画质，开头按关键帧提前 "
                                + formatTime(keyframeAdvance)
                            : "保存成功，已写入相册 / 保存助手 / 剪辑";
                    setStatus(message, R.color.color_success);
                    Toast.makeText(EditorActivity.this, "剪辑已保存到相册",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    setExporting(false);
                    String message = friendlyMessage(error);
                    setStatus("保存失败：" + message, R.color.color_error);
                    Toast.makeText(EditorActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setExporting(boolean value) {
        exporting = value;
        btnSelectVideo.setEnabled(!value);
        btnPreview.setEnabled(!value);
        btnSaveClip.setEnabled(!value);
        trimRangeView.setEnabled(!value);
        progressExport.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) {
            progressExport.setProgress(0);
            setStatus("正在创建相册文件…", R.color.text_secondary);
        }
    }

    private void updateRangeLabels() {
        tvStartTime.setText(getString(R.string.start_time_format, formatTime(trimStartMs)));
        tvEndTime.setText(getString(R.string.end_time_format, formatTime(trimEndMs)));
        tvClipDuration.setText(getString(R.string.kept_time_format,
                formatTime(Math.max(0L, trimEndMs - trimStartMs))));
    }

    private void safeSeekTo(long positionMs) {
        if (videoView == null) return;
        int safePosition = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, positionMs));
        try {
            videoView.seekTo(safePosition);
        } catch (Exception ignored) {
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "相册视频";
    }

    private long queryFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private String buildVideoInfo(String name, long duration, int width, int height,
                                  long size, String mime) {
        StringBuilder builder = new StringBuilder();
        builder.append(TextUtils.isEmpty(name) ? "相册视频" : name);
        builder.append("\n").append(formatTime(duration));
        if (width > 0 && height > 0) builder.append("  ·  ").append(width).append("×").append(height);
        if (size > 0L) builder.append("  ·  ").append(formatBytes(size));
        if (!TextUtils.isEmpty(mime)) builder.append("  ·  ").append(mime);
        return builder.toString();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void installButtonMotion(Button button) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.985f).scaleY(0.965f).setDuration(80L).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void setStatus(String text, int colorResource) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(colorResource));
    }

    private String friendlyMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (TextUtils.isEmpty(message)) return "该视频格式暂时无法剪辑";
        return message;
    }

    private static long parseLong(String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static Bitmap extractThumbnail(MediaMetadataRetriever retriever, long timeUs,
                                           int width, int height) {
        try {
            Bitmap frame = retriever.getScaledFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
            if (frame != null) return frame;
        } catch (Exception ignored) {
        }
        try {
            Bitmap frame = retriever.getScaledFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST, width, height);
            if (frame != null) return frame;
        } catch (Exception ignored) {
        }
        try {
            Bitmap frame = retriever.getFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f GB", bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024d * 1024d));
        }
        return String.format(Locale.CHINA, "%.1f KB", bytes / 1024d);
    }

    private static String formatTime(long milliseconds) {
        long totalTenths = Math.max(0L, milliseconds) / 100L;
        long tenths = totalTenths % 10L;
        long totalSeconds = totalTenths / 10L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes = totalMinutes % 60L;
        long hours = totalMinutes / 60L;
        if (hours > 0L) {
            return String.format(Locale.CHINA, "%d:%02d:%02d.%d",
                    hours, minutes, seconds, tenths);
        }
        return String.format(Locale.CHINA, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void recycleCurrentThumbnails() {
        if (imageVideoPreview != null) {
            imageVideoPreview.setImageDrawable(null);
            imageVideoPreview.setVisibility(View.GONE);
        }
        trimRangeView.setThumbnails(null);
        recycleBitmaps(currentThumbnails);
        currentThumbnails.clear();
    }

    private static void recycleBitmaps(List<Bitmap> bitmaps) {
        if (bitmaps == null) return;
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) videoView.pause();
        setPreviewing(false);
        if (selectedVideoUri != null) showStillPreviewFor(videoView.getCurrentPosition());
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        if (videoView != null) videoView.stopPlayback();
        recycleCurrentThumbnails();
        worker.shutdownNow();
        super.onDestroy();
    }
}
