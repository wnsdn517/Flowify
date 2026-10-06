package com.flowify.ettea.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.flowify.ettea.Settings;
import com.flowify.ettea.lyrics.GlyphIconDrawable;
import com.flowify.ettea.ui.ActionIconDrawable;
import com.flowify.ettea.ui.ActionIconDrawable.Kind;

import java.util.HashMap;
import java.util.Map;

/**
 * The panel's single visual vocabulary: colours, density, and the shared view chrome.
 *
 * <p>Every widget the settings panel builds comes from here, so a colour, corner radius, or
 * minimum row height is stated once. Rows, dialogs, and the section editor all share one
 * {@code PanelStyle} instance rather than re-deriving dimensions and colours locally.
 */
public final class PanelStyle {
    // Glass over the (blurred) lyrics screen: translucent enough for it to show through.
    public static final int COL_CARD = 0xC7121214;
    public static final int COL_CARD_TOP = 0xB8202024;
    public static final int COL_CARD_BORDER = 0x24FFFFFF;
    public static final int COL_DIVIDER = 0x14FFFFFF;
    public static final int COL_TITLE = 0xFFFFFFFF;
    public static final int COL_SUMMARY = 0x99FFFFFF;
    public static final int COL_SECTION = 0x8CFFFFFF;
    /** The accent: the current album's colour, as the lyrics background uses it (see
     *  {@link #useAlbumAccent}); a soft neutral blue when the art has no colour to give. */
    public static final int COL_ACCENT_NEUTRAL = 0xFF9DB8F2;
    public static int COL_ACCENT = COL_ACCENT_NEUTRAL;

    /**
     * Takes the accent from the album colour Spotify extracted for the playing track, lifted to a
     * soft, bright tint that reads on the dark glass - the same source the lyrics screen's own
     * background comes from, so the panel belongs to the screen it opens over.
     */
    public static void useAlbumAccent(String extractedColor) {
        COL_ACCENT = COL_ACCENT_NEUTRAL;
        com.flowify.ettea.ui.PanelDialog.COL_ACCENT = COL_ACCENT;
        if (extractedColor == null || extractedColor.trim().isEmpty()) return;
        try {
            int seed = com.flowify.ettea.lyrics.LyricVisuals.parseSpotifyExtractedColor(extractedColor);
            float[] hsv = new float[3];
            Color.colorToHSV(seed, hsv);
            if (hsv[1] < 0.12f) return; // grey art: no colour to take, keep the default
            hsv[1] = Math.min(0.55f, Math.max(0.35f, hsv[1]));
            hsv[2] = 1f;
            COL_ACCENT = Color.HSVToColor(hsv);
        } catch (Throwable ignored) {
        } finally {
            com.flowify.ettea.ui.PanelDialog.COL_ACCENT = COL_ACCENT;
        }
    }

    private static final int ROW_MIN_HEIGHT_DP = 52;

    /** Leading icon per collapsible section (artifacts/settings-icon-plan.md §4). */
    private static final Map<String, Kind> SECTION_ICONS = new HashMap<>();

    static {
        SECTION_ICONS.put("lyrics", Kind.AUDIO_LINES);
        SECTION_ICONS.put("gestures", Kind.POINTER);
        SECTION_ICONS.put("transliteration", Kind.BOOK_OPEN_TEXT);
        SECTION_ICONS.put("translation", Kind.LANGUAGES);
        SECTION_ICONS.put("now_playing", Kind.DISC_3);
        SECTION_ICONS.put("lyrics_sources", Kind.ROWS_2);
        SECTION_ICONS.put("lyrics_screen", Kind.FULLSCREEN);
        // No Apple-mark glyph in Lucide (brand icons are out); the effects wand reads as motion.
        SECTION_ICONS.put("apple_music", Kind.WAND_SPARKLES);
        SECTION_ICONS.put("ai", Kind.SPARKLES);
        SECTION_ICONS.put("pip", Kind.PICTURE_IN_PICTURE);
        SECTION_ICONS.put("ad_free", Kind.VOLUME_OFF);
        SECTION_ICONS.put("labs", Kind.TEST_TUBE);
        SECTION_ICONS.put("debug", Kind.ACTIVITY);
    }

    /** Recommended-tier leading icons per setting row; absent keys stay text-only. */
    private static final Map<String, Object> ROW_LEADS = new HashMap<>();

    static {
        ROW_LEADS.put("settings_ui_language", Kind.GLOBE);
        ROW_LEADS.put("lyric_sync_offset_ms", Kind.TIMER);
        ROW_LEADS.put("lyrics_live_card_weight", Kind.BOLD);
        ROW_LEADS.put("lyrics_weight", Kind.BOLD);
        ROW_LEADS.put("lyrics_live_card_text_size", Kind.A_LARGE_SMALL);
        ROW_LEADS.put("lyrics_text_size", Kind.A_LARGE_SMALL);
        ROW_LEADS.put("line_spacing", Kind.ALIGN_VERTICAL_DISTRIBUTE_CENTER);
        ROW_LEADS.put("lyrics_font", Kind.TYPE);
        ROW_LEADS.put("lyric_interlude_icon", Kind.ELLIPSIS);
        ROW_LEADS.put("lyrics_translation_enabled", Kind.LANGUAGES);
        ROW_LEADS.put("lyrics_translation_target", Kind.ARROW_RIGHT_LEFT);
        // Reading rows reuse the translit chip's script glyphs (GlyphIconDrawable).
        ROW_LEADS.put("lyrics_japanese_reading_mode", "あ");
        ROW_LEADS.put("lyrics_chinese_mode", "拼");
        ROW_LEADS.put("lyrics_korean_romanization", "한");
        ROW_LEADS.put("lyrics_cyrillic_mode", "Я");
    }

    private final Context context;

    public PanelStyle(Context context) {
        this.context = context;
    }

    public Context context() {
        return context;
    }

    public float density() {
        return context.getResources().getDisplayMetrics().density;
    }

    public int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        Typeface face = panelTypeface();
        if (bold) view.setTypeface(face == null ? Typeface.DEFAULT_BOLD : Typeface.create(face, Typeface.BOLD));
        else if (face != null) view.setTypeface(face);
        return view;
    }

    /** Spotify Mix, the typeface the lyrics screen is set in; null before it has loaded. */
    private static Typeface panelTypeface() {
        java.lang.ref.WeakReference<Typeface> ref = com.flowify.ettea.References.beautifulFont;
        return ref == null ? null : ref.get();
    }

    public static Kind sectionIcon(Settings.Section section) {
        return SECTION_ICONS.get(section.id);
    }

    /** Section icons are white, as every control on the lyrics screen is. */
    public static int sectionTint(Settings.Section section) {
        return COL_TITLE;
    }

    /** The panel's own surface: a faint lift at the top fading into the base, like the glass
     *  the lyrics screen sits behind. */
    public GradientDrawable panelBackground() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COL_CARD_TOP, COL_CARD});
        bg.setCornerRadius(dp(32));
        bg.setStroke(Math.max(1, dp(1)), COL_CARD_BORDER);
        return bg;
    }

    /** Icon in a translucent circle - the lyrics screen's own round button. */
    public ImageView iconChip(Kind kind, int tint, int sizeDp) {
        ImageView chip = kindView(kind, tint, Math.round(sizeDp * 0.48f));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x26FFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        chip.setBackground(bg);
        return chip;
    }

    /** Small all-caps label over a group of hub tiles. */
    public TextView groupCaption(String label) {
        TextView caption = text(label, 12, COL_SECTION, true);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.08f);
        caption.setPadding(dp(6), dp(14), dp(6), dp(6));
        return caption;
    }

    /**
     * A hub entry: coloured icon chip, title, one line about what is inside, an optional
     * state pill and a chevron. The whole row is the tap target.
     */
    public LinearLayout hubTile(Kind icon, int tint, String title, String subtitle,
                                String pill, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(6), dp(10), dp(6), dp(10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF)));
        LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(dp(40), dp(40));
        chip.rightMargin = dp(14);
        row.addView(iconChip(icon, tint, 40), chip);
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 16, COL_TITLE, false));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 13, COL_SUMMARY, false);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(2), 0, 0);
            col.addView(sub);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (pill != null) row.addView(statePill(pill), pillParams());
        row.addView(kindView(Kind.CHEVRON_RIGHT, COL_SECTION, 18),
                new LinearLayout.LayoutParams(dp(26), dp(30)));
        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(4);
        return lp;
    }

    /** A state label such as "On", in the accent colour. */
    private TextView statePill(String label) {
        TextView pill = text(label, 11, COL_ACCENT, true);
        pill.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((COL_ACCENT & 0x00FFFFFF) | 0x26000000);
        bg.setCornerRadius(dp(10));
        pill.setBackground(bg);
        return pill;
    }

    /**
     * A shortcut tile for the top of the hub: a wash of its colour, a big icon, a title and a
     * hint. Two sit side by side, so each takes an equal share of the row.
     */
    public LinearLayout shortcutTile(Kind icon, int tint, String title, String hint,
                                     View.OnClickListener listener) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x17FFFFFF);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1)), 0x1AFFFFFF);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
        tile.addView(iconChip(icon, tint, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = text(title, 15, COL_TITLE, true);
        name.setPadding(0, dp(12), 0, 0);
        tile.addView(name);
        TextView sub = text(hint, 12, COL_SUMMARY, false);
        sub.setPadding(0, dp(2), 0, 0);
        tile.addView(sub);
        tile.setOnClickListener(listener);
        return tile;
    }

    /** Tinted lucide icon view; decorative by default (row text carries the meaning). */
    public ImageView kindView(Kind kind, int color, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new ActionIconDrawable(kind, color, density(), sizeDp));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    public LinearLayout.LayoutParams leadParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.rightMargin = dp(10);
        return lp;
    }

    /** Inserts the row's leading icon at index 0 when one is mapped for this setting. */
    public void applyRowLead(LinearLayout row, String key) {
        Object lead = ROW_LEADS.get(key);
        if (lead == null) return;
        View iconView;
        if (lead instanceof Kind) {
            iconView = kindView((Kind) lead, COL_SECTION, 19);
        } else {
            ImageView glyph = new ImageView(context);
            glyph.setImageDrawable(new GlyphIconDrawable((String) lead, Typeface.DEFAULT_BOLD));
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconView = glyph;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(38));
        lp.rightMargin = dp(8);
        row.addView(iconView, 0, lp);
    }

    /** Circular translucent header action (resize, close). */
    public ImageButton headerIconButton(Kind icon, String contentDescription,
                                        View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setImageDrawable(new ActionIconDrawable(icon, COL_SUMMARY, density()));
        button.setContentDescription(contentDescription);
        button.setTooltipText(contentDescription);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x0DFFFFFF);
        circle.setStroke(Math.max(1, dp(1)), 0x30FFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), circle, mask));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    /** Square translucent icon button used by AI rows and token actions. */
    public ImageButton iconButton(AiSettingsRows.IconAction action) {
        ImageButton button = new ImageButton(context);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setImageDrawable(new ActionIconDrawable(action.icon, COL_TITLE, density()));
        button.setContentDescription(action.contentDescription);
        button.setTooltipText(action.contentDescription);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF)));
        button.setOnClickListener(action.listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(2);
        button.setLayoutParams(lp);
        return button;
    }

    /** Rounded container that visually groups an expanded section's rows. */
    public LinearLayout newCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(2), dp(12), dp(2));
        // A hairline between rows instead of relying on row height alone to separate them.
        GradientDrawable divider = new GradientDrawable();
        divider.setColor(COL_DIVIDER);
        divider.setSize(1, Math.max(1, dp(1) / 2));
        card.setDividerDrawable(divider);
        card.setDividerPadding(dp(8));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return card;
    }

    public void attachCard(LinearLayout parent, LinearLayout card, int at) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        if (at < 0 || at >= parent.getChildCount()) parent.addView(card, lp);
        else parent.addView(card, at, lp);
    }

    /** Full-width tappable row appended to a card; the base for every setting row. */
    public LinearLayout newRow(LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF)));
        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Title + summary column; returns the summary view so callers can patch it by tag. */
    public TextView titleColumn(LinearLayout row, String title, String summary) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 16, COL_TITLE, false));
        TextView sub = text(summary == null ? "" : summary, 13, COL_SUMMARY, false);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        sub.setTag(PanelTags.ROW_SUMMARY);
        col.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(12);
        row.addView(col, lp);
        return sub;
    }

    /** Radio row used by the source-ranking editor; the dot is tagged for repainting. */
    public LinearLayout radioRow(String label, boolean selected) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        ImageView dot = new ImageView(context);
        dot.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(dot, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView text = text(label, 15, selected ? COL_ACCENT : COL_TITLE, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(12);
        row.addView(text, params);
        row.setTag(dot);
        return row;
    }

    /** Paints a radio dot and its label for the current selection. */
    public void paintRadio(LinearLayout row, boolean selected) {
        Object tag = row.getTag();
        if (tag instanceof ImageView) {
            ((ImageView) tag).setImageDrawable(new ActionIconDrawable(
                    Kind.CIRCLE, selected ? COL_ACCENT : COL_SUMMARY, density(), selected));
        }
        TextView label = radioLabel(row);
        if (label != null) label.setTextColor(selected ? COL_ACCENT : COL_TITLE);
    }

    public static TextView radioLabel(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof TextView) return (TextView) row.getChildAt(i);
        }
        return null;
    }
}
