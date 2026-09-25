package com.zgtools.videosaver;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Process;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把作品标题合成到视频画面左下角并重新编码的转码器。
 * 三段式：画面转码（硬解 → GL 叠标题 → 硬编 H.264）→ 音量增强（解码原音轨
 * → 按峰值归一化增益 → 重编码 AAC）→ 音画合并（与 MediaStitcher.mergeVideoAndAudio
 * 同构）。各段沿用本工程已在真机验证过的管线，全程保留原始 PTS 保证同步。
 */
public final class TitleOverlayTranscoder {
    private static final String LOG_TAG = "TitleOverlayTranscoder";
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    /** 解码输出若为 32-bit 整型 PCM（API 31 常量，此处写字面量避免 InlinedApi 告警）。 */
    private static final int PCM_ENCODING_32BIT = 21;
    private static final long CODEC_TIMEOUT_US = 10000;
    /** 音量增益上限（约 +9.5dB），防止把正常响度的视频放大到破音。 */
    /**
     * 封面覆盖在视频开头的时长。
     * <p>
     * 用户的要求是"没播放时能看到封面就行"，所以取一个短到播放时无感的长度。
     * 但**不能只放一帧**：相册生成缩略图取的是第一个可解码的关键帧，覆盖区间太短时
     * 部分机型/图库可能取样到下一帧。200ms 足够稳，人眼在播放时察觉不到。
     */
    public static final long COVER_LEAD_IN_MS = 200L;
    private static final long COVER_LEAD_IN_US = COVER_LEAD_IN_MS * 1000L;

    /** 模糊铺底之后压一层黑，让居中的封面跳出来。0x59 ≈ 35% 不透明度。 */
    private static final int COVER_DIM_COLOR = 0x59000000;

    /** 模糊底图的短边像素数：越小越糊。48 在缩略图尺寸下已足够弥散。 */
    private static final int COVER_BLUR_SHORT_SIDE = 48;

    private static final float AUDIO_MAX_GAIN = 3.0f;
    private static final float AUDIO_TARGET_PEAK = 0.97f;

    private TitleOverlayTranscoder() {
    }

    public interface ProgressCallback {
        void onProgress(int stage, int percent);
    }

    /** 阶段一：逐帧转码画面并叠加标题。 */
    public static final int STAGE_VIDEO = 1;
    /** 阶段二：音量增强。 */
    public static final int STAGE_AUDIO = 2;
    /** 阶段三：把音轨合并进转码结果。 */
    public static final int STAGE_MERGE = 3;

    /**
     * 读取 input 视频并把 title 合成到每一帧左下角，输出到 output。
     * 失败时抛出异常并删除残留输出，调用方负责退回保存原视频。
     */
    /** 只烧标题（保持既有行为：音轨走峰值归一化）。 */
    public static void overlay(File input, File output, String title,
                               ProgressCallback callback) throws Exception {
        if (TextUtils.isEmpty(title) || title.trim().isEmpty()) {
            throw new IllegalArgumentException("标题为空");
        }
        process(input, output, title, null, true, callback);
    }

    /**
     * 只把封面铺在开头若干帧，不动标题，音轨原样保留（不重编码，省一整个音频阶段）。
     *
     * @param cover 已解码的封面位图；调用方负责回收。
     */
    public static void applyCover(File input, File output, Bitmap cover,
                                  ProgressCallback callback) throws Exception {
        if (cover == null || cover.isRecycled()) {
            throw new IllegalArgumentException("封面位图无效");
        }
        process(input, output, null, cover, false, callback);
    }

    /** 标题与封面同时处理（勾了烧标题、又有封面时走这条）。 */
    public static void overlayWithCover(File input, File output, String title, Bitmap cover,
                                        ProgressCallback callback) throws Exception {
        if (TextUtils.isEmpty(title) || title.trim().isEmpty()) {
            throw new IllegalArgumentException("标题为空");
        }
        process(input, output, title, cover, true, callback);
    }

    /**
     * 处理主流程。
     *
     * @param title     可空：为空则不打标题
     * @param cover     可空：为空则不铺封面
     * @param boostAudio 是否对音轨做峰值归一化（烧标题时沿用历史行为；只铺封面时跳过，
     *                   既保留原始音轨又省掉整段 PCM 解码与重编码）
     */
    private static void process(File input, File output, String title, Bitmap cover,
                                boolean boostAudio, ProgressCallback callback)
            throws Exception {
        if (input == null || !input.isFile() || input.length() == 0L) {
            throw new Exception("视频缓存文件不存在");
        }
        if (output == null) {
            throw new IllegalArgumentException("输出位置为空");
        }
        // 转码是 CPU/GPU 密集活。默认优先级会被后台线程抢时间片，
        // 这里提到接近显示管线，让 CPU 调度和频率更倾向这段工作。
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "提升转码线程优先级失败: " + t.getMessage());
        }

        File videoOnly = new File(input.getParentFile(),
                input.getName() + ".titled_video.mp4");
        File boostedAudio = new File(input.getParentFile(),
                input.getName() + ".boosted.m4a");
        File pcmTemp = new File(input.getParentFile(),
                input.getName() + ".pcm");
        boolean completed = false;
        try {
            long stageStart = System.currentTimeMillis();
            transcodeVideo(input, videoOnly, title, cover, callback);
            lastVideoStageMs = System.currentTimeMillis() - stageStart;

            boolean hasAudio = hasAudioTrack(input);
            boolean boosted = false;
            if (hasAudio && boostAudio) {
                stageStart = System.currentTimeMillis();
                boosted = boostAudio(input, boostedAudio, pcmTemp, callback);
                lastAudioStageMs = System.currentTimeMillis() - stageStart;
            } else {
                lastAudioStageMs = boostAudio ? 0L : -1L;
            }
            long mergeStart = System.currentTimeMillis();
            if (hasAudio) {
                mergeVideoWithAudio(videoOnly, boosted ? boostedAudio : input, output, callback);
            } else {
                copyFile(videoOnly, output);
            }
            lastMergeStageMs = System.currentTimeMillis() - mergeStart;
            completed = true;
        } finally {
            if (!completed && output.isFile()) output.delete();
            videoOnly.delete();
            boostedAudio.delete();
            pcmTemp.delete();
        }
        if (!output.isFile() || output.length() == 0L) {
            throw new Exception("生成处理后的视频失败");
        }
    }

    /** 第一段：只处理画面，输出无音轨的带标题 MP4。 */
    private static void transcodeVideo(File input, File output, String title,
                                       Bitmap cover, ProgressCallback callback)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int videoTrack = findTrack(extractor, "video/");
            if (videoTrack < 0) throw new Exception("视频里没有画面轨道");
            extractor.selectTrack(videoTrack);
            MediaFormat inputFormat = extractor.getTrackFormat(videoTrack);
            String decodeMime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (decodeMime == null) throw new Exception("无法识别视频编码格式");

            int codedWidth = getIntSafe(inputFormat, MediaFormat.KEY_WIDTH, 0);
            int codedHeight = getIntSafe(inputFormat, MediaFormat.KEY_HEIGHT, 0);
            if (codedWidth <= 0 || codedHeight <= 0) {
                int[] size = probeVideoSize(input);
                codedWidth = size[0];
                codedHeight = size[1];
                if (codedWidth <= 0 || codedHeight <= 0) throw new Exception("无法读取视频尺寸");
            }
            int rotation = normalizeRotation(getIntSafe(inputFormat, MediaFormat.KEY_ROTATION, -1));
            if (rotation < 0) rotation = normalizeRotation(readRotation(input));

            boolean rotated = rotation == 90 || rotation == 270;
            int outWidth = Math.max(2, (rotated ? codedHeight : codedWidth) & ~1);
            int outHeight = Math.max(2, (rotated ? codedWidth : codedHeight) & ~1);

            long durationUs = getLongSafe(inputFormat, MediaFormat.KEY_DURATION, 0L);
            if (durationUs <= 0L) durationUs = probeDurationUs(input);
            if (durationUs <= 0L) durationUs = 1L;

            int fps = getIntSafe(inputFormat, MediaFormat.KEY_FRAME_RATE, 0);
            if (fps < 5 || fps > 60) fps = 30;
            int inputBitrate = getIntSafe(inputFormat, MediaFormat.KEY_BIT_RATE, 0);
            int bitrate = chooseBitRate(outWidth, outHeight, fps, inputBitrate);

            encodeFrames(extractor, inputFormat, decodeMime, rotation, codedWidth,
                    codedHeight, outWidth, outHeight, fps, bitrate, durationUs,
                    output, title == null ? null : title.trim(),
                    cover, callback);
        } finally {
            extractor.release();
        }
    }

    private static void encodeFrames(MediaExtractor extractor, MediaFormat inputFormat,
                                     String decodeMime, int rotation, int codedWidth,
                                     int codedHeight, int outWidth, int outHeight,
                                     int fps, int bitrate, long durationUs,
                                     File output, String title, Bitmap cover,
                                     ProgressCallback callback)
            throws Exception {
        boolean hasTitle = !TextUtils.isEmpty(title) && !title.trim().isEmpty();
        Bitmap overlay = hasTitle ? buildTitleBitmap(title, outWidth, outHeight) : null;
        if (hasTitle && overlay == null) throw new Exception("无法生成标题画面");

        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec encoder = null;
        MediaCodec decoder = null;
        EglInputSurface eglSurface = null;
        FrameRenderer renderer = null;
        DecoderOutputSurface decoderSurface = null;
        int overlayTexture = 0;
        CoverLayer coverLayer = null;
        boolean completed = false;
        final MuxState muxState = new MuxState();
        try {
            MediaFormat encodeFormat = MediaFormat.createVideoFormat(VIDEO_MIME, outWidth, outHeight);
            encodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // 尽量降低编码器输出延迟；不支持该键的机型会自动忽略
            encodeFormat.setInteger(MediaFormat.KEY_LATENCY, 0);
            encoder = newCodec(VIDEO_MIME, true);
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encoderInput = encoder.createInputSurface();
            encoder.start();
            requestMaxSpeed(encoder, fps);
            eglSurface = new EglInputSurface(encoderInput);
            eglSurface.makeCurrent();
            // 记下实际用的 GL 渲染器：兼容层里可能是软件光栅化，那会是另一个量级的问题
            try {
                String glRenderer = GLES20.glGetString(GLES20.GL_RENDERER);
                lastGlRenderer = glRenderer == null ? "未知" : glRenderer;
                Log.i(LOG_TAG, "GL_RENDERER = " + lastGlRenderer
                        + "，GL_VENDOR = " + GLES20.glGetString(GLES20.GL_VENDOR));
            } catch (Throwable t) {
                lastGlRenderer = "未知";
            }
            renderer = new FrameRenderer();
            if (overlay != null) overlayTexture = renderer.createBitmapTexture(overlay);
            if (cover != null && !cover.isRecycled()) {
                coverLayer = createCoverLayer(renderer, cover);
            }

            decoderSurface = new DecoderOutputSurface(renderer);
            decoder = newCodec(decodeMime, false);
            decoder.configure(inputFormat, decoderSurface.getSurface(), null, 0);
            decoder.start();
            requestMaxSpeed(decoder, fps);
            if (callback != null) callback.onProgress(STAGE_VIDEO, 0);

            MediaCodec.BufferInfo decodeInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int lastPercent = -1;
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) throw new Exception("视频解码输入缓冲区为空");
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = decoder.dequeueOutputBuffer(decodeInfo, CODEC_TIMEOUT_US);
                if (outputIndex >= 0) {
                    boolean eos =
                            (decodeInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = decodeInfo.size > 0 && !eos;
                    decoder.releaseOutputBuffer(outputIndex, render);
                    if (render) {
                        decoderSurface.awaitNewImage();
                        // 保留原始时间戳，与合并段的音轨共用同一条时间线
                        long pts = Math.max(0L, decodeInfo.presentationTimeUs);
                        // 开头的若干帧盖上封面：相册缩略图取第一帧，于是缩略图就是封面
                        boolean showCover = coverLayer != null && pts < COVER_LEAD_IN_US;
                        renderer.drawFrame(decoderSurface, overlayTexture, overlay,
                                coverLayer, showCover,
                                codedWidth, codedHeight, rotation, outWidth, outHeight);
                        eglSurface.setPresentationTime(pts * 1000L);
                        eglSurface.swapBuffers();
                        drainEncoder(encoder, muxer, muxState, false);

                        int percent = 1 + (int) Math.min(98L, pts * 98L / durationUs);
                        if (percent != lastPercent && callback != null) {
                            lastPercent = percent;
                            callback.onProgress(STAGE_VIDEO, percent);
                        }
                    }
                    if (eos) outputDone = true;
                }
            }

            drainEncoder(encoder, muxer, muxState, true);
            if (muxState.videoTrack < 0) throw new Exception("视频编码器没有输出任何画面");

            muxer.stop();
            completed = true;
            if (callback != null) callback.onProgress(STAGE_VIDEO, 100);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                decoder.release();
            }
            if (decoderSurface != null) decoderSurface.release();
            if (overlayTexture != 0) GLES20.glDeleteTextures(1, new int[]{overlayTexture}, 0);
            if (coverLayer != null) coverLayer.release();
            // overlay 是本方法自己创建的，负责回收；cover 由调用方持有，不在这里回收
            if (overlay != null && !overlay.isRecycled()) overlay.recycle();
            if (renderer != null) renderer.release();
            if (eglSurface != null) eglSurface.release();
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                encoder.release();
            }
            if (muxer != null) {
                if (muxState.muxerStarted && !completed) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                muxer.release();
            }
            if (!completed && output.isFile()) output.delete();
        }
    }

    /** 封面在 GL 里需要的三张纹理，以及本类自己创建、需要回收的位图。 */
    private static final class CoverLayer {
        final int sharpTexture;
        final int blurTexture;
        final int dimTexture;
        final Bitmap blurBitmap;
        final Bitmap dimBitmap;
        final int coverWidth;
        final int coverHeight;

        CoverLayer(int sharpTexture, int blurTexture, int dimTexture,
                   Bitmap blurBitmap, Bitmap dimBitmap, int coverWidth, int coverHeight) {
            this.sharpTexture = sharpTexture;
            this.blurTexture = blurTexture;
            this.dimTexture = dimTexture;
            this.blurBitmap = blurBitmap;
            this.dimBitmap = dimBitmap;
            this.coverWidth = coverWidth;
            this.coverHeight = coverHeight;
        }

        void release() {
            int[] textures = new int[]{sharpTexture, blurTexture, dimTexture};
            for (int texture : textures) {
                if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            }
            if (blurBitmap != null && !blurBitmap.isRecycled()) blurBitmap.recycle();
            if (dimBitmap != null && !dimBitmap.isRecycled()) dimBitmap.recycle();
        }
    }

    /**
     * 把封面缩到很小再交给 GL_LINEAR 放大，得到廉价的"糊"效果。
     * 比写高斯模糊着色器简单得多，而铺在背后当底完全够用。
     *
     * @return 缩小后的位图；封面本来就很小或失败时返回 null，调用方退回用原图
     */
    private static Bitmap buildBlurBitmap(Bitmap cover) {
        try {
            int w = Math.max(1, cover.getWidth());
            int h = Math.max(1, cover.getHeight());
            int outW;
            int outH;
            if (w >= h) {
                outW = COVER_BLUR_SHORT_SIDE;
                outH = Math.max(1, Math.round(COVER_BLUR_SHORT_SIDE * h / (float) w));
            } else {
                outH = COVER_BLUR_SHORT_SIDE;
                outW = Math.max(1, Math.round(COVER_BLUR_SHORT_SIDE * w / (float) h));
            }
            if (outW >= w && outH >= h) return null;
            return Bitmap.createScaledBitmap(cover, outW, outH, true);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "build blur bitmap failed: " + t.getMessage());
            return null;
        }
    }

    /** 为封面准备三张纹理：清晰原图、模糊底、压暗层。 */
    private static CoverLayer createCoverLayer(FrameRenderer renderer, Bitmap cover) {
        Bitmap blurBitmap = buildBlurBitmap(cover);
        Bitmap dimBitmap = null;
        try {
            dimBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            dimBitmap.setPixel(0, 0, COVER_DIM_COLOR);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "build dim bitmap failed: " + t.getMessage());
            if (dimBitmap != null && !dimBitmap.isRecycled()) dimBitmap.recycle();
            dimBitmap = null;
        }
        int sharpTexture = renderer.createBitmapTexture(cover);
        int blurTexture = renderer.createBitmapTexture(blurBitmap != null ? blurBitmap : cover);
        int dimTexture = dimBitmap != null ? renderer.createBitmapTexture(dimBitmap) : 0;
        return new CoverLayer(sharpTexture, blurTexture, dimTexture, blurBitmap, dimBitmap,
                Math.max(1, cover.getWidth()), Math.max(1, cover.getHeight()));
    }

    /**
     * 创建并配置 H.264 硬编。
     * <p>
     * 先带 {@code KEY_LATENCY=0} 尝试（尽量降低编码输出延迟）；若该机型硬编不接受这个
     * 扩展键而让 {@code configure} 抛异常，则丢掉扩展键、用最小必需格式重试一次。
     * 这样“提速提示不被支持”只会损失延迟优化，不会让整个标题烧录退化成“保存原视频”。
     */
    // ---------------------------------------------------------- 编解码器挑选与提速

    /** 上一次转码实际用到的编解码器，供界面提示与问题排查。 */
    private static volatile String lastEncoderName = "未知";
    private static volatile String lastDecoderName = "未知";
    private static volatile boolean lastEncoderHardware = true;
    private static volatile boolean lastDecoderHardware = true;

    /** 上一次转码实际用到的 GL 渲染器，以及各阶段耗时（毫秒）。 */
    private static volatile String lastGlRenderer = "未知";
    private static volatile long lastVideoStageMs = 0L;
    private static volatile long lastAudioStageMs = 0L;
    private static volatile long lastMergeStageMs = 0L;

    /**
     * GL 渲染器看起来像软件光栅化时返回它的名字，否则返回 null。
     *
     * <p>鸿蒙的 Android 兼容层里 GLES 有可能是软件实现（SwiftShader / llvmpipe 之类）。
     * 那样每一帧都要 CPU 光栅化，比 GPU 慢几个数量级 —— 这是必须排除的一种可能。
     */
    public static String suspectGlRenderer() {
        String name = lastGlRenderer;
        if (name == null || name.isEmpty() || "未知".equals(name)) return null;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("swiftshader") || lower.contains("llvmpipe")
                || lower.contains("softpipe") || lower.contains("software")
                || lower.contains("emulation") || lower.contains("mesa offscreen")
                || lower.contains("bluestacks")) {
            return name;
        }
        return null;
    }

    /** 上一次转码各阶段的耗时摘要，用于界面提示与问题排查。 */
    public static String lastRunStageSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("转码 ").append(fmtSeconds(lastVideoStageMs));
        if (lastAudioStageMs > 0L) sb.append("／音频 ").append(fmtSeconds(lastAudioStageMs));
        if (lastMergeStageMs > 0L) sb.append("／合并 ").append(fmtSeconds(lastMergeStageMs));
        return sb.toString();
    }

    /** 上一次转码的完整诊断串，只写日志。 */
    public static String lastRunCodecSummary() {
        return "解码 " + lastDecoderName + (lastDecoderHardware ? "[硬件]" : "[软件]")
                + "，编码 " + lastEncoderName + (lastEncoderHardware ? "[硬件]" : "[软件]")
                + "，GL " + lastGlRenderer;
    }

    private static String fmtSeconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /**
     * 上一次转码是否退化成了软件编解码。
     *
     * <p>软件编解码比硬件慢一个数量级，但它在界面上完全看不出来 ——
     * 用户只会觉得"这也太慢了"。所以要把这个事实明确报出去。
     */
    public static boolean lastRunFellBackToSoftware() {
        return !lastEncoderHardware || !lastDecoderHardware;
    }

    private static final class CodecChoice {
        final String name;
        final boolean hardware;

        CodecChoice(String name, boolean hardware) {
            this.name = name;
            this.hardware = hardware;
        }
    }

    /**
     * 挑一个编解码器：优先硬件加速的，尽量避开纯软件实现。
     *
     * <p><b>为什么必须显式挑</b>：{@code createEncoderByType}/{@code createDecoderByType}
     * 返回的是系统默认实现，<b>不保证带硬件加速</b>。在鸿蒙上跑 Android 兼容层
     * （例如用户用的「卓易通」）时，video/avc 的默认实现很可能是纯 CPU 软编，
     * 那会比硬件慢一个数量级 —— 用户反馈的"保存很慢"很可能就出在这里。
     * 顺手把实际选中的编解码器记下来，界面和日志都能看到。
     */
    private static CodecChoice pickCodec(String mime, boolean encoder) {
        CodecChoice softwareFallback = null;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder() != encoder) continue;
                boolean supports = false;
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        supports = true;
                        break;
                    }
                }
                if (!supports) continue;
                boolean hardware;
                try {
                    hardware = info.isHardwareAccelerated();
                } catch (Throwable t) {
                    // 个别实现不支持该方法，退回 isSoftwareOnly 的判断
                    hardware = !info.isSoftwareOnly();
                }
                if (hardware) return new CodecChoice(info.getName(), true);
                if (softwareFallback == null) {
                    softwareFallback = new CodecChoice(info.getName(), false);
                }
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "枚举编解码器失败，退回系统默认: " + t.getMessage());
        }
        return softwareFallback;
    }

    /** 按挑选结果创建编解码器，并记录实际用的是哪个。 */
    private static MediaCodec newCodec(String mime, boolean encoder) throws Exception {
        CodecChoice choice = pickCodec(mime, encoder);
        MediaCodec codec = null;
        if (choice != null && choice.name != null) {
            try {
                codec = MediaCodec.createByCodecName(choice.name);
            } catch (Exception e) {
                Log.w(LOG_TAG, "按名创建编解码器失败，退回系统默认: " + choice.name, e);
                codec = null;
            }
        }
        if (codec == null) {
            codec = encoder
                    ? MediaCodec.createEncoderByType(mime)
                    : MediaCodec.createDecoderByType(mime);
            choice = null;
        }
        String name = choice != null && choice.name != null ? choice.name : "系统默认";
        boolean hardware = choice == null || choice.hardware;
        if (encoder) {
            lastEncoderName = name;
            lastEncoderHardware = hardware;
        } else {
            lastDecoderName = name;
            lastDecoderHardware = hardware;
        }
        Log.i(LOG_TAG, (encoder ? "编码器 " : "解码器 ") + name + (hardware ? " [硬件]" : " [软件]"));
        return codec;
    }

    /**
     * 请编解码器"跑满"。
     *
     * <p>{@code KEY_OPERATING_RATE = Integer.MAX_VALUE} 表示不限速、尽快跑完；
     * {@code KEY_PRIORITY = 0} 表示实时优先。这是 Android 给摄像机/视频编辑场景的官方
     * 提示，能让框架与驱动提高编解码器的运行时钟。属于 hint，不被支持时会被忽略，
     * 也可能直接抛异常，所以全程吞掉 —— 正确性不依赖它，只希望换来速度。
     */
    private static void requestMaxSpeed(MediaCodec codec, int frameRate) {
        if (codec == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaFormat.KEY_OPERATING_RATE, Integer.MAX_VALUE);
            params.putInt(MediaFormat.KEY_PRIORITY, 0);
            codec.setParameters(params);
            Log.i(LOG_TAG, "已请求编解码器全速运行 (fps=" + frameRate + ")");
        } catch (Throwable t) {
            Log.w(LOG_TAG, "请求全速运行不被支持（可忽略）: " + t.getMessage());
        }
    }

    /** 封装器状态：视频轨索引、是否已启动、已写入时间戳（跨排空调用保持）。 */
    private static final class MuxState {
        int videoTrack = -1;
        boolean muxerStarted;
        long lastWrittenPtsUs = -1L;
    }

    /**
     * 编码器排空。非收尾时用 0 超时非阻塞查询，逐帧只取“已就绪”的输出，
     * 消除每帧最多 10ms 的空等（提速关键）；收尾时阻塞直到 EOS。
     */
    private static void drainEncoder(MediaCodec encoder, MediaMuxer muxer, MuxState state,
                                     boolean endOfStream) throws Exception {
        if (endOfStream) encoder.signalEndOfInputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int timeoutUs = endOfStream ? (int) CODEC_TIMEOUT_US : 0;
        int idleTries = 0;
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, timeoutUs);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                if (++idleTries > 3000) throw new Exception("等待视频编码输出超时");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.muxerStarted) throw new Exception("视频编码格式重复变化");
                state.videoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                state.muxerStarted = true;
            } else if (index >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(index);
                if (data == null) throw new Exception("视频编码输出缓冲区为空");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (state.videoTrack < 0) throw new Exception("视频封装器尚未启动");
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    if (info.presentationTimeUs <= state.lastWrittenPtsUs) {
                        info.presentationTimeUs = state.lastWrittenPtsUs + 1;
                    }
                    state.lastWrittenPtsUs = info.presentationTimeUs;
                    muxer.writeSampleData(state.videoTrack, data, info);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    /** 音频解码结果：PCM 临时文件、采样参数、原始首帧 PTS、峰值。 */
    private static final class AudioDecodeResult {
        final File pcmFile;
        final int sampleRate;
        final int channels;
        final long firstPtsUs;
        final int peakAmp;

        AudioDecodeResult(File pcmFile, int sampleRate, int channels,
                          long firstPtsUs, int peakAmp) {
            this.pcmFile = pcmFile;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.firstPtsUs = firstPtsUs;
            this.peakAmp = peakAmp;
        }
    }

    /**
     * 第二段：音量增强。把原音轨解码为 PCM（边解边算峰值），
     * 按峰值归一化计算增益（至少 1.0、最多 3.0 倍，目标峰值 97%），
     * 再重编码为 AAC。源音轨本身接近满幅或无声时不放大，返回 false。
     */
    private static boolean boostAudio(File input, File output, File pcmTemp,
                                      ProgressCallback callback) throws Exception {
        if (callback != null) callback.onProgress(STAGE_AUDIO, 0);
        AudioDecodeResult decoded = decodeAudioToPcm(input, pcmTemp, callback);
        if (decoded.peakAmp <= 0) {
            return false;
        }
        float gain = Math.min(AUDIO_MAX_GAIN,
                Math.max(1.0f, AUDIO_TARGET_PEAK * 32767f / decoded.peakAmp));
        encodeBoostedAac(decoded, gain, output, callback);
        if (callback != null) callback.onProgress(STAGE_AUDIO, 100);
        return true;
    }

    /** 解码音频轨为 16-bit PCM 文件，同时统计峰值与首帧 PTS。 */
    private static AudioDecodeResult decodeAudioToPcm(File input, File pcmTemp,
                                                      ProgressCallback callback)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        FileOutputStream pcmOut = null;
        boolean completed = false;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int track = findTrack(extractor, "audio/");
            if (track < 0) throw new Exception("没有音频轨道");
            extractor.selectTrack(track);
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = getIntSafe(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 0);
            int channels = getIntSafe(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 0);
            decoder = MediaCodec.createDecoderByType(mime);
            try {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
            } catch (Exception ignored) {
            }
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            pcmOut = new FileOutputStream(pcmTemp);
            int pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            long firstPtsUs = -1L;
            int peakAmp = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(inIndex);
                        if (in == null) throw new Exception("音频解码输入为空");
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = decoder.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer out = decoder.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        if (firstPtsUs < 0L) firstPtsUs = Math.max(0L, info.presentationTimeUs);
                        int[] peakHolder = new int[]{peakAmp};
                        byte[] bytes = toPcm16(out, pcmEncoding, peakHolder);
                        peakAmp = peakHolder[0];
                        pcmOut.write(bytes);
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            pcmOut.flush();
            completed = true;
            if (sampleRate <= 0 || channels <= 0) throw new Exception("无法识别音频参数");
            if (callback != null) callback.onProgress(STAGE_AUDIO, 60);
            return new AudioDecodeResult(pcmTemp, sampleRate, channels,
                    Math.max(0L, firstPtsUs), peakAmp);
        } finally {
            if (!completed && pcmTemp.isFile()) pcmTemp.delete();
            if (pcmOut != null) {
                try { pcmOut.close(); } catch (Exception ignored) { }
            }
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                decoder.release();
            }
            extractor.release();
        }
    }

    /**
     * 把解码器输出的 PCM 统一转换成 16-bit signed little-endian 字节流，
     * 并顺带统计峰值（写回 {@code peakHolder[0]}）。
     * <p>
     * 稳健化要点：部分机型（尤其华为/HiSilicon）的解码器会忽略请求、直接输出
     * 32-bit 整型或 float PCM。旧实现只认 16-bit 和 float，遇到 32-bit 会把数据
     * 当 16-bit 解释，重编码后变成刺耳噪音。这里显式覆盖 8/16/32-bit 与 float 四种常见输出。
     */
    private static byte[] toPcm16(ByteBuffer source, int pcmEncoding, int[] peakHolder) {
        int size = source.remaining();
        if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            int frames = size / 4;
            byte[] bytes = new byte[frames * 2];
            source.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                float value = Math.max(-1f, Math.min(1f, source.getFloat()));
                int sv = Math.max(-32768, Math.min(32767, Math.round(value * 32767f)));
                peakHolder[0] = Math.max(peakHolder[0], Math.abs(sv));
                bytes[i * 2] = (byte) (sv & 0xff);
                bytes[i * 2 + 1] = (byte) ((sv >> 8) & 0xff);
            }
            return bytes;
        }
        if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_8BIT) {
            // 8-bit PCM 为无符号，中心值 128
            byte[] bytes = new byte[size * 2];
            for (int i = 0; i < size; i++) {
                int sv = ((source.get() & 0xff) - 128) << 8;
                peakHolder[0] = Math.max(peakHolder[0], Math.abs(sv));
                bytes[i * 2] = (byte) (sv & 0xff);
                bytes[i * 2 + 1] = (byte) ((sv >> 8) & 0xff);
            }
            return bytes;
        }
        if (pcmEncoding == PCM_ENCODING_32BIT) {
            int frames = size / 4;
            byte[] bytes = new byte[frames * 2];
            source.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                int sv = source.getInt() >> 16;
                peakHolder[0] = Math.max(peakHolder[0], Math.abs(sv) == 32768 ? 32767 : Math.abs(sv));
                bytes[i * 2] = (byte) (sv & 0xff);
                bytes[i * 2 + 1] = (byte) ((sv >> 8) & 0xff);
            }
            return bytes;
        }
        byte[] bytes = new byte[size];
        source.get(bytes);
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            int v = (bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8);
            short sv = (short) v;
            int a = sv == Short.MIN_VALUE ? 32767 : Math.abs(sv);
            if (a > peakHolder[0]) peakHolder[0] = a;
        }
        return bytes;
    }

    /** 把 PCM 按增益放大后编码为 AAC m4a，PTS 以原音轨首帧为基准保持音画同步。 */
    private static void encodeBoostedAac(AudioDecodeResult decoded, float gain, File output,
                                         ProgressCallback callback) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME,
                decoded.sampleRate, decoded.channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE,
                decoded.channels >= 2 ? 192000 : 96000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024);
        MediaCodec encoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        MediaMuxer muxer = null;
        FileInputStream pcmIn = null;
        boolean muxerStarted = false;
        boolean completed = false;
        int track = -1;
        int frameBytes = decoded.channels * 2;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            pcmIn = new FileInputStream(decoded.pcmFile);
            long submittedFrames = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = encoder.getInputBuffer(inIndex);
                        if (in == null) throw new Exception("AAC 输入缓冲区为空");
                        in.clear();
                        in.order(ByteOrder.LITTLE_ENDIAN);
                        int maxFrames = in.remaining() / frameBytes;
                        if (maxFrames <= 0) {
                            encoder.queueInputBuffer(inIndex, 0, 0,
                                    ptsOf(submittedFrames, decoded), 0);
                            continue;
                        }
                        byte[] chunk = new byte[maxFrames * frameBytes];
                        int got = 0;
                        while (got < chunk.length) {
                            int n = pcmIn.read(chunk, got, chunk.length - got);
                            if (n < 0) break;
                            got += n;
                        }
                        if (got < chunk.length) {
                            inputDone = true;
                        }
                        int frames = got / frameBytes;
                        for (int f = 0; f < frames; f++) {
                            for (int c = 0; c < decoded.channels; c++) {
                                int off = f * frameBytes + c * 2;
                                int v = (chunk[off] & 0xff) | ((chunk[off + 1] & 0xff) << 8);
                                int amplified = Math.round((short) v * gain);
                                amplified = Math.max(-32768, Math.min(32767, amplified));
                                in.putShort((short) amplified);
                            }
                        }
                        long pts = ptsOf(submittedFrames, decoded);
                        if (inputDone && frames == 0) {
                            encoder.queueInputBuffer(inIndex, 0, 0, pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            encoder.queueInputBuffer(inIndex, 0, frames * frameBytes, pts, 0);
                            submittedFrames += frames;
                        }
                    }
                }
                int outIndex = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    if (callback != null) callback.onProgress(STAGE_AUDIO, 80);
                } else if (outIndex >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(outIndex);
                    if (data == null) throw new Exception("AAC 输出缓冲区为空");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxerStarted) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(track, data, info);
                    }
                    encoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            muxer.stop();
            completed = true;
        } finally {
            if (pcmIn != null) {
                try { pcmIn.close(); } catch (Exception ignored) { }
            }
            try { encoder.stop(); } catch (Exception ignored) { }
            encoder.release();
            if (muxer != null) {
                if (muxerStarted && !completed) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                muxer.release();
            }
            if (!completed && output.isFile()) output.delete();
        }
    }

    private static long ptsOf(long frameIndex, AudioDecodeResult decoded) {
        return decoded.firstPtsUs + frameIndex * 1_000_000L / decoded.sampleRate;
    }

    /**
     * 第三段：把带标题的纯画面视频与音轨合并。
     * 与 MediaStitcher.mergeVideoAndAudio 相同的结构（两个 Extractor、
     * 逐轨 addTrack 后 start、按原 PTS 拷贝），该模式已在真机验证。
     */
    private static void mergeVideoWithAudio(File videoFile, File audioSource, File output,
                                            ProgressCallback callback) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean completed = false;
        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            if (videoTrack < 0) throw new Exception("转码输出缺少画面轨道");
            videoExtractor.selectTrack(videoTrack);

            audioExtractor.setDataSource(audioSource.getAbsolutePath());
            int audioTrack = findTrack(audioExtractor, "audio/");
            if (audioTrack < 0) {
                copyFile(videoFile, output);
                completed = true;
                return;
            }
            audioExtractor.selectTrack(audioTrack);

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack));
            int outAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            muxer.start();
            muxerStarted = true;

            if (callback != null) callback.onProgress(STAGE_MERGE, 5);
            copyTrack(videoExtractor, muxer, videoTrack, outVideoTrack);
            if (callback != null) callback.onProgress(STAGE_MERGE, 60);
            copyTrack(audioExtractor, muxer, audioTrack, outAudioTrack);
            if (callback != null) callback.onProgress(STAGE_MERGE, 99);

            muxer.stop();
            completed = true;
            if (callback != null) callback.onProgress(STAGE_MERGE, 100);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (muxerStarted && !completed) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                muxer.release();
            }
            if (!completed && output.isFile()) output.delete();
        }
    }

    /**
     * 与 MediaStitcher.copyTrack 相同的逐样本拷贝，保留原始 PTS 与关键帧标记。
     * <p>
     * 与旧实现的差异（稳健化）：①不再用 {@code extractor.getSampleTrackIndex()} 反查格式——
     * {@code selectTrack()} 之后、读样本之前它可能返回 -1，导致拿不到 {@code KEY_MAX_INPUT_SIZE}
     * 而退化成 256KB 缓冲区；改为由调用方直接传入已选轨索引。②缓冲区兜底值对齐
     * MediaStitcher（1MB），并在样本超出缓冲区时扩容重试，避免大关键帧被截断或整段失败。
     */
    private static void copyTrack(MediaExtractor extractor, MediaMuxer muxer, int sourceTrack,
                                  int outTrack) throws Exception {
        int maxBytes = 1 * 1024 * 1024;
        if (sourceTrack >= 0) {
            try {
                MediaFormat format = extractor.getTrackFormat(sourceTrack);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBytes = Math.max(maxBytes, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            } catch (Exception ignored) {
            }
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBytes);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size;
            try {
                size = extractor.readSampleData(buffer, 0);
            } catch (IllegalArgumentException tooSmall) {
                // 个别实现缓冲区不足时抛异常而非返回 -1：扩容一倍（上限 16MB）后重试一次
                if (maxBytes >= 16 * 1024 * 1024) throw tooSmall;
                maxBytes *= 2;
                Log.w(LOG_TAG, "样本超出拷贝缓冲区，扩容到 " + maxBytes + " 字节后重试");
                buffer = ByteBuffer.allocateDirect(maxBytes);
                buffer.clear();
                size = extractor.readSampleData(buffer, 0);
            }
            if (size < 0) break;
            if (size > maxBytes) {
                throw new Exception("样本大小 " + size + " 超出缓冲区上限，已中止以防输出损坏");
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0L, extractor.getSampleTime());
            int sampleFlags = extractor.getSampleFlags();
            info.flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
            muxer.writeSampleData(outTrack, buffer, info);
            if (!extractor.advance()) break;
        }
    }

    public static boolean hasAudioTrack(File input) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            return findTrack(extractor, "audio/") >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            extractor.release();
        }
    }

    /**
     * 生成左下角标题贴图：白色文字 + 轻投影（无背景底色）。
     * 字号从较小基准起、按文字量自动缩小，尽量完整显示全文；
     * 只有在最小字号仍放不下时才退回省略号。
     */
    private static Bitmap buildTitleBitmap(String title, int videoWidth, int videoHeight) {
        String text = title.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) return null;
        float maxTextWidth = Math.max(24f, videoWidth * 0.66f);
        // 标题块整体高度不超过画面高度的约五分之一，避免遮挡内容
        float heightBudget = Math.max(24f, videoHeight * 0.20f);
        float maxFontPx = Math.max(16f, videoHeight * 0.024f);
        float minFontPx = Math.max(12f, videoHeight * 0.011f);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        float fontPx = maxFontPx;
        StaticLayout layout;
        float padH;
        float padV;
        while (true) {
            applyTextStyle(paint, fontPx);
            layout = buildTextLayout(text, paint, (int) maxTextWidth, -1, null);
            padH = fontPx * 0.20f;
            padV = fontPx * 0.14f;
            if (layout.getHeight() + padV * 2f <= heightBudget || fontPx <= minFontPx) break;
            fontPx = Math.max(minFontPx, fontPx * 0.90f);
        }
        if (layout.getHeight() + padV * 2f > heightBudget) {
            // 极端长文案：最小字号仍超出高度预算，按预算行数省略
            applyTextStyle(paint, fontPx);
            float lineHeight = layout.getHeight() / (float) Math.max(1, layout.getLineCount());
            int maxLines = Math.max(1,
                    (int) Math.floor((heightBudget - padV * 2f) / lineHeight));
            layout = buildTextLayout(text, paint, (int) maxTextWidth, maxLines,
                    TextUtils.TruncateAt.END);
        }

        float maxLineWidth = 1f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            maxLineWidth = Math.max(maxLineWidth, layout.getLineWidth(i));
        }
        int width = Math.min(videoWidth,
                (int) Math.ceil(maxLineWidth + padH * 2f));
        // 极端长文案在最小字号仍超预算时，图层可能高于画面；此处硬性收在画面尺寸内，
        // 超出部分由 Canvas 自然裁掉，避免生成超大贴图或计算越界。
        int height = (int) Math.min(videoHeight,
                Math.ceil(layout.getHeight() + padV * 2f));
        if (width < 4 || height < 4) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.translate(padH, padV);
        layout.draw(canvas);
        canvas.restore();
        return bitmap;
    }

    private static void applyTextStyle(TextPaint paint, float fontPx) {
        paint.setTextSize(fontPx);
        // 无背景底色，靠稍重的投影保证浅色画面上白字也读得清
        paint.setShadowLayer(fontPx * 0.10f, fontPx * 0.04f, fontPx * 0.04f, 0x99000000);
    }

    private static StaticLayout buildTextLayout(String text, TextPaint paint, int width,
                                                int maxLines, TextUtils.TruncateAt truncate) {
        StaticLayout.Builder builder = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(paint.getTextSize() * 0.12f, 1f)
                .setIncludePad(true);
        if (truncate != null && maxLines > 0) {
            builder.setEllipsize(truncate).setMaxLines(maxLines);
        }
        return builder.build();
    }

    private static int chooseBitRate(int width, int height, int fps, int inputBitRate) {
        long target;
        if (inputBitRate > 0) {
            target = (long) (inputBitRate * 1.25f);
        } else {
            target = (long) width * height * fps / 10L;
        }
        return (int) Math.max(1_500_000L, Math.min(25_000_000L, target));
    }

    /**
     * 只接受 0/90/180/270 四种合法旋转；其余（含 45 之类的脏元数据）一律按 0 处理，
     * 避免 GL 里按任意角度旋转把画面拉成斜的。
     */
    private static int normalizeRotation(int rotation) {
        if (rotation < 0) return -1;
        int value = ((rotation % 360) + 360) % 360;
        return (value == 90 || value == 180 || value == 270) ? value : 0;
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int getIntSafe(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long getLongSafe(MediaFormat format, String key, long fallback) {
        try {
            return format.containsKey(key) ? format.getLong(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int readRotation(File input) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input.getAbsolutePath());
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private static long probeDurationUs(File input) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value) * 1000L;
        } catch (Exception e) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private static int[] probeVideoSize(File input) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input.getAbsolutePath());
            int width = parseIntSafe(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseIntSafe(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{0, 0};
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void copyFile(File source, File dest) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(dest)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    /** 编码器输入 Surface 的 EGL 封装，与 MediaStitcher 中一致。 */
    private static final class EglInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private Surface nativeSurface;

        EglInputSurface(Surface nativeSurface) {
            this.nativeSurface = nativeSurface;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("无法获取 EGLDisplay");
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw new RuntimeException("EGL 初始化失败");
            }
            int[] attribList = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, num, 0)) {
                throw new RuntimeException("EGL 配置失败");
            }
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0],
                    nativeSurface, new int[]{EGL14.EGL_NONE}, 0);
        }

        void makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new RuntimeException("EGL makeCurrent 失败");
            }
        }

        void swapBuffers() {
            if (!EGL14.eglSwapBuffers(display, surface)) throw new RuntimeException("EGL swapBuffers 失败");
        }

        void setPresentationTime(long nanoseconds) {
            EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds);
        }

        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, surface);
                EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            if (nativeSurface != null) nativeSurface.release();
        }
    }

    /** 解码输出到 SurfaceTexture，等待帧到达后交给渲染器。 */
    private static final class DecoderOutputSurface
            implements SurfaceTexture.OnFrameAvailableListener {
        private final FrameRenderer renderer;
        private final int textureId;
        private final SurfaceTexture surfaceTexture;
        private final Surface surface;
        private final Object frameSync = new Object();
        private boolean frameAvailable;
        private final float[] textureMatrix = new float[16];

        DecoderOutputSurface(FrameRenderer renderer) {
            this.renderer = renderer;
            textureId = renderer.createOesTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
        }

        Surface getSurface() {
            return surface;
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (frameSync) {
                frameAvailable = true;
                frameSync.notifyAll();
            }
        }

        void awaitNewImage() throws Exception {
            synchronized (frameSync) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!frameAvailable) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) throw new Exception("等待视频帧超时");
                    try {
                        frameSync.wait(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                frameAvailable = false;
            }
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(textureMatrix);
        }

        float[] textureMatrix() {
            return textureMatrix;
        }

        int textureId() {
            return textureId;
        }

        void release() {
            surface.release();
            surfaceTexture.release();
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        }
    }

    /** 着色器程序及其缓存好的 attribute/uniform 位置，避免逐帧重复查询。 */
    private static final class GlProgram {
        final int id;
        final int positionLocation;
        final int texCoordLocation;
        final int mvpLocation;
        final int texMatrixLocation;
        final int samplerLocation;

        GlProgram(String vertexSource, String fragmentSource) {
            id = FrameRenderer.createProgram(vertexSource, fragmentSource);
            positionLocation = GLES20.glGetAttribLocation(id, "aPosition");
            texCoordLocation = GLES20.glGetAttribLocation(id, "aTextureCoord");
            mvpLocation = GLES20.glGetUniformLocation(id, "uMVPMatrix");
            texMatrixLocation = GLES20.glGetUniformLocation(id, "uTexMatrix");
            samplerLocation = GLES20.glGetUniformLocation(id, "sTexture");
        }
    }

    /** 逐帧绘制：先铺满解码画面（含旋转烘焙），再以预乘 alpha 叠加标题贴图。 */
    private static final class FrameRenderer {
        private static final String VERTEX =
                "uniform mat4 uMVPMatrix;\n" + "uniform mat4 uTexMatrix;\n"
                        + "attribute vec4 aPosition;\n" + "attribute vec4 aTextureCoord;\n"
                        + "varying vec2 vTextureCoord;\n"
                        + "void main() { gl_Position = uMVPMatrix * aPosition;"
                        + " vTextureCoord = (uTexMatrix * aTextureCoord).xy; }\n";
        private static final String FRAGMENT_2D =
                "precision mediump float;\n" + "varying vec2 vTextureCoord;\n"
                        + "uniform sampler2D sTexture;\n"
                        + "void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }\n";
        private static final String FRAGMENT_OES =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n" + "varying vec2 vTextureCoord;\n"
                        + "uniform samplerExternalOES sTexture;\n"
                        + "void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }\n";

        private final GlProgram program2d;
        private final GlProgram programOes;
        private final FloatBuffer quadVertices;
        private final FloatBuffer tex2dCoords;
        private final FloatBuffer texOesCoords;
        private final FloatBuffer overlayVertices = ByteBuffer
                .allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer coverTexCoords = ByteBuffer
                .allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer coverFitVertices = ByteBuffer
                .allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] identity = new float[16];
        private final float[] overlayMvp = new float[16];

        FrameRenderer() {
            quadVertices = floatBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            tex2dCoords = floatBuffer(new float[]{0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
            texOesCoords = floatBuffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
            program2d = new GlProgram(VERTEX, FRAGMENT_2D);
            programOes = new GlProgram(VERTEX, FRAGMENT_OES);
            Matrix.setIdentityM(identity, 0);
            Matrix.setIdentityM(overlayMvp, 0);
        }

        int createBitmapTexture(Bitmap bitmap) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int id = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            setTextureParams(GLES20.GL_TEXTURE_2D);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            return id;
        }

        int createOesTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int id = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
            setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            return id;
        }

        void drawFrame(DecoderOutputSurface source, int overlayTexture, Bitmap overlay,
                       CoverLayer coverLayer, boolean showCover,
                       int sourceWidth, int sourceHeight, int rotation,
                       int targetWidth, int targetHeight) {
            GLES20.glViewport(0, 0, targetWidth, targetHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            drawOesFull(source.textureId(), source.textureMatrix(),
                    sourceWidth, sourceHeight, rotation, targetWidth, targetHeight);
            if (showCover && coverLayer != null) {
                drawCoverFrame(coverLayer, targetWidth, targetHeight);
            }
            if (overlayTexture != 0 && overlay != null) {
                drawOverlay(overlayTexture, overlay, targetWidth, targetHeight);
            }
        }

        /**
         * 画封面：模糊铺底 + 压暗 + 完整居中。
         *
         * <p>为什么不像早先那样"把封面裁切填满整帧"：竖版封面（视频号封面多是 3:4）塞进
         * 横版画面时，裁切会把封面裁掉大半，缩略图根本看不出原封面。
         * 这里改成三步——模糊底把画面填满不留黑边，压暗让主体跳出来，完整封面按比例居中，
         * 一像素不少。音乐播放器与视频 App 的竖版封面都是这个画法。
         */
        private void drawCoverFrame(CoverLayer layer, int targetWidth, int targetHeight) {
            // ① 模糊铺底：裁切填满整帧
            drawCoverFill(layer.blurTexture, layer.coverWidth, layer.coverHeight,
                    targetWidth, targetHeight);
            // ② 压暗
            if (layer.dimTexture != 0) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                drawQuad(program2d, GLES20.GL_TEXTURE_2D, layer.dimTexture,
                        overlayMvp, identity, quadVertices, tex2dCoords);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            // ③ 完整封面居中，保持比例不裁切
            drawCoverFit(layer.sharpTexture, layer.coverWidth, layer.coverHeight,
                    targetWidth, targetHeight);
        }

        /** 裁切填满整帧（保持比例、居中裁切），用纹理坐标裁切，不缩放顶点、不变形。 */
        private void drawCoverFill(int texture, int coverWidth, int coverHeight,
                                   int targetWidth, int targetHeight) {
            computeCoverFillTexCoords(coverWidth, coverHeight, targetWidth, targetHeight);
            GLES20.glDisable(GLES20.GL_BLEND);
            drawQuad(program2d, GLES20.GL_TEXTURE_2D, texture,
                    overlayMvp, identity, quadVertices, coverTexCoords);
        }

        /** 完整封面按比例居中（不裁切）。用缩放顶点实现，纹理坐标取满幅。 */
        private void drawCoverFit(int texture, int coverWidth, int coverHeight,
                                  int targetWidth, int targetHeight) {
            float coverAspect = coverWidth / (float) Math.max(1, coverHeight);
            float targetAspect = targetWidth / (float) Math.max(1, targetHeight);
            float halfW = 1f;
            float halfH = 1f;
            if (coverAspect > targetAspect) {
                // 封面相对更宽：以宽度为准，上下留边
                halfH = targetAspect / coverAspect;
            } else if (coverAspect < targetAspect) {
                // 封面相对更高：以高度为准，左右留边
                halfW = coverAspect / targetAspect;
            }
            coverFitVertices.clear();
            coverFitVertices.put(-halfW).put(-halfH).put(halfW).put(-halfH)
                    .put(-halfW).put(halfH).put(halfW).put(halfH);
            coverFitVertices.position(0);

            GLES20.glDisable(GLES20.GL_BLEND);
            drawQuad(program2d, GLES20.GL_TEXTURE_2D, texture,
                    overlayMvp, identity, coverFitVertices, tex2dCoords);
        }

        /** 计算"裁切填满"需要的纹理坐标子区间，居中裁切。 */
        private void computeCoverFillTexCoords(int coverWidth, int coverHeight,
                                               int targetWidth, int targetHeight) {
            float coverAspect = coverWidth / (float) Math.max(1, coverHeight);
            float targetAspect = targetWidth / (float) Math.max(1, targetHeight);
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
            if (coverAspect > targetAspect) {
                float keep = targetAspect / coverAspect;   // 左右各裁掉
                u0 = (1f - keep) / 2f;
                u1 = 1f - u0;
            } else if (coverAspect < targetAspect) {
                float keep = coverAspect / targetAspect;   // 上下各裁掉
                v0 = (1f - keep) / 2f;
                v1 = 1f - v0;
            }
            coverTexCoords.clear();
            coverTexCoords.put(u0).put(v1).put(u1).put(v1)
                    .put(u0).put(v0).put(u1).put(v0);
            coverTexCoords.position(0);
        }

        private void drawOesFull(int texture, float[] texMatrix, int sourceWidth,
                                 int sourceHeight, int rotation,
                                 int targetWidth, int targetHeight) {
            float[] mvp = new float[16];
            Matrix.setIdentityM(mvp, 0);
            int rotatedWidth = sourceWidth;
            int rotatedHeight = sourceHeight;
            if (rotation == 90 || rotation == 270) {
                rotatedWidth = sourceHeight;
                rotatedHeight = sourceWidth;
            }
            float sourceAspect = rotatedWidth / (float) Math.max(1, rotatedHeight);
            float targetAspect = targetWidth / (float) Math.max(1, targetHeight);
            float scaleX = 1f;
            float scaleY = 1f;
            if (sourceAspect > targetAspect) scaleY = targetAspect / sourceAspect;
            else scaleX = sourceAspect / targetAspect;
            Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f);
            if (rotation != 0) Matrix.rotateM(mvp, 0, rotation, 0f, 0f, 1f);
            drawQuad(programOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture,
                    mvp, texMatrix, quadVertices, texOesCoords);
        }

        private void drawOverlay(int texture, Bitmap overlay,
                                 int targetWidth, int targetHeight) {
            float leftPx = Math.round(targetWidth * 0.035f);
            float bottomPx = Math.round(targetHeight * 0.05f);
            float rightPx = Math.min(targetWidth, leftPx + overlay.getWidth());
            float topPx = Math.min(targetHeight, bottomPx + overlay.getHeight());
            float x0 = 2f * leftPx / targetWidth - 1f;
            float x1 = 2f * rightPx / targetWidth - 1f;
            float y0 = 2f * bottomPx / targetHeight - 1f;
            float y1 = 2f * topPx / targetHeight - 1f;
            overlayVertices.clear();
            overlayVertices.put(x0).put(y0).put(x1).put(y0).put(x0).put(y1).put(x1).put(y1);
            overlayVertices.position(0);

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawQuad(program2d, GLES20.GL_TEXTURE_2D, texture,
                    overlayMvp, identity, overlayVertices, tex2dCoords);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawQuad(GlProgram program, int textureTarget, int texture, float[] mvp,
                              float[] texMatrix, FloatBuffer vertices, FloatBuffer texCoords) {
            vertices.position(0);
            texCoords.position(0);
            GLES20.glUseProgram(program.id);
            GLES20.glEnableVertexAttribArray(program.positionLocation);
            GLES20.glVertexAttribPointer(program.positionLocation, 2,
                    GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(program.texCoordLocation);
            GLES20.glVertexAttribPointer(program.texCoordLocation, 2,
                    GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glUniformMatrix4fv(program.mvpLocation, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(program.texMatrixLocation, 1, false, texMatrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(textureTarget, texture);
            GLES20.glUniform1i(program.samplerLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(program.positionLocation);
            GLES20.glDisableVertexAttribArray(program.texCoordLocation);
        }

        void release() {
            GLES20.glDeleteProgram(program2d.id);
            GLES20.glDeleteProgram(programOes.id);
        }

        private static void setTextureParams(int target) {
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static FloatBuffer floatBuffer(float[] values) {
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
            floatBuffer.put(values).position(0);
            return floatBuffer;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] != GLES20.GL_TRUE) {
                throw new RuntimeException("OpenGL 程序链接失败: "
                        + GLES20.glGetProgramInfoLog(program));
            }
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return program;
        }

        private static int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                throw new RuntimeException("OpenGL Shader 编译失败: "
                        + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }
    }
}
