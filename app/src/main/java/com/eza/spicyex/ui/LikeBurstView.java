package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * The like acknowledgement, done with light the way the newer iOS surfaces are (Liquid Glass, the
 * fluid Face ID ring): everything luminous is drawn additively, so it brightens the blurred
 * backdrop behind the lyrics instead of sitting on it as a flat sticker.
 *
 * <ol>
 *   <li>Gather - an iridescent ring of light swirls in around the finger, its edge rippling like
 *       liquid, tightening as it goes.</li>
 *   <li>Ignite - it collapses into a white-hot core; a shockwave of light rings out and the mark
 *       springs out of the flash with a turn.</li>
 *   <li>Glass - the mark is lit from above, rimmed with light, and a specular sheen sweeps across
 *       it; a soft coloured bloom spills around it and glowing sparks drift out.</li>
 *   <li>Exit - the mark rises and fades; the glow lingers a moment longer.</li>
 * </ol>
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the like
 * button (which is the icon there): the shockwave, bloom and sparks, no gather and no icon.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstView extends View {
    private static final long BIG_MS = 1250L;
    private static final long SMALL_MS = 760L;
    /** Where the gather hands over to the ignite, as a fraction of the big form's run. */
    private static final float IGNITE = 0.26f;

    private final Paint additive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader bloomShader;
    private final Shader coreShader;
    private final Shader sparkShader;
    private final SweepGradient ringShader;
    private final LinearGradient sheenShader;
    private final Matrix matrix = new Matrix();
    private final Path icon;
    private final Path ring = new Path();
    private final boolean big;
    private final boolean star;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private final int accent;
    private final float[][] sparks;
    private final float spin;
    private float t;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the light around the like button
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstView(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.star = star;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        // The exact colours of the like button's saved state.
        this.accent = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);
        // Neighbouring hues for the iridescence: warmer and cooler than the accent.
        int warm = star ? Color.rgb(255, 150, 60) : Color.rgb(255, 120, 70);
        int cool = star ? Color.rgb(255, 245, 190) : Color.rgb(200, 110, 255);
        int pale = blend(accent, Color.WHITE, 0.7f);

        PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        additive.setXfermode(add);
        ringGlow.setXfermode(add);
        ringCore.setXfermode(add);
        sheenPaint.setXfermode(add);
        ringGlow.setStyle(Paint.Style.STROKE);
        ringCore.setStyle(Paint.Style.STROKE);
        ringGlow.setStrokeCap(Paint.Cap.ROUND);
        ringCore.setStrokeCap(Paint.Cap.ROUND);

        // Unit-size shaders, placed and scaled per frame through the canvas.
        ringShader = new SweepGradient(0f, 0f,
                new int[]{accent, warm, pale, cool, accent, warm, accent},
                new float[]{0f, 0.17f, 0.33f, 0.5f, 0.67f, 0.84f, 1f});
        ringGlow.setShader(ringShader);
        ringCore.setShader(ringShader);
        bloomShader = new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(accent, 0x70), withAlpha(warm, 0x30), withAlpha(accent, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        coreShader = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFFFFF, withAlpha(pale, 0xA0), withAlpha(accent, 0)},
                new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP);
        sparkShader = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFFFFF, withAlpha(pale, 0x90), withAlpha(accent, 0)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);

        // The glass body, in the icon's own 24-unit space: lit from above.
        bodyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setShader(new LinearGradient(0f, 1f, 0f, 23f,
                new int[]{blend(accent, Color.WHITE, 0.55f), accent, blend(accent, Color.BLACK, 0.12f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // A rim of light, brightest along the top edge.
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(0.7f);
        rimPaint.setStrokeJoin(Paint.Join.ROUND);
        rimPaint.setShader(new LinearGradient(0f, 1f, 0f, 23f,
                0xE6FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        // A diagonal band of light, moved across the body by its local matrix.
        sheenShader = new LinearGradient(-6f, 0f, 6f, 0f,
                new int[]{0x00FFFFFF, 0x8CFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        sheenPaint.setShader(sheenShader);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);

        Random random = new Random();
        spin = random.nextBoolean() ? 1f : -1f;
        int count = big ? 9 : 6;
        float offset = random.nextFloat() * 6.28f;
        sparks = new float[count][];
        for (int i = 0; i < count; i++) {
            // {angle, reach (fraction of radius), size px, delay, drift}
            sparks[i] = new float[]{
                    offset + i * (6.2832f / count) + (random.nextFloat() - 0.5f) * 0.6f,
                    (big ? 1.5f : 1.3f) + 0.7f * random.nextFloat(),
                    density * (big ? 4.5f : 3.4f) * (0.6f + 0.6f * random.nextFloat()),
                    0.05f * random.nextFloat(),
                    (random.nextFloat() - 0.3f) * 0.5f};
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
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstView.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        // The small form starts at the ignite: the button press was its gather.
        float ignite = big ? IGNITE : 0f;
        if (big) drawGather(canvas, phase(t, 0f, ignite));
        float after = phase(t, ignite, 1f);
        drawBloom(canvas, after);
        drawShockwave(canvas, after);
        drawSparks(canvas, after);
        if (big) drawMark(canvas, after);
        drawCore(canvas, after);
    }

    /** The iridescent ring tightening around the finger, spinning, its edge rippling. */
    private void drawGather(Canvas canvas, float p) {
        if (p <= 0f || p >= 1f) return;
        float in = p * p;                                   // accelerates into the collapse
        float r = radius * (1.75f - 1.35f * in);
        float ripple = 0.07f * (1f - in);
        float phaseShift = p * 9f * spin;
        ring.rewind();
        int steps = 72;
        for (int i = 0; i <= steps; i++) {
            double a = i * (2 * Math.PI / steps);
            float rr = r * (1f + ripple * (float) Math.sin(3 * a + phaseShift)
                    + ripple * 0.5f * (float) Math.sin(5 * a - phaseShift * 1.3f));
            float x = (float) Math.cos(a) * rr;
            float y = (float) Math.sin(a) * rr;
            if (i == 0) ring.moveTo(x, y);
            else ring.lineTo(x, y);
        }
        ring.close();
        float alpha = Math.min(1f, p * 4f);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(spin * 220f * p);
        ringGlow.setStrokeWidth(density * (9f - 4f * in));
        ringGlow.setAlpha(Math.round(70 * alpha));
        canvas.drawPath(ring, ringGlow);
        ringCore.setStrokeWidth(density * (2.2f - 0.8f * in));
        ringCore.setAlpha(Math.round(235 * alpha));
        canvas.drawPath(ring, ringCore);
        canvas.restore();
    }

    /** The white-hot core the ring collapses into, flaring and fading fast. */
    private void drawCore(Canvas canvas, float p) {
        if (p >= 1f) return;
        float flare = big ? phase(p, 0f, 0.28f) : phase(p, 0f, 0.35f);
        if (flare >= 1f) return;
        float strength = flare < 0.2f ? flare / 0.2f : 1f - (flare - 0.2f) / 0.8f;
        strength *= strength;
        float r = radius * (big ? 0.95f : 0.8f) * (0.6f + 0.6f * flare);
        drawGlow(canvas, coreShader, r, strength);
    }

    /** A thin ring of light thrown out by the ignite. */
    private void drawShockwave(Canvas canvas, float p) {
        float w = phase(p, 0f, big ? 0.5f : 0.75f);
        if (w <= 0f || w >= 1f) return;
        float r = radius * (0.7f + (big ? 1.6f : 1.2f) * decelerate(w));
        float fade = (1f - w) * (1f - w);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(spin * 60f * w);
        ringGlow.setStrokeWidth(density * 6f * (1f - 0.6f * w));
        ringGlow.setAlpha(Math.round(55 * fade));
        canvas.drawCircle(0f, 0f, r, ringGlow);
        ringCore.setStrokeWidth(density * 1.3f);
        ringCore.setAlpha(Math.round(200 * fade));
        canvas.drawCircle(0f, 0f, r, ringCore);
        canvas.restore();
    }

    /** Soft coloured light spilling around the mark: swells quickly, fades slowly. */
    private void drawBloom(Canvas canvas, float p) {
        if (p <= 0f || p >= 1f) return;
        float r = radius * (big ? 1.6f : 1.3f) * (0.55f + 0.75f * decelerate(Math.min(1f, p * 1.6f)));
        float alpha = Math.min(1f, p * 8f) * (1f - p) * (1f - p) * (2f - (1f - p));
        drawGlow(canvas, bloomShader, r, Math.min(1f, alpha));
    }

    /** Glowing sparks flung out from the ignite, slowing, drifting, dimming as they go. */
    private void drawSparks(Canvas canvas, float p) {
        for (float[] s : sparks) {
            float local = phase(p, s[3], s[3] + (big ? 0.62f : 0.8f));
            if (local <= 0f || local >= 1f) continue;
            float travel = radius * (0.5f + (s[1] - 0.5f) * decelerate(local));
            double a = s[0] + s[4] * local;
            float x = cx + (float) Math.cos(a) * travel;
            float y = cy + (float) Math.sin(a) * travel - radius * 0.25f * local * local;
            float fade = 1f - local;
            float size = s[2] * (1f - 0.55f * local);
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(size, size);
            additive.setShader(sparkShader);
            additive.setAlpha(Math.round(255 * fade * fade));
            canvas.drawCircle(0f, 0f, 1f, additive);
            canvas.restore();
        }
    }

    /** The glass mark: springs out of the flash with a turn, sheen passing over, then rises. */
    private void drawMark(Canvas canvas, float p) {
        if (p <= 0f) return;
        float in = phase(p, 0f, 0.45f);
        float sprung = spring(in);
        float scale = 0.2f + 0.8f * sprung;
        float out = phase(p, 0.62f, 1f);
        float eased = out * out * (3f - 2f * out);
        float alpha = Math.min(1f, in * 6f) * (1f - eased);
        if (alpha <= 0.003f) return;
        float rise = radius * 0.55f * eased;
        float size = radius * 2f * scale * (1f - 0.1f * eased);
        float turn = (star ? -24f : -10f) * spin * (1f - sprung);

        canvas.save();
        canvas.translate(cx, cy - rise);
        canvas.rotate(turn);
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);

        canvas.save();
        canvas.translate(0f, 0.9f);
        shadowPaint.setAlpha(Math.round(0x38 * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();

        int a255 = Math.round(255 * alpha);
        bodyPaint.setAlpha(a255);
        canvas.drawPath(icon, bodyPaint);

        // Specular sheen: one diagonal pass of light across the body, clipped to it.
        float sweep = phase(p, 0.12f, 0.6f);
        if (sweep > 0f && sweep < 1f) {
            canvas.save();
            canvas.clipPath(icon);
            matrix.setRotate(-30f);
            matrix.postTranslate(-8f + 40f * sweep, 12f);
            sheenShader.setLocalMatrix(matrix);
            sheenPaint.setAlpha(Math.round(255 * alpha * (float) Math.sin(Math.PI * sweep)));
            canvas.drawRect(0f, 0f, 24f, 24f, sheenPaint);
            canvas.restore();
        }

        rimPaint.setAlpha(a255);
        canvas.drawPath(icon, rimPaint);
        canvas.restore();
    }

    private void drawGlow(Canvas canvas, Shader shader, float r, float strength) {
        if (strength <= 0.003f || r <= 0f) return;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        additive.setShader(shader);
        additive.setAlpha(Math.round(255 * Math.min(1f, strength)));
        canvas.drawCircle(0f, 0f, 1f, additive);
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
        if (end <= start) return t >= end ? 1f : 0f;
        if (t <= start) return 0f;
        if (t >= end) return 1f;
        return (t - start) / (end - start);
    }

    private static float decelerate(float p) {
        return 1f - (1f - p) * (1f - p);
    }

    /** A lively spring from 0 to 1 (a clear overshoot), settled by the end. */
    private static float spring(float p) {
        double damping = 0.48;
        double omega = 13.0;
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }
}
