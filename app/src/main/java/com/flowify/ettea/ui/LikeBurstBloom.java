// Double-tap like style "Bloom" (Settings.DOUBLE_TAP_LIKE_EFFECT), kept as it was
// designed at 33000ac4. See LikeBursts for the list.
package com.flowify.ettea.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * The like acknowledgement, in the lyrics screen's own look - soft light on a dark, blurred
 * backdrop rather than confetti. A tinted bloom of light opens behind the mark, a few slim
 * four-point glints twinkle in place around it, and the mark itself (the Liked Songs colour the
 * like button turns to, lit a little from above) springs in - the star with a slight turn -
 * settles, then drifts up and fades.
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the
 * like button (which is the icon there): the bloom and the glints, no icon of its own.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstBloom extends View implements LikeBurst {
    private static final long BIG_MS = 1100L;
    private static final long SMALL_MS = 700L;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon;
    private final Path glint = new Path();
    private final boolean big;
    private final boolean star;
    private final float cx;
    private final float cy;
    private final float radius;
    private final int accent;
    private final float[][] glints;
    private float t;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the bloom and glints alone
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstBloom(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.star = star;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        // The exact colours of the like button's saved state.
        this.accent = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);

        iconPaint.setStyle(Paint.Style.FILL);
        // Lit from above: a lighter tint of the accent at the top, the accent itself below. In
        // the icon's own 24-unit space, which drawIcon scales to size.
        iconPaint.setShader(new LinearGradient(0f, 2f, 0f, 22f,
                blend(accent, Color.WHITE, 0.38f), accent, Shader.TileMode.CLAMP));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);
        // A unit bloom, scaled per frame: the accent faint at the core, gone at the rim.
        bloomPaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(accent, 0x66), withAlpha(accent, 0x24), withAlpha(accent, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glintPaint.setStyle(Paint.Style.FILL);

        // A slim four-point glint, unit size: long rays, pinched waist.
        glint.moveTo(0f, -1f);
        glint.quadTo(0.12f, -0.12f, 1f, 0f);
        glint.quadTo(0.12f, 0.12f, 0f, 1f);
        glint.quadTo(-0.12f, 0.12f, -1f, 0f);
        glint.quadTo(-0.12f, -0.12f, 0f, -1f);
        glint.close();

        // Spaced around the mark with a little jitter so it is not a clock face.
        Random random = new Random();
        int count = big ? 5 : 4;
        float offset = random.nextFloat() * 6.28f;
        float density = context.getResources().getDisplayMetrics().density;
        glints = new float[count][];
        for (int i = 0; i < count; i++) {
            // {angle, reach (fraction of radius), size px, start, accent(1)/white(0)}
            glints[i] = new float[]{
                    offset + i * (6.2832f / count) + (random.nextFloat() - 0.5f) * 0.5f,
                    (big ? 1.25f : 1.15f) + 0.3f * random.nextFloat(),
                    density * (big ? 7f : 5f) * (0.7f + 0.5f * random.nextFloat()),
                    0.08f + 0.22f * random.nextFloat(),
                    i % 2 == 0 ? 0f : 1f};
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
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstBloom.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        drawBloom(canvas);
        drawGlints(canvas);
        if (big) drawIcon(canvas);
    }

    /** Light opening out behind the mark: quick to swell, slow to fade. */
    private void drawBloom(Canvas canvas) {
        float p = phase(t, 0f, big ? 0.75f : 0.9f);
        if (p <= 0f || p >= 1f) return;
        float r = radius * (big ? 1.1f : 1.0f) * (0.6f + 1.1f * decelerate(p));
        float alpha = Math.min(1f, p * 6f) * (1f - p) * (1f - p);
        bloomPaint.setAlpha(Math.round(255 * alpha));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        canvas.drawCircle(0f, 0f, 1f, bloomPaint);
        canvas.restore();
    }

    /** Glints that twinkle where they are - grow, turn a little, shrink away - never fly off. */
    private void drawGlints(Canvas canvas) {
        float span = big ? 0.42f : 0.55f;
        for (float[] g : glints) {
            float local = phase(t, g[3], g[3] + span);
            if (local <= 0f || local >= 1f) continue;
            float twinkle = (float) Math.sin(Math.PI * local);
            twinkle *= twinkle;
            float reach = radius * (g[1] + 0.12f * local);
            float x = cx + (float) Math.cos(g[0]) * reach;
            float y = cy + (float) Math.sin(g[0]) * reach;
            glintPaint.setColor(g[4] > 0.5f ? blend(accent, Color.WHITE, 0.5f) : Color.WHITE);
            glintPaint.setAlpha(Math.round(235 * twinkle));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(25f * local);
            float s = g[2] * (0.35f + 0.65f * twinkle);
            canvas.scale(s, s);
            canvas.drawPath(glint, glintPaint);
            canvas.restore();
        }
    }

    /** The mark: springs in (the star with a slight turn), settles, then drifts up and fades. */
    private void drawIcon(Canvas canvas) {
        float in = phase(t, 0f, 0.42f);
        if (in <= 0f) return;
        float sprung = spring(in);
        float scale = 0.55f + 0.45f * sprung;
        float out = phase(t, 0.6f, 1f);
        float eased = out * out * (3f - 2f * out);
        float alpha = Math.min(1f, in * 4f) * (1f - eased);
        if (alpha <= 0.003f) return;
        float rise = radius * 0.45f * eased;
        float size = radius * 2f * scale * (1f - 0.08f * eased);
        float turn = star ? -14f * (1f - sprung) : 0f;

        canvas.save();
        canvas.translate(cx, cy - rise);
        canvas.rotate(turn);
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);
        canvas.save();
        canvas.translate(0f, 0.7f);
        shadowPaint.setAlpha(Math.round(0x30 * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();
        iconPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, iconPaint);
        canvas.restore();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
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
