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
