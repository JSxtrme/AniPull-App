package com.js.anipull

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 2.0-dev1.0 ENGINE — pure-Kotlin HLS downloader, no ffmpeg.
// Master/variant playlist -> media playlist -> parallel segment
// download (per-segment persistence, so retry/resume only fetches
// missing segments — NEVER a full re-download) -> AES-128 decrypt
// -> REAL .mp4 remux with MediaExtractor + MediaMuxer. The raw-TS-
// concat-named-.mp4 shortcut (MiruDL) is explicitly rejected: files
// must seek and report duration correctly.
//
// Playlist parsing + AES are pure Kotlin (no Android APIs) so they
// can be checked off-device; only the remux touches android.media.

typealias HlsProgressCallback = (downloadedBytes: Long, totalBytesEstimate: Long, speedMBps: Double) -> Unit

// 2.0-dev2.9 — the live HttpURLConnection(s) of one HLS download,
// so the engine can disconnect() them out of a blocked read the
// moment the user cancels (the DDL pattern from DownloadEngine's
// activeDdlConnections, now for HLS too). The engine owns the
// per-job implementation; HlsDownloader only ever sees this
// interface, never a job id. register/unregister bracket each
// EngineHttp fetch; disconnectAll() is the cancel path.
interface HlsConnectionHandle {
    fun register(conn: java.net.HttpURLConnection)
    fun unregister(conn: java.net.HttpURLConnection)
    fun disconnectAll()
}

// 2.0-dev2.9 — thrown when the PRE-SEGMENT phase (master /
// variant / init / key fetch, or the first segment) exceeds its
// hard deadline, or when segment failures pass the fail-fast
// threshold before any segment lands. The engine catches this
// separately from generic failures: it means "this server is
// stalled, try the next one", not "the download failed". Never
// thrown once segments are flowing — a late-segment failure
// stays a plain IOException and resumes from the segment index.
class HlsStallException(message: String) : java.io.IOException(message)

// 2.0-dev2.9 — the phase a download is in, so the row and the
// notification can say "Fetching playlist…" / "Downloading
// init…" / "Segment 34/267" instead of one undifferentiated
// "Connecting…". The engine stores the label + detail on the job
// (DownloadJob.phaseLabel); App.kt renders it. Labels are
// user-facing strings by design — no source names in them.
enum class HlsPhase(val label: String) {
    FETCHING_PLAYLIST("Fetching playlist…"),
    DOWNLOADING_INIT("Downloading init…"),
    FETCHING_KEY("Fetching key…"),
    DOWNLOADING_SEGMENTS("Downloading segments…"),
    // 2.0-dev2.22 — finishing labels are hooks, not technical
    // terms: once the segments are in, the bar jumps to 100% and
    // the label says what the app is doing for the user
    // ("Wrapping up…", "Adding subtitles…", "Almost there…").
    // The old "Remuxing… 99%" sat frozen through the whole
    // remux + inject + publish (JS: "that's a way to lose
    // users"). The engine drops the segment-count detail on
    // these phases — the label alone carries it.
    REMUXING("Wrapping up…"),
    EMBEDDING_SUBTITLES("Adding subtitles…"),
    PUBLISHING("Almost there…"),
}

// 2.0-dev2.9 — hard deadlines for HLS fetches. Why these numbers:
// the diagnosed stall held a fetch open 144 s with zero bytes;
// a healthy playlist/init/key fetch answers in well under 10 s
// on any of the ten servers (the resolve race caps attempts at
// 15 s and masters are <1 MB — the Death Note master was 902 B).
// 10 s to first byte catches a dead/dribbling host fast; 30 s
// total bounds a slow-but-alive playlist/init/key fetch; 20 s
// total bounds a single segment (segments are ~2-10 s of video,
// a few MB at most). Socket connect/read timeouts stay 15 s per
// read() call as a floor under the wall-clock budgets.
internal const val HLS_PLAYLIST_FIRST_BYTE_MS = 10_000
internal const val HLS_PLAYLIST_TOTAL_MS = 30_000
internal const val HLS_KEY_TOTAL_MS = 20_000
internal const val HLS_SEGMENT_FIRST_BYTE_MS = 10_000
internal const val HLS_SEGMENT_TOTAL_MS = 20_000

object HlsDownloader {

    data class Variant(val uri: String, val height: Int, val bandwidth: Long, val audioGroup: String? = null)
    data class Segment(val index: Int, val uri: String, val keyUri: String?, val iv: ByteArray?, val ivFromTag: Boolean = false)
    data class MediaPlaylist(
        val segments: List<Segment>,
        val initSegmentUri: String?,
    )

    // 2.0-dev2.19 — a separate audio rendition (#EXT-X-MEDIA
    // TYPE=AUDIO): the GROUP-ID it belongs to, its language, its
    // playlist URI and whether it is the group's DEFAULT. When the
    // picked variant declares an AUDIO group, its segments are
    // video-only and the audio lives in this playlist — the
    // downloader must fetch it too (the Death Note E1 no-audio bug
    // was exactly this: the audio playlist was never fetched).
    data class AudioRendition(val groupId: String, val language: String, val uri: String, val isDefault: Boolean)

    // 2.0-dev2.19 — one subtitle cue, times in microseconds.
    data class SubtitleCue(val startUs: Long, val endUs: Long, val text: String)

    // 2.0-dev2.19 — a subtitle track's parsed cues, ready to be
    // embedded as one tx3g track in the mp4 (via Mp4WebVtt).
    // Language is one of the five embedded languages (see
    // EMBED_SUBTITLE_LANGUAGES).
    data class SubtitleTrackCues(val language: String, val cues: List<SubtitleCue>)

    // 2.0-dev2.19 — subtitle languages embedded into the mp4 (JS
    // 2026-11-19: "Just some major languages — English, Spanish,
    // Russian, German, Hindi. Enough"). No sidecar files are
    // written anymore: VLC lists these as subtitle tracks inside
    // the video itself.
    private val EMBED_SUBTITLE_LANGUAGES = setOf("en", "es", "ru", "de", "hi")

    // Parse #EXT-X-MEDIA attribute lists: TYPE/GROUP-ID/LANGUAGE/
    // NAME/URI/DEFAULT/FORCED (quoted values may contain commas).
    private fun parseMediaAttrs(line: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val body = line.substringAfter(":", "")
        val re = Regex("""([A-Z\-]+)=(?:"([^"]*)"|([^,]*)),?""")
        for (m in re.findAll(body)) {
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            attrs[m.groupValues[1]] = value
        }
        return attrs
    }

    // 2.0-dev1.6: #EXT-X-MEDIA SUBTITLES lines -> subtitle tracks
    // (LANGUAGE / NAME / URI, resolved against the playlist URL).
    // Audio groups are parsed alongside for variant picking (see
    // parseMasterPlaylist) but are not subtitle tracks.
    fun parseExtXMedia(text: String, baseUrl: String): List<SubtitleTrack> {
        val out = mutableListOf<SubtitleTrack>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            val attrs = parseMediaAttrs(line)
            if (!attrs["TYPE"].equals("SUBTITLES", ignoreCase = true)) continue
            val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: continue
            val language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() }
                ?: languageCodeFromLabel(attrs["NAME"])
            out += SubtitleTrack(
                language = language,
                url = resolveUrl(baseUrl, uri),
                label = attrs["NAME"]?.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    private fun parseAudioGroups(text: String): Map<String, List<String>> {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            val attrs = parseMediaAttrs(line)
            if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
            val group = attrs["GROUP-ID"] ?: continue
            val lang = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() } ?: continue
            groups.getOrPut(group) { mutableListOf() } += lang
        }
        return groups
    }

    // 2.0-dev2.19 — #EXT-X-MEDIA TYPE=AUDIO lines with their
    // playlist URIs (resolved against the master URL), for fetching
    // the separate audio rendition. parseAudioGroups above only
    // keeps languages, which is enough for variant picking but not
    // for fetching the audio playlist itself.
    private fun parseAudioRenditions(text: String, baseUrl: String): List<AudioRendition> {
        val out = mutableListOf<AudioRendition>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            val attrs = parseMediaAttrs(line)
            if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
            val group = attrs["GROUP-ID"] ?: continue
            val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: continue
            val lang = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() }
                ?: languageCodeFromLabel(attrs["NAME"])
            out += AudioRendition(
                groupId = group,
                language = lang,
                uri = resolveUrl(baseUrl, uri),
                isDefault = attrs["DEFAULT"]?.equals("YES", ignoreCase = true) == true,
            )
        }
        return out
    }

    // Does an audio-track language loosely match the requested audio?
    // Sub -> ja/jpn, Dub -> en/eng; case-insensitive prefix match.
    private fun audioMatches(language: String, audio: String): Boolean {
        val lang = language.lowercase()
        val wanted = if (audioCode(audio) == "eng") listOf("en", "eng", "english") else listOf("ja", "jpn", "japanese")
        return wanted.any { lang == it || lang.startsWith(it) }
    }

    // Master playlist: follow the variant at/below the requested
    // quality, else the lowest available. No variants = the master
    // IS the media playlist (returned unchanged).
    // 2.0-dev1.6: `audio` prefers a variant whose AUDIO group
    // contains a track in the requested language (Sub -> Japanese,
    // Dub -> English); no match falls back to the old behaviour.
    // 2.0-dev2.5 DEFECT 3: returns the picked variant's height
    // too — the caller MUST know what was actually picked. A
    // height of null means the master was itself a media playlist
    // (or carried no RESOLUTION): unknown, not low. The old
    // silent fallthrough (360p taken when only 360p exists) is
    // still the pick, but it is no longer silent: download()
    // throws QualityMismatchException when the pick is below the
    // requested floor.
    // 2.0-dev2.19: also returns the picked variant's AUDIO group
    // (null when the variant has no AUDIO tag). The caller needs it
    // to fetch the separate audio rendition — without it, a variant
    // that declares AUDIO serves video-only segments.
    data class PickedVariant(val url: String, val height: Int?, val audioGroup: String? = null)

    internal fun parseMasterPlaylist(
        text: String,
        baseUrl: String,
        requestedQuality: String,
        audio: String = "Sub",
    ): PickedVariant {
        val wanted = Regex("""(\d+)""").find(requestedQuality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
        val audioGroups = parseAudioGroups(text)
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(":")
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val audioGroup = Regex("""AUDIO="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1)
                val uriLine = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (uriLine != null) variants += Variant(resolveUrl(baseUrl, uriLine), height, bandwidth, audioGroup)
            }
            i++
        }
        if (variants.isEmpty()) return PickedVariant(baseUrl, null)
        val withHeight = variants.filter { it.height > 0 }
        val pool0 = if (withHeight.isNotEmpty()) withHeight else variants
        // Prefer variants whose audio group carries the requested
        // language; variants with no AUDIO tag stay eligible only
        // when nothing matches (a missing tag means "default audio").
        val audioPool = pool0.filter { v ->
            v.audioGroup?.let { g -> audioGroups[g]?.any { audioMatches(it, audio) } == true } ?: false
        }
        val pool = if (audioPool.isNotEmpty()) audioPool else pool0
        val picked = pool.filter { it.height in 1..wanted || it.height == 0 }
            .maxByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: pool.minByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: variants.last()
        return PickedVariant(picked.uri, picked.height.takeIf { it > 0 }, picked.audioGroup)
    }

    // Media playlist: segments in order, #EXT-X-KEY AES-128 state
    // carried forward until the next KEY tag, IV from the tag or
    // the segment sequence number as a 16-byte big-endian integer.
    internal fun parseMediaPlaylist(text: String, baseUrl: String): MediaPlaylist {
        val segments = mutableListOf<Segment>()
        var initUri: String? = null
        var mediaSequence = 0
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var seq = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    seq = mediaSequence
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    Regex("""URI="([^"]+)"""").find(line)?.let { initUri = resolveUrl(baseUrl, it.groupValues[1]) }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(":")
                    val method = Regex("""METHOD=([^,]+)""").find(attrs)?.groupValues?.getOrNull(1)?.trim()
                    if (method == "NONE") {
                        keyUri = null
                        keyIv = null
                    } else if (method == "AES-128") {
                        keyUri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1)?.let { resolveUrl(baseUrl, it) }
                        keyIv = Regex("""IV=0x([0-9a-fA-F]+)""").find(attrs)?.groupValues?.getOrNull(1)?.let { hexToBytes(it) }
                    }
                    // Any other METHOD is unsupported: leave keyUri
                    // as-is and let the download fail loudly below.
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val iv = keyIv ?: seq.toBigEndian16()
                    segments += Segment(
                        segments.size,
                        resolveUrl(baseUrl, line),
                        keyUri,
                        if (keyUri != null) iv else null,
                        ivFromTag = keyIv != null,
                    )
                    seq++
                }
            }
        }
        return MediaPlaylist(segments, initUri)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun Int.toBigEndian16(): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 4) out[15 - i] = ((this ushr (i * 8)) and 0xFF).toByte()
        return out
    }

    internal fun decryptSegment(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }

    // --- Subtitles: fetch + parse, for embedding into the mp4 -----
    // 2.0-dev2.19 — no sidecar files anymore (JS 2026-11-19): the
    // real subtitle files are parsed into cues and injected into
    // the video as tx3g tracks (Mp4WebVtt, dev2.20), so VLC lists
    // them as subtitle tracks and nothing extra sits in the folder.

    // Normalize a track's language/label to one of the five
    // embedded languages, or null when it isn't one of them.
    // Handles 2/3-letter codes ("en", "eng"), BCP-47 tags
    // ("es-419", "pt-BR") and names ("English", "Español").
    fun normalizeSubtitleLanguage(language: String?, label: String?): String? {
        for (raw in listOfNotNull(language, label)) {
            val lower = raw.trim().lowercase()
            if (lower.isEmpty()) continue
            val base = lower.split('-', '_').first()
            val mapped = when (base) {
                "en", "eng", "english" -> "en"
                "es", "spa", "spanish", "espanol", "español" -> "es"
                "ru", "rus", "russian" -> "ru"
                "de", "ger", "deu", "german", "deutsch" -> "de"
                "hi", "hin", "hindi" -> "hi"
                else -> null
            }
            if (mapped != null) return mapped
        }
        return null
    }

    private fun parseTimestampToUs(ts: String): Long? {
        val clean = ts.trim().replace(',', '.')
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    (h * 3600 + m * 60) * 1_000_000L + (s * 1_000_000).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    m * 60 * 1_000_000L + (s * 1_000_000).toLong()
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // WebVTT and SRT share one shape once the header/index lines
    // are ignored: blocks separated by blank lines, a "-->" line,
    // then the cue text. Returns cues sorted by start time.
    internal fun parseTimedText(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = text.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val first = lines[0].trim()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first == "STYLE" || first == "REGION") continue
            val tsIndex = lines.indexOfFirst { it.contains("-->") }
            if (tsIndex < 0) continue
            val arrow = lines[tsIndex].split("-->")
            if (arrow.size < 2) continue
            val start = parseTimestampToUs(arrow[0]) ?: continue
            val end = parseTimestampToUs(arrow[1].trim().split(" ").first()) ?: continue
            if (end <= start) continue
            val cueText = lines.drop(tsIndex + 1).joinToString("\n").trim()
            if (cueText.isEmpty()) continue
            cues += SubtitleCue(start, end, cueText)
        }
        return cues.sortedBy { it.startUs }
    }

    // A subtitle media playlist (.m3u8): fetch its .vtt segments
    // and merge their cues onto the episode timeline. Segment cue
    // times may be LOCAL (each chunk's clock restarts at zero) or
    // ABSOLUTE — see SubtitlePlaylist.kt (dev2.21). The old code
    // stitched the raw segment texts together, so with local-timed
    // segments all cues piled into the first ~6 s (Death Note E1).
    // A failed segment only loses that segment's cues — its EXTINF
    // still advances the offset, so later cues stay in place.
    private suspend fun fetchCuesFromSubtitlePlaylist(
        playlistText: String,
        playlistUrl: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        connectionHandle: HlsConnectionHandle?,
    ): List<SubtitleCue>? {
        val segments = parseSubtitlePlaylistSegments(playlistText)
        if (segments.isEmpty()) return null
        val perSegment = mutableListOf<List<TimedCue>>()
        for (seg in segments) {
            if (isCancelled()) return null
            val cues = try {
                val resp = EngineHttp.requestWithRetry(
                    resolveUrl(playlistUrl, seg.uri), headers = headers, timeoutMs = 15_000, followRedirects = true,
                    maxAttempts = 1,
                    totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                    firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                    isCancelled = { isCancelled() },
                    connectionHandle = connectionHandle,
                )
                if (resp.code != 200 || resp.bytes.isEmpty()) {
                    DownloadLogs.log("HLS", "subtitle segment ${seg.uri} HTTP ${resp.code} — skipped")
                    emptyList()
                } else {
                    parseTimedText(String(resp.bytes, Charsets.UTF_8)).map { TimedCue(it.startUs, it.endUs, it.text) }
                }
            } catch (_: Exception) {
                DownloadLogs.log("HLS", "subtitle segment ${seg.uri} fetch failed — skipped")
                emptyList()
            }
            perSegment += cues
        }
        val merged = mergeSubtitleSegmentCues(perSegment, segments.map { it.startOffsetUs })
        return merged.map { SubtitleCue(it.startUs, it.endUs, it.text) }.takeIf { it.isNotEmpty() }
    }

    // Fetch + parse one subtitle track's real content. Returns null
    // when the track has no usable cues — never throws, so a dead
    // subtitle track never kills the video.
    private suspend fun fetchSubtitleCues(
        track: SubtitleTrack,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        connectionHandle: HlsConnectionHandle?,
    ): SubtitleTrackCues? {
        val lang = normalizeSubtitleLanguage(track.language, track.label) ?: return null
        return try {
            val resp = EngineHttp.requestWithRetry(
                track.url, headers = headers, timeoutMs = 15_000, followRedirects = true,
                maxAttempts = 1,
                totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                isCancelled = { isCancelled() },
                connectionHandle = connectionHandle,
            )
            if (resp.code != 200 || resp.bytes.isEmpty()) {
                DownloadLogs.log("HLS", "subtitle $lang: HTTP ${resp.code} — skipped")
                return null
            }
            val body = String(resp.bytes, Charsets.UTF_8)
            val cues: List<SubtitleCue> = if (body.trimStart().startsWith("#EXTM3U")) {
                // The Death Note E1 case: the "subtitle" URL was a
                // media playlist, and saving it as .vtt was the
                // junk that hung VLC. Fetch its segments and merge
                // their cues onto the episode timeline (dev2.21).
                fetchCuesFromSubtitlePlaylist(body, track.url, headers, isCancelled, connectionHandle) ?: return null
            } else {
                parseTimedText(body)
            }
            if (cues.isEmpty()) {
                DownloadLogs.log("HLS", "subtitle $lang: no usable cues — skipped")
                return null
            }
            DownloadLogs.log("HLS", "subtitle $lang: ${cues.size} cues")
            SubtitleTrackCues(lang, cues)
        } catch (e: Exception) {
            DownloadLogs.log("HLS", "subtitle $lang: fetch failed (${e.message ?: "error"}) — skipped")
            null
        }
    }

    // Fetch + parse every usable subtitle track from the list,
    // filtered to the five embedded languages. Best-effort per
    // track; distinct by URL; one track per language (first usable
    // wins). Used by the HLS path (link + master tracks) and the
    // DDL post-pass (link tracks only).
    suspend fun fetchAndParseSubtitles(
        tracks: List<SubtitleTrack>,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        connectionHandle: HlsConnectionHandle?,
    ): List<SubtitleTrackCues> {
        val out = mutableListOf<SubtitleTrackCues>()
        val seenLang = mutableSetOf<String>()
        for (track in tracks.distinctBy { it.url }) {
            if (isCancelled()) break
            val lang = normalizeSubtitleLanguage(track.language, track.label) ?: continue
            if (lang in seenLang) continue
            // .ass/.ssa need a full ASS parser — skip honestly.
            if (track.url.lowercase().let { it.endsWith(".ass") || it.endsWith(".ssa") }) {
                DownloadLogs.log("HLS", "subtitle $lang: .ass format not supported for embedding — skipped")
                continue
            }
            val parsed = fetchSubtitleCues(track, headers, isCancelled, connectionHandle) ?: continue
            seenLang += parsed.language
            out += parsed
        }
        return out
    }

    // Downloads + remuxes one episode. tempDir holds seg files + a
    // segment index so a retry/resume only fetches missing segments.
    // Throws IOException on failure, CancellationException on cancel.
    // onProgress is throttled to ~every 500ms.
    // Returns the subtitle tracks found in the master playlist's
    // #EXT-X-MEDIA SUBTITLES groups (empty when none) plus the
    // picked variant's height (null when the master was itself a
    // media playlist). `audio` steers variant picking toward the
    // requested language.
    // 2.0-dev2.19: `linkSubtitles` (the resolver's tracks) are
    // merged with the master's subtitle tracks, parsed, and
    // EMBEDDED into the mp4 as tx3g tracks (Mp4WebVtt, dev2.20) —
    // no sidecar files are written anymore. When the picked
    // variant declares an AUDIO group, the separate audio
    // rendition is fetched and muxed with the video (the Death
    // Note E1 no-audio fix).
    // `onRemuxProgress` gets the remux fraction 0..1 so the bar
    // keeps moving through the remux instead of freezing at the
    // segment bytes (the 87% freeze).
    // `keyOverrideB64` / `ivOverrideB64` (base64 of 16 raw bytes)
    // replace segment-key fetching entirely when set: the decoded
    // key is used for ALL segment keys, and the decoded IV is used
    // only when a playlist KEY tag has no explicit IV. `referer`
    // is sent on playlist / segment / key fetches when the upstream
    // requires it. `origin` is sent alongside it when the upstream
    // requires an Origin too (JustAnime's calm leg needs both on
    // segments). Every fetch in this path follows redirects
    // (dev1.9 hypothesis left open).
    // 2.0-dev2.5 DEFECT 5: onProgress(0, -1, 0) is emitted at
    // every silent phase (master fetch, variant fetch, init
    // segment, keys, remux) so the UI shows "Connecting…" instead
    // of a silent 0% before the first segment lands.
    // 2.0-dev2.5 DEFECT 3: a picked variant below the requested
    // quality's floor throws QualityMismatchException — the job
    // fails honestly instead of completing a low-quality file.
    data class HlsDownloadResult(
        val subtitles: List<SubtitleTrack>,
        val actualHeight: Int?,
    )

    suspend fun download(
        masterUrl: String,
        outputFile: File,
        tempDir: File,
        quality: String,
        segmentWorkers: Int,
        speedLimitMBps: Int,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onProgress: HlsProgressCallback,
        audio: String = "Sub",
        keyOverrideB64: String? = null,
        ivOverrideB64: String? = null,
        referer: String? = null,
        origin: String? = null,
        // 2.0-dev2.9: live-connection handle (engine disconnects
        // these on cancel) and phase callback (engine shows the
        // phase on the row/notification). Both optional so older
        // callers keep compiling.
        connectionHandle: HlsConnectionHandle? = null,
        onPhase: ((HlsPhase, String?) -> Unit)? = null,
        // 2.0-dev2.19: the resolver's subtitle tracks (the engine
        // owns them; the master-advertised ones are found inside).
        linkSubtitles: List<SubtitleTrack> = emptyList(),
        // 2.0-dev2.19: remux fraction 0..1 (concat 0..0.5, mux
        // 0.5..1) — the engine maps it onto the bar.
        onRemuxProgress: ((Float) -> Unit)? = null,
    ): HlsDownloadResult = withContext(Dispatchers.IO) {
        val overrideKey: ByteArray? = keyOverrideB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { bytes -> bytes.size == 16 }
        }
        val overrideIv: ByteArray? = ivOverrideB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { bytes -> bytes.size == 16 }
        }
        val fetchHeaders: Map<String, String> = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
        }

        tempDir.mkdirs()
        // 2.0-dev2.19: the per-playlist done index now lives inside
        // downloadSegments (video + audio each get their own), so a
        // retry resumes exactly where each one stopped.

        // 2.0-dev1.9 TEMPORARY download diagnostics: every HLS
        // stage is logged (playlist codes, variant pick, key
        // fetches, per-segment errors, remux result) so the report
        // shows exactly where a stream dies. URLs are host+path
        // only — never query params / signatures.
        val hlsStartedMs = System.currentTimeMillis()
        DownloadLogs.log("HLS", "start: master=${DownloadLogs.sanitizeUrl(masterUrl)} quality=$quality audio=$audio referer=${referer ?: "none"} origin=${origin ?: "none"}")
        // 2.0-dev2.9: fail-fast abort flag. When the segment
        // failure threshold trips, this is set before throwing, so
        // sibling workers' in-flight fetches see it on their next
        // chunk check and die within one read instead of waiting
        // out their own deadlines — and the engine's cancel path
        // is not the only way a fetch can end early.
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        // Every fetch's cancel check: the USER cancel only.
        // The fail-fast abort deliberately does NOT ride this
        // lambda: readBounded turns it into CancellationException,
        // which the engine would (rightly) read as a user cancel
        // and mark the job CANCELLED. A fail-fast abort instead
        // disconnects the live connections (SocketException, a
        // plain per-segment failure) and throws HlsStallException
        // / IOException from the worker that tripped it.
        val fetchCancelled: () -> Boolean = { isCancelled() }
        // 2.0-dev2.9 DEFECT 5: connecting phase — no silent 0%.
        onProgress(0, -1, 0.0)
        // 2.0-dev2.9: log BEFORE each fetch (and emit the phase),
        // so the next log names the stall point instead of the
        // last success — the 2.8 log went silent after the media
        // playlist line because the init log only fired on return.
        onPhase?.invoke(HlsPhase.FETCHING_PLAYLIST, null)
        DownloadLogs.log("HLS", "fetching master playlist…")
        val masterText = try {
            EngineHttp.requestWithRetry(
                masterUrl, headers = fetchHeaders, timeoutMs = 15_000, followRedirects = true,
                // 2.0-dev2.9: pre-segment fetches get ONE attempt
                // with a hard total deadline — a stall here must
                // fail over to the next server, not retry the same
                // stalled host three times (~2 min of Connecting…).
                maxAttempts = 1,
                totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                isCancelled = fetchCancelled,
                connectionHandle = connectionHandle,
            )
        } catch (e: java.net.SocketTimeoutException) {
            throw HlsStallException("Server stalled fetching the playlist (${e.message ?: "timed out"}) — trying the next server.")
        }.let {
            DownloadLogs.log("HLS", "master playlist: HTTP ${it.code}, ${it.bytes.size} bytes")
            if (it.code != 200) throw java.io.IOException("Playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
            it.text
        }
        // 2.0-dev2.7 DEFECT 2: the fetch above is a blocking call
        // (up to 15s x 3 attempts, no cancellation inside it) —
        // a cancel can only land while it runs, so check the
        // moment it returns, before doing any more work.
        if (isCancelled()) throw CancellationException("HLS download cancelled")
        val subtitleTracks = parseExtXMedia(masterText, masterUrl).toMutableList()
        val pickedVariant = parseMasterPlaylist(masterText, masterUrl, quality, audio)
        val mediaUrl = pickedVariant.url
        val pickedHeight = pickedVariant.height
        // 2.0-dev2.5 DEFECT 3: the picked variant's height is now
        // known — a pick below the floor fails here, before a
        // single segment is downloaded, instead of completing a
        // mislabelled low-quality file. Unknown height (null) is
        // not a failure: the post-download probe decides those.
        val requestedHeight = qualityHeight(quality)
        if (pickedHeight != null && pickedHeight < qualityFloorFor(requestedHeight)) {
            DownloadLogs.log("HLS", "QUALITY MISMATCH: master offers only ${pickedHeight}p for $quality (floor ${qualityFloorFor(requestedHeight)}p)")
            throw QualityMismatchException("Only ${pickedHeight}p is available for this episode — try a lower quality.")
        }
        DownloadLogs.log("HLS", "variant: ${DownloadLogs.sanitizeUrl(mediaUrl)}${if (mediaUrl == masterUrl) " (master is media playlist)" else ""} picked=${pickedHeight ?: "?"}p requested=$quality")
        val mediaText = if (mediaUrl == masterUrl) masterText else run {
            // 2.0-dev2.5 DEFECT 5: variant-fetch phase, still connecting.
            onProgress(0, -1, 0.0)
            // 2.0-dev2.9: pre-fetch log + phase (see master fetch).
            onPhase?.invoke(HlsPhase.FETCHING_PLAYLIST, null)
            DownloadLogs.log("HLS", "fetching variant playlist…")
            try {
                EngineHttp.requestWithRetry(
                    mediaUrl, headers = fetchHeaders, timeoutMs = 15_000, followRedirects = true,
                    maxAttempts = 1,
                    totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                    firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                    isCancelled = fetchCancelled,
                    connectionHandle = connectionHandle,
                )
            } catch (e: java.net.SocketTimeoutException) {
                throw HlsStallException("Server stalled fetching the variant playlist (${e.message ?: "timed out"}) — trying the next server.")
            }.let {
                DownloadLogs.log("HLS", "variant playlist: HTTP ${it.code}, ${it.bytes.size} bytes")
                if (it.code != 200) throw java.io.IOException("Variant playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
                it.text
            }
        }
        // 2.0-dev2.7 DEFECT 2: same post-fetch cancel check as
        // the master fetch above.
        if (isCancelled()) throw CancellationException("HLS download cancelled")
        if (mediaUrl != masterUrl) {
            for (sub in parseExtXMedia(mediaText, mediaUrl)) {
                if (subtitleTracks.none { it.url == sub.url }) subtitleTracks += sub
            }
        }
        val playlist = parseMediaPlaylist(mediaText, mediaUrl)
        if (playlist.segments.isEmpty()) throw java.io.IOException("Playlist has no segments — the stream may have expired, retry the download.")
        // 2.0-dev2.2: first segment's host, once — the per-segment
        // lines never carry a URL, so without this the report
        // cannot say which host the 403s come from. Host only,
        // never a query/signature.
        val firstSegmentHost = playlist.segments.firstOrNull()?.uri?.let {
            try { java.net.URL(it).host } catch (_: Exception) { null }
        }?.takeIf { it.isNotBlank() } ?: "unknown"
        DownloadLogs.log("HLS", "media playlist: ${playlist.segments.size} segments, init=${playlist.initSegmentUri != null}, subs=${subtitleTracks.size}, first segment host=$firstSegmentHost")

        // 2.0-dev2.19 — separate audio rendition: when the picked
        // variant declares an AUDIO group, its segments are
        // video-only (Death Note E1: the audio playlist was never
        // fetched, so the remux produced a silent file). Fetch the
        // rendition's media playlist now — a stalled fetch fails
        // over to the next server (no segment has landed yet); a
        // plain HTTP error just logs and continues, and the remux
        // decides from the video file's own tracks whether the
        // audio was actually needed.
        var audioPlaylist: MediaPlaylist? = null
        var audioFirstHost = "unknown"
        val pickedAudioGroup = pickedVariant.audioGroup
        if (pickedAudioGroup != null) {
            val renditions = parseAudioRenditions(masterText, masterUrl)
                .filter { it.groupId == pickedAudioGroup }
            val rendition = renditions.firstOrNull { audioMatches(it.language, audio) }
                ?: renditions.firstOrNull { it.isDefault }
                ?: renditions.firstOrNull()
            if (rendition == null) {
                DownloadLogs.log("HLS", "audio group $pickedAudioGroup has no renditions — assuming muxed audio")
            } else {
                DownloadLogs.log("HLS", "audio rendition: lang=${rendition.language} default=${rendition.isDefault} ${DownloadLogs.sanitizeUrl(rendition.uri)}")
                onPhase?.invoke(HlsPhase.FETCHING_PLAYLIST, null)
                val audioText = try {
                    EngineHttp.requestWithRetry(
                        rendition.uri, headers = fetchHeaders, timeoutMs = 15_000, followRedirects = true,
                        maxAttempts = 1,
                        totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                        firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                        isCancelled = fetchCancelled,
                        connectionHandle = connectionHandle,
                    )
                } catch (e: java.net.SocketTimeoutException) {
                    throw HlsStallException("Server stalled fetching the audio playlist (${e.message ?: "timed out"}) — trying the next server.")
                }.let {
                    DownloadLogs.log("HLS", "audio playlist: HTTP ${it.code}, ${it.bytes.size} bytes")
                    if (it.code != 200) {
                        DownloadLogs.log("HLS", "audio playlist HTTP ${it.code} — continuing without separate audio (remux decides)")
                        null
                    } else it.text
                }
                if (audioText != null) {
                    if (isCancelled()) throw CancellationException("HLS download cancelled")
                    val parsed = parseMediaPlaylist(audioText, rendition.uri)
                    if (parsed.segments.isEmpty()) {
                        DownloadLogs.log("HLS", "audio playlist has no segments — continuing without separate audio")
                    } else {
                        audioPlaylist = parsed
                        audioFirstHost = parsed.segments.firstOrNull()?.uri?.let {
                            try { java.net.URL(it).host } catch (_: Exception) { null }
                        }?.takeIf { it.isNotBlank() } ?: "unknown"
                        DownloadLogs.log("HLS", "audio playlist: ${parsed.segments.size} segments, init=${parsed.initSegmentUri != null}, first segment host=$audioFirstHost")
                    }
                }
            }
        }

        // 2.0-dev2.19 — English subtitles are a MUST for Sub
        // downloads (JS 2026-11-19: "English is no debate must"):
        // if no usable English track exists, fail HERE, before a
        // single segment is downloaded, instead of completing a
        // video with no English subs. The other languages stay
        // best-effort (fetched after the segments, below). Dub
        // doesn't gate — its audio is already English. The parsed
        // English cues are kept and embedded at the remux, not
        // fetched twice.
        var englishSubs: SubtitleTrackCues? = null
        val needsEnglishSubs = audioCode(audio) != "eng"
        if (needsEnglishSubs) {
            val enTracks = (linkSubtitles + subtitleTracks).distinctBy { it.url }
                .filter { normalizeSubtitleLanguage(it.language, it.label) == "en" }
            for (track in enTracks) {
                englishSubs = fetchSubtitleCues(track, fetchHeaders, fetchCancelled, connectionHandle)
                if (englishSubs != null) break
            }
            if (englishSubs == null) {
                DownloadLogs.log("HLS", "NO ENGLISH SUBTITLES: no usable English track for this stream — failing before any segment")
                throw java.io.IOException("No English subtitles available from this server — try another server or a different quality.")
            }
            DownloadLogs.log("HLS", "English subtitles confirmed: ${englishSubs.cues.size} cues")
        }

        // Init segment (#EXT-X-MAP) first, if present. Factored
        // (2.0-dev2.19) so the audio rendition's init uses the same
        // fetch, timeouts and stall behaviour as the video init.
        suspend fun fetchInitSegment(uri: String, file: File) {
            if (file.exists()) return
            // 2.0-dev2.5 DEFECT 5: still connecting — keep the
            // phase callback going through init + key fetches.
            onProgress(0, -1, 0.0)
            // 2.0-dev2.9: pre-fetch log + phase — the 2.8 stall
            // died exactly here with no line naming it (the old
            // init log fired only AFTER the fetch returned).
            onPhase?.invoke(HlsPhase.DOWNLOADING_INIT, null)
            DownloadLogs.log("HLS", "fetching init segment…")
            val initResp = try {
                EngineHttp.requestWithRetry(
                    uri, headers = fetchHeaders, timeoutMs = 15_000, followRedirects = true,
                    maxAttempts = 1,
                    totalDeadlineMs = HLS_PLAYLIST_TOTAL_MS,
                    firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                    isCancelled = fetchCancelled,
                    connectionHandle = connectionHandle,
                )
            } catch (e: java.net.SocketTimeoutException) {
                throw HlsStallException("Server stalled fetching the init segment (${e.message ?: "timed out"}) — trying the next server.")
            }
            DownloadLogs.log("HLS", "init segment: HTTP ${initResp.code}, ${initResp.bytes.size} bytes")
            if (initResp.code != 200) throw java.io.IOException("Init segment fetch failed (HTTP ${initResp.code}) — retry the download.")
            file.writeBytes(initResp.bytes)
            // 2.0-dev2.7 DEFECT 2: post-fetch cancel check (see
            // the master fetch above).
            if (isCancelled()) throw CancellationException("HLS download cancelled")
        }

        val initFile = File(tempDir, "init.ts")
        playlist.initSegmentUri?.let { fetchInitSegment(it, initFile) }
        val audioInitFile = File(tempDir, "audio-init.ts")
        audioPlaylist?.initSegmentUri?.let { fetchInitSegment(it, audioInitFile) }

        // Keys: fetched once per unique URI, cached. A key override
        // replaces fetching entirely (rotating per-URI keys still
        // work: the override is used for every key URI).
        // 2.0-dev2.9: segments completed in THIS run. A key/segment
        // stall counts as a server stall (HlsStallException, the
        // engine fails over) only while nothing has completed yet
        // — once segments are flowing, a stall is a per-segment
        // failure and resumes from the segment index instead.
        val completedInRun = java.util.concurrent.atomic.AtomicInteger(0)
        val keyCache = mutableMapOf<String, ByteArray>()
        suspend fun keyFor(uri: String): ByteArray {
            overrideKey?.let { return it }
            synchronized(keyCache) { keyCache[uri] }?.let { return it }
            // 2.0-dev2.9: pre-fetch log + phase + hard deadline.
            onPhase?.invoke(HlsPhase.FETCHING_KEY, null)
            DownloadLogs.log("HLS", "fetching key…")
            return try {
                EngineHttp.requestWithRetry(
                    uri, headers = fetchHeaders, timeoutMs = 10_000, followRedirects = true,
                    maxAttempts = 1,
                    totalDeadlineMs = HLS_KEY_TOTAL_MS,
                    firstByteDeadlineMs = HLS_PLAYLIST_FIRST_BYTE_MS,
                    isCancelled = fetchCancelled,
                    connectionHandle = connectionHandle,
                )
            } catch (e: java.net.SocketTimeoutException) {
                if (completedInRun.get() == 0) throw HlsStallException("Server stalled fetching the stream key (${e.message ?: "timed out"}) — trying the next server.")
                throw java.io.IOException("Could not fetch the stream key (timed out).")
            }.let {
                DownloadLogs.log("HLS", "key fetch: ${DownloadLogs.sanitizeUrl(uri)} -> HTTP ${it.code}, ${it.bytes.size} bytes")
                if (it.code != 200 || it.bytes.size != 16) throw java.io.IOException("Could not fetch the stream key (HTTP ${it.code}).")
                it.bytes
            }.also { synchronized(keyCache) { keyCache[uri] = it } }
        }

        var downloadedBytes = 0L
        var lastReportMs = 0L
        var reportBytes = 0L
        var reportTimeMs = System.currentTimeMillis()
        val lock = Any()
        val throttler = SpeedThrottler(speedLimitMBps * 1024L * 1024L)
        val failed = mutableListOf<String>()

        suspend fun checkPausedOrCancelled() {
            if (isCancelled()) throw CancellationException("HLS download cancelled")
            while (isPaused()) {
                if (isCancelled()) throw CancellationException("HLS download cancelled")
                delay(300)
            }
        }

        // 2.0-dev2.19 — the segment loop, factored so the video AND
        // the separate audio playlist run through the same code
        // (same timeouts, fail-fast, resume, progress). Each
        // playlist gets its own file prefix + done index, so a
        // retry resumes exactly where each one stopped.
        suspend fun downloadSegments(
            list: MediaPlaylist,
            prefix: String,
            doneFileName: String,
            label: String,
            firstHost: String,
        ) {
            val indexFile = File(tempDir, doneFileName)
            val done = synchronized(indexFile) {
                if (indexFile.exists()) indexFile.readLines().mapNotNull { it.toIntOrNull() }.toMutableSet()
                else mutableSetOf<Int>()
            }
            fun markDone(idx: Int) = synchronized(indexFile) {
                done += idx
                try {
                    indexFile.appendText("$idx\n")
                } catch (_: Exception) {
                }
            }
            val count = list.segments.size
            // This playlist's own bytes (resumed + new), for the
            // per-playlist average in the progress estimate; the
            // shared downloadedBytes carries the global total.
            var playlistBytes = done.sumOf { idx ->
                File(tempDir, "${prefix}_%05d.ts".format(idx)).length()
            }
            synchronized(lock) { downloadedBytes += playlistBytes }

        // 2.0-dev2.7 DEFECT 2: cancel can only land between the
        // blocking segment fetches (EngineHttp has no interrupt
        // handle — see the report), never inside one. The one log
        // line here proves which phase a phone cancel died in.
        // 2.0-dev2.9: fixed — every segment fetch now carries the
        // hard total deadline AND the live-connection handle, so a
        // cancel disconnects the connection out of its read at
        // once, and a stalled first segment fails over instead of
        // hanging (see HlsStallException below).
        onPhase?.invoke(HlsPhase.DOWNLOADING_SEGMENTS, "$label ${done.size}/$count")
        DownloadLogs.log("HLS", "starting $label downloads: $count segments (${done.size} already done), $segmentWorkers workers, first segment host=$firstHost")
        try {
            coroutineScope {
            val semaphore = Semaphore(segmentWorkers.coerceIn(1, 16))
            list.segments.map { seg ->
                async {
                    semaphore.withPermit {
                        checkPausedOrCancelled()
                        val segFile = File(tempDir, "${prefix}_%05d.ts".format(seg.index))
                        if (seg.index in done && segFile.exists() && segFile.length() > 0) return@async
                        try {
                            val raw = EngineHttp.requestWithRetry(
                                seg.uri, headers = fetchHeaders, timeoutMs = 15_000, followRedirects = true,
                                // 2.0-dev2.9: one retry, hard 20 s
                                // total per attempt — a segment that
                                // cannot land in 40 s is a dead host,
                                // not a slow one.
                                maxAttempts = 2,
                                totalDeadlineMs = HLS_SEGMENT_TOTAL_MS,
                                firstByteDeadlineMs = HLS_SEGMENT_FIRST_BYTE_MS,
                                isCancelled = fetchCancelled,
                                connectionHandle = connectionHandle,
                            ).let {
                                if (it.code != 200) throw java.io.IOException("Segment ${seg.index} HTTP ${it.code}")
                                it.bytes
                            }
                            // 2.0-dev2.7 DEFECT 2: the fetch above is
                            // blocking — check the moment it returns,
                            // before decrypting / writing its bytes.
                            if (isCancelled()) throw CancellationException("HLS download cancelled")
                            // IV override applies only when the KEY
                            // tag carried no explicit IV; otherwise
                            // the tag's IV (or the sequence-derived
                            // one) stands.
                            val effectiveIv = if (seg.ivFromTag) seg.iv else overrideIv ?: seg.iv
                            val plain = if (seg.keyUri != null && effectiveIv != null) {
                                decryptSegment(raw, keyFor(seg.keyUri), effectiveIv)
                            } else if (seg.keyUri != null) {
                                throw java.io.IOException("Encrypted segment without an IV — unsupported stream.")
                            } else {
                                raw
                            }
                            // Atomic write: temp file then rename, so a
                            // crash never leaves a half segment marked done.
                            val tmp = File(tempDir, "${prefix}_%05d.tmp".format(seg.index))
                            tmp.writeBytes(plain)
                            if (!tmp.renameTo(segFile)) segFile.writeBytes(plain)
                            throttler.throttle(plain.size)
                            synchronized(lock) {
                                downloadedBytes += plain.size
                                playlistBytes += plain.size
                                val now = System.currentTimeMillis()
                                val elapsed = (now - reportTimeMs).coerceAtLeast(1)
                                val speed = (downloadedBytes - reportBytes) * 1000.0 / elapsed / (1024.0 * 1024.0)
                                val remaining = count - done.size - 1
                                val avg = if (done.isNotEmpty()) playlistBytes / done.size.coerceAtLeast(1) else plain.size.toLong()
                                val estimate = downloadedBytes + remaining.coerceAtLeast(0) * avg
                                if (now - lastReportMs >= 500) {
                                    lastReportMs = now
                                    reportBytes = downloadedBytes
                                    reportTimeMs = now
                                    // 2.0-dev2.7 DEFECT 2: never emit
                                    // progress for a job already
                                    // cancelled — a draining run must
                                    // not touch the new incarnation's
                                    // numbers after a re-queue.
                                    if (!isCancelled()) {
                                        onProgress(downloadedBytes, estimate, speed.coerceAtLeast(0.0))
                                        // 2.0-dev2.9: segment count
                                        // rides the same 500 ms
                                        // throttle, so the row can
                                        // show "Segment 34/267".
                                        onPhase?.invoke(HlsPhase.DOWNLOADING_SEGMENTS, "$label ${done.size}/$count")
                                    }
                                }
                            }
                            markDone(seg.index)
                            completedInRun.incrementAndGet()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: HlsStallException) {
                            // A key fetch that stalled before any
                            // segment landed: trip the abort and let
                            // the engine fail over to the next
                            // server. Siblings die on the disconnect.
                            aborted.set(true)
                            connectionHandle?.disconnectAll()
                            throw e
                        } catch (e: Exception) {
                            // A fail-fast abort already in flight:
                            // this worker only died on the
                            // disconnect — swallow it, the worker
                            // that tripped the abort reports it.
                            if (aborted.get()) return@async
                            // 2.0-dev2.9: a timeout before ANY
                            // segment completed in this run is the
                            // diagnosed stall (first segment never
                            // landed) — fail over, don't collect it
                            // as one failure among hundreds.
                            if (e is java.net.SocketTimeoutException && completedInRun.get() == 0) {
                                aborted.set(true)
                                connectionHandle?.disconnectAll()
                                throw HlsStallException("Server stalled on the first segment (${e.message ?: "timed out"}) — trying the next server.")
                            }
                            val msg = "segment ${seg.index}: ${friendlyDownloadError(e)}"
                            DownloadLogs.log("HLS", "FAILED $msg")
                            val failedCount = synchronized(failed) { failed += msg; failed.size }
                            // 2.0-dev2.9 FAIL-FAST: don't wait out
                            // all $total segments when the host is
                            // dead — 8 failures with zero completions
                            // means every worker failed once, and a
                            // quarter of the playlist failing means
                            // the host is flapping. Either way the
                            // remaining segments stay unmarked, so a
                            // retry resumes exactly where this run
                            // stopped. Zero completions fails over
                            // to the next server; later failures take
                            // the normal resume path.
                            val failFastAt = if (completedInRun.get() == 0) 8 else maxOf(8, count / 4)
                            if (failedCount >= failFastAt) {
                                aborted.set(true)
                                connectionHandle?.disconnectAll()
                                DownloadLogs.log("HLS", "fail-fast: $failedCount segment(s) failed, ${completedInRun.get()} completed in this run — aborting instead of waiting out $count $label segments")
                                if (completedInRun.get() == 0) throw HlsStallException("Server stalled — $failedCount segments failed before the first one landed. Trying the next server.")
                                throw java.io.IOException("$failedCount of $count $label segments failed — download aborted early, retry to resume. First: ${failed.first()}")
                            }
                        }
                    }
                }
            }.awaitAll()
            }
        } catch (e: CancellationException) {
            DownloadLogs.log("HLS", "cancelled during $label download (${done.size}/$count segments done)")
            throw e
        }
        if (failed.isNotEmpty()) {
            DownloadLogs.log("HLS", "aborted: ${failed.size} of $count $label segments failed in ${System.currentTimeMillis() - hlsStartedMs}ms — first: ${failed.first()}")
            throw java.io.IOException("${failed.size} of $count $label segments failed — download aborted, retry to resume. First: ${failed.first()}")
        }
        }

        // Video segments first, then the separate audio rendition
        // (if any): if the video fails fast, no time is wasted on
        // the audio playlist.
        downloadSegments(playlist, "seg", "segments.done", "Segment", firstSegmentHost)
        if (audioPlaylist != null) {
            downloadSegments(audioPlaylist, "aseg", "audio.done", "Audio segment", audioFirstHost)
        }
        // 2.0-dev2.7 DEFECT 2: do not emit the finish progress
        // (total == downloaded) for a cancelled job, and do not
        // start the remux after a cancel — remux() itself also
        // checks, per segment and per sample batch.
        checkPausedOrCancelled()
        onProgress(downloadedBytes, downloadedBytes, 0.0)
        DownloadLogs.log("HLS", "all segments done in ${System.currentTimeMillis() - hlsStartedMs}ms, ${downloadedBytes / 1024} KB — remuxing")

        // 2.0-dev2.19 — subtitles, before the remux: the
        // resolver's tracks + the master's tracks, parsed into
        // cues and embedded as text tracks. English was already
        // fetched + gated above for Sub (reused here, not fetched
        // twice); the other languages are best-effort, and for Dub
        // English is fetched here like the rest.
        val subTracksForEmbed = (linkSubtitles + subtitleTracks).distinctBy { it.url }
            .filter { englishSubs == null || normalizeSubtitleLanguage(it.language, it.label) != "en" }
        val otherSubs = fetchAndParseSubtitles(
            subTracksForEmbed,
            fetchHeaders,
            fetchCancelled,
            connectionHandle,
        )
        val embeddedSubs = listOfNotNull(englishSubs) + otherSubs
        if (embeddedSubs.isNotEmpty()) {
            DownloadLogs.log("HLS", "subtitles to embed: ${embeddedSubs.joinToString { "${it.language} (${it.cues.size} cues)" }}")
        }

        // 2.0-dev2.9: remux progress — the phase callback now
        // reports concat/mux progress instead of the UI sitting
        // silent at 100%/Connecting through the whole remux.
        onPhase?.invoke(HlsPhase.REMUXING, "0/${playlist.segments.size + (audioPlaylist?.segments?.size ?: 0)} segments")
        remux(
            tempDir, playlist.segments.size, initFile,
            audioPlaylist?.segments?.size ?: 0,
            if (audioPlaylist != null) audioInitFile else null,
            outputFile, embeddedSubs, isCancelled, onPhase, onRemuxProgress,
        )
        DownloadLogs.log("HLS", "remux done: ${outputFile.length() / 1024} KB -> ${outputFile.name}")
        // Segments are now inside the mp4; clean up.
        try {
            tempDir.deleteRecursively()
        } catch (_: Exception) {
        }
        HlsDownloadResult(subtitleTracks, pickedHeight)
    }

    // Concatenate init + decrypted segments into one TS, then remux
    // to a real mp4 with MediaExtractor + MediaMuxer (NOT raw concat).
    // 2.0-dev2.7 DEFECT 2: remux used to be a single uninterruptible
    // blocking stretch — a cancel tapped during it did nothing until
    // the whole file was remuxed. isCancelled is now checked per
    // segment while concatenating and every 1024 samples while
    // muxing, so a remux-phase cancel lands within one segment
    // copy / one sample batch. Throws CancellationException.
    // 2.0-dev2.9: remux progress — onPhase fires every 16
    // segments during the concat and every 8192 samples during
    // the mux (coarse on purpose: each fire is a job update +
    // notification post, so per-segment/per-sample would spam).
    // Remux itself stays single-threaded: MediaMuxer is serial,
    // and the concat order is the segment order.
    // 2.0-dev2.19: the remux now merges TWO extractors (video +
    // the separate audio rendition, when one was fetched). The
    // video file's own audio is skipped when the separate audio
    // file actually carries an audio track (keeping both would
    // double it); when it doesn't, the video file's audio is kept
    // instead (muxed streams). Subtitles are NOT muxed here —
    // MediaMuxer rejects subtitle tracks (dev2.19 lesson) — they
    // are injected afterwards by Mp4WebVtt (see remux()).
    private fun remux(
        tempDir: File,
        segmentCount: Int,
        initFile: File,
        audioSegmentCount: Int = 0,
        audioInitFile: File? = null,
        outputFile: File,
        subtitleCues: List<SubtitleTrackCues> = emptyList(),
        isCancelled: () -> Boolean = { false },
        onPhase: ((HlsPhase, String?) -> Unit)? = null,
        onRemuxProgress: ((Float) -> Unit)? = null,
    ) {
        // Concat: video first, then audio. The concat half of the
        // remux is progress 0..0.5; the mux half is 0.5..1.
        val totalConcat = segmentCount + audioSegmentCount
        fun concat(prefix: String, count: Int, init: File?, outFile: File, startIndex: Int) {
            outFile.outputStream().use { out ->
                if (init != null && init.exists()) init.inputStream().use { it.copyTo(out) }
                for (i in 0 until count) {
                    if (isCancelled()) {
                        DownloadLogs.log("HLS", "remux cancelled during concat (segment $i/$count)")
                        throw CancellationException("HLS remux cancelled")
                    }
                    val seg = File(tempDir, "${prefix}_%05d.ts".format(i))
                    if (!seg.exists()) throw java.io.IOException("Missing segment $i at remux — retry the download.")
                    seg.inputStream().use { it.copyTo(out) }
                    val done = startIndex + i + 1
                    if (done % 16 == 0 || done == totalConcat) {
                        onPhase?.invoke(HlsPhase.REMUXING, "${done - 1}/$totalConcat segments")
                        if (totalConcat > 0) onRemuxProgress?.invoke(0.5f * done / totalConcat)
                    }
                }
            }
        }
        val combined = File(tempDir, "combined.ts")
        concat("seg", segmentCount, initFile, combined, 0)
        val combinedAudio = if (audioSegmentCount > 0) File(tempDir, "combined-audio.ts") else null
        if (combinedAudio != null) {
            concat("aseg", audioSegmentCount, audioInitFile, combinedAudio, segmentCount)
        }
        val sources = mutableListOf(MuxSource(combined, includeVideo = true, includeAudio = true))
        if (combinedAudio != null) sources += MuxSource(combinedAudio, includeVideo = false, includeAudio = true)
        try {
            muxFromExtractors(sources, outputFile, isCancelled, onPhase, onRemuxProgress)
        } finally {
            try {
                combined.delete()
            } catch (_: Exception) {
            }
            try {
                combinedAudio?.delete()
            } catch (_: Exception) {
            }
        }
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw java.io.IOException("Remux produced an empty file — the stream may be unsupported.")
        }
        // 2.0-dev2.20 — subtitles are injected AFTER the remux,
        // as tx3g tracks (see Mp4WebVtt). MediaMuxer cannot add
        // subtitle tracks at all — dev2.19's text/vtt addTrack
        // was rejected by the muxer and failed the whole download.
        // Best-effort: a failed inject keeps the A/V-only file,
        // never fails the job.
        if (subtitleCues.isNotEmpty()) {
            // 2.0-dev2.22 — the inject rewrites the whole file and
            // is the longest silent stretch of the finish, so the
            // label switches here (the bar is already 100%).
            onPhase?.invoke(HlsPhase.EMBEDDING_SUBTITLES, null)
            val ok = Mp4WebVtt.inject(outputFile, subtitleCues.map { stream ->
                Mp4WebVtt.Track(stream.language, stream.cues.map { Mp4WebVtt.Cue(it.startUs, it.endUs, it.text) })
            }) { msg -> DownloadLogs.log("HLS", msg) }
            DownloadLogs.log(
                "HLS",
                if (ok) "subtitles embedded: ${subtitleCues.joinToString { "${it.language} (${it.cues.size} cues)" }}"
                else "subtitle embed failed — keeping A/V-only file",
            )
        }
    }

    // One input file for the mux: which of its tracks to keep.
    private data class MuxSource(
        val file: File,
        val includeVideo: Boolean,
        val includeAudio: Boolean,
    )

    // One extractor's read-ahead state: the next sample is held
    // (buffer + time + track) so the merge loop can compare
    // timestamps across sources before writing anything.
    private class ExtractorState(
        val extractor: MediaExtractor,
        val trackMap: IntArray, // extractor track index -> muxer track index (-1 = skipped)
        val buffer: ByteBuffer = ByteBuffer.allocate(1024 * 1024),
    ) {
        var hasSample = false
        var sampleTimeUs = 0L
        var sampleSize = 0
        var sampleTrack = -1
        var sampleFlags = 0

        fun readNext(): Boolean {
            sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                hasSample = false
                return false
            }
            sampleTrack = extractor.sampleTrackIndex
            sampleTimeUs = extractor.sampleTime
            sampleFlags = extractor.sampleFlags
            hasSample = true
            return true
        }

        fun advance() = extractor.advance()
    }

    // Mux every kept track from every source, merged by
    // timestamp. Samples are monotonic per track, which is all
    // MediaMuxer requires; cross-track order is the merge's
    // timestamp order. No subtitle tracks here — MediaMuxer
    // rejects them (dev2.19 lesson); Mp4WebVtt injects them
    // afterwards. The DDL post-pass reuses this with a single
    // source (the finished mp4) — see embedSubtitlesIntoMp4.
    private fun muxFromExtractors(
        sources: List<MuxSource>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onPhase: ((HlsPhase, String?) -> Unit)?,
        onRemuxProgress: ((Float) -> Unit)?,
    ) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val opened = sources.map { src ->
                val ex = MediaExtractor()
                extractors += ex
                ex.setDataSource(src.file.absolutePath)
                src to ex
            }
            // A separate audio file wins the audio tracks: the
            // video file's audio is then skipped. When the audio
            // file has no audio track at all, the video file's
            // audio is kept instead.
            val audioSourceHasAudio = opened.any { (src, ex) ->
                !src.includeVideo && src.includeAudio && (0 until ex.trackCount).any {
                    (ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
                }
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val states = mutableListOf<ExtractorState>()
            var videoDurationUs = 0L
            var keptTracks = 0
            for ((src, ex) in opened) {
                val trackMap = IntArray(ex.trackCount) { -1 }
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    val keep = when {
                        isVideo && src.includeVideo -> true
                        isAudio && src.includeAudio && !(src.includeVideo && audioSourceHasAudio) -> true
                        else -> false
                    }
                    if (!keep) continue
                    if (isVideo && format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = maxOf(videoDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                    trackMap[i] = muxer.addTrack(format)
                    ex.selectTrack(i)
                    keptTracks++
                }
                states += ExtractorState(ex, trackMap)
            }
            if (keptTracks == 0) throw java.io.IOException("Remux failed: no tracks found in the stream.")
            DownloadLogs.log(
                "HLS",
                "remux: ${opened.sumOf { it.second.trackCount }} source track(s), $keptTracks kept" +
                    (if (audioSourceHasAudio) ", audio from separate rendition" else ""),
            )
            muxer.start()
            started = true
            val info = android.media.MediaCodec.BufferInfo()
            var samples = 0
            var maxSampleTimeUs = 0L
            var lastProgressSent = 0f
            for (st in states) st.readNext()
            while (true) {
                // 2.0-dev2.7 DEFECT 2: remux-phase cancel check —
                // one sample batch at most.
                if (++samples % 1024 == 0 && isCancelled()) {
                    DownloadLogs.log("HLS", "remux cancelled during mux ($samples samples written)")
                    throw CancellationException("HLS remux cancelled")
                }
                // 2.0-dev2.9: coarse mux progress (see remux header).
                if (samples % 8192 == 0) onPhase?.invoke(HlsPhase.REMUXING, "muxing…")
                var bestTime = Long.MAX_VALUE
                var bestExtractor: ExtractorState? = null
                for (st in states) {
                    if (st.hasSample && st.sampleTimeUs < bestTime) {
                        bestTime = st.sampleTimeUs
                        bestExtractor = st
                    }
                }
                if (bestExtractor == null) break
                val st = bestExtractor
                info.set(0, st.sampleSize, st.sampleTimeUs, st.sampleFlags)
                muxer.writeSampleData(st.trackMap[st.sampleTrack], st.buffer, info)
                maxSampleTimeUs = maxOf(maxSampleTimeUs, st.sampleTimeUs)
                st.advance()
                st.readNext()
                // 2.0-dev2.19: mux fraction from the video
                // timeline, so the bar keeps moving through the
                // remux (the 87% freeze).
                if (videoDurationUs > 0 && samples % 512 == 0) {
                    val frac = (0.5f + 0.5f * (maxSampleTimeUs.toFloat() / videoDurationUs.toFloat())).coerceIn(0.5f, 1f)
                    if (frac - lastProgressSent >= 0.02f) {
                        lastProgressSent = frac
                        onRemuxProgress?.invoke(frac)
                    }
                }
            }
            onRemuxProgress?.invoke(1f)
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            for (ex in extractors) {
                try {
                    ex.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    // 2.0-dev2.20 — embed subtitle cues into a finished mp4 (the
    // DDL path: the file is a complete server file, never remuxed).
    // Copies the file, then injects one tx3g track per language
    // via Mp4WebVtt. No-op when cues is empty. On failure the
    // output file is deleted, so the caller keeps the original
    // file. (dev2.19 did this through MediaMuxer; the muxer
    // rejects subtitle tracks, so that path failed the job.)
    fun embedSubtitlesIntoMp4(
        inputFile: File,
        cues: List<SubtitleTrackCues>,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
    ) {
        if (cues.isEmpty()) return
        if (isCancelled()) return
        inputFile.copyTo(outputFile, overwrite = true)
        val ok = Mp4WebVtt.inject(outputFile, cues.map { stream ->
            Mp4WebVtt.Track(stream.language, stream.cues.map { Mp4WebVtt.Cue(it.startUs, it.endUs, it.text) })
        }) { msg -> DownloadLogs.log("HLS", msg) }
        if (!ok) {
            try {
                outputFile.delete()
            } catch (_: Exception) {
            }
        }
    }
}
