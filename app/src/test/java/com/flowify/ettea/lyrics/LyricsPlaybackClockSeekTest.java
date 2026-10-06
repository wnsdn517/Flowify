package com.flowify.ettea.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsPlaybackClockSeekTest {
    @Test
    public void spotifysOldPositionRightAfterASeekIsIgnored() {
        LyricsPlaybackClock clock = new LyricsPlaybackClock(null);
        clock.forcePositionAt(60_000L, true, 1_000L);
        // 80 ms later Spotify still says 90.3 s (where it was): stale, not a reason to jump back.
        assertTrue(clock.isStalePreSeekSample(90_333L, true, 1_080L));
        // Then the seek lands: accepted, and normal following resumes.
        assertFalse(clock.isStalePreSeekSample(60_150L, true, 1_200L));
        assertFalse(clock.isStalePreSeekSample(90_333L, true, 1_300L));
    }

    @Test
    public void theGuardEndsAfterTheSettleWindow() {
        LyricsPlaybackClock clock = new LyricsPlaybackClock(null);
        clock.forcePositionAt(60_000L, true, 1_000L);
        assertFalse(clock.isStalePreSeekSample(10_000L,
                true, 1_000L + LyricsPlaybackClock.SEEK_SETTLE_MS + 1));
    }

    @Test
    public void aSmallBufferingLagIsNotStale() {
        LyricsPlaybackClock clock = new LyricsPlaybackClock(null);
        clock.forcePositionAt(60_000L, true, 1_000L);
        // Still at the target 600 ms later (buffering): real, so it is followed, gently.
        assertFalse(clock.isStalePreSeekSample(60_000L, true, 1_600L));
    }
}
