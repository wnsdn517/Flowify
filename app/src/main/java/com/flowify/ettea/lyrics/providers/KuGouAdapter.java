package com.flowify.ettea.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.flowify.ettea.lyrics.LyricUtils.isBlank;

/**
 * Line-synced fallback from KuGou's public mobile/lyrics endpoints - a Chinese catalogue that
 * also carries a great many English and Hindi tracks the other sources here don't have.
 *
 * <p>Ported from BitChord (github.com/kushagrasinghx/BitChord, GPLv3 - compatible with this
 * project's AGPLv3): three unauthenticated calls, chained - search the song to get its audio
 * fingerprint ({@code hash}), search lyric candidates against that hash (duration-filtered,
 * closest match first), then download the winning candidate as base64-encoded LRC. A keyword-only
 * lyrics search (skipping the hash) is the fallback that catches whatever the first two miss.
 *
 * <p>Not wired into the source picker or the automatic fallback chain yet - see
 * {@link GeniusAdapter}'s header for why, and {@link QqMusicAdapter}'s own history of landing the
 * same way.
 */
public final class KuGouAdapter {
    /** Bump when parsing changes so stored candidates from older code are re-fetched. */
    static final int ADAPTER_REVISION = 1;

    private static final int DURATION_TOLERANCE_SECONDS = 8;

    public interface LrcCallback {
        void onSuccess(String lrcText);
        void onError(String error);
    }

    private final OkHttpClient http;

    public KuGouAdapter(OkHttpClient http) {
        this.http = http;
    }

    public void fetch(String title, String artist, String album, long durationMs, LrcCallback callback) {
        String keyword = keyword(title, artist, album);
        int seconds = (int) (durationMs / 1000);
        TrackMatchScorer.Target target = new TrackMatchScorer.Target(
                AppleTtmlMirrorAdapter.searchTitle(title), artist, album, Math.max(0, durationMs));
        searchSongHashes(keyword, seconds, target, new HashesCallback() {
            @Override public void onHashes(List<String> hashes) {
                tryHash(hashes, 0, keyword, seconds, target, callback);
            }
            @Override public void onError(String error) {
                searchLyricsByKeyword(keyword, seconds, target, callback);
            }
        });
    }

    private void tryHash(List<String> hashes, int index, String keyword, int seconds,
                         TrackMatchScorer.Target target, LrcCallback callback) {
        if (index >= hashes.size()) {
            searchLyricsByKeyword(keyword, seconds, target, callback);
            return;
        }
        searchLyricsByHash(hashes.get(index), new CandidateCallback() {
            @Override public void onCandidate(String id, String accessKey) {
                downloadLrc(id, accessKey, callback);
            }
            @Override public void onError(String error) {
                tryHash(hashes, index + 1, keyword, seconds, target, callback);
            }
        });
    }

    private void searchLyricsByKeyword(String keyword, int seconds, TrackMatchScorer.Target target,
                                       LrcCallback callback) {
        String url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + encode(keyword)
                + (seconds > 0 ? "&duration=" + (seconds * 1000) : "");
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError("KuGou: keyword search failed: " + error.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("KuGou: keyword search HTTP " + response.code());
                        return;
                    }
                    body = response.body().string();
                }
                Candidate best = firstCandidate(body, target);
                if (best == null) {
                    callback.onError("KuGou: no lyrics candidate for keyword");
                    return;
                }
                downloadLrc(best.id, best.accessKey, callback);
            }
        });
    }

    // --- Step 1: song search (keyword -> duration-filtered hashes, closest first) -----------

    private interface HashesCallback {
        void onHashes(List<String> hashes);
        void onError(String error);
    }

    private void searchSongHashes(String keyword, int seconds, TrackMatchScorer.Target target,
                                  HashesCallback callback) {
        String url = "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0"
                + "&pagesize=8&showtype=0&keyword=" + encode(keyword);
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError("KuGou: song search failed: " + error.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("KuGou: song search HTTP " + response.code());
                        return;
                    }
                    body = response.body().string();
                }
                List<String> hashes = closestHashesFirst(body, seconds, target);
                if (hashes.isEmpty()) {
                    callback.onError("KuGou: no match in song search");
                    return;
                }
                callback.onHashes(hashes);
            }
        });
    }

    /** Song hashes within {@link #DURATION_TOLERANCE_SECONDS} of the track, closest first - a
     *  common title's first hit is as likely to be a cover or remix as the right recording. */
    static List<String> closestHashesFirst(String searchSongJson, int seconds) {
        return closestHashesFirst(searchSongJson, seconds, null);
    }

    /** As above, and with a target the hit's own title and singer must also be this track. */
    static List<String> closestHashesFirst(String searchSongJson, int seconds,
                                           TrackMatchScorer.Target target) {
        List<String> result = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(searchSongJson);
            if (!root.isJsonObject()) return result;
            JsonObject data = root.getAsJsonObject().getAsJsonObject("data");
            if (data == null) return result;
            JsonArray info = data.getAsJsonArray("info");
            if (info == null) return result;
            List<int[]> ranked = new ArrayList<>(); // {index, |delta|}
            List<String> hashes = new ArrayList<>();
            for (JsonElement el : info) {
                if (!el.isJsonObject()) continue;
                JsonObject song = el.getAsJsonObject();
                if (!song.has("hash")) continue;
                int duration = song.has("duration") ? song.get("duration").getAsInt() : -1;
                if (target != null && !TrackMatchScorer.score(target,
                        AppleTtmlMirrorAdapter.searchTitle(com.flowify.ettea.lyrics.Json.optString(song, "songname")),
                        TrackMatchScorer.splitArtists(com.flowify.ettea.lyrics.Json.optString(song, "singername")),
                        com.flowify.ettea.lyrics.Json.optString(song, "album_name"),
                        duration > 0 ? duration * 1000L : 0L).accepted()) {
                    continue;
                }
                if (seconds > 0 && duration >= 0
                        && Math.abs(duration - seconds) > DURATION_TOLERANCE_SECONDS) continue;
                ranked.add(new int[]{hashes.size(), seconds > 0 && duration >= 0 ? Math.abs(duration - seconds) : 0});
                hashes.add(song.get("hash").getAsString());
            }
            ranked.sort((a, b) -> Integer.compare(a[1], b[1]));
            for (int[] r : ranked) result.add(hashes.get(r[0]));
        } catch (RuntimeException malformed) {
            return new ArrayList<>();
        }
        return result;
    }

    // --- Step 2: lyrics search by hash (candidate id + accesskey) ---------------------------

    private interface CandidateCallback {
        void onCandidate(String id, String accessKey);
        void onError(String error);
    }

    private void searchLyricsByHash(String hash, CandidateCallback callback) {
        String url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + encode(hash);
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError("KuGou: lyrics search failed: " + error.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("KuGou: lyrics search HTTP " + response.code());
                        return;
                    }
                    body = response.body().string();
                }
                Candidate best = firstCandidate(body);
                if (best == null) {
                    callback.onError("KuGou: no lyrics candidate for hash");
                    return;
                }
                callback.onCandidate(best.id, best.accessKey);
            }
        });
    }

    static final class Candidate {
        final String id;
        final String accessKey;
        Candidate(String id, String accessKey) {
            this.id = id;
            this.accessKey = accessKey;
        }
    }

    static Candidate firstCandidate(String searchLyricsJson) {
        return firstCandidate(searchLyricsJson, null);
    }

    /** The first lyric candidate whose own song/singer (when it names them) is this track. */
    static Candidate firstCandidate(String searchLyricsJson, TrackMatchScorer.Target target) {
        try {
            JsonElement root = JsonParser.parseString(searchLyricsJson);
            if (!root.isJsonObject()) return null;
            JsonArray candidates = root.getAsJsonObject().getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return null;
            for (JsonElement element : candidates) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                if (!candidate.has("id") || !candidate.has("accesskey")) continue;
                String song = com.flowify.ettea.lyrics.Json.optString(candidate, "song");
                String singer = com.flowify.ettea.lyrics.Json.optString(candidate, "singer");
                if (target != null && !(song.isEmpty() && singer.isEmpty())
                        && !TrackMatchScorer.score(target, AppleTtmlMirrorAdapter.searchTitle(song),
                        TrackMatchScorer.splitArtists(singer), null,
                        (long) com.flowify.ettea.lyrics.Json.optDouble(candidate, 0d, "duration")).accepted()) {
                    continue;
                }
                return new Candidate(candidate.get("id").getAsString(), candidate.get("accesskey").getAsString());
            }
            return null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    // --- Step 3: download (base64 LRC, credits stripped) -------------------------------------

    private void downloadLrc(String id, String accessKey, LrcCallback callback) {
        String url = "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1"
                + "&id=" + encode(id) + "&accesskey=" + encode(accessKey);
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError("KuGou: download failed: " + error.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body;
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("KuGou: download HTTP " + response.code());
                        return;
                    }
                    body = response.body().string();
                }
                String lrc = decodeLrc(body);
                if (isBlank(lrc)) {
                    callback.onError("KuGou: empty lyrics content");
                    return;
                }
                callback.onSuccess(stripCredits(lrc));
            }
        });
    }

    static String decodeLrc(String downloadJson) {
        try {
            JsonElement root = JsonParser.parseString(downloadJson);
            if (!root.isJsonObject()) return null;
            JsonElement content = root.getAsJsonObject().get("content");
            if (content == null || content.isJsonNull()) return null;
            byte[] decoded = Base64.getDecoder().decode(content.getAsString());
            String text = new String(decoded, StandardCharsets.UTF_8);
            // A UTF-8 BOM, if present, decodes to this literal character rather than being
            // consumed - unlike StandardCharsets.UTF_8 paired with a BOM-aware reader.
            return text.length() > 0 && text.charAt(0) == '﻿' ? text.substring(1) : text;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String keyword(String title, String artist, String album) {
        StringBuilder query = new StringBuilder(stripParenthetical(title)).append(" - ")
                .append(stripParenthetical(artist));
        if (!isBlank(album)) query.append(' ').append(album);
        return query.toString();
    }

    private static String stripParenthetical(String value) {
        if (value == null) return "";
        String stripped = PARENTHETICAL.matcher(value).replaceAll("").trim();
        return stripped.isEmpty() ? value.trim() : stripped;
    }

    /**
     * KuGou's lyric files open and close with uncredited lines - songwriter, composer,
     * arranger - that carry a real timestamp and would otherwise be sung as the first and last
     * lines of the song. Cut the same way the source client does: from either end, up to the
     * first/last line matching "label: value", and only within the first and last 30 lines so a
     * legitimate lyric that happens to contain a colon deep in the song is left alone.
     */
    static String stripCredits(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (STAMPED.matcher(line.trim()).matches()) lines.add(line.trim());
        }
        if (lines.isEmpty()) return "";
        int headLimit = Math.min(30, lines.size() - 1);
        int headCut = 0;
        for (int i = headLimit; i >= 0; i--) {
            if (CREDIT.matcher(lines.get(i)).matches()) {
                headCut = i + 1;
                break;
            }
        }
        List<String> body = lines.subList(headCut, lines.size());
        int tailLimit = Math.min(30, body.size() - 1);
        int tailCut = 0;
        for (int i = 0; i <= tailLimit; i++) {
            if (CREDIT.matcher(body.get(body.size() - 1 - i)).matches()) {
                tailCut = i + 1;
                break;
            }
        }
        List<String> trimmed = body.subList(0, body.size() - tailCut);
        return String.join("\n", trimmed);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception impossible) {
            return value;
        }
    }

    private static final Pattern PARENTHETICAL = Pattern.compile("[(（].*?[)）]");
    private static final Pattern STAMPED = Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2,3}].*");
    private static final Pattern CREDIT = Pattern.compile(".+][^\\[]+[:：].+");
}
