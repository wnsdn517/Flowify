package com.eza.spicyex.settings;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.CurrentLyricState;
import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.ui.UiLanguage;
import com.eza.spicyex.ui.GlossyToggle;

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
import com.eza.spicyex.lyrics.cache.CacheClearKind;
import com.eza.spicyex.lyrics.cache.CacheStoragePolicy;
import com.eza.spicyex.lyrics.language.LanguageModelPack;
import com.eza.spicyex.lyrics.providers.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.providers.SpicyManualTokenStore;
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
        SourceOrderEditor.Host, CacheManager.Host {
    /** The section whose page is open, or null on the hub (the list of sections). Every open
     *  starts on the hub: finding a setting begins with choosing where it lives. */
    private Settings.Section currentPage;
    private android.widget.ImageButton backButton;

    private final Context context;
    private final PanelStyle style;
    private int sectionReflowGeneration;
    private static final String TAG_LAYOUT_EDITOR_ACTION = "action:layout_editor";
    private static final String TAG_CARD_EDITOR_ACTION = "action:card_editor";

    private final SettingsStore store;
    private final SettingsWriter writer;
    private final SettingRowFactory rows;
    private final PanelDialogs dialogs;
    private final SourceOrderEditor sources;
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
        SpotifyTrack track = currentTrack();
        if (track != null) PanelStyle.useAlbumAccent(track.color);
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(style.panelBackground());
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

    /**
     * Everything searchable, built once per search session. Every panel setting is in it, not
     * only the ones on screen now: a setting hidden until another is switched on (ad music
     * style, the AI options, Connect's auto-switch) is exactly the one people search for. So
     * are the rows that are not settings - the AI key and model, Connect sign-in, the language
     * models, the current song's lyrics - which open the page they live on.
     */
    private synchronized com.eza.spicyex.settings.SettingsSearch searchIndex() {
        if (searchIndex != null) return searchIndex;
        List<com.eza.spicyex.settings.SettingsSearch.Entry> entries = new ArrayList<>();
        java.util.Set<Settings.Setting<?>> editorOwned =
                new java.util.HashSet<>(com.eza.spicyex.hooks.LayoutEditorSettings.covered());
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = new LinkedHashMap<>();
        for (Settings.Section section : SettingsUiSchema.orderedSections()) {
            List<Settings.Setting<?>> items = new ArrayList<>();
            for (Settings.Setting<?> setting : SettingsUiSchema.orderedSettings(section)) {
                if (!editorOwned.contains(setting)) items.add(setting);
            }
            grouped.put(section, items);
        }
        List<Settings.Section> sections = new ArrayList<>(grouped.keySet());
        sections.add(Settings.DEBUG);
        for (Settings.Section section : sections) {
            String name = uiStrings.section(section);
            entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(section,
                    names(name, com.eza.spicyex.ui.SettingsUiResourceNames.section(section), section.label), "", section.id,
                    sectionKeywords(section)).withId("section:" + section.id));
            List<Settings.Setting<?>> items = grouped.get(section);
            if (items == null) continue;
            for (Settings.Setting<?> setting : items) {
                if (setting == Settings.LYRICS_SOURCE_OVERRIDE || setting == Settings.LYRICS_SOURCE_ORDER) continue;
                entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(setting,
                        names(searchTitle(setting), com.eza.spicyex.ui.SettingsUiResourceNames.setting(setting), setting.label),
                        name, section.id, settingKeywords(setting, section)).withId(setting.key));
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
                    names(searchTitle(setting), com.eza.spicyex.ui.SettingsUiResourceNames.setting(setting), setting.label),
                    card ? cardEditor : lyricsEditor, card ? "editor_card" : "editor_lyrics", extra)
                    .withId("editor:" + setting.key));
        }
        // Rows that are not settings, found by their own labels; they open their page.
        addExtra(entries, Settings.AI, "settings_ai_api_key", "API key", "api key gemini openai deepseek openrouter token");
        addExtra(entries, Settings.AI, "settings_ai_model", "Model", "ai model gemini gpt");
        addExtra(entries, Settings.AI, "settings_action_clear_ai_cache", "Clear AI results", "ai cache delete");
        addExtra(entries, Settings.AD_FREE, "settings_connect_login", "Sign in to Spotify", "connect web player login account");
        addExtra(entries, Settings.AD_FREE, "settings_connect_status_label", "Player", "connect web player status running");
        addExtra(entries, Settings.TRANSLITERATION, "settings_language_models_delete", "Delete language models", "language model delete storage");
        addExtra(entries, Settings.LYRICS_SOURCES, "settings_source_track_title", "Current song lyrics", "pick lyrics wrong lyrics candidate source picker");
        addExtra(entries, Settings.LYRICS_SOURCES, "settings_cache_browser_title", "Stored lyrics", "cache stored lyrics delete search songs");
        addExtra(entries, Settings.DEBUG, "settings_action_clear_lyrics_cache", "Clear lyrics response cache", "cache delete");
        addExtra(entries, Settings.DEBUG, "settings_action_clear_translation_cache", "Clear translation cache", "cache delete");
        addExtra(entries, Settings.DEBUG, "settings_action_clear_reading_cache", "Clear transliteration cache", "cache delete");
        addExtra(entries, Settings.DEBUG, "settings_action_resync_timing", "Reset lyrics sync", "sync timing reset");
        // Synonym groups: each language's own words, from its strings file.
        List<List<String>> concepts = new ArrayList<>();
        for (String id : com.eza.spicyex.settings.SettingsSearch.CONCEPT_IDS) {
            concepts.add(com.eza.spicyex.settings.SettingsSearch.mergeTerms(
                    uiStrings.inEveryLanguage("search_terms_" + id)));
        }
        // Names are also read in Latin letters, and what earlier searches taught is added back.
        searchIndex = new com.eza.spicyex.settings.SettingsSearch(entries, concepts, romanizer(),
                searchPrefs().getString(PREF_LEARNED, ""));
        return searchIndex;
    }

    private static final String PREF_LEARNED = "learned";

    private android.content.SharedPreferences searchPrefs() {
        return context.getSharedPreferences("spicy_settings_search", Context.MODE_PRIVATE);
    }

    /** Any script to the Latin letters it is read with, from the platform's own ICU data - so
     *  no spelling of a Korean, Japanese, Chinese or Russian name has to be written out. */
    private static java.util.function.Function<String, String> romanizer() {
        try {
            final android.icu.text.Transliterator latin =
                    android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII");
            return latin::transliterate;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private void addExtra(List<com.eza.spicyex.settings.SettingsSearch.Entry> entries, Settings.Section section,
                          String resource, String fallback, String keywords) {
        List<String> extra = new ArrayList<>();
        extra.add(keywords);
        extra.add(section.label);
        entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(section,
                names(uiStrings.get(resource, fallback).replace(" (%1$s)", "").replace("%1$s", ""), resource, fallback),
                uiStrings.section(section), section.id, extra).withId("extra:" + resource));
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
        // A search that needed this result teaches it the words that were typed for it.
        com.eza.spicyex.settings.SettingsSearch index = searchIndex;
        if (index != null && index.learn(searchField.getText().toString(), entry)) {
            searchPrefs().edit().putString(PREF_LEARNED, index.exportLearned()).apply();
        }
        searchField.setText("");
        if (target instanceof EditorTarget) {
            EditorTarget editor = (EditorTarget) target;
            com.eza.spicyex.hooks.LayoutEditorSettings.requestElement(
                    com.eza.spicyex.hooks.LayoutEditorSettings.elementFor(editor.setting));
            openEditor(editor.mode);
        } else if (target instanceof Settings.Section) {
            Settings.Section section = (Settings.Section) target;
            reveal(section, null);
        } else if (target instanceof Settings.Setting) {
            final Settings.Setting<?> setting = (Settings.Setting<?>) target;
            reveal(setting.section, setting);
        }
    }

    /** Opens the section's page, then scrolls to one of its rows and marks it. */
    private void reveal(Settings.Section section, Settings.Setting<?> setting) {
        if (sectionsContainer == null) return;
        showPage(section);
        if (setting != null) sectionsContainer.postDelayed(() -> revealRow(setting), 260);
    }

    /** Scrolls a row of an open section into view and marks it with a brief highlight. */
    private void revealRow(Settings.Setting<?> setting) {
        if (sectionsContainer == null || scrollRoot == null) return;
        View row = sectionsContainer.findViewWithTag(PanelTags.row(setting.key));
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
        backButton = style.headerIconButton(Kind.CHEVRON_RIGHT,
                uiStrings.get("settings_panel_back", "Back"), v -> showPage(null));
        backButton.setRotation(180f);
        backButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams backLp = (LinearLayout.LayoutParams) backButton.getLayoutParams();
        backLp.leftMargin = 0;
        backLp.rightMargin = style.dp(10);
        header.addView(backButton);
        panelTitle = style.text(uiStrings.appName(), 26, PanelStyle.COL_TITLE, true);
        header.addView(panelTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // No resize or close buttons: the sheet is sized and closed by dragging it, as the
        // share panel is.
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
        aiRows().ensureInitialModelCheck();
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = groupVisibleSettings();
        // A page whose rows are all hidden now (the Apple style was switched away, say) has
        // nothing left to show: fall back to the hub.
        if (currentPage != null && currentPage != Settings.DEBUG && !grouped.containsKey(currentPage)) {
            currentPage = null;
        }
        updateHeader();
        if (currentPage == null) {
            renderHub(content, grouped);
            return;
        }
        // The AI section's remaining rows are not settings: a key that must not persist as it
        // is typed, and a model list that has to be fetched before it can be offered.
        if (currentPage == Settings.DEBUG) appendDebugCard(content, -1);
        else appendSectionCard(content, currentPage, grouped.get(currentPage), -1);
    }

    /** Title and back button follow the open page; the hub shows the app name. */
    private void updateHeader() {
        boolean page = currentPage != null;
        if (panelTitle != null) {
            panelTitle.setText(page ? uiStrings.section(currentPage) : uiStrings.appName());
            panelTitle.setTextSize(page ? 22 : 26);
        }
        if (searchBar != null) searchBar.setVisibility(page ? View.GONE : View.VISIBLE);
        // Follows a language change made in this same panel.
        if (searchField != null) searchField.setHint(uiStrings.get("settings_search_hint", "Search settings"));
        if (backButton != null) {
            backButton.setVisibility(page ? View.VISIBLE : View.GONE);
            String back = uiStrings.get("settings_panel_back", "Back");
            backButton.setContentDescription(back);
            backButton.setTooltipText(back);
        }
    }

    /** Opens a section's page, or the hub for null; true when something changed. */
    private boolean showPage(Settings.Section page) {
        if (searchField != null && searchField.getText().length() > 0) searchField.setText("");
        if (page == currentPage) return false;
        boolean forward = page != null;
        currentPage = page;
        sectionReflowGeneration++;
        aiBadgeView = null;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
        if (scrollRoot != null) scrollRoot.scrollTo(0, 0);
        if (Motion.animationsEnabled()) {
            float shift = style.dp(forward ? 18 : -18);
            sectionsContainer.setAlpha(0f);
            sectionsContainer.setTranslationX(shift);
            sectionsContainer.animate().alpha(1f).translationX(0f)
                    .setDuration(Motion.dur(Motion.BASE)).setInterpolator(Motion.decel()).start();
        }
        return true;
    }

    /** Back steps from a page to the hub; false on the hub itself, where back closes the panel. */
    public boolean handleBack() {
        return currentPage != null && showPage(null);
    }

    // --- Hub ---

    /** The list of sections: two editor shortcuts on top, then the sections in groups, each
     *  with a line about what is inside it and a mark when its feature is on. */
    private void renderHub(LinearLayout content, LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped) {
        if (onOpenLayoutEditor != null) {
            LinearLayout shortcuts = new LinearLayout(context);
            shortcuts.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            left.rightMargin = style.dp(5);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            right.leftMargin = style.dp(5);
            String hint = uiStrings.get("settings_hub_edit_hint", "Edit it live");
            shortcuts.addView(style.shortcutTile(Kind.ALIGN_VERTICAL_DISTRIBUTE_CENTER,
                    PanelStyle.sectionTint(Settings.LYRICS_SCREEN),
                    uiStrings.get("settings_hub_edit_lyrics", "Lyrics screen"), hint,
                    v -> openEditor(EDITOR_LYRICS)), left);
            shortcuts.addView(style.shortcutTile(Kind.DISC_3, PanelStyle.COL_TITLE,
                    uiStrings.get("settings_hub_edit_card", "Now playing card"), hint,
                    v -> openEditor(EDITOR_CARD)), right);
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.topMargin = style.dp(4);
            content.addView(shortcuts, row);
        }
        appendHubGroup(content, "settings_hub_group_lyrics", "Lyrics content", grouped,
                Settings.LYRICS_SOURCES, Settings.TRANSLATION, Settings.TRANSLITERATION, Settings.AI);
        appendHubGroup(content, "settings_hub_group_use", "Playback & controls", grouped,
                Settings.LYRICS, Settings.GESTURES, Settings.PIP, Settings.AD_FREE);
        appendHubGroup(content, "settings_hub_group_about", "About", grouped, Settings.DEBUG);
    }

    private void appendHubGroup(LinearLayout content, String captionName, String captionFallback,
                                LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped,
                                Settings.Section... sections) {
        LinearLayout card = null;
        for (Settings.Section section : sections) {
            if (section != Settings.DEBUG && !grouped.containsKey(section)) continue;
            if (card == null) {
                content.addView(style.groupCaption(uiStrings.get(captionName, captionFallback)));
                card = style.newCard();
            }
            Kind icon = PanelStyle.sectionIcon(section);
            card.addView(style.hubTile(icon == null ? Kind.CIRCLE : icon,
                    PanelStyle.sectionTint(section), uiStrings.section(section),
                    uiStrings.get("settings_hub_" + section.id, ""),
                    sectionOn(section) ? uiStrings.get("settings_hub_on", "On") : null,
                    v -> showPage(section)));
        }
        if (card != null) style.attachCard(content, card, -1);
    }

    /** Whether the section's main feature is switched on (shown as a mark on its hub tile). */
    private boolean sectionOn(Settings.Section section) {
        if (section == Settings.AI) return aiReady();
        if (section == Settings.PIP) return Boolean.TRUE.equals(store.get(Settings.PIP_ENABLED));
        if (section == Settings.TRANSLATION) {
            return Boolean.TRUE.equals(store.get(Settings.TRANSLATION_ENABLED));
        }
        if (section == Settings.TRANSLITERATION) {
            return Boolean.TRUE.equals(store.get(Settings.TRANSLITERATION_ENABLED));
        }
        if (section == Settings.AD_FREE) {
            return !Settings.AD_MODE_OFF.equals(store.get(Settings.AD_MODE))
                    || Boolean.TRUE.equals(store.get(Settings.CONNECT_ENABLED));
        }
        return false;
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
                .languageModelReady(com.eza.spicyex.lyrics.language.LanguageModelPack.isReady())
                .animatedBackgroundAvailable(FeatureAvailability.animatedBackgroundAvailable())
                .spicySourceEnabled(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.sourceEnabled(
                        context, com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPICY));
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

    /**
     * A page's settings as small captioned cards, one per group (see
     * {@link SettingsUiSchema#groupOf}). The rows that are not settings - the editor launchers,
     * the AI model list, Connect's sign-in - go in the last card, where they always sat.
     */
    private void appendSectionCard(LinearLayout parent, Settings.Section section,
                                   List<Settings.Setting<?>> items, int at) {
        List<List<Settings.Setting<?>>> runs = new ArrayList<>();
        String previous = null;
        for (Settings.Setting<?> setting : items) {
            String group = SettingsUiSchema.groupOf(setting);
            if (runs.isEmpty() || !java.util.Objects.equals(group, previous)) {
                runs.add(new ArrayList<>());
                previous = group;
            }
            runs.get(runs.size() - 1).add(setting);
        }
        if (runs.isEmpty()) runs.add(new ArrayList<>());
        for (int i = 0; i < runs.size(); i++) {
            List<Settings.Setting<?>> run = runs.get(i);
            String group = run.isEmpty() ? null : SettingsUiSchema.groupOf(run.get(0));
            if (group != null) {
                TextView caption = style.groupCaption(uiStrings.get("settings_group_" + group, ""));
                caption.setTag(PanelTags.CARD_PREFIX + section.id + ":caption:" + i);
                parent.addView(caption);
            }
            LinearLayout card = style.newCard();
            card.setTag(PanelTags.CARD_PREFIX + section.id + ":" + i);
            for (Settings.Setting<?> setting : run) renderSetting(card, setting);
            if (i == runs.size() - 1) appendPageExtras(card, section);
            style.attachCard(parent, card, -1);
        }
    }

    /** The non-setting rows a page ends with. */
    private void appendPageExtras(LinearLayout card, Settings.Section section) {
        appendEditorActionRows(card, section);
        if (section == Settings.LYRICS_SOURCES) {
            // Every stored song: search, filter by provider, sort, delete.
            rows.actionRow(card, Kind.SEARCH, uiStrings.get("settings_cache_browser_title", "Stored lyrics"),
                    v -> cacheManager().openBrowser());
        }
        if (section == Settings.TRANSLITERATION && LanguageModelPack.isReady()) {
            // Installed models otherwise have no row at all (the download row hides once they
            // are ready), so there was no way to take the space back.
            rows.actionRow(card, Kind.DELETE, uiStrings.format("settings_language_models_delete",
                    "Delete language models (%1$s)",
                    CacheStoragePolicy.formatBytes(LanguageModelPack.sizeBytes())), v -> confirmDeleteModels());
        }
        if (section == Settings.AI) {
            LinearLayout dynamic = new LinearLayout(context);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setTag(PanelTags.AI_DYNAMIC);
            aiRows().render(dynamic);
            card.addView(dynamic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (section == Settings.AD_FREE) {
            LinearLayout dynamic = new LinearLayout(context);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setTag(PanelTags.CONNECT_DYNAMIC);
            if (Boolean.TRUE.equals(store.get(Settings.CONNECT_ENABLED))) renderConnectLogin(dynamic);
            card.addView(dynamic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static final String FORK_REPO = "wnsdn517/spicy-ex";

    /**
     * The page's head: the wordmark shining in the album's colour, the version in the fork's
     * own scheme, who made it and where the source is, the newest release on GitHub, and what
     * this build is running on.
     */
    private void appendAboutCard(LinearLayout parent) {
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(style.dp(8), style.dp(18), style.dp(8), style.dp(18));
        java.lang.ref.WeakReference<android.graphics.Typeface> font = com.eza.spicyex.References.beautifulFont;
        hero.addView(new com.eza.spicyex.ui.WordmarkView(context, uiStrings.appName(),
                        PanelStyle.COL_ACCENT, font == null ? null : font.get()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView version = style.text(BuildStamp.VERSION + "  (" + BuildStamp.VERSION_CODE + ")",
                17, PanelStyle.COL_TITLE, true);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, style.dp(6), 0, 0);
        hero.addView(version);
        TextView clue = style.text(BuildStamp.CLUE, 12, PanelStyle.COL_ACCENT, false);
        clue.setPadding(style.dp(12), style.dp(4), style.dp(12), style.dp(4));
        android.graphics.drawable.GradientDrawable clueBg = new android.graphics.drawable.GradientDrawable();
        clueBg.setCornerRadius(style.dp(12));
        clueBg.setColor((PanelStyle.COL_ACCENT & 0x00FFFFFF) | 0x24000000);
        clue.setBackground(clueBg);
        LinearLayout.LayoutParams clueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clueLp.topMargin = style.dp(8);
        hero.addView(clue, clueLp);
        LinearLayout heroCard = style.newCard();
        heroCard.addView(hero);
        style.attachCard(parent, heroCard, -1);

        LinearLayout people = style.newCard();
        personRow(people, uiStrings.get("settings_about_developer", "Developer"), "wnsdn517");
        personRow(people, uiStrings.get("settings_about_original", "Original developer"), "amarinne");
        rows.actionRow(people, Kind.EXTERNAL_LINK, uiStrings.get("settings_about_source", "Source code on GitHub"),
                v -> openUrl("https://github.com/" + FORK_REPO));
        rows.actionRow(people, Kind.EXTERNAL_LINK, uiStrings.get("settings_about_announcements", "Announcements"),
                v -> openUrl("https://t.me/spicy_ex"));
        rows.actionRow(people, Kind.EXTERNAL_LINK, uiStrings.get("settings_about_discussion", "Discussion"),
                v -> openUrl("https://t.me/spicy_ex_discussion"));
        rows.actionRow(people, Kind.EXTERNAL_LINK, uiStrings.get("settings_about_ci_channel", "CI builds"),
                v -> openUrl("https://t.me/spicy_ex_ci"));
        TextView latest = rows.infoRow(people, uiStrings.get("settings_about_latest", "Latest on GitHub"),
                uiStrings.get("settings_about_checking", "Checking…"));
        View latestRow = (View) latest.getParent();
        latestRow.setOnClickListener(v -> openUrl("https://github.com/" + FORK_REPO + "/releases"));
        checkLatestRelease(latest);
        style.attachCard(parent, people, -1);

        LinearLayout system = style.newCard();
        rows.infoRow(system, "Spotify", spotifyVersion());
        rows.infoRow(system, "Android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        rows.infoRow(system, uiStrings.get("settings_about_device", "Device"),
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        String framework = com.eza.spicyex.xposed.XpHooks.frameworkLabel();
        if (!framework.isEmpty()) {
            rows.infoRow(system, uiStrings.get("settings_about_framework", "Framework"), framework);
        }
        rows.infoRow(system, uiStrings.get("settings_about_build", "Build"),
                com.eza.spicyex.BuildConfig.BUILD_DATE + " · " + com.eza.spicyex.BuildConfig.GIT_SHA
                        + " · B" + com.eza.spicyex.BuildConfig.UPSTREAM_BASE_CODE);
        style.attachCard(parent, system, -1);
    }

    /** A GitHub account: round avatar, role, name; opens the profile. */
    private void personRow(LinearLayout card, String role, String login) {
        LinearLayout row = style.newRow(card);
        ImageView avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable placeholder = new android.graphics.drawable.GradientDrawable();
        placeholder.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        placeholder.setColor(0x26FFFFFF);
        avatar.setBackground(placeholder);
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(style.dp(40), style.dp(40));
        avatarLp.rightMargin = style.dp(14);
        row.addView(avatar, avatarLp);
        style.titleColumn(row, login, role);
        row.addView(style.kindView(Kind.EXTERNAL_LINK, PanelStyle.COL_SECTION, 16),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
        row.setOnClickListener(v -> openUrl("https://github.com/" + login));
        loadAvatar(avatar, login);
    }

    private static final java.util.Map<String, android.graphics.Bitmap> AVATARS =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private void loadAvatar(ImageView target, String login) {
        android.graphics.Bitmap cached = AVATARS.get(login);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                        new java.net.URL("https://github.com/" + login + ".png?size=120").openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(true);
                try (java.io.InputStream in = connection.getInputStream()) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
                    if (bitmap == null) return;
                    AVATARS.put(login, bitmap);
                    uiHandler.post(() -> target.setImageBitmap(bitmap));
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable ignored) {
            }
        }, "SpicyAboutAvatar");
        worker.setDaemon(true);
        worker.start();
    }

    private String spotifyVersion() {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Newest release of the fork on GitHub (or, with none published, the newest commit). */
    private void checkLatestRelease(TextView target) {
        Thread worker = new Thread(() -> {
            String shown;
            try {
                // The newest release, betas included ("releases/latest" leaves pre-releases out).
                org.json.JSONObject release = null;
                org.json.JSONArray list = githubJsonArray("https://api.github.com/repos/" + FORK_REPO + "/releases?per_page=10");
                for (int i = 0; list != null && i < list.length(); i++) {
                    org.json.JSONObject candidate = list.optJSONObject(i);
                    if (candidate == null || candidate.optBoolean("draft")) continue;
                    // The list is not in date order: keep the most recently published version tag.
                    if (!candidate.optString("tag_name", "").matches("v?[0-9]+[.][0-9]+[.][0-9]+.*")) continue;
                    if (release == null || candidate.optString("published_at", "")
                            .compareTo(release.optString("published_at", "")) > 0) {
                        release = candidate;
                    }
                }
                if (release != null) {
                    String tag = release.optString("tag_name", "");
                    String date = release.optString("published_at", "");
                    boolean newer = isNewer(tag.replaceFirst("^[vV]", ""), BuildStamp.VERSION);
                    shown = tag + (release.optBoolean("prerelease") ? " beta" : "")
                            + (date.length() >= 10 ? " · " + date.substring(0, 10) : "")
                            + " · " + (newer ? uiStrings.get("settings_about_update", "Update available")
                            : uiStrings.get("settings_about_up_to_date", "Up to date"));
                } else {
                    org.json.JSONObject commit = githubJson("https://api.github.com/repos/" + FORK_REPO + "/commits/main");
                    if (commit == null) {
                        shown = uiStrings.get("settings_about_unavailable", "Unavailable");
                    } else {
                        String sha = commit.optString("sha", "");
                        String date = commit.optJSONObject("commit") == null ? ""
                                : commit.optJSONObject("commit").optJSONObject("committer").optString("date", "");
                        shown = sha.substring(0, Math.min(8, sha.length()))
                                + (date.length() >= 10 ? " · " + date.substring(0, 10) : "");
                    }
                }
            } catch (Throwable t) {
                shown = uiStrings.get("settings_about_unavailable", "Unavailable");
            }
            final String text = shown;
            uiHandler.post(() -> target.setText(text));
        }, "SpicyAboutLatest");
        worker.setDaemon(true);
        worker.start();
    }

    private static org.json.JSONArray githubJsonArray(String url) throws Exception {
        String body = githubText(url);
        return body == null ? null : new org.json.JSONArray(body);
    }

    private static org.json.JSONObject githubJson(String url) throws Exception {
        String body = githubText(url);
        return body == null ? null : new org.json.JSONObject(body);
    }

    private static String githubText(String url) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (connection.getResponseCode() != 200) return null;
            try (java.io.InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** True when version a (dotted numbers) is above b. Tags from before the fork's own
     *  numbering (1.58.x, where the middle number was the upstream build line) are older than
     *  any 1.x fork version, whatever the digits say. */
    static boolean isNewer(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        if (x.length > 1 && !x[1].isEmpty() && Integer.parseInt(x[1]) >= 50) return false;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void appendDebugCard(LinearLayout parent, int at) {
        appendAboutCard(parent);
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
        if (setting == Settings.LYRICS_FONT_CUSTOM_PATH) {
            lyricsFontPathRow(content);
            return;
        }
        if (setting == Settings.DOWNLOAD_LANGUAGE_MODELS) {
            downloadLanguageModelsRow(content);
            return;
        }
        if (setting == Settings.PIP_SHAPE) {
            pipShapeRow(content);
            return;
        }
        if (setting == Settings.DOUBLE_TAP_LIKE_EFFECT) {
            effectChipsRow(content);
            if (onTryDoubleTapEffect != null) {
                rows.actionRow(content, Kind.SPARKLES,
                        uiStrings.get("settings_action_try_double_tap_effect", "Effect settings"), v -> {
                            // The host shrinks the sheet and starts the trial: double-tap the
                            // lyrics above while picking the effect right here.
                            onTryDoubleTapEffect.run();
                        });
            }
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
        sectionReflowGeneration++;
        if (sectionsContainer == null) return;
        captureAnchor();
        aiBadgeView = null;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
        restoreAnchor();
    }

    /**
     * Re-renders the open page after a setting that changes which rows exist or how they read.
     * The page is a handful of small cards, so it is simply drawn again, scroll position kept.
     */
    private void rebuildSection(Settings.Section target) {
        rebuildSections();
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
            // The Apple Music section appears/disappears with this pick (a cross-section
            // change), so the whole panel rebuilds anchor-preserved instead of one section.
            rebuildSections();
        } else if (PanelPolicy.shouldRebuildSectionAfterChange(setting)) {
            rebuildSection(setting.section);
        }
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

    private void confirmDeleteModels() {
        new com.eza.spicyex.ui.PanelDialog(context,
                uiStrings.get("settings_language_models_delete_title", "Delete language models?"))
                .paragraph(uiStrings.get("settings_language_models_delete_body",
                        "Japanese and Chinese readings and offline language detection stop working until they are downloaded again."))
                .primary(uiStrings.get("settings_cache_delete", "Delete"),
                        () -> LanguageModelPack.delete(() -> uiHandler.post(() -> {
                            if (panelAttached) rebuildSections();
                        })))
                .secondary(uiStrings.get("settings_ai_cancel", "Cancel"), null)
                .show();
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
        clearAction(content, "settings_action_clear_translation_cache",
                "Clear translation cache", CacheClearKind.TRANSLATION);
        clearAction(content, "settings_action_clear_reading_cache",
                "Clear transliteration cache", CacheClearKind.TRANSLITERATION);
        clearAction(content, "settings_action_clear_ai_cache",
                "Clear AI results", CacheClearKind.AI);
        clearAction(content, "settings_action_clear_lyrics_cache",
                "Clear lyrics response cache", CacheClearKind.LYRICS_RESPONSE);
    }

    private void clearAction(LinearLayout content, String key, String fallback, CacheClearKind kind) {
        rows.actionRow(content, null, uiStrings.get(key, fallback), v -> clearCache(kind));
    }

    private CacheManager cacheManager;

    private CacheManager cacheManager() {
        if (cacheManager == null) cacheManager = new CacheManager(this);
        return cacheManager;
    }

    @Override public String cacheLimitLabel() {
        return uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
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
        return null;
    }

    @Override public boolean unavailable(Settings.Setting<?> setting) {
        return PanelPolicy.unavailable(setting, captureSnapshot());
    }

    @Override public String cacheSizeSummary() {
        String label = uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
        long bytes = storedBytes;
        if (bytes < 0 || android.os.SystemClock.elapsedRealtime() - storedBytesAt > STORED_BYTES_FRESH_MS) {
            refreshStoredBytes();
        }
        return SettingLabels.cacheUsageSummary(panelStrings, label,
                bytes < 0 ? "…" : CacheStoragePolicy.formatBytes(bytes));
    }

    /** Stored-lyrics usage, measured off the UI thread: summing it reads every cache store,
     *  and doing that while the Lyrics sources page was built made the page slow to open. */
    private static volatile long storedBytes = -1L;
    private static volatile long storedBytesAt;
    private static final long STORED_BYTES_FRESH_MS = 20_000L;
    private boolean storedBytesRefreshing;

    private void refreshStoredBytes() {
        if (storedBytesRefreshing) return;
        storedBytesRefreshing = true;
        Thread worker = new Thread(() -> {
            long bytes = CacheStoragePolicy.storedTotal(context);
            storedBytes = bytes;
            storedBytesAt = android.os.SystemClock.elapsedRealtime();
            uiHandler.post(() -> {
                storedBytesRefreshing = false;
                if (!panelAttached || sectionsContainer == null) return;
                View row = sectionsContainer.findViewWithTag(PanelTags.row(Settings.CACHE_SIZE));
                TextView summary = row == null ? null : row.findViewWithTag(PanelTags.ROW_SUMMARY);
                if (summary != null) summary.setText(cacheSizeSummary());
            });
        }, "SpicySettingsCacheSize");
        worker.setDaemon(true);
        worker.start();
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
            updateHeader();
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
    private Runnable onTryDoubleTapEffect;

    /** Shows a "Try it" action under the double-tap effect choice; it runs this, then closes. */
    public void setOnTryDoubleTapEffect(Runnable onTry) {
        onTryDoubleTapEffect = onTry;
    }

    private Runnable onDoubleTapEffectChosen;

    /** Run after an effect chip is picked: the host plays it once while a trial is on. */
    public void setOnDoubleTapEffectChosen(Runnable onChosen) {
        onDoubleTapEffectChosen = onChosen;
    }

    /**
     * The double-tap effects as chips on the page itself - one tap picks and saves - instead of
     * a selector row opening a list: while trying effects on the lyrics above, the choice has
     * to be right at hand.
     */
    private void effectChipsRow(LinearLayout content) {
        Settings.StringSetting setting = (Settings.StringSetting) Settings.DOUBLE_TAP_LIKE_EFFECT;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag(PanelTags.row(setting));
        row.setPadding(style.dp(4), style.dp(12), 0, style.dp(12));
        row.addView(style.text(uiStrings.setting(setting), 16, PanelStyle.COL_TITLE, false));
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(chips);
        List<TextView> views = new ArrayList<>();
        String current = store.get(setting);
        for (String value : setting.allowedValues) {
            String full = uiStrings.option(setting, value);
            int dash = full.indexOf(" - ");
            TextView chip = style.text(dash > 0 ? full.substring(0, dash) : full, 14, PanelStyle.COL_TITLE, false);
            chip.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
            chip.setTag(value);
            paintEffectChip(chip, value.equals(current));
            chip.setOnClickListener(v -> {
                writer.put(setting, value);
                for (TextView other : views) paintEffectChip(other, other == chip);
                if (onDoubleTapEffectChosen != null) onDoubleTapEffectChosen.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = style.dp(8);
            chips.addView(chip, lp);
            views.add(chip);
        }
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = style.dp(10);
        row.addView(scroller, scrollLp);
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Bring the picked one into view.
        scroller.post(() -> {
            for (TextView chip : views) {
                if (current.equals(chip.getTag())) {
                    scroller.scrollTo(Math.max(0, chip.getLeft() - style.dp(24)), 0);
                    break;
                }
            }
        });
    }

    /**
     * The PiP window shapes as tiles, each with the shape drawn to scale over its name: pick by
     * look, in one tap, instead of reading ratios in a list.
     */
    private void pipShapeRow(LinearLayout content) {
        Settings.StringSetting setting = (Settings.StringSetting) Settings.PIP_SHAPE;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag(PanelTags.row(setting));
        row.setPadding(style.dp(4), style.dp(12), 0, style.dp(12));
        row.addView(style.text(uiStrings.setting(setting), 16, PanelStyle.COL_TITLE, false));
        LinearLayout tiles = new LinearLayout(context);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        List<LinearLayout> views = new ArrayList<>();
        String current = store.get(setting);
        for (String value : setting.allowedValues) {
            int[] ratio = Settings.pipShapeRatio(value);
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(style.dp(4), style.dp(10), style.dp(4), style.dp(8));
            tile.setTag(value);
            ImageView shape = new ImageView(context);
            shape.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            tile.addView(shape, new LinearLayout.LayoutParams(style.dp(40), style.dp(40)));
            String full = uiStrings.option(setting, value);
            int paren = full.indexOf(" (");
            TextView name = style.text(paren > 0 ? full.substring(0, paren) : full, 12, PanelStyle.COL_TITLE, false);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setPadding(0, style.dp(6), 0, 0);
            tile.addView(name);
            TextView ratioText = style.text(ratio[0] + ":" + ratio[1], 11, PanelStyle.COL_SUMMARY, false);
            ratioText.setGravity(Gravity.CENTER);
            tile.addView(ratioText);
            tile.setOnClickListener(v -> {
                writer.put(setting, value);
                for (LinearLayout other : views) paintShapeTile(other, ratio(other), other == tile);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = style.dp(6);
            tiles.addView(tile, lp);
            views.add(tile);
            paintShapeTile(tile, ratio, value.equals(current));
        }
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tilesLp.topMargin = style.dp(10);
        row.addView(tiles, tilesLp);
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static int[] ratio(LinearLayout tile) {
        return Settings.pipShapeRatio(String.valueOf(tile.getTag()));
    }

    private void paintShapeTile(LinearLayout tile, int[] ratio, boolean selected) {
        int color = selected ? PanelStyle.COL_ACCENT : 0xB3FFFFFF;
        ((ImageView) tile.getChildAt(0)).setImageDrawable(new com.eza.spicyex.ui.AspectRectDrawable(
                ratio[0] / (float) ratio[1], color, style.density()));
        ((TextView) tile.getChildAt(1)).setTextColor(selected ? PanelStyle.COL_ACCENT : PanelStyle.COL_TITLE);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(style.dp(14));
        bg.setColor(selected ? (PanelStyle.COL_ACCENT & 0x00FFFFFF) | 0x26000000 : 0x0FFFFFFF);
        if (selected) bg.setStroke(Math.max(1, style.dp(1)), PanelStyle.COL_ACCENT);
        tile.setBackground(bg);
    }

    private void paintEffectChip(TextView chip, boolean selected) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(style.dp(18));
        if (selected) {
            bg.setColor((PanelStyle.COL_ACCENT & 0x00FFFFFF) | 0x33000000);
            bg.setStroke(Math.max(1, style.dp(1)), PanelStyle.COL_ACCENT);
            chip.setTextColor(PanelStyle.COL_ACCENT);
        } else {
            bg.setColor(0x14FFFFFF);
            bg.setStroke(Math.max(1, style.dp(1)), 0x1FFFFFFF);
            chip.setTextColor(PanelStyle.COL_TITLE);
        }
        chip.setBackground(bg);
    }

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
