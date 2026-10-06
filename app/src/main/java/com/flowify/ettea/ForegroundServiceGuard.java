package com.eza.spicyex;

import android.app.Notification;
import android.app.Service;
import android.util.Log;

/**
 * Promotes a service to the foreground without letting the platform's refusal take the whole
 * process down with it.
 *
 * <p>{@link Service#startForeground} throws - {@code ForegroundServiceStartNotAllowedException}
 * and friends - whenever the platform decides this particular start wasn't eligible: a cold
 * background start with no exemption, a missing runtime permission for the declared type, or,
 * since Android 15, <em>any</em> mediaPlayback service whose start came from a BOOT_COMPLETED
 * receiver. Thrown out of {@code onCreate} that surfaces as "Unable to create service ..." and
 * kills the app (seen live on Android 16: boot warm-up crashed the module every single boot).
 *
 * <p>The service here is always the optional part; a crash never is. Callers get a plain
 * boolean back and stop themselves when it's false - a service started via
 * {@code startForegroundService} that never reaches the foreground is killed by the platform's
 * own watchdog seconds later anyway, so stopping immediately is both cleaner and quieter.
 */
public final class ForegroundServiceGuard {
    private ForegroundServiceGuard() {
    }

    /** @return true once the service is in the foreground, false if the platform refused. */
    public static boolean promote(Service service, int notificationId, Notification notification,
                                  String logTag) {
        try {
            service.startForeground(notificationId, notification);
            return true;
        } catch (Throwable t) {
            Log.e(logTag, "startForeground refused (" + t.getClass().getSimpleName() + "): "
                    + t.getMessage());
            return false;
        }
    }
}
