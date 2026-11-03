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
