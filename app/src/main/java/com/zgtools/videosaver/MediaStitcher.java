package com.zgtools.videosaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
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
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Android 系统 API 的图文/实况拼接器。
 * 不依赖 FFmpeg 或第三方 Maven 库，方便离线构建。
 */
public final class MediaStitcher {
    private static final String TAG = "MediaStitcher";
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int FPS = 30;
    private static final int AUDIO_RATE = 44100;
    private static final int AUDIO_CHANNELS = 2;
    private static final long CODEC_TIMEOUT_US = 10000;

    private MediaStitcher() {}

    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    public static final class SourceItem {
        public final String imageUrl;
        public final String videoUrl;
        public final int originalIndex;

        public SourceItem(String imageUrl, String videoUrl, int originalIndex) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.originalIndex = originalIndex;
        }

        public boolean isVideo() {
            return videoUrl != null && !videoUrl.isEmpty();
        }
    }

    public static final class Options {
        public float photoDurationSeconds = 2.0f;
        public boolean includeMusic = true;
        public boolean preserveOriginalAudio = true;
        public String musicUrl;
    }

    public static final class StitchResult {
        public final File outputFile;
        public final long durationUs;
        public final boolean includedMusic;
        public final boolean includedOriginalAudio;
        public final String warning;

        StitchResult(File outputFile, long durationUs, boolean includedMusic,
                     boolean includedOriginalAudio, String warning) {
            this.outputFile = outputFile;
            this.durationUs = durationUs;
            this.includedMusic = includedMusic;
            this.includedOriginalAudio = includedOriginalAudio;
            this.warning = warning;
        }
    }

    private static final class LocalSegment {
        final File file;
        final boolean video;
        final long durationUs;
        final int originalIndex;

        LocalSegment(File file, boolean video, long durationUs, int originalIndex) {
            this.file = file;
            this.video = video;
            this.durationUs = durationUs;
            this.originalIndex = originalIndex;
        }
    }

    public static StitchResult stitch(Context context, List<SourceItem> sourceItems,
                                      Options options, ProgressCallback callback) throws Exception {
        if (sourceItems == null || sourceItems.isEmpty()) {
            throw new IllegalArgumentException("没有选择要拼接的内容");
        }
        if (options == null) options = new Options();
        final float photoSeconds = Math.max(0.5f, Math.min(10.0f, options.photoDurationSeconds));
        final File workDir = new File(context.getCacheDir(), "stitch_" + System.currentTimeMillis());
        if (!workDir.mkdirs() && !workDir.isDirectory()) throw new Exception("无法创建拼接缓存目录");

        File videoOnly = new File(workDir, "video_only.mp4");
        File audioOnly = new File(workDir, "audio_only.m4a");
        File finalFile = new File(workDir, "stitched.mp4");
        File originalPcm = new File(workDir, "original_audio.pcm");
        File musicFile = new File(workDir, "music_source.bin");
        ArrayList<LocalSegment> segments = new ArrayList<>();
        StringBuilder warnings = new StringBuilder();

        int count = sourceItems.size();
        for (int i = 0; i < count; i++) {
            SourceItem item = sourceItems.get(i);
            int startPercent = 2 + (int) (20f * i / Math.max(1, count));
            notifyProgress(callback, startPercent, "正在准备第 " + (i + 1) + "/" + count + " 项");
            if (item.isVideo()) {
                File out = new File(workDir, "item_" + i + ".mp4");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    long bytes = DouyinParser.downloadVideoToStream(item.videoUrl, fos, null);
                    if (bytes <= 0) throw new Exception("动态视频数据为空");
                }
                long durationUs = getVideoDurationUs(out);
                if (durationUs <= 0) throw new Exception("无法读取第 " + (i + 1) + " 项视频时长");
                segments.add(new LocalSegment(out, true, durationUs, item.originalIndex));
            } else {
                byte[] data = DouyinParser.downloadImageBytes(item.imageUrl, null);
                if (data == null || data.length == 0) throw new Exception("第 " + (i + 1) + " 张照片下载失败");
                File out = new File(workDir, "item_" + i + ".img");
                try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(data); }
                segments.add(new LocalSegment(out, false,
                        (long) (photoSeconds * 1_000_000L), item.originalIndex));
            }
        }

        notifyProgress(callback, 24, "正在生成视频画面");
        int[] size = chooseOutputSize(segments);
        VideoComposer videoComposer = new VideoComposer(videoOnly, size[0], size[1], callback);
        long durationUs = videoComposer.compose(segments);

        boolean localOriginalAudio = options.preserveOriginalAudio && hasAnyAudioTrack(segments);
        boolean musicDownloaded = false;
        if (options.includeMusic && options.musicUrl != null && !options.musicUrl.isEmpty()) {
            notifyProgress(callback, 72, "正在下载原作品音乐");
            try (FileOutputStream fos = new FileOutputStream(musicFile)) {
                long bytes = DouyinParser.downloadAudioToStream(options.musicUrl, fos, null);
                musicDownloaded = bytes > 0;
            } catch (Exception e) {
                appendWarning(warnings, "原作品音乐下载失败");
                Log.w(TAG, "music download failed", e);
            }
        }

        boolean audioCreated = false;
        boolean includedMusic = false;
        boolean includedOriginal = false;
        if (localOriginalAudio || musicDownloaded) {
            notifyProgress(callback, 76, "正在处理声音");
            try {
                AudioComposer audioComposer = new AudioComposer(callback);
                AudioComposeResult ar = audioComposer.compose(segments, durationUs,
                        localOriginalAudio, musicDownloaded ? musicFile : null,
                        originalPcm, audioOnly);
                audioCreated = ar.created;
                includedMusic = ar.musicUsed;
                includedOriginal = ar.originalUsed;
                if (ar.warning != null) appendWarning(warnings, ar.warning);
            } catch (Exception e) {
                appendWarning(warnings, "声音处理失败，已输出无声拼接视频");
                Log.w(TAG, "audio compose failed", e);
                audioCreated = false;
            }
        }

        notifyProgress(callback, 96, "正在封装 MP4");
        if (audioCreated && audioOnly.isFile() && audioOnly.length() > 0) {
            mergeVideoAndAudio(videoOnly, audioOnly, finalFile);
        } else {
            copyFile(videoOnly, finalFile);
        }
        if (!finalFile.isFile() || finalFile.length() == 0) throw new Exception("拼接输出为空");
        notifyProgress(callback, 100, "拼接完成");
        return new StitchResult(finalFile, durationUs, includedMusic, includedOriginal,
                warnings.length() == 0 ? null : warnings.toString());
    }

    public static boolean remoteVideoHasAudio(String url) {
        if (url == null || url.isEmpty()) return false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36");
            headers.put("Referer", "https://www.douyin.com/");
            retriever.setDataSource(url, headers);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            return "yes".equalsIgnoreCase(value) || "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Exception e) {
            Log.w(TAG, "remote audio probe failed", e);
            return false;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static void notifyProgress(ProgressCallback callback, int percent, String message) {
        if (callback != null) callback.onProgress(Math.max(0, Math.min(100, percent)), message);
    }

    private static void appendWarning(StringBuilder target, String message) {
        if (message == null || message.isEmpty()) return;
        if (target.length() > 0) target.append("；");
        target.append(message);
    }

    private static long getVideoDurationUs(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0 : Long.parseLong(duration) * 1000L;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static int[] chooseOutputSize(List<LocalSegment> segments) throws Exception {
        int width = 720;
        int height = 1280;
        for (LocalSegment segment : segments) {
            if (segment.video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(segment.file.getAbsolutePath());
                    int w = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                    int h = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                    int rotation = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
                    if (rotation == 90 || rotation == 270) { int t = w; w = h; h = t; }
                    if (w > 0 && h > 0) { width = w; height = h; break; }
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(segment.file.getAbsolutePath(), o);
                if (o.outWidth > 0 && o.outHeight > 0) { width = o.outWidth; height = o.outHeight; break; }
            }
        }
        final int maxSide = 1280;
        if (Math.max(width, height) > maxSide) {
            float scale = maxSide / (float) Math.max(width, height);
            width = Math.round(width * scale);
            height = Math.round(height * scale);
        }
        width = Math.max(2, width & ~1);
        height = Math.max(2, height & ~1);
        width = Math.max(64, width);
        height = Math.max(64, height);
        return new int[]{width, height};
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (Exception e) { return fallback; }
    }

    private static boolean hasAnyAudioTrack(List<LocalSegment> segments) {
        for (LocalSegment s : segments) if (s.video && hasAudioTrack(s.file)) return true;
        return false;
    }

    private static boolean hasAudioTrack(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return true;
            }
        } catch (Exception ignored) {
        } finally {
            extractor.release();
        }
        return false;
    }

    private static void copyFile(File source, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
    }

    private static final class VideoComposer {
        private final File output;
        private final int width;
        private final int height;
        private final ProgressCallback callback;
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private EglInputSurface eglInputSurface;
        private TextureRenderer renderer;
        private int muxerTrack = -1;
        private boolean muxerStarted;
        private long lastWrittenPtsUs = -1;

        VideoComposer(File output, int width, int height, ProgressCallback callback) {
            this.output = output;
            this.width = width;
            this.height = height;
            this.callback = callback;
        }

        long compose(List<LocalSegment> segments) throws Exception {
            setupEncoder();
            long timelineUs = 0;
            try {
                for (int i = 0; i < segments.size(); i++) {
                    LocalSegment segment = segments.get(i);
                    int percent = 26 + (int) (44f * i / Math.max(1, segments.size()));
                    notifyProgress(callback, percent, "正在拼接画面 " + (i + 1) + "/" + segments.size());
                    if (segment.video) renderVideo(segment.file, timelineUs, segment.durationUs);
                    else renderImage(segment.file, timelineUs, segment.durationUs);
                    timelineUs += segment.durationUs;
                }
                drainEncoder(true);
                return timelineUs;
            } finally {
                release();
            }
        }

        private void setupEncoder() throws Exception {
            MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int bitRate = Math.max(2_500_000, Math.min(10_000_000, width * height * 5));
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = encoder.createInputSurface();
            encoder.start();
            eglInputSurface = new EglInputSurface(input);
            eglInputSurface.makeCurrent();
            renderer = new TextureRenderer(width, height);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        private void renderImage(File imageFile, long startUs, long durationUs) throws Exception {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            if (bitmap == null) throw new Exception("照片解码失败");
            int texture = 0;
            try {
                texture = renderer.createBitmapTexture(bitmap);
                int frameCount = Math.max(1, (int) Math.ceil(durationUs * FPS / 1_000_000.0));
                long frameStep = 1_000_000L / FPS;
                for (int f = 0; f < frameCount; f++) {
                    long pts = startUs + Math.min(durationUs - 1, f * frameStep);
                    renderer.drawBitmap(texture, bitmap.getWidth(), bitmap.getHeight());
                    eglInputSurface.setPresentationTime(pts * 1000L);
                    eglInputSurface.swapBuffers();
                    drainEncoder(false);
                }
            } finally {
                if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                bitmap.recycle();
            }
        }

        private void renderVideo(File videoFile, long timelineStartUs, long declaredDurationUs) throws Exception {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec decoder = null;
            DecoderOutputSurface outputSurface = null;
            try {
                extractor.setDataSource(videoFile.getAbsolutePath());
                int track = findTrack(extractor, "video/");
                if (track < 0) throw new Exception("动态片段没有视频轨道");
                extractor.selectTrack(track);
                MediaFormat inputFormat = extractor.getTrackFormat(track);
                String mime = inputFormat.getString(MediaFormat.KEY_MIME);
                int sourceW = inputFormat.containsKey(MediaFormat.KEY_WIDTH) ? inputFormat.getInteger(MediaFormat.KEY_WIDTH) : width;
                int sourceH = inputFormat.containsKey(MediaFormat.KEY_HEIGHT) ? inputFormat.getInteger(MediaFormat.KEY_HEIGHT) : height;
                int rotation = 0;
                if (Build.VERSION.SDK_INT >= 23 && inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = inputFormat.getInteger(MediaFormat.KEY_ROTATION);
                } else {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        r.setDataSource(videoFile.getAbsolutePath());
                        rotation = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
                    } finally { try { r.release(); } catch (Exception ignored) {} }
                }

                eglInputSurface.makeCurrent();
                outputSurface = new DecoderOutputSurface(renderer);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
                decoder.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                long firstSourcePts = -1;
                while (!outputDone) {
                    if (!inputDone) {
                        int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (inputIndex >= 0) {
                            ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                            if (inputBuffer == null) throw new Exception("视频解码输入缓冲区为空");
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long sampleTime = extractor.getSampleTime();
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // continue
                    } else if (outputIndex >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = info.size > 0 && !eos;
                        long sourcePts = info.presentationTimeUs;
                        decoder.releaseOutputBuffer(outputIndex, render);
                        if (render) {
                            outputSurface.awaitNewImage();
                            if (firstSourcePts < 0) firstSourcePts = sourcePts;
                            long relative = Math.max(0, sourcePts - firstSourcePts);
                            if (declaredDurationUs > 0) relative = Math.min(relative, declaredDurationUs - 1);
                            outputSurface.drawImage(sourceW, sourceH, rotation);
                            long pts = timelineStartUs + relative;
                            eglInputSurface.setPresentationTime(pts * 1000L);
                            eglInputSurface.swapBuffers();
                            drainEncoder(false);
                        }
                        if (eos) outputDone = true;
                    }
                }
            } finally {
                if (decoder != null) {
                    try { decoder.stop(); } catch (Exception ignored) {}
                    decoder.release();
                }
                if (outputSurface != null) outputSurface.release();
                extractor.release();
            }
        }

        private void drainEncoder(boolean endOfStream) throws Exception {
            if (endOfStream) encoder.signalEndOfInputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int index = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new Exception("视频编码格式重复变化");
                    muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (index >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(index);
                    if (data == null) throw new Exception("视频编码输出缓冲区为空");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0) {
                        if (!muxerStarted) throw new Exception("视频封装器尚未启动");
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        if (info.presentationTimeUs <= lastWrittenPtsUs) info.presentationTimeUs = lastWrittenPtsUs + 1;
                        lastWrittenPtsUs = info.presentationTimeUs;
                        muxer.writeSampleData(muxerTrack, data, info);
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        }

        private void release() {
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
                encoder = null;
            }
            if (eglInputSurface != null) { eglInputSurface.release(); eglInputSurface = null; }
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
                muxer = null;
            }
        }
    }

    private static final class AudioComposeResult {
        final boolean created;
        final boolean musicUsed;
        final boolean originalUsed;
        final String warning;
        AudioComposeResult(boolean created, boolean musicUsed, boolean originalUsed, String warning) {
            this.created = created; this.musicUsed = musicUsed; this.originalUsed = originalUsed; this.warning = warning;
        }
    }

    private static final class PcmData {
        final short[] samples;
        final int sampleRate;
        final int channels;
        PcmData(short[] samples, int sampleRate, int channels) {
            this.samples = samples; this.sampleRate = sampleRate; this.channels = channels;
        }
    }

    private static final class AudioComposer {
        private final ProgressCallback callback;
        AudioComposer(ProgressCallback callback) { this.callback = callback; }

        AudioComposeResult compose(List<LocalSegment> segments, long totalDurationUs,
                                   boolean keepOriginal, File musicSource,
                                   File originalTimeline, File audioOutput) throws Exception {
            long totalFrames = Math.max(1, totalDurationUs * AUDIO_RATE / 1_000_000L);
            boolean originalUsed = false;
            StringBuilder warning = new StringBuilder();

            if (keepOriginal) {
                try (FileOutputStream out = new FileOutputStream(originalTimeline)) {
                    long writtenFrames = 0;
                    for (int i = 0; i < segments.size(); i++) {
                        LocalSegment segment = segments.get(i);
                        long framesNeeded = Math.max(1, segment.durationUs * AUDIO_RATE / 1_000_000L);
                        if (segment.video && hasAudioTrack(segment.file)) {
                            try {
                                PcmData pcm = decodeAudio(segment.file);
                                short[] stereo = resampleToStereo(pcm, AUDIO_RATE);
                                long availableFrames = stereo.length / 2L;
                                long framesToWrite = Math.min(framesNeeded, availableFrames);
                                writeShorts(out, stereo, (int) Math.min(Integer.MAX_VALUE, framesToWrite * 2L));
                                if (framesToWrite < framesNeeded) writeSilence(out, framesNeeded - framesToWrite);
                                originalUsed = originalUsed || framesToWrite > 0;
                            } catch (Exception e) {
                                Log.w(TAG, "decode original audio failed", e);
                                writeSilence(out, framesNeeded);
                                appendWarning(warning, "部分视频原声无法解码");
                            }
                        } else {
                            writeSilence(out, framesNeeded);
                        }
                        writtenFrames += framesNeeded;
                        notifyProgress(callback, 78 + (int) (8f * (i + 1) / Math.max(1, segments.size())), "正在处理视频原声");
                    }
                    if (writtenFrames < totalFrames) writeSilence(out, totalFrames - writtenFrames);
                }
            }

            short[] musicStereo = null;
            boolean musicUsed = false;
            if (musicSource != null && musicSource.isFile() && musicSource.length() > 0) {
                try {
                    PcmData music = decodeAudio(musicSource);
                    musicStereo = resampleToStereo(music, AUDIO_RATE);
                    musicUsed = musicStereo.length >= 2;
                } catch (Exception e) {
                    Log.w(TAG, "decode music failed", e);
                    appendWarning(warning, "原作品音乐无法解码");
                }
            }

            if (!originalUsed && !musicUsed) {
                return new AudioComposeResult(false, false, false, warning.length() == 0 ? null : warning.toString());
            }
            notifyProgress(callback, 88, "正在混音");
            encodeAac(originalUsed ? originalTimeline : null, musicUsed ? musicStereo : null, totalFrames, audioOutput);
            return new AudioComposeResult(true, musicUsed, originalUsed, warning.length() == 0 ? null : warning.toString());
        }

        private void encodeAac(File originalPcm, short[] musicStereo, long totalFrames, File output) throws Exception {
            MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, AUDIO_CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024);
            MediaCodec encoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            MediaMuxer muxer = null;
            FileInputStream originalIn = null;
            boolean muxerStarted = false;
            int track = -1;
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                if (originalPcm != null) originalIn = new FileInputStream(originalPcm);
                long submittedFrames = 0;
                int musicFrame = 0;
                boolean inputDone = false;
                boolean outputDone = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                byte[] originalBytes = new byte[32768];

                while (!outputDone) {
                    if (!inputDone) {
                        int inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (inputIndex >= 0) {
                            ByteBuffer in = encoder.getInputBuffer(inputIndex);
                            if (in == null) throw new Exception("AAC 输入缓冲区为空");
                            in.clear(); in.order(ByteOrder.LITTLE_ENDIAN);
                            if (submittedFrames >= totalFrames) {
                                encoder.queueInputBuffer(inputIndex, 0, 0, submittedFrames * 1_000_000L / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                int maxFrames = Math.max(1, in.remaining() / 4);
                                int frames = (int) Math.min(maxFrames, totalFrames - submittedFrames);
                                int neededBytes = frames * 4;
                                int got = 0;
                                if (originalIn != null) {
                                    while (got < neededBytes) {
                                        int n = originalIn.read(originalBytes, got, neededBytes - got);
                                        if (n < 0) break;
                                        got += n;
                                    }
                                }
                                for (int f = 0; f < frames; f++) {
                                    int off = f * 4;
                                    short ol = got >= off + 4 ? littleShort(originalBytes, off) : 0;
                                    short or = got >= off + 4 ? littleShort(originalBytes, off + 2) : 0;
                                    short ml = 0, mr = 0;
                                    if (musicStereo != null && musicStereo.length >= 2) {
                                        int mf = (musicFrame % (musicStereo.length / 2)) * 2;
                                        ml = musicStereo[mf]; mr = musicStereo[mf + 1]; musicFrame++;
                                    }
                                    boolean hasOriginal = originalIn != null;
                                    boolean hasMusic = musicStereo != null && musicStereo.length >= 2;
                                    int left, right;
                                    if (hasOriginal && hasMusic) {
                                        left = (int) (ol * 0.72f + ml * 0.72f);
                                        right = (int) (or * 0.72f + mr * 0.72f);
                                    } else if (hasMusic) { left = ml; right = mr; }
                                    else { left = ol; right = or; }
                                    in.putShort(clampShort(left)); in.putShort(clampShort(right));
                                }
                                long pts = submittedFrames * 1_000_000L / AUDIO_RATE;
                                encoder.queueInputBuffer(inputIndex, 0, frames * 4, pts, 0);
                                submittedFrames += frames;
                            }
                        }
                    }
                    int outIndex = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new Exception("AAC 输出格式重复变化");
                        track = muxer.addTrack(encoder.getOutputFormat()); muxer.start(); muxerStarted = true;
                    } else if (outIndex >= 0) {
                        ByteBuffer data = encoder.getOutputBuffer(outIndex);
                        if (data == null) throw new Exception("AAC 输出缓冲区为空");
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0 && muxerStarted) {
                            data.position(info.offset); data.limit(info.offset + info.size); muxer.writeSampleData(track, data, info);
                        }
                        encoder.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    }
                }
            } finally {
                if (originalIn != null) try { originalIn.close(); } catch (Exception ignored) {}
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
                if (muxer != null) {
                    if (muxerStarted) try { muxer.stop(); } catch (Exception ignored) {}
                    muxer.release();
                }
            }
        }
    }

    private static PcmData decodeAudio(File source) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = findTrack(extractor, "audio/");
            if (track < 0) throw new Exception("没有音频轨道");
            extractor.selectTrack(track);
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            decoder = MediaCodec.createDecoderByType(mime);
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(inIndex);
                        if (in == null) throw new Exception("音频解码输入为空");
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = decoder.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (outIndex >= 0) {
                    ByteBuffer out = decoder.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size);
                        ByteBuffer copy = out.slice().order(ByteOrder.LITTLE_ENDIAN);
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            while (copy.remaining() >= 4) {
                                float value = Math.max(-1f, Math.min(1f, copy.getFloat()));
                                short sv = (short) Math.round(value * 32767f);
                                pcm.write(sv & 0xff); pcm.write((sv >>> 8) & 0xff);
                            }
                        } else {
                            byte[] bytes = new byte[copy.remaining()]; copy.get(bytes); pcm.write(bytes);
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            byte[] bytes = pcm.toByteArray();
            if (bytes.length < 2) return new PcmData(new short[0], sampleRate, channels);
            short[] samples = new short[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) samples[i] = littleShort(bytes, i * 2);
            return new PcmData(samples, sampleRate, Math.max(1, channels));
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            extractor.release();
        }
    }

    private static short[] resampleToStereo(PcmData pcm, int targetRate) {
        if (pcm.samples.length == 0 || pcm.channels <= 0 || pcm.sampleRate <= 0) return new short[0];
        int inputFrames = pcm.samples.length / pcm.channels;
        int outputFrames = Math.max(1, (int) Math.round(inputFrames * (double) targetRate / pcm.sampleRate));
        short[] out = new short[outputFrames * 2];
        for (int of = 0; of < outputFrames; of++) {
            double srcPos = of * (double) pcm.sampleRate / targetRate;
            int i0 = Math.min(inputFrames - 1, (int) Math.floor(srcPos));
            int i1 = Math.min(inputFrames - 1, i0 + 1);
            float frac = (float) (srcPos - i0);
            for (int ch = 0; ch < 2; ch++) {
                int srcCh = pcm.channels == 1 ? 0 : Math.min(ch, pcm.channels - 1);
                short a = pcm.samples[i0 * pcm.channels + srcCh];
                short b = pcm.samples[i1 * pcm.channels + srcCh];
                out[of * 2 + ch] = clampShort(Math.round(a + (b - a) * frac));
            }
        }
        return out;
    }

    private static void writeShorts(OutputStream out, short[] samples, int sampleCount) throws Exception {
        byte[] buffer = new byte[Math.min(64 * 1024, Math.max(2, sampleCount * 2))];
        int pos = 0;
        while (pos < sampleCount) {
            int count = Math.min(buffer.length / 2, sampleCount - pos);
            for (int i = 0; i < count; i++) {
                short v = samples[pos + i];
                buffer[i * 2] = (byte) (v & 0xff); buffer[i * 2 + 1] = (byte) ((v >>> 8) & 0xff);
            }
            out.write(buffer, 0, count * 2); pos += count;
        }
    }

    private static void writeSilence(OutputStream out, long frames) throws Exception {
        byte[] zeros = new byte[64 * 1024];
        long bytes = frames * 4L;
        while (bytes > 0) {
            int n = (int) Math.min(zeros.length, bytes); out.write(zeros, 0, n); bytes -= n;
        }
    }

    private static short littleShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    private static short clampShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void mergeVideoAndAudio(File video, File audio, File output) throws Exception {
        MediaExtractor v = new MediaExtractor();
        MediaExtractor a = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            v.setDataSource(video.getAbsolutePath()); a.setDataSource(audio.getAbsolutePath());
            int vt = findTrack(v, "video/"); int at = findTrack(a, "audio/");
            if (vt < 0) throw new Exception("拼接视频没有视频轨");
            if (at < 0) { copyFile(video, output); return; }
            v.selectTrack(vt); a.selectTrack(at);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outV = muxer.addTrack(v.getTrackFormat(vt)); int outA = muxer.addTrack(a.getTrackFormat(at)); muxer.start();
            copyTrack(v, muxer, outV); copyTrack(a, muxer, outA);
        } finally {
            v.release(); a.release();
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} muxer.release(); }
        }
    }

    private static void copyTrack(MediaExtractor extractor, MediaMuxer muxer, int outTrack) throws Exception {
        MediaFormat format = extractor.getTrackFormat(extractor.getSampleTrackIndex());
        int max = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(256 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1024 * 1024;
        ByteBuffer buffer = ByteBuffer.allocateDirect(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear(); int size = extractor.readSampleData(buffer, 0); if (size < 0) break;
            int sampleFlags = extractor.getSampleFlags();
            int bufferFlags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = bufferFlags;
            muxer.writeSampleData(outTrack, buffer, info); extractor.advance();
        }
    }

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
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) throw new RuntimeException("EGL 初始化失败");
            int[] attribList = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                    EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, num, 0)) throw new RuntimeException("EGL 配置失败");
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], nativeSurface, new int[]{EGL14.EGL_NONE}, 0);
        }
        void makeCurrent() { if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new RuntimeException("EGL makeCurrent 失败"); }
        void swapBuffers() { if (!EGL14.eglSwapBuffers(display, surface)) throw new RuntimeException("EGL swapBuffers 失败"); }
        void setPresentationTime(long nanoseconds) { EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds); }
        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
            }
            if (nativeSurface != null) nativeSurface.release(); nativeSurface = null;
        }
    }

    private static final class DecoderOutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private final TextureRenderer renderer;
        private final int textureId;
        private final SurfaceTexture surfaceTexture;
        private final Surface surface;
        private final Object frameSync = new Object();
        private boolean frameAvailable;
        private final float[] textureMatrix = new float[16];
        DecoderOutputSurface(TextureRenderer renderer) {
            this.renderer = renderer; textureId = renderer.createOesTexture(); surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this); surface = new Surface(surfaceTexture);
        }
        Surface getSurface() { return surface; }
        @Override public void onFrameAvailable(SurfaceTexture st) { synchronized (frameSync) { frameAvailable = true; frameSync.notifyAll(); } }
        void awaitNewImage() throws Exception {
            synchronized (frameSync) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!frameAvailable) {
                    long wait = deadline - System.currentTimeMillis(); if (wait <= 0) throw new Exception("等待视频帧超时");
                    try { frameSync.wait(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                }
                frameAvailable = false;
            }
            surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(textureMatrix);
        }
        void drawImage(int sourceW, int sourceH, int rotation) { renderer.drawOes(textureId, textureMatrix, sourceW, sourceH, rotation); }
        void release() { surface.release(); surfaceTexture.release(); GLES20.glDeleteTextures(1, new int[]{textureId}, 0); }
    }

    private static final class TextureRenderer {
        private static final String VERTEX =
                "uniform mat4 uMVPMatrix;\n" + "uniform mat4 uTexMatrix;\n" + "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" + "varying vec2 vTextureCoord;\n" +
                "void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = (uTexMatrix * aTextureCoord).xy; }\n";
        private static final String FRAGMENT_2D =
                "precision mediump float;\n" + "varying vec2 vTextureCoord;\n" + "uniform sampler2D sTexture;\n" +
                "void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }\n";
        private static final String FRAGMENT_OES =
                "#extension GL_OES_EGL_image_external : require\n" + "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" + "uniform samplerExternalOES sTexture;\n" +
                "void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }\n";
        private final int targetW, targetH;
        private final FloatBuffer vertices, tex2d, texOes;
        private final int program2d, programOes;
        private final float[] identity = new float[16];
        TextureRenderer(int targetW, int targetH) {
            this.targetW=targetW; this.targetH=targetH;
            vertices=floatBuffer(new float[]{-1f,-1f,1f,-1f,-1f,1f,1f,1f});
            tex2d=floatBuffer(new float[]{0f,1f,1f,1f,0f,0f,1f,0f});
            texOes=floatBuffer(new float[]{0f,0f,1f,0f,0f,1f,1f,1f});
            program2d=createProgram(VERTEX,FRAGMENT_2D); programOes=createProgram(VERTEX,FRAGMENT_OES); Matrix.setIdentityM(identity,0);
        }
        int createBitmapTexture(Bitmap bitmap) {
            int[] textures=new int[1]; GLES20.glGenTextures(1,textures,0); int id=textures[0]; GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id);
            setTextureParams(GLES20.GL_TEXTURE_2D); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0); return id;
        }
        int createOesTexture() { int[] t=new int[1]; GLES20.glGenTextures(1,t,0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,t[0]); setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES); return t[0]; }
        private void setTextureParams(int target) {
            GLES20.glTexParameterf(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameterf(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        }
        void drawBitmap(int texture,int sourceW,int sourceH){draw(program2d,GLES20.GL_TEXTURE_2D,texture,identity,tex2d,sourceW,sourceH,0);}
        void drawOes(int texture,float[] tm,int sourceW,int sourceH,int rotation){draw(programOes,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture,tm,texOes,sourceW,sourceH,rotation);}
        private void draw(int program,int textureTarget,int texture,float[] texMatrix,FloatBuffer texCoords,int sourceW,int sourceH,int rotation) {
            GLES20.glViewport(0,0,targetW,targetH); GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(program);
            int posLoc=GLES20.glGetAttribLocation(program,"aPosition"), texLoc=GLES20.glGetAttribLocation(program,"aTextureCoord");
            int mvpLoc=GLES20.glGetUniformLocation(program,"uMVPMatrix"), texMatrixLoc=GLES20.glGetUniformLocation(program,"uTexMatrix");
            float[] mvp=new float[16]; Matrix.setIdentityM(mvp,0); int rw=sourceW,rh=sourceH; if(rotation==90||rotation==270){rw=sourceH;rh=sourceW;}
            float sourceAspect=rw/(float)Math.max(1,rh), targetAspect=targetW/(float)Math.max(1,targetH), sx=1f,sy=1f;
            if(sourceAspect>targetAspect) sy=targetAspect/sourceAspect; else sx=sourceAspect/targetAspect;
            Matrix.scaleM(mvp,0,sx,sy,1f); if(rotation!=0) Matrix.rotateM(mvp,0,rotation,0f,0f,1f);
            vertices.position(0); texCoords.position(0); GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glEnableVertexAttribArray(texLoc); GLES20.glVertexAttribPointer(texLoc,2,GLES20.GL_FLOAT,false,0,texCoords);
            GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0); GLES20.glUniformMatrix4fv(texMatrixLoc,1,false,texMatrix,0); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(textureTarget,texture); GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"sTexture"),0); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(texLoc);
        }
        private static FloatBuffer floatBuffer(float[] values){ByteBuffer bb=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());FloatBuffer fb=bb.asFloatBuffer();fb.put(values).position(0);return fb;}
        private static int createProgram(String vertex,String fragment){int vs=loadShader(GLES20.GL_VERTEX_SHADER,vertex),fs=loadShader(GLES20.GL_FRAGMENT_SHADER,fragment),p=GLES20.glCreateProgram();GLES20.glAttachShader(p,vs);GLES20.glAttachShader(p,fs);GLES20.glLinkProgram(p);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]!=GLES20.GL_TRUE)throw new RuntimeException("OpenGL 程序链接失败: "+GLES20.glGetProgramInfoLog(p));GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);return p;}
        private static int loadShader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException("OpenGL Shader 编译失败: "+GLES20.glGetShaderInfoLog(s));return s;}
    }
}
