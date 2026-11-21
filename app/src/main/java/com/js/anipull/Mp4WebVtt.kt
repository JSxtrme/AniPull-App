package com.js.anipull

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min

/**
 * 2.0-dev2.20 — inject WebVTT subtitle tracks into a finished MP4.
 *
 * Why this file exists: Android's MediaMuxer cannot add subtitle
 * tracks at all — its docs list only video, audio and metadata
 * tracks, and dev2.19's text/vtt addTrack was rejected by
 * MPEG4Writer ("Track other than video or audio is not
 * supported"), surfacing as IllegalStateException "Failed to add
 * the track to the muxer" and failing the whole download (the
 * Death Note E1 1080p Sub job on dev2.19). So subtitles are
 * written in afterwards, as standard tx3g (3GPP timed text, the
 * MP4 subtitle format both ffmpeg and VLC read) tracks that VLC
 * lists as subtitle tracks — no sidecar files, no re-encode, and
 * the A/V streams are copied byte-for-byte (only the moov box is
 * rebuilt, and a second mdat with the cue samples is appended).
 * (First attempt used wvtt/WebVTT tracks; ffmpeg 8.1.2 files
 * those as data, not subtitles, and its muxer refuses to write
 * them — tx3g is the format every player actually reads.)
 *
 * Pure Kotlin + java.io/java.nio only — no android.* imports — so
 * this file can be compiled and tested on a plain JVM.
 *
 * Safety contract: injection writes a temp file and only replaces
 * the original after the result re-parses cleanly. On ANY failure
 * it returns false and leaves the input file byte-identical, so a
 * subtitle problem can never break (or silently corrupt) a
 * download — the caller keeps the A/V-only file.
 *
 * Layout of the output: [original bytes up to moov) + [new mdat
 * with the cue samples] + [new moov] + [original bytes after
 * moov, if any]. When moov is not the last box (faststart files),
 * the original tracks' chunk offsets are shifted by the size
 * delta of the insert, so their samples still land correctly.
 * Files over ~4 GB are refused (32-bit chunk offsets).
 */
object Mp4WebVtt {

    data class Cue(val startUs: Long, val endUs: Long, val text: String)
    data class Track(val language: String, val cues: List<Cue>) // language: 2-letter code, en/es/ru/de/hi

    private val ISO639_2 = mapOf(
        "en" to "eng", "es" to "spa", "ru" to "rus", "de" to "deu", "hi" to "hin",
    )

    /**
     * Inject one tx3g track per [tracks] entry into [mp4File], in
     * place. Returns true on success (or when there is nothing to
     * inject). Returns false on any failure, leaving [mp4File]
     * untouched. [onLog] receives the failure reason.
     */
    fun inject(mp4File: File, tracks: List<Track>, onLog: (String) -> Unit = {}): Boolean {
        val usable = tracks.mapNotNull { t ->
            val lang3 = ISO639_2[t.language] ?: return@mapNotNull null
            val cues = t.cues
                .filter { it.text.isNotBlank() && it.endUs > it.startUs }
                .sortedBy { it.startUs }
            if (cues.isEmpty()) null else Track(lang3, cues)
        }
        if (usable.isEmpty()) return true
        return try {
            injectOrThrow(mp4File, usable)
        } catch (e: Exception) {
            onLog("subtitle embed failed (${e.message}) — keeping A/V-only file")
            false
        }
    }

    // ---------- top-level flow ----------

    private fun injectOrThrow(mp4File: File, tracks: List<Track>): Boolean {
        val fileSize = mp4File.length()
        if (fileSize < 8 || fileSize >= 0xFFF00000L) return false
        val top = RandomAccessFile(mp4File, "r").use { scanTopLevel(it) }
        val moovInfo = top.firstOrNull { it.type == "moov" } ?: return false
        if (moovInfo.size > 256 * 1024 * 1024) return false
        val moovBytes = RandomAccessFile(mp4File, "r").use { raf ->
            raf.seek(moovInfo.offset)
            ByteArray(moovInfo.size.toInt()).also { raf.readFully(it) }
        }
        val children = scanBoxes(moovBytes, 8, moovBytes.size)
        val mvhd = children.firstOrNull { it.type == "mvhd" } ?: return false
        val mvhdPayload = (mvhd.offset + mvhd.headerSize).toInt()
        val mvhdVersion = moovBytes[mvhdPayload].toInt()
        val movieTimescale = when (mvhdVersion) {
            0 -> moovBytes.u32At(mvhdPayload + 12)
            1 -> moovBytes.u32At(mvhdPayload + 20)
            else -> return false
        }
        if (movieTimescale <= 0) return false
        val nextTrackIdOffset = when (mvhdVersion) {
            0 -> mvhdPayload + 96
            1 -> mvhdPayload + 108
            else -> return false
        }
        var maxTrackId = 0
        for (child in children) {
            if (child.type != "trak") continue
            val tkhd = scanBoxes(
                moovBytes,
                (child.offset + child.headerSize).toInt(),
                (child.offset + child.size).toInt(),
            ).firstOrNull { it.type == "tkhd" } ?: continue
            val p = (tkhd.offset + tkhd.headerSize).toInt()
            val ver = moovBytes[p].toInt()
            val idOff = if (ver == 1) p + 20 else p + 12
            maxTrackId = max(maxTrackId, moovBytes.u32At(idOff).toInt())
        }

        // Build the cue samples first: their sizes fix the new
        // mdat layout, which fixes every chunk offset below.
        val built = tracks.mapNotNull { buildTrackSamples(it) }
        if (built.isEmpty()) return true
        val newMdatDataOffset = moovInfo.offset + 8
        var chunkCursor = newMdatDataOffset
        val chunkOffsets = ArrayList<Long>(built.size)
        for (b in built) {
            chunkOffsets += chunkCursor
            chunkCursor += b.sampleBytes.size
        }
        val newMdatPayloadSize = chunkCursor - newMdatDataOffset
        val newMdatTotalSize = 8 + newMdatPayloadSize

        // Build the new traks (needs the chunk offsets above).
        var nextId = maxTrackId + 1
        val newTraks = ArrayList<ByteArray>(built.size)
        val trackChecks = HashMap<Int, Pair<Long, Long>>() // trackId -> (chunkOffset, sampleBytes)
        for ((i, b) in built.withIndex()) {
            val id = nextId + i
            newTraks += buildTrak(id, b, chunkOffsets[i], movieTimescale)
            trackChecks[id] = chunkOffsets[i] to b.sampleBytes.size.toLong()
        }
        val newNextTrackId = nextId + built.size

        // Rebuild moov: patch mvhd's next_track_ID, copy every
        // other child verbatim, append the new traks.
        val trailing = moovInfo.offset + moovInfo.size < fileSize
        // The new moov's size is fixed by its children, so the
        // shift applied to trailing chunk offsets is known now.
        var newMoovSize = 8L
        for (child in children) newMoovSize += child.size
        for (t in newTraks) newMoovSize += t.size
        val delta = if (trailing) newMdatTotalSize + newMoovSize - moovInfo.size else 0L
        val newChildren = ArrayList<ByteArray>(children.size + newTraks.size)
        for (child in children) {
            val raw = moovBytes.copyOfRange(child.offset.toInt(), (child.offset + child.size).toInt())
            if (child.type == "mvhd") {
                putU32(raw, nextTrackIdOffset - child.offset.toInt(), newNextTrackId.toLong())
            } else if (child.type == "trak" && delta != 0L) {
                if (!patchChunkOffsets(raw, delta)) return false
            }
            newChildren += raw
        }
        newChildren += newTraks
        val newMoov = box("moov", concat(newChildren))
        if (newMoov.size.toLong() != newMoovSize) return false

        // Write the temp file: prefix + new mdat + new moov +
        // trailing bytes (if any). Streamed — the file itself is
        // never held in memory.
        val tmp = File(mp4File.parentFile, mp4File.name + ".tx3g-tmp")
        try {
            RandomAccessFile(mp4File, "r").use { raf ->
                tmp.outputStream().buffered(1024 * 1024).use { out ->
                    copyRange(raf, out, 0, moovInfo.offset)
                    out.write(u32BoxHeader("mdat", newMdatPayloadSize + 8))
                    for (b in built) out.write(b.sampleBytes)
                    out.write(newMoov)
                    if (trailing) copyRange(raf, out, moovInfo.offset + moovInfo.size, fileSize)
                }
            }
            if (!validateOutput(tmp, trackChecks, newMdatDataOffset, newMdatPayloadSize)) {
                tmp.delete()
                return false
            }
            try {
                Files.move(tmp.toPath(), mp4File.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), mp4File.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } finally {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- validation: re-parse the written file ----------

    private fun validateOutput(
        file: File,
        trackChecks: Map<Int, Pair<Long, Long>>,
        newMdatDataOffset: Long,
        newMdatPayloadSize: Long,
    ): Boolean {
        if (file.length() < 8) return false
        val top = RandomAccessFile(file, "r").use { scanTopLevel(it) }
        var covered = 0L
        for (b in top) covered += b.size
        if (covered != file.length()) return false
        val moovInfo = top.firstOrNull { it.type == "moov" } ?: return false
        val moovBytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(moovInfo.offset)
            ByteArray(moovInfo.size.toInt()).also { raf.readFully(it) }
        }
        val children = scanBoxes(moovBytes, 8, moovBytes.size)
        // Every new track must be present, with its chunk offset
        // inside the new mdat and sample sizes summing to its
        // chunk bytes.
        for ((trackId, check) in trackChecks) {
            val (chunkOffset, sampleBytes) = check
            if (chunkOffset < newMdatDataOffset || chunkOffset + sampleBytes > newMdatDataOffset + newMdatPayloadSize) return false
            var found = false
            for (child in children) {
                if (child.type != "trak") continue
                val tkhd = scanBoxes(
                    moovBytes,
                    (child.offset + child.headerSize).toInt(),
                    (child.offset + child.size).toInt(),
                ).firstOrNull { it.type == "tkhd" } ?: continue
                val p = (tkhd.offset + tkhd.headerSize).toInt()
                val ver = moovBytes[p].toInt()
                val idOff = if (ver == 1) p + 20 else p + 12
                if (moovBytes.u32At(idOff).toInt() != trackId) continue
                val stbl = findDescendant(moovBytes, child, "mdia", "minf", "stbl") ?: return false
                val stco = scanBoxes(moovBytes, (stbl.offset + stbl.headerSize).toInt(), (stbl.offset + stbl.size).toInt())
                    .firstOrNull { it.type == "stco" } ?: return false
                val sp = (stco.offset + stco.headerSize).toInt()
                if (moovBytes.u32At(sp + 4) != 1L) return false
                if (moovBytes.u32At(sp + 8) != chunkOffset) return false
                val stsz = scanBoxes(moovBytes, (stbl.offset + stbl.headerSize).toInt(), (stbl.offset + stbl.size).toInt())
                    .firstOrNull { it.type == "stsz" } ?: return false
                val zp = (stsz.offset + stsz.headerSize).toInt()
                if (moovBytes.u32At(zp + 4) != 0L) return false
                val count = moovBytes.u32At(zp + 8).toInt()
                var sum = 0L
                for (i in 0 until count) sum += moovBytes.u32At(zp + 12 + i * 4)
                if (sum != sampleBytes) return false
                found = true
            }
            if (!found) return false
        }
        return true
    }

    // ---------- trak construction (tx3g) ----------

    private data class BuiltTrack(
        val language3: String,
        val sampleBytes: ByteArray,
        val sampleSizes: List<Int>,
        val firstStartMs: Long,
        val lastEndMs: Long,
        val cues: List<Cue>,
        // One duration per sample, cue and gap alike (ms) — the
        // stts clock. A cue's duration is its own end minus its
        // start (truncated at the next cue's start when they
        // overlap); a gap sample's is the silence to the next cue.
        val sampleDurationsMs: List<Long>,
    )

    private fun buildTrackSamples(track: Track): BuiltTrack? {
        // A cue over 64 KB of text cannot fit a tx3g sample — drop
        // it up front so the sample/duration lists stay in lockstep.
        val cues = track.cues.filter { it.text.toByteArray(Charsets.UTF_8).size <= 0xFFFF }
        if (cues.isEmpty()) return null
        val firstStartMs = cues.first().startUs / 1000
        val lastEndMs = cues.last().endUs / 1000
        if (lastEndMs <= firstStartMs) return null
        val out = ByteArrayOutputStream()
        val sizes = ArrayList<Int>(cues.size)
        val durations = ArrayList<Long>(cues.size)
        // 2.0-dev2.25 — the media timeline starts at 0 (no elst
        // empty edit — see buildTrak), so a leading gap sample
        // covers 0 .. first cue start. It holds a single SPACE,
        // not an empty sample: a seek landing on it must find
        // real content, or VLC drops the track (JS, Death Note
        // E1 — a seek into a silence killed the subs, a seek
        // onto a cue at 1:50 worked).
        if (firstStartMs > 0) {
            val lead = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
            lead.putShort(1)
            lead.put(0x20)
            sizes += 3
            out.write(lead.array())
            durations += max(1L, firstStartMs)
        }
        for (i in cues.indices) {
            val cue = cues[i]
            // tx3g text sample: u16 byte length + UTF-8 text.
            // (Modifier boxes for per-cue styling are skipped —
            // the cues are plain text; the entry's default style
            // covers them.)
            val textBytes = cue.text.toByteArray(Charsets.UTF_8)
            val sample = ByteBuffer.allocate(2 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
            sample.putShort(textBytes.size.toShort())
            sample.put(textBytes)
            sizes += sample.array().size
            out.write(sample.array())
            // 2.0-dev2.23 — each cue lasts only until its own end
            // (the old code stretched it to the next cue's start,
            // so a line hung on through the silence until the next
            // line replaced it — JS, Death Note E1, dev2.22). An
            // overlapping cue is truncated at the next cue's start
            // so later start times stay exact. 2.0-dev2.25 — the
            // gap to the next cue is a single-SPACE sample (u16
            // length 1 + 0x20), not an empty (u16 length 0)
            // sample: it shows nothing visible, and its duration
            // keeps every later cue's start time exact — but a
            // seek landing in the silence now finds real content
            // instead of a zero-length sample, so VLC keeps the
            // track alive (seek into a gap killed the subs on
            // dev2.23; seek onto a cue worked).
            val rawDurMs = (cue.endUs - cue.startUs) / 1000
            val durMs = if (i < cues.lastIndex) {
                val untilNextMs = (cues[i + 1].startUs - cue.startUs) / 1000
                max(1L, min(rawDurMs, untilNextMs))
            } else {
                max(1L, rawDurMs)
            }
            durations += durMs
            if (i < cues.lastIndex) {
                val gapMs = (cues[i + 1].startUs - cue.endUs) / 1000
                if (gapMs > 0) {
                    val gap = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
                    gap.putShort(1)
                    gap.put(0x20)
                    sizes += 3
                    out.write(gap.array())
                    durations += max(1L, gapMs)
                }
            }
        }
        return BuiltTrack(track.language, out.toByteArray(), sizes, firstStartMs, lastEndMs, cues, durations)
    }

    private fun buildTrak(trackId: Int, built: BuiltTrack, chunkOffset: Long, movieTimescale: Long): ByteArray {
        // 2.0-dev2.25 — the media timeline starts at 0 (a leading
        // space-gap sample covers 0 .. first cue), so the media
        // span is the full lastEndMs, not lastEndMs - firstStartMs.
        val mediaDurationMs = built.lastEndMs
        val movieDuration = built.lastEndMs * movieTimescale / 1000
        val mediaDuration = mediaDurationMs

        // tkhd (version 0, flags 0x000007 = enabled | in_movie |
        // in_preview; no width/height — a subtitle track has none).
        val tkhd = run {
            val buf = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0x00000007)
            buf.putInt(0) // creation
            buf.putInt(0) // modification
            buf.putInt(trackId)
            buf.putInt(0) // reserved
            buf.putInt(movieDuration.toInt())
            buf.putLong(0) // reserved
            buf.putShort(0) // layer
            buf.putShort(0) // alternate_group
            buf.putShort(0) // volume
            buf.putShort(0) // reserved
            val matrix = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
            for (m in matrix) buf.putInt(m)
            buf.putInt(0) // width
            buf.putInt(0) // height
            box("tkhd", buf.array())
        }

        // mdhd (version 0, timescale 1000 = milliseconds).
        val lang3 = built.language3
        val langPacked = ((lang3[0].code - 0x60) shl 10) or ((lang3[1].code - 0x60) shl 5) or (lang3[2].code - 0x60)
        val mdhd = run {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0) // version/flags
            buf.putInt(0) // creation
            buf.putInt(0) // modification
            buf.putInt(1000) // timescale
            buf.putInt(mediaDuration.toInt())
            buf.putShort(langPacked.toShort())
            buf.putShort(0) // pre_defined
            box("mdhd", buf.array())
        }

        // hdlr (handler "sbtl" — ffmpeg and VLC both map this to
        // a subtitle track; "text" makes ffmpeg file it as data).
        val hdlr = run {
            val name = "SubtitleHandler\u0000".toByteArray(Charsets.US_ASCII)
            val buf = ByteBuffer.allocate(4 + 4 + 4 + 12 + name.size).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0) // version/flags
            buf.putInt(0) // pre_defined
            buf.put("sbtl".toByteArray(Charsets.US_ASCII))
            buf.putInt(0)
            buf.putInt(0)
            buf.putInt(0)
            buf.put(name)
            box("hdlr", buf.array())
        }

        // minf: nmhd + dinf + stbl.
        val nmhd = box("nmhd", byteArrayOf(0, 0, 0, 0))
        val dinf = box(
            "dinf",
            box("dref", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + box("url ", byteArrayOf(0, 0, 0, 1))),
        )

        // stsd: one tx3g entry, byte-identical in layout to what
        // ffmpeg's movenc writes (centred, bottom, opaque-black
        // background, white Arial 16) — the format ffmpeg and VLC
        // both read as subtitles.
        val tx3gEntry = run {
            val buf = ByteBuffer.allocate(6 + 2 + 4 + 1 + 1 + 4 + 8 + 12 + 18).order(ByteOrder.BIG_ENDIAN)
            buf.put(ByteArray(6)) // reserved
            buf.putShort(1) // data_reference_index
            buf.putInt(0) // displayFlags
            buf.put(1) // horizontal justification: centred
            buf.put(-1) // vertical justification: bottom
            buf.put(byteArrayOf(0, 0, 0, 0xFF.toByte())) // background RGBA
            buf.putShort(0) // default text box: top
            buf.putShort(0) // left
            buf.putShort(0) // bottom
            buf.putShort(0) // right
            buf.putShort(0) // default style: startChar
            buf.putShort(0) // endChar
            buf.putShort(1) // fontID
            buf.put(0) // faceStyleFlags
            buf.put(16) // fontSize
            buf.put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) // textColorRGBA: white
            val ftab = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN)
            ftab.putInt(18)
            ftab.put("ftab".toByteArray(Charsets.US_ASCII))
            ftab.putShort(1) // font count
            ftab.putShort(1) // fontID
            ftab.put(5) // name length
            ftab.put("Arial".toByteArray(Charsets.US_ASCII))
            buf.put(ftab.array())
            buf.array()
        }
        val stsd = box("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + box("tx3g", tx3gEntry))

        // stts: one entry per sample, cue and gap alike — a cue
        // sample lasts until its own end, an empty gap sample
        // covers the silence to the next cue, so no line lingers
        // past its time and every later cue keeps its true start.
        val stts = run {
            val entries = ArrayList<Pair<Long, Long>>()
            for (d in built.sampleDurationsMs) {
                val dd = max(1L, d)
                val last = entries.lastOrNull()
                if (last != null && last.second == dd) entries[entries.lastIndex] = last.first + 1 to dd
                else entries += 1L to dd
            }
            val buf = ByteBuffer.allocate(4 + 4 + entries.size * 8).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0) // version/flags
            buf.putInt(entries.size)
            for ((count, delta) in entries) {
                buf.putInt(count.toInt())
                buf.putInt(delta.toInt())
            }
            box("stts", buf.array())
        }

        // stsc: one chunk, all samples, description 1.
        val stsc = run {
            val buf = ByteBuffer.allocate(4 + 4 + 12).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0)
            buf.putInt(1)
            buf.putInt(1) // first_chunk
            buf.putInt(built.sampleSizes.size) // samples_per_chunk
            buf.putInt(1) // sample_description_index
            box("stsc", buf.array())
        }

        // stsz: per-sample sizes.
        val stsz = run {
            val buf = ByteBuffer.allocate(4 + 4 + 4 + built.sampleSizes.size * 4).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0)
            buf.putInt(0) // sample_size = 0 (sizes follow)
            buf.putInt(built.sampleSizes.size)
            for (s in built.sampleSizes) buf.putInt(s)
            box("stsz", buf.array())
        }

        // stco: the chunk offset into the new mdat.
        val stco = run {
            val buf = ByteBuffer.allocate(4 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0)
            buf.putInt(1)
            buf.putInt(chunkOffset.toInt())
            box("stco", buf.array())
        }

        val stbl = box("stbl", stsd + stts + stsc + stsz + stco)
        val minf = box("minf", nmhd + dinf + stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)

        // 2.0-dev2.25 — no elst empty edit. The media timeline
        // is the movie timeline (a leading space-gap sample
        // covers 0 .. first cue), so a seek to any time lands
        // on a real sample instead of an empty-edit region —
        // the old empty edit meant a seek before the first cue
        // found no subtitle sample at all, and VLC dropped the
        // track. Single entry, media_time 0.
        val elst = run {
            val mediaMovie = mediaDurationMs * movieTimescale / 1000
            val buf = ByteBuffer.allocate(4 + 4 + 12).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0)
            buf.putInt(1)
            buf.putInt(mediaMovie.toInt())
            buf.putInt(0)
            buf.putShort(1)
            buf.putShort(0)
            box("elst", buf.array())
        }
        val edts = box("edts", elst)

        return box("trak", tkhd + edts + mdia)
    }

    // ---------- chunk-offset patching (faststart files) ----------

    /**
     * Add [delta] to every stco/co64 entry under this trak box
     * ([raw] starts at the trak box header). Returns false if the
     * structure is not as expected.
     */
    private fun patchChunkOffsets(raw: ByteArray, delta: Long): Boolean {
        val trakEnd = raw.size
        val mdia = findChild(raw, 8, trakEnd, "mdia") ?: return false
        val minf = findChild(raw, mdia.offset.toInt() + mdia.headerSize, (mdia.offset + mdia.size).toInt(), "minf") ?: return false
        val stbl = findChild(raw, minf.offset.toInt() + minf.headerSize, (minf.offset + minf.size).toInt(), "stbl") ?: return false
        val stblStart = (stbl.offset + stbl.headerSize).toInt()
        val stblEnd = (stbl.offset + stbl.size).toInt()
        for (entry in scanBoxes(raw, stblStart, stblEnd)) {
            when (entry.type) {
                "stco" -> {
                    val p = (entry.offset + entry.headerSize).toInt()
                    val count = raw.u32At(p + 4).toInt()
                    for (i in 0 until count) {
                        val v = raw.u32At(p + 8 + i * 4) + delta
                        if (v >= 0x100000000L) return false
                        putU32(raw, p + 8 + i * 4, v)
                    }
                }
                "co64" -> {
                    val p = (entry.offset + entry.headerSize).toInt()
                    val count = raw.u32At(p + 4).toInt()
                    for (i in 0 until count) {
                        putU64(raw, p + 8 + i * 8, raw.u64At(p + 8 + i * 8) + delta)
                    }
                }
            }
        }
        return true
    }

    // ---------- box plumbing ----------

    private data class BoxInfo(val offset: Long, val headerSize: Int, val size: Long, val type: String)

    private fun scanTopLevel(raf: RandomAccessFile): List<BoxInfo> {
        val out = ArrayList<BoxInfo>()
        val fileSize = raf.length()
        var p = 0L
        val head = ByteArray(16)
        while (p + 8 <= fileSize) {
            raf.seek(p)
            if (raf.read(head, 0, 8) < 8) break
            val size32 = head.u32At(0)
            val type = String(head, 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            var size = size32
            if (size == 1L) {
                if (raf.read(head, 8, 8) < 8) break
                size = head.u64At(8)
                headerSize = 16
            } else if (size == 0L) {
                size = fileSize - p
            }
            if (size < headerSize || p + size > fileSize) break
            out += BoxInfo(p, headerSize, size, type)
            p += size
        }
        return out
    }

    private fun scanBoxes(data: ByteArray, start: Int, end: Int): List<BoxInfo> {
        val out = ArrayList<BoxInfo>()
        var p = start
        while (p + 8 <= end) {
            val size32 = data.u32At(p)
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            var size = size32
            if (size == 1L) {
                if (p + 16 > end) break
                size = data.u64At(p + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < headerSize || p + size > end || size > Int.MAX_VALUE) break
            out += BoxInfo(p.toLong(), headerSize, size, type)
            p += size.toInt()
        }
        return out
    }

    private fun findChild(data: ByteArray, start: Int, end: Int, type: String): BoxInfo? =
        scanBoxes(data, start, end).firstOrNull { it.type == type }

    /** Walk a path of nested box types starting at [box]'s payload. */
    private fun findDescendant(data: ByteArray, box: BoxInfo, vararg path: String): BoxInfo? {
        var start = (box.offset + box.headerSize).toInt()
        var end = (box.offset + box.size).toInt()
        var found: BoxInfo? = null
        for (type in path) {
            found = findChild(data, start, end, type) ?: return null
            start = (found.offset + found.headerSize).toInt()
            end = (found.offset + found.size).toInt()
        }
        return found
    }

    private fun copyRange(raf: RandomAccessFile, out: java.io.OutputStream, from: Long, to: Long) {
        if (to <= from) return
        raf.seek(from)
        val buf = ByteArray(1024 * 1024)
        var remaining = to - from
        while (remaining > 0) {
            val toRead = if (remaining < buf.size) remaining.toInt() else buf.size
            val n = raf.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(8 + payload.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(payload)
        return out.array()
    }

    /** 8-byte header for a box whose payload is [payloadSize] bytes (32-bit size). */
    private fun u32BoxHeader(type: String, totalSize: Long): ByteArray {
        val out = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        out.putInt(totalSize.toInt())
        out.put(type.toByteArray(Charsets.US_ASCII))
        return out.array()
    }

    private fun ByteArray.u16At(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun ByteArray.u32At(off: Int): Long =
        ((this[off].toLong() and 0xFF) shl 24) or
            ((this[off + 1].toLong() and 0xFF) shl 16) or
            ((this[off + 2].toLong() and 0xFF) shl 8) or
            (this[off + 3].toLong() and 0xFF)

    private fun ByteArray.u64At(off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
        return v
    }

    private fun putU32(arr: ByteArray, off: Int, v: Long) {
        arr[off] = (v ushr 24).toByte()
        arr[off + 1] = (v ushr 16).toByte()
        arr[off + 2] = (v ushr 8).toByte()
        arr[off + 3] = v.toByte()
    }

    private fun putU64(arr: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) arr[off + i] = (v ushr (56 - i * 8)).toByte()
    }
}
