// Double-tap like style "Lens" (Settings.DOUBLE_TAP_LIKE_EFFECT), kept as it was
// designed at c8d7d31f. See LikeBursts for the list.
package com.flowify.ettea.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
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
 * The like acknowledgement as a drop of liquid glass, after iOS 26's material: where the finger
 * was, a lens forms over the screen itself - the lyrics and the backdrop behind it bend through
 * it, split into a faint rainbow at its edge (chromatic dispersion) and its outline ripples as it
 * spreads and thins out. The mark rides in it: soft-cornered, pearly, turning broadly into place
 * on a slow spring rather than snapping, then lifting away as the lens dissolves.
 *
 * <p>About a second in all - long enough to read as unhurried, short enough for a like. The lens
 * is an AGSL shader applied to the parent as a RenderEffect (API 33+); below that, the mark plays
 * on its own.
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the like
 * button (which is the icon there): a smaller lens, no mark.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself (and
 * clears the lens from the parent) when done. One animator drives every part.
 */
public final class LikeBurstLens extends View implements LikeBurst {
    private static final long BIG_MS = 1050L;
    private static final long SMALL_MS = 760L;

    /**
     * The lens: inside it the content is magnified a little; along its rim it is pushed outward
     * and the three channels are pulled apart along the radius (the rainbow fringe), with a faint
     * iridescent sheen. The rim ripples (two slow angular waves) and the whole lens fades with
     * {@code strength}, so at 0 the content passes through untouched.
     */
    private static final String LENS_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float radius;"
            + "uniform float strength;"
            + "uniform float time;"
            + "uniform float aberration;"
            + "half4 main(float2 p) {"
            + "  float2 d = p - center;"
            + "  float r = length(d);"
            + "  if (strength <= 0.001 || r > radius * 2.2) return content.eval(p);"
            + "  float2 dir = r > 0.001 ? d / r : float2(0.0);"
            + "  float ang = atan(d.y, d.x);"
            + "  float wob = 1.0 + 0.07 * sin(ang * 3.0 + time * 5.0) + 0.045 * sin(ang * 5.0 - time * 3.7);"
            + "  float R = radius * wob;"
            + "  float x = r / R;"
            + "  float k = (x - 1.0) * 3.0;"
            + "  float rim = exp(-k * k);"
            + "  float inside = 1.0 - smoothstep(0.0, 1.05, x);"
            + "  float2 base = center + d * (1.0 - 0.13 * strength * inside) - dir * (0.11 * R * strength * rim);"
            + "  float2 ca = dir * aberration * strength * (rim + 0.25 * inside * x);"
            + "  half4 c = content.eval(base);"
            + "  half3 col = half3(content.eval(base + ca).r, c.g, content.eval(base - ca).b);"
            + "  float hue = ang / 6.2831853 + time * 0.12;"
            + "  half3 iri = half3(0.5 + 0.5 * cos(6.2831853 * (hue + float3(0.0, 0.33, 0.67))));"
            + "  col += iri * half(0.09 * strength * rim);"
            + "  col += half3(0.06 * strength * rim * (0.5 + 0.5 * dir.y * -1.0));"
            + "  return half4(col, c.a);"
            + "}";

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pearlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SweepGradient flowShader;
    private final android.graphics.Matrix flowMatrix = new android.graphics.Matrix();
    private final Path icon = new Path();
    private final boolean big;
    private final boolean star;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private Object lens;            // RuntimeShader, API 33+
    private ViewGroup host;
    private float t;
    private float appliedBlur = -1f;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the mark; false is the lens alone
     * @param x     centre, in the parent's coordinates
     * @param size  the mark's size in px (big), or the button's size (small)
     */
    public LikeBurstLens(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.star = star;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        // Soft corners: the points are rounded off once, into the path every paint uses.
        Paint rounder = new Paint();
        rounder.setStyle(Paint.Style.FILL);
        rounder.setPathEffect(new CornerPathEffect(star ? 2.6f : 1.4f));
        rounder.getFillPath(ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART), icon);
        // The exact colours of the like button's saved state.
        int accent = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);
        int pale = blend(accent, Color.WHITE, 0.62f);

        // In the mark's own 24-unit space: pale at the top, the colour lower down.
        bodyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setShader(new LinearGradient(0f, 2f, 0f, 22f,
                new int[]{pale, blend(accent, Color.WHITE, 0.18f), accent},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        // A pearly sheen resting in the upper left, as if lit by a soft window.
        pearlPaint.setShader(new RadialGradient(8f, 7f, 12f,
                new int[]{0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(0.45f);
        rimPaint.setStrokeJoin(Paint.Join.ROUND);
        rimPaint.setShader(new LinearGradient(0f, 2f, 0f, 14f,
                0x8CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Light flowing round the edge: two soft arcs on a sweep, turned as it plays.
        flowShader = new SweepGradient(12f, 12f,
                new int[]{0x00FFFFFF, 0xB0FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x80FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.12f, 0.26f, 0.5f, 0.62f, 0.76f});
        flowPaint.setStyle(Paint.Style.STROKE);
        flowPaint.setStrokeWidth(0.7f);
        flowPaint.setStrokeJoin(Paint.Join.ROUND);
        flowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        flowPaint.setShader(flowShader);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                lens = new android.graphics.RuntimeShader(LENS_AGSL);
            } catch (Throwable t) {
                // AGSL compiles on the device: a shader error shows up here, not at build time.
                android.util.Log.w("SpicyLikeBurst", "lens shader unavailable: " + t);
                lens = null;
            }
        }
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the burst to {@code parent} and plays it; it removes itself at the end. */
    public void play(ViewGroup parent) {
        host = parent;
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(big ? BIG_MS : SMALL_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            applyLens();
            if (big) applyFocusBlur();
            invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                clearLens();
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstLens.this);
            }
        });
        clock.start();
    }

    @Override protected void onDetachedFromWindow() {
        clearLens();
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (big) drawMark(canvas);
    }

    /** The lens over the parent: swells out from the finger, rippling, then thins away. */
    private void applyLens() {
        if (Build.VERSION.SDK_INT < 33 || lens == null || host == null) return;
        android.graphics.RuntimeShader shader = (android.graphics.RuntimeShader) lens;
        float grow = decelerate(phase(t, 0f, 0.8f));
        float lensRadius = radius * (big ? 1.25f : 1.1f) * (0.55f + 0.95f * grow);
        // Quick to form, slow to go: rises over the first fifth, then eases out to nothing.
        float rise = smooth(phase(t, 0f, 0.2f));
        float fall = 1f - smooth(phase(t, 0.3f, 1f));
        float strength = rise * fall;
        shader.setFloatUniform("center", cx, cy);
        shader.setFloatUniform("radius", lensRadius);
        shader.setFloatUniform("strength", strength);
        shader.setFloatUniform("time", t * (big ? BIG_MS : SMALL_MS) / 1000f);
        shader.setFloatUniform("aberration", density * (big ? 5f : 3.5f));
        host.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
    }

    private void clearLens() {
        if (Build.VERSION.SDK_INT >= 31 && host != null) host.setRenderEffect(null);
        host = null;
    }

    /** The mark blurs in as it arrives and back out as it leaves (API 31+). */
    private void applyFocusBlur() {
        if (Build.VERSION.SDK_INT < 31) return;
        float focus = 1f - smooth(phase(t, 0f, 0.35f));
        float blur = 8f * density * Math.max(focus, exit() * 1.1f);
        if (Math.abs(blur - appliedBlur) < 0.25f) return;
        appliedBlur = blur;
        setRenderEffect(blur < 0.5f ? null
                : android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
    }

    /** The leaving part: 0 until the hold ends, then eases to 1. */
    private float exit() {
        return smooth(phase(t, 0.62f, 1f));
    }

    private void drawMark(Canvas canvas) {
        float alpha = smooth(phase(t, 0f, 0.25f)) * (1f - exit());
        if (alpha <= 0.003f) return;
        float in = phase(t, 0f, 0.7f);
        float scale = 0.5f + 0.5f * spring(in, 0.62, 10.0) + 0.06f * exit();
        // A broad, unhurried turn into place on a slow spring - not a short snap.
        float turn = (star ? -48f : -16f) * (1f - spring(in, 0.78, 7.0));
        float size = radius * 2f * scale;
        float e = exit();
        float rise = density * 22f * e * e;

        canvas.save();
        canvas.translate(cx, cy - rise);
        canvas.rotate(turn);
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);
        int a255 = Math.round(255 * alpha);
        bodyPaint.setAlpha(a255);
        canvas.drawPath(icon, bodyPaint);
        canvas.save();
        canvas.clipPath(icon);
        pearlPaint.setAlpha(a255);
        canvas.drawRect(-2f, -2f, 26f, 26f, pearlPaint);
        canvas.restore();
        rimPaint.setAlpha(a255);
        canvas.drawPath(icon, rimPaint);
        flowMatrix.setRotate(-60f + 260f * smooth(phase(t, 0.05f, 0.9f)), 12f, 12f);
        flowShader.setLocalMatrix(flowMatrix);
        flowPaint.setAlpha(a255);
        canvas.drawPath(icon, flowPaint);
        canvas.restore();
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

    /** A spring from 0 to 1 over p in [0, 1]; damping/omega set its overshoot and pace. */
    private static float spring(float p, double damping, double omega) {
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    /** This style answers with its own layer only. */
    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        play(parent);
    }
}
