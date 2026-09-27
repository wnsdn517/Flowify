package com.eza.spicyex.lyrics;

import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Classifies fetch failures so durable no-lyrics results do not get mixed with transient errors. */
public final class LyricsFetchErrors {
    private LyricsFetchErrors() {
    }

    public static boolean isDurableNoLyrics(String error) {
        if (isBlank(error)) return false;
        String normalized = error.toLowerCase(Locale.ROOT);
        // A fallback miss cannot establish absence while the remote source is temporarily unavailable.
        // Legacy "spicy ..." prefixes are kept so errors produced before the rename still classify.
        if (normalized.contains("spicy rate-limited") || normalized.contains("spicy upstream-error")
                || normalized.contains("spicy queued") || normalized.contains("spicy auth rejected")
                || normalized.contains("spicy operation 0 missing")
                || normalized.contains("spicy request cancelled")
                || normalized.contains("apple music rate-limited") || normalized.contains("apple music upstream-error")
                || normalized.contains("apple music queued") || normalized.contains("apple music auth rejected")
                || normalized.contains("apple music operation 0 missing")
                || normalized.contains("apple music request cancelled")
                || normalized.contains("remote source disabled")) return false;
        return normalized.contains("lrclib empty")
                || normalized.contains("no lrclib result")
                || normalized.contains("lrclib http 404")
                || normalized.contains("cached no-result");
    }

    /**
     * A failure that says nothing about whether the song has lyrics - the network, a provider
     * that is busy or refusing for now, or every source switched off - so the screen should say
     * what went wrong rather than "No lyrics". Anything else means the sources were asked and
     * none had lyrics.
     */
    public static boolean isTransient(String error) {
        if (isBlank(error)) return false;
        String normalized = error.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out")
                || normalized.contains("unable to resolve") || normalized.contains("failed to connect")
                || normalized.contains("unknownhost") || normalized.contains("network")
                || normalized.contains("socket") || normalized.contains("ssl")
                || normalized.contains("connection") || normalized.contains("http 5")
                || normalized.contains("http 429") || normalized.contains("rate-limited")
                || normalized.contains("queued") || normalized.contains("cancelled")
                || normalized.contains("all lyric sources disabled");
    }
}
