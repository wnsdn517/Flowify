package com.flowify.ettea.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The player's clock labels as Spotify writes them, and the remaining time drawn instead. */
public class ApplePlayerClockTest {
    @Test
    public void parsesSpotifyClockLabels() {
        assertEquals(2, ApplePlayerStyler.parseClock("0:02"));
        assertEquals(230, ApplePlayerStyler.parseClock("3:50"));
        assertEquals(3723, ApplePlayerStyler.parseClock("1:02:03"));
        // Our own remaining-time text, or anything else, is not a duration.
        assertEquals(-1, ApplePlayerStyler.parseClock("-3:48"));
        assertEquals(-1, ApplePlayerStyler.parseClock("--:--"));
        assertEquals(-1, ApplePlayerStyler.parseClock(""));
        assertEquals(-1, ApplePlayerStyler.parseClock(null));
    }

    @Test
    public void formatsLikeSpotify() {
        assertEquals("0:00", ApplePlayerStyler.clock(0));
        assertEquals("4:16", ApplePlayerStyler.clock(256));
        assertEquals("1:00:05", ApplePlayerStyler.clock(3605));
    }

    @Test
    public void seekJumpsAreNotAnimatedAsContinuousClockTicks() {
        assertTrue(ApplePlayerStyler.isContinuousClockTransition(120, 121));
        assertTrue(ApplePlayerStyler.isContinuousClockTransition(120, 122));
        assertFalse(ApplePlayerStyler.isContinuousClockTransition(120, 300));
        assertTrue(ApplePlayerStyler.isContinuousClockTransition(-1, 300));
    }

    @Test
    public void artworkSourcesAreCentreCroppedToTheSameSquareWithoutStretching() {
        assertArrayEquals(new int[]{200, 0, 800, 600},
                ApplePlayerStyler.centerCropSourceBounds(1000, 600));
        assertArrayEquals(new int[]{0, 200, 600, 800},
                ApplePlayerStyler.centerCropSourceBounds(600, 1000));
        assertArrayEquals(new int[]{0, 0, 600, 600},
                ApplePlayerStyler.centerCropSourceBounds(600, 600));
        assertNull(ApplePlayerStyler.centerCropSourceBounds(0, 600));
    }
}
