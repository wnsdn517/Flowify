package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppleLiftMotionTest {
    private static final float EPS = 0.0001f;

    @Test
    public void appleLiftSplineRisesAndHolds() {
        assertEquals(0f, LyricAnimations.appleLiftYOffsetSpline(0f), EPS);
        assertEquals(-LyricAnimations.APPLE_LIFT_EM, LyricAnimations.appleLiftYOffsetSpline(1f), EPS);
        float mid = LyricAnimations.appleLiftYOffsetSpline(0.5f);
        assertTrue(mid < 0f && mid > -LyricAnimations.APPLE_LIFT_EM);
    }

    @Test
    public void wordMotionYRisesAndHoldsWhileSung() {
        // Apple lift holds -APPLE_LIFT_EM once sung.
        assertEquals(-LyricAnimations.APPLE_LIFT_EM,
                LyricsAnimationApplier.wordMotionY(false, true, 0.9f, 0f), EPS);
        // Unsung words stay put; an active word is on its way up.
        assertEquals(0f, LyricsAnimationApplier.wordMotionY(false, false, 0.5f, 0f), EPS);
        assertEquals(LyricAnimations.appleLiftYOffsetSpline(0.5f),
                LyricsAnimationApplier.wordMotionY(true, false, 0.5f, 0f), EPS);
        // A held note lifts a little further.
        assertTrue(LyricsAnimationApplier.wordMotionY(true, false, 0.5f, 1f)
                < LyricsAnimationApplier.wordMotionY(true, false, 0.5f, 0f));
    }

    @Test
    public void appleGlowFalloffsBracketShared() {
        // Wider wash and tighter anchor than the shared falloff at distance 2.
        assertTrue(LyricAnimations.appleLetterGlowFalloff(2f)
                > LyricAnimations.letterGlowFalloff(2f));
        assertTrue(LyricAnimations.appleActiveLetterGlowFalloff(2f)
                > LyricAnimations.appleLetterGlowFalloff(2f));
    }

}
