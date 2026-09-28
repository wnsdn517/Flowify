# Non-root (LSPatch) setup: problems and plan

## Where non-root users get stuck today

1. **Login needs a two-APK dance.** Spotify checks Play Integrity at login, and an LSPatch'd
   APK fails it. The only documented way through is: patch an old Spotify (pre-integrity) and a
   new one, install the old one, log in, then install the new one over it.
2. **Every update means re-patching.** In LSPatch's *integrated* mode the module is baked into
   the Spotify APK, so each Spicy EX update needs a new patched Spotify, installed over the old
   one with "Override version code".
3. **Nothing tells the user whether it worked.** The module APK has no launcher screen. If the
   patch, scope or signature is wrong, Spotify just looks stock, and there is nowhere to check.
4. **Extras need steps that aren't discoverable.** The language model pack (download from
   Settings), the "draw over other apps" permission for Spicy Connect, and battery exemptions
   are all found by trial and error.

## Plan

### 1. Make LSPatch *local* (manager) mode the documented path

In local mode the patched Spotify loads the module from the installed Spicy EX APK through the
LSPatch manager. Updating Spicy EX is then an ordinary APK update - no re-patching, no second
Spotify install. Re-patching is only needed when **Spotify** itself updates.

- readme/FAQ: lead with local mode; keep integrated mode as a fallback for devices where the
  manager gets killed.
- FAQ "How do I update?": split into "Spicy EX update" (just install) and "Spotify update"
  (re-patch with the same keystore, install over).

### 2. A setup screen in the Spicy EX app (launcher activity)

A small launcher activity in the module APK that checks each step live and has one button per
step. Every check is possible without root:

| Check | How |
|---|---|
| Spotify installed, version | `PackageManager.getPackageInfo("com.spotify.music")` |
| Spotify is patched (not Play-signed) | signing cert differs from Spotify's known cert, and the LSPatch metadata (`assets/lspatch/`) is present in its APK |
| LSPatch manager installed (local mode) | `PackageManager` lookup of the manager package |
| Module actually running inside Spotify | the hook already runs in Spotify's process: have it send a heartbeat to the existing exported receiver (`PlayerWarmReceiver` pattern) on startup; the setup screen shows the last heartbeat time and Spotify version |
| Tested Spotify version | compare with a `TESTED_SPOTIFY_VERSIONS` list shipped in the APK |
| Language model pack | `LanguageModelPack` status (same code the Settings row uses) |
| Spicy Connect overlay permission | `Settings.canDrawOverlays()`, button opens the permission screen |
| Battery optimization (Connect) | `PowerManager.isIgnoringBatteryOptimizations()`, button opens the exemption dialog |

Buttons open LSPatch, the permission screens, and the official release pages for the tested
Spotify versions (links only - we never redistribute Spotify APKs).

### 3. Make the login step as short as possible

- **Ship the exact version pair.** List the tested old/new Spotify versions in the setup screen
  (and readme), with the store page / version archive links, so users don't guess which "old"
  version still logs in.
- **Detect the state and say what to do next.** From the heartbeat (logged-in flag read in the
  hook) and the installed version, the setup screen can show: "Old version installed and logged
  in → now install the patched new version" instead of a static list.
- **Research item: login without the downgrade.** Two options need testing before anything is
  promised: (a) whether Spotify's email one-time-code login is also integrity-gated, and (b)
  whether a session carried over by Android's backup/restore of a stock install survives
  patching. Neither is implemented; both would remove the old-APK step if they work.

### 4. Guard rails

- Warn in the setup screen when a known-conflicting package (ReVanced Spotify, old Spotify
  Plus) is installed.
- In-Spotify Settings: a "Non-root setup" row that deep-links to the setup screen when the hook
  detects it is running under LSPatch.

## Suggested order

1. Docs change for local mode (no code).
2. Heartbeat from the hook + setup screen with the status checks.
3. Version-pair guidance and state-aware next step.
4. Login research (a)/(b).
