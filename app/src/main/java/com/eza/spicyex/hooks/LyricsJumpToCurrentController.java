package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/** Owns the floating "jump back to active lyric" affordance. */
final class LyricsJumpToCurrentController {
    private static final int ICON_SIZE_DP = 40;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private final View button;

    private LyricsJumpToCurrentController(View button) {
        this.button = button;
    }

    static LyricsJumpToCurrentController attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            Runnable onClick
    ) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(pillBackground());
        pill.setPadding(dp(12), 0, dp(14), 0);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setAlpha(0f);
        pill.setVisibility(View.GONE);
        pill.setElevation(dp(8));
        applyPressScale(pill);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ActionIconDrawable(
                ActionIconDrawable.Kind.AUDIO_LINES, TEXT_COLOR, density));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        pill.addView(icon, iconLp);

        TextView label = new TextView(activity);
        label.setText("Follow lyrics");
        label.setTextColor(TEXT_COLOR);
        label.setTextSize(13f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setTypeface(textFactory.resolveTypeface(false));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(6);
        pill.addView(label, labelLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ICON_SIZE_DP),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(24));
        parent.addView(pill, lp);

        pill.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        return new LyricsJumpToCurrentController(pill);
    }

    void update(boolean show) {
        int targetVisibility = show ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != targetVisibility) {
            button.setVisibility(targetVisibility);
        }
        button.setAlpha(show ? 0.92f : 0f);
    }

    /** Raises the chip above the bottom track-info readout (bottom mode) or restores it. */
    void setBottomMarginDp(int marginDp) {
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        int target = dp(marginDp);
        if (flp.bottomMargin != target) {
            flp.bottomMargin = target;
            button.setLayoutParams(flp);
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
