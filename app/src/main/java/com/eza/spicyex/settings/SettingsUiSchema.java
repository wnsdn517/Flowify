package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Explicit UI registry over the {@link Settings} persistence schema.
 *
 * <p>Panel section order and row order used to be implicit contracts of declaration order in
 * {@code Settings.ALL} — a different file's line order decided what the panel showed and in
 * which order. Both are stated here as data instead, and {@code SettingsUiSchemaOrderTest}
 * fails if this list stops matching the renderable setting set exactly.
 *
 * <p>Composite settings map to hand-built rows; everything else maps to one renderer per kind.
 * DEBUG renders separately; INTERNAL never renders and is deliberately absent below.
 */
public final class SettingsUiSchema {
    private SettingsUiSchema() {
    }

    /** Panel section order, first to last. DEBUG is rendered separately; INTERNAL never renders. */
    public static List<Settings.Section> orderedSections() {
        return Collections.unmodifiableList(Arrays.asList(
                Settings.LYRICS,
                Settings.GESTURES,
                Settings.LYRICS_SOURCES,
                Settings.LYRICS_SCREEN,
                Settings.TRANSLITERATION,
                Settings.TRANSLATION,
                Settings.AI,
                Settings.PIP,
                Settings.AD_FREE,
                Settings.LABS));
    }

    /**
     * Every renderable setting in panel row order. Grouped by section in the same order as
     * {@link #orderedSections()}, so the panel can filter this list per section and keep both
     * the grouping and the intra-section order explicit.
     */
    private static final List<Settings.Setting<?>> ORDERED = Collections.unmodifiableList(Arrays.asList(
            // Behavior
            Settings.UI_LANGUAGE,
            Settings.TAP_SEEK_MODE,
            Settings.DOUBLE_TAP_LIKE,
            Settings.DOUBLE_TAP_LIKE_MARK,
            Settings.DOUBLE_TAP_LIKE_EFFECT,
            Settings.STAY_IN_LYRICS,
            Settings.AUTO_RESUME_FOLLOW,
            Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS,
            Settings.AUTO_SKIP_INTRO_OUTRO,
            Settings.STATUS_BAR_HIDDEN_MODE,
            Settings.TRANSITION_FEEL,
            Settings.DEVICE_CHANGE_BANNER,
            Settings.SYNC_OFFSET_MS,
            Settings.MINI_PLAYER_LYRICS_ICON,
            Settings.LYRICS_IN_PLAYER,
            Settings.HIDE_SPOTIFY_LYRICS_CARD,
            Settings.PLAYER_APPLE_STYLE,
            Settings.PLAYER_APPLE_TIME,
            Settings.PLAYER_ANIMATED_ARTWORK,
            Settings.HYPERGLOW_ENABLED,
            Settings.LONG_PRESS_SHARE,
            Settings.SHARE_GESTURE_HINT,
            // Lyrics sources
            Settings.LYRICS_SOURCE_MODE,
            Settings.LYRICS_SOURCE_OVERRIDE,
            Settings.SPICY_MANUAL_TOKEN,
            Settings.LYRICS_SOURCE_ORDER,
            Settings.CACHE_SIZE,
            // Layout editor (tap behaviour stays in the panel; looks are edited on-screen)
            // Editor-managed (PanelPolicy hides these); the schema test requires every
            // non-internal setting listed exactly once.
            Settings.FULLSCREEN_CONTROLS,
            Settings.ANIMATION_STYLE,
            // Reading & transliteration
            Settings.DOWNLOAD_LANGUAGE_MODELS,
            Settings.TRANSLITERATION_ENABLED,
            Settings.ALIGNED_PER_WORD_ROMAJI,
            Settings.JAPANESE_READING_MODE,
            Settings.FURIGANA_BRIGHTNESS,
            Settings.FURIGANA_POSITION_PERCENT,
            Settings.CHINESE_MODE,
            Settings.KOREAN_ROMANIZATION,
            Settings.CHINESE_TONES,
            Settings.CYRILLIC_MODE,
            Settings.CYRILLIC_KEEP_SIGNS,
            // Translation
            Settings.TRANSLATION_ENABLED,
            Settings.TRANSLATION_TARGET,
            Settings.TRANSLATION_BRIGHTNESS,
            // AI
            Settings.AI_ENABLED,
            Settings.AI_PROVIDER,
            Settings.AI_DEEPSEEK_REASONING,
            Settings.AI_TRANSLATION_MODE,
            Settings.AI_TRANSLATION_PIPELINE,
            Settings.AI_PRONUNCIATION_MODE,
            Settings.AI_PRONUNCIATION_SOURCE,
            Settings.AI_BUTTON_BEHAVIOR,
            // Picture-in-picture
            Settings.PIP_ENABLED,
            Settings.PIP_ON_CLOSE,
            Settings.PIP_SHAPE,
            Settings.PIP_CONTROLS,
            Settings.PIP_SONG_INFO,
            Settings.PIP_LEAVE_SPOTIFY,
            // Ad-free listening
            Settings.AD_MODE,
            Settings.AD_MUSIC_THEME,
            Settings.CONNECT_ENABLED,
            Settings.CONNECT_AUTO_SWITCH,
            Settings.CONNECT_NETWORK_RECOVERY,
            // Labs
            Settings.KARAOKE_ORIGINAL_LYRICS,
            Settings.CONTEXT_TRANSLATION,
            Settings.ARTWORK_FRAME_FIT));

    /** Every renderable setting, in panel row order. */
    public static List<Settings.Setting<?>> orderedSettings() {
        return ORDERED;
    }

    /** Renderable settings belonging to one section, in panel row order. */
    public static List<Settings.Setting<?>> orderedSettings(Settings.Section section) {
        List<Settings.Setting<?>> items = new ArrayList<>();
        for (Settings.Setting<?> setting : ORDERED) {
            if (setting.section == section) items.add(setting);
        }
        return items;
    }

    /** Which labelled group of its page a setting sits in: a page lists several small groups
     *  rather than one long run of rows. The id names the caption string
     *  {@code settings_group_<id>}; settings absent here are shown without a caption. */
    private static final java.util.Map<Settings.Setting<?>, String> GROUPS = new java.util.HashMap<>();

    private static void group(String id, Settings.Setting<?>... settings) {
        for (Settings.Setting<?> setting : settings) GROUPS.put(setting, id);
    }

    static {
        group("general_language", Settings.UI_LANGUAGE);
        group("general_lyrics", Settings.STAY_IN_LYRICS, Settings.AUTO_RESUME_FOLLOW,
                Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS, Settings.AUTO_SKIP_INTRO_OUTRO,
                Settings.STATUS_BAR_HIDDEN_MODE, Settings.TRANSITION_FEEL, Settings.DEVICE_CHANGE_BANNER);
        group("general_timing", Settings.SYNC_OFFSET_MS);
        group("general_outside", Settings.MINI_PLAYER_LYRICS_ICON, Settings.LYRICS_IN_PLAYER,
                Settings.HIDE_SPOTIFY_LYRICS_CARD, Settings.PLAYER_APPLE_STYLE,
                Settings.PLAYER_APPLE_TIME, Settings.HYPERGLOW_ENABLED);
        group("gestures_seek", Settings.TAP_SEEK_MODE);
        group("gestures_like", Settings.DOUBLE_TAP_LIKE, Settings.DOUBLE_TAP_LIKE_MARK,
                Settings.DOUBLE_TAP_LIKE_EFFECT);
        group("gestures_share", Settings.LONG_PRESS_SHARE, Settings.SHARE_GESTURE_HINT);
        group("sources_sources", Settings.LYRICS_SOURCE_MODE, Settings.LYRICS_SOURCE_OVERRIDE,
                Settings.SPICY_MANUAL_TOKEN, Settings.LYRICS_SOURCE_ORDER);
        group("sources_karaoke", Settings.KARAOKE_ORIGINAL_LYRICS);
        group("labs_translation", Settings.CONTEXT_TRANSLATION);
        group("labs_artwork", Settings.ARTWORK_FRAME_FIT);
        group("sources_storage", Settings.CACHE_SIZE);
        group("pip_open", Settings.PIP_ENABLED, Settings.PIP_ON_CLOSE);
        group("pip_window", Settings.PIP_SHAPE, Settings.PIP_CONTROLS, Settings.PIP_SONG_INFO,
                Settings.PIP_LEAVE_SPOTIFY);
        group("adfree_ads", Settings.AD_MODE, Settings.AD_MUSIC_THEME);
        group("adfree_connect", Settings.CONNECT_ENABLED, Settings.CONNECT_AUTO_SWITCH,
                Settings.CONNECT_NETWORK_RECOVERY);
    }

    /** The group id of a setting within its page, or null for none. */
    public static String groupOf(Settings.Setting<?> setting) {
        return GROUPS.get(setting);
    }

    public static SettingUiSpec.RowKind kindOf(Settings.Setting<?> setting) {
        return SettingUiSpec.kindOf(setting);
    }

    /** True for settings rendered by explicit composite rows rather than a kind renderer. */
    public static boolean isComposite(Settings.Setting<?> setting) {
        return kindOf(setting) == SettingUiSpec.RowKind.COMPOSITE;
    }
}
