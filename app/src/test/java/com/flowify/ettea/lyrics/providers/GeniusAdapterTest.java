package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Ported from BitChord's GeniusTest.kt (github.com/kushagrasinghx/BitChord), minus the live
 *  network case - this suite only covers the pure parsing/scoring logic. */
public class GeniusAdapterTest {

    private static final String SAMPLE_HTML = "<!DOCTYPE html><html><head>"
            + "<title>Queen - Bohemian Rhapsody Lyrics | Genius Lyrics</title></head><body>"
            + "<div data-lyrics-container=\"true\" class=\"Lyrics__Container\">"
            + "<div data-exclude-from-selection=\"true\" class=\"LyricsHeader__Container\">"
            + "<button>523 Contributors</button>"
            + "<div class=\"SongBioPreview__Container\">Song Bio</div>"
            + "</div>"
            + "[Intro]<br>Is this the real life?<br>Is this just fantasy?<br><br>"
            + "[Verse 1]<br>Mama, just killed a man<br>"
            + "Put a gun against his head, pulled my trigger, now he's dead<br>"
            + "15You might also like<br>"
            + "</div>"
            + "<div data-lyrics-container=\"true\" class=\"Lyrics__Container\">"
            + "[Chorus]<br>Mama, life had just begun<br>"
            + "But now I've gone and thrown it all away<br>42Embed"
            + "</div></body></html>";

    @Test
    public void detectsSectionHeadersCorrectly() {
        assertTrue(GeniusAdapter.isSectionHeader("[Verse 1]"));
        assertTrue(GeniusAdapter.isSectionHeader("[Chorus]"));
        assertTrue(GeniusAdapter.isSectionHeader("[Guitar Solo]"));
        assertTrue(GeniusAdapter.isSectionHeader("[Bridge: Freddie Mercury]"));
        assertFalse(GeniusAdapter.isSectionHeader("Is this the real life?"));
        assertFalse(GeniusAdapter.isSectionHeader("[short"));
        assertFalse(GeniusAdapter.isSectionHeader("]short["));
    }

    @Test
    public void parsesGeniusHtmlAndExtractsCleanLyricsAndSections() {
        List<String> lines = GeniusAdapter.parseHtml(SAMPLE_HTML);
        assertNotNull(lines);

        assertFalse(lines.stream().anyMatch(line -> line.contains("Contributors")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("Song Bio")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("You might also like")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("Embed")));

        assertTrue(lines.contains("[Intro]"));
        assertTrue(lines.contains("Is this the real life?"));
        assertTrue(lines.contains("Is this just fantasy?"));
        assertTrue(lines.contains("[Verse 1]"));
        assertTrue(lines.contains("Mama, just killed a man"));
        assertTrue(lines.contains("[Chorus]"));
        assertTrue(lines.contains("Mama, life had just begun"));
        assertTrue(lines.contains("But now I've gone and thrown it all away"));
    }

    @Test
    public void stripsArtifactsLikeEmbedCountersAndUnicodeSpaces() {
        String raw = "Some lyric line with non-breaking spaces\n12You might also like\nAnother line\n345Embed";
        String cleaned = GeniusAdapter.stripArtifacts(raw);

        assertFalse(cleaned.contains("You might also like"));
        assertFalse(cleaned.contains("Embed"));
        assertFalse(cleaned.contains(" "));
        assertTrue(cleaned.contains("Some lyric line with non-breaking spaces"));
        assertTrue(cleaned.contains("Another line"));
    }

    @Test
    public void convertsMultiLineTextIntoLyricLinesWithProperStanzaSeparation() {
        String input = "[Verse 1]\nLine 1\nLine 2\n\n[Chorus]\nLine 3";

        List<String> lines = GeniusAdapter.textToLines(input);
        assertEquals(6, lines.size());
        assertEquals("[Verse 1]", lines.get(0));
        assertEquals("Line 1", lines.get(1));
        assertEquals("Line 2", lines.get(2));
        assertTrue(lines.get(3).isEmpty());
        assertEquals("[Chorus]", lines.get(4));
        assertEquals("Line 3", lines.get(5));
    }

    @Test
    public void searchAttemptsSplitArtistPrefixedTitles() {
        List<GeniusAdapter.SearchAttempt> attempts =
                GeniusAdapter.searchAttempts("Queen - Bohemian Rhapsody", "Queen");
        assertFalse(attempts.isEmpty());
        assertTrue(attempts.stream().anyMatch(a -> a.title.equalsIgnoreCase("Bohemian Rhapsody")));
    }

    @Test
    public void searchAttemptsStripNoisyYoutubeStyleTitles() {
        List<GeniusAdapter.SearchAttempt> attempts = GeniusAdapter.searchAttempts(
                "Shape of You [Official Lyric Video]", "Ed Sheeran");
        assertTrue(attempts.stream().anyMatch(a -> a.title.equalsIgnoreCase("Shape of You")));
    }

    @Test
    public void bestMatchUrlPicksExactTitleAndArtistOverLooseHits() {
        String json = "{\"response\":{\"sections\":[{\"type\":\"song\",\"hits\":["
                + "{\"result\":{\"title\":\"Believer (Remix)\",\"artist_names\":\"Imagine Dragons\","
                + "\"url\":\"https://genius.com/remix\",\"path\":\"/remix\"}},"
                + "{\"result\":{\"title\":\"Believer\",\"artist_names\":\"Imagine Dragons\","
                + "\"url\":\"https://genius.com/believer\",\"path\":\"/believer\"}}"
                + "]}]}}";
        String url = GeniusAdapter.bestMatchUrl(json, "Believer", "Imagine Dragons");
        assertEquals("https://genius.com/believer", url);
    }

    @Test
    public void bestMatchUrlPenalizesTranslationsAndTracklists() {
        String json = "{\"response\":{\"sections\":[{\"type\":\"song\",\"hits\":["
                + "{\"result\":{\"title\":\"Believer\",\"artist_names\":\"Genius Türkçe Çeviriler\","
                + "\"url\":\"https://genius.com/turkce\",\"path\":\"/genius-turkce-ceviriler-believer-turkce-ceviri-lyrics\"}},"
                + "{\"result\":{\"title\":\"Believer\",\"artist_names\":\"Imagine Dragons\","
                + "\"url\":\"https://genius.com/believer\",\"path\":\"/believer\"}}"
                + "]}]}}";
        String url = GeniusAdapter.bestMatchUrl(json, "Believer", "Imagine Dragons");
        assertEquals("https://genius.com/believer", url);
    }
}
