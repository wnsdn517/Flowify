package com.flowify.ettea.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.flowify.ettea.lyrics.LyricUtils.isBlank;
import static com.flowify.ettea.lyrics.LyricUtils.safe;

/**
 * Plain-text fallback for when nothing synced has the track at all: Genius carries no timing, but
 * its catalogue is the largest one around for exactly the tracks the timed sources miss.
 *
 * <p>Ported from BitChord (github.com/kushagrasinghx/BitChord, GPLv3 - compatible with this
 * project's AGPLv3), whose {@code Genius.kt} is itself the result of iterating against the real
 * site: the search endpoint is open (no key), and the lyrics page's {@code data-lyrics-container}
 * attribute survives Genius's hashed CSS classes changing under it. One correction carried over
 * verbatim from their own comment, because it is counterintuitive and was measured, not guessed:
 * {@link #USER_AGENT} must NOT claim to be a browser. Genius sits behind Cloudflare, which
 * challenges a browser-claiming User-Agent whose TLS fingerprint doesn't back the claim; a plain
 * one is answered normally.
 *
 * <p>Not wired into the source picker or the automatic fallback chain yet - this is the adapter on
 * its own, ready to be called, matching how {@link QqMusicAdapter} itself first landed before its
 * own wiring slice (see that class's header). {@link #fetch} is the entry point a future slice
 * would call from {@code LyricsRepository.fetchSingleSource}.
 */
public final class GeniusAdapter {
    /** Bump when parsing changes so stored candidates from older code are re-fetched. */
    static final int ADAPTER_REVISION = 1;

    private static final String USER_AGENT = "SpicyEX";
    private static final String SEARCH_URL = "https://genius.com/api/search/multi?q=";

    public interface LinesCallback {
        void onSuccess(List<String> lines);
        void onError(String error);
    }

    private final OkHttpClient http;

    public GeniusAdapter(OkHttpClient http) {
        this.http = http;
    }

    /** Searches, picks the best hit, fetches its page, and extracts plain lyric lines. */
    public void fetch(String title, String artist, LinesCallback callback) {
        List<SearchAttempt> attempts = searchAttempts(title, artist);
        if (attempts.isEmpty()) {
            callback.onError("Genius: nothing to search for");
            return;
        }
        tryAttempt(attempts, 0, callback);
    }

    private void tryAttempt(List<SearchAttempt> attempts, int index, LinesCallback callback) {
        if (index >= attempts.size()) {
            callback.onError("Genius: no matching song found");
            return;
        }
        SearchAttempt attempt = attempts.get(index);
        Request request = pageRequest(SEARCH_URL + encode(attempt.query));
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                tryAttempt(attempts, index + 1, callback);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String songUrl;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        tryAttempt(attempts, index + 1, callback);
                        return;
                    }
                    songUrl = bestMatchUrl(response.body().string(), attempt.title, attempt.artist);
                }
                if (songUrl == null) {
                    tryAttempt(attempts, index + 1, callback);
                    return;
                }
                fetchPage(songUrl, attempts, index, callback);
            }
        });
    }

    private void fetchPage(String songUrl, List<SearchAttempt> attempts, int index, LinesCallback callback) {
        http.newCall(pageRequest(songUrl)).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                tryAttempt(attempts, index + 1, callback);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                List<String> lines;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        tryAttempt(attempts, index + 1, callback);
                        return;
                    }
                    lines = parseHtml(response.body().string());
                }
                if (lines == null || lines.isEmpty()) {
                    tryAttempt(attempts, index + 1, callback);
                    return;
                }
                callback.onSuccess(lines);
            }
        });
    }

    private Request pageRequest(String url) {
        return new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception impossible) {
            return value;
        }
    }

    // --- Query planning -------------------------------------------------------------------

    static final class SearchAttempt {
        final String query;
        final String title;
        final String artist;

        SearchAttempt(String query, String title, String artist) {
            this.query = query;
            this.title = title;
            this.artist = artist;
        }
    }

    /** Several queries, broadest-information-first, so a title that already embeds the artist
     *  ("Artist - Title") or a noisy YouTube-style title still finds the right song. */
    static List<SearchAttempt> searchAttempts(String rawTitle, String rawArtist) {
        String cleanTitle = cleanQuery(safe(rawTitle));
        String cleanArtist = cleanQuery(safe(rawArtist));

        String[] titleParts = cleanTitle.contains(" - ") || TITLE_SEPARATOR.matcher(cleanTitle).find()
                ? TITLE_SEPARATOR.split(cleanTitle, 2) : null;

        String extractedTitle = cleanTitle;
        String extractedArtist = cleanArtist;
        if (titleParts != null && titleParts.length == 2) {
            String left = titleParts[0].trim();
            String right = titleParts[1].trim();
            if (left.equalsIgnoreCase(cleanArtist)) {
                extractedTitle = right;
            } else if (right.equalsIgnoreCase(cleanArtist)) {
                extractedTitle = left;
            } else if (!left.isEmpty() && !right.isEmpty()) {
                extractedTitle = right;
                if (cleanArtist.isEmpty()) extractedArtist = left;
            }
        }

        String titleWithoutBrackets = NON_ALPHANUMERIC
                .matcher(BRACKETED_CONTENT.matcher(extractedTitle).replaceAll(" "))
                .replaceAll(" ").replaceAll("\\s+", " ").trim();

        List<SearchAttempt> attempts = new ArrayList<>();
        if (!extractedArtist.isEmpty() && !extractedTitle.isEmpty()) {
            attempts.add(new SearchAttempt((extractedArtist + " " + extractedTitle).trim(), extractedTitle, extractedArtist));
        }
        if (!extractedArtist.isEmpty() && !titleWithoutBrackets.isEmpty()
                && !titleWithoutBrackets.equals(extractedTitle)) {
            attempts.add(new SearchAttempt((extractedArtist + " " + titleWithoutBrackets).trim(),
                    titleWithoutBrackets, extractedArtist));
        }
        if (!titleWithoutBrackets.isEmpty()) {
            attempts.add(new SearchAttempt(titleWithoutBrackets, titleWithoutBrackets, extractedArtist));
        } else if (!extractedTitle.isEmpty()) {
            attempts.add(new SearchAttempt(extractedTitle, extractedTitle, extractedArtist));
        }

        List<SearchAttempt> distinct = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SearchAttempt attempt : attempts) {
            if (!attempt.query.isEmpty() && seen.add(attempt.query)) distinct.add(attempt);
        }
        return distinct;
    }

    static String cleanQuery(String text) {
        String cleaned = DECORATIVE_CHARS.matcher(text).replaceAll(" ");
        cleaned = NOISE.matcher(cleaned).replaceAll(" ");
        cleaned = PRODUCER_TAGS.matcher(cleaned).replaceAll(" ");
        int pipe = cleaned.indexOf(" | ");
        if (pipe >= 0) cleaned = cleaned.substring(0, pipe);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? text.trim() : cleaned;
    }

    // --- Search result ranking -------------------------------------------------------------

    /** Best-matching song's page URL out of a {@code /api/search/multi} response, or null. */
    static String bestMatchUrl(String searchJson, String targetTitle, String targetArtist) {
        try {
            JsonElement root = JsonParser.parseString(searchJson);
            if (!root.isJsonObject()) return null;
            JsonObject response = root.getAsJsonObject().getAsJsonObject("response");
            if (response == null) return null;
            JsonArray sections = response.getAsJsonArray("sections");
            if (sections == null) return null;
            JsonArray hits = null;
            for (JsonElement sectionEl : sections) {
                if (!sectionEl.isJsonObject()) continue;
                JsonObject section = sectionEl.getAsJsonObject();
                if (section.has("type") && "song".equals(section.get("type").getAsString())) {
                    hits = section.getAsJsonArray("hits");
                    break;
                }
            }
            if (hits == null) return null;
            List<JsonObject> candidates = new ArrayList<>();
            for (JsonElement hitEl : hits) {
                if (!hitEl.isJsonObject()) continue;
                JsonElement result = hitEl.getAsJsonObject().get("result");
                if (result != null && result.isJsonObject()) candidates.add(result.getAsJsonObject());
            }
            JsonObject best = bestMatch(candidates, targetTitle, targetArtist);
            if (best == null || !best.has("url")) return null;
            return best.get("url").getAsString();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static JsonObject bestMatch(List<JsonObject> candidates, String targetTitle, String targetArtist) {
        if (candidates.isEmpty()) return null;
        String normTitle = matchKey(AppleTtmlMirrorAdapter.searchTitle(targetTitle));
        String normArtist = matchKey(AppleTtmlMirrorAdapter.primaryArtist(targetArtist));

        JsonObject bestItem = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonObject item : candidates) {
            String title = matchKey(optString(item, "title"));
            String artist = matchKey(optString(item, "artist_names"));
            // Both must match, and neither side may be empty: "x".contains("") is true, and a
            // title-only match on a common word picked a different artist's song entirely.
            boolean titleMatches = !normTitle.isEmpty() && !title.isEmpty()
                    && (title.equals(normTitle) || title.contains(normTitle) || normTitle.contains(title));
            boolean artistMatches = !normArtist.isEmpty() && !artist.isEmpty()
                    && (artist.equals(normArtist) || artist.contains(normArtist) || normArtist.contains(artist));
            if (!titleMatches || !artistMatches) continue;
            if (!TrackMatchScorer.score(new TrackMatchScorer.Target(targetTitle, targetArtist, null, 0L),
                    AppleTtmlMirrorAdapter.searchTitle(optString(item, "title")),
                    TrackMatchScorer.splitArtists(optString(item, "artist_names")), null, 0L)
                    .accepted()) {
                continue;
            }

            int score = 0;
            score += title.equals(normTitle) ? 50 : (titleMatches ? 25 : 0);
            if (artistMatches) score += artist.equals(normArtist) ? 40 : 20;

            String path = optString(item, "path").toLowerCase(Locale.ROOT);
            if (path.contains("translation") && !normTitle.contains("translation")) score -= 30;
            if (path.contains("türkçe") || path.contains("polskie-tlumaczenie")) score -= 40;
            if (path.contains("tracklist") || path.contains("album-art")) score -= 50;

            if (score > 0 && score > bestScore) {
                bestScore = score;
                bestItem = item;
            }
        }
        return bestItem;
    }

    /** Lower case, letters and digits only: punctuation and spacing differ between catalogues. */
    static String matchKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String optString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    // --- Page parsing -----------------------------------------------------------------------

    /** Plain lyric lines from a Genius song page, or null if the page has none. Genius's own
     *  {@code data-exclude-from-selection} marks exactly the header/bio/ad clutter inside the
     *  lyrics container that is not a lyric - a stable signal that survives Genius restyling,
     *  unlike matching its hashed CSS class names. */
    public static List<String> parseHtml(String html) {
        if (isBlank(html)) return null;
        Document doc = Jsoup.parse(html);
        Elements containers = doc.select("div[data-lyrics-container=true]");
        if (containers.isEmpty()) containers = doc.select("div.lyrics");
        if (containers.isEmpty()) return null;

        StringBuilder full = new StringBuilder();
        for (Element container : containers) {
            for (Element excluded : container.select(
                    "[data-exclude-from-selection=true], .LyricsHeader__Container, "
                            + ".SongBioPreview__Container, .InreadAd__Container, button, script, style")) {
                excluded.remove();
            }
            for (Element br : container.select("br")) br.replaceWith(new TextNode("\n"));
            for (Element p : container.select("p")) p.prependText("\n");
            String text = container.wholeText();
            if (!isBlank(text)) full.append(text).append('\n');
        }
        if (full.length() == 0) return null;
        return textToLines(stripArtifacts(full.toString()));
    }

    /** Common scraper artifacts: Genius's inline recommendation and the trailing contributor
     *  count that precedes "Embed" on the copy-paste text of the page. */
    static String stripArtifacts(String raw) {
        String cleaned = raw.replace('\u00A0', ' ').replace('\u200B', ' ').replace('\uFEFF', ' ');
        cleaned = YOU_MIGHT_ALSO_LIKE.matcher(cleaned).replaceAll("");
        cleaned = cleaned.trim();
        cleaned = TRAILING_EMBED.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /** Multi-line text to lyric lines, one blank entry per stanza gap (never leading/trailing). */
    static List<String> textToLines(String text) {
        List<String> result = new ArrayList<>();
        boolean lastWasGap = false;
        for (String rawLine : text.split("\n", -1)) {
            String line = TRAILING_EMBED.matcher(rawLine.trim()).replaceAll("").trim();
            if (line.isEmpty()) {
                if (!lastWasGap && !result.isEmpty()) {
                    result.add("");
                    lastWasGap = true;
                }
            } else {
                result.add(line);
                lastWasGap = false;
            }
        }
        while (!result.isEmpty() && result.get(0).isEmpty()) result.remove(0);
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return result;
    }

    /** A section header like "[Verse 1]", "[Chorus]", "[Bridge: Freddie Mercury]". */
    public static boolean isSectionHeader(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]")
                && trimmed.length() >= 3 && trimmed.length() <= 60;
    }

    private static final Pattern TITLE_SEPARATOR = Pattern.compile("\\s*[-\u2013\u2014:]\\s*");
    private static final Pattern DECORATIVE_CHARS = Pattern.compile("[\u266A\u266B\u2605\u2606\u3010\u3011\u300A\u300B\u300C\u300D~_]");
    private static final Pattern PRODUCER_TAGS = Pattern.compile("(?i)\\b(?:prod(?:uced)?\\.?(?:\\s+by)?)\\s+.*$");
    private static final Pattern NOISE = Pattern.compile(
            "\\s*[(\\[]\\s*(?:from|feat\\.?|ft\\.?|featuring|with|prod\\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\\]]*[)\\]]"
                    + "|\\s*\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|lyrical(?:\\s+video)?|full\\s+song|4k\\s+video|hd\\s+video|music\\s+video)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETED_CONTENT = Pattern.compile("\\s*[(\\[].*?[)\\]]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern YOU_MIGHT_ALSO_LIKE = Pattern.compile("(?i)\\d*You might also like");
    private static final Pattern TRAILING_EMBED = Pattern.compile("(?i)\\d*Embed\\s*$");
}
