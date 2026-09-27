package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Handles lyric scroll touch hold, optional tap-to-seek, and long-press-to-share gestures.
 *
 * <p>With {@link Settings#ACCIDENTAL_TOUCH_GUARD} on, a touch only counts as a tap (seek, like)
 * when it looks deliberate, judged by distance rather than time (a fast real tap and a brush can
 * last equally short): the finger never travelled more than touch slop from where it landed -
 * the farthest point of the path, so a finger that slides off and back is not a tap - it used
 * one finger, and it did not land on a list that was still moving. The platform convention is
 * that a touch which stops a fling only stops it; here it used to also seek to the line it hit.
 */
public final class LyricsTapSeekHandler implements View.OnTouchListener {
    /** A down this soon after the list last moved under the user's own scroll caught it. */
    static final long SCROLL_CATCH_MS = 120L;
    private final Context context;
    private final SpotifyPlusConfig config;
    private final HoldCallback holdCallback;
    private final TouchCallback touchCallback;
    private final SeekCallback seekCallback;
    private final LongPressCallback longPressCallback;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = this::fireLongPress;
    private float scrollDownY;
    private long scrollDownAtMs;
    private long lastTapAtMs;
    private float lastTapY;
    private float lastTapX;
    private float scrollDownX;
    private DoubleTapCallback doubleTapCallback;
    /** A single-tap seek waiting to see whether a second tap makes it a double tap. */
    private final Runnable pendingSeek = this::runPendingSeek;
    private float pendingSeekY;
    private boolean longPressFired;
    private long lastUserScrollAtMs;
    /** This gesture landed on a moving list, or grew a second finger: never a tap. */
    private boolean notATap;
    /** Farthest the finger has been from its down point during this gesture, in px. */
    private float maxTravelPx;

    public LyricsTapSeekHandler(
            Context context,
            SpotifyPlusConfig config,
            HoldCallback holdCallback,
            TouchCallback touchCallback,
            SeekCallback seekCallback
    ) {
        this(context, config, holdCallback, touchCallback, seekCallback, null);
    }

    public LyricsTapSeekHandler(
            Context context,
            SpotifyPlusConfig config,
            HoldCallback holdCallback,
            TouchCallback touchCallback,
            SeekCallback seekCallback,
            LongPressCallback longPressCallback
    ) {
        this.context = context;
        this.config = config;
        this.holdCallback = holdCallback;
        this.touchCallback = touchCallback;
        this.seekCallback = seekCallback;
        this.longPressCallback = longPressCallback;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scrollDownY = event.getY();
            scrollDownX = event.getX();
            scrollDownAtMs = SystemClock.elapsedRealtime();
            longPressFired = false;
            maxTravelPx = 0f;
            notATap = guarded() && scrollDownAtMs - lastUserScrollAtMs < SCROLL_CATCH_MS;
            armLongPress();
            if (touchCallback != null) touchCallback.touching(true);
            hold();
        } else if (action == MotionEvent.ACTION_MOVE) {
            maxTravelPx = Math.max(maxTravelPx, (float) Math.hypot(
                    event.getX() - scrollDownX, event.getY() - scrollDownY));
            // Refresh the "last manual scroll" timestamp on every move, not just the initial
            // down - otherwise a drag held longer than the auto-resume cooldown leaves that
            // timestamp stale from the start of the gesture, so the moment the finger lifts the
            // cooldown already reads as elapsed and auto-resume can snap back with no grace
            // period at all, which feels like it's fighting an in-progress touch.
            if (Math.abs(event.getY() - scrollDownY) >= dp(10)
                    || (guarded() && maxTravelPx >= touchSlop())) {
                cancelLongPress();
                // A scroll right after a tap is not the tap's seek any more.
                longPressHandler.removeCallbacks(pendingSeek);
            }
            if (touchCallback != null) touchCallback.touching(true);
            hold();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // A second finger makes this a pinch or a grip, not a tap or a long press.
            if (guarded()) {
                notATap = true;
                cancelLongPress();
                longPressHandler.removeCallbacks(pendingSeek);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            cancelLongPress();
            if (touchCallback != null) touchCallback.touching(false);
            if (longPressFired) return true;
            float dy = Math.abs(event.getY() - scrollDownY);
            long held = SystemClock.elapsedRealtime() - scrollDownAtMs;
            boolean guarded = guarded();
            float travel = Math.max(maxTravelPx, (float) Math.hypot(
                    event.getX() - scrollDownX, event.getY() - scrollDownY));
            boolean deliberate = !guarded || (!notATap && travel < touchSlop());
            if (!deliberate) {
                // A slide or a scroll catch must not arm a double tap either.
                lastTapAtMs = 0;
                return false;
            }
            if (dy < dp(10) && held < 600) {
                String mode = config == null ? "" : config.get(Settings.TAP_SEEK_MODE);
                if (doubleTapCallback != null && config != null
                        && Boolean.TRUE.equals(config.get(Settings.DOUBLE_TAP_LIKE))) {
                    // Double tap likes. A single tap still seeks in "Single tap" mode; the tap
                    // that completes a double tap does not seek again.
                    long now = SystemClock.elapsedRealtime();
                    long window = ViewConfiguration.getDoubleTapTimeout();
                    if (now - lastTapAtMs < window && Math.abs(event.getY() - lastTapY) < dp(40)
                            && (!guarded || Math.abs(event.getX() - lastTapX) < dp(40))) {
                        lastTapAtMs = 0;
                        longPressHandler.removeCallbacks(pendingSeek);
                        doubleTapCallback.onDoubleTap(event.getX(), event.getY());
                    } else {
                        lastTapAtMs = now;
                        lastTapY = event.getY();
                        lastTapX = event.getX();
                        if ("Single tap".equalsIgnoreCase(mode)) {
                            // Seek only once no second tap came: a double tap likes, it never
                            // also seeks.
                            pendingSeekY = event.getY();
                            longPressHandler.removeCallbacks(pendingSeek);
                            longPressHandler.postDelayed(pendingSeek, window);
                        }
                    }
                } else if ("Double tap".equalsIgnoreCase(mode)) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastTapAtMs < 350 && Math.abs(event.getY() - lastTapY) < dp(20)
                            && (!guarded || Math.abs(event.getX() - lastTapX) < dp(40))) {
                        seek(event.getY());
                        lastTapAtMs = 0;
                    } else {
                        lastTapAtMs = now;
                        lastTapY = event.getY();
                        lastTapX = event.getX();
                    }
                } else if ("Single tap".equalsIgnoreCase(mode)) {
                    seek(event.getY());
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelLongPress();
            if (touchCallback != null) touchCallback.touching(false);
        }
        return false;
    }

    /** The list moved under the user's own scroll or fling (not a programmatic follow scroll);
     *  a down landing right after this caught the list rather than tapped a line. */
    public void noteUserScroll() {
        lastUserScrollAtMs = SystemClock.elapsedRealtime();
    }

    private boolean guarded() {
        try {
            return config != null && Boolean.TRUE.equals(config.get(Settings.ACCIDENTAL_TOUCH_GUARD));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int touchSlop() {
        return context == null ? dp(8) : ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** Receives double taps when "Double-tap to like" is on (they no longer seek then). */
    public void setDoubleTapCallback(DoubleTapCallback callback) {
        doubleTapCallback = callback;
    }

    /** The current gesture already fired its long press (it is still held, or just lifted). */
    public boolean longPressFired() {
        return longPressFired;
    }

    private void armLongPress() {
        if (longPressCallback == null) return;
        if (config == null || !Boolean.TRUE.equals(config.get(Settings.LONG_PRESS_SHARE))) return;
        longPressHandler.removeCallbacks(longPressRunnable);
        longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelLongPress() {
        longPressHandler.removeCallbacks(longPressRunnable);
    }

    private void fireLongPress() {
        longPressFired = true;
        if (longPressCallback != null) longPressCallback.onLongPress(scrollDownY);
    }

    private void runPendingSeek() {
        seek(pendingSeekY);
    }

    private void hold() {
        if (holdCallback != null) holdCallback.holdUntil(SystemClock.elapsedRealtime() + 3500);
    }

    private void seek(float y) {
        if (seekCallback != null) seekCallback.seekAt(y);
    }

    private int dp(int value) {
        float density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    public interface HoldCallback {
        void holdUntil(long untilMs);
    }

    public interface TouchCallback {
        void touching(boolean touching);
    }

    public interface SeekCallback {
        void seekAt(float y);
    }

    public interface DoubleTapCallback {
        void onDoubleTap(float x, float y);
    }

    public interface LongPressCallback {
        void onLongPress(float y);
    }
}
