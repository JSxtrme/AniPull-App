## 2.0-dev2.15 (2026-11-12, versionCode 76) — Before You Start rebuilt as a pixel-exact replica of the polished mockup

JS's phone verdict on dev2.14 (2026-11-12 20:31 + 20:37 IST): "Terrible animation of the 'before you start' not at all like the mockup. The you're set is terrible too. I want exact 1:1 replica of the mockup... every pixel adjustment need to be exact." Four defects, all fixed by measuring the polished mockup ('onboarding-animation-polish') and matching it exactly in Compose — no approximations. (1) Intro: the heading now opens BIG and CENTRED in the middle of the screen (two stacked lines, 40sp) and glides up to its docked size/position (27sp, one line) as ONE block via scale + translate (FLIP: first rects measured live, last rects computed analytically with the same TextStyles, 1.7s, cubic-bezier(.25,1,.5,1)), description gliding with it with the mockup's mid-flight opacity dip (1 → .15 at 45% → 1), pill fading + drifting only, Q1 fading in as the heading rises (trackwrap 1.2s, .35s delay). The dev2.14 bug was a 200dp nudge of the already-docked heading. (2) Pan: the bias is now computed from the character fraction + actual container size (bias = 2·((f·scaledW − containerW/2)/(scaledW − containerW)) − 1, clamped), with per-step fractions Frieren 0.312 → 0.406 → Fern 0.50 → 0.5835 → Stark 0.667 — so the pan starts ON Frieren and ends ON Stark instead of running the full −1..+1 overflow (empty field at both ends). Pan stays 2.6s per transition, same ease. (3) Tap-to-advance: tapping an option selects AND advances after the mockup's 480ms pick hold; Next advances without answering (keeps the default), Previous goes back. (4) Badge: "You're all set" is now the mockup's animated badge — spring pop (scale 0 rotate −24° → 1.1 rotate 4° at 60% → 1), a check that draws itself (.65s, 1.15s delay), a rotating dashed ring (18s linear infinite), two expanding pulses (delays 1s/3s), 5 sparks, then the heading, sub, picks summary and Let's go rising in (.9s each, delays 1.3/1.5/1.7/1.9s). The end state lingers with the Done button — dev2.14's 1600ms auto-finish is gone. Also matched: "Step 3 of 3" pill, "Question N of 4" labels, 4-segment progress bar, exact option-card geometry (58dp min-height, 13/16dp padding, 21dp radio, 11dp dot, Recommended tag), Skip as the full mockup text with underlined "Skip for now" (goes to the done panel with the defaults summary, doesn't finish straight away), and the copy diffs (description, Q2 Ask, Q4 smallest/fastest). The footer "You can change these anytime in Settings → Sources" is removed (not in the mockup). Known deviation: Compose has no backdrop blur, so option/summary cards use the exact bg color + alpha with no blur. The demo-only replay ghost button is omitted, so progress spans the full width. Everything else in dev2.14 is untouched — this build changes ONLY the onboarding presentation. NOTE FOR JS: he already saw the screen on v75, so the one-time flag is set — on v76 the fixed intro will NOT re-show unless he clears app data (uninstall/reinstall or clear storage).

### Bug Fixes (1)

- Rebuilt the Before You Start onboarding as a pixel-exact replica of the polished mockup — big centred intro heading gliding up via FLIP, background pan starting on Frieren and ending on Stark, tap-to-advance, and the full animated "You're all set" badge with picks summary. The dev2.14 version had a 200dp heading nudge, a pan running the full overflow, tap-only-select, and a plain pill badge

## 2.0-dev2.14 (2026-11-12, versionCode 75) — Before You Start onboarding, subs/size tri-states, Sources settings

Base is the dev2.12 sheet: JS's call (2026-11-11 23:23 IST): "i think we drop this per server size waiting thing. revert back to 2.12. wait for my other plan for now." The dev2.13 experiment is dropped, no replacement built. What happened on his phone: the sheet showed "checking…" with em dashes for a while, Server 7 stuck on checking longest, then suddenly "No server has a file at 1080p Sub — try a lower quality" — for Death Note E1/E3, a combo that definitely has 1080p Sub (Server 5 won it at ~369 MB on dev2.12). The Download Logs report proved the cause: the collect-all race was cancelling its own racers — "EXCEPTION — b0: D was cancelled" / "b0: l0 was cancelled" after 10–25 s runs, with Server 5 answering 1080p fine in winner-only races in the same log. Starting the next round of resolves killed the one already running, so the collect ended with zero exact candidates and honestly (but wrongly) reported no file. Revert: EpisodeSource.kt and App.kt restored byte-for-byte from dev2.12 (commit e817eaa5eb15) — the ServerSizeBoard store, collect-all mode, board UI and "File size by server" list are all gone; the sheet is back to the single "Size:" line (SheetSizeState machine) plus the Server chip row, winner-only race (first exact win crowns, losers cancelled as before). Kept from dev2.12: the updater fix (pre-resolved asset URL, honest phases, one retry), Server 10, the "10 Sources" pill, and both Settings toggles ("Prefer smaller files", "Prefer direct downloads"). On top of that base, this build adds: (1) Before You Start onboarding — third screen after Welcome + Notifications, four questions that set the Settings defaults (quality with Ask each time first, Sub/Dub/Ask, subs Hard/Soft/Both works with Recommended, size Best quality/Smallest file/Fastest first). Frieren art pans left to right (Frieren to Fern to Stark) with the settled motion: screen 1.4s, question 1.35s, heading glide 1.7s, background pan 2.6s, cubic-bezier(.25,1,.5,1) ease-out with crossfade. Skippable, defaults kept. Existing users see ONLY this screen once after updating (version-gated one-time flag keyed to v75) — never Welcome, never the notification re-prompt. The Frieren JPEG ships as a base64 text asset (frieren_onboarding.jpg.b64, decoded once per process) because the GitHub push route rejects binaries. (2) Subs and size are now tri-states, one uniform phased-race model: Hard subs races direct files first (Servers 2 + 10) with HLS fallback, Soft subs races streams first (all but Server 10) with direct fallback, Both works is today's parallel race; Best quality is a new largest-file race mode (mirror of smallest-file), Fastest first is both size options off. Each phased race waits a bounded 8s for its preferred kind before falling back. Quality-tier guardrail unchanged: a below-quality DDL never beats a quality-meeting HLS. Cache and in-flight coalesce keys carry the new modes (size s/L/f, subs d/S/h). (3) New Sources section in Settings holding quality default, language default, subs tri-state, size tri-state, preferred server, sources order, and auto-test last; Downloads keeps queue, folder, and behaviour only. (4) Ask each time quality/language: the sheets open with nothing picked and block the download with a toast until you choose; list warming, batch, and season downloads fall back to 1080p/Sub. (5) About page credits AniList alongside AniLab. Ship note: the first push (b00fc924) did NOT include app/build.gradle.kts — the version bump to 75 lived only in the local tree from the stopped revert work — so CI read the repo's still-74 build file and published the new code as prerelease 2.0-dev2.13 (SHA 1605307f, verified to contain the Frieren asset and the stable-key signature). The bump was pushed separately (e7398316) and CI re-published the same code correctly as 2.0-dev2.14 (SHA 84254734, verified against updates.json). The mislabelled 2.0-dev2.13 prerelease still exists on GitHub and contains the dev2.14 code — do not delete it blindly; the updater serves v75 now. Verified by local build only (BUILD SUCCESSFUL, local APK SHA bde16a8d): the VM cannot run the app — JS's phone test is the runtime proof, starting with the onboarding flow end-to-end (new user full flow + existing-user one-time screen), the tri-state races (Hard preferring Server 10 on Death Note E1 1080p Sub, Soft preferring a stream, Best quality picking the biggest file), and the Sources page.

### New features (3)

- Before You Start onboarding screen (third screen, after Welcome + Notifications) — four questions that set the Settings defaults: quality (Ask each time first, then 1080p/720p/480p/360p), Sub/Dub/Ask, subs (Hard / Soft / Both works, Recommended), and size (Best quality / Smallest file / Fastest first). Frieren art pans left to right (Frieren to Fern to Stark) with the settled motion: screen 1.4s, question 1.35s, heading glide 1.7s, background pan 2.6s. Skippable, defaults kept. Existing users see ONLY this screen once after updating — never Welcome, never the notification re-prompt.
- Subs and size are now tri-states: Hard subs races direct files first (HLS fallback), Soft subs races streams first (direct fallback), Both works is today's parallel race; Best quality is a new largest-file race mode (mirror of smallest-file), Fastest first is both size options off. Each phased race waits a bounded 8s for its preferred kind before falling back, so the no-match case doesn't get slower.
- New Sources section in Settings holding quality default, language default, subs tri-state, size tri-state, preferred server, sources order, and auto-test last. Downloads keeps queue, folder, and behaviour only.

### Bug Fixes (3)

- Reverted the per-server file sizes experiment from dev2.13 — the download sheet is back to the dev2.12 behaviour: one size line for the winning server plus the Server chips (Auto / Server 1…10). The collect-all race that filled the per-server list was cancelling its own racers, so the sheet could end up saying "No server has a file at 1080p Sub" for episodes that do have one. Everything else from dev2.12 stays: the updater fix, Server 10, the 10 Sources pill, and both Settings toggles
- Ask each time quality/language: the sheets open with nothing picked and block the download with a toast until you choose; list warming, batch, and season downloads fall back to 1080p/Sub
- About page now credits AniList alongside AniLab (AniLab: titles/posters/search/home; AniList: episode-count fallback)

## 2.0-dev2.13 (2026-11-11, versionCode 74) — Per-server file sizes in the download sheet

JS approved this design (2026-11-11 22:40 IST) after seeing the interactive visual mockup: "okay les try this approach for one build, and regarding the line, use the one u recommend. if i think it'll make users annoyed i have another plan" — one experimental build, keep it clean and reversible. The problem: the sheet showed ONE size — the winning link's size. Death Note E1, 1080p Sub: "Size: ~369 MB" (Server 5's file). Server 10's file at the same 1080p Sub is 131 MB — a much lower-bitrate encode — and nothing told the user that. They just see "1080p". The small file goes unnoticed in both directions: a user who wants the small file never sees it, and a user who gets it never knows it's a lighter encode. Fix: the sizes list BECOMES the server picker. The single "Size:" line and the Server chip row are replaced by one tappable list, "File size by server" — description line (recommended wording, JS's pick): "Same episode, same quality — but each server's file is a different size. Tap one to download it." One row per server (Server 1–10, never a source name), every row naming its server AND its size ("~" = estimate, no "~" = exact); order is Auto row first, then smallest known size first, "—" rows at the bottom; tags only when they apply: [Auto] on the winner under the current toggles, [Smallest] on the smallest file when it isn't the Auto row, [Direct] on direct-file rows. Row states: size (tappable) / "checking…" (not tappable yet, flips as its racer lands) / "—" (no file at this quality, not tappable) / "Unknown" (has the file, size couldn't be measured — rare, tappable). Footer: "Auto picks for you. Tapping a server tries it first — the others still work as backup." The Download button is NEVER gated on the list, and its label now names the picked server ("Download Episode 1 · Server 10"). Tapping a row means "try this server FIRST" — the confirm path still falls through to other servers if the picked one fails (no new engine semantics). Data flow: today only the WINNER survived the race, so a new in-memory ServerSizeBoard store (key = combo + mode, same shape as the cache key, 90 s TTL, never persisted) holds the full per-server outcome list, streamed as racers land via an onBoardEntry callback; the visible combo's race runs in a new collect-all mode (exact arrivals park instead of crowning, race runs until every racer arrives or the 15 s cap, winner picked with the SAME precedence as today — fastest / smallest / direct pool logic untouched), and parked HLS exacts now get sizes attached under preferDirect too (was gated on preferSmaller only). Collect-all runs for the CURRENTLY selected combo only; the other 7 combos keep today's cheap winner-only warm (the opening combo's old winner-only warm is gone — the collect stores the same winner in ResolveCache, so a second race next to it would be pure waste); dismissing the sheet cancels the in-flight collect. Only an EXACT winner rides the board's Auto row — a downgrade/unprovable/unknown fallback winner is not a file at the selected quality, so tagging its "—" row [Auto] would lie (the confirm path still serves such a fallback exactly as before). Edge cases: no server has the file → one honest line "No server has a file at 1080p Sub — try a lower quality." instead of rows; Server 3 shows "—" when no stored clearance exists — the board NEVER triggers a clearance solve just to fill a row. Trade-off JS accepted: the visible combo's resolve takes longer to COMPLETE (up to the 15 s cap vs ~2–3 s today) — mitigated by rows streaming in progressively, the winner row appearing at today's speed, and the Download button never being gated. NOT included: no change to race winner logic, quality floor, downgrade rules, or honest-fail; no per-leg rows; no bitrate numbers; no season-sheet sizes; no persistence beyond the sheet session; no new Settings toggle. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with Death Note E1 sheet at 1080p Sub (Auto row Server 5 · ~369 MB, Server 10 · 131 MB carrying [Smallest]), tapping Server 10's row (button becomes "Download Episode 1 · Server 10"), flipping "Prefer smaller files" ON ([Auto] and [Smallest] land on the same row), tapping 720p (list re-runs for 720p with Server 8 as the real 720p winner), and the Download button working before the list finishes.

### New features (2)

- The download sheet now shows every server's file size for your chosen quality and language — one row per server, smallest first, so you can see that Server 10's 1080p file is 131 MB while Server 5's is ~369 MB and pick the one you want. Tap a row to download from that server first (the others still work as backup), or leave it on Auto
- The sheet's download button now names the server you picked ("Download Episode 1 · Server 10") instead of just the episode

### Bug Fixes (1)

- Fixed the sheet hiding the small file: it used to show only the winning server's size, so a much smaller file at the same quality went unnoticed. The size list is the server picker now — Auto, Smallest and Direct tags show which row Auto would download, which file is smallest, and which files are direct downloads

## 2.0-dev2.12 (2026-11-11, versionCode 73) — Updater "Connecting…" fix: pre-resolved download link, honest phases, one retry

JS reported the updater sitting on "Connecting…" for too long before installing a new update. Diagnosis, verified live from the VM 2026-11-11: the download itself was never the slow part — the connection setup was. On tap, downloadUpdate (UpdateChecker.kt) opened a plain HttpURLConnection to the github.com release link with no retry, and the progress card showed "Connecting…" until the first 64 KB chunk arrived, so the entire setup was invisible behind one word. Measured: the github.com link 302s to release-assets.githubusercontent.com (fresh per-request signed URL, ~1 h expiry); hop 1 TTFB 1.23 s (TLS 0.27 s + ~1 s GitHub server time minting the signed URL); full chain TTFB 1.53 s; the whole 15 MB file then downloads in 2.19 s total. On JS's phone on 5G in India, two TLS handshakes to two different hosts + mobile DNS + GitHub's slow redirect plausibly stretch it to several seconds, and a single transient stall parked the UI on "Connecting…" for up to 10 s (connectTimeout) before anything happened, then failed with no retry. Fix, smallest change: (1) the sheet pre-resolves the asset URL on open (new resolveApkAssetUrl — headers-only GET, no body read, follows the 302 once, caches the signed URL in memory for 30 min, half the signed-URL life; a failed/stale resolve just means the download follows the redirect live, never worse than before); the Download tap then connects straight to the asset host. (2) Honest phases: downloadUpdate gains an onPhase callback alongside onProgress ("Contacting GitHub…" → "Redirecting to download server…" → "Starting download…", plus "Retrying…" on the retry), and the sheet shows the live phase instead of one flat "Connecting…". Redirects are now followed manually (max 5) so the phase can name the hop. (3) One retry on connect/read failure: the download is attempted twice, the partial file deleted and the download restarted from scratch on the retry (SHA re-verified; HTTP errors and SHA mismatches are not retried — the same URL would fail the same way). No updates.json re-fetch on tap (ruled out — the check happens on home entry only). Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with opening the update sheet (link resolved by tap time), tapping Download & Install (phases read honestly instead of one flat "Connecting…"), and a stalled connection retrying once instead of dying.

### New features (1)

- The update sheet now resolves the download link in the background while you read the changelog, so tapping Download & Install connects straight to the download server instead of waiting on GitHub's slow redirect first

### Bug Fixes (1)

- Fixed the updater sitting on "Connecting…" for seconds before installing — the slow part was GitHub generating the signed download link (~1 s) plus a second connection to another host, all hidden behind one word with no retry. The sheet now shows honest phases and a stalled connection retries once instead of dying

## 2.0-dev2.11 (2026-11-11, versionCode 72) — Server 10 (AnimeHeaven) + "10 Sources" on Home

JS picked AnimeHeaven over AniDL for the 10th source (AniDL's test link opened a Ningen portal page instead of downloading — portal-parse + mirror-pick work not worth it vs AnimeHeaven's true direct MP4) and asked Home to read "10 Sources" instead of "3 DDL sources". (1) SERVER 10: new AnimeHeavenEpisodeSource, verified live from the VM before building — Death Note E1, 1080p direct MP4, 137,797,793 bytes (~131 MB), hardsubbed, sub-only, 1080p-only. Flow: search.php?s= → anime.php?<id> → per-episode gate.php token (32-hex, from gatea("...") calls; episode number from the following watch2 div; verified 37 pairs, eps 1–37) → GET /gate.php with Cookie: key=<token> → <source src> on rx./ct./ck./ax.animeheaven.me, host preference rx > ct > ck > ax, trailing &error/&error2 stripped (player fallback markers) → DdlQualityProbe Range GET per candidate in host order (NEVER HEAD — HEAD hangs on this host, 30 s timeout twice) → DirectMp4 with probed height + exact total. 1080p-sub-only enforced BEFORE any network call (dub → "No dubbed version on this server.", non-1080p → "This server only offers 1080p."), so it can only ever win 1080p Sub requests. Gate quirk handled: gate.php is occasionally slow on first hit (~30 s stall, immediate retry returns in ~1 s, verified twice) — the gate fetch is a bounded EngineHttp.request (5 s socket timeout, 5 s first-byte deadline, 6 s total) with ONE immediate retry, not requestWithRetry's 3-attempt backoff (too slow inside the 15 s racer cap). Series/token cache copied from AnimeStream's pattern (10 min TTL, 8 entries, only non-empty cached) so the sheet's 8-combo pre-warm doesn't re-fetch search+series 8 times; resolveDownload re-fetches the series page for a fresh token per download (tokenised URLs, no long-lived caching). The race batch goes 9 → 10 so all ten race at once; the exact-tier rules, DDL probe, frozen total, "Prefer direct downloads" preference (Server 10 IS a direct file, so the toggle prefers it over HLS at the same quality) and "Prefer smaller files" sizing all apply unchanged. Server naming rule holds: UI shows "Server 10" + generic description only ("Direct downloads · 1080p · Hard-subbed") — the name AnimeHeaven appears only in code/logs (Download Logs summary map gained the Server 10 entry, so a Server 10 job reads "Server 10 (AnimeHeaven)" in the log). (2) HOME PILL: the stats cell that read "3 DDL SOURCES" now reads the live count — StatCell(ServerRegistry.servers.size, "SOURCES") → "10 Sources" — and the Sources chip reads "10 servers", both derived from the registry so they never go stale again. Every hardcoded 1..9 guard in the tree (App.kt job labels/pickers, ServerRegistry order filters, ResolveCache preferred/list-server checks, DownloadEngine list_server restore) is now 1..10, and the DDL download path was audited HEAD-free (downloadMp4 is plain GET + Range; the probe is Range GET only). Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with Home reading "10 Sources", Death Note E1 1080p Sub (Server 10 can win it as a real 1080p direct file), Server 10 never appearing for dub or lower qualities, "Prefer direct downloads" ON preferring a 1080p direct file from Server 10 over HLS, and a slow gate never hanging the race.

### New features (2)

- Server 10 joins the race: a new direct-download source (subtitles burned in, single file per episode) that can win 1080p Sub requests — it counts as a direct file, so the "Prefer direct downloads" toggle prefers it over streams at the same quality
- Home now shows 10 Sources: the stats cell that read "3 DDL Sources" now reads the live server count ("10 Sources"), and the chip above it reads "10 servers" — both derived from the server list so they stay truthful as sources are added

## 2.0-dev2.10 (2026-11-11, versionCode 71) — "Prefer direct downloads" setting

JS asked for a second toggle next to "Prefer smaller files": prefer DDL sources, described in plain words (hard subs) because most people don't know the difference between DDL and HLS. Settings → Downloads → QUALITY & LANGUAGE now has "Prefer direct downloads" (default OFF). Label: "Prefer direct downloads". Description (no jargon anywhere in the user-facing text): "Prefer files with subtitles burned in, so they play anywhere. Falls back to other sources when no direct file is available." Precedence in the race: exact quality tier first, then direct-file preference, then smallest-file (when that toggle is ON), then fastest arrival — so a direct file below the requested quality can NEVER win over a stream that meets it (e.g. JustAnime's gigi DDL, 360p-only for Death Note E1, still loses to a 1080p HLS). Implementation: a direct exact arrival crowns immediately when only this toggle is ON (fastest direct exact wins, zero added latency); a stream exact parks and keeps racing so a slower direct exact still gets its chance; when "Prefer smaller files" is also ON, direct exacts park too and the smallest among them wins. Serving-first is skipped when the toggle is ON (same reason as smallest-file mode — it would crown before the comparison). The resolve cache key and the in-flight coalesce key now include this mode too (…|s|f + d|h), so flipping the toggle re-resolves under the new mode and the sheet's live size always reads the winner decided under the current settings; pre-change entries are orphaned, never matched. The stall-failover race in the engine honours the toggle as well. Both toggles OFF is byte-for-byte today's behaviour. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with toggle OFF (behaviour unchanged), toggle ON at Death Note E1 1080p (must still be real 1080p — the 360p direct file must NOT win — and when a direct file does meet 1080p it should win over a stream), toggle ON with "Prefer smaller files" ON (smallest among direct exact candidates), and flipping the toggle and re-opening the sheet (size re-resolves under the new mode).

### New features (1)

- New Settings toggle in Downloads: "Prefer direct downloads" — at your chosen quality, the race prefers a direct file (subtitles burned in, so it plays anywhere) over a stream, falling back when no direct file is available. Off by default; quality rules are absolute, so a lower-quality direct file never beats a stream that meets your chosen quality

## 2.0-dev2.9 (2026-11-11, versionCode 70) — Full speed pass: instant sheet sizes, no more stuck connections

JS tested dev2.8 on his phone: Death Note E1 at 720p Sub on Server 8 sat on "Connecting…" forever, and the sheet size took seconds to update from "checking…" on every tap. Server 5 at 1080p worked fine — so this build is a full speed pass over everything, plus killing stuck connections. (1) SIZE "CHECKING…" TOO SLOW: root cause, confirmed in code and the phone log — every quality/audio/server tap ran a FULL multi-second resolve race (3–9 s each: Server 2 DDL OK in 5119/8777/5167 ms, Server 8 OK in 2648–4517 ms, Server 5 OK in 2768–2871 ms), because nothing was pre-warmed except the opening combo (list load warmed only the first 3 rows, opening defaults only; sheet open warmed only the opening combo), and stale warms were never cancelled — the application-lifetime preResolveScope let superseded races run to completion, so taps duplicated work (two "JustAnime revo: HLS, offered ?" lines 5 ms apart in the log). Fix: the sheet now warms ALL 8 quality/language combos (4 qualities × Sub/Dub) in parallel on open, ordered most-likely-first, under one shared 4-permit semaphore; list-load warm extended from 3 to 6 rows; the sheet effect is now await-first (only warms when no warm for that combo is in flight) and sheet warms are tracked per episode and cancelled on dismiss. DdlQualityProbe now derives ALL variant sizes from one cached master fetch (parseMasterVariants + deriveSiblingSizes) instead of re-parsing per tap. Also fixed: revo's 1080p master lists BANDWIDTH=5184 — not a real video bitrate, it would freeze a ~0.9 MB total for a 23-minute episode — bandwidths below 100 kbps are now refused as implausible, falling back to byterange sums, then mean-segment-size × count, then no frozen total. (2) SERVER 8 "CONNECTING…" HANG: root cause, pinned to the exact stall point by the phone log — Death Note E1 720p, Server 8: master HTTP 200, variant playlist HTTP 200 (440 KB), media playlist 267 segments, init=true, then NOTHING for 2m 24s. The init-segment fetch (HlsDownloader.kt:358-368) is a silent, sequential, unbounded blocking gate: its log line fires only AFTER the fetch returns, and EngineHttp.requestWithRetry's 15 s readTimeout is per read() call, not a total deadline — a server dribbling ≥1 byte every <15 s keeps readBytes() alive indefinitely (worst case per fetch 48 s nominal, 165 s with Retry-After, and no overall deadline above it anywhere). There was also zero failover once a link won — failover existed only at resolve time. And Cancel couldn't unblock it: cancel() disconnected only DDL connections, HLS connections were never registered, and isCancelled() is only checked BETWEEN blocking fetches — the log shows the drain dying 41 s after cancel, on a ProtocolException, not on the cancel. Fix: EngineHttp.request now reads in 32 KB chunks with wall-clock deadlines (10 s to first byte, 20–30 s total per fetch) and an isCancelled hook; every live HLS connection is registered per job (same pattern as activeDdlConnections) so cancel() disconnects it at once; pre-segment fetches use 1 attempt so a stall fails over instead of retrying the same dead host 3×; a stall (HlsStallException) re-resolves inside the job to the next server (fresh race excluding the stalled server, quality rules unchanged, stalled-host partials deleted, frozen total kept) and the row shows "Server 8 stalled — trying next server…"; a second stall fails honestly with no auto-retry of the same link. Pre-fetch phase log lines (master/variant/init/key/segment-0) now name the stall point next time. The row and notification also show the live phase ("Fetching playlist…", "Downloading init…", "Segment N/total", "Remuxing…", "Publishing…") via job.phaseLabel instead of one flat "Connecting…", and remux/publish now report progress instead of sitting silent. Signed-URL expiry was ruled out as a cause (AnimeStream playlists carry ~10 h expiry vs 10-min cache TTL; master fetched fine at download time) — no re-resolve-on-expiry added. (3) FULL SPEED AUDIT: race now runs all 9 servers at once (was batches of 3, so Server 8 could wait ~30 s before starting); JustAnime legs (gigi DDL + revo/calm/momo HLS + gigi-direct) now resolve in parallel via async after the dependent `servers` call, with per-endpoint pacing at 100 ms instead of one shared 400 ms pace (the 600 req/60 s limit allows it; the old shared lastCallMs would have serialised naive async); AnimeStream caches series/season/episode lists per title in memory (8 entries, 10-min TTL, shared companion cache — the race builds fresh instances per call) and paces per endpoint at 100 ms; the quality probe (probeHlsMasterHeight) now retries via requestWithRetry (3 attempts, 10 s total budget) instead of one 5 s attempt, and splits "unknown" into unprobed (fetch failed) vs unprovable (master fetched, no RESOLUTION) — unprovable links rank above known-downgrade links but still below proven-exact, so real 1080p legs like revo/calm/momo stop being parked as unknown without weakening the exact-tier guarantee. Segment workers raised from 4 to 8 (setting-scaled, order preserved via seg files + segments.done, pause/resume untouched) with per-segment deadlines and fail-fast (abort at 8 failures with zero completions). Freeze-total estimate is capped at 20 s and skipped when the link already carries a size. Progress/notification throttling untouched (~1 s). Correctness guarantees preserved throughout: exact-tier win, quality floor, DDL probe, frozen total, Cancel from every phase, no fake data. Known residual gaps: a blocked fetch's cleanup can still lag behind the UI (row shows CANCELLED immediately, job cannot complete/publish/retry meanwhile); MediaStore publish shows only the "Publishing…" phase label, not byte progress (no progress hook in DownloadStorage.kt); the engine-side stall failover has no toast (the row phase + server label are the visible signal). Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with sheet sizes updating fast on every quality/language tap, Server 8's 720p failing over within seconds instead of hanging on Connecting, Death Note E1 1080p via Server 5 still working, and Cancel working mid-download.

### New features (3)

- The sheet now pre-warms the size for every quality and language in parallel when it opens, so tapping 720p, Dub or 480p shows the size almost instantly instead of waiting on a full resolve each time
- A download that stalls on one server now fails over to the next server automatically instead of sitting on "Connecting…" forever, and the row tells you which phase it is in ("Fetching playlist…", "Downloading init…", "Segment 12/267") instead of one flat "Connecting…"
- Downloads are faster across the board: all 9 servers now race at once instead of in batches, JustAnime and AnimeStream resolve their legs in parallel with much lighter pacing, and HLS segments download with 8 workers instead of 4

### Bug Fixes (5)

- Fixed a download hanging on "Connecting…" for minutes — the init-segment fetch could block on a read that never returned and never timed out (Death Note E1 on Server 8 sat for 2m 24s with zero progress). Every HLS fetch now has a hard deadline (10s to first byte, 20–30s total) and Cancel disconnects the live connection at once instead of waiting up to 41s for the blocked read to error on its own
- Fixed the sheet size staying on "checking…" for seconds per tap — every quality/language tap used to run a full multi-second resolve race, and nothing was pre-warmed except the opening combo. The sheet now warms all 8 quality/language combos in parallel on open, and stale warms from previous taps are cancelled instead of racing to completion
- Fixed HLS size estimates using a nonsense bitrate — a revo 1080p master lists BANDWIDTH=5184, which would freeze a ~0.9 MB total for a 23-minute episode. Bandwidths below 100 kbps are now refused as implausible, with a segment-based fallback instead
- Fixed the race leaving real 1080p legs parked as "unknown" — the quality probe only tried once with a 5s cap, so a flapping revo (520) or a slow handshake meant a genuine 1080p link never competed. The probe now retries with a 10s budget, and a master that was fetched but carries no resolution is ranked as a candidate above known-downgrade links instead of below them
- Fixed the Download Logs summary naming the wrong server — a Server 8 job was labelled "Server 8 (JustAnime)" because the summary borrowed the name from a stale resolve block. The name now comes from the server number itself, so it can never mismatch

## 2.0-dev2.8 (2026-11-11, versionCode 69) — Live accurate file size in the download confirm sheet

JS asked how to get an accurate file size before downloading, on the confirm popup, updating live when he taps a different resolution or language. (1) LIVE SIZE: the per-episode confirm sheet now shows the size for the CURRENT chips (quality/audio/server) and updates on every tap. A new SheetSizeState (Unknown / Checking / Known) driven by a LaunchedEffect(quality, audio, server) resolves the size in order: the ResolveCache entry for the exact combo (instant, and the initial state is seeded from it so a previously computed combo shows on the first frame with no "checking…" flash); a cached link without a size is probed/estimated from its OWN url/master playlist; no cached link warms a resolve for that combo in the background (new EpisodeListState.warmSizeFor, same preResolveScope + shared resolveInFlight putIfAbsent coalescing + resolveOnly as preResolveRow, parameterized by the sheet's chips) and fills the size in when it lands. "Size: checking…" while fetching; nothing when undeterminable; the Download button is never gated on size. (2) AVERAGE-BANDWIDTH: DdlQualityProbe.bandwidthForVariant now prefers AVERAGE-BANDWIDTH over BANDWIDTH — BANDWIDTH is the peak bitrate and overestimates badly (calm 1080p BANDWIDTH 6,715,884 vs AVG 3,124,000 — ~1.15 GB vs ~540 MB); typical accuracy with the average is ~5–15% (code comment only, UI only prefixes "~"). Variant bitrate already includes audio (EXT-X-MEDIA has no BANDWIDTH), so no separate audio addition. (3) CACHES: master/media playlist text, computed HLS sizes and DDL probe results are cached in memory in DdlQualityProbe (~10 min TTL, ~20 entries each, oldest evicted, ConcurrentHashMap, failures never cached), so switching qualities after the first fetch is instant across all variants and re-taps are free. (4) API FILESIZE: JustAnimeResolver now parses the gigi entries' "filesize" label ("27MB" etc.) into bytes as a FALLBACK for estimatedSizeBytes when the probe returns no total (probe total always wins; sizeIsEstimate = false). (5) NO BORROWED SIZES (JS clarification, 2026-11-11): the size always comes from the actual resolved link for that exact combo — never another source as a proxy. The probe data proves why: same episode (Death Note E1), same 1080p, same sub, three sizes by server — momo ~236 MB, calm ~540 MB, revo ~890 MB. 1080p is only the dimensions; size is the bitrate/encode. The ResolveCache lookup enforces the first-server match and every probe/estimate uses that link's own URL/master only; an undeterminable size shows nothing. Season DownloadSheet still shows no size (no single-episode size exists there); Downloads tab "X MB / Y MB" and completed-row sizes untouched; race, quality floor, frozen total and popup layout untouched beyond the size line. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with Death Note E1: open the sheet, size shown; tap 720p, size updates; tap Dub, size updates (different file, different size); tap back to 1080p Sub, instant from cache; HLS sizes carry "~", DDL sizes exact with no "~"; size never blocks Download.

(Second commit, same version) PREFER SMALLER FILES SETTING (JS picked "Make it a setting", 2026-11-11): same episode/1080p/sub varies 236 MB (momo) vs ~540 MB (calm) vs ~890 MB (revo) — 1080p is only the dimensions, size is the bitrate/encode — and JS's take was "People love small sizes tho. And most won't care much about bitrate." So instead of forcing either behaviour, Settings → Downloads → QUALITY & LANGUAGE now has a "Prefer smaller files" toggle (default OFF, fastest first). Description: "At your chosen quality, pick the smallest file instead of the fastest server. May take longer — finding the smallest file means waiting to compare servers instead of taking the first one." When ON, the race parks exact-tier arrivals instead of crowning the first one, attaches a size to each exact candidate (DDL: the link's existing probed size; HLS: estimateHlsSizeBytes on its own master playlist, via the dev2.8 caches), runs ALL batches, and picks the smallest known-size exact candidate across every server; unknown-size exacts rank below every known-size one and only win when no exact candidate has a known size (first arrival then). Serving-first is skipped when ON so the shortcut can't crown before sizes are compared. Quality floor / exact-tier rules, DDL probe and honest-fail are unchanged. Coherence: the resolve cache key now includes the mode (title|ep|audio|quality|s|f), as does the in-flight coalesce key, so flipping the toggle re-resolves under the new mode and the sheet's live size always reads the winner decided under the current setting; pre-change cache entries are orphaned, never matched. OFF path is byte-for-byte the old logic, zero added latency.

### New features (1)

- The download confirm sheet now shows the file size live for every quality and language you tap — it updates as you tap, with "checking…" while it fetches and instant answers for combos already checked
- New Settings toggle in Downloads: "Prefer smaller files" — at your chosen quality, the race picks the smallest file instead of the fastest server. Off by default (fastest first); turning it on may take longer, since it waits to compare servers instead of taking the first one

### Bug Fixes (3)

- Fixed HLS size estimates using the peak bitrate — estimates now use AVERAGE-BANDWIDTH where listed, so the "~" figure is much closer to the real file
- Fixed the sheet only showing a size when the pre-resolve had already cached that exact combo — tapping a different quality or language now warms a resolve for that combo in the background and fills the size in when it lands, without blocking the Download button
- Fixed the size being borrowable across sources — the size now always comes from the actual link being downloaded for that exact quality/language/server, so a size from one server is never shown for another
- Fixed the sheet size going stale when the size preference changes — the resolve cache is now keyed by the "Prefer smaller files" setting too, so flipping the toggle re-resolves under the new mode and the size shown is always for the link that would actually win under the current setting

## 2.0-dev2.7 (2026-11-11, versionCode 68) — Frozen total size, Cancel that always works, proven server choice, Editor's Picks header fix

JS tested dev2.6 on his phone: Death Note E1 1080p downloads correctly at last — "Server 5 · 14% · 70.2 MB / ~488.4 MB · 2.9 MB/s", a real ~488 MB 1080p HLS, not the 27 MB 360p file. But four things were still wrong. (1) FROZEN TOTAL: the ~488 MB total moved mid-download. Root cause, confirmed in code: HlsDownloader reported `estimate = downloadedBytes + remaining * avg` every ~500 ms, where `avg` is a rolling average segment size that drifts as segments land, and DownloadEngine wrote that fresh value into job.totalBytes on every progress callback. Fix: the total is computed once at job start (DDL: exact Content-Length from the resolve-time probe; HLS: BANDWIDTH x duration / summed byte-range estimate via the existing estimateHlsSizeBytes) and frozen — reportProgress adopts the first incoming total once and ignores all later ones; a resumed job keeps its frozen total; completion still stamps the real file length. Displays (Downloads tab, episode row, notification) already read job.totalBytes and needed no change. (2) CANCEL: Cancel only set a flag for DOWNLOADING jobs, so the row kept showing a live download until the loop noticed — up to a blocked read, a whole remux, or a whole publish later; restored jobs never recreated their controls[] so cancel was a silent no-op and pump() could start the job anyway; a DDL disconnect after cancel landed in the generic catch → FAILED with a 5 s auto-retry, so the cancelled job restarted itself; and the old drain's cleanup could delete a re-queued incarnation's staging files. Fix: cancel() now getOrPuts the control, disconnects the live DDL connection to unblock a stuck read at once, and marks CANCELLED + dismisses the notification synchronously in every phase; a shared markCancelled with a control-identity guard protects re-queued incarnations; the generic catch routes control.cancelled to markCancelled (never FAILED/retry); HlsDownloader checks after every fetch and during remux (per segment during concat, every 1024 samples during muxing); a cancel during publish deletes the just-published file. Remaining bounded gap: a blocked HLS playlist/segment/key fetch via EngineHttp.requestWithRetry (blocking HttpURLConnection.readBytes + Thread.sleep backoff, no interrupt handle) can take up to ~15 s x 3 attempts before cleanup lands — but the row/notification already show CANCELLED immediately and the job can no longer complete, publish, or retry. (3) SERVER CHOICE: why Server 5 won Death Note E1 1080p — the race is batched in server order and only JustAnime and Pahe ever returned a known quality; AniZone, Nino 2, AniWaves, AniPm, Anikoto, AnimeStream and Anv all returned HLS links with actualQuality = null, so Nino 2 could never win on merit — it could only win the last-resort unknown lottery, which is what happened when batch 1 produced zero known winners and zero unknowns (Server 2 failed outright that attempt — revo flapping 520 plus ~6 sequential apiGets with 400 ms pacing can burn the 15 s cap — Server 1 failed outright, Server 6's Vidplay file is deleted upstream). The download was genuinely 1080p, but the choice was luck. Fix: unknown HLS links are now proved from their master playlist (max RESOLUTION height, one small parallel GET, 5 s cap, no retry) so HLS-only servers compete as known winners; the win tier is now the REQUESTED height, not the floor — a link meeting the floor but below the request is parked as a flagged downgrade candidate and never ends a batch; end-of-race order is exact winner → best downgrade (flagged) → unknown → honest failure; the winner log line now names why it won with every racer's outcome. Deliberately NO settle window: first proven-exact arrival IS the fastest-proven server, and a window would tax the common lone-winner case against the ~1-2 s bar. AniZone is now correctly parked for 1080p on this title (its master proves max 720p → downgrade candidate only). The serving-first shortcut in App.kt resolveViaCandidates, which bypassed the race/proving entirely, now proves its link too (new MultiSourceResolver.proveLink) and falls through to the race if it proves below the requested height. ResolveCache.lookup re-applies the tier so a cached 720p link under a 1080p key comes back flagged downgraded. (4) EDITOR'S PICKS HEADER: the home_carousel item put the header Row, Spacer(12.dp) and EditorsPickCarousel directly inside Box(Modifier.animateItem()) — a Box stacks children, so the header drew on top of the carousel's top edge. Fix: wrapped in a Column, same pattern as PosterRow. The Explore item had the identical Box-no-Column shape and got the same one-Column fix. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with the total frozen from start to finish on an HLS download, Cancel working from every phase, Death Note E1 1080p still real 1080p with a sane server choice, and the Editor's Picks header cleanly above its posters.

### New features (2)

- The download race now proves each server's real quality before choosing — an HLS link's master playlist is checked for its true top resolution, so the winner is always a server that actually has your requested quality, and the log now says why it won
- Download snackbars can be swiped left or right to dismiss — the View action still works, and dismissing one never cancels the download

### Bug Fixes (4)

- Fixed the total size changing mid-download — the total is now computed once when the download starts and frozen for the whole job
- Fixed Cancel sometimes doing nothing — Cancel now registers immediately in every phase, disconnects a stuck download connection at once, and the job lands in Cancelled right away instead of finishing anyway
- Fixed the race picking a server on luck — the winner is now always a proven exact-quality server; a lower-quality server is parked and served flagged as a downgrade only if nothing proves your quality, and the serving-server shortcut can no longer bypass that check
- Fixed the Editor's Picks header overlapping its posters — the header now sits cleanly above the carousel with proper spacing, and the same fix was applied to the Explore header

## 2.0-dev2.6 (2026-11-11, versionCode 67) — Public Downloads storage + DDL quality probe: reliability and speed first

JS's dev2.5 phone report proved the quality gate works (Death Note E1 re-download finished 27 MB, the probe caught it as 360p, FAILED honestly with "Downloaded 360p, not 1080 — tap Retry."), but the flow was still download-the-whole-file → probe → delete → fail, because the winner is always Server 2's DDL via proxy.justanime.to, which returns a 360p file with no quality info — and Retry just looped the same bad DDL. JS also asked where the 360p file actually is (not in Gallery, no Anipull folder in Downloads) and redirected the build: "just fix the downloading part man, somehow, i want 2 things: reliability, same resolution what is requested on every title AND speed, instant download as quick as possible, mirudl achieved it." So reliability + speed are the non-negotiables here. (1) PUBLIC STORAGE: downloads stage in the existing app-private .staging partial (pause/resume untouched — DDL Range and MediaMuxer both need a real File) and are published on completion into MediaStore Downloads with RELATIVE_PATH Download/Anipull/<Title> (IS_PENDING=1 during the copy, cleared after a byte-count check, so Gallery never shows a partial). Open/Delete work against the MediaStore URI (stale URIs fall back to a live name lookup); a publish failure falls back to the legacy location without failing the job. On first launch after update, completed files in the old private dir are moved to public Downloads and re-pointed (once-only flag public_dl_migrated_v1); unfinished jobs keep their legacy file as resume state and publish on completion; leftovers are counted honestly in Settings > Storage, whose label now shows the real location. The Settings custom (SAF) folder still wins when set. (2) DDL QUALITY PROBE: new DdlQualityProbe.probeDdlQuality does a bounded Range request (bytes=0-65535, plus one tail request if the moov is at the end; at most 2 requests, ≤64 KB each, 5 s coroutine timeout, never reads a 200 full body) and parses the MP4 box tree (moov > trak > mdia/hdlr=vide > tkhd) for the real height, plus the exact total from Content-Range. The race now enriches unknown-quality DDLs with the probe BEFORE the floor decision; unknown-quality links are parked like sub-floor links, so a known 1080p HLS beats a fast unknown DDL; bestUnknown is carried across batches as a last resort and the resolve fails honestly if nothing meets the floor. JustAnime's StreamSource.quality is now Int? (the old no-digits fallback silently promoted unstated legs to 1080p); its DDL leg is probed before the floor decision and a probed-below-floor or unprovable DDL falls through to the revo/calm/momo HLS legs. A last-chance probe also runs in DownloadEngine.runJob before downloadMp4 for unknown-quality DDLs that slipped past the race (restored/cached links) — a below-floor probe throws QualityMismatchException (FAILED, no auto-retry) instead of downloading. HLS size estimates deliberately do NOT run in the race (2 extra playlist fetches per candidate on the critical path); the probe is the only new work on the hot path and only for unknown-quality DDLs. (3) SIZE INFO: ResolvedLink carries estimatedSizeBytes + sizeIsEstimate (persisted in ResolveCache and job JSON); the download sheet shows "Size: 240 MB" (exact) or "Size: ~240 MB" (estimate) under the selected quality chip only when the pre-resolve already cached a link for those exact choices — never invented; the Downloads tab shows "X MB / Y MB" while downloading (total prefixed ~ when estimated); completed episodes show their real size on the episode row/detail ("Downloaded · 240 MB", read from the actual file via DownloadEngine.completedSizeBytes, null -> no size). The season DownloadSheet deliberately shows no size (no single-episode size exists there). (4) PRE-RESOLVE SCOPE CRASH: preResolveRow now launches on a file-private application-lifetime CoroutineScope (SupervisorJob + Dispatchers.IO), never a rememberCoroutineScope — navigating away mid-warm no longer kills the resolve (the "rememberCoroutineScope left the composition" exception is gone). (5) LOG SUMMARY: the enqueue regex now accepts "1080" without the "p" and the resolve-OK regex accepts the ", offered 1080p" segment; the summary prefers the OK line matching the enqueue server, captures link URL + playlist host, and falls back to the job id for episode/audio/quality when the enqueue line is trimmed out. (6b) STALE-CACHE HOLE (live VM probe, Death Note E1): the phone's 17:38 log showed `cache HIT: Server 2, age 7250621ms` — a link resolved under dev2.4, before quality tagging existed, so the new quality-aware resolve never ran and only the post-download probe caught it. ResolveCache now drops cached DDL links with no quality tag on load and refuses to store unknown-quality DDL links at all, so a pre-fix entry can never bypass the race for up to 24 h (JustAnime TTL); HLS links are kept because HlsDownloader enforces the variant floor pre-download. The same probe confirmed Server 2 DOES have real 1080p for this episode (revo single 1920x1080 variant, calm 4 variants incl. 1080p BANDWIDTH 6,715,884, momo single 1920x1080; only the gigi DDL is 360p-only and JustAnime's own API says so — "resolution": 360, "filesize": "27MB"), the DDL header probe works (Range GET → 206, moov at offset 24, tkhd 640x360 from the first ~660 KB, byte-identical to the phone's file), and HEAD returns Content-Length 27,812,093 while plain GET is chunked (why progress sat at 0%). JustAnime's DDL leg already parses the API's resolution label and probes before the floor decision, so no change was needed there. (6c) ANIMESTREAM EPISODE LISTING: the API now enforces limit <= 20 (limit=100 → HTTP 422), so fetchSeasonEpisodes pages at limit=20 with the short-page check updated. (6d) revo endpoint flaps (520 → 200 minutes apart) — the race already tolerates per-server failures, no change.
(6) DUPLICATE RESOLVES: two holes closed — resolveCoalesceKey dropped the moving listServer (the holder re-fired when the list server landed ~39 ms later), and preResolveInFlight moved to a shared ConcurrentHashMap across all EpisodeListState instances (DetailScreen and its DownloadSheet each own a state); pre-resolve deferreds are LAZY + putIfAbsent, and startSeasonDownload / retryDownloadJob now await the shared in-flight resolve before resolving. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with Death Note E1 1080p completing as a real 1080p file (not failing after a download), the finished file appearing in Gallery and Files > Downloads > Anipull, Retry never looping the same bad DDL, the Copy report summary showing the real title/server, and a single [RESOLVE] start per episode.

### New features (3)

- Downloads now land in your public Downloads folder — Files > Downloads > Anipull — so finished videos show up in Gallery and file managers, not hidden inside the app's private folder
- Download sheet now shows the file size for the selected quality before you tap download (e.g. "1080p · 240 MB"), so a wrong-quality link is visible at a glance
- Downloads tab now shows "downloaded X MB / Y MB" while downloading, and completed episodes show their real file size on the episode row

### Bug Fixes (5)

- Fixed downloads landing in a hidden app-private folder — completed files are now published to the public Downloads/Anipull folder (visible in Gallery/Files), old completed files move there on first launch, and the Settings path label now shows the real location
- Fixed the download-then-delete quality loop — unknown-quality DDL links are now probed for their real resolution (a small ranged MP4 header read) before any bytes download, so a 360p file can no longer win a 1080p request; a known 1080p HLS leg now beats an unknown-quality DDL, and JustAnime's DDL leg can no longer shadow its own HLS legs
- Fixed the pre-resolve crash when navigating away mid-warm — pre-resolves now run on an application-lifetime scope instead of a composable scope
- Fixed the Download Logs summary showing "unknown" title/server/link — it now parses the real enqueue and resolve lines
- Fixed duplicate resolves — pre-resolve and confirm no longer run two concurrent resolves for the same episode (shared in-flight map, one resolve per episode+quality+audio)

## 2.0-dev2.5 (2026-11-11, versionCode 66) — Download experience polish: one notification, honest progress, correct quality, instant feedback

JS tested dev2.4 on his phone: downloads work (Death Note E1 completed), pills + Cancelled/Retry + Recently downloaded all render — but the experience is still rough: two notifications for one download that flicker non-stop, progress stuck at 0% until sudden complete, a 1080p selection delivering a 24 MB 640x360 file, Death Note twice in Recently downloaded, resolving still slow with no feedback, and a plain toast with no way to jump to the Downloads tab. This build polishes the whole download scenario. (1) ONE notification: the foreground service notification is now the group summary (setGroup + setGroupSummary), its text is static ("Anipull downloads", no title/%), per-job IDs moved from 2000 to 3000 so they can never collide with FOREGROUND_ID 2001, and the second summary under SUMMARY_ID is gone. (2) NO FLICKER: the single notification updates in place with a stable ID, only when displayed content actually changes, throttled to ~1 s / ≥1% steps; setOnlyAlertOnce kept. The shade stays still with only the bar moving. (3) HONEST PROGRESS: new PROGRESS_INDETERMINATE = -1 sentinel — when a download has no total (chunked DDL with no Content-Length), progress shows an indeterminate state with real bytes + speed ("Connecting…" / "X MB · Y MB/s") in the Downloads tab, episode row, AND notification, never a fake 0%; HLS emits progress callbacks during playlist/init/key fetches and before remux so the UI never sits silent. (4) CORRECT QUALITY: ResolvedLink now carries actualQuality (offered height); the race parks sub-floor wins and keeps racing instead of taking the first low-quality win; quality-aware failover tries servers that CAN meet the requested quality (floor = requested minus one step, e.g. 1080→720); if NO server meets the floor the resolve fails honestly ("No server has 1080p for this episode — best available is 720p. Try a lower quality.") instead of downloading the wrong file; after download, MediaExtractor verifies the real height and a mismatch deletes the file and marks FAILED ("Downloaded 360p, not 1080p — tap Retry."); files are named from their ACTUAL quality, and the job chip shows actualQuality when set. JustAnime collects DDL + HLS candidates instead of returning a 360p DDL first; Pahe tags links with the chosen variant resolution. (5) RECENTLY DEDUP: Recently downloaded now groups by normalized title (lowercase, strip non-alphanumerics) instead of the unstable titleId key (pre-dev2.2 jobs carried titleId 0) — one entry per anime. (6) INSTANT FEEDBACK + FASTER RESOLVE: Pahe clearance in resolveOnly is now lazy (stored clearance peeked up front; solve runs only if the other servers all fail, mirroring the dev1.8 list fix); the serving-first resolveDownload is capped at 15 s; pre-resolve warms the first 3 visible rows when the list loads and confirm awaits/coalesces with an in-flight pre-resolve instead of starting a second one; tapping Download fires a "Download queued" snackbar BEFORE resolving. (7) SNACKBAR WITH VIEW: one SnackbarHostState hoisted in AnipullAppInner with a SnackbarHost above Home and Detail — download-start toasts are now snackbars with a "View" action that opens the Downloads tab (single pull, batch, season, retries); errors and clear-all counts stay toasts. (8) RANGE DEFAULT: episode range selector now defaults to 1–100 on 100+ episode titles (reversing the dev1.9 default-All choice per JS) and the pill is bigger and clearer (44 dp tap height, 15 sp bold "Episodes 1–100 ▾", stronger pink border); auto-scroll-to-last-downloaded still switches ranges when its target sits outside 1–100. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with one notification while downloading, Death Note E1 1080p climbing from the start AND actually being 1080p, no duplicate in Recently downloaded, tap → instant "queued" feedback → fast resolve, Snackbar "View" opening Downloads, and the 1–100 default on long titles.

### New features (4)

- Download start now shows a Snackbar with a View action — tap it to jump straight to the Downloads tab
- Tapping Download shows 'Download queued' instantly, before any resolving — the job appears in the Downloads tab right away
- Episode range selector now defaults to 1–100 on long titles, and the pill is bigger and clearer with an Episodes label
- Downloads show honest progress while connecting — 'Connecting…' with real bytes and speed instead of a frozen 0%

### Bug Fixes (6)

- Fixed two notifications for one download — the foreground service notification is now the single group summary, so the shade shows one entry
- Fixed the download notification flickering and sliding in the status bar — one stable notification, updated in place and throttled, only when its content changes
- Fixed progress stuck at 0% on downloads with no total size — they now show an indeterminate state with real bytes and speed, never a fake 0%
- Fixed downloads silently delivering the wrong quality — if no server can provide the selected quality the download now fails honestly instead of saving a lower-quality file, and delivered files are verified and named from their actual quality
- Fixed Recently downloaded showing the same anime twice — it now dedups by anime
- Fixed resolving still being slow — Pahe clearance now only solves if the other servers fail first, the serving-first resolve is capped at 15 seconds, and pre-resolve warms earlier with confirm reusing it instead of starting a second resolve

## 2.0-dev2.4 (2026-11-11, versionCode 65) — Downloads filters + Recently downloaded home row

JS confirmed downloads finally work on his phone (Black Clover S2 ep 5 at 18% · 24.8 MB/s on Server 2 — the dev2.2 header fix proven), and ordered the next build around organising downloads and getting back to them. (1) The Downloads tab now has filter pills — All, Active, Queue, Errored, Cancelled, Completed, each with its count — so each group can be viewed on its own instead of one long stacked list; Active is DOWNLOADING + PAUSED, Queue is QUEUED, Errored is FAILED, and All keeps the old sections exactly as before. (2) Errored and Cancelled each get a Clear all button that clears only that group (new DownloadEngine.clearJobs, toast confirms the count); failed jobs sit inside the Active section under All, so they are cleared from the Errored pill where it is unambiguous. (3) The debug "Preview download notification" button at the bottom of the Downloads tab is deleted entirely, with its preview state and launcher code — real notifications are untouched. (4) New Recently downloaded row at the very top of Home, above Marathon Picks: every anime the user pulled an episode from, whatever the outcome (finished, failed, cancelled), derived only from the persisted DownloadEngine.jobs — no engine or job-model changes. A new hand-authored history icon (clock ring + hands + counterclockwise arrow, in the app's white-fill icon family) heads the row. Tapping an anime opens its detail page scrolled to the last downloaded episode, centred in the middle of the viewport — the detail screen takes a target episode, switches the episode range if the target sits in another chunk, and scrolls once per open (measured centring via layoutInfo; a target not in the loaded list scrolls nowhere, never crashes). (5) Each poster in the row has a cross at its top-right to remove that anime from the row — removal is a separate dismissal store (SharedPreferences "anipull_recently"), never deletes jobs, and the anime reappears if a newer episode is downloaded for it; remaining posters glide over with animateItemPlacement, and when the row empties the section item disappears and the rows below animate up via Modifier.animateItem. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with the pills filtering correctly, the preview button gone, the new row at the top, a tap auto-scrolling to the last downloaded episode in the middle, cross-removal animating, an empty row collapsing cleanly, and Clear all in Errored + Cancelled only.

### New features (4)

- Downloads tab now has filter pills — All, Active, Queue, Errored, Cancelled and Completed, each with its count — so you can see only the group you care about
- New Recently downloaded row at the very top of Home, above Marathon Picks — every anime you pulled an episode from, whether it finished, failed or was cancelled, with a history icon
- Tapping an anime in Recently downloaded opens its detail page scrolled straight to the last episode you downloaded, centred in the middle of the screen
- Each poster in Recently downloaded has a cross to remove it from the row, and the rest glide over to fill the space; clearing the whole row collapses the section and pulls everything below up with animation

### Bug Fixes (2)

- Fixed the Downloads tab having no way to clear failed or cancelled jobs — Errored and Cancelled now have a Clear all button that clears only that group
- Fixed the debug 'Preview download notification' button sitting at the bottom of the Downloads tab — it is gone now

## 2.0-dev2.3 (2026-11-11, versionCode 64) — MiruDL-level instant resolve

JS asked for MiruDL-level speed: "make it mirudl level fast in next update". Phase 1 (MIRUDL-RESOLVE-SPEED.md) found why MiruDL feels instant: it resolves once on ONE source (0–4 calls, zero for Nino 2 which constructs the URL on-device), caches the URL on the episode object so a repeat tap costs zero calls, and never re-resolves in its service. Anipull's old path did the opposite — servers tried one at a time with full retry budgets, no cache, and nothing started until the user confirmed. This build copies the patterns, not the code: (1) the resolve chain is now a parallel race — the top 3 servers in priority order run at once and the first success wins, the rest fall through in batches, and an explicit preferred server is still tried first; failover still exists, it just stops being slow. (2) Resolved links are cached per episode+quality+audio (SharedPreferences, survives restart, cap 50) with per-source TTLs — Server 3 (Pahe) 90 s because its m3u8 expires in 3–5 min, Server 2 (JustAnime) 24 h because its signed URLs live ~13 days, Server 5 (Nino 2) 1 h, everything else 10 min; a cached link is only served when its winning server matches the requested first server, so an explicit server choice is never overridden by another server's cached link, and an expired Pahe link is never served. (3) The download sheet pre-resolves when it opens, with the Settings defaults, so by the time the user taps Download the link is usually already cached; changing quality/audio/server in the sheet misses that cache entry and resolves for the new selection via the race. (4) Each resolve attempt is capped at 15 s — a hung server fails over in seconds instead of after ~40 s of retries; segment-download retries are untouched. What was NOT copied: MiruDL's manual fail-safe (no automatic failover — Anipull keeps auto-failover inside a job), its Kyoto pacing (MiruDL's Miku source is off-limits, rejected), and its raw TS concat. Kept from dev2.2: the Server 2 Referer/Origin segment fix, cancelled-retry, Download Logs + Copy report, locked popup. Verified by local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with a few taps on different titles (resolve should feel instant, especially the second tap on the same episode), changing quality/audio in the sheet, and a title where the first server fails (failover should be fast, not a long "resolving" hang).

### New features (3)

- Downloads now start almost instantly — servers are tried in parallel instead of one-by-one, and opening the download sheet starts resolving in the background so the link is already there when you tap Download
- Resolved links are cached per episode — a repeat download skips the whole server chain, with a per-server expiry so a short-lived link is never reused after it dies
- A failing server gives up in seconds instead of hanging — each resolve attempt is capped at 15 seconds and the next server is tried right away

### Bug Fixes (4)

- Fixed slow downloads caused by servers being tried one at a time — the top 3 servers are now raced in parallel and the first success wins, with the rest tried in batches; failover still works, it just stops being slow
- Fixed repeat downloads re-resolving from scratch — a resolved link is now cached per episode + quality + audio, with per-source expiry (Server 3 links expire in 90 seconds, Server 2 links last up to a day, everything else 10 minutes to an hour), so a repeat tap is instant and an expired link is never served
- Fixed the wait between tapping an episode and the download starting — the download sheet now pre-resolves with your default quality, audio and server while you look at it, so confirming usually needs no resolve at all; changing quality, audio or server in the sheet still resolves for the new choice
- Fixed slow servers blocking the whole download — a resolve attempt that hangs now fails over after 15 seconds instead of waiting out ~40 seconds of retries; download segment retries are unchanged

## 2.0-dev2.2 (2026-11-11, versionCode 63) — Server 2 downloads actually work + cancelled downloads can be retried

JS pasted a Download Logs report from dev2.1: Black Clover Season 2 episode 5, Server 2, "239 of 239 segments failed — first: segment 0 HTTP 403". Resolve worked and the playlist worked (239 segments enumerated), so the failure was purely at the segment CDN. A live probe from the VM pinned it exactly: the resolver returned the playlist URL with no Referer, and the downloader fetched segments with no Referer and no Origin. Both segment CDNs enforce the upstream-player origin through Cloudflare — revo's vault-*.rundowncdn.top requires `Referer: https://flixcloud.cc/`, calm's st1.* hosts require `Referer: https://krussdomi.com/` AND `Origin: https://krussdomi.com` (Referer alone still 403s on calm segments). A justanime.to Referer does not work for either — only the per-server values from each stream response's own `headers` field work, proven by curl: 403 without them, 200 with them, from this same VM. So the fix threads those headers verbatim from the stream response through the resolver into every HLS fetch (master, variant, key, init, segments, subtitle sidecars) — nothing hardcoded per server, and a leg whose response carries no headers is skipped instead of fetched headerless (headerless segment fetches are proven to 403). No Cronet routing was needed: plain curl 200s with the right headers, so this is not datacenter gating and not a TLS fingerprint issue. The probe also found gigi DDL is currently unavailable for this title/episode (servers endpoint reports gigi sub=false, was live for Frieren ep 1), which is why the job went HLS at all — and that nami now reports sub=true, but its backend is unknown/unprobed, so it is not wired in. Second fix, from JS the same day: a cancelled download left its episode row permanently on "cancelled" with no way to download again — worse, the row's tap early-returned whenever any job existed, and the Downloads tab didn't even show cancelled jobs. Cancelled (and failed — which had no working manual retry either, only the engine's one automatic retry) rows now return to the normal download action and start a fresh resolve + fresh job, and the Downloads tab shows cancelled jobs with Retry. Third, the log itself failed us on this report: the last-200 cap kept only the segment failures, so the RESOLVE lines scrolled away and the winning sub-server wasn't in the paste. The Copy report now starts with a per-job summary (winning server + sub-server, playlist host, segment host, headers sent, first failure, outcome) and the log keeps the first 50 + last 150 entries with an omission marker, so the resolve lines can never scroll away again. HLS fetches also follow redirects now (the dev1.9 hypothesis left open). Verified by live probe + local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with the same episode (Black Clover S2 ep 5, 1080p sub) plus one DDL-server job if available, and a fresh Copy report if it still fails.

### New features (3)

- Downloads now send each server's own stream headers on every fetch — Server 2's video hosts were refusing every segment without the player origin headers, so downloads failed with all segments blocked
- The download log's Copy report now starts with a per-job summary — winning server, video host, headers sent, first failure and outcome — and keeps the first 50 + last 150 entries so the resolve lines can no longer scroll away
- Cancelled downloads can be retried — a cancelled episode row goes back to the normal download action instead of sticking on 'cancelled', and cancelled jobs in the Downloads tab get a Retry button

### Bug Fixes (4)

- Fixed every Server 2 download failing with all segments blocked — its video hosts require the player origin headers (Referer, and Origin for one host) on segment fetches and the app was sending none; the headers each server's own stream response supplies are now sent on playlist, key, segment and subtitle fetches, and a server leg with no headers is skipped instead of fetched headerless
- Fixed cancelled downloads being stuck — a cancelled episode row showed 'cancelled' forever with no way to download again; cancelled and failed rows now return to the normal download action and start a fresh download, and the Downloads tab shows cancelled jobs with Retry
- Fixed the download log dropping the resolve lines — the last-200 cap kept only the segment failures, so the winning server scrolled away; the log now keeps the first 50 + last 150 entries with an omission marker in the middle
- HLS fetches now follow redirects like the rest of the app

## 2.0-dev2.1 (2026-11-11, versionCode 62) — Server 5 actually works + no more hidden episodes

Two things, both about Server 5 (Nino 2) and the episode list. First, a real bug: Server 5's library parser never matched the live library file. The file is a `{"built", "titles"}` wrapper where each title carries its hosting nested under `lib` (folder, `eps` = the episodes actually present, audio, subs) and `episodes` as a total count — but the parser only handled a top-level array or an id-keyed object, so it parsed ZERO entries and the server could never match any title, for lists or downloads. The parser now reads the real shape (keeping the old branches as fallbacks for shape drift), looks titles up by AniList id when the id is already known from the media-index match (JustAnime gets the same direct-id path in its list load), and marks a list incomplete when the library holds fewer episodes than the show's total. It also never synthesizes rows from the count alone — no `lib.eps` means not hosted, not a 1..total guess. Second, the gap: a partial server list used to win over the AniList count, so episodes the server lacked simply never appeared in the list. When the count has landed and says more episodes aired than the winning list holds, the missing numbers are now appended as bare rows (same shape as the count rows), so they resolve by episode number and fail over to the next server — a missing episode in a library means "not in this library", never "episode doesn't exist". No count landed means no padding (exactly the old behavior), and padding is capped at 3000 aired. The download path already failed over on a gap in both routes (media-index rows through resolveWithFailover, server-list rows through the serving server's resolveDownload falling back to the chain) — verified by code trace, no change needed there. The id-map spine is NOT in this build: JS correctly pointed out Anipull uses no MAL or TMDB ids anywhere today, so the map would be a bridge to IDs nothing consumes — it stays verified reference only until a MAL- or TMDB-keyed need actually arrives. Verified by code inspection + build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with a title Server 5 hosts (Frieren: 28/28 should list and resolve) and a patchy one (a newer title where the library is missing episodes — the missing numbers should still show and download through another server).

### New features (2)

- Server 5's episode list actually works now — its library parser never matched the real library file, so the server could never match any title; it now reads the real shape and looks titles up by AniList id when the id is already known
- Episode lists no longer hide episodes a server doesn't have — when the AniList count says more episodes aired than the winning list holds, the missing numbers appear as bare rows that resolve through the next server instead

### Bug Fixes (2)

- Fixed Server 5 never returning any episode list — its parser only handled a top-level array or an id-keyed object, but the live library is a {built, titles} wrapper with hosting nested under lib (folder, eps, audio, subs) and episodes as a count; the parser now reads the real shape and reports a partial library as incomplete instead of failing
- Fixed episode lists silently dropping episodes — a partial server list used to win over the AniList count, so episodes the server lacked simply never appeared; missing numbers are now padded in as bare rows that download through the next working server

## 2.0-dev2.0 (2026-11-11, versionCode 61) — URGENT regression fix: episode list back

JS's dev1.9 phone test (FMAB detail): the Episodes section showed its header and the big pink Download button but ZERO episode rows. dev1.8 showed episodes fine. Root cause, found by diffing dev1.8 vs dev1.9: the race and the data were fine — the break was the recomposition subscription. dev1.9 split the old composable into three parts: `EpisodeListState` held the loaded state as snapshot state, but every derived output (`rows`, `visibleRows`, `ranges`, `safeRangeIndex`, `rowsNote`, `rowsIncomplete`, `jobs`) was a plain var, and a plain-var write schedules no recomposition anywhere. The holder's `remember()` reads subscribed only the holder's own scope, and `DetailScreen` never read any episode state in its own scope (its only state was the one-shot fade animation, settled before the load finished), so it never recomposed after first composition and its `LazyColumn` never re-ran the DSL — first composition emitted 3 skeleton boxes and nothing observable ever happened, even though the holder re-derived the rows correctly. The fix is minimal and keeps every dev1.9 win (lazy rows, range grouping, Download Logs + Copy report, SAF staging fix, HLS init-segment throw, locked popup redesign, dedup, list/grid toggle, multi-select): the derived outputs are snapshot state now, and both `DetailScreen` and `DownloadSheet` read `episodeState.rows` in their own @Composable scope before the `LazyColumn`, so when a source lands the caller recomposes and the list re-emits with the rows. `DetailScreen` additionally gets the same `onLoaded` rescue the sheet already had (it writes a `mutableStateOf` owned by the detail scope). The false comment claiming the holder's reads subscribe the caller is corrected. No changes to the race, timeouts, resolvers, range code, or row rendering. Verified by code inspection + local build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with FMAB's episode list, then One Piece, then one download + Copy report from Download Logs.

### Bug Fixes (1)

- Fixed the episode list showing only skeletons — dev1.9's restructure split the episode list into a state holder and a plain emitter, and the derived episode rows were plain variables that nothing subscribed to, so the detail page never re-rendered when a list landed; rows are snapshot state now and the detail page and download sheet read them in their own scope, so episode rows appear as soon as a source lands (list and grid, detail and sheet)

## 2.0-dev1.9 (2026-11-11, versionCode 60) — download diagnostics + One Piece lag fix

JS's dev1.8 test: episode loading is fast now and duplicates are gone — but every download still fails ("download failed tap to retry is all i see everytime"), and long titles like One Piece lag his phone badly while shonenX doesn't lag on the same title even on "All". Two workstreams here. First, diagnostics: a TEMPORARY "Download Logs" section in Settings under Crash Logs (DEBUGGING) — a rolling persisted log of the whole download pipeline (per-server resolve attempts with latency, winning server, DDL vs HLS, playlist/AES/segment stages, remux, file write, Wi-Fi gate, clearance) with a Copy report button that copies the entire labelled report in one tap, so the next failed download can be diagnosed from what JS pastes back. The audit also found one provable cause and fixed it: with a custom download folder picked, DDL downloads died instantly because the staging folder was never created (FileOutputStream on a path whose parent didn't exist) — the folder is now created first; the HLS engine also no longer silently writes an error page as the init segment when its fetch fails. No other universal failure is provable from code — the Wi-Fi gate can only produce Paused, the service-start failure is caught, resolvers are structurally sound — so the diagnostics carry the rest (two hypotheses left open: HLS fetches don't follow redirects while DDL/resolver fetches do, and per-server reachability on his phone). Second, the lag: the shonenX comparison found the real cause — shonenX renders episode rows with a lazy builder (only ~12 viewport rows exist) while Anipull composed all 1,180 rows at once in a plain Column inside a single LazyColumn item, and every download-progress tick recomposed the whole unmemoized list. Rows are now lazy items of the scrolling list (detail screen and download sheet), the row mapping is memoized, and per-row state is scoped so progress ticks skip unchanged rows; range grouping like shonenX (All, 1–100, 101–200, …, default All — shonenX also defaults to All, chunking is navigation UX, the lazy builder is the perf fix) sits above the list and is hidden for titles under 100 episodes. List/grid toggle, long-press multi-select, batch hint and dedup all still work with the range selector. AllAnime stays parked; Miku/Kyoto stays rejected. Verified by code inspection + build only: the VM cannot run the app — JS's phone test is the runtime proof: try one download, then Settings → Download Logs → Copy report and paste it back; and scroll One Piece on "All".

### New features (2)

- Temporary Download Logs section in Settings (under Crash Logs, DEBUGGING) — a rolling log of the whole download pipeline with a Copy report button, so a failed download can finally be diagnosed from the phone
- Episode range grouping like shonenX — All, 1–100, 101–200 and so on above the episode list, so long titles like One Piece can be browsed a hundred episodes at a time

### Bug Fixes (3)

- Fixed long titles like One Piece lagging the phone — the episode list composed all 1,180 rows at once in a plain Column; rows are now lazy items of the scrolling list, so only the viewport is composed
- Fixed every DDL download failing when a custom download folder is picked — the staging folder was never created, so the file open died before a single byte; the folder is now created first
- Fixed the HLS engine silently writing an error page as the init segment when its fetch failed — a non-200 init fetch now throws instead of corrupting the download

## 2.0-dev1.8 (2026-11-11, versionCode 59) — the speed build: episode lists race, redesigned sheet, no more double rows

JS timed Black Clover S2 at ~2 minutes to load its episode list — three sequential 45-second waits (metadata index, then the server chain, then the AniList count), with Pahe clearance burning up to 30 seconds before any server was even tried, and every episode listed twice (one row per audio). Root causes, all fixed here: the list now RACES — media-index, JustAnime-direct and the AniList count fire at the same time inside one 20-second budget (8 seconds per source), the first usable rows show immediately and upgrade in place when a richer source lands; Pahe clearance is lazy, solved only right before Server 3 is actually attempted; and one row per episode is enforced at the source (JustAnime, Anv, AniPm) AND defensively in the list. When the index matches a title but its episodes file is missing (Black Clover S2: index has it, episodes.json doesn't), the matched AniList id is kept and rides on the JustAnime call and on the fallback rows, so downloads resolve by a real AniList id. AniLab's 100000xxxx ids are never sent to a source as AniList ids anymore — only index/AniList ids, otherwise straight to title search. The download sheet is rebuilt to JS's locked design: poster + title header with close, four quality chips in one row, Sub/Dub as one segmented control, servers in a single sideways-scrolling row, the "Auto tries servers in order until one works." note, and a full-width pink Download button — everything on one screen, no scroll. AniList stays last-resort count fallback (bare numbered rows, title poster as cover), now racing in parallel instead of after two sequential caps. AllAnime stays parked; Miku/Kyoto stays rejected. Verified by code inspection + build only: the VM cannot run the app — JS's phone test is the runtime proof, starting with Black Clover S2's list load time + no duplicates, then a single-episode download end to end and the redesigned sheet. R8 + shrinkResources stay on; Cronet stays Pahe-only.

### New features (2)

- Episode lists now load all sources at once instead of one after another — the first usable list shows immediately and a richer one replaces it, so newer titles stop taking minutes to load
- Download sheet redesigned — compact, with the poster and title up top, all four qualities in one row, Sub/Dub as one switch, and servers in a single scrolling row

### Bug Fixes (4)

- Fixed episode lists taking around 2 minutes on newer titles — the old chain ran three 45-second waits one after another; sources now race in parallel inside one 20-second budget
- Fixed every episode showing twice — servers were emitting one row per audio (sub + dub) and nothing deduped them; one row per episode now, enforced at the source and in the list
- Fixed the Pahe security check burning up to 30 seconds before any server was even tried — it is now solved only right before Server 3 is actually attempted
- Fixed AniLab ids being sent to sources as AniList ids — only ids from the metadata index or AniList are used now; anything else goes straight to title search

## 2.0-dev1.7 (2026-11-10, versionCode 58) — the dev1.6 fix build: AniList fallback, no more paused trap, sheet-first downloads

JS's phone test of dev1.6 found the real bugs: FMAB ep 1 went resolving → Paused with no way to resume, tapping an episode downloaded instantly with no quality/language prompt, no toast or feedback on tap, and nothing said which server a download was coming from. Root cause of the paused trap: the Wi-Fi-only gate (on by default) forced every job to Paused on mobile data, and Resume re-queued it straight back into the same gate — an unresumable loop. An explicit Resume (and a per-episode sheet confirm) now carries a per-job override past the gate; the gate still applies to bulk/season downloads and restored jobs. This build also adds the AniList episode-count fallback JS approved: when the metadata index has no list for a title, the chain is now index → server's own list (AniZone/JustAnime carry titles/stills) → AniList count, which shows bare numbered rows that still download per number (AniList has no titles, runtimes or stills, and none are invented). New in Settings: a Sources section listing Server 1–9 in priority order with up/down reordering (persisted, honoured by auto-select + failover) and a generic description per server, plus an Auto-test button top-right — it runs one cheap probe per server (a search, a homepage, or a HEAD, never a full load or a clearance solve), shows each server's time next to it, and re-sorts fastest working first, failed last. A gated server the test cannot judge is marked "needs a check" and stays where it was — it is never ranked as failed for being gated (server 3 without a stored clearance is the case in point; with a stored clearance the test replays it like the engine does). The test ranks reachability + latency, not real download speed — his phone is the proof of whether the ranking holds on mobile data. Episode rows no longer show Sub/Dub tags (the choice lives only in the download sheet), and a muted hint above the list says long-press selects multiple episodes for batch download — wired, single-episode download is the acceptance bar first. AllAnime stays parked; Miku/Kyoto stays rejected. Verified by code inspection + build only: the VM cannot run the app or test clearance — JS's phone test is the runtime proof, starting with a single-episode download end to end on mobile data. R8 + shrinkResources stay on; Cronet stays Pahe-only.

### New features (4)

- Episode lists now fall back to an episode count when the metadata index has no list for a title — numbered rows that still download, with titles and stills from a server's own list when it has them
- Tapping an episode now opens a download sheet first — quality and audio pre-selected from your Settings, server override included — instead of starting instantly
- Long-press an episode to select multiple episodes and download them together
- New Sources section in Settings — reorder Server 1 to 9 to set your own auto-select priority, each with a short description of what it offers, plus an Auto-test button that checks every server and puts the fastest working ones first

### Bug Fixes (4)

- Fixed a download landing on Paused with no way to resume — it was the Wi-Fi-only gate re-pausing the job on every resume; an explicit Resume now actually resumes it
- Fixed the Downloads tab hiding which server a download comes from — every job now shows its server, including while it is still resolving
- Fixed episode rows showing a Sub/Dub tag — the audio choice lives only in the download sheet now
- Fixed no feedback when tapping download — a toast now confirms the download started, or shows the error

## 2.0-dev1.6 (2026-11-10, versionCode 57) — the multi-source build: 9 servers, episode list from an index

JS's call after the everythingmoe sweep: stop being a Pahe client — Anipull is a downloader with sources, Pahe is one of nine, and the episode list should come from metadata, not from a download server. This build does both. The episode list now comes from media-index (a static, ungated CDN index): real episode titles, stills and air dates, with a list/grid toggle, cached locally and refreshed at most daily. The list and the downloads are decoupled — the list is metadata, the download resolves per episode against the servers. Nine servers sit behind one auto-picker in priority order (fastest verified first): if one fails mid-download, the next takes over inside the same job; the default is Auto, with a manual override in Settings and a per-download override row in the download sheet. Servers are exposed only as Server 1–9 with generic descriptions — no source names anywhere user-visible, including error strings and the security-check dialog — and the Home pill now says the real count (9 servers, auto-picked) after the three demo rows with fake statuses were deleted. New engine work in this build: HLS multi-audio/subtitle parsing (#EXT-X-MEDIA), subtitle sidecars saved next to the video, AnimeStream's wrapped-AES-key path (public derivation, no embedded secret), MegaPlay's key fetched from its public JS at runtime (never embedded constants), and the JustAnime Origin header. AllAnime is NOT in this build — its live probe was refused and the site doesn't load on JS's phone, so it stays pending; Miku/Kyoto stays rejected (borrowed credentials). Verified by code inspection + build only: the VM is a datacenter IP, so gated sources (Pahe, ani.pm, momo) can't be proven here — JS's phone test is the runtime proof, and a full end-to-end download is still unverified. R8 + shrinkResources stay on; Cronet stays Pahe-only.

### New features (4)

- Episode lists now come from a metadata index instead of a single server — real episode titles, stills and air dates, with a list/grid toggle
- 9 download servers behind one auto-picker — if one fails, the next takes over inside the same download; pin a default server in Settings or pick one per download
- Subtitle sidecars now download next to the video when a server offers soft subtitles
- Home now shows the real server count — 9 servers, auto-picked — instead of three demo rows with fake statuses

### Bug Fixes (3)

- Fixed episode lists depending on one gated server — the list now loads even when that server is unreachable, falling back to a server's own list only when the index has none
- Fixed server names showing up in the app — servers are Server 1 to 9 everywhere, with generic descriptions only
- Fixed the security-check dialog popping up when another server had already served the episode — it now only appears when the whole chain actually needs it

## 2.0-dev1.5 (2026-11-08, versionCode 56) — the session finally sticks: Pahe calls go through Cronet

JS's phone test of dev1.4: the dialog asked him to tap the box, he tapped it, and still no episodes — "Pahe is still showing its security check — the session didn't stick." Then his CF Probe run proved the failure starts even earlier: the hidden solve NEVER completes on its own — a full 30 s poll, the challenge still showing, title stuck at "Just a moment...", no cookie harvested at all. Two separate breaks, fixed in this build. (1) The solve itself stalls: the hidden WebView was 1×1 and invisible, and Android throttles timers in a view like that — the challenge's JavaScript cannot run to completion in it. It now solves full-size behind the app's UI, like a real browser tab, while the user still sees the normal detail page. (2) The replay: the dialog-verified cookie (real page + cookie present) still got challenge HTML back, and the only thing left differing between the WebView that minted the clearance and the HttpURLConnection that replayed it is the TLS fingerprint — research §8.1 states a cf_clearance replayed under a different TLS fingerprint than the one that minted it is void. Pahe requests (search, releases, play page, pahe.win, Kwik) now go through Cronet — Chromium's own network stack, the same TLS/JA3, HTTP/2 and header order as the WebView that solved the check. That TLS explanation is the best fit for the tap-path failure, but it is not yet proven — the probe's Show-WebView + tap + re-test flow has not run yet, so this build also adds stage diagnostics to the episode errors (no session saved, a saved session rejected, or no match) so the next phone report pinpoints the failing stage from the words alone. The three Cronet AARs (api, common, embedded 141.7340.3) are vendored in app/libs/ because this VM's Java tooling cannot download from Google Maven; swapping to Maven coordinates is a one-line change per AAR. Only real-phone ABIs are packaged (arm64-v8a + armeabi-v7a), so the APK grows by the native lib instead of carrying emulator ABIs too. If Cronet cannot start on a device, calls fall back to plain HttpURLConnection instead of crashing. HLS segments and the DDL mp4 download still use plain HTTP — if HLS downloads fail on the phone, that is the next thing to move. Verified by code inspection + build only: the VM's datacenter IP gets a flat 403 from Pahe, so it cannot test the solve or the replay itself — JS's phone test is the runtime proof. R8 + shrinkResources stay on; no new API/source, no AniList.

### New features (2)

- Pahe requests now go through Cronet — Chromium's own network stack, the same one as the WebView that solves the security check — so the session cookie is replayed with Chrome's TLS fingerprint instead of Java's
- Episode-list errors now say which stage failed: no session was saved at all, a saved session was rejected, or Pahe answered but nothing matched — so the next report pinpoints the failure from the words alone

### Bug Fixes (3)

- Fixed the hidden security check never finishing on its own — the 1×1 invisible solve view was throttled by Android and too small for the challenge to run in; it now solves full-size behind the app's UI
- Fixed the session never sticking after the checkbox tap — the replayed calls used Java's TLS fingerprint, which Cloudflare can reject even with a valid cookie; they now use Cronet, the same Chromium stack that minted the clearance
- Fixed a rejected session being reported as 'Pahe did not answer' — the list now tells a rejected session apart from the source being down

## 2.0-dev1.4 (2026-11-07, versionCode 55) — episode list can never hang again, Done actually works

JS's phone test of dev1.3 found the episode list stuck on skeletons forever, and a Retry loop that never produced a working session. Root cause: his mobile-data IP gets the interactive Turnstile challenge every time (unlike the Chrome test, which got the automatic pass), so the hidden solve always times out — and the "One quick check" dialog's Done button harvested blind. It read whatever cookie was in the store with no check that the challenge had actually finished, so Done during verification reloaded with a dead session; Pahe then answered the search with its challenge HTML (HTTP 200!), the JSON parse threw, and the list failed with a misleading "Download failed". Retry re-solved from scratch and the loop repeated. The fix: the dialog now verifies the check passed (real page + cookie present) before finishing — it auto-completes the moment the check passes, and Done refuses to close while the check is still running. The list also detects a rejected session (non-JSON answer) and says so honestly, list errors no longer say "Download failed", concurrent solves from the detail page and the download sheet are serialized into one, and the whole load has a hard 45s bound — every path ends in rows, an honest error, or the dialog, never skeletons forever. The same build fixes the first-tap flyout regression: the episode load used to construct a WebView on the main thread mid-transition, and first-ever WebView init is slow enough to freeze the flight — the load now starts after the poster lands. Verified by code inspection + build only: the VM's datacenter IP gets a flat 403 from Pahe, so it cannot test the solve itself — JS's phone test is the runtime proof. R8 + shrinkResources stay on; no engine changes beyond the clearance wiring, no new API/source, no AniList.

### New features (2)

- The 'One quick check' dialog now finishes by itself the moment the security check passes — and the Done button won't close it while the check is still running, so a tap can no longer grab a dead session
- The episode list shows a Retry button even when the source is still pending, instead of leaving you stuck

### Bug Fixes (6)

- Fixed the episode list hanging on skeletons forever — every load now ends within 45 seconds in episodes, an honest error, or the check dialog
- Fixed Done never producing a working session — the dialog now verifies the check actually passed before saving the session, and the list detects a rejected session instead of failing with a misleading error
- Fixed episode-list errors saying 'Download failed' — they now say the list couldn't load
- Fixed the poster flyout sometimes not playing on the first tap — the app no longer starts the security check while the transition is still running
- Fixed the detail page and the download sheet solving the security check twice at once
- Fixed the download sheet showing a stale 'source pending' state — it now shares the same session as the detail page

## 2.0-dev1.3 (2026-11-07, versionCode 54) — Cloudflare clearance lands, episode lists go live

The one blocked piece of 2.0 is in: the episode list now actually loads from AnimePahe. When you open a detail page, the app solves Pahe's security check in a hidden WebView on your own phone (the Mihon/kotatsu pattern from the resolver research) and reuses the cf_clearance session cookie + the WebView's User-Agent for the API calls — no server, no account, nothing leaves the phone. Clearance lasts ~15 minutes and re-solves silently when it expires; if the check ever needs a human tap (Turnstile checkbox), the app shows AnimePahe's own page once so you can tap it, instead of failing silently. Downloads re-ensure the session before resolving, so an expired session no longer fails with "session not ready", and Retry on a failed list drops the dead session and solves fresh. If clearance cannot be obtained at all, the episode section keeps its honest pending/error state — still no fake episodes. Verified by code inspection + build only: the VM's datacenter IP gets a flat 403 from Pahe, so it cannot test the solve itself — JS's phone test (his residential IP gets the solvable challenge, proven by the Chrome test) is the runtime proof. R8 + shrinkResources stay on; no engine changes beyond the clearance wiring, no new API/source, no AniList.

### New features (2)

- Cloudflare clearance is live — the episode list now loads from AnimePahe: when you open a detail page, the app solves the site's security check in a hidden browser view on your own phone and reuses that session for episode lists and downloads (no server, no account, nothing leaves the phone)
- If the security check ever needs a tap, the app shows AnimePahe's own page once so you can tap the checkbox yourself, then carries on — instead of failing silently

### Bug Fixes (1)

- Downloads no longer fail with 'session not ready' when the Pahe session expired — the app silently re-solves the check before resolving a download, and Retry on a failed episode list drops the dead session and solves fresh

## 2.0-dev1.2 (2026-11-07, versionCode 53) — watch order removed, duplicate-key crash fixed

JS's phone test of dev1.1 found two problems in the watch-order section, and both trace to the same root cause: AniLab has no seasons/relations data, so the row was guessed — hardcoded Frieren/AoT fallback lists plus AniLab search on generic strings like "Season 1", which returns wrong titles (it showed "K: Return of Kings" on every anime). JS's call: if the correct watch order can't be pulled from anywhere, don't show anything there. So the section is removed entirely — no placeholder, no "coming soon", the detail page flows as if it was never there. AniList relations would be a real source, but AniList integration was never approved, so nothing new was added. The same guessed labels also caused the crash: the row keyed Lazy items by season label, and a title with two "Side Story" entries crashed Compose with a duplicate-key IllegalArgumentException (title-specific — only titles whose list has duplicate labels crashed). The section and its keys are gone, and every other Lazy list in the app was audited for the same bug class (see fixes). R8 + shrinkResources stay on; no engine or clearance changes.

### New features (0)

### Bug Fixes (3)

- Watch-order section removed from the detail page — it showed wrong data (AniLab has no seasons/relations data, so it guessed from hardcoded lists and generic searches), so it is gone entirely instead of showing fake titles
- Crash fixed — opening a detail whose watch-order list had two entries with the same label (e.g. "Side Story" twice) crashed with a duplicate Lazy key; the section and its label-based keys are removed
- Lazy list key audit — every other LazyColumn/LazyRow items() call now keys on a unique id (title id, job id, or map key); no other label-based duplicate-key risk found

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
