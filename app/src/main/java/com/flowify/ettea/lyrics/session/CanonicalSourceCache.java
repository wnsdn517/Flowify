package com.flowify.ettea.lyrics.session;

import android.content.Context;

import com.flowify.ettea.Diagnostics;

/**
 * The public release's one-winner canonical store, now read-only. The catalog imports a track's
 * record from here the first time it loads that track; nothing writes here any more. Deleting a
 * track's saved lyrics also removes its record so the import cannot bring it back.
 */
public final class CanonicalSourceCache {
    private static final String PREFS = "SpotifyPlusCanonicalSourceCache";

    private CanonicalSourceCache() {
    }

    public static CanonicalSourceCodec.Record load(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return null;
        try {
            String raw = com.flowify.ettea.lyrics.cache.SpicyCacheStore.get(context, PREFS, entryKey(trackUri));
            return CanonicalSourceCodec.decode(raw);
        } catch (Throwable t) {
            Diagnostics.warn("CanonicalSourceCache", "load", t);
            return null;
        }
    }

    public static void clear(Context context) {
        com.flowify.ettea.lyrics.cache.SpicyCacheStore.clear(context, PREFS);
    }

    /** Drops only one track's legacy record. */
    public static void remove(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return;
        com.flowify.ettea.lyrics.cache.SpicyCacheStore.remove(context, PREFS, entryKey(trackUri));
    }

    /** Combined logical-payload usage of the legacy store, for the settings panel. */
    public static long usageBytes(Context context) {
        return com.flowify.ettea.lyrics.cache.SpicyCacheStore.usageBytes(context, PREFS);
    }

    public static int entryCount(Context context) {
        return com.flowify.ettea.lyrics.cache.SpicyCacheStore.entryCount(context, PREFS);
    }

    /** A saved song, for the stored-lyrics browser (backed by the lyrics catalog). */
    public static final class Entry {
        public final String key;
        public final String trackUri;
        public final String trackId;
        public final String title;
        public final String artist;
        /** Where the lyrics came from ("LRCLIB", "Apple Music"...). */
        public final String source;
        public final String firstLine;
        public final long bytes;
        public final long savedAtMs;

        Entry(String key, String trackUri, String trackId, String title, String artist,
              String source, String firstLine, long bytes, long savedAtMs) {
            this.key = key;
            this.trackUri = trackUri;
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.firstLine = firstLine;
            this.bytes = bytes;
            this.savedAtMs = savedAtMs;
        }
    }

    /** Every song with stored lyrics, newest first. */
    public static java.util.List<Entry> entries(Context context) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        for (com.flowify.ettea.lyrics.catalog.CatalogStore.StoredTrack track
                : com.flowify.ettea.lyrics.catalog.CatalogStore.storedTracks(context)) {
            String source = track.source == null ? ""
                    : com.flowify.ettea.lyrics.catalog.CatalogPickerModel.displaySource(track.source);
            out.add(new Entry(track.trackId, track.uri, track.trackId, track.title, track.artists,
                    source, "", track.bytes, track.savedAtMs));
        }
        return out;
    }

    /** Deletes one song's stored lyrics (every candidate and its legacy record). */
    public static void remove(Context context, Entry entry) {
        if (context == null || entry == null) return;
        com.flowify.ettea.lyrics.catalog.CatalogStore.deleteTrack(context, entry.trackId);
        remove(context, entry.trackUri);
    }

    private static String entryKey(String trackUri) {
        return "canon-v" + CanonicalSourceCodec.SCHEMA_VERSION + "|" + Digests.sha256(trackUri);
    }
}
