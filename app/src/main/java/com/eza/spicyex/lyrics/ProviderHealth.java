package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;

import java.util.EnumMap;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * How each lyrics provider's server has been answering, from every response on the shared
 * client (an interceptor, so no provider needs to report anything itself).
 *
 * <p>Two kinds of failure mean different things:
 * <ul>
 * <li><b>Gone</b> - 401, 403, 404, 410: the server refuses or no longer has the endpoint. That
 *     does not fix itself by waiting, so after it keeps happening the settings suggest turning
 *     the provider off. A single 404 is not enough - several providers answer 404 for "no
 *     lyrics for this song" - so it takes that many different songs in a row, with no success
 *     in between, before anything is said (fewer for 401/403/410, which never mean that).</li>
 * <li><b>Busy</b> - 429 and 5xx: rate limits and outages. Those recover on their own; the
 *     settings only mention it, and never suggest turning anything off.</li>
 * </ul>
 * Any successful response from a provider clears its record.
 */
public final class ProviderHealth {

    /** What the settings should say about a provider, if anything. */
    public static final class Advice {
        public final int status;
        /** The server keeps refusing: suggest turning the provider off. */
        public final boolean suggestOff;
        /** Rate-limited or down for now: it will come back. */
        public final boolean busy;

        Advice(int status, boolean suggestOff, boolean busy) {
            this.status = status;
            this.suggestOff = suggestOff;
            this.busy = busy;
        }
    }

    /** Songs in a row answered with 401/403/410 before suggesting to turn a provider off. */
    static final int REFUSED_IN_A_ROW = 3;
    /** Songs in a row answered with 404 - which may just mean "not found" - before suggesting it. */
    static final int MISSING_IN_A_ROW = 8;
    /** A busy note stays this long after the last busy answer. */
    static final long BUSY_NOTE_MS = 10 * 60 * 1000L;

    private static final class Record {
        int lastStatus;
        int hardInARow;
        String lastRequestKey = "";
        long lastBusyAt;
        int busyStatus;
    }

    private static final Map<Source, Record> RECORDS = new EnumMap<>(Source.class);

    private ProviderHealth() {
    }

    /** Observes every response on the client it is added to. */
    public static Interceptor interceptor() {
        return chain -> {
            Request request = chain.request();
            Response response = chain.proceed(request);
            Source source = sourceFor(request.url().host());
            if (source != null) {
                record(source, response.code(), request.url().encodedPath() + "?" + request.url().encodedQuery(),
                        System.currentTimeMillis());
            }
            return response;
        };
    }

    /** Which provider a host belongs to; null for anything else (Google, artwork, Spotify). */
    static Source sourceFor(String host) {
        if (host == null) return null;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith("lrclib.net")) return Source.LRCLIB;
        if (h.endsWith("music.163.com")) return Source.NETEASE;
        if (h.endsWith("qq.com")) return Source.QQ;
        if (h.endsWith("musixmatch.com")) return Source.MUSIXMATCH;
        if (h.endsWith("spicylyrics.org")) return Source.SPICY;
        if (h.endsWith("devon-shoutz.workers.dev")) return Source.APPLE_MUSIC;
        return null;
    }

    static synchronized void record(Source source, int status, String requestKey, long now) {
        Record r = RECORDS.get(source);
        if (r == null) {
            r = new Record();
            RECORDS.put(source, r);
        }
        if (status >= 200 && status < 400) {
            r.hardInARow = 0;
            r.lastStatus = status;
            r.busyStatus = 0;
            r.lastBusyAt = 0L;
            return;
        }
        if (isBusy(status)) {
            r.busyStatus = status;
            r.lastBusyAt = now;
            return;
        }
        if (isGone(status)) {
            // Retries of the same request are one song, not several.
            if (!requestKey.equals(r.lastRequestKey) || r.lastStatus != status) r.hardInARow++;
            r.lastRequestKey = requestKey;
            r.lastStatus = status;
        }
    }

    /** Null when there is nothing to say. */
    public static synchronized Advice advice(Source source, long now) {
        Record r = RECORDS.get(source);
        if (r == null) return null;
        if (isGone(r.lastStatus)) {
            int needed = r.lastStatus == 404 ? MISSING_IN_A_ROW : REFUSED_IN_A_ROW;
            if (r.hardInARow >= needed) return new Advice(r.lastStatus, true, false);
        }
        if (r.busyStatus != 0 && now - r.lastBusyAt < BUSY_NOTE_MS) {
            return new Advice(r.busyStatus, false, true);
        }
        return null;
    }

    /** The provider was turned off, or back on: start its record over. */
    public static synchronized void forget(Source source) {
        RECORDS.remove(source);
    }

    static boolean isGone(int status) {
        return status == 401 || status == 403 || status == 404 || status == 410;
    }

    static boolean isBusy(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    static synchronized void resetForTest() {
        RECORDS.clear();
    }
}
