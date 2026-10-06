package com.flowify.ettea.lyrics.providers;

import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Keyless mirrors of Apple Music's word-timed TTML, asked by name (ported from BitChord's
 * BetterLyrics.kt and BiniLyrics.kt, GPLv3):
 * <ul>
 *   <li>BetterLyrics ({@code lyrics-api.boidu.dev}), the backend of the YouTube Music extension
 *       of that name: one call with title, artist and length, answering {@code {"ttml": ...}}.</li>
 *   <li>BiniLyrics ({@code lyrics-api.binimum.org}): the same catalogue behind a different matcher
 *       (it finds tracks BetterLyrics misses, especially outside English releases); its search
 *       returns a document URL, so the TTML is a second request. It sits behind Cloudflare, which
 *       answers some networks with a JavaScript challenge a native client cannot pass - that is
 *       reported as a transient failure, not a miss.</li>
 * </ul>
 * Both hand back TTML only; the caller parses it with the AMLL TTML parser.
 */
public final class AppleTtmlMirrorAdapter {
    static final int ADAPTER_REVISION = 1;
    private static final String USER_AGENT = "Flowify (https://github.com/wnsdn517/Flowify)";
    private static final String BETTER_LYRICS = "https://lyrics-api.boidu.dev/getLyrics";
    private static final String BINI_LYRICS = "https://lyrics-api.binimum.org/";

    public interface TtmlCallback {
        void onSuccess(String ttml);

        void onError(String error);
    }

    private final OkHttpClient http;

    public AppleTtmlMirrorAdapter(OkHttpClient http) {
        this.http = http;
    }

    public void fetchBetterLyrics(String title, String artist, String album, long durationMs,
                                  TtmlCallback callback) {
        StringBuilder url = new StringBuilder(BETTER_LYRICS)
                .append("?s=").append(Uri.encode(searchTitle(title)))
                .append("&a=").append(Uri.encode(primaryArtist(artist)));
        long seconds = Math.max(0, durationMs) / 1000L;
        if (seconds > 0) url.append("&d=").append(seconds);
        if (album != null && !album.trim().isEmpty()) url.append("&al=").append(Uri.encode(album.trim()));
        get(url.toString(), "BetterLyrics", new BodyCallback() {
            @Override public void onBody(String body) {
                String ttml = extractTtml(body);
                if (ttml == null) callback.onError("BetterLyrics: no lyrics in response");
                else callback.onSuccess(ttml);
            }

            @Override public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    public void fetchBiniLyrics(String title, String artist, String album, long durationMs,
                                TtmlCallback callback) {
        StringBuilder url = new StringBuilder(BINI_LYRICS)
                .append("?track=").append(Uri.encode(searchTitle(title)))
                .append("&artist=").append(Uri.encode(primaryArtist(artist)));
        if (album != null && !album.trim().isEmpty()) url.append("&album=").append(Uri.encode(album.trim()));
        long seconds = Math.max(0, durationMs) / 1000L;
        if (seconds > 0) url.append("&duration=").append(seconds);
        get(url.toString(), "BiniLyrics", new BodyCallback() {
            @Override public void onBody(String body) {
                String document = firstLyricsUrl(body);
                if (document == null) {
                    callback.onError("BiniLyrics: no match");
                    return;
                }
                get(document, "BiniLyrics", new BodyCallback() {
                    @Override public void onBody(String ttml) {
                        if (looksLikeTtml(ttml)) callback.onSuccess(ttml);
                        else callback.onError("BiniLyrics: document is not TTML");
                    }

                    @Override public void onError(String error) {
                        callback.onError(error);
                    }
                });
            }

            @Override public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private interface BodyCallback {
        void onBody(String body);

        void onError(String error);
    }

    private void get(String url, String label, BodyCallback callback) {
        Request request = new Request.Builder().url(url).get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError(label + ": request failed: " + error.getMessage());
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    int code = response.code();
                    String body = response.body() == null ? "" : response.body().string();
                    if (isChallenge(code, body)) {
                        callback.onError(label + ": blocked by a Cloudflare challenge on this network");
                    } else if (code == 401 || code == 404) {
                        // Both answer a miss this way rather than with an empty result.
                        callback.onError(label + ": no match");
                    } else if (!response.isSuccessful() || body.trim().isEmpty()) {
                        callback.onError(label + ": HTTP " + code);
                    } else {
                        callback.onBody(body);
                    }
                } catch (Throwable error) {
                    callback.onError(label + ": " + error.getMessage());
                }
            }
        });
    }

    static boolean isChallenge(int code, String body) {
        String head = body == null ? "" : body.trim();
        if (head.length() > 600) head = head.substring(0, 600);
        head = head.toLowerCase(Locale.ROOT);
        return (code == 307 || code == 403 || code == 503 || head.startsWith("<html") || head.startsWith("<!doctype"))
                && (head.contains("cloudflare") || head.contains("challenge-platform") || head.contains("cf-ray"));
    }

    /** The TTML inside a provider envelope ({@code {"ttml": "..."}}), or the body when it is TTML. */
    static String extractTtml(String body) {
        if (body == null) return null;
        String trimmed = body.replace("﻿", "").trim();
        if (looksLikeTtml(trimmed)) return trimmed;
        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (!root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            for (String key : new String[] {"ttml", "ttmlContent", "lyrics", "content"}) {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonPrimitive()) {
                    String text = value.getAsString().trim();
                    if (looksLikeTtml(text)) return text;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** {@code results[0].lyricsUrl} of a BiniLyrics search. */
    static String firstLyricsUrl(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonElement results = root.getAsJsonObject().get("results");
            if (results == null || !results.isJsonArray()) return null;
            JsonArray list = results.getAsJsonArray();
            if (list.size() == 0 || !list.get(0).isJsonObject()) return null;
            JsonElement url = list.get(0).getAsJsonObject().get("lyricsUrl");
            String value = url != null && url.isJsonPrimitive() ? url.getAsString().trim() : "";
            return value.startsWith("http") ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean looksLikeTtml(String text) {
        if (text == null) return false;
        String head = text.trim();
        if (head.length() > 400) head = head.substring(0, 400);
        // Must start as markup: a JSON envelope also contains the TTML namespace URL.
        return head.startsWith("<tt") || (head.startsWith("<") && head.contains("http://www.w3.org/ns/ttml"));
    }

    // --- Names as a lyrics catalogue filed them (BitChord's LyricsQuery) -------------------------

    private static final Pattern[] CREDITS = {
            Pattern.compile("\\s*[(\\[]\\s*(feat|ft|featuring|with)\\b[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+(feat|ft|featuring)\\.?\\s+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*[(\\[]\\s*(official\\s*)?(music\\s*)?(video|audio|visuali[sz]er|lyrics?\\s*video|lyrics?|m/?v|hd|hq|4k)\\s*[)\\]]",
                    Pattern.CASE_INSENSITIVE),
    };

    /**
     * The title without credits ("(feat. X)", "[with Y]") or upload packaging: catalogues file
     * "Song (feat. X)" as "Song". Version markers (Remix, Live, Acoustic) stay - they name a
     * different recording, and the wrong recording's words are worse than none.
     */
    public static String searchTitle(String title) {
        if (title == null) return "";
        String name = title;
        for (Pattern credit : CREDITS) name = credit.matcher(name).replaceAll(" ");
        name = name.replaceAll("\\s+", " ").trim().replaceAll("[,\\-–—]+$", "").trim();
        return name.isEmpty() ? title.trim() : name;
    }

    /** The first credited artist: "A, B" / "A & B" / "A feat. B" are filed under A. */
    public static String primaryArtist(String artist) {
        if (artist == null) return "";
        String value = artist.trim();
        String[] separators = {", ", " & ", " feat. ", " ft. ", " x ", " / ", "; "};
        int cut = value.length();
        for (String separator : separators) {
            int at = value.indexOf(separator);
            if (at > 0 && at < cut) cut = at;
        }
        return value.substring(0, cut).trim();
    }
}
