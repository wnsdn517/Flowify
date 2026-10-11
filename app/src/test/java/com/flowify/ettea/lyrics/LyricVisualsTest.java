package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the letter-animator eligibility gate. The English/CJK duration asymmetry here is
 * intentional (see RtlLyricsContractTest#shortMultiCjkSegmentsExposeCharacterMotionUnits) - each
 * CJK character is roughly its own syllable, so a short multi-character run is still a
 * meaningful per-character motion unit under the 1000ms bar an equivalent-length Latin syllable
 * needs. The English/Japanese grow-intensity mismatch this gate was investigated for turned out
 * to live in the furigana word-vs-letter fallback instead - see LyricAnimationsTest's
 * wordScaleSplineStrong coverage.
 */
public class LyricVisualsTest {

    private static SyllableSegment segment(String text, long totalMs) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        seg.totalMs = totalMs;
        return seg;
    }

    @Test
    public void segmentsHeldAtLeast80msAnimateByLetter() {
        assertTrue(LyricVisuals.shouldUseLetterAnimator(segment("go", 100)));
        assertTrue(LyricVisuals.shouldUseLetterAnimator(segment("あ", 400)));
        assertFalse(LyricVisuals.shouldUseLetterAnimator(segment("go", 50)));
    }

    @Test
    public void codePointCapStillApplies() {
        String longText = "abcdefghijklmnopqrstuvwxyz123"; // 29 code points, over the 28 cap
        assertFalse(LyricVisuals.shouldUseLetterAnimator(segment(longText, 5000)));
    }
}
