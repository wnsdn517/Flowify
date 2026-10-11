package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.SpotifyPlusConfig;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bounded display-only lyric overlay mounted above Spotify's unchanged album cover. In Apple
 * inline mode it is the Apple-style player's lyrics view instead: no scrim or buttons (the cover
 * behind it fades away), edges that fade out, lines that glide up, and a tap that closes it.
 */
public final class ArtworkLyricsOverlayView extends FrameLayout {
    private static final int TIMED_CANDIDATE_RADIUS = 6;
    private static final int STATIC_CANDIDATE_LIMIT = 12;
    private final LinearLayout rowsHost;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsFrameRenderer frameRenderer;
    private final Set<Integer> mountedIndices = new LinkedHashSet<>();

    private LyricsDocument document;
    private LyricsRenderConfig renderConfig;
    private int[] rowHeights;
    private int measuredAnchor = Integer.MIN_VALUE;
    private int mountedStart = -1;
    private int mountedEnd = -1;
    private int mountedAnchor = -1;
    private final View scrim;
    private final LinearLayout actions;
    private final Paint edgeMask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int edgeMaskHeight = -1;
    private boolean appleInline;
    /** Window anchor that was centred last, for the glide to the next line. */
    private int centeredAnchor = -1;
    private InlineGestures inlineGestures;
    private TextView emptyMessage;
    private float touchDownX;
    private float touchDownY;
    private boolean touchSwiped;

    /** What a tap and a vertical swipe on the in-place lyrics do (Apple inline mode). */
    public interface InlineGestures {
        void onTap();

        /** @param down the finger moved down the screen */
        void onSwipe(boolean down);
    }

    public ArtworkLyricsOverlayView(Context context) {
        super(context);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClipChildren(true);
        setClipToPadding(true);

        scrim = new View(context);
        GradientDrawable scrimBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0xBF000000, 0xD9000000});
        scrim.setBackground(scrimBackground);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        rowsHost = new LinearLayout(context);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        rowsHost.setGravity(Gravity.TOP);
        rowsHost.setClipChildren(false);
        rowsHost.setClipToPadding(false);
        LayoutParams rowsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        int horizontal = dp(14);
        rowsHost.setPadding(horizontal, dp(54), horizontal, dp(12));
        addView(rowsHost, rowsLp);

        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(6), dp(8), 0);
        LayoutParams actionsLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP);
        addView(actions, actionsLp);

        TextView expand = actionButton(context, "↗", context.getString(R.string.artwork_lyrics_expand));
        TextView close = actionButton(context, "×", context.getString(R.string.artwork_lyrics_close));
        actions.addView(expand, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        closeLp.leftMargin = dp(4);
        actions.addView(close, closeLp);
        expand.setTag(ActionTag.EXPAND);
        close.setTag(ActionTag.CLOSE);

        styleBatcher = new FrameStyleBatcher(context);
        frameRenderer = new LyricsFrameRenderer(context, styleBatcher);
    }

    public void setActions(Runnable onClose, Runnable onExpand) {
        View close = findViewWithTag(ActionTag.CLOSE);
        View expand = findViewWithTag(ActionTag.EXPAND);
        if (close != null) close.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
        if (expand != null) expand.setOnClickListener(v -> { if (onExpand != null) onExpand.run(); });
    }

    /**
     * Apple inline mode: the lyrics take the cover's place in the Apple-style player. Wider
     * margins, no scrim or buttons, faded edges; taps and vertical swipes go to
     * {@link #setInlineGestures}.
     */
    public void setAppleInline(boolean inline) {
        if (appleInline == inline) return;
        appleInline = inline;
        scrim.setVisibility(inline ? GONE : VISIBLE);
        actions.setVisibility(inline ? GONE : VISIBLE);
        int horizontal = dp(inline ? 26 : 14);
        rowsHost.setPadding(horizontal, dp(inline ? 18 : 54), horizontal, dp(inline ? 18 : 12));
        setClickable(inline);
        setWillNotDraw(!inline);
        edgeMaskHeight = -1;
        invalidateMeasurements();
    }

    /** A centred note in place of the lyrics (a song without any); null removes it. */
    public void setEmptyMessage(CharSequence message) {
        if (message == null) {
            if (emptyMessage != null) emptyMessage.setVisibility(GONE);
            return;
        }
        if (emptyMessage == null) {
            emptyMessage = new TextView(getContext());
            emptyMessage.setTextColor(0xB3FFFFFF);
            emptyMessage.setTextSize(20f);
            emptyMessage.setGravity(Gravity.CENTER);
            emptyMessage.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            int pad = dp(32);
            emptyMessage.setPadding(pad, 0, pad, 0);
            addView(emptyMessage, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        if (!android.text.TextUtils.equals(emptyMessage.getText(), message)) emptyMessage.setText(message);
        emptyMessage.setVisibility(VISIBLE);
    }

    public void setInlineGestures(InlineGestures gestures) {
        inlineGestures = gestures;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!appleInline || inlineGestures == null) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchSwiped = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                // One swipe per touch, and only a clearly vertical one.
                if (!touchSwiped && Math.abs(dy) > dp(36) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    touchSwiped = true;
                    inlineGestures.onSwipe(dy > 0f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (!touchSwiped && Math.abs(event.getX() - touchDownX) < slop
                        && Math.abs(event.getY() - touchDownY) < slop) {
                    inlineGestures.onTap();
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!appleInline || getWidth() <= 0 || getHeight() <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        int h = getHeight();
        if (edgeMaskHeight != h) {
            edgeMaskHeight = h;
            edgeMask.setShader(new LinearGradient(0f, 0f, 0f, h,
                    new int[]{Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT},
                    new float[]{0f, 0.12f, 0.86f, 1f}, Shader.TileMode.CLAMP));
            edgeMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }
        int save = canvas.saveLayer(0f, 0f, getWidth(), h, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0f, 0f, getWidth(), h, edgeMask);
        canvas.restoreToCount(save);
    }

    public void showOverlay() {
        if (getVisibility() == VISIBLE) return;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        setVisibility(VISIBLE);
        invalidateMeasurements();
    }

    public void hideOverlay() {
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        clearMountedRows();
    }

    public boolean isOverlayVisible() {
        return getVisibility() == VISIBLE;
    }

    public void setDocument(LyricsDocument nextDocument, LyricsRenderConfig nextConfig) {
        document = nextDocument;
        renderConfig = nextConfig;
        invalidateMeasurements();
        if (isOverlayVisible()) post(this::requestLayout);
    }

    public void applyConfig(LyricsRenderConfig nextConfig) {
        renderConfig = nextConfig;
        invalidateMeasurements();
    }

    /** Rebuilds mounted rows after an in-place reading or translation update. */
    public void invalidateMountedContent() {
        invalidateMeasurements();
        if (isOverlayVisible()) post(this::requestLayout);
    }

    public void clearDocument() {
        document = null;
        invalidateMeasurements();
    }

    public void renderFrame(long positionMs, float deltaSeconds) {
        if (!isOverlayVisible() || document == null || renderConfig == null
                || document.appliedLines == null || document.appliedLines.isEmpty()
                || getWidth() <= 0 || getHeight() <= 0) return;
        int available = Math.max(1, getHeight()
                - rowsHost.getPaddingTop() - rowsHost.getPaddingBottom());
        ensureMeasurements(positionMs, available);
        if (rowHeights == null) return;
        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                document, positionMs, available, rowHeights);
        if (window.isEmpty()) {
            clearMountedRows();
            return;
        }
        if (window.startInclusive != mountedStart || window.endExclusive != mountedEnd
                || window.anchorIndex != mountedAnchor) {
            mountWindow(window);
        }
        if (mountedIndices.isEmpty()) return;
        if (LyricsRenderMode.isStatic(document)) {
            frameRenderer.applyStatic(document, mountedIndices, rowsHost);
        } else {
            frameRenderer.applySynced(document, mountedIndices, rowsHost, renderConfig,
                    positionMs, window.anchorIndex, deltaSeconds, false,
                    window.startInclusive, window.endExclusive - 1);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) invalidateMeasurements();
    }

    private void ensureMeasurements(long positionMs, int availableHeight) {
        if (document == null) return;
        boolean staticDocument = LyricsRenderMode.isStatic(document);
        int anchor = staticDocument ? 0
                : LyricTimeline.findPrimaryActiveRow(document.appliedLines, positionMs);
        if (anchor < 0) return;
        if (rowHeights != null && measuredAnchor == anchor) return;
        Activity activity = getContext() instanceof Activity ? (Activity) getContext() : null;
        if (activity == null) return;
        int width = Math.max(1, getWidth() - rowsHost.getPaddingLeft() - rowsHost.getPaddingRight());
        rowHeights = new int[document.appliedLines.size()];
        Arrays.fill(rowHeights, availableHeight + 1);
        int start = staticDocument ? 0 : Math.max(0, anchor - TIMED_CANDIDATE_RADIUS);
        int end = staticDocument
                ? Math.min(document.appliedLines.size(), STATIC_CANDIDATE_LIMIT)
                : Math.min(document.appliedLines.size(), anchor + TIMED_CANDIDATE_RADIUS + 1);
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = LyricsSurfaceRowPlanner.SurfacePolicy.artwork(renderConfig);
        for (int i = start; i < end; i++) {
            AppliedLine line = document.appliedLines.get(i);
            LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document, policy);
            View row = factory.build(plan.line, plan.options, null);
            row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowHeights[i] = Math.max(1, row.getMeasuredHeight());
            clearLineViewState(plan.line);
        }
        measuredAnchor = anchor;
    }

    private void mountWindow(ArtworkLyricWindowPlanner.Window window) {
        float previousCenter = activeCenterOnScreen(window.anchorIndex);
        clearMountedRows();
        Activity activity = getContext() instanceof Activity ? (Activity) getContext() : null;
        if (activity == null) return;
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = LyricsSurfaceRowPlanner.SurfacePolicy.artwork(renderConfig);
        for (int i = window.startInclusive; i < window.endExclusive; i++) {
            AppliedLine line = document.appliedLines.get(i);
            LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document, policy);
            View row = factory.build(plan.line, plan.options, null);
            rowsHost.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mountedIndices.add(i);
        }
        mountedStart = window.startInclusive;
        mountedEnd = window.endExclusive;
        mountedAnchor = window.anchorIndex;
        rowsHost.post(() -> centerActiveRow(window.anchorIndex, previousCenter));
    }

    /**
     * Where the row that is about to become active sits now (its centre, in this view), if it is
     * already mounted - so the remounted window can start from there and glide into place.
     */
    private float activeCenterOnScreen(int nextAnchor) {
        if (!appleInline || document == null || nextAnchor < 0
                || nextAnchor >= document.appliedLines.size() || mountedIndices.isEmpty()) {
            return Float.NaN;
        }
        View row = LyricsLineViewState.attachedRowView(
                document.appliedLines.get(nextAnchor), rowsHost);
        if (row == null) return Float.NaN;
        return rowsHost.getTranslationY() + (row.getTop() + row.getBottom()) * 0.5f;
    }

    private void invalidateMeasurements() {
        rowHeights = null;
        measuredAnchor = Integer.MIN_VALUE;
        clearMountedRows();
    }

    private void clearMountedRows() {
        if (document != null && document.appliedLines != null) {
            for (int index : mountedIndices) {
                if (index >= 0 && index < document.appliedLines.size()) {
                    clearLineViewState(document.appliedLines.get(index));
                }
            }
        }
        styleBatcher.clearPendingWrites();
        rowsHost.animate().cancel();
        rowsHost.removeAllViews();
        rowsHost.setTranslationY(0f);
        mountedIndices.clear();
        mountedStart = -1;
        mountedEnd = -1;
        mountedAnchor = -1;
    }

    private void clearLineViewState(AppliedLine line) {
        LyricsLineViewState.clear(line, rowsHost,
                target -> LyricsLineViewState.invalidate(target, styleBatcher));
    }

    private void centerActiveRow(int anchorIndex, float previousCenter) {
        if (document == null || anchorIndex < 0 || anchorIndex >= document.appliedLines.size()
                || rowsHost.getChildCount() == 0) return;
        View active = LyricsLineViewState.attachedRowView(
                document.appliedLines.get(anchorIndex), rowsHost);
        View first = rowsHost.getChildAt(0);
        View last = rowsHost.getChildAt(rowsHost.getChildCount() - 1);
        if (active == null || first == null || last == null) return;
        float contentCenter = (rowsHost.getPaddingTop()
                + rowsHost.getHeight() - rowsHost.getPaddingBottom()) * 0.5f;
        float desired = contentCenter - (active.getTop() + active.getBottom()) * 0.5f;
        float min = rowsHost.getPaddingTop() - first.getTop();
        float max = rowsHost.getHeight() - rowsHost.getPaddingBottom() - last.getBottom();
        float bounded = min <= max
                ? Math.max(min, Math.min(max, desired))
                : Math.max(-rowsHost.getHeight() * 0.5f,
                        Math.min(rowsHost.getHeight() * 0.5f, desired));
        rowsHost.animate().cancel();
        float activeCenter = (active.getTop() + active.getBottom()) * 0.5f;
        if (appleInline && !Float.isNaN(previousCenter) && anchorIndex != centeredAnchor) {
            // Start where the row was before the window was rebuilt, then glide to centre.
            rowsHost.setTranslationY(previousCenter - activeCenter);
            rowsHost.animate().translationY(bounded).setDuration(560L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        } else {
            rowsHost.setTranslationY(bounded);
        }
        centeredAnchor = anchorIndex;
    }

    private static TextView actionButton(Context context, String glyph, String description) {
        TextView button = new TextView(context);
        button.setText(glyph);
        button.setTextColor(Color.WHITE);
        button.setTextSize(25f);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x66000000);
        background.setShape(GradientDrawable.OVAL);
        button.setBackground(background);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum ActionTag { CLOSE, EXPAND }
}
