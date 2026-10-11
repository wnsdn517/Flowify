package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Set;

/** Applies one fullscreen lyric animation frame to the currently mounted row window. */
public final class LyricsFrameRenderer {
    private static final int SCROLL_RENDER_MARGIN_ROWS = 8;
    /** A row this short reads as a visual accent rather than a paragraph, and takes a gentler blur
     *  curve so it does not dissolve entirely while its neighbours stay legible. */
    private static final int SHORT_LINE_CODE_POINTS = 12;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsAnimationApplier.StyleSink styleSink;
    private final float scaledDensity;
    private final float density;
    private int lastActiveIndex = Integer.MIN_VALUE;
    private boolean lastUserScrollHeld;

    public LyricsFrameRenderer(Context context, FrameStyleBatcher styleBatcher) {
        this.styleBatcher = styleBatcher;
        scaledDensity = context == null ? 1f : context.getResources().getDisplayMetrics().scaledDensity;
        density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        styleSink = new LyricsAnimationApplier.StyleSink() {
            @Override
            public void applyAlpha(View view, float alpha) {
                LyricsFrameRenderer.this.styleBatcher.applyAlphaIfChanged(view, alpha);
            }

            @Override
            public void applyScale(View view, float scaleX, float scaleY) {
                LyricsFrameRenderer.this.styleBatcher.applyScaleIfChanged(view, scaleX, scaleY);
            }

            @Override
            public void applyTranslationY(View view, float translationY) {
                LyricsFrameRenderer.this.styleBatcher.applyTranslationYIfChanged(view, translationY);
            }
        };
    }

    // Cascading rows are drawn away from their layout slot for a moment, so the culling keeps a
    // couple of rows beyond the estimated viewport.
    private static final int OFFSCREEN_MARGIN_ROWS = 2;

    /**
     * The one culling rule, shared by the frame pass and the pending-animation probe.
     *
     * <p>A row outside this window keeps only its cheap row-level fade/blur, so its scale, glow
     * and syllable springs are left mid-flight by design. Counting such a row as pending would
     * pin the frame scheduler on forever: a paused player with one animating row scrolled out of
     * view would never go idle.
     *
     * @param visibleEnd {@link Integer#MAX_VALUE} for "no viewport known" (all lines visible)
     */
    static boolean isCulledOffscreen(int index, int activeIndex, int visibleStart, int visibleEnd) {
        return index != activeIndex && visibleEnd != Integer.MAX_VALUE
                && (index < visibleStart - OFFSCREEN_MARGIN_ROWS
                    || index > visibleEnd + OFFSCREEN_MARGIN_ROWS);
    }

    /**
     * True while a mounted row the renderer would actually draw still has a spring to drain.
     *
     * <p>Culling has to match {@link #applySynced}: rows that pass culled cannot settle while
     * they are off screen, so they must not hold the scheduler open either.
     */
    public boolean hasPendingAnimation(LyricsDocument document, Set<Integer> mountedIndices,
                                       ViewGroup mountedRowsHost, int activeIndex,
                                       int visibleStart, int visibleEnd) {
        if (document == null || document.appliedLines == null || mountedIndices == null) return false;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            if (isCulledOffscreen(i, activeIndex, visibleStart, visibleEnd)) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (LyricsLineViewState.isMounted(line, mountedRowsHost)
                    && !LyricsLineViewState.isSettled(line)) return true;
        }
        return false;
    }

    /** Unsynced lyrics: every mounted row drawn fully bright, no blur/scale/wash. */
    public void applyStatic(LyricsDocument document, Set<Integer> mountedIndices, ViewGroup mountedRowsHost) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;
            LyricsLineViewState.applyStaticFrame(line, styleBatcher);
        }
        styleBatcher.flush();
    }

    public void applySynced(
            LyricsDocument document,
            Set<Integer> mountedIndices,
            ViewGroup mountedRowsHost,
            LyricsRenderConfig config,
            long positionMs,
            int activeIndex,
            float deltaSeconds,
            boolean userScrollHeld
    ) {
        applySynced(document, mountedIndices, mountedRowsHost, config, positionMs, activeIndex,
                deltaSeconds, userScrollHeld, 0, Integer.MAX_VALUE);
    }

    public void applySynced(
            LyricsDocument document,
            Set<Integer> mountedIndices,
            ViewGroup mountedRowsHost,
            LyricsRenderConfig config,
            long positionMs,
            int activeIndex,
            float deltaSeconds,
            boolean userScrollHeld,
            int visibleStart,
            int visibleEnd
    ) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        // Every caller should select the correct mode, but this boundary must fail closed. Static
        // documents can acquire synthetic timestamps during parsing; those timestamps are layout
        // data, not permission to dim every row except a fabricated active one.
        if (LyricsRenderMode.isStatic(document)) {
            applyStatic(document, mountedIndices, mountedRowsHost);
            return;
        }
        // Per-segment lift-motion constants, synced for the segments this frame will step
        // (not only on config change): springs are created lazily at mount, so a
        // config-path-only sync misses first mount with Apple already on. Each renderer syncs
        // its own segments - the now-playing card can run with lift off ("Minimal") while the
        // lyrics screen runs it on - so one surface never resets the other's springs. No-op per
        // segment when unchanged.
        boolean appleMotion = config != null && config.appleLift;
        for (int syncIndex : mountedIndices) {
            if (syncIndex < 0 || syncIndex >= document.appliedLines.size()) continue;
            AppliedLine syncLine = document.appliedLines.get(syncIndex);
            if (syncLine == null || syncLine.words == null) continue;
            if (!LyricsLineViewState.isMounted(syncLine, mountedRowsHost)) continue;
            for (SyllableSegment word : syncLine.words) {
                LyricsSyllableViewState.syncSegmentMotion(word, appleMotion);
            }
        }
        int boundedVisibleStart = Math.max(0, visibleStart - SCROLL_RENDER_MARGIN_ROWS);
        int boundedVisibleEnd = visibleEnd >= Integer.MAX_VALUE - SCROLL_RENDER_MARGIN_ROWS
                ? Integer.MAX_VALUE
                : visibleEnd + SCROLL_RENDER_MARGIN_ROWS;
        boolean activeChanged = activeIndex != lastActiveIndex;
        boolean scrollHoldChanged = userScrollHeld != lastUserScrollHeld;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;
            boolean isOutsideVisibleHoldWindow = userScrollHeld && i != activeIndex
                    && (i < boundedVisibleStart || i > boundedVisibleEnd);
            // Off screen (mounted ahead of the viewport, or already scrolled past): nobody sees its
            // per-syllable motion, so it keeps only its cheap row-level fade/blur. When it scrolls
            // into view its springs are still unsettled and it gets the full pass from then on.
            boolean offscreen = isCulledOffscreen(i, activeIndex, visibleStart, visibleEnd);
            LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                    line, positionMs, config.lineGradientEnabled, config.appleDimPassed);
            int targetClass = lineState.active ? 1 : lineState.sung ? 2 : 0;
            // Refresh blur on hold transitions so rows go sharp while held and restore on
            // release; steady hold needs no refresh (values already settled). While held,
            // active-line advances must not restore blur either: resume happens only on
            // snap-back (hold release), which arrives as scrollHoldChanged.
            boolean blurNeedsRefresh = blurNeedsRefresh(
                    config.lineBlurEnabled, activeChanged, scrollHoldChanged, userScrollHeld);
            if (!lineState.active && (offscreen || !blurNeedsRefresh
                    && !LyricsLineViewState.needsFrame(line, targetClass))) {
                // Keep applying the frame while the blur spring settles. Stepping the spring but
                // returning here left the View's RenderEffect at its old value, so the next
                // active-line refresh appeared to "undo" the gradual blur in one frame.
                float blurTarget = mobileLineBlurPx(line, i, activeIndex, lineState.active, userScrollHeld, config, positionMs);
                float blur = LyricsLineViewState.stepLineBlur(line, blurTarget, deltaSeconds);
                float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active,
                        lineState.sung, deltaSeconds, config.appleDimPassed);
                LyricsLineViewState.ensureScalePivots(line);
                LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blur);
                continue;
            }
            LyricsLineViewState.invalidateSettled(line);
            float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active, lineState.sung,
                    deltaSeconds, config.appleDimPassed);
            float blurTarget = mobileLineBlurPx(line, i, activeIndex, lineState.active, userScrollHeld, config, positionMs);
            float blur = LyricsLineViewState.stepLineBlur(line, blurTarget, deltaSeconds);
            // Always drain blur for rows outside the visible hold window, but skip
            // expensive rendering since they're not visible during the scroll hold.
            if (isOutsideVisibleHoldWindow) {
                continue;
            }
            LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blur);
            // Let motion and blur carry the transition; do not add a heavier shadow behind the
            // newly focused line. Drain any legacy shadow state to zero as well.
            float lineShadow = LyricsLineViewState.stepLineShadow(line, 0f, deltaSeconds);
            LyricsLineViewState.applyLineShadow(line, lineShadow);
            float lineGlowTarget = config.glowBlurEnabled ? lineState.glowTarget : 0f;
            if (config.appleDimPassed && lineState.active) lineGlowTarget = Math.max(lineGlowTarget, 0.28f);
            float lineGlow = LyricsAnimationApplier.stepLineGlow(line, lineGlowTarget, deltaSeconds);

            boolean appleLineDocument = "Line".equalsIgnoreCase(document.type);
            // Line-synced Apple rows light as a whole; see LyricsLineViewState#stepLineLit.
            float litBrightness = appleLineDocument
                    ? lineState.brightnessTarget * LyricsLineViewState.stepLineLit(
                            line, lineState.active || lineState.sung, deltaSeconds)
                    : 1f;
            if (LyricsLineViewState.hasMainView(line)) {
                float lineScaleTarget = !appleLineDocument ? lineState.scaleTarget : 1f;
                float scale = LyricsAnimationApplier.stepLineScale(line, lineScaleTarget, deltaSeconds);
                LyricsLineViewState.updateMainScalePivot(line);
                scale = LyricsLineViewState.fitScale(LyricsLineViewState.mainViewOf(line),
                        line.oppositeAligned, scale, scaleEdgeMarginPx());
                LyricsLineViewState.applyMainScale(line, styleBatcher, scale);
                if (appleLineDocument) {
                    LyricsLineViewState.applyLineLevelGradient(
                            line, LyricAnimations.GRADIENT_SUNG, 0f, litBrightness);
                } else {
                    LyricsLineViewState.applyLineLevelGradient(
                            line, lineState.gradient, lineGlow, lineState.brightnessTarget);
                }
            } else if (line.words != null && !line.words.isEmpty()) {
                // A row without a single main text view is a word-row block: it gets the
                // per-visual-line zoom and the held-word swell.
                View wordContainer = LyricsSyllableViewState.parentView(line.words.get(0));
                if (wordContainer != null) {
                    boolean bounce = !appleLineDocument;
                    float lineScaleTarget = bounce ? lineState.scaleTarget : 1f;
                    GlowFlexbox flex = wordContainer instanceof GlowFlexbox ? (GlowFlexbox) wordContainer : null;
                    boolean perVisualLine = flex != null && flex.visualLineCount() > 1;
                    if (perVisualLine) {
                        // Wrapped line: the block itself only settles to 1; the zoom goes to the
                        // visual line being sung (GlowFlexbox#stepLineZoom).
                        if (lineState.active) lineScaleTarget = Math.min(1f, lineScaleTarget);
                        float zoomTarget = 1f;
                        float heldTarget = 1f;
                        int sungLine = -1;
                        if (lineState.active && bounce) {
                            float maxScale = 1.03f;
                            // The whole wrapped line zooms as the lyric starts - not following the
                            // audio line by line, which drifted from the singing - but as a quick
                            // top-to-bottom ripple: each visual line a moment after the one above.
                            long intoLyric = positionMs - line.startMs;
                            sungLine = (int) Math.min(flex.visualLineCount() - 1,
                                    Math.max(0L, intoLyric) / VISUAL_LINE_STAGGER_MS);
                            zoomTarget = maxScale;
                            heldTarget = maxScale;
                        }
                        flex.stepLineZoom(sungLine, zoomTarget, heldTarget, deltaSeconds,
                                line.oppositeAligned, scaleEdgeMarginPx());
                    } else if (flex != null && flex.hasLineZoom()) {
                        flex.stepLineZoom(-1, 1f, 1f, deltaSeconds, line.oppositeAligned, scaleEdgeMarginPx());
                    }
                    if (flex != null) {
                        // Held words swell, the rest of their visual line narrows to make room.
                        SyllableSegment held = lineState.active && bounce ? heldWord(line, positionMs) : null;
                        flex.stepWordEmphasis(held == null ? null : LyricsSyllableViewState.wordView(held),
                                held == null ? 0f : heldEmphasis(held, positionMs), deltaSeconds);
                    }
                    float scale = LyricsAnimationApplier.stepLineScale(line, lineScaleTarget, deltaSeconds);
                    if (wordContainer.getWidth() > 0 && wordContainer.getHeight() > 0) {
                        wordContainer.setPivotX(line.oppositeAligned ? wordContainer.getWidth() : 0f);
                        wordContainer.setPivotY(wordContainer.getHeight() * 0.5f);
                    }
                    scale = LyricsLineViewState.fitScale(wordContainer, line.oppositeAligned, scale,
                            scaleEdgeMarginPx());
                    styleBatcher.applyScaleIfChanged(wordContainer, scale, scale);
                }
            }
            if (line.dotLine) {
                if (lineState.active) {
                    LyricsAnimationApplier.animateInterludeDots(line, positionMs, deltaSeconds, spToPx(44), styleSink);
                } else {
                    LyricsAnimationApplier.resetInterludeDots(line, styleSink, lineState.sung);
                }
            } else if (appleLineDocument) {
                // Line-synced Apple rows have no real word spans: never animate fabricated words
                // or sweep them. A row built from word views (reading or furigana attached to
                // fabricated spans) lights as a whole, exactly like a single-text row - resetting
                // its words alone left them at the unsung colour, so the line never lit at all.
                if (!LyricsLineViewState.hasMainView(line) && line.words != null) {
                    LyricsAnimationApplier.resetSyllables(line, styleSink, false);
                    for (SyllableSegment word : line.words) {
                        LyricsSyllableViewState.applyLitFrame(word, litBrightness);
                    }
                }
                LyricsLineViewState.applyLineSecondaryGradient(
                        line, LyricAnimations.GRADIENT_SUNG, 0f);
            } else {
                applySecondaryGradient(line, positionMs, lineGlow, config);
                if (lineState.active || lineState.sung) {
                    LyricsAnimationApplier.animateSyllables(
                            line,
                            positionMs,
                            deltaSeconds,
                            spToPx(LyricsLineViewState.effectiveBaseTextSp(line)),
                            styleSink,
                            config.glowBlurEnabled,
                            config.appleLift,
                            config.appleDimPassed);
                } else {
                    LyricsAnimationApplier.resetSyllables(line, styleSink, config.appleLift,
                            false, config.appleLift);
                }
            }
            LyricsLineViewState.markFrameApplied(line, targetClass);
        }
        lastActiveIndex = activeIndex;
        lastUserScrollHeld = userScrollHeld;
        styleBatcher.flush();
    }

    /**
     * Blur refresh gate (pure, unit-tested): active-line advances refresh blur only when
     * the user is not holding the scroll; a hold release (snap-back) always refreshes so
     * blur restores exactly when follow resumes.
     */
    static boolean blurNeedsRefresh(boolean lineBlurEnabled, boolean activeChanged,
            boolean scrollHoldChanged, boolean userScrollHeld) {
        if (!lineBlurEnabled) return false;
        if (scrollHoldChanged) return true;
        return activeChanged && !userScrollHeld;
    }

    private void applySecondaryGradient(AppliedLine line, long positionMs, float spotGlow, LyricsRenderConfig config) {
        if (line == null) return;
        boolean animate = config.lineGradientEnabled;
        float gradient = animate ? secondaryLineGradientPosition(line, positionMs) : 100f;
        float glow = config.glowBlurEnabled && animate && positionMs >= line.startMs && positionMs < line.endMs ? 0.10f : 0f;
        LyricsLineViewState.applyLineSecondaryGradient(line, gradient, glow);
    }

    private float secondaryLineGradientPosition(AppliedLine line, long positionMs) {
        if (line == null || positionMs < line.startMs) return LyricAnimations.GRADIENT_UNSUNG;
        long fillEnd = LyricTimeline.fillEndMs(line);
        if (positionMs >= fillEnd) return LyricAnimations.GRADIENT_SUNG;
        return LyricAnimations.gradientPosition(progress01(positionMs, line.startMs, fillEnd));
    }

    /**
     * Settles a row that just (re)entered the mounted window at the opacity and blur the current
     * frame wants, and applies them before it is drawn - see
     * {@link LyricsLineViewState#snapRowSprings}.
     */
    public void settleMountedRow(LyricsDocument document, AppliedLine line, int index, int activeIndex,
                                 long positionMs, LyricsRenderConfig config, boolean userScrollHeld) {
        if (line == null || config == null || document == null) return;
        LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                line, positionMs, config.lineGradientEnabled, config.appleDimPassed);
        float opacity = LyricsAnimationApplier.lineOpacityTarget(line, lineState.active,
                lineState.sung, config.appleDimPassed);
        float blur = mobileLineBlurPx(line, index, activeIndex, lineState.active,
                userScrollHeld, config, positionMs);
        LyricsLineViewState.snapRowSprings(line, opacity, blur);
        LyricsLineViewState.ensureScalePivots(line);
        LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blur);
    }

    private float mobileLineBlurPx(AppliedLine line, int index, int active, boolean lineActive,
                                   boolean userScrollHeld, LyricsRenderConfig config, long positionMs) {
        if (line == null || Build.VERSION.SDK_INT < 31) return 0f;
        if (!config.lineBlurEnabled) return 0f;
        if (userScrollHeld) return 0f;
        // The active row (and an extended-window active row past the focus index) always stays
        // sharp.
        if (lineActive) return 0f;
        // A background-vocal row is its own applied-row-list entry, one slot after the main line
        // it is sung with - so once it falls quiet (its own, usually shorter, sungUntil passes)
        // "distance" below reads it as the next (inactive) LINE and starts blurring it, even
        // though the main line it belongs to, right behind it, is still being sung. Judge it by
        // that main line's own window instead, not the applied-row list position.
        if (line.bgLine && line.sourceLine != null
                && positionMs >= line.sourceLine.startMs && positionMs < line.sourceLine.endMs) {
            return 0f;
        }
        float quality = config.blurQuality;
        if (quality <= 0f) return 0f;
        if (active < 0) return 0f;
        int distance = Math.abs(index - active);
        if (distance == 0) return 0f;
        boolean emphasized = line.dotLine || line.isShortText(SHORT_LINE_CODE_POINTS);
        // A short/accent row already gets a lower ceiling below, but at distance 1 the curve
        // still put a few px on it immediately - on a couple of glyphs that reads as the row
        // dissolving the moment it stops being active, not as a gradual recede. Giving it one
        // extra row of distance before the curve starts keeps its immediate neighbour sharp.
        int curveDistance = emphasized ? Math.max(0, distance - 1) : distance;
        if (curveDistance == 0) return 0f;
        // Let nearby rows dissolve into the ambient blur as focus advances. The active row
        // remains sharp; the first neighbour gets a restrained veil and the curve grows smoothly
        // with distance rather than switching on abruptly at row two.
        float max = config.lineBlurHeavy
                ? (emphasized ? 7.0f : 10.0f)
                : (emphasized ? 3.2f : 4.8f);
        float curved = (float) Math.pow(Math.min(1f, curveDistance / 3.2f), 0.72);
        return max * curved * quality;
    }

    private float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return LyricAnimations.clamp01((positionMs - startMs) / (float) (endMs - startMs));
    }

    private float spToPx(float sp) {
        return sp * scaledDensity;
    }

    /** The word being sung right now (started, not yet ended), or null. */
    private static SyllableSegment heldWord(AppliedLine line, long positionMs) {
        for (SyllableSegment segment : line.words) {
            if (segment == null) continue;
            if (segment.startMs <= positionMs && positionMs < segment.endMs) return segment;
        }
        return null;
    }

    /**
     * 0..1: how much a word swells. Only held words - short ones never swell, so fast passages
     * stay calm - rising over the first part of the word and holding while it is sustained.
     */
    private static float heldEmphasis(SyllableSegment word, long positionMs) {
        long duration = word.endMs - word.startMs;
        float slowness = LyricAnimations.clamp01((duration - 380f) / 900f);
        if (slowness <= 0f) return 0f;
        float progress = LyricAnimations.clamp01((positionMs - word.startMs) / (float) Math.max(1L, duration));
        return slowness * LyricAnimations.easeSinOut(Math.min(1f, progress * 2.5f));
    }

    private static final long VISUAL_LINE_STAGGER_MS = 110L;

    /** Space a zoomed line always keeps from the screen edge. */
    private float scaleEdgeMarginPx() {
        return 16f * density;
    }
}
