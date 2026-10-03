// Double-tap like style "Crystal" (Settings.DOUBLE_TAP_LIKE_EFFECT), kept as it was
// designed at af2d6fc6. See LikeBursts for the list.
package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like acknowledgement as liquid glass, everything done on the screen itself by one AGSL
 * shader (a RenderEffect on the parent, API 33+):
 *
 * <ol>
 *   <li>Ripples - two waves run out from the finger across the whole screen; the lyrics and the
 *       backdrop really bend in them, and their slopes split into a faint rainbow (each colour
 *       channel is displaced by a different amount).</li>
 *   <li>Drop to mark - a round drop of glass forms and flows into the mark (a soft-cornered star,
 *       or a heart): the shape is a signed distance field morphing from a circle, its edge
 *       rippling faintly like liquid, settling on a spring with little bounce and a small,
 *       unhurried turn.</li>
 *   <li>Glass - clear crystal, not coloured gel: inside it the screen is magnified a little and
 *       bent along a narrow bevel, barely frosted, with only a breath of the Liked Songs colour;
 *       a small sharp highlight where the light from the top left meets the rim, a band of light
 *       passing through, a faint rainbow along the bevel and a hairline of light on the outline.
 *       (Squash-and-stretch, a big turn, a gold tint and a glow round the outline all read as a
 *       toy, and were taken out.)</li>
 *   <li>Melt - it softens back toward a drop, lifts and thins away, sending one last small
 *       ripple out.</li>
 * </ol>
 *
 * <p>Below API 33 a plain frosted mark plays instead. The small form (around the like button,
 * which animates itself) is intentionally nothing - one acknowledgement is enough.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself (and
 * clears the effect from the parent) when done.
 */
public final class LikeBurstCrystal extends View implements LikeBurst {
    private static final long DURATION_MS = 1300L;

    private static final String LIQUID_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 origin;"
            + "uniform float2 center;"
            + "uniform float R;"
            + "uniform float scale;"
            + "uniform float sx;"
            + "uniform float sy;"
            + "uniform float angle;"
            + "uniform float morph;"
            + "uniform float wobble;"
            + "uniform float glass;"
            + "uniform float time;"
            + "uniform float4 ripA;"
            + "uniform float4 ripB;"
            + "uniform half3 tint;"
            + "uniform float heart;"
            + "uniform float2 bounds;"
            // Every sample stays inside the view: past its edge there is nothing, and the ripples
            // pulled that nothing in as dark seams along the sides.
            + "half4 at(float2 q) { return content.eval(clamp(q, float2(0.5), bounds - 0.5)); }"

            + "float dot2(float2 v) { return dot(v, v); }"
            // Inigo Quilez's five-pointed star (y up), rounded by `rr`.
            + "float sdStar(float2 p) {"
            + "  float2 k1 = float2(0.809016994375, -0.587785252292);"
            + "  float2 k2 = float2(-k1.x, k1.y);"
            + "  float rr = 0.13;"
            + "  float r = 0.92 - rr;"
            + "  p.x = abs(p.x);"
            + "  p -= 2.0 * max(dot(k1, p), 0.0) * k1;"
            + "  p -= 2.0 * max(dot(k2, p), 0.0) * k2;"
            + "  p.x = abs(p.x);"
            + "  p.y -= r;"
            + "  float2 ba = 0.48 * float2(-k1.y, k1.x) - float2(0.0, 1.0);"
            + "  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);"
            + "  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y) - rr;"
            + "}"
            // Inigo Quilez's heart (y up, base at the origin).
            + "float sdHeart(float2 p) {"
            + "  p.x = abs(p.x);"
            + "  if (p.y + p.x > 1.0) return sqrt(dot2(p - float2(0.25, 0.75))) - 0.35355339;"
            + "  return sqrt(min(dot2(p - float2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0))))"
            + "      * sign(p.x - p.y);"
            + "}"
            // The drop: a circle flowing into the mark, its edge rippling (q in units of R, y down).
            + "float shape(float2 q) {"
            + "  float a = atan(q.y, q.x);"
            + "  float mark = heart > 0.5"
            + "      ? sdHeart(float2(q.x, -q.y) * 0.8 + float2(0.0, 0.5)) / 0.8"
            + "      : sdStar(float2(q.x, -q.y));"
            + "  float d = mix(length(q) - 0.7, mark, morph);"
            + "  return d + wobble * 0.025 * (sin(3.0 * a + time * 7.0) + 0.6 * sin(5.0 * a - time * 5.3));"
            + "}"
            // A ripple ring: rp = (radius, amplitude px, width px, unused).
            + "float2 ripple(float2 p, float4 rp) {"
            + "  if (rp.y <= 0.01) return float2(0.0);"
            + "  float2 v = p - origin;"
            + "  float r = length(v);"
            + "  if (r < 0.001) return float2(0.0);"
            + "  float x = (r - rp.x) / rp.z;"
            + "  return (v / r) * rp.y * exp(-x * x) * sin(x * 2.4);"
            + "}"

            + "half4 main(float2 p) {"
            + "  float2 disp = ripple(p, ripA) + ripple(p, ripB);"
            // Dispersion: each channel bends by a different amount.
            + "  half3 col = half3(at(p + disp * 1.2).r, at(p + disp).g,"
            + "      at(p + disp * 0.8).b);"
            + "  half alpha = at(p).a;"
            + "  if (glass > 0.001) {"
            + "    float2 d = p - center;"
            + "    float c = cos(-angle);"
            + "    float s = sin(-angle);"
            + "    float2 q = float2(c * d.x - s * d.y, s * d.x + c * d.y)"
            + "        / float2(R * scale * sx, R * scale * sy);"
            + "    if (abs(q.x) < 1.7 && abs(q.y) < 1.7) {"
            + "      float sd = shape(q);"
            + "      float e = 0.01;"
            + "      float2 n = float2(shape(q + float2(e, 0.0)) - shape(q - float2(e, 0.0)),"
            + "                        shape(q + float2(0.0, e)) - shape(q - float2(0.0, e)));"
            + "      n = n / max(length(n), 0.00001);"
            + "      float2 ns = float2(c * n.x + s * n.y, -s * n.x + c * n.y);"
            + "      float inside = smoothstep(0.02, -0.02, sd);"
            + "      float depth = clamp(-sd / 0.2, 0.0, 1.0);"
            + "      float edgeK = 1.0 - depth;"
            // Refraction: pulled in hard along the rim, magnified through the middle.
            + "      float2 sp = p + (-ns * R * scale * 0.22 * edgeK * edgeK + (center - p) * 0.12) * glass;"
            + "      float ca = R * scale * 0.035 * edgeK * glass;"
            + "      half3 g = half3(at(sp + ns * ca).r, at(sp).g, at(sp - ns * ca).b);"
            + "      float f = R * 0.06;"
            + "      half3 frost = (at(sp + float2(f, 0.0)).rgb + at(sp - float2(f, 0.0)).rgb"
            + "          + at(sp + float2(0.0, f)).rgb + at(sp - float2(0.0, f)).rgb) * 0.25;"
            + "      g = mix(g, frost, 0.15);"
            + "      g = mix(g, half3(1.0), 0.16);"
            + "      g = mix(g, tint, 0.08);"
            // Glass on a dark backdrop only reads as glass where it catches light: a fresnel sheen
            // brightening toward the rim.
            + "      g += half3(0.20 * edgeK * edgeK);"
            // Light from the top left: highlight on the lit rim, shade on the far one.
            + "      float2 L = normalize(float2(-0.45, -0.9));"
            + "      float spec = pow(clamp(dot(ns, L), 0.0, 1.0), 16.0) * edgeK * edgeK * edgeK;"
            + "      float shade = clamp(dot(ns, -L), 0.0, 1.0) * edgeK * edgeK;"
            + "      g += half3(spec * 1.3);"
            + "      g *= half(1.0 - 0.10 * shade);"
            // A band of light sweeping through the body.
            + "      float z = (q.x + q.y * 0.6 - (time * 1.5 - 1.3)) * 3.0;"
            + "      g += half3(0.2 * exp(-z * z) * depth);"
            // A rainbow fringe running round the rim.
            + "      float hue = atan(q.y, q.x) / 6.2831853 + time * 0.15;"
            + "      half3 iri = half3(0.5 + 0.5 * cos(6.2831853 * (hue + float3(0.0, 0.33, 0.67))));"
            + "      g += iri * half(0.12 * edgeK * edgeK * edgeK * edgeK);"
            + "      col = mix(col, clamp(g, 0.0, 1.0), half(inside * glass));"
            // A hairline of light along the outline - cut crystal, not a glowing sticker.
            + "      col += half3(0.5 * glass * exp(-abs(sd) * 45.0));"
            + "    }"
            + "  }"
            + "  return half4(clamp(col, 0.0, 1.0), alpha);"
            + "}";

    private final Path mark = new Path();
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean big;
    private final boolean star;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private final int accent;
    private Object liquid;          // RuntimeShader, API 33+
    private ViewGroup host;
    private float t;

    /**
     * @param star  a star instead of a heart
     * @param big   the double-tap form; false (around the like button) shows nothing
     * @param x     centre, in the parent's coordinates
     * @param size  the mark's nominal size in px
     */
    public LikeBurstCrystal(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.star = star;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = star ? Color.rgb(255, 204, 64) : Color.rgb(255, 90, 120);
        Paint rounder = new Paint();
        rounder.setStyle(Paint.Style.FILL);
        rounder.setPathEffect(new CornerPathEffect(star ? 2.4f : 1.4f));
        rounder.getFillPath(ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART), mark);

        if (big && Build.VERSION.SDK_INT >= 33) {
            try {
                android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(LIQUID_AGSL);
                shader.setFloatUniform("tint", Color.red(accent) / 255f,
                        Color.green(accent) / 255f, Color.blue(accent) / 255f);
                shader.setFloatUniform("heart", star ? 0f : 1f);
                shader.setFloatUniform("origin", x, y);
                liquid = shader;
            } catch (Throwable t) {
                // AGSL compiles on the device: a shader error shows up here, not at build time.
                android.util.Log.w("SpicyLikeBurst", "liquid shader unavailable: " + t);
                liquid = null;
            }
        }
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the burst to {@code parent} and plays it; it removes itself at the end. */
    public void play(ViewGroup parent) {
        if (!big) return;
        // One at a time per parent: both would drive the same RenderEffect, and the first to end
        // would clear it from under the second.
        LikeBurstCrystal previous = PLAYING.get(parent);
        if (previous != null) previous.finish();
        PLAYING.put(parent, this);
        host = parent;
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(DURATION_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            applyLiquid();
            if (liquid == null) invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                finish();
            }
        });
        this.clock = clock;
        clock.start();
    }

    private static final java.util.WeakHashMap<ViewGroup, LikeBurstCrystal> PLAYING =
            new java.util.WeakHashMap<>();
    private ValueAnimator clock;

    private void finish() {
        if (clock != null) {
            ValueAnimator c = clock;
            clock = null;
            c.removeAllListeners();
            c.removeAllUpdateListeners();
            c.cancel();
        }
        ViewGroup h = host;
        if (h != null && PLAYING.get(h) == this) PLAYING.remove(h);
        clearLiquid();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        clearLiquid();
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    // -- motion ------------------------------------------------------------------------------

    private float exit() {
        return smooth(phase(t, 0.72f, 1f));
    }

    /** Pops in on a lively spring (a jelly overshoot), shrinks a little as it melts. */
    private float scale() {
        return spring(phase(t, 0.03f, 0.7f), 0.7, 10.0) * (1f - 0.3f * exit());
    }

    /** Circle (0) to mark (1), slightly past it on the way, back toward a drop as it melts. */
    private float morph() {
        return spring(phase(t, 0.06f, 0.6f), 0.8, 10.0) * (1f - 0.6f * exit());
    }

    /** The liquid edge: restless as the drop forms, calm while held, restless as it melts. */
    private float wobble() {
        return 1.0f * (1f - smooth(phase(t, 0f, 0.5f))) + 0.15f + 0.7f * exit();
    }

    /** A broad, unhurried turn into place, a little further as it leaves. */
    private float angleDegrees() {
        float settle = star ? -12f : -6f;
        return settle * (1f - spring(phase(t, 0f, 0.85f), 0.85, 6.0)) + 5f * exit();
    }

    private float presence() {
        return smooth(phase(t, 0.02f, 0.14f)) * (1f - exit());
    }

    private float rise() {
        float e = exit();
        return density * 42f * e * e;
    }

    /** A ripple ring (radius, amplitude, width, -): runs out over [start, end], fading. */
    private float[] ripple(float start, float end, float reach, float ampDp) {
        float p = phase(t, start, end);
        if (p <= 0f || p >= 1f) return new float[]{0f, 0f, 1f, 0f};
        float fade = (1f - p);
        return new float[]{
                reach * decelerate(p),
                density * ampDp * fade * (float) Math.sqrt(fade) * Math.min(1f, p * 8f),
                density * (26f + 70f * p),
                0f};
    }

    // -- drawing -----------------------------------------------------------------------------

    private void applyLiquid() {
        if (Build.VERSION.SDK_INT < 33 || liquid == null || host == null) return;
        android.graphics.RuntimeShader shader = (android.graphics.RuntimeShader) liquid;
        float reach = 0.62f * Math.max(host.getWidth(), host.getHeight());
        float[] a = t < 0.74f ? ripple(0f, 0.74f, reach, 18f)
                : ripple(0.74f, 1f, reach * 0.35f, 8f);   // the melt's own small ripple
        float[] b = ripple(0.1f, 0.86f, reach * 0.85f, 9f);
        shader.setFloatUniform("bounds", host.getWidth(), host.getHeight());
        shader.setFloatUniform("center", cx, cy - rise());
        shader.setFloatUniform("R", radius);
        shader.setFloatUniform("scale", Math.max(0.001f, scale()));
        shader.setFloatUniform("sx", 1f);
        shader.setFloatUniform("sy", 1f);
        shader.setFloatUniform("angle", (float) Math.toRadians(angleDegrees()));
        shader.setFloatUniform("morph", morph());
        shader.setFloatUniform("wobble", wobble());
        shader.setFloatUniform("glass", presence());
        shader.setFloatUniform("time", t * DURATION_MS / 1000f);
        shader.setFloatUniform("ripA", a[0], a[1], a[2], a[3]);
        shader.setFloatUniform("ripB", b[0], b[1], b[2], b[3]);
        host.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
    }

    private void clearLiquid() {
        if (Build.VERSION.SDK_INT >= 31 && host != null) host.setRenderEffect(null);
        host = null;
    }

    /** Only without the shader: a frosted mark on the same motion. */
    @Override protected void onDraw(Canvas canvas) {
        if (liquid != null) return;
        float presence = presence();
        if (presence <= 0.003f) return;
        float size = radius * 2f * Math.max(0f, scale());
        canvas.save();
        canvas.translate(cx, cy - rise());
        canvas.rotate(angleDegrees());
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);
        fallbackPaint.setColor(blend(Color.WHITE, accent, 0.3f));
        fallbackPaint.setAlpha(Math.round(220 * presence));
        canvas.drawPath(mark, fallbackPaint);
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

    /** A spring from 0 to 1 over p in [0, 1]; damping/omega set its bounce and pace. */
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
