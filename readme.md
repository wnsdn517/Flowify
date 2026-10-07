<div align="center">

# Spicy EX
Animated synced lyrics inside Spotify for Android (Spicy Lyrics for Android), as an Xposed/LSPosed/LSPatch module.<br>
Unofficial community project — not affiliated with Spotify or Spicy Lyrics.<br>
For the desktop version, check out [spicy-lyrics](https://github.com/amarinne/spicy-lyrics).
Xiaomi HyperOS 3 lockscreen/AOD integration: [HyperGlow](https://github.com/amarinne/hyperglow)

<img src="assets/demo.gif" width="320" alt="Spicy EX lyrics demo">

</div>
---

<div align="center">

### Join the community

Have an idea, found a bug, or just want to talk? Pick a channel.

<a href="https://t.me/flowify_music"><img src="https://img.shields.io/badge/Announcements-229ED9?style=flat&logo=telegram&logoColor=white" alt="Announcements"></a> <a href="https://t.me/flowify_discussion"><img src="https://img.shields.io/badge/Discussion-7B61FF?style=flat&logo=telegram&logoColor=white" alt="Discussion"></a> <a href="https://t.me/flowify_ci"><img src="https://img.shields.io/badge/CI_builds-2EA44F?style=flat&logo=telegram&logoColor=white" alt="CI_builds"></a> <a href="https://github.com/wnsdn517/spicy-ex/discussions"><img src="https://img.shields.io/badge/GitHub_Discussions-181818?style=flat&logo=github&logoColor=white" alt="GitHub_Discussions"></a>

<sub>Bot in Discussion: <code>/release</code> · <code>/beta</code> · <code>/ci</code></sub>

</div>

---

## What's new in v1.6.1 (first fork release)
- **Lyrics:** Apple Music-style motion, sync that follows Spotify's audio clock, momentum scrolling with edge fade, tap-to-seek, sync offset, follow chip, outro skip that works on free accounts, NetEase / QQ sources and a per-song source picker.
- **Layout Editor:** edit the lyrics screen live in place (artwork, text, focus point, background, chips, top controls, now-playing card).
- **Double-tap like:** 14 effect styles, heart/star/like-button mark, and a Try it trial bar.
- **Share cards:** 7 designs, artist-photo backdrop, multi-line picking, Instagram / Facebook Stories.
- **Picture-in-Picture:** floating lyrics with song info, landscape layout and several window shapes.
- **Seamless Listening:** mute ads or replace them with generated music, plus an ad card on the lyrics screen.
- **Spicy Connect:** a web player with Seamless Listening built in that appears as a Spotify Connect device.
- **Settings:** bottom sheet with search, About page, fork versioning; Korean and Japanese translations completed.

## Features
- Full-screen synced lyrics — Spicy karaoke wash, per-word animation, interludes.
- Live current line in the player, with a ♪ placeholder on no-lyric tracks.
- Transliteration: Japanese (furigana / romaji / both), Chinese (pinyin / jyutping), Korean / Cyrillic / Greek — optionally per-word.
- Google Translate.
- Optional AI translation and pronunciation/transliteration features.
- In-Spotify settings; works even when Spotify itself has no lyrics.
- [Read more](FEATURES_USER.md)

## Install
APK from [Releases](../../releases). 

Download language models in Settings to enable readings. Lyrics remain available without the pack.

**Rooted (LSPosed):** install, enable, scope to Spotify — you know the drill.

**Non-rooted (LSPatch):**
Spotify enforces Play Integrity during login. Bypass this using the **Downgrade-Login-Upgrade** method:

1. Download two Spotify APKs: an older version (e.g., `v8.9.18`) and the target version (`v9.1.28.2252`).
2. Patch both APKs with Spicy EX using [LSPatch](https://github.com/JingMatrix/LSPatch) (ensures matching signatures).
3. **Uninstall** current Spotify and **install the patched OLD version**.
4. **Log in** (Email/Password only, no Google/Facebook).
5. **Install the patched NEW version** over the old one as an update.

> [!NOTE]
> Tested on Spotify **v9.1.28.2252**.

> [!WARNING]
> May conflicts with ReVanced / modified Spotify or old Spotify Plus 

## Build
JDK 21 and an Android SDK are required. Gradle wrapper builds should be run with JDK 21; newer JDKs can fail during build-script compilation. The Android app still targets Java 11 bytecode unless that is changed intentionally.

```sh
# Build the debug APK and copy the stamped APK into artifacts/.
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug

# Run the primary JVM unit suite.
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest
```

The single APK includes transliteration, translation, language dictionaries, extra fonts, and Spotify Connect support.

Language models (kuromoji, CharSoup, JMdict) are not in the APK; they are
delivered as a separate pack that the app downloads from Settings. Create the
versioned archive with `:app:packageLanguageModelPack` (JMdict sources live in
`app/language-models/`), publish it over HTTPS, and point builds at it with
`-PLANGUAGE_MODEL_PACK_URL=...` and its SHA-256 in
`-PLANGUAGE_MODEL_PACK_SHA256=...`.

Docs-only changes do not require unit/device testing. Device behavior remains the final validation path for UI, hook, and Spotify-host integration changes.

## Credits
- [LeNerd46/SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus)
- [Spikerko/Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
- [surfbryce/beautiful-lyrics](https://github.com/surfbryce/beautiful-lyrics)
- [boidushya/better-lyrics](https://github.com/boidushya/better-lyrics) (+ kawarp background)

## License
[AGPL-3.0](LICENSE). See [NOTICE](NOTICE) for attribution.
