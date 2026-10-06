package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;
import static com.eza.spicyex.hooks.NativeLyricsUtils.trackIdFromUri;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.R;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

/** Owns Spotify activity takeover, entry injection, keepalive, and native shell root mount. */
final class LyricsActivityTakeoverHook {
    private final java.util.Map<Activity, Boolean> miniPlayerWaitLogged =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";
    private static final int TAG_NATIVE_SPICY_ROOT = 0x53504C53; // SPLS
    private static final int TAG_EXTRA_LYRICS_BUTTON = 0x53504C58; // SPLX
    private static final int TAG_MINI_PLAYER_LYRICS_BUTTON = 0x53504C4D; // SPLM
    private static final int TAG_COVERED_SIBLINGS = 0x53504C43; // SPLC
    private static final long KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS = 3500L;
    private static final long[] EXTRA_INJECTION_DELAYS_MS = {450L, 950L, 1400L, 2400L};
    private static final long MINI_PLAYER_STEADY_RETRY_MS = 3000L;
    private static final long MINI_PLAYER_STEADY_RETRY_MAX_MS = 10 * 60 * 1000L;

    private static final WeakHashMap<Activity, Long> EXPLICIT_LYRICS_EXIT_UNTIL_MS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> KEEP_LYRICS_ACTIVITY_UNTIL_MS = new WeakHashMap<>();
    // Exact-class method lookups bypass subclass overrides, so one Activity-level
    // back hook is not enough (see ensureLegacyBackCoverage). Each declaring
    // class+method pair is covered once per process.
    private static final Set<String> LEGACY_BACK_COVERED =
            Collections.synchronizedSet(new HashSet<>());
    // A back key observed at dispatch means a shortly-following finish is
    // user-initiated even when no onBackPressed override ran (a view consumed
    // the key and finished directly). Consumed-without-finish presses expire.
    private static final WeakHashMap<Activity, Long> BACK_PRESS_UNTIL_MS = new WeakHashMap<>();
    private static final long BACK_PRESS_EXIT_WINDOW_MS = 2500L;
    private static final String NOW_PLAYING_ACTIVITY =
            "com.spotify.nowplaying.musicinstallation.NowPlayingActivity";
    // Armed by our own entry button right before launching the lyrics activity; consumed when we mount.
    // Spotify's native lyric card launches the same activity without arming this, so it stays native.
    private static volatile boolean takeoverArmed = false;
    // True while our native lyrics screen is the active session. Survives activity recreation
    // (rotation/config change) so we re-mount instead of falling back to Spotify's native screen;
    // cleared on an explicit exit or a real (non-config-change) destroy.
    private static volatile boolean nativeLyricsSessionActive = false;
    // Spotify's own requested orientation for the lyrics activity, read before we override it, so
    // an explicit exit hands the screen back exactly as Spotify left it.
    private static volatile int spotifyLyricsOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private static volatile boolean spotifyLyricsOrientationKnown;

    private final NativeSpicyLyricsHook host;
    private final NowPlayingInjector nowPlayingInjector;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Activity, ExtraInjectionRetry> extraInjectionRetries = new WeakHashMap<>();
    private final WeakHashMap<Activity, OnBackInvokedCallback> backCallbacks = new WeakHashMap<>();
    private WeakReference<Activity> resumedNowPlayingActivity = new WeakReference<>(null);
    private volatile boolean adInterruptedLyricsSession;
    private long lastAdStateCheckAtMs;
    private boolean adReturnCheckScheduled;
    private final Runnable adReturnCheck = this::reopenLyricsAfterAdIfNeeded;

    LyricsActivityTakeoverHook(NativeSpicyLyricsHook host, NowPlayingInjector nowPlayingInjector) {
        this.host = host;
        this.nowPlayingInjector = nowPlayingInjector;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookLyricsActivityLifecycle");
        XpHooks.findAfter(Activity.class, "onCreate", "takeover:Activity#onCreate", param -> {
            Activity activity = (Activity) param.thisObject;
            if (isLyricsFullscreenActivity(activity)) {
                // No system-back registration here: native Spotify screens stay untouched.
                // Registration happens only after takeover ownership is established.
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    XpLog.log(NativeSpicyLyricsHook.TAG
                            + " lyrics activity onCreate (takeover) " + activity.getClass().getName());
                }
                // else: opened via Spotify's native lyric card - leave Spotify's screen untouched.
            } else {
                scheduleExtraLyricsButtonInjection(activity);
            }
        }, android.os.Bundle.class);

        XpHooks.findAfter(Activity.class, "onResume", "takeover:Activity#onResume", param -> {
            Activity activity = (Activity) param.thisObject;
            References.setCurrentActivity(activity);
            if (isLyricsFullscreenActivity(activity)) {
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    ensureLegacyBackCoverage(activity);
                    // A resume does not remount the shell, but Spotify rebuilds its window
                    // state here - including the status bar the shell hid. Re-apply so the
                    // hide survives coming back from another app or a notification shade.
                    refreshShellStatusBar(activity);
                    remountShellIfConfigurationChanged(activity);
                }
                // else: native lyric card opened Spotify's own screen - do not take over.
            } else {
                rememberResumedActivity(activity);
                scheduleExtraLyricsButtonInjection(activity);
            }
        });

        XpHooks.findAfter(Activity.class, "onWindowFocusChanged", "takeover:Activity#onWindowFocusChanged",
                param -> {
                    if (!((boolean) param.args[0])) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!isLyricsFullscreenActivity(activity)) return;
                    // Explicit exit must not rearm while the old root still exists: focus
                    // transitions during the exit animation would otherwise remount/re-register.
                    if (isExplicitLyricsExit(activity)) {
                        unregisterSystemBackCallback(activity);
                        return;
                    }
                    if (activateNativeTakeover(activity)) {
                        ensureSystemBackCallback(activity);
                        ensureLegacyBackCoverage(activity);
                        mountNativeSpicyRoot(activity);
                    }
                }, boolean.class);

        XpHooks.findBefore(Activity.class, "onDestroy", "takeover:Activity#onDestroy", param -> {
            Activity activity = (Activity) param.thisObject;
            cancelExtraLyricsButtonInjection(activity);
            nowPlayingInjector.destroy(activity);
            if (!isLyricsFullscreenActivity(activity)) return;
            unregisterSystemBackCallback(activity);
            // Keep takeover state through every lyrics-page destroy. Spotify may report a
            // track-change rotation as a normal destroy, and the old content root can already
            // be detached before this hook runs. Non-lyrics onResume and explicit back clear it.
            removeNativeSpicyRoot(activity);
        });

        XpHooks.findAfter(Activity.class, "onPause", "takeover:Activity#onPause", param -> {
            Activity activity = (Activity) param.thisObject;
            forgetResumedActivity(activity);
            cancelExtraLyricsButtonInjection(activity);
            nowPlayingInjector.stop(activity); // quiet the now-playing card ticker
        });

        // Fires only when the user actually leaves (Home, recents, another app) - never for a
        // back press or our own finish(), so this is PIP_ON_CLOSE's real trigger. Back closing
        // the screen into a floating window instead was surprising, not a convenience.
        XpHooks.findAfter(Activity.class, "onUserLeaveHint", "takeover:Activity#onUserLeaveHint", param -> {
            Activity activity = (Activity) param.thisObject;
            if (!isLyricsFullscreenActivity(activity) || isExplicitLyricsExit(activity)) return;
            if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) return;
            host.openLyricsPipOnClose(activity);
        });

        XpHooks.findBefore(Activity.class, "onBackPressed", "takeover:Activity#onBackPressed", param -> {
            handleLegacyActivityBack((Activity) param.thisObject, param);
        });

        // Back keys reach views before any onBackPressed override runs. On the legacy path
        // (notably the API-32 Fold), offer Back-up to owned shell layers first; otherwise an open
        // editor sheet is skipped and Spotify exits the whole lyrics activity. When no shell
        // layer consumes it, record Back-down and leave Spotify's normal handling untouched.
        XpHooks.findBefore(Activity.class, "dispatchKeyEvent", "takeover:Activity#dispatchKeyEvent", param -> {
            handleActivityKeyForBack((Activity) param.thisObject, param);
        }, KeyEvent.class);

        XpHooks.findBefore(Activity.class, "finish", "takeover:Activity#finish", param -> {
            Activity activity = (Activity) param.thisObject;
            // A back press observed at dispatch promotes this finish to an
            // explicit exit (clearing the session) instead of suppressing it.
            if (isLyricsFullscreenActivity(activity) && consumeRecentBackPress(activity)) {
                markExplicitLyricsExit(activity);
            }
            if (!shouldKeepLyricsActivityOpen(activity)) return;
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " suppressed non-explicit lyrics activity finish to keep native renderer open");
            param.setResult(null);
        });

        // Spotify locks every screen to portrait at runtime - its manifest leaves all
        // activities orientation-unspecified, so the lock is a setRequestedOrientation()
        // call, and it is why the lyrics screen never rotated. While our shell owns the
        // screen the user's rotation setting is the only thing that should decide, so any
        // such call from Spotify's own code is replaced with the user's setting.
        XpHooks.findBefore(Activity.class, "setRequestedOrientation",
                "takeover:Activity#setRequestedOrientation", param -> {
                    Activity activity = (Activity) param.thisObject;
                    if (!nativeLyricsSessionActive || !isLyricsFullscreenActivity(activity)) return;
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Integer)) return;
                    param.args[0] = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
                }, int.class);
    }

    boolean isNativeSpicyEnabled(Activity activity) {
        try {
            return SpotifyPlusConfig.from(activity).get(Settings.NATIVE_SPICY_ENABLED);
        } catch (Throwable ignored) {
            return true;
        }
    }

    void markExplicitLyricsExit(Activity activity) {
        if (activity == null) return;
        nativeLyricsSessionActive = false; // user is leaving - end the session (next open stays native)
        adInterruptedLyricsSession = false;
        restoreLyricsOrientation(activity);
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            EXPLICIT_LYRICS_EXIT_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + 1200);
        }
        // End owned-back handling immediately so the exit animation/focus transition
        // cannot rearm it while the old root still exists. Destroy also unregisters.
        unregisterSystemBackCallback(activity);
    }

    private boolean isExplicitLyricsExit(Activity activity) {
        if (activity == null) return false;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Legacy back path shared by every onBackPressed declaring class (see
    // ensureLegacyBackCoverage). Legacy path (API <33, or predictive back
    // off): the dispatcher callback never runs, so finish explicitly instead
    // of relying on Spotify's onBackPressed to finish. Inactive native
    // screens fall through untouched.
    private void handleLegacyActivityBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null) return;
        if (!isLyricsFullscreenActivity(activity)) {
            if (nowPlayingInjector.consumeArtworkBack(activity)) param.setResult(null);
            return;
        }
        // An override that calls super would otherwise exit twice; the first
        // firing already marked the exit and finished.
        if (isExplicitLyricsExit(activity)) {
            param.setResult(null);
            return;
        }
        if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) return;
        param.setResult(null);
        // The lyrics screen closes its own layers first (share sheet, line picker, editor).
        // PIP_ON_CLOSE triggers from onUserLeaveHint (Home/recents), not from here: back is
        // the user asking to close the screen, not to float it.
        if (shellConsumesBack(activity)) return;
        markExplicitLyricsExit(activity);
        activity.finish();
    }

    // getDeclaredMethod lookups are exact-class only, so an onBackPressed
    // override in the concrete lyrics activity (or its androidx base) bypasses
    // the Activity-level hook while its finish() still hits our suppression:
    // back then does nothing and the screen looks stuck. Cover each declaring
    // class once, lazily, from an activity instance that carries the host
    // classloader. Misses fail silent and keep the old behavior.
    private void ensureLegacyBackCoverage(Activity activity) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        coverHostMethod(activity.getClass(), "onBackPressed");
        coverHostMethod(activity.getClass(), "dispatchKeyEvent");
        Class<?> component = XpReflect.findClassIfExists(
                "androidx.activity.ComponentActivity", activity.getClass().getClassLoader());
        coverHostMethod(component, "onBackPressed");
        coverHostMethod(component, "dispatchKeyEvent");
    }

    private void coverHostMethod(Class<?> clazz, String method) {
        if (clazz == null || clazz == Activity.class) return;
        String key = clazz.getName() + "#" + method;
        synchronized (LEGACY_BACK_COVERED) {
            if (!LEGACY_BACK_COVERED.add(key)) return;
        }
        try {
            Class<?>[] params = "dispatchKeyEvent".equals(method)
                    ? new Class<?>[]{KeyEvent.class} : new Class<?>[0];
            clazz.getDeclaredMethod(method, params);
        } catch (NoSuchMethodException missing) {
            return;
        }
        XpLog.log(NativeSpicyLyricsHook.TAG + " covering " + method + " override in " + clazz.getName());
        try {
            if ("dispatchKeyEvent".equals(method)) {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#dispatchKeyEvent-override",
                        (XpHooks.Before) param -> handleActivityKeyForBack(
                                (Activity) param.thisObject, param));
            } else {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#onBackPressed-override",
                        (XpHooks.Before) param -> handleLegacyActivityBack(
                                (Activity) param.thisObject, param));
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleActivityKeyForBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
        KeyEvent event = (KeyEvent) param.args[0];
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP
                && shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))
                && shellConsumesBack(activity)) {
            synchronized (BACK_PRESS_UNTIL_MS) {
                BACK_PRESS_UNTIL_MS.remove(activity);
            }
            param.setResult(true);
            return;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            synchronized (BACK_PRESS_UNTIL_MS) {
                BACK_PRESS_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + BACK_PRESS_EXIT_WINDOW_MS);
            }
        }
    }

    private static boolean consumeRecentBackPress(Activity activity) {
        if (activity == null) return false;
        synchronized (BACK_PRESS_UNTIL_MS) {
            Long until = BACK_PRESS_UNTIL_MS.remove(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Owned-overlay back only: registered after takeover ownership is established    // (active session), never on native Spotify screens. No host-dispatch fallback:
    // unregistered screens keep normal host handling untouched.
    private void ensureSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        if (!isLyricsFullscreenActivity(activity)) return;
        if (isExplicitLyricsExit(activity)) return;
        if (!nativeLyricsSessionActive) return;
        synchronized (backCallbacks) {
            if (backCallbacks.containsKey(activity)) return;
            OnBackInvokedCallback callback = () -> {
                // Registration implies ownership; re-check for races without ever
                // delegating to deprecated host handling from inside the callback.
                if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) {
                    unregisterSystemBackCallback(activity);
                    return;
                }
                // This callback outranks everything else on the dispatcher, so the layout editor
                // never saw a back press and back closed the whole lyrics screen mid-edit. Ask
                // the shell first: the editor's sheet closes, then the editor, then the screen.
                if (shellConsumesBack(activity)) return;
                markExplicitLyricsExit(activity);
                activity.finish();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            backCallbacks.put(activity, callback);
        }
    }

    private void unregisterSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (backCallbacks) {
            OnBackInvokedCallback callback = backCallbacks.remove(activity);
            if (callback != null) activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(callback);
        }
    }

    void markLyricsActivityKeepWindow(Activity activity) {
        if (activity == null) return;
        synchronized (KEEP_LYRICS_ACTIVITY_UNTIL_MS) {
            KEEP_LYRICS_ACTIVITY_UNTIL_MS.put(
                    activity,
                    SystemClock.elapsedRealtime() + KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS
            );
        }
    }

    /** Our lyrics left the player screen they were over: its own card and buttons come back. */
    void resumePlayerScreen(Activity activity) {
        scheduleExtraLyricsButtonInjection(activity);
    }

    private void rememberResumedActivity(Activity activity) {
        if (activity == null) return;
        if (NOW_PLAYING_ACTIVITY.equals(activity.getClass().getName())) {
            resumedNowPlayingActivity = new WeakReference<>(activity);
            scheduleAdReturnCheck();
        } else {
            resumedNowPlayingActivity = new WeakReference<>(null);
        }
    }

    private void forgetResumedActivity(Activity activity) {
        if (activity != null && resumedNowPlayingActivity.get() == activity) {
            resumedNowPlayingActivity = new WeakReference<>(null);
        }
    }

    /** Watches for an ad ending after Spotify has moved our active lyrics session away. */
    void onPlayerStateUpdate() {
        if (!nativeLyricsSessionActive) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAdStateCheckAtMs < 250L) return;
        lastAdStateCheckAtMs = now;
        SpotifyTrack track = host.getCurrentTrackSafely();
        if (track == null || track.uri == null || track.uri.isEmpty()) return;
        if (track.uri.startsWith("spotify:ad:")) {
            adInterruptedLyricsSession = true;
            return;
        }
        if (adInterruptedLyricsSession) scheduleAdReturnCheck();
    }

    private void scheduleAdReturnCheck() {
        if (!adInterruptedLyricsSession || adReturnCheckScheduled) return;
        adReturnCheckScheduled = true;
        mainHandler.postDelayed(adReturnCheck, 250L);
    }

    private void reopenLyricsAfterAdIfNeeded() {
        adReturnCheckScheduled = false;
        Activity activity = resumedNowPlayingActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SpotifyTrack track = host.getCurrentTrackSafely();
        if (track == null || track.uri == null || track.uri.isEmpty()) return;
        boolean adPlaying = track.uri.startsWith("spotify:ad:");
        if (!shouldReopenAfterAd(nativeLyricsSessionActive, adInterruptedLyricsSession,
                NOW_PLAYING_ACTIVITY.equals(activity.getClass().getName()), adPlaying)) return;
        if (launchNativeLyricsFullscreen(activity, false)) {
            adInterruptedLyricsSession = false;
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " restored native lyrics after Spotify returned from an ad to Now Playing");
        }
    }

    static boolean shouldReopenAfterAd(boolean sessionActive, boolean adInterrupted,
                                       boolean nowPlayingVisible, boolean adPlaying) {
        return sessionActive && adInterrupted && nowPlayingVisible && !adPlaying;
    }

    private void scheduleExtraLyricsButtonInjection(Activity activity) {
        if (activity == null) return;
        try {
            if (!isNativeSpicyEnabled(activity)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            cancelExtraLyricsButtonInjection(activity);
            ExtraInjectionRetry retry = new ExtraInjectionRetry(activity, decor);
            synchronized (extraInjectionRetries) {
                extraInjectionRetries.put(activity, retry);
            }
            retry.start();
            nowPlayingInjector.schedule(activity);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " schedule extra lyrics injection failed: " + t);
        }
    }

    private boolean injectExtraLyricsButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()
                    || isLyricsFullscreenActivity(activity)
                    || !isNativeSpicyEnabled(activity)) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            if (content.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return true;

            // The Share/Queue cluster (accessory_row) is an R8-obfuscated ConstraintLayout. Add the
            // entry button to its parent and position it into the empty footer space after layout.
            // Looked up first, by its resource id: this runs every few seconds for minutes in the
            // single main activity (the mini player alone passes the now-playing check), and the
            // name-based scan plus the now-playing check walked the whole view tree each time.
            View rowView = accessoryRow(activity, content);
            if (rowView == null || !rowView.isShown() || rowView.getWidth() == 0) return false;
            if (!isLikelyNowPlayingScreen(activity, content)) return false;
            ViewGroup buttonHost = rowView.getParent() instanceof ViewGroup ? (ViewGroup) rowView.getParent() : null;
            if (buttonHost == null) return false;
            if (buttonHost.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return true;
            int side = rowView.getHeight() > 0 ? rowView.getHeight() : dp(48);
            View button = createExtraLyricsRowButton(activity);
            buttonHost.addView(button, new ViewGroup.LayoutParams(side, side));
            button.setTranslationX((buttonHost.getWidth() - side) / 2f);
            button.setTranslationY(rowView.getTop() + (rowView.getHeight() - side) / 2f);
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " inserted Extra lyrics ♪ centered in footer in " + activity.getClass().getName());
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " inject extra lyrics button failed: " + t);
            return false;
        }
    }

    private void cancelExtraLyricsButtonInjection(Activity activity) {
        ExtraInjectionRetry retry;
        synchronized (extraInjectionRetries) {
            retry = extraInjectionRetries.remove(activity);
        }
        if (retry != null) retry.cancel();
    }

    private final class ExtraInjectionRetry
            implements Runnable, ViewTreeObserver.OnPreDrawListener {
        private final Activity activity;
        private final View decor;
        private int attempt;
        private int preDrawFrames;
        private long steadyElapsedMs;
        private boolean cancelled;
        private boolean extraDoneOnce;
        private boolean listening;
        private View extraButton;
        private View miniPlayerButton;

        ExtraInjectionRetry(Activity activity, View decor) {
            this.activity = activity;
            this.decor = decor;
        }

        void start() {
            if (ApplePlayerStyler.isPlayerScreen(activity)) {
                decor.getViewTreeObserver().addOnPreDrawListener(this);
                listening = true;
            }
            postNext();
        }

        void postNext() {
            if (cancelled) return;
            if (attempt < EXTRA_INJECTION_DELAYS_MS.length) {
                decor.postDelayed(this, EXTRA_INJECTION_DELAYS_MS[attempt++]);
                return;
            }
            // injectExtraLyricsButton's target (accessory_row) is only reached post-playback
            // (NowPlayingActivity), so it's always ready within this burst. The mini-player bar
            // lives on every screen but stays uninflated/GONE until the very first track starts
            // playing in this activity's lifetime, which can happen long after this burst - so
            // once the burst is spent, keep polling steadily (bounded, not forever) instead of
            // giving up permanently.
            if (activity.isFinishing() || steadyElapsedMs >= MINI_PLAYER_STEADY_RETRY_MAX_MS) return;
            steadyElapsedMs += MINI_PLAYER_STEADY_RETRY_MS;
            decor.postDelayed(this, MINI_PLAYER_STEADY_RETRY_MS);
        }

        void cancel() {
            cancelled = true;
            decor.removeCallbacks(this);
            stopListening();
        }

        private void stopListening() {
            if (!listening) return;
            listening = false;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            if (cancelled || ++preDrawFrames % 4 != 1) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return true;
            boolean changed = false;
            if (!isDescendant(content, extraButton)) {
                injectExtraLyricsButton(activity);
                View current = content.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON);
                changed |= current != null && current != extraButton;
                extraButton = current;
            }
            if (!isDescendant(content, miniPlayerButton)) {
                injectMiniPlayerLyricsButton(activity);
                View current = content.findViewWithTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
                changed |= current != null && current != miniPlayerButton;
                miniPlayerButton = current;
            }
            return !changed;
        }

        @Override
        public void run() {
            if (cancelled) return;
            // Timer polling handles late initial inflation; the pre-draw monitor below keeps
            // the injected controls present if Spotify replaces the player hierarchy later.
            boolean extraDone = extraDoneOnce || injectExtraLyricsButton(activity);
            extraDoneOnce = extraDone;
            boolean miniPlayerDone = injectMiniPlayerLyricsButton(activity);
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content != null) {
                extraButton = content.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON);
                miniPlayerButton = content.findViewWithTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
            }
            if (extraDone && miniPlayerDone) {
                if (!listening) {
                    synchronized (extraInjectionRetries) {
                        if (extraInjectionRetries.get(activity) == this) {
                            extraInjectionRetries.remove(activity);
                        }
                    }
                }
                return;
            }
            postNext();
        }

        private boolean isDescendant(ViewGroup root, View view) {
            if (root == null || view == null) return false;
            View current = view;
            while (current != null) {
                if (current == root) return true;
                android.view.ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return false;
        }
    }

    /**
     * Adds a button to the persistent mini player that jumps straight to Spicy's fullscreen
     * lyrics, as a real child of Spotify's own layout - not a floating view positioned over it.
     * Returns true once handled (or once determined not applicable) so the retry loop can stop;
     * false to keep retrying while the player is not laid out yet.
     *
     * <p>Portrait bar ({@code now_playing_bar}, a MotionLayout): its MotionScene already reserves
     * a slot in the button chain, between the device icon and +, for {@code skippable_ad_view_stub}
     * - a stub that stays GONE except while a skippable ad plays. The button takes that slot's id,
     * so both ConstraintSets (default and large) place it there and the track title gives it room
     * on its own. A child the scene doesn't know would be zero-sized by MotionLayout instead.
     *
     * <p>Landscape side panel ({@code nowplayingmini_*}, a plain ConstraintLayout): the button
     * takes the + button's own constraints (end of the panel, centred on the title) one button
     * further in, and the title's end margin grows by its width.
     *
     * <p>Both read their layout params from Spotify's own layout XML through the container's
     * {@code generateLayoutParams(AttributeSet)}, so nothing depends on R8-renamed members. When a
     * Spotify update drops one of these ids or layouts, the button is simply not added there.
     */
    private boolean injectMiniPlayerLyricsButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || isLyricsFullscreenActivity(activity)
                    || !isNativeSpicyEnabled(activity)) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            View existingButton = content.findViewWithTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
            if (!SpotifyPlusConfig.from(activity).get(Settings.MINI_PLAYER_LYRICS_ICON)) {
                if (existingButton != null) removeMiniPlayerButton(existingButton); // toggled off live
                return true;
            }
            View bar = findViewByResourceEntryName(content, "now_playing_bar_layout");
            boolean barReady = bar instanceof ViewGroup && bar.isShown() && bar.getWidth() > 0
                    && findViewByResourceEntryName(bar, "play_pause_button") != null;
            View heart = null;
            ViewGroup panel = null;
            if (!barReady) {
                View sidebar = findViewByResourceEntryName(content, "now_playing_mini_container");
                if (sidebar != null && sidebar.isShown() && sidebar.getWidth() > 0) {
                    heart = findViewByResourceEntryName(sidebar, "animated_heart_button");
                    if (heart != null && heart.getParent() instanceof ViewGroup) {
                        panel = (ViewGroup) heart.getParent();
                    }
                }
            }
            if (existingButton != null) {
                // Still in the player on screen: done. In one that was replaced (rotation, a
                // rebuilt bar), it goes and a new one is added below.
                android.view.ViewParent parent = existingButton.getParent();
                if ((barReady && parent == bar) || (panel != null && parent == panel)) return true;
                removeMiniPlayerButton(existingButton);
            }
            if (!barReady && panel == null) {
                // Retried on a timer until the player lays out: say so once per screen, not per try.
                if (miniPlayerWaitLogged.put(activity, Boolean.TRUE) == null) {
                    XpLog.log(NativeSpicyLyricsHook.TAG
                            + " mini player: not laid out yet in " + activity.getClass().getName());
                }
                return false;
            }
            boolean added = barReady
                    ? addToBarSlot(activity, (ViewGroup) bar)
                    : addToSidePanel(activity, panel, heart);
            XpLog.log(NativeSpicyLyricsHook.TAG + " mini player lyrics button "
                    + (added ? "added to " : "has no slot in ")
                    + (barReady ? "now_playing_bar" : "side panel") + " in "
                    + activity.getClass().getName());
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " inject mini player lyrics button failed: " + t);
            return true; // don't retry forever on a real failure
        }
    }

    private static final String BAR_SLOT_ID = "skippable_ad_view_stub";

    private boolean addToBarSlot(Activity activity, ViewGroup bar) {
        int slotId = spotifyId(activity, BAR_SLOT_ID);
        if (slotId == 0) return false;
        ViewGroup.LayoutParams params = layoutParamsFromSpotifyLayout(activity, bar,
                new String[]{"now_playing_bar"}, slotId);
        if (params == null) return false;
        View button = createMiniPlayerLyricsButton(activity);
        button.setId(slotId);
        // Last child: Spotify's own findViewById still reaches its stub first, while the
        // MotionLayout's id lookup (which the chain's neighbours resolve through) gets the button.
        bar.addView(button, params);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) ->
                yieldBarSlotToAd(bar, button, slotId));
        return true;
    }

    /**
     * While a skippable ad shows its own view in the slot, the button steps out of it; once the
     * ad's view is gone it comes back, re-added so the slot's id resolves to it again.
     */
    private static void yieldBarSlotToAd(ViewGroup bar, View button, int slotId) {
        if (button.getParent() != bar) return;
        boolean adShowing = false;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child != button && child.getId() == slotId && child.getVisibility() == View.VISIBLE
                    && !(child instanceof android.view.ViewStub)) {
                adShowing = true;
                break;
            }
        }
        if (adShowing) {
            if (button.getVisibility() != View.GONE) button.setVisibility(View.GONE);
        } else if (button.getVisibility() == View.GONE) {
            bar.post(() -> {
                if (button.getParent() != bar) return;
                ViewGroup.LayoutParams params = button.getLayoutParams();
                bar.removeView(button);
                button.setVisibility(View.VISIBLE);
                bar.addView(button, params);
            });
        }
    }

    private static final String[] SIDE_PANEL_LAYOUTS = {
            "nowplayingmini_default", "nowplayingmini_endlessfeed",
            "nowplayingmini_reinventfree", "nowplayingmini_ads"};

    private boolean addToSidePanel(Activity activity, ViewGroup panel, View heart) {
        int heartId = heart.getId();
        ViewGroup.LayoutParams params = layoutParamsFromSpotifyLayout(activity, panel,
                SIDE_PANEL_LAYOUTS, heartId);
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return false;
        View title = findViewByResourceEntryName(panel, "track_info_view");
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        // One + button further in from the panel's end, the same size as it.
        int heartWidth = heart.getWidth() > 0 ? heart.getWidth() : params.width;
        int side = params.width > 0 ? params.width : dp(40);
        margins.setMarginEnd(margins.getMarginEnd() + heartWidth);
        View button = createMiniPlayerLyricsButton(activity);
        // The panel's touch targets are larger than the bar's; the glyph matches the + inside them.
        int inset = Math.max(dp(9), Math.round(side * 0.28f));
        button.setPadding(inset, inset, inset, inset);
        button.setId(View.generateViewId());
        panel.addView(button, params);
        if (title != null && title.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams titleParams =
                    (ViewGroup.MarginLayoutParams) title.getLayoutParams();
            TITLE_MARGIN_BEFORE.put(button, titleParams.getMarginEnd());
            titleParams.setMarginEnd(titleParams.getMarginEnd() + side);
            title.setLayoutParams(titleParams);
        }
        return true;
    }

    private void removeMiniPlayerButton(View button) {
        android.view.ViewParent parent = button.getParent();
        if (!(parent instanceof ViewGroup)) return;
        Object titleMargin = TITLE_MARGIN_BEFORE.remove(button);
        if (titleMargin instanceof Integer) {
            View title = findViewByResourceEntryName((View) parent, "track_info_view");
            if (title != null && title.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams titleParams =
                        (ViewGroup.MarginLayoutParams) title.getLayoutParams();
                titleParams.setMarginEnd((Integer) titleMargin);
                title.setLayoutParams(titleParams);
            }
        }
        ((ViewGroup) parent).removeView(button);
    }

    private static int spotifyId(Activity activity, String name) {
        try {
            return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Layout params for a child of {@code parent}, read from the element with {@code elementId}
     * in the first of Spotify's {@code layouts} that has it - parsed by the container itself, so
     * ConstraintLayout's own constraint attributes come out as Spotify built them.
     */
    private static ViewGroup.LayoutParams layoutParamsFromSpotifyLayout(Activity activity,
            ViewGroup parent, String[] layouts, int elementId) {
        android.content.res.Resources res = activity.getResources();
        for (String name : layouts) {
            int layoutId = res.getIdentifier(name, "layout", activity.getPackageName());
            if (layoutId == 0) continue;
            try (android.content.res.XmlResourceParser parser = res.getLayout(layoutId)) {
                int event;
                while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                    if (parser.getAttributeResourceValue(ANDROID_NS, "id", 0) != elementId) continue;
                    return parent.generateLayoutParams(android.util.Xml.asAttributeSet(parser));
                }
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " mini player params from " + name
                        + " failed: " + t);
            }
        }
        return null;
    }

    /** The side panel title's end margin before the button made room, to put back. */
    private static final java.util.Map<View, Integer> TITLE_MARGIN_BEFORE = new java.util.WeakHashMap<>();

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private View createMiniPlayerLyricsButton(Activity activity) {
        ImageButton button = new ImageButton(activity);
        button.setTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
        button.setContentDescription("Open Spicy lyrics");
        // House mark (mic + sparkles), same as the footer entry button — not a Lucide glyph.
        NativeIconButtons.setModuleIcon(button, activity, R.drawable.ic_spicy_lyrics_page);
        button.setColorFilter(Color.rgb(232, 232, 238));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> launchNativeLyricsFullscreen(activity));
        return button;
    }

    private View createExtraLyricsRowButton(Activity activity) {
        ImageButton button = new ImageButton(activity);
        button.setTag(TAG_EXTRA_LYRICS_BUTTON);
        button.setContentDescription("Open Spicy lyrics");
        NativeIconButtons.setModuleIcon(button, activity, R.drawable.ic_spicy_lyrics_page);
        button.setColorFilter(Color.rgb(232, 232, 238));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> launchNativeLyricsFullscreen(activity));
        return button;
    }

    private int accessoryRowId;

    private View accessoryRow(Activity activity, View content) {
        if (accessoryRowId == 0) {
            accessoryRowId = activity.getResources().getIdentifier(
                    "accessory_row", "id", activity.getPackageName());
            if (accessoryRowId == 0) accessoryRowId = View.NO_ID;
        }
        if (accessoryRowId != View.NO_ID) return content.findViewById(accessoryRowId);
        return findViewByResourceEntryName(content, "accessory_row");
    }

    private View findViewByResourceEntryName(View root, String entryName) {
        if (root == null || isBlank(entryName)) return null;
        return ViewIds.findByEntry(root, entryName);
    }

    private boolean isLikelyNowPlayingScreen(Activity activity, View root) {
        String activityName = activity == null ? "" : activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (activityName.contains("settings") || activityName.contains("lyrics")) return false;
        if (activityName.contains("nowplaying") || activityName.contains("now_playing")) return true;
        if (hasVisibleClassNameContaining(root, "nowplaying")
                || hasVisibleClassNameContaining(root, "now_playing")
                || hasVisibleClassNameContaining(root, "com.spotify.nowplaying")) return true;
        SpotifyTrack track = host.getCurrentTrackSafely();
        return track != null
                && !isBlank(trackIdFromUri(track.uri))
                && containsVisibleText(root, track.title)
                && containsVisibleText(root, track.artist);
    }

    private boolean hasVisibleClassNameContaining(View root, String needleLower) {
        if (root == null || isBlank(needleLower)) return false;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            String name = view.getClass().getName().toLowerCase(Locale.ROOT);
            if (view.isShown() && name.contains(needleLower)) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    private boolean containsVisibleText(View root, String needle) {
        if (root == null || isBlank(needle)) return false;
        String normalizedNeedle = needle.trim().toLowerCase(Locale.ROOT);
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            if (view.isShown() && view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                if (text != null && text.toString().trim().toLowerCase(Locale.ROOT).contains(normalizedNeedle)) {
                    return true;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    boolean launchNativeLyricsFullscreen(Activity activity) {
        return launchNativeLyricsFullscreen(activity, true);
    }

    private boolean launchNativeLyricsFullscreen(Activity activity, boolean armTakeover) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (armTakeover) takeoverArmed = true;
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), LYRICS_FULLSCREEN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + (armTakeover
                    ? " launched native lyrics fullscreen (takeover armed) from Extra lyrics button"
                    : " reopened native lyrics fullscreen after ad"));
            return true;
        } catch (Throwable t) {
            if (armTakeover) takeoverArmed = false;
            XpLog.log(NativeSpicyLyricsHook.TAG + " launch native lyrics fullscreen failed: " + t);
            return false;
        }
    }

    private boolean consumeTakeoverArmed() {
        if (takeoverArmed) {
            takeoverArmed = false;
            return true;
        }
        return false;
    }

    private boolean activateNativeTakeover(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Stale-root reclaim must not resurrect a session the user just exited.
        if (isExplicitLyricsExit(activity)) return false;
        if (nativeLyricsSessionActive || hasNativeSpicyRoot(activity)) return true;
        if (!consumeTakeoverArmed()) return false;
        // Promote the one-shot entry signal before waiting for a mount-ready window. This is the
        // durable lifecycle signal carried across rotation and Spotify activity relaunches.
        nativeLyricsSessionActive = true;
        unlockLyricsOrientation(activity);
        return true;
    }

    /**
     * Lets the lyrics screen rotate. Spotify asks the activity for portrait the moment it
     * is created, which keeps the screen portrait whatever the phone does; the shell's own
     * landscape layout never gets a chance to engage. The user's rotation setting decides
     * instead (FULL_USER also honours a system-wide rotation lock). Spotify's own request is
     * remembered so an explicit exit can put the screen back the way Spotify left it, and the
     * {@code setRequestedOrientation} hook above keeps Spotify from re-locking it mid-session
     * (notably on the recreate a rotation triggers, which would otherwise ping-pong).
     */
    private void unlockLyricsOrientation(Activity activity) {
        if (activity == null) return;
        try {
            if (!spotifyLyricsOrientationKnown) {
                spotifyLyricsOrientation = activity.getRequestedOrientation();
                spotifyLyricsOrientationKnown = true;
            }
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " unlock lyrics orientation failed: " + t);
        }
    }

    /** Hands the orientation back to Spotify, once, when the user leaves our lyrics screen. */
    private void restoreLyricsOrientation(Activity activity) {
        if (!spotifyLyricsOrientationKnown) return;
        spotifyLyricsOrientationKnown = false;
        try {
            if (activity != null && !activity.isFinishing()
                    && !(Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                activity.setRequestedOrientation(spotifyLyricsOrientation);
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " restore lyrics orientation failed: " + t);
        }
    }

    private boolean isStayInLyricsEnabled(Activity activity) {
        try {
            return SpotifyPlusConfig.from(activity).get(Settings.STAY_IN_LYRICS);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // Pure back/finish decision matrix (unit-tested): user back must exit even while
    // track-change/rotation finishes stay suppressed; inactive native screens stay untouched.
    static boolean shouldInterceptLyricsBack(boolean sessionActive, boolean hasRoot) {
        return sessionActive || hasRoot;
    }

    static boolean shouldSuppressLyricsFinish(boolean stayInLyricsEnabled,
                                              boolean nativeSpicyEnabled,
                                              boolean sessionActive,
                                              boolean explicitExit) {
        if (!nativeSpicyEnabled) return false;
        if (!stayInLyricsEnabled) return false;
        if (!sessionActive) return false;
        return !explicitExit;
    }

    private boolean shouldKeepLyricsActivityOpen(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Spotify invalidates and finishes its fullscreen lyrics activity after some track
        // changes. During rotation that finish can happen before our recreated root mounts, so
        // root presence and short keep windows are not stable ownership signals. The takeover
        // session is stable; explicit back clears it before calling finish.
        boolean explicitExit;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            explicitExit = until != null && SystemClock.elapsedRealtime() <= until;
        }
        return shouldSuppressLyricsFinish(isStayInLyricsEnabled(activity),
                isNativeSpicyEnabled(activity), nativeLyricsSessionActive, explicitExit);
    }

    static boolean isLyricsFullscreenActivity(Activity activity) {
        return activity != null && LYRICS_FULLSCREEN_ACTIVITY.equals(activity.getClass().getName());
    }

    private void mountNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("mountNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            if (activity == null || activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            if (!isLyricsFullscreenActivity(activity)) return;
            if (isExplicitLyricsExit(activity)) {
                unregisterSystemBackCallback(activity);
                return;
            }
            if (!isNativeSpicyEnabled(activity)) {
                removeNativeSpicyRoot(activity);
                return;
            }
            // Captured before the flag flips below: true here means a lyrics session was already
            // active going into this call, i.e. this mount is a reattach after an orientation-
            // driven activity recreate, not the screen's first open this session.
            boolean rotationContinuation = nativeLyricsSessionActive;
            nativeLyricsSessionActive = true; // our screen owns this lyrics session (survives rotation)
            ensureSystemBackCallback(activity);

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " content root missing");
                return;
            }

            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                if (shell.matchesCurrentLayoutConfiguration()) {
                    shell.start();
                    if (existing.getAlpha() >= 1f) hideCoveredSiblings(content, existing);
                    ensureSystemBackCallback(activity);
                    return;
                }
                restoreCoveredSiblings(shell);
                shell.stop();
                content.removeView(existing);
            }

            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_NATIVE_SPICY_ROOT);
            // Rotation reattach: panel was already visible a moment ago, show it instantly
            // without fading in. Fresh open: start invisible and fade up to announce arrival.
            if (rotationContinuation) {
                root.setAlpha(1f);
                root.setTranslationY(0f);
            } else {
                root.setAlpha(0f);
                root.setTranslationY(NativeLyricsUtils.dp(24));
            }
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            markLyricsActivityKeepWindow(activity);
            root.start();
            if (!rotationContinuation) {
                root.animate().alpha(1f).translationY(0f).setDuration(260)
                        .withEndAction(() -> hideCoveredSiblings(content, root)).start();
            } else {
                hideCoveredSiblings(content, root);
            }
            XpLog.log(NativeSpicyLyricsHook.TAG + " mounted native Spicy renderer shell");
            Diagnostics.event("renderer", "mount_state",
                    Diagnostics.context("surface", "fullscreen", "mounted", "true"));
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " mount failed: " + t);
            Diagnostics.event("renderer", "mount_state", t,
                    Diagnostics.context("surface", "fullscreen", "mounted", "false"));
        }
    }

    private void remountShellIfConfigurationChanged(Activity activity) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        View root = content == null ? null : content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
        if (root instanceof NativeSpicyShellView
                && !((NativeSpicyShellView) root).matchesCurrentLayoutConfiguration()) {
            mountNativeSpicyRoot(activity);
        }
    }

    private void removeNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("removeNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                // Before any fade-out: the page underneath must be drawn again once the shell
                // stops covering it.
                restoreCoveredSiblings(shell);
                // Host activity may already be leaving for rotation or a track-driven recreate.
                // Do not rely on an exit animation callback from a detached window to stop the
                // shell; that callback can be skipped, leaving stale subscriptions alive.
                if (activity.isDestroyed() || activity.isFinishing() || activity.isChangingConfigurations()) {
                    shell.stop();
                    content.removeView(shell);
                    return;
                }
                shell.animate().alpha(0f).translationY(NativeLyricsUtils.dp(24)).setDuration(220).withEndAction(() -> {
                    try {
                        shell.stop();
                        content.removeView(shell);
                    } catch (Throwable t) {
                        XpLog.log(NativeSpicyLyricsHook.TAG + " remove animation cleanup failed: " + t);
                    }
                }).start();
                XpLog.log(NativeSpicyLyricsHook.TAG + " removed native Spicy shell");
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " remove failed: " + t);
        }
        // Rotation keeps the session (and its owned back) alive across remount; only release
        // owned back when ownership itself ended. Explicit exit and destroy unregister directly.
        if (!nativeLyricsSessionActive) unregisterSystemBackCallback(activity);
    }

    /**
     * Spotify's own lyrics page (a full-screen ComposeView) stays mounted under the opaque shell.
     * Left VISIBLE it is still recorded and rasterized every frame behind the lyrics - a whole
     * extra screen of drawing, plus Compose's own lyric animations - without a single pixel of it
     * ever reaching the display. Hiding it once the shell fully covers it changes nothing on
     * screen; it stays attached and laid out, so anything reading its views still works.
     */
    private static void hideCoveredSiblings(FrameLayout content, View root) {
        try {
            if (content == null || root == null || root.getParent() != content) return;
            if (root.getTag(TAG_COVERED_SIBLINGS) != null) return;
            java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
            int rootIndex = content.indexOfChild(root);
            for (int i = 0; i < rootIndex; i++) {
                View child = content.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                child.setVisibility(View.INVISIBLE);
                hidden.add(child);
            }
            root.setTag(TAG_COVERED_SIBLINGS, hidden);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " hide covered page failed: " + t);
        }
    }

    private static void restoreCoveredSiblings(View root) {
        try {
            Object tag = root == null ? null : root.getTag(TAG_COVERED_SIBLINGS);
            if (!(tag instanceof java.util.List)) return;
            root.setTag(TAG_COVERED_SIBLINGS, null);
            for (Object o : (java.util.List<?>) tag) {
                // Only undo our own change; Spotify may have hidden the view itself meanwhile.
                if (o instanceof View && ((View) o).getVisibility() == View.INVISIBLE) {
                    ((View) o).setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean shellConsumesBack(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            View root = content == null ? null : content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            return root instanceof NativeSpicyShellView && ((NativeSpicyShellView) root).consumeBack();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether a lyrics page opened now would become our takeover screen. */
    boolean takeoverWouldApply() {
        return takeoverArmed || nativeLyricsSessionActive;
    }

    /** The armed launch went elsewhere (the PiP dock); the next lyrics page stays Spotify's. */
    void disarmTakeover() {
        takeoverArmed = false;
    }

    boolean hasNativeShell(Activity activity) {
        return hasNativeSpicyRoot(activity);
    }

    private boolean hasNativeSpicyRoot(Activity activity) {
        try {
            if (activity == null) return false;
            FrameLayout content = activity.findViewById(android.R.id.content);
            return content != null && content.findViewWithTag(TAG_NATIVE_SPICY_ROOT) instanceof NativeSpicyShellView;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Re-applies the shell's status-bar preference without remounting the screen. */
    private void refreshShellStatusBar(Activity activity) {
        try {
            if (activity == null) return;
            FrameLayout content = activity.findViewById(android.R.id.content);
            View root = content == null ? null : content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (root instanceof NativeSpicyShellView) ((NativeSpicyShellView) root).refreshStatusBar();
        } catch (Throwable ignored) {
        }
    }
}
