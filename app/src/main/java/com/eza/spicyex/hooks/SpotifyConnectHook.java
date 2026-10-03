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
    private static final long FOREGROUND_REWARM_MIN_MS = 5L * 60_000L;

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
        // Warm first: the reply confirms the player is up and signed in, and carries the id
        // it registered under, so the hand-off selects exactly our device.
        warm(app, (code, data) -> {
            if (code == WARM_STARTING) return;
            if (code != WARM_READY || (data != null && data.getBoolean(EXTRA_PLAYING))) {
                silence.restore();
                return;
            }
            String deviceId = data == null ? null : data.getString(EXTRA_DEVICE_ID);
            ConnectRouteHandoff.transfer(app, deviceId, null, result -> {
                if (result == ConnectRouteHandoff.Result.SWITCHED) silence.keepUntilStopped();
                else silence.restore();
            });
        }, true);
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
        long now = android.os.SystemClock.elapsedRealtime();
        boolean first = !firstWarmSent;
        if (!first && now - lastForegroundWarmElapsed < FOREGROUND_REWARM_MIN_MS) return;
        Context app = activity.getApplicationContext();
        if (app == null || !Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(app)
                .get(com.eza.spicyex.Settings.CONNECT_ENABLED))) return;
        lastForegroundWarmElapsed = now;
        firstWarmSent = true;
        String mode = autoSwitchMode(app);
        // Routes take a few seconds to show up once discovery starts; begin now so they are
        // known by the time a hand-off needs them.
        if (first && (SWITCH_ON_START.equals(mode) || SWITCH_ON_FIRST_PLAY.equals(mode))) {
            ConnectRouteHandoff.prepare(app);
        }
        if (first && SWITCH_ON_START.equals(mode) && isPlayingHere(app)) {
            // Already playing on the phone as Spotify comes up: move it over right away.
            // With nothing playing there is nothing to move: a Connect switch always starts
            // playback on its target, and every way of stopping that again left the web
            // player wedged (claiming to play a stream that never starts) often enough to be
            // unusable. The player is started and its route discovered now, and the moment
            // playback starts, it is handed over (see onLocalPlaybackStarted).
            XpLog.log("[SpicyConnect] foreground warm-up, moving current playback to the web player");
            localTakeoverSent = true;
            warm(app, (code, data) -> {
                if (code != WARM_READY || data == null || data.getBoolean(EXTRA_PLAYING)) return;
                ConnectRouteHandoff.transfer(app, data.getString(EXTRA_DEVICE_ID),
                        data.getString(EXTRA_ACTIVE_DEVICE_ID), null);
            }, true);
            return;
        }
        XpLog.log("[SpicyConnect] foreground warm-up");
        warm(app, null, false);
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
                    ConnectRouteHandoff.transfer(app,
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

    private static final int TAG_PICKER_WEBVIEW_BUTTON = 0x53504C57; // SPLW
    private static final long[] PICKER_RETRY_DELAYS_MS = {400L, 900L, 1600L, 2800L, 4500L};

    /** Watches every Dialog Spotify shows; when the Connect device picker appears, appends a
     *  "start web player service" button so a device lost to a network switch can be brought
     *  back right from the picker instead of digging through settings. */
    public static void installPickerButton() {
        try {
            XpHooks.findAfter(android.app.Dialog.class, "show", "connect:Dialog#show", param -> {
                try {
                    if (!(param.thisObject instanceof android.app.Dialog)) return;
                    if (!isAvailable()) return;
                    schedulePickerScan((android.app.Dialog) param.thisObject, 0);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] picker button hook failed type=" + t.getClass().getName());
        }
    }

    private static void schedulePickerScan(android.app.Dialog dialog, int attempt) {
        try {
            android.view.Window window = dialog.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null || attempt >= PICKER_RETRY_DELAYS_MS.length) return;
            final int next = attempt + 1;
            decor.postDelayed(() -> {
                try {
                    if (!dialog.isShowing()) return;
                    if (injectPickerButton(dialog)) return;
                    schedulePickerScan(dialog, next);
                } catch (Throwable ignored) {
                }
            }, PICKER_RETRY_DELAYS_MS[attempt]);
        } catch (Throwable ignored) {
        }
    }

    private static boolean injectPickerButton(android.app.Dialog dialog) {
        try {
            android.view.Window window = dialog.getWindow();
            View content = window == null ? null : window.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) content;
            if (group.findViewWithTag(TAG_PICKER_WEBVIEW_BUTTON) != null) return true;
            String match = pickerMatch(dialog, group);
            if (match == null) return false;
            XpLog.log("[SpicyConnect] picker matched by [" + match + "]");
            android.widget.Button button = makePickerButton(dialog.getContext());
            group.addView(button, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            XpLog.log("[SpicyConnect] inserted webview start button in device picker");
            return true;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] picker inject failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static final String PICKER_BUTTON_LABEL = "\u266A Start web player service";
    private static final String PICKER_BUTTON_WAITING = "\u266A Waiting for device\u2026";
    private static final long PICKER_BUTTON_TIMEOUT_MS = 20000L;

    private static android.widget.Button makePickerButton(Context context) {
        android.widget.Button button = new android.widget.Button(context);
        button.setTag(TAG_PICKER_WEBVIEW_BUTTON);
        button.setAllCaps(false);
        button.setText(PICKER_BUTTON_LABEL);
        try {
            button.setBackgroundColor(0xFF2A2A2A);
            button.setTextColor(0xFFFFFFFF);
        } catch (Throwable ignored) {
        }
        button.setOnClickListener(v -> {
            android.widget.Button b = (android.widget.Button) v;
            final boolean[] settled = {false};
            Runnable timeout = () -> {
                if (settled[0]) return;
                settled[0] = true;
                restorePickerButton(b);
            };
            b.setEnabled(false);
            b.setText(PICKER_BUTTON_WAITING);
            try {
                b.postDelayed(timeout, PICKER_BUTTON_TIMEOUT_MS);
            } catch (Throwable ignored) {
            }
            startWebViewService(v.getContext(), code -> {
                if (settled[0]) return;
                if (code == WARM_READY) {
                    settled[0] = true;
                    try {
                        b.removeCallbacks(timeout);
                    } catch (Throwable ignored) {
                    }
                    b.setVisibility(View.GONE);
                } else if (code == WARM_FAILED) {
                    settled[0] = true;
                    try {
                        b.removeCallbacks(timeout);
                    } catch (Throwable ignored) {
                    }
                    restorePickerButton(b);
                } else if (code == WARM_LOGIN_REQUIRED) {
                    settled[0] = true;
                    try {
                        b.removeCallbacks(timeout);
                    } catch (Throwable ignored) {
                    }
                    b.setEnabled(true);
                    b.setText("♫ Login required");
                    b.setOnClickListener(login -> openLogin(login.getContext()));
                }
            });
        });
        return button;
    }

    private static void restorePickerButton(android.widget.Button button) {
        try {
            button.setEnabled(true);
            button.setText(PICKER_BUTTON_LABEL);
        } catch (Throwable ignored) {
        }
    }

    /** Embedded (non-dialog) pickers live inside the activity's own view tree - the 2s layout
     *  watcher calls this, so a sheet that appears later still gets the button.
     *  Root is the window decor, NOT android.R.id.content: the Connect bottom sheet is a
     *  sibling overlay outside content (confirmed via hierarchy dump), so content-scoped
     *  scans never see it. */
    public static void scanActivityForPicker(android.app.Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (!isAvailable()) return;
            android.view.Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) decor;
            // Cheap tag sweep first: when the sheet button is already in, skip the two
            // resource-resolving walks below entirely.
            if (group.findViewWithTag(TAG_PICKER_WEBVIEW_BUTTON) != null) return;
            if (isConnectPickerPage(activity)) {
                if (injectPickerPageButton(activity, group)) return;
                scheduleSheetRescan(activity, 0);
                return;
            } else if (injectSheetButton(activity, group)) return;
            View anchor = pickerAnchor(group);
            if (anchor == null) {
                if (findViewByResourceEntry(group, "bottom_sheet_container") != null) {
                    scheduleSheetRescan(activity, 0);
                }
                return;
            }
            if (!(anchor.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) anchor.getParent();
            if (parent.findViewWithTag(TAG_PICKER_WEBVIEW_BUTTON) != null) return;
            parent.addView(makePickerButton(activity), parent.indexOfChild(anchor) + 1,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            XpLog.log("[SpicyConnect] inserted webview start button in activity picker by ["
                    + describeAnchor(anchor) + "]");
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] activity picker scan failed type=" + t.getClass().getName());
        }
    }

    /** Precise signal: Spotify opens the device tab as a page with this deep link (confirmed
     *  via dumpsys: dat=spotify:connect-device-picker on PageActivity). No text guessing. */
    private static boolean isConnectPickerPage(android.app.Activity activity) {
        try {
            android.content.Intent intent = activity == null ? null : activity.getIntent();
            android.net.Uri data = intent == null ? null : intent.getData();
            if (data == null) return false;
            return data.toString().toLowerCase(java.util.Locale.ROOT).contains("connect");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Inserts the start row right below the sheet's drag handle inside a confirmed picker
     *  page. Appending at the container end pushed it off-screen (the Compose list above
     *  fills the whole sheet) - verified visually via screenshot. */
    private static boolean injectPickerPageButton(android.app.Activity activity, ViewGroup decor) {
        try {
            View sheet = findViewByResourceEntry(decor, "bottom_sheet_container");
            if (!(sheet instanceof ViewGroup)) return false;
            ViewGroup sheetGroup = (ViewGroup) sheet;
            View grapple = findViewByResourceEntry(sheetGroup, "bottom_sheet_grapple");
            int desired = grapple != null && grapple.getParent() == sheetGroup
                    ? sheetGroup.indexOfChild(grapple) + 1 : 0;
            View existing = sheetGroup.findViewWithTag(TAG_PICKER_WEBVIEW_BUTTON);
            if (existing != null) {
                if (sheetGroup.indexOfChild(existing) == desired) return true;
                sheetGroup.removeView(existing);
            }
            sheetGroup.addView(makePickerButton(activity), desired, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            XpLog.log("[SpicyConnect] inserted webview start button in connect picker page");
            return true;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] picker page inject failed type=" + t.getClass().getName());
            return false;
        }
    }

    /** The Connect sheet is a bottom_sheet_container in the activity window (confirmed via
     *  hierarchy dump) - append the button as its last child so it sits at the sheet bottom.
     *  Other bottom sheets share the container id, so the Connect header text is required too. */
    private static boolean injectSheetButton(android.app.Activity activity, ViewGroup content) {
        try {
            View sheet = findViewByResourceEntry(content, "bottom_sheet_container");
            if (!(sheet instanceof ViewGroup)) return false;
            ViewGroup sheetGroup = (ViewGroup) sheet;
            if (sheetGroup.findViewWithTag(TAG_PICKER_WEBVIEW_BUTTON) != null) return true;
            if (!subtreeHasConnectHeader(sheetGroup)) return false;
            sheetGroup.addView(makePickerButton(activity), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            XpLog.log("[SpicyConnect] inserted webview start button in connect sheet");
            return true;
        } catch (Throwable t) {
            XpLog.log("[SpicyConnect] sheet inject failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static final java.util.WeakHashMap<android.app.Activity, Boolean> sheetRescanActive =
            new java.util.WeakHashMap<>();
    private static final android.os.Handler sheetRescanHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long[] SHEET_RESCAN_DELAYS_MS = {1000L, 2000L, 4000L};

    /** A fully-open static sheet fires no more layouts, so the watcher alone can catch it
     *  half-inflated and then go silent. Bounded re-scan chain (container present but
     *  unmatched only) - layout passes restart it for free once the flag clears. */
    private static void scheduleSheetRescan(android.app.Activity activity, int attempt) {
        try {
            if (activity == null || activity.isFinishing() || attempt >= SHEET_RESCAN_DELAYS_MS.length) return;
            synchronized (sheetRescanActive) {
                if (attempt == 0 && sheetRescanActive.containsKey(activity)) return;
                sheetRescanActive.put(activity, Boolean.TRUE);
            }
            final int next = attempt + 1;
            final java.lang.ref.WeakReference<android.app.Activity> ref =
                    new java.lang.ref.WeakReference<>(activity);
            sheetRescanHandler.postDelayed(() -> {
                try {
                    android.app.Activity a = ref.get();
                    if (a == null || a.isFinishing()) {
                        synchronized (sheetRescanActive) {
                            sheetRescanActive.remove(activity);
                        }
                        return;
                    }
                    scanActivityForPicker(a);
                    if (next >= SHEET_RESCAN_DELAYS_MS.length) {
                        synchronized (sheetRescanActive) {
                            sheetRescanActive.remove(a);
                        }
                    } else {
                        scheduleSheetRescan(a, next);
                    }
                } catch (Throwable ignored) {
                }
            }, SHEET_RESCAN_DELAYS_MS[attempt]);
        } catch (Throwable ignored) {
        }
    }

    private static View findViewByResourceEntry(View root, String entryName) {
        if (root == null) return null;
        if (root.getId() != View.NO_ID) {
            try {
                String name = root.getResources().getResourceEntryName(root.getId());
                if (entryName.equals(name)) return root;
            } catch (Throwable ignored) {
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View res = findViewByResourceEntry(group.getChildAt(i), entryName);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static boolean subtreeHasConnectHeader(View root) {
        if (root == null) return false;
        if (root instanceof TextView) {
            CharSequence raw = ((TextView) root).getText();
            if (raw != null) {
                String text = raw.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (text.contains("connect") || text.contains("\uc5f0\uacb0")) return true;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                if (subtreeHasConnectHeader(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** Returns the matched signal, or null when this dialog is not the device picker. */
    private static String pickerMatch(android.app.Dialog dialog, View root) {
        String route = mediaRouteMatch(dialog);
        if (route != null) return route;
        View anchor = pickerAnchor(root);
        return anchor == null ? null : describeAnchor(anchor);
    }

    /** Spotify routes Connect/cast picking through MediaRouter - the chooser/controller dialogs
     *  keep their framework class names even when everything else is obfuscated. */
    private static String mediaRouteMatch(android.app.Dialog dialog) {
        try {
            Class<?> c = dialog.getClass();
            while (c != null && c != Object.class) {
                String simple = c.getSimpleName();
                if (simple.contains("MediaRouteChooserDialog")
                        || simple.contains("MediaRouteControllerDialog")) {
                    return "class:" + simple;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static class PickerScanState {
        View connectHeader = null;
        View deviceText = null;
        View firstDeviceEntry = null;
    }

    /** Finds the picker header view, or null. Skips our own injected UI so the settings
     *  panel ("Device name" row under a "Spotify Connect" header) never self-matches.
     *  Calibrated against the real sheet: header "Connect" plus either device-ish text
     *  ("No devices found on this network") or a device entry. The mini player has device
     *  entries but no Connect header, so it no longer matches. */
    private static View pickerAnchor(View root) {
        if (root == null) return null;
        PickerScanState state = new PickerScanState();
        View directMatch = fillPickerAnchorState(root, state);
        if (directMatch != null) return directMatch;
        if (state.connectHeader != null && (state.deviceText != null || state.firstDeviceEntry != null)) {
            return state.connectHeader;
        }
        return null;
    }

    private static View fillPickerAnchorState(View view, PickerScanState state) {
        if (view == null || isOwnView(view)) return null;
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            if (raw != null) {
                String text = raw.toString().trim().toLowerCase(java.util.Locale.ROOT);
                boolean connectWord = text.contains("connect") || text.contains("\uc5f0\uacb0");
                boolean deviceWord = text.contains("device") || text.contains("\uae30\uae30")
                        || text.contains("\ub514\ubc14\uc774\uc2a4");
                if ((connectWord && deviceWord)
                        || text.contains("no devices found")
                        || text.equals("devices") || text.equals("\uae30\uae30")
                        || text.equals("\ub514\ubc14\uc774\uc2a4")) {
                    return view;
                }
                if (state.connectHeader == null && connectWord) state.connectHeader = view;
                if (state.deviceText == null && deviceWord) state.deviceText = view;
            }
        } else if (view.getId() != View.NO_ID && view.isLaidOut()) {
            try {
                String entry = view.getResources().getResourceEntryName(view.getId())
                        .toLowerCase(java.util.Locale.ROOT);
                if (entry.contains("device") && state.firstDeviceEntry == null) {
                    state.firstDeviceEntry = view;
                }
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View match = fillPickerAnchorState(group.getChildAt(i), state);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static boolean isOwnView(View view) {
        try {
            Object tag = view.getTag();
            if (tag instanceof Integer) {
                int id = (Integer) tag;
                // SPLW (this button), SPLX/SPLH (lyrics entry buttons in LyricsActivityTakeoverHook).
                if (id == TAG_PICKER_WEBVIEW_BUTTON || id == 0x53504C58 || id == 0x53504C48) {
                    return true;
                }
            }
            if (tag instanceof String) {
                String s = (String) tag;
                if (s.startsWith("card:") || s.startsWith("hdr:")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String describeAnchor(View anchor) {
        try {
            if (anchor instanceof TextView) {
                CharSequence raw = ((TextView) anchor).getText();
                if (raw != null) return "text:" + truncate(raw.toString().trim());
            }
            if (anchor.getId() != View.NO_ID) {
                return "entry:" + anchor.getResources().getResourceEntryName(anchor.getId());
            }
        } catch (Throwable ignored) {
        }
        return anchor.getClass().getSimpleName();
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "\u2026" : s;
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
