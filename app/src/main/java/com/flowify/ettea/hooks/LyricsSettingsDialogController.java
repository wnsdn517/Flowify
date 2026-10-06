package com.flowify.ettea.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import com.flowify.ettea.settings.SettingsPanel;
import com.flowify.ettea.SettingsStore;
import com.flowify.ettea.ui.VsyncFrameScheduler;
import com.flowify.ettea.lyrics.LyricsAmbientController;
import com.flowify.ettea.ui.Motion;
import com.flowify.ettea.ui.PanelSurface;

import com.flowify.ettea.xposed.XpLog;

/** Owns the in-Spotify settings modal lifecycle and render-loop pause/resume. */
final class LyricsSettingsDialogController {
    private final Activity activity;
    private final VsyncFrameScheduler frameScheduler;
    private final LyricsAmbientController ambientController;
    private final LyricsHost host;
    private final Runnable onClosed;
    private final java.util.function.IntConsumer onOpenLayoutEditor;
    private final Runnable onResyncTiming;
    private Runnable onTryDoubleTap;
    private Runnable onTryDoubleTapEnd;
    private Runnable onTryDoubleTapPreview;
    private final String logTag;
    private Dialog currentDialog;
    private PanelSurface currentSurface;

    LyricsSettingsDialogController(
            Activity activity,
            VsyncFrameScheduler frameScheduler,
            LyricsAmbientController ambientController,
            LyricsHost host,
            Runnable onClosed,
            java.util.function.IntConsumer onOpenLayoutEditor,
            Runnable onResyncTiming,
            String logTag
    ) {
        this.activity = activity;
        this.frameScheduler = frameScheduler;
        this.ambientController = ambientController;
        this.host = host;
        this.onClosed = onClosed;
        this.onOpenLayoutEditor = onOpenLayoutEditor;
        this.onResyncTiming = onResyncTiming;
        this.logTag = logTag;
    }

    // Sticky across opens: half mode anchors the panel to the top so lyrics preview underneath.
    private static boolean halfMode;

    /** Run after the panel closed from its "Try it" action under the double-tap effect. */
    void setOnTryDoubleTap(Runnable onTry, Runnable onEnd, Runnable onPreview) {
        onTryDoubleTap = onTry;
        onTryDoubleTapEnd = onEnd;
        onTryDoubleTapPreview = onPreview;
    }

    boolean show() {
        if (isShowing()) return true;
        // Every open starts expanded: the small size is for trying something on the lyrics
        // (see "Try it"), and reopening small after that read as stuck.
        halfMode = false;
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            PanelSurface.configureWindow(dialog.getWindow());

            final PanelSurface[] surfaceRef = new PanelSurface[1];
            final View[] panelRef = new View[1];
            // The dialog is its own window, layered above the activity's by the platform
            // regardless of view z-order inside either one - the layout editor's overlay lives
            // in the activity's hierarchy (see LyricsLayoutEditController), so opening it while
            // this dialog's window is still up leaves it added but invisible underneath. Record
            // the request instead of acting on it immediately, and run it from the dismiss
            // listener below, once this window is actually gone.
            int[] openLayoutEditorPending = {0};
            SettingsPanel panel = new SettingsPanel(activity, new SettingsStore(activity),
                    () -> halfMode, () -> {
                        PanelSurface surface = surfaceRef[0];
                        View sheet = panelRef[0];
                        if (surface != null && sheet != null) setCollapsed(dialog, surface, sheet, !halfMode);
                    }, () -> {
                        if (surfaceRef[0] != null) {
                            surfaceRef[0].exit(null);
                        }
                    },
                    mode -> openLayoutEditorPending[0] = mode,
                    host::clearLyricsCache, onResyncTiming);
            panel.setLyricsHost(host);
            boolean[] trialRunning = {false};
            final android.widget.LinearLayout[] sheetRef = new android.widget.LinearLayout[1];
            if (onTryDoubleTapPreview != null) panel.setOnDoubleTapEffectChosen(onTryDoubleTapPreview);
            if (onTryDoubleTap != null) {
                // "Try it": the sheet shrinks below the lyrics and the trial starts - double-tap
                // the lyrics above, change the effect right here, until the sheet closes.
                panel.setOnTryDoubleTapEffect(() -> {
                    if (surfaceRef[0] != null && sheetRef[0] != null && !halfMode) {
                        setCollapsed(dialog, surfaceRef[0], sheetRef[0], true);
                    }
                    if (!trialRunning[0]) {
                        trialRunning[0] = true;
                        onTryDoubleTap.run();
                    }
                });
            }
            final View panelView = panel.build();
            panelView.setBackground(null);
            final View[] handleRef = new View[1];
            final android.widget.LinearLayout sheet = buildSheet(panelView, handleRef);
            panelRef[0] = sheet;
            sheetRef[0] = sheet;
            final View panelViewForSize = sheet;

            PanelSurface surface = new PanelSurface(activity, dialog, sheet,
                    halfMode ? 0f : EXPANDED_DIM);
            surface.setSheet(true);
            surfaceRef[0] = surface;
            fitSheetToInsets(surface, sheet);
            SheetDrag drag = new SheetDrag(dialog, surface, sheet);
            drag.attachHandle(handleRef[0]);
            sheetDrag = drag;

            applyCardSize(sheet, halfMode);

            // Back and outside-scrim taps route through the unified animated exit.
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    // On a section's page, back returns to the list of sections first.
                    // Android 13+ already ran the back-invoked callback registered below for
                    // this same press; handling it here too would step back twice.
                    if (android.os.Build.VERSION.SDK_INT >= 33) return true;
                    if (panel.handleBack()) return true;
                    if (surfaceRef[0] != null) {
                        surfaceRef[0].exit(null);
                    }
                    return true;
                }
                return false;
            });

            dialog.setContentView(surface);

            // Re-fit when the window changes shape while open (rotation, fold/unfold, split
            // screen); the dialog outlives those changes, and a size fitted to the old shape
            // is wrong in the new one.
            surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if ((r - l) != (or - ol) || (b - t) != (ob - ot)) applyCardSize(panelViewForSize, halfMode);
            });

            dialog.setOnDismissListener(d -> {
                if (currentDialog == dialog) {
                    currentDialog = null;
                    currentSurface = null;
                }
                frameScheduler.start();
                // Source toggles and order are saved inside the panel; the session re-seats the
                // current track from stored candidates instead of waiting for the next track.
                host.reconcileLyricsSources();
                onClosed.run();
                if (trialRunning[0]) {
                    trialRunning[0] = false;
                    if (onTryDoubleTapEnd != null) onTryDoubleTapEnd.run();
                }
                if (openLayoutEditorPending[0] != 0) {
                    int mode = openLayoutEditorPending[0];
                    openLayoutEditorPending[0] = 0;
                    if (onOpenLayoutEditor != null) onOpenLayoutEditor.accept(mode);
                }
            });

            dialog.show();
            // Android 13+ delivers Back through the window's back-invoked dispatcher, not the key
            // listener above; registered after show() so it sits in front of the dialog's own.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                                if (panel.handleBack()) return;
                                if (surfaceRef[0] != null) surfaceRef[0].exit(null);
                            });
                } catch (Throwable ignored) {
                }
            }
            currentDialog = dialog;
            currentSurface = surface;
            PanelSurface.configureWindow(dialog.getWindow());
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            if (halfMode) applyWindowState(dialog, true);
            surface.enter(halfMode ? 0f : EXPANDED_DIM);
            return true;
        } catch (Throwable t) {
            XpLog.log(logTag + " settings dialog failed: " + t);
            if (currentDialog != null) currentDialog.dismiss();
            return false;
        }
    }

    boolean isShowing() {
        return currentDialog != null && currentDialog.isShowing();
    }

    boolean close() {
        if (!isShowing() || currentSurface == null) return false;
        currentSurface.exit(null);
        return true;
    }

    /** A settings list reads best at phone width; wider just makes every row a long empty bar. */
    private static final int PANEL_MAX_WIDTH_DP = 640;
    /** No dim: the sheet is solid enough on its own, and darkening the lyrics read as heavy. */
    private static final float EXPANDED_DIM = 0f;
    /** The collapsed sheet's share of the screen: the lyrics above stay visible and touchable. */
    private static final float COLLAPSED_SHARE = 0.46f;
    /** Expanded, the sheet is glass over a blurred screen; collapsed nothing behind it is
     *  blurred (the lyrics above stay sharp), so it turns nearly solid instead of showing them
     *  through itself. */
    private static final int EXPANDED_ALPHA = 0xEB;
    private static final int COLLAPSED_ALPHA = 0xF7;

    /** Glass only where the system really blurs what is behind it (cross-window blur can be off:
     *  battery saver, the developer option, some vendors); otherwise the sheet is near-solid,
     *  or the lyrics read straight through it. */
    private int expandedAlpha() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.view.WindowManager wm = activity.getSystemService(android.view.WindowManager.class);
                if (glassExpanded() && wm != null && wm.isCrossWindowBlurEnabled()) return EXPANDED_ALPHA;
            }
        } catch (Throwable ignored) {
        }
        return COLLAPSED_ALPHA;
    }

    /** Blur off with the dim, so the sheet is as solid expanded as collapsed. */
    private static boolean glassExpanded() {
        return false;
    }

    /**
     * The settings live in a bottom sheet over the lyrics screen. Expanded, it takes most of the
     * screen over a dimmed, blurred backdrop; collapsed, it sits in the lower part and its window
     * shrinks to the sheet itself, so the lyrics above are seen and can be touched - tapped, seeked,
     * double-tapped - while a setting is being tried. Dragging the handle moves between the two,
     * and dragging the collapsed sheet down closes it.
     */
    private android.widget.LinearLayout buildSheet(View panelView, View[] handleOut) {
        float density = activity.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout sheet = new android.widget.LinearLayout(activity) {
            private float downX;
            private float downY;

            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
                SheetDrag drag = sheetDrag;
                if (drag == null) return false;
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - downY;
                        float dx = Math.abs(event.getRawX() - downX);
                        // A pull down with the content at its top is the sheet's, not a row's.
                        if (dy > drag.slop && dy > dx * 1.5f && !panelView.canScrollVertically(-1)) {
                            drag.begin(event.getRawY());
                            return true;
                        }
                        return false;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public boolean onTouchEvent(android.view.MotionEvent event) {
                SheetDrag drag = sheetDrag;
                if (drag == null || !drag.dragging) return super.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_MOVE:
                        drag.moveTo(event.getRawY());
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        drag.finish(event.getRawY());
                        return true;
                    default:
                        return true;
                }
            }
        };
        sheet.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{com.flowify.ettea.settings.PanelStyle.COL_CARD_TOP | 0xFF000000,
                        com.flowify.ettea.settings.PanelStyle.COL_CARD | 0xFF000000});
        float r = 30 * density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, Math.round(density)), com.flowify.ettea.settings.PanelStyle.COL_CARD_BORDER);
        sheet.setBackground(bg);
        bg.setAlpha(halfMode ? COLLAPSED_ALPHA : expandedAlpha());
        sheet.setClipToOutline(true);

        FrameLayout handle = new FrameLayout(activity);
        View pill = new View(activity);
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setColor(0x59FFFFFF);
        pillBg.setCornerRadius(3 * density);
        pill.setBackground(pillBg);
        handle.addView(pill, new FrameLayout.LayoutParams(Math.round(40 * density),
                Math.round(5 * density), Gravity.CENTER));
        handle.setContentDescription(com.flowify.ettea.ui.UiLanguage.strings(activity,
                new SettingsStore(activity).get(com.flowify.ettea.Settings.UI_LANGUAGE))
                .get("settings_panel_resize", "Resize settings panel"));
        sheet.addView(handle, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.round(26 * density)));
        sheet.addView(panelView, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        handleOut[0] = handle;
        return sheet;
    }

    /** The sheet reaches the screen's bottom edge; its content stays clear of the navigation bar
     *  and the keyboard. */
    private static void fitSheetToInsets(PanelSurface surface, View sheet) {
        surface.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, ime.bottom);
                sheet.setPadding(0, 0, 0, ime.bottom > 0 ? 0 : bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), 0);
                sheet.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    private int sheetHeight(boolean collapsed) {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        if (collapsed) return Math.round(screenH * COLLAPSED_SHARE);
        return Math.round(screenH * (screenH < dm.widthPixels ? 0.96f : 0.9f));
    }

    private void applyCardSize(View card, boolean collapsed) {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, Math.round(PANEL_MAX_WIDTH_DP * dm.density));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(w, sheetHeight(collapsed));
        lp.width = w;
        lp.height = sheetHeight(collapsed);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        card.setLayoutParams(lp);
    }

    /** Expanded: a full-screen modal window (dim, blur, tap outside closes). Collapsed: a window
     *  only as tall as the sheet that lets every touch outside it through to the lyrics. */
    private void applyWindowState(Dialog dialog, boolean collapsed) {
        Window window = dialog.getWindow();
        if (window == null) return;
        if (collapsed) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight(true));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        } else {
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        }
    }

    private void setCollapsed(Dialog dialog, PanelSurface surface, View sheet, boolean collapse) {
        int expandedH = sheetHeight(false);
        int collapsedH = sheetHeight(true);
        int duration = Motion.dur(Motion.BASE);
        sheet.animate().cancel();
        halfMode = collapse;
        if (collapse) {
            surface.animateDim(0f, duration);
            if (sheet.getBackground() != null) sheet.getBackground().setAlpha(COLLAPSED_ALPHA);
            Runnable done = () -> {
                applyCardSize(sheet, true);
                sheet.setTranslationY(0f);
                applyWindowState(dialog, true);
            };
            if (!Motion.animationsEnabled()) {
                done.run();
                return;
            }
            sheet.animate().translationY(Math.max(0, sheet.getHeight() - collapsedH))
                    .setDuration(duration).setInterpolator(Motion.decel()).withEndAction(done).start();
        } else {
            float from = sheet.getTranslationY();
            applyWindowState(dialog, false);
            if (sheet.getBackground() != null) sheet.getBackground().setAlpha(expandedAlpha());
            applyCardSize(sheet, false);
            sheet.setTranslationY(from + expandedH - collapsedH);
            surface.animateDim(EXPANDED_DIM, duration);
            if (!Motion.animationsEnabled()) {
                sheet.setTranslationY(0f);
                return;
            }
            sheet.animate().translationY(0f).setDuration(duration)
                    .setInterpolator(Motion.decel()).start();
        }
    }

    /**
     * Pulling the sheet down closes it, as the share panel does - by its handle, or by its
     * content once that is scrolled to the top. Collapsed, pulling it up expands it again; a tap
     * on the handle toggles the two sizes.
     */
    /** The open sheet's drag; read by the sheet container's touch interception. */
    private SheetDrag sheetDrag;

    private final class SheetDrag {
        private final Dialog dialog;
        private final PanelSurface surface;
        private final View sheet;
        final float slop;
        private float startY;
        boolean dragging;
        /** Set once an upward drag has borrowed the expanded window; see {@link #move}. */
        private boolean tempExpandedWindow;

        void begin(float rawY) {
            startY = rawY;
            dragging = true;
            sheet.animate().cancel();
        }

        void moveTo(float rawY) {
            move(rawY - startY);
        }

        void finish(float rawY) {
            dragging = false;
            release(rawY - startY);
        }

        SheetDrag(Dialog dialog, PanelSurface surface, View sheet) {
            this.dialog = dialog;
            this.surface = surface;
            this.sheet = sheet;
            this.slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        void attachHandle(View handle) {
            if (handle == null) return;
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        dragging = false;
                        sheet.animate().cancel();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        move(event.getRawY() - startY);
                        return true;
                    case android.view.MotionEvent.ACTION_UP: {
                        float dy = event.getRawY() - startY;
                        if (Math.abs(dy) < slop) {
                            setCollapsed(dialog, surface, sheet, !halfMode);
                        } else {
                            release(dy);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        release(0f);
                        return true;
                    default:
                        return false;
                }
            });
        }

        private void move(float dy) {
            if (halfMode && dy < 0 && !tempExpandedWindow) {
                // Collapsed, the window is sized to exactly the collapsed sheet height and
                // anchored to the screen bottom (applyWindowState). Translating the sheet
                // upward from there moved it past the window's own top edge, which clipped it
                // mid-drag instead of smoothly growing - the window only got resized in
                // setCollapsed(), after release(). Borrow the full-size window for the drag;
                // release() below puts it back if the drag ends up staying collapsed.
                tempExpandedWindow = true;
                applyWindowState(dialog, false);
            }
            sheet.setTranslationY(dy > 0 ? dy : (halfMode ? dy * 0.35f : dy * 0.12f));
        }

        /**
         * Full size: a short pull settles at the middle size, a long one closes. Middle size: a
         * pull down closes, a pull up goes back to full size.
         */
        private void release(float dy) {
            float density = activity.getResources().getDisplayMetrics().density;
            float shortPull = Math.min(sheet.getHeight() * 0.12f, 90 * density);
            float longPull = halfMode ? Math.min(sheet.getHeight() * 0.25f, 160 * density)
                    : Math.max(sheet.getHeight() - sheetHeight(true) + 80 * density, sheet.getHeight() * 0.62f);
            boolean hadTempWindow = tempExpandedWindow;
            tempExpandedWindow = false;
            if (dy > longPull) {
                surface.exit(null);
            } else if (!halfMode && dy > shortPull) {
                setCollapsed(dialog, surface, sheet, true);
            } else if (halfMode && dy < -slop * 3) {
                setCollapsed(dialog, surface, sheet, false);
            } else {
                sheet.animate().translationY(0f).setDuration(Motion.dur(Motion.BASE))
                        .setInterpolator(Motion.decel())
                        .withEndAction(hadTempWindow ? () -> applyWindowState(dialog, true) : null)
                        .start();
            }
        }
    }
}
