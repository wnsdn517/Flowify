package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.settings.SettingsWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Direct-manipulation layout editor: tap/drag the *real* rendered artwork and focus-line directly
 * on the live lyrics screen to change {@link Settings#TRACK_INFO_POSITION},
 * {@link Settings#TRACK_INFO_ART_RADIUS}, artwork size, and {@link Settings#LYRICS_FOCUS_POSITION}
 * - plus a blur-intensity control with no on-screen element of its own.
 *
 * <p>Added as an overlay directly into {@code shellRoot} - the same {@link NativeSpicyShellViewImpl}
 * the artwork and lyrics already live in, not a separate window - so the selection outline and
 * handles share the exact same coordinate space as the real views with no cross-window alignment
 * math. Every write goes through {@link SettingsWriter}, the same path an ordinary settings row
 * uses, so the real screen underneath updates the moment a value changes.
 */
final class LyricsLayoutEditController {
    private static final int HANDLE_SIZE_DP = 22;
    /** Extra grab margin around the corner grip's drawn bracket. */
    private static final int HANDLE_TOUCH_PAD_DP = 10;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int ACCENT_COLOR = Color.rgb(30, 215, 96);

    private LyricsLayoutEditController() {
    }

    static void show(Activity activity, ViewGroup shellRoot, Supplier<View> artFrameSupplier,
            View focusArea, Runnable applyPreferences, Runnable onChromeReveal,
            Runnable enableDemoData, Runnable disableDemoData) {
        if (activity == null || shellRoot == null) return;
        new Session(activity, shellRoot, artFrameSupplier, focusArea, applyPreferences, onChromeReveal,
                enableDemoData, disableDemoData).start();
    }

    /** One editor invocation's mutable state - a plain instance instead of a pile of one-element
     *  arrays now that there's real state (selected element, snapshot, current drag) to carry. */
    private static final class Session {
        private static final Settings.Setting<?>[] TOUCHED_SETTINGS = {
                Settings.TRACK_INFO_POSITION, Settings.TRACK_INFO_ART_RADIUS,
                Settings.TRACK_INFO_ART_SIZE, Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP,
                Settings.TRACK_INFO_TEXT_ALIGN, Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                Settings.LYRICS_FOCUS_POSITION, Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT,
                Settings.LYRICS_BLUR_INTENSITY, Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                Settings.TRACK_INFO_TEXT_SIZE, Settings.TRACK_INFO_TEXT_SIZE_CUSTOM,
                Settings.BACKGROUND_STYLE, Settings.BEAT_REACTIVE_BACKGROUND,
                Settings.FORCE_DARK_BACKGROUND, Settings.EXTRA_DARK_BACKGROUND
        };

        /** BLUR and TRACK_INFO used to be separate top-level elements, reachable only through the
         *  chip row this file no longer has - BLUR folded into BACKGROUND (a piece of glass), and
         *  TRACK_INFO into ARTWORK (there's no on-screen element for "track info" distinct from
         *  the artwork it's paired with). */
        private enum Element { ARTWORK, FOCUS, TEXT, BACKGROUND }

        private final Activity activity;
        private final ViewGroup shellRoot;
        private final Supplier<View> artFrameSupplier;
        private final View focusArea;
        private final SettingsStore store;
        private final SettingsWriter writer;
        private final Runnable applyPreferences;
        private final Runnable onChromeReveal;
        private final Runnable enableDemoData;
        private final Runnable disableDemoData;
        private final Map<Settings.Setting<?>, Object> snapshot = new HashMap<>();

        private final FrameLayout overlay;
        private final FrameLayout artLayer;
        private final LinearLayout optionsCard;
        private TextView demoButton;

        private Element selected = Element.ARTWORK;
        private View artCapture;
        private View artHandle;
        private View focusHandle;
        private boolean demoActive;

        Session(Activity activity, ViewGroup shellRoot, Supplier<View> artFrameSupplier, View focusArea,
                Runnable applyPreferences, Runnable onChromeReveal,
                Runnable enableDemoData, Runnable disableDemoData) {
            this.activity = activity;
            this.shellRoot = shellRoot;
            this.artFrameSupplier = artFrameSupplier;
            this.focusArea = focusArea;
            this.applyPreferences = applyPreferences;
            this.onChromeReveal = onChromeReveal;
            this.enableDemoData = enableDemoData;
            this.disableDemoData = disableDemoData;
            this.store = new SettingsStore(activity);
            this.writer = new SettingsWriter(store);
            this.overlay = new FrameLayout(activity);
            this.artLayer = new FrameLayout(activity);
            this.optionsCard = new LinearLayout(activity);
        }

        /** Writes through the settings store, then forces the real shell to re-apply - bypassing
         *  the async SharedPreferences-listener round trip, so overlay geometry read afterward
         *  sees the real view already caught up instead of one frame behind. The apply itself is
         *  always posted, never run inline from the touch callback that called put(): some
         *  position changes reparent a whole view subtree (entering/leaving Header mode moves the
         *  art+title row between the chrome header and the track-info box), and doing that
         *  synchronously while this same call stack is still mutating this overlay's own view
         *  tree (removeAllViews()/addView() in refreshArtwork()) can reenter Android's
         *  measure/layout pass mid-traversal and crash with a NPE deep in FrameLayout.onMeasure.
         *  Anything that needs the post-apply real geometry goes in afterApply, chained after. */
        private <T> void put(Settings.Setting<T> setting, T value) {
            put(setting, value, null);
        }

        private <T> void put(Settings.Setting<T> setting, T value, Runnable afterApply) {
            writer.put(setting, value);
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                // applyPreferences() only *requests* a layout pass (requestLayout() never runs
                // synchronously) - reading the frame's geometry immediately after it, as
                // refreshArtwork() does, could still see pre-change values (wrong width/height/
                // position), landing the capture outline somewhere that doesn't overlap the real,
                // now-repositioned artwork - taps on the visible artwork then hit nothing. Wait
                // for an actual layout pass to land before running afterApply.
                if (afterApply != null) afterNextLayout(afterApply);
            });
        }

        /** Runs action after the next real layout pass on this overlay, or after a short timeout
         *  if the write didn't end up changing any layout (e.g. re-picking the same position) -
         *  onGlobalLayout would otherwise never fire and afterApply would never run at all. */
        private void afterNextLayout(Runnable action) {
            ViewTreeObserver observer = overlay.getViewTreeObserver();
            boolean[] done = {false};
            ViewTreeObserver.OnGlobalLayoutListener[] listenerRef =
                    new ViewTreeObserver.OnGlobalLayoutListener[1];
            listenerRef[0] = () -> {
                if (done[0]) return;
                done[0] = true;
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                action.run();
            };
            if (observer.isAlive()) observer.addOnGlobalLayoutListener(listenerRef[0]);
            overlay.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                action.run();
            }, 150L);
        }

        void start() {
            for (Settings.Setting<?> setting : TOUCHED_SETTINGS) snapshot.put(setting, store.get(setting));

            overlay.setBackgroundColor(0x4D000000);
            overlay.setClickable(true);
            // Any tap that reaches the overlay's own background - i.e. not claimed by the
            // artwork, the lyrics layer, the focus handle, the top bar, or the options card -
            // is by definition a tap on open background. No extra geometry bookkeeping needed.
            overlay.setOnClickListener(v -> selectElement(Element.BACKGROUND));

            artLayer.setClipChildren(false);
            overlay.addView(artLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Added before the focus handle so the handle's smaller touch band still wins where
            // the two overlap (later-added children win overlapping touches in a FrameLayout).
            buildTextTapLayer();
            buildFocusHandle();

            // The top bar (Cancel/Reset/Apply) sits on top of the interactive artwork/text layers
            // and the focus handle so its buttons are always grabbable.
            overlay.addView(topBar(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));

            optionsCard.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xF01C1C22);
            cardBg.setCornerRadius(dp(20));
            cardBg.setStroke(dp(1), 0x24FFFFFF);
            optionsCard.setBackground(cardBg);
            optionsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            optionsCard.setElevation(dp(12));
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            cardLp.leftMargin = dp(20);
            cardLp.rightMargin = dp(20);
            cardLp.bottomMargin = dp(28);
            overlay.addView(optionsCard, cardLp);

            refreshArtwork();
            selectElement(Element.ARTWORK);

            shellRoot.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        private void paintDemoButton() {
            if (demoButton == null) return;
            demoButton.setTextColor(demoActive ? ACCENT_COLOR : TEXT_COLOR);
        }

        private void close() {
            if (demoActive && disableDemoData != null) {
                demoActive = false;
                disableDemoData.run();
            }
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
            // The overlay's own scrim sat over the real chrome row the whole time it was open;
            // make sure it (and the settings cog on it) is actually visible again afterward
            // rather than relying on whatever auto-hide state it happened to be in already.
            if (onChromeReveal != null) onChromeReveal.run();
        }

        // -- top bar --------------------------------------------------------

        private LinearLayout topBar() {
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(sideSystemPadding(activity), topSystemPadding(activity),
                    sideSystemPadding(activity), dp(8));

            TextView cancel = textButton("Cancel");
            cancel.setOnClickListener(v -> {
                for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                    restoreTyped(writer, setting, snapshot.get(setting));
                }
                // Deferred, then close() (which removes this overlay's own view tree) only runs
                // once that's done - restoring TRACK_INFO_POSITION can reparent a view subtree,
                // and doing that synchronously in the same callback as our own removeView() risks
                // the same reentrant-measure crash put() guards against elsewhere in this file.
                overlay.post(() -> {
                    if (applyPreferences != null) applyPreferences.run();
                    close();
                });
            });
            bar.addView(cancel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = text("Layout Editor", 16, Color.WHITE, true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleLp.gravity = Gravity.CENTER;
            bar.addView(title, titleLp);

            TextView reset = textButton("Reset");
            reset.setOnClickListener(v -> {
                for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                    restoreTyped(writer, setting, setting.defaultValue);
                }
                overlay.post(() -> {
                    if (applyPreferences != null) applyPreferences.run();
                    afterNextLayout(() -> {
                        refreshArtwork();
                        repaintFocusHandle();
                        selectElement(selected);
                    });
                });
            });
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            resetLp.leftMargin = dp(12);
            bar.addView(reset, resetLp);

            // Preview toggle: swaps in a synthetic track/artwork/lyrics document so the editor
            // has something to show even with nothing (useful) actually playing. Only offered
            // when the shell wired the callbacks up - never null in production, but Runnable
            // params default to skippable rather than assumed.
            if (enableDemoData != null && disableDemoData != null) {
                demoButton = textButton("Demo");
                demoButton.setOnClickListener(v -> {
                    demoActive = !demoActive;
                    paintDemoButton();
                    (demoActive ? enableDemoData : disableDemoData).run();
                });
                paintDemoButton();
                LinearLayout.LayoutParams demoLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                demoLp.leftMargin = dp(12);
                bar.addView(demoButton, demoLp);
            }

            TextView apply = textButton("Apply");
            apply.setTextColor(ACCENT_COLOR);
            apply.setOnClickListener(v -> close());
            LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            applyLp.leftMargin = dp(12);
            bar.addView(apply, applyLp);
            return bar;
        }

        // -- artwork element --------------------------------------------------

        /** Rebuilds the capture outline + resize handle anchored to whichever art frame is
         *  currently visible - called at startup and again after anything that could change
         *  *which* real view is showing (a position change moves the artwork to a different
         *  frame object entirely: top/bottom/side are three separate views). */
        private void refreshArtwork() {
            artLayer.removeAllViews();
            View frame = artFrameSupplier == null ? null : artFrameSupplier.get();
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                artCapture = null;
                artHandle = null;
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            int radiusDp = safeGet(Settings.TRACK_INFO_ART_RADIUS);

            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(radiusDp));
            capture.setBackground(outline);
            FrameLayout.LayoutParams captureLp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            captureLp.leftMargin = pos[0];
            captureLp.topMargin = pos[1];
            artLayer.addView(capture, captureLp);
            artCapture = capture;
            installArtworkDrag(capture, captureLp);

            // An Apple-style curved corner grip - hugging the actual corner point rather than a
            // dot floating centered on top of it - covers a generous touch box for grabbability
            // while only painting the curved bracket itself.
            // Touch box extends a bit past the drawn bracket's vertex for grabbability
            // (HANDLE_SIZE_DP is the bracket's own arm length) - the vertex itself sits at the
            // real frame corner, with both arms drawn extending down-right from it.
            int touchPad = dp(HANDLE_TOUCH_PAD_DP);
            View handle = new CornerGripView(activity, touchPad);
            int handleSize = dp(HANDLE_SIZE_DP) + touchPad;
            FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                    handleSize, handleSize, Gravity.TOP | Gravity.START);
            handleLp.leftMargin = pos[0] + frame.getWidth() - touchPad;
            handleLp.topMargin = pos[1] + frame.getHeight() - touchPad;
            artLayer.addView(handle, handleLp);
            artHandle = handle;
            installResizeDrag(handle, handleLp, captureLp);
        }

        /** Vertical drag on the artwork body itself live-translates it, snapping to Top or
         *  Bottom (whichever the release direction points at) once the drag clears touch slop -
         *  a real drag producing a preset result, not a tap-only chip. A drag that never clears
         *  slop is treated as a plain tap (select, don't move). */
        private void installArtworkDrag(View capture, FrameLayout.LayoutParams captureLp) {
            int slopPx = dp(8);
            float[] startY = new float[1];
            boolean[] dragging = new boolean[1];
            capture.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY[0] = event.getRawY();
                        dragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startY[0];
                        if (!dragging[0] && Math.abs(dy) > slopPx) dragging[0] = true;
                        if (dragging[0]) capture.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = event.getRawY() - startY[0];
                        capture.setTranslationY(0f);
                        if (dragging[0]) {
                            put(Settings.TRACK_INFO_POSITION, dy < 0 ? "Top" : "Bottom", () -> {
                                refreshArtwork();
                                selectElement(Element.ARTWORK);
                            });
                        } else {
                            selectElement(Element.ARTWORK);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }

        /** Drag on the bottom-right corner handle changes artwork size continuously - averaging
         *  both axes reads naturally for a corner handle (drag away from center to grow, toward
         *  it to shrink) regardless of whether the drag is mostly horizontal, vertical, or
         *  diagonal. Resizes the capture/outline immediately for responsive visual feedback (the
         *  real artwork follows a frame later via the normal preference-change pipeline once the
         *  write lands). */
        private void installResizeDrag(View handle, FrameLayout.LayoutParams handleLp,
                FrameLayout.LayoutParams captureLp) {
            int min = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.minValue;
            int max = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.maxValue;
            float density = activity.getResources().getDisplayMetrics().density;
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            int[] startSizePx = new int[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX[0] = event.getRawX();
                        startRawY[0] = event.getRawY();
                        startSizePx[0] = captureLp.width;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dxDp = (event.getRawX() - startRawX[0]) / density;
                        float dyDp = (event.getRawY() - startRawY[0]) / density;
                        int startSizeDp = Math.round(startSizePx[0] / density);
                        int newSizeDp = clamp(Math.round(startSizeDp + (dxDp + dyDp) / 2f), min, max);
                        int newSizePx = dp(newSizeDp);
                        captureLp.width = newSizePx;
                        captureLp.height = newSizePx;
                        artCapture.setLayoutParams(captureLp);
                        int touchPad = dp(HANDLE_TOUCH_PAD_DP);
                        handleLp.leftMargin = captureLp.leftMargin + newSizePx - touchPad;
                        handleLp.topMargin = captureLp.topMargin + newSizePx - touchPad;
                        handle.setLayoutParams(handleLp);
                        // Plain writes, not put(): the visual feedback here is entirely local
                        // (captureLp/handleLp already updated above), nothing reads the real
                        // frame back mid-drag, so there's no need to force an early re-apply -
                        // the real artwork frame catches up on its own via the normal
                        // preference-change pipeline, same as any ordinary settings write.
                        writer.put(Settings.TRACK_INFO_ART_SIZE, "Custom");
                        writer.put(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP, newSizeDp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        selectElement(Element.ARTWORK);
                        return true;
                    default:
                        return false;
                }
            });
        }

        // -- lyrics-text element -----------------------------------------------

        /** Transparent layer sized to focusArea's real on-screen rect. A plain tap selects TEXT;
         *  a drag past touch slop instead forwards the gesture to the real {@code focusArea} (the
         *  frame wrapping the live lyrics ScrollView) so the actual lyrics keep scrolling while
         *  the editor is open - the overlay's own clickable background would otherwise swallow
         *  every touch over the lyrics before the real ScrollView ever saw them. The forward
         *  starts only once a drag is confirmed (not from the initial down) so a plain tap can
         *  never leak through as a real tap-seek on the row underneath.
         *
         *  <p>Sized to focusArea rather than the whole overlay because the artwork readout floats
         *  *over* the same lyrics region in Top/Bottom/Header placements - a full-bleed layer
         *  would sit above (and swallow touches meant for) the artwork capture/handle in
         *  {@code artLayer}, which is added earlier and would otherwise lose that overlap by
         *  z-order alone. A touch landing on the live artwork rect is declined outright (returns
         *  false on ACTION_DOWN) so it falls through to whatever real view is underneath instead. */
        private void buildTextTapLayer() {
            View layer = new View(activity);
            int[] pos = relativePosition(focusArea, shellRoot);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), Math.max(0, focusArea.getHeight()),
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            overlay.addView(layer, lp);

            int slopPx = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            boolean[] dragging = new boolean[1];
            MotionEvent[] pendingDown = new MotionEvent[1];
            layer.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (withinArtwork(event.getRawX(), event.getRawY())) return false;
                        startRawX[0] = event.getRawX();
                        startRawY[0] = event.getRawY();
                        dragging[0] = false;
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = MotionEvent.obtain(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - startRawX[0];
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.hypot(dx, dy) > slopPx) {
                            dragging[0] = true;
                            if (pendingDown[0] != null) forwardToFocusArea(pendingDown[0]);
                            recycleQuietly(pendingDown[0]);
                            pendingDown[0] = null;
                        }
                        if (dragging[0]) forwardToFocusArea(event);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging[0]) {
                            forwardToFocusArea(event);
                        } else {
                            selectElement(Element.TEXT);
                        }
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) forwardToFocusArea(event);
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        return true;
                    default:
                        return false;
                }
            });
        }

        /** Re-targets a copy of {@code event} into focusArea's local coordinate space and
         *  dispatches it directly - focusArea and this overlay are siblings in shellRoot, not
         *  parent/child, so the real ScrollView inside it never receives these touches otherwise. */
        private void forwardToFocusArea(MotionEvent event) {
            int[] pos = relativePosition(focusArea, shellRoot);
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(-pos[0], -pos[1]);
            try {
                focusArea.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }

        private static void recycleQuietly(MotionEvent event) {
            if (event != null) event.recycle();
        }

        /** True when the point hits the live artwork capture outline or its resize handle -
         *  queried against their real current geometry (kept in sync by refreshArtwork()) rather
         *  than a cached rect, so it never goes stale after a position/size change. */
        private boolean withinArtwork(float rawX, float rawY) {
            return hitsView(artCapture, rawX, rawY) || hitsView(artHandle, rawX, rawY);
        }

        private static boolean hitsView(View view, float rawX, float rawY) {
            if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX < loc[0] + view.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + view.getHeight();
        }

        // -- focus-point element ----------------------------------------------

        // The visible line is thin (2dp), but its touch target is a full 44dp-tall band centered
        // on it - a 2dp-tall touch target is nearly impossible to grab reliably on a real screen.
        private static final int FOCUS_TOUCH_HEIGHT_DP = 44;

        private void buildFocusHandle() {
            View line = new FocusLineView(activity);
            int touchHeight = dp(FOCUS_TOUCH_HEIGHT_DP);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, touchHeight, Gravity.TOP);
            overlay.addView(line, lp);
            focusHandle = line;
            repaintFocusHandle();

            float[] startRawY = new float[1];
            int[] startTopMargin = new int[1];
            line.setOnTouchListener((v, event) -> {
                int[] pos = relativePosition(focusArea, shellRoot);
                int areaHeight = Math.max(1, focusArea.getHeight());
                int half = touchHeight / 2;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTopMargin[0] = lp.topMargin;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int newTop = clamp(startTopMargin[0]
                                + Math.round(event.getRawY() - startRawY[0]),
                                pos[1] - half, pos[1] + areaHeight - half);
                        lp.topMargin = newTop;
                        line.setLayoutParams(lp);
                        // Percent is measured from the visual line's center, not the touch band's
                        // top edge, so it matches what repaintFocusHandle() draws back later.
                        int percent = clamp(Math.round(100f * (newTop + half - pos[1]) / areaHeight), 0, 100);
                        // Plain writes: the line's own position is driven directly by the touch
                        // delta above, not read back from real state, so no forced re-apply needed.
                        writer.put(Settings.LYRICS_FOCUS_POSITION, "Custom");
                        writer.put(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT, percent);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        selectElement(Element.FOCUS);
                        return true;
                    default:
                        return false;
                }
            });
        }

        private void repaintFocusHandle() {
            if (focusHandle == null || focusArea == null) return;
            int[] pos = relativePosition(focusArea, shellRoot);
            int areaHeight = Math.max(1, focusArea.getHeight());
            float fraction = currentFocusFraction();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusHandle.getLayoutParams();
            lp.topMargin = pos[1] + Math.round(areaHeight * fraction) - lp.height / 2;
            focusHandle.setLayoutParams(lp);
        }

        private float currentFocusFraction() {
            String pos = store.get(Settings.LYRICS_FOCUS_POSITION);
            if ("Top".equals(pos)) return 0.28f;
            if ("Bottom".equals(pos)) return 0.72f;
            if ("Custom".equals(pos)) {
                return clamp(safeGet(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT), 0, 100) / 100f;
            }
            return 0.5f;
        }

        // -- options card -------------------------------------------------------

        /** Which element is selected is now purely tap-driven on the real on-screen regions
         *  (artwork, focus line, lyrics text, open background) - no interactive chip strip here
         *  any more, just a label naming the current selection for orientation. */
        private void selectElement(Element element) {
            selected = element;
            optionsCard.removeAllViews();

            optionsCard.addView(text(labelFor(element), 14, TEXT_COLOR, true), matchWrap(10));
            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            switch (element) {
                case ARTWORK:
                    buildArtworkOptions();
                    break;
                case FOCUS:
                    buildFocusOptions();
                    break;
                case TEXT:
                    buildTextOptions();
                    break;
                case BACKGROUND:
                    buildBackgroundOptions();
                    break;
            }
        }

        private static String labelFor(Element element) {
            switch (element) {
                case ARTWORK: return "Artwork";
                case FOCUS: return "Focus point";
                case TEXT: return "Lyrics text";
                default: return "Background";
            }
        }

        private void buildArtworkOptions() {
            optionsCard.addView(text("Position", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(chipRow(Settings.TRACK_INFO_POSITION,
                    new String[]{"Off", "Top", "Bottom", "Header"},
                    new String[]{"Off", "Top", "Bottom", "Header"},
                    () -> {
                        refreshArtwork();
                        selectElement(Element.ARTWORK);
                    }), matchWrap(12));

            optionsCard.addView(text("Drag the handle at its corner to resize; drag the artwork "
                    + "itself up/down to reposition.", 12, 0x80FFFFFF, false), matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Corner radius", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(dragRow(
                    Settings.TRACK_INFO_ART_RADIUS.minValue, Settings.TRACK_INFO_ART_RADIUS.maxValue,
                    safeGet(Settings.TRACK_INFO_ART_RADIUS), "dp",
                    value -> {
                        // Plain write: refreshArtwork()'s outline radius comes from our own
                        // store read, not the real view, and radius changes never reparent
                        // anything - safe to read straight back synchronously.
                        writer.put(Settings.TRACK_INFO_ART_RADIUS, value);
                        refreshArtwork();
                    }),
                    matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Text alignment", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(chipRow(Settings.TRACK_INFO_TEXT_ALIGN,
                    new String[]{"Top", "Center", "Bottom"},
                    new String[]{"Top", "Center", "Bottom"}, null), matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Title/artist text size", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(toggleRow(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                    "Adaptive (scales with artwork size)",
                    () -> selectElement(Element.ARTWORK)), matchWrap(8));
            if (!Boolean.TRUE.equals(store.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE))) {
                optionsCard.addView(chipRow(Settings.TRACK_INFO_TEXT_SIZE,
                        new String[]{"Small", "Normal", "Large", "XLarge"},
                        new String[]{"Small", "Normal", "Large", "XL"}, null), matchWrap(12));
                optionsCard.addView(text("Custom size", 13, 0x99FFFFFF, false), matchWrap(4));
                optionsCard.addView(dragRow(
                        Settings.TRACK_INFO_TEXT_SIZE_CUSTOM.minValue,
                        Settings.TRACK_INFO_TEXT_SIZE_CUSTOM.maxValue,
                        safeGet(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM), "%",
                        value -> {
                            writer.put(Settings.TRACK_INFO_TEXT_SIZE, "Custom");
                            writer.put(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, value);
                        }),
                        matchWrap(0));
            }
        }

        private void buildFocusOptions() {
            optionsCard.addView(text("Position", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(chipRow(Settings.LYRICS_FOCUS_POSITION,
                    new String[]{"Auto", "Top", "Center", "Bottom"},
                    new String[]{"Auto", "Top", "Center", "Bottom"},
                    this::repaintFocusHandle), matchWrap(12));
            optionsCard.addView(text("Or drag the line across the lyrics directly.", 12,
                    0x80FFFFFF, false), matchWrap(0));
        }

        private void buildTextOptions() {
            optionsCard.addView(text("Size", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(chipRow(Settings.LYRICS_TEXT_SIZE,
                    new String[]{"small", "normal", "large", "xlarge"},
                    new String[]{"Small", "Normal", "Large", "XL"}, null), matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Custom size", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(dragRow(
                    Settings.LYRICS_TEXT_SIZE_CUSTOM.minValue, Settings.LYRICS_TEXT_SIZE_CUSTOM.maxValue,
                    safeGet(Settings.LYRICS_TEXT_SIZE_CUSTOM), "%",
                    value -> {
                        writer.put(Settings.LYRICS_TEXT_SIZE, "custom");
                        writer.put(Settings.LYRICS_TEXT_SIZE_CUSTOM, value);
                    }),
                    matchWrap(0));
        }

        private void buildBackgroundOptions() {
            optionsCard.addView(text("Style", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(chipRow(Settings.BACKGROUND_STYLE,
                    new String[]{"Gradient", "Static texture", "Animated texture"},
                    new String[]{"Gradient", "Static", "Animated"},
                    () -> selectElement(Element.BACKGROUND)), matchWrap(12));

            if ("Animated texture".equals(store.get(Settings.BACKGROUND_STYLE))) {
                optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                optionsCard.addView(toggleRow(Settings.BEAT_REACTIVE_BACKGROUND,
                        "Beat-reactive", null), matchWrap(12));
            }

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(toggleRow(Settings.FORCE_DARK_BACKGROUND,
                    "Force dark background", null), matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Extra darken", 13, 0x99FFFFFF, false), matchWrap(4));
            optionsCard.addView(dragRow(
                    Settings.EXTRA_DARK_BACKGROUND.minValue, Settings.EXTRA_DARK_BACKGROUND.maxValue,
                    safeGet(Settings.EXTRA_DARK_BACKGROUND), "%",
                    value -> writer.put(Settings.EXTRA_DARK_BACKGROUND, value)),
                    matchWrap(12));

            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            optionsCard.addView(text("Blur intensity - how much stronger the blur gets on lines "
                    + "further from the current one", 12, 0x99FFFFFF, false), matchWrap(8));
            optionsCard.addView(dragRow(
                    Settings.LYRICS_BLUR_INTENSITY.minValue, Settings.LYRICS_BLUR_INTENSITY.maxValue,
                    safeGet(Settings.LYRICS_BLUR_INTENSITY), "%",
                    value -> writer.put(Settings.LYRICS_BLUR_INTENSITY, value)),
                    matchWrap(0));
        }

        // -- shared row builders ------------------------------------------------

        private LinearLayout chipRow(Settings.Setting<String> setting, String[] values,
                String[] labels, Runnable onChanged) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView[] chips = new TextView[values.length];
            String current = store.get(setting);
            for (int i = 0; i < values.length; i++) {
                TextView chip = chip(labels[i]);
                chips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                row.addView(chip, lp);
            }
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                chips[i].setOnClickListener(v -> {
                    for (int j = 0; j < values.length; j++) paintChip(chips[j], values[j].equals(value));
                    // Deferred: TRACK_INFO_POSITION can reparent a whole view subtree
                    // (entering/leaving Header mode) - see put()'s javadoc for why that can't
                    // happen synchronously from inside this click callback.
                    put(setting, value, onChanged);
                });
                paintChip(chips[i], value.equals(current));
            }
            return row;
        }

        /** Label + a two-state On/Off chip, for boolean settings - the same {@code chip()}/
         *  {@code paintChip()} widgets {@code chipRow()} uses, just one toggling chip instead of
         *  a row of mutually-exclusive ones. */
        private LinearLayout toggleRow(Settings.Setting<Boolean> setting, String label, Runnable onChanged) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView labelView = text(label, 13, 0xCCFFFFFF, false);
            row.addView(labelView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean current = Boolean.TRUE.equals(store.get(setting));
            TextView toggle = chip(current ? "On" : "Off");
            paintChip(toggle, current);
            toggle.setOnClickListener(v -> {
                boolean next = !Boolean.TRUE.equals(store.get(setting));
                toggle.setText(next ? "On" : "Off");
                paintChip(toggle, next);
                put(setting, next, onChanged);
            });
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(toggle, toggleLp);
            return row;
        }

        private LinearLayout dragRow(int min, int max, int initial, String unit, IntConsumer onChange) {
            int[] value = {initial};
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(initial + unit, 13, Color.WHITE, false);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(52), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);
            View thumb = new View(activity);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setShape(GradientDrawable.OVAL);
            thumbBg.setColor(ACCENT_COLOR);
            thumb.setBackground(thumbBg);
            int thumbSize = dp(HANDLE_SIZE_DP);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                    thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.START);
            track.addView(thumb, thumbLp);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
            trackLp.leftMargin = dp(10);
            row.addView(track, trackLp);

            Runnable[] paint = new Runnable[1];
            paint[0] = () -> {
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) {
                    track.post(() -> paint[0].run());
                    return;
                }
                float fraction = (value[0] - min) / (float) Math.max(1, max - min);
                thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
                thumb.setLayoutParams(thumbLp);
            };
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
            track.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(newValue + unit);
                    onChange.accept(newValue);
                }
                paint[0].run();
                return true;
            });
            return row;
        }

        // -- small view helpers -------------------------------------------------

        private TextView text(String value, int sp, int color, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            return view;
        }

        private TextView textButton(String label) {
            TextView view = text(label, 14, TEXT_COLOR, false);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            view.setClickable(true);
            view.setFocusable(true);
            return view;
        }

        private TextView chip(String label) {
            TextView chip = text(label, 13, TEXT_COLOR, false);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setPadding(dp(4), dp(10), dp(4), dp(10));
            return chip;
        }

        private void paintChip(TextView chip, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(selected ? 0x331ED760 : 0x14FFFFFF);
            chip.setBackground(bg);
            chip.setTextColor(selected ? ACCENT_COLOR : TEXT_COLOR);
        }

        private View divider() {
            View line = new View(activity);
            line.setBackgroundColor(0x1FFFFFFF);
            return line;
        }

        private LinearLayout.LayoutParams matchWrap(int bottomMarginDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(bottomMarginDp);
            return lp;
        }

        private int safeGet(Settings.IntegerSetting setting) {
            Integer value = store.get(setting);
            return value == null ? setting.defaultValue : value;
        }
    }

    /** A curved bracket hugging the frame's actual corner point (vertex at this view's own
     *  top-left, which {@code refreshArtwork()} positions exactly on the real corner) - an
     *  Apple-style resize grip, not a dot floating centered on top of the corner. Rounded stroke
     *  joins give the two arms a single continuous curve at the vertex instead of a sharp right
     *  angle. The view's full bounds stay the touch target; only the bracket itself is painted. */
    private static final class CornerGripView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int vertexOffset;

        CornerGripView(Activity activity, int vertexOffset) {
            super(activity);
            this.vertexOffset = vertexOffset;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(ACCENT_COLOR);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int arm = dp(HANDLE_SIZE_DP);
            float v = vertexOffset;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(v, v + arm);
            path.lineTo(v, v);
            path.lineTo(v + arm, v);
            canvas.drawPath(path, paint);
        }
    }

    /** Focus-point line visual: four small L-shaped corner brackets (viewfinder-style, echoing
     *  {@link CornerGripView}'s curved-corner language) at the left/right ends of a thin dashed
     *  line, instead of a single flat bar spanning the full width. Purely decorative - the touch
     *  band and drag logic in {@code buildFocusHandle()} are unchanged. */
    private static final class FocusLineView extends View {
        private static final int CORNER_ARM_DP = 8;
        private static final int BRACKET_HALF_HEIGHT_DP = 10;
        private static final int INSET_DP = 20;
        private final android.graphics.Paint bracketPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dashPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        FocusLineView(Activity activity) {
            super(activity);
            bracketPaint.setStyle(android.graphics.Paint.Style.STROKE);
            bracketPaint.setStrokeWidth(dp(3));
            bracketPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            bracketPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            bracketPaint.setColor(ACCENT_COLOR);
            dashPaint.setStyle(android.graphics.Paint.Style.STROKE);
            dashPaint.setStrokeWidth(dp(2));
            dashPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            dashPaint.setColor(0x99FFFFFF);
            dashPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(4), dp(4)}, 0));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getHeight() / 2f;
            float arm = dp(CORNER_ARM_DP);
            float halfBracket = dp(BRACKET_HALF_HEIGHT_DP);
            float left = dp(INSET_DP);
            float right = getWidth() - dp(INSET_DP);

            android.graphics.Path corners = new android.graphics.Path();
            // Top-left / bottom-left.
            corners.moveTo(left, centerY - halfBracket + arm);
            corners.lineTo(left, centerY - halfBracket);
            corners.lineTo(left + arm, centerY - halfBracket);
            corners.moveTo(left, centerY + halfBracket - arm);
            corners.lineTo(left, centerY + halfBracket);
            corners.lineTo(left + arm, centerY + halfBracket);
            // Top-right / bottom-right.
            corners.moveTo(right, centerY - halfBracket + arm);
            corners.lineTo(right, centerY - halfBracket);
            corners.lineTo(right - arm, centerY - halfBracket);
            corners.moveTo(right, centerY + halfBracket - arm);
            corners.lineTo(right, centerY + halfBracket);
            corners.lineTo(right - arm, centerY + halfBracket);
            canvas.drawPath(corners, bracketPaint);

            float dashStart = left + arm + dp(6);
            float dashEnd = right - arm - dp(6);
            if (dashEnd > dashStart) canvas.drawLine(dashStart, centerY, dashEnd, centerY, dashPaint);
        }
    }

    private static int[] relativePosition(View child, View ancestor) {
        int[] childLoc = new int[2];
        int[] ancestorLoc = new int[2];
        child.getLocationOnScreen(childLoc);
        ancestor.getLocationOnScreen(ancestorLoc);
        return new int[]{childLoc[0] - ancestorLoc[0], childLoc[1] - ancestorLoc[1]};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @SuppressWarnings("unchecked")
    private static <T> void restoreTyped(SettingsWriter writer, Settings.Setting<T> setting, Object value) {
        writer.put(setting, (T) value);
    }
}
