package com.js.anipull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

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
// download time): BANDWIDTH x total duration as an estimate, or
// summed #EXT-X-BYTERANGE lengths when the playlist carries them
// (exact). No segments are ever downloaded here.

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
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { probeBlocking(url, headers) }
        }
    }

    // Estimate an HLS stream's total size in bytes: summed segment
    // byte-ranges when present (exact), else the picked variant's
    // BANDWIDTH x summed #EXTINF duration (estimate). Fetches only
    // the master + picked media playlist (small text, capped), never
    // segments. `headers` is the link's own Referer/Origin. Returns
    // null when no honest number can be computed — never throws,
    // never invents a size.
    suspend fun estimateHlsSizeBytes(
        masterUrl: String,
        headers: Map<String, String> = emptyMap(),
        requestedQuality: String = "1080p",
        audio: String = "Sub",
        timeoutMs: Long = 5_000,
    ): HlsSizeEstimate? {
        if (masterUrl.isBlank()) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                estimateBlocking(masterUrl, headers, requestedQuality, audio)
            }
        }
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

    private fun fetchTextCapped(url: String, headers: Map<String, String>): String? {
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
            return bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // BANDWIDTH of the variant whose URI is `pickedUrl`. The master
    // parser drops bandwidth, so it is re-read from the #EXT-X-
    // STREAM-INF line directly above that variant's URI.
    private fun bandwidthForVariant(masterText: String, masterUrl: String, pickedUrl: String): Long? {
        val lines = masterText.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val uriLine = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (uriLine != null && resolveUrl(masterUrl, uriLine) == pickedUrl) {
                    return Regex("""BANDWIDTH=(\d+)""").find(line.substringAfter(":"))
                        ?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                }
            }
            i++
        }
        return null
    }

    private fun estimateBlocking(
        masterUrl: String,
        headers: Map<String, String>,
        requestedQuality: String,
        audio: String,
    ): HlsSizeEstimate? {
        return try {
            val masterText = fetchTextCapped(masterUrl, headers) ?: return null
            val picked = HlsDownloader.parseMasterPlaylist(masterText, masterUrl, requestedQuality, audio)
            val mediaText = if (picked.url == masterUrl) masterText
            else fetchTextCapped(picked.url, headers) ?: return null
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
            // Exact path: every segment carries a byte-range length.
            if (segmentCount > 0 && byteRanges.size == segmentCount) {
                val sum = byteRanges.sum()
                if (sum > 0) return HlsSizeEstimate(sum, isEstimate = false)
            }
            // Estimate path: picked variant bitrate x total duration.
            val bandwidth = bandwidthForVariant(masterText, masterUrl, picked.url)
            if (bandwidth != null && bandwidth > 0 && durationSum > 0) {
                val bytes = (bandwidth * durationSum / 8.0).toLong()
                if (bytes > 0) return HlsSizeEstimate(bytes, isEstimate = true)
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
