package com.flowify.ettea.ui;

import android.content.Context;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The Apple Music player backdrop: the cover across the top of the screen edge to edge, melting
 * into a soft, blurred continuation of itself that the title, lyrics line and controls sit on -
 * instead of a square cover on a flat colour.
 *
 * <p>Laid out as BitChord's player does it: the cover extends behind the status bar, full width,
 * and its last third dissolves into a blurred continuation confined to the
 * lower part of the screen. Below, the screen carries on in a darker shade of the cover's own
 * colour rather than a fixed grey.
 *
 * <p>Each cover gets a small pre-blurred fallback bitmap. On Android 12+, a cached RenderNode
 * supplies the larger GPU blur; its display list is rebuilt only when the cover, swipe offset or
 * view size changes. While the cover carousel is dragged, each visible page follows its own
 * offset and the colours crossfade between them.
 */
public final class ApplePlayerBackdrop extends View {
    /** A cover on screen: its bitmaps and where the carousel has it (0 = centred). */
    public static final class Page {
        public Bitmap cover;
        public Bitmap blur;
        /** BitChord-style colour mesh of the cover (see meshOf). */
        public Bitmap mesh;
        /** Linear-light luminance of the cover area under the header. */
        public float topLuminance;
        public float offsetPx;
        /** Dark artwork-derived page colour. */
        public int deepColor = Color.rgb(24, 24, 26);
        /** The artwork's bottom-edge colour, used for the hand-off below the cover. */
        public int washColor = Color.rgb(24, 24, 26);
        public int accentColor = Color.rgb(24, 24, 26);
        public int elevatedColor = Color.rgb(24, 24, 26);
        /** A white tinted with the cover's colour: the handle. */
        public int handleColor = 0xB3FFFFFF;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (topGlyphAnimator != null) {
            topGlyphAnimator.cancel();
            topGlyphAnimator = null;
        }
        topGlyphTarget = Integer.MIN_VALUE;
        super.onDetachedFromWindow();
    }

    /** Share of the cover's drawn height that dissolves into the backdrop. */
    private static final float FADE_FRACTION = 0.42f;
    /** At most this much larger than full width: a little of the sides, never the subject. */
    private static final float MAX_ZOOM = 1.12f;

    private final Page[] pages = {new Page(), new Page()};
    private int pageCount;
    private int bannerHeight;
    private int coverTop;
    private float topBlurZone;
    private boolean coverReplacedByAnimatedArtwork;
    private float handleCenterY = -1f;
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint deepPaint = new Paint();
    private final Paint palettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private final float density;
    private int paletteShaderWidth = -1;
    private int paletteShaderHeight = -1;
    private int paletteShaderBanner = -1;
    private int paletteShaderAccent;
    private int paletteShaderElevated;
    private float paletteAccentX;
    private float paletteAccentY;
    private float paletteAccentRadius;
    private float paletteElevatedX;
    private float paletteElevatedY;
    private float paletteElevatedRadius;
    private RadialGradient paletteAccentShader;
    private RadialGradient paletteElevatedShader;
    private int topGlyphColor = Color.WHITE;
    private int topGlyphTarget = Integer.MIN_VALUE;
    private ValueAnimator topGlyphAnimator;

    public ApplePlayerBackdrop(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The page currently drawn first (the centred cover), or null. */
    public Page currentPage() {
        return pageCount > 0 ? pages[0] : null;
    }

    /** Where the banner ends: the bottom of the square cover it replaces. */
    public void setBannerHeight(int heightPx) {
        if (heightPx == bannerHeight) return;
        bannerHeight = heightPx;
        invalidate();
    }

    /** A subtle blur behind the status bar, blended into the cover's top edge. */
    public void setTopBlurZone(int zonePx) {
        float target = Math.max(0, zonePx);
        float difference = target - topBlurZone;
        if (Math.abs(difference) < 0.5f) return;
        topBlurZone = Math.abs(difference) < 1f
                ? target : topBlurZone + difference * 0.28f;
        invalidate();
        if (Math.abs(target - topBlurZone) >= 0.5f) postInvalidateOnAnimation();
    }

    public void setCoverTop(int topPx) {
        if (topPx == coverTop) return;
        coverTop = topPx;
        invalidate();
    }

    public void setCoverReplacedByAnimatedArtwork(boolean replaced) {
        if (coverReplacedByAnimatedArtwork == replaced) return;
        coverReplacedByAnimatedArtwork = replaced;
        invalidate();
    }

    /** The handle's centre line (under the status bar, above "Playing from"); <0 hides it. */
    public void setHandleCenterY(float y) {
        if (handleCenterY < 0f) {
            handleCenterY = y;
        } else {
            float difference = y - handleCenterY;
            if (Math.abs(difference) < 0.5f) return;
            handleCenterY = Math.abs(difference) < 1f
                    ? y : handleCenterY + difference * 0.28f;
        }
        invalidate();
        if (Math.abs(y - handleCenterY) >= 0.5f) postInvalidateOnAnimation();
    }

    /** Smoothly changes the shared foreground tint for the drag handle and header glyphs. */
    public void setTopGlyphColor(int color) {
        if (color == topGlyphTarget) return;
        if (topGlyphAnimator != null) {
            topGlyphColor = (int) topGlyphAnimator.getAnimatedValue();
            topGlyphAnimator.cancel();
            topGlyphAnimator = null;
        }
        topGlyphTarget = color;
        if (color == topGlyphColor) return;
        topGlyphAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), topGlyphColor, color);
        topGlyphAnimator.setDuration(220L);
        topGlyphAnimator.addUpdateListener(animation -> {
            topGlyphColor = (int) animation.getAnimatedValue();
            invalidate();
        });
        topGlyphAnimator.start();
    }

    /** Top-edge luminance interpolated across both pages while the carousel is swiped. */
    public float topLuminance() {
        if (pageCount <= 0) return 0f;
        if (pageCount == 1) return pages[0].topLuminance;
        float weight = secondPageWeight();
        return lerp(pages[0].topLuminance, pages[1].topLuminance, weight);
    }

    /** The pages to draw this frame (up to two while a swipe is in between). */
    public void setPages(Page first, Page second) {
        boolean changed = false;
        changed |= assign(pages[0], first);
        changed |= assign(pages[1], second);
        int count = first == null ? 0 : second == null ? 1 : 2;
        if (count != pageCount) changed = true;
        pageCount = count;
        if (changed) invalidate();
    }

    private static boolean assign(Page into, Page from) {
        Bitmap cover = from == null ? null : from.cover;
        Bitmap blur = from == null ? null : from.blur;
        float offset = from == null ? 0f : from.offsetPx;
        Bitmap mesh = from == null ? null : from.mesh;
        boolean changed = into.cover != cover || into.blur != blur || into.mesh != mesh
                || into.offsetPx != offset;
        into.cover = cover;
        into.blur = blur;
        into.mesh = mesh;
        into.topLuminance = from == null ? 0f : from.topLuminance;
        into.offsetPx = offset;
        into.deepColor = from == null ? Color.rgb(24, 24, 26) : from.deepColor;
        into.washColor = from == null ? Color.rgb(24, 24, 26) : from.washColor;
        into.accentColor = from == null ? Color.rgb(24, 24, 26) : from.accentColor;
        into.elevatedColor = from == null ? Color.rgb(24, 24, 26) : from.elevatedColor;
        into.handleColor = from == null ? 0xB3FFFFFF : from.handleColor;
        return changed;
    }

    // --- Opening -----------------------------------------------------------------------------

    private static final long ENTRANCE_MS = 760L;
    /** How much larger the cover arrives before settling into place. */
    private static final float ENTRANCE_ZOOM = 1.08f;
    private static final android.view.animation.Interpolator ENTRANCE_EASE =
            new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f);
    private boolean entranceArmed;
    private long entranceStart = -1L;

    /** The opening: the cover arrives slightly larger and settles gently into place, fading in. */
    public void playEntrance() {
        if (!Motion.animationsEnabled()) return;
        entranceArmed = true;
        entranceStart = -1L; // starts on the first frame it is drawn with a cover
        invalidate();
    }

    private float entranceProgress() {
        if (!entranceArmed) return 1f;
        long now = android.os.SystemClock.uptimeMillis();
        if (entranceStart < 0L) entranceStart = now;
        float t = (now - entranceStart) / (float) ENTRANCE_MS;
        if (t >= 1f) {
            entranceArmed = false;
            return 1f;
        }
        postInvalidateOnAnimation();
        return ENTRANCE_EASE.getInterpolation(Math.max(0f, t));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // --- Drawing -----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float banner = bannerHeight > 0 ? Math.min(h, bannerHeight) : w;
        boolean swiping = pageCount > 0
                && Math.abs(pages[0].offsetPx) > Math.max(1f, density * 2f);
        if (swiping) entranceArmed = false;
        float progress = pageCount > 0 && pages[0].cover != null && !swiping
                ? entranceProgress() : 1f;
        int deep = pageCount > 0 ? pages[0].deepColor : Color.rgb(24, 24, 26);
        int wash = pageCount > 0 ? pages[0].washColor : deep;
        int accent = pageCount > 0 ? pages[0].accentColor : deep;
        int elevated = pageCount > 0 ? pages[0].elevatedColor : deep;
        float secondWeight = secondPageWeight();
        if (pageCount == 2) {
            deep = blend(pages[0].deepColor, pages[1].deepColor, secondWeight);
            wash = blend(pages[0].washColor, pages[1].washColor, secondWeight);
            accent = blend(pages[0].accentColor, pages[1].accentColor, secondWeight);
            elevated = blend(pages[0].elevatedColor, pages[1].elevatedColor, secondWeight);
        }
        float zone = topBlurZone;
        // With the status-bar blur on, the cover runs edge to edge up behind the bar (no dark
        // strip above it): the blur band softens it instead.
        float top = zone > 0 ? 0f : Math.max(0, coverTop);
        float softTop = zone > 0 ? 0f : Math.min(48f * density, top * 0.6f + 1f);
        canvas.drawColor(deep);
        if (pageCount == 0) return;
        // BitChord's backdrop: the sleeve's own colours as a mesh (its bottom edge held up to the
        // banner, the rest below it turned over and sideways), under a light scrim.
        drawMeshBackdrop(canvas, w, h, banner, secondWeight);
        // The cover: full width, coming in under the blur band at the top with a soft edge,
        // dissolving into the colours below over its last third.
        for (int i = 0; !coverReplacedByAnimatedArtwork && i < pageCount; i++) {
            Page page = pages[i];
            if (page.cover == null || page.cover.isRecycled()) continue;
            float left = page.offsetPx;
            float fit = w / (float) page.cover.getWidth();
            float room = Math.max(1f, banner - top);
            float scale = Math.max(fit, Math.min(room / page.cover.getHeight(), fit * MAX_ZOOM));
            float alpha = 1f;
            if (progress < 1f && i == 0) {
                // A gentle settle: a little larger at first, easing down into place.
                scale *= lerp(ENTRANCE_ZOOM, 1f, progress);
                alpha = Math.min(1f, progress * 1.6f);
            }
            float drawnW = page.cover.getWidth() * scale;
            float bottom = top + Math.min(room, page.cover.getHeight() * scale);
            matrix.setScale(scale, scale);
            matrix.postTranslate(left + (w - drawnW) / 2f, top);
            BitmapShader bitmapShader = new BitmapShader(page.cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bitmapShader.setLocalMatrix(matrix);
            float span = Math.max(1f, bottom - top);
            float fadeStart = bottom - span * FADE_FRACTION;
            LinearGradient mask = new LinearGradient(0, top, 0, bottom,
                    new int[]{softTop <= 0f ? Color.BLACK : Color.TRANSPARENT, Color.BLACK,
                            Color.BLACK, Color.TRANSPARENT},
                    new float[]{0f, Math.min(0.3f, softTop / span), (fadeStart - top) / span, 1f},
                    Shader.TileMode.CLAMP);
            coverPaint.setShader(new ComposeShader(bitmapShader, mask, PorterDuff.Mode.DST_IN));
            coverPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawRect(left, top, left + w, bottom, coverPaint);
        }
        coverPaint.setShader(null);
        coverPaint.setAlpha(255);
        if (zone > 0 && !coverReplacedByAnimatedArtwork) {
            drawTopProgressiveBlur(canvas, w, top, banner, zone / 0.6f + 56f * density);
        }
        // The handle: a rounded bar in a white tinted with the cover.
        if (handleCenterY > 0f) {
            float hw = 18f * density;
            float hh = 2.5f * density;
            handlePaint.setColor(topGlyphColor);
            canvas.drawRoundRect(w / 2f - hw, handleCenterY - hh, w / 2f + hw, handleCenterY + hh,
                    hh, hh, handlePaint);
        }
    }

    private void drawPaletteBlobs(Canvas canvas, int w, int h, float banner,
                                  int accent, int elevated) {
        int bannerKey = Math.round(banner);
        if (paletteAccentShader == null || paletteShaderWidth != w || paletteShaderHeight != h
                || paletteShaderBanner != bannerKey || paletteShaderAccent != accent
                || paletteShaderElevated != elevated) {
            paletteShaderWidth = w;
            paletteShaderHeight = h;
            paletteShaderBanner = bannerKey;
            paletteShaderAccent = accent;
            paletteShaderElevated = elevated;
            paletteAccentX = w * 0.12f;
            paletteAccentY = Math.min(h, banner + (h - banner) * 0.08f);
            paletteAccentRadius = w * 0.80f;
            paletteElevatedX = w * 0.96f;
            paletteElevatedY = Math.min(h, banner + (h - banner) * 0.30f);
            paletteElevatedRadius = w * 0.95f;
            paletteAccentShader = new RadialGradient(paletteAccentX, paletteAccentY,
                    paletteAccentRadius,
                    new int[]{withAlpha(accent, 0.18f), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP);
            paletteElevatedShader = new RadialGradient(paletteElevatedX, paletteElevatedY,
                    paletteElevatedRadius,
                    new int[]{withAlpha(elevated, 0.36f), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP);
        }
        palettePaint.setShader(paletteAccentShader);
        canvas.drawCircle(paletteAccentX, paletteAccentY, paletteAccentRadius, palettePaint);
        palettePaint.setShader(paletteElevatedShader);
        canvas.drawCircle(paletteElevatedX, paletteElevatedY,
                paletteElevatedRadius, palettePaint);
        palettePaint.setShader(null);
    }

    // --- Real blur ----------------------------------------------------------------------------

    private static final float BLUR_RADIUS_DP = 56f;
    private static final float BLUR_OPACITY = 0.34f;
    private android.graphics.RenderNode blurNode;
    private Bitmap blurNodeCover0;
    private Bitmap blurNodeCover1;
    private float blurNodeOffset0 = Float.NaN;
    private float blurNodeOffset1 = Float.NaN;
    private int blurNodeW;
    private int blurNodeH;
    private final Paint blurNodePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // --- BitChord's artwork mesh -------------------------------------------------------------

    private static final int MESH_GRID = 6;
    private static final int MESH_TEX = 32;
    private static final float MESH_BLUR_RADIUS_DP = 32f;
    private final Paint meshPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint meshScrimPaint = new Paint();
    private int meshScrimHeight = -1;
    private android.graphics.RenderNode meshNode;

    private void drawMeshBackdrop(Canvas canvas, int w, int h, float banner, float secondWeight) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            if (meshNode == null) {
                meshNode = new android.graphics.RenderNode("applePlayerMesh");
                float radius = MESH_BLUR_RADIUS_DP * density;
                meshNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
            }
            meshNode.setPosition(0, 0, w, h);
            android.graphics.RecordingCanvas recording = meshNode.beginRecording(w, h);
            try {
                drawMeshContent(recording, w, h, banner, secondWeight);
            } finally {
                meshNode.endRecording();
            }
            canvas.drawRenderNode(meshNode);
            return;
        }
        drawMeshContent(canvas, w, h, banner, secondWeight);
    }

    private void drawMeshContent(Canvas canvas, int w, int h, float banner, float secondWeight) {
        int seam = Math.round(Math.max(0f, Math.min(h, banner)));
        for (int i = 0; i < pageCount; i++) {
            Page page = pages[i];
            if (page.mesh == null || page.mesh.isRecycled()) continue;
            meshPaint.setAlpha(i == 0 ? 255 : Math.round(255f * secondWeight));
            if (seam > 0) {
                src.set(0, 0, page.mesh.getWidth(), 1);
                dst.set(0, 0, w, seam);
                canvas.drawBitmap(page.mesh, src, dst, meshPaint);
            }
            src.set(0, 0, page.mesh.getWidth(), page.mesh.getHeight());
            dst.set(0, seam, w, h);
            canvas.drawBitmap(page.mesh, src, dst, meshPaint);
        }
        if (meshScrimHeight != h) {
            meshScrimHeight = h;
            meshScrimPaint.setShader(new LinearGradient(0, 0, 0, h,
                    Color.argb(Math.round(255 * 0.06f), 0, 0, 0),
                    Color.argb(Math.round(255 * 0.30f), 0, 0, 0), Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, meshScrimPaint);
    }

    /**
     * The cover averaged into a 6x6 grid of means, row 0 being its own bottom edge (flipped as
     * read) and the later rows turned over and shifted sideways by a seeded amount, then smoothly
     * resampled up to a small texture. What stays is the cover's colours in roughly its own
     * proportions; what goes is anything recognisable.
     */
    /**
     * Samples the upper artwork band in linear light. The 75th percentile gives the glyph
     * foreground a safer choice than a mean when the band contains both dark and bright areas.
     */
    public static float topLuminanceOf(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return 0f;
        int w = cover.getWidth();
        int rows = Math.max(1, Math.round(cover.getHeight() * 0.22f));
        int stepY = Math.max(1, rows / 24);
        int stepX = Math.max(1, w / 48);
        int sampleCount = ((rows - 1) / stepY + 1) * ((w - 1) / stepX + 1);
        float[] luminances = new float[sampleCount];
        int[] line = new int[w];
        int count = 0;
        for (int y = 0; y < rows; y += stepY) {
            cover.getPixels(line, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += stepX) {
                int c = line[x];
                float r = linearChannel(Color.red(c) / 255f);
                float g = linearChannel(Color.green(c) / 255f);
                float b = linearChannel(Color.blue(c) / 255f);
                luminances[count++] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            }
        }
        if (count == 0) return 0f;
        java.util.Arrays.sort(luminances, 0, count);
        return luminances[Math.min(count - 1, Math.round((count - 1) * 0.75f))];
    }

    private static float linearChannel(float channel) {
        return channel <= 0.04045f
                ? channel / 12.92f
                : (float) Math.pow((channel + 0.055f) / 1.055f, 2.4);
    }

    public static Bitmap meshOf(Bitmap source, int seed) {
        if (source == null || source.isRecycled()) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 1 || height < 1) return null;
        int cols = Math.min(MESH_GRID, width);
        int rows = Math.min(MESH_GRID, height);
        int rowStep = Math.max(1, height / 128);
        int colStep = Math.max(1, width / 128);
        int cells = rows * cols;
        long[] red = new long[cells];
        long[] green = new long[cells];
        long[] blue = new long[cells];
        int[] count = new int[cells];
        int[] line = new int[width];
        for (int y = 0; y < height; y += rowStep) {
            source.getPixels(line, 0, width, 0, y, width, 1);
            int rowBase = ((height - 1 - y) * rows / height) * cols;
            for (int x = 0; x < width; x += colStep) {
                int cell = rowBase + x * cols / width;
                int pixel = line[x];
                red[cell] += (pixel >> 16) & 0xFF;
                green[cell] += (pixel >> 8) & 0xFF;
                blue[cell] += pixel & 0xFF;
                count[cell]++;
            }
        }
        int[] grid = new int[cells];
        float[] hsl = new float[3];
        for (int cell = 0; cell < cells; cell++) {
            int n = Math.max(1, count[cell]);
            int color = Color.rgb((int) (red[cell] / n), (int) (green[cell] / n), (int) (blue[cell] / n));
            androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
            hsl[1] = Math.min(1f, hsl[1] * 1.12f);
            hsl[2] = Math.max(hsl[2], 0.045f);
            grid[cell] = androidx.core.graphics.ColorUtils.HSLToColor(hsl);
        }
        if (rows > 1) {
            java.util.Random random = new java.util.Random(seed);
            boolean mirror = random.nextBoolean();
            int shift = random.nextInt(cols);
            int[] turned = grid.clone();
            for (int row = 1; row < rows; row++) {
                for (int x = 0; x < cols; x++) {
                    int from = mirror ? cols - 1 - x : x;
                    turned[row * cols + x] = grid[row * cols + (from + shift) % cols];
                }
            }
            grid = turned;
        }
        int[] texels = new int[MESH_TEX * MESH_TEX];
        for (int ty = 0; ty < MESH_TEX; ty++) {
            float fy = (ty + 0.5f) / MESH_TEX * rows - 0.5f;
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(fy)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float wy = smoothstep(fy - y0);
            for (int tx = 0; tx < MESH_TEX; tx++) {
                float fx = (tx + 0.5f) / MESH_TEX * cols - 0.5f;
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(fx)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float wx = smoothstep(fx - x0);
                int topRow = blend(grid[y0 * cols + x0], grid[y0 * cols + x1], wx);
                int bottomRow = blend(grid[y1 * cols + x0], grid[y1 * cols + x1], wx);
                texels[ty * MESH_TEX + tx] = blend(topRow, bottomRow, wy);
            }
        }
        return Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888);
    }

    private static float smoothstep(float t) {
        float x = Math.max(0f, Math.min(1f, t));
        return x * x * (3f - 2f * x);
    }

    // --- Top bar blur (BitChord's TopFadeBlur: full blur at the top edge, easing to none) --------

    private static final float TOP_BLUR_RADIUS_DP = 30f;
    /** How much blur the fade reaches at the very top: short of all of it, as BitChord's PEAK. */
    private static final float TOP_BLUR_PEAK = 1f;
    /** How dark the legibility scrim starts at the top (BitChord's SCRIM_PEAK). */
    private static final float TOP_SCRIM_PEAK = 0.30f;
    private static final int TOP_RAMP_STOPS = 12;
    private android.graphics.RenderNode topBlurNode;
    private Bitmap topBlurCover0;
    private Bitmap topBlurCover1;
    private float topBlurOffset0 = Float.NaN;
    private float topBlurOffset1 = Float.NaN;
    private int topBlurW;
    private int topBlurBanner = -1;
    private int topBlurTop = -1;
    private final Paint topBlurMask = new Paint();
    private final Paint topScrimPaint = new Paint();
    private final Paint topBlurCoverPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float[] topRampStops;
    private int[] topBlurColors;
    private int[] topScrimColors;
    private int topRampBand = -1;
    {
        topBlurMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /**
     * The status-bar zone is the cover itself, blurred: a blurred copy of exactly what the cover
     * shows there, crossfaded in along an ease-out ramp (so it is strongest at the top edge and
     * gone before the band ends), under a faint eased scrim for the glyphs. No flat colour is
     * laid over the artwork, so it reads as the picture going soft rather than as a tinted bar.
     */
    private void drawTopProgressiveBlur(Canvas canvas, int w, float top, float banner, float bandH) {
        if (android.os.Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated()) return;
        Page first = pages[0];
        if (first.cover == null || first.cover.isRecycled()) return;
        int band = Math.round(Math.max(1f, bandH));
        Page second = pageCount == 2 ? pages[1] : null;
        Bitmap cover1 = second == null ? null : second.cover;
        float offset1 = second == null ? Float.NaN : second.offsetPx;
        if (topBlurNode == null) {
            topBlurNode = new android.graphics.RenderNode("applePlayerTopBlur");
            float radius = TOP_BLUR_RADIUS_DP * density;
            topBlurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        }
        boolean sameOffset1 = topBlurOffset1 == offset1
                || (Float.isNaN(topBlurOffset1) && Float.isNaN(offset1));
        boolean stale = topBlurCover0 != first.cover || topBlurOffset0 != first.offsetPx
                || topBlurCover1 != cover1 || !sameOffset1 || topBlurW != w
                || topBlurBanner != Math.round(banner) || topBlurTop != Math.round(top)
                || !topBlurNode.hasDisplayList();
        if (stale) {
            // Wider than the band so the blur has real cover to sample past its bottom edge.
            int nodeH = band + Math.round(TOP_BLUR_RADIUS_DP * density * 3f);
            topBlurNode.setPosition(0, 0, w, nodeH);
            android.graphics.RecordingCanvas rc = topBlurNode.beginRecording(w, nodeH);
            try {
                for (int i = 0; i < pageCount; i++) {
                    Page page = pages[i];
                    if (page.cover == null || page.cover.isRecycled()) continue;
                    float fit = w / (float) page.cover.getWidth();
                    float room = Math.max(1f, banner - top);
                    float scale = Math.max(fit, Math.min(room / page.cover.getHeight(), fit * MAX_ZOOM));
                    float drawnW = page.cover.getWidth() * scale;
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(page.offsetPx + (w - drawnW) / 2f, top);
                    topBlurCoverPaint.setAlpha(i == 0 ? 255 : Math.round(255f * secondPageWeight()));
                    rc.drawBitmap(page.cover, matrix, topBlurCoverPaint);
                }
            } finally {
                topBlurNode.endRecording();
            }
            topBlurCover0 = first.cover;
            topBlurOffset0 = first.offsetPx;
            topBlurCover1 = cover1;
            topBlurOffset1 = offset1;
            topBlurW = w;
            topBlurBanner = Math.round(banner);
            topBlurTop = Math.round(top);
        }
        if (topRampBand != band) {
            topRampBand = band;
            topRampStops = new float[TOP_RAMP_STOPS];
            topBlurColors = new int[TOP_RAMP_STOPS];
            topScrimColors = new int[TOP_RAMP_STOPS];
            for (int i = 0; i < TOP_RAMP_STOPS; i++) {
                float t = i / (TOP_RAMP_STOPS - 1f);
                float fade = 1f - easeOutCubic(t);
                topRampStops[i] = t;
                topBlurColors[i] = Color.argb(Math.round(255f * TOP_BLUR_PEAK * fade), 0, 0, 0);
                topScrimColors[i] = Color.argb(Math.round(255f * TOP_SCRIM_PEAK * fade), 0, 0, 0);
            }
            topBlurMask.setShader(new LinearGradient(0f, 0f, 0f, band,
                    topBlurColors, topRampStops, Shader.TileMode.CLAMP));
            topScrimPaint.setShader(new LinearGradient(0f, 0f, 0f, band,
                    topScrimColors, topRampStops, Shader.TileMode.CLAMP));
        }
        int save = canvas.saveLayer(0f, 0f, w, band, null);
        canvas.drawRenderNode(topBlurNode);
        canvas.drawRect(0f, 0f, w, band, topBlurMask);
        canvas.restoreToCount(save);
        canvas.drawRect(0f, 0f, w, band, topScrimPaint);
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /**
     * The cover itself, Gaussian-blurred by the GPU (Android 12+), covering the lower backdrop:
     * a real blur, smooth at any size, rather than a tiny copy stretched up. Recorded only when
     * the cover, its offset or the size changes; between those the blurred layer is reused as is.
     */
    private boolean drawGpuBlur(Canvas canvas, int w, int h, float progress,
                                float opacity, float secondWeight) {
        if (android.os.Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated()) return false;
        Page first = pages[0];
        if (first.cover == null || first.cover.isRecycled()) return false;
        Page second = pageCount == 2 ? pages[1] : null;
        Bitmap cover1 = second == null ? null : second.cover;
        float offset1 = second == null ? Float.NaN : second.offsetPx;
        if (blurNode == null) {
            blurNode = new android.graphics.RenderNode("applePlayerBlur");
            float radius = BLUR_RADIUS_DP * density;
            blurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        }
        boolean sameOffset1 = blurNodeOffset1 == offset1
                || (Float.isNaN(blurNodeOffset1) && Float.isNaN(offset1));
        boolean stale = blurNodeCover0 != first.cover || blurNodeOffset0 != first.offsetPx
                || blurNodeCover1 != cover1 || !sameOffset1
                || blurNodeW != w || blurNodeH != h || !blurNode.hasDisplayList();
        if (stale) {
            blurNode.setPosition(0, 0, w, h);
            android.graphics.RecordingCanvas rc = blurNode.beginRecording(w, h);
            try {
                rc.drawColor(Color.BLACK);
                for (int i = 0; i < pageCount; i++) {
                    Page page = pages[i];
                    if (page.cover == null || page.cover.isRecycled()) continue;
                    blurNodePaint.setAlpha(i == 0 ? 255 : Math.round(255f * secondWeight));
                    // Fills the screen keeping the cover's proportions (centre crop by height).
                    float s = Math.max(w / (float) page.cover.getWidth(),
                            h / (float) page.cover.getHeight()) * 1.4f;
                    float bw = page.cover.getWidth() * s;
                    float left = (w - bw) / 2f + page.offsetPx * 0.5f;
                    float bh = page.cover.getHeight() * s;
                    float top = (h - bh) / 2f;
                    src.set(0, 0, page.cover.getWidth(), page.cover.getHeight());
                    dst.set(left, top, left + bw, top + bh);
                    rc.drawBitmap(page.cover, src, dst, blurNodePaint);
                }
            } finally {
                blurNode.endRecording();
            }
            blurNodeCover0 = first.cover;
            blurNodeOffset0 = first.offsetPx;
            blurNodeCover1 = cover1;
            blurNodeOffset1 = offset1;
            blurNodeW = w;
            blurNodeH = h;
        }
        blurNode.setAlpha(opacity * Math.min(1f, 0.35f + progress));
        canvas.drawRenderNode(blurNode);
        return true;
    }

    private void drawFallbackBlur(Canvas canvas, int w, int h, float progress,
                                  float opacity, float secondWeight) {
        int alpha = Math.round(255f * opacity * Math.min(1f, 0.35f + progress));
        int layer = canvas.saveLayerAlpha(0f, 0f, w, h, alpha);
        for (int i = 0; i < pageCount; i++) {
            Page page = pages[i];
            if (page.blur == null || page.blur.isRecycled()) continue;
            blurPaint.setAlpha(i == 0 ? 255 : Math.round(255f * secondWeight));
            float blurScale = Math.max(w / (float) page.blur.getWidth(),
                    h / (float) page.blur.getHeight()) * 1.4f;
            float blurW = page.blur.getWidth() * blurScale;
            float blurH = page.blur.getHeight() * blurScale;
            float blurLeft = (w - blurW) / 2f + page.offsetPx * 0.5f;
            src.set(0, 0, page.blur.getWidth(), page.blur.getHeight());
            dst.set(blurLeft, (h - blurH) / 2f, blurLeft + blurW, (h + blurH) / 2f);
            canvas.drawBitmap(page.blur, src, dst, blurPaint);
        }
        canvas.restoreToCount(layer);
    }

    private float secondPageWeight() {
        if (pageCount < 2) return 0f;
        float firstDistance = Math.abs(pages[0].offsetPx);
        float secondDistance = Math.abs(pages[1].offsetPx);
        float total = firstDistance + secondDistance;
        return total <= 0f ? 0f : Math.max(0f, Math.min(1f, firstDistance / total));
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(255 * alpha) << 24) | (color & 0x00FFFFFF);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    // --- Colours -----------------------------------------------------------------------------

    /** A dark, theme-safe colour from the cover's strongest hue. */
    public static int deepColorOf(Bitmap artwork) {
        if (artwork == null || artwork.isRecycled()) return Color.rgb(24, 24, 26);
        final int hueBins = 24;
        double[] weights = new double[hueBins];
        double[] hueSin = new double[hueBins];
        double[] hueCos = new double[hueBins];
        double[] saturations = new double[hueBins];
        double[] values = new double[hueBins];
        int stepX = Math.max(1, artwork.getWidth() / 72);
        int stepY = Math.max(1, artwork.getHeight() / 72);
        int[] row = new int[artwork.getWidth()];
        for (int y = 0; y < artwork.getHeight(); y += stepY) {
            artwork.getPixels(row, 0, artwork.getWidth(), 0, y, artwork.getWidth(), 1);
            for (int x = 0; x < artwork.getWidth(); x += stepX) {
                int color = row[x];
                int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int delta = max - min;
                if (delta == 0 || max == 0) continue;
                float saturation = delta / (float) max;
                float hue;
                if (max == r) hue = 60f * (((g - b) / (float) delta) % 6f);
                else if (max == g) hue = 60f * ((b - r) / (float) delta + 2f);
                else hue = 60f * ((r - g) / (float) delta + 4f);
                if (hue < 0f) hue += 360f;
                float value = max / 255f;
                int bin = Math.min(hueBins - 1, (int) (hue / 15f));
                double weight = saturation * (0.35 + 0.65
                        * Math.max(0.15, 1.0 - Math.abs(value - 0.58f) / 0.58f));
                double angle = Math.toRadians(hue);
                weights[bin] += weight;
                hueSin[bin] += Math.sin(angle) * weight;
                hueCos[bin] += Math.cos(angle) * weight;
                saturations[bin] += saturation * weight;
                values[bin] += value * weight;
            }
        }
        int dominant = 0;
        for (int i = 1; i < hueBins; i++) {
            if (weights[i] > weights[dominant]) dominant = i;
        }
        double totalWeight = 0, sin = 0, cos = 0, saturation = 0, value = 0;
        // Adjacent bins keep hues around the 0/360 boundary in one mood colour.
        for (int delta = -1; delta <= 1; delta++) {
            int bin = (dominant + delta + hueBins) % hueBins;
            totalWeight += weights[bin];
            sin += hueSin[bin];
            cos += hueCos[bin];
            saturation += saturations[bin];
            value += values[bin];
        }
        if (totalWeight <= 0d) {
            float[] hsv = new float[3];
            Color.colorToHSV(averageOf(artwork, 0.5f), hsv);
            return paletteColor(Color.HSVToColor(hsv), 0.20f, 0.62f, 0.13f, 0.13f);
        }
        float[] hsv = new float[3];
        hsv[0] = (float) Math.toDegrees(Math.atan2(sin, cos));
        if (hsv[0] < 0f) hsv[0] += 360f;
        hsv[1] = Math.min(0.9f, (float) (saturation / totalWeight) * 1.2f);
        hsv[2] = Math.min((float) (value / totalWeight), 0.55f);
        return paletteColor(Color.HSVToColor(hsv), 0.20f, 0.62f, 0.13f, 0.13f);
    }

    /** What the artwork's bottom edge blurs into, bounded like BitChord's dark wash palette. */
    public static int washColorOf(Bitmap artwork) {
        if (artwork == null || artwork.isRecycled()) return Color.rgb(24, 24, 26);
        return paletteColor(averageOf(artwork, 0.82f), 0.18f, 0.58f, 0.14f, 0.24f);
    }

    public static int accentColorOf(int moodColor) {
        return paletteColor(moodColor, 0.55f, 1f, 0.62f, 0.78f);
    }

    public static int elevatedColorOf(int moodColor) {
        return paletteColor(moodColor, 0.20f, 0.62f, 0.22f, 0.22f);
    }

    private static int paletteColor(int color, float minSaturation, float maxSaturation,
                                    float minLightness, float maxLightness) {
        float[] hsl = toHsl(color);
        if (hsl[1] >= 0.12f) {
            hsl[1] = Math.max(minSaturation, Math.min(maxSaturation, hsl[1]));
        }
        hsl[2] = Math.max(minLightness, Math.min(maxLightness, hsl[2]));
        return fromHsl(hsl);
    }

    private static float[] toHsl(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h = 0f;
        float l = (max + min) * 0.5f;
        float s = 0f;
        if (delta > 0f) {
            s = delta / (1f - Math.abs(2f * l - 1f));
            if (max == r) h = 60f * (((g - b) / delta) % 6f);
            else if (max == g) h = 60f * ((b - r) / delta + 2f);
            else h = 60f * ((r - g) / delta + 4f);
            if (h < 0f) h += 360f;
        }
        return new float[]{h, s, l};
    }

    private static int fromHsl(float[] hsl) {
        float h = hsl[0] / 60f;
        float s = hsl[1];
        float l = hsl[2];
        float chroma = (1f - Math.abs(2f * l - 1f)) * s;
        float x = chroma * (1f - Math.abs(h % 2f - 1f));
        float r = 0f, g = 0f, b = 0f;
        if (h < 1f) { r = chroma; g = x; }
        else if (h < 2f) { r = x; g = chroma; }
        else if (h < 3f) { g = chroma; b = x; }
        else if (h < 4f) { g = x; b = chroma; }
        else if (h < 5f) { r = x; b = chroma; }
        else { r = chroma; b = x; }
        float m = l - chroma * 0.5f;
        return Color.rgb(Math.round((r + m) * 255f), Math.round((g + m) * 255f),
                Math.round((b + m) * 255f));
    }

    /** A white with a touch of the cover's colour, for the handle. */
    public static int handleColorOf(Bitmap blur) {
        int average = averageOf(blur, 0f);
        float[] hsv = new float[3];
        Color.colorToHSV(average, hsv);
        hsv[1] = Math.min(hsv[1], 0.18f);
        hsv[2] = 1f;
        return (0xB3 << 24) | (Color.HSVToColor(hsv) & 0x00FFFFFF);
    }

    private static int averageOf(Bitmap blur, float fromFraction) {
        if (blur == null || blur.isRecycled()) return Color.rgb(24, 24, 26);
        int w = blur.getWidth();
        int h = blur.getHeight();
        int start = Math.min(h - 1, Math.round(h * fromFraction));
        int rows = Math.max(1, h - start);
        int[] px = new int[w * rows];
        blur.getPixels(px, 0, w, 0, start, w, rows);
        long r = 0, g = 0, b = 0;
        for (int c : px) {
            r += (c >> 16) & 0xff;
            g += (c >> 8) & 0xff;
            b += c & 0xff;
        }
        int n = px.length;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /** A small, strongly blurred copy of {@code cover}: the backdrop's colours. */
    public static Bitmap blurOf(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        int size = 72;
        Bitmap small = Bitmap.createScaledBitmap(cover, size, size, true);
        Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
        if (small != cover && small != copy) small.recycle();
        int[] px = new int[size * size];
        copy.getPixels(px, 0, size, 0, 0, size, size);
        // Three box passes in each direction approximate a gaussian; radius in small pixels.
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, size, size, 6, true);
            boxBlur(px, size, size, 6, false);
        }
        copy.setPixels(px, 0, size, 0, 0, size, size);
        return copy;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int[] line = new int[len];
        int window = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int a = 0, rr = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                a += c >>> 24; rr += (c >> 16) & 0xff; g += (c >> 8) & 0xff; b += c & 0xff;
            }
            for (int i = 0; i < len; i++) {
                int out = ((a / window) << 24) | ((rr / window) << 16) | ((g / window) << 8) | (b / window);
                px[horizontal ? l * w + i : i * w + l] = out;
                int add = line[Math.min(len - 1, i + r + 1)];
                int remove = line[Math.max(0, i - r)];
                a += (add >>> 24) - (remove >>> 24);
                rr += ((add >> 16) & 0xff) - ((remove >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((remove >> 8) & 0xff);
                b += (add & 0xff) - (remove & 0xff);
            }
        }
    }
}
