// Double-tap like style "Watercolor" (Settings.DOUBLE_TAP_LIKE_EFFECT). See LikeBursts for the list.
package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like acknowledgement as paint on wet paper:
 *
 * <ul>
 *   <li>The backdrop (behind the lyrics): the Liked Songs colour soaks out from the finger with
 *       an uneven, wandering edge where the pigment pools darker, the paper inside lightly
 *       granulated and a little rippled while wet; then it dries out and fades.</li>
 *   <li>The mark: a star (or heart) painted in - the colour floods out from its middle to fill
 *       the shape, uneven inside and darker along its edge - which then fades with the wash.</li>
 *   <li>The lyrics are left alone.</li>
 * </ul>
 *
 * <p>Both are AGSL (API 33+): the mark is a shader drawn by this view, the backdrop gets a
 * RenderEffect. Below API 33 nothing plays. The small form (around the like button) is nothing.
 */
public final class LikeBurstWatercolor extends View implements LikeBurst {
    private static final long DURATION_MS = 1700L;

    private static final String NOISE = ""
            + "float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }"
            + "float vnoise(float2 p) {"
            + "  float2 i = floor(p);"
            + "  float2 f = fract(p);"
            + "  float2 u = f * f * (3.0 - 2.0 * f);"
            + "  return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),"
            + "             mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);"
            + "}"
            + "float fbm(float2 p) {"
            + "  float v = 0.0;"
            + "  float a = 0.5;"
            + "  for (int i = 0; i < 4; i++) { v += a * vnoise(p); p = p * 2.03 + 17.1; a *= 0.5; }"
            + "  return v;"
            + "}";

    private static final String SHAPES = ""
            + "float dot2(float2 v) { return dot(v, v); }"
            + "float sdStar(float2 p) {"
            + "  float2 k1 = float2(0.809016994375, -0.587785252292);"
            + "  float2 k2 = float2(-k1.x, k1.y);"
            + "  float rr = 0.08;"
            + "  float r = 0.95 - rr;"
            + "  p.x = abs(p.x);"
            + "  p -= 2.0 * max(dot(k1, p), 0.0) * k1;"
            + "  p -= 2.0 * max(dot(k2, p), 0.0) * k2;"
            + "  p.x = abs(p.x);"
            + "  p.y -= r;"
            + "  float2 ba = 0.45 * float2(-k1.y, k1.x) - float2(0.0, 1.0);"
            + "  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);"
            + "  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y) - rr;"
            + "}"
            + "float sdHeart(float2 p) {"
            + "  p.x = abs(p.x);"
            + "  if (p.y + p.x > 1.0) return sqrt(dot2(p - float2(0.25, 0.75))) - 0.35355339;"
            + "  return sqrt(min(dot2(p - float2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0))))"
            + "      * sign(p.x - p.y);"
            + "}";

    /** The painted mark, drawn by this view (normal blending): q in units of R, y down. */
    private static final String MARK_AGSL = ""
            + "uniform float2 center;"
            + "uniform float R;"
            + "uniform float fill;"
            + "uniform float alpha;"
            + "uniform float heart;"
            + "uniform half3 tint;"
            + NOISE
            + SHAPES
            + "half4 main(float2 p) {"
            + "  if (alpha <= 0.002) return half4(0.0);"
            + "  float2 q = (p - center) / R;"
            + "  float sd = heart > 0.5"
            + "      ? sdHeart(float2(q.x, -q.y) * 0.8 + float2(0.0, 0.5)) / 0.8"
            + "      : sdStar(float2(q.x, -q.y));"
            // A ragged edge: the outline wanders a little with the paper.
            + "  sd += (fbm(q * 3.5 + 4.0) - 0.5) * 0.12;"
            // The colour floods out from the middle to fill the shape.
            + "  float front = fill * 1.4 - length(q) + (fbm(q * 2.5) - 0.5) * 0.5;"
            + "  float wet = smoothstep(0.0, 0.08, front);"
            + "  float inside = smoothstep(0.03, -0.03, sd) * wet;"
            + "  if (inside <= 0.001) return half4(0.0);"
            // Pigment pools darker along the edge; uneven inside.
            + "  float pool = exp(-abs(sd) * 14.0);"
            + "  float grain = fbm(q * 9.0);"
            + "  float3 col = mix(mix(float3(tint), float3(1.0), 0.35), float3(tint), 0.4 + 0.6 * pool);"
            + "  col *= 0.88 + 0.24 * grain;"
            + "  float a = inside * alpha * (0.72 + 0.2 * pool + 0.08 * grain);"
            + "  return half4(half3(col * a), half(a));"
            + "}";

    /** The backdrop: the colour soaking out through it, darker where it pools at the edge. */
    private static final String BACKDROP_AGSL = ""
            + "uniform shader content;"
            + "uniform float2 center;"
            + "uniform float2 bounds;"
            + "uniform float spread;"
            + "uniform float strength;"
            + "uniform float wet;"
            + "uniform float time;"
            + "uniform half3 tint;"
            + NOISE
            + "half4 main(float2 p) {"
            + "  float2 v = p - center;"
            + "  float r = length(v);"
            + "  float2 n = v / max(spread, 1.0);"
            + "  float edgeNoise = fbm(n * 2.2 + 3.0) - 0.5;"
            + "  float x = r / max(spread, 1.0) + edgeNoise * 0.45;"
            + "  float inside = smoothstep(1.0, 0.92, x);"
            + "  float pool = exp(-pow(abs(x - 0.96) * 10.0, 2.0));"
            // Wet paper ripples a little: a smooth sway, no noise - value noise over the screen
            // showed up as blocks there.
            + "  float2 wob = float2(sin(v.y / 37.0 + time * 3.1), sin(v.x / 41.0 - time * 2.7));"
            + "  float2 sp = clamp(p + wob * wet * 4.0 * inside, float2(0.5), bounds - 0.5);"
            + "  half4 c = content.eval(sp);"
            // Uneven pigment: the same low-frequency noise as the edge, so it follows the stain.
            + "  float grain = fbm(n * 4.5 + 11.0);"
            + "  float amt = strength * (inside * (0.28 + 0.12 * grain) + pool * 0.3);"
            + "  half3 ink = half3(tint) * half(0.75 + 0.25 * grain);"
            // Not scaled by the backdrop's alpha: it varies tile by tile there, and the stain
            // showed that as blocks. The stain is opaque paint where it lies.
            + "  half k = half(clamp(amt, 0.0, 0.8));"
            + "  c.rgb = mix(c.rgb, ink, k);"
            + "  c.a = max(c.a, k);"
            + "  return c;"
            + "}";

    private final Paint markPaint = new Paint();
    private final boolean big;
    private final float cx;
    private final float cy;
    private final float radius;
    private Object markShader;
    private Object backdropShader;
    private View backdropView;
    private final float[] backdropOffset = new float[2];
    private ValueAnimator clock;
    private float t;

    private static final java.util.WeakHashMap<ViewGroup, LikeBurstWatercolor> PLAYING =
            new java.util.WeakHashMap<>();
    private ViewGroup host;

    public LikeBurstWatercolor(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.55f;
        int accent = star ? Color.rgb(242, 176, 52) : Color.rgb(236, 72, 108);
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
                android.graphics.RuntimeShader backdrop = new android.graphics.RuntimeShader(BACKDROP_AGSL);
                backdrop.setFloatUniform("tint", tr, tg, tb);
                backdropShader = backdrop;
            } catch (Throwable t) {
                android.util.Log.w("SpicyLikeBurst", "watercolor shaders unavailable: " + t);
                markShader = null;
                backdropShader = null;
            }
        }
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override public void play(ViewGroup parent, View backdrop, View lyrics) {
        if (!big || markShader == null) return;
        LikeBurstWatercolor previous = PLAYING.get(parent);
        if (previous != null) previous.finish();
        PLAYING.put(parent, this);
        host = parent;
        backdropView = backdrop;
        if (backdrop != null) {
            int[] a = new int[2];
            int[] b = new int[2];
            parent.getLocationInWindow(a);
            backdrop.getLocationInWindow(b);
            backdropOffset[0] = b[0] - a[0];
            backdropOffset[1] = b[1] - a[1];
        }
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator c = ValueAnimator.ofFloat(0f, 1f);
        c.setDuration(DURATION_MS);
        c.setInterpolator(new LinearInterpolator());
        c.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            applyBackdrop();
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
        if (Build.VERSION.SDK_INT >= 31 && backdropView != null) backdropView.setRenderEffect(null);
        backdropView = null;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31 && backdropView != null) backdropView.setRenderEffect(null);
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    /** Dries: everything fades over the last part of the run. */
    private float dry() {
        return smooth(phase(t, 0.6f, 1f));
    }

    private void applyBackdrop() {
        if (Build.VERSION.SDK_INT < 33 || backdropView == null || backdropShader == null) return;
        android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) backdropShader;
        float soak = phase(t, 0f, 0.75f);
        float reach = radius * 5.5f;
        s.setFloatUniform("center", cx - backdropOffset[0], cy - backdropOffset[1]);
        s.setFloatUniform("bounds", backdropView.getWidth(), backdropView.getHeight());
        s.setFloatUniform("spread", reach * (1f - (1f - soak) * (1f - soak) * (1f - soak)));
        s.setFloatUniform("strength", smooth(phase(t, 0f, 0.1f)) * (1f - dry()));
        s.setFloatUniform("wet", 1f - smooth(phase(t, 0.2f, 0.8f)));
        s.setFloatUniform("time", t * DURATION_MS / 1000f);
        backdropView.setRenderEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (markShader == null || Build.VERSION.SDK_INT < 33) return;
        android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) markShader;
        s.setFloatUniform("fill", smooth(phase(t, 0f, 0.4f)));
        s.setFloatUniform("alpha", 1f - dry());
        float reach = radius * 1.6f;
        canvas.drawRect(cx - reach, cy - reach, cx + reach, cy + reach, markPaint);
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
