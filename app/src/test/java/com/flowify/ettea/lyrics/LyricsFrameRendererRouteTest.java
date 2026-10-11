package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsFrameRendererRouteTest {
    @Test
    public void hyperAodBounceUsesDirectSplinePathForSyntheticRows() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/com/flowify/ettea/lyrics/LyricsFrameRenderer.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String compact = source.replaceAll("\\s+", " ");
        // Syllable animation is driven directly with the lift/dim flags straight from config.
        String directCall = "config.glowBlurEnabled, config.appleLift, config.appleDimPassed);";
        assertTrue(compact.contains(directCall));
    }

    @Test
    public void blurRefreshSkipsActiveChangesWhileScrolling() {
        // Active-line advance while held: no refresh, rows stay sharp.
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(true, true, false, true));
        // Steady hold with no change: no refresh.
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(true, false, false, true));
        // Snap-back (hold release) always refreshes so blur restores on resume.
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, false, true, false));
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, true, true, true));
        // Normal playback advance refreshes; blur off never refreshes.
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, true, false, false));
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(false, true, true, false));
    }

    /**
     * The frame pass stops driving a row's scale/glow/syllable springs once it culls the row, so
     * those springs cannot settle. The pending-animation probe has to cull the same rows, or a
     * paused player with one animating row scrolled out of view never stops rendering.
     */
    @Test
    public void pendingAnimationCullsTheSameRowsAsTheFramePass() {
        // In view: still pending until its springs drain.
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(10, 4, 10, 20));
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(15, 4, 10, 20));
        // The cascade margin around the viewport is drawn, so it stays pending.
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(8, 4, 10, 20));
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(22, 4, 10, 20));
        // Past the margin in either direction: culled, so it cannot hold the scheduler open.
        assertTrue(LyricsFrameRenderer.isCulledOffscreen(7, 4, 10, 20));
        assertTrue(LyricsFrameRenderer.isCulledOffscreen(23, 4, 10, 20));
        // A row scrolled far above the viewport: the frozen-spring case from the report.
        assertTrue(LyricsFrameRenderer.isCulledOffscreen(0, 4, 10, 20));
        // The active row is always drawn, even when the viewport has moved off it.
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(4, 4, 10, 20));
        // No viewport known (all lines): nothing is culled.
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(0, 4, 0, Integer.MAX_VALUE));
        assertFalse(LyricsFrameRenderer.isCulledOffscreen(9999, 4, 0, Integer.MAX_VALUE));
    }

    private static SyllableSegment segment(String text, long startMs, long endMs) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        seg.startMs = startMs;
        seg.endMs = endMs;
        seg.totalMs = Math.max(0L, endMs - startMs);
        return seg;
    }
}
