# Changelog

All notable changes to the Anipull Android app are tracked here.

## How releases work

- `main` is always the last stable release (currently 1.8).
- Work for the next release accumulates on `dev/1.9` in small commits.
  Nothing on this branch is published as a Release.
- Test builds from this branch are labelled 1.9-dev (versionCode 11)
  so they install cleanly over 1.8 for testing.
- When 1.9 is ready: merge `dev/1.9` into `main`, set the version to
  clean 1.9, tag it, publish the Release, and move the notes below
  from Unreleased into a 1.9 section.

## Unreleased (1.9 — in progress)

_Empty for now. Add a line here for every feature, fix, or change
as it lands, so the 1.9 release notes write themselves._

- _Nothing yet — add the first 1.9 change here._

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
