package com.eza.spicyex.lyrics.providers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.Json;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Request;
import okhttp3.Response;

import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Musixmatch, asked the way its current web client asks (ported from BitChord's Musixmatch.kt,
 * GPLv3): {@code app_id=mobile-app-v1.0}, a browser User-Agent, and every request signed with
 * HMAC-SHA256 over {@code <url><UTC yyyyMMdd>} using the key the web app ships in its JavaScript
 * (read from musixmatch.com, a known key as fallback).
 *
 * <p>Why not the old Android-player route ({@code android-player-v1.0}, unsigned): its
 * {@code token.get} hands out a token once and then answers the same address with 401
 * {@code captcha} - and the token lived only in memory, so every Spotify restart asked again,
 * hit the captcha and paused this source. Searches never ran, which is why tracks Spotify's own
 * (Musixmatch-backed) lyrics had were "not found" here. The signed web route gets a token from
 * the same address, and the token is now kept across restarts.
 *
 * <p>Lyrics: word-level {@code track.richsync.get} when the track has it, else line-synced
 * {@code track.subtitle.get}; both are wrapped as the {@code macro.subtitles.get} shape
 * {@link LyricsParser#parseMusixmatchLyrics} reads.
 */
public final class MusixmatchAdapter {
    public static final int ADAPTER_REVISION = 2;
    private static final String BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String APP_ID = "mobile-app-v1.0";
    private static final String SEARCH_PAGE = "https://www.musixmatch.com/search";
    private static final String BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** Used only while the page serving the rotating key is unavailable. */
    private static final String FALLBACK_SECRET = "f09016176ba43a1cfd1031fbd6b3d26c";
    private static final long SECRET_TTL_MS = 12 * 60 * 60 * 1000L;
    private static final long CAPTCHA_BACKOFF_MS = 10 * 60 * 1000L;
    private static final String PREFS = "SpicyExMusixmatch";
    private static final String GUID = UUID.randomUUID().toString();
    private static final Pattern APP_SCRIPT = Pattern.compile(
            "src=[\"']([^\"']*/_next/static/chunks/pages/_app-[^\"']+\\.js)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_SECRET = Pattern.compile("from\\(\\s*[\"']([^\"']+)[\"']\\s*\\.split");

    private static volatile String secret;
    private static volatile long secretAtMs;
    private static volatile String token;
    private static volatile long blockedUntilMs;

    private final okhttp3.OkHttpClient http;
    private final LyricsRepository.Parser parser;
    private final ScheduledExecutorService scheduler;

    public MusixmatchAdapter(okhttp3.OkHttpClient http, LyricsRepository.Parser parser,
                             ScheduledExecutorService scheduler) {
        this.http = http;
        this.parser = parser;
        this.scheduler = scheduler;
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      boolean karaokeOriginalLyrics, LyricsRepository.ResultCallback callback) {
        if (System.currentTimeMillis() < blockedUntilMs) {
            fail(context, track, callback, "Musixmatch paused after a captcha");
            return;
        }
        SpotifyTrack searchTrack = KaraokeTitles.forLyricsSearch(track, karaokeOriginalLyrics);
        boolean substituted = searchTrack != track;
        scheduler.execute(() -> {
            try {
                String title = AppleTtmlMirrorAdapter.searchTitle(safe(searchTrack.title));
                String artist = AppleTtmlMirrorAdapter.primaryArtist(safe(searchTrack.artist));
                int seconds = (int) (Math.max(0, searchTrack.duration) / 1000L);
                JsonObject found = bestTrack(context, title, artist, seconds,
                        new TrackMatchScorer.Target(title, safe(searchTrack.artist),
                                searchTrack.album, Math.max(0, searchTrack.duration)));
                if (found == null) {
                    fail(context, track, callback, "Musixmatch: no match");
                    return;
                }
                long trackId = found.get("track_id").getAsLong();
                String body = lyricsBody(context, trackId,
                        (int) Json.optDouble(found, 0d, "has_richsync") != 0,
                        (int) Json.optDouble(found, 0d, "has_subtitles") != 0);
                if (body == null) {
                    fail(context, track, callback, "Musixmatch: no lyrics for the match");
                    return;
                }
                LyricsDocument doc = parser.parseMusixmatchLyrics(context, track, body);
                if (doc == null || doc.lines.isEmpty()) {
                    fail(context, track, callback, "Musixmatch empty");
                    return;
                }
                doc.generation = generation;
                doc.selectedSource = "Musixmatch";
                doc.selectionMode = "strict";
                doc.selectionOverride = "Musixmatch";
                CatalogSource.MatchMethod method = substituted
                        ? CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION
                        : CatalogSource.MatchMethod.STRONG_SEARCH;
                CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.MUSIXMATCH, track, doc,
                        method, String.valueOf(trackId), body, ADAPTER_REVISION);
                callback.onSuccess(doc);
            } catch (Throwable t) {
                fail(context, track, callback, "Musixmatch failed: " + safe(t.getMessage()));
            }
        });
    }

    private void fail(Context context, SpotifyTrack track,
                      LyricsRepository.ResultCallback callback, String error) {
        CatalogAdapters.recordError(context, CatalogSource.SourceId.MUSIXMATCH, track, error);
        callback.onError(error);
    }

    // --- Matching -------------------------------------------------------------------------------

    private JsonObject bestTrack(Context context, String title, String artist, int seconds,
                                 TrackMatchScorer.Target target) throws IOException {
        String body = call(context, "track.search?app_id=" + APP_ID + "&format=json"
                + "&q_track=" + Uri.encode(title) + "&q_artist=" + Uri.encode(artist)
                + "&f_has_lyrics=1&s_track_rating=desc&quorum_factor=1&page_size=10&page=1");
        if (body == null) return null;
        JsonObject message = message(body);
        JsonObject payload = message == null ? null : objectAt(message, "body");
        JsonElement list = payload == null ? null : payload.get("track_list");
        if (list == null || !list.isJsonArray()) return null;
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement item : list.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject candidate = objectAt(item.getAsJsonObject(), "track");
            if (candidate == null || !candidate.has("track_id")) continue;
            int length = (int) Json.optDouble(candidate, 0d, "track_length");
            // The shared gate first (a miss beats a wrong song), BitChord's score to rank.
            TrackMatchScorer.Score match = TrackMatchScorer.score(target,
                    AppleTtmlMirrorAdapter.searchTitle(Json.optString(candidate, "track_name")),
                    TrackMatchScorer.splitArtists(Json.optString(candidate, "artist_name")),
                    Json.optString(candidate, "album_name"), length * 1000L);
            if (!match.accepted()) continue;
            double score = score(Json.optString(candidate, "track_name"),
                    Json.optString(candidate, "artist_name"), length, title, artist, seconds);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** BitChord's scoring: title 80/40, artist 40, length +30/+15/+5 or -20 when far off. */
    static double score(String name, String artistName, int length, String title, String artist,
                        int seconds) {
        double score = 0d;
        String n = safe(name).trim().toLowerCase(Locale.ROOT);
        String t = safe(title).trim().toLowerCase(Locale.ROOT);
        if (!n.isEmpty() && !t.isEmpty()) {
            if (n.equals(t)) score += 80d;
            else if (n.contains(t) || t.contains(n)) score += 40d;
        }
        String a = safe(artist).trim().toLowerCase(Locale.ROOT);
        if (!a.isEmpty() && safe(artistName).toLowerCase(Locale.ROOT).contains(a)) score += 40d;
        if (length > 0 && seconds > 0) {
            int diff = Math.abs(length - seconds);
            score += diff <= 2 ? 30d : diff <= 5 ? 15d : diff <= 10 ? 5d : -20d;
        }
        return score;
    }

    /** The lyrics as a {@code macro.subtitles.get}-shaped body, or null. */
    private String lyricsBody(Context context, long trackId, boolean hasRichsync,
                              boolean hasSubtitles) throws IOException {
        JsonObject calls = new JsonObject();
        if (hasRichsync) {
            String rich = call(context, "track.richsync.get?app_id=" + APP_ID
                    + "&format=json&track_id=" + trackId);
            JsonObject message = rich == null ? null : message(rich);
            if (message != null && status(message) == 200) calls.add("track.richsync.get", wrap(message));
        }
        if (!calls.has("track.richsync.get") && hasSubtitles) {
            String sub = call(context, "track.subtitle.get?app_id=" + APP_ID
                    + "&format=json&subtitle_format=lrc&track_id=" + trackId);
            JsonObject message = sub == null ? null : message(sub);
            JsonObject subtitle = message == null ? null : objectAt(message, "body", "subtitle");
            if (subtitle != null && status(message) == 200) {
                // The parser reads the macro call's subtitle_list[0].subtitle.subtitle_body.
                JsonObject entry = new JsonObject();
                entry.add("subtitle", subtitle);
                JsonArray subtitleList = new JsonArray();
                subtitleList.add(entry);
                JsonObject body = new JsonObject();
                body.add("subtitle_list", subtitleList);
                JsonObject header = new JsonObject();
                header.addProperty("status_code", 200);
                JsonObject wrapped = new JsonObject();
                wrapped.add("header", header);
                wrapped.add("body", body);
                calls.add("track.subtitles.get", wrap(wrapped));
            }
        }
        if (calls.size() == 0) return null;
        JsonObject root = new JsonObject();
        JsonObject message = new JsonObject();
        JsonObject body = new JsonObject();
        body.add("macro_calls", calls);
        message.add("body", body);
        root.add("message", message);
        return root.toString();
    }

    private static JsonObject wrap(JsonObject message) {
        JsonObject call = new JsonObject();
        call.add("message", message);
        return call;
    }

    // --- Signed calls ---------------------------------------------------------------------------

    /** One signed API call with the kept token; a 401/402 renews key and token once. */
    private String call(Context context, String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String key = secret();
            String user = token(context, key);
            if (user == null) return null;
            String body = get(sign(BASE + path + "&usertoken=" + Uri.encode(user), key));
            if (body == null) return null;
            JsonObject message = message(body);
            int status = message == null ? 0 : status(message);
            String hint = message == null ? "" : Json.optString(objectAt(message, "header"), "hint");
            if (status == 401 && "captcha".equalsIgnoreCase(hint)) {
                blockedUntilMs = System.currentTimeMillis() + CAPTCHA_BACKOFF_MS;
                return null;
            }
            if (status == 401 || status == 402) {
                forgetToken(context);
                secret = null;
                continue;
            }
            return body;
        }
        return null;
    }

    private String token(Context context, String key) throws IOException {
        String kept = token;
        if (usable(kept)) return kept;
        synchronized (MusixmatchAdapter.class) {
            if (usable(token)) return token;
            SharedPreferences prefs = prefs(context);
            String stored = prefs == null ? null : prefs.getString("user_token", null);
            if (usable(stored)) {
                token = stored;
                return stored;
            }
            String body = get(sign(BASE + "token.get?app_id=" + APP_ID + "&guid=" + GUID
                    + "&format=json", key));
            JsonObject message = body == null ? null : message(body);
            if (message == null) return null;
            if ("captcha".equalsIgnoreCase(Json.optString(objectAt(message, "header"), "hint"))) {
                blockedUntilMs = System.currentTimeMillis() + CAPTCHA_BACKOFF_MS;
                return null;
            }
            String fresh = Json.optString(objectAt(message, "body"), "user_token");
            if (!usable(fresh)) return null;
            token = fresh;
            // Kept across Spotify restarts: asking for a new one each time is what earns the
            // captcha.
            if (prefs != null) prefs.edit().putString("user_token", fresh).apply();
            return fresh;
        }
    }

    private void forgetToken(Context context) {
        token = null;
        SharedPreferences prefs = prefs(context);
        if (prefs != null) prefs.edit().remove("user_token").apply();
    }

    private static SharedPreferences prefs(Context context) {
        try {
            return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean usable(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) != '0') return true;
        return false;
    }

    /** The web app's signing key, read from its JavaScript (half a day at a time). */
    private String secret() {
        String kept = secret;
        if (kept != null && System.currentTimeMillis() - secretAtMs < SECRET_TTL_MS) return kept;
        String found = null;
        try {
            String page = browserGet(SEARCH_PAGE, "text/html,application/xhtml+xml");
            Matcher script = page == null ? null : APP_SCRIPT.matcher(page);
            if (script != null && script.find()) {
                String url = script.group(1).startsWith("http") ? script.group(1)
                        : "https://www.musixmatch.com" + (script.group(1).startsWith("/") ? "" : "/")
                        + script.group(1);
                String js = browserGet(url, "*/*");
                Matcher encoded = js == null ? null : ENCODED_SECRET.matcher(js);
                if (encoded != null && encoded.find()) found = decodeSecret(encoded.group(1));
            }
        } catch (Throwable ignored) {
        }
        secret = found == null || found.isEmpty() ? FALLBACK_SECRET : found;
        secretAtMs = System.currentTimeMillis();
        return secret;
    }

    /** The key is shipped base64-encoded and reversed. */
    static String decodeSecret(String encoded) {
        String reversed = new StringBuilder(encoded).reverse().toString();
        return new String(java.util.Base64.getDecoder().decode(reversed), StandardCharsets.UTF_8);
    }

    /** {@code url&signature=…&signature_protocol=sha256}: HMAC-SHA256 of url + UTC yyyyMMdd. */
    static String sign(String url, String key) {
        String normalized = url.replace("%20", "+").replace(" ", "+");
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        return signFor(normalized, key, day.format(new Date()));
    }

    static String signFor(String normalizedUrl, String key, String utcDay) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((normalizedUrl + utcDay).getBytes(StandardCharsets.UTF_8));
            String signature = java.util.Base64.getEncoder().encodeToString(raw);
            return normalizedUrl + "&signature=" + java.net.URLEncoder.encode(signature, "UTF-8")
                    + "&signature_protocol=sha256";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get()
                .header("User-Agent", BROWSER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) return null;
            String body = response.body().string();
            return body.isEmpty() ? null : body;
        }
    }

    private String browserGet(String url, String accept) throws IOException {
        Request request = new Request.Builder().url(url).get()
                .header("User-Agent", BROWSER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "mxm_bab=AB")
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.isSuccessful() && response.body() != null ? response.body().string() : null;
        }
    }

    private static JsonObject message(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            return root.isJsonObject() ? objectAt(root.getAsJsonObject(), "message") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int status(JsonObject message) {
        JsonObject header = objectAt(message, "header");
        return header == null ? 0 : (int) Json.optDouble(header, 0d, "status_code");
    }

    private static JsonObject objectAt(JsonObject root, String... path) {
        JsonObject current = root;
        for (String key : path) {
            if (current == null || !current.has(key) || !current.get(key).isJsonObject()) return null;
            current = current.getAsJsonObject(key);
        }
        return current;
    }
}
