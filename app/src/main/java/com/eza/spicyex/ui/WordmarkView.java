package com.eza.spicyex.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The "Spicy EX" wordmark on the About page. Letters rise in one after another, then the word
 * keeps a slow flow of colour (the album's accent and its neighbouring hue) under a soft glow
 * that breathes, with a few sparkles twinkling around it. Runs only while on screen.
 */
public final class WordmarkView extends View {
    private static final long LETTER_STAGGER_MS = 70L;
    private static final long LETTER_IN_MS = 620L;
    private static final long FLOW_MS = 5200L;
    private static final long GLOW_MS = 2600L;

    private final String text;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix flowMatrix = new Matrix();
    private final Path star = new Path();
    private final float[] advances;
    private final int accent;
    private final int accentLight;
    private final int partner;
    private LinearGradient shader;
    private ValueAnimator clock;
    private long startedAt;
    private float textWidth;

    /** Sparkles: x, y as fractions of the word's box (outside it too), size, phase. */
    private static final float[][] SPARKS = {
            {-0.06f, 0.15f, 1.0f, 0.0f}, {0.18f, -0.25f, 0.7f, 0.35f}, {0.52f, -0.32f, 0.9f, 0.7f},
            {0.86f, -0.18f, 0.6f, 0.15f}, {1.06f, 0.55f, 0.85f, 0.55f}, {0.70f, 1.18f, 0.65f, 0.85f},
            {0.30f, 1.15f, 0.75f, 0.25f},
    };

    public WordmarkView(Context context, String text, int accent, Typeface typeface) {
        super(context);
        this.text = text;
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = accent | 0xFF000000;
        this.accentLight = blend(this.accent, 0xFFFFFFFF, 0.6f);
        float[] hsv = new float[3];
        Color.colorToHSV(this.accent, hsv);
        hsv[0] = (hsv[0] + 42f) % 360f;
        hsv[1] = Math.max(0.45f, hsv[1]);
        hsv[2] = 1f;
        this.partner = Color.HSVToColor(hsv);
        float size = 46f * context.getResources().getDisplayMetrics().scaledDensity;
        Typeface face = typeface == null ? Typeface.DEFAULT_BOLD : Typeface.create(typeface, Typeface.BOLD);
        for (Paint p : new Paint[]{fill, glow}) {
            p.setTextSize(size);
            p.setTypeface(face);
            p.setLetterSpacing(-0.02f);
        }
        glow.setMaskFilter(new BlurMaskFilter(size * 0.32f, BlurMaskFilter.Blur.NORMAL));
        spark.setColor(Color.WHITE);
        advances = new float[text.length()];
        fill.getTextWidths(text, advances);
        for (float a : advances) textWidth += a;
        // The glow's blur needs the software pipeline; the view is small.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription(text);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int pad = Math.round(fill.getTextSize() * 0.6f);
        int w = Math.round(textWidth) + pad * 2;
        int h = Math.round(fill.getTextSize() * 1.9f);
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAt = android.os.SystemClock.uptimeMillis();
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000L);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.addUpdateListener(a -> invalidate());
        if (Motion.animationsEnabled()) clock.start();
        else startedAt -= 10_000L; // everything already in place
    }

    @Override
    protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        clock = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long t = android.os.SystemClock.uptimeMillis() - startedAt;
        float size = fill.getTextSize();
        float left = (getWidth() - textWidth) / 2f;
        Paint.FontMetrics fm = fill.getFontMetrics();
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        if (shader == null) {
            shader = new LinearGradient(0, 0, textWidth, 0,
                    new int[]{accentLight, accent, partner, accent, accentLight, 0xFFFFFFFF, accentLight},
                    null, Shader.TileMode.MIRROR);
        }
        float flow = (t % FLOW_MS) / (float) FLOW_MS;
        flowMatrix.setTranslate(left - flow * textWidth * 2f, 0f);
        shader.setLocalMatrix(flowMatrix);
        fill.setShader(shader);

        // The glow arrives with the last letter, then breathes.
        long allIn = LETTER_STAGGER_MS * (text.length() - 1) + LETTER_IN_MS;
        float glowIn = clamp((t - allIn * 0.6f) / 500f);
        float breathe = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * (t % GLOW_MS) / GLOW_MS);
        glow.setColor((accent & 0x00FFFFFF) | (Math.round(255 * glowIn * (0.38f + 0.27f * breathe)) << 24));
        if (glowIn > 0f) canvas.drawText(text, left, baseline, glow);

        float x = left;
        for (int i = 0; i < text.length(); i++) {
            float p = clamp((t - i * LETTER_STAGGER_MS) / (float) LETTER_IN_MS);
            float ease = overshoot(p);
            String letter = text.substring(i, i + 1);
            if (p > 0f) {
                canvas.save();
                float cx = x + advances[i] / 2f;
                canvas.translate(0f, (1f - ease) * size * 0.45f);
                float scale = 0.7f + 0.3f * ease;
                canvas.scale(scale, scale, cx, baseline);
                fill.setAlpha(Math.round(255 * Math.min(1f, p * 1.6f)));
                canvas.drawText(letter, x, baseline, fill);
                canvas.restore();
            }
            x += advances[i];
        }
        fill.setAlpha(255);

        // Sparkles once the word is in.
        float sparkIn = clamp((t - allIn) / 400f);
        if (sparkIn > 0f) {
            float top = baseline + fm.ascent;
            float h = fm.descent - fm.ascent;
            for (float[] s : SPARKS) {
                float phase = ((t / 1900f) + s[3]) % 1f;
                float tw = (float) Math.max(0, Math.sin(phase * Math.PI * 2));
                if (tw <= 0.02f) continue;
                float sx = left + s[0] * textWidth;
                float sy = top + s[1] * h;
                drawStar(canvas, sx, sy, size * 0.2f * s[2] * (0.6f + 0.4f * tw),
                        Math.round(230 * tw * sparkIn));
            }
        }
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r, int alpha) {
        star.reset();
        float k = r * 0.22f;
        star.moveTo(cx, cy - r);
        star.quadTo(cx + k, cy - k, cx + r, cy);
        star.quadTo(cx + k, cy + k, cx, cy + r);
        star.quadTo(cx - k, cy + k, cx - r, cy);
        star.quadTo(cx - k, cy - k, cx, cy - r);
        star.close();
        spark.setColor(blend(accentLight, 0xFFFFFFFF, 0.5f));
        spark.setAlpha(alpha);
        canvas.drawPath(star, spark);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Ease-out with a small overshoot, so each letter lands with a little spring. */
    private static float overshoot(float p) {
        float s = 1.6f;
        float q = p - 1f;
        return q * q * ((s + 1f) * q + s) + 1f;
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
