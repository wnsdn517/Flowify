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
                Settings.GENERAL,
                Settings.LYRICS,
                Settings.GESTURES,
                Settings.LYRICS_SOURCES,
                Settings.NOW_PLAYING,
                Settings.LYRICS_SCREEN,
                Settings.APPLE,
                Settings.TRANSLITERATION,
                Settings.TRANSLATION,
                Settings.AI,
                Settings.ADS,
                Settings.PIP,
                Settings.CONNECT));
    }

    /** Every renderable setting, in panel row order. Rebuilt from the actual settings registry so
     *  schema drift cannot silently break the panel contract. */
    private static final List<Settings.Setting<?>> ORDERED = buildOrdered();

    private static List<Settings.Setting<?>> buildOrdered() {
        List<Settings.Setting<?>> rows = new ArrayList<>();
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section != Settings.INTERNAL) {
                rows.add(setting);
            }
        }
        return Collections.unmodifiableList(rows);
    }

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

    public static SettingUiSpec specOf(Settings.Setting<?> setting) {
        return SettingUiSpec.of(setting);
    }

    /** True for settings rendered by explicit composite rows rather than a kind renderer. */
    public static boolean isComposite(Settings.Setting<?> setting) {
        return specOf(setting).kind == SettingUiSpec.RowKind.COMPOSITE;
    }
}
