# Flowify 훅 감사 보고서 (APK 분석 세션 인계용)

작성 기준
- 저장소: `wnsdn517/Flowify`
- 브랜치
  - `ccr-fe1821eb-8jc004` (PR #17, 광고/Connect 제거 후 = **현재 빌드**)
  - `archive/connect-ads` (제거 전 원본)
- 비교 대상 원본(upstream): `amarinne/spicy-ex` main (`06e8dc9`)
- 테스트 기준 Spotify 버전: 9.1.88 (FEATURES_USER.md 기준)
- 모든 경로는 `app/src/main/java/com/flowify/ettea/` 기준. 단, Java 패키지명은 여전히 `com.eza.spicyex.*`
  (폴더만 rename 됨).
- 라인 번호는 PR #17 브랜치 기준.

---

## 0. 문제 상황 요약 (다음 세션이 먼저 알아야 할 것)

- 증상: 이 포크(Flowify)를 쓰면 Spotify 앱에서 **"앱에서 문제가 발생했다"** 류의 메시지와 함께 서비스 사용이 막힘.
  원본(Spicy EX)은 같은 환경에서 막히지 않음.
- 새 계정으로 로그인해도 **1곡 재생 후 더 이상 재생이 안 됨**. 광고도 나오지 않음.
- 지금까지의 판단(검증 안 됨, 코드만 비교한 결과):
  - 훅 엔진(`XpHooks`), `module.prop`, 예외 모드, DexKit 사용법은 원본과 **동일**. 따라서 "Xposed가 깔려 있다"는
    일반적인 흔적만으로 막는 거라면 원본도 막혀야 함 → **포크에서만 추가된 기능**이 원인일 가능성이 높음.
  - 새 계정에서도 재현되므로 **계정이 아니라 기기/설치본 단위 제한**일 가능성도 있음(추정).
  - 1차 조치로 광고·Connect 기능 전체를 PR #17에서 제거함(§3).
- 아직 안 해본 결정적 확인: **LSPosed에서 모듈 스코프를 끈 상태**로 Spotify를 강제 종료 후 재생해 보기.
  그래도 막히면 모듈이 아니라 기기/설치본 쪽 제한.

### 다음 세션에서 APK로 확인해야 할 것
1. **Spotify APK**를 분석하는 경우
   - "문제가 발생했다" 에러 문자열(리소스)과 그걸 띄우는 코드 경로를 찾아, 무엇을 조건으로 띄우는지.
     (재생 거부 / 무결성 / 훅 감지 / 서버 응답 코드 중 어느 것인지)
   - Xposed/LSPosed/훅 감지 코드 존재 여부. 검색 키워드:
     `xposed`, `lsposed`, `de.robv`, `XposedBridge`, `/proc/self/maps`, `libdexkit`, `getStackTrace`,
     `Thread.getAllStackTraces`, `ArtMethod`, `frida`, `magisk`, `su`, `PlayIntegrity`/`IntegrityManager`,
     `SafetyNet`, `getInstallerPackageName`, `signatures`.
   - 크래시/텔레메트리 업로드 시 스택 트레이스를 서버로 보내는지 (훅 프레임 `com.eza.spicyex.*`가 섞여 나감).
   - §2의 각 훅 대상 클래스/메서드가 그 버전에 실제로 존재하는지, DexKit 문자열 조건이 엉뚱한 메서드에
     매칭되지 않는지.
2. **모듈(Flowify) APK**를 분석하는 경우
   - 실제로 설치된 빌드에 §3의 제거 대상 클래스가 남아 있는지 (`AdMuteController`, `SpotifyConnectHook`,
     `player.WebPlayerService` 등). 남아 있으면 PR #17 이전 빌드임.
   - `AndroidManifest.xml`에 `WebPlayerService`, `PlayerWarmReceiver`, `BootWarmReceiver`, `SYSTEM_ALERT_WINDOW`
     등이 남아 있는지.

---

## 1. 훅 인프라 (원본과 동일)

| 항목 | 위치 | 내용 |
|---|---|---|
| 엔트리 | `xposed/SpicyEXModule.java` | LibXposed API 102 `XposedModule`. `onPackageReady`에서 `com.spotify.music`만 처리 |
| 모듈 메타 | `src/main/resources/META-INF/xposed/` | `java_init.list`=`com.eza.spicyex.xposed.SpicyEXModule`, `module.prop`: `minApiVersion=102`, `targetApiVersion=102`, `staticScope=true`, `exceptionMode=protective`, `scope.list`=`com.spotify.music` |
| 훅 어댑터 | `xposed/XpHooks.java` | 모든 훅이 이 클래스를 거침. `api.hook(executable).setId(id).setExceptionMode(PROTECTIVE).intercept(hooker)` |
| 심볼 탐색 | `xposed/SpotifySymbolResolver.java` | DexKit(`System.loadLibrary("dexkit")`, `DexKitBridge.create(sourceDir)`)로 난독화된 Spotify 클래스/메서드 탐색 |
| 심볼 캐시 | `xposed/ResolvedSymbolCache.java` | `getNoBackupFilesDir()/spicy-symbols-<process>.properties`. 키 = resolver revision + 패키지 + versionCode + lastUpdateTime + APK 경로/크기/mtime |

`XpHooks.hook` 동작
- before 콜백 → (setResult 안 했으면) `chain.proceed(args)` → after 콜백 → 결과 반환.
- 원본 메서드가 throw하면 after에서 `setResult`로 삼킬 수 있음.
- PROTECTIVE 모드
  - 콜백이 throw하면 로그만 남고 훅이 없는 것처럼 진행.
  - proceed 이후에 throw하면 이미 나온 결과를 반환.
  - 단, before에서 이미 바꾼 인자나 `setResult`는 그대로 적용됨.
- `hookAllMethods` / `hookAllConstructors`
  - 같은 id를 여러 executable에 붙임.
  - LibXposed 문서상 id는 executable 단위로 구분되므로 서로 덮어쓰지 않음.

감지 관점에서 공통적인 흔적 (훅 개별이 아니라 모듈 전체)
- Spotify 프로세스 메모리에 LibXposed/LSPosed 브리지, `libdexkit.so`가 로드됨 (DexKit은 심볼 캐시가 없을 때 = 첫 실행/Spotify 업데이트 직후에만).
- 훅된 메서드를 지나는 모든 스택 트레이스에 LSPosed 프레임과 `com.eza.spicyex.*` 프레임이 찍힘.
  `Activity` 생명주기, `dispatchKeyEvent`, OkHttp 체인, `PlayerState$Builder.build` 등이 훅돼 있어 **거의 모든 UI/네트워크 스택**에 나타남.
- Spotify 데이터 폴더(`/data/data/com.spotify.music/`) 안에 모듈 파일이 생김 (§5).

---

## 2. 현재 빌드(PR #17)에 남아 있는 훅 전체 목록

표기
- **원본** = upstream에도 같은 훅이 있음
- **포크** = 포크에서만 추가
- 위험도 = 동작을 바꾸는 정도 + 감지/부작용 가능성 (주관적)

### 2-1. 동작을 바꾸는 훅 (인자 변경 / 원본 호출 생략 / 결과 교체)

| # | 대상 | 위치 | 출처 | 동작 | 위험 |
|---|---|---|---|---|---|
| 1 | `Activity.finish()` before | `hooks/LyricsActivityTakeoverHook.java:200` | 원본 | 가사 전체화면 Activity(`com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity`)에서 `shouldKeepLyricsActivityOpen()`(:1053)이 true면 `setResult(null)`로 **finish를 막음**. 직전 back 입력이 있으면 명시적 종료로 처리 | 높음 |
| 2 | `Activity.onBackPressed()` before | `LyricsActivityTakeoverHook.java:188` | 원본 | 가사 화면이면 Spotify 처리 대신 자체 처리(`handleLegacyActivityBack`), 다른 화면이면 `NowPlayingInjector.consumeArtworkBack` | 높음 |
| 3 | `Activity.dispatchKeyEvent(KeyEvent)` before | `LyricsActivityTakeoverHook.java:196` | 원본 | 가사 화면에서 BACK 키 UP을 셸 레이어가 소비하면 `setResult(true)` | 중상 |
| 4 | 위 2,3의 **런타임 추가 훅** | `LyricsActivityTakeoverHook.java:316, :320` (`coverHostMethod`) | 원본 | 가사 Activity 클래스와 `androidx.activity.ComponentActivity`에 `onBackPressed`/`dispatchKeyEvent` override가 있으면 그 클래스에도 `hookAllMethods`로 훅 | 중상 |
| 5 | `Activity.setRequestedOrientation(int)` before | `LyricsActivityTakeoverHook.java:218` | **포크** | 가사 세션 중 가사 Activity면 인자를 `SCREEN_ORIENTATION_FULL_USER`로 **바꿔치기** | 중 |
| 6 | `Activity.startActivityForResult(Intent,int,Bundle)` before | `hooks/LyricsPipController.java:159` | **포크** | 가사 페이지 실행 Intent를 감지해 (a) `setResult(null)`로 실행 생략, 또는 (b) `NowPlayingActivity` + extra(`com.eza.spicyex.LYRICS_PIP`, `..._DOCK`)로 **Intent 교체** | 중 |
| 7 | `Activity.dispatchKeyEvent` before (PiP) | `LyricsPipController.java:189` | **포크** | API<33, 도킹된 Activity에서 BACK 소비 | 중 |
| 8 | `NowPlayingActivity.onPictureInPictureModeChanged(boolean,Configuration)` before | `LyricsPipController.java:209` | 원본 | 모듈 PiP 호스트면 Spotify 콜백 **생략**(`setResult(null)`) 후 자체 처리. 대상 = `com.spotify.nowplaying.musicinstallation.NowPlayingActivity` | 중 |
| 9 | `Activity.isInPictureInPictureMode()` before | `LyricsPipController.java:233` | 원본 | 모듈 PiP 호스트면 **false 반환**(실제로는 PiP) | 중 |
| 10 | `Activity.setPictureInPictureParams` before | `LyricsPipController.java:237` | 원본 | 호스트/도크면 Spotify 쪽 호출 **무시** | 중 |
| 11 | `TextView.onConfigurationChanged(Configuration)` before+after | `LyricsPipController.java:224` | 원본 | PiP 호스트의 TextView만 typeface 저장 후 복원 | 낮음 |
| 12 | Compose 가사 카드 함수 before | `hooks/SpotifyLyricsCardHider.java:59` | **포크** | DexKit: 문자열 `"#LyricsCardTag"`를 쓰는 static 메서드 중 `androidx.compose.runtime.Composer` 파라미터가 있는 것. 설정 `HIDE_SPOTIFY_LYRICS_CARD`(기본 **false**)가 켜져 있으면 `setResult(null)`로 **Composable 실행 생략**. Composer 단위로 결정을 고정 | 중상 (설정 켰을 때) |
| 13 | `com.spotify.localfiles.mediastore.MediaStoreReader.query(Uri,boolean)` after | `hooks/LocalFilesFolderHook.java:45` | **포크** | 외부 오디오 MediaStore 쿼리 결과 Cursor를 `MatrixCursor`로 **항상 교체**하고 원본을 close. 숨긴 폴더가 없어도 `ALBUM` 컬럼을 **폴더 이름으로 덮어씀** (`filterCursor`, :133~) | 중 (상시 동작) |
| 14 | `Activity.dispatchActivityResult(String,int,int,Intent,...)` (hidden API) before | `hooks/ActivityResultBridge.java:44` | 원본 | 모듈이 등록한 requestCode(현재 `10234`, 레이아웃 에디터 파일 선택)면 결과를 가로채 Spotify에 전달 안 함 | 낮음 |

### 2-2. 읽기 전용 / 관찰 훅 (값을 바꾸지 않음)

| # | 대상 | 위치 | 출처 | 동작 | 비고 |
|---|---|---|---|---|---|
| 15 | `okhttp3.internal.http.RealInterceptorChain` 등의 `proceed`/`intercept` after | `hooks/NativeLyricsNetworkHook.java:77` | 원본 | 후보 클래스 6개: `okhttp3.internal.http.RealInterceptorChain`, `okhttp3.internal.connection.RealInterceptorChain`, `okhttp3.RealCall`, `okhttp3.internal.connection.RealCall`, `com.spotify.okhttp.OkHttpInterceptorChain`, `com.spotify.okhttp.RealInterceptorChain`. **모든 HTTP 요청마다** reflection으로 URL을 읽음. `color-lyrics` / `lyrics/v2` / `lyrics/v3` URL이면 응답 body 객체를 트랙 ID와 짝지어 저장 | 핫패스. 짝 못 지은 body가 스트림으로 읽히면 `PENDING` 맵에 남음 |
| 16 | `okhttp3.ResponseBody.string()` / `.bytes()` after | `NativeLyricsNetworkHook.java:110, :112` | 원본 | **모든 응답 본문**에 대해 `trim()`, 정규식 컴파일·스캔(`spotify:track:`), `bytes()`는 UTF-8 문자열로 변환. 짝이 없으면 현재 재생 트랙 ID로 대체 → `captureColorLyricsResponse` | 오귀속, 성능 문제 |
| 17 | 가사 클래스 생성자/메서드 after (최대 40클래스 × 생성자 전부 + 메서드 18개) | `hooks/NativeLyricsCaptureHook.java:405, :420` | 원본 | 이름으로 찾는 클래스 (`NativeLyricsCaptureHook.java:40-53`): `com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity` (+`$Line`, `$Syllable`, `$Provider`), `com.spotify.lyrics.data.model.Lyrics`, `com.spotify.lyrics.data.model.ColorLyrics`, `com.spotify.lyrics.serviceretrofit.proto.v3.LyricsWrapperResponse`, `...proto.v3.LyricsV3Response`, `...proto.ColorLyricsResponse`, `...proto.LyricsResponse`. DexKit 문자열 probe (`:62-70`): `"lyrics_entities("`, `"SELECT * FROM lyrics_entities WHERE track_id = ?"`, ``"INSERT OR REPLACE INTO `lyrics_entities`"``, `{"syncStatus","vocalRemovalStatus"}`, `"GeneratedJsonAdapter(LyricsDatabaseEntity.Line)"`, `"...(LyricsDatabaseEntity.Syllable)"`, `"lyricsLines_"`, `"LyricsLineTag"` (probe당 최대 8클래스) | R8이 무관한 코드와 합친 클래스면 핫패스 메서드까지 훅됨 (코드 주석에 9.1.88 사례 있음) |
| 18 | DexKit trace 메서드 after | `NativeLyricsCaptureHook.java:375` | 원본 | `NATIVE_LOAD_TRACES`(`:80`)의 문자열을 쓰는 메서드 + 그 선언 클래스 전체(`hookResolvedNativeLyricsClass`) | 같은 위험 |
| 19 | `ClassLoader.loadClass(String,boolean)` after | `NativeLyricsCaptureHook.java:305` | 원본 | 이름으로 못 찾은 클래스가 APK에 있을 때만 설치. 그 경우 **모든 클래스 로드**마다 콜백 | 조건부 |
| 20 | 색상 가사 Retrofit 서비스 생성자 after | `NativeLyricsCaptureHook.java:171` | 원본 | DexKit: Retrofit 어노테이션 값 `color-lyrics/v2/track/{trackId}`를 가진 메서드 (파라미터 `String, boolean, String, boolean`, 반환 `io.reactivex.rxjava3.core.Single`) → 그 Retrofit 인터페이스 필드를 가진 서비스 클래스의 인스턴스를 캡처. 이후 모듈이 **직접** `endpoint.invoke(client, id, false, language, false)`로 Spotify 가사 API를 호출 (`requestSpotifyTrack`, :188~) | 포크는 language에 `Locale.getDefault()` 사용 (원본은 앱 locale로 수정됨) → 요청 값이 Spotify 자체 요청과 다를 수 있음 |
| 21 | `com.spotify.player.model.AutoValue_PlayerState$Builder.build()` after | `hooks/PlaybackBridge.java:60` | 원본(+포크 리스너) | 새 PlayerState마다 `References.playerState` 갱신 + 가사 리스너(`LyricsActivityTakeoverHook.onPlayerStateUpdate`) 호출 | 초당 여러 번 호출되는 핫패스 |
| 22 | PlayerWrapper `getState()` after | `PlaybackBridge.java:108` | 원본 | DexKit: public final, interface 1개, 필드 구성(String / ArrayList / Object / Bundle)으로 클래스 찾은 뒤 `getState` 메서드. `this`만 저장 | |
| 23 | `MediaSession.setMetadata(MediaMetadata)` after | `PlaybackBridge.java:120` | 원본 | 아트워크 캐시(`SpotifyArtworkCache.capture`) | |
| 24 | `MediaSession.setPlaybackState(PlaybackState)` after | `PlaybackBridge.java:127` | 원본 | 세션/재생 여부/위치 기록 | |
| 25 | `Activity.onCreate(Bundle)` after | `SpicyEXModule.java:82`, `LyricsActivityTakeoverHook.java:105`, `LyricsPipController.java:120` | 원본 | 폰트 로드 / 가사 takeover 활성화 / PiP 인텐트 처리. 가사 화면 외 Activity에는 **추가 버튼 주입 예약**(`scheduleExtraLyricsButtonInjection`) | |
| 26 | `Activity.onResume()` after | `SpicyEXModule.java:63`, `LyricsActivityTakeoverHook.java:121`, `LyricsPipController.java:139` | 원본 | 현재 Activity 기록, takeover 재적용, 상태바 숨김 재적용, PiP 마운트, 가사 화면이면 도크로 리다이렉트 | |
| 27 | `Activity.onDestroy()` before | `SpicyEXModule.java:79`, `LyricsActivityTakeoverHook.java:159`, `LyricsPipController.java:244` | 원본 | 정리 | |
| 28 | `Activity.onPause()` / `onUserLeaveHint()` / `onWindowFocusChanged(boolean)` after | `LyricsActivityTakeoverHook.java:171, :181, :141` | 원본 / `onUserLeaveHint`는 **포크** | Home으로 나갈 때 PiP 열기 등 | |
| 29 | `Activity.onNewIntent(Intent)` / `onStart()` / `onStop()` after | `LyricsPipController.java:126, :197, :200` | 원본 | PiP 상태 추적 | |
| 30 | `Application.attach(Context)` after | `SpicyEXModule.java:101` | 원본 | 부트스트랩: 진단 초기화, 메모리 압박 리스너, Spotify `cacheDir`의 `*.apk` **삭제**(`cleanUpCache`), DexKit 심볼 탐색, 모든 훅 설치 | |

### 2-3. 훅은 아니지만 Spotify에 영향을 주는 동작 (현재 빌드)

| 항목 | 위치 | 출처 | 내용 |
|---|---|---|---|
| Apple 스타일 플레이어 | `hooks/ApplePlayerStyler.java` (`NowPlayingInjector.java:57`에서 attach) | **포크** | 설정 `PLAYER_APPLE_STYLE` **기본값 true**. Spotify `NowPlayingActivity`의 뷰를 리소스 이름(`overlay_controls_layout`, `track_carousel`, `player_overlay_header` 등)으로 찾아 **직접 변형**: 배경 뷰 삽입, 캐러셀 alpha 변경, 상태바 처리, "더보기" 버튼 / **"Download from YouTube Music" 버튼 추가**(:461), 재생바 부착 |
| YouTube 다운로더 | `hooks/YoutubeDownloader.java` | **포크** | 위 버튼을 누르면 NewPipe Extractor로 YouTube 검색·스트림 추출 후 **Spotify의 `filesDir/YouTubeMusic/`**에 m4a 저장. Spotify 패키지 이름으로 알림 채널 `youtube_downloads` 생성 |
| 가사 화면 takeover / 상태바 숨김 | `LyricsActivityTakeoverHook`, `NativeSpicyShellViewImpl` | 원본 | Spotify 가사 Activity 위에 자체 뷰 트리를 붙임 |
| Now Playing 카드 / 버튼 주입 | `hooks/NowPlayingInjector.java`, `NowPlayingLyricController.java` | 원본(포크 수정) | Spotify 플레이어 화면에 가사 카드, 버튼 추가 |
| 재생 제어 | `PlaybackBridge` | 원본 | `MediaController.getTransportControls().seekTo/skipToNext/...` (Spotify 자체 MediaSession 경유) |
| 좋아요 | `hooks/SpotifyCollectionAction.java` | 원본 | MediaSession custom action 중 "collection" 액션 dispatch |
| AudioDriver 읽기 | `hooks/AudioOutputLatency.java` | 원본 | `com.spotify.playbacknative.AudioDriver`의 static 세션 맵을 reflection으로 읽어 `AudioTrack.getTimestamp` (훅 아님) |
| 인트로/아웃트로 스킵 | `NativeSpicyShellViewImpl.java` `performSkipSeek` | 원본 | 설정 `AUTO_SKIP_INTRO_OUTRO` 기본 Off. 켜면 곡 끝 250ms 전으로 seek (무료 계정 skip 제한을 피하려는 의도라고 주석에 명시) |
| Spotify Web API 호출 | `sharecard/LyricsShareCardController.java:4352, :4439` | 포크 | `api.spotify.com/v1/tracks|artists`. 토큰은 `SpotifyWebApiToken.current()`인데 **토큰을 저장하는 코드(`SpotifyTokenStore.store`)를 부르는 곳이 없어** 실제로는 토큰 없이 실패. 확인 필요 |
| HyperGlow 브리지 | `SpicyLyricBridgePublisher` | 원본 | Spotify 프로세스에서 외부 앱 서비스에 bind |
| 디버그 에이전트 채널 | `hooks/AgentCommandChannel.java` | 포크 | `BuildConfig.DEBUG` + `filesDir/spicy-agent/arm` 파일이 있을 때만 동작. 릴리스 빌드는 비활성 |

---

## 3. PR #17에서 제거한 것 (포크 전용, `archive/connect-ads`에 원본 보존)

### 3-1. 제거된 훅

| 대상 | 원래 위치 (archive 브랜치) | 원래 동작 |
|---|---|---|
| `AudioTrack.play()` after | `hooks/AdMuteController.java:188` | 광고 중이면 트랙 볼륨 0, 아니면 기억한 볼륨으로 복원 |
| `AudioTrack.setVolume(float)` before | `AdMuteController.java:200` | 광고 중이면 **인자를 0으로 교체**, 아니면 값 기억 |
| `AudioTrack.setStereoVolume(float,float)` before | `AdMuteController.java:212` | 위와 동일 (좌/우) |
| `TextView.setText(CharSequence,BufferType,boolean,int)` (private) after | `hooks/AdBreakInfo.java:175` | 광고 재생 중에만 설치/해제. "37초 남음" 라벨 파싱 |
| `MediaSession.setMetadata` after | `AdBreakInfo.java:259` | 광고 위치 "2 of 3" 파싱 |
| `NotificationManager.notify(String,int,Notification)` before | `AdBreakInfo.java:267` | 알림 텍스트 파싱 (읽기만) |
| `Activity.onResume()` after | `hooks/SpotifyConnectHook.java:156` | Spotify가 앞으로 오면 웹 플레이어 warm-up, 로컬 재생 중이면 웹 플레이어로 이관 |
| `AudioTrack.play()` after | `SpotifyConnectHook.java:174` | 첫 로컬 재생 시 로컬 트랙 `setVolume(0f)` 후 웹 플레이어로 hand-off. 실패 시 복원 |
| `AudioTrack.pause/stop/flush` after | `SpotifyConnectHook.java:406` | hand-off로 음소거한 트랙을 `setVolume(1f)`로 복원 |
| Connect 전송 컨트롤러 생성자 after | `hooks/ConnectTransfer.java:56` | DexKit 문자열 `"Transfer playback failure","local_device"`로 찾은 클래스 인스턴스 캡처 → static `String x(Owner,String deviceId,String,int)`로 Spotify 내부 재생 전환 호출 |
| `okhttp3.Request$Builder.build` after | `hooks/ConnectMirror.java:45` | **설치 코드가 호출되지 않아 원래부터 비활성**. URL에 player/transfer/command가 있으면 Connect 쪽에 알림. 광고 차단용 `build` 훅은 주석 처리돼 있었음 |

### 3-2. 제거된 비훅 기능

- **Spicy Connect 웹 플레이어** (`player/` 전체: `WebPlayerService`, `WebLoginActivity`, `BrowserDisguise`, `PlayerSession`, `PlayerWarmReceiver`, `BootWarmReceiver`, `spotifyconnect/ConnectEntry`, `ForegroundServiceGuard`)
  - 모듈 앱 자체 프로세스 `:player`에서 WebView로 `open.spotify.com` 웹 플레이어를 로그인 상태로 띄워 Spotify Connect 기기로 노출.
  - `BrowserDisguise`
    - WebView를 Chrome으로 위장: `X-Requested-With` 제거, Sec-CH-UA 조작, UA에서 `; wv` 제거, JS 주입.
  - `WebPlayerService.java:386-411` (archive 기준)
    - 분석·광고 도메인 차단: `doubleclick.net`, `sentry.io`, `aet.spotify.com`, `gabo-receiver-service`, `audio-ads.spotify.com`, `adeventtracker.spotify.com`, `pixel.spotify.com`, `ads.spotify.com`, `mp3ad.scdn.co` 등.
  - 부팅 시 자동 시작(`BOOT_COMPLETED`), 오버레이 권한(Widevine 때문에 실제 창 필요), foreground mediaPlayback 서비스.
  - **서버 입장에서 "같은 계정에 광고/분석 요청이 빠진 웹 플레이어 기기가 상시 붙어 있음"으로 보이는 가장 강한 신호였음** (추정).
- **광고 대체 음악** (`AdMusicPlayer`, `AdMusicPreview`): 광고 중 자체 생성 음악 재생.
- **Connect 부가 기능**: `ConnectRouteHandoff`(MediaRouter로 출력 경로 전환), `DeviceChangeBanner`, `AudioOutputRoute`.
- **설정**
  - 섹션 "Seamless Listening"(`ad_free`)과 키 `ad_mode`, `ad_music_theme`, `connect_enabled`, `connect_auto_switch`(기본 "On app start"), `connect_network_recovery`.
  - 첫 실행 시 `SettingsStore.migrateRemovedAdAndConnect`가 이 값들과 레거시 `auto_mute_ads`를 Spotify prefs에서 삭제.
- **매니페스트**: 위 서비스/리시버/액티비티와 권한 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`.
- **기타**
  - `PlaybackBridge`의 Connect 전용 메서드(`pause`, `sessionPlaying`, `playingLocally`, `setShuffleOff`, `sessionKnown`, `isPlayerStatePaused`)와 AdMute용 state 리스너.
  - 에이전트 `banner` 명령.
  - 가사 화면 광고 카드의 음소거/음악 표시와 광고 위치/남은 시간 표시 (**"Lyrics resume after the ad" 카드만 남김**, 훅 없이 `spotify:ad:` URI로만 판단).

---

## 4. 원본(upstream) 대비 차이 요약

- 원본에만 있음 (포크에서 빠짐)
  - Android Auto 연동(`auto/*`, `com.google.android.projection.gearhead` 스코프, `XpHooks.deoptimize`)
  - `SpicyOrgRetentionHook`
- 포크에만 있음 (현재 빌드 기준)
  - 훅: §2의 5, 6, 7, 12, 13, 28(`onUserLeaveHint`)
  - 비훅: Apple 스타일 플레이어, YouTube 다운로더, 공유 카드의 Spotify Web API 호출, 추가 가사 소스(NetEase, QQ, KuGou, Genius, Musixmatch, Apple TTML 미러), 더블탭 좋아요 등
- 같은 파일이지만 내용이 다른 것
  - `NativeLyricsCaptureHook`: 가사 요청 언어. 원본은 앱 locale, 포크는 시스템 locale.
  - `PlaybackBridge`: 원본에 있는 `readEffectivePlaybackRate`가 포크에는 없음.
  - `NativeSpicyLyricsHook`: 설치 목록.

---

## 5. Spotify 데이터 폴더에 모듈이 남기는 흔적

모듈 코드는 Spotify 프로세스와 Spotify의 Context로 돌기 때문에 아래 파일이 **`/data/data/com.spotify.music/`** 안에 생긴다.

- `shared_prefs/`
  - `SpotifyPlus.xml` (모듈 설정 전체. 레거시 키 `native_spotify_access_token` 포함 가능)
  - `SpotifyPlusAiCredentials.xml`, `SpotifyPlusAIPaidArtifactCache.xml`, `SpotifyPlusCanonicalSourceCache.xml`, `SpotifyPlusLyricsSourceSelection.xml`
  - `SpicyExMusixmatch.xml`, `SpicyLyrics_QueryBreaker_g1.xml`
  - `spicy_settings_search.xml`, `spicyex_share_hint.xml`
  - `spicy_diagnostic_capture_v1.xml`, `spicy_diagnostic_draft_v1.xml`
- `no_backup/spicy-symbols-<process>.properties` (DexKit 심볼 캐시)
- `files/language-models-*` (언어 모델 팩), `files/YouTubeMusic/` (YouTube 다운로드), `files/spicy-agent/` (디버그만)
- 가사/번역 캐시 (`lyrics/cache/*`)
- 반대로 **삭제**하는 것: Spotify `cache/*.apk` (`SpicyEXModule.cleanUpCache`)
- 알림 채널: `youtube_downloads` (YouTube 버튼을 한 번이라도 눌렀다면)

모듈이 Spotify 프로세스 안에서 직접 HTTP 요청을 보내는 외부 호스트:
`lrclib.net`, `music.163.com`, `c.y.qq.com`, `lyrics.kugou.com`, `genius.com`, `apic.musixmatch.com`, `amp-api.music.apple.com`, `amll-ttml-db.stevexmh.net`, `amlldb.bikonoo.com`, `lyrics-api.binimum.org`, `lyrics-api.boidu.dev`, `spotifyplus-api.devon-shoutz.workers.dev`, `translate.googleapis.com`, `clients5.google.com`, AI 제공자들(openai, deepseek, openrouter, generativelanguage.googleapis.com), `api.github.com`, `www.youtube.com`, `api.spotify.com`, `open.spotify.com/oembed`.

---

## 6. 의심 순위 (현재 빌드 기준, 추정)

1. **설치본/기기 단위 제한이 이미 걸린 상태** (모듈과 무관하게 지속)
   - 새 계정에서도 1곡 후 멈추는 증상과 가장 잘 맞음.
   - 모듈을 끈 상태로도 재현되는지 먼저 확인.
2. **이전 빌드가 여전히 로드 중**
   - 새 APK 설치 후 Spotify를 강제 종료하거나 재부팅하지 않았으면 예전 훅이 메모리에 그대로 있음.
   - LSPosed 로그에 `[SpicyAdMute]`, `[SpicyConnect]`, `[SpicyWeb]`이 보이면 예전 빌드.
   - 모듈 앱 데이터에는 예전 웹 플레이어 WebView 쿠키(로그인)가 남아 있을 수 있음.
3. **Apple 스타일 플레이어 (기본 ON, 포크 전용)**
   - Spotify 플레이어 화면 뷰를 직접 변형하고 다운로드 버튼을 추가함.
   - 플레이어 화면 로직이 뷰 상태를 검사한다면 영향 가능. 설정에서 끄고 비교해 볼 것.
4. **LocalFilesFolderHook (상시, 포크 전용)**
   - 로컬 파일 Cursor를 항상 교체하고 ALBUM 값을 바꿈.
   - 로컬 파일 기능이 망가지거나 메타데이터 불일치가 생길 수 있음.
5. **가사 직접 요청 (`requestSpotifyTrack`)**
   - Spotify 자체 Retrofit 클라이언트로 가사 API를 호출하는데, 언어 파라미터가 Spotify 자체 요청과 다름.
6. **OkHttp / ResponseBody 전역 훅**
   - 원본에도 있지만 모든 HTTP 응답 본문을 읽고 문자열로 만듦. 성능과 메모리 부담.
7. 그 밖의 Activity 레벨 훅: §2-1의 1~11.

---

## 7. 빠른 검증 체크리스트

- [ ] LSPosed 스코프 해제 → Spotify 강제 종료 → 재생 2~3곡: 멈추는가?
- [ ] (멈추면) Spotify 앱 데이터 삭제 또는 재설치 후 다시 확인
- [ ] (안 멈추면) 모듈 다시 켜고 설정에서 Apple 스타일 플레이어 OFF → 재확인
- [ ] LSPosed 로그에서 `[SpotifyPlus] Loading SpotifyPlus` 이후 설치된 훅 목록 확인 (`[SpicyAdMute]`, `[SpicyConnect]`가 없어야 함)
- [ ] Spotify APK에서 에러 문자열과 그 호출 조건 확인 (§0)

---

## 8. 후속 조치 (코드로 해결한 항목)

| 항목 | 조치 |
|---|---|
| §2 #20 가사 요청 언어 | `NativeLyricsCaptureHook.clientLanguage()`를 원본과 동일하게 복원. 앱 locale(`applicationContext` Configuration) 우선, 없으면 시스템 locale |
| §2 #13 / §6-4 LocalFilesFolderHook | 숨긴 폴더가 없으면 Spotify Cursor를 **그대로 통과**. 숨긴 폴더가 있어도 `ALBUM` 컬럼은 더 이상 덮어쓰지 않음 (행 제외만 수행) |
| §2 #16 / §6-6 ResponseBody 전역 훅 | 짝지어지지 않은 응답은 `"lines"` + `"startTime…"`가 있는 JSON일 때만 파싱 (그 외 JSON이 현재 트랙 가사로 오귀속되던 문제 차단). `bytes()`는 짝지어졌거나 `{`/`[`로 시작할 때만 문자열 변환 (이미지·protobuf 제외) |
| §2 #15 `PENDING` 누수 | 최대 16개로 제한, 넘으면 비움 |
| §2-3 공유 카드 Web API 토큰 | 확인 결과 `SpotifyWebApiToken.current()`가 null이면 `api.spotify.com` 요청 자체를 보내지 않음 (oEmbed만 사용). 원본도 토큰 캡처 훅(`AuthTokenCaptureHook`)을 `3e18742a`에서 의도적으로 제거했으므로 **캡처 훅은 다시 넣지 않음** |

코드로 해결하지 않은 것 (기기 확인 필요)
- §6-1/§6-2: 스코프 해제 상태 재현 여부, 이전 빌드 잔존 여부는 §7 체크리스트로 확인.
- §6-3 Apple 스타일 플레이어 기본 ON: 원인으로 확인되면 `PLAYER_APPLE_STYLE` 기본값을 false로 변경.

---

## 9. Spotify APK 분석 결과 (기기에서 뽑은 base/split APK + 테스트 앱)

분석 대상: 폰에서 `adb pull`한 `com.spotify.music` 9.1.90.2270 (base.dex ×15 + arm64 split 네이티브).
교차 확인: 같은 탐지 로직을 재현한 `:detector-test` 앱을 실제 기기(SM-G970N)에서 실행.

### 9-1. Spotify가 쓰는 보호 메커니즘 전체 목록

| # | 메커니즘 | 위치 | 성격 | 이 기기에서 |
|---|---|---|---|---|
| 1 | **네이티브 app_integrity + DRM** | `liborbit-jni-spotify.so` (`AppIntegrityLoggerSetupImpl`, `app_integrity_delayed_request`, `spotify::drm::collectFridaFeatures`, `frida_detect`, Widevine, `playback_error_drm_denied`) | 재생 게이트 | **1곡 후 멈춤의 유력 원인.** DRM 키 거부(`playback_error_drm_denied`). Frida 탐지는 디버그 빌드에서 passive |
| 2 | **Play Integrity API** (Standard+Express) | `classes.dex`, `classes2.dex` (`PlayIntegritySessionPlugin`, `requestExpressIntegrityToken`) | 서버 어테스테이션 | 로그인/세션 시점. 루팅 기기는 DEVICE_INTEGRITY 실패 |
| 3 | **reCAPTCHA Enterprise** | `classes.dex` (`mobile_client/authentication`, `EsRecaptchaInfo`) | 로그인 abuse | 로그인 시 |
| 4 | **RASP 루트/후킹 체크 (RootBeer 계열)** | `classes3.dex` | 클라이언트 | **테스트 앱에서 전부 통과** (아래 9-2) |
| 5 | **Ravelin 네이티브 루트 체커** | `librootChecker.so` (`RootCheckerNative`, `/proc/self/mounts` magisk 스캔) | 클라이언트 | 루트 숨김으로 통과 추정 |
| 6 | **APK 서명 자가검증** | `classes.dex` (`SigningInfo`, `getPackageInfo`, `CRC32`, `getLocalCertificates`) | 클라이언트 | 모듈은 Spotify APK를 건드리지 않음 → 통과 |
| 7 | **Crashlytics + gabo 텔레메트리** | `classes.dex` (`X-Crashlytics-*`, `eventsender/gabo`, `setDefaultUncaughtExceptionHandler`) | 간접(수집) | Spotify 프로세스 크래시 시 스택에 `com.eza.spicyex.*` 프레임이 업로드될 수 있음 |

### 9-2. 테스트 앱(`:detector-test`) 결과 — 이 기기의 실제 상태

`com.spicytest.detector`를 자기 프로세스에서 실행 → **11개 체크 전부 clean**:
- 루트/후킹 매니저 앱 22종 없음 / su·busybox·magisk 바이너리 앱에서 안 보임 / test-keys 아님(release-keys) /
  `ro.secure=1 ro.debuggable=0` / system read-only / magisk 마운트 없음 / 스택 훅 없음 /
  XposedBridge 로드 불가 / 에뮬레이터 아님 / 디버거 없음.
- 단, **adb 셸에서는** `/system/bin/su` 존재 + `org.lsposed.corepatch` 설치 확인됨.
- 해석: **루트가 앱 샌드박스(SELinux)에서 숨겨져 있어** RootBeer/Ravelin류 클라이언트 체크(#4,#5)는
  Spotify에서도 통과할 가능성이 높음. 앱이 자기 프로세스에서 볼 수 있는 유일한 흔적은
  **"내 프로세스가 후킹됐는가"**(XposedBridge 로드 / 훅된 스택)인데, 이는 LSPosed가
  **스코프에 든 앱(Spotify)에만** 주입하므로 테스트 앱에선 안 보임.

### 9-3. 결론 (수정된 의심 순위)

1. **네이티브 DRM / app_integrity (#1)** — `playback_error_drm_denied`가 1곡 후 멈춤과 직결. 모듈 코드로 해결 불가.
2. **Play Integrity (#2)** — 로그인/세션 레벨. 루팅/부트로더 상태에 좌우. 모듈 무관.
3. **프로세스 내부 후킹 탐지** — Spotify 프로세스에 한해 `XposedBridge` 로드가 보임. LSPosed DenyList/숨김 영역.
4. RootBeer/Ravelin 클라이언트 체크 — 이 기기에선 통과(루트 숨김).

즉 **차단은 RASP(dex) 쪽이 아니라 네이티브 DRM/무결성·기기 루트 레벨**일 가능성이 높고,
모듈의 훅 노출면 축소로는 바뀌지 않을 공산이 큼. "최대한 피하기"의 핵심은 모듈이 아니라
LSPosed 숨김 + 루트 DenyList(이미 부분적으로 숨겨져 있음) 쪽.

### 9-4. 미확인 영역
- `liborbit-jni-spotify.so`의 app_integrity가 **정확히 무엇을 신호로 보내는지**(네이티브 역어셈블 필요).
- Widevine 보안 레벨(L1/L3)과 provisioning 상태가 이 기기에서 정상인지.
