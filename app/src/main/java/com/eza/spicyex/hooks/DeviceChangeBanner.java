package com.eza.spicyex.hooks;

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

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.ui.ActionIconDrawable;

/**
 * Says so on the lyrics screen when playback moves to another device: a glass pill drops in at
 * the top - the device's icon in a ring of the album's colour, "Now playing on", its name - and
 * lifts away a moment later. Watches only while the lyrics screen is on screen
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
    private View showing;
    private boolean running;

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

    private void check() {
        PlaybackBridge bridge = PlaybackBridge.current;
        if (bridge == null) return;
        String device = currentDevice(bridge);
        if (device == null) return;
        String previous = lastDevice;
        lastDevice = device;
        if (previous == null || previous.equals(device)) return;
        if (!Boolean.TRUE.equals(config.get(Settings.DEVICE_CHANGE_BANNER))) return;
        show(device, !device.equals(phoneLabel()));
    }

    /** The device playing now, by name where it can be known; null before playback is known. */
    private String currentDevice(PlaybackBridge bridge) {
        if (!bridge.playbackIsRemote()) return bridge.sessionKnown() ? phoneLabel() : null;
        String routed = ConnectRouteHandoff.selectedConnectRouteName(context);
        if (routed != null) return routed;
        if (SpotifyConnectHook.webPlayerCarrying()) return "Spicy Connect";
        return strings().get("lyrics_device_other", "Another device");
    }

    /** Read each time: the language can be changed while the lyrics screen stays open. */
    /** Debug agent: shows the banner for {@code device} as if playback had just moved there. */
    void preview(String device) {
        boolean phone = device == null || device.isEmpty();
        show(phone ? phoneLabel() : device, !phone);
    }

    private com.eza.spicyex.ui.SettingsUiStrings strings() {
        return com.eza.spicyex.ui.UiLanguage.strings(context, config.get(Settings.UI_LANGUAGE));
    }

    private String phoneLabel() {
        return strings().get("lyrics_device_phone", "This phone");
    }

    private void show(String device, boolean remote) {
        if (showing != null) {
            View old = showing;
            old.animate().cancel();
            host.removeView(old);
        }
        float density = context.getResources().getDisplayMetrics().density;
        int accent = com.eza.spicyex.settings.PanelStyle.COL_ACCENT | 0xFF000000;
        int height = dp(density, 52);
        // The pill opens out of a dot the size of its height, the way a Dynamic Island does: its
        // outline, which also clips the content and the shadow, grows out from the centre.
        final float[] open = {0f};
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(density, 8), 0, dp(density, 22), 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{blend(0xFF161618, accent, 0.22f) & 0xF0FFFFFF, 0xF0161618, 0xF0161618});
        bg.setCornerRadius(height / 2f);
        bg.setStroke(Math.max(1, dp(density, 1)), (accent & 0x00FFFFFF) | 0x55000000);
        pill.setBackground(bg);
        pill.setElevation(dp(density, 14));
        pill.setClipToOutline(true);
        pill.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                int visible = Math.round(h + (w - h) * Math.max(0f, open[0]));
                visible = Math.min(w, Math.max(h, visible));
                int left = (w - visible) / 2;
                outline.setRoundRect(left, 0, left + visible, h, h / 2f);
            }
        });

        FrameLayout badge = new FrameLayout(context);
        badge.setClipChildren(false);
        View ripple = new View(context);
        GradientDrawable rippleBg = new GradientDrawable();
        rippleBg.setShape(GradientDrawable.OVAL);
        rippleBg.setColor(0);
        rippleBg.setStroke(dp(density, 2), accent);
        ripple.setBackground(rippleBg);
        ripple.setAlpha(0f);
        badge.addView(ripple, new FrameLayout.LayoutParams(dp(density, 36), dp(density, 36), Gravity.CENTER));
        View ring = new View(context);
        GradientDrawable ringBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(accent, 0xFFFFFFFF, 0.25f), accent});
        ringBg.setShape(GradientDrawable.OVAL);
        ring.setBackground(ringBg);
        badge.addView(ring, new FrameLayout.LayoutParams(dp(density, 36), dp(density, 36), Gravity.CENTER));
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(new ActionIconDrawable(remote ? ActionIconDrawable.Kind.SPEAKER
                : ActionIconDrawable.Kind.SMARTPHONE, readableOn(accent), density, 18));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(density, 36), dp(density, 36), Gravity.CENTER));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(density, 36), dp(density, 36));
        badgeLp.rightMargin = dp(density, 12);
        pill.addView(badge, badgeLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView caption = new TextView(context);
        caption.setText(strings().get("lyrics_device_now_playing_on", "Now playing on"));
        caption.setTextColor(blend(accent, 0xFFFFFFFF, 0.55f));
        caption.setTextSize(11);
        TextView name = new TextView(context);
        name.setText(device);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(density, 240));
        java.lang.ref.WeakReference<android.graphics.Typeface> font = com.eza.spicyex.References.beautifulFont;
        android.graphics.Typeface face = font == null ? null : font.get();
        name.setTypeface(face == null ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.create(face, android.graphics.Typeface.BOLD));
        if (face != null) caption.setTypeface(face);
        texts.addView(caption);
        texts.addView(name);
        pill.addView(texts);

        // A band of light that crosses the pill once it is open.
        Sheen sheen = new Sheen(accent);
        pill.getOverlay().add(sheen);
        pill.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            sheen.setBounds(0, 0, r - l, b - t);
            v.invalidateOutline();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = statusBarHeight() + dp(density, 10);
        host.addView(pill, lp);
        showing = pill;

        View[] parts = {caption, name};
        if (!com.eza.spicyex.ui.Motion.animationsEnabled()) {
            open[0] = 1f;
            pill.invalidateOutline();
            main.postDelayed(() -> dismiss(pill, open, parts, badge, false), HOLD_MS);
            return;
        }
        // 1. A dot drops in.  2. It unfolds into the pill.  3. The badge pops, the words slide
        // in, the badge sends ripples out and a sheen crosses.
        pill.setAlpha(0f);
        pill.setTranslationY(-dp(density, 26));
        pill.setScaleX(0.6f);
        pill.setScaleY(0.6f);
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        badge.setScaleX(0.4f);
        badge.setScaleY(0.4f);
        badge.setRotation(-30f);
        badge.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(90).setDuration(460)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlpha(0f);
            parts[i].setTranslationX(-dp(density, 14));
            parts[i].animate().alpha(1f).translationX(0f).setStartDelay(300 + i * 70L).setDuration(360)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        android.animation.ValueAnimator unfold = android.animation.ValueAnimator.ofFloat(0f, 1f);
        unfold.setStartDelay(170);
        unfold.setDuration(560);
        unfold.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
        unfold.addUpdateListener(a -> {
            open[0] = (float) a.getAnimatedValue();
            pill.invalidateOutline();
        });
        unfold.start();
        for (int k = 0; k < 2; k++) {
            main.postDelayed(() -> {
                if (showing != pill) return;
                ripple.setScaleX(1f);
                ripple.setScaleY(1f);
                ripple.setAlpha(0.9f);
                ripple.animate().scaleX(1.9f).scaleY(1.9f).alpha(0f).setDuration(700)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            }, 420 + k * 420L);
        }
        main.postDelayed(() -> {
            if (showing == pill) sheen.sweep(pill);
        }, 640);
        main.postDelayed(() -> dismiss(pill, open, parts, badge, true), HOLD_MS);
    }

    /** The entrance in reverse: the words go, the pill folds back to a dot, the dot lifts away. */
    private void dismiss(LinearLayout pill, float[] open, View[] parts, View badge, boolean motion) {
        if (showing != pill) return;
        Runnable remove = () -> {
            host.removeView(pill);
            if (showing == pill) showing = null;
        };
        if (!motion) {
            remove.run();
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        for (View part : parts) part.animate().alpha(0f).setStartDelay(0).setDuration(160).start();
        badge.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setStartDelay(120).setDuration(220).start();
        android.animation.ValueAnimator fold = android.animation.ValueAnimator.ofFloat(open[0], 0f);
        fold.setStartDelay(80);
        fold.setDuration(320);
        fold.setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f));
        fold.addUpdateListener(a -> {
            open[0] = (float) a.getAnimatedValue();
            pill.invalidateOutline();
        });
        fold.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                pill.animate().alpha(0f).translationY(-dp(density, 24)).scaleX(0.5f).scaleY(0.5f)
                        .setDuration(200).setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f))
                        .withEndAction(remove).start();
            }
        });
        fold.start();
    }

    /** A soft diagonal band of light that crosses the pill once. */
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
            return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
        }
        return insets == null ? 0 : insets.getSystemWindowInsetTop();
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }
}
