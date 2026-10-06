package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AppleTtmlMirrorAdapterTest {
    private static final String TTML = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>";

    @Test
    public void unwrapsTheTtmlEnvelopeButNotTheJsonItself() {
        String envelope = "{\"ttml\":\"<tt xmlns=\\\"http://www.w3.org/ns/ttml\\\"><body/></tt>\"}";
        // The envelope mentions the TTML namespace too; it must not be mistaken for the document.
        assertFalse(AppleTtmlMirrorAdapter.looksLikeTtml(envelope));
        assertEquals(TTML, AppleTtmlMirrorAdapter.extractTtml(envelope));
        assertEquals(TTML, AppleTtmlMirrorAdapter.extractTtml("﻿" + TTML));
        assertNull(AppleTtmlMirrorAdapter.extractTtml("{\"error\":\"not found\"}"));
    }

    @Test
    public void readsTheBiniLyricsDocumentUrl() {
        assertEquals("https://storage.example/doc.ttml", AppleTtmlMirrorAdapter.firstLyricsUrl(
                "{\"total\":1,\"results\":[{\"track_name\":\"x\",\"lyricsUrl\":\"https://storage.example/doc.ttml\"}]}"));
        assertNull(AppleTtmlMirrorAdapter.firstLyricsUrl("{\"total\":0,\"results\":[]}"));
        assertNull(AppleTtmlMirrorAdapter.firstLyricsUrl("<html>"));
    }

    @Test
    public void cloudflareChallengeIsNotAMiss() {
        assertTrue(AppleTtmlMirrorAdapter.isChallenge(307,
                "<html><head><title>307 Temporary Redirect</title></head><center>cloudflare</center>"));
        assertFalse(AppleTtmlMirrorAdapter.isChallenge(200, "{\"results\":[]}"));
    }

    @Test
    public void namesAsCataloguesFiledThem() {
        assertEquals("Dracula", AppleTtmlMirrorAdapter.searchTitle("Dracula (feat. JENNIE)"));
        assertEquals("Song", AppleTtmlMirrorAdapter.searchTitle("Song [with Someone]"));
        assertEquals("Song", AppleTtmlMirrorAdapter.searchTitle("Song feat. Someone"));
        // A different recording stays named as one.
        assertEquals("Song (Live)", AppleTtmlMirrorAdapter.searchTitle("Song (Live)"));
        assertEquals("Lady Gaga", AppleTtmlMirrorAdapter.primaryArtist("Lady Gaga, Bruno Mars"));
        assertEquals("Simon", AppleTtmlMirrorAdapter.primaryArtist("Simon & Garfunkel"));
        assertEquals("Lil Nas X", AppleTtmlMirrorAdapter.primaryArtist("Lil Nas X"));
    }

    @Test
    public void lrclibKeepsOnlyThisRecording() {
        JsonObject theirs = new JsonObject();
        theirs.addProperty("trackName", "Patient Zero");
        theirs.addProperty("artistName", "Rejecta");
        theirs.addProperty("duration", 179.0);
        JsonObject ours = new JsonObject();
        ours.addProperty("trackName", "Patient Zero");
        ours.addProperty("artistName", "Taylor Swift");
        ours.addProperty("duration", 226.0);
        List<JsonObject> picked = LrclibQueryPlanner.matching(Arrays.asList(theirs, ours),
                "Patient Zero", "Taylor Swift, Someone Else", "", 226000L);
        assertEquals(1, picked.size());
        assertEquals("Taylor Swift", picked.get(0).get("artistName").getAsString());
        // Nobody is this recording: a miss, never the closest stranger.
        assertEquals(0, LrclibQueryPlanner.matching(Arrays.asList(theirs, ours),
                "Patient Zero", "Nobody", "", 226000L).size());
        // Other providers' text wrapped in the LRCLIB shape carries no names and passes.
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("syncedLyrics", "[00:01.00]x");
        assertEquals(1, LrclibQueryPlanner.matching(Arrays.asList(wrapped),
                "Patient Zero", "Taylor Swift", "", 226000L).size());
    }
}
