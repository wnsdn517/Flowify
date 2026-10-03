package com.eza.spicyex.spotifyconnect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ResultReceiver;

/**
 * Small, stable boundary between code injected into Spotify and the player owned by SpicyEX.
 *
 * <p>Injected code has Spotify's UID, so it must not try to construct or retain player objects.
 * Commands go through our exported receiver; it runs under SpicyEX's UID and starts the
 * foreground service locally. Keeping this class free of player state is intentional: it is
 * loaded reflectively by the common (lite/full) source set.
 */
public final class ConnectEntry {
    private static final String PACKAGE = "com.eza.spicyex";
    private static final String RECEIVER = "com.eza.spicyex.player.PlayerWarmReceiver";
    private static final String LOGIN_ACTIVITY = "com.eza.spicyex.player.WebLoginActivity";
    private static final String EXTRA_WARM_REPLY = "warm_reply";
    private static final String EXTRA_UI_LANGUAGE = "ui_language";
    private static final String EXTRA_AD_MODE = "ad_mode";
    private static final String EXTRA_AD_MUSIC_THEME = "ad_music_theme";

    private ConnectEntry() {
    }

    public static void init(Context context) {
        // Deliberately stateless. Kept for the reflection ABI used by SpotifyConnectHook.
    }

    public static void setEnabled(Context context, boolean enabled) {
        // Turning the setting on must actually start the player - warmUp() is what really
        // launches WebPlayerService; without this, flipping the switch did nothing observable
        // and the only ways to ever start it were the settings "Play test track" row or hitting
        // the injected picker button inside an already-open Spotify Connect dialog.
        if (enabled) warmUp(context, null); else send(context, "com.eza.spicyex.player.STOP", null);
    }

    public static void warmUp(Context context, ResultReceiver reply) {
        warmUp(context, reply, false);
    }

    /** needDeviceId: the reply is for a hand-off, so hold READY until the device id is known. */
    public static void warmUp(Context context, ResultReceiver reply, boolean needDeviceId) {
        warmUp(context, reply, needDeviceId, true);
    }

    /** networkRecovery mirrors Settings.CONNECT_NETWORK_RECOVERY for the player. */
    public static void warmUp(Context context, ResultReceiver reply, boolean needDeviceId,
                              boolean networkRecovery) {
        Intent command = new Intent("com.eza.spicyex.player.WARMUP");
        if (reply != null) command.putExtra(EXTRA_WARM_REPLY, reply);
        if (needDeviceId) command.putExtra("need_device_id", true);
        command.putExtra("network_recovery", networkRecovery);
        send(context, command);
    }

    public static void openLogin(Context context) {
        openLogin(context, null);
    }

    public static void openLogin(Context context, ResultReceiver reply) {
        if (context == null) return;
        // Called from a user tap in Spotify. With a reply, the receiver hands back a
        // PendingIntent for the sign-in screen that Spotify fires itself, keeping that tap's
        // launch right (Spotify can't resolve our activity directly: this package isn't
        // visible to it). Without one, the direct start is the best remaining try.
        if (reply == null) {
            try {
                Intent login = new Intent();
                login.setComponent(new ComponentName(PACKAGE, LOGIN_ACTIVITY));
                login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(login);
                return;
            } catch (Throwable ignored) {
            }
        }
        Intent command = new Intent("com.eza.spicyex.player.LOGIN");
        if (reply != null) command.putExtra(EXTRA_WARM_REPLY, reply);
        send(context, command);
    }

    // Compatibility endpoints used by ConnectMirror. They intentionally do not infer remote
    // commands from Spotify traffic: doing so can create playback loops or duplicate requests.
    private static volatile boolean remoteActive;
    private static volatile long remoteAt;
    private static volatile boolean phonePlaying;
    private static volatile long phonePosition;

    public static void onPhoneState(Context context, boolean playing, long positionMs) {
        phonePlaying = playing;
        phonePosition = Math.max(0, positionMs);
    }

    public static void onRemoteCommand(Context context, String kind) {
        remoteActive = true;
        remoteAt = System.currentTimeMillis();
    }

    public static boolean isRemoteActive() {
        return remoteActive && System.currentTimeMillis() - remoteAt < 10L * 60_000L;
    }

    public static boolean phonePlaying() {
        return phonePlaying;
    }

    public static long phonePosition() {
        return phonePosition;
    }

    /** The Spicy EX interface language (a module setting, not the system locale). */
    private static String uiLanguage(Context context) {
        try {
            String stored = com.eza.spicyex.SpotifyPlusConfig.from(context)
                    .get(com.eza.spicyex.Settings.UI_LANGUAGE);
            return com.eza.spicyex.ui.UiLanguage.strings(context, stored).selectedLanguage();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ads on the web player are handled there (its audio never passes through Spotify's
     *  AudioTracks), so it gets the same ad settings with every command. */
    private static void putAdSettings(Context context, Intent command) {
        try {
            com.eza.spicyex.SpotifyPlusConfig config = com.eza.spicyex.SpotifyPlusConfig.from(context);
            command.putExtra(EXTRA_AD_MODE, config.get(com.eza.spicyex.Settings.AD_MODE));
            command.putExtra(EXTRA_AD_MUSIC_THEME, config.get(com.eza.spicyex.Settings.AD_MUSIC_THEME));
        } catch (Throwable ignored) {
        }
    }

    private static void send(Context context, String action, Intent extras) {
        Intent command = extras == null ? new Intent(action) : extras;
        command.setAction(action);
        send(context, command);
    }

    private static void send(Context context, Intent command) {
        if (context == null || command == null) return;
        try {
            command.setComponent(new ComponentName(PACKAGE, RECEIVER));
            command.putExtra(EXTRA_UI_LANGUAGE, uiLanguage(context));
            putAdSettings(context, command);
            // A force-stopped module app otherwise never sees the broadcast at all.
            command.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(command);
        } catch (Throwable ignored) {
        }
    }
}
