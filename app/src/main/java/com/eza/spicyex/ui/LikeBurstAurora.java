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
 * The like acknowledgement in the language of an ambient overlay (think Gemini's) rather than
 * drawn-on ornaments - rays, rings, glints and particles all read as tacky:
 *
 * <ul>
 *   <li>The screen's edges light up: three neighbouring hues of the Liked Songs colour flow
 *       round the frame, with a thin line of sparkling halftone dots along it.</li>
 *   <li>The mark: a star (or heart) filled with those same sparkling dots, inside a soft glow
 *       that follows its shape and a fine outline. It springs in with a turn, holds, then lifts
 *       away shrinking as its light fades. A faint wave of sparkles runs out from it and dies
 *       away nearby.</li>
 *   <li>The backdrop (behind the lyrics) undulates gently, bends a little round the mark and
 *       takes its colour; the lyrics sway far more gently, without colour fringes, and catch
 *       the mark's light where they are near it.</li>
 * </ul>
 *
 * <p>Everything is AGSL (API 33+): the overlay is a shader drawn by this view over the whole
 * screen, the backdrop and the lyrics get RenderEffects of their own. Below API 33 a soft glow
 * plays alone. The small form (around the like button, which animates itself) is intentionally
 * nothing.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself (and
 * clears the effects it set) when done.
 */
public final class LikeBurstAurora extends View implements LikeBurst {
    private static final long DURATION_MS = 1600L;

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

    /** Waves of displacement running out from the mark: radial plus a slower sideways sway. */
    private static final String WAVES = ""
            + "float2 waves(float2 v, float amp, float time, float wl) {"
            + "  if (amp <= 0.001) return float2(0.0);"
            + "  float r = length(v);"
            + "  if (r < 0.001) return float2(0.0);"
            + "  float2 dir = v / r;"
            + "  float2 side = float2(-dir.y, dir.x);"
            + "  float front = time * wl * 2.4;"
            // Strongest just behind the outgoing front, quiet ahead of it and far behind it.
            + "  float env = exp(-pow(max(r - front, 0.0) / (wl * 0.9), 2.0))"
            + "      * exp(-max(front - r, 0.0) / (wl * 3.0));"
            + "  float ph = r / wl * 6.2831853 - time * 9.0;"
            + "  float a = atan(v.y, v.x);"
            + "  return (dir * sin(ph) + side * 0.45 * sin(ph * 0.6 + a * 3.0 + time * 4.0)) * amp * env;"
            + "}";

    /**
     * Drawn by this view over the whole screen with an additive paint, in the visual language
     * of Gemini's own overlay rather than drawn-on ornaments: light flowing along the screen's
     * edges in three neighbouring hues, a fine halftone field of dots that sparkle one by one -
     * spreading out from the finger and along the lit edges - and the mark itself made of those
     * same sparkling dots inside a soft glow and a fine outline. q is in units of R, y down.
     */
    private static final String MARK_AGSL = ""
            + "uniform float2 center;"
            + "uniform float2 size;"
            + "uniform float R;"
            + "uniform float scale;"
            + "uniform float angle;"
            + "uniform float glow;"
            + "uniform float edgeA;"
            + "uniform float ringR;"
            + "uniform float ringA;"
            + "uniform float time;"
            + "uniform float cell;"
            + "uniform float heart;"
            + "uniform half3 c1;"
            + "uniform half3 c2;"
            + "uniform half3 c3;"
            + SHAPES
            + "float shapeAt(float2 q) {"
            + "  return heart > 0.5"
            + "      ? sdHeart(float2(q.x, -q.y) * 0.8 + float2(0.0, 0.5)) / 0.8"
            + "      : sdStar(float2(q.x, -q.y));"
            + "}"
            + "float2 turn(float2 v, float a) {"
            + "  float c = cos(a);"
            + "  float s = sin(a);"
            + "  return float2(c * v.x - s * v.y, s * v.x + c * v.y);"
            + "}"
            + "float hash(float2 v) { return fract(sin(dot(v, float2(127.1, 311.7))) * 43758.5453); }"
            // Three hues blended round a loop.
            + "float3 palette(float s) {"
            + "  s = fract(s);"
            + "  float3 a = float3(c1);"
            + "  float3 b = float3(c2);"
            + "  float3 c = float3(c3);"
            + "  if (s < 0.33333) return mix(a, b, s * 3.0);"
            + "  if (s < 0.66667) return mix(b, c, (s - 0.33333) * 3.0);"
            + "  return mix(c, a, (s - 0.66667) * 3.0);"
            + "}"
            + "half4 main(float2 p) {"
            + "  float3 col = float3(0.0);"
            + "  float2 mid = size * 0.5;"
            + "  float shortSide = min(size.x, size.y);"
            + "  float e = min(min(p.x, p.y), min(size.x - p.x, size.y - p.y));"
            + "  float s = atan(p.y - mid.y, p.x - mid.x) / 6.2831853;"
            // Light flowing along the edges, its hues travelling round the screen.
            + "  if (edgeA > 0.001) {"
            + "    float band = exp(-e / (shortSide * 0.12));"
            + "    float flow = 0.6 + 0.4 * sin(s * 12.566 - time * 2.6);"
            + "    col += palette(s + time * 0.16) * band * flow * edgeA * 0.75;"
            + "  }"
            // The halftone field: one dot per cell, sized by how lit its cell is, each twinkling.
            + "  float2 id = floor(p / cell);"
            + "  float2 cc = (id + 0.5) * cell;"
            + "  float h = hash(id);"
            // The sparkle wave fades with distance from the finger: a ripple near it, not a
            // screen door drawn over everything.
            + "  float dist = length(cc - center);"
            + "  float x = (dist - ringR) / (cell * 5.0);"
            + "  float ring = exp(-x * x) * ringA * 0.6 * exp(-dist / (shortSide * 0.55));"
            + "  float edgeDots = exp(-e / (shortSide * 0.03)) * edgeA * 0.75;"
            + "  float2 qc = turn((cc - center) / (R * scale), -angle);"
            + "  float inMark = smoothstep(0.06, -0.06, shapeAt(qc)) * glow;"
            + "  float f = max(max(ring, edgeDots), inMark);"
            + "  if (f > 0.04) {"
            + "    float tw = 0.5 + 0.5 * sin(time * 7.5 + h * 6.2831853);"
            + "    float rad = cell * 0.34 * f * (0.3 + 0.7 * tw);"
            + "    float spot = smoothstep(rad, rad - 1.2, length(p - cc));"
            + "    float3 dc = mix(palette(h * 0.35 + s + time * 0.16), float3(1.0), 0.3 + 0.45 * inMark);"
            + "    col += dc * spot;"
            + "  }"
            // The mark's own light: a soft glow following its shape and a fine outline.
            + "  if (glow > 0.001) {"
            + "    float2 q = turn((p - center) / (R * scale), -angle);"
            + "    float sd = shapeAt(q);"
            + "    float halo = exp(-max(sd, 0.0) * 3.0);"
            + "    float rim = exp(-abs(sd) * 22.0);"
            + "    float core = exp(-dot(q, q) * 2.0) * smoothstep(0.02, -0.02, sd);"
            + "    col += (float3(c1) * halo * 0.32 + float3(1.0) * rim * 0.4"
            + "        + mix(float3(c1), float3(1.0), 0.6) * core * 0.3) * glow;"
            + "  }"
            + "  col = clamp(col, 0.0, 1.0);"
            + "  return half4(half3(col), half(max(col.r, max(col.g, col.b))));"
            + "}";

    /** The backdrop: undulating, a gentle lens round the mark, the colour spreading as light. */
    private static final String BACKDROP_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float2 bounds;"
            + "uniform float lensR;"
            + "uniform float lens;"
            + "uniform float swirl;"
            + "uniform float washR;"
            + "uniform float wash;"
            + "uniform float waveAmp;"
            + "uniform float waveLen;"
            + "uniform float time;"
            + "uniform half3 tint;"
            + WAVES
            + "half4 at(float2 q) { return content.eval(clamp(q, float2(0.5), bounds - 0.5)); }"
            + "half4 main(float2 p) {"
            + "  float2 v = p - center;"
            + "  float r2 = dot(v, v);"
            + "  float2 disp = waves(v, waveAmp, time, waveLen);"
            + "  if (lens > 0.001 || abs(swirl) > 0.001) {"
            + "    float e2 = lensR * lensR;"
            + "    float soft = lensR * 0.75;"
            + "    float fall = exp(-r2 / (e2 * 18.0));"
            + "    float k = e2 / (r2 + soft * soft);"
            + "    float w = swirl * k * fall;"
            + "    float cw = cos(w);"
            + "    float sw = sin(w);"
            + "    float2 tw = float2(cw * v.x - sw * v.y, sw * v.x + cw * v.y);"
            + "    disp += (tw - v) - tw * (lens * k) * fall;"
            + "  }"
            + "  half4 c = at(p + disp);"
            + "  c.r = at(p + disp * 1.05).r;"
            + "  c.b = at(p + disp * 0.95).b;"
            + "  float x = sqrt(r2) / max(washR, 1.0);"
            + "  float spread = exp(-x * x) * wash;"
            + "  c.rgb = c.rgb + tint * half(spread * 0.45) + half3(spread * 0.08);"
            + "  return c;"
            + "}";

    /** The lyrics: sway with the waves (gently, no fringes) and catch the mark's light. */
    private static final String LYRICS_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float2 bounds;"
            + "uniform float reach;"
            + "uniform float light;"
            + "uniform float waveAmp;"
            + "uniform float waveLen;"
            + "uniform float time;"
            + "uniform half3 tint;"
            + WAVES
            + "half4 main(float2 p) {"
            + "  float2 v = p - center;"
            + "  float x = length(v) / reach;"
            + "  float near = exp(-x * x);"
            + "  half4 c = content.eval(clamp(p + waves(v, waveAmp, time, waveLen),"
            + "      float2(0.5), bounds - 0.5));"
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

    private static final java.util.WeakHashMap<ViewGroup, LikeBurstAurora> PLAYING =
            new java.util.WeakHashMap<>();
    private ViewGroup host;

    /**
     * @param star  a star instead of a heart
     * @param big   the double-tap form; false (around the like button) shows nothing
     * @param x     centre, in the parent's coordinates
     * @param size  the mark's nominal size in px
     */
    public LikeBurstAurora(Context context, boolean star, boolean big, float x, float y, float size) {
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
                float[][] hues = star
                        ? new float[][]{{1f, 0.80f, 0.32f}, {1f, 0.50f, 0.26f}, {0.48f, 0.74f, 1f}}
                        : new float[][]{{1f, 0.42f, 0.58f}, {0.72f, 0.44f, 1f}, {1f, 0.66f, 0.40f}};
                mark.setFloatUniform("c1", hues[0]);
                mark.setFloatUniform("c2", hues[1]);
                mark.setFloatUniform("c3", hues[2]);
                mark.setFloatUniform("cell", 6f * context.getResources().getDisplayMetrics().density);
                mark.setFloatUniform("heart", star ? 0f : 1f);
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
     * @param backdrop the view behind the lyrics that undulates and takes the colour, or null
     * @param lyrics   the view holding the lyrics, swaying and lit by the mark, or null
     */
    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        if (!big) return;
        // One at a time per parent: two would drive the same effects, and the first to end would
        // clear them from under the second.
        LikeBurstAurora previous = PLAYING.get(parent);
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

    private float seconds() {
        return t * DURATION_MS / 1000f;
    }

    private float exit() {
        return smooth(phase(t, 0.66f, 1f));
    }

    /** Flashes on - a moment brighter than it settles - holds, fades as it leaves. */
    private float glow() {
        float on = smooth(phase(t, 0f, 0.1f));
        float flash = 0.45f * (float) Math.exp(-10.0 * Math.max(0f, t - 0.08f)) * phase(t, 0.02f, 0.08f);
        return (on + flash) * (1f - exit());
    }

    /** Pops in from small, springing past full size once; shrinks as it lifts away. */
    private float scale() {
        return (0.5f + 0.5f * spring(phase(t, 0f, 0.55f), 0.65, 11.0)) * (1f - 0.35f * exit());
    }

    /** Spins in and settles. */
    private float angleRadians() {
        float settle = (star ? -0.6f : -0.2f) * (1f - spring(phase(t, 0f, 0.6f), 0.8, 9.0));
        return settle + 0.15f * exit();
    }

    private float rise() {
        float e = exit();
        return density * 34f * e * e;
    }

    /** The edges' light: comes up softly, holds, fades. */
    private float edgeLight() {
        return smooth(phase(t, 0f, 0.16f)) * (1f - smooth(phase(t, 0.55f, 1f)));
    }

    /** The sparkling field's front, running out from the finger across the screen. */
    private float ringRadius(float reach) {
        float p = phase(t, 0f, 0.85f);
        return reach * (1f - (1f - p) * (1f - p));
    }

    private float ringAlpha() {
        float p = phase(t, 0f, 0.85f);
        if (p <= 0f || p >= 1f) return 0f;
        return smooth(Math.min(1f, p * 8f)) * (1f - p);
    }

    /** How strongly the screen undulates: swells with the flash, settles over the run. */
    private float waves() {
        return 0.6f * smooth(phase(t, 0f, 0.08f)) * (1f - smooth(phase(t, 0.35f, 1f)));
    }

    /** A gentle twist of the backdrop round the mark, springing back. */
    private float swirl() {
        float p = phase(t, 0.02f, 0.9f);
        float wind = p <= 0f || p >= 1f ? 0f
                : (float) (1.1 * Math.exp(-4.5 * p) * Math.cos(p * 8.0)) * smooth(phase(t, 0.02f, 0.12f));
        return (star ? 1f : -1f) * (wind + 0.3f * exit());
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
        float lit = smooth(phase(t, 0f, 0.12f)) * (1f - exit());
        float waves = waves();
        float wl = radius * 2.2f;
        float time = seconds();
        if (backdropView != null && backdropShader != null) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) backdropShader;
            s.setFloatUniform("center", cx - backdropOffset[0], cy - backdropOffset[1]);
            s.setFloatUniform("bounds", backdropView.getWidth(), backdropView.getHeight());
            s.setFloatUniform("lensR", radius * 1.5f * scale());
            s.setFloatUniform("lens", lit * 0.55f);
            s.setFloatUniform("swirl", swirl());
            s.setFloatUniform("waveAmp", density * 8f * waves);
            s.setFloatUniform("waveLen", wl);
            s.setFloatUniform("time", time);
            float spread = phase(t, 0.03f, 1f);
            s.setFloatUniform("washR", radius * (1.2f + 6f * (1f - (1f - spread) * (1f - spread))));
            s.setFloatUniform("wash", lit * (1f - 0.6f * spread));
            backdropView.setRenderEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"));
        }
        if (lyricsView != null && lyricsShader != null) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) lyricsShader;
            s.setFloatUniform("center", cx - lyricsOffset[0], cy - lyricsOffset[1]);
            s.setFloatUniform("bounds", lyricsView.getWidth(), lyricsView.getHeight());
            s.setFloatUniform("reach", radius * 3.6f);
            s.setFloatUniform("light", lit);
            s.setFloatUniform("waveAmp", density * 2.5f * waves);
            s.setFloatUniform("waveLen", wl);
            s.setFloatUniform("time", time);
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
        float y = cy - rise();
        if (markShader != null && Build.VERSION.SDK_INT >= 33) {
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) markShader;
            float w = getWidth();
            float h = getHeight();
            s.setFloatUniform("center", cx, y);
            s.setFloatUniform("size", w, h);
            s.setFloatUniform("glow", glow());
            s.setFloatUniform("scale", Math.max(0.05f, scale()));
            s.setFloatUniform("angle", angleRadians());
            s.setFloatUniform("edgeA", edgeLight());
            s.setFloatUniform("ringR", ringRadius((float) Math.hypot(w, h)));
            s.setFloatUniform("ringA", ringAlpha());
            s.setFloatUniform("time", seconds());
            canvas.drawRect(0f, 0f, w, h, markPaint);
            return;
        }
        // No shaders: a soft glow on the same timing.
        float g = glow();
        if (g <= 0.003f) return;
        float r = radius * (1.2f + 0.6f * exit());
        canvas.save();
        canvas.translate(cx, y);
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
