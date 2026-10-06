// Double-tap like style "Pulse" (Settings.DOUBLE_TAP_LIKE_EFFECT): the Glow mark with a
// heartbeat. See LikeBursts for the list.
package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like acknowledgement as a mark made of light that beats twice like a heart, with the screen answering in layers rather
 * than all being bent alike:
 *
 * <ul>
 *   <li>The mark: a star (or heart) that gives light - white at its heart, the Liked Songs colour
 *       toward its edge, a fine line of light on its outline and two soft halos that follow its
 *       shape - drawn additively, so it brightens whatever is under it. It comes on softly,
 *       settling into place on a spring with almost no bounce and a small turn; one sheen slides
 *       across it; then it swells a touch and its light fades out.</li>
 *   <li>The backdrop (behind the lyrics): bends gently round the mark, like space round a mass,
 *       while the colour spreads out through it as light.</li>
 *   <li>The lyrics: never warped - text bent out of shape reads as a glitch. They catch the
 *       light instead: the lines near the mark brighten with it and settle as it fades.</li>
 * </ul>
 *
 * <p>Particles, light streaks, ripples and glassy 3D bodies were all tried and read as busy or
 * toy-like; this keeps only light and a calm motion.
 *
 * <p>Everything is AGSL (API 33+): the mark is a shader drawn by this view, the backdrop and the
 * lyrics get RenderEffects of their own. Below API 33 a soft glow plays alone. The small form
 * (around the like button, which animates itself) is intentionally nothing.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself (and
 * clears the effects it set) when done.
 */
public final class LikeBurstPulse extends View implements LikeBurst {
    private static final long DURATION_MS = 1500L;

    private static final String SHAPES = ""
            + "float dot2(float2 v) { return dot(v, v); }"
            // Inigo Quilez's five-pointed star (y up), corners rounded a little.
            + "float sdStar(float2 p) {"
            + "  float2 k1 = float2(0.809016994375, -0.587785252292);"
            + "  float2 k2 = float2(-k1.x, k1.y);"
            + "  float rr = 0.06;"
            + "  float r = 0.95 - rr;"
            + "  p.x = abs(p.x);"
            + "  p -= 2.0 * max(dot(k1, p), 0.0) * k1;"
            + "  p -= 2.0 * max(dot(k2, p), 0.0) * k2;"
            + "  p.x = abs(p.x);"
            + "  p.y -= r;"
            + "  float2 ba = 0.42 * float2(-k1.y, k1.x) - float2(0.0, 1.0);"
            + "  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);"
            + "  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y) - rr;"
            + "}"
            // Inigo Quilez's heart (y up, base at the origin).
            + "float sdHeart(float2 p) {"
            + "  p.x = abs(p.x);"
            + "  if (p.y + p.x > 1.0) return sqrt(dot2(p - float2(0.25, 0.75))) - 0.35355339;"
            + "  return sqrt(min(dot2(p - float2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0))))"
            + "      * sign(p.x - p.y);"
            + "}";

    /** The mark, drawn by this view with an additive paint: q is in units of R, y down. */
    private static final String MARK_AGSL = ""
            + "uniform float2 center;"
            + "uniform float R;"
            + "uniform float scale;"
            + "uniform float angle;"
            + "uniform float glow;"
            + "uniform float sheenPos;"
            + "uniform float heart;"
            + "uniform half3 tint;"
            + SHAPES
            + "float shapeAt(float2 q) {"
            + "  return heart > 0.5"
            + "      ? sdHeart(float2(q.x, -q.y) * 0.8 + float2(0.0, 0.5)) / 0.8"
            + "      : sdStar(float2(q.x, -q.y));"
            + "}"
            + "half4 main(float2 p) {"
            + "  if (glow <= 0.001) return half4(0.0);"
            + "  float2 d = (p - center) / (R * scale);"
            + "  float c = cos(-angle);"
            + "  float s = sin(-angle);"
            + "  float2 q = float2(c * d.x - s * d.y, s * d.x + c * d.y);"
            + "  float sd = shapeAt(q);"
            + "  float inside = smoothstep(0.015, -0.015, sd);"
            + "  float depth = clamp(-sd / 0.45, 0.0, 1.0);"
            + "  float core = exp(-dot(q, q) * 1.8);"
            + "  float3 white = float3(1.0);"
            + "  float3 tintC = float3(tint);"
            // The body gives light: white at the heart, the like colour toward the edge.
            + "  float3 body = mix(tintC, white, 0.25 + 0.6 * core) * (0.55 + 0.35 * depth);"
            // A fine line of light on the outline, and two halos that follow the shape.
            + "  float rim = exp(-abs(sd) * 22.0);"
            + "  float halo = exp(-max(sd, 0.0) * 4.0);"
            + "  float halo2 = exp(-max(sd, 0.0) * 1.4);"
            // One sheen passing over it, as light slides across.
            + "  float z = (q.x * 0.8 - q.y * 0.6 - sheenPos) * 4.0;"
            + "  float sheen = exp(-z * z) * inside;"
            + "  float3 col = body * inside + white * rim * 0.45"
            + "      + mix(tintC, white, 0.35) * halo * 0.35 + tintC * halo2 * 0.12"
            + "      + white * sheen * 0.35;"
            + "  col = clamp(col * glow, 0.0, 1.0);"
            + "  return half4(half3(col), half(max(col.r, max(col.g, col.b))));"
            + "}";

    /** The backdrop: a gravitational lens round the mark and the colour spreading as light. */
    private static final String BACKDROP_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float2 bounds;"
            + "uniform float lensR;"
            + "uniform float lens;"
            + "uniform float swirl;"
            + "uniform float washR;"
            + "uniform float wash;"
            + "uniform half3 tint;"
            + "half4 at(float2 q) { return content.eval(clamp(q, float2(0.5), bounds - 0.5)); }"
            + "half4 main(float2 p) {"
            + "  float2 v = p - center;"
            + "  float r2 = dot(v, v);"
            + "  float2 disp = float2(0.0);"
            + "  if (lens > 0.001 || abs(swirl) > 0.001) {"
            + "    float e2 = lensR * lensR;"
            + "    float soft = lensR * 0.75;"
            + "    float fall = exp(-r2 / (e2 * 18.0));"
            + "    float k = e2 / (r2 + soft * soft);"
            + "    float w = swirl * k * fall;"
            + "    float cw = cos(w);"
            + "    float sw = sin(w);"
            + "    float2 tw = float2(cw * v.x - sw * v.y, sw * v.x + cw * v.y);"
            + "    disp = (tw - v) - tw * (lens * k) * fall;"
            + "  }"
            + "  half4 c = at(p + disp);"
            + "  c.r = at(p + disp * 1.04).r;"
            + "  c.b = at(p + disp * 0.96).b;"
            + "  float x = sqrt(r2) / max(washR, 1.0);"
            + "  float spread = exp(-x * x) * wash;"
            + "  c.rgb = c.rgb + tint * half(spread * 0.4) + half3(spread * 0.08);"
            + "  return c;"
            + "}";

    /** The lyrics: never bent - lit by the mark where they are near it, nudged a touch aside. */
    private static final String LYRICS_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float2 bounds;"
            + "uniform float reach;"
            + "uniform float light;"
            + "uniform float push;"
            + "uniform half3 tint;"
            + "half4 main(float2 p) {"
            + "  float2 v = p - center;"
            + "  float r = length(v);"
            + "  float2 dir = r > 0.001 ? v / r : float2(0.0);"
            + "  float x = r / reach;"
            + "  float near = exp(-x * x);"
            + "  half4 c = content.eval(clamp(p - dir * push * near, float2(0.5), bounds - 0.5));"
            + "  half3 lit = mix(tint, half3(1.0), 0.6);"
            // Premultiplied: adding light scaled by alpha lights the glyphs and nothing between.
            + "  c.rgb = min(c.rgb + lit * c.a * half(light * near * 0.75), half3(c.a));"
            + "  return c;"
            + "}";

    private final Paint markPaint = new Paint();
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean big;
    private final boolean star;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private final int accent;
    private Object markShader;      // RuntimeShader, API 33+
    private Object backdropShader;
    private Object lyricsShader;
    private View backdropView;
    private View lyricsView;
    private final float[] backdropOffset = new float[2];
    private final float[] lyricsOffset = new float[2];
    private ValueAnimator clock;
    private float t;

    private static final java.util.WeakHashMap<ViewGroup, LikeBurstPulse> PLAYING =
            new java.util.WeakHashMap<>();
    private ViewGroup host;

    /**
     * @param star  a star instead of a heart
     * @param big   the double-tap form; false (around the like button) shows nothing
     * @param x     centre, in the parent's coordinates
     * @param size  the mark's nominal size in px
     */
    public LikeBurstPulse(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.star = star;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = star ? Color.rgb(255, 206, 84) : Color.rgb(255, 96, 128);
        float tr = Color.red(accent) / 255f;
        float tg = Color.green(accent) / 255f;
        float tb = Color.blue(accent) / 255f;
        if (big && Build.VERSION.SDK_INT >= 33) {
            try {
                android.graphics.RuntimeShader mark = new android.graphics.RuntimeShader(MARK_AGSL);
                mark.setFloatUniform("tint", tr, tg, tb);
                mark.setFloatUniform("heart", star ? 0f : 1f);
                mark.setFloatUniform("center", x, y);
                mark.setFloatUniform("R", radius);
                markShader = mark;
                markPaint.setShader(mark);
                markPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
                android.graphics.RuntimeShader backdrop = new android.graphics.RuntimeShader(BACKDROP_AGSL);
                backdrop.setFloatUniform("tint", tr, tg, tb);
                backdropShader = backdrop;
                android.graphics.RuntimeShader lyrics = new android.graphics.RuntimeShader(LYRICS_AGSL);
                lyrics.setFloatUniform("tint", tr, tg, tb);
                lyricsShader = lyrics;
            } catch (Throwable t) {
                // AGSL compiles on the device: a shader error shows up here, not at build time.
                android.util.Log.w("SpicyLikeBurst", "shaders unavailable: " + t);
                markShader = null;
                backdropShader = null;
                lyricsShader = null;
            }
        }
        fallbackPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        fallbackPaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{0xCCFFFFFF, (accent & 0x00FFFFFF) | 0x66000000, accent & 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Plays over {@code parent} alone (no backdrop or lyrics response). */
    public void play(ViewGroup parent) {
        play(parent, null, null);
    }

    /**
     * Adds the burst to {@code parent} and plays it; it removes itself at the end.
     *
     * @param backdrop the view behind the lyrics that bends and takes the colour, or null
     * @param lyrics   the view holding the lyrics, lit by the mark, or null
     */
    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        if (!big) return;
        // One at a time per parent: two would drive the same effects, and the first to end would
        // clear them from under the second.
        LikeBurstPulse previous = PLAYING.get(parent);
        if (previous != null) previous.finish();
        PLAYING.put(parent, this);
        host = parent;
        backdropView = backdrop;
        lyricsView = lyrics;
        offsetOf(parent, backdrop, backdropOffset);
        offsetOf(parent, lyrics, lyricsOffset);
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator c = ValueAnimator.ofFloat(0f, 1f);
        c.setDuration(DURATION_MS);
        c.setInterpolator(new LinearInterpolator());
        c.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            applyEffects();
            invalidate();
        });
        c.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                finish();
            }
        });
        clock = c;
        c.start();
    }

    /** Where {@code view}'s origin sits in {@code parent}'s coordinates. */
    private static void offsetOf(View parent, View view, float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        if (view == null) return;
        int[] a = new int[2];
        int[] b = new int[2];
        parent.getLocationInWindow(a);
        view.getLocationInWindow(b);
        out[0] = b[0] - a[0];
        out[1] = b[1] - a[1];
    }

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
        clearEffects();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        clearEffects();
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    // -- motion ------------------------------------------------------------------------------

    /** The light comes on quickly and softly. */
    private float ignite() {
        return smooth(phase(t, 0f, 0.18f));
    }

    private float exit() {
        return smooth(phase(t, 0.64f, 1f));
    }

    /** Two beats, lub-dub: quick swells of the mark, the second smaller. 0 between them. */
    private float beats() {
        return bump(0.2f, 0.035f) + 0.7f * bump(0.33f, 0.035f);
    }

    private float bump(float at, float width) {
        float x = (t - at) / width;
        return (float) Math.exp(-x * x);
    }

    /** Light of the mark: comes on, flares with each beat, fades as it leaves. */
    private float glow() {
        return ignite() * (1f + 0.45f * beats()) * (1f - exit());
    }

    /** Settles into place, swelling with each beat, a touch larger as it leaves. */
    private float scale() {
        return 0.72f + 0.28f * spring(phase(t, 0f, 0.4f), 0.8, 11.0) + 0.16f * beats()
                + 0.1f * exit();
    }

    /** A small, unhurried turn into place. */
    private float angleRadians() {
        return (star ? -0.16f : -0.06f) * (1f - smooth(phase(t, 0f, 0.55f)));
    }

    /** Where the passing sheen is, across the mark (in units of its radius). */
    private float sheenPos() {
        return -1.8f + 3.6f * smooth(phase(t, 0.16f, 0.58f));
    }

    /** The backdrop and lyrics beat with the mark. */
    private float pulse() {
        return beats();
    }

    /** A gentle twist of the backdrop round the mark, springing back. */
    private float swirl() {
        float p = phase(t, 0.02f, 0.9f);
        float wind = p <= 0f || p >= 1f ? 0f
                : (float) (0.9 * Math.exp(-4.5 * p) * Math.cos(p * 8.0)) * smooth(phase(t, 0.02f, 0.12f));
        return (star ? 1f : -1f) * (wind + 0.25f * exit());
    }

    /** A spring from 0 to 1 over p in [0, 1]; damping/omega set its bounce and pace. */
    private static float spring(float p, double damping, double omega) {
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    // -- drawing -----------------------------------------------------------------------------

    private void applyEffects() {
        if (Build.VERSION.SDK_INT < 33) return;
        float lit = Math.min(1f, ignite()) * (1f - exit());
        float pulse = pulse();
        if (backdropView != null && backdropShader != null) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) backdropShader;
            s.setFloatUniform("center", cx - backdropOffset[0], cy - backdropOffset[1]);
            s.setFloatUniform("bounds", backdropView.getWidth(), backdropView.getHeight());
            s.setFloatUniform("lensR", radius * (1.5f + 0.45f * pulse));
            s.setFloatUniform("lens", lit * (0.5f + 0.25f * pulse));
            s.setFloatUniform("swirl", swirl());
            // The colour spreads out from the mark as light, wide and soft, then fades.
            float spread = phase(t, 0.04f, 1f);
            s.setFloatUniform("washR", radius * (1.2f + 6f * (1f - (1f - spread) * (1f - spread))));
            s.setFloatUniform("wash", lit * (1f - 0.6f * spread) * (1f + 0.8f * beats()));
            backdropView.setRenderEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"));
        }
        if (lyricsView != null && lyricsShader != null) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) lyricsShader;
            s.setFloatUniform("center", cx - lyricsOffset[0], cy - lyricsOffset[1]);
            s.setFloatUniform("bounds", lyricsView.getWidth(), lyricsView.getHeight());
            s.setFloatUniform("reach", radius * 3.6f);
            s.setFloatUniform("light", lit * (1f + 0.5f * Math.max(0f, pulse)));
            s.setFloatUniform("push", density * 5f * Math.max(0f, pulse) * lit);
            lyricsView.setRenderEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"));
        }
    }

    private void clearEffects() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (backdropView != null) backdropView.setRenderEffect(null);
        if (lyricsView != null) lyricsView.setRenderEffect(null);
        backdropView = null;
        lyricsView = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (markShader != null && Build.VERSION.SDK_INT >= 33) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) markShader;
            s.setFloatUniform("glow", glow());
            s.setFloatUniform("scale", scale());
            s.setFloatUniform("sheenPos", sheenPos());
            s.setFloatUniform("angle", angleRadians());
            float reach = radius * 5f;
            canvas.drawRect(cx - reach, cy - reach, cx + reach, cy + reach, markPaint);
            return;
        }
        // No shaders: a soft glow on the same timing.
        float g = glow();
        if (g <= 0.003f) return;
        float r = radius * (1.2f + 0.6f * exit());
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        fallbackPaint.setAlpha(Math.round(255 * Math.min(1f, g)));
        canvas.drawCircle(0f, 0f, 1f, fallbackPaint);
        canvas.restore();
    }

    private static float phase(float t, float start, float end) {
        if (t <= start) return 0f;
        if (t >= end) return 1f;
        return (t - start) / (end - start);
    }

    private static float smooth(float p) {
        return p * p * (3f - 2f * p);
    }
}
