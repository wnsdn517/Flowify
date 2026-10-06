package com.flowify.ettea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArtworkFrameFitTest {
    private static final int W = 100;
    private static final int WHITE = 0xFFFFFFFF;
    /** A 20% corner radius: it cuts a corner inset by under ~5.9px. */
    private static final float R = 20f;

    /** A cover of {@code border} with a busy picture filling [l, r) x [t, b). */
    private static int[] framed(int border, int l, int t, int r, int b) {
        int[] px = new int[W * W];
        for (int y = 0; y < W; y++) {
            for (int x = 0; x < W; x++) {
                boolean inside = x >= l && x < r && y >= t && y < b;
                px[y * W + x] = inside ? 0xFF000000 | ((x * 37 + y * 11) & 0x7F) << 8 | (x * 5 & 0x7F) : border;
            }
        }
        return px;
    }

    @Test
    public void thinFrameWhoseCornersTheClipCutsIsFound() {
        ArtworkFrameFit.Frame frame = ArtworkFrameFit.detect(framed(WHITE, 3, 3, 97, 97), W, W, R);
        assertNotNull(frame);
        assertEquals(3, frame.left);
        assertEquals(97, frame.bottom);
        assertTrue(ArtworkFrameFit.near(frame.color, WHITE));
    }

    @Test
    public void thickBorderTheClipNeverReachesIsLeftAlone() {
        assertNull(ArtworkFrameFit.detect(framed(WHITE, 12, 12, 88, 88), W, W, R));
    }

    @Test
    public void fullBleedCoverIsLeftAlone() {
        assertNull(ArtworkFrameFit.detect(framed(WHITE, 0, 0, 100, 100), W, W, R));
    }

    @Test
    public void letterboxBarsAreNotARectangleInside() {
        assertNull(ArtworkFrameFit.detect(framed(0xFF000000, 0, 3, 100, 97), W, W, R));
    }

    @Test
    public void subjectOnAFlatBackgroundIsNotAFrame() {
        // A round blob near the margins: its outline touches each side only in the middle.
        int[] px = new int[W * W];
        for (int y = 0; y < W; y++) {
            for (int x = 0; x < W; x++) {
                double d = Math.hypot(x - 49.5, y - 49.5);
                px[y * W + x] = d < 47 ? 0xFF203040 : WHITE;
            }
        }
        assertNull(ArtworkFrameFit.detect(px, W, W, R));
    }

    @Test
    public void cornerClipGeometry() {
        assertTrue(ArtworkFrameFit.cornerClipped(3, 3, 20f));
        assertFalse(ArtworkFrameFit.cornerClipped(6, 6, 20f));
        assertFalse(ArtworkFrameFit.cornerClipped(3, 3, 0f));
    }

    @Test
    public void shrinkInsetClearsTheRoundedClip() {
        assertEquals(0.01f, ArtworkFrameFit.shrinkInset(0f), 1e-4f);
        float inset = ArtworkFrameFit.shrinkInset(0.1f);
        assertTrue(inset > 0.029f && inset < 0.05f);
    }
}
