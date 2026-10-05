package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MusixmatchAdapterTest {
    @Test
    public void signsLikeTheWebClient() {
        // Reference computed independently (Python hmac/sha256/base64/quote_plus).
        String url = "https://apic.musixmatch.com/ws/1.1/token.get?app_id=mobile-app-v1.0&format=json";
        assertEquals(url + "&signature=McQxQheicVLLDnAxB8HFqkifuf6pl32OaFNeg3HPlDs%3D&signature_protocol=sha256",
                MusixmatchAdapter.signFor(url, "f09016176ba43a1cfd1031fbd6b3d26c", "20261004"));
    }

    @Test
    public void secretIsReversedBase64() {
        assertEquals("hello-key", MusixmatchAdapter.decodeSecret("5V2at8GbsVGa"));
    }

    @Test
    public void scoringPrefersTheRightRecording() {
        double exact = MusixmatchAdapter.score("Man I Need", "Olivia Dean", 184, "Man I Need", "Olivia Dean", 184);
        double cover = MusixmatchAdapter.score("Man I Need", "Olivia Eldredge feat. Sophia Dean", 194,
                "Man I Need", "Olivia Dean", 184);
        double live = MusixmatchAdapter.score("Man I Need (Live) - Spotify Live Room", "Olivia Dean", 184,
                "Man I Need", "Olivia Dean", 184);
        assertTrue(exact > live);
        assertTrue(exact > cover);
        assertTrue(MusixmatchAdapter.score("Other Song", "Someone", 300, "Man I Need", "Olivia Dean", 184) < 40d);
    }

    @Test
    public void zeroTokensAreNotTokens() {
        assertFalse(MusixmatchAdapter.usable("0000000000"));
        assertFalse(MusixmatchAdapter.usable(null));
        assertTrue(MusixmatchAdapter.usable("261004d384bae36d"));
    }
}
