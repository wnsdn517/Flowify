package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.SkipGapPolicy;
import com.eza.spicyex.ui.ActionIconDrawable;

/**
 * Owns the floating "skip intro" / "skip" / "next track" affordance shown during the gaps before
 * the first synced line, between sections, and after the last one. Both chips share one
 * baseline. The skip chip lifts to sit just above the "jump to current line" chip (see
 * LyricsJumpToCurrentController) only while that chip is actually on screen and the two would
 * otherwise collide - same anchor, or different anchors whose pills overlap horizontally - and
 * settles back down to the baseline once the follow chip goes away. The lift is animated, so a
 * follow chip appearing under a skip chip that is already up reads as pushing it upward.
 *
 * <p>Opens as a labelled pill, because an unexplained double-chevron in the corner of a lyrics
 * screen does not tell anyone what it will do. Left alone it then collapses to the icon so it
 * stops competing with the lyrics for attention - the label has done its job by then. Which of
 * those two states it settles in is {@link Settings#SKIP_CHIP_STYLE}: a fixed icon, a fixed
 * label, or the collapse. Which corner it floats at is {@link Settings#SKIP_CHIP_POSITION};
 * the jump-to-current chip's own corner is {@link Settings#FOLLOW_CHIP_POSITION}.
 */
final class LyricsSkipGapController {
    /** How long the label stays before the pill collapses to its icon, in "Auto". */
    private static final long COLLAPSE_DELAY_MS = 3200L;
    private static final long COLLAPSE_DURATION_MS = 260L;
    private static final long HIDE_DURATION_MS = 240L;
    private static final int ICON_SIZE_DP = 40;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int SIDE_MARGIN_DP = 16;
    /** Vertical distance between the two stacked chips: 40dp chip + 8dp gap. */
    private static final int STACK_OFFSET_DP = 48;
    /** Chips on different anchors closer than this horizontally count as colliding - wide enough
     *  that two chips never sit flush side by side, reading as one glued-together blob. */
    private static final int COLLISION_GAP_DP = 12;
    /** Quick, so the room is made before the follow chip (held back by the same amount, see
     *  {@link LyricsJumpToCurrentController}) has grown into it. */
    private static final long LIFT_DURATION_MS = 300L;
    private static final long SETTLE_DURATION_MS = 380L;
    /** Lets the follow chip start its own exit before the skip chip drops into its place. */
    private static final long SETTLE_DELAY_MS = 70L;

    private final SpotifyPlusConfig config;
    private final LinearLayout pill;
    private final TextView label;
    private final FrameLayout.LayoutParams lp;
    private final Runnable onTap;
    private final Runnable collapse = this::collapseToIcon;
    /** The follow chip this one stacks above while both are showing. */
    private final LyricsJumpToCurrentController jump;

    private String style = Settings.SKIP_CHIP_STYLE.defaultValue;
    private String position = Settings.SKIP_CHIP_POSITION.defaultValue;
    private String shownLabel = "";
    private boolean collapsed;
    // Tracked separately from the view's own visibility, which lags: hide() fades out over 140ms
    // and only then sets GONE, so a show() landing inside that window used to read the pill as
    // still visible, decline to reset it, and leave a half-faded collapsed circle on screen.
    private boolean visible;
    private ValueAnimator widthAnimator;
    private ValueAnimator liftAnimator;
    private float liftTarget;
    /** Set only by {@link #showForEditing()}, so {@link #restoreAfterEditing()} never hides a
     *  chip a real skip gap put up on its own. */
    private boolean editingForcedVisible;

    private LyricsSkipGapController(SpotifyPlusConfig config, LinearLayout pill, TextView label,
            FrameLayout.LayoutParams lp, Runnable onTap, LyricsJumpToCurrentController jump) {
        this.config = config;
        this.pill = pill;
        this.label = label;
        this.lp = lp;
        this.onTap = onTap;
        this.jump = jump;
    }

    static LyricsSkipGapController attach(
            Activity activity,
            FrameLayout parent,
            SpotifyPlusConfig config,
            LyricsJumpToCurrentController jump,
            Runnable onClick
    ) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        // CENTER, not CENTER_VERTICAL: collapsed, the pill is a 40dp box holding a 20dp icon,
        // and vertical-only centering leaves that icon pinned to the left edge.
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(pillBackground());
        pill.setPadding(dp(10), 0, dp(10), 0);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setAlpha(0f);
        pill.setVisibility(View.GONE);
        pill.setElevation(dp(8));
        NativeIconButtons.applyPressScale(pill);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ActionIconDrawable(
                ActionIconDrawable.Kind.CHEVRONS_RIGHT, TEXT_COLOR, density));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        pill.addView(icon, iconLp);

        TextView label = new TextView(activity);
        label.setTextColor(TEXT_COLOR);
        label.setTextSize(13f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(7);
        pill.addView(label, labelLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ICON_SIZE_DP), Gravity.BOTTOM | Gravity.END);
        // Same default baseline as the follow chip until the readout pushes the real one.
        lp.bottomMargin = dp(24);
        parent.addView(pill, lp);

        LyricsSkipGapController controller = new LyricsSkipGapController(
                config, pill, label, lp, onClick, jump);
        pill.setOnClickListener(v -> {
            if (controller.onTap != null) controller.onTap.run();
            controller.hide();
        });
        if (jump != null) {
            jump.setStackListener(() -> controller.updateStack(true));
            // Answering "am I moving up?" lets the follow chip hold its entrance until there
            // is room, instead of the two crossing mid-animation.
            // Width changes (a label collapsing to its icon, a new label) can open or close a
            // horizontal collision between chips on different anchors.
            View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) controller.updateStack(true);
            };
            jump.view().addOnLayoutChangeListener(relayout);
            pill.addOnLayoutChangeListener(relayout);
        }
        controller.onPreferenceChanged();
        return controller;
    }

    /** Re-reads {@link Settings#SKIP_CHIP_STYLE} and {@link Settings#SKIP_CHIP_POSITION} (call
     *  at mount and from the preference listener). */
    void onPreferenceChanged() {
        String nextStyle = config.get(Settings.SKIP_CHIP_STYLE);
        style = nextStyle == null ? Settings.SKIP_CHIP_STYLE.defaultValue : nextStyle;
        if (pill.getVisibility() == View.VISIBLE) applyStyle(shownLabel, false);

        String nextPosition = config.get(Settings.SKIP_CHIP_POSITION);
        position = nextPosition == null ? Settings.SKIP_CHIP_POSITION.defaultValue : nextPosition;
        applyPosition();
        updateStack(true);
    }

    /** True when the follow chip is on screen and would collide with this one at the shared
     *  baseline: same anchor, or different anchors whose pills overlap horizontally. */
    private boolean shouldStack() {
        if (jump == null || !jump.isVisible()) return false;
        String jumpPosition = jump.position();
        if (position.equals(jumpPosition)) return true;
        View parent = (View) pill.getParent();
        int parentWidth = parent == null ? 0 : parent.getWidth();
        if (parentWidth <= 0) return false;
        int[] mine = span(position, predictedWidth(pill, lp), parentWidth);
        View jumpView = jump.view();
        int[] theirs = span(jumpPosition,
                predictedWidth(jumpView, jumpView.getLayoutParams()), parentWidth);
        int gap = dp(COLLISION_GAP_DP);
        return mine[0] < theirs[1] + gap && theirs[0] < mine[1] + gap;
    }

    /** Horizontal extent [left, right) a chip of the given width takes at an anchor. Layout
     *  direction is ignored: both chips mirror together, and Center is symmetric. */
    private static int[] span(String anchor, int width, int parentWidth) {
        int left;
        if ("Left".equals(anchor)) left = dp(SIDE_MARGIN_DP);
        else if ("Center".equals(anchor)) left = (parentWidth - width) / 2;
        else left = parentWidth - dp(SIDE_MARGIN_DP) - width;
        return new int[]{left, left + width};
    }

    /** The chip's width as it will be laid out: a fixed (collapsed/animating) width if set,
     *  otherwise its wrap width - measured, so a chip that is still GONE can be judged too. */
    private static int predictedWidth(View view, ViewGroup.LayoutParams params) {
        if (params != null && params.width > 0) return params.width;
        // The laid-out width is only trusted when no layout is pending: a chip that just
        // expanded from its icon (or got a new label) still reports its old width until the
        // next pass, which made the check miss a collision until the chips visibly overlapped.
        if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0
                && !view.isLayoutRequested()) {
            return view.getWidth();
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(ICON_SIZE_DP), View.MeasureSpec.EXACTLY));
        return view.getMeasuredWidth();
    }

    /**
     * Moves the chip to its stacked or baseline height. Rising (the follow chip arriving under
     * it) springs up with a small overshoot, as if pushed; settling (the follow chip gone) glides
     * down with a slight landing bounce after a short beat, so it drops into the space being
     * vacated rather than through a chip that is still leaving. A hidden chip just jumps there.
     */
    boolean updateStack(boolean animate) {
        float target = shouldStack() ? -dp(STACK_OFFSET_DP) : 0f;
        if (liftAnimator != null && target == liftTarget) return false;
        liftTarget = target;
        cancelLiftAnimation();
        float current = pill.getTranslationY();
        if (current == target) return false;
        if (!animate || !visible || pill.getVisibility() != View.VISIBLE) {
            pill.setTranslationY(target);
            return false;
        }
        boolean rising = target < current;
        ValueAnimator animator = ValueAnimator.ofFloat(current, target);
        animator.setDuration(rising ? LIFT_DURATION_MS : SETTLE_DURATION_MS);
        animator.setStartDelay(rising ? 0L : SETTLE_DELAY_MS);
        animator.setInterpolator(rising
                ? new OvershootInterpolator(1.4f)
                : new PathInterpolator(0.3f, 0.8f, 0.35f, 1.1f));
        animator.addUpdateListener(a -> pill.setTranslationY((Float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (liftAnimator == a) liftAnimator = null;
            }
        });
        liftAnimator = animator;
        animator.start();
        return rising;
    }

    private void cancelLiftAnimation() {
        ValueAnimator animator = liftAnimator;
        liftAnimator = null;
        if (animator != null) animator.cancel();
    }

    private void applyPosition() {
        int gravity;
        int leftMargin;
        int rightMargin;
        if ("Left".equals(position)) {
            gravity = Gravity.BOTTOM | Gravity.START;
            leftMargin = dp(SIDE_MARGIN_DP);
            rightMargin = 0;
        } else if ("Center".equals(position)) {
            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            leftMargin = 0;
            rightMargin = 0;
        } else {
            gravity = Gravity.BOTTOM | Gravity.END;
            leftMargin = 0;
            rightMargin = dp(SIDE_MARGIN_DP);
        }
        boolean changed = lp.gravity != gravity || lp.leftMargin != leftMargin
                || lp.rightMargin != rightMargin;
        lp.gravity = gravity;
        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
        if (changed) pill.setLayoutParams(lp);
    }

    /** Keeps the chip above the bottom track-info readout, on the follow chip's baseline; any
     *  stacking above that chip is a translation on top (see {@link #updateStack}). */
    void setBottomMarginDp(int baselineDp) {
        int target = dp(baselineDp);
        if (lp.bottomMargin == target) return;
        lp.bottomMargin = target;
        pill.setLayoutParams(lp);
    }

    /** The real on-screen chip, for the layout editor's tap/outline overlay. */
    View view() {
        return pill;
    }

    /** Layout editor preview: shows the chip with a placeholder label so its position can be
     *  edited even with no real skip gap active right now. A no-op if a real gap already has it
     *  showing - {@link #restoreAfterEditing()} must never hide that. */
    void showForEditing() {
        if (visible) return;
        editingForcedVisible = true;
        show(SkipGapPolicy.defaultLabel(SkipGapPolicy.GapKind.LEADING));
        pill.removeCallbacks(collapse);
    }

    /** Pairs with {@link #showForEditing()}: hides the chip again only if this controller was
     *  the one that forced it visible. */
    void restoreAfterEditing() {
        if (!editingForcedVisible) return;
        editingForcedVisible = false;
        hide();
    }

    /** Shows the chip with the given label, or updates the label of an already-shown chip
     *  without replaying the appear animation. */
    void show(String labelText) {
        String text = labelText == null ? "" : labelText;
        boolean appearing = !visible;
        if (!appearing && text.equals(shownLabel)) return;

        visible = true;
        shownLabel = text;
        if (appearing) {
            pill.animate().cancel();
            // Appears straight at whichever height is free now; only later changes move it.
            cancelLiftAnimation();
            liftTarget = shouldStack() ? -dp(STACK_OFFSET_DP) : 0f;
            pill.setTranslationY(liftTarget);
            pill.setVisibility(View.VISIBLE);
            pill.setAlpha(0f);
            pill.setScaleX(0.82f);
            pill.setScaleY(0.82f);
            pill.animate().alpha(0.92f).scaleX(1f).scaleY(1f).setDuration(220L).start();
        }
        applyStyle(text, appearing);
        // A new, wider label can run into the follow chip: judge it now, before layout draws it.
        if (!appearing) updateStack(true);
    }

    private void applyStyle(String text, boolean appearing) {
        cancelWidthAnimation();
        pill.removeCallbacks(collapse);
        label.setText(text);

        if ("Icon".equals(style)) {
            setCollapsed(true);
            return;
        }
        setCollapsed(false);
        if (!"Label".equals(style)) {
            // "Auto": only worth collapsing if it was ever expanded; re-showing the same label
            // mid-life restarts the timer rather than snapping it shut.
            pill.postDelayed(collapse, appearing ? COLLAPSE_DELAY_MS : COLLAPSE_DELAY_MS / 2);
        }
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        label.setVisibility(value ? View.GONE : View.VISIBLE);
        label.setAlpha(value ? 0f : 1f);
        ViewGroup.LayoutParams pillLp = pill.getLayoutParams();
        if (pillLp != null) {
            pillLp.width = value ? dp(ICON_SIZE_DP) : ViewGroup.LayoutParams.WRAP_CONTENT;
            pill.setLayoutParams(pillLp);
        }
        pill.setPadding(value ? 0 : dp(10), 0, value ? 0 : dp(10), 0);
        pill.setBackground(value ? NativeIconButtons.createRoundButtonBackground() : pillBackground());
    }

    /**
     * Animates the pill down to a plain circle. Width is animated explicitly rather than left to
     * a layout transition, because the pill lives in a FrameLayout that nothing else is
     * animating - a WRAP_CONTENT change there just snaps.
     */
    private void collapseToIcon() {
        if (collapsed || pill.getVisibility() != View.VISIBLE) return;
        int startWidth = pill.getWidth();
        int endWidth = dp(ICON_SIZE_DP);
        if (startWidth <= endWidth) {
            setCollapsed(true);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(startWidth, endWidth);
        animator.setDuration(COLLAPSE_DURATION_MS);
        animator.addUpdateListener(a -> {
            int width = (Integer) a.getAnimatedValue();
            ViewGroup.LayoutParams pillLp = pill.getLayoutParams();
            if (pillLp == null) return;
            pillLp.width = width;
            pill.setLayoutParams(pillLp);
            float progress = (float) (startWidth - width) / (float) Math.max(1, startWidth - endWidth);
            label.setAlpha(Math.max(0f, 1f - progress * 1.6f));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (pill.getVisibility() == View.VISIBLE) setCollapsed(true);
            }
        });
        widthAnimator = animator;
        animator.start();
    }

    void hide() {
        pill.removeCallbacks(collapse);
        if (!visible) return;
        visible = false;
        shownLabel = "";
        cancelWidthAnimation();
        pill.animate().cancel();
        // No geometry reset here: forcing setCollapsed(false) on an already-collapsed icon right
        // as it starts hiding made it visibly snap back to full label width for a frame before
        // shrinking away - an unwanted "expand, then vanish" instead of a clean fade from
        // whatever state it was already in. The next show() resets geometry itself via
        // applyStyle() before the entrance fade-in even starts (while alpha is still 0), so
        // nothing here needs to pre-empt that.
        // Shrinks slightly as it goes rather than only fading. A control that just dims out reads
        // as the screen dropping frames; one that pulls back reads as the control retiring itself.
        pill.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f)
                .setDuration(HIDE_DURATION_MS)
                .withEndAction(() -> {
                    if (!visible) {
                        pill.setVisibility(View.GONE);
                        pill.setScaleX(1f);
                        pill.setScaleY(1f);
                    }
                }).start();
    }

    private void cancelWidthAnimation() {
        if (widthAnimator != null) {
            widthAnimator.cancel();
            widthAnimator = null;
        }
    }

    private static GradientDrawable pillBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(ICON_SIZE_DP) / 2f);
        background.setColor(Color.argb(48, 255, 255, 255));
        background.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        return background;
    }
}
