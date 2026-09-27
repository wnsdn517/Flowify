package com.eza.spicyex.player;

import android.webkit.WebView;

/**
 * Shared holder for the single headless player WebView and its login/page state. A plain
 * WebView needs no runtime object and its cookies live in the app-wide CookieManager, so the
 * login activity automatically shares the session without any handoff.
 */
final class PlayerSession {
    static volatile WebView webview;
    /** Mirrors the sp_dc session cookie, confirmed against the live page when one is loaded
     *  (see WebPlayerService#checkLogin). */
    static volatile boolean loggedIn;
    static volatile String lastPageUrl = "";
    /** The Spicy EX interface language the Spotify side runs in (its own setting, not the
     *  system locale), so our own screens and notification match the settings panel. */
    private static volatile String uiLanguage;
    private static final String PREFS = "SpicyPlayerUi";
    private static final String KEY_LANGUAGE = "ui_language";
    static final String EXTRA_UI_LANGUAGE = "ui_language";

    private PlayerSession() {
    }

    /** Adopts the language a command carried; remembered for starts that carry none. */
    static void noteUiLanguage(android.content.Context context, android.content.Intent intent) {
        String language = intent == null ? null : intent.getStringExtra(EXTRA_UI_LANGUAGE);
        if (language == null || language.isEmpty() || language.equals(uiLanguage)) return;
        uiLanguage = language;
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putString(KEY_LANGUAGE, language).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Resources in the interface language, or the context's own when none is known. */
    static android.content.res.Resources resources(android.content.Context context) {
        String language = uiLanguage;
        if (language == null) {
            try {
                language = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                        .getString(KEY_LANGUAGE, null);
                uiLanguage = language;
            } catch (Throwable ignored) {
            }
        }
        if (language == null || language.isEmpty()) return context.getResources();
        try {
            android.content.res.Configuration config =
                    new android.content.res.Configuration(context.getResources().getConfiguration());
            config.setLocale(java.util.Locale.forLanguageTag(language));
            return context.createConfigurationContext(config).getResources();
        } catch (Throwable t) {
            return context.getResources();
        }
    }

    private static final String KEY_AD_MODE = "ad_mode";
    private static final String KEY_AD_MUSIC_THEME = "ad_music_theme";
    /** Mirrors Settings.AD_MODE / AD_MUSIC_THEME on the Spotify side ("Off", "Mute",
     *  "Play music instead"); null until the first command carrying them. */
    private static volatile String adMode;
    private static volatile String adMusicTheme;

    /** Adopts the ad settings a command carried; remembered for starts that carry none. */
    static void noteAdSettings(android.content.Context context, android.content.Intent intent) {
        String mode = intent == null ? null : intent.getStringExtra(KEY_AD_MODE);
        String theme = intent == null ? null : intent.getStringExtra(KEY_AD_MUSIC_THEME);
        if (mode == null || mode.isEmpty()) return;
        if (mode.equals(adMode) && (theme == null || theme.equals(adMusicTheme))) return;
        adMode = mode;
        if (theme != null) adMusicTheme = theme;
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putString(KEY_AD_MODE, mode).putString(KEY_AD_MUSIC_THEME, adMusicTheme).apply();
        } catch (Throwable ignored) {
        }
    }

    static String adMode(android.content.Context context) {
        loadAdSettings(context);
        return adMode;
    }

    static String adMusicTheme(android.content.Context context) {
        loadAdSettings(context);
        return adMusicTheme;
    }

    private static void loadAdSettings(android.content.Context context) {
        if (adMode != null) return;
        try {
            android.content.SharedPreferences p =
                    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            adMusicTheme = p.getString(KEY_AD_MUSIC_THEME, null);
            adMode = p.getString(KEY_AD_MODE, null);
        } catch (Throwable ignored) {
        }
    }

    static void notePageUrl(String url) {
        lastPageUrl = url == null ? "" : url;
    }
}
