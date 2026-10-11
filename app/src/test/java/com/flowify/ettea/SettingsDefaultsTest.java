package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.language.KoreanDisplayMode;

import org.junit.Test;

public class SettingsDefaultsTest {
    @Test
    public void quietReadableDefaultsAreOptInForProcessing() {
        assertEquals("Fullscreen", Settings.LIVE_CARD_TAP_TARGET.defaultValue);
        assertEquals("lyrics_live_card_tap_target", Settings.LIVE_CARD_TAP_TARGET.key);
        assertEquals(java.util.Arrays.asList("Fullscreen", "Artwork"),
                Settings.LIVE_CARD_TAP_TARGET.allowedValues);
        assertEquals("spacious", Settings.LINE_SPACING.defaultValue);
        assertEquals("dots", Settings.INTERLUDE_ICON.defaultValue);
        assertEquals("On demand", Settings.AUTO_SKIP_INTRO_OUTRO.defaultValue);
        assertEquals("apple", Settings.LYRICS_FONT.defaultValue);
        assertEquals(java.util.Arrays.asList("spotify", "apple", "custom"),
                Settings.LYRICS_FONT.allowedValues);
        assertEquals("custom", Settings.LYRICS_FONT.coerce("custom"));
        assertEquals("apple", Settings.LYRICS_FONT.coerce("bogus"));
        // The pre-"spotify" alias still means the Spotify font, not the (new) default.
        assertEquals("spotify", Settings.LYRICS_FONT.coerce("default"));
        assertEquals("", Settings.LYRICS_FONT_CUSTOM_PATH.defaultValue);
        assertEquals(Settings.INTERNAL, Settings.LYRICS_FONT_CUSTOM_PATH.section); // edited in the layout editor
        assertEquals("Both", Settings.STATUS_BAR_HIDDEN_MODE.defaultValue);
        assertEquals(Settings.LYRICS, Settings.STATUS_BAR_HIDDEN_MODE.section);
        assertEquals(java.util.Arrays.asList("Off", "Portrait", "Landscape", "Both"),
                Settings.STATUS_BAR_HIDDEN_MODE.allowedValues);
        assertEquals("Both", Settings.STATUS_BAR_HIDDEN_MODE.coerce("Both"));
        assertEquals("Both", Settings.STATUS_BAR_HIDDEN_MODE.coerce("bogus"));
        assertEquals(java.util.Arrays.asList("Off", "On demand", "Auto"),
                Settings.AUTO_SKIP_INTRO_OUTRO.allowedValues);
        assertEquals("Auto", Settings.AUTO_SKIP_INTRO_OUTRO.coerce("Auto"));
        assertEquals("On demand", Settings.AUTO_SKIP_INTRO_OUTRO.coerce("bogus"));
        assertTrue(Settings.MINI_PLAYER_LYRICS_ICON.defaultValue);
        assertFalse(Settings.KARAOKE_ORIGINAL_LYRICS.defaultValue);
        assertEquals("Single tap", Settings.PANEL_MEDIA_CONTROLS.defaultValue);
        assertEquals(java.util.Arrays.asList("Off", "Single tap", "Double tap"),
                Settings.PANEL_MEDIA_CONTROLS.allowedValues);
        assertEquals("Double tap", Settings.PANEL_MEDIA_CONTROLS.coerce("Double tap"));
        assertEquals("Single tap", Settings.PANEL_MEDIA_CONTROLS.coerce("bogus"));

        // Apple Music is the only animation; the card's removed modes coerce to it.
        assertEquals("Apple Music", Settings.LIVE_CARD_ANIMATION.defaultValue);
        assertEquals(java.util.Arrays.asList("Minimal", "Apple Music"),
                Settings.LIVE_CARD_ANIMATION.allowedValues);
        assertEquals("Apple Music", Settings.LIVE_CARD_ANIMATION.coerce("Karaoke fill"));
        assertEquals("Apple Music", Settings.LIVE_CARD_ANIMATION.coerce("Spotlight word"));
        assertEquals("Spring", Settings.SCROLL_MOTION.defaultValue);
        assertEquals(java.util.Arrays.asList("Static", "Smooth", "Spring"),
                Settings.SCROLL_MOTION.allowedValues);
        assertTrue(Settings.APPLE_FADE_PASSED_LINES.defaultValue);
        assertTrue(Settings.APPLE_COMPACT_TEXT.defaultValue);
        assertTrue(Settings.LINE_SLIDE_ANIMATION.defaultValue);
        assertTrue(Settings.APPLE_LIFT.defaultValue);
        assertEquals(Settings.INTERNAL, Settings.APPLE_LIFT.section); // edited in the layout editor
        assertTrue(Settings.AUTO_RESUME_FOLLOW.defaultValue);
        assertEquals(3, (int) Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS.defaultValue);
        assertEquals(Settings.LYRICS, Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS.section);
        assertEquals("Auto", Settings.SKIP_CHIP_STYLE.defaultValue);
        assertEquals("Right", Settings.SKIP_CHIP_POSITION.defaultValue);
        assertEquals("Right", Settings.FOLLOW_CHIP_POSITION.defaultValue);
        assertEquals("Auto", Settings.FOLLOW_CHIP_STYLE.defaultValue);
        assertEquals(Settings.INTERNAL, Settings.SKIP_CHIP_STYLE.section);
        assertEquals(Settings.INTERNAL, Settings.SKIP_CHIP_POSITION.section);
        assertEquals(Settings.INTERNAL, Settings.FOLLOW_CHIP_POSITION.section);
        assertEquals(Settings.INTERNAL, Settings.FOLLOW_CHIP_STYLE.section);
        assertFalse(Settings.HYPERGLOW_ENABLED.defaultValue);
        assertEquals("en", Settings.UI_LANGUAGE.defaultValue);
        // Default stays Google draft until device comparison proves another flow better; adding
        // the preview experiment must not migrate either existing stored choice.
        assertEquals("Google draft", Settings.AI_TRANSLATION_PIPELINE.defaultValue);
        assertEquals(java.util.Arrays.asList("Google preview", "Google draft", "AI only"),
                Settings.AI_TRANSLATION_PIPELINE.allowedValues);
        assertEquals("Google preview", Settings.AI_TRANSLATION_PIPELINE.coerce("Google preview"));
        assertEquals("Google draft", Settings.AI_TRANSLATION_PIPELINE.coerce("Google draft"));
        assertEquals("AI only", Settings.AI_TRANSLATION_PIPELINE.coerce("AI only"));
        assertEquals("Layered", Settings.AI_PRONUNCIATION_SOURCE.allowedValues.get(0));
        assertEquals("AI only", Settings.AI_PRONUNCIATION_SOURCE.allowedValues.get(1));

        assertFalse(Settings.TRANSLITERATION_ENABLED.defaultValue);
        assertFalse(Settings.TRANSLATION_ENABLED.defaultValue);
        assertEquals("google_unofficial", Settings.TRANSLATION_BACKEND.defaultValue);
        assertEquals(Settings.INTERNAL, Settings.TRANSLATION_BACKEND.section);
        assertEquals(2, Settings.TRANSLATION_BACKEND.allowedValues.size());
        assertEquals("provider", Settings.TRANSLATION_BACKEND.coerce("provider"));
        assertFalse(Settings.NATIVE_SPICY_ROMANIZATION.defaultValue);
        assertFalse(Settings.NATIVE_SPICY_TRANSLATION.defaultValue);

        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, Settings.JAPANESE_READING_MODE.defaultValue);
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_PINYIN, Settings.CHINESE_MODE.defaultValue);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, Settings.KOREAN_ROMANIZATION.defaultValue);
        assertTrue(Settings.KOREAN_ROMANIZATION.allowedValues.contains("Off"));
        assertEquals("Off", Settings.KOREAN_ROMANIZATION.coerce("Off"));
        assertEquals("Off", Settings.KOREAN_ROMANIZATION.coerce("off"));

        assertEquals(Integer.valueOf(51), Settings.LYRICS_BLUR_INTENSITY.defaultValue);
        assertEquals(25, Settings.LYRICS_BLUR_INTENSITY.minValue);
        assertEquals(250, Settings.LYRICS_BLUR_INTENSITY.maxValue);
        assertEquals(5, Settings.LYRICS_BLUR_INTENSITY.stepValue);

        assertEquals(Integer.valueOf(59), Settings.FURIGANA_BRIGHTNESS.defaultValue);
        assertEquals(20, Settings.FURIGANA_BRIGHTNESS.minValue);
        assertEquals(100, Settings.FURIGANA_BRIGHTNESS.maxValue);
        assertEquals(5, Settings.FURIGANA_BRIGHTNESS.stepValue);

        assertEquals(Integer.valueOf(100), Settings.FURIGANA_POSITION_PERCENT.defaultValue);
        assertEquals(40, Settings.FURIGANA_POSITION_PERCENT.minValue);
        assertEquals(200, Settings.FURIGANA_POSITION_PERCENT.maxValue);
        assertEquals(10, Settings.FURIGANA_POSITION_PERCENT.stepValue);

        // Text glow defaults ON since the B322+ desktop-parity rework made it subtle and cheap.
        assertFalse(Settings.ALIGNED_PER_WORD_ROMAJI.defaultValue);
        assertTrue(Settings.ENABLE_GLOW_BLUR.defaultValue);
        assertEquals("Heavy", Settings.ENABLE_LINE_BLUR.defaultValue);
        assertEquals(java.util.Arrays.asList("Off", "Slight", "Heavy"),
                Settings.ENABLE_LINE_BLUR.allowedValues);
        assertEquals("Heavy", Settings.ENABLE_LINE_BLUR.coerce("Heavy"));
        assertEquals("Heavy", Settings.ENABLE_LINE_BLUR.coerce("bogus"));
        assertTrue(Settings.FORCE_DARK_BACKGROUND.defaultValue);
        assertEquals(Integer.valueOf(35), Settings.EXTRA_DARK_BACKGROUND.defaultValue);
    }
}
