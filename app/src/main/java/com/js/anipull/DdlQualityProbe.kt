package com.js.anipull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// 2.0-dev2.6 — DDL QUALITY PROBE + HLS SIZE ESTIMATE.
//
// The defect: a DDL link can arrive with NO stated quality
// (actualQuality == null) and still be a 360p file — Death Note E1
// at 1080p resolved to JustAnime's gigi DDL (a 27 MB file) which
// the race could not reject, so the whole file downloaded and the
// post-download MediaExtractor check deleted it, forever, on Retry.
// This probe reads the real height from the mp4 header BEFORE the
// full download, with at most two small Range requests and a hard
// timeout. It never throws and never returns a guess: null means
// "could not determine", and callers must treat null as unknown
// (probe again later, or let the post-download check decide).
//
// HLS links get the same treatment for SIZE only (their height
// comes from the master playlist, parsed by HlsDownloader at
// download time): AVERAGE-BANDWIDTH x total duration as an
// estimate, or summed #EXT-X-BYTERANGE lengths when the playlist
// carries them (exact). No segments are ever downloaded here.
//
// 2.0-dev2.8 — SIZE CACHES. The download sheet re-asks for the
// size on every quality/audio/server tap; without caching each
// tap re-fetched the master + media playlists (and re-probed the
// DDL). Master/media playlist text, computed HLS sizes and DDL
// probe results are now cached in memory (~10 min TTL, ~20
// entries each, oldest evicted, ConcurrentHashMap, never throws),
// so a re-tap of a computed combo is instant with no re-fetch.
// The caches are per-URL / per-(master, quality, audio): a size
// is NEVER borrowed from another server's link — 1080p is only
// the dimensions, every source encodes at its own bitrate.
//
// 2.0-dev2.9 — ONE-MASTER-ALL-VARIANTS. estimateHlsSizeBytes
// used to fetch one media playlist per quality tap (master text
// was cached, the per-variant media fetch was not shared): the
// sheet's 4 quality taps cost 4 media fetches. Now one media
// fetch fixes the total duration and every other quality's size
// is derived as its own variant bitrate x that duration and
// cached under its own key — a later tap answers from cache.
// Only estimates are derived; an exact size still needs that
// variant's own media playlist. Plus an implausible-bandwidth
// guard: a variant stating under 100 kbps (JustAnime revo's
// BANDWIDTH=5184 for 1080p) is never multiplied into a frozen
// total — the estimate falls back to a partial-byterange mean
// or null instead of ~0.9 MB for a ~1400 s episode.

object DdlQualityProbe {

    // Height in px and/or exact total file size in bytes, when each
    // could be read. Either field may be null independently: a
    // faststart mp4 yields both, a moov-at-end file yields the size
    // from Content-Range even if the tail fetch finds no moov.
    // Null (the whole result) = neither could be determined.
    data class ProbeResult(val height: Int?, val totalBytes: Long?)

    // `isEstimate` is false only when the size came from summed
    // segment byte-ranges (exact); BANDWIDTH x duration is always
    // an estimate and must be displayed with "~".
    data class HlsSizeEstimate(val bytes: Long, val isEstimate: Boolean)

    private const val HEAD_BYTES = 64 * 1024 // one Range chunk
    private const val MAX_PLAYLIST_BYTES = 512 * 1024

    // 2.0-dev2.9 — implausible-bandwidth floor (see the object
    // header). A video variant stating under 100 kbps is not a
    // real video bitrate, so it is never multiplied into a size.
    private const val MIN_PLAUSIBLE_VIDEO_BANDWIDTH_BPS = 100_000L

    // 2.0-dev2.8 — in-memory size caches (see the object header).
    // TTL ~10 min: playlists are small text that can change, and
    // a DDL file's size/height never changes, but a bounded TTL
    // keeps stale entries from living forever. Bounded ~20 each,
    // oldest evicted first. Failures are never cached, so a failed
    // fetch/probe can be retried on the next tap.
    private const val SIZE_CACHE_TTL_MS = 10 * 60_000L
    private const val SIZE_CACHE_MAX_ENTRIES = 20

    private data class CachedEntry<T>(val value: T, val atMs: Long)

    private val masterPlaylistCache = ConcurrentHashMap<String, CachedEntry<String>>()
    private val mediaPlaylistCache = ConcurrentHashMap<String, CachedEntry<String>>()
    private val hlsSizeCache = ConcurrentHashMap<String, CachedEntry<HlsSizeEstimate>>()
    private val ddlProbeCache = ConcurrentHashMap<String, CachedEntry<ProbeResult>>()

    private fun <T> cachedGet(map: ConcurrentHashMap<String, CachedEntry<T>>, key: String): T? {
        return try {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.atMs > SIZE_CACHE_TTL_MS) {
                map.remove(key, entry)
                return null
            }
            entry.value
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> cachedPut(map: ConcurrentHashMap<String, CachedEntry<T>>, key: String, value: T) {
        try {
            if (map.size >= SIZE_CACHE_MAX_ENTRIES) {
                map.entries.minByOrNull { it.value.atMs }?.key?.let { map.remove(it) }
            }
            map[key] = CachedEntry(value, System.currentTimeMillis())
        } catch (_: Exception) {
            // Caching must never break the probe itself.
        }
    }

    // Probe a DDL mp4 URL: real video height + exact total size.
    // `headers` is the download's own headers (e.g. Referer) — the
    // proxy/CDN sees the same request the download would make.
    // Bounded: <= 2 ranged requests of <= 64 KB each, hard timeout
    // `timeoutMs` overall (default 5 s). Returns null when the
    // quality/size cannot be determined — never throws.
    suspend fun probeDdlQuality(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 5_000,
    ): ProbeResult? {
        if (url.isBlank()) return null
        // 2.0-dev2.8: a probed URL's height + exact total are file
        // properties — the same URL probes identically every time,
        // so a cached result answers a re-tap with no requests.
        cachedGet(ddlProbeCache, url)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { probeBlocking(url, headers) }
        }?.also { cachedPut(ddlProbeCache, url, it) }
    }

    // Estimate an HLS stream's total size in bytes: summed segment
    // byte-ranges when present (exact), else the picked variant's
    // BANDWIDTH x summed #EXTINF duration (estimate). Fetches only
    // the master + picked media playlist (small text, capped), never
    // segments. `headers` is the link's own Referer/Origin. Returns
    // null when no honest number can be computed — never throws,
    // never invents a size.
    // 2.0-dev2.9: as a side effect this also derives + caches the
    // sibling qualities' sizes from the same master + duration
    // (see deriveSiblingSizes), and refuses implausible
    // bandwidths (see MIN_PLAUSIBLE_VIDEO_BANDWIDTH_BPS).
    suspend fun estimateHlsSizeBytes(
        masterUrl: String,
        headers: Map<String, String> = emptyMap(),
        requestedQuality: String = "1080p",
        audio: String = "Sub",
        timeoutMs: Long = 5_000,
    ): HlsSizeEstimate? {
        if (masterUrl.isBlank()) return null
        // 2.0-dev2.8: a computed size is keyed by the exact
        // (master, quality, audio) — a re-tap of the same combo is
        // instant, and a different combo/server never reuses it.
        val sizeKey = "$masterUrl|$requestedQuality|$audio"
        cachedGet(hlsSizeCache, sizeKey)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                estimateBlocking(masterUrl, headers, requestedQuality, audio)
            }
        }?.also { cachedPut(hlsSizeCache, sizeKey, it) }
    }

    // --- DDL probe (blocking I/O; caller confines to Dispatchers.IO) ---

    private data class RangeResult(val bytes: ByteArray, val totalBytes: Long?)

    private fun probeBlocking(url: String, headers: Map<String, String>): ProbeResult? {
        return try {
            // Request 1: head of the file. A 206 gives us the moov
            // box (faststart) and Content-Range gives the exact
            // total size. A 200 means the server ignored Range: the
            // probe ABORTS (return null) — reading the body would
            // download the whole file, which is exactly what the
            // probe exists to avoid. Any other code: unknown.
            val first = rangeGet(url, headers, 0L, HEAD_BYTES.toLong() - 1) ?: return null
            val height = findVideoHeight(first.bytes)
            val total = first.totalBytes
            if (height != null || total == null) {
                // Height found, or no total to aim a tail request at.
                if (height == null && total == null) return null
                return ProbeResult(height, total)
            }
            // Height not in the head but the total is known: moov is
            // probably at the end. Request 2 (the ONLY second
            // request): the tail of the file.
            val tailStart = (total - HEAD_BYTES).coerceAtLeast(0)
            val tail = rangeGet(url, headers, tailStart, total - 1)
            val tailHeight = tail?.let { findVideoHeight(it.bytes) }
            if (tailHeight == null && height == null) return ProbeResult(null, total)
            ProbeResult(tailHeight ?: height, total)
        } catch (_: Exception) {
            null
        }
    }

    // One bounded Range GET. Returns null unless the server honoured
    // Range with a 206. The body is read capped at the requested
    // range length; on a 200 the body is NEVER read.
    private fun rangeGet(
        url: String,
        headers: Map<String, String>,
        from: Long,
        to: Long,
    ): RangeResult? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1_500
                readTimeout = 2_000
                instanceFollowRedirects = true // the DDL proxy redirects
                setRequestProperty("User-Agent", BROWSER_UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", "bytes=$from-$to")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val total = conn.getHeaderField("Content-Range")
                ?.substringAfterLast("/", "")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val bytes = readCapped(conn, (to - from + 1).toInt().coerceAtLeast(0))
            return RangeResult(bytes, total)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readCapped(conn: HttpURLConnection, maxBytes: Int): ByteArray {
        conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }
    }

    // --- mp4 box parsing (pure, in-memory, bounds-checked) ---

    private fun u32be(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or
            ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or
            (data[off + 3].toLong() and 0xFF)

    private fun u64be(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }

    private data class Box(val type: String, val contentStart: Int, val contentEnd: Int)

    // Top-level / child boxes in [start, end). Stops at the first
    // malformed or truncated header — a cut chunk (tail fetch) just
    // yields fewer boxes, never a crash or a misread.
    private fun childBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        var off = start
        while (off + 8 <= end) {
            val size32 = u32be(data, off)
            val type = try {
                String(data, off + 4, 4, Charsets.US_ASCII)
            } catch (_: Exception) {
                break
            }
            // Box types are printable ASCII. Anything else means
            // this offset is not a box boundary (e.g. mdat payload).
            if (!type.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) break
            val headerSize: Int
            val boxEnd: Long
            when (size32) {
                1L -> {
                    if (off + 16 > end) break
                    headerSize = 16
                    boxEnd = off + u64be(data, off + 8)
                }
                0L -> {
                    headerSize = 8
                    boxEnd = end.toLong()
                }
                else -> {
                    headerSize = 8
                    boxEnd = off + size32
                }
            }
            if (boxEnd <= off + headerSize || boxEnd > end) break
            out += Box(type, off + headerSize, boxEnd.toInt())
            off = boxEnd.toInt()
        }
        return out
    }

    // Width/height are the last 8 bytes of a tkhd box, 16.16 fixed
    // point, in both tkhd versions. Null when absent or insane.
    private fun tkhdDims(data: ByteArray, contentStart: Int, contentEnd: Int): Int? {
        if (contentEnd - contentStart < 8) return null
        val w = (u32be(data, contentEnd - 8) shr 16).toInt()
        val h = (u32be(data, contentEnd - 4) shr 16).toInt()
        if (w <= 0 || w > 7680 || h <= 0 || h > 4320) return null
        return h
    }

    private fun trakVideoHeight(data: ByteArray, trak: Box): Int? {
        val tkhd = childBoxes(data, trak.contentStart, trak.contentEnd)
            .firstOrNull { it.type == "tkhd" } ?: return null
        val dims = tkhdDims(data, tkhd.contentStart, tkhd.contentEnd) ?: return null
        // hdlr handler_type sits 8 bytes into the hdlr content.
        val mdia = childBoxes(data, trak.contentStart, trak.contentEnd)
            .firstOrNull { it.type == "mdia" }
        val hdlr = mdia?.let {
            childBoxes(data, it.contentStart, it.contentEnd).firstOrNull { b -> b.type == "hdlr" }
        }
        val isVideo = hdlr != null && hdlr.contentEnd - hdlr.contentStart >= 12 &&
            String(data, hdlr.contentStart + 8, 4, Charsets.US_ASCII) == "vide"
        // Video track wins; an audio tkhd (0x0 dims) never reaches
        // here because tkhdDims already rejected it. A trak with no
        // hdlr at all is only used when no video trak was found.
        return if (isVideo) dims else null
    }

    // Fallback for a tail chunk whose moov header was cut off: scan
    // for a tkhd box directly. Validated hard (sane box size,
    // version 0/1, sane dims) because the bytes could be payload.
    private fun scanTkhdDims(data: ByteArray): Int? {
        var i = 0
        while (i + 4 <= data.size) {
            if (data[i] == 't'.code.toByte() && data[i + 1] == 'k'.code.toByte() &&
                data[i + 2] == 'h'.code.toByte() && data[i + 3] == 'd'.code.toByte()
            ) {
                if (i >= 4) {
                    val boxSize = u32be(data, i - 4)
                    val boxEnd = i - 4 + boxSize
                    if (boxSize in 20..65_536 && boxEnd <= data.size) {
                        val version = data[i + 4].toInt()
                        if (version == 0 || version == 1) {
                            val dims = tkhdDims(data, i + 4, boxEnd.toInt())
                            if (dims != null) return dims
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    // Real video height in px, or null when it cannot be read from
    // these bytes (no moov here, non-mp4, truncated). Structured
    // walk first (moov > trak with hdlr 'vide' > tkhd); the raw
    // tkhd scan only runs when no moov box survived the cut.
    private fun findVideoHeight(data: ByteArray): Int? {
        val moovs = childBoxes(data, 0, data.size).filter { it.type == "moov" }
        for (moov in moovs) {
            val traks = childBoxes(data, moov.contentStart, moov.contentEnd)
                .filter { it.type == "trak" }
            var fallback: Int? = null
            for (trak in traks) {
                val dims = trakVideoHeight(data, trak)
                if (dims != null) return dims
                // Trak without a readable hdlr: remember its dims
                // as a last resort, but keep looking for a video one.
                if (fallback == null) {
                    val tkhd = childBoxes(data, trak.contentStart, trak.contentEnd)
                        .firstOrNull { it.type == "tkhd" }
                    fallback = tkhd?.let { tkhdDims(data, it.contentStart, it.contentEnd) }
                }
            }
            if (fallback != null) return fallback
        }
        return if (moovs.isEmpty()) scanTkhdDims(data) else null
    }

    // --- HLS size estimate (blocking I/O; caller confines to Dispatchers.IO) ---

    // 2.0-dev2.8: `cache` is the playlist cache for this fetch
    // (master or media playlist text, keyed by URL) — a cached
    // playlist answers with no request. Null = no caching.
    private fun fetchTextCapped(
        url: String,
        headers: Map<String, String>,
        cache: ConcurrentHashMap<String, CachedEntry<String>>? = null,
    ): String? {
        cache?.let { cachedGet(it, url)?.let { text -> return text } }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1_500
                readTimeout = 2_500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = readCapped(conn, MAX_PLAYLIST_BYTES)
            if (bytes.isEmpty()) return null
            return bytes.toString(Charsets.UTF_8).also { text ->
                cache?.let { cachedPut(it, url, text) }
            }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // One variant row of a master playlist: height from
    // RESOLUTION, bitrate, and audio group for the Sub/Dub pick.
    // Parsed once per master fetch and reused for every quality,
    // instead of re-reading the master per tap.
    // 2.0-dev2.8 (carried over): AVERAGE-BANDWIDTH is preferred
    // over BANDWIDTH. BANDWIDTH is the PEAK bitrate and
    // overestimates badly — a calm 1080p variant states BANDWIDTH
    // 6,715,884 against an AVERAGE-BANDWIDTH of 3,124,000
    // (~1.15 GB vs ~540 MB for the same stream). The average is
    // the honest basis for a size estimate (typical accuracy
    // ~5–15% — code comment only, the UI never claims accuracy,
    // it only prefixes "~").
    // Audio: the variant bitrate ALREADY includes the audio track
    // — EXT-X-MEDIA carries no BANDWIDTH of its own — so NO
    // separate audio-bitrate addition is ever made here.
    private data class MasterVariant(val url: String, val height: Int, val bandwidth: Long, val audioGroup: String?)

    private fun parseMasterVariants(masterText: String, masterUrl: String): List<MasterVariant> {
        val lines = masterText.lineSequence().map { it.trim() }.toList()
        val out = mutableListOf<MasterVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(":")
                val uriLine = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (uriLine != null) {
                    val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val bandwidth = Regex("""AVERAGE-BANDWIDTH=(\d+)""").find(attrs)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                        ?: Regex("""BANDWIDTH=(\d+)""").find(attrs)
                            ?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
                    val audioGroup = Regex("""AUDIO="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1)
                    out += MasterVariant(resolveUrl(masterUrl, uriLine), height, bandwidth, audioGroup)
                }
            }
            i++
        }
        return out
    }

    // Mirrors HlsDownloader.parseAudioGroups / audioMatches
    // (private there, and this file must not depend on the
    // engine): GROUP-ID -> languages for TYPE=AUDIO #EXT-X-MEDIA
    // lines. Sub -> ja/jpn, Dub -> en/eng; case-insensitive
    // prefix match.
    private fun parseAudioGroups(masterText: String): Map<String, List<String>> {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (raw in masterText.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            val attrs = line.substringAfter(":")
            val type = Regex("""TYPE=([^,]+)""").find(attrs)?.groupValues?.getOrNull(1)?.trim()
            if (!type.equals("AUDIO", ignoreCase = true)) continue
            val group = Regex("""GROUP-ID="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1) ?: continue
            val lang = Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            groups.getOrPut(group) { mutableListOf() } += lang
        }
        return groups
    }

    private fun audioGroupMatches(language: String, audio: String): Boolean {
        val lang = language.lowercase()
        val wanted = if (audioCode(audio) == "eng") listOf("en", "eng", "english") else listOf("ja", "jpn", "japanese")
        return wanted.any { lang == it || lang.startsWith(it) }
    }

    // Mirrors HlsDownloader.parseMasterPlaylist's pick exactly
    // (audio-group preference, then tallest at/below the wanted
    // height, else the shortest) so the size is computed for the
    // SAME variant the download would pick — a different pick
    // here would show another variant's size.
    private fun pickVariant(
        variants: List<MasterVariant>,
        audioGroups: Map<String, List<String>>,
        requestedQuality: String,
        audio: String,
    ): MasterVariant? {
        if (variants.isEmpty()) return null
        val wanted = Regex("""(\d+)""").find(requestedQuality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
        val withHeight = variants.filter { it.height > 0 }
        val pool0 = if (withHeight.isNotEmpty()) withHeight else variants
        val audioPool = pool0.filter { v ->
            v.audioGroup?.let { g -> audioGroups[g]?.any { audioGroupMatches(it, audio) } == true } ?: false
        }
        val pool = if (audioPool.isNotEmpty()) audioPool else pool0
        return pool.filter { it.height in 1..wanted || it.height == 0 }
            .maxByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: pool.minByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: variants.last()
    }

    // Totals of one media playlist: summed #EXTINF durations,
    // segment count, and the #EXT-X-BYTERANGE lengths carried
    // (one per segment when the playlist is exact).
    private data class MediaStats(val durationSum: Double, val segmentCount: Int, val byteRanges: List<Long>)

    private fun parseMediaStats(mediaText: String): MediaStats {
        var durationSum = 0.0
        var segmentCount = 0
        val byteRanges = mutableListOf<Long>()
        var pendingRange: Long? = null
        for (raw in mediaText.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    line.substringAfter(":").substringBefore(",").toDoubleOrNull()
                        ?.let { durationSum += it }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = line.substringAfter(":").substringBefore("@").toLongOrNull()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segmentCount++
                    pendingRange?.let { byteRanges += it }
                    pendingRange = null
                }
            }
        }
        return MediaStats(durationSum, segmentCount, byteRanges)
    }

    // 2.0-dev2.9 — one-master-all-variants: the master fetch
    // lists every variant's bitrate, and this media fetch fixed
    // the total duration (same episode, different encode — the
    // durations match across variants). Derive every other
    // quality's size as its own bitrate x that duration and cache
    // it under its own (master, quality, audio) key, so the
    // sheet's next tap for a sibling quality answers from cache
    // with no re-fetch. Only estimates are derived — an exact
    // size still needs that variant's own media playlist. A
    // sibling picking the same file as the requested pick is
    // skipped (its size is the requested one, computed exactly
    // when possible), and variants with implausible bandwidths
    // are skipped, never cached — failures are never cached, so
    // a later direct estimate can still try.
    // shortcut: sibling sizes assume all variants share this
    // variant's total duration; if a source ever serves different
    // cuts per quality, sibling estimates drift by the duration
    // difference — fetch that variant's media playlist then.
    private fun deriveSiblingSizes(
        masterUrl: String,
        variants: List<MasterVariant>,
        audioGroups: Map<String, List<String>>,
        audio: String,
        durationSum: Double,
        requestedQuality: String,
        pickedUrl: String?,
    ) {
        if (variants.isEmpty() || durationSum <= 0) return
        for (quality in listOf("360", "480", "720", "1080")) {
            if (quality == requestedQuality.removeSuffix("p")) continue
            val key = "$masterUrl|$quality|$audio"
            if (cachedGet(hlsSizeCache, key) != null) continue
            val variant = pickVariant(variants, audioGroups, quality, audio) ?: continue
            if (variant.url == pickedUrl) continue
            if (variant.bandwidth < MIN_PLAUSIBLE_VIDEO_BANDWIDTH_BPS) continue
            val bytes = (variant.bandwidth * durationSum / 8.0).toLong()
            if (bytes > 0) cachedPut(hlsSizeCache, key, HlsSizeEstimate(bytes, isEstimate = true))
        }
    }

    private fun estimateBlocking(
        masterUrl: String,
        headers: Map<String, String>,
        requestedQuality: String,
        audio: String,
    ): HlsSizeEstimate? {
        return try {
            val masterText = fetchTextCapped(masterUrl, headers, masterPlaylistCache) ?: return null
            // 2.0-dev2.9: parse the variants ONCE from this single
            // master fetch — the pick below and every sibling
            // derivation read the same list, no re-parse per tap.
            val variants = parseMasterVariants(masterText, masterUrl)
            val audioGroups = parseAudioGroups(masterText)
            val picked = pickVariant(variants, audioGroups, requestedQuality, audio)
            val mediaText = if (picked == null || picked.url == masterUrl) masterText
            else fetchTextCapped(picked.url, headers, mediaPlaylistCache) ?: return null
            val stats = parseMediaStats(mediaText)
            // Exact path: every segment carries a byte-range length.
            if (stats.segmentCount > 0 && stats.byteRanges.size == stats.segmentCount) {
                val sum = stats.byteRanges.sum()
                if (sum > 0) {
                    deriveSiblingSizes(masterUrl, variants, audioGroups, audio, stats.durationSum, requestedQuality, picked?.url)
                    return HlsSizeEstimate(sum, isEstimate = false)
                }
            }
            // Estimate path: picked variant bitrate x total duration.
            // 2.0-dev2.9: the bitrate must clear the plausibility
            // floor — an implausible value (revo's 5184 bps for
            // 1080p) falls through to the byterange fallback below
            // instead of freezing a nonsense total.
            val bandwidth = picked?.bandwidth
            if (bandwidth != null && bandwidth >= MIN_PLAUSIBLE_VIDEO_BANDWIDTH_BPS && stats.durationSum > 0) {
                val bytes = (bandwidth * stats.durationSum / 8.0).toLong()
                if (bytes > 0) {
                    deriveSiblingSizes(masterUrl, variants, audioGroups, audio, stats.durationSum, requestedQuality, picked.url)
                    return HlsSizeEstimate(bytes, isEstimate = true)
                }
            }
            deriveSiblingSizes(masterUrl, variants, audioGroups, audio, stats.durationSum, requestedQuality, picked?.url)
            // Last resort: the mean of the byte-range lengths the
            // playlist DOES carry x segment count — still an
            // estimate — or null when nothing honest can be
            // computed. Never a bandwidth number below the floor.
            if (stats.segmentCount > 0 && stats.byteRanges.isNotEmpty()) {
                val mean = stats.byteRanges.sum() / stats.byteRanges.size.toDouble()
                val bytes = (mean * stats.segmentCount).toLong()
                if (bytes > 0) return HlsSizeEstimate(bytes, isEstimate = true)
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
