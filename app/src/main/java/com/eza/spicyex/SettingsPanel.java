package com.eza.spicyex;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.diagnostics.DiagnosticReportingDialog;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.CacheStoragePolicy;
import com.eza.spicyex.lyrics.LanguageModelPack;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.SpicyManualTokenStore;
import com.eza.spicyex.settings.PanelDialogs;
import com.eza.spicyex.settings.PanelPolicy;
import com.eza.spicyex.settings.PanelSnapshot;
import com.eza.spicyex.settings.PanelStrings;
import com.eza.spicyex.settings.PanelStyle;
import com.eza.spicyex.settings.PanelTags;
import com.eza.spicyex.settings.RowSyncPlan;
import com.eza.spicyex.settings.SettingLabels;
import com.eza.spicyex.settings.SettingRowFactory;
import com.eza.spicyex.settings.SettingUiSpec;
import com.eza.spicyex.settings.SettingsUiSchema;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.settings.SourceOrderEditor;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.Motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinator for the in-Spotify settings panel.
 *
 * <p>Owns only what is genuinely panel-wide: the scrolling card, the section list, one applied
 * state snapshot per render pass, anchor-preserving rebuilds, and the dispatch of each setting
 * to its renderer. Everything with a narrower responsibility lives in an owner:
 *
 * <ul>
 * <li>{@link PanelStyle} — colours, density, and every shared view construction.</li>
 * <li>{@link SettingRowFactory} — one renderer per row kind, plus in-place patching.</li>
 * <li>{@link SourceOrderEditor} — the merged source row, ranking, and drag-to-reorder.</li>
 * <li>{@link PanelDialogs} — option, confirming, cache, and credential dialogs.</li>
 * <li>{@link PanelPolicy} — pure visibility, availability, commit, and rebuild policy.</li>
 * </ul>
 *
 * <p>Does not own setting defaults ({@link Settings}) or persistence ({@link SettingsStore}).
 * Rendered as a single floating rounded card using platform widgets and {@link GlossyToggle};
 * layout stays visually stable unless device screenshots verify a change.
 */
public final class SettingsPanel implements SettingRowFactory.Host, PanelDialogs.Host,
        SourceOrderEditor.Host, com.eza.spicyex.settings.CacheManager.Host {
    // Static: survives panel re-opens within the process, so the panel never re-opens fully collapsed.
    private static final Set<String> expandedSections = new java.util.HashSet<>();

    private final Context context;
    private final PanelStyle style;
    private int sectionReflowGeneration;
    private static final String TAG_SECTION_CHEVRON = "hdr:chevron";
    private static final String TAG_LAYOUT_EDITOR_ACTION = "action:layout_editor";
    private static final String TAG_CARD_EDITOR_ACTION = "action:card_editor";

    private final SettingsStore store;
    private final SettingsWriter writer;
    private final SettingRowFactory rows;
    private final PanelDialogs dialogs;
    private final SourceOrderEditor sources;
    private final com.eza.spicyex.settings.CacheManager cache;
    private final java.util.function.BooleanSupplier isHalfSize;
    private final Runnable onToggleSize;
    private final Runnable onClose;
    /** Opens the layout editor: {@link #EDITOR_LYRICS} or {@link #EDITOR_CARD}. */
    private final java.util.function.IntConsumer onOpenLayoutEditor;
    public static final int EDITOR_LYRICS = 1;
    public static final int EDITOR_CARD = 2;
    private final java.util.function.Consumer<CacheClearKind> onClearCache;
    private final Runnable onResyncTiming;
    private com.eza.spicyex.hooks.LyricsHost lyricsHost;

    private LinearLayout sectionsContainer;
    private TextView panelTitle;
    private SettingsUiStrings uiStrings;
    private AiSettingsRows aiSettingsRows;
    private ScrollView scrollRoot;
    private ImageView aiBadgeView;
    private String anchorTag;
    private int anchorDelta;
    /**
     * Whether the built view is currently attached to a window.
     *
     * <p>AI model probes and discovery run on background threads and post back afterwards. This
     * is the panel's own lifecycle signal, so a late result cannot rebuild a dismissed panel or
     * repaint a detached badge.
     */
    private volatile boolean panelAttached;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** One queued download-status tick at a time, so attach/detach cannot stack polling loops. */
    private boolean languageModelPollQueued;

    /** Locale lookup for the pure policy layer; reads the current uiStrings on every call. */
    private final PanelStrings panelStrings = new PanelStrings() {
        @Override public String get(String name, String fallback) {
            return uiStrings.get(name, fallback);
        }

        @Override public String format(String name, String fallback, Object... args) {
            return uiStrings.format(name, fallback, args);
        }
    };

    public SettingsPanel(Context context, SettingsStore store,
                         java.util.function.BooleanSupplier isHalfSize,
                         Runnable onToggleSize, Runnable onClose,
                         java.util.function.IntConsumer onOpenLayoutEditor,
                         java.util.function.Consumer<CacheClearKind> onClearCache,
                         Runnable onResyncTiming) {
        this.context = context;
        this.style = new PanelStyle(context);
        this.store = store;
        this.writer = new SettingsWriter(store);
        this.rows = new SettingRowFactory(this);
        this.dialogs = new PanelDialogs(this);
        this.sources = new SourceOrderEditor(this);
        this.cache = new com.eza.spicyex.settings.CacheManager(this);
        this.isHalfSize = isHalfSize;
        this.onToggleSize = onToggleSize;
        this.onClose = onClose;
        this.onOpenLayoutEditor = onOpenLayoutEditor;
        this.onClearCache = onClearCache;
        this.onResyncTiming = onResyncTiming;
        writer.ensureBackgroundStyleMigrated(store.get(Settings.ENABLE_BACKGROUND));
        this.uiStrings = UiLanguage.strings(context, store.get(Settings.UI_LANGUAGE));
    }

    /** Builds the card view; the host sizes/centers it. */
    public View build() {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(PanelStyle.COL_CARD);
        cardBg.setCornerRadius(style.dp(26));
        cardBg.setStroke(style.dp(1), PanelStyle.COL_CARD_BORDER);
        scroll.setBackground(cardBg);
        scroll.setClipToOutline(true);
        scrollRoot = scroll;
        // The panel's lifecycle owner is its own view tree: the host shows and dismisses this
        // ScrollView, so attach/detach is the exact moment work must start or stop.
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                panelAttached = true;
                // The panel is rebuilt on every open, so an install started earlier has no loop
                // left to continue: resume it or the row keeps the progress it was built with.
                if (LanguageModelPack.status().phase == LanguageModelPack.Phase.DOWNLOADING) {
                    resumeLanguageModelDownloadPolling();
                }
            }

            @Override public void onViewDetachedFromWindow(View v) {
                panelAttached = false;
                sectionReflowGeneration++;
                if (sectionsContainer != null) {
                    for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
                        View child = sectionsContainer.getChildAt(i);
                        child.animate().cancel();
                        child.setTranslationY(0f);
                    }
                }
                languageModelPollQueued = false;
                uiHandler.removeCallbacksAndMessages(null);
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(style.dp(20), style.dp(18), style.dp(20), style.dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        renderHeader(content);
        buildSearch(content);
        sectionsContainer = new LinearLayout(context);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(sectionsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderSections(sectionsContainer);
        return scroll;
    }

    // --- Search ---

    private LinearLayout searchBar;
    private android.widget.EditText searchField;
    private LinearLayout searchResults;
    private volatile com.eza.spicyex.settings.SettingsSearch searchIndex;

    /** A result that opens a Layout Editor rather than a panel row. */
    private static final class EditorTarget {
        final int mode;
        final Settings.Setting<?> setting;

        EditorTarget(int mode, Settings.Setting<?> setting) {
            this.mode = mode;
            this.setting = setting;
        }
    }

    /**
     * The search field above the section list. Typing swaps the list for results (see
     * {@link com.eza.spicyex.settings.SettingsSearch} for how they are found: any language,
     * typos, other words for the same idea); tapping one opens its section, scrolls to the row
     * and marks it, or opens the editor for what only the Layout Editor edits.
     */
    private void buildSearch(LinearLayout content) {
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(style.dp(12), 0, style.dp(4), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(style.dp(14));
        bg.setColor(0x14FFFFFF);
        searchBar.setBackground(bg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(18), style.dp(18));
        iconLp.rightMargin = style.dp(8);
        searchBar.addView(style.kindView(Kind.SEARCH, PanelStyle.COL_SUMMARY, 18), iconLp);
        searchField = new android.widget.EditText(context);
        searchField.setSingleLine(true);
        searchField.setHint(uiStrings.get("settings_search_hint", "Search settings"));
        searchField.setTextColor(PanelStyle.COL_TITLE);
        searchField.setHintTextColor(PanelStyle.COL_SUMMARY);
        searchField.setTextSize(15);
        searchField.setBackground(null);
        searchField.setPadding(0, style.dp(10), 0, style.dp(10));
        searchField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        searchBar.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.ImageView clear = style.kindView(Kind.CLOSE, PanelStyle.COL_SUMMARY, 16);
        clear.setPadding(style.dp(8), style.dp(8), style.dp(8), style.dp(8));
        clear.setContentDescription(uiStrings.get("settings_ai_cancel", "Cancel"));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> searchField.setText(""));
        searchBar.addView(clear, new LinearLayout.LayoutParams(style.dp(34), style.dp(34)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = style.dp(8);
        content.addView(searchBar, barLp);

        searchResults = new LinearLayout(context);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setVisibility(View.GONE);
        content.addView(searchResults, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Reading every language's strings takes a moment: start as soon as the field is
        // focused, so the first letter typed does not wait for it.
        searchField.setOnFocusChangeListener((v, focused) -> {
            if (focused && searchIndex == null) {
                Thread warm = new Thread(this::searchIndex, "SettingsSearchIndex");
                warm.setDaemon(true);
                warm.start();
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String query = text.toString();
                boolean searching = !query.trim().isEmpty();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (sectionsContainer != null) sectionsContainer.setVisibility(searching ? View.GONE : View.VISIBLE);
                searchResults.setVisibility(searching ? View.VISIBLE : View.GONE);
                if (!searching) {
                    searchResults.removeAllViews();
                    return;
                }
                renderSearchResults(query);
            }
        });
    }

    private void hideKeyboard() {
        if (searchField == null) return;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
    }

    /** Everything searchable, built once per search session from what the panel shows now. */
    private synchronized com.eza.spicyex.settings.SettingsSearch searchIndex() {
        if (searchIndex != null) return searchIndex;
        List<com.eza.spicyex.settings.SettingsSearch.Entry> entries = new ArrayList<>();
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = groupVisibleSettings();
        List<Settings.Section> sections = new ArrayList<>(grouped.keySet());
        sections.add(Settings.DEBUG);
        for (Settings.Section section : sections) {
            String name = uiStrings.section(section);
            entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(section,
                    names(name, SettingsUiResourceNames.section(section), section.label), "", section.id,
                    sectionKeywords(section)));
            List<Settings.Setting<?>> items = grouped.get(section);
            if (items == null) continue;
            for (Settings.Setting<?> setting : items) {
                if (setting == Settings.LYRICS_SOURCE_OVERRIDE || setting == Settings.LYRICS_SOURCE_ORDER) continue;
                entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(setting,
                        names(searchTitle(setting), SettingsUiResourceNames.setting(setting), setting.label),
                        name, section.id, settingKeywords(setting, section)));
            }
        }
        // What only the Layout Editor edits: found here too, opening the editor.
        String lyricsEditor = uiStrings.get("settings_search_editor_lyrics", "Layout editor · Lyrics screen");
        String cardEditor = uiStrings.get("settings_search_editor_card", "Layout editor · Now playing");
        for (Settings.Setting<?> setting : com.eza.spicyex.hooks.LayoutEditorSettings.covered()) {
            boolean card = com.eza.spicyex.hooks.LayoutEditorSettings.isCardSetting(setting);
            List<String> extra = settingKeywords(setting, null);
            extra.add("layout editor");
            extra.add(uiStrings.section(Settings.LYRICS_SCREEN));
            entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(
                    new EditorTarget(card ? EDITOR_CARD : EDITOR_LYRICS, setting),
                    names(searchTitle(setting), SettingsUiResourceNames.setting(setting), setting.label),
                    card ? cardEditor : lyricsEditor, card ? "editor_card" : "editor_lyrics", extra));
        }
        // Synonym groups: each language's own words, from its strings file.
        List<List<String>> concepts = new ArrayList<>();
        for (String id : com.eza.spicyex.settings.SettingsSearch.CONCEPT_IDS) {
            concepts.add(com.eza.spicyex.settings.SettingsSearch.mergeTerms(
                    uiStrings.inEveryLanguage("search_terms_" + id)));
        }
        searchIndex = new com.eza.spicyex.settings.SettingsSearch(entries, concepts);
        return searchIndex;
    }

    /** A name as shown, then as every shipped language has it, then the English built-in. */
    private List<String> names(String shown, String resource, String builtIn) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(shown);
        out.addAll(uiStrings.inEveryLanguage(resource));
        if (builtIn != null) out.add(builtIn);
        return new ArrayList<>(out);
    }

    private String searchTitle(Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            return uiStrings.get("settings_source_list_title", "Sources");
        }
        return uiStrings.setting(setting);
    }

    private List<String> sectionKeywords(Settings.Section section) {
        List<String> extra = new ArrayList<>();
        extra.add(section.id.replace('_', ' '));
        if (section == Settings.LYRICS_SOURCES) {
            extra.add("Apple Music Musixmatch LRCLIB NetEase QQ Music Spotify cache");
            extra.add(uiStrings.get("settings_cache_title", "Stored lyrics"));
        }
        return extra;
    }

    /** The words that describe a setting besides its name: key, section, option labels. */
    private List<String> settingKeywords(Settings.Setting<?> setting, Settings.Section section) {
        List<String> extra = new ArrayList<>();
        extra.add(setting.key.replace('_', ' '));
        if (section != null) extra.add(section.label);
        // Words written for search, in every language that has them.
        extra.addAll(uiStrings.inEveryLanguage("settings_search_" + setting.key));
        if (setting instanceof Settings.StringSetting && setting.allowedValues != null) {
            for (Object value : setting.allowedValues) {
                String raw = String.valueOf(value);
                extra.add(raw);
                extra.add(uiStrings.option((Settings.StringSetting) setting, raw));
            }
        }
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            extra.add("Apple Music Musixmatch LRCLIB NetEase QQ Music Spotify smart order ranking");
            extra.add(uiStrings.section(Settings.LYRICS_SOURCES));
        }
        if (setting == Settings.CACHE_SIZE) {
            extra.add(uiStrings.get("settings_cache_title", "Stored lyrics"));
            extra.add(uiStrings.get("settings_cache_clear", "Clear"));
        }
        return extra;
    }

    private void renderSearchResults(String query) {
        searchResults.removeAllViews();
        List<com.eza.spicyex.settings.SettingsSearch.Result> results = searchIndex().search(query, 12, 4);
        if (results.isEmpty()) {
            TextView empty = style.text(uiStrings.get("settings_search_empty", "No matching settings"), 14,
                    PanelStyle.COL_SUMMARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, style.dp(32), 0, style.dp(32));
            searchResults.addView(empty);
            return;
        }
        boolean relatedShown = false;
        for (com.eza.spicyex.settings.SettingsSearch.Result result : results) {
            if (result.related && !relatedShown) {
                relatedShown = true;
                TextView caption = style.text(uiStrings.get("settings_search_related", "Related"), 13,
                        PanelStyle.COL_SECTION, true);
                caption.setPadding(style.dp(6), style.dp(14), 0, style.dp(4));
                searchResults.addView(caption);
            }
            searchResults.addView(searchResultRow(result.entry));
        }
    }

    private View searchResultRow(final com.eza.spicyex.settings.SettingsSearch.Entry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(56));
        row.setPadding(style.dp(8), style.dp(8), style.dp(6), style.dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(style.dp(14));
        shape.setColor(0x00000000);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), shape, shape));

        Kind icon = entry.target instanceof Settings.Section
                ? PanelStyle.sectionIcon((Settings.Section) entry.target)
                : entry.target instanceof EditorTarget ? Kind.EDIT
                : entry.target instanceof Settings.Setting
                ? PanelStyle.sectionIcon(((Settings.Setting<?>) entry.target).section) : null;
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(20), style.dp(20));
        iconLp.rightMargin = style.dp(14);
        row.addView(style.kindView(icon == null ? Kind.SETTINGS : icon, PanelStyle.COL_SECTION, 18), iconLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = style.text(entry.title, 15, PanelStyle.COL_TITLE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        String detail = entry.place;
        if (entry.target instanceof Settings.StringSetting) {
            Settings.StringSetting setting = (Settings.StringSetting) entry.target;
            String value = labelFor(setting, store.get(setting));
            if (value != null && !value.isEmpty()) detail = detail.isEmpty() ? value : detail + " \u00b7 " + value;
        }
        if (!detail.isEmpty()) {
            TextView sub = style.text(detail, 12, PanelStyle.COL_SUMMARY, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 16),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(24)));
        row.setOnClickListener(v -> openSearchResult(entry));
        return row;
    }

    private void openSearchResult(com.eza.spicyex.settings.SettingsSearch.Entry entry) {
        hideKeyboard();
        Object target = entry.target;
        searchField.setText("");
        if (target instanceof EditorTarget) {
            openEditor(((EditorTarget) target).mode);
        } else if (target instanceof Settings.Section) {
            Settings.Section section = (Settings.Section) target;
            reveal(section, null);
        } else if (target instanceof Settings.Setting) {
            final Settings.Setting<?> setting = (Settings.Setting<?>) target;
            reveal(setting.section, setting);
        }
    }

    /** Expands a section (the panel's sections fold) and scrolls to it, or to one of its rows. */
    private void reveal(Settings.Section section, Settings.Setting<?> setting) {
        if (sectionsContainer == null) return;
        if (!expandedSections.contains(section.id)) {
            expandedSections.add(section.id);
            rebuildSection(section);
        }
        sectionsContainer.postDelayed(() -> {
            if (setting != null) {
                revealRow(setting);
                return;
            }
            View header = findChildByTag(sectionsContainer, PanelTags.header(section));
            if (header != null && scrollRoot != null) {
                scrollRoot.smoothScrollTo(0, Math.max(0, sectionsContainer.getTop() + header.getTop() - style.dp(12)));
            }
        }, 240);
    }

    /** Scrolls a row of an open section into view and marks it with a brief highlight. */
    private void revealRow(Settings.Setting<?> setting) {
        if (sectionsContainer == null || scrollRoot == null) return;
        View cardView = findChildByTag(sectionsContainer, PanelTags.card(setting.section));
        if (!(cardView instanceof LinearLayout)) return;
        View row = findRowIn((LinearLayout) cardView, setting.key);
        if (row == null) return;
        int y = 0;
        for (View v = row; v != null && v != scrollRoot; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            y += v.getTop();
        }
        scrollRoot.smoothScrollTo(0, Math.max(0, y - style.dp(96)));
        final android.graphics.drawable.GradientDrawable mark = new android.graphics.drawable.GradientDrawable();
        mark.setCornerRadius(style.dp(12));
        mark.setColor(PanelStyle.COL_ACCENT);
        mark.setAlpha(0);
        row.setForeground(mark);
        android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(1600);
        pulse.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            // In quickly, hold, then out slowly.
            float level = p < 0.15f ? p / 0.15f : p < 0.45f ? 1f : 1f - (p - 0.45f) / 0.55f;
            mark.setAlpha(Math.round(56 * level));
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                row.setForeground(null);
            }
        });
        pulse.start();
    }

    private void renderHeader(LinearLayout content) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = style.text(uiStrings.appName(), 26, PanelStyle.COL_TITLE, true);
        header.addView(panelTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onToggleSize != null) {
            // Chevrons up/down = collapse toward the top-anchored half panel; down/up = grow.
            android.widget.ImageButton resize = style.headerIconButton(resizeKind(),
                    uiStrings.get("settings_panel_resize", "Resize settings panel"), v -> {
                onToggleSize.run();
                ((android.widget.ImageButton) v).setImageDrawable(
                        new ActionIconDrawable(resizeKind(), PanelStyle.COL_SUMMARY, style.density()));
            });
            header.addView(resize);
        }
        if (onClose != null) {
            header.addView(style.headerIconButton(Kind.CLOSE,
                    uiStrings.get("settings_panel_close", "Close settings panel"), v -> onClose.run()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = style.dp(6);
        content.addView(header, lp);
    }

    private Kind resizeKind() {
        return isHalfSize != null && isHalfSize.getAsBoolean()
                ? Kind.CHEVRONS_DOWN_UP
                : Kind.CHEVRONS_UP_DOWN;
    }

    // --- Section rendering ---

    private void renderSections(LinearLayout content) {
        if (aiAvailable()) aiRows().ensureInitialModelCheck();
        for (Map.Entry<Settings.Section, List<Settings.Setting<?>>> entry
                : groupVisibleSettings().entrySet()) {
            renderSectionGroup(content, entry.getKey(), entry.getValue());
        }
        appendSectionHeader(content, Settings.DEBUG, expandedSections.contains(Settings.DEBUG.id), -1);
        if (expandedSections.contains(Settings.DEBUG.id)) appendDebugCard(content, -1);
    }

    /** Closes this dialog (its usual animated exit), then hands off to the shell: the layout
     *  editor is an overlay on the real lyrics screen, not a separate window. */
    private void openEditor(int mode) {
        if (onClose != null) onClose.run();
        if (onOpenLayoutEditor != null) onOpenLayoutEditor.accept(mode);
    }

    private LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> groupVisibleSettings() {
        PanelSnapshot snapshot = captureSnapshot();
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = new LinkedHashMap<>();
        // Section order and row order are explicit schema data (SettingsUiSchema), not an
        // accident of declaration order in Settings.ALL.
        for (Settings.Section section : SettingsUiSchema.orderedSections()) {
            for (Settings.Setting<?> setting : SettingsUiSchema.orderedSettings(section)) {
                if (!PanelPolicy.shouldRender(setting, snapshot)) continue;
                List<Settings.Setting<?>> items = grouped.get(section);
                if (items == null) {
                    items = new ArrayList<>();
                    grouped.put(section, items);
                }
                items.add(setting);
            }
            // The layout-editor section keeps its editor entries even if every row is hidden.
            if (section == Settings.LYRICS_SCREEN && !grouped.containsKey(section)) {
                grouped.put(section, new ArrayList<>());
            }
        }
        return grouped;
    }

    /** One immutable applied-state snapshot per render pass; policy reads this, never the store. */
    @Override public PanelSnapshot snapshot() {
        PanelSnapshot.Builder snapshot = PanelSnapshot.builder()
                .translationAvailable(FeatureAvailability.translationAvailable())
                .transliterationAvailable(FeatureAvailability.transliterationAvailable())
                .languageModelReady(com.eza.spicyex.lyrics.LanguageModelPack.isReady())
                .appleFontAvailable(FeatureAvailability.appleFontAvailable())
                .animatedBackgroundAvailable(FeatureAvailability.animatedBackgroundAvailable())
                .spicySourceEnabled(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.sourceEnabled(
                        context, com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPICY))
                .aiOffered(aiAvailable());
        snapshot.put(Settings.AI_ENABLED, store.get(Settings.AI_ENABLED));
        snapshot.put(Settings.PIP_ENABLED, store.get(Settings.PIP_ENABLED));
        snapshot.put(Settings.PIP_ON_CLOSE, store.get(Settings.PIP_ON_CLOSE));
        snapshot.put(Settings.AD_MODE, store.get(Settings.AD_MODE));
        snapshot.put(Settings.DOUBLE_TAP_LIKE, store.get(Settings.DOUBLE_TAP_LIKE));
        snapshot.put(Settings.TAP_SEEK_MODE, store.get(Settings.TAP_SEEK_MODE));
        snapshot.put(Settings.CONNECT_ENABLED, store.get(Settings.CONNECT_ENABLED));
        snapshot.put(Settings.AI_PROVIDER, store.get(Settings.AI_PROVIDER));
        snapshot.put(Settings.TRANSLATION_ENABLED, store.get(Settings.TRANSLATION_ENABLED));
        snapshot.put(Settings.TRANSLITERATION_ENABLED, store.get(Settings.TRANSLITERATION_ENABLED));
        snapshot.put(Settings.BACKGROUND_STYLE, store.get(Settings.BACKGROUND_STYLE));
        snapshot.put(Settings.FORCE_DARK_BACKGROUND, store.get(Settings.FORCE_DARK_BACKGROUND));
        snapshot.put(Settings.ANIMATION_STYLE, store.get(Settings.ANIMATION_STYLE));
        snapshot.put(Settings.LIVE_CARD_ANIMATION, store.get(Settings.LIVE_CARD_ANIMATION));
        snapshot.put(Settings.LYRICS_TEXT_SIZE, store.get(Settings.LYRICS_TEXT_SIZE));
        snapshot.put(Settings.LINE_SPACING, store.get(Settings.LINE_SPACING));
        snapshot.put(Settings.LIVE_CARD_TEXT_SIZE, store.get(Settings.LIVE_CARD_TEXT_SIZE));
        snapshot.put(Settings.TRACK_INFO_TEXT_SIZE, store.get(Settings.TRACK_INFO_TEXT_SIZE));
        return snapshot.build();
    }

    private void renderSectionGroup(LinearLayout content, Settings.Section section,
                                    List<Settings.Setting<?>> items) {
        boolean expanded = expandedSections.contains(section.id);
        appendSectionHeader(content, section, expanded, -1);
        if (!expanded) return;
        // The AI section's remaining rows are not settings: a key that must not persist as it
        // is typed, and a model list that has to be fetched before it can be offered.
        appendSectionCard(content, section, items, -1);
    }

    /** Card for a settings section; AI gets its non-setting rows appended after the settings. */
    private void appendSectionCard(LinearLayout parent, Settings.Section section,
                                   List<Settings.Setting<?>> items, int at) {
        LinearLayout card = style.newCard();
        card.setTag(PanelTags.card(section));
        for (Settings.Setting<?> setting : items) renderSetting(card, setting);
        appendEditorActionRows(card, section);
        if (section == Settings.AI && aiAvailable()) {
            // Dynamic AI rows churn with setup state; they live in their own tagged block so
            // keyed rebinding refreshes them as a unit without touching ordinary rows.
            LinearLayout dynamic = new LinearLayout(context);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setTag(PanelTags.AI_DYNAMIC);
            aiRows().render(dynamic);
            card.addView(dynamic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (section == Settings.AD_FREE) {
            // Same tagged-block treatment as the AI rows above: Connect's sign-in and status rows
            // appear/disappear with the receiver toggle, which per-key row sync never touches.
            LinearLayout dynamic = new LinearLayout(context);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setTag(PanelTags.CONNECT_DYNAMIC);
            if (Boolean.TRUE.equals(store.get(Settings.CONNECT_ENABLED))) renderConnectLogin(dynamic);
            card.addView(dynamic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        style.attachCard(parent, card, at);
    }

    private void appendDebugCard(LinearLayout parent, int at) {
        LinearLayout card = style.newCard();
        card.setTag(PanelTags.card(Settings.DEBUG));
        renderActions(card);
        renderStatus(card);
        renderDiagnostics(card);
        style.attachCard(parent, card, at);
    }

    // --- Anchor-preserving rebuilds ---
    // scrollY alone orphans the reader's anchor when a section above folds; anchor on the
    // first header/card boundary visible at viewport top instead.

    private void captureAnchor() {
        anchorTag = null;
        anchorDelta = 0;
        if (scrollRoot == null || sectionsContainer == null) return;
        int scrollY = scrollRoot.getScrollY();
        int bottom = scrollY + Math.max(1, scrollRoot.getHeight());
        int base = sectionsContainer.getTop();
        for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
            View child = sectionsContainer.getChildAt(i);
            if (!(child.getTag() instanceof String)) continue;
            int absTop = base + child.getTop();
            if (absTop >= scrollY && absTop < bottom) {
                anchorTag = (String) child.getTag();
                anchorDelta = absTop - scrollY;
                return;
            }
        }
        // Nothing starts inside the viewport: anchor on the last boundary above it.
        for (int i = sectionsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = sectionsContainer.getChildAt(i);
            if (!(child.getTag() instanceof String)) continue;
            int absTop = base + child.getTop();
            if (absTop <= scrollY) {
                anchorTag = (String) child.getTag();
                anchorDelta = absTop - scrollY; // ≤ 0
                return;
            }
        }
    }

    private void restoreAnchor() {
        if (scrollRoot == null || sectionsContainer == null || anchorTag == null) return;
        final String tag = anchorTag;
        final int delta = anchorDelta;
        final ViewTreeObserver observer = scrollRoot.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (scrollRoot == null) return true;
                ViewTreeObserver currentObserver = scrollRoot.getViewTreeObserver();
                if (currentObserver.isAlive()) {
                    currentObserver.removeOnPreDrawListener(this);
                } else if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                if (sectionsContainer == null) return true;
                for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
                    View child = sectionsContainer.getChildAt(i);
                    if (!tag.equals(child.getTag())) continue;
                    scrollRoot.scrollTo(0,
                            Math.max(0, sectionsContainer.getTop() + child.getTop() - delta));
                    break;
                }
                return true;
            }
        });
    }

    private void retargetAnchorToHeader(Settings.Section target) {
        int headerIdx = indexOfChildByTag(PanelTags.header(target));
        if (headerIdx < 0 || scrollRoot == null) return;
        View header = sectionsContainer.getChildAt(headerIdx);
        anchorTag = PanelTags.header(target);
        anchorDelta = sectionsContainer.getTop() + header.getTop() - scrollRoot.getScrollY();
    }

    // --- Setting dispatch ---

    private void renderSetting(LinearLayout content, Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            sources.rows(content);
            return;
        }
        if (setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_ORDER) {
            return;
        }
        if (setting == Settings.SPICY_MANUAL_TOKEN) {
            spicyTokenRow(content);
            return;
        }
        if (setting == Settings.CACHE_SIZE) {
            // The limit row with the stored-lyrics block under it, one keyed unit: patchRow
            // still finds the limit's summary inside, and the block refreshes itself.
            LinearLayout unit = new LinearLayout(context);
            unit.setOrientation(LinearLayout.VERTICAL);
            rows.selectorRow(unit, Settings.CACHE_SIZE);
            if (unit.getChildCount() > 0) unit.getChildAt(0).setTag(null);
            cache.block(unit);
            unit.setTag(PanelTags.row(Settings.CACHE_SIZE));
            content.addView(unit, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        if (setting == Settings.LYRICS_FONT_CUSTOM_PATH) {
            lyricsFontPathRow(content);
            return;
        }
        if (setting == Settings.DOWNLOAD_LANGUAGE_MODELS) {
            downloadLanguageModelsRow(content);
            return;
        }
        // Renderer dispatch follows the UI schema; composite rows above stay hand-built.
        SettingUiSpec.RowKind kind = SettingsUiSchema.kindOf(setting);
        if (kind == SettingUiSpec.RowKind.TOGGLE && setting instanceof Settings.BooleanSetting) {
            rows.switchRow(content, (Settings.BooleanSetting) setting);
        } else if (kind == SettingUiSpec.RowKind.STEPPER && setting instanceof Settings.IntegerSetting) {
            rows.stepperRow(content, (Settings.IntegerSetting) setting);
        } else if (setting instanceof Settings.StringSetting) {
            Settings.StringSetting s = (Settings.StringSetting) setting;
            if (kind == SettingUiSpec.RowKind.TEXT_FIELD) rows.textFieldRow(content, s);
            else if (setting == Settings.UI_LANGUAGE) {
                rows.selectorRow(content, s, uiStrings.availableUiLanguages(), null);
            } else rows.selectorRow(content, s);
        }
    }

    /**
     * True when the AI star should be lit: the whole family is configured and could run now.
     *
     * <p>Falls back to the enable flag only before the AI rows exist, which is the one moment
     * nothing can be asked about credentials.
     */
    private boolean aiReady() {
        return aiSettingsRows != null ? aiSettingsRows.isReady()
                : Boolean.TRUE.equals(store.get(Settings.AI_ENABLED));
    }

    private void rebuildSections() {
        searchIndex = null;
        sectionReflowGeneration++;
        if (sectionsContainer == null) return;
        captureAnchor();
        aiBadgeView = null;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
        restoreAnchor();
    }

    /**
     * Re-renders one section (header + card) in place instead of tearing down the whole panel.
     * Gating toggles, selector picks and section folds land here; UI_LANGUAGE still takes the
     * full path because every label changes. Anchor-preserving either way.
     *
     * <p>The header is one fixed-height row and updates in place; card rows rebind by stable
     * ID (ordinary rows patch in place, composites swap in place). DEBUG carries live values
     * and re-renders its card as a unit.
     */
    private void rebuildSection(Settings.Section target) {
        if (sectionsContainer == null) return;
        // What is visible may have changed; the search index is rebuilt from it on next use.
        searchIndex = null;
        int headerIdx = indexOfChildByTag(PanelTags.header(target));
        if (headerIdx < 0) {
            rebuildSections();
            return;
        }
        captureAnchor();
        Map<View, Float> previousTops = new java.util.IdentityHashMap<>();
        if (Motion.animationsEnabled() && scrollRoot != null && sectionsContainer.isLaidOut()) {
            for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
                View child = sectionsContainer.getChildAt(i);
                float top = child.getTop() + child.getTranslationY() - scrollRoot.getScrollY();
                child.animate().cancel();
                previousTops.put(child, top);
            }
        }
        boolean expanded = expandedSections.contains(target.id);
        if (!expanded && PanelTags.card(target).equals(anchorTag)) {
            retargetAnchorToHeader(target);
        }

        View headerView = sectionsContainer.getChildAt(headerIdx);
        if (headerView != null) {
            View chevron = headerView.findViewWithTag(TAG_SECTION_CHEVRON);
            if (chevron != null) {
                float targetRotation = expanded ? 90f : 0f;
                if (!Motion.animationsEnabled()) {
                    chevron.animate().cancel();
                    chevron.setRotation(targetRotation);
                } else {
                    chevron.animate().rotation(targetRotation).setDuration(Motion.dur(Motion.REVEAL)).start();
                }
            }
            if (target == Settings.AI && aiBadgeView != null) {
                aiBadgeView.setImageDrawable(new ActionIconDrawable(Kind.SPARKLES,
                        aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, style.density()));
            }
        }

        if (!expanded) {
            int staleCard = indexOfChildByTag(PanelTags.card(target));
            if (staleCard >= 0) sectionsContainer.removeViewAt(staleCard);
        } else if (target == Settings.DEBUG) {
            int cardIdx = indexOfChildByTag(PanelTags.card(target));
            if (cardIdx >= 0) sectionsContainer.removeViewAt(cardIdx);
            appendDebugCard(sectionsContainer, headerIdx + 1);
        } else {
            rebindCard(target, headerIdx);
        }
        restoreAnchor();
        animateSectionReflow(previousTops);
    }

    private void animateSectionReflow(Map<View, Float> previousTops) {
        final int generation = ++sectionReflowGeneration;
        if (previousTops.isEmpty()) return;
        final ViewTreeObserver observer = sectionsContainer.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                if (generation != sectionReflowGeneration || !sectionsContainer.isAttachedToWindow()) return true;
                for (Map.Entry<View, Float> entry : previousTops.entrySet()) {
                    View child = entry.getKey();
                    if (child.getParent() != sectionsContainer) continue;
                    float offset = entry.getValue() - (child.getTop() - scrollRoot.getScrollY());
                    child.setTranslationY(Motion.animationsEnabled() ? offset : 0f);
                    child.animate().translationY(0f).setDuration(Motion.dur(Motion.BASE))
                            .setInterpolator(Motion.decel()).start();
                }
                return true;
            }
        });
    }

    /** Keyed card sync: stale rows out, the rest reused by ID and patched, missing rows built. */
    private void rebindCard(Settings.Section target, int headerIdx) {
        List<Settings.Setting<?>> items = groupVisibleSettings().get(target);
        if (items == null || items.isEmpty()) {
            rebuildSections(); // defensive: rendered section without visible settings
            return;
        }
        int cardIdx = indexOfChildByTag(PanelTags.card(target));
        LinearLayout card;
        if (cardIdx < 0 || !(sectionsContainer.getChildAt(cardIdx) instanceof LinearLayout)) {
            card = style.newCard();
            card.setTag(PanelTags.card(target));
            style.attachCard(sectionsContainer, card, headerIdx + 1);
        } else {
            card = (LinearLayout) sectionsContainer.getChildAt(cardIdx);
        }
        PanelSnapshot snapshot = captureSnapshot();
        Map<String, Settings.Setting<?>> byKey = new java.util.HashMap<>();
        List<String> visibleKeys = new ArrayList<>();
        for (Settings.Setting<?> setting : items) {
            // The merged source row owns MODE; OVERRIDE and ORDER render nothing on their own.
            if (setting == Settings.LYRICS_SOURCE_OVERRIDE
                    || setting == Settings.LYRICS_SOURCE_ORDER) {
                continue;
            }
            byKey.put(setting.key, setting);
            visibleKeys.add(setting.key);
        }
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < card.getChildCount(); i++) {
            String key = PanelTags.keyOf(card.getChildAt(i).getTag());
            if (key != null) currentKeys.add(key);
        }
        RowSyncPlan plan = RowSyncPlan.of(visibleKeys, currentKeys);
        // The AI dynamic block re-renders as a unit; detach it so positions count rows only.
        View dynamic = findChildByTag(card, PanelTags.AI_DYNAMIC);
        if (dynamic != null) card.removeView(dynamic);
        View connectDynamic = findChildByTag(card, PanelTags.CONNECT_DYNAMIC);
        if (connectDynamic != null) card.removeView(connectDynamic);
        for (String dead : plan.removals) {
            View stale = findRowIn(card, dead);
            if (stale != null) card.removeView(stale);
        }
        for (int i = 0; i < plan.order.size(); i++) {
            String key = plan.order.get(i);
            Settings.Setting<?> setting = byKey.get(key);
            if (setting == null) continue;
            View row = findRowIn(card, key);
            if (row == null || SettingsUiSchema.isComposite(setting)) {
                if (row != null) card.removeView(row);
                renderSetting(card, setting); // appends fresh; repositioned below
                row = card.getChildAt(card.getChildCount() - 1);
            } else {
                rows.patchRow(row, setting, snapshot);
            }
            if (row != null) {
                card.removeView(row);
                card.addView(row, Math.min(i, card.getChildCount()));
            }
        }
        refreshAiDynamicBlock(card, target);
        refreshConnectDynamicBlock(card, target);
        syncEditorActionRows(card, target);
    }

    /** Connect's sign-in/status rows appear only while CONNECT_ENABLED is on; same tagged-block
     *  treatment as {@link #refreshAiDynamicBlock}, since toggling it is a cross-row visibility
     *  change the ordinary per-key row sync never touches. */
    private void refreshConnectDynamicBlock(LinearLayout card, Settings.Section target) {
        View dynamic = findChildByTag(card, PanelTags.CONNECT_DYNAMIC);
        if (target != Settings.AD_FREE) {
            if (dynamic != null) card.removeView(dynamic);
            return;
        }
        LinearLayout block;
        if (dynamic instanceof LinearLayout) {
            block = (LinearLayout) dynamic;
            block.removeAllViews();
        } else {
            block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag(PanelTags.CONNECT_DYNAMIC);
            card.addView(block, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (Boolean.TRUE.equals(store.get(Settings.CONNECT_ENABLED))) renderConnectLogin(block);
    }

    /** Keeps non-setting editor launch rows present on both full renders and keyed section
     * rebuilds. These rows have no preference key, so the ordinary RowSyncPlan cannot own them. */
    private void syncEditorActionRows(LinearLayout card, Settings.Section section) {
        removeChildByTag(card, TAG_LAYOUT_EDITOR_ACTION);
        removeChildByTag(card, TAG_CARD_EDITOR_ACTION);
        appendEditorActionRows(card, section);
    }

    private void appendEditorActionRows(LinearLayout card, Settings.Section section) {
        if (section != Settings.LYRICS_SCREEN) return;
        // Everything about how the lyrics screen and the now-playing card look is edited on the
        // screen itself; tap behaviour and transition feel stay in the ordinary settings rows.
        rows.actionRow(card, Kind.ALIGN_VERTICAL_DISTRIBUTE_CENTER,
                uiStrings.get("settings_layout_editor", "Layout editor…"),
                v -> openEditor(EDITOR_LYRICS));
        card.getChildAt(card.getChildCount() - 1).setTag(TAG_LAYOUT_EDITOR_ACTION);
        rows.actionRow(card, Kind.ALIGN_VERTICAL_DISTRIBUTE_CENTER,
                uiStrings.get("settings_card_editor", "Now playing card editor…"),
                v -> openEditor(EDITOR_CARD));
        card.getChildAt(card.getChildCount() - 1).setTag(TAG_CARD_EDITOR_ACTION);
    }

    private static void removeChildByTag(LinearLayout parent, String tag) {
        View child = findChildByTag(parent, tag);
        if (child != null) parent.removeView(child);
    }

    /** AI dynamic rows re-render as one tagged block; ordinary AI settings rows patch by key. */
    private void refreshAiDynamicBlock(LinearLayout card, Settings.Section target) {
        View dynamic = findChildByTag(card, PanelTags.AI_DYNAMIC);
        if (target != Settings.AI || !aiAvailable()) {
            if (dynamic != null) card.removeView(dynamic);
            return;
        }
        LinearLayout block;
        if (dynamic instanceof LinearLayout) {
            block = (LinearLayout) dynamic;
            block.removeAllViews();
        } else {
            block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag(PanelTags.AI_DYNAMIC);
            card.addView(block, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        aiRows().render(block);
    }

    private View findRowIn(LinearLayout card, String key) {
        return findChildByTag(card, PanelTags.row(key));
    }

    private static View findChildByTag(LinearLayout parent, String tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (tag.equals(parent.getChildAt(i).getTag())) return parent.getChildAt(i);
        }
        return null;
    }

    private int indexOfChildByTag(String tag) {
        for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
            if (tag.equals(sectionsContainer.getChildAt(i).getTag())) return i;
        }
        return -1;
    }

    /** UI language rebuilds every label; dependency settings rebuild only their own section. */
    @Override public void onSettingChanged(Settings.Setting<?> setting) {
        // Double tap is one gesture: turning on double-tap to like takes it from seeking.
        if (setting == Settings.DOUBLE_TAP_LIKE && Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))
                && "Double tap".equals(store.get(Settings.TAP_SEEK_MODE))) {
            writer.put(Settings.TAP_SEEK_MODE, "Off");
        }
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            com.eza.spicyex.lyrics.session.LyricsSourcePreferences.setRankingMode(context,
                    com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode.parse(
                            String.valueOf(store.get(setting))));
        }
        if (setting == Settings.CONNECT_ENABLED) {
            com.eza.spicyex.hooks.SpotifyConnectHook.onSettingsChanged(context,
                    Boolean.TRUE.equals(store.get(Settings.CONNECT_ENABLED)));
        }
        if (setting == Settings.UI_LANGUAGE) {
            rebuildSections();
        } else if (setting == Settings.ANIMATION_STYLE) {
            // The Apple Music card appears/disappears with this pick (a cross-section change),
            // so the whole panel rebuilds anchor-preserved instead of one section in place.
            if ("Apple Music".equals(String.valueOf(store.get(setting)))) {
                expandedSections.add(Settings.APPLE.id);
            }
            rebuildSections();
        } else if (PanelPolicy.shouldRebuildSectionAfterChange(setting)) {
            rebuildSection(setting.section);
        }
    }

    /**
     * Full-only. AI needs no on-device language packages, so nothing stops it running in Lite —
     * which is exactly the problem: Lite would gain translation while its own capability flag says
     * it has none. Until that flag is untangled, the family is not offered there.
     */
    private static boolean aiAvailable() {
        return FeatureAvailability.translationAvailable();
    }

    /** Section headers --- */

    private LinearLayout buildSectionHeader(Settings.Section section, boolean expanded) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(44));
        row.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row.setTag(PanelTags.header(section));

        Kind sectionIcon = PanelStyle.sectionIcon(section);
        if (sectionIcon != null) {
            ImageView sectionIconView = style.kindView(sectionIcon,
                    section == Settings.AI && aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, 18);
            if (section == Settings.AI) aiBadgeView = sectionIconView;
            row.addView(sectionIconView, style.leadParams());
        }

        TextView title = style.text(uiStrings.section(section), 14, PanelStyle.COL_TITLE, true);
        title.setAllCaps(true);
        title.setLetterSpacing(0.05f);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 16);
        chevron.setTag(TAG_SECTION_CHEVRON);
        chevron.setRotation(expanded ? 90f : 0f);
        row.addView(chevron,
                new LinearLayout.LayoutParams(style.dp(28), style.dp(28)));
        row.setOnClickListener(v -> {
            boolean nowExpanded = !expandedSections.contains(section.id);
            if (nowExpanded) expandedSections.add(section.id);
            else expandedSections.remove(section.id);
            rebuildSection(section);
        });
        return row;
    }

    private void appendSectionHeader(LinearLayout parent, Settings.Section section,
                                     boolean expanded, int at) {
        View row = buildSectionHeader(section, expanded);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = style.dp(4);
        if (at < 0 || at >= parent.getChildCount()) parent.addView(row, lp);
        else parent.addView(row, at, lp);
    }

    // --- Credentials row (composite) ---

    private void spicyTokenRow(LinearLayout content) {
        String masked = SpicyManualTokenStore.masked(context);
        List<AiSettingsRows.IconAction> actions = new ArrayList<>();
        actions.add(new AiSettingsRows.IconAction(Kind.EDIT,
                uiStrings.get("settings_spicy_token_edit", "Edit token"), v -> dialogs.promptSpicyToken()));
        if (!masked.isEmpty()) {
            actions.add(new AiSettingsRows.IconAction(Kind.VISIBILITY,
                    uiStrings.get("settings_spicy_token_reveal", "Reveal token"),
                    v -> dialogs.revealSpicyToken()));
            actions.add(new AiSettingsRows.IconAction(Kind.DELETE,
                    uiStrings.get("settings_spicy_token_delete", "Delete token"), v -> {
                SpicyManualTokenStore.delete(context);
                rebuildSection(Settings.LYRICS_SOURCES);
            }));
        }
        rows.aiFieldRow(content, uiStrings.setting(Settings.SPICY_MANUAL_TOKEN),
                masked.isEmpty() ? uiStrings.get("settings_spicy_token_absent", "Not set") : masked,
                false, Settings.SPICY_MANUAL_TOKEN.key, v -> dialogs.promptSpicyToken(),
                actions.toArray(new AiSettingsRows.IconAction[0]));
    }

    private void lyricsFontPathRow(LinearLayout content) {
        String path = store.get(Settings.LYRICS_FONT_CUSTOM_PATH);
        String display = path == null || path.isEmpty()
                ? uiStrings.get("settings_lyrics_font_path_absent", "Not set") : path;
        List<AiSettingsRows.IconAction> actions = new ArrayList<>();
        actions.add(new AiSettingsRows.IconAction(Kind.EDIT,
                uiStrings.get("settings_lyrics_font_path_edit", "Edit font path"),
                v -> dialogs.promptLyricsFontPath()));
        rows.aiFieldRow(content, uiStrings.setting(Settings.LYRICS_FONT_CUSTOM_PATH),
                display, false, Settings.LYRICS_FONT_CUSTOM_PATH.key,
                v -> dialogs.promptLyricsFontPath(),
                actions.toArray(new AiSettingsRows.IconAction[0]));
        String coverage = fontCoverageSummaryForPanel(path);
        if (!coverage.isEmpty()) {
            TextView cov = style.text(coverage, 12, PanelStyle.COL_SUMMARY, false);
            cov.setPadding(style.dp(52), 0, style.dp(16), style.dp(12));
            content.addView(cov, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private String fontCoverageSummaryForPanel(String path) {
        if (path == null || path.isEmpty()) return "";
        android.graphics.Typeface typeface = null;
        java.io.File file = new java.io.File(path);
        if (file.isFile()) {
            try {
                typeface = android.graphics.Typeface.createFromFile(file);
            } catch (Throwable ignored) {
            }
        }
        if (typeface == null) typeface = android.graphics.Typeface.create(path, android.graphics.Typeface.NORMAL);
        java.util.List<String> missing = com.eza.spicyex.lyrics.LyricsFontValidator.missingScripts(typeface);
        return missing.isEmpty()
                ? uiStrings.get("settings_lyrics_font_check_all_covered", "Covers every supported language")
                : uiStrings.get("settings_lyrics_font_check_missing", "Falls back for") + ": "
                        + String.join(", ", missing);
    }

    private void refreshLanguageModelDownloadStatus() {
        languageModelPollQueued = false;
        if (!panelAttached) return;
        LanguageModelPack.DownloadStatus status = LanguageModelPack.status();
        // Rebuild once more after the worker switches to READY or ERROR; otherwise the polling
        // loop would stop before the terminal state became visible in the panel.
        if (status.phase == LanguageModelPack.Phase.READY) rebuildSections();
        else rebuildSection(Settings.TRANSLITERATION);
        if (status.phase == LanguageModelPack.Phase.DOWNLOADING) {
            languageModelPollQueued = true;
            uiHandler.postDelayed(this::refreshLanguageModelDownloadStatus, 500);
        }
    }

    /**
     * Starts the status loop for a panel that was built while an install was already running.
     *
     * <p>Detach cancels the queued tick, and the host builds a fresh panel on every open, so
     * without this the reopened panel shows the progress it rendered once and never notices the
     * worker reach READY — leaving the transliteration toggle disabled after a successful install.
     */
    private void resumeLanguageModelDownloadPolling() {
        if (languageModelPollQueued) return;
        languageModelPollQueued = true;
        uiHandler.postDelayed(this::refreshLanguageModelDownloadStatus, 500);
    }

    private void downloadLanguageModelsRow(LinearLayout content) {
        LanguageModelPack.DownloadStatus status = LanguageModelPack.status();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(Settings.DOWNLOAD_LANGUAGE_MODELS.key));
        row.setOnClickListener(v -> {
            if (LanguageModelPack.isReady()) return;
            if (status.phase == LanguageModelPack.Phase.ERROR) {
                LanguageModelPack.clearTransientState();
            }
            LanguageModelPack.requestDownload();
            rebuildSection(Settings.TRANSLITERATION);
            refreshLanguageModelDownloadStatus();
        });

        TextView title = style.text(uiStrings.setting(Settings.DOWNLOAD_LANGUAGE_MODELS), 16, PanelStyle.COL_TITLE, false);
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(title);

        String summary;
        String small = "";
        if (status.phase == LanguageModelPack.Phase.DOWNLOADING) {
            summary = uiStrings.get("settings_language_model_downloading", "Downloading…");
            small = uiStrings.get("settings_language_model_progress", status.progressPercent + "%");
        } else if (status.phase == LanguageModelPack.Phase.ERROR) {
            summary = uiStrings.get("settings_language_model_failed", "Download failed");
            small = status.errorCode.isEmpty()
                    ? uiStrings.get("settings_language_model_retry", "Tap to retry")
                    : status.errorCode;
        } else {
            summary = uiStrings.get("settings_language_model_idle", "Tap to download");
            small = uiStrings.get("settings_language_model_size", "Optional language pack");
        }

        TextView statusView = style.text(summary, 12, PanelStyle.COL_SUMMARY, false);
        statusView.setPadding(0, style.dp(2), 0, 0);
        info.addView(statusView);

        if (status.phase == LanguageModelPack.Phase.DOWNLOADING || status.phase == LanguageModelPack.Phase.ERROR) {
            ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(status.phase == LanguageModelPack.Phase.DOWNLOADING ? status.progressPercent : 0);
            progress.setIndeterminate(status.phase == LanguageModelPack.Phase.DOWNLOADING && status.progressPercent <= 0);
            progress.setPadding(0, style.dp(8), 0, style.dp(6));
            info.addView(progress, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!small.isEmpty()) {
            TextView extra = style.text(small, 11, status.phase == LanguageModelPack.Phase.ERROR ? 0xFFFFB4B4 : PanelStyle.COL_SECTION, false);
            extra.setPadding(0, style.dp(2), 0, 0);
            info.addView(extra);
        }

        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(style.kindView(Kind.LANGUAGES, PanelStyle.COL_ACCENT, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
    }

    // --- Connect rows ---

    private void renderConnectLogin(LinearLayout card) {
        // Labels follow the player's own answer (session cookie confirmed by the live page).
        // The sign-in row always opens the sign-in screen, which shows "signed in" and closes
        // itself when there is nothing to do.
        TextView login = rows.actionRow(card, Kind.GLOBE,
                uiStrings.get("settings_connect_checking", "Checking Spotify sign-in…"),
                v -> com.eza.spicyex.hooks.SpotifyConnectHook.openLogin(context));
        TextView player = rows.infoRow(card,
                uiStrings.get("settings_connect_status_label", "Player"),
                uiStrings.get("settings_connect_status_starting", "Starting…"));
        TextView device = rows.infoRow(card,
                uiStrings.get("settings_connect_device_label", "Device"),
                "—");
        java.lang.ref.WeakReference<TextView> loginRef = new java.lang.ref.WeakReference<>(login);
        java.lang.ref.WeakReference<TextView> playerRef = new java.lang.ref.WeakReference<>(player);
        java.lang.ref.WeakReference<TextView> deviceRef = new java.lang.ref.WeakReference<>(device);
        com.eza.spicyex.hooks.SpotifyConnectHook.queryStatus(context, (code, deviceId, activeId, playing) -> {
            TextView loginLabel = loginRef.get();
            TextView playerValue = playerRef.get();
            TextView deviceValue = deviceRef.get();
            if (loginLabel == null || playerValue == null || deviceValue == null) return;
            if (code == com.eza.spicyex.hooks.SpotifyConnectHook.WARM_STARTING) return;
            if (code != com.eza.spicyex.hooks.SpotifyConnectHook.WARM_READY) {
                loginLabel.setText(uiStrings.get("settings_connect_login", "Login required · Sign in to Spotify"));
                playerValue.setText(code == com.eza.spicyex.hooks.SpotifyConnectHook.WARM_LOGIN_REQUIRED
                        ? uiStrings.get("settings_connect_status_login", "Sign-in required")
                        : uiStrings.get("settings_connect_status_failed", "Not running"));
                deviceValue.setText("—");
                return;
            }
            loginLabel.setText(uiStrings.get("settings_connect_signed_in", "Signed in to Spotify · Manage account"));
            playerValue.setText(uiStrings.get("settings_connect_status_running", "Running"));
            String state;
            if (deviceId == null) {
                state = uiStrings.get("settings_connect_device_registering", "Registering…");
            } else if (playing) {
                state = uiStrings.get("settings_connect_device_playing", "Playing on the web player");
            } else if (deviceId.equals(activeId)) {
                state = uiStrings.get("settings_connect_device_active", "Selected · paused");
            } else {
                state = uiStrings.get("settings_connect_device_idle", "Available");
            }
            deviceValue.setText("Web Player · " + state);
        });
    }

    // --- Diagnostics card ---

    private void renderActions(LinearLayout content) {
        rows.actionRow(content, Kind.BUG,
                DiagnosticReportingDialog.reportProblemLabel(context, store),
                v -> DiagnosticReportingDialog.show(context, store));
        rows.actionRow(content, null,
                uiStrings.get("settings_action_resync_timing", "Reset lyrics sync"),
                v -> {
                    // The panel owns the stored offset; the host re-anchors its playback clock so
                    // the next frame measures from the real position instead of the old offset.
                    writer.put(Settings.SYNC_OFFSET_MS, 0);
                    if (onResyncTiming != null) onResyncTiming.run();
                    android.widget.Toast.makeText(context,
                            uiStrings.get("settings_resync_timing_done", "Lyrics sync reset"),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
        rows.actionRow(content, null,
                uiStrings.get("settings_action_audio_debug", "Audio analysis (diagnostic)"),
                v -> showAudioDebug());
        // Cache clears live with the cache itself, on the Lyrics sources page (CacheManager).
        rows.actionRow(content, Kind.EXTERNAL_LINK,
                uiStrings.get("settings_action_open_github", "Open GitHub"), v -> openGithub());
    }

    /**
     * Live view of the audio analysis behind the beat-reactive background and the instrumental
     * visualizer: the band levels, loudness and beat, the stream format, and how often each
     * AudioTrack#write overload fires. All zeros in the write counts means Spotify is not
     * playing through a Java AudioTrack on this device, so there is nothing to analyse.
     */
    private void showAudioDebug() {
        com.eza.spicyex.ui.PanelDialog dialog = new com.eza.spicyex.ui.PanelDialog(context,
                uiStrings.get("settings_action_audio_debug", "Audio analysis (diagnostic)"));
        com.eza.spicyex.lyrics.InstrumentalVisualizerView bars =
                new com.eza.spicyex.lyrics.InstrumentalVisualizerView(context,
                        () -> com.eza.spicyex.hooks.AudioDebug.spectrum);
        bars.setBands(true);
        bars.setLayoutParams(new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(140 * context.getResources().getDisplayMetrics().density)));
        dialog.add(bars);
        android.widget.TextView stats = dialog.readOnlyBlock("…");
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        long[][] previous = {com.eza.spicyex.hooks.AudioDebug.writeCalls()};
        long[] previousAt = {android.os.SystemClock.uptimeMillis()};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                long[] calls = com.eza.spicyex.hooks.AudioDebug.writeCalls();
                String[] kinds = com.eza.spicyex.hooks.AudioDebug.kinds();
                float seconds = Math.max(0.001f, (now - previousAt[0]) / 1000f);
                StringBuilder out = new StringBuilder();
                out.append(String.format(java.util.Locale.ROOT, "loudness %.2f   kick %.2f   snare %.2f%n",
                        com.eza.spicyex.hooks.AudioDebug.loudness, com.eza.spicyex.hooks.AudioDebug.beat,
                        com.eza.spicyex.hooks.AudioDebug.accent));
                boolean reactive = Boolean.TRUE.equals(store.get(Settings.BEAT_REACTIVE_BACKGROUND));
                out.append("beat-reactive background: ").append(reactive ? "on" : "off").append("\n");
                String format = com.eza.spicyex.hooks.AudioDebug.format();
                out.append(format.isEmpty() ? "format —" : format).append('\n');
                out.append("AudioTrack.write /s:");
                for (int i = 0; i < kinds.length; i++) {
                    out.append(String.format(java.util.Locale.ROOT, "  %s %.0f", kinds[i],
                            (calls[i] - previous[0][i]) / seconds));
                }
                stats.setText(out.toString());
                previous[0] = calls;
                previousAt[0] = now;
                handler.postDelayed(this, 500L);
            }
        };
        com.eza.spicyex.hooks.AudioDebug.watch(true);
        dialog.onDismiss(() -> {
            handler.removeCallbacks(tick);
            com.eza.spicyex.hooks.AudioDebug.watch(false);
        });
        dialog.show();
        handler.postDelayed(tick, 500L);
    }

    @Override public void clearCache(CacheClearKind kind) {
        if (onClearCache == null) return;
        onClearCache.accept(kind);
        // Cache clears update preference memory (and the AI database) before returning. Rebuild
        // the owning row now so its usage summary reflects the clear without closing the panel.
        rebuildSection(Settings.LYRICS_SOURCES);
    }

    private void renderStatus(LinearLayout content) {
        CurrentLyricState s = CurrentLyricState.get();
        String summary = uiStrings.format("settings_status_summary",
                "Last state: %1$s\nTrack: %2$s\nLine: %3$s",
                s.status, s.title, s.originalLine);
        TextView state = style.text(summary, 12, PanelStyle.COL_SUMMARY, false);
        state.setPadding(0, style.dp(4), 0, style.dp(2));
        content.addView(state);
        TextView version = style.text(BuildStamp.FULL, 11, PanelStyle.COL_SECTION, false);
        version.setPadding(0, style.dp(12), 0, 0);
        content.addView(version);
    }

    private void renderDiagnostics(LinearLayout content) {
        LyricsFetchDiagnosticsState.Snapshot s = LyricsFetchDiagnosticsState.get();
        rows.infoRow(content, uiStrings.get("settings_diagnostic_source_chosen", "Source chosen"),
                s.displayedSourceChosen());
        rows.infoRow(content, uiStrings.get("settings_diagnostic_candidates_seen", "Candidates seen"),
                s.candidatesSeen);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_provider", "Provider"), s.provider);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_type_chosen", "Type chosen"),
                s.typeChosen);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_cache_write", "Cache write"),
                yesNo(s.cacheWrite));
    }

    // --- AI rows adapter ---

    /** Adapter giving the AI rows the panel's own row vocabulary, so they look like every other row. */
    private AiSettingsRows aiRows() {
        if (aiSettingsRows != null) return aiSettingsRows;
        aiSettingsRows = new AiSettingsRows(context, new AiSettingsRows.Host() {
            @Override public void info(LinearLayout content, String label, String value) {
                rows.infoRow(content, label, value);
            }

            @Override public void field(LinearLayout content, String label, String value,
                                        View.OnClickListener listener,
                                        AiSettingsRows.IconAction... actions) {
                rows.aiFieldRow(content, label, value, false, null, listener, actions);
            }

            @Override public void selector(LinearLayout content, String label, String value,
                                           View.OnClickListener listener,
                                           AiSettingsRows.IconAction... actions) {
                rows.aiFieldRow(content, label, value, true, null, listener, actions);
            }

            @Override public void rebuild() {
                // A probe can finish after the panel was dismissed; nothing is mounted then.
                if (!panelAttached) return;
                rebuildSection(Settings.AI);
            }

            @Override public void updateAiBadge(boolean live) {
                if (!panelAttached) return;
                // The probe outcome is not what the star reports; setup completeness is. This is
                // only the signal that something about the AI configuration may have moved.
                if (aiBadgeView == null) {
                    rebuildSection(Settings.AI);
                    return;
                }
                aiBadgeView.setImageDrawable(new ActionIconDrawable(Kind.SPARKLES,
                        aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, style.density()));
            }

            @Override public String string(String name, String fallback) {
                return uiStrings.get(name, fallback);
            }
        }, store);
        return aiSettingsRows;
    }

    // --- Misc ---

    private String yesNo(boolean value) {
        return value
                ? uiStrings.get("settings_yes", "yes")
                : uiStrings.get("settings_no", "no");
    }

    private void openGithub() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/amarinne/spicy-ex"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** Option label; magnitude-based selectors show the plain multiplier as the label. */
    @Override public String labelFor(Settings.StringSetting setting, String value) {
        String mult = SettingLabels.multiplierFor(setting.key, value);
        if (mult != null) return "\u00d7" + mult;
        return uiStrings.option(setting, value);
    }

    @Override public String stepperSummary(Settings.IntegerSetting setting) {
        if (setting == Settings.SYNC_OFFSET_MS) {
            return uiStrings.get("settings_sync_offset_summary", "Positive shows lyrics earlier");
        }
        return null;
    }

    /** Explains why a row is unavailable. */
    @Override public String unavailableSummary(Settings.Setting<?> setting) {
        if (setting == Settings.TRANSLITERATION_ENABLED && !LanguageModelPack.isReady()) {
            return uiStrings.setting(Settings.DOWNLOAD_LANGUAGE_MODELS);
        }
        return uiStrings.get("settings_unavailable_full_build", "Full build required");
    }

    @Override public boolean unavailable(Settings.Setting<?> setting) {
        return PanelPolicy.unavailable(setting, captureSnapshot());
    }

    @Override public String cacheLimitLabel() {
        return uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
    }

    @Override public String cacheSizeSummary() {
        String label = uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
        return SettingLabels.cacheUsageSummary(panelStrings, label,
                CacheStoragePolicy.formatBytes(CacheStoragePolicy.storedTotal(context)));
    }

    private PanelSnapshot captureSnapshot() {
        return snapshot();
    }

    // --- SettingRowFactory.Host ---

    @Override public SettingsStore store() {
        return store;
    }

    @Override public SettingsWriter writer() {
        return writer;
    }

    @Override public SettingsUiStrings strings() {
        return uiStrings;
    }

    @Override public PanelStyle style() {
        return style;
    }

    @Override public void openSelector(Settings.StringSetting setting, List<String> values,
                                       TextView valueView) {
        dialogs.openSelector(setting, values, valueView);
    }

    // --- PanelDialogs.Host ---

    /** Writes the value and applies immediate side effects (the UI-language swap). */
    @Override public void onOptionChosen(Settings.StringSetting setting, String value) {
        writer.put(setting, value);
        // Seeking on double tap takes the gesture back from double-tap to like.
        if (setting == Settings.TAP_SEEK_MODE && "Double tap".equals(value)
                && Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))) {
            writer.put(Settings.DOUBLE_TAP_LIKE, false);
            onSettingChanged(Settings.DOUBLE_TAP_LIKE); // its switch and mark row follow
        }
        if (setting == Settings.UI_LANGUAGE) {
            uiStrings = UiLanguage.strings(context, value);
            if (panelTitle != null) panelTitle.setText(uiStrings.appName());
        }
    }

    @Override public void afterSettingChosen(Settings.Setting<?> setting) {
        onSettingChanged(setting);
    }

    @Override public String rowSummaryFor(Settings.StringSetting setting, String value) {
        return setting == Settings.CACHE_SIZE ? cacheSizeSummary() : labelFor(setting, value);
    }

    @Override public PanelStrings panelStrings() {
        return panelStrings;
    }

    @Override public void onSpicyTokenChanged() {
        rebuildSection(Settings.LYRICS_SOURCES);
    }

    // --- SourceOrderEditor.Host ---

    @Override public void onSourcesCommitted() {
        onSettingChanged(Settings.LYRICS_SOURCE_MODE);
    }

    /** Session access for the current-song lyrics item; unset when hosted without a hook. */
    public void setLyricsHost(com.eza.spicyex.hooks.LyricsHost host) {
        lyricsHost = host;
    }

    @Override public com.eza.spicyex.SpotifyTrack currentTrack() {
        try {
            return lyricsHost == null ? null : lyricsHost.getCurrentTrackSafely();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override public void manageCurrentTrackLyrics() {
        try {
            if (!(context instanceof android.app.Activity) || lyricsHost == null) return;
            com.eza.spicyex.SpotifyTrack current = lyricsHost.getCurrentTrackSafely();
            if (current == null || current.uri == null || current.uri.isEmpty()) return;
            com.eza.spicyex.hooks.LyricsSourcePickerDialog.show(
                    (android.app.Activity) context, lyricsHost, uiStrings,
                    message -> android.widget.Toast.makeText(context, message,
                            android.widget.Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }
}
