// Double-tap like style "Pearl" (Settings.DOUBLE_TAP_LIKE_EFFECT), kept as it was
// designed at 54a91ff8. See LikeBursts for the list.
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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like acknowledgement, done the way Apple's own are: one mark, no confetti, all motion
 * carried by light and focus. It springs into focus out of a blur - swelling past full size once
 * and settling - in a pearly gradient of the Liked Songs colour; a single soft wave of light opens
 * out behind it and highlights flow round its edge; then it lifts away with momentum, blurring
 * and fading. The light is drawn additively, so it brightens the blurred backdrop rather than
 * sitting on it.
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the like
 * button (which is the icon there): only the glow, breathing out once.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstPearl extends View implements LikeBurst {
    private static final long BIG_MS = 1150L;
    private static final long SMALL_MS = 650L;
    /** Blur the mark comes into focus from, and dissolves back into (px at 1x density). */
    private static final float FOCUS_BLUR_DP = 9f;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pearlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SweepGradient flowShader;
    private final android.graphics.Matrix flowMatrix = new android.graphics.Matrix();
    private final Path icon;
    private final boolean big;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private float t;
    private float appliedBlur = -1f;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the glow alone
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstPearl(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        // The exact colours of the like button's saved state.
        int accent = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);
        int pale = blend(accent, Color.WHITE, 0.62f);

        // A faint glow of the colour, additive: a light, not a disc.
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        glowPaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(pale, 0x55), withAlpha(accent, 0x22), withAlpha(accent, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        // The body, in the icon's own 24-unit space: pale at the top, the colour lower down.
        bodyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setShader(new LinearGradient(0f, 2f, 0f, 22f,
                new int[]{pale, blend(accent, Color.WHITE, 0.18f), accent},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        // A pearly sheen resting in the upper left, as if lit by a soft window.
        pearlPaint.setShader(new RadialGradient(8f, 7f, 11f,
                new int[]{0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // A hairline of light along the top edge only.
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(0.45f);
        rimPaint.setStrokeJoin(Paint.Join.ROUND);
        rimPaint.setShader(new LinearGradient(0f, 2f, 0f, 14f,
                0x8CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));

        // One soft wave of light: a blurred annulus, clear in the middle, faint at its crest.
        wavePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        wavePaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(pale, 0), withAlpha(pale, 0), withAlpha(pale, 0x50),
                        withAlpha(accent, 0)},
                new float[]{0f, 0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        // Light flowing round the edge: two bright arcs on a sweep, turned as it plays.
        flowShader = new SweepGradient(12f, 12f,
                new int[]{0x00FFFFFF, 0xD0FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x90FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.1f, 0.22f, 0.5f, 0.6f, 0.72f});
        flowPaint.setStyle(Paint.Style.STROKE);
        flowPaint.setStrokeWidth(0.7f);
        flowPaint.setStrokeJoin(Paint.Join.ROUND);
        flowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        flowPaint.setShader(flowShader);

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
            if (big) applyFocusBlur();
            invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstPearl.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (big) {
            drawWave(canvas);
            drawGlow(canvas, presence() * 0.9f, 1.35f + 0.25f * exit());
            drawMark(canvas);
        } else {
            // Breathes out once around the button.
            float p = t;
            float strength = (float) Math.sin(Math.PI * Math.min(1f, p * 1.15f));
            drawGlow(canvas, strength * 0.8f, 1.1f + 0.5f * decelerate(p));
        }
    }

    /** 0 to 1 as the mark arrives, back to 0 as it leaves. */
    private float presence() {
        float in = smooth(phase(t, 0f, 0.32f));
        return in * (1f - exit());
    }

    /** The leaving part: 0 until the hold ends, then eases to 1. */
    private float exit() {
        return smooth(phase(t, 0.58f, 1f));
    }

    /** The whole view blurs: into focus on arrival, back out as it leaves (API 31+). */
    private void applyFocusBlur() {
        if (Build.VERSION.SDK_INT < 31) return;
        float focus = 1f - smooth(phase(t, 0f, 0.36f));
        float blur = FOCUS_BLUR_DP * density * Math.max(focus, exit() * 1.1f);
        if (Math.abs(blur - appliedBlur) < 0.25f) return;
        appliedBlur = blur;
        setRenderEffect(blur < 0.5f ? null
                : android.graphics.RenderEffect.createBlurEffect(blur, blur,
                        Shader.TileMode.DECAL));
    }

    private void drawMark(Canvas canvas) {
        float alpha = presence();
        if (alpha <= 0.003f) return;
        float in = phase(t, 0f, 0.55f);
        // A lively spring: it swells past full size once and settles - no second bounce.
        float scale = 0.45f + 0.55f * spring(in) + 0.08f * exit();
        float size = radius * 2f * scale;
        // Leaves upward with momentum, picking up speed as it dissolves.
        float e = exit();
        float rise = density * 26f * e * e;

        canvas.save();
        canvas.translate(cx - size / 2f, cy - rise - size / 2f);
        canvas.scale(size / 24f, size / 24f);
        int a255 = Math.round(255 * alpha);
        bodyPaint.setAlpha(a255);
        canvas.drawPath(icon, bodyPaint);
        canvas.save();
        canvas.clipPath(icon);
        pearlPaint.setAlpha(a255);
        canvas.drawRect(0f, 0f, 24f, 24f, pearlPaint);
        canvas.restore();
        rimPaint.setAlpha(a255);
        canvas.drawPath(icon, rimPaint);
        flowMatrix.setRotate(-60f + 300f * smooth(phase(t, 0.05f, 0.85f)), 12f, 12f);
        flowShader.setLocalMatrix(flowMatrix);
        flowPaint.setAlpha(a255);
        canvas.drawPath(icon, flowPaint);
        canvas.restore();
    }

    /** A single soft wave of light opening out from the mark as it arrives. */
    private void drawWave(Canvas canvas) {
        float w = phase(t, 0.04f, 0.7f);
        if (w <= 0f || w >= 1f) return;
        float r = radius * (0.9f + 1.7f * decelerate(w));
        float strength = (float) Math.sin(Math.PI * Math.sqrt(w));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        wavePaint.setAlpha(Math.round(255 * strength));
        canvas.drawCircle(0f, 0f, 1f, wavePaint);
        canvas.restore();
    }

    private void drawGlow(Canvas canvas, float strength, float reach) {
        if (strength <= 0.003f) return;
        float r = radius * reach;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        glowPaint.setAlpha(Math.round(255 * Math.min(1f, strength)));
        canvas.drawCircle(0f, 0f, 1f, glowPaint);
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

    private static float smooth(float p) {
        return p * p * (3f - 2f * p);
    }

    private static float decelerate(float p) {
        return 1f - (1f - p) * (1f - p);
    }

    /** A spring from 0 to 1 with one clear swell past it (about 10%), settled by the end. */
    private static float spring(float p) {
        double damping = 0.56;
        double omega = 12.5;
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    /** This style answers with its own layer only. */
    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        play(parent);
    }
}
