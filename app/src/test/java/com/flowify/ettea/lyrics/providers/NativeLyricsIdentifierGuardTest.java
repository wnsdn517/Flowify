package com.flowify.ettea.lyrics.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flowify.ettea.lyrics.LyricsLine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NativeLyricsIdentifierGuardTest {
    private static List<LyricsLine> lines(String... texts) {
        List<LyricsLine> out = new ArrayList<>();
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            out.add(line);
        }
        return out;
    }

    @Test
    public void viewIdListsAreNotLyrics() {
        assertTrue(NativeLyricsSource.looksLikeIdentifier(
                "ime_window_insets_space,fragment_container_bottom_overlap_touch_event_consumer"));
        assertTrue(NativeLyricsSource.mostlyIdentifiers(lines(
                "now_playing_view_container,navigation_bar,limited_experience_indicator",
                "ime_window_insets_space,fragment_container_bottom_overlap_touch_event_consumer")));
    }

    @Test
    public void realLyricsPass() {
        assertFalse(NativeLyricsSource.looksLikeIdentifier("Tell me you got something to give"));
        assertFalse(NativeLyricsSource.looksLikeIdentifier("Supercalifragilistic"));
        assertFalse(NativeLyricsSource.looksLikeIdentifier("사랑해"));
        assertFalse(NativeLyricsSource.mostlyIdentifiers(lines(
                "Tell me you got something to give", "I want it", "the_one_line_like_this")));
    }
}
