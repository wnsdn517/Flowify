# Flowify FAQ

This FAQ is for this fork ([wnsdn517/Flowify](https://github.com/wnsdn517/Flowify)). See the
[feature guide](FEATURES_USER.md) for what it adds over upstream.

### It does not work with my Spotify version

Hooks depend on the Spotify version. This fork is currently tested on **9.1.88** from Google Play.
Older, beta, ReVanced or otherwise modified builds may not work. After a Spotify update, check for a
new fork release before reporting a problem.

### What is included?

- One APK with the lyric renderer, transliteration, translation, extra fonts, Spotify Connect,
  ad handling, picture-in-picture, share cards and the HyperGlow bridge.
- Language models (Japanese/Chinese dictionaries, language detection) are downloaded on demand from
  Settings instead of being bundled. Lyrics work without them; readings need them.

### Which LSPosed scope?

Spotify only (`com.spotify.music`).

After installing or updating the module, force-stop Spotify and reopen it.

### Flowify settings are missing

Check that:

- the module is enabled,
- Spotify is selected in the LSPosed scope,
- Spotify was fully restarted,
- your Spotify build is one the current hooks support.

Still broken? Open an issue with your Spotify version and the module version.

### Where do I change the lyric screen's look?

In the **Layout Editor**: open the lyrics screen and edit the artwork, track text, focus point,
background, chips, top controls or now-playing card in place. Those settings are deliberately not
repeated in the Settings panel, which holds everything else (sources, readings, translation,
double-tap like, picture-in-picture, AI, Seamless Listening, diagnostics).

### How do I choose where lyrics come from?

Settings → **Lyrics Sources**. Pick the source (Apple, Spotify native, AMLL, LRCLIB, QQ Music,
NetEase). Availability and timing quality vary by track
and source.

### Double-tap like

Double-tap the lyrics to like the song. Choose the effect and mark in Settings and use **Try it** to
preview without liking anything. It never removes a like. If you want double-tap to seek instead,
turn double-tap like off — the two cannot share the gesture.

### Ads and Spicy Connect

- **Ads**: Settings → Seamless Listening → mute, or play generated music during the break.
- **Spicy Connect**: a background web player that shows up as a Connect device. Sign in from Settings.
  It needs Spotify's web player to keep working, so it can break when Spotify changes it.
- Both are fork-only features and are not part of upstream.

### Lyrics are missing, wrong, or delayed

Try another source in Settings → Lyrics Sources. Playback sync follows Spotify's audio clock; if
lyrics are consistently early or late on your device, adjust the sync offset (±5000 ms).

### Does LSPatch work?

Possible, but less reliable. A patched Spotify may fail Play Integrity at login; follow the
[downgrade-login-upgrade method](readme.md#install). The [non-root plan](docs/NON_ROOT_PLAN.md)
describes how this is meant to get easier.

### HyperGlow

Optional. It publishes lyrics to HyperGlow for HyperOS 3 lockscreen/AOD rendering.

### How do I update?

Install the new APK from this fork's [Releases](https://github.com/wnsdn517/Flowify/releases) over
the old one, then restart Spotify. With LSPatch, enable **Override version code**.

### Is it affiliated with upstream or Spotify?

No. It is an unofficial community fork of [amarinne/spicy-ex](https://github.com/amarinne/spicy-ex)
and is not affiliated with Spotify or Spicy Lyrics.
