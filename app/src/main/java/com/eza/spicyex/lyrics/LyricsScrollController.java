package com.eza.spicyex.lyrics;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** View-coordinate helpers for the fullscreen lyric scroll surface. */
public final class LyricsScrollController {
    public static final long ALL_LINES = packRange(0, Integer.MAX_VALUE);
    public static final float CENTER_ANCHOR_FRACTION = 0.5f;
    /** Apple slide rest anchor (active line sits high); mirrored below the center for Bottom. */
    public static final float RAISED_ANCHOR_FRACTION = 0.28f;
    public static final float LOWERED_ANCHOR_FRACTION = 1f - RAISED_ANCHOR_FRACTION;
    private float anchorFraction = CENTER_ANCHOR_FRACTION;
    private final ScrollView scrollView;
    private final LinearLayout contentColumn;
    private final View topStaticSpacer;
    private final Rect workRect = new Rect();

    public LyricsScrollController(ScrollView scrollView, LinearLayout contentColumn, View topStaticSpacer) {
        this.scrollView = scrollView;
        this.contentColumn = contentColumn;
        this.topStaticSpacer = topStaticSpacer;
    }

    public void applyCenterPadding(int safeTopPx, int bottomPaddingPx, int fallbackViewportHeightPx, int rowHalfPx) {
        applyCenterPadding(safeTopPx, bottomPaddingPx, fallbackViewportHeightPx, rowHalfPx, 0);
    }

    /** @param sidePaddingPx horizontal inset for the lyric text itself - kept here rather than on
     *  an outer container so the container's own background/blur surface can still reach the
     *  true screen edges while the text keeps a safe reading margin. */
    public void applyCenterPadding(int safeTopPx, int bottomPaddingPx, int fallbackViewportHeightPx,
            int rowHalfPx, int sidePaddingPx) {
        if (scrollView == null) return;
        int viewport = scrollView.getHeight();
        if (viewport <= 0) viewport = fallbackViewportHeightPx;
        int topAnchor;
        int bottomAnchor;
        if (anchorFraction == CENTER_ANCHOR_FRACTION) {
            int center = Math.max(0, viewport / 2 - rowHalfPx);
            topAnchor = center;
            bottomAnchor = center;
        } else {
            topAnchor = Math.max(0, Math.round(viewport * anchorFraction) - rowHalfPx);
            bottomAnchor = Math.max(0, Math.round(viewport * (1f - anchorFraction)) - rowHalfPx);
        }
        // Horizontal padding is removed from the scroll container itself and moved to individual
        // rows (see LyricsRowViewFactory) so the rows can reach the true screen edges for unclipped
        // blur/glow effects while the text keeps its margin.
        scrollView.setPadding(0, Math.max(safeTopPx, topAnchor),
                0, Math.max(bottomPaddingPx, bottomAnchor));
    }

    /** Where the active line rests vertically, as a fraction of the viewport height (0 = top edge,
     *  0.5 = legacy center, 1 = bottom edge). See {@link Settings#LYRICS_FOCUS_POSITION}. */
    public void setAnchorFraction(float fraction) {
        anchorFraction = fraction;
    }

    private int anchorCenterY(int scrollY, int viewportHeight, int paddingTop) {
        if (anchorFraction == CENTER_ANCHOR_FRACTION) return contentCenterY(scrollY, viewportHeight, paddingTop);
        return scrollY + Math.round(Math.max(1, viewportHeight) * anchorFraction) - Math.max(0, paddingTop);
    }

    public int viewportAnchor(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return 0;
        int center = anchorCenterY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop());
        int offset = Math.max(0, center - topStaticSpacer.getHeight());
        return LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, offset, lineCount);
    }

    public long visibleLineRange(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return ALL_LINES;
        int contentTop = scrollView.getScrollY() - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int contentBottom = scrollView.getScrollY() + scrollView.getHeight()
                - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int start = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentTop), lineCount);
        int end = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentBottom), lineCount);
        return packRange(start, Math.max(start, end));
    }

    public static int rangeStart(long packedRange) {
        return (int) (packedRange >> 32);
    }

    public static int rangeEnd(long packedRange) {
        return (int) packedRange;
    }

    private static long packRange(int start, int end) {
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    static int contentCenterY(int scrollY, int viewportHeight, int paddingTop) {
        return scrollY + Math.max(1, viewportHeight) / 2 - Math.max(0, paddingTop);
    }

    public static boolean shouldScrollInstantly(boolean requested, int previousActiveIndex) {
        return requested || previousActiveIndex < 0;
    }

    public int contentYForTouch(float yInScroll) {
        if (scrollView == null) return Math.round(yInScroll);
        return scrollView.getScrollY() + Math.round(yInScroll) - scrollView.getPaddingTop();
    }

    public int centeredScrollTarget(View row) {
        if (scrollView == null || contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        if (anchorFraction == CENTER_ANCHOR_FRACTION) {
            return scrollView.getPaddingTop() + workRect.top - (scrollView.getHeight() / 2) + (row.getHeight() / 2);
        }
        return scrollView.getPaddingTop() + workRect.top
                - Math.round(scrollView.getHeight() * anchorFraction) + (row.getHeight() / 2);
    }

    public boolean isRowVisible(View row, int minVisiblePx) {
        if (scrollView == null || contentColumn == null || row == null) return false;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int top = scrollView.getScrollY() + scrollView.getPaddingTop();
        int bottom = scrollView.getScrollY() + scrollView.getHeight() - scrollView.getPaddingBottom();
        return Math.min(workRect.bottom, bottom) - Math.max(workRect.top, top) >= Math.max(1, minVisiblePx);
    }

    public int rowCenterInContent(View row) {
        if (contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        return workRect.top + Math.max(1, workRect.height()) / 2;
    }
}
