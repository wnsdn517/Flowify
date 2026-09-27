package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;
import org.junit.Before;
import org.junit.Test;

public class ProviderHealthTest {
    @Before
    public void reset() {
        ProviderHealth.resetForTest();
    }

    @Test
    public void refusedOnSeveralSongsSuggestsTurningOff() {
        for (int i = 0; i < ProviderHealth.REFUSED_IN_A_ROW; i++) {
            ProviderHealth.record(Source.MUSIXMATCH, 403, "/song" + i, 0L);
        }
        ProviderHealth.Advice advice = ProviderHealth.advice(Source.MUSIXMATCH, 0L);
        assertTrue(advice.suggestOff);
        assertEquals(403, advice.status);
    }

    @Test
    public void retriesOfOneSongCountOnce() {
        for (int i = 0; i < 10; i++) ProviderHealth.record(Source.MUSIXMATCH, 403, "/same", 0L);
        assertNull(ProviderHealth.advice(Source.MUSIXMATCH, 0L));
    }

    @Test
    public void notFoundNeedsManySongsAndAnySuccessClearsIt() {
        for (int i = 0; i < ProviderHealth.MISSING_IN_A_ROW - 1; i++) {
            ProviderHealth.record(Source.LRCLIB, 404, "/get" + i, 0L);
        }
        assertNull(ProviderHealth.advice(Source.LRCLIB, 0L));        // "no lyrics" is common
        ProviderHealth.record(Source.LRCLIB, 200, "/search", 0L);
        ProviderHealth.record(Source.LRCLIB, 404, "/get-x", 0L);
        assertNull(ProviderHealth.advice(Source.LRCLIB, 0L));
    }

    @Test
    public void busyNeverSuggestsOffAndFades() {
        for (int i = 0; i < 20; i++) ProviderHealth.record(Source.NETEASE, i % 2 == 0 ? 429 : 503, "/s" + i, 1000L);
        ProviderHealth.Advice advice = ProviderHealth.advice(Source.NETEASE, 2000L);
        assertTrue(advice.busy);
        assertFalse(advice.suggestOff);
        assertNull(ProviderHealth.advice(Source.NETEASE, 1000L + ProviderHealth.BUSY_NOTE_MS + 1));
    }

    @Test
    public void hostsMapToProviders() {
        assertEquals(Source.LRCLIB, ProviderHealth.sourceFor("lrclib.net"));
        assertEquals(Source.NETEASE, ProviderHealth.sourceFor("interface3.music.163.com"));
        assertEquals(Source.QQ, ProviderHealth.sourceFor("c.y.qq.com"));
        assertNull(ProviderHealth.sourceFor("translate.googleapis.com"));
    }
}
