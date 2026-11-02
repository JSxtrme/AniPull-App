# Anipull — Android app

Download-only anime, for personal use first. Search an anime, pick a
resolution, and pull clean direct-download links — episode by episode, or a
whole season in one go.

**What Anipull is not:** it does not stream video, it does not track what
you pull, and it does not host any files. It indexes direct-download (DDL)
sources and resolves links. No torrents.

This is the **app** repo. The website lives in a separate repo
([JSxtrme/AniPull](https://github.com/JSxtrme/AniPull), live at
[anipull.vercel.app](https://anipull.vercel.app/), demo only and paused for
now) — this repo is where the Android app actually lives.

## Status — read this first

Honest state, November 2026:

- ✅ Browsing and search work, live, via the AniLab metadata API (no key).
  Home rows, search-as-you-type, detail pages, season switcher, watch-order
  guide, genre catalog rows, settings, downloads page shell.
- ⚠️ **Downloads are still demo-only.** Tapping download shows
  "Not wired yet". The download engine / resolver stage has not started:
  no per-episode pull, no queue, no pause/resume yet. AniLab gives no
  episode data, so an episode source is still an open problem.
- The app asks for exactly one permission: `INTERNET`. No storage, media,
  or notification permissions yet. Notifications (first-boot prompt +
  download progress) are designed and agreed, but belong to the
  download-engine stage, not this build.

## Get the app

Grab an APK from the
[Releases](https://github.com/JSxtrme/AniPull-App/releases) page and
install it over the previous build — every build so far is signed with the
same debug key on purpose, so this updates in place:

```sh
adb install -r anipull-<version>.apk
```

If Android complains about a signature mismatch, uninstall first
(`adb uninstall com.js.anipull`) and then install fresh. A proper release
signing key is a later step, before any public release.

| | |
| --- | --- |
| Package | `com.js.anipull` |
| Min / target SDK | 26 / 36 (compile SDK 37) |
| Size | ~7.5 MB |
| Language / UI | Kotlin + Jetpack Compose (Material 3) |
| Font | Outfit (OFL, bundled in `res/font`) |

## Features

- Live home feed from AniLab: Spotlight, Trending, Most Popular, Top
  Airing, Most Watched, plus Weekend marathon picks and an Editor's Pick
  card. Last good home is cached on-device, so the next launch renders
  instantly and refreshes in the background; demo titles only appear if
  the live feed fails.
- Live search as you type (debounced), with result cards, type chips
  (Movies / Series / OVA), and a real "nothing found" state — no fake
  results.
- Detail pages with synopsis, seasons as separate posters with an
  in-detail switcher, and a canon-only watch-order guide for multi-season
  series. Live titles never show an invented episode count.
- A finger-trail easter egg, Nothing Weather style: hold, drag and lift
  on the header only. No hint text, no effect on poster scrolling.
- Downloads page (empty state for now) and a Settings page that already
  persists, ready for the engine: parallel downloads (1–5, default 2),
  speed cap (Unlimited–10 MB/s), download folder (with a folder picker),
  preferred quality (360p/480p/720p/1080p, default 1080p), Sub/Dub
  language (default Sub), Wi-Fi-only, ask-before-season, subtitles, and
  home particles toggles, plus Clear home cache.

## Build it yourself

You need JDK 17 and the Android SDK (compile SDK 37, build-tools 37).

```sh
# 1. Point Gradle at your SDK, e.g. create local.properties:
#    sdk.dir=/path/to/android-sdk

# 2. Build the release APK:
./gradlew assembleRelease

# 3. The APK lands at:
#    app/build/outputs/apk/release/app-release.apk

# 4. Install it:
adb install -r app/build/outputs/apk/release/app-release.apk
```

`local.properties` is intentionally not committed — it only holds your
local SDK path. Nothing in this repo is a secret: no API keys, no tokens,
no passwords, no signing keys. The debug signing key lives in your
`~/.android` folder, not in this repo.

## Project layout

```
app/src/main/
  AndroidManifest.xml            # INTERNET only
  java/com/js/anipull/
    MainActivity.kt               # entry point
    App.kt                        # the whole UI (~2000 lines, Compose)
    AniLab.kt                     # AniLab API client + poster cache
    DemoData.kt                   # 15-title demo fallback catalog
  res/
    drawable/                     # vector icons + launcher icon layers
    mipmap-anydpi-v26/            # adaptive launcher icon
    font/                         # Outfit Regular / Bold / ExtraBold
build.gradle.kts                  # root + app module (app/build.gradle.kts)
settings.gradle.kts
gradle.properties
```

## Version history

| Version | What landed |
| --- | --- |
| 1.0 | AniLab plugged in, edge-to-edge layout, demo fallback, honest "episode pull is next" detail state |
| 1.1 | 16-bug fix round: icon centring (first pass), real SVG logo in-app, vector icons, Editor's Pick rebuild, search clear button, focus handling |
| 1.2 | Outfit font, corrected icon centring, Editor's Pick rebuilt to the website's pick-tag, Weekend marathon picks, sticky logo header |
| 1.3 | Instant load (cached home + progressive refresh) and live-as-you-type search fix |
| 1.4 | Tap-away dismisses keyboard/focus, unmissable search clear button, updated hero copy |
| 1.5 | Full-width search cards, 48 drifting/glowing particles, continuous glowing trail line, focus-only pill border, Downloads + Settings pages |
| 1.6 | Sub/Dub chips, folder picker, thicker/less-laggy trail, How section removed, extra genre catalog rows (Action, Romance, Comedy, Fantasy) |
| 1.7 | Logo swapped to the v2 mark: pink diamond + two rounded chevrons + white arrow, dark launcher background |
| 1.8 | Themed/monochrome launcher icon simplified to diamond + chevrons only (no arrow) — in a single colour the white arrow merged into the white chevrons. Colour icon unchanged. *Phone check pending.* |

## Design notes

- **Icon rule:** a multi-colour mark must stay legible when flattened to
  one colour. The colour launcher icon and the monochrome (themed icon)
  layer are designed separately for exactly this reason — the monochrome
  layer is white-on-transparent, no pink square.
- **Metadata:** AniLab (`https://anilab2.amdapi.click/api`, no key) is
  the metadata provider. Its home/search endpoints return poster + id
  only, so titles are enriched one `/post` call at a time, and episode
  counts are never invented.
- **Sources, for later:** AnimePahe, AnimeTosho, AnimeOut. Pahe cannot be
  a Vercel backend (Cloudflare blocks datacenter IPs, verified live) —
  that is a website-repo problem, not an app problem; on-device, a WebView
  clearance + Kwik unpacking route is the planned first resolver step.

## License / use

Personal project, private repo. Don't redistribute builds as your own.
