package com.eza.spicyex.hooks;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.xposed.SpotifySymbolResolver;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Settings.HIDE_SPOTIFY_LYRICS_CARD: Spotify's own "Lyrics" / "Lyrics preview" card under the
 * player is not composed at all.
 *
 * <p>The card is a Compose function, so there is no view or resource id to hide; it is found by
 * the test tag it carries ({@code "#LyricsCardTag"}, a string R8 leaves alone) and its call is
 * skipped - nothing of it is built, measured or drawn, and its lyrics flow never starts.
 *
 * <p>The decision is taken once per composition (per Composer) and kept for it: switching a
 * composable call on or off inside a composition that already has it would leave Compose's slot
 * table out of step. A changed setting applies to the next player screen.
 */
final class SpotifyLyricsCardHider {
    private static final String TAG = NativeSpicyLyricsHook.TAG;
    private static final Map<Object, Boolean> DECISIONS = new WeakHashMap<>();
    private static volatile Context context;

    private SpotifyLyricsCardHider() {
    }

    static void install(Context appContext, ClassLoader loader, SpotifySymbolResolver symbols) {
        context = appContext;
        try {
            Method card = symbols.cache.method("nowplaying.lyricsCard", () -> {
                var matches = symbols.dexKit().findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("#LyricsCardTag")));
                for (var data : matches) {
                    Method candidate = data.getMethodInstance(loader);
                    if (candidate != null && Modifier.isStatic(candidate.getModifiers())
                            && composerIndex(candidate) >= 0) {
                        return candidate;
                    }
                }
                throw new NoSuchMethodException("lyrics card composable");
            });
            int composer = composerIndex(card);
            if (composer < 0) {
                XpLog.log(TAG + " lyrics card hider: no Composer parameter on " + card);
                return;
            }
            XpHooks.hookBefore(card, "nowplaying:lyricsCard", param -> {
                Object key = param.args[composer];
                if (key == null) return;
                boolean hide;
                synchronized (DECISIONS) {
                    Boolean decided = DECISIONS.get(key);
                    if (decided == null) {
                        decided = hiddenBySetting();
                        DECISIONS.put(key, decided);
                    }
                    hide = decided;
                }
                if (hide) param.setResult(null);
            });
            XpLog.log(TAG + " lyrics card hider ready: " + card.getDeclaringClass().getName()
                    + "#" + card.getName());
        } catch (Throwable t) {
            XpLog.log(TAG + " lyrics card hider unavailable: " + t);
        }
    }

    private static boolean hiddenBySetting() {
        Context current = context;
        if (current == null) return false;
        try {
            return Boolean.TRUE.equals(SpotifyPlusConfig.from(current).get(Settings.HIDE_SPOTIFY_LYRICS_CARD));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Index of the androidx.compose.runtime.Composer parameter (its name survives R8). */
    private static int composerIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if ("androidx.compose.runtime.Composer".equals(types[i].getName())) return i;
        }
        return -1;
    }
}
