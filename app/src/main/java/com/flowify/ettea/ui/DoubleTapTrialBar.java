package com.flowify.ettea.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "Try it" bar for the double-tap like: the double-tap gesture hint, a row of the effect
 * styles to switch between while trying them, and a way out. It sits at the bottom of the
 * lyrics screen until the user leaves; double taps elsewhere on the screen play the chosen
 * style (the owner handles those).
 */
public final class DoubleTapTrialBar extends LinearLayout {
    public interface Listener {
        /** A style was picked: save it and show it once. */
        void onStyle(String style);

        void onExit();
    }

    private final float density;
    private final LinearLayout chips;
    private final String[] styles;
    private String selected;

    /**
     * @param styles  the stored values, in order
     * @param labels  what to show for each
     */
    public DoubleTapTrialBar(Context context, String hint, String exitLabel, String[] styles,
                             String[] labels, String selected, Listener listener) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.styles = styles;
        this.selected = selected;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);

        DoubleTapHintView hintView = new DoubleTapHintView(context, hint);
        LayoutParams hintLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = dp(10);
        addView(hintView, hintLp);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC161616);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x33FFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        // Taps on the bar never reach the lyrics behind it.
        card.setClickable(true);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        chips = new LinearLayout(context);
        chips.setOrientation(HORIZONTAL);
        for (int i = 0; i < styles.length; i++) {
            final String style = styles[i];
            TextView chip = pill(context, labels[i]);
            chip.setOnClickListener(v -> {
                this.selected = style;
                refresh();
                listener.onStyle(style);
            });
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(6);
            chips.addView(chip, lp);
        }
        scroll.addView(chips);
        card.addView(scroll, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView exit = pill(context, exitLabel);
        GradientDrawable exitBg = new GradientDrawable();
        exitBg.setColor(Color.WHITE);
        exitBg.setCornerRadius(dp(18));
        exit.setBackground(exitBg);
        exit.setTextColor(0xFF111111);
        exit.setOnClickListener(v -> listener.onExit());
        LayoutParams exitLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        exitLp.leftMargin = dp(8);
        card.addView(exit, exitLp);

        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        refresh();
        // Bring the chosen style into view once laid out.
        scroll.post(() -> {
            for (int i = 0; i < styles.length; i++) {
                if (styles[i].equals(this.selected)) {
                    View chip = chips.getChildAt(i);
                    scroll.scrollTo(Math.max(0, chip.getLeft() - dp(24)), 0);
                }
            }
        });
    }

    private TextView pill(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        view.setSingleLine(true);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void refresh() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean on = styles[i].equals(selected);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(on ? 0xF2FFFFFF : 0x1FFFFFFF);
            chip.setBackground(bg);
            chip.setTextColor(on ? 0xFF111111 : 0xE6FFFFFF);
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
