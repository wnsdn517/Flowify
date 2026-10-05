# Spicy EX Feature Guide

This is the feature list of this fork ([wnsdn517/spicy-ex](https://github.com/wnsdn517/spicy-ex)).
It tracks the upstream project ([amarinne/spicy-ex](https://github.com/amarinne/spicy-ex)) and adds
its own features on top; items marked **Fork** were built in this fork (several were later
merged upstream through pull requests).

Spicy EX is an Xposed/LSPosed module that replaces Spotify's basic lyric surface with a fullscreen
Spicy Lyrics / Apple Music-style lyric screen, a live now-playing lyric card, reading aids,
translation, and a lot of visual customization. Everything is configured from a settings panel
inside Spotify.

## APK Contents

One APK carries the lyric renderer, transliteration, translation, extra fonts, Spotify Connect and
the HyperGlow bridge. The Japanese/Chinese language models are a separate pack that is downloaded
from Settings when you want readings.

## Lyrics Experience

- Fullscreen synced lyrics inside Spotify, with line-, word- and syllable-timed lyrics when the
  source provides them.
- Spicy karaoke wash / spotlight animation.
- **Fork** — Apple Music-style motion with cascade, spring, lift, line slide and word bounce.
- Static fallback for unsynced lyrics; loading, empty, error and no-lyrics states; interlude
  indicators (dots or a note).
- Optional "stay in lyrics" across track changes.
- **Fork** — Tap-to-seek on lyric rows (off, single tap or double tap), manual sync offset of ±5000 ms, and a
  follow chip that jumps back to the current line after you scroll away (waveform icon, collapses
  to a round button).
- **Fork** — Playback sync follows Spotify's audio clock (heard position, speed-aware), so lyrics
  stay on the beat across seeks and pauses.
- **Fork** — Scroll with momentum between swipes, a smooth hand-over between fingers, and lines
  that dissolve into the background at the screen edge.
- **Fork** — Outro skip chip that seeks to the end of the song, so it also works on free accounts.
- **Fork** — Empty-state screen when no lyrics are available instead of a blank surface.

## Lyrics Sources

- A source catalog with a picker: Apple, Spotify native, AMLL, LRCLIB, QQ Music and
  NetEase. Choose per song which source's lyrics to show. **Fork** — the NetEase and QQ sources.

## Layout Editor — Fork

- **Fork** — Edit the lyric screen in place: tap artwork, track text, focus point, text, background,
  skip chip, follow chip, the top controls dock or the now-playing card and change its position,
  size and style live.
- Covers text size, font, weight, spacing, blur, glow, background style and quality, interlude
  icon, song-info header (including lyrics starting below it), and adaptive/landscape layout.

## Double-Tap Like — Fork

- Double-tap the lyrics to like the song; a burst plays where the finger landed. It never removes a
  like.
- 14 selectable effect styles (Aurora, Glow, Pulse, Watercolor, Radiant, Stardust, Gravity,
  Crystal, Liquid, Lens, Pearl, Prism, Bloom, Classic) and a mark of Like button, Heart or Star.
- A **Try it** action in Settings opens a trial bar with the gesture hint, lets you switch styles
  live and plays the effect without liking anything.
- Taps right after a like never seek, so a double tap does not jump a line.

## Share Cards — Fork

- Share a lyric line as an image card with several designs (Glass, Classic, Minimal, Polaroid,
  Poster, Vinyl, Ticket), a blur, lyrics or artist-photo backdrop, an optional Spotify code, save
  to gallery, and story sharing buttons.
- **Fork** — The card opens without waiting on the blur and thumbnail, and the next-line hint chip
  keeps one width.

## Picture-in-Picture — Fork

- Continue the lyrics in a PiP window from a dedicated button, or when leaving the lyrics screen
  (option).
- Song info header with artwork and title, landscape layout with an artwork column and larger
  text, lighter and faster to open, and the mini player opens Spotify's own page.

## Now-Playing Lyrics

- Live current lyric line in Spotify's now-playing view, with a placeholder on tracks without
  lyrics.
- Configurable tap shortcut to fullscreen lyrics.
- Main, transliteration, translation or combined secondary line; independent size, weight,
  animation, glow, fill, overflow and transition settings.

## Transliteration And Reading Aids

- Global toggle, optional per-word readings, and an in-lyrics chip that cycles modes.
- Japanese (furigana, romaji, both), Chinese (pinyin or jyutping, with tone marks/numbers),
  Korean (readable romanization or pronunciation with sound changes), Cyrillic (Russian or
  Ukrainian, optional hard/soft sign) and Greek (static table).

## Translation And AI

- Google unofficial translation backend, batched, with a target language and a cache.
- Optional AI features: separate Meaning and Sound lanes, Gemini, OpenAI and OpenAI-compatible
  providers, a Google preliminary result superseded by an accepted AI result, and reusable cached
  results. AI is opt-in and uses your own credentials.
- Translation text is sent to the service only while translation is enabled.

## Backgrounds

- **Fork** — Animated album-art ambient background, force-dark and extra-dark modes, fallback gradient for
  low-contrast art, and a render-quality option.

## Seamless Listening — Fork

- Mute Spotify's ads, or replace the ad break with soft generated music (Lofi, Cafe jazz, Bossa
  nova, Ambient, or random). Only Spotify's own audio is muted; the phone's media volume is not
  touched.
- An ad card on the lyrics screen during breaks.

## Spicy Connect — Fork

- A background web player appears as a Spotify Connect device you can cast to, with its ads
  stripped.
- Sign in from Settings, choose when Spotify hands playback to it (on app start, on first play,
  never), and optionally reconnect after network changes. The player stops with Spotify and exits
  when idle.

## HyperGlow Integration

- Publishes synchronized lyrics, timing, metadata, transliteration and translation to HyperGlow for
  HyperOS 3 lockscreen/AOD rendering. No Spotify token is sent.

## In-Spotify Settings

- Settings panel grouped by behavior, lyrics sources, reading, translation, layout
  editor, Apple animation style, now-playing, picture-in-picture, AI, Seamless Listening and
  diagnostics. Anything the Layout Editor can change is edited there, not duplicated in the panel.
- Cache actions for translation and lyric responses, a status panel, and English, Korean, Japanese, Russian
  and Simplified Chinese interface languages.
- User-triggered private problem reports with a readable preview; see the
  [data policy](DIAGNOSTIC_DATA_POLICY.md). Nothing is uploaded in the background.

## Installation

- Rooted: LSPosed, scoped to Spotify. Non-root: the LSPatch flow in the [readme](readme.md#install).
- APKs are published on this fork's [Releases](https://github.com/wnsdn517/spicy-ex/releases).

## Current Limits

- Spotify compatibility depends on the version; the fork is currently tested on **9.1.88**.
- Non-root login still needs the downgrade-login-upgrade flow.
- Artistic Japanese readings and Mandarin polyphones can still be wrong; Cyrillic is romanization,
  not pronunciation; Greek is a static table.
- Spicy Connect and ad handling rely on Spotify's web player and can break when it changes.
