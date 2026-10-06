package com.flowify.ettea.hooks;

import com.flowify.ettea.xposed.SpotifySymbolResolver;
import com.flowify.ettea.xposed.XpHooks;
import com.flowify.ettea.xposed.XpLog;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Moves playback to a Connect device the way Spotify's own device picker does: through its
 * Connect controller, which sends a TransferRequest to Spotify's core.
 *
 * <p>Why not Android's output switcher ({@link ConnectRouteHandoff}): on Spotify 9.1.x selecting
 * Spotify's MediaRouter2 route only changes local state. The system media controls and Spotify's
 * session then say "Spicy Connect", but the server never moves playback - the web player gets no
 * command and nothing is heard, while Spotify itself still shows this phone.
 *
 * <p>The controller is obfuscated and renamed every release. It is found by what it says: the
 * method that builds the TransferRequest logs "Transfer playback failure" and special-cases
 * "local_device". Its class also holds the static entry the picker uses -
 * {@code start(controller, deviceId, String, int)}: it records the interaction and launches the
 * transfer coroutine, returning the interaction id. The live controller instance is caught as
 * Spotify constructs it.
 */
final class ConnectTransfer {
    private static final String TAG = "[SpicyConnect]";
    private static volatile Method start;
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);

    private ConnectTransfer() {
    }

    static void install(ClassLoader loader, SpotifySymbolResolver symbols) {
        try {
            Method build = symbols.cache.method("connect.transfer.build", () -> {
                var matches = symbols.dexKit().findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("Transfer playback failure", "local_device")));
                for (var data : matches) {
                    Method candidate = data.getMethodInstance(loader);
                    if (candidate != null && !Modifier.isStatic(candidate.getModifiers())) return candidate;
                }
                throw new NoSuchMethodException("Connect transfer builder");
            });
            Class<?> owner = build.getDeclaringClass();
            start = findStart(owner);
            if (start == null) {
                XpLog.log(TAG + " in-app transfer entry not found in " + owner.getName());
                return;
            }
            start.setAccessible(true);
            XpHooks.hookAllConstructors(owner, "connect:transferController",
                    (XpHooks.After) param -> controller = new WeakReference<>(param.thisObject));
            XpLog.log(TAG + " in-app transfer ready: " + owner.getName() + "#" + start.getName());
        } catch (Throwable t) {
            XpLog.log(TAG + " in-app transfer unavailable: " + t);
        }
    }

    /** static String x(Owner, String deviceId, String, int) - the picker's entry. */
    private static Method findStart(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class && p.length == 4
                    && p[0] == owner && p[1] == String.class && p[2] == String.class && p[3] == int.class) {
                return m;
            }
        }
        return null;
    }

    static boolean available() {
        return start != null && controller.get() != null;
    }

    /** Asks Spotify to move playback to this device; false when the path is not available. */
    static boolean transfer(String deviceId) {
        Method entry = start;
        Object target = controller.get();
        if (entry == null || target == null || deviceId == null || deviceId.isEmpty()) return false;
        try {
            Object interaction = entry.invoke(null, target, deviceId, null, 0);
            XpLog.log(TAG + " in-app transfer to " + deviceId + " requested (interaction " + interaction + ")");
            return true;
        } catch (Throwable t) {
            XpLog.log(TAG + " in-app transfer failed: " + (t.getCause() != null ? t.getCause() : t));
            return false;
        }
    }
}
