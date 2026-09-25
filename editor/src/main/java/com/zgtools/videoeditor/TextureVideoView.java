package com.zgtools.videoeditor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

/**
 * A small TextureView-backed video player. Unlike VideoView/SurfaceView, its video frames
 * participate in the normal view hierarchy, so still-image overlays cannot hide the live
 * picture because of vendor-specific surface ordering.
 */
public final class TextureVideoView extends TextureView
        implements TextureView.SurfaceTextureListener {
    private Uri videoUri;
    private MediaPlayer mediaPlayer;
    private Surface playbackSurface;
    private boolean prepared;
    private boolean startWhenPrepared;
    private boolean hasRenderedFirstFrame;
    private int pendingSeekMs = -1;
    private int videoWidth;
    private int videoHeight;

    private MediaPlayer.OnPreparedListener preparedListener;
    private MediaPlayer.OnCompletionListener completionListener;
    private MediaPlayer.OnErrorListener errorListener;
    private Runnable firstFrameListener;

    public TextureVideoView(Context context) {
        this(context, null);
    }

    public TextureVideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextureVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setSurfaceTextureListener(this);
        setOpaque(true);
    }

    public void setVideoURI(Uri uri) {
        videoUri = uri;
        pendingSeekMs = 0;
        startWhenPrepared = false;
        hasRenderedFirstFrame = false;
        releasePlayer();
        openVideoIfReady();
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) {
        preparedListener = listener;
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        completionListener = listener;
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        errorListener = listener;
    }

    public void setOnFirstFrameListener(Runnable listener) {
        firstFrameListener = listener;
    }

    public boolean hasRenderedFirstFrame() {
        return hasRenderedFirstFrame;
    }

    public void start() {
        if (prepared && mediaPlayer != null) {
            mediaPlayer.start();
            setKeepScreenOn(true);
        } else {
            startWhenPrepared = true;
        }
    }

    public void pause() {
        startWhenPrepared = false;
        if (prepared && mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        setKeepScreenOn(false);
    }

    public boolean isPlaying() {
        if (!prepared || mediaPlayer == null) return false;
        try {
            return mediaPlayer.isPlaying();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public int getCurrentPosition() {
        if (!prepared || mediaPlayer == null) return Math.max(0, pendingSeekMs);
        try {
            return Math.max(0, mediaPlayer.getCurrentPosition());
        } catch (IllegalStateException ignored) {
            return Math.max(0, pendingSeekMs);
        }
    }

    public void seekTo(int positionMs) {
        pendingSeekMs = Math.max(0, positionMs);
        if (!prepared || mediaPlayer == null) return;
        try {
            mediaPlayer.seekTo(pendingSeekMs);
        } catch (IllegalStateException ignored) {
        }
    }

    public void stopPlayback() {
        videoUri = null;
        pendingSeekMs = -1;
        startWhenPrepared = false;
        hasRenderedFirstFrame = false;
        releasePlayer();
    }

    private void openVideoIfReady() {
        if (videoUri == null || playbackSurface == null) return;
        releasePlayer();
        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        prepared = false;
        hasRenderedFirstFrame = false;
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setDataSource(getContext(), videoUri);
            player.setSurface(playbackSurface);
            player.setScreenOnWhilePlaying(true);
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (mp != mediaPlayer) return;
                videoWidth = Math.max(0, width);
                videoHeight = Math.max(0, height);
                requestLayout();
            });
            player.setOnPreparedListener(mp -> {
                if (mp != mediaPlayer) return;
                prepared = true;
                videoWidth = Math.max(0, mp.getVideoWidth());
                videoHeight = Math.max(0, mp.getVideoHeight());
                requestLayout();
                if (pendingSeekMs >= 0) {
                    try {
                        mp.seekTo(pendingSeekMs);
                    } catch (IllegalStateException ignored) {
                    }
                }
                if (preparedListener != null) preparedListener.onPrepared(mp);
                if (startWhenPrepared) {
                    startWhenPrepared = false;
                    mp.start();
                    setKeepScreenOn(true);
                }
            });
            player.setOnCompletionListener(mp -> {
                if (mp != mediaPlayer) return;
                setKeepScreenOn(false);
                if (completionListener != null) completionListener.onCompletion(mp);
            });
            player.setOnInfoListener((mp, what, extra) -> {
                if (mp == mediaPlayer
                        && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    markFirstFrameRendered();
                }
                return false;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp != mediaPlayer) return true;
                prepared = false;
                startWhenPrepared = false;
                setKeepScreenOn(false);
                return errorListener != null && errorListener.onError(mp, what, extra);
            });
            player.prepareAsync();
        } catch (Exception error) {
            releasePlayer();
            if (errorListener != null) errorListener.onError(player, -1, 0);
        }
    }

    private void releasePlayer() {
        prepared = false;
        setKeepScreenOn(false);
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player == null) return;
        try {
            player.reset();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    private void markFirstFrameRendered() {
        if (hasRenderedFirstFrame) return;
        hasRenderedFirstFrame = true;
        if (firstFrameListener != null) post(firstFrameListener);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (playbackSurface != null) playbackSurface.release();
        playbackSurface = new Surface(surfaceTexture);
        openVideoIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        requestLayout();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        boolean resumeAfterRecreate = isPlaying();
        int resumePosition = getCurrentPosition();
        releasePlayer();
        if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
        pendingSeekMs = resumePosition;
        startWhenPrepared = resumeAfterRecreate;
        hasRenderedFirstFrame = false;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        if (!hasRenderedFirstFrame && prepared && isPlaying()) {
            markFirstFrameRendered();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(videoWidth, widthMeasureSpec);
        int height = getDefaultSize(videoHeight, heightMeasureSpec);
        if (videoWidth > 0 && videoHeight > 0) {
            int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);
            if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
                width = widthSize;
                height = heightSize;
                if (videoWidth * height < width * videoHeight) {
                    width = height * videoWidth / videoHeight;
                } else if (videoWidth * height > width * videoHeight) {
                    height = width * videoHeight / videoWidth;
                }
            } else if (widthMode == MeasureSpec.EXACTLY) {
                width = widthSize;
                height = width * videoHeight / videoWidth;
                if (heightMode == MeasureSpec.AT_MOST && height > heightSize) {
                    height = heightSize;
                }
            } else if (heightMode == MeasureSpec.EXACTLY) {
                height = heightSize;
                width = height * videoWidth / videoHeight;
                if (widthMode == MeasureSpec.AT_MOST && width > widthSize) {
                    width = widthSize;
                }
            }
        }
        setMeasuredDimension(width, height);
    }
}
