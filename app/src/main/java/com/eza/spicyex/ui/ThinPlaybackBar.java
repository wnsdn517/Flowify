package com.eza.spicyex.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;

/** A thumb-less, rounded playback scrubber with a generous touch target. */
public final class ThinPlaybackBar extends View {
    public interface SeekListener {
        void onSeek(float fraction);
    }

    private static final int ACTIVE_COLOR = 0xEBFFFFFF;
    private static final int INACTIVE_COLOR = 0x42FFFFFF;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float idleHeight;
    private final float activeHeight;
    private SeekListener seekListener;
    private float progress;
    private float dragProgress;
    private float drawnHeight;
    private boolean dragging;
    private boolean seekable;
    private ValueAnimator heightAnimator;

    public ThinPlaybackBar(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        idleHeight = 7f * density;
        activeHeight = 12f * density;
        drawnHeight = idleHeight;
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Playback position");
    }

    public void setSeekListener(SeekListener listener) {
        seekListener = listener;
    }

    public void setProgress(float fraction) {
        float next = clamp(fraction);
        if (progress == next) return;
        progress = next;
        if (!dragging) invalidate();
    }

    public void setSeekable(boolean value) {
        seekable = value;
        setEnabled(value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float height = dragging ? activeHeight : drawnHeight;
        float top = (getHeight() - height) * 0.5f;
        track.set(0f, top, getWidth(), top + height);
        float radius = height * 0.5f;
        paint.setColor(INACTIVE_COLOR);
        canvas.drawRoundRect(track, radius, radius, paint);
        float fraction = dragging ? dragProgress : progress;
        float filled = getWidth() * clamp(fraction);
        if (filled > 0f) {
            track.right = Math.min(getWidth(), Math.max(filled, height));
            paint.setColor(ACTIVE_COLOR);
            canvas.drawRoundRect(track, radius, radius, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!seekable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                dragProgress = fractionAt(event.getX());
                animateHeight(activeHeight);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                dragProgress = fractionAt(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                dragProgress = fractionAt(event.getX());
                progress = dragProgress;
                dragging = false;
                animateHeight(idleHeight);
                invalidate();
                if (seekListener != null) seekListener.onSeek(progress);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                animateHeight(idleHeight);
                invalidate();
                return true;
            default:
                return dragging;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void animateHeight(float target) {
        if (heightAnimator != null) heightAnimator.cancel();
        heightAnimator = ValueAnimator.ofFloat(drawnHeight, target);
        heightAnimator.setDuration(180L);
        heightAnimator.setInterpolator(new DecelerateInterpolator());
        heightAnimator.addUpdateListener(animation -> {
            drawnHeight = (float) animation.getAnimatedValue();
            invalidate();
        });
        heightAnimator.start();
    }

    private float fractionAt(float x) {
        return getWidth() <= 0 ? 0f : clamp(x / getWidth());
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
