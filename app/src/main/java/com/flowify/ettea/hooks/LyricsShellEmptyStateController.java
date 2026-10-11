package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsSkeletonView;
import com.eza.spicyex.lyrics.LyricsTextFactory;

/** Builds transient loading and error rows for the fullscreen lyric surface. */
final class LyricsShellEmptyStateController {
    private static final long ERROR_DISPLAY_MS = 10_000L;
    private final Activity activity;
    private final SpotifyPlusConfig config;
    private final LyricsTextFactory textFactory;
    private int stateToken;

    LyricsShellEmptyStateController(
            Activity activity,
            SpotifyPlusConfig config,
            LyricsTextFactory textFactory
    ) {
        this.activity = activity;
        this.config = config;
        this.textFactory = textFactory;
    }

    /** Ads carry no lyrics: a compact card with an "AD" badge in their place, saying that
     *  lyrics come back after it. */
    void showAdState(ScrollView lyricsScroll, LinearLayout lyricsColumn) {
        stateToken++;
        lyricsColumn.removeAllViews();
        com.eza.spicyex.ui.SettingsUiStrings strings = com.eza.spicyex.ui.UiLanguage.strings(activity,
                config.get(Settings.UI_LANGUAGE));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setAlpha(0f);
        card.setTranslationY(dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x1A000000);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x26FFFFFF);
        card.setBackground(cardBg);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView badge = textFactory.createText(activity, "AD", 13, Color.rgb(20, 20, 24),
                textFactory.resolveTypeface(true));
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.12f);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.rgb(255, 205, 80));
        badgeBg.setCornerRadius(dp(8));
        badge.setBackground(badgeBg);
        card.addView(badge, new LinearLayout.LayoutParams(dp(46), dp(26)));

        TextView label = textFactory.createText(activity,
                strings.get("lyrics_ad_title", "Advertisement"), 20, Color.WHITE,
                textFactory.resolveTypeface(true));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, 0);
        card.addView(label);

        TextView hint = textFactory.createText(activity,
                strings.get("lyrics_ad_resume", "Lyrics resume after the ad"), 13,
                Color.rgb(190, 190, 200), textFactory.resolveTypeface(false));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(96);
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        lyricsColumn.addView(card, cardLp);
        card.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        // A slow breath on the badge; runs only while the card is on screen.
        ValueAnimator pulse = ValueAnimator.ofFloat(0.72f, 1f);
        pulse.setDuration(1300);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> badge.setAlpha((float) a.getAnimatedValue()));
        badge.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                pulse.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                pulse.cancel();
            }
        });
        if (badge.isAttachedToWindow()) pulse.start();
        lyricsScroll.post(() -> lyricsScroll.scrollTo(0, 0));
    }

    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message) {
        showLoading(lyricsScroll, lyricsColumn, message, 0.5f, 0);
    }

    /**
     * @param anchorFraction where the current line rests (the focus position), so the skeleton
     *                       starts where the lyrics will rather than always at the middle
     * @param sideInsetPx    the side margin the lyric rows keep
     */
    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message,
                     float anchorFraction, int sideInsetPx) {
        stateToken++;
        lyricsColumn.removeAllViews();
        if (config.get(Settings.SHOW_SKELETON)) {
            LyricsSkeletonView skeleton = new LyricsSkeletonView(activity);
            skeleton.setHorizontalInsetPx(sideInsetPx);
            LinearLayout.LayoutParams skeletonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            skeletonLp.topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop(), anchorFraction);
            lyricsColumn.addView(skeleton, skeletonLp);
            alignLoadingStart(lyricsScroll, lyricsColumn, skeleton, anchorFraction);
            skeleton.setAlpha(1f);
            return;
        }
        TextView loading = textFactory.createText(
                activity,
                message,
                22,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(true));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(16), dp(100), dp(16), dp(16));
        lyricsColumn.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        loading.setAlpha(1f);
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView,
            float anchorFraction
    ) {
        Runnable align = () -> {
            if (loadingView.getParent() != lyricsColumn) return;
            ViewGroup.LayoutParams rawParams = loadingView.getLayoutParams();
            if (!(rawParams instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) rawParams;
            int topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop(), anchorFraction);
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin;
                loadingView.setLayoutParams(params);
            }
            // A song change can leave the prior document's scroll offset in place. Reset both now
            // and after layout, when ScrollView has recalculated the shorter loading content range.
            lyricsScroll.scrollTo(0, 0);
        };
        align.run();
        lyricsScroll.post(align);
    }

    static int loadingTopMargin(int viewportHeightPx, int paddingTopPx) {
        return loadingTopMargin(viewportHeightPx, paddingTopPx, 0.5f);
    }

    /** Top margin that starts the loading placeholder at the focus position. */
    static int loadingTopMargin(int viewportHeightPx, int paddingTopPx, float anchorFraction) {
        if (viewportHeightPx <= 0) return 0;
        float fraction = Float.isNaN(anchorFraction) ? 0.5f
                : Math.max(0f, Math.min(1f, anchorFraction));
        return Math.max(0, Math.round(viewportHeightPx * fraction) - Math.max(0, paddingTopPx));
    }

    /**
     * An instrumental track: a quiet note and its label in place of "No lyrics found", so a
     * deliberate no-lyrics track does not read as a lookup failure.
     */
    void showInstrumental(LinearLayout lyricsColumn) {
        ++stateToken;
        lyricsColumn.removeAllViews();
        com.eza.spicyex.ui.SettingsUiStrings strings = com.eza.spicyex.ui.UiLanguage.strings(activity,
                config.get(Settings.UI_LANGUAGE));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView note = textFactory.createText(activity, "♫", 56, Color.WHITE,
                textFactory.resolveTypeface(true));
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(16), dp(72), dp(16), dp(4));
        box.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = textFactory.createText(activity,
                strings.get("lyrics_instrumental", "Instrumental"), 22, Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setAlpha(0.85f);
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // A slow breath on the note, so the screen reads as music playing rather than an error.
        ValueAnimator breathe = ValueAnimator.ofFloat(0.55f, 1f);
        breathe.setDuration(1600);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setInterpolator(new AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(a -> note.setAlpha((float) a.getAnimatedValue()));
        note.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                breathe.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                breathe.cancel();
            }
        });
        if (note.isAttachedToWindow()) breathe.start();
    }

    void showError(LinearLayout lyricsColumn, String error) {
        final int token = ++stateToken;
        lyricsColumn.removeAllViews();
        LinearLayout errorBox = new LinearLayout(activity);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = textFactory.createText(
                activity,
                "No lyrics found",
                24,
                Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(16), dp(80), dp(16), dp(8));
        errorBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView message = textFactory.createText(
                activity,
                safe(error),
                14,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(false));
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(16), dp(4), dp(16), dp(16));
        errorBox.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(errorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        errorBox.setAlpha(1f);
        lyricsColumn.postDelayed(() -> {
            if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
            errorBox.animate().alpha(0f).setDuration(350L).withEndAction(() -> {
                if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
                lyricsColumn.removeAllViews();
                showInterludeIndicator(lyricsColumn);
            }).start();
        }, ERROR_DISPLAY_MS);
    }

    private void showInterludeIndicator(LinearLayout lyricsColumn) {
        boolean noteMode = "note".equals(config.get(Settings.INTERLUDE_ICON));
        TextView indicator = textFactory.createText(
                activity, noteMode ? "♪" : "•  •  •", noteMode ? 38 : 44,
                Color.WHITE, textFactory.resolveTypeface(true));
        indicator.setGravity(Gravity.START);
        indicator.setAlpha(0f);
        indicator.setPadding(dp(16), dp(80), dp(16), dp(16));
        lyricsColumn.addView(indicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        indicator.animate().alpha(1f).setDuration(350L).start();
    }
}
