package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Apple Music's line-scale targets: the active line ramps to 1.03, the rest rest at 0.95. */
public class AppleOnlyMotionGateTest {
    private static final float EPS = 1e-4f;

    @Test
    public void appleActiveLineRampsTowardItsPeak() {
        float half = LyricAnimations.easeSinOut(0.5f);
        assertEquals(1.0f + 0.03f * half, target(3_000), EPS);
    }

    @Test
    public void inactiveLinesRestRecessed() {
        assertEquals(0.95f, target(500), EPS);
        assertEquals(0.95f, target(6_000), EPS);
    }

    private static float target(long positionMs) {
        AppliedLine line = new AppliedLine();
        line.startMs = 1_000;
        line.endMs = 5_000;
        return LyricsLineAnimationState.forLine(line, positionMs, true, false).scaleTarget;
    }
}
