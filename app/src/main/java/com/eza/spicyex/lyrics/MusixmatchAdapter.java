package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import okhttp3.Request;
import okhttp3.Response;

import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Musixmatch adapter (ported from Lyricify Lyrics Helper's Musixmatch provider, Apache-2.0):
 * title/artist search on the Android app's API, then {@code macro.subtitles.get} for word-level
 * richsync, else synced LRC, else plain lyrics. An explicit-check source like QQ and NetEase:
 * off by default, never in the automatic fallback order. A captcha pauses it for ten minutes.
 * Every terminal outcome persists a candidate or a source state.
 */
public final class MusixmatchAdapter {
    public static final int ADAPTER_REVISION = 1;
    private static final String MXM_BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String MXM_APP_ID = "android-player-v1.0";
    private static final String MXM_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13)";
    private static final long MXM_CAPTCHA_BACKOFF_MS = 10 * 60 * 1000L;
    private static volatile String mxmToken;
    private static volatile long mxmBlockedUntilMs;

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
        if (System.currentTimeMillis() < mxmBlockedUntilMs) {
            fail(context, track, callback, "Musixmatch paused after a captcha");
            return;
        }
        SpotifyTrack searchTrack = KaraokeTitles.forLyricsSearch(track, karaokeOriginalLyrics);
        boolean substituted = searchTrack != track;
        scheduler.execute(() -> {
            try {
                JsonObject found = findTrack(searchTrack);
                if (found == null) {
                    fail(context, track, callback, "Musixmatch: no matching track");
                    return;
                }
                long trackId = found.get("track_id").getAsLong();
                String body = get("macro.subtitles.get?namespace=lyrics_richsynched"
                        + "&optional_calls=track.richsync&subtitle_format=lrc"
                        + "&track_id=" + trackId + "&f_subtitle_length_max_deviation=40");
                if (body == null) {
                    fail(context, track, callback, "Musixmatch: no lyrics response");
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

    /** track.search, then the first hit whose title and artist actually match and whose length
     *  is within a few seconds - Musixmatch returns loosely related tracks for most queries. */
    private JsonObject findTrack(SpotifyTrack track) throws IOException {
        StringBuilder query = new StringBuilder("track.search?page_size=10&page=1&s_track_rating=desc");
        query.append("&q_track=").append(Uri.encode(safe(track.title)));
        query.append("&q_artist=").append(Uri.encode(safe(track.artist)));
        long durationSec = Math.max(0, track.duration) / 1000L;
        if (durationSec > 0) query.append("&q_duration=").append(durationSec);
        String body = get(query.toString());
        if (body == null) return null;
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement listElement = root.getAsJsonObject("message").getAsJsonObject("body").get("track_list");
        if (listElement == null || !listElement.isJsonArray()) return null;
        String wantTitle = normalize(track.title);
        String wantArtist = normalize(track.artist);
        for (JsonElement item : listElement.getAsJsonArray()) {
            if (!item.isJsonObject() || !item.getAsJsonObject().has("track")) continue;
            JsonObject candidate = item.getAsJsonObject().getAsJsonObject("track");
            String title = normalize(Json.optString(candidate, "track_name"));
            String artist = normalize(Json.optString(candidate, "artist_name"));
            boolean titleOk = !title.isEmpty() && (title.contains(wantTitle) || wantTitle.contains(title));
            boolean artistOk = wantArtist.isEmpty() || artist.contains(wantArtist) || wantArtist.contains(artist);
            long length = candidate.has("track_length") ? candidate.get("track_length").getAsLong() : 0L;
            boolean lengthOk = durationSec <= 0 || length <= 0 || Math.abs(length - durationSec) <= 4;
            if (titleOk && artistOk && lengthOk && candidate.has("track_id")) return candidate;
        }
        return null;
    }

    private static String normalize(String value) {
        String lower = safe(value).toLowerCase(java.util.Locale.ROOT);
        // Drop bracketed qualifiers ("(Remastered 2011)", "[feat. X]") and punctuation.
        lower = lower.replaceAll("[(\\[].*?[)\\]]", " ").replaceAll("[\\p{Punct}\\s]+", " ");
        return lower.trim();
    }

    /** One API call with the cached user token; renews it once on a 401 "renew". */
    private String get(String call) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = ensureToken();
            if (token == null) return null;
            String url = MXM_BASE + call + "&usertoken=" + Uri.encode(token) + "&format=json"
                    + "&app_id=" + MXM_APP_ID + "&t=" + java.util.UUID.randomUUID().toString().replace("-", "");
            String body = httpGet(url);
            if (body == null) return null;
            JsonObject header = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("message").getAsJsonObject("header");
            int status = header.has("status_code") ? header.get("status_code").getAsInt() : 0;
            String hint = Json.optString(header, "hint");
            if (status == 200 || status == 404) return body;
            if (status == 401 && "captcha".equalsIgnoreCase(hint)) {
                mxmBlockedUntilMs = System.currentTimeMillis() + MXM_CAPTCHA_BACKOFF_MS;
                return null;
            }
            if (status == 401 && "renew".equalsIgnoreCase(hint)) {
                mxmToken = null;
                continue;
            }
            return null;
        }
        return null;
    }

    private String ensureToken() throws IOException {
        String token = mxmToken;
        if (tokenUsable(token)) return token;
        synchronized (MusixmatchAdapter.class) {
            if (tokenUsable(mxmToken)) return mxmToken;
            String body = httpGet(MXM_BASE + "token.get?user_language=en&app_id=" + MXM_APP_ID
                    + "&t=" + java.util.UUID.randomUUID().toString().replace("-", ""));
            if (body == null) return null;
            JsonObject message = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("message");
            JsonObject header = message.getAsJsonObject("header");
            if ("captcha".equalsIgnoreCase(Json.optString(header, "hint"))) {
                mxmBlockedUntilMs = System.currentTimeMillis() + MXM_CAPTCHA_BACKOFF_MS;
                return null;
            }
            JsonElement bodyElement = message.get("body");
            String fresh = bodyElement != null && bodyElement.isJsonObject()
                    ? Json.optString(bodyElement.getAsJsonObject(), "user_token") : null;
            if (!tokenUsable(fresh)) return null;
            mxmToken = fresh;
            return fresh;
        }
    }

    private static boolean tokenUsable(String token) {
        if (token == null || token.trim().isEmpty() || "null".equals(token)) return false;
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) != '0') return true;
        return false;
    }

    private String httpGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", MXM_USER_AGENT)
                .header("Cookie", "AWSELB=0; AWSELBCORS=0")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) return null;
            String body = response.body().string();
            return body.isEmpty() ? null : body;
        }
    }
}
