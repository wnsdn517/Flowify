package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.function.Supplier;

/**
 * What plays in place of lyrics on an instrumental track: a few soft, overlapping light ribbons
 * over a hairline, in the manner of the iOS Siri wave (github.com/kopiro/siriwave), rather than
 * a bar graph.
 *
 * <p>Each ribbon is a sine that tapers to nothing at both ends and is mirrored about the centre
 * line into a translucent lens. Bass, mids and highs each drive their own ribbons' height, and
 * the overall level drives how fast they travel, so a kick swells the wide slow ribbons and hats
 * shimmer the narrow quick ones. Levels go through critically damped springs, stepped with the
 * real frame time, so the motion is continuous at any display rate whatever rate the analysis
 * arrives at (a reading that arrives late or in a burst moves a target, never the picture).
 * With no signal (paused, or nothing measured yet) the ribbons breathe low instead of stopping.
 *
 * <p>{@link #setBands} switches to plain spectrum bars for the audio diagnostics dialog.
 */
public final class InstrumentalVisualizerView extends View {
    /** Spring for the levels: fast enough for a kick to land, damped so it never rings. */
    private static final float OMEGA = 16f;
    private static final float ZETA = 0.9f;
    private static final float IDLE = 0.09f;

    /** Ribbon: which level drives it (0 bass, 1 mid, 2 high, 3 all), cycles across the width,
     *  travel speed (signed), and how much of the height it may take. */
    private static final int[] DRIVER = {0, 3, 1, 2, 0};
    private static final float[] CYCLES = {1.35f, 2.1f, 2.9f, 4.2f, 1.75f};
    private static final float[] SPEED = {1.1f, -1.6f, 2.2f, -2.9f, -0.8f};
    private static final float[] REACH = {1.0f, 0.8f, 0.62f, 0.42f, 0.7f};
    /** Faint tints so the overlaps read as light, not as grey shapes. */
    private static final int[] TINT = {0x2EFFFFFF, 0x26DDE9FF, 0x24FFE2EF, 0x22E3FFF4, 0x1FFFFFFF};

    private final Supplier<float[]> spectrum;
    private final Paint ribbon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    /** bass, mid, high, all: spring position and velocity. */
    private final float[] level = new float[4];
    private final float[] velocity = new float[4];
    private final float[] phase = new float[DRIVER.length];
    private float[] bandLevel = new float[0];
    private float[] bandVelocity = new float[0];
    private float travel;
    private long lastFrameNanos;
    private int lineWidth = -1;
    private boolean bands;

    public InstrumentalVisualizerView(Context context, Supplier<float[]> spectrum) {
        super(context);
        this.spectrum = spectrum;
        ribbon.setStyle(Paint.Style.FILL);
        crest.setStyle(Paint.Style.STROKE);
        crest.setStrokeCap(Paint.Cap.ROUND);
        crest.setStrokeJoin(Paint.Join.ROUND);
        line.setStyle(Paint.Style.FILL);
        bar.setColor(0xCCFFFFFF);
        for (int i = 0; i < phase.length; i++) phase[i] = i * 1.3f;
    }

    /** Plain per-band bars instead of ribbons (diagnostics, where the spectrum itself matters). */
    public void setBands(boolean bands) {
        this.bands = bands;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtimeNanos();
        float dt = lastFrameNanos == 0L ? 1f / 60f
                : Math.max(1f / 240f, Math.min(0.05f, (now - lastFrameNanos) / 1e9f));
        lastFrameNanos = now;
        float[] source = spectrum == null ? null : spectrum.get();
        boolean silent = isQuiet(source);

        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            if (bands) drawBands(canvas, source, silent, dt, w, h);
            else drawRibbons(canvas, source, silent, dt, now, w, h);
        }
        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private void drawRibbons(Canvas canvas, float[] source, boolean silent, float dt, long now,
                             int w, int h) {
        float breath = IDLE * (0.75f + 0.25f * (float) Math.sin(now / 1e9 * 1.3));
        float[] target = new float[4];
        if (silent) {
            java.util.Arrays.fill(target, breath);
        } else {
            int n = source.length;
            target[0] = average(source, 0, Math.max(1, n / 4));
            target[1] = average(source, n / 4, Math.max(n / 4 + 1, n * 5 / 8));
            target[2] = average(source, n * 5 / 8, n);
            target[3] = average(source, 0, n);
            for (int i = 0; i < 4; i++) target[i] = Math.max(breath, target[i]);
        }
        for (int i = 0; i < 4; i++) {
            float accel = OMEGA * OMEGA * (target[i] - level[i]) - 2f * ZETA * OMEGA * velocity[i];
            velocity[i] += accel * dt;
            level[i] = Math.max(0f, Math.min(1.2f, level[i] + velocity[i] * dt));
        }
        // Livelier travel when the music is louder; never stopped.
        travel = 0.35f + 1.4f * level[3];
        for (int r = 0; r < phase.length; r++) {
            phase[r] = (float) ((phase[r] + SPEED[r] * travel * dt) % (Math.PI * 2));
        }

        float mid = h * 0.5f;
        float maxHalf = h * 0.46f;
        float step = Math.max(2f, w / 180f);

        if (lineWidth != w) {
            lineWidth = w;
            line.setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{0x00FFFFFF, 0x59FFFFFF, 0x59FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, mid - 0.5f, w, mid + 0.5f, line);

        for (int r = 0; r < DRIVER.length; r++) {
            // A little of the overall level in every ribbon, so none sits dead on a sparse mix.
            float amp = (0.8f * level[DRIVER[r]] + 0.2f * level[3]) * REACH[r] * maxHalf;
            buildLens(w, mid, amp, CYCLES[r], phase[r], step);
            ribbon.setColor(TINT[r]);
            canvas.drawPath(path, ribbon);
        }
        // The main ribbon's crest drawn as a bright hairline over the fills: the "light" edge.
        float amp = (0.8f * level[0] + 0.2f * level[3]) * REACH[0] * maxHalf;
        buildCrest(w, mid, amp, CYCLES[0], phase[0], step);
        crest.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.2f));
        crest.setColor(0x8CFFFFFF);
        canvas.drawPath(path, crest);
    }

    /** Taper that takes a ribbon to zero at both ends (siriwave's attenuation, K = 2). */
    private static float taper(float u) {
        float x = 2f * u; // -2..2
        float k = 2f;
        return (float) Math.pow(k / (k + x * x * x * x), k);
    }

    /** Mirrored lens: the |sine| above the centre line, then back along its reflection below. */
    private void buildLens(int w, float mid, float amp, float cycles, float phase, float step) {
        path.rewind();
        path.moveTo(0, mid);
        for (float x = 0; x <= w; x += step) {
            path.lineTo(x, mid - offset(x, w, amp, cycles, phase));
        }
        path.lineTo(w, mid);
        for (float x = w; x >= 0; x -= step) {
            path.lineTo(x, mid + offset(x, w, amp, cycles, phase));
        }
        path.close();
    }

    private void buildCrest(int w, float mid, float amp, float cycles, float phase, float step) {
        path.rewind();
        path.moveTo(0, mid);
        for (float x = 0; x <= w; x += step) {
            float u = x / w * 2f - 1f;
            float y = (float) Math.sin(u * Math.PI * cycles + phase) * taper(u) * amp;
            path.lineTo(x, mid - y);
        }
    }

    private static float offset(float x, int w, float amp, float cycles, float phase) {
        float u = x / w * 2f - 1f;
        return Math.abs((float) Math.sin(u * Math.PI * cycles + phase)) * taper(u) * amp;
    }

    private void drawBands(Canvas canvas, float[] source, boolean silent, float dt, int w, int h) {
        int n = source == null || source.length == 0 ? 28 : source.length;
        if (bandLevel.length != n) {
            bandLevel = new float[n];
            bandVelocity = new float[n];
        }
        float slot = w / (float) n;
        float barWidth = slot * 0.5f;
        float mid = h * 0.5f;
        for (int i = 0; i < n; i++) {
            float target = silent ? 0f : source[i];
            float accel = OMEGA * OMEGA * (target - bandLevel[i]) - 2f * ZETA * OMEGA * bandVelocity[i];
            bandVelocity[i] += accel * dt;
            bandLevel[i] = Math.max(0f, Math.min(1f, bandLevel[i] + bandVelocity[i] * dt));
            float half = Math.max(barWidth * 0.5f, bandLevel[i] * h * 0.46f);
            float cx = slot * (i + 0.5f);
            rect.set(cx - barWidth * 0.5f, mid - half, cx + barWidth * 0.5f, mid + half);
            canvas.drawRoundRect(rect, barWidth * 0.5f, barWidth * 0.5f, bar);
        }
    }

    private static float average(float[] values, int from, int to) {
        if (to <= from) return 0f;
        float sum = 0f;
        for (int i = from; i < to; i++) sum += values[i];
        return sum / (to - from);
    }

    private static boolean isQuiet(float[] source) {
        if (source == null || source.length == 0) return true;
        for (float v : source) if (v > 0.02f) return false;
        return true;
    }
}
