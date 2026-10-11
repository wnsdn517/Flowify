package com.eza.spicyex.hooks;

import android.app.Activity;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewConfiguration;
import android.view.Gravity;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.motion.MotionArtworkFinder;
import com.eza.spicyex.motion.MotionArtworkView;
import com.eza.spicyex.ui.ApplePlayerBackdrop;
import com.eza.spicyex.ui.RollingTimeDrawable;
import com.eza.spicyex.ui.ThinPlaybackBar;
import com.eza.spicyex.xposed.XpLog;

import java.util.ArrayDeque;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restyles Spotify's player screen (NowPlayingActivity) after Apple Music's: the cover fills the
 * top edge to edge and melts into a blurred continuation behind the controls (no square cover
 * card), the transport button gets an Apple-like treatment, and the times read elapsed /
 * -remaining with digits that roll as they change. An optional animated artwork layer replaces
 * the static cover (Settings.PLAYER_ANIMATED_ARTWORK).
 *
 * <p>Nothing is rebuilt or laid over the screen: Spotify's own views are kept and changed in
 * place, found by resource name, and the change is made from a pre-draw listener attached when
 * the screen resumes - before the first frame those views appear in, so Spotify's original look
 * is never drawn first and then replaced. The cover is read from Spotify's own cover view (its
 * already decoded image, no download), and the carousel stays where it is, visible until the
 * backdrop has its first cover and invisible afterwards, so swiping still moves the backdrop.
 */
final class ApplePlayerStyler implements ViewTreeObserver.OnPreDrawListener {
    private static final WeakHashMap<Activity, ApplePlayerStyler> ATTACHED = new WeakHashMap<>();
    private static final String NOW_PLAYING = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity";
    private static final long SCREEN_MAINTENANCE_INTERVAL_MS = 250L;
    private static final int ACTION_BUTTON_SIZE_DP = 32;
    private static final int ACTION_ICON_SIZE_DP = 16;
    private static final ExecutorService ARTWORK_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "spicy-player-artwork");
        thread.setDaemon(true);
        return thread;
    });

    private final Activity activity;
    private final View decor;
    private final boolean styleScreen;
    private final boolean styleTime;
    private final boolean animatedArtworkEnabled;
    private final NativeSpicyLyricsHook hook;

    private ViewGroup overlay;
    private ViewGroup carousel;
    private ViewGroup header;
    private ApplePlayerBackdrop backdrop;
    private FadingArtworkFrame animatedArtworkLayer;
    // Motion artwork (Apple / Tidal / community video), when the track has any: shown in place of
    // the shader once its first frame is up.
    private MotionArtworkView motionView;
    private String motionTrackUri = "";
    private long motionSyncAt;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final WeakHashMap<View, TransportButtonState> transportButtonStates =
            new WeakHashMap<>();
    private final WeakHashMap<ImageView, LikeButtonState> likeButtonStates = new WeakHashMap<>();
    private final WeakHashMap<View, Integer> hiddenPlaylistControls = new WeakHashMap<>();
    private final WeakHashMap<View, Float> trackInfoTranslations = new WeakHashMap<>();
    private final WeakHashMap<TextView, TrackTextLayoutState> trackTextLayouts = new WeakHashMap<>();
    private final WeakHashMap<ViewGroup, ViewGroupClippingState> playerChromeClipping =
            new WeakHashMap<>();
    private boolean transportScanLogged;
    private boolean statusBarStateCaptured;
    private int originalStatusBarColor;
    private boolean originalStatusBarContrastEnforced;
    private View statusBarBackground;
    private float originalStatusBarBackgroundAlpha = 1f;
    private boolean statusBarBackgroundAlphaCaptured;
    private TextView position;
    private TextView duration;
    private RollingTimeDrawable positionClock;
    private RollingTimeDrawable durationClock;
    private ThinPlaybackBar playbackBar;
    private View nativeProgress;
    private int nativeProgressVisibility = View.VISIBLE;
    private long playbackDurationMs;
    private long playbackReadAt;
    private long playbackBoundsReadAt;
    private long seekCapabilityReadAt;
    private long progressScanAt;
    private boolean canSeekPlayback;
    private String clockTrackUri = "";
    private int durationSeconds = -1;
    private int lastPositionSeconds = -1;
    private boolean writingDuration;
    private final WeakHashMap<Drawable, ApplePlayerBackdrop.Page> pageCache = new WeakHashMap<>();
    private final WeakHashMap<View, ImageView> coverOf = new WeakHashMap<>();
    private final WeakHashMap<View, Float> lastCarouselOffsets = new WeakHashMap<>();
    private final WeakHashMap<View, Float> accumulatedCarouselTravel = new WeakHashMap<>();
    private final WeakHashMap<View, Long> lastCarouselMotionAt = new WeakHashMap<>();
    private final Rect savedBounds = new Rect();
    private final Rect gestureExclusion = new Rect();
    private int stableBannerHeight;
    private float originalCarouselAlpha = 1f;
    private long albumReadAt;
    private String albumTrackUri = "";
    private String albumName;
    private long likeStateReadAt;
    private boolean likeStateKnown;
    private boolean liked;
    private ImageView currentLikeButton;
    private boolean likeNeighboursLogged;
    private long lastCarouselSwipeAt;

    static boolean isPlayerScreen(Activity activity) {
        return activity != null && NOW_PLAYING.equals(activity.getClass().getName());
    }

    /** Attaches to {@code activity}'s player screen if a style is on; idempotent. */
    static void attach(Activity activity, NativeSpicyLyricsHook hook) {
        if (!isPlayerScreen(activity) || activity.getWindow() == null) return;
        synchronized (ATTACHED) {
            ApplePlayerStyler existing = ATTACHED.get(activity);
            if (existing != null) {
                return;
            }
        }
        SpotifyPlusConfig config = SpotifyPlusConfig.from(activity);
        boolean screen = Boolean.TRUE.equals(config.get(Settings.PLAYER_APPLE_STYLE));
        boolean time = Boolean.TRUE.equals(config.get(Settings.PLAYER_APPLE_TIME));
        boolean animatedArtwork = screen
                && Boolean.TRUE.equals(config.get(Settings.PLAYER_ANIMATED_ARTWORK));
        if (!screen && !time) return;
        ApplePlayerStyler styler = new ApplePlayerStyler(
                activity, screen, time, animatedArtwork, hook);
        synchronized (ATTACHED) {
            ATTACHED.put(activity, styler);
        }
        styler.decor.getViewTreeObserver().addOnPreDrawListener(styler);
    }

    static void detach(Activity activity) {
        ApplePlayerStyler styler;
        synchronized (ATTACHED) {
            styler = ATTACHED.remove(activity);
        }
        if (styler != null) styler.decor.getViewTreeObserver().removeOnPreDrawListener(styler);
    }

    /** Consume a back event misreported at the end of a horizontal track-carousel swipe. */
    static boolean consumeRecentCarouselSwipe(Activity activity) {
        ApplePlayerStyler styler;
        synchronized (ATTACHED) {
            styler = ATTACHED.get(activity);
        }
        if (styler == null) return false;
        long elapsed = android.os.SystemClock.elapsedRealtime() - styler.lastCarouselSwipeAt;
        if (styler.lastCarouselSwipeAt == 0L || elapsed < 0L || elapsed > 350L) {
            if (elapsed > 350L) styler.lastCarouselSwipeAt = 0L;
            return false;
        }
        styler.lastCarouselSwipeAt = 0L;
        return true;
    }

    private static ApplePlayerStyler stylerFor(Activity activity) {
        synchronized (ATTACHED) {
            return ATTACHED.get(activity);
        }
    }

    /**
     * Window-space area for lyrics shown inside the styled player: under the small cover row,
     * down to the progress bar - or to the bottom edge while the controls are swiped away. Null
     * when the Apple screen style is not on this activity or its views are not laid out yet.
     */
    static android.graphics.Rect inlineLyricsBounds(Activity activity) {
        ApplePlayerStyler styler = stylerFor(activity);
        return styler == null || !styler.styleScreen ? null : styler.inlineBounds();
    }

    /**
     * Lyrics take (or give back) the cover's place, the way BitChord and Apple Music do it: the
     * big cover shrinks into a small one at the top beside the title, and the lyrics fill the
     * space between it and the controls. {@code closeAction} runs when the small cover row is
     * tapped.
     */
    static void setInlineLyricsOpen(Activity activity, boolean open, Runnable closeAction) {
        ApplePlayerStyler styler = stylerFor(activity);
        if (styler != null) styler.setInlineOpen(open, closeAction);
    }

    /** Swipe the playback controls down out of the way (true) or back up (false). */
    static void setInlineControlsHidden(Activity activity, boolean hidden) {
        ApplePlayerStyler styler = stylerFor(activity);
        if (styler != null) styler.setControlsHidden(hidden);
    }

    static boolean inlineControlsHidden(Activity activity) {
        ApplePlayerStyler styler = stylerFor(activity);
        return styler != null && styler.inlineControlsHidden;
    }

    /** How long the cover shrink and the controls slide take; the lyrics area follows in step. */
    static final long INLINE_TRANSITION_MS = 420L;

    private ApplePlayerStyler(Activity activity, boolean styleScreen, boolean styleTime,
                              boolean animatedArtworkEnabled, NativeSpicyLyricsHook hook) {
        this.activity = activity;
        this.hook = hook;
        this.decor = activity.getWindow().getDecorView();
        this.styleScreen = styleScreen;
        this.styleTime = styleTime;
        this.animatedArtworkEnabled = animatedArtworkEnabled;
    }

    private int id(String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private int frame;
    private int failures;
    private long screenMaintenanceAt;

    @Override
    public boolean onPreDraw() {
        try {
            frame++;
            // Spotify rebuilds the player page on its own (a new track's template, coming back
            // from the lyrics, a configuration change): views styled once are then detached and
            // its plain page is what shows. A styled page that is no longer on screen is let go
            // and the new one styled on the frame it first appears.
            if (overlay != null && (!overlay.isAttachedToWindow()
                    || (backdrop != null && backdrop.getParent() != overlay)
                    || (frame % 30 == 0 && decor.findViewById(id("overlay_controls_layout")) != overlay))) {
                release();
            }
            if (overlay == null) {
                // Not every frame: a page that has no player yet (a dock or PiP over it) costs
                // one lookup every few frames, and is picked up as soon as it appears.
                if (frame % 4 != 1 && frame > 2) return true;
                if (!resolve()) return true;
            }
            if (styleScreen) {
                // Spotify briefly restores its native play/pause treatment while its playback
                // state animation runs. Keep this small, idempotent override on every draw;
                // broader layout maintenance remains throttled below.
                styleTransportButton();
                // The like button is otherwise only restyled on the throttled maintenance pass,
                // so on the frames it first appears it flashes Spotify's native heart before the
                // Apple circle is applied. Restyle it every draw too (the track-saved read inside
                // stays throttled), so it is Apple-styled on the first frame it exists.
                styleLikeButton();
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - screenMaintenanceAt >= SCREEN_MAINTENANCE_INTERVAL_MS) {
                    screenMaintenanceAt = now;
                    maintainScreen();
                } else {
                    updateBackdrop();
                    updatePlaybackBar();
                }
            }
            if (positionClock != null) pollClocks();
            failures = 0;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " apple player failed: " + t);
            release();
            // A screen that keeps failing is left as Spotify draws it.
            if (++failures >= 3) detach(activity);
        }
        return true;
    }

    /** Lets go of a page Spotify replaced (or that failed), so the next one is styled afresh. */
    private void release() {
        clearGestureExclusion();
        restoreStatusBar();
        restoreTopContrast();
        restoreAddButton();
        releaseAnimatedArtworkLayer();
        if (carousel != null) carousel.setAlpha(originalCarouselAlpha);
        for (java.util.Map.Entry<View, TransportButtonState> entry
                : transportButtonStates.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        transportButtonStates.clear();
        for (java.util.Map.Entry<ImageView, LikeButtonState> entry : likeButtonStates.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        likeButtonStates.clear();
        for (java.util.Map.Entry<View, Integer> entry : hiddenPlaylistControls.entrySet()) {
            entry.getKey().setVisibility(entry.getValue());
        }
        hiddenPlaylistControls.clear();
        for (java.util.Map.Entry<View, Float> entry : trackInfoTranslations.entrySet()) {
            entry.getKey().setTranslationY(entry.getValue());
        }
        trackInfoTranslations.clear();
        for (java.util.Map.Entry<TextView, TrackTextLayoutState> entry : trackTextLayouts.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        trackTextLayouts.clear();
        for (java.util.Map.Entry<ViewGroup, ViewGroupClippingState> entry
                : playerChromeClipping.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        playerChromeClipping.clear();
        transportScanLogged = false;
        screenMaintenanceAt = 0L;
        if (playbackBar != null && playbackBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) playbackBar.getParent()).removeView(playbackBar);
        }
        if (nativeProgress != null && nativeProgress.getVisibility() == View.INVISIBLE) {
            nativeProgress.setVisibility(nativeProgressVisibility);
        }
        playbackBar = null;
        nativeProgress = null;
        progressScanAt = 0L;
        playbackBoundsReadAt = 0L;
        if (topScrim != null && topScrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) topScrim.getParent()).removeView(topScrim);
        }
        topScrim = null;
        releaseInlineViews();
        if (backdrop != null && backdrop.getParent() instanceof ViewGroup) {
            ((ViewGroup) backdrop.getParent()).removeView(backdrop);
        }
        if (moreButton != null && moreButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) moreButton.getParent()).removeView(moreButton);
        }
        if (downloadButton != null && downloadButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) downloadButton.getParent()).removeView(downloadButton);
        }
        overlay = null;
        carousel = null;
        header = null;
        backdrop = null;
        moreButton = null;
        downloadButton = null;
        menuButton = null;
        position = null;
        duration = null;
        positionClock = null;
        durationClock = null;
        seenPosition = "";
        seenDuration = "";
        coverOf.clear();
        lastCarouselOffsets.clear();
        accumulatedCarouselTravel.clear();
        lastCarouselMotionAt.clear();
        stableBannerHeight = 0;
    }

    private void releaseAnimatedArtworkLayer() {
        if (backdrop != null) backdrop.setCoverReplacedByAnimatedArtwork(false);
        if (motionView != null) motionView.release();
        if (animatedArtworkLayer != null && animatedArtworkLayer.getParent() instanceof ViewGroup) {
            ((ViewGroup) animatedArtworkLayer.getParent()).removeView(animatedArtworkLayer);
        }
        animatedArtworkLayer = null;
        motionView = null;
        motionTrackUri = "";
    }

    /** Finds the player's views; on the frame they first exist, styles them before it draws. */
    private boolean resolve() {
        View overlayView = decor.findViewById(id("overlay_controls_layout"));
        if (!(overlayView instanceof ViewGroup)) return false;
        View carouselView = overlayView.findViewById(id("track_carousel"));
        if (!(carouselView instanceof ViewGroup)) return false;
        overlay = (ViewGroup) overlayView;
        carousel = (ViewGroup) carouselView;
        originalCarouselAlpha = carousel.getAlpha();
        View headerView = overlay.findViewById(id("player_overlay_header"));
        header = headerView instanceof ViewGroup ? (ViewGroup) headerView : null;
        if (styleScreen) {
            backdrop = new ApplePlayerBackdrop(activity);
            overlay.addView(backdrop, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            attachAnimatedArtworkLayer();
            attachTopScrim();
            if (inlineLyricsOpen) {
                backdrop.setCoverDimmed(true);
                applyInlineLayoutNow();
            }
        }
        if (styleTime) attachClocks();
        if (styleScreen) {
            clearStatusBar();
            addMoreButton();
            addDownloadButton();
            attachPlaybackBar();
        }
        XpLog.log(NativeSpicyLyricsHook.TAG + " apple player styled screen=" + styleScreen
                + " time=" + (positionClock != null) + " more=" + (moreButton != null)
                + " download=" + (downloadButton != null));
        return true;
    }

    // --- Status bar --------------------------------------------------------------------------

    /** The cover runs up behind the status bar: no coloured bar over it. */
    private void clearStatusBar() {
        Window window = activity.getWindow();
        if (!statusBarStateCaptured) {
            originalStatusBarColor = window.getStatusBarColor();
            if (Build.VERSION.SDK_INT >= 29) {
                originalStatusBarContrastEnforced = window.isStatusBarContrastEnforced();
            }
            statusBarStateCaptured = true;
        }
        View background = decor.findViewById(android.R.id.statusBarBackground);
        if (background != statusBarBackground) {
            statusBarBackground = background;
            statusBarBackgroundAlphaCaptured = background != null;
            if (background != null) originalStatusBarBackgroundAlpha = background.getAlpha();
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setStatusBarContrastEnforced(false);
    }

    private void restoreStatusBar() {
        if (!statusBarStateCaptured) return;
        Window window = activity.getWindow();
        window.setStatusBarColor(originalStatusBarColor);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(originalStatusBarContrastEnforced);
        }
        if (statusBarBackground != null && statusBarBackgroundAlphaCaptured) {
            statusBarBackground.setAlpha(originalStatusBarBackgroundAlpha);
        }
        statusBarBackground = null;
        statusBarBackgroundAlphaCaptured = false;
        statusBarStateCaptured = false;
    }

    // --- More --------------------------------------------------------------------------------

    private View menuButton;
    private View moreButton;
    private View downloadButton;

    /**
     * The header's menu button, hidden with the rest of the header, comes back next to the like
     * button as Apple's player has it: a round "..." that presses Spotify's own button, so the
     * menu that opens is exactly Spotify's.
     */
    private void addMoreButton() {
        if (header == null) return;
        for (int i = header.getChildCount() - 1; i >= 0; i--) {
            View child = header.getChildAt(i);
            if (child instanceof android.widget.ImageButton) {
                menuButton = child;
                break;
            }
        }
        View feedback = overlay.findViewById(id("feedback_buttons_container"));
        if (menuButton == null || !(feedback instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) feedback;
        float density = activity.getResources().getDisplayMetrics().density;
        int size = Math.round(ACTION_BUTTON_SIZE_DP * density);
        android.widget.ImageButton more = new android.widget.ImageButton(activity);
        more.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                com.eza.spicyex.ui.ActionIconDrawable.Kind.ELLIPSIS, Color.WHITE, density,
                ACTION_ICON_SIZE_DP));
        more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int inset = Math.round((ACTION_BUTTON_SIZE_DP - ACTION_ICON_SIZE_DP) * 0.5f * density);
        more.setPadding(inset, inset, inset, inset);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        more.setBackground(circle);
        CharSequence label = menuButton.getContentDescription();
        more.setContentDescription(label != null ? label : "More options");
        more.setOnClickListener(v -> {
            View target = menuButton;
            if (target != null) target.performClick();
        });
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(size, size);
        params.setMarginStart(Math.round(8 * density));
        params.gravity = android.view.Gravity.CENTER_VERTICAL;
        row.addView(more, params);
        moreButton = more;
    }

    /**
     * Adds a download button next to the more button that triggers a YouTube Music download
     * of the currently playing track.
     */
    private void addDownloadButton() {
        if (hook == null) return;
        View feedback = overlay.findViewById(id("feedback_buttons_container"));
        if (!(feedback instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) feedback;
        float density = activity.getResources().getDisplayMetrics().density;
        int size = Math.round(ACTION_BUTTON_SIZE_DP * density);
        android.widget.ImageButton download = new android.widget.ImageButton(activity);
        download.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                com.eza.spicyex.ui.ActionIconDrawable.Kind.DOWNLOAD, Color.WHITE, density,
                ACTION_ICON_SIZE_DP));
        download.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int inset = Math.round((ACTION_BUTTON_SIZE_DP - ACTION_ICON_SIZE_DP) * 0.5f * density);
        download.setPadding(inset, inset, inset, inset);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        download.setBackground(circle);
        download.setContentDescription("Download from YouTube Music");
        download.setOnClickListener(v -> {
            if (hook != null) {
                com.eza.spicyex.hooks.YoutubeDownloader downloader =
                        new com.eza.spicyex.hooks.YoutubeDownloader(activity, hook);
                downloader.downloadCurrentTrack();
            }
        });
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(size, size);
        params.setMarginStart(Math.round(8 * density));
        params.gravity = android.view.Gravity.CENTER_VERTICAL;
        row.addView(download, params);
        downloadButton = download;
    }

    // --- Screen ------------------------------------------------------------------------------

    private void maintainScreen() {
        allowPlayerChromeOverflow();
        // Spotify fades these back in on its own transitions; keep them as styled.
        if (statusBarBackground != null && statusBarBackground.getAlpha() != 0f) {
            statusBarBackground.setAlpha(0f);
        }
        if (header != null) {
            for (int i = 0; i < header.getChildCount(); i++) {
                View child = header.getChildAt(i);
                // Only the "Playing from" lines stay; the close and menu buttons go.
                boolean keep = child.findViewById(id("context_header_title")) != null;
                int wanted = keep ? child.getVisibility() : View.INVISIBLE;
                if (!keep && child.getVisibility() == View.VISIBLE) child.setVisibility(wanted);
            }
        }
        placeTop();
        if (inlineLyricsOpen) maintainInlineLayout();
        styleTrackInfo();
        hidePlaylistHideButton();
        styleLikeButton();
        styleAddButton();
        boolean backdropReady = updateBackdrop();
        applyTopContrast();
        float carouselAlpha = backdropReady ? 0f : 1f;
        if (carousel.getAlpha() != carouselAlpha) carousel.setAlpha(carouselAlpha);
        updatePlaybackBar();
    }

    private void hidePlaylistHideButton() {
        if (overlay == null) return;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(overlay);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            String entry = ViewIds.entryName(view);
            CharSequence description = view.getContentDescription();
            String label = description == null ? ""
                    : description.toString().toLowerCase(java.util.Locale.ROOT);
            String name = entry == null ? "" : entry.toLowerCase(java.util.Locale.ROOT);
            boolean hideButton = "ban_button".equals(entry)
                    || "hide_in_this_playlist_button".equals(entry)
                    || "hide_in_playlist_button".equals(entry)
                    || "close_button".equals(entry)
                    || "dismiss_button".equals(entry)
                    || name.contains("ban") || name.contains("dislike")
                    || name.contains("not_interested") || name.contains("hide_song")
                    || name.contains("dont_play") || name.contains("hide_in")
                    || label.contains("hide in this playlist")
                    || label.contains("hide in playlist")
                    || label.contains("don't play this song")
                    || label.contains("hide this song") || label.contains("dislike")
                    || label.contains("don't play") || label.contains("don’t play")
                    || label.startsWith("hide")
                    || label.contains("재생 안") || label.contains("재생하지")
                    || label.contains("숨기") || label.contains("숨김")
                    || label.contains("싫어");
            if (hideButton && view != moreButton && view.getVisibility() != View.GONE) {
                if (!hiddenPlaylistControls.containsKey(view)) {
                    hiddenPlaylistControls.put(view, view.getVisibility());
                }
                // GONE, not INVISIBLE: the others close up over the gap it leaves.
                view.setVisibility(View.GONE);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
    }

    private View addButton;
    private Drawable addButtonBackground;
    private Drawable addButtonStyledBackground;
    private int addButtonPaddingLeft, addButtonPaddingTop, addButtonPaddingRight, addButtonPaddingBottom;
    private int addButtonOriginalWidth, addButtonOriginalHeight;
    private boolean addButtonOriginalSizeCaptured;
    private int addButtonStyledWidth = -1;
    private int addButtonStyledHeight = -1;
    private boolean addButtonMissingIconLogged;
    private String addButtonLastLayoutSignature = "";
    private final WeakHashMap<ImageView, AddButtonIconState> addButtonIconStates = new WeakHashMap<>();

    /** Keep Spotify's native add button and refine its visual size to match the neighboring actions. */
    private void styleAddButton() {
        View feedback = overlay.findViewById(id("feedback_buttons_container"));
        if (!(feedback instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) feedback;
        View found = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if ("AddToButtonView".equals(child.getClass().getSimpleName())) {
                found = child;
                break;
            }
        }
        if (found == null || found.getVisibility() == View.GONE) return;
        int[] buttonLocation = new int[2];
        found.getLocationInWindow(buttonLocation);
        ViewGroup.LayoutParams measuredParams = found.getLayoutParams();
        String layoutSignature = buttonLocation[0] + "," + buttonLocation[1] + " "
                + found.getWidth() + "x" + found.getHeight() + " lp="
                + (measuredParams == null ? "none"
                : measuredParams.width + "x" + measuredParams.height);
        if (!layoutSignature.equals(addButtonLastLayoutSignature)) {
            addButtonLastLayoutSignature = layoutSignature;
            XpLog.log(NativeSpicyLyricsHook.TAG + " Spotify add button layout " + layoutSignature);
        }
        if (addButton != found) {
            restoreAddButton();
            addButton = found;
            addButtonBackground = found.getBackground();
            addButtonPaddingLeft = found.getPaddingLeft();
            addButtonPaddingTop = found.getPaddingTop();
            addButtonPaddingRight = found.getPaddingRight();
            addButtonPaddingBottom = found.getPaddingBottom();
            ViewGroup.LayoutParams originalParams = found.getLayoutParams();
            if (originalParams != null) {
                addButtonOriginalWidth = originalParams.width;
                addButtonOriginalHeight = originalParams.height;
                addButtonOriginalSizeCaptured = true;
            }
            int contentInset = Math.round(
                    4f * activity.getResources().getDisplayMetrics().density);
            found.setPadding(addButtonPaddingLeft + contentInset,
                    addButtonPaddingTop + contentInset,
                    addButtonPaddingRight + contentInset,
                    addButtonPaddingBottom + contentInset);
        }
        // Do not resize the native add button's layout params: Spotify lays the feedback row out
        // with a constraint/linear solver, and rewriting this child's width/height every frame
        // made it thrash position (the "+" jumped off its slot). We only restyle it visually -
        // a circular background inset to the Apple action size and a shrunk icon - and leave the
        // view's measured slot exactly where Spotify put it, matching the like/ban buttons.
        int width = found.getWidth();
        int height = found.getHeight();
        if (width != addButtonStyledWidth || height != addButtonStyledHeight) {
            addButtonStyledWidth = width;
            addButtonStyledHeight = height;
            float density = activity.getResources().getDisplayMetrics().density;
            int visualSize = Math.round(ACTION_BUTTON_SIZE_DP * density);
            int side = width > 0 && height > 0 ? Math.min(visualSize, Math.min(width, height))
                    : visualSize;
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x33FFFFFF);
            int insetX = width > side ? (width - side) / 2 : 0;
            int insetY = height > side ? (height - side) / 2 : 0;
            addButtonStyledBackground = insetX == 0 && insetY == 0 ? circle
                    : new android.graphics.drawable.InsetDrawable(
                            circle, insetX, insetY, insetX, insetY);
        }
        if (addButtonStyledBackground != null
                && found.getBackground() != addButtonStyledBackground) {
            found.setBackground(addButtonStyledBackground);
        }
        alignMoreButtonAfterAdd(row, found, width);
        shrinkAddButtonIcons(found);
    }

    /**
     * The add button keeps Spotify's wider slot, its circle inset inside it, so the gap to the
     * "..." button we add after it reads twice the gap between our own buttons. Pull our button
     * in by that transparent inset; Spotify's view is left untouched.
     */
    private void alignMoreButtonAfterAdd(ViewGroup row, View add, int addWidth) {
        if (moreButton == null || addWidth <= 0
                || row.indexOfChild(moreButton) != row.indexOfChild(add) + 1) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int inset = Math.max(0, (addWidth - Math.round(ACTION_BUTTON_SIZE_DP * density)) / 2);
        int wanted = Math.round(8 * density) - inset;
        ViewGroup.LayoutParams params = moreButton.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams) params).getMarginStart() != wanted) {
            ((ViewGroup.MarginLayoutParams) params).setMarginStart(wanted);
            moreButton.setLayoutParams(params);
        }
    }

    private void shrinkAddButtonIcons(View root) {
        float density = activity.getResources().getDisplayMetrics().density;
        float targetSize = ACTION_ICON_SIZE_DP * density;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
            if (!(view instanceof ImageView)) continue;
            ImageView icon = (ImageView) view;
            AddButtonIconState state = addButtonIconStates.get(icon);
            if (state == null) {
                state = new AddButtonIconState(icon);
                addButtonIconStates.put(icon, state);
            }
            if (icon.getDrawable() == null) {
                if (state.fallbackDrawable == null) {
                    state.fallbackDrawable = new com.eza.spicyex.ui.ActionIconDrawable(
                            com.eza.spicyex.ui.ActionIconDrawable.Kind.PLUS, Color.WHITE,
                            density, ACTION_ICON_SIZE_DP);
                }
                icon.setImageDrawable(state.fallbackDrawable);
                if (!addButtonMissingIconLogged) {
                    addButtonMissingIconLogged = true;
                    XpLog.log(NativeSpicyLyricsHook.TAG + " Spotify add button icon was empty; "
                            + "showing fallback (view=" + icon.getClass().getName()
                            + ", size=" + icon.getWidth() + "x" + icon.getHeight()
                            + ", visibility=" + icon.getVisibility()
                            + ", alpha=" + icon.getAlpha() + ")");
                }
            }
            if (icon.getWidth() <= 0 || icon.getHeight() <= 0) continue;
            int contentWidth = Math.max(0,
                    icon.getWidth() - state.paddingLeft - state.paddingRight);
            int contentHeight = Math.max(0,
                    icon.getHeight() - state.paddingTop - state.paddingBottom);
            int insetX = Math.max(0, Math.round((contentWidth - targetSize) * 0.5f));
            int insetY = Math.max(0, Math.round((contentHeight - targetSize) * 0.5f));
            int paddingLeft = state.paddingLeft + insetX;
            int paddingTop = state.paddingTop + insetY;
            int paddingRight = state.paddingRight + insetX;
            int paddingBottom = state.paddingBottom + insetY;
            if (icon.getPaddingLeft() != paddingLeft || icon.getPaddingTop() != paddingTop
                    || icon.getPaddingRight() != paddingRight
                    || icon.getPaddingBottom() != paddingBottom) {
                icon.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            }
            if (icon.getScaleType() != ImageView.ScaleType.CENTER_INSIDE) {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
        }
    }

    private void restoreAddButton() {
        for (java.util.Map.Entry<ImageView, AddButtonIconState> entry
                : addButtonIconStates.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        addButtonIconStates.clear();
        if (addButton != null) {
            ViewGroup.LayoutParams params = addButton.getLayoutParams();
            if (addButtonOriginalSizeCaptured && params != null) {
                params.width = addButtonOriginalWidth;
                params.height = addButtonOriginalHeight;
                addButton.setLayoutParams(params);
            }
            addButton.setBackground(addButtonBackground);
            addButton.setPadding(addButtonPaddingLeft, addButtonPaddingTop,
                    addButtonPaddingRight, addButtonPaddingBottom);
        }
        addButton = null;
        addButtonOriginalSizeCaptured = false;
        addButtonMissingIconLogged = false;
        addButtonLastLayoutSignature = "";
        addButtonStyledBackground = null;
        addButtonStyledWidth = -1;
        addButtonStyledHeight = -1;
    }

    private static final class AddButtonIconState {
        final Drawable originalDrawable;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final ImageView.ScaleType scaleType;
        Drawable fallbackDrawable;

        AddButtonIconState(ImageView imageView) {
            originalDrawable = imageView.getDrawable();
            paddingLeft = imageView.getPaddingLeft();
            paddingTop = imageView.getPaddingTop();
            paddingRight = imageView.getPaddingRight();
            paddingBottom = imageView.getPaddingBottom();
            scaleType = imageView.getScaleType();
        }

        void restore(ImageView imageView) {
            if (fallbackDrawable != null && imageView.getDrawable() == fallbackDrawable) {
                imageView.setImageDrawable(originalDrawable);
            }
            imageView.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            imageView.setScaleType(scaleType);
        }
    }

    private boolean lastStatusBarLight;
    private boolean topContrastApplied;
    private int originalSystemUiVisibility;
    private int topTextTarget = Integer.MIN_VALUE;
    private ValueAnimator topTextColorAnimator;

    /**
     * The "Playing from" lines, the handle and the status-bar icons sit on the cover's top edge.
     * Picking black or white from the edge's average brightness failed on busy covers - part of
     * the text always landed on the wrong half - so, as Apple Music does, they stay white over a
     * soft dark scrim that deepens the brighter the cover's top is, with a light text shadow.
     */
    private void applyTopContrast() {
        if (backdrop == null || header == null) return;
        ApplePlayerBackdrop.Page page = backdrop.currentPage();
        if (page == null) return;
        float luminance = backdrop.topLuminance();
        int wanted = Color.WHITE;
        boolean light = false;
        View title = header.findViewById(id("context_header_title"));
        View subtitle = header.findViewById(id("context_header_subtitle"));
        if (topScrim != null) {
            topScrim.setLuminance(luminance);
            sizeTopScrim();
        }
        shadowHeaderText(title);
        shadowHeaderText(subtitle);
        backdrop.setTopGlyphColor(wanted);
        animateTopTextColor(title, subtitle, wanted);
        if (!topContrastApplied || light != lastStatusBarLight) {
            if (!topContrastApplied) originalSystemUiVisibility = decor.getSystemUiVisibility();
            topContrastApplied = true;
            lastStatusBarLight = light;
            int flags = decor.getSystemUiVisibility();
            flags = light ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            decor.setSystemUiVisibility(flags);
        }
    }

    private void animateTopTextColor(View title, View subtitle, int targetColor) {
        if (!(title instanceof TextView)) return;
        TextView titleText = (TextView) title;
        TextView subtitleText = subtitle instanceof TextView ? (TextView) subtitle : null;
        if (targetColor == topTextTarget) {
            if (topTextColorAnimator == null || !topTextColorAnimator.isRunning()) {
                if (titleText.getCurrentTextColor() != targetColor) titleText.setTextColor(targetColor);
                if (subtitleText != null && subtitleText.getCurrentTextColor() != targetColor) {
                    subtitleText.setTextColor(targetColor);
                }
            }
            return;
        }
        int fromColor = titleText.getCurrentTextColor();
        if (topTextColorAnimator != null) {
            fromColor = (int) topTextColorAnimator.getAnimatedValue();
            topTextColorAnimator.cancel();
        }
        topTextTarget = targetColor;
        topTextColorAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(), fromColor, targetColor);
        topTextColorAnimator.setDuration(220L);
        topTextColorAnimator.addUpdateListener(animation -> {
            int color = (int) animation.getAnimatedValue();
            if (titleText == header.findViewById(id("context_header_title"))) {
                titleText.setTextColor(color);
                if (subtitleText != null && subtitleText == header.findViewById(
                        id("context_header_subtitle"))) {
                    subtitleText.setTextColor(color);
                }
            }
        });
        topTextColorAnimator.start();
    }

    private void restoreTopContrast() {
        if (topTextColorAnimator != null) {
            topTextColorAnimator.cancel();
            topTextColorAnimator = null;
        }
        topTextTarget = Integer.MIN_VALUE;
        if (topContrastApplied) decor.setSystemUiVisibility(originalSystemUiVisibility);
        topContrastApplied = false;
        for (java.util.Map.Entry<TextView, float[]> entry : headerShadows.entrySet()) {
            float[] o = entry.getValue();
            entry.getKey().setShadowLayer(o[0], o[1], o[2], (int) o[3]);
        }
        headerShadows.clear();
    }

    // --- Top scrim ---------------------------------------------------------------------------

    private TopScrimView topScrim;
    /** Header lines given a shadow, with their original {radius, dx, dy, color}. */
    private final WeakHashMap<TextView, float[]> headerShadows = new WeakHashMap<>();

    /** Above the cover and any motion artwork, under the header: so it darkens both. */
    private void attachTopScrim() {
        if (!(overlay instanceof FrameLayout) || backdrop == null) return;
        TopScrimView scrim = new TopScrimView(activity, backdrop);
        int index = overlay.indexOfChild(animatedArtworkLayer != null
                ? animatedArtworkLayer : backdrop) + 1;
        overlay.addView(scrim, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, minTopScrimHeight(), Gravity.TOP));
        backdrop.setHandleHost(scrim);
        topScrim = scrim;
    }

    private int minTopScrimHeight() {
        return Math.round(150f * activity.getResources().getDisplayMetrics().density);
    }

    /** Reaches a little past the "Playing from" lines, wherever Spotify laid them out. */
    private void sizeTopScrim() {
        int wanted = minTopScrimHeight();
        if (header != null && header.getHeight() > 0) {
            int[] headerAt = new int[2];
            int[] overlayAt = new int[2];
            header.getLocationInWindow(headerAt);
            overlay.getLocationInWindow(overlayAt);
            int headerBottom = headerAt[1] - overlayAt[1] + header.getHeight();
            float density = activity.getResources().getDisplayMetrics().density;
            wanted = Math.max(wanted, headerBottom + Math.round(56f * density));
        }
        ViewGroup.LayoutParams params = topScrim.getLayoutParams();
        if (params != null && Math.abs(params.height - wanted) > 2) {
            params.height = wanted;
            topScrim.setLayoutParams(params);
        }
    }

    private void shadowHeaderText(View view) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        if (!headerShadows.containsKey(text)) {
            headerShadows.put(text, new float[]{text.getShadowRadius(), text.getShadowDx(),
                    text.getShadowDy(), text.getShadowColor()});
        }
        float density = activity.getResources().getDisplayMetrics().density;
        float radius = 6f * density;
        if (text.getShadowRadius() != radius) {
            text.setShadowLayer(radius, 0f, density, 0x73000000);
        }
    }

    /**
     * Apple Music's top edge: a soft black fade from the top that keeps the status bar, the
     * handle and "Playing from" readable over any cover, stronger over a bright cover top. The
     * backdrop's handle is drawn here, on top of the fade, so the fade never dims it.
     */
    private static final class TopScrimView extends View {
        private final Paint paint = new Paint();
        private final ApplePlayerBackdrop backdrop;
        private float luminance = -1f;
        private int shaderHeight = -1;

        TopScrimView(Activity activity, ApplePlayerBackdrop backdrop) {
            super(activity);
            this.backdrop = backdrop;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setLuminance(float value) {
            float clamped = Math.max(0f, Math.min(1f, value));
            if (luminance >= 0f && Math.abs(clamped - luminance) < 0.02f) return;
            luminance = clamped;
            shaderHeight = -1;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int h = getHeight();
            if (h <= 0) return;
            if (luminance >= 0f) {
                if (shaderHeight != h) {
                    shaderHeight = h;
                    float strength = 0.30f + 0.30f * luminance;
                    int top = Color.argb(Math.round(255 * strength), 0, 0, 0);
                    int middle = Color.argb(Math.round(255 * strength * 0.55f), 0, 0, 0);
                    paint.setShader(new LinearGradient(0f, 0f, 0f, h,
                            new int[]{top, middle, Color.TRANSPARENT},
                            new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                }
                canvas.drawRect(0f, 0f, getWidth(), h, paint);
            }
            backdrop.drawHandle(canvas, getWidth());
        }
    }

    private void styleLikeButton() {
        if (overlay == null) return;
        View target = overlay.findViewById(id("heart_button"));
        if (!(target instanceof ImageView) || target.getVisibility() != View.VISIBLE) return;
        ImageView button = (ImageView) target;
        if (!likeNeighboursLogged && target.getParent() instanceof ViewGroup) {
            likeNeighboursLogged = true;
            ViewGroup row = (ViewGroup) target.getParent();
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                XpLog.log(NativeSpicyLyricsHook.TAG + " like row child " + ViewIds.entryName(v)
                        + " desc=" + v.getContentDescription() + " vis=" + v.getVisibility());
            }
        }
        if (currentLikeButton != button) {
            currentLikeButton = button;
            likeStateKnown = false;
        }
        LikeButtonState state = likeButtonStates.get(button);
        if (state == null) {
            state = new LikeButtonState(button);
            likeButtonStates.put(button, state);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!likeStateKnown || now - likeStateReadAt >= 500L) {
            com.eza.spicyex.SpotifyTrack track = hook == null ? null : hook.getCurrentTrackSafely();
            liked = track != null && track.saved;
            likeStateReadAt = now;
            likeStateKnown = true;
        }
        state.apply(button, liked, activity.getResources().getDisplayMetrics().density);
    }

    private static final class LikeButtonState {
        private final Drawable originalDrawable;
        private final Drawable originalBackground;
        private final ColorStateList imageTint;
        private final android.graphics.ColorFilter colorFilter;
        private final ImageView.ScaleType scaleType;
        private final int originalPaddingLeft;
        private final int originalPaddingTop;
        private final int originalPaddingRight;
        private final int originalPaddingBottom;
        private Drawable styledDrawable;
        private Drawable styledBackground;
        private int styledColor;
        private int styledBackgroundWidth = -1;
        private int styledBackgroundHeight = -1;

        LikeButtonState(ImageView button) {
            originalDrawable = button.getDrawable();
            originalBackground = button.getBackground();
            imageTint = button.getImageTintList();
            colorFilter = button.getColorFilter();
            scaleType = button.getScaleType();
            originalPaddingLeft = button.getPaddingLeft();
            originalPaddingTop = button.getPaddingTop();
            originalPaddingRight = button.getPaddingRight();
            originalPaddingBottom = button.getPaddingBottom();
        }

        void apply(ImageView button, boolean liked, float density) {
            int color = liked ? 0xFF1ED760 : Color.WHITE;
            if (styledDrawable == null || styledColor != color) {
                styledDrawable = new com.eza.spicyex.ui.ActionIconDrawable(
                        com.eza.spicyex.ui.ActionIconDrawable.Kind.HEART, color, density,
                        ACTION_ICON_SIZE_DP);
                styledColor = color;
            }
            int width = button.getWidth();
            int height = button.getHeight();
            if (styledBackground == null || width != styledBackgroundWidth
                    || height != styledBackgroundHeight) {
                styledBackgroundWidth = width;
                styledBackgroundHeight = height;
                int circleSize = Math.round(ACTION_BUTTON_SIZE_DP * density);
                int insetX = width > circleSize ? (width - circleSize) / 2 : 0;
                int insetY = height > circleSize ? (height - circleSize) / 2 : 0;
                android.graphics.drawable.GradientDrawable circle =
                        new android.graphics.drawable.GradientDrawable();
                circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                circle.setColor(0x33FFFFFF);
                styledBackground = insetX == 0 && insetY == 0 ? circle
                        : new android.graphics.drawable.InsetDrawable(
                                circle, insetX, insetY, insetX, insetY);
            }
            if (button.getImageTintList() != null) button.setImageTintList(null);
            if (button.getColorFilter() != null) button.setColorFilter(null);
            if (button.getDrawable() != styledDrawable) button.setImageDrawable(styledDrawable);
            if (button.getBackground() != styledBackground) {
                button.setBackground(styledBackground);
            }
            if (button.getScaleType() != ImageView.ScaleType.CENTER_INSIDE) {
                button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
        }

        void restore(ImageView button) {
            if (button.getDrawable() == styledDrawable) button.setImageDrawable(originalDrawable);
            if (button.getBackground() == styledBackground) {
                button.setBackground(originalBackground);
            }
            button.setImageTintList(imageTint);
            button.setColorFilter(colorFilter);
            button.setScaleType(scaleType);
            button.setPadding(originalPaddingLeft, originalPaddingTop,
                    originalPaddingRight, originalPaddingBottom);
        }
    }

    // --- Inline lyrics ------------------------------------------------------------------------

    private boolean inlineLyricsOpen;
    private long artworkLayerFadeUntil;

    private static final int MINI_COVER_DP = 56;
    private static final int MINI_ROW_DP = 72;

    private boolean inlineControlsHidden;
    private Runnable inlineCloseAction;
    private LinearLayout miniHeader;
    private ImageView miniCover;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView flyingCover;
    private android.graphics.Bitmap miniCoverBitmap;

    private void setInlineOpen(boolean open, Runnable closeAction) {
        inlineCloseAction = open ? closeAction : null;
        if (inlineLyricsOpen == open) return;
        inlineLyricsOpen = open;
        if (backdrop != null) backdrop.setCoverDimmed(open);
        if (animatedArtworkLayer != null) {
            artworkLayerFadeUntil = android.os.SystemClock.elapsedRealtime() + INLINE_TRANSITION_MS + 40L;
            animatedArtworkLayer.animate().cancel();
            animatedArtworkLayer.animate().alpha(open ? 0f : 1f).setDuration(INLINE_TRANSITION_MS).start();
        }
        if (motionView != null && open) motionView.setPlaying(false);
        if (overlay == null) return;
        if (!open) setControlsHidden(false);
        ensureMiniHeader();
        maintainInlineLayout();
        flyCover(open);
        float hiddenAlpha = open ? 0f : 1f;
        fadeTo(header, hiddenAlpha);
        fadeTo(overlay.findViewById(id("track_info_feedback_container")), hiddenAlpha);
        if (miniHeader != null) {
            miniHeader.animate().cancel();
            if (open) {
                miniHeader.setVisibility(View.VISIBLE);
                miniHeader.setTranslationY(dp(10));
            }
            miniHeader.animate().alpha(open ? 1f : 0f).translationY(0f)
                    .setStartDelay(open ? INLINE_TRANSITION_MS / 3 : 0L)
                    .setDuration(open ? INLINE_TRANSITION_MS * 2 / 3 : INLINE_TRANSITION_MS / 2)
                    .withEndAction(() -> {
                        if (!inlineLyricsOpen && miniHeader != null) miniHeader.setVisibility(View.GONE);
                    }).start();
        }
    }

    /** A rebuilt page while the lyrics are open: take the open layout without animating. */
    private void applyInlineLayoutNow() {
        ensureMiniHeader();
        maintainInlineLayout();
        if (header != null) header.setAlpha(0f);
        View info = overlay.findViewById(id("track_info_feedback_container"));
        if (info != null) info.setAlpha(0f);
        if (miniHeader != null) {
            miniHeader.setVisibility(View.VISIBLE);
            miniHeader.setAlpha(1f);
            miniHeader.setTranslationY(0f);
        }
        if (miniCover != null) miniCover.setAlpha(1f);
        if (inlineControlsHidden) {
            inlineControlsHidden = false;
            setControlsHidden(true);
        }
    }

    private void fadeTo(View view, float alpha) {
        if (view == null) return;
        view.animate().cancel();
        view.animate().alpha(alpha).setDuration(INLINE_TRANSITION_MS / 2).start();
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** The small cover row: cover, title over artist, and the "..." Spotify's header had. */
    private void ensureMiniHeader() {
        if (miniHeader != null && miniHeader.getParent() == overlay) return;
        if (!(overlay instanceof FrameLayout)) return;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), 0, dp(20), 0);
        row.setAlpha(0f);
        row.setVisibility(View.GONE);
        // Tapping the cover row goes back to the big cover, as in Apple Music.
        row.setOnClickListener(v -> {
            Runnable close = inlineCloseAction;
            if (close != null) close.run();
        });

        ImageView cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(roundedOutline(dp(10)));
        row.addView(cover, new LinearLayout.LayoutParams(dp(MINI_COVER_DP), dp(MINI_COVER_DP)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMarginStart(dp(14));
        textsLp.setMarginEnd(dp(12));
        TextView title = new TextView(activity);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView artist = new TextView(activity);
        artist.setTextColor(0xB3FFFFFF);
        artist.setTextSize(15f);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(artist);
        row.addView(texts, textsLp);

        if (menuButton != null) {
            android.widget.ImageButton more = new android.widget.ImageButton(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            more.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                    com.eza.spicyex.ui.ActionIconDrawable.Kind.ELLIPSIS, Color.WHITE, density,
                    ACTION_ICON_SIZE_DP));
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x33FFFFFF);
            more.setBackground(circle);
            CharSequence label = menuButton.getContentDescription();
            more.setContentDescription(label != null ? label : "More options");
            more.setOnClickListener(v -> {
                View target = menuButton;
                if (target != null) target.performClick();
            });
            row.addView(more, new LinearLayout.LayoutParams(dp(ACTION_BUTTON_SIZE_DP + 4),
                    dp(ACTION_BUTTON_SIZE_DP + 4)));
        }

        overlay.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MINI_ROW_DP), Gravity.TOP));
        miniHeader = row;
        miniCover = cover;
        miniTitle = title;
        miniArtist = artist;
        miniCoverBitmap = null;
    }

    private static android.view.ViewOutlineProvider roundedOutline(float radius) {
        return new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    /** Where the small cover row sits: in the "Playing from" lines' place, under the handle. */
    private int miniHeaderTop() {
        int top = dp(44);
        if (header != null && header.getHeight() > 0 && overlay != null) {
            int[] headerAt = new int[2];
            int[] overlayAt = new int[2];
            header.getLocationInWindow(headerAt);
            overlay.getLocationInWindow(overlayAt);
            top = headerAt[1] - overlayAt[1];
        }
        return Math.max(dp(28), top);
    }

    /** Keeps the small cover row placed and showing the current song. */
    private void maintainInlineLayout() {
        if (miniHeader == null || overlay == null) return;
        ViewGroup.LayoutParams params = miniHeader.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
            int top = miniHeaderTop();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                miniHeader.setLayoutParams(lp);
            }
        }
        if (miniHeader.getParent() == overlay
                && overlay.indexOfChild(miniHeader) != overlay.getChildCount() - 1
                && (flyingCover == null || flyingCover.getParent() != overlay)) {
            miniHeader.bringToFront();
        }
        View title = overlay.findViewById(id("track_info_view_title"));
        View artist = overlay.findViewById(id("track_info_view_subtitle"));
        if (title instanceof TextView && miniTitle != null) {
            CharSequence text = ((TextView) title).getText();
            if (!android.text.TextUtils.equals(text, miniTitle.getText())) miniTitle.setText(text);
        }
        if (artist instanceof TextView && miniArtist != null) {
            CharSequence text = ((TextView) artist).getText();
            if (!android.text.TextUtils.equals(text, miniArtist.getText())) miniArtist.setText(text);
        }
        android.graphics.Bitmap cover = currentCoverBitmap();
        if (cover != null && cover != miniCoverBitmap && miniCover != null) {
            miniCoverBitmap = cover;
            miniCover.setImageBitmap(cover);
        }
    }

    private android.graphics.Bitmap currentCoverBitmap() {
        ApplePlayerBackdrop.Page page = backdrop == null ? null : backdrop.currentPage();
        return page == null || page.cover == null || page.cover.isRecycled() ? null : page.cover;
    }

    /**
     * The big cover flies into the small one (or back out): a copy of it travels between the
     * banner and the small cover's slot while the backdrop's own cover fades, so the cover is
     * seen moving rather than vanishing in one place and appearing in another.
     */
    private void flyCover(boolean toSmall) {
        android.graphics.Bitmap cover = currentCoverBitmap();
        if (overlay == null || miniHeader == null || cover == null || overlay.getWidth() <= 0) {
            if (miniCover != null) miniCover.setAlpha(toSmall ? 1f : 0f);
            return;
        }
        if (flyingCover != null && flyingCover.getParent() instanceof ViewGroup) {
            flyingCover.animate().cancel();
            ((ViewGroup) flyingCover.getParent()).removeView(flyingCover);
        }
        int bigSide = overlay.getWidth();
        int smallSide = dp(MINI_COVER_DP);
        float smallScale = smallSide / (float) bigSide;
        float smallX = dp(24);
        float smallY = miniHeaderTop() + (dp(MINI_ROW_DP) - smallSide) / 2f;
        ImageView flying = new ImageView(activity);
        flying.setImageBitmap(cover);
        flying.setScaleType(ImageView.ScaleType.CENTER_CROP);
        flying.setPivotX(0f);
        flying.setPivotY(0f);
        flying.setClipToOutline(true);
        float[] radius = {0f};
        flying.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius[0]);
            }
        });
        flying.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.addView(flying, new FrameLayout.LayoutParams(bigSide, bigSide, Gravity.TOP | Gravity.LEFT));
        flyingCover = flying;
        if (miniCover != null) miniCover.setAlpha(0f);
        // In screen units the small cover's corners are 10dp; the view is drawn scaled, so the
        // radius it needs grows as it shrinks.
        float smallRadius = dp(10) / smallScale;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(INLINE_TRANSITION_MS);
        animator.setInterpolator(new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float p = toSmall ? t : 1f - t;
            float scale = 1f + (smallScale - 1f) * p;
            flying.setScaleX(scale);
            flying.setScaleY(scale);
            flying.setTranslationX(smallX * p);
            flying.setTranslationY(smallY * p);
            radius[0] = smallRadius * p;
            flying.invalidateOutline();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (miniCover != null) miniCover.setAlpha(toSmall && inlineLyricsOpen ? 1f : 0f);
                if (flying.getParent() instanceof ViewGroup) ((ViewGroup) flying.getParent()).removeView(flying);
                if (flyingCover == flying) flyingCover = null;
            }
        });
        animator.start();
    }

    /** The views that slide away with the controls: progress and times, transport, footer row. */
    private View[] inlineControlViews() {
        if (overlay == null) return new View[0];
        return new View[]{
                overlay.findViewById(id("track_seekbar")),
                overlay.findViewById(id("playback_controls_container")),
                overlay.findViewById(id("revised_template_overlay_footer")),
        };
    }

    private void setControlsHidden(boolean hidden) {
        if (inlineControlsHidden == hidden || overlay == null) return;
        inlineControlsHidden = hidden;
        View seekbar = overlay.findViewById(id("track_seekbar"));
        float distance = overlay.getHeight();
        if (seekbar != null && seekbar.getHeight() > 0) {
            int[] seekAt = new int[2];
            int[] overlayAt = new int[2];
            seekbar.getLocationInWindow(seekAt);
            overlay.getLocationInWindow(overlayAt);
            distance = overlay.getHeight() - (seekAt[1] - overlayAt[1] - seekbar.getTranslationY());
        }
        android.view.animation.Interpolator ease =
                new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
        for (View view : inlineControlViews()) {
            if (view == null) continue;
            view.animate().cancel();
            view.animate().translationY(hidden ? distance : 0f).alpha(hidden ? 0f : 1f)
                    .setDuration(INLINE_TRANSITION_MS).setInterpolator(ease).start();
        }
        if (playbackBar != null) {
            playbackBar.animate().cancel();
            playbackBar.animate().alpha(hidden ? 0f : 1f)
                    .setDuration(INLINE_TRANSITION_MS / 2).start();
        }
    }

    /** Puts back everything the open lyrics changed, and drops the views they added. */
    private void releaseInlineViews() {
        for (View view : inlineControlViews()) {
            if (view == null) continue;
            view.animate().cancel();
            view.setTranslationY(0f);
            view.setAlpha(1f);
        }
        if (playbackBar != null) playbackBar.setAlpha(1f);
        if (header != null) header.setAlpha(1f);
        if (overlay != null) {
            View info = overlay.findViewById(id("track_info_feedback_container"));
            if (info != null) info.setAlpha(1f);
        }
        if (miniHeader != null && miniHeader.getParent() instanceof ViewGroup) {
            miniHeader.animate().cancel();
            ((ViewGroup) miniHeader.getParent()).removeView(miniHeader);
        }
        if (flyingCover != null && flyingCover.getParent() instanceof ViewGroup) {
            ((ViewGroup) flyingCover.getParent()).removeView(flyingCover);
        }
        miniHeader = null;
        miniCover = null;
        miniTitle = null;
        miniArtist = null;
        flyingCover = null;
        miniCoverBitmap = null;
    }

    /**
     * Top of the controls block, where it rests (a running slide's offset taken out). Spotify
     * hides the progress bar for a moment around playback changes, so it falls back to the
     * transport row and then to a fixed share of the screen rather than giving up.
     */
    private int controlsTopInWindow(int[] overlayAt) {
        for (String name : new String[]{"track_seekbar", "playback_controls_container"}) {
            View view = overlay.findViewById(id(name));
            if (view == null || view.getHeight() <= 0 || view.getVisibility() != View.VISIBLE) continue;
            int[] at = new int[2];
            view.getLocationInWindow(at);
            return at[1] - Math.round(view.getTranslationY());
        }
        return overlayAt[1] + Math.round(overlay.getHeight() * 0.70f);
    }

    private android.graphics.Rect inlineBounds() {
        if (overlay == null || !overlay.isAttachedToWindow() || overlay.getWidth() <= 0) return null;
        int[] overlayAt = new int[2];
        overlay.getLocationInWindow(overlayAt);
        int top = overlayAt[1] + miniHeaderTop() + dp(MINI_ROW_DP) + dp(4);
        int bottom;
        if (inlineControlsHidden) {
            bottom = overlayAt[1] + overlay.getHeight() - dp(24);
        } else {
            bottom = controlsTopInWindow(overlayAt) - dp(4);
        }
        if (bottom - top < dp(160)) return null;
        return new android.graphics.Rect(overlayAt[0], top,
                overlayAt[0] + overlay.getWidth(), bottom);
    }

    private void attachAnimatedArtworkLayer() {
        if (!animatedArtworkEnabled || !(overlay instanceof FrameLayout)) return;
        try {
            FadingArtworkFrame layer = new FadingArtworkFrame(activity);
            MotionArtworkView motion = new MotionArtworkView(activity);
            // Transparent until the clip's first frame, so the still cover underneath is what
            // shows (and what stays) for a track with no clip; never hidden with visibility, which
            // would deny the video its surface.
            motion.setAlpha(0f);
            motion.setListener(new MotionArtworkView.Listener() {
                @Override
                public void onFirstFrame() {
                    if (motionView != motion) return;
                    motion.animate().cancel();
                    // The still cover under it is dropped only once the clip is fully opaque,
                    // so there is no moment with neither, and nothing translucent is left over.
                    motion.animate().alpha(1f).setDuration(600L).withEndAction(() -> {
                        if (motionView == motion && backdrop != null) {
                            backdrop.setCoverReplacedByAnimatedArtwork(true);
                        }
                    }).start();
                }

                @Override
                public void onFailed() {
                    if (motionView == motion) clearMotion();
                }
            });
            layer.addView(motion, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, carousel.getWidth()),
                    Gravity.TOP | Gravity.LEFT);
            overlay.addView(layer, 1, params);
            animatedArtworkLayer = layer;
            motionView = motion;
        } catch (RuntimeException e) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " player motion artwork unavailable: " + e);
            if (motionView != null) motionView.release();
            animatedArtworkLayer = null;
            motionView = null;
        }
    }

    /** Drops the motion clip; the still cover underneath is what shows again. */
    private void clearMotion() {
        if (motionView == null) return;
        motionView.animate().cancel();
        if (backdrop != null) backdrop.setCoverReplacedByAnimatedArtwork(false);
        motionView.setAlpha(0f);
        motionView.setClip(null);
    }

    /** Looks up motion artwork when the track changes; the clip fades in over the still cover. */
    private void syncMotionArtwork() {
        if (motionView == null || hook == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - motionSyncAt < 250L) return;
        motionSyncAt = now;
        com.eza.spicyex.SpotifyTrack track = hook.getCurrentTrackSafely();
        String uri = track == null || track.uri == null ? "" : track.uri;
        if (uri.equals(motionTrackUri)) return;
        motionTrackUri = uri;
        clearMotion();
        if (uri.isEmpty()) return;
        MotionArtworkView target = motionView;
        MotionArtworkFinder.find(uri, track.title, track.artist, track.album, clip -> {
            if (clip == null || motionView != target || !uri.equals(motionTrackUri)) return;
            target.setClip(clip);
        });
    }

    private void updateAnimatedArtwork(ApplePlayerBackdrop.Page page, boolean swiping) {
        if (motionView == null || animatedArtworkLayer == null) return;
        int wantedHeight = Math.max(1,
                stableBannerHeight > 0 ? stableBannerHeight : carousel.getWidth());
        FrameLayout.LayoutParams layerParams =
                (FrameLayout.LayoutParams) animatedArtworkLayer.getLayoutParams();
        if (layerParams.height != wantedHeight) {
            layerParams.height = wantedHeight;
            animatedArtworkLayer.setLayoutParams(layerParams);
        }
        FrameLayout.LayoutParams motionParams = motionView.getLayoutParams()
                instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) motionView.getLayoutParams()
                : new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, carousel.getWidth()),
                        Gravity.TOP | Gravity.LEFT);
        int squareSide = Math.max(1, carousel.getWidth());
        if (motionParams.width != ViewGroup.LayoutParams.MATCH_PARENT
                || motionParams.height != squareSide || motionParams.gravity != (Gravity.TOP | Gravity.LEFT)) {
            motionParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            motionParams.height = squareSide;
            motionParams.gravity = Gravity.TOP | Gravity.LEFT;
            motionView.setLayoutParams(motionParams);
        }
        if (swiping) {
            // The clip belongs to the track being left: dropped now, re-resolved once the
            // carousel settles on the next one.
            animatedArtworkLayer.setAlpha(0f);
            if (motionView.hasClip()) {
                motionTrackUri = "";
                clearMotion();
            }
            return;
        }
        if (!inlineLyricsOpen
                && android.os.SystemClock.elapsedRealtime() >= artworkLayerFadeUntil) {
            animatedArtworkLayer.setAlpha(1f);
        }
        syncMotionArtwork();
        updateAnimatedArtworkPlayback();
    }

    private long animatedPlaybackReadAt;

    private void updateAnimatedArtworkPlayback() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - animatedPlaybackReadAt < 250L) return;
        animatedPlaybackReadAt = now;
        motionView.setPlaying(!inlineLyricsOpen
                && (hook == null || hook.isPlayerActuallyPlaying()));
    }

    private static final class FadingArtworkFrame extends FrameLayout {
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int maskContentHeight = -1;

        FadingArtworkFrame(Activity activity) {
            super(activity);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void updateMask(int contentHeight) {
            int height = Math.max(1, Math.min(getHeight(), contentHeight));
            if (height == maskContentHeight) return;
            maskContentHeight = height;
            mask.setShader(new LinearGradient(0f, 0f, 0f, height,
                    new int[]{Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT},
                    new float[]{0f, 0.12f, 0.58f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            View artwork = getChildCount() == 0 ? null : getChildAt(0);
            int artworkBottom = artwork == null ? getHeight() : artwork.getBottom();
            updateMask(artworkBottom);
            int save = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), mask);
            canvas.restoreToCount(save);
        }
    }

    private static final float PLAY_GLYPH_SCALE = 1.35f;

    private static final class TransportButtonState {
        final Drawable background;
        final ColorStateList backgroundTint;
        final Drawable iconBackground;
        final ColorStateList iconBackgroundTint;
        final ImageView icon;
        final ColorStateList imageTint;
        final android.graphics.ColorFilter imageColorFilter;
        final android.graphics.PorterDuffColorFilter whiteGlyphFilter =
                new android.graphics.PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        final ImageView.ScaleType scaleType;
        final int left, top, right, bottom;
        final View animationLayer;
        final int animationLayerVisibility;
        Drawable circularDrawable;
        Paint circlePaint;
        int circleAlpha;

        TransportButtonState(View button, ImageView icon) {
            this.icon = icon;
            background = button.getBackground();
            backgroundTint = button.getBackgroundTintList();
            iconBackground = icon.getBackground();
            iconBackgroundTint = icon.getBackgroundTintList();
            imageTint = icon.getImageTintList();
            imageColorFilter = icon.getColorFilter();
            scaleType = icon.getScaleType();
            animationLayer = findLottieLayer(button, icon);
            animationLayerVisibility = animationLayer == null
                    ? View.GONE : animationLayer.getVisibility();
            left = button.getPaddingLeft();
            top = button.getPaddingTop();
            right = button.getPaddingRight();
            bottom = button.getPaddingBottom();
        }

        void apply(View button) {
            clearBackground(button);
            clearBackground(icon);
            Drawable current = icon.getDrawable();
            // Spotify's play/pause glyph is a custom drawable that paints its own filled circle
            // behind the glyph. Its obfuscated class name drifts between app versions (p.dxe,
            // p.daf, ...), so match structurally - any non-framework drawable - instead of a
            // hardcoded name, then zero the circle fill so only the white glyph remains.
            if (current != null && !current.getClass().getName().startsWith("android.")) {
                if (current != circularDrawable) {
                    circularDrawable = current;
                    circlePaint = spotifyCirclePaint(current);
                    circleAlpha = circlePaint == null ? 0 : circlePaint.getAlpha();
                }
                if (circlePaint != null && circlePaint.getAlpha() != 0) {
                    circlePaint.setAlpha(0);
                    current.invalidateSelf();
                }
            }
            if (animationLayer != null && animationLayer.getVisibility() != View.GONE) {
                animationLayer.setVisibility(View.GONE);
            }
            if (icon.getImageTintList() != null) {
                icon.setImageTintList(null);
            }
            if (icon.getColorFilter() != whiteGlyphFilter) {
                icon.setColorFilter(whiteGlyphFilter);
            }
            if (icon.getScaleType() != ImageView.ScaleType.FIT_CENTER) {
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            // Reads smaller than the skip buttons beside it: the glyph is scaled up to match.
            if (icon.getScaleX() != PLAY_GLYPH_SCALE) {
                icon.setScaleX(PLAY_GLYPH_SCALE);
                icon.setScaleY(PLAY_GLYPH_SCALE);
            }
        }

        void restore(View button) {
            if (circularDrawable != null && circlePaint != null) {
                circlePaint.setAlpha(circleAlpha);
                circularDrawable.invalidateSelf();
            }
            button.setBackground(background);
            button.setBackgroundTintList(backgroundTint);
            button.setPadding(left, top, right, bottom);
            icon.setBackground(iconBackground);
            icon.setBackgroundTintList(iconBackgroundTint);
            icon.setImageTintList(imageTint);
            icon.setColorFilter(imageColorFilter);
            icon.setScaleType(scaleType);
            icon.setScaleX(1f);
            icon.setScaleY(1f);
            if (animationLayer != null) {
                animationLayer.setVisibility(animationLayerVisibility);
            }
        }

        private static void clearBackground(View view) {
            if (view.getBackground() != null || view.getBackgroundTintList() != null) {
                view.setBackground(null);
                view.setBackgroundTintList(null);
            }
        }
    }

    /** Quiet probe: the drawable's own FILL paint (the filled circle), or null. */
    private static Paint findFillPaint(Drawable drawable) {
        try {
            for (java.lang.reflect.Field field : drawable.getClass().getDeclaredFields()) {
                if (!Paint.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object nested = field.get(drawable);
                if (nested instanceof Paint && ((Paint) nested).getStyle() == Paint.Style.FILL) {
                    return (Paint) nested;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Treated as "no circle paint" by callers.
        }
        return null;
    }

    private static Paint spotifyCirclePaint(Drawable drawable) {
        Paint paint = findFillPaint(drawable);
        if (paint == null) {
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " Spotify play/pause drawable has no circle fill paint");
        }
        return paint;
    }

    private static View findLottieLayer(View root, View icon) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view != root && view != icon
                    && view.getClass().getName().contains("LottieAnimationView")) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
        return null;
    }

    private void styleTransportButton() {
        if (overlay == null) return;
        TransportButtonTarget target = findTransportButton();
        if (target != null && !transportScanLogged) {
            transportScanLogged = true;
            XpLog.log(NativeSpicyLyricsHook.TAG + " player transport target="
                    + (target == null ? "none" : describeView(target.button)
                    + " icon=" + describeView(target.icon)
                    + " drawable=" + (target.icon.getDrawable() == null
                    ? "none" : target.icon.getDrawable().getClass().getName())));
        }
        if (target == null) return;
        TransportButtonState state = transportButtonStates.get(target.button);
        if (state == null) {
            state = new TransportButtonState(target.button, target.icon);
            transportButtonStates.put(target.button, state);
        }
        state.apply(target.button);
    }

    private static final class TransportButtonTarget {
        final View button;
        final ImageView icon;

        TransportButtonTarget(View button, ImageView icon) {
            this.button = button;
            this.icon = icon;
        }
    }

    private TransportButtonTarget findTransportButton() {
        View spotifyControl = ViewIds.findByEntry(overlay,
                "nowplaying_elements_playpause_button");
        if (spotifyControl != null && spotifyControl.getVisibility() == View.VISIBLE) {
            ImageView icon = findTransportIcon(spotifyControl);
            if (icon != null) return new TransportButtonTarget(spotifyControl, icon);
        }
        View named = ViewIds.findByEntry(overlay, "play_pause_button");
        if (named instanceof ImageView && named.getVisibility() == View.VISIBLE) {
            return new TransportButtonTarget(named, (ImageView) named);
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(overlay);
        TransportButtonTarget best = null;
        int bestScore = Integer.MIN_VALUE;
        float density = activity.getResources().getDisplayMetrics().density;
        int minSize = Math.round(48f * density);
        int maxSize = Math.round(112f * density);
        int[] overlayLocation = new int[2];
        overlay.getLocationOnScreen(overlayLocation);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) continue;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
            if (!(view instanceof ImageView) || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
            ImageView image = (ImageView) view;
            View control = view;
            boolean semantic = isTransportDescription(view.getContentDescription());
            String entry = ViewIds.entryName(view).toLowerCase(java.util.Locale.ROOT);
            boolean namedTransport = entry.contains("play") && entry.contains("pause");
            for (View parent = view.getParent() instanceof View
                    ? (View) view.getParent() : null;
                 parent != null && parent != overlay;
                 parent = parent.getParent() instanceof View ? (View) parent.getParent() : null) {
                String parentEntry = ViewIds.entryName(parent).toLowerCase(java.util.Locale.ROOT);
                boolean parentSemantic = isTransportDescription(parent.getContentDescription());
                boolean parentNamed = parentEntry.contains("play") && parentEntry.contains("pause");
                if (parentSemantic || parentNamed || (parent.isClickable()
                        && parent.getWidth() >= minSize && parent.getHeight() >= minSize)) {
                    control = parent;
                }
                semantic |= parentSemantic;
                namedTransport |= parentNamed;
            }
            int[] location = new int[2];
            control.getLocationOnScreen(location);
            float centerX = location[0] - overlayLocation[0] + control.getWidth() * 0.5f;
            float centerY = location[1] - overlayLocation[1] + control.getHeight() * 0.5f;
            float rootCenterX = overlay.getWidth() * 0.5f;
            float rootHeight = Math.max(1f, overlay.getHeight());
            boolean likelyCenterControl = (control.isClickable() || view.isClickable())
                    && control.getWidth() >= minSize && control.getHeight() >= minSize
                    && control.getWidth() <= maxSize && control.getHeight() <= maxSize
                    && Math.abs(centerX - rootCenterX) <= overlay.getWidth() * 0.15f
                    && centerY / rootHeight >= 0.62f && centerY / rootHeight <= 0.9f;
            if (!semantic && !namedTransport && !likelyCenterControl) continue;
            int score = semantic ? 1000 : namedTransport ? 900 : 0;
            if (likelyCenterControl) {
                score += 300 - Math.round(Math.abs(centerY / rootHeight - 0.80f) * 1000f);
            }
            if (score > bestScore) {
                best = new TransportButtonTarget(control, image);
                bestScore = score;
            }
        }
        return best;
    }

    private static ImageView findTransportIcon(View root) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        ImageView fallback = null;
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) continue;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
            if (!(view instanceof ImageView)) continue;
            if (view.getClass().getName().contains("LottieAnimationView")) continue;
            ImageView image = (ImageView) view;
            Drawable drawable = image.getDrawable();
            // Prefer the icon whose drawable is Spotify's custom circular play/pause glyph
            // (non-framework class with a discoverable fill paint), resilient to its obfuscated
            // name changing across app versions; otherwise fall back to the first icon found.
            if (drawable != null && !drawable.getClass().getName().startsWith("android.")
                    && findFillPaint(drawable) != null) {
                return image;
            }
            if (fallback == null) fallback = image;
        }
        return fallback;
    }

    private static boolean isTransportDescription(CharSequence description) {
        if (description == null) return false;
        String value = description.toString().toLowerCase(java.util.Locale.ROOT);
        return value.contains("play") || value.contains("pause")
                || value.contains("resume") || value.contains("재생")
                || value.contains("일시정지") || value.contains("再生")
                || value.contains("一時停止") || value.contains("воспроиз")
                || value.contains("пауза");
    }

    private static String describeView(View view) {
        if (view == null) return "none";
        return view.getClass().getName() + "#" + ViewIds.entryName(view)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " clickable=" + view.isClickable();
    }

    private boolean updateBackdrop() {
        if (backdrop == null) return false;
        int width = carousel.getWidth();
        if (width <= 0) return false;
        ApplePlayerBackdrop.Page first = null;
        ApplePlayerBackdrop.Page second = null;
        for (int i = 0; i < carousel.getChildCount(); i++) {
            View pageView = carousel.getChildAt(i);
            float offset = pageOffset(pageView);
            trackCarouselMotion(pageView, offset);
            if (Math.abs(offset) >= width) continue;
            ApplePlayerBackdrop.Page page = pageFor(pageView);
            if (page == null) continue;
            page.offsetPx = offset;
            if (first == null || Math.abs(offset) < Math.abs(first.offsetPx)) {
                second = first;
                first = page;
            } else if (second == null || Math.abs(offset) < Math.abs(second.offsetPx)) {
                second = page;
            }
        }
        if (first == null) return false;
        if (second != null && Math.abs(first.offsetPx) < centerOffsetTolerance()) second = null;
        if (second == null && Math.abs(first.offsetPx) < centerOffsetTolerance()) {
            int measuredBanner = bannerBottom();
            if (measuredBanner > 0) stableBannerHeight = measuredBanner;
        }
        if (stableBannerHeight > 0) backdrop.setBannerHeight(stableBannerHeight);
        View centered = centredCover();
        if (centered != null) updateGestureExclusion(centered);
        backdrop.setPages(first, second);
        if (!entrancePlayed && Math.abs(first.offsetPx) < centerOffsetTolerance()) {
            entrancePlayed = playEntrance();
        }
        updateAnimatedArtwork(first, second != null
                || Math.abs(first.offsetPx) >= centerOffsetTolerance());
        return true;
    }

    private float pageOffset(View pageView) {
        int[] pageLocation = new int[2];
        int[] backdropLocation = new int[2];
        pageView.getLocationInWindow(pageLocation);
        backdrop.getLocationInWindow(backdropLocation);
        float renderedWidth = pageView.getWidth() * Math.abs(pageView.getScaleX());
        return pageLocation[0] - backdropLocation[0] + renderedWidth * 0.5f
                - backdrop.getWidth() * 0.5f;
    }

    private float centerOffsetTolerance() {
        return Math.max(1f, activity.getResources().getDisplayMetrics().density);
    }

    private void trackCarouselMotion(View pageView, float offset) {
        long now = android.os.SystemClock.elapsedRealtime();
        Float previous = lastCarouselOffsets.put(pageView, offset);
        if (previous == null) return;
        float movement = Math.abs(offset - previous);
        Long lastMotion = lastCarouselMotionAt.get(pageView);
        if (movement < 0.75f) {
            if (lastMotion != null && now - lastMotion > 140L) {
                accumulatedCarouselTravel.remove(pageView);
            }
            return;
        }
        float travel = lastMotion == null || now - lastMotion > 140L
                ? movement : accumulatedCarouselTravel.getOrDefault(pageView, 0f) + movement;
        accumulatedCarouselTravel.put(pageView, travel);
        lastCarouselMotionAt.put(pageView, now);
        if (travel >= ViewConfiguration.get(activity).getScaledTouchSlop()) {
            lastCarouselSwipeAt = now;
        }
    }

    private void updateGestureExclusion(View cover) {
        if (android.os.Build.VERSION.SDK_INT < 29 || carousel == null
                || carousel.getWidth() <= 0 || cover.getHeight() <= 0) return;
        int[] carouselLocation = new int[2];
        int[] coverLocation = new int[2];
        carousel.getLocationInWindow(carouselLocation);
        cover.getLocationInWindow(coverLocation);
        int top = Math.max(0, coverLocation[1] - carouselLocation[1]);
        int bottom = Math.min(carousel.getHeight(), top + cover.getHeight());
        Rect next = new Rect(0, top, carousel.getWidth(), bottom);
        if (next.equals(gestureExclusion)) return;
        gestureExclusion.set(next);
        carousel.setSystemGestureExclusionRects(
                next.isEmpty() ? java.util.Collections.emptyList()
                        : java.util.Collections.singletonList(new Rect(next)));
    }

    private void clearGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && carousel != null) {
            carousel.setSystemGestureExclusionRects(java.util.Collections.emptyList());
        }
        gestureExclusion.setEmpty();
    }

    // --- Header, title, lyrics line ------------------------------------------------------------

    /** Positions the drag handle between the status bar and the player's contextual header. */
    private void placeTop() {
        if (header == null || header.getHeight() <= 0) return;
        int[] at = new int[2];
        int[] origin = new int[2];
        header.getLocationInWindow(at);
        backdrop.getLocationInWindow(origin);
        int headerTop = at[1] - origin[1];
        int statusBottom = 0;
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            statusBottom = android.os.Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                    : insets.getSystemWindowInsetTop();
        }
        statusBottom -= origin[1];
        float density = activity.getResources().getDisplayMetrics().density;
        float handleY = statusBottom > 0 && headerTop - statusBottom > 6 * density
                ? (statusBottom + headerTop) / 2f : headerTop + 4 * density;
        backdrop.setHandleCenterY(handleY);
        backdrop.setTopBlurZone(Math.max(0, Math.round(statusBottom * 0.6f)));
        View title = header.findViewById(id("context_header_title"));
        View subtitle = header.findViewById(id("context_header_subtitle"));
        if (title instanceof TextView) {
            // Retry promptly during player startup; once available, refresh once a second.
            long now = android.os.SystemClock.elapsedRealtime();
            if (albumName == null || now - albumReadAt >= 250L) {
                com.eza.spicyex.SpotifyTrack track = hook == null ? null : hook.getCurrentTrackSafely();
                String nextUri = track == null || track.uri == null ? "" : track.uri;
                if (!nextUri.equals(albumTrackUri)) {
                    albumTrackUri = nextUri;
                    albumName = null;
                }
                if (track != null && track.album != null && !track.album.isEmpty()) {
                    albumName = track.album;
                } else if (albumName == null) {
                    albumName = "";
                }
                albumReadAt = now;
            }
            String name = albumName;
            if (name.isEmpty() || "null".equals(name)) {
                name = subtitle instanceof TextView ? String.valueOf(((TextView) subtitle).getText()) : "";
            }
            if (name.isEmpty() || "null".equals(name)) return;
            String wanted = (playingFrom() + " " + name).trim();
            TextView titleView = (TextView) title;
            if (!wanted.contentEquals(titleView.getText())) {
                titleView.setText(wanted);
                titleView.setSingleLine(true);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            if (subtitle != null && subtitle.getVisibility() != View.GONE) subtitle.setVisibility(View.GONE);
        }
    }

    private String playingFromText;

    /** Spotify's own "Playing from", in the user's language. */
    private String playingFrom() {
        if (playingFromText == null) {
            int res = activity.getResources().getIdentifier(
                    "context_type_description_activity", "string", activity.getPackageName());
            String text = "";
            try {
                if (res != 0) text = activity.getString(res);
            } catch (Throwable ignored) {
            }
            playingFromText = text.isEmpty() ? "Playing from" : text;
        }
        return playingFromText;
    }

    private static final float TITLE_SCALE = 1.4f;
    private static final float ARTIST_SCALE = 1.3f;
    private final WeakHashMap<TextView, Float> baseSizes = new WeakHashMap<>();

    /**
     * Title and artist as large as Apple's player sets them, and the lyrics line under them
     * rather than above - the order of the image the player follows: title, line, time.
     */
    private void styleTrackInfo() {
        View feedback = overlay.findViewById(id("track_info_feedback_container"));
        if (feedback == null) return;
        Float originalTranslation = trackInfoTranslations.get(feedback);
        if (originalTranslation == null) {
            originalTranslation = feedback.getTranslationY();
            trackInfoTranslations.put(feedback, originalTranslation);
        }

        float raisedTranslation = originalTranslation
                - 16f * activity.getResources().getDisplayMetrics().density;
        if (Math.abs(feedback.getTranslationY() - raisedTranslation) > 0.5f) {
            feedback.setTranslationY(raisedTranslation);
        }
        View title = feedback.findViewById(id("track_info_view_title"));
        View subtitle = feedback.findViewById(id("track_info_view_subtitle"));
        keepTrackTextOnOneLine(title);
        keepTrackTextOnOneLine(subtitle);
        scaleText(title, TITLE_SCALE, true);
        scaleText(subtitle, ARTIST_SCALE, false);
        if (!(feedback.getParent() instanceof ViewGroup)) return;
        // Checked on every pass, not latched: Spotify rebuilds the footer around the first open
        // and the injected lyrics card can arrive after this runs, either of which would
        // otherwise leave the lyric line above the title.
        ViewGroup footer = (ViewGroup) feedback.getParent();
        for (int i = 0; i < footer.getChildCount(); i++) {
            View child = footer.getChildAt(i);
            if (!(child instanceof com.eza.spicyex.lyrics.LiveLyricCardView)) continue;
            if (i < footer.indexOfChild(feedback)) {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                footer.removeView(child);
                footer.addView(child, footer.indexOfChild(feedback) + 1, params);
                XpLog.log(NativeSpicyLyricsHook.TAG + " apple player: lyrics line moved under the title");
            }
            break;
        }
    }

    private void allowPlayerChromeOverflow() {
        if (overlay == null) return;
        if (header != null) {
            allowChromeOverflowFrom(header.findViewById(id("context_header_title")));
            allowChromeOverflowFrom(header.findViewById(id("context_header_subtitle")));
        }
        allowChromeOverflowFrom(overlay.findViewById(id("track_info_feedback_container")));
        allowChromeOverflowFrom(overlay.findViewById(id("feedback_buttons_container")));
    }

    private void allowChromeOverflowFrom(View view) {
        for (View current = view; current != null; ) {
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                if (!playerChromeClipping.containsKey(group)) {
                    playerChromeClipping.put(group, new ViewGroupClippingState(group));
                }
                if (group.getClipChildren()) group.setClipChildren(false);
                if (group.getClipToPadding()) group.setClipToPadding(false);
            }
            if (current == overlay) return;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    private void keepTrackTextOnOneLine(View view) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        TrackTextLayoutState state = trackTextLayouts.get(text);
        if (state == null) {
            state = new TrackTextLayoutState(text);
            trackTextLayouts.put(text, state);
        }
        if (!text.isSingleLine()) text.setSingleLine(true);
        if (text.getEllipsize() != android.text.TextUtils.TruncateAt.END) {
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private void scaleText(View view, float scale, boolean bold) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        Float base = baseSizes.get(text);
        if (base == null) {
            base = text.getTextSize();
            baseSizes.put(text, base);
        }
        float wanted = base * scale;
        if (Math.abs(text.getTextSize() - wanted) > 0.5f) {
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, wanted);
            if (bold) text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        }
    }

    private static final class TrackTextLayoutState {
        private final boolean singleLine;
        private final int maxLines;
        private final android.text.TextUtils.TruncateAt ellipsize;

        TrackTextLayoutState(TextView text) {
            singleLine = text.isSingleLine();
            maxLines = text.getMaxLines();
            ellipsize = text.getEllipsize();
        }

        void restore(TextView text) {
            text.setSingleLine(singleLine);
            if (!singleLine && maxLines > 0) text.setMaxLines(maxLines);
            text.setEllipsize(ellipsize);
        }
    }

    private static final class ViewGroupClippingState {
        private final boolean clipChildren;
        private final boolean clipToPadding;

        ViewGroupClippingState(ViewGroup group) {
            clipChildren = group.getClipChildren();
            clipToPadding = group.getClipToPadding();
        }

        void restore(ViewGroup group) {
            group.setClipChildren(clipChildren);
            group.setClipToPadding(clipToPadding);
        }
    }

    /** Each time the player comes to the front - not on a restyle after Spotify rebuilt it. */
    private boolean entrancePlayed;

    /** Starts the backdrop entrance once Spotify's centred cover page is laid out. */
    private boolean playEntrance() {
        View cover = centredCover();
        if (cover == null || cover.getWidth() <= 0 || cover.getHeight() <= 0) return false;
        backdrop.playEntrance();
        return true;
    }

    private View centredCover() {
        for (int i = 0; i < carousel.getChildCount(); i++) {
            View pageView = carousel.getChildAt(i);
            if (Math.abs(pageOffset(pageView)) < centerOffsetTolerance()) return coverOf.get(pageView);
        }
        return null;
    }

    /**
     * Where the banner ends, in the backdrop's coordinates: halfway between the bottom of the
     * square cover it replaces and the title block under it - BitChord's "sleeve bottom plus half
     * the gap", so the title and controls keep exactly the place they had.
     */
    private int bannerBottom() {
        View cover = null;
        for (int i = 0; i < carousel.getChildCount(); i++) {
            View pageView = carousel.getChildAt(i);
            if (Math.abs(pageOffset(pageView)) < centerOffsetTolerance()) {
                cover = coverOf.get(pageView);
                break;
            }
        }
        if (cover == null || cover.getHeight() <= 0) return 0;
        int[] at = new int[2];
        int[] origin = new int[2];
        cover.getLocationInWindow(at);
        backdrop.getLocationInWindow(origin);
        int coverBottom = at[1] - origin[1] + cover.getHeight();
        View footer = overlay.findViewById(id("player_overlay_footer"));
        if (footer != null && footer.getHeight() > 0) {
            footer.getLocationInWindow(at);
            int footerTop = at[1] - origin[1];
            if (footerTop > coverBottom) return (coverBottom + footerTop) / 2;
        }
        return coverBottom;
    }

    /** The cover bitmaps of one carousel page, made once per image Spotify shows in it. */
    private ApplePlayerBackdrop.Page pageFor(View pageView) {
        ImageView cover = coverOf.get(pageView);
        if (cover == null || cover.getParent() == null) {
            View found = pageView.findViewById(id("image"));
            if (!(found instanceof ImageView)) return null;
            cover = (ImageView) found;
            coverOf.put(pageView, cover);
        }
        Drawable drawable = cover.getDrawable();
        if (drawable == null) return null;
        ApplePlayerBackdrop.Page page = pageCache.get(drawable);
        if (page != null) return page;
        Bitmap bitmap = bitmapOf(drawable, cover);
        if (bitmap == null) return null;
        ApplePlayerBackdrop.Page previous = lastGoodPage.get(cover);
        if (previous != null && !(drawable instanceof BitmapDrawable)
                && isPlaceholderGray(ApplePlayerBackdrop.deepColorOf(bitmap))) {
            // Spotify puts a grey placeholder drawable on the cover while it updates; painting
            // from it turned the lower backdrop grey. Keep the real cover's page until the
            // real image is back.
            pageCache.put(drawable, previous);
            return previous;
        }
        page = new ApplePlayerBackdrop.Page();
        page.cover = bitmap;
        page.blur = ApplePlayerBackdrop.blurOf(bitmap);
        page.mesh = ApplePlayerBackdrop.meshOf(bitmap, drawable.hashCode());
        page.topLuminance = ApplePlayerBackdrop.topLuminanceOf(bitmap);
        page.deepColor = ApplePlayerBackdrop.deepColorOf(bitmap);
        page.washColor = ApplePlayerBackdrop.washColorOf(bitmap);
        page.accentColor = ApplePlayerBackdrop.accentColorOf(page.deepColor);
        page.elevatedColor = ApplePlayerBackdrop.elevatedColorOf(page.washColor);
        page.handleColor = ApplePlayerBackdrop.handleColorOf(page.blur);
        XpLog.log(NativeSpicyLyricsHook.TAG + " player artwork palette hooked drawable="
                + drawable.getClass().getName() + " base=#"
                + Integer.toHexString(page.deepColor) + " wash=#"
                + Integer.toHexString(page.washColor));
        pageCache.put(drawable, page);
        if (!isPlaceholderGray(page.deepColor)) lastGoodPage.put(cover, page);
        return page;
    }

    private final WeakHashMap<ImageView, ApplePlayerBackdrop.Page> lastGoodPage = new WeakHashMap<>();

    /** The near-neutral dark grey of Spotify's cover placeholder (no hue to speak of). */
    private static boolean isPlaceholderGray(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 8;
    }

    private void attachPlaybackBar() {
        if (playbackBar != null || overlay == null) return;
        playbackBar = new ThinPlaybackBar(activity);
        playbackBar.setSeekListener(fraction -> {
            long target = Math.round(playbackDurationMs * fraction);
            if (!hook.seekSpotifyTo(target)) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " now-playing scrub failed at " + target + "ms");
            }
        });
        int height = Math.round(34f * activity.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.TOP | Gravity.LEFT);
        overlay.addView(playbackBar, params);
        updatePlaybackBarBounds();
    }

    private void updatePlaybackBar() {
        if (!styleScreen || hook == null) return;
        if (playbackBar == null) attachPlaybackBar();
        if (playbackBar == null) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - playbackBoundsReadAt >= 100L) {
            playbackBoundsReadAt = now;
            updatePlaybackBarBounds();
        }
        if (now - playbackReadAt < 50L) return;
        playbackReadAt = now;
        com.eza.spicyex.SpotifyTrack track = hook.getCurrentTrackSafely();
        if (track == null || track.duration <= 0L) {
            playbackDurationMs = 0L;
            playbackBar.setProgress(0f);
            playbackBar.setSeekable(false);
            playbackBar.setVisibility(View.INVISIBLE);
            clearPlaybackClocks();
            return;
        }
        playbackBar.setVisibility(View.VISIBLE);
        playbackDurationMs = track.duration;
        long position = hook.readBestMeasuredProgressMs(track, hook.isPlayerActuallyPlaying());
        if (position < 0L) position = track.position;
        if (position < 0L) position = 0L;
        position = Math.min(position, track.duration);
        playbackBar.setProgress(track.duration > 0L
                ? position / (float) track.duration : 0f);
        String trackUri = track.uri == null ? "" : track.uri;
        boolean newTrack = !trackUri.equals(clockTrackUri);
        clockTrackUri = trackUri;
        updatePlaybackClocks(position, track.duration, !newTrack);
        if (now - seekCapabilityReadAt >= 400L) {
            seekCapabilityReadAt = now;
            canSeekPlayback = hook.canSeek();
        }
        playbackBar.setSeekable(canSeekPlayback);
    }

    private void updatePlaybackBarBounds() {
        if (playbackBar == null || overlay == null || overlay.getWidth() <= 0) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int touchHeight = Math.round(34f * density);
        View positionView = overlay.findViewById(id("position_text"));
        View durationView = overlay.findViewById(id("duration_text"));
        int[] overlayLocation = new int[2];
        overlay.getLocationInWindow(overlayLocation);
        if (nativeProgress != null && nativeProgress.getParent() == null) {
            nativeProgress = null;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        View progressView = nativeProgress;
        if (progressView == null && now - progressScanAt >= 300L) {
            progressScanAt = now;
            progressView = findNativeProgressView(positionView);
        }
        int left = Math.round(16f * density);
        int width = Math.max(1, overlay.getWidth() - left * 2);
        int centerY = -1;
        if (progressView != null) {
            if (nativeProgress != progressView) {
                if (nativeProgress != null && nativeProgress.getVisibility() == View.INVISIBLE) {
                    nativeProgress.setVisibility(nativeProgressVisibility);
                }
                nativeProgress = progressView;
                nativeProgressVisibility = progressView.getVisibility();
                nativeProgress.setVisibility(View.INVISIBLE);
            }
            int[] location = new int[2];
            progressView.getLocationInWindow(location);
            left = location[0] - overlayLocation[0];
            width = progressView.getWidth();
            centerY = location[1] - overlayLocation[1] + progressView.getHeight() / 2;
        } else if (nativeProgress != null) {
            if (nativeProgress.getVisibility() == View.INVISIBLE) {
                nativeProgress.setVisibility(nativeProgressVisibility);
            }
            nativeProgress = null;
        }

        if (centerY < 0 && positionView != null && positionView.getHeight() > 0) {
            int[] location = new int[2];
            positionView.getLocationInWindow(location);
            int positionTop = location[1] - overlayLocation[1];
            centerY = positionTop - Math.round(5f * density);
            left = Math.max(0, location[0] - overlayLocation[0] - Math.round(10f * density));
            if (durationView != null && durationView.getWidth() > 0) {
                int[] durationLocation = new int[2];
                durationView.getLocationInWindow(durationLocation);
                int right = durationLocation[0] - overlayLocation[0] + durationView.getWidth()
                        + Math.round(10f * density);
                width = Math.max(1, right - left);
            } else {
                width = Math.max(1, overlay.getWidth() - left - Math.round(16f * density));
            }
        }
        if (centerY < 0) {
            View footer = overlay.findViewById(id("player_overlay_footer"));
            if (footer == null || footer.getHeight() <= 0) return;
            int[] location = new int[2];
            footer.getLocationInWindow(location);
            centerY = location[1] - overlayLocation[1] + Math.round(18f * density);
        }

        int edgeInset = Math.min(Math.round(24f * density), overlay.getWidth() / 2);
        int maxRight = Math.max(1, overlay.getWidth() - edgeInset);
        left = Math.max(edgeInset, Math.min(left, maxRight - 1));
        width = Math.max(1, Math.min(left + width, maxRight) - left);

        FrameLayout.LayoutParams existing = playbackBar.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) playbackBar.getLayoutParams() : null;
        FrameLayout.LayoutParams params = existing != null ? existing
                : new FrameLayout.LayoutParams(width, touchHeight, Gravity.TOP | Gravity.LEFT);
        int wantedWidth = Math.max(1, width);
        int wantedTop = Math.max(0, centerY - touchHeight / 2);
        if (existing == null || params.width != wantedWidth || params.height != touchHeight
                || params.leftMargin != Math.max(0, left) || params.topMargin != wantedTop
                || (params.gravity & (Gravity.TOP | Gravity.LEFT)) != (Gravity.TOP | Gravity.LEFT)) {
            params.width = wantedWidth;
            params.height = touchHeight;
            params.leftMargin = Math.max(0, left);
            params.topMargin = wantedTop;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            playbackBar.setLayoutParams(params);
        }
    }

    private View findNativeProgressView(View positionView) {
        if (overlay == null) return null;
        int timeTop = Integer.MAX_VALUE;
        int[] location = new int[2];
        if (positionView != null && positionView.getHeight() > 0) {
            positionView.getLocationInWindow(location);
            timeTop = location[1];
        }
        overlay.getLocationInWindow(location);
        int overlayTop = location[1];
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(overlay);
        View best = null;
        long bestScore = Long.MAX_VALUE;
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view != playbackBar && (view.getVisibility() == View.VISIBLE || view == nativeProgress)
                    && view.getAlpha() > 0.01f
                    && view.getWidth() >= overlay.getWidth() * 0.45f && view.getHeight() > 0) {
                boolean progressWidget = view instanceof ProgressBar
                        && ((ProgressBar) view).getMax() > 0;
                String name = ViewIds.entryName(view).toLowerCase(java.util.Locale.ROOT);
                boolean progressNamed = name.contains("progress") || name.contains("seek");
                if (progressWidget || progressNamed) {
                    view.getLocationInWindow(location);
                    int top = location[1];
                    int bottom = top + view.getHeight();
                    if (timeTop == Integer.MAX_VALUE || (top < timeTop + Math.round(8f * density())
                            && bottom > timeTop - Math.round(110f * density()))) {
                        int target = timeTop == Integer.MAX_VALUE
                                ? overlayTop + overlay.getHeight() / 2
                                : timeTop - Math.round(12f * density());
                        long score = Math.abs((long) (top + view.getHeight() / 2) - target)
                                - (progressWidget ? 1_000_000L : 0L);
                        if (score < bestScore) {
                            best = view;
                            bestScore = score;
                        }
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
            }
        }
        return best;
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private Bitmap bitmapOf(Drawable drawable, ImageView view) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                int[] crop = centerCropSourceBounds(bitmap.getWidth(), bitmap.getHeight());
                if (crop == null) return null;
                if (crop[0] == 0 && crop[1] == 0
                        && crop[2] == bitmap.getWidth() && crop[3] == bitmap.getHeight()) return bitmap;
                return Bitmap.createBitmap(bitmap, crop[0], crop[1],
                        crop[2] - crop[0], crop[3] - crop[1]);
            }
        }
        int size = Math.max(view.getWidth(), view.getHeight());
        if (size <= 0) size = drawable.getIntrinsicWidth();
        if (size <= 0) return null;
        size = Math.min(size, 1080);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        // Preserve non-bitmap drawable aspect ratios and centre-crop just as the motion clip
        // does. Stretching an animated placeholder or vector here made the backdrop palette and
        // the replacement video disagree with the square artwork frame.
        savedBounds.set(drawable.getBounds());
        int sourceWidth = drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth() : Math.max(1, view.getWidth());
        int sourceHeight = drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight() : Math.max(1, view.getHeight());
        float aspect = sourceWidth / (float) sourceHeight;
        int drawWidth = aspect >= 1f ? Math.round(size * aspect) : size;
        int drawHeight = aspect >= 1f ? size : Math.round(size / aspect);
        int left = (size - drawWidth) / 2;
        int top = (size - drawHeight) / 2;
        int bleedX = Math.round(drawWidth * 0.02f);
        int bleedY = Math.round(drawHeight * 0.02f);
        try {
            drawable.setBounds(left - bleedX, top - bleedY,
                    left + drawWidth + bleedX, top + drawHeight + bleedY);
            drawable.draw(canvas);
        } finally {
            drawable.setBounds(savedBounds);
        }
        return out;
    }

    static int[] centerCropSourceBounds(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        if (width > height) {
            int side = height;
            int left = (width - side) / 2;
            return new int[]{left, 0, left + side, height};
        }
        int side = width;
        int top = (height - side) / 2;
        return new int[]{0, top, width, top + side};
    }

    // --- Times -------------------------------------------------------------------------------

    private void attachClocks() {
        View positionView = overlay.findViewById(id("position_text"));
        View durationView = overlay.findViewById(id("duration_text"));
        if (!(positionView instanceof TextView) || !(durationView instanceof TextView)) return;
        position = (TextView) positionView;
        duration = (TextView) durationView;
        int color = position.getCurrentTextColor();
        positionClock = new RollingTimeDrawable(position.getTextSize(), position.getTypeface(), color, false);
        durationClock = new RollingTimeDrawable(duration.getTextSize(), duration.getTypeface(),
                duration.getCurrentTextColor(), true);
        position.setForeground(positionClock);
        duration.setForeground(durationClock);
        // The labels keep their text (layout, accessibility); the clocks draw it.
        position.setTextColor(Color.TRANSPARENT);
        duration.setTextColor(Color.TRANSPARENT);
        // The remaining time is a character wider than the duration was, and these labels never
        // lay out again (Spotify suppresses their layout so a ticking clock doesn't re-measure the
        // screen): the right-aligned clock draws leftward past its label instead.
        if (duration.getParent() instanceof ViewGroup) ((ViewGroup) duration.getParent()).setClipChildren(false);
        durationSeconds = parseClock(String.valueOf(duration.getText()));
        position.addTextChangedListener(new Watcher(true));
        duration.addTextChangedListener(new Watcher(false));
        onClockText(true, false);
    }

    private String seenPosition = "";
    private String seenDuration = "";

    /**
     * Once per frame: Spotify's labels do not always report their updates to a TextWatcher, so
     * the texts are compared directly - two short strings, nothing else.
     */
    private void pollClocks() {
        CharSequence p = position.getText();
        CharSequence d = duration.getText();
        boolean durationChanged = !seenDuration.contentEquals(d);
        boolean positionChanged = !seenPosition.contentEquals(p);
        if (!durationChanged && !positionChanged) return;
        seenPosition = String.valueOf(p);
        seenDuration = String.valueOf(d);
        if (durationChanged) onClockText(false, true);
        else onClockText(true, true);
        seenDuration = String.valueOf(duration.getText());
    }

    private final class Watcher implements TextWatcher {
        private final boolean isPosition;

        Watcher(boolean isPosition) {
            this.isPosition = isPosition;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            onClockText(isPosition, true);
        }
    }

    private void onClockText(boolean fromPosition, boolean animate) {
        if (writingDuration || position == null) return;
        if (!fromPosition) {
            // Spotify set the duration (a new track): remember it, then show the remaining time.
            int parsed = parseClock(String.valueOf(duration.getText()));
            if (parsed >= 0) durationSeconds = parsed;
        }
        // Spotify sets the label colours again on some updates; keep the drawn text in step.
        if (position.getCurrentTextColor() != Color.TRANSPARENT) {
            positionClock.setColor(position.getCurrentTextColor());
            position.setTextColor(Color.TRANSPARENT);
        }
        if (duration.getCurrentTextColor() != Color.TRANSPARENT) {
            durationClock.setColor(duration.getCurrentTextColor());
            duration.setTextColor(Color.TRANSPARENT);
        }
        String elapsedText = String.valueOf(position.getText());
        int elapsed = parseClock(elapsedText);
        if (elapsed < 0) return;
        boolean forward = elapsed >= lastPositionSeconds;
        lastPositionSeconds = elapsed;
        positionClock.setText(elapsedText, forward, animate);
        if (durationSeconds >= 0 && elapsed >= 0) {
            String remaining = "-" + clock(Math.max(0, durationSeconds - elapsed));
            durationClock.setText(remaining, !forward, animate);
            if (!remaining.contentEquals(duration.getText())) {
                writingDuration = true;
                try {
                    duration.setText(remaining);
                } finally {
                    writingDuration = false;
                }
            }
        } else {
            durationClock.setText(String.valueOf(duration.getText()), true, false);
        }
    }

    private void updatePlaybackClocks(long positionMs, long durationMs, boolean animate) {
        if (positionClock == null || durationClock == null || position == null || duration == null) return;
        long boundedPosition = Math.max(0L, Math.min(positionMs, durationMs));
        int elapsedSeconds = (int) Math.min(Integer.MAX_VALUE, boundedPosition / 1000L);
        durationSeconds = (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
        String elapsedText = clock(elapsedSeconds);
        String remainingText = "-" + clock(Math.max(0, durationSeconds - elapsedSeconds));
        boolean forward = lastPositionSeconds < 0 || elapsedSeconds >= lastPositionSeconds;
        boolean animateChange = animate
                && isContinuousClockTransition(lastPositionSeconds, elapsedSeconds);
        writingDuration = true;
        try {
            if (!elapsedText.contentEquals(position.getText())) position.setText(elapsedText);
            if (!remainingText.contentEquals(duration.getText())) duration.setText(remainingText);
        } finally {
            writingDuration = false;
        }
        if (!elapsedText.equals(positionClock.text())) {
            positionClock.setText(elapsedText, forward, animateChange);
        }
        if (!remainingText.equals(durationClock.text())) {
            durationClock.setText(remainingText, !forward, animateChange);
        }
        lastPositionSeconds = elapsedSeconds;
        seenPosition = elapsedText;
        seenDuration = remainingText;
    }

    static boolean isContinuousClockTransition(int previousSeconds, int nextSeconds) {
        return previousSeconds < 0 || Math.abs((long) nextSeconds - previousSeconds) <= 2L;
    }

    private void clearPlaybackClocks() {
        if (positionClock == null || durationClock == null || position == null || duration == null) return;
        positionClock.setText("", true, false);
        durationClock.setText("", true, false);
        writingDuration = true;
        try {
            if (position.length() > 0) position.setText("");
            if (duration.length() > 0) duration.setText("");
        } finally {
            writingDuration = false;
        }
        durationSeconds = -1;
        lastPositionSeconds = -1;
        seenPosition = "";
        seenDuration = "";
        clockTrackUri = "";
    }

    static int parseClock(String text) {
        if (text == null) return -1;
        String value = text.trim();
        if (value.startsWith("-")) return -1;
        String[] parts = value.split(":");
        if (parts.length < 2 || parts.length > 3) return -1;
        try {
            int total = 0;
            for (String part : parts) {
                if (part.isEmpty()) return -1;
                total = total * 60 + Integer.parseInt(part);
            }
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String clock(int seconds) {
        int h = seconds / 3600;
        int m = (seconds / 60) % 60;
        int s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }
}
