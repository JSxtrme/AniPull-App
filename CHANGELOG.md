## 2.0-dev1.1 (2026-11-07, versionCode 52) — watch order, like the catalogs

JS's clarification: the watch-order row should be exactly like the home catalog rows — a horizontal scrollable row that depicts the watch order (5 seasons → S1 in slot 1, S2 in slot 2, and so on). Dev1.0 shipped it with a snap fling and non-tappable cards by mistake; this build fixes both. The row now free-scrolls with the catalogs' default fling (no snap), and each card is a real catalog card (PosterCard): tap opens that title's detail through the same openTitle path with a "watchorder-<id>" shared-element slot, so the poster flight and back behaviour match the catalog rows; download button and hold popup work too. Ordering is strict list order end to end — title.seasons comes from the AniLab JSON array in index order (no sort anywhere), the Frieren/AoT fallbacks are literal ordered lists, and LazyRow renders them as-is. R8 + shrinkResources stay on; no engine or clearance changes.

### New features (2)

- Watch-order cards are tappable — tapping a card opens that title's detail page with the same shared-element poster flight as the catalog cards
- Watch-order cards are catalog cards now — rating pill, 1080p pill, download button and hold popup, just like the home rows

### Bug Fixes (2)

- Watch-order row no longer snaps on flick — it free-scrolls exactly like the catalog rows
- Watch-order row renders strictly in watch order (S1 first, S2 second, and so on) — no re-ordering anywhere in the row

## 2.0-dev1.0 (2026-11-06, versionCode 51) — first 2.0 dev build

Downloads land officially, in stages. This build is the engine + UI foundation: the Pahe episode source is built behind a clean seam (search / releases / play-page parse, Kwik JsPacker, pahe.win → Kwik → direct mp4, HLS fallback), but it takes an already-obtained clearance cookie as input and never solves a challenge itself — with no clearance available, the episode section shows an honest "source connection pending" state, never fake episodes. The HLS engine (pure Kotlin, AES-128, per-segment resume, MediaExtractor/MediaMuxer remux) and the download engine (queue, parallel episodes, true pause/resume, foreground service + notifications) are fully working and do not depend on clearance. Verified: HLS playlist parser + AES decrypt + JsPacker unpack checked 15/15 on the JVM against real playlists/fixtures; the full HLS download + remux loop is verified by code inspection only (the VM's Java network gate blocks a live end-to-end run) — JS's phone test is the runtime proof. Queued HLS jobs resolve their links at download start (m3u8 links expire in 3–5 min); a job that waits minutes will fail with "link expired — retry". R8 + shrinkResources stay on.

### New features (9)

- Episode list in detail pages — episodes now load from a real episode source (AnimePahe parsing built in); while the source connection is still pending the section says so honestly instead of showing fake rows
- Watch order is now a horizontal poster line — swipe through the series in order; short orders just fit, with no scroll hint
- Downloads tab is real — active downloads with progress, MB/s, Pause and Cancel; completed downloads with file size, Open and Delete
- Library Downloaded section is real — completed downloads grouped by title with episode counts and a Download more action
- Download sheet wired — quality chips 360/480/720/1080, Sub/Dub toggle, per-episode download and Download All, with real progress once started
- HLS download engine, pure Kotlin — AES-128 segment decrypt, parallel segment downloads, per-segment resume (a retry only fetches missing segments), and a real MediaExtractor/MediaMuxer remux to .mp4 — no ffmpeg, no raw TS concat
- Download engine — queue with parallel episodes from your Downloads setting, true pause/resume (HTTP Range for direct MP4s), foreground service with per-episode progress notifications + MB/s + Pause/Cancel and a grouped summary
- Direct MP4 downloads — Referer header and Range resume from the resolved link
- Manual 'Check for updates' now shows a centered success popup with an animated checkmark when you're on the latest version

### Bug Fixes (5)

- Detail pages no longer show invented episode rows or the Frieren demo episode counts
- Watch order is no longer a numbered text list
- Downloads tab no longer shows an empty-state placeholder when downloads exist
- The download sheet no longer shows 'Copy-all-links' or 'Not wired yet' — episode downloads enqueue for real
- Download buttons on cards, rows and the carousel now open the download sheet instead of toasting 'Not wired yet'

## 1.9 (2026-11-06, versionCode 50) — Stable

The 1.9 dev line, cleaned up and shipped: every dev build from dev2 through dev5.8, deduplicated, audited against the full changelog (10 missing items restored, 5 half-covered lines completed — see STABLE-1.9-CHANGELOG-DRAFT.md v2). Optimised for stable: R8 enabled (minify + shrinkResources, conservative proguard-rules.pro keeping only the manifest-reached entry points), APK 10,181,235 bytes (dev5.8, no R8) -> 3,698,868 bytes (1.9 stable, R8) — 6,482,367 bytes saved (63.7%). Dex 21.65 MB raw / 7.42 MB compressed -> 2.56 MB raw / 1.27 MB compressed (single dex now, was two); resources.arsc 430,116 -> 125,608 bytes (shrinkResources); SF Pro font (2.33 MB) and both onboarding wallpapers unchanged — the font is now the largest entry at 43% of the APK. Signed with the stable release key, updates in place over 1.8 and over every 1.9 dev build (versionCode 50 > 49). Default update channel is now Stable for fresh installs (clean versionName, no -dev suffix); dev users on 5.8 update to this the same way.

### New features (33)

- In-app updater — update inside the app, no more manual APKs; Dev and Stable channels, chosen in About, with the default following the build you installed
- Update changelog card — New features and Bug Fixes sections with count pills, no arrows, both expanded by default, and a "Show all (N)" row for long lists
- Long-press any poster — a small sheet with Save to Library / Remove from Library, Download and Share; it disappears on scroll or when you tap elsewhere
- Library tab — Saved titles (they survive "Clear home cache") and a Downloaded section, with backup & restore of your Saved list to a file
- Onboarding on first launch — a Welcome screen and a notification-permission screen with real artwork
- Loading screen on every launch — pink/black/white gradient with the Orbit logo rising, and posters start loading while it plays
- New Orbit spark logo everywhere — launcher icon, themed/monochrome icon and the in-app header
- Editor's Picks — a 5-item carousel with auto-scroll, morphing dots and infinite looping; cards rebuilt with the title at the bottom, compact Download + Details buttons, rating/type/genre chips and a title shadow, descriptions removed from the cards altogether, and manual swipes stack the next card underneath
- Explore grid — a 3-per-row grid after the home catalogs that loads page by page as you scroll, with 2, 3 or 4 columns in Layout, complete rows only and a clean full final row; header uses a 2×2 grid icon now, and the "Keep scrolling…" sub-heading is gone
- More home catalogs — Most Favorite, Top 10 Today / Week / Month, plus genre rows expanded from 4 to 11; Weekend marathon picks cleaned up to title only
- "6000+ / Anime Titles" hero stat and quick chips — Movies, Series, OVA, Specials that filter live titles by type
- SF Pro Display Semibold headings across the whole app, with Outfit kept for body text
- Settings redesigned as a hub — Appearance, Layout, Downloads, Notifications, Storage and About, each its own sub-page with a large heading, icon rows and a logo + version footer
- Accent colours — a 10-colour grid plus Sunset and a custom HSV colour picker; light accents now keep buttons and the dock readable
- AMOLED Black and Light mode
- Translucent floating dock, on by default — a Nuvio-style morphing dock that shrinks to icons-only as you scroll, with one travelling highlight pill and a subtle press enlarge
- Transition speed options — Slow, Normal and Fast for the detail animation
- Crash Logs — view, expand, copy and clear crash reports from Settings
- Share Anipull — share the app from About
- Haptics toggle and Show Editor's Picks toggle in Layout
- Storage — poster cache size shown next to Clear home cache, plus Reset all settings (with a confirmation dialog)
- Download notification preview — a sample per-episode progress notification with Pause/Cancel, from the Downloads tab (real download notifications arrive with the 2.0 engine); the completion ping taps through to Downloads
- Downloads settings expanded — Auto-retry and Keep screen on, saved for the download engine in 2.0
- Search opens instantly on your cached catalog — "From your catalog" posters appear the moment you tap search, the hero, stats, chips and sources collapse in one smooth motion, and live search waits for a pause in typing and cancels the previous call, so fast typing fires one call
- Header redesign — it scrolls away with the page instead of sticking, with a bigger logo and tighter spacing; the welcome screens are scaled up too
- Small polish throughout — Settings pages slide in from the right, cards and the dock press-scale, dropdowns are beautified with descriptions, a proper pill back button, and white switch thumbs
- Reduce motion toggle in Appearance — turns off auto-scroll and press animations
- Dock behaviour dropdown in Layout — Always expanded, Always compact, or Adaptive (the scroll morph)
- Show Explore toggle in Layout — hides the Explore grid
- Editor's Picks auto-scroll toggle — turns the carousel auto-scroll on or off
- Home particles toggle in Layout
- SF headings toggle in Appearance — switch the Semibold headings on or off
- Notifications settings — progress and completion notification toggles

### Bug Fixes (26)

- Detail transitions rebuilt from scratch — the poster flies into place on a curved arc, home slides out with the empty slot left behind, there is no black screen at any point, the reverse mirrors it exactly, and hammering back and reopening can't break it
- First tap on any card now animates — it used to teleport to the detail on the first tap
- Detail info fades in from below only after the poster lands, and the detail title next to the poster stays fixed with no effects
- Editor's Picks corners stay rounded through the whole transition, and its rating pill and download button fade in instead of popping
- Opening a detail no longer replays its fade after you visit Settings and come back
- Fixed a crash when pressing back from a detail — the app no longer force-closes mid-animation
- The reverse animation no longer snaps halfway or lands on a duplicate card
- Posters no longer flash black when you go back to Home
- Rows stay exactly where you left them when you go back from a detail
- A detail opened from Library now closes on the system back gesture, instead of closing the tab first
- The dock stays translucent during transitions instead of going solid
- Particles freeze in place while you scroll or type, instead of vanishing and snapping back
- Scroll is smoother — posters decode at the size they're shown at, cancelled loads actually stop instead of running to timeout, section updates wait for scroll to stop, decodes are capped, and the poster cache is a smarter 100 MB LRU
- Rating, type and genre text now sits in dark translucent pills with a white border, so it's readable over any poster
- Search fixes — results and "From your catalog" grids clear the floating dock so the last row is never hidden under it; tapping search always starts at the top; the catalog reverse animation works even with the keyboard up; scrolling no longer closes search; the keyboard hides on scroll; skeletons match the results grid; old results stay visible while a new search loads; the footer no longer appears mid-screen on short lists; the X button now closes search outright
- The updater no longer downloads the APK twice when the install permission is off — it keeps the downloaded file and opens the install dialog as soon as you allow it
- The update sheet opens fully extended — header, changelog and buttons all visible, no manual drag needed
- The updater no longer sits at 0% while a download starts — it shows "Connecting…" until the first bytes arrive
- The updater checks the install permission before downloading, so it never wastes a download
- Skeletons now match the real layout, so cards no longer jump when content lands
- Settings subheadings are readable now — bigger, bolder and less grey
- Editor's Picks auto-scroll no longer advances while a detail is open, and the carousel resumes on the exact pick you opened
- Demo titles are gone — the app shows skeletons and honest empty states instead of placeholder titles, with a Retry button when loading fails
- Carousel auto-scroll no longer stops mid-way between two cards
- Search closes with the device back button, and by tapping the strip above the search pill
- Settings toggle descriptions no longer run into the toggle switch

## 1.9-dev5.8 (2026-11-06, versionCode 49) — Version bump only, to see the 5.7 redesigned card

No code changes except the version strings. 5.7 redesigned the updater changelog card (count pills on the right, no arrows, both sections expanded by default, "Show all (N)" row past 5 entries), but that code lives in the updater of the version you are updating FROM — so it can only be seen by updating FROM 5.7 to a newer build. This build exists only to give 5.7 something to update to. JS test, on 5.7 via the updater: sheet offers 1.9-dev5.8, changelog shows "New features" and "Bug Fixes" BOTH expanded, each with its count pill on the right and NO arrows; tapping a header collapses/expands it; tap Download & Install -> card shows "Connecting…" until bytes arrive, then the Downloaded X/Y — N% progress; Android dialog -> reopen = 5.8, About shows 1.9-dev5.8.
Signed stable key, in-place over 5.7.

## 1.9-dev5.7 (2026-11-06, versionCode 48) — Changelog card redesign (confirmed in chat first)

JS tested 5.5 -> 5.6 and disliked the changelog card: "the update popup card ain't looking good, remove the arrows and add the number of changes like this image" (ReVanced screenshot). He also asked that the redesign be shown to him in chat first and confirmed there instead of guessed again — an HTML preview of the card was shown and he CONFIRMED it with two changes: (1) no sub-heading/explainer text in the app (the preview's "Tap a section header..." line was preview-only; the app sheet never had it and still doesn't), (2) BOTH sections expanded by default — he takes back the earlier rule (New expanded, Bug Fixes collapsed).
Changes, all in UpdateSheet.kt: chevron/arrow removed entirely; count pill moved to the right edge and made prominent (larger, 14sp, like the screenshot's 24/69 pills); sections with more than 5 entries show the first 5 plus a "Show all (N)" row, with "Show less" to collapse back; header stays a real button (tap to expand/collapse, accessibility labels kept); icons/colours unchanged (star pink, bug glyph teal — Anipull's own, NOT the screenshot's). One-section rule unchanged: a build with only one list shows just that section, expanded. 5.7 has no bug fixes, so its sheet shows ONLY the New features section, expanded.
Sequencing note: this card code lives in 5.7, so the sheet offering 5.7 (rendered by 5.6's code) still shows the OLD card — a dummy 5.8 is needed afterwards so JS can see the new card by updating 5.7 -> 5.8.
JS test, on 5.6 via the updater: sheet offers 1.9-dev5.7; Android dialog -> reopen = 5.7, About shows 1.9-dev5.7.
Signed stable key, in-place over 5.6.

## 1.9-dev5.6 (2026-11-06, versionCode 47) — Version bump only, to test the 5.5 updater

No code changes except the version strings. 5.5 contains the updater polish (the "Connecting…" state while a download starts, and the New features / Bug Fixes changelog dropdowns), but those live in the updater code of the version you are updating FROM — so they can only be exercised by updating FROM 5.5 to a newer build. This build exists only to give 5.5 something to update to. JS test, on 5.5 via the updater: sheet offers 1.9-dev5.6, changelog shows "New features" expanded with its count badge and "Bug Fixes" collapsed with its count badge, tapping a header expands/collapses it; tap Download & Install -> card shows "Connecting…" (not "Downloaded 0.0 MB / ? — 0%") until bytes arrive, then the Downloaded X/Y — N% progress; Android dialog -> reopen = 5.6, About shows 1.9-dev5.6.
Signed stable key, in-place over 5.5.

## 1.9-dev5.5 (2026-11-06, versionCode 46) — Updater "Connecting…" state + changelog dropdowns

JS phone test on 5.4: "shows dev5.4 perfectly, that's green" — the Actions pipeline is proven end to end (5.3 -> 5.4 via the updater, stable-key signed, in place). Two updater polish items from that test, both built here:
1. "Sleeping" before download starts: after tapping Download & Install, progress sat at 0% for 2-3s even on fast internet, then finished in under a second. JS: "fix the sleeping problem before download starts". It happened on earlier updates too, not just 5.4.
   Root cause, found in code (not guessed): UpdateChecker.downloadUpdate() opens the github.com release URL, and HttpURLConnection follows a 302 to a second host (objects.githubusercontent.com) before the first byte arrives; onProgress only fires once a 64KB chunk is read. The 2-3s is connection setup + redirect, the 10 MB file itself downloads in <1s — so the bar was honestly showing 0% during the part where nothing had downloaded yet.
   Fix (UpdateSheet.kt only, download code untouched): a downloadStarted flag, set true on the first onProgress callback. While downloading and it is still false, the progress card shows "Connecting…" with an indeterminate pink bar; the "Downloaded X MB / Y MB — N%" text and determinate bar appear only once the first chunk lands. Permission pre-check, resume, SHA verification, sheet behaviour all identical.
2. Changelog dropdowns in the update sheet (JS + ReVanced changelog screenshot): replace the flat What's new / Bug fixes list with two collapsible sections — "New features" (the app's own star vector, pink, from the JSON "features" list) and "Bug Fixes" (a bug glyph drawn in the app's simple line style, Anipull teal, from "fixes"). Pattern borrowed from the screenshot only (section + count badge + expand/collapse); icons and colours are Anipull's own, NOT the screenshot's — JS added this explicitly for 5.5. Colours, typography (SF Pro Display Semibold headings, Outfit body) and card style stay Anipull's own dark/pink. Rules, exactly as JS locked them: both lists present -> New first and expanded, Bug Fixes second and collapsed; only one list present -> just that section, expanded, no empty section shown; both empty -> no changelog card at all. Section headers are real buttons (role + expand/collapse label). The sheet stays half-height-or-less (fillMaxHeight 0.55f, skipPartiallyExpanded = true from 5.2, header/buttons pinned, changelog scrolling inside) — not regressed.
JS test, on 5.4 via the updater: sheet offers 1.9-dev5.5, tap Download & Install -> card shows "Connecting…" (not "Downloaded 0.0 MB / ? — 0%") until bytes arrive, then the Downloaded X/Y — N% progress; changelog shows "New features" expanded with its count badge and "Bug Fixes" collapsed with its count badge (5.5 has both lists), tapping a header expands/collapses it; Android dialog -> reopen = 5.5, About shows 1.9-dev5.5.
Signed stable key, in-place over 5.4.

## 1.9-dev5.4 (2026-11-06, versionCode 45) — GitHub Actions automation + updates.json moves to the app repo
Note: workflow file .github/workflows/dev-release.yml was added via the GitHub web UI (API push of workflow files is refused with 403); this commit triggers the first Actions run.


JS approved: "set up GitHub Actions now, with the keystore as a secret". This is the FIRST Actions-built build.
1. GitHub Actions automation: new .github/workflows/dev-release.yml — on every push to dev/1.9 (paths-ignore updates.json, so the workflow's own updates.json commit does not loop) and on manual workflow_dispatch, it builds the signed release APK, verifies the signer fingerprint is the stable key (0b9c4b02..., job fails otherwise — in-place updates depend on it), creates a prerelease (tag = versionName, NOT latest release) with the APK attached, then regenerates updates.json with the real SHA-256 and commits it back with "[skip ci]". Re-runs are idempotent (same-tag prerelease deleted first, no duplicate release, no commit loop). Never touches main, never creates a stable release. First run is slow (cold cache: SDK + Gradle download) — that is build time only, the app itself is unchanged and just as fast.
2. Signing from env vars: build.gradle.kts now reads ANIPULL_KEYSTORE_FILE / ANIPULL_KEY_ALIAS / ANIPULL_STORE_PASSWORD / ANIPULL_KEY_PASSWORD when ALL four are set (fed from encrypted repo secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD), else falls back to the local signing.properties path exactly as before. No password is hardcoded or logged anywhere; the local VM build is unchanged and still produces the stable-key signature.
3. updates.json moves to the app repo: the file now lives at the repo root on dev/1.9, and the updater (UpdateChecker.kt) fetches https://raw.githubusercontent.com/JSxtrme/AniPull-App/dev/1.9/updates.json first, falling back to the old https://anipull.vercel.app/updates.json if raw fails or 404s. One push to dev/1.9 now carries code + update entry; no second repo, no Vercel redeploy wait. The initial updates.json committed with this build has a placeholder SHA (all zeros — fail-closed: a download against it fails verification rather than installing unverified); the Actions workflow overwrites it with the real SHA before anyone tests. TRANSITION: 5.3's updater fetches Vercel only, so the Vercel updates.json is ALSO pointed at 5.4 for the 5.3 -> 5.4 update; from 5.4 on, the updater reads the app-repo raw URL and the Vercel copy is legacy.
JS test, on 5.3 via the updater: sheet offers 1.9-dev5.4, Download & Install, Android dialog, reopen = 5.4, About shows 1.9-dev5.4. This also proves the Actions-built APK is stable-key signed and updates in place.
Signed stable key, in-place over 5.3.

## 1.9-dev5.3 (2026-11-06, versionCode 44) — Version bump only, to test the 5.2 updater fixes

No code changes except the version strings. 5.2 contains the updater permission-resume fix (keep downloaded APK, install dialog on return, no re-download) and the sheet fully-extended fix (skipPartiallyExpanded), but those fixes live in the updater code of the version you are updating FROM — so they can only be exercised by updating FROM 5.2 to a newer build. This build exists only to give 5.2 something to update to. JS test, on 5.2 with the install allow OFF: updater sheet must open fully extended (header + changelog + buttons visible, no drag), tap Download & Install -> permission screen -> turn allow on -> return -> download starts by itself, no second tap, no second download, then Android dialog -> reopen = 5.3.
Signed stable key, in-place over 5.2.

## 1.9-dev5.2 (2026-11-06, versionCode 43) — Updater permission resume + sheet opens fully extended

JS phone test on 5.1: updater worked perfectly ("Yoooo, the updater worked perfectly, I'm jumping in excitement lol"), search bugs fixed (catalog reverse + search scroll-to-top, both confirmed). Two updater issues left, both fixed here:
1. Permission resume re-downloaded the APK (JS, exact words): "when install unknown apps permission is not given, it starts downloading and finishes downloading and then redirects user to turn on that permission which is really good but what's not good is that after turning that on the app tells the user to tap download and install again. If both times the data is consumed then that is bad, anymex does the first part exactly like ours but once the user turns on the permission and comes back to the app, the app directly pops up the android update dialog box, but in our app i have to download and install again, rn it is fine since it's only around 9mb but that is bad presentation."
   Root cause, found in code (not guessed): 5.0/5.1's flow discarded the downloaded file on the permission failure — installUpdate() returned false, the code reset phase to Idle, showed "tap Download & Install again", and opened the allow screen with a plain startActivity (no result callback at all), so nothing ever ran on return; the next tap ran downloadUpdate() from scratch, overwriting the same cache file. The bytes were downloaded twice for one update.
   Fix (AnymeX's model, read from ~/workspace/anymex-src/lib/utils/updater.dart: download to a file, request the install permission, then install that SAME saved file on return — _downloadAndInstallAndroid downloads to tempDir/app_update.apk, then Permission.requestInstallPackages.request(), then InstallPlugin.installApk(savePath)): (a) the downloaded APK is KEPT — pendingInstallFile state in the sheet + the same file on disk (cacheDir/updates/anipull-update.apk), SHA-256 verified before any reuse (new helpers in UpdateChecker.kt: downloadedUpdateFile, sha256Of, usableDownloadedUpdate — the last only reuses a file found on disk when its SHA matches the JSON, so a stale/other-version file can never be installed; a mismatch deletes it). (b) The allow screen is now opened through an ActivityResultLauncher (rememberLauncherForActivityResult + StartActivityForResult, intent built by new unknownSourcesIntent() WITHOUT FLAG_ACTIVITY_NEW_TASK, which would have broken result delivery), so the sheet gets a real callback the moment the user comes back. (c) On return with the allow now on, Path A fires the Android install dialog IMMEDIATELY from the kept file — no second tap, no re-download. Path B (5.1's pre-download check: allow off before any download) auto-starts the download on return, also with no second tap. If the user comes back with the allow STILL off, nothing re-opens settings in a loop — the error stays, the file stays, and the next tap installs from the file instead of re-downloading. (d) The single entry point startDownloadAndInstall() is used by the button AND the return callback, so both paths share the reuse logic. Bonus coverage: if the sheet was dismissed or the app was killed while the user was in settings, the next tap finds the SHA-verified file on disk and installs it straight away. The ONLY legitimate re-download is a SHA mismatch (file deleted + error). 5.1's pre-download permission check is kept, not regressed.
2. Update sheet visual bug (JS + screenshot): "The card is not extended to the top, it can be manually extended tho." In the screenshot the sheet opens at ~half height, the What's new / Bug fixes card is cut off and the pink Download & Install button is partly cut off at the screen bottom edge, and the user can drag it up manually.
   Root cause, found in code: ModalBottomSheet's default is skipPartiallyExpanded = false, so the sheet opens at the ~50% partially-expanded anchor — shorter than this sheet's 0.55-height content, so the bottom of the content (changelog tail + buttons) is clipped until the user drags up to the expanded anchor.
   Fix: UpdateSheet now passes rememberModalBottomSheetState(skipPartiallyExpanded = true), so the sheet opens at the expanded anchor — its full intended ~half height, header + full changelog card + buttons all visible, no manual drag. Still a half-height-or-less sheet, NOT full-screen (fillMaxHeight(0.55f) unchanged); the changelog card still scrolls inside the sheet when its content is long, header and buttons still pinned.
Updater testable in ONE build (same pattern as 5.1, which worked): updates.json dev entry will point at the 5.2 prerelease APK (versionCode 43, correct SHA-256) once the prerelease exists — NOTE: updates.json is NOT yet updated to 5.2 (see §3 in the build report); do NOT point it at the release asset until tag 1.9-dev5.2 is live, or the updater 404s. Stable channel has NO entry, so Stable users are honestly told "no updates".
Verification, by construction + build (not by claim): audit passes (sheetState skipPartiallyExpanded, launcher callback paths A/B, pending-file keep + SHA verify on return and on tap, disk reuse, no NEW_TASK on the launcher intent, no re-download except SHA mismatch, 5.1 search fixes untouched). I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms: (1) updater sheet on 5.1 offers 5.2 and opens fully extended with buttons visible, no drag, (2) with the install allow OFF: download -> permission screen -> turn on -> return -> Android dialog appears straight away, no second download and no second tap, (3) same for the pre-download path (allow off before tapping Download & Install: return -> download starts by itself).
Signed stable key, in-place over 5.1.

## 1.9-dev5.1 (2026-11-06, versionCode 42) — Catalog reverse fix + search always scrolls to top + updater testable

JS phone test on 5.0, rest good, 2 bugs:
1. Catalog reverse STILL fails for the top two "From your catalog" posters when the keyboard is NOT dismissed before tapping — back pushes to Home, no reverse. Even after dismissing the keyboard by swiping a little down it still fails — seems like a threshold: user must scroll/swipe down a minimum amount before tapping for reverse to play.
   Root cause, found in code (not guessed), two parts: (a) the cached grid was driven by isSearchFocused — the TRANSIENT focus of the search TextField. Focus is lost/cleared by the LazyColumn tap-outside detector firing on a poster tap, by the pill being disposed when home is disposed during a detail, and by IME back / keyboard hide. 5.0 snapshotted isSearchFocused at tap time, so if focus was already gone at that instant the snapshot was false, back returned to plain Home rows, the "cached-" shared slot never existed, and the reverse flight had nothing to land on. Searched items never had this bug: a non-blank query drives their results regardless of focus. (b) The list kept Home's scroll offset when the composition switched to search mode, so the cached grid started displaced and its top posters were only laid out in the viewport after the user scrolled back up a minimum amount — JS's threshold.
   Fix: search MODE is now explicit state (searchMode, owned by AppInner) — set true when the pill gains focus, cleared ONLY by an explicit close (closeSearch). Focus loss alone never closes it. The cached grid, hero collapse, bottom spacer, home-only sections gating, and the back-restore all read searchMode, never focus. openTitle snapshots searchMode; goBackFromDetail restores searchMode (+ isSearchFocused for state consistency) with NO list scroll change — the tapped poster is already in the viewport at the scroll position search left it at, and scrolling to top on back would move a lower-row poster out of the viewport. The X clear button now calls onCloseSearch() explicitly (it used to close search by clearing focus, which no longer closes anything). Searched-items back, 4.9 row-scroll restore, tap-to-detail, hold popup and Library back untouched.
2. Tapping the search bar must ALWAYS scroll to the top first: if the user tapped search while scrolled down on Home, the hero collapse + cached grid happened ABOVE the viewport while the user stayed scrolled down by the same amount.
   Fix: on pill focus gain, scope.launch { listState.scrollToItem(0) } — an instant jump (no animation racing the 320ms hero collapse), a no-op when already at the top. Chip taps get the same scroll-to-top (same displacement). Applies from the top of the page or from scrolled-down Home alike.
3. Updater testable in ONE build (no split needed): updates.json is published at https://anipull.vercel.app/updates.json with the dev entry pointing at the 5.1 APK (versionCode 42, correct SHA-256). JS on 5.0 updates to 5.1 via the updater sheet, then tests bugs 1 + 2 in 5.1. Do NOT send the 5.1 APK in chat first — that would defeat the test. Stable channel has NO entry in updates.json yet (no newer stable than 1.8), so Stable users are honestly told "no updates" and never offered a dev build. NOTE (2026-11-06): the dev apkUrl is a temporary Muse file link (expires 2026-11-07 19:53 UTC), NOT a GitHub Releases asset yet — this environment has no release-create / asset-upload tool (no create_release in the GitHub connector, no gh auth), so the prerelease (tag 1.9-dev5.1) still has to be created in the browser with the APK attached, after which updates.json's dev apkUrl should be swapped to the release asset URL.
4. Updater refinement (from the 5.0 research report): 5.0 checked the install permission only AFTER downloading — a wasted ~10 MB download when the one-time allow is off. 5.1's UpdateSheet now pre-checks canRequestPackageInstalls() BEFORE downloading (error + opens the allow screen, no download started). Safe: this only changes 5.1's own updater for future updates; 5.0 on JS's phone does the checking for 5.1 with its own 5.0 code.
Verification, by construction + build (not by claim): audit passes (searchMode drives grid/hero/spacer/gating/back-restore, snapshot reads mode, X closes explicitly, focus-gain + chip scroll to top, no scroll change on back, pre-check before download). I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms: (1) updater sheet on 5.0 offers 5.1 and updates in place, (2) catalog top posters reverse with keyboard up, no swipe needed, (3) search bar from scrolled-down Home starts at the top.
Signed stable key, in-place over 5.0.

## 1.9-dev5.0 (2026-11-06, versionCode 41) — In-app updater + hold popup + search fixes + Settings additions (QOL, JS list)

JS 2026-11-04/06: updater IN 1.9 stable (not after), wait for his QOL list first, then build updater + QOL together. QOL list (2026-11-06): (1) hold popup LOCKED to 3 actions, (2) search grid dock + catalog back, (3) Settings top 3 + Share Anipull. Repo went PUBLIC 2026-11-06, which removes the private-repo blocker for the updater (public version JSON, no token). This dev build becomes clean 1.9 stable after JS tests it — first a dev build is necessary, do NOT ship stable yet.
1. In-app updater (AnymeX sheet + Nuvio channel, moderate): UpdateChecker.kt — public version JSON at https://anipull.vercel.app/updates.json, per-channel {versionCode, versionName, apkUrl, sha256, features[], fixes[]}; only versionCode > installed counts (no downgrade, dev never offered to Stable even though dev codes are higher than stable 1.8). Default channel follows downloaded build type on first launch ("-dev" in versionName -> Dev, clean -> Stable), sticky after that. UpdateSheet.kt — half-height bottom sheet (fillMaxHeight 0.55), home dimmed behind it, drag handle, pinned header (download icon in grey circle, "New Version Available", old -> new pills) + pinned buttons (Later dark left, Download & Install pink right), changelog card scrollable INSIDE the sheet (features / bug fixes groups). Flow: sheet on home entry after home loads (background check, never blocks loading) -> Download & Install -> button greys to "Downloading..." + progress card "Downloaded X MB / Y MB — N%" -> 100% "Installing..." -> FileProvider + ACTION_VIEW fires Android's "Do you want to update this app?" dialog (REQUEST_INSTALL_PACKAGES in manifest, one-time unknown-sources allow via settings if not yet granted) -> Update -> app closes, reopen = updated. NO silent auto-update. SHA-256 verified during download when the JSON provides one (mismatch = file deleted, download failed). Later snoozes that versionCode for auto-check; plain dismiss hides it for this launch only. About -> Update channel row + Stable/Dev radio dialog (Stable "Intended for everyday use.", Dev "Early updates that may contain bugs, break features, or behave unexpectedly."), Check for updates row — "No updates available — you're on the latest build" when the channel has nothing newer (stable user checking while only dev builds exist still gets this, not a dev popup), network failure says couldn't check, NOT "no updates"; manual check ignores the snooze. NOTE: updates.json does NOT exist yet — until JS/parent publishes it, the updater honestly reports no update. JSON shape is documented in UpdateChecker.kt; APK URL should point at a public GitHub Releases asset.
2. Hold popup (QOL 1): long-press any poster card (Home rows, Explore grid, search results, cached catalog, Library, Editor's Picks) opens HoldSheet — small bottom sheet, poster + title on top, EXACTLY 3 rows: Save to Library / Remove from Library (toast "Saved to Library" / "Removed from Library"), Download (opens the same download sheet as the detail page — still demo-only in 5.0), Share (Android share sheet with the anime title). NO Hide, NO Copy title, NO Open Details, NO Watch/Play, NO tracking status. Haptic on long-press (Haptics toggle, item 4). Tap-to-detail SharedTransition untouched (combinedClickable keeps the same press-scale + click path; only adds onLongClick). DISMISSAL (JS 2026-11-06, MUST): sheet disappears on scroll AND on tap elsewhere — implemented as a custom overlay in the same window, NOT ModalBottomSheet (a ModalBottomSheet lives in its own dialog window: scrim handles taps only, scroll gestures get swallowed, sheet would stay stuck). Scrim pointer input dismisses on the FIRST touch — tap (down+up) or first movement of a scroll attempt — and a swipe down on the card itself dismisses too.
3. Search fixes (QOL 2, JoJo screenshot): (a) search results + pre-search "From your catalog" grids had no footer (footer is home-only), so the last row ended under the floating dock with titles hidden and no way to scroll further — added a 140dp bottom spacer item in search mode so the last row fully clears the dock. (b) Detail from "From your catalog" cards: reverse animation didn't play, back pushed to Home. Cause — cached grid only shows while the search pill is focused (blank query), focus is lost when the detail disposes the pill, so on back home showed plain rows and the "cached-" shared slot was gone, leaving the reverse flight nothing to land on. Fix: openTitle snapshots searchOpenBeforeDetail (focused OR query non-blank); goBackFromDetail restores isSearchFocused when returning to a blank-query search, so the catalog grid and its shared slot are there for the reverse flight. Searched-items back was already correct (non-blank query drives results regardless of focus).
4. Settings additions (QOL 3): About — Update channel + Check for updates (see 1) + Share Anipull (locked message exactly: "Anipull — Download anime, no streaming, no tracking, upto 1080p, sub + dub. Check it out now" + https://github.com/JSxtrme/AniPull-App). Storage — poster cache size shown in MB next to "Clear home cache"; Backup & restore Saved library (export Saved list to a JSON file via CreateDocument, import it back via OpenDocument — local file only, no account; import replaces Saved list, dedupes by id); Reset all settings (confirm dialog, restores defaults, Saved library + home cache kept). Layout — Haptics toggle (long-press + dock tap, default on; dock uses a light KeyboardTap buzz, hold uses LongPress) and Show Editor's Picks toggle (default on; hides the whole carousel item when off). No account/sign-in, watch history, recommendations, or more Appearance options.
References read (code only, nothing copied verbatim): AnymeX long-press = rich peek preview (rejected — JS locked the small shonenX-style sheet instead); shonenX long-press = small context menu at finger position (model for the 3-row sheet); Nuvio About channel dialog + update rows (model for channel picker). Nuvio's scroll block NOT copied (known Anipull scroll-gap lesson).
Verification, by construction + build (not by claim): audit passes (updater JSON/channel/sheet/progress/install-handoff/snooze/manual messages, hold popup on all 6 card surfaces + 3 actions only + dismiss-on-scroll/outside-tap, search spacer + catalog back restore, Settings rows + share message + backup/reset/haptics/picks). I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms, especially: updater sheet + progress + install handoff against a published updates.json, hold popup on every card type + dismiss on scroll/outside tap, search last-row clearance, catalog detail back animation, and the new Settings rows.
Signed stable key, in-place over 4.9.

## 1.9-dev4.9 (2026-11-04, versionCode 40) — Bugs/glitches + Crash Logs (JS list, 9 items)

JS 2026-11-04 21:27 IST, next version 1.9-dev4.9, 9 items + crash-log UI style:
1. Dock goes solid during transition (translucent on): cause — hazeSource + dock translucent gated on screen==null, screen flips at tap before 300ms slide ends. Fix: gate on screen==null OR pageTransitioning, so sliding-out home dock stays translucent through transition.
2. Row resets after back (7th slot -> first 3, poster vanishes right): cause — PosterRow LazyRow state lived inside row, home disposed during detail, state died. Fix: AppInner-owned rowScrollStore (heading -> firstVisible index+offset), rows restore on back, save via snapshotFlow while scrolling, row exactly where left.
3. Easing options don't make sense (curve fixed): replaced hero_motion easing (Ease in/out, Overshoot, Bouncy, Cinematic) with 3 speed options in Layout settings: Slow 450ms, Normal 300ms, Fast 200ms. One duration (LocalTransitionDurationMs) drives page slide, poster arc (boundsTransformFor), post-landing fades, pageTransitioning, stale-key clear, detail fade — poster + page never drift.
4. Particles disappear on typing/scroll: old particlesOn = pref && !scrolling hid them and lost animTime, snapping to t=0 on stop. Fix: always drawn when pref on, ticker PAUSES on scroll/typing (particlesPaused), animTime freezes exactly where it is, resumes same spot, no jerk/snap. Culling: header is LazyColumn item, disposed off-screen, ticker cancels automatically.
5. Post-load lag: concrete, Nuvio/Coil-style — PosterCache LRU (accessOrder=true, evict least-recently-viewed, not oldest-inserted) + cap 50MB -> 100MB (home ~180 posters, 50MB evicted constantly on scroll-back forcing re-decode); PosterImage sync memory-cache init (also fix 7); particles frozen during scroll; row-state restore (no re-layout jump). Cloudstream/Dantotsu repos were not cloned locally (only Nuvio/AnymeX/ShonenX were), so claims are limited to those patterns, not a full port.
6. Library back gesture: cause — BackHandler checked showLibrary BEFORE screen, so first back closed tab (detail still on top, looked like nothing), second closed detail to Home. Fix order: sheet -> detail -> tabs -> search. On-screen BackPill already did detail first.
7. Poster -> black -> poster on back (screenshot, green circle = back gesture indicator, detail poster blank): cause — PosterImage always started null, even when in memory cache; reverse overlay uses freshly-composed home poster as flight child, so frame 1 blank, then load, then flight. Fix: initialise bitmap synchronously from PosterCache, cached poster present frame 1, no blank. Animation untouched.
8. Settings subheadings unreadable: SettingsSectionLabel was 11sp Faint grey, smaller/greyer than card content. Now 13sp Muted, ExtraBold, tighter tracking — still smaller than card titles (15-16sp) and main heading (36sp).
9. Crash Logs (2nd screenshot, DEBUGGING section): added to Settings hub, red-tinted icon box, same card/chevron style, beautified to app style (Bg, Card, Pink, 20dp). Functional: CrashLogs.kt, MainActivity installs uncaught handler on launch, writes crash synchronously to filesDir/crash_logs/ then delegates to previous handler (app still crashes normally, report appears next launch), page lists newest-first, expandable full stack trace, Copy, Clear all, Add test log (non-fatal, checks UI without crashing), logs only on phone, never uploaded.
First 4.9 build failed on missing imports (snapshotFlow, Text in CrashLogs.kt) — fixed, rebuild succeeded 2m 53s.
Verification, by construction + build (not by claim): audit passes all 9 (dock gate x2, row store, speed, particle pause, LRU 100MB, back order, sync cache, subheading, crash handler+screen), build succeeded. I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms all 9, especially row position on back, Library back gesture, and back poster blank glitch.
Signed stable key, in-place over 4.8.17.

## 1.9-dev4.8.17 (2026-11-04, versionCode 39) — Fade correction (JS) + stale-key re-fade fix

JS 2026-11-04 16:11 IST on 4.8.16: 'excellent job, u nailed it', but 4.8.16 fade was over-applied (his words, his bad on wording): (a) details tab title on RIGHT of poster shall be FIXED, no effects; (b) normal cards (except Editor's Picks) shall travel with rating, type and title — title below cards fixed, NOT faded, way in and way out; (c) Editor's Picks: KEEP fade in title/type below poster, title on right fixed (no effect), rating on top + download button on side of poster appear suddenly after tapping — make those fade in normally (no direction, normal fade).
Fixes, nothing else changed (4.8.16 flight, directional reverse, arc loop, carousel corners, loading preload/range all kept): (a) DetailScreen info Column (right title) — 4.8.16 delayed fade-from-below REMOVED entirely, column is weight + translationX(detailFade) only, fixed for all origins. Pre-existing whole-screen 300ms detailAlpha fade from 4.8.15 stays, as in approved build. (b) PosterCard title/meta — fade now ONLY when slot is carousel-origin AND tapped (DetailScreen poster opened from Editor's Picks, title/type below poster). All grid/search/explore/library cards and normal-origin detail posters: titleVisible true from first composition, alpha 1, translation 0, fixed below card, travelling with page slide, no fade in either direction. (c) Editor's Picks kept: carousel details Column fade-from-below after return unchanged. NEW: detail PosterCard overlays for carousel origin ONLY (top rating pill, download button, plus 1080p pill which pops the same way) — hidden at fresh composition, 300ms delay (landing), then 300ms alpha-only fade, no slide. Normal-origin posters already carry those overlays on home, so they travel with shared poster, no fade.
JS 2026-11-04 16:15 IST, second bug, same build: tap Death Note, back, Settings, Home, Death Note title/type/year fades from below AGAIN. Root cause: sharedPosterKey was set on tap and NEVER cleared, so a later tab return recomposed home fresh with stale tapped key and re-ran return fades. Note: 4.8.17 normal-title fix alone already kills the Death Note case, but the stale key would still re-trigger carousel details fade on tab return. Fix: LaunchedEffect(screen) clears sharedPosterKey 650ms after a return (300ms transition + 300ms fade + buffer, cancelled on reopen so rapid back-and-reopen keeps new key), and goTab clears it immediately (tab switch is not a detail return). Always-shared posters (4.8.16) do not depend on this key, so clearing cannot break first-tap flight.
Verification, by construction + build (not by claim): audit passes (detailInfo gone, right column fixed, title fade carousel-only, overlays fade carousel-only x3, carousel details kept, stale-key clear 650ms + goTab, always-shared + directional + arc + 20dp clip + loading preload/range still present), build succeeded 2m 27s. I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms: detail right title fixed, normal cards fixed below (in + out), Editor's Picks title/type below still fades, rating/download fade in normally (no slide), and Death Note (or any card) does NOT re-fade after Settings->Home.
Signed stable key, in-place over 4.8.16.

## 1.9-dev4.8.16 (2026-11-04, versionCode 38) — First-tap fix, directional reverse, arc loop, carousel corners, delayed details, loading preload

JS 2026-11-04 15:20 IST on 4.8.15: no black, animation good, with a catch and a bug: (1) FIRST tap on ANY card teleports poster to detail, no animation, second tap on same card animates, same for every new card, grid + carousel. (2) Editor's Picks reverse: poster morphs back to rectangle nicely, then instantly snaps to rounded corners and details appear instantly — add morph round corners + fade in from below details after return. Same going in: instantly becomes rectangle, then animates, details (rating on poster, type, title below poster) appear instantly. (3) Reverse page direction wrong: detail moves left, home comes from right, like infinite scroll — forward should push home left / detail from right, back should detail go right / home pull back (from left, mirrored, not same direction). (4) Loading: posters should start loading during loading screen, loading range 1.75-2.0s, no hard cap at 1.75, hard cap at 2.0. (5) Arc: going in anticlockwise, reverse clockwise to come back — going in should be clockwise too, mirror path on other side, keep reverse same, whole animation looks like a loop, don't change curve path.
Fixes: (1) First-tap cause: poster only became shared at tap time (sharedPosterKey change), so framework had no starting bounds on first tap and teleported; second tap worked because key was already that slot. Fix: ALL posters with a slot are ALWAYS shared (grid PosterCard + carousel PosterImage), pre-registered before any tap, unique slot keys, only tapped slot matches detail. (2) Directional AnimatedContent: forward (target non-null) slideIn right / slideOut left, reverse (target null) slideIn left / slideOut right — mirrored, not infinite scroll. (3) Arc loop: BoundsTransform now direction-aware by target.top vs initial.top — forward (to detail, top smaller) ArcBelow (mirrored), reverse (back home, top larger) ArcAbove (kept), same 300ms easeInOutCubic, only side flips. (4) Carousel corners: shared PosterImage now clipped 20dp INSIDE shared element (was unclipped, parent card clip didn't travel, so it became rectangle in flight), and detail poster for carousel slots is 20dp (grid stays 16dp), so corners stay rounded throughout, no snap. (5) Details delayed fade from below: detail info Column (title/type/score/genres) — 300ms delay after transition start, then 300ms fade + 20dp slide up, so info appears after poster lands, not instantly. Tapped grid title/meta and tapped carousel details — same pattern on fresh composition after reverse (initial hidden if tapped, 300ms delay, then 300ms fade + 16dp slide up); untapped cards and forward outgoing cards unchanged. (6) Loading: preload featured + first section (10) + second section (5) posters to disk cache the moment sections land DURING loading screen (fetch already runs during loading), so home PosterImage finds them on disk instantly; loading timer min 1.75s, then up to 250ms more waiting for at least one poster on disk, hard cap 2.0s total.
Verification, by construction + build (not by claim): audit passes (always-shared grid/carousel, directional slide both ways, directional arc ArcBelow/ArcAbove, 300ms easeInOutCubic unchanged, carousel 20dp clip + detail 20dp carousel, delayed detail/grid/carousel fades, loading 1750+250 + preload), build succeeded 3m. I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms: first tap animates (grid + carousel), reverse page mirrors (not infinite scroll), arc loop clockwise both ways, carousel corners stay rounded, details fade in from below after landing (forward + reverse), loading 1.75-2.0s with posters ready.
Signed stable key, in-place over 4.8.15.

## 1.9-dev4.8.15 (2026-11-04, versionCode 37) — SharedTransition, animation rebuilt from scratch

JS 2026-11-04 13:38 IST: 4.8.14 animation doesn't even happen, want a SIMPLE animation: tap card -> it starts animation -> behind it home flies left (home flies out with the empty space left by the card) -> details push out home and take home's place and come with a gap for the poster to fly into and sit in -> Editor's Picks same (home flies left and out with the gap left by the card now flying) -> only difference is smooth morph to card dimensions while flying, as in 4.8.7-hotfix, then sit in details page which pushed home out -> NO black screen at all -> reverse on back. Take your time, start from scratch (animation part only, not whole app), use any skill/repo, even if it takes an hour, plenty tokens.
This build does exactly that, with NO manual flight left: (1) SharedTransitionLayout + AnimatedContent, targetState = screen, slideIn from right + slideOut to left, 300ms easeInOutCubic — AnimatedContent renders BOTH pages during the slide, so home + detail always cover the screen, no gap, no black, by framework design, not by manual offsets. (2) Poster-only sharedElement: grid PosterCard poster Box, carousel PosterImage only (title/buttons/gradient slide out with home, not shared), detail PosterCard poster Box — all with the SAME slot key (search-/cached-/explore-/library-/carousel-/grid-Heading- + id, unique per slot, so no duplicate key in home content). Framework hides the poster in place during transition (empty slot in home, gap in detail), flies it in the overlay with PosterBoundsTransform (keyframes, ArcAbove bulging up, 300ms easeInOutCubic, same as page slide, so poster + page never drift), and lands it in the detail 118x177 slot. No captured rects, no PosterCard replica, no hiding alpha, no pre-measure, no HeroFlightOverlay (function DELETED), no pageAnim, no pendingTitle, no pageClosing. (3) Spam-proof by framework: taps just flip AnimatedContent targetState, framework interrupts and continues from current value. Carousel frozen for 350ms after every screen change (pageTransitioning flag), so a carousel auto-advance cannot lose the tapped slot mid-reverse. (4) openTitle/goBack are now trivial: set shared key BEFORE screen, screen = t / screen = null, nothing else.
Verification, by construction + build (not by claim): code audit passes (no manual overlay, no pageAnim/pendingTitle/pageClosing in code, SharedTransitionLayout + AnimatedContent + slideIn/slideOut + shared scopes provided + grid/carousel/detail sharedElement + arc bounds + carousel freeze + unique slots), coverage math for p=0,0.25,0.5,0.75,1.0 (outgoing [-p,1-p] + incoming [1-p,2-p] cover [0,1]), build succeeded 2m 30s. I cannot run this on a phone here (no emulator/KVM), so runtime verification is JS's phone test — task NOT done until JS confirms: animation happens, home flies out with empty slot, detail arrives with gap, poster morphs and sits, reverse same, no black, no balloon, for grid AND Editor's Picks.
Signed stable key, in-place over 4.8.14.

## 1.9-dev4.8.14 (2026-11-04, versionCode 36) — AnymeX slide, NO manual hero flight at all

JS 2026-11-04 13:15-13:17 IST: Editor's Picks STILL enlarges after tap (screenshot: Violet poster full width, ~568dp tall, top area empty black), it was good before shonenx was mentioned in 4.8.7-hotfix, black screen STILL shows after tapping a card, and: try AnymeX if shonenx is hard to replicate, and do NOT stop until black is gone and Editor's Picks are fixed — verified, not claimed.
AnymeX, read from ~/workspace/anymex-src (not guessed): SmoothSlideTransition (lib/widgets/animation/page_transition.dart) — incoming Offset(1,0)->0, outgoing Offset(0)->(-0.3,0), easeInOutCubic, 300ms. Outgoing slides only 30%, NOT fully off like shonenx (-1,0). Hero is poster-ONLY, flightShuttleBuilder returns just the poster image (Material transparency, hero.child), NO meta, NO PosterCard replica, NO captured rects — Flutter's Hero framework interpolates the real layout sizes, so it cannot balloon.
This build copies AnymeX, and REMOVES the manual flight that kept breaking: (1) Home slides -0.3, detail slides 1->0, easeInOutCubic 300ms. Home right edge = (1-0.3*p)*W, detail left edge = (1-p)*W, home right > detail left for all p, so home+detail ALWAYS overlap and cover the screen — NO gap, NO black, even if home offset is imperfect, because home is UNDERNEATH detail, not beside a gap. Shonenx -1.0 required home to actually exit as a page, which is what kept failing in Compose. (2) NO hero overlay at all — HeroFlightOverlay is NOT called (function left unused for a future SharedTransition replacement, do NOT re-enable it without SharedTransition). No captured boundsInWindow rect, no PosterCard height = width*1.5 path, so the 80%-screen Violet poster CANNOT happen: detail poster is just a normal 118x177 PosterCard in DetailScreen, sliding in with the page at its real layout size, and home poster stays visible until detail covers it (no hiding, pageHiddenId=null, heroPosterHidden=false). (3) Haze off while detail open, as in 4.8.13.
Verification, by construction + build (not by claim): code audit asserts overlay NOT called, home -0.3, detail (1-p), hiding OFF, and the coverage math above for p=0,0.25,0.5,0.75,1.0. I cannot run this on a phone here (no emulator/KVM on this VM), so runtime verification is JS's phone test — this task is NOT marked done until JS confirms black gone and Editor's Picks fixed on his phone.
Signed stable key, in-place over 4.8.13. Uploaded APK checked: user-sent anipull-1.9-dev4.8.7-hotfix APK SHA d3e34d10 matches our hotfix exactly, so that is the known-good reference.

## 1.9-dev4.8.13 (2026-11-04, versionCode 35) — Black home + balloon + straight-line flight, root-cause fix

JS 2026-11-04 12:47 IST, after 4.8.12: home STILL goes black except the tapped card, detail (green arrow) comes from right pushing the black screen out, our card flies in a straight diagonal (red arrow), shonenx flies in a curved arc (his image 1), and Editor's Picks STILL balloons to ~80% of the screen, showing only the top half of the poster, then snaps back, same on reverse.
Root cause, found in code, NOT the offset mechanism: AnipullAppInner read pageAnim.value at the top of composition, so the WHOLE home LazyColumn recomposed every animation frame during the 300ms flight. Posters reloaded / LazyColumn janked every frame, home went black, and the overlay (in the same composition) lagged behind the layout-driven page slide and snapped at the end — a stale/wrong rect in that lagged composition could then fall into the grid PosterCard path (full-width card, height = width*1.5) and balloon.
Fix: (1) NO top-level pageProgress read in AnipullAppInner. pageAnim is read only in layout lambdas (Tab offset, Detail offset — layout invalidation, not composition), in derivedStateOf (pageHiddenId, heroPosterHidden — notify only when the result changes, i.e. at flight start/end, not per frame), and inside the new isolated HeroFlightOverlay (only the overlay recomposes per frame). Home/detail stay frozen and visible during the whole push. (2) Haze source + translucent dock are OFF while a detail is open (screen != null), so Haze capture/blur cannot interfere with the moving pages; dock is solid for 300ms, then translucent again on home. (3) Hero flight is now a CURVED ARC, not a straight line: Flutter Hero MaterialRectArcTween — centre travels on a circular arc bulging up (perp to the straight line, sin(pi*pr), 0 at both ends, 1 in the middle, max 60dp), so reverse retraces the exact same arc. This is the swoop JS drew for shonenx, versus our old straight diagonal. (4) Overlay size is HARD-CAPPED to max(src,dst) in both dimensions — a stale rect can no longer balloon beyond the larger of src/dst, and carousel stays poster-only to detail poster size (118x177), never a grid PosterCard balloon, with meta fading inside the poster on both directions.
Signed stable key, in-place over 4.8.12.

## 1.9-dev4.8.12 (2026-11-04, versionCode 34) — Continuous push, no black screen + Editor's Picks unified flight + readable pills

JS frame-by-frame (screen recording, every frame): tap -> screen goes black -> tapped card appears on black exactly where it was on home -> card flies to detail as details fly in from right into black -> back: details fly out right revealing black again, card flies back on black, then black goes out and home loads. Shonenx, by contrast (his 2nd image, green seam): NO black, home is pushed left by details, details take home's place like swiping Instagram photos/posts, home + details are one continuous long page, card flies on home while home pushes out, then takes its place in detail, all together.
Root cause, found in code: home was moved with graphicsLayer (draw-only translation), detail with layout offset — two different mechanisms, and in practice home did not move as a page (LazyColumn + Haze inside a graphicsLayer-translated Box), so detail slid over black, not over home. Fix, matching shonenx AppPageTransition exactly (incoming Offset(1,0)->0, outgoing Offset(0)->(-1,0), easeInOutCubic, same progress): home now moves with LAYOUT offset too (-progress*W), detail with (1-progress)*W, seam exactly at (1-progress)*W, strip always covers the screen, no gap, no black. Card overlay still flies src->dst on the same progress, landing as the strip arrives.
Editor's Picks, still glitching in 4.8.11: flight is now forced by src width, not flag alone — a full-width src (>200dp) is always a carousel poster-only flight to detail poster size (118x177, same as grid cards), never a grid PosterCard that balloons to width*1.5 tall. Carousel card now hides WHOLE during flight (title/buttons were floating on a dark card when only the poster hid), and overlay meta (rating/type/genres) now fades inside the poster on BOTH directions with 1-pr, shrinking/growing with the poster to/from grid-card size.
Readability: format/genre/rating were gray/pink text straight over bright posters. Now every meta item sits in a dock-like translucent pill — Black 0.60 + white border + white text — rating pill, type pill, genre pills, in carousel, in overlay, and detail format/genres are no longer gray (TextMain white). Bottom gradient strengthened 0.65 -> 0.78 for legibility.
Signed stable key, in-place over 4.8.11.

## 1.9-dev4.8.11 (2026-11-04, versionCode 33) — Editor's Picks: fixed snap + stacked manual swipe + Nuvio-style meta

JS on 4.8.10: normal cards are nailed, Editor's Picks were left out — tap in and back out snapped after showing a huge poster (screenshots: detail sliding right, title cut off, a full-width tall Violet poster filling the screen, then snap to the 275dp card).
Root causes found in code:
- Carousel Details button never set tap bounds (card tap did, the button didn't, nested clickable wins), so a Details tap flew with a stale / missing src, and openTitle only set the carousel flag when src != null, so the flag could stay false from a previous grid tap. Flight then took the grid PosterCard path with a carousel-sized src, and PosterCard height = width*1.5, so a full-width src became a huge tall poster overflowing its box, then snapped to the real card at landing. Fixes: Details button now sets bounds exactly like card tap; openTitle always sets the carousel flag for a new title; grid overlay is now clipped to its lerped box, so a PosterCard can never overflow to huge again even if a src is wrong.
- Editor's Picks rework, as JS asked: manual swipe only — the card underneath sits slightly to the side (+24dp), slightly down (+16dp), slightly smaller (0.92) and faded (0.65), easing to full size / full alpha / centred as it becomes current, current card on top (zIndex). Auto-scroll is untouched (autoScrolling flag distinguishes it from a manual drag), so auto still slides normally, no stacking.
- Title shadow: centred drop shadow (black 0.85, offset 0,2, blur 10) on Editor's Picks titles, and same in the reverse overlay, so titles stay legible over any poster.
- Under the title, left-aligned (NOT centred like Nuvio): rating + type line (★ score in pink, • type in muted) and up to two genre chips (translucent white, white border, 12sp) — Nuvio-style info from its VisionQuest card, in our left-aligned layout. Hidden gracefully when score/type/genres are missing.
- Signed stable key, in-place over 4.8.10.

## 1.9-dev4.8.10 (2026-11-04, versionCode 32) — shonenx EXACT transition + scroll-during-loading fix

JS, on 4.8.9, rightly frustrated: details appeared out of nowhere, poster tried to go to the previous spot, details arrived from the right then magically snapped after going black. And separately: scroll is still laggy while loading, stable once everything finishes, and still day-and-night vs Nuvio.
Root causes, found in code, not guessed:
- 4.8.9 captured the detail poster rect DURING the slide, when the detail page was off-screen / partially visible, so the overlay either never appeared (black empty slot, poster snaps in at the end) or appeared late at an intermediate rect. Fix: pre-measure on the FIRST ever tap only — a hidden detail page at final position (alpha 0, offset 0) for one frame captures the true final rect, THEN the slide starts with dest already known. Detail poster position is constant (118dp, fixed header), so that dest is cached forever after first capture — every later tap slides immediately with overlay from frame 1. No more re-measuring per tap, no more previous-spot stale rect.
- Shonenx exact, from its own code (lib/core/theme/app_theme.dart AppPageTransition + go_router): 300ms, easeInOutCubic (CubicBezier 0.65, 0, 0.35, 1), slide in Offset(1,0)->0, slide out Offset(0)->(-1,0). 4.8.9 used 400ms FastOutSlowIn and a static home — wrong curve, wrong duration, wrong home motion. Now: 300ms easeInOutCubic for page + poster (single progress, as before), AND home slides left as detail slides in, exactly like shonenx's outgoing page. Hero is still poster-only (CardThumbnail Hero wraps SmartImage only, tag per media), detail info slides with the page, not separately.
- Scroll during loading: poster network delay 120ms -> 300ms (disk/memory hits still instant), so fling cards never start a network fetch + decode at all — Nuvio's "takes its sweet time" behaviour, on purpose. Plus Explore batches now wait for scroll stop before applying, same rule home/extra sections already had. Decode was already off-main-thread (IO, semaphore 4), so the lag was network + GPU upload + mid-scroll recompositions competing with scroll frames, not decode on Main — once cached, all that competition disappears, which is exactly why scroll stabilised after loading.
- Signed with the stable key, updates in place over 4.8.9. Nuvio gap is still real and will not close in one build — Nuvio uses Coil with prefetch + a much smaller initial home set, Anipull loads 9 home rows + 11 genre rows + Explore progressively — but this build removes the two biggest Anipull-specific jank sources (mid-flight dest capture and mid-scroll batch applies).

## 1.9-dev4.8.9 (2026-11-04, versionCode 31) — shonenx page transition, spam-proof

JS, on 4.8.8: shonenx is spam-proof, its WHOLE details page slides in from the right like a player over home, poster flies into an empty slot, and hammering back + same poster fast never breaks it. Ours instantly appears/disappears, and is not spam-proof. He is right — and yes, it can be copied in Compose, by copying Flutter's structure, not its code: ONE AnimationController drives BOTH page slide and Hero flight.
Rework, not a patch:
- Home/tab is ALWAYS mounted underneath now. Detail is a page OVER home, sliding in from the right (offset = (1-progress) * screen width) and sliding out to the right on back, revealing home underneath — no more instant appear/disappear, no more home popping in at the end. Dock is also always rendered, covered by the opaque detail page while open, revealed during slide-out.
- ONE Animatable, pageAnim, drives page slide AND poster flight with the same progress value (forward 0->1, reverse 1->0). No separate forward/reverse heroes, no 0.999 clear, no timeout guard. A tap mid-flight just flips the target: back during opening slides back out from the current progress, same-poster tap during closing slides back in from the current progress — never a jump, never a half-cleared state, never a stuck overlay. That is the spam-proof part, and it is why rapid back/open/back/open stays smooth.
- Empty slots on both sides, Flutter Hero style: during flight only the POSTER is hidden (home card title/buttons stay, detail poster slot stays empty), so the overlay poster is the only poster, flying into an empty slot in the detail page on forward, and back into an empty poster slot on home on reverse.
- Carousel reverse info still flies in from the right during flight; grid overlay is still the full PosterCard. Detail info motion is now the page slide itself, so the separate title slide is gone (it would have double-slid).
- Signed with the stable key from 4.8.8-stablekey, so this updates in place over that build — no uninstall.

## 1.9-dev4.8.8-stablekey (2026-11-03, versionCode 30) — stable signing key, same code as 4.8.8

Route A, JS-approved: a single stable release keystore (alias anipull, RSA 2048, valid 10000 days, generated on this VM with keytool) now signs every build. Wiring: build.gradle.kts reads the 4 signing values ONLY from signing.properties in the app folder on this VM; that file + release.keystore are NEVER pushed to GitHub (push-files uses an explicit file list, and neither file is on it). If signing.properties is missing, the build falls back to the debug key so a fresh checkout still builds. Backup files given to JS: anipull-release.keystore + anipull-release-key-backup.txt (passwords inside that txt only, never in chat, memory, or repo). One-time cost: this build will NOT install over debug-signed 4.8.8 — uninstall com.js.anipull first, then install this, then every future build updates in place.
## 1.9-dev4.8.8 (2026-11-03, versionCode 29) — reverse rework from AnymeX / shonenx

JS: reverse still snaps halfway through — flies back, then skips and snaps to its place. And he pointed at AnymeX (RyanYuuki) and shonenx (roshancodespace) as the smooth reference, especially shonenx's info flying in from the right.
Checked both repos (cloned, ~/workspace/anymex-src, ~/workspace/shonenx-src): both are Flutter, and both do the same thing — a native Hero for the poster ONLY (same tag home + detail), destination hidden for the whole flight, overlay fully opaque for the whole flight, swap only at landing when rects match exactly, and detail info sliding in as part of the page transition (shonenx also has StaggeredFadeIn: fade + slide, easeOutCubic, 500ms, 50ms stagger). Anipull's manual overlay was breaking all three Hero rules, and the halfway snap was ours, not the phone's:
- The 4.8.7-hotfix crossfade started at pr=0.8, so the destination card appeared WHILE the overlay was still 20% away — a card snapping into the slot mid-flight, then the overlay fading out under it. That is the halfway skip. Removed: destination is now fully hidden (alpha 0, still clickable for re-tap) for the whole flight, overlay fully opaque, swap only at pr=1.
- Reverse was animateFloatAsState + a clear at >=0.999 + a 1500ms timeout guard — three ways to cut the flight short (bouncy springs were cut at 0.999, a slow device could hit the 1500ms guard mid-flight). Now it is ONE Animatable, like Flutter's AnimationController: snapTo(0), animateTo(1, heroSpec), clear only after full settle. Safety fallback is now 3000ms, far beyond any 450-700ms flight.
- Destination rect was stale tap-time only. Now the ONE destination card reports its live bounds on placement (safeBounds, single card, once — not the old every-card-per-frame crash source), and the overlay target follows it if LazyColumn settles to a different Y than the tap-time rect. That stale-rect mismatch was the other snap source.
- Carousel reverse: overlay was poster-only, so name + Download/Details popped in together at landing. Now they fly in from the right DURING the flight (alpha = pr, 80px right -> 0), so at landing the overlay already matches the destination card exactly.
- Forward info, shonenx-style: detail title/type/rating now slide in from the RIGHT (40dp -> 0) while fading, instead of sliding up or just fading in place.

## 1.9-dev4.8.7-hotfix (2026-11-03, versionCode 28) — empty slot regression, not a new feature

JS, on 4.8.7: the slot the card came from is no longer empty for it to land back on — the 4.8.5 slot-clear fix regressed. He is right, and it is 4.8.7's own doing: to fix the pop and the re-tap dead zone, 4.8.7 made the destination card fade in linearly with reverseProgress, so it was visible (faint, then full) throughout the flight, and the overlay landed on a duplicate.
Fix, keeping all three things at once: destination card alpha is now a crossfade, not a linear fade — 0 for the first 80% of the flight (slot truly empty, overlay is the only poster, exactly like 4.8.5), then 0 -> 1 over the last 20% while the overlay fades out (overlay alpha = 1 - crossfade). At landing the card is already fully visible, so there is no pop, and the card was always composed and clickable (alpha 0 does not disable clicks), so the 4.8.7 re-tap fix survives: tapping the empty-looking slot mid-reverse still reopens the detail. Grid and carousel alike.
Plus, same hotfix, Editor's picks transition (JS, second report):
- Carousel pager page is hoisted to App, not remembered inside the carousel. The carousel is disposed while a detail is open, so its local pager reset to page 500 (pick 0) on every back — reverse then flew to a slot holding a different pick, and the opened pick's name + Download/Details buttons jerked back into place when swiped to again, worse on back than on open. Hoisted, the carousel resumes on the exact pick that was opened, so the reverse destination actually matches.
- Detail title/type/rating no longer appear instantly at 20% on opening: detailFade is now heroProgress itself (0 -> 1), not 0.2 + 0.8*heroProgress, so the title slides up (already in 4.8.7) while fading from invisible, instead of popping in at 20% opacity. Same on back via the crossfade above: carousel name/buttons fade in with the card, not all at once at landing.

## 1.9-dev4.8.7 (2026-11-03, versionCode 27)

- Nuvio-inspired poster loading (scroll): network poster fetches now wait 120ms first, and only if the poster is not already in memory/disk cache. Delay() is cancellable, so a card that flashes past in a fling is disposed before its fetch ever starts — it never queues a decode for a card that is already gone. After the fling settles, 120ms is imperceptible. Memory/disk hits still load at once, no delay. Plus Coil-like cancellation in loadPoster: the HttpURLConnection is disconnected when its coroutine is cancelled, and ensureActive() is checked before network, after read, and before decode/cache — before, a cancelled load kept running (blocking read, holding a decode slot) until its 8s timeout, which is part of why Anipull "tried hard" to keep up while Nuvio just dropped cancelled loads. This is the middle ground JS corrected: not wait-for-settle, not chase-every-card — some posters load, some stay placeholders during a hard fling, and they fill in after.
- Ease in motion (Settings > Layout > Poster transition motion): slow start, then speeds up into place (FastOutLinearIn, 550ms) — the mirror of Ease out. Same spec drives forward and reverse.
- Dropdown beautified (Layout page, all three dropdowns): Card field with Pink border when open, chevron that rotates 180°, menu in Card with 16dp corners and shadow, selected row tinted Pink with a check, and a subtitle under every option explaining it. The old raw Material3 menu (default surface, no selected state, no descriptions) is gone.
- Editor's picks smooth title (forward + reverse): detail title now slides up 20dp while it fades in during the hero (detailFade-driven), instead of just appearing at its final spot. Reverse destination cards (grid and carousel alike) no longer flip from an empty slot to a full card — they stay full cards throughout reverse, fading in with reverseProgress underneath the flying overlay, so at landing they are already fully visible and there is no pop. Carousel title/buttons fade in with the card, not suddenly after it.
- Re-tap dead zone fixed: destination cards and the reverse overlay itself are clickable throughout reverse. Tapping the same card during reverse reopens its detail instantly (no hero, no lost tap); tapping a different card cancels reverse first, then runs its normal hero. Before, the destination was an empty slot with no clickable and the overlay had a no-op click, so a tap right after backing out was lost and the user had to tap again — most noticeable on Editor's picks, whose hidden slot was a whole 275dp box.
- Signing key 51f74e55, SAME as 4.8.6/hotfixes, updates in place.

## 1.9-dev4.8.6-hotfix2 (2026-11-03, versionCode 26) — reverse overlay visual bugs, not a 4.8.7

JS, on the (non-crashing) hotfix: reverse has the same two bugs forward hero had early on, and they are the hotfix's own doing:
1. Poster flies back at full-card height, then snaps to poster height when the real card appears.
2. Title/type/rating do not travel with it — they pop in at the end.
Cause: the first hotfix made the reverse overlay poster-only for grid AND carousel, so PosterImage filled the whole card box (233dp) instead of the poster area (177dp), with no title/meta at all. Fix: reverse overlay is full PosterCard for grid sources again, exactly like forward hero — its height is cardWidth*1.5 + title/meta, which is exactly the lerped rect height, so no snap and the title travels. Carousel stays poster-only, because a carousel card is not a PosterCard (its poster already fills the card). Restoring the card is safe: the logcat-confirmed crash was in home cards' onPlaced reverse check, not in the overlay card, and the overlay card reports no bounds, so its onPlaced only stores coordinates. Signing key 51f74e55, SAME as hotfix/4.8.6, updates in place.
Observation from JS, not built yet: after a very fast/hard scroll, Nuvio (screenshot) still shows empty poster placeholders — it takes its sweet time loading posters, while Anipull tries very hard to keep up with the fling. That lazy-during-fling behaviour is a strong scroll-smoothness lead: deferring poster decodes until scroll settles (or a short delay so cards that flash past never decode) would trade a moment of placeholders for less decode/GPU work during the fling. Not built here — needs JS's call, since it changes poster-appear timing.

## 1.9-dev4.8.6-hotfix (2026-11-03, versionCode 25) — reverse crash hotfix, NOT a 4.8.7

JS, on 4.8.6: app force-closes when pressing back from a detail — animation starts, then the app closes before the overlay lands in the empty slot. Twice, not a one-off phone glitch. No stack trace was available from the phone, so this hotfix removes every risky reverse mechanism by construction instead of patching one line:
- Slot-clear is now by ID, not by position: the destination card hides because its id matches reverseHiddenId, with no boundsInWindow(), no LaunchedEffect, and no state set from onPlaced. Prime crash suspect was exactly that — bounds read + state set during layout, for every visible card, on every back. Trade-off, stated plainly: every copy of that title hides for the 0.45s flight, not just the one slot. Position-matching can return once a logcat confirms the real culprit.
- Reverse overlay is poster-only for grid AND carousel alike: no full PosterCard in the overlay (no interaction, press scale, title/meta, or its own onPlaced).
- Clearing is now single-path: tab switches clear the reverse synchronously in goTab()'s tap handler, not in a separate LaunchedEffect(currentTab) that could fire mid-composition. Progress and timeout clearing remain, and all three clear reverseHiddenId too.
- Guard: a reverse overlay with a zero, negative, or non-finite rect is skipped, not rendered.
- Nothing else changed: same hero motions, same scroll code, same dock, same settings. Signing key 51f74e55, SAME as dev4.8.6, so it updates dev4.8.6 in place.
- Root cause CONFIRMED by JS's logcat (2026-11-03 21:05, FATAL, PID 29002): java.lang.IllegalStateException: LayoutCoordinate operations are only valid when isAttached is true, at LayoutCoordinatesKt.boundsInWindow, at AppKt.PosterCard lambda (App.kt:494, OnPlacedNode.onPlaced) — exactly the 4.8.6 reverse slot-clear onPlaced check, on a LazyList item whose parent chain was deactivated (first node in the dump: deactivated=true). The hotfix above already removes that call. Second pass, same hotfix build: every remaining placement-time bounds read (detail report, poster bounds, carousel visibility, tap-time bounds, search-pill localBoundingBoxOf) now goes through safeBounds(), which tries boundsInWindow() and returns null on IllegalStateException instead of crashing — checking isAttached on the child alone would NOT have been enough, because the log's node was isAttached=true and the throw came from a parent coordinator.

## 1.9-dev4.8.6

- CRITICAL stuck-reverse fix: dev4.8.5 required two consecutive frames >=0.999 to clear the hero overlay, but if the animation jumps straight to 1.0 in one frame, that second frame never comes — the poster stays fixed over every tab until restart (JS's Downloads screenshot). Fixed: monotonic motions clear on first crossing again (as in 4.8.4, which never stuck); bouncy motions clear on a timeout (900/1100ms, after settle); a 1500ms timeout guard clears ANY stuck overlay; switching tabs mid-reverse clears it immediately (dest rect only makes sense on its own tab).
- Scroll: PosterCard no longer forces a graphicsLayer on every card at scale 1 (separate GPU layer per visible card during scroll, even when not pressed). Press shrink is kept, layer only exists while actually pressed. Nuvio's poster card has no such layer.
- Scroll, dock+list read in full (Nuvio FloatingNavigationBar.android.kt, NavigationBar.kt, ShelfComponents.kt, PosterGrid.kt, HomeScreen.kt, MainTabsDestination.kt vs our FloatingDock + LazyColumn + PosterRow + PosterCard): dock formulas are identical (track 48+16*fraction, padding 58-30*fraction, snap threshold 60px, labelFraction tween) and hazeSource placement is identical (whole tab content, nestedScroll only when adaptive) — our dock IS their code, copied, not a guess. The real scroll differences found: (1) our 4.8.5 slot-clear stored card bounds in a STATE set from onPlaced, which fires every scroll frame — every visible card recomposed every frame during a fling, Nuvio's card has no onPlaced state at all (fixed: hidden flag set once on reverseDestRect change + guarded reverse-only placement check, never during normal scroll; same for carousel); (2) LazyColumn position + search pill position were MutableStates set from placement every frame — now plain holders, tap handler reads current holder at tap time, placement costs nothing (Nuvio's list has no placement state at all); (3) Nuvio's poster card is simpler (no press scale, no overlay buttons, no border) — ours keeps those, but press layer is now conditional, so they cost composition once, not per frame.
- Nuvio scroll block, checked honestly: their LazyColumn of LazyRows, Coil posters, Haze dock, and dock snap (60px threshold, snap to 0/1, not continuous) are already in Anipull — our dockScrollConnection is literally that snap logic. Copying their KMP scroll code verbatim would not change anything, because the copyable parts are already here and the rest (ViewModel, data model, KMP) cannot be pasted into our Android-only app without breaking it. Next scroll step is measurement on JS's phone, not another blind build: turn Translucent dock OFF and Home particles OFF in Settings and hard-fling — if it becomes Nuvio-smooth, it's Haze/particles; if not, it's list/poster work.
- Signing key 51f74e55, NEW (VM regenerated again) — will NOT update dev4.8.5 in place, uninstall first.

## 1.9-dev4.8.5

- Reverse slot-clear: the destination card (grid, search, Explore, Library, carousel) is position-matched to the reverse flight (within 20px) and renders as an empty slot — same size, Bg background, no poster/title — while the overlay flies back, so it never sits on top of a duplicate. Other copies of the same title in other rows stay visible. When reverse ends, the card reappears exactly as the overlay lands.
- Carousel freeze: Editor's Picks auto-scroll now stops while any detail is open (screen != null). Before, it kept advancing under a detail, so on back the reverse slot already held a different pick (e.g. 3rd instead of 1st). Timer restarts fresh on return, pager never jumps.
- Explore skeleton fix: initial Explore load used SearchSkeletonGrid (2 per row) while the real grid is 3 per row — the jump JS screenshotted on a fast scroll. Initial skeleton now uses the same column count as the grid.
- Explore columns setting (Layout): 2 / 3 / 4 per row, default 3, saved as explore_columns. Grid, chunking, full-row rule, and all skeletons follow the chosen count.
- Scroll round 3: home LazyColumn tap handler no longer restarts on every scroll frame (it was keyed on pillCoords + lazyColumnCoords, both changing as the header scrolls — now it reads them from State objects, keys only on search focus); dock Haze inputScale 0.5f -> 0.33f (blur work scales with the square, translucency unchanged, just softer). Nuvio 0.6.6-beta APK (com.nuvio.app, code 149, SHA e5702769) and its cmp-rewrite source were checked: list structure (LazyColumn of LazyRows), Coil AsyncImage with no explicit size, and Haze dock are essentially the same as ours — so the remaining gap is per-frame work in our composition, not a different list or loader.
- Poster transition motion options (Settings > Layout, hero_motion): Normal (current BitChord spring, kept as requested), Ease out (fast start, gradually sitting there — FastOutSlowIn, 500ms), Overshoot (spring 300/0.75, slight bounce past), Bouncy (spring 400/0.5), Cinematic (slow ease-in-out cubic, 700ms). Same spec drives forward AND reverse. Clearing now waits for two consecutive near-1 frames, so overshoot/bouncy are not cut short on first crossing.
- Signing key 66ebab6d, SAME as dev4.8.4, so it updates dev4.8.4 in place (not dev4.8.3, which was 45c57e79).

## 1.9-dev4.8.4 (2026-11-03, versionCode 22) — skeletons that match, carousel rebuild, reverse hero, Nuvio dots/dock, scroll round 2

JS, with screenshots: initial fast scroll still laggy; skeletons show wrong layout (real cards land in completely different positions); back animation should reverse out of Detail; Nuvio hero dots for Editor's Picks; Editor's Picks rebuilt like the reference (title above 2 compact buttons, Download instead of Watch, no glass, no spotlight text, not huge); back button too small/close to status bar; switch thumb dark purple -> white; Settings toggle descriptions touching the toggle; Explore must always end on a full 3-card row; dock icons get a subtle press enlarge like Nuvio.

- Skeletons, pixel-identical: SkeletonRow now matches PosterRow exactly — heading 17dp icon + 20dp bar, Spacer 12, poster 118x177 corner 16 (was corner 12, no title/meta), Spacer 8, title 2 lines, Spacer 2, meta bar. SearchSkeletonGrid and ExploreSkeletonRow got the missing second title line + meta, same spacings. Home loading now also shows a CarouselSkeleton (275dp + dots) first, so the real carousel no longer pushes everything down 292dp when it appears.
- Editor's Picks rebuilt: no top type pill, no dark horizontal gradient, only a bottom gradient. Title moved to the BOTTOM, just above the buttons, so the poster face/middle stay clear. Buttons are now one compact row: Download (white, black text, our download icon, no "1080p" direct label) + Details (solid dark #1A1A24, info icon, no glass blur). Card stays 275dp — not the reference's huge size.
- Reverse hero: back from Detail (button or system back) now flies the same overlay backwards on the same 195-stiffness spring — full PosterCard for grid sources, poster-only for carousel sources — using persistently stored detail rects, so back always has a real source. Detail onBack and BackHandler both route through goBackFromDetail().
- Dots, verified in NuvioMobile HomeHeroSection.kt: 8dp tall, width 8dp + 24dp * visibility, alpha 0.35 + 0.57 * visibility, monochrome onBackground colour, Row spacedBy 8dp, clickable to the nearest occurrence — morphing continuously with the pager offset, not just on page settle.
- Dock press: icon scales 1.0 -> 1.12 on press (spring, graphicsLayer only, layout never shifts), label untouched. Nuvio's own dock press is part of its morphing pill track, not a separate icon scale — this is the subtle equivalent JS described.
- Back button: new BackPill (40dp pill, Card bg, Pink border, 15sp bold) used in Detail, Downloads, and Settings — replaces the 14sp text.
- Switch: checked thumb is now White (was dark purple), track Pink. SettingsToggle now has a fixed 16dp gap between text and switch, so long descriptions never run into the toggle.
- Explore: trailing partial row is now dropped even when finished — bottom line is always a full 3-card row (was: partial row shown on finish, ending with a lone first card).
- Scroll round 2: home section updates wait (on their IO job) until a scroll stops before applying (fetchHomeProgressive callbacks are now suspend so the wait is legal); genre batch also waits for scroll idle; poster loads capped at 4 concurrent decodes (semaphore); poster memory cache capped at ~50MB by bytes (was 200 bitmaps, ~150MB); PosterImage filterQuality Low.
- Dock revert: dev4.8.3's haze-off-while-scrolling is REVERTED — JS spotted it as a bug (translucency disappearing mid-scroll, popping back on stop). Dock stays translucent always now.

Posters also decode as hardware bitmaps (Coil's GPU-memory trick, API 26+ = our minSdk) with an ARGB fallback. NEW debug key in this build (66ebab6d, VM regenerated the debug keystore) — it will NOT update dev4.8.3 in place, uninstall first.

## 1.9-dev4.8.3 (2026-11-03, versionCode 21) — initial fast-scroll fix, Nuvio-informed

JS: "scroll is still not smooth, initial fast scrolls right after app opening, it's cancer" — even at 90Hz. Checked NuvioMobile (cmp-rewrite) for how they keep lists smooth.

What Nuvio actually does (verified in their repo, not guessed):
- Every LazyColumn item has `key =` + `contentType =` (HomeScreen.kt) — ours had none, so every progressive home update recomposed/re-measured all visible items.
- Images go through Coil AsyncImage: size-aware decode to the composable's size, memory-cached, no per-poster state recomposition.
- Blur uses `HazeInputScale.Fixed(0.66f)` for large blur areas, `Auto` for small overlays.

What changed here (no Coil added — Gradle cannot fetch new deps on this VM, so we mirrored Coil's behaviour in our own loader):
- Home LazyColumn items now all carry keys + contentTypes (header, search, cached, weekend, carousel, genre rows, skeletons, extra rows, Explore, Explore-more/done, footer).
- Poster decode is now size-aware: PosterImage passes the card width in px, loadPoster downsamples via inSampleSize to that width, and the memory cache is keyed by URL + target width (grid card vs carousel no longer share one bitmap).
- Haze: dock blur input scale Fixed(0.5f) (Nuvio's trick, halved for a dock-sized area), and — the big one — translucent dock + haze source turn OFF while the home list is actually scrolling and back on when it stops. Translucent-default-on in dev4.8.2 meant every fast scroll paid a full blur re-render at 90Hz.
- Particles and carousel auto-scroll pause while scrolling (they only flip at scroll start/end, so no per-frame cost).
- Explore no longer starts the moment home lands: it starts when the Explore item composes (user actually scrolls near it). It used to fire 12 category fetches + poster loads in parallel with the home fetch, right during the first fast scroll.
- Extra genre rows: fetched in parallel but applied in ONE state update, after a 1.5s delay so they never compete with the first scroll.
- Home cache JSON build + prefs write moved off the main thread to Dispatchers.IO (it used to run right as the fresh home finished, exactly when the user is scrolling).

Same debug key as dev4.8.1/dev4.8.2, so it updates dev4.8.2 in place.

Catalogs added in the same build (JS: "more catalogs before Explore, and before the genre catalogs starts", then "only 4 if we exclude Weekend marathon, need more"):
- 4 new AniLab home catalogs, placed between the home rows and the genre rows: Most Favorite, Top 10 Today, Top 10 Week, Top 10 Month (verified live in /api/home, which returns 9 sections — we were showing only 5).
- Genre rows expanded 4 -> 11: Action, Romance, Comedy, Fantasy + Drama, Adventure, Sci-Fi, Mystery, Slice of Life, Supernatural, Sports (all verified AniLab category IDs).
- Final order before Explore: 9 home rows (incl. Weekend marathon picks) + 11 genre rows. Genre fetch is still batched into one state update after a 1.5s delay, so the extra rows do not compete with the first fast scroll.
- Build fixes in this round: listState was used before it was defined (moved up), and HazeMaterials.ultraThin() is @Composable so it cannot be called inside the hazeEffect {} block — the style is now computed outside the block and assigned inside.

# Changelog

All notable changes to the Anipull Android app are tracked here.

## How releases work

- `main` is always the last stable release (currently 1.8).
- Work for the next release accumulates on `dev/1.9` in small commits.
  Nothing on this branch is published as a Release.
- Test builds from this branch are labelled 1.9-dev / 1.9-dev2
  (versionCode 11 / 12). They are debug-signed, and the debug key
  has changed between builds before — if a dev build shows a
  package conflict, a full uninstall before installing is the fix,
  and a stable release keystore is needed before 1.9 ships.
- When 1.9 is ready: merge `dev/1.9` into `main`, set the version to
  clean 1.9, tag it, publish the Release, and move the notes below
  from Unreleased into a 1.9 section.

## Unreleased (1.9 — in progress)

- Logo: Orbit spark replaces the v2 stack everywhere — launcher
  colour icon, themed/monochrome icon, and in-app header logo.
  Pink sparkle inside a broken orbit ring, dot on the ring.
  Wordmark ("Ani" white + "pull" pink) unchanged. Themed-icon
  phone check still pending.

### 1.9-dev4.8.2 (versionCode 20) — test build, not a release

- Weekend marathon picks: the "Sun" and "Full seasons ready"
  pills are gone — title only, as JS asked.
- Full-card hero (JS): the detail transition no longer flies
  just the poster. The overlay is now the whole PosterCard
  (poster + rating pill + 1080p tag + download button +
  title + type/year), so those elements travel with it to
  the detail position instead of popping in there. The
  math holds because a PosterCard's height is exactly
  cardWidth*1.5 + a constant title/meta height, so a card
  rendered at the lerped width is exactly the lerped height
  — no stretch, no snap. Carousel sources keep a
  poster-only overlay (a carousel card is not a PosterCard).
- Home scroll optimisation (JS: fast scroll jerked/stuck
  for instants): (1) particle ticker moved out of
  HeaderSection into its own ParticleLayer, so it no longer
  recomposes the whole header 20x/sec; (2) PosterCard and
  carousel bounds are no longer reported by
  onGloballyPositioned on every scroll frame — they are
  captured at tap time from stored LayoutCoordinates, and
  the detail card reports on place only; (3) Explore grid
  no longer runs animateContentSize over 120 posters on
  every new page; (4) PosterRows are keyed by title id;
  (5) poster memory cache 120 → 200, so scrolling back up
  from Explore no longer reloads home posters.
- Carousel infinite (JS): after the 5th card it no longer
  scrolls left back to the 1st. The pager is now 5000
  pages (5 picks x 1000, starting in the middle) and every
  page maps to picks[page % 5], so auto-scroll always moves
  right and the picks just repeat. Dots show currentPage
  % 5, and a dot tap jumps to that pick's next occurrence
  to the right, never left. Dots also stretch wide with a
  spring when selected, and carousel cards now press-scale
  to 98% like posters.
- Explore multiples of 3 (JS): each batch is now 9 titles
  (exactly 3 rows), and while loading only complete rows
  are shown — a row never appears with 1 card while the
  other 2 fetch; the remainder stays buffered until the
  next batch completes it, or until Explore finishes. While
  the next row loads, 3 shimmering skeleton cards + title
  text show in its place, not a "Loading more…" line.
- Explore icon: the compass is replaced by a 2x2 grid
  square icon, and the "Keep scrolling…" sub-heading under
  "Explore" is removed.
- White accent fix (JS: white killed the app): solid
  accent backgrounds (dock pill, pink buttons, 1080p pill,
  Download buttons, Start/Allow/Search/Retry/Browse) now
  use OnAccent — black text/icons when the accent is
  light (luminance > 0.6), white otherwise. Pink text on a
  dark background was already readable, so only solid
  backgrounds changed. White stays pickable, and it no
  longer makes the dock unreadable.
- Accent: one more fixed colour, Sunset (#FF6B35), plus a
  last custom cell — a real HSV picker (saturation/value
  square + rainbow hue slider, preview + hex, Apply) that
  saves any colour as the accent, surviving restarts.
- Light mode (JS, default off): Appearance → Light mode
  toggle, mutually exclusive with AMOLED Black. Light
  surfaces (Bg #F2F2F7, Card white, search pill white,
  poster placeholder light grey), dark text (TextMain
  #11111A, Muted, Faint), light dock with dark unselected
  icons, light Settings icon boxes, and grey-lavender
  particles instead of invisible white ones. Featured
  carousel cards deliberately stay dark (white text on
  them is readable and looks intentional), as does the
  loading splash and onboarding wallpapers.
- Translucent dock is now default ON (prefs default true
  in the app, Settings, and dock wiring).
- Search keyboard (JS): scrolling the "From your
  catalog" section, or any search results, now auto-hides
  the keyboard on scroll — hide only, never clearFocus
  or closeSearch, so the list stays exactly where it was.
- More animations, on my own: Settings sub-pages slide
  in from the right (hub back in from the left), carousel
  dots stretch, carousel press scale, explore skeleton
  shimmer. All of them are cheap (one transition, one
  dot row, one loading row) so they don't cost the scroll
  smoothness above.

### 1.9-dev4.8.1 (versionCode 19) — test build, not a release

- Carousel auto-scroll fix (JS, screenshots): the old effect was
  keyed on currentPage, and currentPage changes mid-animation
  (as soon as the pager crosses halfway), cancelling
  animateScrollToPage — so auto-scroll kept stopping in the
  middle of two cards. Now a loop: delay 5s, animate fully,
  repeat; a manual drag only delays the next tick. Cards are
  also uniform and bigger now: a fixed 275dp, poster filling
  the whole card, buttons pinned to the bottom — the bigger
  card JS liked, for every card, plus a touch more.
- Detail hero stretch fix (JS, screenshots): PosterCard used
  to report its whole column (poster + title + meta, ~237dp)
  as the hero bounds, so the overlay poster filled that tall
  rect and then snapped/croped back to the 177dp poster.
  Now it reports the poster image only (118x177, 2:3), so
  source, overlay and detail poster are the same rect, plus
  a matching border on the overlay so nothing pops at the end.
- Settings redesign, Nuvio-style (JS, screenshots): the old
  single long page is now a hub with a large "Settings"
  heading and grouped cards (Appearance + Layout, Downloads
  + Notifications, Storage + About), each opening its own
  sub-page with the same large heading, icon rows and
  dividers, and a logo + version footer. System back on a
  sub-page returns to the hub, not out of Settings.
- Appearance, Nuvio-style: accent colour grid (Anipull Pink,
  White, Crimson, Ocean, Violet, Emerald, Amber, Rose, Teal,
  Lavender) that recolours buttons, pills, dock highlight
  and wordmark instantly and survives restarts (accent is
  live state loaded in MainActivity before first
  composition, so no pink flash); AMOLED Black toggle
  (pure black backgrounds); SF headings toggle; Reduce
  motion toggle (kills auto-scroll and press scale).
- More real settings, on my own: Layout page (translucent
  dock, dock behaviour dropdown, Show Explore on Home,
  carousel auto-scroll, home particles); Downloads page
  (parallel, speed, folder + Browse, quality, language,
  Wi-Fi only, ask season, subs, plus Auto-retry and Keep
  screen on, both saved for the 2.0 engine); Notifications
  page (progress notifications on/off, completion ping
  on/off); Storage page (clear home cache, download
  folder); About page (version, GitHub app + website repo
  links that actually open, website demo link, AniLab +
  sources note).
- Download progress notification, designed and previewable:
  low-importance Downloads channel, per-episode progress +
  MB/s + Pause/Cancel, completion ping tapping to
  Downloads (Notifications.kt + DownloadActionReceiver).
  Downloads tab has a "Preview download notification"
  button that posts a sample (Frieren Episode 1, 0–100%
  at 8.4 MB/s) — Pause/Cancel in the shade really pause
  and cancel that preview. Real downloads in 2.0 will
  plug into this same helper.

### 1.9-dev4.8 (versionCode 18) — test build, not a release

- SF font mixed into the app (JS): SF Pro Display Semibold is no
  longer onboarding-only — it is now the heading font (hero
  titles, section headings, detail title, Settings/Library
  titles), with Outfit kept for body text.
- Loading screen 1.75s (was 1.5s, JS's call), still a hard cap —
  fetch continues in the background, skeletons swap in.
- Explore catalog, after all home/genre catalogs: a 3-per-row
  grid (not a horizontal row) that loads page by page as you
  scroll — 12 AniLab category pages, 10 titles each, distinct —
  and only when it finally stops does the footer appear. This
  is what Downloads' "Browse anime" points at.
- Editor's picks (plural): the single pick card is now a
  5-item carousel (featured + Spotlight), descriptions removed
  altogether, auto-scroll one poster every 5s, manually
  swipeable, dots to jump, and auto-scroll pauses the moment
  the carousel is fully off-screen (and while you drag), so it
  never wastes battery on an unseen carousel.
- Appearance in Settings: translucent dock toggle (real Haze
  backdrop blur, not full liquid glass — off = solid dock),
  dock behaviour dropdown (Always expanded / Always compact /
  Adaptive, the current scroll morph), and an Editor's picks
  auto-scroll toggle.
- Detail hero animation, from BitChord's actual code
  (kushagrasinghx/BitChord, PlayerSheetMotion.kt): BitChord
  keeps one player sheet mounted and drives its artwork on a
  critically damped spring (stiffness 195, no bounce, window
  animations off so nothing double-animates). Anipull has no
  persistent player, so the same spring drives a hero overlay
  poster from the tapped grid card's bounds to the detail
  poster's bounds, while detail text fades in. Without a hero
  (no bounds), detail fades in over 300ms instead of appearing
  instantly.
- Search scroll fix (JS): scrolling the cached catalog below
  the search pill no longer closes search — the old
  scroll-clears-focus effect is gone. Only a true tap on empty
  space (not a scroll, not a poster tap — posters consume their
  own taps) closes it, plus the tap-above strip and back.
  Back is unchanged: first back closes the keyboard (IME),
  second back goes Home.
- Extra smoothness, on my own: posters shrink to 96% on press
  with a spring, Explore grid grows with animateContentSize
  instead of jumping, and detail content fades in.

### 1.9-dev4.7 (versionCode 17) — test build, not a release

- Loading screen HARD-CAPPED at 1.5s (JS, 2026-11-03): dev4.6 was
  max(fetch, 1500ms), so a slow AniLab fetch stretched it to
  4.5–5s. Now loading ends at 1500ms no matter what, fetch
  continues in the background, cached rows show instantly and
  unloaded rows show skeletons that swap in progressively.
  First launch with no cache shows skeletons, never demo titles.
  Caching was NOT removed — it was just hidden behind the old
  loading gate.
- Search animation, Nuvio-style single motion: the whole hero
  (logo, DOWNLOAD-ONLY pill, Find it / Keep it offline,
  subheading) collapses in ONE fade + expand/shrink animation
  (320ms FastOutSlowIn), so the search pill rises smoothly to
  the top instead of jumping in stages. Chips/stats/sources
  collapse the same way. No staged snaps, no per-letter work on
  the main thread — the animation is a vsync-driven Compose
  animation, so on a 120Hz phone it runs at 120Hz.
- Footer (Anipull about section) is home-only now: it no longer
  appears when the search box is tapped, while results are
  fetching, or on search results — it was a trailing list item,
  so short search/skeleton lists exposed it mid-screen.
- Search skeleton fixed to 2 cards per row (was 3), matching the
  real 2-column results grid, so results no longer jump sideways
  when they land. While a new search is in flight, old results
  stay visible instead of flashing to skeleton.
- Focused empty search now shows cached posters instantly
  ("From your catalog", up to 12, from the home cache + poster
  disk cache — zero network), instead of just "Type to search".
- Live search rate-limited: 500ms debounce, distinct-query guard,
  in-flight call cancelled on the next keystroke — a fast typer
  fires one AniLab call, not one per letter.
- Back button + tap-above close search: device back from search
  returns to home (detail still outranks search, so back from a
  detail opened via search leaves the detail first), and a 28dp
  tappable strip above the pill in search mode closes search too.

### 1.9-dev4.6 (versionCode 16) — test build, not a release

- Loading screen every launch (JS's call — it looks nice):
  previously it only showed when there was no cached home, so
  every relaunch skipped it and the cached home appeared
  instantly. Now it shows on every launch, with a 1500ms
  minimum so the logo-rise + name animation always plays out
  fully instead of flashing by. Safety valve: if AniLab is
  slow, a cached home still breaks out after 5s and the
  refresh continues in the background — the loading screen
  never traps a cached user behind a hanging fetch. First
  boot still shows onboarding first, as before.

### 1.9-dev4.5 (versionCode 15) — test build, not a release

- Header gap, fixed properly (JS's circled space in his dev4
  screenshot): the gap was 36dp LazyColumn spacing between the
  header item and the hero PLUS 10dp + 24dp spacers at the top
  of the hero — ~70dp of dead space. The header is now folded
  INTO the hero (TopBar is the first thing in HeaderSection),
  so no list spacing lands between them: header -> pill is
  now 4dp + 12dp, and everything below moves up ~54dp.
- Header logo bigger again — dev4's 36/28/22 was still not
  big enough: now chip 48dp, icon 38dp, name 28sp, left corner
  as before. Footer logo unchanged.

### 1.9-dev4 (versionCode 14) — test build, not a release

- Header no longer sticky (JS's call, folded into dev4 before
  ship): the stickyHeader + solid-on-scroll pill are gone, the
  header is a plain first item that scrolls away with the hero,
  so there is no reserved sticky strip at the top — only the
  status-bar inset above it. Logo + name stay in the left
  corner, a bit bigger: chip 36dp, icon 28dp, name 22sp
  (footer logo keeps the old size).
- Welcome scaled up to fill the solid bottom area (JS's dev3
  screenshot, red circle): Welcome 24sp, logo 60dp / wordmark
  46sp, tagline 26sp, button 60dp, wider gaps — the block now
  spans the highlighted area instead of sitting small in it.
  Notification page scaled to match (bell card 100dp, title
  34sp, body 20sp, buttons 60dp).
- Dock rebuilt 1:1 from NuvioMobile's actual FloatingNavigationBar
  (adaptive, glow off), read in full this time — not just the
  labelVisibility fragment. The dev3 staging bug was the label:
  it was dropped below fraction 0.02, so mid-morph the column
  height jumped a full label height (snap, then rest of morph).
  Now, like Nuvio's NavItemLabel, label height / spacer / alpha
  all scale with the fraction, dropped only at exactly 0.
  Pill width now comes from Nuvio's padding formula (58dp
  collapsed → 28dp expanded) and height 48dp + 16dp*fraction,
  one continuous tween — no separate width/height stages.
  Selected highlight is now ONE travelling pill (spring X,
  jelly-style settle) instead of an instant per-item background
  swap, and the morph state is global: tapping a tab while mini
  keeps it mini on the new tab, with the pill sliding there.
  Scroll state is Nuvio's single-accumulator connection, 60px
  threshold, direction-change reset.

### 1.9-dev3 (versionCode 13) — test build, not a release

- Dock jerk fix (JS feedback on dev2): dev2 computed scroll as
  index * 1000 + offset, but the header alone is taller than
  1000px, so that total dropped when the first item changed even
  while scrolling down — the dock expanded for a frame, then
  shrank again. Rebuilt Nuvio-style after checking NuvioMobile's
  FloatingNavigationBar / NuvioNavBarScrollState: real nested-
  scroll pixel deltas with a 90px hysteresis, and a continuous
  labelVisibility fraction (1f expanded, 0f icons-only) animated
  with a tween, so width, height and label alpha all fade
  together — labels never pop in or out.
- Onboarding readability fix (JS feedback on dev2 screenshots):
  fade now goes solid #07070C from 62% down (Anime+ style), so
  all text and buttons sit on solid black with room to breathe,
  never on busy art. "Welcome to" is now solid white, larger.
  Onboarding text is now SF Pro Display Semibold (JS-supplied
  OTF, onboarding screens only — rest of the app stays Outfit),
  with larger semibold body text on both screens and a solid
  dark bell card on the notification screen. Note: the SF OTF
  uses CFF outlines — if it fails to render on a phone, the fix
  is a TrueType conversion, not a redesign.

### 1.9-dev2 (versionCode 12) — test build, not a release

- Morphing floating dock (Nuvio-style, very smooth spring):
  Home, Library, Downloads, Settings. Expanded = icon + label,
  scrolling down shrinks it to icons-only, scrolling up expands
  it again. Header is now logo-only; Downloads/Settings buttons
  moved to the dock.
- Library tab (decided): Saved section (bookmark a title from
  its detail page, saved titles survive "Clear home cache") and
  Downloaded section (honest empty state — downloads become real
  in 2.0, with a per-title downloaded-episode count then).
- Onboarding, first boot only: Welcome screen (My Hero Academia
  wallpaper, "Welcome to" + logo + name, one "Start" button with a
  real arrow vector) slides left into a notification-grant screen
  (Attack on Titan portrait wallpaper, real bell vector, Allow /
  Skip). Catalogs load in the background while onboarding runs.
  Allow fires the system POST_NOTIFICATIONS dialog; Skip and a
  denied dialog both go straight to Home. Outfit font throughout.
- Loading screen: calm pink/black/white gradient, Orbit logo
  rises, "AniPull" name appears, small loading bar, then Home.
  Shown when there is no cached live home; if AniLab still has
  not loaded, Home shows skeletons + Retry — never demo titles.
  Demo titles are no longer used as a search/chip fallback either.
- Hero stat: "6000+ / Anime Titles" (AniLab IDs verified to
  1000006620, higher IDs 404 — see the AniLab count note).
- Quick chips: Movies, Series, OVA, Specials (1080p ready
  dropped). Chips now filter live titles by type.
- Metadata unchanged in this build: AniLab is still primary.
  The AniList-primary proposal is NOT included — it was a
  recommendation, not an approved switch, and is still awaiting
  JS's decision.

## 1.8 — 2026-11-02

- Themed/monochrome launcher icon simplified to diamond + chevrons
  only (no arrow). In a single colour the white arrow merged into
  the white chevrons. Colour icon unchanged.
- Phone check of the themed icon still pending at release time.

## 1.7 — 2026-11-02

- Logo swapped to the v2 mark: pink diamond + two rounded chevrons
  + white arrow, dark launcher background (#07070C).
- In-app header chip changed from pink to dark to match.

## 1.6 — 2026-11-02

- Sub/Dub language chips in Settings (default Sub).
- Folder Browse picker with persistable folder access.
- Thicker, less-laggy finger trail.
- "How Anipull works" section removed.
- Extra genre catalog rows: Action, Romance, Comedy, Fantasy
  (10 titles each, via AniLab /api/category).

## 1.5 — 2026-11-02

- Full-width search cards, 48 drifting/glowing/twinkling particles,
  continuous glowing trail line, focus-only search pill border.
- Downloads page (empty state) and Settings page (parallel downloads,
  speed cap, folder, quality, toggles, Clear home cache).

## 1.4 — 2026-11-02

- Tap-away dismisses keyboard/focus, unmissable search clear button,
  updated hero subheading and search placeholder copy.

## 1.3 — 2026-11-02

- Instant load: cached home renders immediately, then refreshes.
- Live-as-you-type search (debounced), fixing search that only
  filtered demo titles.

## 1.2 — 2026-11-02

- Outfit font bundled, corrected icon centring, Editor's Pick rebuilt
  to the website's pick-tag, Weekend marathon picks, sticky logo header.

## 1.1 — 2026-11-02

- 16-bug fix round: icon centring (first pass), real SVG logo in-app,
  vector icons, Editor's Pick rebuild, search clear button, focus handling.

## 1.0 — 2026-11-02

- First release build: AniLab plugged in, edge-to-edge layout,
  demo fallback, honest "episode pull is next" detail state.
- Downloads demo-only from the start — the download engine /
  resolver stage has not started yet.
