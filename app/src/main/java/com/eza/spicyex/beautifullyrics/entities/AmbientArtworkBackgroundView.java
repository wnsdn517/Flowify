package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.View;
import androidx.annotation.RequiresApi;

/** A high-precision Kawase texture animated with the desktop Kawarp equations. All renderer state is UI-thread. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class AmbientArtworkBackgroundView extends View implements AmbientBackgroundLayer {
    // Domain warp and output math ported from @kawarp/core. Keep float intermediates:
    // floating-point blur textures preserve smooth gradients without moving contours.
    private static final String AGSL =
            "uniform shader image;\n"
            + "uniform float2 resolution;\n"
            + "uniform float time;\n"
            + "uniform float dark;\n"
            + "uniform float brightness;\n"
            + "uniform float warpIntensity;\n"
            + "  float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n"
            + "  float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n"
            + "  float3 permute(float3 x) { return mod289(((x*34.0)+1.0)*x); }\n"
            + "\n"
            + "  float snoise(float2 v) {\n"
            + "    const float4 C = float4(0.211324865405187, 0.366025403784439,\n"
            + "                        -0.577350269189626, 0.024390243902439);\n"
            + "    float2 i  = floor(v + dot(v, C.yy));\n"
            + "    float2 x0 = v - i + dot(i, C.xx);\n"
            + "    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);\n"
            + "    float4 x12 = x0.xyxy + C.xxzz;\n"
            + "    x12.xy -= i1;\n"
            + "    i = mod289(i);\n"
            + "    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));\n"
            + "    float3 m = max(0.5 - float3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);\n"
            + "    m = m*m; m = m*m;\n"
            + "    float3 x = 2.0 * fract(p * C.www) - 1.0;\n"
            + "    float3 h = abs(x) - 0.5;\n"
            + "    float3 ox = floor(x + 0.5);\n"
            + "    float3 a0 = x - ox;\n"
            + "    m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);\n"
            + "    float3 g;\n"
            + "    g.x = a0.x * x0.x + h.x * x0.y;\n"
            + "    g.yz = a0.yz * x12.xz + h.yz * x12.yw;\n"
            + "    return 130.0 * dot(m, g);\n"
            + "  }\n"
            + "\n"
            + "float hash(float3 p) {\n"
            + "    p = fract(p * 0.1031);\n"
            + "    p += dot(p, p.zyx + 31.32);\n"
            + "    return fract((p.x + p.y) * p.z);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "\n"
            + "    float2 uv = p / resolution;\n"
            + "    float t = time * 0.05;\n"
            + "\n"
            + "    float2 center = uv - 0.5;\n"
            + "    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));\n"
            + "\n"
            + "    // Large-scale movement (slow, big blobs)\n"
            + "    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));\n"
            + "    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));\n"
            + "\n"
            + "    // Medium-scale detail (adds organic movement)\n"
            + "    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));\n"
            + "    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));\n"
            + "\n"
            + "    // Combine two octaves\n"
            + "    float2 warp = float2(\n"
            + "      n1 * 0.65 + n3 * 0.35,\n"
            + "      n2 * 0.65 + n4 * 0.35\n"
            + "    ) * centerWeight;\n"
            + "\n"
            + "    float2 warpedUV = uv + warp * warpIntensity;\n"
            + "    warpedUV = clamp(warpedUV, 0.0, 1.0);\n"
            + "\n"
            + "\n"
            + "    // Two 128px tiles retain the desktop tint-before-blur order for either dark setting.\n"
            + "    float2 samplePx = clamp(warpedUV * 128.0, 0.5, 127.5);\n"
            + "    samplePx.x += dark * 128.0;\n"
            + "    float3 c = float3(image.eval(samplePx).rgb);\n"
            + "    c *= 1.0 - dot(center, center) * 0.3;\n"
            + "    float gray = dot(c, float3(0.299, 0.587, 0.114));\n"
            + "    c = clamp(mix(float3(gray), c, mix(1.5, 0.75, dark)), 0.0, 1.0);\n"
            + "    // Desktop fullscreen CSS: normal saturate(2.5) brightness(.65), dark\n"
            + "    // brightness(.38) saturate(.9) contrast(.95). CSS saturation uses Rec.709.\n"
            + "    float cssGray = dot(c, float3(0.213, 0.715, 0.072));\n"
            + "    float3 normal = clamp(mix(float3(cssGray), c, 2.5), 0.0, 1.0) * 0.65;\n"
            + "    float3 dim = (mix(float3(cssGray), c, 0.9) * 0.38 - 0.5) * 0.95 + 0.5;\n"
            + "    c = mix(normal, dim, dark) * brightness;\n"
            + "    // Desktop hash/dither strength, moved after final darkening to retain output precision.\n"
            + "    // Zero brightness must stay exactly black.\n"
            + "    float noise = hash(float3(floor(p), 0.0));\n"
            + "    c += (noise - 0.5) * 0.008 * min(1.0, brightness * 255.0);\n"
            + "    return half4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private final RuntimeShader shader = new RuntimeShader(AGSL);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fallback = new Paint(Paint.DITHER_FLAG);
    private Bitmap texture;
    private boolean enabled = true, moving = true, playing = true, posted;
    private long lastFrame;
    private double elapsedSeconds;
    private int colorA = Color.rgb(30,21,18), colorB = Color.rgb(16,15,16);
    private final Choreographer.FrameCallback frame = this::tick;
    /**
     * The AGSL noise shader's cost is per output pixel, so it's rendered into a much smaller
     * offscreen surface and upscaled (the blur/warp look hides the extra softness) instead of at
     * full view resolution every frame - a full-res fragment shader running continuously for the
     * whole lyrics session is a real sustained-heat source on older/weaker GPUs.
     */
    private static final float DEFAULT_RENDER_SCALE = 0.34f;
    /** User-configurable via {@link #setRenderScale}, backing the Background panel's Quality
     *  slider - lower trades softness for less sustained GPU/heat load, higher for crisper noise. */
    private float renderScale = DEFAULT_RENDER_SCALE;
    // A RenderNode is drawn on RenderThread/GPU like the main canvas, unlike a Bitmap-backed
    // Canvas (always CPU raster) which RuntimeShader/AGSL cannot draw into at all.
    private RenderNode offscreenNode;
    /** Freezes the animation (last frame stays visible) once the OS reports the device running
     *  hot, instead of continuing to add GPU load on top of whatever caused it. */
    private boolean thermalThrottled;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    /*
     * Music reactivity. The drums come in already separated from the vocals and the rest of the
     * mix (see BeatTracker), in three layers:
     *  - kick (and, smaller, the snare): the whole background "breathes" - a zoom driven by a
     *    slightly under-damped spring, so a hit punches out fast, settles back with a soft
     *    rebound, and back-to-back hits build on each other instead of restarting. The zoom is a
     *    view property, composited at the display rate without re-running the shader. (It used
     *    to jump the shader's warp amount on each kick, redrawn at 20-30fps, which read as the
     *    image twitching.)
     *  - snare/clap: a short brightening flash, and with the kick a surge in how far the flow has
     *    travelled - the blobs lurch forward on a hit and drift on, never jump.
     *  - energy: the song's loudness, smoothed and measured against its own running level, sets
     *    how fast and how far the background flows. Quiet verses drift; a loud chorus moves.
     */
    private static final float BEAT_ZOOM = 0.034f;
    private static final float SNARE_ZOOM_SHARE = 0.4f;
    private static final float BEAT_BRIGHTEN = 0.12f;
    private static final float SNARE_BRIGHTEN = 0.14f;
    /** Spring behind the zoom: ~3.6 Hz, damping ratio 0.55 (a small, soft rebound). */
    private static final float SPRING_OMEGA = (float) (2 * Math.PI * 3.6);
    private static final float SPRING_DAMPING = 0.55f;
    private static final float FLASH_ATTACK_SEC = 0.02f;
    private static final float FLASH_RELEASE_SEC = 0.2f;
    private static final float SURGE_ATTACK_SEC = 0.05f;
    private static final float SURGE_RELEASE_SEC = 0.5f;
    /** Extra flow speed at a full-strength hit (1 = the resting speed again on top). */
    private static final float SURGE_SPEED = 2.2f;
    private volatile float audioLevel;
    private volatile float audioAccent;
    /** Spring position/velocity (zoom), snare flash and flow surge actually shown. UI thread. */
    private float pulse, pulseVelocity;
    private float flash;
    private float surge;
    private long lastPulseNanos;
    private float appliedZoom = 1f;
    // Energy: short and long loudness averages; energy01 is 0.5 at the song's usual level.
    private float loudShort;
    private float loudLong;
    private float energy01 = 0.5f;
    private long lastEnergyNanos;
    private long energyUpdatedNanos;
    private float baseBrightness = 1f;

    public AmbientArtworkBackgroundView(Context context, boolean dark) {
        super(context);
        shader.setFloatUniform("brightness", 1f);
        shader.setFloatUniform("warpIntensity", 1f);
        setForceDark(dark);
    }

    public View asView() { return this; }
    public void setForceDark(boolean dark) {
        shader.setFloatUniform("dark", dark ? 1f : 0f);
        invalidate();
    }
    public void setDarkening(float brightness, ColorFilter filter) {
        baseBrightness = brightness;
        shader.setFloatUniform("brightness", brightness);
        // Animated shader receives brightness directly; only the fallback canvas needs a filter.
        fallback.setColorFilter(filter);
        invalidate();
    }
    public void setPaletteColors(int[] colors) {
        if (colors == null || colors.length < 2) return;
        colorA = colors[0]; colorB = colors[colors.length-1];
        updateFallback(); invalidate();
    }
    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        if (!playing) {
            pulse = 0f;
            pulseVelocity = 0f;
            flash = 0f;
            surge = 0f;
            applyPulse();
        }
        schedule();
    }
    public void setMotionEnabled(boolean value) {
        if (moving == value) return;
        moving = value;
        if (!moving) elapsedSeconds = 0;
        schedule(); invalidate();
    }
    public void pauseRendering() { enabled = false; schedule(); }
    public void resumeRendering() { enabled = true; schedule(); }

    /** Accepts a prepared, module-owned texture. No asynchronous upload can outlive this call. */
    public void updateImage(Bitmap prepared) {
        updateImage(prepared, null);
    }

    /**
     * Accepts the prepared normal/dark texture atlas. The profile is retained in the shared
     * handoff contract, but desktop Kawarp does not modulate brightness by artwork variance.
     */
    public void updateImage(Bitmap prepared, AmbientArtworkProfile profile) {
        if (prepared == null || prepared.isRecycled()) return;
        BitmapShader input = new BitmapShader(prepared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        shader.setInputShader("image", input);
        texture = prepared;
        // Dropping references lets RenderThread finish using the previous texture before collection.
        paint.setShader(shader);
        schedule(); invalidate();
    }

    /**
     * One frame of this background at {@code width} x {@code height}, for the lyric share card.
     * Renders with its own copy of the shader (so the live view's uniforms are never touched from
     * another thread) through an offscreen HardwareRenderer, and softens it like the live layer.
     * Null when there is no artwork texture yet or the platform refuses.
     */
    public Bitmap snapshot(int width, int height) {
        Bitmap source = texture;
        if (source == null || source.isRecycled()) return null;
        android.media.ImageReader reader = null;
        HardwareRenderer renderer = null;
        try {
            RuntimeShader copy = new RuntimeShader(AGSL);
            BitmapShader input = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            copy.setInputShader("image", input);
            copy.setFloatUniform("resolution", width, height);
            copy.setFloatUniform("time", (float) elapsedSeconds);
            copy.setFloatUniform("dark", 0f);
            copy.setFloatUniform("brightness", baseBrightness);
            copy.setFloatUniform("warpIntensity", 1f);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setShader(copy);
            RenderNode node = new RenderNode("ambientSnapshot");
            node.setPosition(0, 0, width, height);
            node.setRenderEffect(RenderEffect.createBlurEffect(9f, 9f, Shader.TileMode.CLAMP));
            RecordingCanvas canvas = node.beginRecording();
            canvas.drawRect(0, 0, width, height, p);
            node.endRecording();
            reader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (android.media.Image image = reader.acquireNextImage()) {
                if (image == null) return null;
                android.hardware.HardwareBuffer buffer = image.getHardwareBuffer();
                if (buffer == null) return null;
                try {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer,
                            ColorSpace.get(ColorSpace.Named.SRGB));
                    return wrapped == null ? null : wrapped.copy(Bitmap.Config.ARGB_8888, false);
                } finally {
                    buffer.close();
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (renderer != null) renderer.destroy();
            if (reader != null) reader.close();
        }
    }

    public void release() {
        enabled = false; schedule();
        paint.setShader(null);
        shader.setInputShader("image", new LinearGradient(0,0,1,1,Color.BLACK,Color.BLACK,Shader.TileMode.CLAMP));
        texture = null;
        invalidate();
    }
    private boolean animating() {
        return enabled && moving && playing && !thermalThrottled && texture != null
                && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown()
                && com.eza.spicyex.ui.Motion.animationsEnabled();
    }
    private void schedule() {
        if (!animating()) {
            Choreographer.getInstance().removeFrameCallback(frame);
            posted = false; lastFrame = 0; return;
        }
        if (!posted) {
            posted = true;
            // ~20fps: slow drifting noise reads the same as at 33fps but wakes the GPU less often.
            // The zoom is not tied to this rate (see stepPulse), but the flash and the surge ride
            // on the shader, so ~30fps while the music is driving it - still on the small layer.
            Choreographer.getInstance().postFrameCallbackDelayed(frame, energyLive() ? 33 : 50);
        }
    }
    private void tick(long now) {
        posted = false;
        if (!animating()) { lastFrame = 0; return; }
        if (lastFrame != 0) {
            double dt = Math.min(0.1, (now - lastFrame) / 1e9);
            elapsedSeconds += dt * (flowSpeed() + SURGE_SPEED * surge);
        }
        // Keeps the beat envelope moving when nothing else calls setAudioLevel this frame.
        stepPulse(System.nanoTime());
        lastFrame = now;
        invalidate();
        schedule();
    }
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerThermalListener();
        schedule();
    }
    protected void onDetachedFromWindow() {
        unregisterThermalListener();
        Choreographer.getInstance().removeFrameCallback(frame);
        posted = false; lastFrame = 0;
        super.onDetachedFromWindow();
    }
    private void registerThermalListener() {
        if (thermalListener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        try {
            thermalListener = status -> {
                boolean hot = status >= PowerManager.THERMAL_STATUS_MODERATE;
                if (hot == thermalThrottled) return;
                thermalThrottled = hot;
                schedule();
            };
            pm.addThermalStatusListener(getContext().getMainExecutor(), thermalListener);
            thermalThrottled = pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        } catch (Throwable ignored) {
            thermalListener = null;
        }
    }
    private void unregisterThermalListener() {
        if (thermalListener == null) return;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try { pm.removeThermalStatusListener(thermalListener); } catch (Throwable ignored) { }
        }
        thermalListener = null;
        thermalThrottled = false;
    }
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) schedule();
    }
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed,visibility);
        if (frame != null) schedule();
    }
    /** Quality<->performance slider (Background panel), 0.15-1.0: lower renders the offscreen
     *  noise surface at a coarser resolution (softer, cheaper on the GPU), 1.0 is full view
     *  resolution. Re-sizes the offscreen surface immediately if already laid out. */
    public void setRenderScale(float scale) {
        float clamped = Math.max(0.15f, Math.min(1f, scale));
        if (clamped == renderScale) return;
        renderScale = clamped;
        if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        int lowW = Math.max(1, Math.round(w * renderScale));
        int lowH = Math.max(1, Math.round(h * renderScale));
        // Uniform tracks the offscreen surface's own size, not the view's - the shader's uv
        // mapping (p / resolution) only needs to span 0..1 across whatever it's drawn onto.
        shader.setFloatUniform("resolution", lowW, lowH);
        if (offscreenNode == null) {
            offscreenNode = new RenderNode("ambientArtwork");
            // The compositing layer is what makes this node an actual lowW x lowH surface. Without
            // it, drawRenderNode just replays the rect under the parent's scale, so the shader ran
            // per full-resolution screen pixel on every frame the lyrics above it redrew. With it,
            // the shader runs only when onDraw re-records (the ~20fps tick); other frames reuse
            // the layer texture, bilinear-upscaled.
            offscreenNode.setUseCompositingLayer(true, null);
        }
        // Soften the low-resolution layer before it is upscaled. Unblurred, its warp edges and
        // dither stair-step and shimmer once magnified (the background "crawled"), while the
        // blurred lyric rows over it are smooth; a small blur at layer scale gives both the same
        // soft texture. Cheap: it runs on the small layer, and only when the layer is redrawn.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = Math.max(1.5f, 3.2f * renderScale / DEFAULT_RENDER_SCALE);
            offscreenNode.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        }
        offscreenNode.setPosition(0, 0, lowW, lowH);
        updateFallback();
    }
    private void updateFallback() {
        fallback.setShader(new LinearGradient(0,0,Math.max(1,getWidth()),Math.max(1,getHeight()),
                colorA,colorB,Shader.TileMode.CLAMP));
    }
    @Override
    public void setAudioLevel(float level0to1) {
        audioLevel = Math.max(0f, Math.min(1f, level0to1));
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) stepPulse(System.nanoTime());
    }

    @Override
    public void setAudioAccent(float accent0to1) {
        audioAccent = Math.max(0f, Math.min(1f, accent0to1));
    }

    @Override
    public void setAudioEnergy(float loudness0to1) {
        long now = System.nanoTime();
        float dt = lastEnergyNanos == 0 ? 0f : Math.min(0.2f, (now - lastEnergyNanos) / 1e9f);
        lastEnergyNanos = now;
        float loud = Math.max(0f, Math.min(1f, loudness0to1));
        if (loud <= 0.001f || dt <= 0f) return;
        energyUpdatedNanos = now;
        if (loudLong <= 0f) {
            loudShort = loud;
            loudLong = loud;
        } else {
            loudShort += (loud - loudShort) * (1f - (float) Math.exp(-dt / 0.7f));
            loudLong += (loud - loudLong) * (1f - (float) Math.exp(-dt / 12f));
        }
        float e = 0.5f + (loudShort - loudLong) / (loudLong * 1.2f + 0.02f);
        energy01 = Math.max(0f, Math.min(1f, e));
    }

    /** True while the lyrics screen is feeding live loudness (beat-reactive on, audio playing). */
    private boolean energyLive() {
        return energyUpdatedNanos != 0 && System.nanoTime() - energyUpdatedNanos < 1_000_000_000L;
    }

    private float flowSpeed() {
        return energyLive() ? 0.6f + energy01 : 1f;
    }

    /** Steps the zoom spring, the flash and the surge toward the live drum envelopes. */
    private void stepPulse(long now) {
        float dt = lastPulseNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastPulseNanos) / 1e9f);
        lastPulseNanos = now;
        if (dt <= 0f) return;
        boolean live = moving && playing && enabled;
        float kick = live ? audioLevel : 0f;
        float snare = live ? audioAccent : 0f;
        // Hits in a quiet passage are gentler than in a loud one.
        float scale = energyLive() ? 0.55f + 0.6f * energy01 : 1f;
        kick *= scale;
        snare *= scale;

        float target = Math.min(1.2f, kick + SNARE_ZOOM_SHARE * snare);
        // Semi-implicit Euler in <=4ms steps: stable at any frame rate, 60-120 Hz alike.
        float k = SPRING_OMEGA * SPRING_OMEGA;
        float c = 2f * SPRING_DAMPING * SPRING_OMEGA;
        for (float left = dt; left > 0f; ) {
            float h = Math.min(0.004f, left);
            left -= h;
            pulseVelocity += (k * (target - pulse) - c * pulseVelocity) * h;
            pulse += pulseVelocity * h;
        }
        if (target == 0f && Math.abs(pulse) < 0.002f && Math.abs(pulseVelocity) < 0.01f) {
            pulse = 0f;
            pulseVelocity = 0f;
        }
        flash = ease(flash, snare, dt, FLASH_ATTACK_SEC, FLASH_RELEASE_SEC);
        surge = ease(surge, Math.max(kick, snare), dt, SURGE_ATTACK_SEC, SURGE_RELEASE_SEC);
        applyPulse();
    }

    private static float ease(float value, float target, float dt, float attackSec, float releaseSec) {
        float tau = target > value ? attackSec : releaseSec;
        value += (target - value) * (1f - (float) Math.exp(-dt / tau));
        return value < 0.002f ? 0f : value;
    }

    private void applyPulse() {
        // Only the zoom goes out every frame: a view property, composited without touching the
        // background layer. The brightening rides along in the shader's own ~20fps redraw
        // (onDraw) - changing the layer's paint every frame instead damaged the layer, so the
        // shader and its blur re-rendered at the full display rate and the GPU fell behind.
        // Never below 1: the spring's rebound would otherwise pull the edges into view.
        float zoom = 1f + BEAT_ZOOM * Math.max(0f, pulse);
        if (Math.abs(zoom - appliedZoom) > 0.0004f) {
            appliedZoom = zoom;
            setScaleX(zoom);
            setScaleY(zoom);
        }
    }

    protected void onDraw(Canvas canvas) {
        if (texture != null) {
            shader.setFloatUniform("time", (float) elapsedSeconds);
            // A paused or stopped background must not keep pulsing: the measured level can still
            // be non-zero for a beat after the shell stops advancing time.
            // Energy widens the flow a little in loud sections; the beat is applied at composite
            // time (applyPulse), not here.
            float energyWarp = moving && playing && energyLive() ? 0.45f * (energy01 - 0.5f) : 0f;
            shader.setFloatUniform("warpIntensity", 1f + energyWarp);
            float lift = BEAT_BRIGHTEN * Math.max(0f, Math.min(1f, pulse)) + SNARE_BRIGHTEN * flash;
            shader.setFloatUniform("brightness", baseBrightness * (1f + lift));
            if (offscreenNode != null && canvas.isHardwareAccelerated()) {
                int lowW = offscreenNode.getWidth(), lowH = offscreenNode.getHeight();
                RecordingCanvas recording = offscreenNode.beginRecording();
                recording.drawRect(0, 0, lowW, lowH, paint);
                offscreenNode.endRecording();
                canvas.save();
                canvas.scale(getWidth() / (float) lowW, getHeight() / (float) lowH);
                canvas.drawRenderNode(offscreenNode);
                canvas.restore();
                return;
            }
        }
        canvas.drawRect(0,0,getWidth(),getHeight(),texture == null ? fallback : paint);
    }
}
