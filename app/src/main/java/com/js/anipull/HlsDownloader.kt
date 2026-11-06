package com.js.anipull

import android.media.MediaExtractor
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

object HlsDownloader {

    data class Variant(val uri: String, val height: Int, val bandwidth: Long)
    data class Segment(val index: Int, val uri: String, val keyUri: String?, val iv: ByteArray?)
    data class MediaPlaylist(
        val segments: List<Segment>,
        val initSegmentUri: String?,
    )

    // Master playlist: follow the variant at/below the requested
    // quality, else the lowest available. No variants = the master
    // IS the media playlist (returned unchanged).
    internal fun parseMasterPlaylist(text: String, baseUrl: String, requestedQuality: String): String {
        val wanted = Regex("""(\d+)""").find(requestedQuality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(":")
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val uriLine = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (uriLine != null) variants += Variant(resolveUrl(baseUrl, uriLine), height, bandwidth)
            }
            i++
        }
        if (variants.isEmpty()) return baseUrl
        val withHeight = variants.filter { it.height > 0 }
        val pool = if (withHeight.isNotEmpty()) withHeight else variants
        val picked = pool.filter { it.height in 1..wanted || it.height == 0 }
            .maxByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: pool.minByOrNull { it.height * 1_000_000L + it.bandwidth }
            ?: variants.last()
        return picked.uri
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
                    segments += Segment(segments.size, resolveUrl(baseUrl, line), keyUri, if (keyUri != null) iv else null)
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

    // Downloads + remuxes one episode. tempDir holds seg files + a
    // segment index so a retry/resume only fetches missing segments.
    // Throws IOException on failure, CancellationException on cancel.
    // onProgress is throttled to ~every 500ms.
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
    ) = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        val indexFile = File(tempDir, "segments.done")
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

        val masterText = EngineHttp.requestWithRetry(masterUrl, timeoutMs = 15_000).let {
            if (it.code != 200) throw java.io.IOException("Playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
            it.text
        }
        val mediaUrl = parseMasterPlaylist(masterText, masterUrl, quality)
        val mediaText = if (mediaUrl == masterUrl) masterText else EngineHttp.requestWithRetry(mediaUrl, timeoutMs = 15_000).let {
            if (it.code != 200) throw java.io.IOException("Variant playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
            it.text
        }
        val playlist = parseMediaPlaylist(mediaText, mediaUrl)
        if (playlist.segments.isEmpty()) throw java.io.IOException("Playlist has no segments — the stream may have expired, retry the download.")

        // Init segment (#EXT-X-MAP) first, if present.
        val initFile = File(tempDir, "init.ts")
        if (playlist.initSegmentUri != null && !initFile.exists()) {
            val bytes = EngineHttp.requestWithRetry(playlist.initSegmentUri, timeoutMs = 15_000).bytes
            initFile.writeBytes(bytes)
        }

        // Keys: fetched once per unique URI, cached.
        val keyCache = mutableMapOf<String, ByteArray>()
        suspend fun keyFor(uri: String): ByteArray = synchronized(keyCache) { keyCache[uri] }
            ?: EngineHttp.requestWithRetry(uri, timeoutMs = 10_000).let {
                if (it.code != 200 || it.bytes.size != 16) throw java.io.IOException("Could not fetch the stream key (HTTP ${it.code}).")
                it.bytes
            }.also { synchronized(keyCache) { keyCache[uri] = it } }

        val total = playlist.segments.size
        var downloadedBytes = done.sumOf { idx ->
            File(tempDir, "seg_%05d.ts".format(idx)).length()
        }
        var lastReportMs = 0L
        var reportBytes = downloadedBytes
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

        coroutineScope {
            val semaphore = Semaphore(segmentWorkers.coerceIn(1, 16))
            playlist.segments.map { seg ->
                async {
                    semaphore.withPermit {
                        checkPausedOrCancelled()
                        val segFile = File(tempDir, "seg_%05d.ts".format(seg.index))
                        if (seg.index in done && segFile.exists() && segFile.length() > 0) return@async
                        try {
                            val raw = EngineHttp.requestWithRetry(seg.uri, timeoutMs = 15_000).let {
                                if (it.code != 200) throw java.io.IOException("Segment ${seg.index} HTTP ${it.code}")
                                it.bytes
                            }
                            val plain = if (seg.keyUri != null && seg.iv != null) {
                                decryptSegment(raw, keyFor(seg.keyUri), seg.iv)
                            } else if (seg.keyUri != null) {
                                throw java.io.IOException("Encrypted segment without an IV — unsupported stream.")
                            } else {
                                raw
                            }
                            // Atomic write: temp file then rename, so a
                            // crash never leaves a half segment marked done.
                            val tmp = File(tempDir, "seg_%05d.tmp".format(seg.index))
                            tmp.writeBytes(plain)
                            if (!tmp.renameTo(segFile)) segFile.writeBytes(plain)
                            throttler.throttle(plain.size)
                            synchronized(lock) {
                                downloadedBytes += plain.size
                                val now = System.currentTimeMillis()
                                val elapsed = (now - reportTimeMs).coerceAtLeast(1)
                                val speed = (downloadedBytes - reportBytes) * 1000.0 / elapsed / (1024.0 * 1024.0)
                                val remaining = total - done.size - 1
                                val avg = if (done.isNotEmpty()) downloadedBytes / done.size.coerceAtLeast(1) else plain.size.toLong()
                                val estimate = downloadedBytes + remaining.coerceAtLeast(0) * avg
                                if (now - lastReportMs >= 500) {
                                    lastReportMs = now
                                    reportBytes = downloadedBytes
                                    reportTimeMs = now
                                    onProgress(downloadedBytes, estimate, speed.coerceAtLeast(0.0))
                                }
                            }
                            markDone(seg.index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            synchronized(failed) { failed += "segment ${seg.index}: ${friendlyDownloadError(e)}" }
                        }
                    }
                }
            }.awaitAll()
        }
        if (failed.isNotEmpty()) {
            throw java.io.IOException("${failed.size} of $total segments failed — download aborted, retry to resume. First: ${failed.first()}")
        }
        onProgress(downloadedBytes, downloadedBytes, 0.0)

        remux(tempDir, playlist.segments.size, initFile, outputFile)
        // Segments are now inside the mp4; clean up.
        try {
            tempDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    // Concatenate init + decrypted segments into one TS, then remux
    // to a real mp4 with MediaExtractor + MediaMuxer (NOT raw concat).
    private fun remux(tempDir: File, segmentCount: Int, initFile: File, outputFile: File) {
        val combined = File(tempDir, "combined.ts")
        combined.outputStream().use { out ->
            if (initFile.exists()) initFile.inputStream().use { it.copyTo(out) }
            for (i in 0 until segmentCount) {
                val seg = File(tempDir, "seg_%05d.ts".format(i))
                if (!seg.exists()) throw java.io.IOException("Missing segment $i at remux — retry the download.")
                seg.inputStream().use { it.copyTo(out) }
            }
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(combined.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount == 0) throw java.io.IOException("Remux failed: no tracks found in the stream.")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = IntArray(trackCount)
            for (i in 0 until trackCount) {
                trackMap[i] = muxer.addTrack(extractor.getTrackFormat(i))
                extractor.selectTrack(i)
            }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val trackIndex = extractor.sampleTrackIndex
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(trackMap[trackIndex], buffer, info)
                extractor.advance()
            }
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
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            try {
                combined.delete()
            } catch (_: Exception) {
            }
        }
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw java.io.IOException("Remux produced an empty file — the stream may be unsupported.")
        }
    }
}
