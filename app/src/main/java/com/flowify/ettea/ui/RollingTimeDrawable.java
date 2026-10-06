package com.eza.spicyex.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * A clock label drawn the way Apple Music's player draws its times (SwiftUI's numeric-text
 * content transition): when the value changes, only the characters that changed move - the old
 * one slides out and fades while the new one slides in from the other side, upward as the value
 * grows and downward as it shrinks (the remaining time counting down). Digits are tabular, so a
 * changing digit never shifts its neighbours.
 *
 * <p>Drawn as a TextView's foreground over its own (transparent) text: the label keeps its place
 * in Spotify's layout and its accessibility text, and no view is added over the screen.
 */
public final class RollingTimeDrawable extends Drawable implements Runnable {
    private static final long DURATION_MS = 520L;
    private static final long DIGIT_STAGGER_MS = 44L;
    private static final int MAX_STAGGERED_PLACES = 4;
    private static final Interpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final boolean alignEnd;
    private String text = "";
    private String previous = "";
    private int direction = 1;
    private long startedAt;
    private boolean animating;

    public RollingTimeDrawable(float textSizePx, Typeface typeface, int color, boolean alignEnd) {
        this.alignEnd = alignEnd;
        paint.setTextSize(textSizePx);
        if (typeface != null) paint.setTypeface(typeface);
        paint.setColor(color);
        // Tabular figures: every digit the same width, as Apple's clock labels have.
        paint.setFontFeatureSettings("tnum");
    }

    public void setColor(int color) {
        if (paint.getColor() == color) return;
        paint.setColor(color);
        invalidateSelf();
    }

    public String text() {
        return text;
    }

    /** Shows {@code next}, rolling the changed characters; {@code increasing} picks the side. */
    public void setText(String next, boolean increasing, boolean animate) {
        if (next == null) next = "";
        if (next.equals(text)) return;
        previous = text;
        text = next;
        direction = increasing ? 1 : -1;
        if (animate && containsDigit(previous) && containsDigit(text) && Motion.animationsEnabled()) {
            startedAt = SystemClock.uptimeMillis();
            if (!animating) {
                animating = true;
                scheduleSelf(this, SystemClock.uptimeMillis() + 16);
            }
        } else {
            animating = false;
            unscheduleSelf(this);
        }
        invalidateSelf();
    }

    private static boolean containsDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) return true;
        }
        return false;
    }

    /** Width the widest text of this shape ("-00:00") needs, for the label's minimum width. */
    public float widthFor(String sample) {
        return paint.measureText(sample);
    }

    @Override
    public void run() {
        if (!animating) return;
        invalidateSelf();
        if (SystemClock.uptimeMillis() - startedAt < totalDurationMs(text)) {
            scheduleSelf(this, SystemClock.uptimeMillis() + 16);
        } else {
            animating = false;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || text.isEmpty()) return;
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float lineHeight = metrics.descent - metrics.ascent;
        float baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        float progress = 1f;
        if (animating) {
            float t = Math.min(1f, elapsed / (float) totalDurationMs(text));
            progress = EASE.getInterpolation(t);
        }
        int baseAlpha = paint.getAlpha();
        float width = paint.measureText(text);
        float x = alignEnd ? bounds.right - width : bounds.left;
        int length = text.length();
        int oldLength = previous.length();
        canvas.save();
        canvas.clipRect(bounds.left - width, bounds.top, bounds.right + width, bounds.bottom);
        for (int i = 0; i < length; i++) {
            String ch = text.substring(i, i + 1);
            float charWidth = paint.measureText(ch);
            // Characters are matched from the end, where a clock's seconds are: 9:59 -> 10:00
            // keeps ":" and the seconds' places aligned while the minutes grow a digit.
            int fromEnd = length - 1 - i;
            int oldIndex = oldLength - 1 - fromEnd;
            String old = oldIndex >= 0 && oldIndex < oldLength ? previous.substring(oldIndex, oldIndex + 1) : "";
            long delay = DIGIT_STAGGER_MS * Math.min(fromEnd, MAX_STAGGERED_PLACES);
            float digitTime = Math.max(0f, Math.min(1f,
                    (elapsed - delay) / (float) DURATION_MS));
            float digitProgress = EASE.getInterpolation(digitTime);
            if (progress >= 1f || !Character.isDigit(ch.charAt(0)) || ch.equals(old)) {
                paint.setAlpha(baseAlpha);
                canvas.drawText(ch, x, baseline, paint);
            } else {
                float travel = lineHeight * 0.55f * direction;
                // Outgoing: up (or down) and away.
                if (!old.isEmpty()) {
                    paint.setAlpha(Math.round(baseAlpha * (1f - Math.min(1f, digitProgress * 1.6f))));
                    canvas.drawText(old, x + (charWidth - paint.measureText(old)) / 2f,
                            baseline - travel * digitProgress, paint);
                }
                // Incoming: from the other side into place.
                paint.setAlpha(Math.round(baseAlpha * Math.min(1f, digitProgress * 1.25f)));
                canvas.drawText(ch, x, baseline + travel * (1f - digitProgress), paint);
            }
            x += charWidth;
        }
        paint.setAlpha(baseAlpha);
        canvas.restore();
    }

    private static long totalDurationMs(String value) {
        return DURATION_MS + DIGIT_STAGGER_MS
                * Math.min(Math.max(0, value.length() - 1), MAX_STAGGERED_PLACES);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
