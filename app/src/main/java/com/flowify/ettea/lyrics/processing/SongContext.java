package com.eza.spicyex.lyrics.processing;

import android.net.Uri;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Context-aware translation (Labs): steadies Google's lyric translation without a key.
 *
 * <p>Google translates each lyric like a stray sentence: it mixes polite and plain speech inside
 * one song ("...싶어요" next to "...했어" and "...하겠습니다."), calls the other person "당신" in a
 * breakup song, and reads slang literally ("start shit"). So, around the same Google request:
 * <ol>
 *   <li>The song's own summary comes from keyless lookups (Wikipedia's REST summary, then the
 *       DuckDuckGo Instant Answer) and decides the register: lyrics are spoken plainly, except
 *       hymns, prayers, anthems and lullabies, which are left as Google wrote them.</li>
 *   <li>Before sending: dropped-g spellings and common lyric slang are written out in plain
 *       English ({@code evenin'}, {@code tryna}, {@code start shit}).</li>
 *   <li>After: Korean endings are unified to plain speech and "당신/그녀" become "너/걔".</li>
 * </ol>
 * The rewrite is on the request only; the lyric line itself and its cache identity are untouched.
 */
public final class SongContext {
    public enum Register {
        /** Plain speech throughout (the default for lyrics). */
        CASUAL,
        /** Plain and blunt: the original swears, so "you" is お前, not 君. */
        BLUNT,
        /** Leave Google's register alone (devotional/ceremonial songs). */
        KEEP
    }

    private static final String USER_AGENT = "SpicyEX/1.0 (lyrics context; github.com/wnsdn517/spicy-ex)";
    private static final int MAX_TRACKS = 32;
    private static final Map<String, String[]> META = new LinkedHashMap<String, String[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > MAX_TRACKS;
        }
    };
    /** Tracks whose lyrics swear; noted from the lines being translated. */
    private static final java.util.Set<String> BLUNT_TRACKS = java.util.Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRACKS;
                }
            });
    private static final Map<String, Register> REGISTERS = new LinkedHashMap<String, Register>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Register> eldest) {
            return size() > MAX_TRACKS;
        }
    };

    private SongContext() {
    }

    /** Called on track change: the translation lane only knows the bare track id. */
    public static void remember(String trackUri, String title, String artist) {
        if (trackUri == null || trackUri.isEmpty() || title == null || title.trim().isEmpty()) return;
        String[] meta = {title.trim(), artist == null ? "" : artist.trim()};
        synchronized (META) {
            // Lanes key by either the URI or the bare id; keep both.
            META.put(trackUri, meta);
            META.put(com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(trackUri), meta);
        }
    }

    private static final Pattern PROFANITY = Pattern.compile(
            "\\b(fuck\\w*|shit\\w*|bitch\\w*|ass|asshole|damn|motherfucker|dick)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Marks the track blunt once any of its lines swears; it stays so for the whole song. */
    public static void noteLyrics(String trackId, Iterable<String> lines) {
        if (trackId == null || lines == null) return;
        for (String line : lines) {
            if (line != null && PROFANITY.matcher(line).find()) {
                synchronized (BLUNT_TRACKS) {
                    BLUNT_TRACKS.add(trackId);
                }
                return;
            }
        }
    }

    /** The register for a track: its context (looked up once, network I/O) and its own words. */
    public static Register register(OkHttpClient http, String trackId) {
        Register base = contextRegister(http, trackId);
        if (base == Register.KEEP) return base;
        synchronized (BLUNT_TRACKS) {
            return trackId != null && BLUNT_TRACKS.contains(trackId) ? Register.BLUNT : base;
        }
    }

    private static Register contextRegister(OkHttpClient http, String trackId) {
        if (trackId == null) return Register.CASUAL;
        synchronized (REGISTERS) {
            Register known = REGISTERS.get(trackId);
            if (known != null) return known;
        }
        String[] meta;
        synchronized (META) {
            meta = META.get(trackId);
        }
        Register register = Register.CASUAL;
        if (meta != null && http != null) {
            OkHttpClient quick = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build();
            register = classify(summary(quick, meta[0], meta[1]));
        }
        synchronized (REGISTERS) {
            REGISTERS.put(trackId, register);
        }
        return register;
    }

    private static final Pattern KEEP_REGISTER = Pattern.compile(
            "\\b(hymn|prayer|national anthem|lullaby|nursery rhyme|children's song|gospel|psalm|worship)\\b",
            Pattern.CASE_INSENSITIVE);

    static Register classify(String summary) {
        if (summary != null && KEEP_REGISTER.matcher(summary).find()) return Register.KEEP;
        return Register.CASUAL;
    }

    /** Wikipedia's song article summary, else DuckDuckGo's abstract, else "". */
    static String summary(OkHttpClient http, String title, String artist) {
        String[] pages = {title + " (song)", title + " (" + artist + " song)", title};
        for (String page : pages) {
            JsonObject json = getJson(http, "https://en.wikipedia.org/api/rest_v1/page/summary/"
                    + Uri.encode(page.replace(' ', '_')));
            if (json == null || !"standard".equals(string(json, "type"))) continue;
            String description = string(json, "description").toLowerCase(Locale.ROOT);
            // Only a song's own article: "Watermelon" alone is the fruit.
            if (description.contains("song") || description.contains("single")) {
                return string(json, "extract");
            }
        }
        JsonObject ddg = getJson(http, "https://api.duckduckgo.com/?format=json&no_html=1&q="
                + Uri.encode(title + " " + artist + " song"));
        return ddg == null ? "" : string(ddg, "AbstractText");
    }

    private static JsonObject getJson(OkHttpClient http, String url) {
        Request request = new Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonElement root = JsonParser.parseString(response.body().string());
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    // --- Source side ----------------------------------------------------------------------------

    private static final Pattern G_DROP = Pattern.compile("\\b([A-Za-z]{2,})in'(?=\\W|$)");
    /** Lyric spellings and slang Google reads literally; whole words, case-insensitive. */
    private static final String[][] SLANG = {
            {"start shit", "start a fight"}, {"talk shit", "talk trash"},
            {"talkin' shit", "talking trash"}, {"go without$", "live without it"},
            {"tryna", "trying to"}, {"finna", "going to"}, {"i'ma", "I'm going to"},
            {"imma", "I'm going to"}, {"gonna", "going to"}, {"wanna", "want to"},
            {"gotta", "got to"}, {"gimme", "give me"}, {"lemme", "let me"},
            {"dunno", "don't know"}, {"outta", "out of"}, {"kinda", "kind of"},
            {"sorta", "sort of"}, {"sugar high", "sugar rush"}, {"c'mon", "come on"}, {"'cause", "because"},
            {"cuz", "because"}, {"'til", "until"}, {"'em", "them"}, {"y'all", "you all"},
            {"lil", "little"}, {"ya", "you"},
    };
    private static final Pattern[] SLANG_PATTERNS = new Pattern[SLANG.length];

    static {
        for (int i = 0; i < SLANG.length; i++) {
            String from = SLANG[i][0];
            boolean atEnd = from.endsWith("$");
            if (atEnd) from = from.substring(0, from.length() - 1);
            String lead = Character.isLetterOrDigit(from.charAt(0)) ? "\\b" : "(?<![A-Za-z])";
            String tail = atEnd ? "(?=[\\s.,!?]*$)" : "(?![A-Za-z'])";
            SLANG_PATTERNS[i] = Pattern.compile(lead + Pattern.quote(from) + tail,
                    Pattern.CASE_INSENSITIVE);
        }
    }

    /** The English line as sent to Google: spelled out, never shown. */
    public static String preprocess(String line) {
        if (line == null || line.isEmpty()) return line;
        String out = G_DROP.matcher(line).replaceAll("$1ing");
        for (int i = 0; i < SLANG.length; i++) {
            out = SLANG_PATTERNS[i].matcher(out).replaceAll(Matcher.quoteReplacement(SLANG[i][1]));
        }
        return out;
    }

    // --- Target side ----------------------------------------------------------------------------

    /** Polite → plain Korean endings, longest first; anchored at a clause end. */
    private static final String[][] KO_PLAIN = {
            {"하겠습니다", "할게"}, {"겠습니다", "겠어"}, {"입니다", "이야"}, {"합니다", "해"},
            {"좋습니다", "좋아"}, {"습니다", "어"}, {"이에요", "이야"}, {"예요", "야"},
            {"하세요", "해"}, {"주세요", "줘"}, {"가세요", "가"}, {"오세요", "와"},
            {"보세요", "봐"}, {"마세요", "마"}, {"세요", "어"},
            {"네요", "네"}, {"군요", "구나"}, {"죠", "지"},
    };
    /**
     * Bare "요" only after a syllable that ends a verb in polite speech (했어요, 좋아요, 가요,
     * 줘요, 할게요, 그런데요): "필요", "중요", "고요" are nouns and keep their 요.
     */
    private static final Pattern KO_BARE_YO = Pattern.compile(
            "(?<=[아어여해애에지게래데걸까나든가와워봐줘돼써려쳐켜혀])요(?=[.!?,…~\"')\\]]*(?:\\s|$))");
    private static final String KO_CLAUSE_END = "(?=[.!?,…~\"')\\]]*(?:\\s|$))";
    private static final Pattern[] KO_PLAIN_PATTERNS = new Pattern[KO_PLAIN.length];
    private static final String[][] KO_PRONOUNS = {
            {"당신의", "너의"}, {"당신은", "넌"}, {"당신이", "네가"}, {"당신을", "널"},
            {"당신에게", "너에게"}, {"당신", "너"}, {"그녀는", "걘"}, {"그녀가", "걔가"},
            {"그녀를", "걜"}, {"그녀의", "걔의"}, {"그녀", "걔"},
    };

    static {
        for (int i = 0; i < KO_PLAIN.length; i++) {
            KO_PLAIN_PATTERNS[i] = Pattern.compile(KO_PLAIN[i][0] + KO_CLAUSE_END);
        }
    }

    /** Polite → plain Japanese endings, longest first; anchored at a clause end. */
    private static final String[][] JA_PLAIN = {
            {"ではありませんでした", "じゃなかった"}, {"ではありません", "じゃない"},
            {"わかりませんでした", "わからなかった"}, {"わかりません", "わからない"},
            {"知りません", "知らない"}, {"できません", "できない"}, {"ありません", "ない"},
            {"していました", "していた"}, {"ていました", "ていた"}, {"でいました", "でいた"},
            {"しています", "している"}, {"ています", "ている"}, {"でいます", "でいる"},
            {"でしたが", "だったけど"},
            {"しました", "した"}, {"しましょう", "しよう"}, {"します", "する"},
            // Ichidan (e-row) verbs drop まし: やめました → やめた, 疲れました → 疲れた.
            {"えました", "えた"}, {"けました", "けた"}, {"せました", "せた"}, {"てました", "てた"},
            {"ねました", "ねた"}, {"べました", "べた"}, {"めました", "めた"}, {"れました", "れた"},
            {"させてください", "させて"}, {"てください", "て"}, {"でください", "で"},
            {"思います", "思う"}, {"言います", "言う"}, {"えます", "える"}, {"べます", "べる"},
            {"けます", "ける"}, {"いですね", "いね"}, {"いです", "い"}, {"でしょう", "だろう"},
            {"でした", "だった"}, {"ですね", "だね"}, {"です", "だ"},
    };
    /** Japanese has no spaces: any punctuation (or the line's end) closes the clause. */
    private static final String JA_CLAUSE_END = "(?=[。、，,！？!?…」』)]|\\s|$)";
    private static final Pattern[] JA_PLAIN_PATTERNS = new Pattern[JA_PLAIN.length];
    /** Formal second-person imperatives → informal (lyrics address one person). */
    private static final String[][] RU_PLAIN = {
            {"позвольте", "позволь"}, {"Позвольте", "Позволь"}, {"скажите", "скажи"},
            {"Скажите", "Скажи"}, {"дайте", "дай"}, {"Дайте", "Дай"},
            {"послушайте", "послушай"}, {"Послушайте", "Послушай"},
    };

    static {
        for (int i = 0; i < JA_PLAIN.length; i++) {
            JA_PLAIN_PATTERNS[i] = Pattern.compile(JA_PLAIN[i][0] + JA_CLAUSE_END);
        }
    }

    /**
     * Google's translation as displayed, in the song's register. Every language loses Google's
     * closing full stop (a lyric line is not a sentence); Korean, Japanese and Russian, the
     * targets whose politeness Google mixes within one song, are also made plain throughout.
     * Chinese needs nothing: Google already writes 你 there.
     */
    public static String postprocess(String translated, String targetLang, Register register) {
        if (translated == null || translated.isEmpty() || register == Register.KEEP) return translated;
        String lang = targetLang == null ? "" : targetLang.toLowerCase(Locale.ROOT);
        String out = translated;
        if (lang.startsWith("ko")) {
            for (int i = 0; i < KO_PLAIN.length; i++) {
                out = KO_PLAIN_PATTERNS[i].matcher(out).replaceAll(KO_PLAIN[i][1]);
            }
            out = KO_BARE_YO.matcher(out).replaceAll("");
            for (String[] pronoun : KO_PRONOUNS) out = out.replace(pronoun[0], pronoun[1]);
        } else if (lang.startsWith("ja")) {
            for (int i = 0; i < JA_PLAIN.length; i++) {
                out = JA_PLAIN_PATTERNS[i].matcher(out).replaceAll(JA_PLAIN[i][1]);
            }
            out = out.replace("あなた", register == Register.BLUNT ? "お前" : "君");
        } else if (lang.startsWith("ru")) {
            for (String[] pair : RU_PLAIN) {
                out = out.replaceAll("(?<!\\p{L})" + pair[0] + "(?!\\p{L})", pair[1]);
            }
        }
        return out.replaceAll("(?<![.。])[.。]\\s*$", "").trim();
    }
}
