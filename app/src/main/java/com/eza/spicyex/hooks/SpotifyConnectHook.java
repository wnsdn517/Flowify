package com.eza.spicyex.hooks;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.util.ArrayDeque;

public final class SpotifyConnectHook {
    private static final String ENTRY = "com.eza.spicyex.spotifyconnect.ConnectEntry";

    private SpotifyConnectHook() {
    }

    public static void init(Context context) {
        try {
            Class<?> entry = Class.forName(ENTRY);
            entry.getMethod("init", Context.class).invoke(null, context);
            XpLog.log("[SpicyConnect] init dispatched ok (picker scan v6)");
            try {
                context.getPackageManager().getPackageInfo("com.eza.spicyex", 0);
                XpLog.log("[SpicyConnect] self-visibility: VISIBLE");
            } catch (Throwable t) {
                XpLog.log("[SpicyConnect] self-visibility: INVISIBLE type="
                        + t.getClass().getSimpleName());
            }
        } catch (ClassNotFoundException e) {
            return;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            XpLog.log("[SpicyConnect] init failed type=" + t.getClass().getName()
                    + " cause=" + cause.getClass().getName() + " msg=" + cause.getMessage());
        }
    }

    public static void onSettingsChanged(Context context, boolean enabled) {
        if (enabled) {
            warm(context, null, false);
            return;
        }
        try {
            Class<?> entry = Class.forName(ENTRY);
            entry.getMethod("setEnabled", Context.class, boolean.class).invoke(null, context, enabled);
        } catch (ClassNotFoundException e) {
            return;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] settings failed type=" + t.getClass().getName());
        }
    }

    public static boolean isAvailable() {
        try {
            Class.forName(ENTRY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void openLogin(Context context) {
        try {
            Class<?> entry = Class.forName(ENTRY);
            entry.getMethod("openLogin", Context.class, android.os.ResultReceiver.class)
                    .invoke(null, context, newReply(context, null));
        } catch (ClassNotFoundException e) {
            return;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] login failed type=" + t.getClass().getName());
        }
    }

    public static final int WARM_READY = 1;
    public static final int WARM_STARTING = 2;
    public static final int WARM_FAILED = 3;
    public static final int WARM_LOGIN_REQUIRED = 4;
    // Mirrors WebPlayerService.WARM_RESULT_NEEDS_KICK / EXTRA_KICK.
    private static final int WARM_NEEDS_KICK = 5;
    private static final String EXTRA_KICK = "kick";
    // Mirrors WebPlayerService.EVENT_RESELECT.
    private static final int EVENT_RESELECT = 6;
    /** WebPlayerService.EVENT_SHUFFLE_OFF */
    private static final int EVENT_SHUFFLE_OFF = 7;
    // Mirror WebPlayerService.EXTRA_DEVICE_ID / EXTRA_ACTIVE_DEVICE_ID / EXTRA_PLAYING.
    private static final String EXTRA_DEVICE_ID = "device_id";
    private static final String EXTRA_ACTIVE_DEVICE_ID = "active_device_id";
    private static final String EXTRA_PLAYING = "playing";

    // Stored values of Settings.CONNECT_AUTO_SWITCH.
    private static final String SWITCH_ON_START = "On app start";
    private static final String SWITCH_ON_FIRST_PLAY = "On first play";

    private static String autoSwitchMode(Context app) {
        try {
            return com.eza.spicyex.SpotifyPlusConfig.from(app).get(com.eza.spicyex.Settings.CONNECT_AUTO_SWITCH);
        } catch (Throwable t) {
            return SWITCH_ON_START;
        }
    }

    /** What the settings panel shows about the player. */
    public interface StatusListener {
        /** @param code WARM_* code; the rest is only meaningful with WARM_READY */
        void onStatus(int code, String deviceId, String activeDeviceId, boolean playing);
    }

    /** Warms the player (it has to run to have a state) and reports what it knows. */
    public static void queryStatus(Context context, StatusListener listener) {
        warm(context, (code, data) -> listener.onStatus(code,
                data == null ? null : data.getString(EXTRA_DEVICE_ID),
                data == null ? null : data.getString(EXTRA_ACTIVE_DEVICE_ID),
                data != null && data.getBoolean(EXTRA_PLAYING)), false);
    }

    /** Fires the player's own start PendingIntent from this (foreground) process: the sender's
     *  foreground state is what lets the start through. Android 14+ also wants the sender to
     *  opt in explicitly before it lends that privilege. */
    private static boolean fireKick(Context context, android.os.Bundle data) {
        try {
            android.app.PendingIntent kick = data == null ? null : data.getParcelable(EXTRA_KICK);
            if (kick == null) return false;
            android.os.Bundle options = null;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                options = o.toBundle();
            }
            kick.send(context, 0, null, null, null, null, options);
            XpLog.log("[SpicyConnect] player start handed through Spotify's foreground");
            return true;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] kick failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static volatile boolean autoStartArmed;
    private static volatile boolean firstWarmSent;
    private static volatile long lastForegroundWarmElapsed;
    // Short: the player can be gone at any time (idle stop, memory trim, its process killed), and
    // a warm-up of a live player is just a cheap command.
    private static final long FOREGROUND_REWARM_MIN_MS = 60_000L;

    /** Auto-start rides on Spotify's own UI coming to the front rather than on process start:
     *  at process start Spotify isn't foreground yet, so it has no start privilege to lend.
     *  Later resumes re-warm, rate limited, in case the system reclaimed the player meanwhile. */
    public static void armAutoStart(Context context) {
        if (autoStartArmed || context == null) return;
        autoStartArmed = true;
        try {
            XpHooks.findAfter(android.app.Activity.class, "onResume", "connect:Activity#onResume",
                    param -> {
                        try {
                            if (param.thisObject instanceof android.app.Activity) {
                                onSpotifyResumed((android.app.Activity) param.thisObject);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] auto-start hook failed type=" + t.getClass().getName());
        }
        try {
            // Spotify only opens an AudioTrack for playback on this phone itself; remote
            // Connect playback never does. So the first one after launch is exactly "the user
            // pressed play and it started locally" - the moment to hand playback to the web
            // player, and a moment where the active device is provably this phone (never,
            // say, the user's desktop that happens to be signed in too).
            XpHooks.findAfter(android.media.AudioTrack.class, "play", "connect:AudioTrack#play",
                    param -> {
                        try {
                            onLocalPlaybackStarted(context, param.thisObject instanceof android.media.AudioTrack
                                    ? (android.media.AudioTrack) param.thisObject : null);
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] local playback hook failed type=" + t.getClass().getName());
        }
    }

    private static volatile boolean localTakeoverSent;

    private static final long LOCAL_SILENCE_MAX_MS = 12_000L;
    private static final long HANDOFF_ANSWER_MS = 40_000L; // past the player's device-id wait (WebPlayerService.DEVICE_ID_POLL_MAX)

    private static void onLocalPlaybackStarted(Context context, android.media.AudioTrack track) {
        if (localTakeoverSent) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(app)
                .get(com.eza.spicyex.Settings.CONNECT_ENABLED))) return;
        String mode = autoSwitchMode(app);
        if (!SWITCH_ON_FIRST_PLAY.equals(mode) && !SWITCH_ON_START.equals(mode)) return;
        localTakeoverSent = true;
        XpLog.log("[SpicyConnect] local playback started, handing it to the web player");
        // The phone's own copy of this playback is about to move: keep it silent meanwhile, so
        // the hand-off isn't heard as the song starting on the phone first. Anything but a
        // completed switch gives the sound straight back (and a failsafe does regardless).
        Silence silence = silence(track);
        // A start refused by the system never answers at all: without this the hand-off would
        // stay "in progress" until Spotify restarted.
        boolean[] answered = {false};
        onMainDelayed(() -> {
            if (answered[0]) return;
            XpLog.log("[SpicyConnect] player did not answer, hand-off dropped for now");
            localTakeoverSent = false;
            silence.restore();
        }, HANDOFF_ANSWER_MS);
        // Warm first: the reply confirms the player is up and signed in, and carries the id
        // it registered under, so the hand-off selects exactly our device.
        warm(app, (code, data) -> {
            answered[0] = true;
            if (code == WARM_STARTING) return;
            if (code != WARM_READY || (data != null && data.getBoolean(EXTRA_PLAYING))) {
                // Not handed over: the next local play gets another try.
                localTakeoverSent = false;
                silence.restore();
                return;
            }
            String deviceId = data == null ? null : data.getString(EXTRA_DEVICE_ID);
            handOff(app, deviceId, null, result -> {
                if (result != ConnectRouteHandoff.Result.SWITCHED) {
                    localTakeoverSent = false;
                    silence.restore();
                    return;
                }
                // Android's output switcher showing the route selected is not Spotify having
                // moved playback: confirm the web player really is the account's active device.
                verifyActive(app, deviceId, 0, ok -> {
                    if (ok) {
                        silence.keepUntilStopped();
                    } else {
                        localTakeoverSent = false;
                        silence.restore();
                    }
                });
            });
        }, true);
    }

    private interface Verified {
        void onResult(boolean ok);
    }

    /** How long the web player gets to start sounding after the route switch, checked every
     *  VERIFY_POLL_MS; one release-and-select-again retry follows. */
    private static final long VERIFY_WINDOW_MS = 9000L;
    private static final long VERIFY_POLL_MS = 1500L;

    /**
     * Confirms the hand-off by the only thing that matters to the listener: the web player
     * actually playing audio. Neither the route showing "selected" in Android's output switcher
     * nor Spotify's own session turning remote proves that - both were seen on-device while the
     * server never moved playback, and keeping the phone muted on their word was silence. A
     * retry releases the route first (re-selecting a selected route does nothing) and is tried
     * once, after a fair wait: releasing too early cancels a transfer about to land.
     */
    private static void verifyActive(Context app, String deviceId, int attempt, Verified done) {
        long deadline = android.os.SystemClock.elapsedRealtime() + VERIFY_WINDOW_MS;
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> warm(app, (code, data) -> {
            if (code == WARM_STARTING) return;
            if (code == WARM_READY && data != null && data.getBoolean(EXTRA_PLAYING)) {
                XpLog.log("[SpicyConnect] hand-off confirmed, the web player is playing");
                done.onResult(true);
                return;
            }
            if (android.os.SystemClock.elapsedRealtime() < deadline) {
                onMainDelayed(poll[0], VERIFY_POLL_MS);
                return;
            }
            if (attempt == 0) {
                XpLog.log("[SpicyConnect] web player silent, selecting it again");
                reselect(app, deviceId,
                        r -> verifyActive(app, deviceId, attempt + 1, done));
                return;
            }
            XpLog.log("[SpicyConnect] hand-off not confirmed, the web player never played (player=" + deviceId + ")");
            done.onResult(false);
        }, false);
        onMainDelayed(poll[0], VERIFY_POLL_MS);
    }

    /**
     * Spotify's own transfer (what its device picker does) when the web player's device id is
     * known; Android's output switcher only as the fallback - selecting the route there changes
     * what the system shows without Spotify's server moving playback.
     */
    private static void handOff(Context app, String deviceId, String activeDeviceId,
                                ConnectRouteHandoff.Done done) {
        if (deviceId != null && activeDeviceId != null && activeDeviceId.equalsIgnoreCase(deviceId)) {
            if (done != null) done.onDone(ConnectRouteHandoff.Result.SKIPPED);
            return;
        }
        if (ConnectTransfer.transfer(deviceId)) {
            if (done != null) done.onDone(ConnectRouteHandoff.Result.SWITCHED);
            return;
        }
        XpLog.log("[SpicyConnect] in-app transfer unavailable (id=" + deviceId + "), using the output switcher");
        ConnectRouteHandoff.transfer(app, deviceId, activeDeviceId, done);
    }

    private static void reselect(Context app, String deviceId, ConnectRouteHandoff.Done done) {
        if (ConnectTransfer.transfer(deviceId)) {
            if (done != null) done.onDone(ConnectRouteHandoff.Result.SWITCHED);
            return;
        }
        ConnectRouteHandoff.reselect(app, deviceId, null, done);
    }

    private static void onMainDelayed(Runnable r, long ms) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, ms);
    }

    /** Local tracks muted for a completed hand-off: they get their volume back only once
     *  Spotify itself stops them (see installLocalTrackRelease). */
    private static final java.util.Set<android.media.AudioTrack> MUTED_UNTIL_STOPPED =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<>()));
    private static volatile boolean localTrackReleaseInstalled;

    /** A muted local track and how to undo that. */
    private static final class Silence {
        private final java.lang.ref.WeakReference<android.media.AudioTrack> ref;
        private boolean settled;

        Silence(android.media.AudioTrack track) {
            ref = new java.lang.ref.WeakReference<>(track);
        }

        /** The hand-off failed or was not tried: the phone plays on, audibly. */
        void restore() {
            onMain(() -> {
                if (settled) return;
                settled = true;
                unmute(ref.get());
            });
        }

        /**
         * The hand-off completed. The volume used to come back on the failsafe timer anyway,
         * and whenever Spotify had not stopped its local copy by then, the song played twice -
         * here and on the web player. It now stays muted until Spotify pauses or stops that
         * track, which is also the moment it can be heard again safely.
         */
        void keepUntilStopped() {
            onMain(() -> {
                if (settled) return;
                settled = true;
                android.media.AudioTrack t = ref.get();
                if (t != null) MUTED_UNTIL_STOPPED.add(t);
            });
        }

        /** Failsafe for a hand-off that never answered at all. */
        void restoreLater() {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::restore, LOCAL_SILENCE_MAX_MS);
        }
    }

    private static void onMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static void unmute(android.media.AudioTrack t) {
        try {
            if (t != null && t.getState() == android.media.AudioTrack.STATE_INITIALIZED) t.setVolume(1f);
        } catch (Throwable ignored) {
        }
    }

    /** Mutes a local AudioTrack for a hand-off in progress. */
    private static Silence silence(android.media.AudioTrack track) {
        Silence silence = new Silence(track);
        if (track == null) return silence;
        installLocalTrackRelease();
        try {
            track.setVolume(0f);
        } catch (Throwable ignored) {
        }
        silence.restoreLater();
        return silence;
    }

    /** Gives a track muted for a completed hand-off its volume back as Spotify stops it. */
    private static void installLocalTrackRelease() {
        if (localTrackReleaseInstalled) return;
        localTrackReleaseInstalled = true;
        for (String method : new String[]{"pause", "stop", "flush"}) {
            try {
                XpHooks.findAfter(android.media.AudioTrack.class, method, "connect:AudioTrack#" + method,
                        param -> {
                            Object t = param.thisObject;
                            if (t instanceof android.media.AudioTrack && MUTED_UNTIL_STOPPED.remove(t)) {
                                unmute((android.media.AudioTrack) t);
                            }
                        });
            } catch (Throwable t) {
                XpLog.log("[SpicyConnect] local track release hook failed (" + method + ") type="
                        + t.getClass().getName());
            }
        }
    }

    private static void onSpotifyResumed(android.app.Activity activity) {
        Context app = activity.getApplicationContext();
        if (app == null || !Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(app)
                .get(com.eza.spicyex.Settings.CONNECT_ENABLED))) return;
        String mode = autoSwitchMode(app);
        boolean auto = SWITCH_ON_START.equals(mode) || SWITCH_ON_FIRST_PLAY.equals(mode);
        long now = android.os.SystemClock.elapsedRealtime();
        boolean first = !firstWarmSent;
        // Routes take a few seconds to show up once discovery starts; begin now so they are
        // known by the time a hand-off needs them.
        if (first && auto) ConnectRouteHandoff.prepare(app);
        // Still playing on the phone as Spotify comes to the front: move it over now. This also
        // catches a hand-off that could not run earlier - playback started from the background
        // (notification, headset, lock screen), where Android hides Spotify's Connect routes
        // from Spotify itself, so the web player's route was never found.
        // With nothing playing there is nothing to move: a Connect switch always starts
        // playback on its target, and every way of stopping that again left the web player
        // wedged often enough to be unusable. The player is warmed instead, and the moment
        // playback starts it is handed over (see onLocalPlaybackStarted).
        if (auto && !localTakeoverSent && isPlayingHere(app)) {
            XpLog.log("[SpicyConnect] playing on the phone as Spotify comes up, moving it to the web player");
            firstWarmSent = true;
            lastForegroundWarmElapsed = now;
            onLocalPlaybackStarted(app, null);
            return;
        }
        if (!first && now - lastForegroundWarmElapsed < FOREGROUND_REWARM_MIN_MS) return;
        lastForegroundWarmElapsed = now;
        firstWarmSent = true;
        // Coming back to Spotify after a while: the player may have been recycled meanwhile, and
        // the next local play is a fresh start worth handing over again.
        if (!first) localTakeoverSent = false;
        XpLog.log("[SpicyConnect] foreground warm-up");
        foregroundWarm(app, new java.lang.ref.WeakReference<>(activity), 0);
    }

    /** Last time the player answered anything at all: proof that a start went through. */
    private static volatile long lastPlayerReplyElapsed;
    private static final long FOREGROUND_WARM_SETTLE_MS = 1200L;
    private static final long FOREGROUND_WARM_ANSWER_MS = 10_000L;
    private static final int FOREGROUND_WARM_ATTEMPTS = 3;

    /**
     * Starts the player on Spotify's foreground privilege. Android lends that privilege only once
     * Spotify's window is really up: a start sent the instant the activity resumes - before its
     * first frame - is refused ("Background started FGS: Disallowed"), silently, since the refusal
     * happens inside the system. So the start waits for the window to settle, and when the
     * player has not answered a few seconds later it is sent again, while Spotify is still in
     * front.
     */
    private static void foregroundWarm(Context app, java.lang.ref.WeakReference<android.app.Activity> ref, int attempt) {
        onMainDelayed(() -> {
            android.app.Activity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (!activity.hasWindowFocus() && attempt < FOREGROUND_WARM_ATTEMPTS) {
                foregroundWarm(app, ref, attempt + 1);
                return;
            }
            long sentAt = android.os.SystemClock.elapsedRealtime();
            warm(app, null, false);
            onMainDelayed(() -> {
                if (lastPlayerReplyElapsed >= sentAt) return;
                android.app.Activity current = ref.get();
                boolean inFront = current != null && !current.isFinishing() && current.hasWindowFocus();
                if (attempt + 1 < FOREGROUND_WARM_ATTEMPTS && inFront) {
                    XpLog.log("[SpicyConnect] player did not start, asking again");
                    foregroundWarm(app, ref, attempt + 1);
                } else {
                    XpLog.log("[SpicyConnect] player did not start; trying again on the next return to Spotify");
                    lastForegroundWarmElapsed = 0L;
                }
            }, FOREGROUND_WARM_ANSWER_MS);
        }, attempt == 0 ? FOREGROUND_WARM_SETTLE_MS : 600L);
    }

    public interface WarmStateListener {
        void onWarmState(int code);
    }

    public static void startWebViewService(Context context) {
        startWebViewService(context, null);
    }

    public static void startWebViewService(Context context, WarmStateListener listener) {
        warm(context, listener == null ? null : (code, data) -> listener.onWarmState(code), false);
    }

    /** Like WarmStateListener, plus the reply's extras (READY carries the device id). */
    private interface WarmReplyListener {
        void onReply(int code, android.os.Bundle data);
    }

    /** Spotify playing on the phone itself. Not AudioManager.isMusicActive(): that is
     *  device-wide and counts the web player's own audio as "playing here". */
    private static boolean isPlayingHere(Context context) {
        PlaybackBridge bridge = PlaybackBridge.current;
        return bridge != null && bridge.playingLocally();
    }

    private static void warm(Context context, WarmReplyListener listener, boolean needDeviceId) {
        android.os.ResultReceiver reply = newReply(context, listener);
        boolean recovery = true;
        try {
            recovery = !Boolean.FALSE.equals(com.eza.spicyex.SpotifyPlusConfig.from(context)
                    .get(com.eza.spicyex.Settings.CONNECT_NETWORK_RECOVERY));
        } catch (Throwable ignored) {
        }
        try {
            Class<?> entry = Class.forName(ENTRY);
            entry.getMethod("warmUp", Context.class, android.os.ResultReceiver.class, boolean.class,
                    boolean.class).invoke(null, context, reply, needDeviceId, recovery);
        } catch (ClassNotFoundException e) {
            if (listener != null) listener.onReply(WARM_FAILED, null);
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] startWebViewService failed type=" + t.getClass().getName());
            if (listener != null) listener.onReply(WARM_FAILED, null);
        }
    }

    /** Every command carries a reply: even with no listener it carries the start-token
     *  handshake (WARM_NEEDS_KICK) that gets the player past Android's background-start ban. */
    private static android.os.ResultReceiver newReply(Context context, WarmReplyListener listener) {
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new android.os.ResultReceiver(
                new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, android.os.Bundle resultData) {
                if (resultCode != WARM_NEEDS_KICK) lastPlayerReplyElapsed = android.os.SystemClock.elapsedRealtime();
                if (resultCode == WARM_NEEDS_KICK) {
                    if (fireKick(app, resultData)) return;
                    resultCode = WARM_FAILED;
                }
                if (resultCode == EVENT_SHUFFLE_OFF) {
                    PlaybackBridge bridge = PlaybackBridge.current;
                    boolean sent = bridge != null && bridge.setShuffleOff();
                    XpLog.log("[SpicyConnect] web player asked for shuffle off, sent=" + sent);
                    return;
                }
                if (resultCode == EVENT_RESELECT) {
                    // The player reconnected after a network change as a new device while
                    // music was playing: select it again, as the user had it.
                    XpLog.log("[SpicyConnect] player reconnected, selecting it again");
                    handOff(app,
                            resultData == null ? null : resultData.getString(EXTRA_DEVICE_ID), null, null);
                    return;
                }
                if (listener == null) return;
                try {
                    listener.onReply(resultCode, resultData);
                } catch (Throwable ignored) {
                }
            }
        };
    }

    static boolean isMainProcess(Context context) {
        try {
            String process = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? Application.getProcessName() : "unknown";
            return context.getPackageName().equals(process);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
