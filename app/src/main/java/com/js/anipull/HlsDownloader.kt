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

    data class Variant(val uri: String, val height: Int, val bandwidth: Long, val audioGroup: String? = null)
    data class Segment(val index: Int, val uri: String, val keyUri: String?, val iv: ByteArray?, val ivFromTag: Boolean = false)
    data class MediaPlaylist(
        val segments: List<Segment>,
        val initSegmentUri: String?,
    )

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
    internal fun parseMasterPlaylist(
        text: String,
        baseUrl: String,
        requestedQuality: String,
        audio: String = "Sub",
    ): String {
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
        if (variants.isEmpty()) return baseUrl
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

    // Downloads + remuxes one episode. tempDir holds seg files + a
    // segment index so a retry/resume only fetches missing segments.
    // Throws IOException on failure, CancellationException on cancel.
    // onProgress is throttled to ~every 500ms.
    // Returns the subtitle tracks found in the master playlist's
    // #EXT-X-MEDIA SUBTITLES groups (empty when none) — the caller
    // downloads them as sidecars. `audio` steers variant picking
    // toward the requested language. `keyOverrideB64` /
    // `ivOverrideB64` (base64 of 16 raw bytes) replace segment-key
    // fetching entirely when set: the decoded key is used for ALL
    // segment keys, and the decoded IV is used only when a playlist
    // KEY tag has no explicit IV. `referer` is sent on playlist /
    // segment / key fetches when the upstream requires it.
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
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val overrideKey: ByteArray? = keyOverrideB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { bytes -> bytes.size == 16 }
        }
        val overrideIv: ByteArray? = ivOverrideB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { bytes -> bytes.size == 16 }
        }
        val fetchHeaders: Map<String, String> = referer?.takeIf { it.isNotBlank() }
            ?.let { mapOf("Referer" to it) } ?: emptyMap()

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

        // 2.0-dev1.9 TEMPORARY download diagnostics: every HLS
        // stage is logged (playlist codes, variant pick, key
        // fetches, per-segment errors, remux result) so the report
        // shows exactly where a stream dies. URLs are host+path
        // only — never query params / signatures.
        val hlsStartedMs = System.currentTimeMillis()
        DownloadLogs.log("HLS", "start: master=${DownloadLogs.sanitizeUrl(masterUrl)} quality=$quality audio=$audio")
        val masterText = EngineHttp.requestWithRetry(masterUrl, headers = fetchHeaders, timeoutMs = 15_000).let {
            DownloadLogs.log("HLS", "master playlist: HTTP ${it.code}, ${it.bytes.size} bytes")
            if (it.code != 200) throw java.io.IOException("Playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
            it.text
        }
        val subtitleTracks = parseExtXMedia(masterText, masterUrl).toMutableList()
        val mediaUrl = parseMasterPlaylist(masterText, masterUrl, quality, audio)
        DownloadLogs.log("HLS", "variant: ${DownloadLogs.sanitizeUrl(mediaUrl)}${if (mediaUrl == masterUrl) " (master is media playlist)" else ""}")
        val mediaText = if (mediaUrl == masterUrl) masterText else EngineHttp.requestWithRetry(mediaUrl, headers = fetchHeaders, timeoutMs = 15_000).let {
            DownloadLogs.log("HLS", "variant playlist: HTTP ${it.code}, ${it.bytes.size} bytes")
            if (it.code != 200) throw java.io.IOException("Variant playlist fetch failed (HTTP ${it.code}) — the link may have expired, retry the download.")
            it.text
        }
        if (mediaUrl != masterUrl) {
            for (sub in parseExtXMedia(mediaText, mediaUrl)) {
                if (subtitleTracks.none { it.url == sub.url }) subtitleTracks += sub
            }
        }
        val playlist = parseMediaPlaylist(mediaText, mediaUrl)
        if (playlist.segments.isEmpty()) throw java.io.IOException("Playlist has no segments — the stream may have expired, retry the download.")
        DownloadLogs.log("HLS", "media playlist: ${playlist.segments.size} segments, init=${playlist.initSegmentUri != null}, subs=${subtitleTracks.size}")

        // Init segment (#EXT-X-MAP) first, if present.
        val initFile = File(tempDir, "init.ts")
        if (playlist.initSegmentUri != null && !initFile.exists()) {
            val initResp = EngineHttp.requestWithRetry(playlist.initSegmentUri, headers = fetchHeaders, timeoutMs = 15_000)
            DownloadLogs.log("HLS", "init segment: HTTP ${initResp.code}, ${initResp.bytes.size} bytes")
            if (initResp.code != 200) throw java.io.IOException("Init segment fetch failed (HTTP ${initResp.code}) — retry the download.")
            initFile.writeBytes(initResp.bytes)
        }

        // Keys: fetched once per unique URI, cached. A key override
        // replaces fetching entirely (rotating per-URI keys still
        // work: the override is used for every key URI).
        val keyCache = mutableMapOf<String, ByteArray>()
        suspend fun keyFor(uri: String): ByteArray {
            overrideKey?.let { return it }
            synchronized(keyCache) { keyCache[uri] }?.let { return it }
            return EngineHttp.requestWithRetry(uri, headers = fetchHeaders, timeoutMs = 10_000).let {
                DownloadLogs.log("HLS", "key fetch: ${DownloadLogs.sanitizeUrl(uri)} -> HTTP ${it.code}, ${it.bytes.size} bytes")
                if (it.code != 200 || it.bytes.size != 16) throw java.io.IOException("Could not fetch the stream key (HTTP ${it.code}).")
                it.bytes
            }.also { synchronized(keyCache) { keyCache[uri] = it } }
        }

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
                            val raw = EngineHttp.requestWithRetry(seg.uri, headers = fetchHeaders, timeoutMs = 15_000).let {
                                if (it.code != 200) throw java.io.IOException("Segment ${seg.index} HTTP ${it.code}")
                                it.bytes
                            }
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
                            val msg = "segment ${seg.index}: ${friendlyDownloadError(e)}"
                            DownloadLogs.log("HLS", "FAILED $msg")
                            synchronized(failed) { failed += msg }
                        }
                    }
                }
            }.awaitAll()
        }
        if (failed.isNotEmpty()) {
            DownloadLogs.log("HLS", "aborted: ${failed.size} of $total segments failed in ${System.currentTimeMillis() - hlsStartedMs}ms — first: ${failed.first()}")
            throw java.io.IOException("${failed.size} of $total segments failed — download aborted, retry to resume. First: ${failed.first()}")
        }
        onProgress(downloadedBytes, downloadedBytes, 0.0)
        DownloadLogs.log("HLS", "all $total segments done in ${System.currentTimeMillis() - hlsStartedMs}ms, ${downloadedBytes / 1024} KB — remuxing")

        remux(tempDir, playlist.segments.size, initFile, outputFile)
        DownloadLogs.log("HLS", "remux done: ${outputFile.length() / 1024} KB -> ${outputFile.name}")
        // Segments are now inside the mp4; clean up.
        try {
            tempDir.deleteRecursively()
        } catch (_: Exception) {
        }
        subtitleTracks
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
            DownloadLogs.log("HLS", "remux: $trackCount track(s) from ${combined.length() / 1024} KB combined TS")
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
