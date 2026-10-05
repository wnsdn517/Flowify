package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Re-reads of one response, and classes that never carry lyrics, cost no parse or notify. */
public class NativeLyricsCaptureDedupeTest {
    static final class Line {
        public String text;
        public long startTime = 1000L;

        Line(String text) {
            this.text = text;
        }
    }

    static final class Message {
        public java.util.List<Line> lines;

        Message(String text) {
            lines = Collections.singletonList(new Line(text));
        }
    }

    static final class LyricsHolderModel {
    }

    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    @Test
    public void sameResponseReadAgainNotifiesOnce() {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicInteger calls = new AtomicInteger();
        source.addNativeListener((id, doc) -> calls.incrementAndGet());
        Message message = new Message("hello");

        source.captureCandidate(TRACK, message, new Object[0], "a");
        source.captureCandidate(TRACK, message, new Object[0], "b");
        source.captureCandidate(TRACK, new Message("hello"), new Object[0], "c");

        assertEquals(1, calls.get());
        source.captureCandidate(TRACK, new Message("changed"), new Object[0], "d");
        assertEquals(2, calls.get());
    }

    @Test
    public void classThatNeverParsesIsWrittenOff() {
        // Any class without "lyric" in its name (nested test classes inherit this file's name).
        java.util.concurrent.atomic.AtomicLong junk = new java.util.concurrent.atomic.AtomicLong();
        for (int i = 0; i < NativeLyricsSource.CLASS_MISS_LIMIT; i++) {
            assertFalse(NativeLyricsSource.neverLyrics(junk));
            NativeLyricsSource.noteClassMiss(new java.util.concurrent.atomic.AtomicLong());
        }
        assertTrue(NativeLyricsSource.neverLyrics(junk));
    }

    @Test
    public void lyricsModelsAndTextAreNeverWrittenOff() {
        for (int i = 0; i < NativeLyricsSource.CLASS_MISS_LIMIT * 2; i++) {
            NativeLyricsSource.noteClassMiss(new LyricsHolderModel());
            NativeLyricsSource.noteClassMiss("not json");
        }
        assertFalse(NativeLyricsSource.neverLyrics(new LyricsHolderModel()));
        assertFalse(NativeLyricsSource.neverLyrics("{\"lyrics\":{}}"));
    }
}
