package com.flowify.ettea.hooks;

/** Public read of Spotify's captured Web API token, for callers outside this package. */
public final class SpotifyWebApiToken {
    private SpotifyWebApiToken() {
    }

    /** The current usable token, or {@code null} when none has been captured. */
    public static String current() {
        SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
        return auth == null ? null : auth.token();
    }
}
