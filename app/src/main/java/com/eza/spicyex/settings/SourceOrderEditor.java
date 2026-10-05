package com.eza.spicyex.settings;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.ui.GlossyToggle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.PanelDialog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Owner of the merged "Lyrics source" row and its ranking/order/toggle dialog.
 *
 * <p>This is the panel's one genuinely stateful editor: a working copy of ranking, order, and
 * enabled flags is edited locally and committed only on Save, through
 * {@link SourcePreferencesAdapter}, because the values span two preference namespaces.
 *
 * <p>Selection is tracked by <em>persisted value</em>, never by rendered label text. The old
 * implementation decided which radio was lit by comparing displayed strings, so a localized
 * label silently broke selection. Display labels resolve through the locale; the value that is
 * read, compared, and saved stays the stable persisted token.
 *
 * <p>Retired sources (Spicy's remote path) stay in the backing order for compatibility with
 * old persisted data but are never shown. Because the visible list is therefore a projection,
 * a visible drop position maps back to a full-order index by identity.
 */
public final class SourceOrderEditor {
    /** The two persisted ranking tokens. They are values, not labels; do not localize them. */
    private static final String MODE_AUTO = "Auto";
    private static final String MODE_SOURCE_ORDER = "Source order";

    /** What the editor needs from the panel. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        /** Ranking/order/enabled were saved; refresh the owning section. */
        void onSourcesCommitted();

        /** Currently playing track, or null when the hook cannot see one. */
        com.eza.spicyex.SpotifyTrack currentTrack();

        /** Opens the lyrics manager scoped to the current song. */
        void manageCurrentTrackLyrics();
    }

    private final Host host;

    public SourceOrderEditor(Host host) {
        this.host = host;
    }

    /**
     * {@code enumSetting} is declared as {@code Setting<String>} but is constructed as a
     * {@link Settings.StringSetting}; the option-label lookup needs the concrete type.
     */
    private static Settings.StringSetting modeSetting() {
        return (Settings.StringSetting) Settings.LYRICS_SOURCE_MODE;
    }

    // --- Collapsed row ---

    /**
     * One keyed composite for every source control shown in the settings card.
     *
     * <p>The panel's keyed rebuild owns one direct child per setting. Keep the two visible rows
     * inside that child so a rebuild can replace them atomically instead of leaving an untagged
     * current-song row behind.
     */
    public void rows(LinearLayout content) {
        LinearLayout group = new LinearLayout(host.style().context());
        group.setOrientation(LinearLayout.VERTICAL);
        group.setTag(PanelTags.row(Settings.LYRICS_SOURCE_MODE));
        content.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inlineEditor(group);
        trackRow(group);
    }

    /**
     * Ranking and the source list right on the page, saved as they change. They used to sit
     * behind a summary row and a dialog with its own Save - three taps and a confirmation to
     * turn one source off.
     */
    private void inlineEditor(LinearLayout group) {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final String[] ranking = new String[]{rankingValue()};
        final ArrayList<Source> order = new ArrayList<>(LyricsSourcePreferences.sourceOrder(style.context()));
        final EnumMap<Source, GlossyToggle> toggles = new EnumMap<>(Source.class);
        final ArrayList<ImageView> grips = new ArrayList<>();
        final Runnable commit = () -> {
            EnumMap<Source, Boolean> enabled = new EnumMap<>(Source.class);
            for (Source source : Source.values()) {
                GlossyToggle toggle = toggles.get(source);
                enabled.put(source, toggle != null ? toggle.isChecked()
                        : LyricsSourcePreferences.sourceEnabled(style.context(), source));
            }
            sourceAdapter().commit(host.writer(), new SourcePreferencesAdapter.Commit(ranking[0], order, enabled));
        };

        final ArrayList<LinearLayout> rankingRows = new ArrayList<>();
        final String[][] rankingOptions = new String[][]{
                {MODE_AUTO, strings.option(modeSetting(), MODE_AUTO)},
                {MODE_SOURCE_ORDER, strings.option(modeSetting(), MODE_SOURCE_ORDER) + " — "
                        + strings.get("settings_source_ranking_order_desc", "follow the order below")}
        };
        for (final String[] option : rankingOptions) {
            LinearLayout row = style.radioRow(option[1], option[0].equals(ranking[0]));
            row.setPadding(style.dp(4), style.dp(12), style.dp(4), style.dp(12));
            rankingRows.add(row);
            final String value = option[0];
            row.setOnClickListener(v -> {
                if (value.equals(ranking[0])) return;
                ranking[0] = value;
                refreshRankingRows(rankingRows, ranking[0]);
                boolean ordered = MODE_SOURCE_ORDER.equals(value);
                for (ImageView grip : grips) grip.setVisibility(ordered ? View.VISIBLE : View.GONE);
                commit.run();
            });
            group.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        refreshRankingRows(rankingRows, ranking[0]);

        final LinearLayout list = new LinearLayout(style.context());
        list.setOrientation(LinearLayout.VERTICAL);
        for (Source source : order) {
            // Spicy's remote path is retired from the user-selectable set; it stays in the
            // backing order for old persisted data but is never shown.
            if (source == Source.SPICY) continue;
            LinearLayout row = new LinearLayout(style.context());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(style.dp(4), style.dp(6), style.dp(0), style.dp(6));
            row.setTag(source);
            TextView label = style.text(sourceLabel(source), 16, PanelStyle.COL_TITLE, false);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView grip = style.kindView(Kind.CHEVRONS_UP_DOWN, PanelStyle.COL_SUMMARY, 20);
            grip.setContentDescription(strings.get("settings_source_drag", "Drag to reorder"));
            grip.setVisibility(MODE_SOURCE_ORDER.equals(ranking[0]) ? View.VISIBLE : View.GONE);
            grips.add(grip);
            row.addView(grip, new LinearLayout.LayoutParams(style.dp(40), style.dp(40)));
            GlossyToggle toggle = new GlossyToggle(style.context());
            toggle.setAccent(PanelStyle.COL_ACCENT);
            toggle.setChecked(LyricsSourcePreferences.sourceEnabled(style.context(), source), false);
            toggles.put(source, toggle);
            toggle.setOnChangeListener(() -> {
                commit.run();
                // Turning Spicy's source on or off shows or hides its token row.
                host.onSourcesCommitted();
            });
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
            row.addView(toggle);
            attachSourceDrag(grip, row, list, order, commit);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        group.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Separate management item scoped to the current song: opens its lyrics picker. */
    private void trackRow(LinearLayout content) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        com.eza.spicyex.SpotifyTrack current = null;
        try {
            current = host.currentTrack();
        } catch (Throwable ignored) {
        }
        String subtitle;
        if (current == null || current.title == null || current.title.trim().isEmpty()) {
            subtitle = strings.get("settings_source_track_idle", "Nothing playing");
        } else {
            String artist = current.artist == null ? "" : current.artist.trim();
            subtitle = artist.isEmpty() ? current.title.trim()
                    : current.title.trim() + " — " + artist;
        }
        LinearLayout row = style.newRow(content);
        TextView value = style.titleColumn(row,
                strings.get("settings_source_track_title", "Current song lyrics"), subtitle);
        value.setTextColor(PanelStyle.COL_ACCENT);
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
        row.setOnClickListener(v -> {
            try {
                host.manageCurrentTrackLyrics();
            } catch (Throwable ignored) {
            }
        });
    }

    /** Provider names are brands and stay as authored; only the surrounding copy localizes. */
    public static String sourceLabel(Source source) {
        if (source == Source.APPLE_MUSIC) return "Apple Music";
        if (source == Source.SPICY) return "Spicy";
        if (source == Source.SPOTIFY) return "Spotify";
        if (source == Source.AMLL) return "AMLL";
        if (source == Source.QQ) return "QQ Music";
        if (source == Source.NETEASE) return "NetEase";
        if (source == Source.KUGOU) return "KuGou";
        if (source == Source.GENIUS) return "Genius";
        if (source == Source.MUSIXMATCH) return "Musixmatch";
        if (source == Source.BETTERLYRICS) return "BetterLyrics";
        if (source == Source.BINILYRICS) return "BiniLyrics";
        return "LRCLIB";
    }

    /** Current ranking as a persisted token; anything unrecognized reads as Auto. */
    private String rankingValue() {
        String stored = host.store().get(Settings.LYRICS_SOURCE_MODE);
        return MODE_SOURCE_ORDER.equals(stored) ? MODE_SOURCE_ORDER : MODE_AUTO;
    }

    /** Repaints radio rows from the persisted value, never from rendered label text. */
    private void refreshRankingRows(List<LinearLayout> rows, String selected) {
        for (int i = 0; i < rows.size(); i++) {
            boolean isSelected = i == (MODE_SOURCE_ORDER.equals(selected) ? 1 : 0);
            host.style().paintRadio(rows.get(i), isSelected);
        }
    }

    // --- Drag to reorder ---

    /**
     * Grip drag with sliding neighbors: the dragged row follows the finger via translationY
     * while the rows it passes slide out of the way. Order commits on release.
     */
    private void attachSourceDrag(View handle, LinearLayout row, final LinearLayout list,
                                  final ArrayList<Source> order, final Runnable onReordered) {
        final PanelStyle style = host.style();
        final float[] startRawY = new float[1];
        final int[] fromIndex = new int[1];
        final int[] rowHeight = new int[1];
        final int[] targetIndex = new int[1];
        final boolean[] dragging = new boolean[1];
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (row.getHeight() <= 0) return false;
                    startRawY[0] = event.getRawY();
                    fromIndex[0] = list.indexOfChild(row);
                    targetIndex[0] = fromIndex[0];
                    if (fromIndex[0] < 0) return false;
                    rowHeight[0] = row.getHeight();
                    dragging[0] = true;
                    disallowIntercept(list, true);
                    if (android.os.Build.VERSION.SDK_INT >= 21) row.setElevation(style.dp(6));
                    row.setAlpha(0.92f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging[0]) return false;
                    float dy = event.getRawY() - startRawY[0];
                    row.setTranslationY(dy);
                    float center = row.getTop() + dy + rowHeight[0] / 2f;
                    int target = insertionIndex(list, row, center);
                    targetIndex[0] = target;
                    slideNeighbors(list, row, fromIndex[0], target, rowHeight[0]);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging[0]) return false;
                    dragging[0] = false;
                    boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP;
                    int from = fromIndex[0];
                    int target = commit ? targetIndex[0] : from;
                    // Stop neighbor animations before moving the child. Pending animator
                    // writes were racing the reparent and caused overlap/jumps on release.
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View child = list.getChildAt(i);
                        child.animate().cancel();
                        if (child != row) child.setTranslationY(0f);
                    }
                    if (commit && target != from && target >= 0) {
                        // The visible list hides retired sources, so a visible position is not
                        // an index into the full backing order. Resolve by identity.
                        Source dragged = (Source) row.getTag();
                        int orderFrom = order.indexOf(dragged);
                        if (dragged != null && orderFrom >= 0) {
                            order.remove(orderFrom);
                            order.add(visibleInsertionToOrderIndex(order, target), dragged);
                            list.removeView(row);
                            list.addView(row, Math.min(target, list.getChildCount()));
                            if (onReordered != null) onReordered.run();
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 21) row.setElevation(0);
                    row.setAlpha(1f);
                    settleTranslations(list);
                    disallowIntercept(list, false);
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /**
     * Maps a visible-list insertion position to an index in the full source order, which can
     * contain retired entries hidden from the reorder UI. Hidden entries keep their slots: the
     * dragged source lands before the visible item at the target position, or at the end when
     * the target is past the last visible item.
     */
    static int visibleInsertionToOrderIndex(List<Source> order, int visibleTarget) {
        int seen = 0;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i) == Source.SPICY) continue;
            if (seen == visibleTarget) return i;
            seen++;
        }
        return order.size();
    }

    /** Insertion index after the dragged row is removed: non-row children above the finger point. */
    private int insertionIndex(LinearLayout list, LinearLayout row, float centerY) {
        int position = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child == row) continue;
            float mid = child.getTop() + child.getHeight() / 2f;
            if (centerY > mid) position++;
        }
        return Math.max(0, Math.min(list.getChildCount() - 1, position));
    }

    /** Slides the rows between the drag origin and the insertion point out of the way. */
    private void slideNeighbors(LinearLayout list, LinearLayout row, int from, int target, int height) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child == row) continue;
            float shift = 0f;
            if (target > from && i > from && i <= target) shift = -height;
            else if (target < from && i >= target && i < from) shift = height;
            if (child.getTranslationY() != shift) {
                child.animate().translationY(shift).setDuration(120).start();
            }
        }
    }

    private void settleTranslations(LinearLayout list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getTranslationY() != 0f) {
                child.animate().translationY(0f).setDuration(120).start();
            }
        }
    }

    private void disallowIntercept(View view, boolean disallow) {
        View current = view;
        while (current != null) {
            android.view.ViewParent parent = current.getParent();
            if (parent == null) return;
            parent.requestDisallowInterceptTouchEvent(disallow);
            if (!(parent instanceof View)) return;
            current = (View) parent;
        }
    }

    /** Source-namespace sink for the merged Save; ordinary values go via the writer. */
    private SourcePreferencesAdapter sourceAdapter() {
        PanelStyle style = host.style();
        return new SourcePreferencesAdapter(new SourcePreferencesAdapter.Sink() {
            @Override public void setRankingMode(RankingMode mode) {
                LyricsSourcePreferences.setRankingMode(style.context(), mode);
            }

            @Override public void setSourceOrder(List<Source> order) {
                LyricsSourcePreferences.setSourceOrder(style.context(), order);
            }

            @Override public void setSourceEnabled(Source source, boolean enabled) {
                LyricsSourcePreferences.setSourceEnabled(style.context(), source, enabled);
            }
        });
    }
}
