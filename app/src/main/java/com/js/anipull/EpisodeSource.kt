package com.js.anipull

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// 2.0-dev1.6 ENGINE — episode sources (multi-source).
// A source turns an AniLab title into an episode list and resolves one
// episode (+quality) into a downloadable link. The engine never talks
// to a source directly: DownloadEngine only ever sees ResolvedLink.
//
// HARD RULE (do not "fix" this): a source takes an already-obtained
// clearance cookie as a plain String input and NEVER solves a
// challenge itself — no WebView, no CookieManager harvesting, no
// cf_clearance handling, no Cloudflare bypass of any kind. When the
// cookie is null the Pahe source returns SourcePending without
// making any network call.
//
// IDENTITY RULE: `sourceName` is internal/logging ONLY and must never
// reach the UI. What the user sees is the server NUMBER (1..10) and the
// generic `serverDescription` — no source names anywhere user-visible.

data class EpisodeInfo(
    val number: Int,
    val title: String?,
    val duration: String?,
    val audio: String, // "jpn" = sub, "eng" = dub
    val fansub: String?,
    val episodeSession: String,
    val animeSession: String,
)

sealed class EpisodeResult {
    data class Loaded(val episodes: List<EpisodeInfo>, val incomplete: Boolean = false) : EpisodeResult()
    object SourcePending : EpisodeResult()
    data class Failed(val message: String) : EpisodeResult()
}

// One soft-subtitle track. `language` is a short code where known
// ("en", "es", "fr" …; locale forms like "en-US" are kept verbatim).
// `url` is a direct .vtt/.ass/.srt file URL. Downloaded as a sidecar
// next to the video — best-effort, never fails the job.
data class SubtitleTrack(
    val language: String,
    val url: String,
    val label: String? = null,
)

sealed class ResolvedLink {
    // Plain mp4. referer MUST be sent on the download GET (Kwik
    // rejects downloads without it). Expires: no — a direct mp4 URL
    // is stable until the file is fetched.
    // 2.0-dev2.5: `actualQuality` is the height (px) this link
    // actually offers, as stated by the server (DDL resolution /
    // picked HLS variant height). Null = the server didn't say —
    // unknown, NOT zero; callers must never treat null as "low".
    // It is the ONLY honest basis for the file name and for
    // quality-aware failover; DownloadJob.quality stays the
    // REQUESTED quality.
    // 2.0-dev2.6: `estimatedSizeBytes` is the file's size in bytes
    // when it is known BEFORE downloading — exact for DDL (from
    // the probe's Content-Range total), an estimate for HLS (see
    // DdlQualityProbe). `sizeIsEstimate` is true only for the HLS
    // BANDWIDTH x duration estimate; a DDL size is exact. Null =
    // genuinely unknown: show NOTHING for it, never invent a size.
    data class DirectMp4(val url: String, val referer: String, val subtitles: List<SubtitleTrack> = emptyList(), val actualQuality: Int? = null, val estimatedSizeBytes: Long? = null, val sizeIsEstimate: Boolean = false) : ResolvedLink()
    // Master playlist URL. m3u8 URLs expire in 3-5 minutes: the
    // caller must hand this to HlsDownloader immediately, in the
    // same job, with no gap — never queue it for later.
    // keyOverrideB64 / ivOverrideB64: base64 of 16 raw bytes. When
    // set, HlsDownloader uses the decoded bytes for ALL segment keys
    // instead of fetching them (AnimeStream's wrapped key). The IV
    // override applies only when the playlist KEY tag has no explicit
    // IV. `referer` is sent on playlist/segment/key fetches when the
    // upstream requires it (e.g. MegaPlay); null = no Referer header.
    // `origin` is sent alongside it when the upstream requires an
    // Origin too (e.g. JustAnime's calm leg needs both Referer and
    // Origin on segment fetches); null = no Origin header.
    data class Hls(
        val masterPlaylistUrl: String,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val keyOverrideB64: String? = null,
        val ivOverrideB64: String? = null,
        val referer: String? = null,
        val origin: String? = null,
        // 2.0-dev2.5: offered height in px, when the resolver knew
        // it (e.g. the Pahe button resolution). Null when the master
        // playlist hasn't been parsed yet — HlsDownloader fills the
        // real picked height in at download time and the
        // post-download MediaExtractor check is the final truth.
        // 2.0-dev2.6: `estimatedSizeBytes` / `sizeIsEstimate` are
        // the HLS size estimate (BANDWIDTH x duration, "~", or
        // summed segment byte-ranges, exact) — see DirectMp4 above.
        // Null = genuinely unknown, never a made-up number.
        val actualQuality: Int? = null,
        val estimatedSizeBytes: Long? = null,
        val sizeIsEstimate: Boolean = false,
        // 2.0-dev2.9: true when the master playlist WAS fetched
        // and parsed but carries no RESOLUTION (a single-variant /
        // media playlist — Anikoto's MegaPlay `file` is one): the
        // quality is UNPROVABLE, not merely unfetched. The race
        // ranks this above a known downgrade but still below a
        // proven-exact link; it never promotes the link to exact.
        // False for every other link, including links whose master
        // fetch failed (unprobed — those stay in the plain unknown
        // tier below any known-quality link).
        val masterHasNoVariants: Boolean = false,
    ) : ResolvedLink()
    data class Failed(val message: String) : ResolvedLink()
}

// A resolved link plus the server whose link won (1..10, null when
// every candidate failed). The number — never a source name — is
// what the UI shows ("Server N") and what the engine stores on the
// job. 2.0-dev1.7.
// 2.0-dev2.5: `downgraded` is true when the winning link's offered
// height is below the REQUESTED height (see qualityFloorFor) —
// i.e. no server could prove the requested quality, and this
// best-available link (one step down the ladder, meeting the
// floor) was accepted anyway. The file is then named from the
// ACTUAL quality, never the requested one. A link below the
// floor is never served at all: the race fails honestly instead
// (see MultiSourceResolver). App.kt's resolve path surfaces this
// with a toast such as "Only 720p was available on this server"
// when the returned ResolvedDownload has downgraded=true.
data class ResolvedDownload(val link: ResolvedLink, val serverNumber: Int?, val downgraded: Boolean = false)

// 2.0-dev2.5 — quality ladder + floor. A download that meets the
// requested height is ideal; one step down the ladder is the
// accepted downgrade floor (1080 -> 720, 720 -> 480, 480 -> 360,
// 360 -> 240, 240 -> 240). Anything below the floor is NOT
// accepted while another server might still meet it — the race
// keeps collecting — and a delivered file below the floor fails
// verification instead of completing.
internal val QUALITY_LADDER = listOf(2160, 1440, 1080, 720, 480, 360, 240)

internal fun qualityFloorFor(requestedHeight: Int): Int {
    val stepIndex = QUALITY_LADDER.indexOfFirst { it <= requestedHeight }
    if (stepIndex < 0) return QUALITY_LADDER.last()
    return QUALITY_LADDER.getOrElse(stepIndex + 1) { QUALITY_LADDER[stepIndex] }
}

// Unknown offered quality (null) MEETS the floor in the abstract:
// the server didn't say, so the race can't reject it on the number
// alone. 2.0-dev2.6: that is NOT a free win any more — raceGroup
// parks an unknown-quality link (after probing it once, see
// enrichLink) and lets any known-quality link that meets the floor
// win first; the unknown link is served only when the whole race
// ends with no known winner. The post-download MediaExtractor
// check stays the backstop for those links.
internal fun offeredMeetsFloor(offeredHeight: Int?, requestedHeight: Int): Boolean =
    offeredHeight == null || offeredHeight >= qualityFloorFor(requestedHeight)

internal fun ResolvedLink.offeredHeight(): Int? = when (this) {
    is ResolvedLink.DirectMp4 -> actualQuality
    is ResolvedLink.Hls -> actualQuality
    is ResolvedLink.Failed -> null
}

// 2.0-dev2.6 — file size, when known BEFORE downloading. Null =
// genuinely unknown: the UI must show NOTHING for it, never invent
// a size. `sizeLabel()` is the display string ("240 MB" exact,
// "~240 MB" estimate) or null when unknown.
internal fun ResolvedLink.sizeBytes(): Long? = when (this) {
    is ResolvedLink.DirectMp4 -> estimatedSizeBytes
    is ResolvedLink.Hls -> estimatedSizeBytes
    is ResolvedLink.Failed -> null
}

internal fun ResolvedLink.sizeIsEstimate(): Boolean = when (this) {
    is ResolvedLink.DirectMp4 -> sizeIsEstimate
    is ResolvedLink.Hls -> sizeIsEstimate
    is ResolvedLink.Failed -> false
}

internal fun ResolvedLink.sizeLabel(): String? =
    sizeBytes()?.let { formatSizeBytes(it, sizeIsEstimate()) }

// Human size: B / KB / whole MB at >= 100 MB else one decimal / GB.
// Estimates carry a "~" prefix (hard rule: never show an estimate
// as if it were exact).
internal fun formatSizeBytes(bytes: Long, estimate: Boolean): String {
    val prefix = if (estimate) "~" else ""
    return prefix + when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> {
            val mb = bytes / (1024.0 * 1024.0)
            if (mb >= 100) "${mb.toInt()} MB"
            else "%.1f MB".format(java.util.Locale.US, mb)
        }
        else -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

// Thrown (never auto-retried) when the stream/file that actually
// arrived is below the requested quality's floor: the HLS variant
// pick, or the post-download MediaExtractor probe. The engine
// catches this separately from generic failures and marks the job
// FAILED with this message, so the user sees the real reason and
// Retry re-resolves instead of re-downloading the same low file.
internal class QualityMismatchException(message: String) : java.io.IOException(message)

interface EpisodeSource {
    // Internal/logging ONLY — must never reach the UI.
    val sourceName: String
    // 1..10, see ServerRegistry. The ONLY user-facing identity.
    val serverNumber: Int
    // Generic description, no source name — see ServerRegistry.
    val serverDescription: String
    suspend fun loadEpisodes(title: ApiTitle, anilistId: Long? = null): EpisodeResult
    suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink
    // Resolve by (series identity + episode number), the decoupled
    // path used with the media-index episode list. `anilistId` is the
    // AniList id when known (from the media-index match), else null.
    // `audio` is "Sub"/"Dub" (or "jpn"/"eng"). Default: not supported.
    suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        return ResolvedLink.Failed("This server can't resolve by episode number.")
    }
}

object EpisodeSources {
    // clearanceCookie: a cf_clearance value the USER's own browser
    // session already holds, passed in as a plain string. Null =
    // no clearance available -> the source reports SourcePending
    // and makes no network call at all.
    // userAgent: the WebView User-Agent that minted the clearance.
    // A clearance is void if replayed with a different UA than the
    // one that minted it, so the minting UA must be replayed with
    // the cookie (research §8.1). Null -> the default browser UA.
    fun pahe(clearanceCookie: String?, userAgent: String? = null): EpisodeSource =
        PaheEpisodeSource(clearanceCookie, userAgent)

    fun aniZone(): EpisodeSource = AniZoneEpisodeSource()
    fun justAnime(): EpisodeSource = JustAnimeEpisodeSource()
    fun aniPm(): EpisodeSource = AniPmEpisodeSource()
    fun nino2(): EpisodeSource = Nino2EpisodeSource()
    fun aniWaves(): EpisodeSource = AniWavesEpisodeSource()
    fun anikoto(): EpisodeSource = AnikotoEpisodeSource()
    fun animeStream(): EpisodeSource = AnimeStreamEpisodeSource()
    fun anv(): EpisodeSource = AnvEpisodeSource()
    fun animeHeaven(): EpisodeSource = AnimeHeavenEpisodeSource()
}

// Try candidates in priority order. 2.0-dev2.7: the win tier is
// the REQUESTED height, not the floor — a link that merely meets
// the floor (one step down, e.g. 720p for a 1080p request) is
// PARKED as a downgrade candidate, never crowned on arrival, so
// a slower server that CAN prove the requested quality still gets
// its chance and a 720p-max server can never win a 1080p request
// outright. If no server proves the requested height, the best
// downgrade candidate is served FLAGGED (downgraded=true, so the
// UI toasts the real height); a link below the floor is never
// served — the race fails honestly instead. Unknown-quality links
// rank below any known floor-meeting link (they might meet the
// request, but a certain 720p beats a gamble) and above the
// below-floor fallback. 2.0-dev2.9: EXCEPT a link whose master was
// fetched and parsed with no RESOLUTION (single-variant): it ranks
// above a known downgrade but still below proven-exact — it may BE
// the requested height, and the post-download check is the
// backstop. If all fail, the last Failed is returned
// (or a generic message when nothing ran), with serverNumber
// null. onAttempt fires with each server number as it is tried,
// so the UI can show live progress ("Trying Server N…"). The
// Pahe candidate is passed in already constructed with its
// clearance — the engine NEVER solves Cloudflare.
//
// 2.0-dev2.3: RACE, don't chain (MiruDL pattern — no sequential
// failover tax). 2.0-dev2.9: ALL candidates race at once (batch of
// 10, was 3) and the first proven-exact success wins — the old
// batches of 3 made Server 8 wait up to ~30 s behind two full
// batches before it even started. The exact-tier parking rules
// (downgrade / unknown park, exact wins) are unchanged, so a slow
// server no longer blocks the servers behind it and a fast
// downgrade still can't beat a slow exact prover.
// Racers are launched as children of the CALLER's job (via the
// current coroutine context) and are NOT joined after a win —
// joining them would stall the win by up to a socket timeout when
// a loser is blocked in non-cancellable I/O. Losers are cancelled
// best-effort; they are bounded (per-attempt timeout + socket
// timeouts) and die with the caller's job. Each racer is capped
// at 15 s: a hung resolve fails over in seconds, not after ~40 s
// of retries (fail fast on resolve — segment retries are untouched).
//
// 2.0-dev2.8: SMALLEST-FILE mode (preferSmaller=true, Settings →
// Downloads → "Prefer smaller files"). An exact-tier arrival is
// PARKED, not crowned: the batch runs until ALL its racers arrive,
// every exact candidate gets a size attached (DDL: the probe total
// it already carries; HLS: estimateHlsSizeBytes on its OWN master
// playlist — a failed estimate leaves the size unknown), and ALL
// batches run before the winner is picked — the smallest KNOWN-size
// exact candidate across every server. When no exact candidate has
// a known size, the first-arrival exact wins. The downgrade /
// unknown / honest-fail fallbacks below are UNCHANGED, and the
// mode never touches the quality floor or the exact-tier rules.
// OFF (default) is byte-for-byte the old behaviour: first proven-
// exact arrival crowns immediately, zero added latency.
//
// 2.0-dev2.14: the subs + size TRI-STATES, same uniform model.
// Subs: Hard = preferDirect (DDL-first phased race, HLS fallback);
// Soft = preferSoft (HLS-first phased race, DDL fallback); Both =
// neither (one parallel race, today's behaviour). Size: Best =
// preferLargest (mirror of preferSmaller — biggest known-size
// exact wins); Smallest = preferSmaller; Fastest = neither (first
// arrival wins). Two booleans each, mutually exclusive in the UI
// (a race defensively treats preferDirect as winning if both subs
// flags are somehow on). Phase 1 races only the preferred-kind
// servers (Hard: {2, 10}; Soft: all but 10) with a BOUNDED wait
// (RESOLVE_PHASE_WAIT_MS); phase 2 races the rest only when phase 1
// holds no preferred-kind exact. The size compare happens inside
// the winning subs phase's pool first. Quality-tier guardrail
// UNCHANGED: only exact-tier (offered >= requested) links ever
// compete, so a below-quality DDL can never beat a
// quality-meeting HLS.
object MultiSourceResolver {
    // 2.0-dev2.9: was 3 — batch [7,8,9] could not start until
    // batches 1-2 fully ended, so Server 8 waited up to ~30 s
    // behind two 15 s-capped batches. All 9 race at once now; the
    // exact-tier parking rules inside raceGroup are unchanged, so
    // this costs no correctness — only latency.
    // 2.0-dev2.11: 10 — Server 10 joins the single batch; all 10
    // race at once now.
    private const val RESOLVE_RACE_BATCH = 10
    private const val RESOLVE_ATTEMPT_TIMEOUT_MS = 15_000L
    // 2.0-dev2.14: BOUNDED wait for a phased race's first phase.
    // A phase must not hold the fallback hostage: if no
    // preferred-kind exact has arrived within this long, the
    // phase ends with what it has and the fallback phase runs.
    // 8 s covers Server 10's gate fetch + one retry and
    // Server 2's legs; the typical phase answers in 1–3 s, so
    // the no-match case pays ~1–3 s extra, never the full 15 s
    // attempt cap on top of the fallback race.
    private const val RESOLVE_PHASE_WAIT_MS = 8_000L

    suspend fun resolveWithFailover(
        candidates: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
        onAttempt: ((Int) -> Unit)? = null,
        // 2.0-dev2.8: smallest-file mode — see the object header.
        // A pure parameter: EpisodeSource never reads prefs itself.
        preferSmaller: Boolean = false,
        // 2.0-dev2.10: direct-download mode — among exact-tier
        // links, a direct MP4 wins over an HLS master. Also a pure
        // parameter. It NEVER overrides the quality rules: only
        // exact-tier (offered >= requested) links compete, so a
        // 360p DDL can never beat a 1080p HLS.
        preferDirect: Boolean = false,
        // 2.0-dev2.14: soft-subs mode — the mirror of
        // preferDirect. Among exact-tier links, an HLS master
        // (separate soft subs) wins over a direct MP4. Same
        // quality rules: only exact-tier links compete.
        preferSoft: Boolean = false,
        // 2.0-dev2.14: largest-file mode — the mirror of
        // preferSmaller. The race waits, compares sizes, and the
        // biggest known-size exact wins. Same parking rules.
        preferLargest: Boolean = false,
    ): ResolvedDownload {
        // 2.0-dev1.9 TEMPORARY download diagnostics: every resolve
        // attempt is logged (server number, result/error, latency)
        // so a pasted Download Logs report shows which servers even
        // answer on the phone. No URLs, no cookies in the log.
        DownloadLogs.log(
            "RESOLVE",
            "start: ep $number ${audio.lowercase()} $quality, candidates=${candidates.map { it.serverNumber }}" +
                (if (preferSmaller) ", prefer-smaller" else "") +
                (if (preferLargest) ", prefer-largest" else "") +
                (if (preferDirect) ", prefer-direct" else "") +
                (if (preferSoft) ", prefer-soft" else ""),
        )
        if (candidates.isEmpty()) {
            DownloadLogs.log("RESOLVE", "no candidates — nothing to try")
            return ResolvedDownload(ResolvedLink.Failed("No server available — tap Retry."), null)
        }
        var last: ResolvedLink = ResolvedLink.Failed("All servers failed — tap Retry.")
        // 2.0-dev2.5: best sub-floor link seen across ALL batches.
        // A batch's first sub-quality win must NOT end the race:
        // it is parked and the remaining servers are still tried.
        // If NO server meets the floor, say so honestly instead of
        // downloading the wrong quality (brief: never silently
        // deliver a lower quality than selected).
        var bestFallback: ResolvedDownload? = null
        // 2.0-dev2.7: best DOWNGRADE link seen across ALL batches —
        // known quality, meets the floor, but below the requested
        // height (e.g. 720p for a 1080p request). It never ends a
        // batch: a slower server proving the requested height
        // always beats it. Served, flagged downgraded=true, only
        // when the whole race ends with no exact winner.
        var bestDowngrade: ResolvedDownload? = null
        // 2.0-dev2.9: best UNPROVABLE link seen across ALL batches
        // — an HLS link whose master WAS fetched and parsed but
        // carries no RESOLUTION (single-variant / media playlist),
        // so its quality can't be proven either way. It ranks
        // ABOVE a known downgrade (it may well be the requested
        // height — a variant-less master is usually the server's
        // only rendition) but BELOW any proven-exact winner, and
        // the post-download check stays the backstop. Unprobed
        // links (master fetch failed) are NOT in this tier — they
        // stay in bestUnknown below.
        var bestUnprovable: ResolvedDownload? = null
        // 2.0-dev2.6: best UNKNOWN-quality link seen across ALL
        // batches (probed once, still unprovable). It ranks ABOVE
        // the known sub-floor fallback — it might meet the floor —
        // but BELOW any known meeting-floor win, and it is only
        // served after every batch has run, so a slower server that
        // CAN prove its quality always gets its chance first.
        var bestUnknown: ResolvedDownload? = null
        // 2.0-dev2.8: exact-tier candidates parked for the
        // smallest-file comparison (see raceGroup) — collected
        // across ALL batches, picked only after the last one.
        val exactCandidates = mutableListOf<ResolvedDownload>()
        // 2.0-dev2.14: uniform phased race for the subs tri-state.
        // Hard (preferDirect) races DDL-capable servers first
        // (Server 2's DDL leg + Server 10), then the HLS rest;
        // Soft (preferSoft) races the HLS servers first (every
        // server except 10), then Server 10 alone. Both (neither
        // on) keeps today's single parallel race over all 10.
        // Defensive: if both flags are somehow on, Hard wins
        // (preferDirect is the older, user-visible toggle). Only
        // phase 1 gets a deadline (RESOLVE_PHASE_WAIT_MS) — the
        // fallback phase always runs to completion. After phase 1,
        // if preferred-kind exacts already exist, phase 2 can add
        // nothing to the preferred pool (its servers only return
        // the other kind), so the loop stops there and the pool
        // pick below decides.
        val preferDirectMode = preferDirect && !preferSoft
        val preferSoftMode = preferSoft && !preferDirect
        val phased = preferDirectMode || preferSoftMode
        val groups: List<List<EpisodeSource>> = when {
            preferDirectMode -> listOf(
                candidates.filter { it.serverNumber == 2 || it.serverNumber == 10 },
                candidates.filter { it.serverNumber != 2 && it.serverNumber != 10 },
            ).filter { it.isNotEmpty() }
            preferSoftMode -> listOf(
                candidates.filter { it.serverNumber != 10 },
                candidates.filter { it.serverNumber == 10 },
            ).filter { it.isNotEmpty() }
            else -> candidates.chunked(RESOLVE_RACE_BATCH)
        }
        for ((groupIndex, group) in groups.withIndex()) {
            val isPhase1 = phased && groupIndex == 0
            val phaseDeadlineMs = if (isPhase1) System.currentTimeMillis() + RESOLVE_PHASE_WAIT_MS else null
            if (isPhase1) DownloadLogs.log(
                "RESOLVE",
                "phased race: phase 1 = servers ${group.map { it.serverNumber }} (${if (preferDirectMode) "DDL first" else "HLS first"}), " +
                    "phase 2 = ${if (groups.size > 1) "servers ${groups[1].map { it.serverNumber }}" else "none"}",
            )
            val result = raceGroup(group, title, anilistId, number, audio, quality, onAttempt, preferSmaller, preferDirectMode, preferSoftMode, preferLargest, phaseDeadlineMs)
            last = result.last
            if (result.winner != null) return result.winner
            exactCandidates += result.exactCandidates
            if (isPhase1) {
                val hasPreferred = exactCandidates.any {
                    if (preferDirectMode) it.link is ResolvedLink.DirectMp4 else it.link is ResolvedLink.Hls
                }
                if (hasPreferred) {
                    DownloadLogs.log(
                        "RESOLVE",
                        "phase 1 already holds preferred-kind exacts — skipping phase 2",
                    )
                    break
                }
            }
            if (result.downgrade != null) {
                val offered = result.downgrade.link.offeredHeight() ?: 0
                val bestOffered = bestDowngrade?.link?.offeredHeight() ?: 0
                if (bestDowngrade == null || offered > bestOffered) bestDowngrade = result.downgrade
            }
            if (result.fallback != null) {
                val offered = result.fallback.link.offeredHeight() ?: 0
                val bestOffered = bestFallback?.link?.offeredHeight() ?: 0
                if (bestFallback == null || offered > bestOffered) bestFallback = result.fallback
            }
            if (result.unprovableFallback != null && bestUnprovable == null) bestUnprovable = result.unprovableFallback
            if (result.unknownFallback != null && bestUnknown == null) bestUnknown = result.unknownFallback
        }
        // 2.0-dev2.8: smallest-file mode — the race ran ALL batches
        // (no exact arrival crowned early), so the smallest
        // known-size exact candidate across EVERY server wins here,
        // ahead of the downgrade / unknown / honest-fail fallbacks,
        // which are unchanged. Unknown-size exacts only win when
        // no exact candidate has a known size (first arrival then).
        // 2.0-dev2.10: direct-download mode joins the same pick.
        // Precedence: exact tier first, then direct (a DirectMp4
        // exact wins over an HLS exact), then smallest-file (when
        // ON, smallest within the preferred pool), then fastest
        // arrival. The pool only ever holds EXACT candidates
        // (offered >= requested), so a sub-request DDL — e.g.
        // JustAnime's 360p-only gigi file for a 1080p request —
        // can never reach this pick, let alone beat a 1080p HLS.
        if (preferSmaller || preferLargest || preferDirect || preferSoft) {
            // 2.0-dev2.14: the pool is built from the PREFERRED
            // kind first (DDL for Hard, HLS for Soft, any for
            // Both) and the size mode compares inside that pool —
            // size never pulls the pick across subs kinds, and a
            // fallback-kind exact only wins when the preferred
            // kind has no exact at all.
            val pool = when {
                preferDirect -> exactCandidates.filter { it.link is ResolvedLink.DirectMp4 }.takeIf { it.isNotEmpty() } ?: exactCandidates
                preferSoft -> exactCandidates.filter { it.link is ResolvedLink.Hls }.takeIf { it.isNotEmpty() } ?: exactCandidates
                else -> exactCandidates
            }
            val best = when {
                preferSmaller -> pickSmallestExact(pool)
                preferLargest -> pickLargestExact(pool)
                else -> pool.firstOrNull()
            }
            best?.let {
                DownloadLogs.log(
                    "RESOLVE",
                    (if (preferDirect && it.link is ResolvedLink.DirectMp4) "direct-download mode: winner Server ${it.serverNumber} (direct file) — " else "") +
                        (if (preferSoft && it.link is ResolvedLink.Hls) "soft-subs mode: winner Server ${it.serverNumber} (stream) — " else "") +
                        (if (preferSmaller) "smallest-file mode: winner Server ${it.serverNumber} — " else if (preferLargest) "largest-file mode: winner Server ${it.serverNumber} — " else "winner Server ${it.serverNumber} — ") +
                        "${it.link.sizeLabel() ?: "size unknown"} at ${it.link.offeredHeight() ?: "?"}p for $quality; " +
                        "exact candidates: ${exactCandidates.joinToString("; ") { "Server ${it.serverNumber} (${if (it.link is ResolvedLink.DirectMp4) "direct" else "stream"}, ${it.link.sizeLabel() ?: "size unknown"})" }}",
                )
                return it
            }
        }
        // 2.0-dev2.9: unprovable beats downgrade (see above) but
        // never beats a proven-exact winner — the preferSmaller
        // pick and the exact-winner early return above already
        // ran first, so reaching here means no server proved the
        // requested height.
        if (bestUnprovable != null) {
            DownloadLogs.log(
                "RESOLVE",
                "no server proved ${qualityHeight(quality)}p for $quality — " +
                    "serving the unprovable single-variant master from Server ${bestUnprovable.serverNumber} " +
                    "(master fetched, no RESOLUTION to prove against; post-download check is the backstop)",
            )
            return bestUnprovable
        }
        if (bestDowngrade != null) {
            val offered = bestDowngrade.link.offeredHeight()
            DownloadLogs.log(
                "RESOLVE",
                "no server proved ${qualityHeight(quality)}p for $quality — " +
                    "serving the best downgrade, ${offered ?: "?"}p from Server ${bestDowngrade.serverNumber}, flagged (UI toasts the real height)",
            )
            return bestDowngrade.copy(downgraded = true)
        }
        if (bestUnknown != null) {
            DownloadLogs.log(
                "RESOLVE",
                "no server could PROVE ${qualityFloorFor(qualityHeight(quality))}p for $quality — " +
                    "serving the unprovable link from Server ${bestUnknown.serverNumber} as a last resort (post-download check is the backstop)",
            )
            return bestUnknown
        }
        if (bestFallback != null) {
            val offered = bestFallback.link.offeredHeight()
            val msg = "No server has ${qualityHeight(quality)}p for this episode — " +
                "best available is ${offered ?: "?"}p. Try a lower quality."
            DownloadLogs.log(
                "RESOLVE",
                "no server met the ${qualityFloorFor(qualityHeight(quality))}p floor for $quality — " +
                    "best offered ${offered ?: "?"}p from Server ${bestFallback.serverNumber}; failing honestly instead of downloading the wrong quality",
            )
            return ResolvedDownload(ResolvedLink.Failed(msg), null)
        }
        val lastMsg = (last as? ResolvedLink.Failed)?.message ?: "All servers failed — tap Retry."
        DownloadLogs.log("RESOLVE", "all servers failed — last: $lastMsg")
        return ResolvedDownload(last, null)
    }

    // One batch of the race. Returns the winning download (a link
    // whose PROVEN quality meets the REQUESTED height), the best
    // downgrade link parked in this batch (known quality, meets
    // the floor but below the requested height), the best sub-floor
    // link parked in this batch (or null), the best still-unknown-
    // quality link parked in this batch (or null), plus the last
    // failure seen, so the caller can report it when every batch
    // fails. Every racer logs its own result + latency. An exact
    // win still ends the batch immediately (fast path preserved);
    // a downgrade win only parks and the batch keeps racing until
    // its racers are done, so a slower server that CAN meet the
    // requested quality still gets its chance. 2.0-dev2.6: an
    // UNKNOWN-quality win parks exactly the same way — a fast
    // unprovable DDL must never beat a slower provable HLS just
    // by answering first. 2.0-dev2.9: a link whose master WAS
    // fetched but carries no RESOLUTION parks one tier higher —
    // above a known downgrade, still below proven-exact (see
    // bestUnprovable in resolveWithFailover). A link whose master
    // fetch FAILED stays in the plain unknown tier below.
    private data class RaceResult(
        val winner: ResolvedDownload?,
        val downgrade: ResolvedDownload?,
        val fallback: ResolvedDownload?,
        val unknownFallback: ResolvedDownload?,
        // 2.0-dev2.9: best unprovable (master fetched, no
        // RESOLUTION) link parked in this batch, or null.
        val unprovableFallback: ResolvedDownload? = null,
        val last: ResolvedLink,
        // 2.0-dev2.8: exact-tier arrivals parked in this batch for
        // the smallest-file comparison — empty when the mode is OFF
        // or no racer proved the requested height. The caller picks
        // the global smallest across ALL batches (see
        // pickSmallestExact); a batch never crowns one itself.
        val exactCandidates: List<ResolvedDownload> = emptyList(),
    )

    // 2.0-dev2.8: smallest KNOWN-size exact wins; an unknown-size
    // candidate NEVER beats a known-size one. When NO candidate has
    // a known size, first arrival wins — minByOrNull returns the
    // first of the equal (MAX_VALUE) keys, and arrival order is
    // list order: earlier batch first, earlier arrival within a
    // batch first. A tie on equal known sizes likewise keeps the
    // earlier arrival.
    private fun pickSmallestExact(candidates: List<ResolvedDownload>): ResolvedDownload? =
        candidates.minByOrNull { it.link.sizeBytes() ?: Long.MAX_VALUE }

    // 2.0-dev2.14: largest-file mode — the mirror of
    // pickSmallestExact. Unknown-size candidates sort last (a
    // known size always beats an unknown one); ties keep the
    // first arrival (maxByOrNull returns the first max).
    private fun pickLargestExact(candidates: List<ResolvedDownload>): ResolvedDownload? =
        candidates.maxByOrNull { it.link.sizeBytes() ?: Long.MIN_VALUE }

    // 2.0-dev2.8: smallest-file mode only. An HLS link that proved
    // the EXACT requested height gets its size attached from its
    // OWN master playlist (estimateHlsSizeBytes — summed
    // byte-ranges when present, else AVERAGE-BANDWIDTH x duration,
    // "~"), so the batch can compare candidates by size. Any other
    // link passes through untouched: DDL links already carry the
    // probe total when enrichLink probed them (and a known-quality
    // DDL with no size stays unknown — no extra probe on the hot
    // path), and non-exact links never compete on size. A link
    // that already carries a size keeps it. Never throws; a failed
    // estimate returns the link unchanged (unknown size).
    private suspend fun attachExactHlsSizeIfNeeded(link: ResolvedLink, quality: String, audio: String, requestedHeight: Int): ResolvedLink {
        if (link !is ResolvedLink.Hls) return link
        if ((link.offeredHeight() ?: 0) < requestedHeight) return link
        if (link.sizeBytes() != null) return link
        val headers = buildMap {
            link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            link.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
        }
        val estimate = DdlQualityProbe.estimateHlsSizeBytes(link.masterPlaylistUrl, headers, quality, audio) ?: return link
        return link.copy(
            estimatedSizeBytes = estimate.bytes.takeIf { it > 0 },
            sizeIsEstimate = estimate.isEstimate,
        )
    }

    // 2.0-dev2.6 — the reliability gate, run inside each racer
    // BEFORE the floor decision sees the link. ONLY unknown-quality
    // links are probed here: a known-quality link passes through
    // untouched (zero added latency on the fast path — speed is
    // non-negotiable, and HLS size estimates deliberately do NOT
    // run here; estimateHlsSizeBytes is a public API for lazy,
    // off-hot-path callers only). A successful probe is ground
    // truth: its height replaces the unknown, and its exact total
    // size rides along for the sheet. A failed probe returns the
    // link unchanged — unknown stays unknown and the parking rule
    // below, not the probe, decides its fate.
    // 2.0-dev2.7: unknown HLS links are proved the same way, from
    // the master playlist (max RESOLUTION height across variants
    // — the same regex HlsDownloader's parseMasterPlaylist uses).
    // This is what makes the HLS-only servers (AniZone, Nino 2,
    // AniWaves, …) compete as KNOWN winners at all: they return
    // no quality tag, so before this fix they could only ever win
    // the last-resort unknown lottery, never the race on merit —
    // which is exactly how Server 5 "won" Death Note E1 1080p
    // without anything proving it was the best choice. One small
    // GET, in parallel inside the racer, the link's own Referer /
    // Origin. 2.0-dev2.9: RETRIED with a longer budget — the old
    // single-attempt 5 s fetch parked a good 1080p master as
    // "offered ?" on a transient 520 (revo flaps) or a slow TLS
    // handshake. requestWithRetry (3 attempts, 5 s each, outer
    // 10 s cap + the fetch's own 10 s total deadline) + the
    // unprobed/unprovable split: a master that IS a media playlist
    // (no variants) still proves no height, but it is now FLAGGED
    // (masterHasNoVariants) instead of lumped with fetch failures
    // — the race ranks it above a known downgrade, still below
    // proven-exact, and it is never promoted to exact.
    private suspend fun enrichLink(link: ResolvedLink): ResolvedLink {
        if (link is ResolvedLink.DirectMp4) {
            if (link.actualQuality != null) return link
            val probe = DdlQualityProbe.probeDdlQuality(
                link.url,
                mapOf("Referer" to link.referer),
            ) ?: return link
            return link.copy(
                actualQuality = probe.height ?: link.actualQuality,
                estimatedSizeBytes = probe.totalBytes ?: link.estimatedSizeBytes,
                sizeIsEstimate = false,
            )
        }
        if (link is ResolvedLink.Hls) {
            if (link.actualQuality != null) return link
            return when (val probe = probeHlsMasterHeight(link)) {
                is HlsProbeOutcome.Proven -> link.copy(actualQuality = probe.height)
                // 2.0-dev2.9: the master WAS fetched and parsed but
                // carries no RESOLUTION — unprovable, not unprobed.
                // The link keeps actualQuality null (never promoted
                // to exact) but carries the flag so the race can
                // rank it above a known downgrade.
                HlsProbeOutcome.Unprovable -> link.copy(masterHasNoVariants = true)
                // Fetch failed / timed out / not a playlist: the
                // probe proved nothing — the link stays plain
                // unknown and the parking rule below decides it.
                HlsProbeOutcome.Unprobed -> link
            }
        }
        return link
    }

    // 2.0-dev2.7: same proving as the race, exposed for the
    // serving-first path in App.kt resolveViaCandidates, which
    // bypasses the race — a serving server's link must be proven
    // before it is served, or a 720p-max server can be served
    // for a 1080p request with no downgrade flag.
    internal suspend fun proveLink(link: ResolvedLink): ResolvedLink = enrichLink(link)

    // 2.0-dev2.9: three outcomes, not two — the old Int? could not
    // tell "the fetch failed" (stay unknown, below any known link)
    // from "the master was fetched and has no RESOLUTION"
    // (single-variant: quality is unprovable — rank it above a
    // known downgrade, still below proven-exact). Never throws.
    private sealed class HlsProbeOutcome {
        data class Proven(val height: Int) : HlsProbeOutcome()
        object Unprovable : HlsProbeOutcome()
        object Unprobed : HlsProbeOutcome()
    }

    private suspend fun probeHlsMasterHeight(link: ResolvedLink.Hls): HlsProbeOutcome {
        if (link.masterPlaylistUrl.isBlank()) return HlsProbeOutcome.Unprobed
        // Capture the caller's Job BEFORE entering Dispatchers.IO:
        // the blocking fetch below can then be cancelled from
        // inside via EngineHttp's isCancelled hook when the race
        // itself cancels this racer (exact winner found / 15 s
        // cap). The outer 10 s cap below is bounded separately by
        // the fetch's own total deadline, which does not need the
        // Job — a timeout there throws inside the fetch itself.
        val job = currentCoroutineContext()[Job]
        return try {
            withTimeoutOrNull(10_000L) {
                withContext(Dispatchers.IO) {
                    val headers = buildMap {
                        link.referer?.let { put("Referer", it) }
                        link.origin?.let { put("Origin", it) }
                    }
                    val resp = EngineHttp.requestWithRetry(
                        link.masterPlaylistUrl,
                        headers = headers,
                        timeoutMs = 5_000,
                        followRedirects = true,
                        maxAttempts = 3,
                        // 2.0-dev2.9: the fetch's own hard total
                        // deadline (EngineHttp bounded read), so a
                        // dribble-feed server is cut off inside the
                        // fetch instead of holding a thread past
                        // the outer cap above.
                        totalDeadlineMs = 10_000,
                        isCancelled = { job?.isCancelled == true },
                    )
                    if (resp.code != 200 || !resp.text.contains("#EXTM3U")) HlsProbeOutcome.Unprobed
                    else Regex("""RESOLUTION=\d+x(\d+)""").findAll(resp.text)
                        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                        .maxOrNull()?.takeIf { it > 0 }
                        ?.let { HlsProbeOutcome.Proven(it) }
                        ?: HlsProbeOutcome.Unprovable
                }
            } ?: HlsProbeOutcome.Unprobed
        } catch (_: Exception) {
            HlsProbeOutcome.Unprobed
        }
    }

    // 2.0-dev2.8: with preferSmaller, an EXACT win parks exactly
    // the same way — the batch runs until ALL its racers arrive,
    // and the caller picks the smallest across ALL batches. The
    // `winner` field is then never set by this batch (it stays
    // null); exact arrivals land in `exactCandidates` instead.
    private suspend fun raceGroup(
        group: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
        onAttempt: ((Int) -> Unit)?,
        preferSmaller: Boolean = false,
        // 2.0-dev2.10: direct-download mode — see resolveWithFailover.
        preferDirect: Boolean = false,
        // 2.0-dev2.14: soft-subs mode — see resolveWithFailover.
        preferSoft: Boolean = false,
        // 2.0-dev2.14: largest-file mode — see resolveWithFailover.
        preferLargest: Boolean = false,
        // 2.0-dev2.14: phase deadline (epoch ms) — null means this
        // group runs to completion (phase 2 / unphased race).
        // When set, the wait loop ends at the deadline with
        // whatever arrived; the caller then runs the next phase
        // or picks from the parked pools.
        phaseDeadlineMs: Long? = null,
    ): RaceResult {
        val requestedHeight = qualityHeight(quality)
        val floor = qualityFloorFor(requestedHeight)
        val scope = CoroutineScope(currentCoroutineContext())
        val deferreds = group.map { src ->
            scope.async {
                onAttempt?.invoke(src.serverNumber)
                val startedMs = System.currentTimeMillis()
                try {
                    val raw = withTimeoutOrNull(RESOLVE_ATTEMPT_TIMEOUT_MS) {
                        src.resolveEpisode(title, anilistId, number, audio, quality)
                    } ?: ResolvedLink.Failed("Server ${src.serverNumber} timed out.")
                    // 2.0-dev2.6: probe an unknown-quality link now —
                    // a 360p file hiding behind an unknown label is
                    // parked below, never crowned. Known links are
                    // untouched (no added latency). 2.0-dev2.7: HLS
                    // links are proved from the master playlist too,
                    // so an HLS-only server can win on merit.
                    // 2.0-dev2.8: smallest-file mode — an HLS link
                    // that proves the exact height also gets its
                    // size attached here, inside the racer, so all
                    // candidates size in parallel (see
                    // attachExactHlsSizeIfNeeded). The dev2.8 size
                    // caches make a re-tap's master fetch usually
                    // free; a failed estimate just leaves the size
                    // unknown.
                    val enriched = enrichLink(raw)
                    val r = if (preferSmaller || preferLargest) attachExactHlsSizeIfNeeded(enriched, quality, audio, requestedHeight) else enriched
                    val tookMs = System.currentTimeMillis() - startedMs
                    when (r) {
                        is ResolvedLink.DirectMp4 -> {
                            DownloadLogs.log("RESOLVE", "Server ${src.serverNumber} (${src.sourceName}): OK in ${tookMs}ms — DDL mp4, offered ${r.offeredHeight() ?: "?"}p (${DownloadLogs.sanitizeUrl(r.url)})" + (r.sizeBytes()?.let { " size=${formatSizeBytes(it, r.sizeIsEstimate())}" } ?: ""))
                        }
                        is ResolvedLink.Hls -> {
                            DownloadLogs.log("RESOLVE", "Server ${src.serverNumber} (${src.sourceName}): OK in ${tookMs}ms — HLS master, offered ${r.offeredHeight() ?: "?"}p (${DownloadLogs.sanitizeUrl(r.masterPlaylistUrl)})" + (r.sizeBytes()?.let { " size=${formatSizeBytes(it, r.sizeIsEstimate())}" } ?: ""))
                        }
                        is ResolvedLink.Failed -> {
                            DownloadLogs.log("RESOLVE", "Server ${src.serverNumber}: FAILED in ${tookMs}ms — ${r.message}")
                        }
                    }
                    Triple(src, r, tookMs)
                } catch (e: Exception) {
                    val tookMs = System.currentTimeMillis() - startedMs
                    DownloadLogs.log("RESOLVE", "Server ${src.serverNumber}: EXCEPTION in ${tookMs}ms — ${e.javaClass.simpleName}: ${e.message ?: ""}")
                    Triple(src, ResolvedLink.Failed(friendlyDownloadError(e)), tookMs)
                }
            }
        }
        var last: ResolvedLink = ResolvedLink.Failed("All servers failed — tap Retry.")
        var winner: ResolvedDownload? = null
        var downgrade: ResolvedDownload? = null
        var fallback: ResolvedDownload? = null
        var unknownFallback: ResolvedDownload? = null
        // 2.0-dev2.9: best unprovable link parked in this batch —
        // master fetched, no RESOLUTION (see the unknown branch
        // below). First arrival; there is no height to compare.
        var unprovableFallback: ResolvedDownload? = null
        // 2.0-dev2.8: exact-tier arrivals parked for the
        // smallest-file comparison (see pickSmallestExact).
        val exactCandidates = mutableListOf<ResolvedDownload>()
        // 2.0-dev2.7: one-line outcome per arrived racer, so the
        // winner line below can name WHY it won (and what each
        // loser did) instead of leaving the next phone log to
        // guess — e.g. "Server 5 won" with no reason is how the
        // Death Note E1 question arose in the first place.
        val outcomes = mutableListOf<String>()
        val arrivedNumbers = mutableSetOf<Int>()
        val pending = deferreds.toMutableList()
        // 2.0-dev2.14: phase deadline — when set, the wait ends at
        // the deadline with whatever arrived (parked pools and
        // fallback tiers are still honoured by the caller).
        var phaseTimedOut = false
        while (pending.isNotEmpty() && winner == null && !phaseTimedOut) {
            select<Unit> {
                pending.forEach { d ->
                    d.onAwait { (src, r, tookMs) ->
                        pending.remove(d)
                        arrivedNumbers += src.serverNumber
                        when (r) {
                            is ResolvedLink.DirectMp4, is ResolvedLink.Hls -> {
                                val offered = r.offeredHeight()
                                val download = ResolvedDownload(r, src.serverNumber)
                                if (offered == null) {
                                    if (r is ResolvedLink.Hls && r.masterHasNoVariants) {
                                        // 2.0-dev2.9: master WAS fetched
                                        // and parsed — no RESOLUTION to
                                        // prove against (single-variant /
                                        // media playlist). Parked ABOVE a
                                        // known downgrade but BELOW any
                                        // proven-exact arrival: it wins
                                        // only if the race ends with no
                                        // exact winner at all.
                                        outcomes += "Server ${src.serverNumber}: single-variant master in ${tookMs}ms — parked as unprovable"
                                        DownloadLogs.log(
                                            "RESOLVE",
                                            "Server ${src.serverNumber}: master fetched, no RESOLUTION (single-variant) — parked as unprovable, above downgrade but below proven-exact",
                                        )
                                        if (unprovableFallback == null) unprovableFallback = download
                                    } else {
                                        // 2.0-dev2.6: still unknown after
                                        // the probe (fetch failed — the
                                        // probe never saw the master).
                                        // Parked BELOW any known link
                                        // meeting the floor; it wins only
                                        // if the batch ends with no known
                                        // winner at all.
                                        outcomes += "Server ${src.serverNumber}: unknown quality in ${tookMs}ms — parked"
                                        DownloadLogs.log(
                                            "RESOLVE",
                                            "Server ${src.serverNumber}: quality still unknown after probing — parked, a known-quality link wins first",
                                        )
                                        if (unknownFallback == null) unknownFallback = download
                                    }
                                } else if (offered >= requestedHeight) {
                                    // 2.0-dev2.10/2.14: subs-preference
                                    // crowning. A preferred-kind exact
                                    // arrival crowns IMMEDIATELY when
                                    // only its subs mode is ON: a
                                    // DirectMp4 for Hard, an HLS for
                                    // Soft — it is the fastest
                                    // preferred-kind exact, and no
                                    // size comparison waits on it. The
                                    // other kind parks instead — a
                                    // slower preferred-kind exact must
                                    // still get its chance. When a
                                    // size mode is ALSO on, the
                                    // preferred exact parks too
                                    // (smallest/largest among
                                    // preferred-kind exacts wins).
                                    val directExactCrownsNow = preferDirect && !preferSmaller && !preferLargest && r is ResolvedLink.DirectMp4
                                    val hlsExactCrownsNow = preferSoft && !preferSmaller && !preferLargest && r is ResolvedLink.Hls
                                    if ((preferSmaller || preferLargest || preferDirect || preferSoft) && !directExactCrownsNow && !hlsExactCrownsNow) {
                                        // 2.0-dev2.8/2.14: size/subs
                                        // mode — an exact arrival is
                                        // PARKED, not crowned. The
                                        // batch runs until ALL its
                                        // racers arrive (or the phase
                                        // deadline hits) and the caller
                                        // picks across ALL batches (see
                                        // pickSmallestExact /
                                        // pickLargestExact); an
                                        // unknown-size exact only wins
                                        // when no exact candidate has
                                        // a known size.
                                        val sizeText = r.sizeLabel() ?: "size unknown"
                                        val parkReason = when {
                                            preferSmaller || preferLargest -> "parked for size compare"
                                            preferDirect -> "parked — waiting for a direct file"
                                            else -> "parked — waiting for a stream"
                                        }
                                        outcomes += "Server ${src.serverNumber}: ${offered}p in ${tookMs}ms — exact, $parkReason ($sizeText)"
                                        DownloadLogs.log(
                                            "RESOLVE",
                                            "Server ${src.serverNumber}: exact ${offered}p for $quality in ${tookMs}ms — $parkReason ($sizeText), still racing",
                                        )
                                        exactCandidates += download
                                    } else {
                                        // 2.0-dev2.7: EXACT win — the only
                                        // tier that ends the batch. First
                                        // proven exact arrival IS the
                                        // fastest-proven server, so no
                                        // settle window is needed (and a
                                        // window would tax the lone-winner
                                        // common case against the ~1-2 s
                                        // bar). Log why it won, with every
                                        // other racer's outcome so far.
                                        outcomes += "Server ${src.serverNumber}: ${offered}p in ${tookMs}ms — WINNER"
                                        winner = download
                                        val stillRacing = group.map { it.serverNumber } - arrivedNumbers
                                        DownloadLogs.log(
                                            "RESOLVE",
                                            "race winner Server ${src.serverNumber} in ${tookMs}ms — offered ${offered}p, exact for $quality; " +
                                                "batch ${group.map { it.serverNumber }}: ${outcomes.joinToString("; ")}" +
                                                (if (stillRacing.isNotEmpty()) "; still racing (cancelled): ${stillRacing.joinToString(prefix = "Server ", separator = ", Server ")}" else ""),
                                        )
                                    }
                                } else if (offered >= floor) {
                                    // 2.0-dev2.7: meets the floor but NOT
                                    // the request — parked as a downgrade
                                    // candidate, never crowned on
                                    // arrival. A 720p-max server (e.g.
                                    // AniZone on Death Note E1) must
                                    // never win a 1080p request
                                    // outright; it is served, flagged,
                                    // only if no server proves 1080p.
                                    outcomes += "Server ${src.serverNumber}: ${offered}p in ${tookMs}ms — parked as downgrade"
                                    DownloadLogs.log(
                                        "RESOLVE",
                                        "Server ${src.serverNumber}: only ${offered}p for $quality " +
                                            "(requested ${requestedHeight}p, floor ${floor}p) — parked as downgrade, still racing",
                                    )
                                    val downgradeOffered = downgrade?.link?.offeredHeight() ?: 0
                                    if (downgrade == null || offered > downgradeOffered) downgrade = download
                                } else {
                                    outcomes += "Server ${src.serverNumber}: ${offered}p in ${tookMs}ms — parked, below floor ${floor}p"
                                    DownloadLogs.log(
                                        "RESOLVE",
                                        "Server ${src.serverNumber}: only ${offered}p for $quality " +
                                            "(floor ${floor}p) — parked, still racing",
                                    )
                                    val fallbackOffered = fallback?.link?.offeredHeight() ?: 0
                                    if (fallback == null || offered > fallbackOffered) fallback = download
                                }
                            }
                            is ResolvedLink.Failed -> {
                                outcomes += "Server ${src.serverNumber}: FAILED in ${tookMs}ms"
                                last = r
                            }
                        }
                    }
                }
                // 2.0-dev2.14: bounded phase wait (see above). The
                // timeout clause only exists when a deadline was
                // passed; without one the race waits for every
                // racer, as before.
                phaseDeadlineMs?.let { deadline ->
                    onTimeout(maxOf(1L, deadline - System.currentTimeMillis())) {
                        phaseTimedOut = true
                        DownloadLogs.log(
                            "RESOLVE",
                            "phase wait expired after ${RESOLVE_PHASE_WAIT_MS}ms — moving on with ${exactCandidates.size} exact candidate(s) parked, ${pending.size} racer(s) still out",
                        )
                    }
                }
            }
        }
        if (winner == null) {
            if ((preferSmaller || preferLargest || preferDirect || preferSoft) && exactCandidates.isNotEmpty()) {
                // 2.0-dev2.8: this batch parked exact candidates
                // instead of crowning one — one line naming the
                // batch's best, so the phone log shows the
                // comparison happening instead of going quiet
                // between batches. The caller picks the global
                // best across ALL batches. 2.0-dev2.10/2.14: same
                // parking happens in the subs modes (a
                // fallback-kind exact waiting for a preferred
                // one) and the largest-file mode.
                val batchBest = when {
                    preferSmaller -> pickSmallestExact(exactCandidates)
                    preferLargest -> pickLargestExact(exactCandidates)
                    else -> exactCandidates.firstOrNull()
                }
                DownloadLogs.log(
                    "RESOLVE",
                    "batch ${group.map { it.serverNumber }} ended with ${exactCandidates.size} exact candidate(s) for $quality (subs/size-preference mode) — " +
                        exactCandidates.joinToString("; ") { "Server ${it.serverNumber} (${if (it.link is ResolvedLink.DirectMp4) "direct" else "stream"}, ${it.link.sizeLabel() ?: "size unknown"})" } +
                        "; batch best: ${batchBest?.let { "Server ${it.serverNumber} (${it.link.sizeLabel() ?: "size unknown"})" } ?: "none"}; " +
                        "best downgrade: ${downgrade?.let { "Server ${it.serverNumber} (${it.link.offeredHeight() ?: "?"}p)" } ?: "none"}; " +
                        "unprovable parked: ${unprovableFallback?.serverNumber?.let { "Server $it" } ?: "none"}; " +
                        "unknown parked: ${unknownFallback?.serverNumber?.let { "Server $it" } ?: "none"}",
                )
            } else {
                // Batch ended with no exact winner: one line saying so,
                // so the next phone log shows the race moved on (and
                // why) instead of going quiet between batches.
                DownloadLogs.log(
                    "RESOLVE",
                    "batch ${group.map { it.serverNumber }} ended with no exact winner for $quality — " +
                        "best downgrade: ${downgrade?.let { "Server ${it.serverNumber} (${it.link.offeredHeight() ?: "?"}p)" } ?: "none"}; " +
                        "unprovable parked: ${unprovableFallback?.serverNumber?.let { "Server $it" } ?: "none"}; " +
                        "unknown parked: ${unknownFallback?.serverNumber?.let { "Server $it" } ?: "none"}",
                )
            }
        }
        // Best-effort cancel of the losers; their blocking I/O
        // finishes on its own threads and the results are discarded.
        pending.forEach { it.cancel() }
        return RaceResult(winner, downgrade, fallback, unknownFallback, unprovableFallback, last, exactCandidates)
    }

    suspend fun loadEpisodesWithFailover(
        candidates: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long? = null,
    ): EpisodeResult {
        var last: EpisodeResult = EpisodeResult.Failed("All servers failed — tap Retry.")
        var sawPending = false
        var sawFailed = false
        for (src in candidates) {
            try {
                when (val r = src.loadEpisodes(title, anilistId)) {
                    is EpisodeResult.Loaded -> return r
                    is EpisodeResult.SourcePending -> sawPending = true
                    is EpisodeResult.Failed -> {
                        last = r
                        sawFailed = true
                    }
                }
            } catch (e: Exception) {
                last = EpisodeResult.Failed(friendlyEpisodeListError(e))
                sawFailed = true
            }
        }
        // Every source pending (e.g. only the clearance-gated source
        // was offered and it has no clearance) reports pending, not
        // failure — the UI then offers the clearance flow.
        if (sawPending && !sawFailed) return EpisodeResult.SourcePending
        return last
    }
}

// --- Shared helpers for the resolvers --------------------------------

// Normalize for title matching: lowercase, strip all non-alphanumerics.
internal fun normalizeTitle(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

// 0 exact, 1 prefix, 2 contains, 3 no match. Lower is better.
internal fun titleMatchScore(found: String, want: String): Int = when {
    found.isEmpty() || want.isEmpty() -> 3
    found == want -> 0
    found.startsWith(want) || want.startsWith(found) -> 1
    found.contains(want) || want.contains(found) -> 2
    else -> 3
}

internal fun audioCode(audio: String): String =
    if (audio.equals("eng", ignoreCase = true) || audio.equals("Dub", ignoreCase = true)) "eng" else "jpn"

internal fun qualityHeight(quality: String): Int =
    Regex("""(\d+)""").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080

// Map a human track label ("English", "Español", "en-US") to a short
// language code for sidecar filenames. Unknown labels pass through
// sanitized by the caller.
internal fun languageCodeFromLabel(label: String?): String {
    val raw = label?.trim().orEmpty()
    if (raw.isEmpty()) return "en"
    // Already a code / locale: en, en-US, pt-BR …
    if (Regex("""^[A-Za-z]{2,3}(-[A-Za-z]{2})?$""").matches(raw)) return raw
    val lower = raw.lowercase()
        .substringBefore("[")
        .substringBefore("(")
        .trim()
    return when {
        lower.startsWith("english") -> "en"
        lower.startsWith("spanish") || lower.startsWith("espa") -> "es"
        lower.startsWith("french") || lower.startsWith("fran") -> "fr"
        lower.startsWith("german") || lower.startsWith("deutsch") -> "de"
        lower.startsWith("italian") -> "it"
        lower.startsWith("portuguese") || lower.startsWith("portugu") -> "pt"
        lower.startsWith("russian") -> "ru"
        lower.startsWith("arabic") -> "ar"
        lower.startsWith("japanese") -> "ja"
        lower.startsWith("hindi") -> "hi"
        lower.startsWith("tamil") -> "ta"
        lower.startsWith("telugu") -> "te"
        lower.startsWith("indonesian") -> "id"
        lower.startsWith("thai") -> "th"
        lower.startsWith("vietnamese") -> "vi"
        lower.startsWith("polish") -> "pl"
        lower.startsWith("malay") -> "ms"
        lower.startsWith("chinese") || lower.startsWith("mandarin") -> "zh"
        lower.startsWith("korean") -> "ko"
        lower.startsWith("turkish") -> "tr"
        lower.startsWith("dutch") -> "nl"
        lower.startsWith("czech") -> "cs"
        lower.startsWith("hungarian") -> "hu"
        lower.startsWith("romanian") -> "ro"
        lower.startsWith("ukrainian") -> "uk"
        lower.startsWith("bengali") -> "bn"
        lower.startsWith("malayalam") -> "ml"
        else -> "en"
    }
}

// Shared base for the HTTP resolvers: pacing between calls,
// blocking I/O confined to Dispatchers.IO, common title/audio utils.
// No Cloudflare solving, no WebView, no CookieManager anywhere.
// 2.0-dev2.9: pacing is PER ENDPOINT (paceFor), not one shared
// timestamp — the old single lastCallMs serialised parallel legs:
// each async child saw the previous child's timestamp and delayed
// ~400 ms in turn, so "parallel" legs still ran ~400 ms apart.
// The default pace() keeps the old 400 ms single-key behaviour
// for the resolvers that still call it; JustAnime/AnimeStream pace
// per endpoint at 100 ms (their rate limits allow it — JustAnime
// 600 req/60 s — and a resolve makes ~7 calls total).
internal abstract class BaseEpisodeSource(
    override val serverNumber: Int,
    override val serverDescription: String,
    override val sourceName: String,
) : EpisodeSource {
    private val paceLock = Any()
    private val paceLastCallMs = mutableMapOf<String, Long>()

    // Minimum gap `ms` between calls sharing `key`. Different keys
    // never wait on each other, so parallel legs paced on different
    // endpoints run concurrently. The wait is computed under a lock
    // and the timestamp is stamped after the delay, so two callers
    // racing the same key still space out by ~ms.
    protected suspend fun paceFor(key: String, ms: Long) {
        val wait = synchronized(paceLock) {
            val last = paceLastCallMs[key] ?: 0L
            (ms - (System.currentTimeMillis() - last)).coerceAtLeast(0L)
        }
        if (wait > 0) delay(wait)
        synchronized(paceLock) { paceLastCallMs[key] = System.currentTimeMillis() }
    }

    protected suspend fun pace(ms: Long = 400L) = paceFor("default", ms)

    protected fun normalize(s: String): String = normalizeTitle(s)

    protected fun score(found: String, want: String): Int = titleMatchScore(found, want)

    protected suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
    ): HttpResponse = withContext(Dispatchers.IO) {
        EngineHttp.requestWithRetry(url, headers = headers, timeoutMs = timeoutMs, followRedirects = followRedirects)
    }

    protected suspend fun httpPostJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val all = headers + mapOf("Content-Type" to "application/json")
        EngineHttp.requestWithRetry(
            url, method = "POST", headers = all,
            body = jsonBody.toByteArray(Charsets.UTF_8),
            timeoutMs = timeoutMs, followRedirects = false,
        )
    }

    // Parse a JSON episode number that may arrive as 1.0 (Double).
    protected fun jsonEpisodeNumber(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        else -> null
    }

    protected fun minutesToDuration(minutes: Long?): String? =
        minutes?.takeIf { it > 0 }?.let { "${it}m" }

    protected fun msToDuration(ms: Long?): String? =
        ms?.takeIf { it > 0 }?.let { "${it / 60_000}m" }
}

// --- Shared HTTP plumbing for the engine (PaheResolver, HlsDownloader).
// Browser UA + 8-15s timeouts, mirroring the AniLab.kt patterns.
// No new dependency: HttpURLConnection only.

internal const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

internal data class HttpResponse(
    val code: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun setCookies(): List<String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
}

internal object EngineHttp {
    // One request, no retries. followRedirects=false by default: the
    // Kwik form POST must NOT follow its 302 (the Location IS the mp4).
    //
    // 2.0-dev2.9 — BOUNDED READ (Connecting-hang fix): the old
    // `stream.readBytes()` relied on readTimeout alone, which is
    // per read() call, not a total deadline — a server dribbling
    // >=1 byte every <readTimeout kept readBytes() alive
    // indefinitely (Death Note E1, Server 8: 144 s stuck in the
    // init fetch, no return, no error). The body is now read in
    // 32 KB chunks with a wall-clock check between chunks:
    //  - totalDeadlineMs (>0): hard total deadline for the whole
    //    fetch — throws SocketTimeoutException when it fires.
    //  - firstByteDeadlineMs (>0): no byte within this long of
    //    starting the body read also throws SocketTimeoutException.
    //  - isCancelled: checked between chunks — throws
    //    CancellationException, so a cancel lands inside a fetch,
    //    not only between fetches.
    // All three default to off (0 / null), so every pre-2.9 caller
    // (resolvers, MediaIndex, ServerHealth) behaves exactly as
    // before: same bytes, same per-call socket timeouts.
    // `connectionHandle`, when given, is told about the live
    // HttpURLConnection so the caller can disconnect() it out of
    // a blocked read (the DDL cancel pattern, now for HLS too);
    // it is always unregistered in the finally below.
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        totalDeadlineMs: Int = 0,
        firstByteDeadlineMs: Int = 0,
        isCancelled: (() -> Boolean)? = null,
        connectionHandle: HlsConnectionHandle? = null,
    ): HttpResponse {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.size.toString())
            }
        }
        connectionHandle?.register(conn)
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                readBounded(input, totalDeadlineMs, firstByteDeadlineMs, isCancelled)
            } ?: ByteArray(0)
            return HttpResponse(code, bytes, conn.headerFields.filterKeys { it != null })
        } finally {
            connectionHandle?.unregister(conn)
            conn.disconnect()
        }
    }

    // Chunked body read with wall-clock deadlines. A single
    // read() can still block up to readTimeout, but the loop can
    // never run past the total deadline: every chunk return
    // re-checks the clock, so a dribble-feed server is cut off at
    // totalDeadlineMs instead of holding the fetch open forever.
    private fun readBounded(
        input: java.io.InputStream,
        totalDeadlineMs: Int,
        firstByteDeadlineMs: Int,
        isCancelled: (() -> Boolean)?,
    ): ByteArray {
        val startedMs = System.currentTimeMillis()
        val totalDeadline = if (totalDeadlineMs > 0) startedMs + totalDeadlineMs else Long.MAX_VALUE
        val firstByteDeadline = if (firstByteDeadlineMs > 0) startedMs + firstByteDeadlineMs else Long.MAX_VALUE
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var gotFirstByte = false
        while (true) {
            if (isCancelled?.invoke() == true) throw CancellationException("cancelled")
            val now = System.currentTimeMillis()
            if (!gotFirstByte && now > firstByteDeadline) {
                throw java.net.SocketTimeoutException("No data received within ${firstByteDeadlineMs}ms (first-byte deadline)")
            }
            if (now > totalDeadline) {
                throw java.net.SocketTimeoutException("Fetch exceeded its ${totalDeadlineMs}ms total deadline")
            }
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                gotFirstByte = true
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // Retries with backoff; honours Retry-After (seconds) on 429/503.
    // Returns the last response even on failure — the caller decides
    // what a non-200 means (a 403 is a signal, not an exception).
    // 2.0-dev2.9: the deadline / cancel / connection params are
    // threaded through to each attempt (per-attempt budgets, not a
    // cross-retry budget — HLS pre-segment callers pass
    // maxAttempts=1, so one attempt IS the whole budget there).
    // A cancel is checked before every backoff sleep, so a
    // retrying fetch still dies promptly on cancel instead of
    // sleeping out its backoff first. New params are appended
    // after maxAttempts so existing positional callers
    // (CronetHttp) keep compiling unchanged.
    fun requestWithRetry(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        maxAttempts: Int = 3,
        totalDeadlineMs: Int = 0,
        firstByteDeadlineMs: Int = 0,
        isCancelled: (() -> Boolean)? = null,
        connectionHandle: HlsConnectionHandle? = null,
    ): HttpResponse {
        var last: HttpResponse? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                last = request(url, method, headers, body, timeoutMs, followRedirects, totalDeadlineMs, firstByteDeadlineMs, isCancelled, connectionHandle)
                if (last.code !in listOf(429, 503) && last.code < 500) return last
                if (attempt == maxAttempts) return last
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                if (isCancelled?.invoke() == true) throw CancellationException("cancelled")
                Thread.sleep(1000L shl (attempt - 1))
                continue
            }
            if (isCancelled?.invoke() == true) throw CancellationException("cancelled")
            val retryAfter = last?.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60)
            val backoffMs = retryAfter?.times(1000) ?: (1000L shl (attempt - 1))
            Thread.sleep(backoffMs)
        }
        return last ?: throw java.io.IOException("Request failed with no response: $url")
    }
}

// Simple per-second throttle for the speed_mbps setting.
internal class SpeedThrottler(private val limitBytesPerSec: Long) {
    private var windowStartMs = System.currentTimeMillis()
    private var windowBytes = 0L
    suspend fun throttle(bytes: Int) {
        if (limitBytesPerSec <= 0) return
        windowBytes += bytes
        val now = System.currentTimeMillis()
        if (windowBytes >= limitBytesPerSec) {
            val wait = 1000L - (now - windowStartMs)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            windowStartMs = System.currentTimeMillis()
            windowBytes = 0
        } else if (now - windowStartMs >= 1000L) {
            windowStartMs = now
            windowBytes = 0
        }
    }
}

// Unescape a JS single-quoted string literal body: \' \\ \n \r \t
// \uXXXX \xXX and any other \c -> c. Used to decode Alpine
// x-data JSON.parse('...') blobs and similar embedded JSON.
internal fun unescapeJsString(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 6).coerceAtMost(s.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: 'u')
                    i += 4
                }
                'x' -> {
                    val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 4).coerceAtMost(s.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: 'x')
                    i += 2
                }
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// All JSON.parse('...') string bodies in a page, unescaped.
// Callers try each one as JSON until one has the shape they need.
internal fun extractJsonParseStrings(html: String): List<String> {
    val re = Regex("""JSON\.parse\('((?:[^'\\]|\\.)*)'\)""", RegexOption.DOT_MATCHES_ALL)
    return re.findAll(html).map { unescapeJsString(it.groupValues[1]) }.toList()
}

// Resolve a possibly-relative URL against a playlist/page URL.
internal fun resolveUrl(base: String, relative: String): String {
    if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
    return try {
        java.net.URL(java.net.URL(base), relative).toString()
    } catch (_: Exception) {
        relative
    }
}

// Friendly error mapping: raw failures become user-actionable strings
// instead of surfacing HTTP codes (MiruDL lesson).
internal fun friendlyDownloadError(e: Exception): String = when (e) {
    is java.io.FileNotFoundException -> "Video not found on the server — try another quality."
    is java.net.SocketTimeoutException -> "Connection timed out — check your network and retry."
    is java.net.UnknownHostException -> "No connection — check your network and retry."
    is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Download failed — tap Retry."
    else -> "Download failed — tap Retry."
}

// 2.0-dev1.4: the episode LIST has its own mapper. The list load
// used to reuse the download one, so a failed list told the user
// "Download failed" — wrong action, wrong words. List failures
// speak about the list. (2.0-dev1.6: scrubbed of source names —
// no source name may appear in any user-facing string.)
internal fun friendlyEpisodeListError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Couldn't load the episode list — connection timed out. Tap Retry."
    is java.net.UnknownHostException -> "Couldn't load the episode list — no connection. Tap Retry."
    is org.json.JSONException -> "Couldn't read the episode list — tap Retry."
    is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load the episode list — tap Retry."
    else -> "Couldn't load the episode list — tap Retry."
}
