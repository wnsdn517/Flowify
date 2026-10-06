package com.flowify.ettea.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Cross-UID trigger for WebPlayerService. Our Xposed code runs inside Spotify's
 * process/UID, and explicit startService calls from there fail to resolve our service
 * ("not found") even with forceQueryable - so commands go here as explicit broadcasts
 * instead, and this receiver (running as us) starts the service locally. One choke point:
 * every player action is forwarded with its extras untouched.
 */
public final class PlayerWarmReceiver extends BroadcastReceiver {
    private static final String TAG = "[SpicyPlayer]";
    private static final String PACKAGE = "com.flowify.ettea";
    private static final String SERVICE_CLASS = "com.flowify.ettea.player.WebPlayerService";
    private static final String ACTION_WARMUP = "com.flowify.ettea.player.WARMUP";
    private static final String ACTION_STOP = "com.flowify.ettea.player.STOP";
    private static final String ACTION_LOGIN = "com.flowify.ettea.player.LOGIN";
    static final String PREFS_NAME = "SpicyPlayer";
    static final String KEY_CONNECT_LAST_ENABLED = "connect_last_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null || intent.getAction() == null) return;
            Log.i(TAG, "receiver got " + intent.getAction());
            // The CONNECT_ENABLED toggle itself lives in Spotify's own process prefs (see
            // SpotifyPlusConfig's class comment - no cross-process IPC by design), which this
            // app's own process cannot read. Remember the last state we were actually told about
            // instead, in OUR OWN prefs, so BootWarmReceiver has something to check at boot -
            // when it's the only moment a background service start is exempted for this app.
            if (ACTION_WARMUP.equals(intent.getAction()) || ACTION_STOP.equals(intent.getAction())) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_CONNECT_LAST_ENABLED, ACTION_WARMUP.equals(intent.getAction()))
                        .apply();
            }
            if (ACTION_LOGIN.equals(intent.getAction()) && replyWithLoginKick(context, intent)) {
                return;
            }
            Intent service = new Intent(intent.getAction());
            service.setClassName(PACKAGE, SERVICE_CLASS);
            if (intent.getExtras() != null) service.putExtras(intent.getExtras());
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
                else context.startService(service);
            } catch (Throwable denied) {
                // Android 12+ refuses a background FGS start from here (this app is never in the
                // foreground itself). Spotify, the sender, usually is - hand it a PendingIntent
                // for the same start so it can fire it with its own foreground privilege.
                if (replyWithKick(context, service)) {
                    Log.i(TAG, "receiver start denied, handed start token back to sender");
                    return;
                }
                Log.e(TAG, "receiver start denied, posting tap-to-start type="
                        + denied.getClass().getSimpleName());
                postTapToStart(context, service);
            }
        } catch (Throwable t) {
            Log.e(TAG, "receiver forward failed type=" + t.getClass().getName());
        }
    }

    /** Spotify can't start our sign-in activity itself (this package isn't visible to it, so
     *  the explicit start doesn't resolve), and we can't from the background. A PendingIntent
     *  for it, fired by Spotify right after the user's tap, carries Spotify's own launch right. */
    private static boolean replyWithLoginKick(Context context, Intent command) {
        try {
            android.os.ResultReceiver reply = command.getParcelableExtra(WebPlayerService.EXTRA_WARM_REPLY);
            if (reply == null) return false;
            Intent login = new Intent(context, WebLoginActivity.class);
            login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            login.putExtra(PlayerSession.EXTRA_UI_LANGUAGE, command.getStringExtra(PlayerSession.EXTRA_UI_LANGUAGE));
            login.putExtra(WebLoginActivity.EXTRA_FROM_SPOTIFY, true);
            android.os.Bundle data = new android.os.Bundle();
            data.putParcelable(WebPlayerService.EXTRA_KICK, PendingIntent.getActivity(context, 8, login,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            reply.send(WebPlayerService.WARM_RESULT_NEEDS_KICK, data);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "login kick failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static boolean replyWithKick(Context context, Intent service) {
        try {
            android.os.ResultReceiver reply = service.getParcelableExtra(WebPlayerService.EXTRA_WARM_REPLY);
            if (reply == null) return false;
            PendingIntent kick = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(context, 7, service,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.getService(context, 7, service,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.os.Bundle data = new android.os.Bundle();
            data.putParcelable(WebPlayerService.EXTRA_KICK, kick);
            reply.send(WebPlayerService.WARM_RESULT_NEEDS_KICK, data);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "kick reply failed type=" + t.getClass().getName());
            return false;
        }
    }

    /** Cold start from background is refused by the OS - the user taps this once, and that
     *  tap carries the exemption a background start never gets. Needs POST_NOTIFICATIONS,
     *  requested once in ExtensionManagerActivity; without the grant this silently drops. */
    private void postTapToStart(Context context, Intent service) {
        try {
            String channel = "spicy_player_start";
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(new NotificationChannel(
                        channel, "Spicy web player starter", NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent tap = PendingIntent.getService(context,
                    service.getAction() == null ? 0 : service.getAction().hashCode(),
                    service, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, channel)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Start Spicy web player?")
                    .setContentText("Tap to start playback service")
                    .setContentIntent(tap)
                    .setAutoCancel(true);
            manager.notify(2002, builder.build());
        } catch (Throwable t) {
            Log.e(TAG, "tap-to-start post failed type=" + t.getClass().getName());
        }
    }
}
