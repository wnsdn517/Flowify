package com.flowify.ettea.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flowify.ettea.lyrics.language.SpicyJapaneseChineseProcessor;

import org.junit.Test;

public class FuriganaTextSegmentTest {
    @Test
    public void wordLevelFuriganaKeepsCompoundReadingAtSegmentStart() {
        SpicyJapaneseChineseProcessor.FuriganaSegment segment =
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "ことし");
        assertTrue(FuriganaText.segmentStartsInWord(segment, 0, 1));
        assertFalse(FuriganaText.segmentStartsInWord(segment, 1, 2));
    }
}
