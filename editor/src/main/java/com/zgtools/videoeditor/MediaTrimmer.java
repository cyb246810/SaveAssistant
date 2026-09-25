package com.zgtools.videoeditor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Fast, lossless MP4 trimming by remuxing samples into a pending MediaStore item. */
public final class MediaTrimmer {
    private static final int DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BUFFER_BYTES = 64 * 1024 * 1024;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static final class Result {
        public final Uri uri;
        public final long requestedStartMs;
        public final long actualStartMs;
        public final long durationMs;

        Result(Uri uri, long requestedStartMs, long actualStartMs, long durationMs) {
            this.uri = uri;
            this.requestedStartMs = requestedStartMs;
            this.actualStartMs = actualStartMs;
            this.durationMs = durationMs;
        }
    }

    private MediaTrimmer() {
    }

    public static Result trim(Context context, Uri inputUri, long requestedStartMs,
                              long requestedEndMs, ProgressListener progressListener)
            throws Exception {
        if (inputUri == null) throw new IllegalArgumentException("没有选择视频");
        if (requestedStartMs < 0L || requestedEndMs <= requestedStartMs) {
            throw new IllegalArgumentException("剪辑范围无效");
        }

        long requestedStartUs = requestedStartMs * 1000L;
        long requestedEndUs = requestedEndMs * 1000L;
        long actualStartUs = findPreviousVideoSyncUs(context, inputUri, requestedStartUs);
        if (requestedEndUs - actualStartUs < 100_000L) {
            throw new IllegalArgumentException("所选片段太短");
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "保存助手_剪辑_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                        .format(new Date()) + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/保存助手/剪辑");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new Exception("无法在相册创建剪辑文件");

        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        AssetFileDescriptor inputDescriptor = null;
        ParcelFileDescriptor outputDescriptor = null;
        boolean muxerStarted = false;
        boolean completed = false;
        try {
            extractor = new MediaExtractor();
            inputDescriptor = resolver.openAssetFileDescriptor(inputUri, "r");
            if (inputDescriptor == null) throw new Exception("无法打开所选视频");
            setExtractorDataSource(extractor, inputDescriptor);

            outputDescriptor = resolver.openFileDescriptor(outputUri, "rw");
            if (outputDescriptor == null) throw new Exception("无法打开相册输出位置");
            muxer = new MediaMuxer(outputDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int rotation = readRotation(context, inputUri);
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation);
            }

            int trackCount = extractor.getTrackCount();
            int[] outputTracks = new int[trackCount];
            Arrays.fill(outputTracks, -1);
            int maximumInputSize = DEFAULT_BUFFER_BYTES;
            int videoInputTrack = -1;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    continue;
                }
                if (mime.startsWith("video/") && videoInputTrack < 0) videoInputTrack = i;
                outputTracks[i] = muxer.addTrack(format);
                extractor.selectTrack(i);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    int candidate = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    maximumInputSize = Math.max(maximumInputSize, candidate);
                }
            }
            if (videoInputTrack < 0) throw new Exception("所选文件没有可剪辑的视频轨道");
            maximumInputSize = Math.min(MAX_BUFFER_BYTES,
                    Math.max(DEFAULT_BUFFER_BYTES, maximumInputSize));

            muxer.start();
            muxerStarted = true;
            extractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ByteBuffer buffer = ByteBuffer.allocateDirect(maximumInputSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int lastProgress = -1;
            int writtenVideoSamples = 0;
            while (true) {
                int inputTrack = extractor.getSampleTrackIndex();
                if (inputTrack < 0) break;
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0L || sampleTimeUs > requestedEndUs) break;
                if (sampleTimeUs < actualStartUs || outputTracks[inputTrack] < 0) {
                    if (!extractor.advance()) break;
                    continue;
                }

                buffer.clear();
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;
                info.offset = 0;
                info.size = sampleSize;
                info.presentationTimeUs = Math.max(0L, sampleTimeUs - actualStartUs);
                int sampleFlags = extractor.getSampleFlags();
                info.flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                }
                muxer.writeSampleData(outputTracks[inputTrack], buffer, info);
                if (inputTrack == videoInputTrack) writtenVideoSamples++;

                int progress = (int) Math.min(99L, Math.max(0L,
                        (sampleTimeUs - actualStartUs) * 100L
                                / Math.max(1L, requestedEndUs - actualStartUs)));
                if (progress != lastProgress && progressListener != null) {
                    lastProgress = progress;
                    progressListener.onProgress(progress);
                }
                if (!extractor.advance()) break;
            }
            if (writtenVideoSamples == 0) throw new Exception("所选范围没有可导出的视频画面");

            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(outputUri, ready, null, null);
            if (progressListener != null) progressListener.onProgress(100);
            completed = true;
            return new Result(outputUri, requestedStartMs, actualStartUs / 1000L,
                    Math.max(0L, requestedEndMs - actualStartUs / 1000L));
        } finally {
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            if (extractor != null) extractor.release();
            if (inputDescriptor != null) {
                try {
                    inputDescriptor.close();
                } catch (Exception ignored) {
                }
            }
            if (outputDescriptor != null) {
                try {
                    outputDescriptor.close();
                } catch (Exception ignored) {
                }
            }
            if (!completed) resolver.delete(outputUri, null, null);
        }
    }

    private static long findPreviousVideoSyncUs(Context context, Uri inputUri, long requestedUs)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        AssetFileDescriptor descriptor = null;
        try {
            descriptor = context.getContentResolver().openAssetFileDescriptor(inputUri, "r");
            if (descriptor == null) throw new Exception("无法读取所选视频");
            setExtractorDataSource(extractor, descriptor);
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) throw new Exception("所选文件没有视频轨道");
            extractor.selectTrack(videoTrack);
            extractor.seekTo(Math.max(0L, requestedUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long syncUs = extractor.getSampleTime();
            return syncUs < 0L ? 0L : Math.min(requestedUs, syncUs);
        } finally {
            extractor.release();
            if (descriptor != null) descriptor.close();
        }
    }

    private static void setExtractorDataSource(MediaExtractor extractor,
                                               AssetFileDescriptor descriptor) throws Exception {
        long length = descriptor.getDeclaredLength();
        if (length >= 0L) {
            extractor.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
        } else {
            extractor.setDataSource(descriptor.getFileDescriptor());
        }
    }

    private static int readRotation(Context context, Uri inputUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, inputUri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
