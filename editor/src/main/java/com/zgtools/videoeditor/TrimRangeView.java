package com.zgtools.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A dependency-free two-handle video range selector with a thumbnail timeline. */
public final class TrimRangeView extends View {
    public static final int HANDLE_START = 0;
    public static final int HANDLE_END = 1;

    public interface OnRangeChangeListener {
        void onRangeChanged(float startFraction, float endFraction, int activeHandle,
                            boolean fromUser);
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect sourceRect = new Rect();
    private final RectF destinationRect = new RectF();
    private final ArrayList<Bitmap> thumbnails = new ArrayList<>();

    private float startFraction = 0f;
    private float endFraction = 1f;
    private float playheadFraction = 0f;
    private float minimumSpanFraction = 0.01f;
    private int activeHandle = HANDLE_START;
    private boolean dragging;
    private float dragTouchOffsetX;
    private OnRangeChangeListener listener;

    public TrimRangeView(Context context) {
        this(context, null);
    }

    public TrimRangeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrimRangeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFocusable(true);
        setClickable(true);
    }

    public void setOnRangeChangeListener(OnRangeChangeListener listener) {
        this.listener = listener;
    }

    public void setDurationMs(long durationMs) {
        if (durationMs <= 0L) {
            minimumSpanFraction = 0.01f;
        } else {
            minimumSpanFraction = Math.max(0.001f, Math.min(0.5f, 500f / durationMs));
        }
    }

    public void setRange(float start, float end) {
        startFraction = clamp(start, 0f, 1f);
        endFraction = clamp(end, 0f, 1f);
        if (endFraction - startFraction < minimumSpanFraction) {
            endFraction = Math.min(1f, startFraction + minimumSpanFraction);
            startFraction = Math.max(0f, endFraction - minimumSpanFraction);
        }
        invalidate();
        notifyRangeChanged(activeHandle, false);
    }

    public void setPlayheadFraction(float fraction) {
        float clamped = clamp(fraction, 0f, 1f);
        if (Math.abs(clamped - playheadFraction) < 0.0005f) return;
        playheadFraction = clamped;
        invalidate();
    }

    public void setThumbnails(List<Bitmap> values) {
        thumbnails.clear();
        if (values != null) thumbnails.addAll(values);
        invalidate();
    }

    public List<Bitmap> getThumbnails() {
        return Collections.unmodifiableList(thumbnails);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float contentLeft = dp(22f);
        float contentRight = getWidth() - dp(22f);
        float contentTop = dp(11f);
        float contentBottom = getHeight() - dp(11f);
        if (contentRight <= contentLeft || contentBottom <= contentTop) return;

        destinationRect.set(0f, dp(2f), getWidth(), getHeight() - dp(2f));
        fillPaint.setColor(Color.rgb(43, 44, 48));
        canvas.drawRoundRect(destinationRect, dp(16f), dp(16f), fillPaint);
        drawThumbnails(canvas, contentLeft, contentTop, contentRight, contentBottom);

        float startX = contentLeft + startFraction * (contentRight - contentLeft);
        float endX = contentLeft + endFraction * (contentRight - contentLeft);
        fillPaint.setColor(Color.argb(178, 0, 0, 0));
        canvas.drawRect(contentLeft, contentTop, startX, contentBottom, fillPaint);
        canvas.drawRect(endX, contentTop, contentRight, contentBottom, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2.5f));
        strokePaint.setColor(Color.rgb(232, 74, 98));
        canvas.drawLine(startX, contentTop + dp(1.25f),
                endX, contentTop + dp(1.25f), strokePaint);
        canvas.drawLine(startX, contentBottom - dp(1.25f),
                endX, contentBottom - dp(1.25f), strokePaint);
        strokePaint.setStyle(Paint.Style.FILL);

        float handleOffset = dp(13f);
        drawRangeHandle(canvas, clamp(startX - handleOffset, dp(8f), getWidth() - dp(8f)));
        drawRangeHandle(canvas, clamp(endX + handleOffset, dp(8f), getWidth() - dp(8f)));

        float playheadX = contentLeft + playheadFraction * (contentRight - contentLeft);
        drawPlayhead(canvas, playheadX);
    }

    private void drawThumbnails(Canvas canvas, float left, float top, float right, float bottom) {
        if (thumbnails.isEmpty()) return;
        float segmentWidth = (right - left) / thumbnails.size();
        for (int i = 0; i < thumbnails.size(); i++) {
            Bitmap bitmap = thumbnails.get(i);
            if (bitmap == null || bitmap.isRecycled()) continue;
            destinationRect.set(left + i * segmentWidth, top,
                    i == thumbnails.size() - 1 ? right : left + (i + 1) * segmentWidth,
                    bottom);
            calculateCenterCrop(bitmap, destinationRect, sourceRect);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint);
        }
    }

    private void calculateCenterCrop(Bitmap bitmap, RectF destination, Rect source) {
        float sourceRatio = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());
        float destinationRatio = destination.width() / Math.max(1f, destination.height());
        if (sourceRatio > destinationRatio) {
            int cropWidth = Math.max(1, Math.round(bitmap.getHeight() * destinationRatio));
            int left = Math.max(0, (bitmap.getWidth() - cropWidth) / 2);
            source.set(left, 0, Math.min(bitmap.getWidth(), left + cropWidth), bitmap.getHeight());
        } else {
            int cropHeight = Math.max(1, Math.round(bitmap.getWidth() / destinationRatio));
            int top = Math.max(0, (bitmap.getHeight() - cropHeight) / 2);
            source.set(0, top, bitmap.getWidth(), Math.min(bitmap.getHeight(), top + cropHeight));
        }
    }

    private void drawRangeHandle(Canvas canvas, float centerX) {
        float width = dp(5.5f);
        float height = Math.min(dp(30f), getHeight() * 0.34f);
        float centerY = getHeight() / 2f;
        destinationRect.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f);
        fillPaint.setColor(Color.WHITE);
        canvas.drawRoundRect(destinationRect, width / 2f, width / 2f, fillPaint);
    }

    private void drawPlayhead(Canvas canvas, float centerX) {
        float width = dp(5.5f);
        float top = dp(3f);
        float bottom = getHeight() - dp(3f);
        destinationRect.set(centerX - width / 2f, top, centerX + width / 2f, bottom);
        fillPaint.setColor(Color.rgb(232, 74, 98));
        canvas.drawRoundRect(destinationRect, width / 2f, width / 2f, fillPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        float contentLeft = dp(22f);
        float contentRight = getWidth() - dp(22f);
        if (contentRight <= contentLeft) return false;
        float startX = contentLeft + startFraction * (contentRight - contentLeft);
        float endX = contentLeft + endFraction * (contentRight - contentLeft);
        float handleOffset = dp(13f);
        float startHandleX = clamp(startX - handleOffset, dp(8f), getWidth() - dp(8f));
        float endHandleX = clamp(endX + handleOffset, dp(8f), getWidth() - dp(8f));

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                activeHandle = Math.abs(event.getX() - startHandleX)
                        <= Math.abs(event.getX() - endHandleX)
                        ? HANDLE_START : HANDLE_END;
                dragTouchOffsetX = event.getX()
                        - (activeHandle == HANDLE_START ? startHandleX : endHandleX);
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                updateFromHandleCenter(event.getX() - dragTouchOffsetX,
                        contentLeft, contentRight, handleOffset);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    updateFromHandleCenter(event.getX() - dragTouchOffsetX,
                            contentLeft, contentRight, handleOffset);
                    performClick();
                }
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return false;
        }
    }

    private void updateFromHandleCenter(float handleCenterX, float left, float right,
                                        float handleOffset) {
        float selectionX = activeHandle == HANDLE_START
                ? handleCenterX + handleOffset : handleCenterX - handleOffset;
        float value = clamp((selectionX - left) / Math.max(1f, right - left), 0f, 1f);
        if (activeHandle == HANDLE_START) {
            startFraction = Math.min(value, endFraction - minimumSpanFraction);
            startFraction = Math.max(0f, startFraction);
        } else {
            endFraction = Math.max(value, startFraction + minimumSpanFraction);
            endFraction = Math.min(1f, endFraction);
        }
        invalidate();
        notifyRangeChanged(activeHandle, true);
    }

    private void notifyRangeChanged(int handle, boolean fromUser) {
        if (listener != null) listener.onRangeChanged(startFraction, endFraction, handle, fromUser);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
