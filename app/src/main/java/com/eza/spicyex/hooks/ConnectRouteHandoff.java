package com.eza.spicyex.hooks;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.xposed.XpLog;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Hands Spotify's playback to the web player the way the user would by picking it in the
 * device list - but through Android's own output-switcher API instead of Spotify's UI.
 *
 * <p>Spotify publishes every Connect device it knows as a MediaRouter2 route of its own
 * (SpotifyMediaRouteProviderService, feature {@link #FEATURE}), and selecting one makes Spotify's
 * core perform an ordinary phone-initiated transfer. That matters: pulling playback off a phone
 * from the web player's side is refused for Free accounts (and would need guessing which device
 * is the phone), while the phone handing it over itself always works. Runs inside Spotify's
 * process, where these routes are visible to their own app.
 *
 * <p>Two timing facts, both seen on-device: the provider only lists devices some seconds after
 * discovery starts (so {@link #prepare} starts it when Spotify comes to the front, well before
 * anyone presses play), and a transfer issued in the same instant local playback starts gets
 * overridden by Spotify's own start-up of that playback (so {@link #transfer} waits a moment and
 * then verifies the route really got selected, retrying a bounded number of times).
 */
final class ConnectRouteHandoff {
    private static final String TAG = "[SpicyConnect]";
    private static final String FEATURE = "com.spotify.music.SPOTIFY_CONNECT";
    private static final long SETTLE_MS = 600L;
    private static final long POLL_MS = 500L;
    private static final long FIND_WINDOW_MS = 30_000L;
    private static final long VERIFY_MS = 3000L;
    private static final int MAX_ATTEMPTS = 3;
    /** Discovery started by prepare() is dropped after this if no hand-off ever needs it. */
    private static final long DISCOVERY_IDLE_MS = 3 * 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable STOP_DISCOVERY = ConnectRouteHandoff::stopDiscovery;
    // Main thread only.
    private static MediaRouter2 router;
    private static MediaRouter2.RouteCallback discovery;

    private ConnectRouteHandoff() {
    }

    /** Starts route discovery ahead of time; cheap (Spotify's device list is cloud-side). */
    static void prepare(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        MAIN.post(() -> {
            startDiscovery(context);
            MAIN.removeCallbacks(STOP_DISCOVERY);
            MAIN.postDelayed(STOP_DISCOVERY, DISCOVERY_IDLE_MS);
        });
    }

    enum Result { SWITCHED, SKIPPED, FAILED }

    interface Done {
        void onDone(Result result);
    }

    /**
     * @param deviceId       the web player's current Connect device id, or null if unknown
     * @param activeDeviceId the account's active device as the web player last saw it; when it
     *                       is another device Spotify lists (a desktop, a speaker...), playback
     *                       is left alone - null skips that check (playback is on this phone)
     * @param done           told how it ended, or null
     */
    static void transfer(Context context, String deviceId, String activeDeviceId, Done done) {
        if (Build.VERSION.SDK_INT < 30) {
            if (done != null) done.onDone(Result.FAILED);
            return;
        }
        MAIN.post(() -> {
            MAIN.removeCallbacks(STOP_DISCOVERY);
            startDiscovery(context);
            if (router != null) {
                MAIN.postDelayed(new Attempt(deviceId, activeDeviceId, done), SETTLE_MS);
            } else if (done != null) {
                done.onDone(Result.FAILED);
            }
        });
    }

    private static void startDiscovery(Context context) {
        if (discovery != null) return;
        try {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            router = MediaRouter2.getInstance(app);
            discovery = new MediaRouter2.RouteCallback() {
            };
            router.registerRouteCallback(MAIN::post, discovery,
                    new RouteDiscoveryPreference.Builder(Collections.singletonList(FEATURE), true).build());
        } catch (Throwable t) {
            discovery = null;
            XpLog.log(TAG + " route discovery unavailable type=" + t.getClass().getName());
        }
    }

    private static void stopDiscovery() {
        if (discovery == null || router == null) return;
        try {
            router.unregisterRouteCallback(discovery);
        } catch (Throwable ignored) {
        }
        discovery = null;
    }

    private static final class Attempt implements Runnable {
        private final String deviceId;
        private final String activeDeviceId;
        private final Done done;
        private final long findDeadline = SystemClock.elapsedRealtime() + SETTLE_MS + FIND_WINDOW_MS;
        private int attempts;
        private boolean logged;
        private MediaRoute2Info target;

        Attempt(String deviceId, String activeDeviceId, Done done) {
            this.deviceId = deviceId;
            this.activeDeviceId = activeDeviceId == null ? null : activeDeviceId.toLowerCase(Locale.ROOT);
            this.done = done;
        }

        private void end(Result result) {
            stopDiscovery();
            if (done != null) {
                try {
                    done.onDone(result);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void run() {
            try {
                if (target != null && isSelected(target)) {
                    XpLog.log(TAG + " playback now on \"" + target.getName() + "\"");
                    end(Result.SWITCHED);
                    return;
                }
                if (target == null) {
                    List<MediaRoute2Info> routes = router.getRoutes();
                    target = pick(routes, deviceId, !logged && !routes.isEmpty());
                    if (!routes.isEmpty()) logged = true;
                    if (target == null) {
                        if (SystemClock.elapsedRealtime() < findDeadline) {
                            MAIN.postDelayed(this, POLL_MS);
                        } else {
                            StringBuilder seen = new StringBuilder();
                            for (MediaRoute2Info r : routes) seen.append(r.getName()).append(r.getFeatures().contains(FEATURE) ? "*" : "").append(' ');
                            XpLog.log(TAG + " web player route not found (id=" + deviceId + ", routes: " + seen + ")");
                            end(Result.FAILED);
                        }
                        return;
                    }
                    if (activeDeviceId != null && deviceId != null
                            && activeDeviceId.equals(deviceId.toLowerCase(Locale.ROOT))) {
                        XpLog.log(TAG + " web player is already the active device");
                        end(Result.SKIPPED);
                        return;
                    }
                    MediaRoute2Info other = activeDeviceId == null ? null : routeFor(routes, activeDeviceId);
                    if (other != null && other != target) {
                        XpLog.log(TAG + " \"" + other.getName() + "\" is the active device, leaving it");
                        end(Result.SKIPPED);
                        return;
                    }
                }
                if (attempts++ >= MAX_ATTEMPTS) {
                    XpLog.log(TAG + " hand-off not applied after " + MAX_ATTEMPTS + " attempts");
                    end(Result.FAILED);
                    return;
                }
                XpLog.log(TAG + " handing playback to route \"" + target.getName() + "\" attempt " + attempts
                        + " controllers=" + router.getControllers().size());
                router.transferTo(target);
                MAIN.postDelayed(this, VERIFY_MS);
            } catch (Throwable t) {
                XpLog.log(TAG + " route transfer failed type=" + t.getClass().getName());
                end(Result.FAILED);
            }
        }

        private boolean isSelected(MediaRoute2Info route) {
            try {
                for (MediaRouter2.RoutingController controller : router.getControllers()) {
                    for (MediaRoute2Info selected : controller.getSelectedRoutes()) {
                        if (route.getId().equals(selected.getId())) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private static MediaRoute2Info routeFor(List<MediaRoute2Info> routes, String deviceId) {
        for (MediaRoute2Info route : routes) {
            if (route.getId() != null && route.getId().toLowerCase(Locale.ROOT).contains(deviceId)) return route;
        }
        return null;
    }

    /** Exact device id first (route ids embed it); by name only when it is unambiguous. */
    private static MediaRoute2Info pick(List<MediaRoute2Info> routes, String deviceId, boolean log) {
        MediaRoute2Info byName = null;
        int nameMatches = 0;
        String id = deviceId == null ? null : deviceId.toLowerCase(Locale.ROOT);
        for (MediaRoute2Info route : routes) {
            if (!route.getFeatures().contains(FEATURE)) continue;
            String rid = route.getId() == null ? "" : route.getId().toLowerCase(Locale.ROOT);
            String name = route.getName() == null ? "" : route.getName().toString();
            if (log) XpLog.log(TAG + " route id=" + rid + " name=" + name);
            if (id != null && rid.contains(id)) return route;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("spicy connect") || lower.startsWith("web player")) {
                byName = route;
                nameMatches++;
            }
        }
        return nameMatches == 1 ? byName : null;
    }
}
