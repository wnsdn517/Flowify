package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the in-app Layout Editor edits, for code outside this package (the settings search, which
 * sends those results to the editor rather than to a panel row that does not exist).
 */
public final class LayoutEditorSettings {
    private LayoutEditorSettings() {
    }

    public static List<Settings.Setting<?>> covered() {
        return Collections.unmodifiableList(Arrays.asList(LyricsLayoutEditController.coveredSettings()));
    }

    /** Whether a covered setting belongs to the Now Playing card editor (else the lyrics one). */
    public static boolean isCardSetting(Settings.Setting<?> setting) {
        return setting != null && setting.key != null && setting.key.startsWith("lyrics_live_card");
    }

    private static volatile String requestedElement;

    /**
     * The editor element a setting is edited on (its {@code editor select} name), so a search
     * result opens the editor on that element with its options showing, not on a bare screen
     * where the setting still has to be found.
     */
    public static String elementFor(Settings.Setting<?> setting) {
        if (setting == null || setting.key == null) return null;
        String key = setting.key;
        if (key.startsWith("lyrics_live_card")) return "card";
        if (setting == Settings.LYRICS_FOCUS_POSITION || setting == Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT) return "focus";
        if (setting == Settings.BACKGROUND_STYLE || setting == Settings.FORCE_DARK_BACKGROUND
                || setting == Settings.EXTRA_DARK_BACKGROUND || setting == Settings.BACKGROUND_RENDER_QUALITY) return "background";
        if (setting == Settings.SKIP_CHIP_POSITION || setting == Settings.SKIP_CHIP_STYLE) return "skip";
        if (setting == Settings.FOLLOW_CHIP_POSITION || setting == Settings.FOLLOW_CHIP_STYLE) return "follow";
        if (setting == Settings.LIKED_SONGS_BUTTON || setting == Settings.CHROME_CLUSTER_POSITION
                || setting == Settings.CHROME_CLUSTER_LAYOUT || setting == Settings.SHOW_FULLSCREEN_BACK_BUTTON
                || setting == Settings.FULLSCREEN_CONTROLS || setting == Settings.PANEL_MEDIA_CONTROLS) return "top_bar";
        if (setting == Settings.TRACK_INFO_TEXT_ALIGN || setting == Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE
                || setting == Settings.TRACK_INFO_SHOW_TITLE || setting == Settings.TRACK_INFO_SHOW_ARTIST
                || setting == Settings.TRACK_INFO_SHOW_ALBUM || setting == Settings.TRACK_INFO_TEXT_SIZE
                || setting == Settings.TRACK_INFO_TEXT_SIZE_CUSTOM || setting == Settings.TRACK_INFO_BACKGROUND
                || setting == Settings.TRACK_INFO_TEXT_OVERFLOW) return "track_text";
        if (key.startsWith("lyrics_track_info") || key.startsWith("track_info")
                || setting == Settings.TRACK_INFO_POSITION || setting == Settings.TRACK_INFO_LYRICS_FLOW
                || setting == Settings.TRACK_INFO_ART_RADIUS || setting == Settings.TRACK_INFO_ART_SIZE
                || setting == Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP) return "artwork";
        return "lyrics";
    }

    /** Asks the next editor that opens to select this element (consumed once). */
    public static void requestElement(String element) {
        requestedElement = element;
    }

    static String consumeRequestedElement() {
        String element = requestedElement;
        requestedElement = null;
        return element;
    }
}
