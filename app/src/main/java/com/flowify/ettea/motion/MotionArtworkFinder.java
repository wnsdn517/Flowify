package com.flowify.ettea.motion;

import com.flowify.ettea.hooks.NativeRuntime;
import com.flowify.ettea.xposed.XpLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Finds the looping video that stands in for a track's cover (Apple motion artwork, Tidal video
 * cover, a community index), the way BitChord's CanvasRepository does. Apple first, then Tidal,
 * then the community list. Every source is someone else's public endpoint and will happily answer
 * with the wrong record, so each answer is re-checked against the track and any failure is simply
 * "no motion artwork": the still cover is always underneath.
 */
public final class MotionArtworkFinder {
    private static final String TAG = "[SpotifyPlusMotionArt]";

    /** A clip: {@code url} is tried first, {@code fallbackUrl} once if it will not play. */
    public static final class Clip {
        public final String url;
        public final String fallbackUrl;
        final String title;
        final String artist;
        final String album;

        Clip(String url, String fallbackUrl, String title, String artist, String album) {
            this.url = url;
            this.fallbackUrl = fallbackUrl;
            this.title = title;
            this.artist = artist;
            this.album = album;
        }

        /** Title and artists must match exactly; the album only when both sides know it. */
        boolean matches(String wantTitle, String wantArtist, String wantAlbum) {
            boolean titleOk = title == null || wantTitle.isEmpty()
                    || normalize(title).equals(normalize(wantTitle));
            List<String> wanted = splitArtists(wantArtist);
            List<String> ours = splitArtists(artist == null ? "" : artist);
            boolean artistOk = artist == null || wantArtist.isEmpty()
                    || (!wanted.isEmpty() && !ours.isEmpty() && ours.containsAll(wanted));
            boolean albumOk = album == null || album.isEmpty() || wantAlbum == null
                    || wantAlbum.isEmpty() || normalize(album).equals(normalize(wantAlbum));
            return titleOk && artistOk && albumOk;
        }
    }

    public interface Callback {
        /** Main thread. {@code clip} is null when nothing was found. */
        void onResult(Clip clip);
    }

    private interface Source {
        Clip search(String title, String artist, String album) throws Exception;
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int CACHE_SIZE = 64;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "spicy-motion-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Settled answers, misses included, so a revisit costs nothing. A miss reached without the
     *  album is provisional: the album is what makes catalogue searches land. */
    private static final class Cached {
        final Clip clip;
        final boolean withAlbum;

        Cached(Clip clip, boolean withAlbum) {
            this.clip = clip;
            this.withAlbum = withAlbum;
        }
    }

    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private MotionArtworkFinder() {
    }

    /** Looks up asynchronously; the callback runs on the main thread. Never throws. */
    public static void find(String trackUri, String title, String artist, String album, Callback callback) {
        final String cleanTitle = cleaned(title);
        final String cleanArtist = cleaned(artist);
        final String cleanAlbum = album == null || album.trim().isEmpty() ? null : album.trim();
        if (trackUri == null || trackUri.isEmpty() || cleanTitle.isEmpty() || cleanArtist.isEmpty()) {
            MAIN.post(() -> callback.onResult(null));
            return;
        }
        final String key = trackUri;
        final boolean withAlbum = cleanAlbum != null;
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && (cached.clip != null || cached.withAlbum || !withAlbum)) {
                MAIN.post(() -> callback.onResult(cached.clip));
                return;
            }
        }
        WORKER.execute(() -> {
            Clip found = null;
            synchronized (CACHE) {
                Cached cached = CACHE.get(key);
                if (cached != null && (cached.clip != null || cached.withAlbum || !withAlbum)) {
                    Clip clip = cached.clip;
                    MAIN.post(() -> callback.onResult(clip));
                    return;
                }
            }
            Source[] sources = {Apple::search, Tidal::search, Community::search};
            for (Source source : sources) {
                try {
                    Clip clip = source.search(cleanTitle, cleanArtist, cleanAlbum);
                    if (clip != null && clip.matches(cleanTitle, cleanArtist, cleanAlbum)) {
                        found = clip;
                        break;
                    }
                } catch (Throwable t) {
                    XpLog.log(TAG + " source failed: " + t.getClass().getSimpleName());
                }
            }
            final Clip result = found;
            XpLog.log(TAG + " '" + cleanTitle + "' by '" + cleanArtist + "' -> "
                    + (result == null ? "no motion artwork" : result.url));
            synchronized (CACHE) {
                CACHE.put(key, new Cached(result, withAlbum));
            }
            MAIN.post(() -> callback.onResult(result));
        });
    }

    // ---- Matching helpers ----------------------------------------------------------------------

    private static final Pattern NOISE = Pattern.compile(
            "\\((?:from|official|lyrical|video|audio)[^)]*\\)|\\[[^]]*]|"
                    + "\\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SEPARATORS = Pattern.compile(
            "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Packaging catalogue services never see ("| Official Video", bracketed tags) comes off. */
    static String cleaned(String raw) {
        if (raw == null) return "";
        String s = NOISE.matcher(raw).replaceAll(" ");
        int bar = s.indexOf(" | ");
        if (bar >= 0) s = s.substring(0, bar);
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? raw.trim() : s;
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static List<String> splitArtists(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : ARTIST_SEPARATORS.split(raw)) {
            String n = normalize(part);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    static String get(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> h : headers.entrySet()) builder.header(h.getKey(), h.getValue());
        try (Response response = NativeRuntime.HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return response.body().string();
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String country() {
        String c = Locale.getDefault().getCountry();
        return c != null && c.length() == 2 ? c.toUpperCase(Locale.ROOT) : "US";
    }

    // ---- Tidal: a track search returns the album, whose videoCover id expands to a CDN URL ------

    private static final class Tidal {
        private static final String SEARCH = "https://api.tidal.com/v1/search";
        /** The token Tidal's public embed player ships; read-only, no account. */
        private static final String EMBED_TOKEN = "vNVdglQOjFJJGG2U";

        static Clip search(String title, String artist, String album) throws Exception {
            String query = album == null ? artist + " " + title : album + " " + artist + " " + title;
            HttpUrl url = HttpUrl.get(SEARCH).newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("limit", "10")
                    .addQueryParameter("types", "TRACKS")
                    .addQueryParameter("countryCode", country())
                    .build();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Tidal-Token", EMBED_TOKEN);
            headers.put("User-Agent", UA);
            String body = get(url.toString(), headers);
            if (body == null) return null;
            JSONObject tracks = new JSONObject(body).optJSONObject("tracks");
            JSONArray items = tracks == null ? null : tracks.optJSONArray("items");
            if (items == null) return null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject track = items.optJSONObject(i);
                if (track == null) continue;
                String trackTitle = optString(track, "title");
                if (trackTitle == null) continue;
                List<String> artists = new ArrayList<>();
                JSONArray credited = track.optJSONArray("artists");
                for (int a = 0; credited != null && a < credited.length(); a++) {
                    String name = optString(credited.optJSONObject(a), "name");
                    if (name != null) artists.add(name);
                }
                if (!isMatch(trackTitle, artists, title, artist)) continue;
                JSONObject albumObj = track.optJSONObject("album");
                String cover = optString(albumObj, "videoCover");
                if (cover == null) continue;
                String videoUrl = coverUrl(cover);
                if (videoUrl == null) continue;
                return new Clip(videoUrl, null, trackTitle, join(artists), optString(albumObj, "title"));
            }
            return null;
        }

        private static boolean isMatch(String gotName, List<String> gotArtists,
                                       String wantName, String wantArtist) {
            if (!normalize(gotName).equals(normalize(wantName))) return false;
            List<String> wanted = splitArtists(wantArtist);
            List<String> credited = new ArrayList<>();
            for (String a : gotArtists) {
                String n = normalize(a);
                if (!n.isEmpty()) credited.add(n);
            }
            return !wanted.isEmpty() && !credited.isEmpty() && credited.containsAll(wanted);
        }

        /** Five dash-separated segments spell out the CDN path; anything else is unknown. */
        private static String coverUrl(String id) {
            String[] parts = id.split("-");
            if (parts.length != 5) return null;
            return "https://resources.tidal.com/videos/" + String.join("/", parts) + "/1280x1280.mp4";
        }

        private static String join(List<String> artists) {
            if (artists.isEmpty()) return null;
            return String.join(", ", artists);
        }
    }

    // ---- Community: one JSON manifest of song + artist -> looping video --------------------------

    private static final class Community {
        private static final String MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json";
        private static final long TTL_MS = 30L * 60 * 1000;

        private static final class Item {
            final String song;
            final String artist;
            final String album;
            final String url;

            Item(String song, String artist, String album, String url) {
                this.song = song;
                this.artist = artist;
                this.album = album;
                this.url = url;
            }
        }

        private static List<Item> items = new ArrayList<>();
        private static long fetchedAtMs;

        static Clip search(String title, String artist, String album) {
            List<Item> index = manifest();
            if (index.isEmpty()) return null;
            String wantTitle = normalize(title);
            String wantArtist = normalize(artist);
            String wantAlbum = album == null ? null : normalize(album);
            // Contributors write titles as they please, so this side matches on containment; the
            // album check keeps it honest when one is known.
            for (Item item : index) {
                String song = normalize(item.song);
                String credited = normalize(item.artist);
                String listed = normalize(item.album);
                boolean titleOk = !song.isEmpty() && (wantTitle.contains(song) || song.contains(wantTitle));
                boolean artistOk = !credited.isEmpty()
                        && (wantArtist.contains(credited) || credited.contains(wantArtist));
                boolean albumOk = listed.isEmpty() || wantAlbum == null || wantAlbum.isEmpty()
                        || listed.equals(wantAlbum);
                if (titleOk && artistOk && albumOk) {
                    return new Clip(item.url, null, item.song, item.artist,
                            item.album.isEmpty() ? null : item.album);
                }
            }
            return null;
        }

        private static synchronized List<Item> manifest() {
            long now = System.currentTimeMillis();
            if (!items.isEmpty() && now - fetchedAtMs < TTL_MS) return items;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", UA);
            String body = get(MANIFEST, headers);
            fetchedAtMs = now;
            if (body == null) return items; // keep what we have rather than lose it for 30 minutes
            try {
                JSONArray array = new JSONObject(body).optJSONArray("items");
                List<Item> parsed = new ArrayList<>();
                for (int i = 0; array != null && i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    String song = optString(o, "song");
                    String artist = optString(o, "artist");
                    String url = optString(o, "url");
                    if (song == null || artist == null || url == null) continue;
                    String album = optString(o, "album");
                    parsed.add(new Item(song, artist, album == null ? "" : album, url));
                }
                if (!parsed.isEmpty()) items = parsed;
            } catch (Exception ignored) {
            }
            return items;
        }
    }

    // ---- Apple Music: catalog search with editorialVideo, scored rather than trusted -------------

    private static final class Apple {
        private static final String AMP = "https://amp-api.music.apple.com/v1/catalog";
        private static final String WEB_PLAYER = "https://music.apple.com/us/browse";
        private static final int MIN_SCORE = 12;
        private static final long TOKEN_RETRY_MS = 30L * 60 * 1000;
        private static final String[] EDITION_WORDS = {
                "deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus"};
        private static final String[] COMPILATION_MARKERS = {
                "playlist", "set list", "essentials", "dj mix", "mixed",
                "apple music", "today's hits", "session"};

        private static String cachedToken;
        private static long tokenExpiresAtMs;
        private static long retryTokenAfterMs;
        private static final Set<String> REJECTED = new HashSet<>();

        private static String storefront() {
            String c = Locale.getDefault().getCountry();
            return c != null && c.length() == 2 ? c.toLowerCase(Locale.ROOT) : "us";
        }

        static Clip search(String title, String artist, String album) throws Exception {
            String bearer = token();
            if (bearer == null) return null;
            StringBuilder term = new StringBuilder();
            if (!containsIgnoreCase(title, artist)) term.append(artist).append(' ');
            term.append(title);
            if (album != null && !containsIgnoreCase(title, album)) term.append(' ').append(album);
            HttpUrl url = HttpUrl.get(AMP + "/" + storefront() + "/search").newBuilder()
                    .addQueryParameter("term", term.toString())
                    .addQueryParameter("types", "songs")
                    .addQueryParameter("limit", "10")
                    .addQueryParameter("extend", "editorialVideo")
                    .addQueryParameter("include", "albums")
                    .build();
            String body = authedGet(url.toString(), bearer);
            if (body == null) return null;
            JSONObject songs = new JSONObject(body).optJSONObject("results");
            songs = songs == null ? null : songs.optJSONObject("songs");
            JSONArray data = songs == null ? null : songs.optJSONArray("data");
            if (data == null) return null;

            List<Object[]> ranked = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject song = data.optJSONObject(i);
                Integer score = song == null ? null : score(song, title, artist, album);
                if (score != null) ranked.add(new Object[]{score, song});
            }
            ranked.sort((a, b) -> Integer.compare((Integer) b[0], (Integer) a[0]));

            for (Object[] entry : ranked) {
                if ((Integer) entry[0] < MIN_SCORE) break;
                JSONObject song = (JSONObject) entry[1];
                JSONObject attributes = song.optJSONObject("attributes");
                if (attributes == null) continue;
                String songName = optString(attributes, "name");
                String songArtist = optString(attributes, "artistName");
                String albumName = optString(attributes, "albumName");
                String[] inline = motionUrls(attributes.optJSONObject("editorialVideo"));
                if (inline != null) return new Clip(inline[0], inline[1], songName, songArtist, albumName);
                String albumId = albumId(song);
                if (albumId == null) continue;
                Clip fromAlbum = fetchAlbum(albumId, bearer, songName, songArtist);
                if (fromAlbum != null) return fromAlbum;
            }
            return null;
        }

        /** How well a hit lines up with what is playing, or null to reject it outright. */
        private static Integer score(JSONObject song, String title, String artist, String album) {
            JSONObject attributes = song.optJSONObject("attributes");
            if (attributes == null) return null;
            String hitName = attributes.optString("name", "");
            String hitArtist = attributes.optString("artistName", "");
            String hitAlbum = attributes.optString("albumName", "");
            if (isCompilation(hitName) || isCompilation(hitAlbum)) return null;
            List<String> wanted = splitArtists(artist);
            List<String> credited = splitArtists(hitArtist);
            if (wanted.isEmpty() || credited.isEmpty() || !credited.containsAll(wanted)) return null;

            int score = 10;
            String wantTitle = normalize(title);
            String hitTitle = normalize(hitName);
            if (hitTitle.equals(wantTitle)) score += 15;
            else if (hitTitle.contains(wantTitle) || wantTitle.contains(hitTitle)) score += 7;
            else score -= 10;

            if (album != null && !hitAlbum.isEmpty()) {
                String wantAlbum = normalize(album);
                String gotAlbum = normalize(hitAlbum);
                if (gotAlbum.equals(wantAlbum)) score += 20;
                else if (gotAlbum.contains(wantAlbum) || wantAlbum.contains(gotAlbum)) score += 10;
            }
            // "(Deluxe)" on one side only is a different master, often a different clip.
            for (String word : EDITION_WORDS) {
                boolean inWanted = containsIgnoreCase(title, word);
                boolean inHit = containsIgnoreCase(hitName, word);
                if (inWanted && inHit) score += 5;
                else if (inHit) score -= 3;
            }
            return score;
        }

        private static boolean isCompilation(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String marker : COMPILATION_MARKERS) if (lower.contains(marker)) return true;
            return false;
        }

        private static String albumId(JSONObject song) {
            JSONObject rel = song.optJSONObject("relationships");
            JSONObject albums = rel == null ? null : rel.optJSONObject("albums");
            JSONArray data = albums == null ? null : albums.optJSONArray("data");
            if (data != null && data.length() > 0) {
                String id = optString(data.optJSONObject(0), "id");
                if (id != null) return id.startsWith("pl.") ? null : id;
            }
            // The web URL always ends in the album id: .../album/<slug>/<id>?i=<song id>
            String url = optString(song.optJSONObject("attributes"), "url");
            if (url == null) return null;
            int at = url.indexOf("/album/");
            if (at < 0) return null;
            String tail = url.substring(at + 7);
            int q = tail.indexOf('?');
            if (q >= 0) tail = tail.substring(0, q);
            tail = tail.substring(tail.lastIndexOf('/') + 1);
            return !tail.isEmpty() && tail.matches("\\d+") ? tail : null;
        }

        private static Clip fetchAlbum(String albumId, String bearer, String songTitle, String songArtist)
                throws Exception {
            HttpUrl url = HttpUrl.get(AMP + "/" + storefront() + "/albums/" + albumId).newBuilder()
                    .addQueryParameter("extend", "editorialVideo")
                    .build();
            String body = authedGet(url.toString(), bearer);
            if (body == null) return null;
            JSONArray data = new JSONObject(body).optJSONArray("data");
            JSONObject album = data == null ? null : data.optJSONObject(0);
            JSONObject attributes = album == null ? null : album.optJSONObject("attributes");
            if (attributes == null) return null;
            String albumName = attributes.optString("name", "");
            if (isCompilation(albumName)) return null;
            String[] urls = motionUrls(attributes.optJSONObject("editorialVideo"));
            if (urls == null) return null;
            String artist = songArtist != null ? songArtist : optString(attributes, "artistName");
            return new Clip(urls[0], urls[1], songTitle, artist, albumName);
        }

        /** The square rendition first (fills a square sleeve uncropped); another as the retry. */
        private static String[] motionUrls(JSONObject video) {
            if (video == null) return null;
            String square = link(video, "motionDetailSquare");
            if (square == null) square = link(video, "motionSquareVideo1x1");
            String raw = link(video, "motionDetailRaw");
            String tall = link(video, "motionDetailTall");
            if (tall == null) tall = link(video, "motionTallVideo3x4");
            String primary = square != null ? square : raw != null ? raw : tall;
            if (primary == null) return null;
            String alternate = null;
            for (String candidate : new String[]{square, raw, tall}) {
                if (candidate != null && !candidate.equals(primary)) {
                    alternate = candidate;
                    break;
                }
            }
            return new String[]{primary, alternate};
        }

        private static String link(JSONObject video, String key) {
            JSONObject asset = video.optJSONObject(key);
            if (asset == null) return null;
            for (String field : new String[]{"video", "videoUrl", "hlsUrl", "url"}) {
                String v = optString(asset, field);
                if (v != null) return v;
            }
            return null;
        }

        private static boolean containsIgnoreCase(String haystack, String needle) {
            return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        }

        // -- Token: the anonymous bearer the web player mints, scraped from its JS bundle --

        private static synchronized String token() {
            long now = System.currentTimeMillis();
            if (cachedToken != null && now < tokenExpiresAtMs - 60_000) return cachedToken;
            if (now < retryTokenAfterMs) return null;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", UA);
            String html = get(WEB_PLAYER, headers);
            List<String> scripts = new ArrayList<>();
            if (html != null) {
                Matcher m = Pattern.compile("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js").matcher(html);
                while (m.find()) if (!scripts.contains(m.group())) scripts.add(m.group());
            }
            Pattern jwtPattern = Pattern.compile("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
            for (String path : scripts) {
                String script = get("https://music.apple.com" + path, headers);
                if (script == null) continue;
                String best = null;
                long bestExpiry = 0L;
                boolean bestIsWebPlayer = false;
                Matcher m = jwtPattern.matcher(script);
                Set<String> seen = new HashSet<>();
                while (m.find()) {
                    String jwt = m.group();
                    if (!seen.add(jwt) || REJECTED.contains(jwt)) continue;
                    long expiry = expiry(jwt);
                    if (expiry <= now) continue;
                    boolean web = isWebPlayerToken(jwt);
                    if (best == null || (web && !bestIsWebPlayer)) {
                        best = jwt;
                        bestExpiry = expiry;
                        bestIsWebPlayer = web;
                    }
                }
                if (best != null) {
                    cachedToken = best;
                    tokenExpiresAtMs = bestExpiry;
                    return best;
                }
            }
            retryTokenAfterMs = now + TOKEN_RETRY_MS;
            return null;
        }

        /** A 401 means the picked token is not the one the endpoint honours: strike it off. */
        private static String authedGet(String url, String bearer) {
            Request request = new Request.Builder().url(url)
                    .header("Authorization", "Bearer " + bearer)
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .header("User-Agent", UA)
                    .build();
            try (Response response = NativeRuntime.HTTP.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) return response.body().string();
                if (response.code() == 401) {
                    synchronized (Apple.class) {
                        REJECTED.add(bearer);
                        if (bearer.equals(cachedToken)) {
                            cachedToken = null;
                            tokenExpiresAtMs = 0L;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private static boolean isWebPlayerToken(String jwt) {
            try {
                String[] parts = jwt.split("\\.");
                String header = new String(Base64.getUrlDecoder().decode(parts[0]), "UTF-8");
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), "UTF-8");
                return header.contains("WebPlayKid") || payload.contains("AMPWebPlay");
            } catch (Exception e) {
                return false;
            }
        }

        private static long expiry(String jwt) {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), "UTF-8");
                Matcher m = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(payload);
                return m.find() ? Long.parseLong(m.group(1)) * 1000L : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }
    }
}
