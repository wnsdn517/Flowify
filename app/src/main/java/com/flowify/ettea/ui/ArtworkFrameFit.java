package com.eza.spicyex.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Artwork frame fit (Labs, beta): some covers are a picture inside a flat border or letterbox, so
 * the readout's rounded clip rounds the border while the picture inside keeps sharp square
 * corners - or, without a border, the rounding eats into the picture's own corners.
 *
 * <p>It acts only on that problem. Detection works on a small copy: the four corner pixels must
 * agree on one border colour, each side is peeled inward while its whole row/column is that
 * colour, every side of what is left must be a straight edge along its full length (a drawn
 * rectangle, not a subject on a flat background), and the rounded clip must actually cut at least
 * two of its corners. Full-bleed covers and thick-bordered ones are left alone. The modes then:
 * <ul>
 *   <li>{@link #MODE_ZOOM}: crop to the picture, so it fills the frame and gets the rounded clip.</li>
 *   <li>{@link #MODE_ROUND}: keep the border, round the picture's own corners into it.</li>
 *   <li>{@link #MODE_SHRINK}: scale the whole cover down just enough that the rounded clip no longer
 *       reaches its corners, on its edge colour.</li>
 * </ul>
 */
public final class ArtworkFrameFit {
    public static final String MODE_OFF = "Off";
    public static final String MODE_ZOOM = "Zoom";
    public static final String MODE_ROUND = "Round corners";
    public static final String MODE_SHRINK = "Shrink";

    /** Per-channel distance that still counts as the border colour (JPEG noise, gradients). */
    static final int TOLERANCE = 28;
    /** Share of a row/column that must match for it to be border. */
    static final float ROW_MATCH = 0.96f;
    /** A side is peeled at most this far: beyond it the "border" is really the picture. */
    static final float MAX_INSET = 0.38f;
    private static final int SAMPLE = 256;
    /** Share of a rectangle's edge line that must differ from the border for it to be a drawn edge. */
    static final float EDGE_MATCH = 0.85f;

    private ArtworkFrameFit() {
    }

    /** Result of {@link #detect}: the picture rect in sample pixels and the border colour. */
    static final class Frame {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int color;

        Frame(int left, int top, int right, int bottom, int color) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
        }
    }

    /**
     * Applies {@code mode} to a square cover. Returns {@code src} itself when nothing applies
     * (mode off, or no frame found for Zoom/Round); otherwise a new bitmap the same size, and the
     * caller owns both.
     *
     * @param cornerFraction the view's corner radius over its side length
     */
    public static Bitmap apply(Bitmap src, String mode, float cornerFraction) {
        if (src == null || mode == null || MODE_OFF.equals(mode)) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 8 || h < 8) return src;
        Bitmap small = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true);
        int[] px = new int[SAMPLE * SAMPLE];
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        if (small != src) small.recycle();
        Frame frame = detect(px, SAMPLE, SAMPLE, cornerFraction * SAMPLE);
        // Only a cover whose inner rectangle the rounded clip actually cuts is touched - not
        // flat-edged art, not full-bleed covers (their corners are meant to be rounded).
        if (frame == null) return src;
        float sx = w / (float) SAMPLE;
        float sy = h / (float) SAMPLE;
        if (MODE_SHRINK.equals(mode)) return shrink(src, frame.color, cornerFraction);
        Rect content = new Rect(Math.round(frame.left * sx), Math.round(frame.top * sy),
                Math.round(frame.right * sx), Math.round(frame.bottom * sy));
        if (content.width() < 4 || content.height() < 4) return src;
        if (MODE_ZOOM.equals(mode)) return zoom(src, content);
        if (MODE_ROUND.equals(mode)) return round(src, content, frame.color, cornerFraction);
        return src;
    }

    /**
     * The rectangle drawn inside a uniform border whose corners the view's rounded clip (radius
     * {@code radiusPx}, in sample pixels) cuts off - or null when there is no such problem:
     * no border, a border too thick for the clip to reach the rectangle's corners, or a "frame"
     * that is really a shape on a flat background (its edges are not straight full-length lines).
     */
    static Frame detect(int[] px, int w, int h, float radiusPx) {
        if (px == null || w <= 2 || h <= 2 || px.length < w * h) return null;
        int c0 = px[0];
        int c1 = px[w - 1];
        int c2 = px[(h - 1) * w];
        int c3 = px[(h - 1) * w + w - 1];
        if (!near(c0, c1) || !near(c0, c2) || !near(c0, c3)) return null;
        int ref = average(c0, c1, c2, c3);
        int maxX = (int) (w * MAX_INSET);
        int maxY = (int) (h * MAX_INSET);
        int top = 0;
        while (top < maxY && rowMatches(px, w, top, ref)) top++;
        int bottom = h;
        while (h - bottom < maxY && rowMatches(px, w, bottom - 1, ref)) bottom--;
        int left = 0;
        while (left < maxX && columnMatches(px, w, h, left, ref)) left++;
        int right = w;
        while (w - right < maxX && columnMatches(px, w, h, right - 1, ref)) right--;
        // Peeling ran to the limit on a side: the "border" is a flat-coloured picture, not a frame.
        if (top >= maxY || h - bottom >= maxY || left >= maxX || w - right >= maxX) return null;
        // A rectangle inside: border on every side, and each side of it a straight edge running
        // (nearly) its whole length, not a subject's outline touching the margin somewhere.
        if (top < 1 || left < 1 || h - bottom < 1 || w - right < 1) return null;
        if (!edgeIsStraight(px, w, ref, top, left, right, true)
                || !edgeIsStraight(px, w, ref, bottom - 1, left, right, true)
                || !edgeIsStraight(px, w, ref, left, top, bottom, false)
                || !edgeIsStraight(px, w, ref, right - 1, top, bottom, false)) {
            return null;
        }
        int clipped = 0;
        if (cornerClipped(left, top, radiusPx)) clipped++;
        if (cornerClipped(w - right, top, radiusPx)) clipped++;
        if (cornerClipped(left, h - bottom, radiusPx)) clipped++;
        if (cornerClipped(w - right, h - bottom, radiusPx)) clipped++;
        if (clipped < 2) return null;
        return new Frame(left, top, right, bottom, ref);
    }

    /**
     * Whether a corner inset by (ix, iy) from the cover's corner falls outside a rounded clip of
     * radius r: the arc's centre is (r, r), so the point is cut when it is farther than r from it.
     */
    static boolean cornerClipped(int ix, int iy, float r) {
        if (r <= 0f || ix >= r || iy >= r) return false;
        float dx = r - ix;
        float dy = r - iy;
        return dx * dx + dy * dy > r * r;
    }

    private static boolean edgeIsStraight(int[] px, int w, int ref, int line, int from, int to,
                                          boolean row) {
        int span = to - from;
        if (span <= 0) return false;
        int hits = 0;
        for (int i = from; i < to; i++) {
            int c = row ? px[line * w + i] : px[i * w + line];
            if (!near(c, ref)) hits++;
        }
        return hits >= span * EDGE_MATCH;
    }

    private static boolean rowMatches(int[] px, int w, int y, int ref) {
        int hits = 0;
        int base = y * w;
        for (int x = 0; x < w; x++) if (near(px[base + x], ref)) hits++;
        return hits >= w * ROW_MATCH;
    }

    private static boolean columnMatches(int[] px, int w, int h, int x, int ref) {
        int hits = 0;
        for (int y = 0; y < h; y++) if (near(px[y * w + x], ref)) hits++;
        return hits >= h * ROW_MATCH;
    }

    static boolean near(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= TOLERANCE
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= TOLERANCE
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= TOLERANCE;
    }

    private static int average(int... colors) {
        int r = 0;
        int g = 0;
        int b = 0;
        for (int c : colors) {
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int n = Math.max(1, colors.length);
        return 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /**
     * How far (as a share of the side) a square must be inset so its corners clear a rounded clip
     * of {@code cornerFraction}: the arc's farthest point from the corner is r(1 - 1/sqrt 2).
     */
    static float shrinkInset(float cornerFraction) {
        float r = Math.max(0f, Math.min(0.5f, cornerFraction));
        return r * (1f - (float) (1d / Math.sqrt(2d))) + 0.01f;
    }

    private static Bitmap zoom(Bitmap src, Rect content) {
        // Square-crop the picture (centre), then fill the cover's size with it.
        int side = Math.min(content.width(), content.height());
        int cx = content.centerX();
        int cy = content.centerY();
        Rect square = new Rect(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side);
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, square, new RectF(0, 0, out.getWidth(), out.getHeight()),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static Bitmap round(Bitmap src, Rect content, int borderColor, float cornerFraction) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return src;
        float radius = Math.max(2f, Math.min(content.width(), content.height())
                * Math.max(0.04f, Math.min(0.5f, cornerFraction)));
        Path corners = new Path();
        corners.addRect(new RectF(content), Path.Direction.CW);
        Path rounded = new Path();
        rounded.addRoundRect(new RectF(content), radius, radius, Path.Direction.CW);
        corners.op(rounded, Path.Op.DIFFERENCE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(borderColor);
        new Canvas(out).drawPath(corners, paint);
        return out;
    }

    private static Bitmap shrink(Bitmap src, int background, float cornerFraction) {
        int w = src.getWidth();
        int h = src.getHeight();
        float inset = shrinkInset(cornerFraction);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(background);
        RectF target = new RectF(w * inset, h * inset, w * (1f - inset), h * (1f - inset));
        canvas.drawBitmap(src, null, target, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }
}
