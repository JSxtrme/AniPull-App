package com.js.anipull

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
// reach the UI. What the user sees is the server NUMBER (1..9) and the
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
    ) : ResolvedLink()
    data class Failed(val message: String) : ResolvedLink()
}

// A resolved link plus the server whose link won (1..9, null when
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
    // 1..9, see ServerRegistry. The ONLY user-facing identity.
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
// below-floor fallback. If all fail, the last Failed is returned
// (or a generic message when nothing ran), with serverNumber
// null. onAttempt fires with each server number as it is tried,
// so the UI can show live progress ("Trying Server N…"). The
// Pahe candidate is passed in already constructed with its
// clearance — the engine NEVER solves Cloudflare.
//
// 2.0-dev2.3: RACE, don't chain (MiruDL pattern — no sequential
// failover tax). Candidates run in batches of 3 in parallel and the
// first success wins; a batch with no winner falls through to the
// next batch. A slow server no longer blocks the servers behind it.
// Racers are launched as children of the CALLER's job (via the
// current coroutine context) and are NOT joined after a win —
// joining them would stall the win by up to a socket timeout when
// a loser is blocked in non-cancellable I/O. Losers are cancelled
// best-effort; they are bounded (per-attempt timeout + socket
// timeouts) and die with the caller's job. Each racer is capped
// at 15 s: a hung resolve fails over in seconds, not after ~40 s
// of retries (fail fast on resolve — segment retries are untouched).
object MultiSourceResolver {
    private const val RESOLVE_RACE_BATCH = 3
    private const val RESOLVE_ATTEMPT_TIMEOUT_MS = 15_000L

    suspend fun resolveWithFailover(
        candidates: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
        onAttempt: ((Int) -> Unit)? = null,
    ): ResolvedDownload {
        // 2.0-dev1.9 TEMPORARY download diagnostics: every resolve
        // attempt is logged (server number, result/error, latency)
        // so a pasted Download Logs report shows which servers even
        // answer on the phone. No URLs, no cookies in the log.
        DownloadLogs.log(
            "RESOLVE",
            "start: ep $number ${audio.lowercase()} $quality, candidates=${candidates.map { it.serverNumber }}",
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
        // 2.0-dev2.6: best UNKNOWN-quality link seen across ALL
        // batches (probed once, still unprovable). It ranks ABOVE
        // the known sub-floor fallback — it might meet the floor —
        // but BELOW any known meeting-floor win, and it is only
        // served after every batch has run, so a slower server that
        // CAN prove its quality always gets its chance first.
        var bestUnknown: ResolvedDownload? = null
        for (group in candidates.chunked(RESOLVE_RACE_BATCH)) {
            val result = raceGroup(group, title, anilistId, number, audio, quality, onAttempt)
            last = result.last
            if (result.winner != null) return result.winner
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
            if (result.unknownFallback != null && bestUnknown == null) bestUnknown = result.unknownFallback
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
    // by answering first.
    private data class RaceResult(
        val winner: ResolvedDownload?,
        val downgrade: ResolvedDownload?,
        val fallback: ResolvedDownload?,
        val unknownFallback: ResolvedDownload?,
        val last: ResolvedLink,
    )

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
    // Origin, 5 s cap, no retry; a master that IS a media playlist
    // (no variants) proves nothing and stays unknown.
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
            val height = probeHlsMasterHeight(link) ?: return link
            return link.copy(actualQuality = height)
        }
        return link
    }

    // 2.0-dev2.7: same proving as the race, exposed for the
    // serving-first path in App.kt resolveViaCandidates, which
    // bypasses the race — a serving server's link must be proven
    // before it is served, or a 720p-max server can be served
    // for a 1080p request with no downgrade flag.
    internal suspend fun proveLink(link: ResolvedLink): ResolvedLink = enrichLink(link)

    private suspend fun probeHlsMasterHeight(link: ResolvedLink.Hls): Int? {
        if (link.masterPlaylistUrl.isBlank()) return null
        return try {
            withTimeoutOrNull(5_000L) {
                withContext(Dispatchers.IO) {
                    val headers = buildMap {
                        link.referer?.let { put("Referer", it) }
                        link.origin?.let { put("Origin", it) }
                    }
                    val resp = EngineHttp.request(
                        link.masterPlaylistUrl,
                        headers = headers,
                        timeoutMs = 5_000,
                        followRedirects = true,
                    )
                    if (resp.code != 200 || !resp.text.contains("#EXTM3U")) null
                    else Regex("""RESOLUTION=\d+x(\d+)""").findAll(resp.text)
                        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                        .maxOrNull()?.takeIf { it > 0 }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun raceGroup(
        group: List<EpisodeSource>,
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
        onAttempt: ((Int) -> Unit)?,
    ): RaceResult {
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
                    val r = enrichLink(raw)
                    val tookMs = System.currentTimeMillis() - startedMs
                    when (r) {
                        is ResolvedLink.DirectMp4 -> {
                            DownloadLogs.log("RESOLVE", "Server ${src.serverNumber} (${src.sourceName}): OK in ${tookMs}ms — DDL mp4, offered ${r.offeredHeight() ?: "?"}p (${DownloadLogs.sanitizeUrl(r.url)})" + (r.sizeBytes()?.let { " size=${formatSizeBytes(it, r.sizeIsEstimate())}" } ?: ""))
                        }
                        is ResolvedLink.Hls -> {
                            DownloadLogs.log("RESOLVE", "Server ${src.serverNumber} (${src.sourceName}): OK in ${tookMs}ms — HLS master, offered ${r.offeredHeight() ?: "?"}p (${DownloadLogs.sanitizeUrl(r.masterPlaylistUrl)})")
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
        // 2.0-dev2.7: one-line outcome per arrived racer, so the
        // winner line below can name WHY it won (and what each
        // loser did) instead of leaving the next phone log to
        // guess — e.g. "Server 5 won" with no reason is how the
        // Death Note E1 question arose in the first place.
        val outcomes = mutableListOf<String>()
        val arrivedNumbers = mutableSetOf<Int>()
        val pending = deferreds.toMutableList()
        val requestedHeight = qualityHeight(quality)
        val floor = qualityFloorFor(requestedHeight)
        while (pending.isNotEmpty() && winner == null) {
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
                                    // 2.0-dev2.6: still unprovable after
                                    // the probe — park it. A known link
                                    // meeting the floor beats it even if
                                    // it arrives later in this batch; it
                                    // wins only if the batch ends with no
                                    // known winner at all.
                                    outcomes += "Server ${src.serverNumber}: unknown quality in ${tookMs}ms — parked"
                                    DownloadLogs.log(
                                        "RESOLVE",
                                        "Server ${src.serverNumber}: quality still unknown after probing — parked, a known-quality link wins first",
                                    )
                                    if (unknownFallback == null) unknownFallback = download
                                } else if (offered >= requestedHeight) {
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
            }
        }
        if (winner == null) {
            // Batch ended with no exact winner: one line saying so,
            // so the next phone log shows the race moved on (and
            // why) instead of going quiet between batches.
            DownloadLogs.log(
                "RESOLVE",
                "batch ${group.map { it.serverNumber }} ended with no exact winner for $quality — " +
                    "best downgrade: ${downgrade?.let { "Server ${it.serverNumber} (${it.link.offeredHeight() ?: "?"}p)" } ?: "none"}; " +
                    "unknown parked: ${unknownFallback?.serverNumber?.let { "Server $it" } ?: "none"}",
            )
        }
        // Best-effort cancel of the losers; their blocking I/O
        // finishes on its own threads and the results are discarded.
        pending.forEach { it.cancel() }
        return RaceResult(winner, downgrade, fallback, unknownFallback, last)
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

// Shared base for the HTTP resolvers: 0.4s pacing between calls,
// blocking I/O confined to Dispatchers.IO, common title/audio utils.
// No Cloudflare solving, no WebView, no CookieManager anywhere.
internal abstract class BaseEpisodeSource(
    override val serverNumber: Int,
    override val serverDescription: String,
    override val sourceName: String,
) : EpisodeSource {
    protected var lastCallMs = 0L

    protected suspend fun pace(ms: Long = 400L) {
        val wait = ms - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

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
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
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
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, bytes, conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }

    // Retries with backoff; honours Retry-After (seconds) on 429/503.
    // Returns the last response even on failure — the caller decides
    // what a non-200 means (a 403 is a signal, not an exception).
    fun requestWithRetry(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        maxAttempts: Int = 3,
    ): HttpResponse {
        var last: HttpResponse? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                last = request(url, method, headers, body, timeoutMs, followRedirects)
                if (last.code !in listOf(429, 503) && last.code < 500) return last
                if (attempt == maxAttempts) return last
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                Thread.sleep(1000L shl (attempt - 1))
                continue
            }
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
