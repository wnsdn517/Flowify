package com.eza.spicyex.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Warms the web player service back up at boot if it was last known to be enabled.
 *
 * <p>WebPlayerService lives in this app's own process/UID (see the manifest's exported=true
 * comments) - a different app from Spotify, whose foreground state has no bearing here. A cold
 * background start of a foreground service is refused by Android unless this specific app (not
 * the caller) is itself exempt at that moment, and this app has no launcher activity or other
 * everyday way to become "foreground" on its own (see PlayerWarmReceiver's javadoc and the main
 * manifest's "no launcher activity, no IPC" comment). BOOT_COMPLETED is one of the few starts
 * Android explicitly exempts regardless of that, so this is the one reliable way to have the web
 * player already warm by the time Spotify (re)launches and broadcasts its own warm-up request -
 * that broadcast then lands on an already-running service instead of needing a fresh background
 * start, which is the case Android actually refuses.
 */
public final class BootWarmReceiver extends BroadcastReceiver {
    private static final String TAG = "[SpicyPlayer]";
    private static final String PACKAGE = "com.eza.spicyex";
    private static final String SERVICE_CLASS = "com.eza.spicyex.player.WebPlayerService";
    private static final String ACTION_WARMUP = "com.eza.spicyex.player.WARMUP";

    /**
     * Android 15 (API 35) banned launching a mediaPlayback foreground service from a
     * BOOT_COMPLETED receiver outright - and the refusal doesn't surface here, where it could be
     * caught, but inside the service's own startForeground() call, which crashed the module on
     * every single boot (confirmed on an Android 16 device). There is no "start it a bit later"
     * dodge either; the ban is on the start's BOOT_COMPLETED provenance, not its timing. So on
     * those versions the warm-up simply doesn't happen, and Spotify's own later warm-up broadcast
     * falls through to PlayerWarmReceiver's tap-to-start notification instead.
     */
    private static final int FIRST_SDK_BANNING_BOOT_FGS = 35;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null) return;
            if (Build.VERSION.SDK_INT >= FIRST_SDK_BANNING_BOOT_FGS) {
                Log.i(TAG, "boot warm-up skipped: API " + Build.VERSION.SDK_INT
                        + " forbids mediaPlayback foreground starts from BOOT_COMPLETED");
                return;
            }
            SharedPreferences prefs = context.getSharedPreferences(
                    PlayerWarmReceiver.PREFS_NAME, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(PlayerWarmReceiver.KEY_CONNECT_LAST_ENABLED, false)) return;
            Intent service = new Intent(ACTION_WARMUP);
            service.setClassName(PACKAGE, SERVICE_CLASS);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            Log.i(TAG, "boot warm-up started (connect was last enabled)");
        } catch (Throwable t) {
            Log.e(TAG, "boot warm-up failed type=" + t.getClass().getName());
        }
    }
}
