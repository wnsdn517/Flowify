// Double-tap like style "Classic" (Settings.DOUBLE_TAP_LIKE_EFFECT), kept as it was
// designed at 763dd7f9. See LikeBursts for the list.
package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * The like acknowledgement, in the lyrics screen's own look: the Liked Songs colour the like
 * button itself turns to, a hairline glass ring, and a few soft dots - no gradients, glitter or
 * streaks. The icon springs in once, settles, and floats up a little while it fades.
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the
 * like button (which is the icon there): the ring and the dots, no icon of its own.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstClassic extends View implements LikeBurst {
    private static final long BIG_MS = 950L;
    private static final long SMALL_MS = 620L;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon;
    private final boolean big;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private final int accent;
    private final float[][] dots;
    private float t;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the ring and dots alone
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstClassic(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        // The exact colours of the like button's saved state.
        this.accent = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);

        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(accent);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, density * 1.2f));
        ringPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);

        // A loose ring of dots, evenly spaced with a little jitter so it is not a clock face.
        Random random = new Random();
        int count = big ? 8 : 6;
        float offset = random.nextFloat() * 6.28f;
        dots = new float[count][];
        for (int i = 0; i < count; i++) {
            // {angle, reach (fraction of radius), size px, delay, accent(1)/white(0)}
            dots[i] = new float[]{
                    offset + i * (6.2832f / count) + (random.nextFloat() - 0.5f) * 0.25f,
                    1.45f + 0.35f * random.nextFloat(),
                    density * (big ? 2.4f : 1.9f) * (0.8f + 0.4f * random.nextFloat()),
                    0.04f + 0.04f * random.nextFloat(),
                    i % 2 == 0 ? 1f : 0f};
        }
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the burst to {@code parent} and plays it; it removes itself at the end. */
    public void play(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(big ? BIG_MS : SMALL_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstClassic.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        drawRing(canvas);
        drawDots(canvas);
        if (big) drawIcon(canvas);
    }

    /** One thin ring that opens out from the icon and fades. */
    private void drawRing(Canvas canvas) {
        float p = phase(t, 0f, big ? 0.5f : 0.7f);
        if (p <= 0f || p >= 1f) return;
        float r = radius * (0.8f + 0.9f * decelerate(p));
        ringPaint.setAlpha(Math.round(120 * (1f - p) * (1f - p)));
        canvas.drawCircle(cx, cy, r, ringPaint);
    }

    /** A few round dots drifting out and fading - quiet, never streaking. */
    private void drawDots(Canvas canvas) {
        for (float[] d : dots) {
            float local = phase(t, d[3], d[3] + (big ? 0.5f : 0.7f));
            if (local <= 0f || local >= 1f) continue;
            float travel = radius * (0.7f + (d[1] - 0.7f) * decelerate(local));
            float x = cx + (float) Math.cos(d[0]) * travel;
            float y = cy + (float) Math.sin(d[0]) * travel;
            float fade = 1f - local * local;
            dotPaint.setColor(d[4] > 0.5f ? accent : Color.WHITE);
            dotPaint.setAlpha(Math.round((d[4] > 0.5f ? 230 : 170) * fade));
            canvas.drawCircle(x, y, d[2] * (1f - 0.5f * local), dotPaint);
        }
    }

    /** The icon: springs in, settles, then rises a little and fades. */
    private void drawIcon(Canvas canvas) {
        float in = phase(t, 0f, 0.4f);
        if (in <= 0f) return;
        float scale = 0.35f + 0.65f * spring(in);
        float out = phase(t, 0.55f, 1f);
        float alpha = Math.min(1f, in * 5f) * (1f - out * out);
        if (alpha <= 0.003f) return;
        float rise = radius * 0.7f * out * out;
        float size = radius * 2f * scale * (1f - 0.1f * out);

        canvas.save();
        canvas.translate(cx - size / 2f, cy - rise - size / 2f);
        canvas.scale(size / 24f, size / 24f);
        canvas.save();
        canvas.translate(0f, 0.6f);
        shadowPaint.setAlpha(Math.round(0x2A * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();
        iconPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, iconPaint);
        canvas.restore();
    }

    private static float phase(float t, float start, float end) {
        if (t <= start) return 0f;
        if (t >= end) return 1f;
        return (t - start) / (end - start);
    }

    private static float decelerate(float p) {
        return 1f - (1f - p) * (1f - p);
    }

    /** A soft spring from 0 to 1 (a few percent overshoot), settled by the end. */
    private static float spring(float p) {
        double damping = 0.62;
        double omega = 12.0;
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    /** This style answers with its own layer only. */
    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        play(parent);
    }
}
