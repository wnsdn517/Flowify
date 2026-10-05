package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Real cases from live LRCLIB results that the Lyricify matrix rejected. */
public class TrackMatchCreditsTest {
    private static TrackMatchScorer.Score score(String title, String artist, long ms,
                                                String hitTitle, String hitArtist, long hitMs) {
        TrackMatchScorer.Target target = new TrackMatchScorer.Target(
                AppleTtmlMirrorAdapter.searchTitle(title), artist, null, ms);
        return TrackMatchScorer.score(target, AppleTtmlMirrorAdapter.searchTitle(hitTitle),
                TrackMatchScorer.splitArtists(hitArtist), null, hitMs);
    }

    @Test
    public void leadArtistAloneIsTheSameSong() {
        assertTrue(score("One Of The Girls (with JENNIE, Lily Rose Depp)", "The Weeknd, JENNIE, Lily-Rose Depp",
                244000, "One Of The Girls", "The Weeknd", 244000).accepted());
        assertTrue(score("Golden", "HUNTR/X, EJAE, AUDREY NUNA, REI AMI, KPop Demon Hunters Cast",
                194000, "Golden", "HUNTR/X", 194000).accepted());
    }

    @Test
    public void aStrangerWithTheSameTitleIsNot() {
        assertTrue(!score("Patient Zero", "Taylor Swift", 226000, "Patient Zero", "Rejecta", 179000).accepted());
        assertTrue(!score("Golden", "HUNTR/X, EJAE", 194000, "Golden", "Jill Scott", 194000).accepted());
    }

    @Test
    public void artistPrefixedUploadTitles() {
        assertEquals("One Of The Girls", LrclibQueryPlanner.withoutArtistPrefix(
                "The Weeknd, JENNIE & Lily Rose Depp - One Of The Girls", "The Weeknd, JENNIE"));
        assertEquals("Bohemian Rhapsody - Remastered 2011", LrclibQueryPlanner.withoutArtistPrefix(
                "Bohemian Rhapsody - Remastered 2011", "Queen"));
    }

    @Test
    public void cjkArtistSeparators() {
        // KuGou lists "ROSÉ、Bruno Mars"; one undivided name used to score artist NONE.
        assertTrue(score("APT.", "ROSÉ, Bruno Mars", 170000, "APT.", "ROSÉ、Bruno Mars", 169000).accepted());
        assertEquals(java.util.Arrays.asList("lady gaga", "bruno mars"),
                TrackMatchScorer.splitArtists("Lady Gaga、Bruno Mars"));
    }
}
