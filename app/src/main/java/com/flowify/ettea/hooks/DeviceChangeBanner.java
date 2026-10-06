package com.flowify.ettea.hooks;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.flowify.ettea.Settings;
import com.flowify.ettea.SpotifyPlusConfig;
import com.flowify.ettea.ui.ActionIconDrawable;

/**
 * Says so on the lyrics screen when playback moves to another device: a frosted-glass card (the
 * screen behind it, blurred) springs in at the top while a ripple with a touch of colour split runs
 * through the screen from where it lands - the device's icon in a ring of the accent colour, "Now
 * playing on", its name, bouncing bars - and lifts away a moment later. Watches only while the lyrics screen is on screen
 * ({@link Settings#DEVICE_CHANGE_BANNER}).
 */
final class DeviceChangeBanner {
    private static final long POLL_MS = 1200L;
    private static final long HOLD_MS = 2600L;

    private final FrameLayout host;
    private final Context context;
    private final SpotifyPlusConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastDevice;
    /** Icon for the device {@link #currentDevice} last named. */
    private ActionIconDrawable.Kind currentKind = ActionIconDrawable.Kind.SMARTPHONE;
    private View showing;
    private boolean running;
    private boolean suppressed;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            check();
            main.postDelayed(this, POLL_MS);
        }
    };

    DeviceChangeBanner(FrameLayout host, Context context, SpotifyPlusConfig config) {
        this.host = host;
        this.context = context;
        this.config = config;
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                running = true;
                lastDevice = null; // what is playing now is the baseline, not a change
                main.removeCallbacks(poll);
                main.post(poll);
            }

            @Override public void onViewDetachedFromWindow(View v) {
                running = false;
                main.removeCallbacks(poll);
            }
        });
    }

    /** The pill is sized for the full-screen lyrics layout (a 52dp-tall badge+text row); a PiP
     *  window is routinely narrower than that, so it only clipped there instead of showing a
     *  miniature version. Simplest fix: it never appears while in PiP. */
    void setSuppressed(boolean value) {
        suppressed = value;
        if (suppressed && showing != null) {
            View old = showing;
            showing = null;
            old.animate().cancel();
            host.removeView(old);
        }
    }

    private void check() {
        PlaybackBridge bridge = PlaybackBridge.current;
        if (bridge == null) return;
        String device = currentDevice(bridge);
        if (device == null) return;
        String previous = lastDevice;
        lastDevice = device;
        if (previous == null || previous.equals(device)) return;
        if (suppressed) return;
        if (!Boolean.TRUE.equals(config.get(Settings.DEVICE_CHANGE_BANNER))) return;
        show(device, currentKind);
    }

    /** The device playing now, by name where it can be known; null before playback is known. */
    private String currentDevice(PlaybackBridge bridge) {
        if (!bridge.playbackIsRemote()) {
            if (!bridge.sessionKnown()) return null;
            // Playing here: say where it actually comes out - earbuds, a wired headset, the phone.
            AudioOutputRoute route = AudioOutputRoute.current(context, (key, fallback) -> strings().get(key, fallback));
            currentKind = route.kind;
            return route.label;
        }
        currentKind = ActionIconDrawable.Kind.SPEAKER;
        String routed = ConnectRouteHandoff.selectedConnectRouteName(context);
        if (routed != null) return routed;
        if (SpotifyConnectHook.webPlayerCarrying()) return "Spicy Connect";
        return strings().get("lyrics_device_other", "Another device");
    }

    /** Read each time: the language can be changed while the lyrics screen stays open. */
    /** Debug agent: shows the banner for {@code device} as if playback had just moved there. */
    void preview(String device) {
        if (suppressed) return;
        boolean phone = device == null || device.isEmpty();
        show(phone ? phoneLabel() : device,
                phone ? ActionIconDrawable.Kind.SMARTPHONE : ActionIconDrawable.Kind.SPEAKER);
    }

    private com.flowify.ettea.ui.SettingsUiStrings strings() {
        return com.flowify.ettea.ui.UiLanguage.strings(context, config.get(Settings.UI_LANGUAGE));
    }

    private String phoneLabel() {
        return strings().get("lyrics_device_phone", "This phone");
    }

    /**
     * A slow refraction swell that keeps the chip's own shape: distance is measured from the
     * pill's rounded outline (a rounded-box SDF), not from its centre, so the wavefront grows out
     * as a widening pill rather than a circle. It bends what it passes outward and back like a
     * lens edge, fading out before it leaves the top band; the colour fringe blends five
     * samples per channel into a soft spectral edge instead of three offset copies.
     */
    private static final String RIPPLE_AGSL =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float corner;\n"
            + "uniform float radius;\n"
            + "uniform float width;\n"
            + "uniform float reach;\n"
            + "uniform float strength;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 rel = p - center;\n"
            + "  float2 q = abs(rel) - (halfSize - corner);\n"
            + "  float2 outer = max(q, 0.0);\n"
            + "  float dist = length(outer) + min(max(q.x, q.y), 0.0) - corner;\n"
            + "  float2 dir = length(outer) > 0.001 ? normalize(outer) : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "  dir *= sign(rel + 0.0001);\n"
            + "  float x = clamp((dist - radius) / width, -1.0, 1.0);\n"
            + "  float crest = 1.0 - smoothstep(0.0, 1.0, abs(x));\n"
            + "  float fade = 1.0 - smoothstep(reach * 0.5, reach, dist);\n"
            + "  float inside = smoothstep(-4.0, 6.0, dist);\n"
            + "  float2 off = dir * sin(x * 3.14159) * crest * fade * inside * strength * 9.0;\n"
            + "  half4 c0 = content.eval(p - off * 0.92);\n"
            + "  half4 c1 = content.eval(p - off * 0.96);\n"
            + "  half4 c2 = content.eval(p - off);\n"
            + "  half4 c3 = content.eval(p - off * 1.04);\n"
            + "  half4 c4 = content.eval(p - off * 1.08);\n"
            + "  half r = c2.r * 0.2 + c3.r * 0.35 + c4.r * 0.45;\n"
            + "  half g = c1.g * 0.25 + c2.g * 0.5 + c3.g * 0.25;\n"
            + "  half b = c0.b * 0.45 + c1.b * 0.35 + c2.b * 0.2;\n"
            + "  half a = c2.a;\n"
            + "  half lift = half(crest * fade * inside * strength * 0.03);\n"
            + "  return half4(min(r + lift, a), min(g + lift, a), min(b + lift, a), a);\n"
            + "}\n";

    private void show(String device, ActionIconDrawable.Kind kind) {
        if (showing != null) {
            View old = showing;
            old.animate().cancel();
            host.removeView(old);
        }
        float density = context.getResources().getDisplayMetrics().density;
        int accent = com.flowify.ettea.settings.PanelStyle.COL_ACCENT | 0xFF000000;
        int height = dp(density, 64);
        // A chip: both ends full semicircles. The 40dp badge with 12dp padding sits concentric
        // with the left end.
        float radius = height / 2f;
        int top = statusBarHeight() + dp(density, 12);

        // Frosted glass: the screen strip behind the card, blurred, drawn as the card's own
        // background (child layers would set the card's width), under a tint and a hairline.
        GlassBackground glass = new GlassBackground(radius, accent, density);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(density, 12), 0, dp(density, 26), 0);
        card.setBackground(glass);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        FrameLayout badge = new FrameLayout(context);
        badge.setClipChildren(false);
        View ring = new View(context);
        GradientDrawable ringBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(accent, 0xFFFFFFFF, 0.35f), accent});
        ringBg.setShape(GradientDrawable.OVAL);
        ring.setBackground(ringBg);
        badge.addView(ring, new FrameLayout.LayoutParams(dp(density, 40), dp(density, 40), Gravity.CENTER));
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(new ActionIconDrawable(kind, readableOn(accent), density, 19));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(density, 40), dp(density, 40), Gravity.CENTER));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(density, 40), dp(density, 40));
        badgeLp.rightMargin = dp(density, 12);
        card.addView(badge, badgeLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView caption = new TextView(context);
        caption.setText(strings().get("lyrics_device_now_playing_on", "Now playing on"));
        caption.setTextColor(0xB3FFFFFF);
        caption.setTextSize(11);
        caption.setLetterSpacing(0.04f);
        TextView name = new TextView(context);
        name.setText(device);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(density, 230));
        java.lang.ref.WeakReference<android.graphics.Typeface> font = com.flowify.ettea.References.beautifulFont;
        android.graphics.Typeface face = font == null ? null : font.get();
        name.setTypeface(face == null ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.create(face, android.graphics.Typeface.BOLD));
        if (face != null) caption.setTypeface(face);
        texts.addView(caption);
        texts.addView(name);
        card.addView(texts);

        // Three bars bouncing in the accent colour: something is playing over there.
        EqualizerView bars = new EqualizerView(context, blend(accent, 0xFFFFFFFF, 0.25f), density);
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(dp(density, 16), dp(density, 16));
        barsLp.leftMargin = dp(density, 14);
        card.addView(bars, barsLp);

        Sheen sheen = new Sheen(accent);
        card.getOverlay().add(sheen);
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            sheen.setBounds(0, 0, r - l, b - t);
            glass.setOffset(l, host.getWidth());
            v.invalidateOutline();
        });

        captureBackdrop(glass, card, top, height);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = top;
        host.addView(card, lp);
        showing = card;

        View[] parts = {caption, name};
        if (!com.flowify.ettea.ui.Motion.animationsEnabled()) {
            main.postDelayed(() -> dismiss(card, parts, badge, false), HOLD_MS);
            return;
        }
        // The card drops in on a spring while a ripple runs through the screen from where it
        // lands; then the badge pops, the words slide in and a sheen crosses.
        card.setAlpha(0f);
        card.setTranslationY(-dp(density, 28));
        card.setScaleX(0.86f);
        card.setScaleY(0.86f);
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        // After the card's first layout: the ripple starts where the card actually is.
        card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                ripple(card, 1f);
                return true;
            }
        });
        badge.setScaleX(0.3f);
        badge.setScaleY(0.3f);
        badge.setRotation(-40f);
        badge.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(140).setDuration(520)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlpha(0f);
            parts[i].setTranslationX(-dp(density, 12));
            parts[i].animate().alpha(1f).translationX(0f).setStartDelay(240 + i * 80L).setDuration(400)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        bars.setAlpha(0f);
        bars.animate().alpha(1f).setStartDelay(420).setDuration(300).start();
        main.postDelayed(() -> {
            if (showing == card) sheen.sweep(card);
        }, 700);
        main.postDelayed(() -> dismiss(card, parts, badge, true), HOLD_MS);
    }

    /**
     * Copies the screen strip the card will sit on (before the card is added) at low resolution
     * and blurs it in software. PixelCopy reads the window's real pixels, so the shader-drawn
     * background and the blurred lyric rows come out as seen; without it the card is tinted glass.
     */
    private void captureBackdrop(GlassBackground glass, View card, int top, int height) {
        if (!(context instanceof android.app.Activity) || host.getWidth() <= 0) return;
        android.view.Window window = ((android.app.Activity) context).getWindow();
        if (window == null) return;
        int[] at = new int[2];
        host.getLocationInWindow(at);
        android.graphics.Rect src = new android.graphics.Rect(at[0], at[1] + top,
                at[0] + host.getWidth(), at[1] + top + height);
        android.graphics.Bitmap strip;
        try {
            strip = android.graphics.Bitmap.createBitmap(Math.max(1, src.width() / 8),
                    Math.max(1, src.height() / 8), android.graphics.Bitmap.Config.ARGB_8888);
        } catch (Throwable oom) {
            return;
        }
        try {
            android.view.PixelCopy.request(window, src, strip, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    strip.recycle();
                    return;
                }
                boxBlur(strip, 2, 3);
                glass.setBackdrop(strip);
                card.invalidate();
            }, main);
        } catch (Throwable unsupported) {
            strip.recycle();
        }
    }

    /** Separable box blur, {@code passes} times (three passes approximate a Gaussian). */
    static void boxBlur(android.graphics.Bitmap bitmap, int radius, int passes) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < passes; pass++) {
            blurLine(px, tmp, w, h, radius, true);
            blurLine(tmp, px, w, h, radius, false);
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w;
        int length = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int c = horizontal ? in[line * w + j] : in[j * w + line];
                    a += c >>> 24;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                int value = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                if (horizontal) out[line * w + i] = value;
                else out[i * w + line] = value;
            }
        }
    }

    /**
     * Runs the ripple through every full-size view of the screen behind the card (not the card).
     * AGSL needs Android 13; earlier versions just get the card's own spring.
     */
    private void ripple(View card, float strength) {
        if (android.os.Build.VERSION.SDK_INT < 33 || card.getWidth() <= 0) return;
        final java.util.List<View> layers = new java.util.ArrayList<>();
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child == card || child.getVisibility() != View.VISIBLE) continue;
            if (child.getWidth() >= host.getWidth() / 2 && child.getHeight() >= host.getHeight() / 2) {
                layers.add(child);
            }
        }
        if (layers.isEmpty()) return;
        final android.graphics.RuntimeShader shader;
        try {
            shader = new android.graphics.RuntimeShader(RIPPLE_AGSL);
        } catch (Throwable unsupported) {
            return;
        }
        // The pill's resting bounds (its entrance scale and offset are still animating).
        float centerX = card.getLeft() + card.getWidth() / 2f;
        float centerY = card.getTop() + card.getHeight() / 2f;
        float halfW = card.getWidth() / 2f;
        float halfH = card.getHeight() / 2f;
        // The top band only: the swell grows about a third of the screen out from the pill.
        float reach = host.getHeight() * 0.34f;
        float density = context.getResources().getDisplayMetrics().density;
        android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(3600);
        // Leaves the pill steadily, then slows to a drift as it fades.
        run.setInterpolator(new android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
        run.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Swells in over the first fifth, holds, then ebbs away - no pop at either end.
            float rise = Math.min(1f, t / 0.2f);
            float envelope = rise * rise * (3f - 2f * rise) * (1f - t * t);
            for (View layer : layers) {
                shader.setFloatUniform("center", centerX - layer.getLeft(), centerY - layer.getTop());
                shader.setFloatUniform("halfSize", halfW, halfH);
                shader.setFloatUniform("corner", halfH);
                shader.setFloatUniform("radius", reach * t);
                shader.setFloatUniform("reach", reach);
                shader.setFloatUniform("width", (60f + 70f * t) * density);
                shader.setFloatUniform("strength", strength * envelope * density);
                layer.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
            }
        });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                for (View layer : layers) layer.setRenderEffect(null);
            }

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                for (View layer : layers) layer.setRenderEffect(null);
            }
        });
        run.start();
    }

    /** The words go, the card shrinks back up and fades. */
    private void dismiss(View card, View[] parts, View badge, boolean motion) {
        if (showing != card) return;
        Runnable remove = () -> {
            host.removeView(card);
            if (showing == card) showing = null;
        };
        if (!motion) {
            remove.run();
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        for (View part : parts) part.animate().alpha(0f).setStartDelay(0).setDuration(160).start();
        badge.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setStartDelay(60).setDuration(220).start();
        card.animate().alpha(0f).translationY(-dp(density, 22)).scaleX(0.9f).scaleY(0.9f)
                .setStartDelay(120).setDuration(340)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                .withEndAction(remove).start();
    }

    /** Frosted glass: blurred backdrop strip (placed under the card), tint, top light, hairline. */
    private static final class GlassBackground extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint image = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final int accent;
        private android.graphics.Bitmap backdrop;
        private int offsetX;
        private int screenWidth;

        GlassBackground(float radius, int accent, float density) {
            this.radius = radius;
            this.accent = accent;
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, density));
        }

        void setBackdrop(android.graphics.Bitmap bitmap) {
            backdrop = bitmap;
            invalidateSelf();
        }

        void setOffset(int cardLeft, int width) {
            offsetX = cardLeft;
            screenWidth = width;
            invalidateSelf();
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            android.graphics.RectF box = new android.graphics.RectF(b);
            if (backdrop != null && !backdrop.isRecycled() && screenWidth > 0) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(backdrop, null, new android.graphics.RectF(-offsetX, b.top,
                        screenWidth - offsetX, b.bottom), image);
                canvas.restore();
            }
            // Dark glass with a breath of the accent on the leading side.
            fill.setShader(new android.graphics.LinearGradient(b.left, 0, b.right, 0,
                    new int[]{(blend(0xFF0E0E10, accent, 0.35f) & 0x00FFFFFF) | 0x8C000000,
                            0x7A0E0E10, 0x8C0E0E10}, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, radius, radius, fill);
            // Light from above: a soft top highlight.
            fill.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f,
                    0x22FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, radius, radius, fill);
            fill.setShader(null);
            float inset = stroke.getStrokeWidth() / 2f;
            box.inset(inset, inset);
            stroke.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.bottom,
                    0x40FFFFFF, 0x14FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, radius - inset, radius - inset, stroke);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Three bars bouncing out of phase, like a "now playing" indicator. */
    private static final class EqualizerView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final long start = android.os.SystemClock.uptimeMillis();

        EqualizerView(Context context, int color, float density) {
            super(context);
            this.density = density;
            paint.setColor(color);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float bar = w / 5f;
            float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
            float[] phase = {0f, 1.7f, 3.1f};
            for (int i = 0; i < 3; i++) {
                float level = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 7.5f + phase[i]));
                float left = i * bar * 2f;
                canvas.drawRoundRect(left, h - h * level, left + bar, h, bar / 2f, bar / 2f, paint);
            }
            if (isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }

    /** A soft diagonal band of light that crosses the card once. */
    private static final class Sheen extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int tint;
        private float at = -1f;

        Sheen(int accent) {
            tint = blend(accent, 0xFFFFFFFF, 0.7f) & 0x00FFFFFF;
        }

        void sweep(View host) {
            android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(900);
            run.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            run.addUpdateListener(a -> {
                at = (float) a.getAnimatedValue();
                host.invalidate();
            });
            run.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    at = -1f;
                    host.invalidate();
                }
            });
            run.start();
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            if (at < 0f) return;
            android.graphics.Rect b = getBounds();
            float band = b.height() * 1.4f;
            float x = -band + (b.width() + band * 2f) * at;
            paint.setShader(new android.graphics.LinearGradient(x - band, 0, x + band, b.height() * 0.5f,
                    new int[]{tint, tint | 0x48000000, tint}, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static int readableOn(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 170 ? 0xFF111114 : 0xFFFFFFFF;
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private int statusBarHeight() {
        android.view.WindowInsets insets = host.getRootWindowInsets();
        if (insets != null && android.os.Build.VERSION.SDK_INT >= 30) {
            // Ignoring visibility: the lyrics screen may hide the bar, but it comes back on a
            // swipe (or when "Hide status bar" is off mid-session) and the card must not sit
            // under the clock then.
            return insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top;
        }
        return insets == null ? 0 : insets.getSystemWindowInsetTop();
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }
}
