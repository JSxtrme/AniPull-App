package com.js.anipull

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 2.0-dev1.0 ENGINE — queue, jobs, persistence, DDL + HLS execution.
//
// Folder setting (read from Settings.kt first, as specified):
// "folder" is a DISPLAY string (default "Downloads/Anipull") and
// "folder_uri" is the real handle — a SAF tree URI picked with the
// Browse picker. Under scoped storage the display string is NOT a
// writable path, so: if folder_uri is set, files go into that SAF
// tree (via DocumentsContract, no new dependency); otherwise they
// go to getExternalFilesDir(DIRECTORY_DOWNLOADS)/Anipull/<title>/,
// which FileProvider can serve. A revoked SAF permission falls
// back to the app-private dir rather than failing the download.
//
// m3u8 expiry caveat: resolveDownload returns a playlist URL that
// dies in 3-5 min, so the UI must resolve immediately before
// enqueue and the engine drains the queue promptly. A queued HLS
// job that waits too long fails with "link expired — retry", and
// retry needs a fresh resolve (the engine holds no source/cookie).

data class DownloadJob(
    val id: String,
    val animeTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val quality: String,
    val audio: String, // "Sub" or "Dub"
    val poster: String?,
    val state: JobState,
    val progress: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedMBps: Double,
    val error: String?,
    val filePath: String?,
    // 2.0-dev1.7: which server resolved this job (1..9, 0 = unknown)
    // and whether an explicit Resume consented to mobile data for
    // this job (see the Wi-Fi gate in runJob). Both default so old
    // persisted jobs still parse.
    val serverNumber: Int = 0,
    val wifiOverride: Boolean = false,
    // 2.0-dev2.2: resolve context, so a terminal (FAILED/CANCELLED)
    // job can be retried from the Downloads tab with a FRESH resolve
    // instead of resurrecting its stale (possibly expired HLS) link.
    // titleId is the ApiTitle id; anilistId the media-index match id
    // (may differ from titleId); fromMediaIndex/listServer pick the
    // resolve path. All default so old persisted jobs still parse —
    // those pre-date the context and retry via the episode row.
    val titleId: Long = 0,
    val anilistId: Long? = null,
    val fromMediaIndex: Boolean = false,
    val listServer: Int? = null,
)

enum class JobState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

object DownloadEngine {
    private const val PREFS_JOBS = "anipull_downloads"
    private const val KEY_JOBS_JSON = "jobs_json"
    private const val PREFS_SETTINGS = "anipull_settings"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val lock = Any()
    private var appContext: Context? = null
    private var initialized = false

    private class Control {
        @Volatile var paused = false
        @Volatile var cancelled = false
    }
    private val controls = mutableMapOf<String, Control>()
    private val attempts = mutableMapOf<String, Int>()
    private val links = mutableMapOf<String, ResolvedLink>()
    private val running = mutableSetOf<String>()
    private val lastEmitMs = mutableMapOf<String, Long>()

    // --- Settings ------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

    internal fun parallelLimit(context: Context): Int =
        prefs(context).getInt("parallel", 2).coerceIn(1, 5)

    private fun speedLimitMbps(context: Context): Int =
        prefs(context).getInt("speed_mbps", 0).coerceAtLeast(0)

    private fun wifiOnly(context: Context): Boolean =
        prefs(context).getBoolean("wifi_only", true)

    private fun autoRetry(context: Context): Boolean =
        prefs(context).getBoolean("auto_retry", true)

    private fun safTreeUri(context: Context): Uri? =
        prefs(context).getString("folder_uri", null)?.takeIf { it.isNotBlank() }?.let {
            try {
                Uri.parse(it)
            } catch (_: Exception) {
                null
            }
        }

    // --- Filenames -----------------------------------------------------

    fun sanitizeFileName(name: String, maxLen: Int = 80): String {
        val clean = name.map { c ->
            if (c.code < 32 || c in "/\\:*?\"<>|") '-' else c
        }.joinToString("").trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= maxLen) clean else clean.take(maxLen).trimEnd('-', ' ', '.')
    }

    fun fileNameFor(animeTitle: String, episodeNumber: Int, quality: String, audio: String): String {
        val q = quality.removeSuffix("p")
        val lang = if (audio.equals("eng", ignoreCase = true) || audio.equals("Dub", ignoreCase = true)) "Dub" else "Sub"
        val stem = "$animeTitle - E${episodeNumber.toString().padStart(2, '0')} [${q}p][$lang]"
        return sanitizeFileName(stem) + ".mp4"
    }

    private fun downloadDir(context: Context, animeTitle: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        return File(File(base, "Anipull"), sanitizeFileName(animeTitle))
    }

    // --- Persistence ---------------------------------------------------

    private fun jobToJson(job: DownloadJob): JSONObject = JSONObject().apply {
        put("id", job.id)
        put("animeTitle", job.animeTitle)
        put("episodeNumber", job.episodeNumber)
        put("episodeTitle", job.episodeTitle ?: JSONObject.NULL)
        put("quality", job.quality)
        put("audio", job.audio)
        put("poster", job.poster ?: JSONObject.NULL)
        put("state", job.state.name)
        put("progress", job.progress)
        put("downloadedBytes", job.downloadedBytes)
        put("totalBytes", job.totalBytes)
        put("speedMBps", job.speedMBps)
        put("error", job.error ?: JSONObject.NULL)
        put("filePath", job.filePath ?: JSONObject.NULL)
        put("server", job.serverNumber)
        put("wifi_ok", job.wifiOverride)
        if (job.titleId > 0) put("title_id", job.titleId)
        job.anilistId?.let { put("anilist_id", it) }
        if (job.fromMediaIndex) put("from_media_index", true)
        job.listServer?.let { put("list_server", it) }
        links[job.id]?.let { link ->
            val linkSubs: List<SubtitleTrack> = when (link) {
                is ResolvedLink.DirectMp4 -> link.subtitles
                is ResolvedLink.Hls -> link.subtitles
                else -> emptyList()
            }
            if (linkSubs.isNotEmpty()) {
                val subsArr = JSONArray()
                linkSubs.forEach { s ->
                    subsArr.put(JSONObject().apply {
                        put("language", s.language)
                        put("url", s.url)
                        put("label", s.label ?: JSONObject.NULL)
                    })
                }
                put("link_subs", subsArr)
            }
            when (link) {
                is ResolvedLink.DirectMp4 -> {
                    put("link_type", "mp4")
                    put("link_url", link.url)
                    put("link_referer", link.referer)
                }
                is ResolvedLink.Hls -> {
                    put("link_type", "hls")
                    put("link_url", link.masterPlaylistUrl)
                    link.keyOverrideB64?.let { put("link_key", it) }
                    link.ivOverrideB64?.let { put("link_iv", it) }
                    link.referer?.let { put("link_referer", it) }
                    link.origin?.let { put("link_origin", it) }
                }
                else -> {}
            }
        }
    }

    private fun subsFromJson(obj: JSONObject): List<SubtitleTrack> {
        val out = mutableListOf<SubtitleTrack>()
        val arr = obj.optJSONArray("link_subs") ?: return out
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            out += SubtitleTrack(
                language = s.optString("language").takeIf { it.isNotBlank() } ?: "en",
                url = url,
                label = s.optString("label").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    private fun jsonToJob(obj: JSONObject): DownloadJob? {
        return try {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            when (obj.optString("link_type")) {
                "mp4" -> links[id] = ResolvedLink.DirectMp4(obj.optString("link_url"), obj.optString("link_referer"), subsFromJson(obj))
                "hls" -> links[id] = ResolvedLink.Hls(
                    obj.optString("link_url"),
                    subsFromJson(obj),
                    obj.optString("link_key").takeIf { it.isNotBlank() },
                    obj.optString("link_iv").takeIf { it.isNotBlank() },
                    obj.optString("link_referer").takeIf { it.isNotBlank() },
                    obj.optString("link_origin").takeIf { it.isNotBlank() },
                )
            }
            DownloadJob(
                id = id,
                animeTitle = obj.optString("animeTitle"),
                episodeNumber = obj.optInt("episodeNumber"),
                episodeTitle = obj.optString("episodeTitle").takeIf { it.isNotBlank() },
                quality = obj.optString("quality", "1080p"),
                audio = obj.optString("audio", "Sub"),
                poster = obj.optString("poster").takeIf { it.isNotBlank() },
                state = runCatching { JobState.valueOf(obj.optString("state")) }.getOrDefault(JobState.QUEUED),
                progress = obj.optInt("progress"),
                downloadedBytes = obj.optLong("downloadedBytes"),
                totalBytes = obj.optLong("totalBytes"),
                speedMBps = obj.optDouble("speedMBps"),
                error = obj.optString("error").takeIf { it.isNotBlank() },
                filePath = obj.optString("filePath").takeIf { it.isNotBlank() },
                serverNumber = obj.optInt("server", 0),
                wifiOverride = obj.optBoolean("wifi_ok", false),
                titleId = obj.optLong("title_id", 0),
                anilistId = obj.optLong("anilist_id", -1).takeIf { it > 0 },
                fromMediaIndex = obj.optBoolean("from_media_index", false),
                listServer = obj.optInt("list_server", -1).takeIf { it in 1..9 },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            synchronized(lock) { _jobs.value.forEach { arr.put(jobToJson(it)) } }
            context.getSharedPreferences(PREFS_JOBS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JOBS_JSON, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadPersisted(context: Context): List<DownloadJob> {
        return try {
            val text = context.getSharedPreferences(PREFS_JOBS, Context.MODE_PRIVATE)
                .getString(KEY_JOBS_JSON, null) ?: return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::jsonToJob) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        // 2.0-dev1.9 TEMPORARY download diagnostics: init the
        // rolling log here (also called from DownloadService and
        // the clearance path) so resolve-time entries flush to file.
        DownloadLogs.init(ctx)
        synchronized(lock) {
            appContext = ctx
            if (initialized) return
            initialized = true
        }
        val restored = loadPersisted(ctx).map {
            // A crash mid-download leaves DOWNLOADING behind; re-queue
            // it (DDL resumes via Range, HLS via its segment index —
            // an expired HLS link fails fast with "retry" instead).
            if (it.state == JobState.DOWNLOADING) it.copy(state = JobState.QUEUED, speedMBps = 0.0) else it
        }
        _jobs.value = restored
        DownloadLogs.log("ENGINE", "init: restored ${restored.size} job(s), ${restored.count { it.state == JobState.QUEUED }} queued")
        if (restored.any { it.state == JobState.QUEUED }) pump()
    }

    private fun updateJob(id: String, transform: (DownloadJob) -> DownloadJob, persistNow: Boolean = false) {
        val ctx = appContext
        var changed: DownloadJob? = null
        synchronized(lock) {
            _jobs.value = _jobs.value.map {
                if (it.id == id) transform(it).also { changed = it } else it
            }
        }
        changed?.let { job ->
            if (persistNow) ctx?.let { persist(it) }
            notifyJob(job)
        }
    }

    private fun notifyJob(job: DownloadJob) {
        val ctx = appContext ?: return
        when (job.state) {
            JobState.COMPLETED -> DownloadNotifications.showJobComplete(ctx, job)
            JobState.DOWNLOADING, JobState.PAUSED -> DownloadNotifications.showJobProgress(ctx, job)
            // A dead job must not leave a stale progress notification.
            JobState.FAILED, JobState.CANCELLED -> DownloadNotifications.cancelJob(ctx, job.id)
            else -> {}
        }
        val active = _jobs.value.filter { it.state == JobState.DOWNLOADING || it.state == JobState.PAUSED }
        if (active.size > 1) DownloadNotifications.showSummary(ctx, active)
        else DownloadNotifications.cancelSummary(ctx)
    }

    // --- Queue ---------------------------------------------------------

    fun enqueue(
        context: Context,
        title: ApiTitle,
        episode: EpisodeInfo,
        quality: String,
        link: ResolvedLink,
        serverNumber: Int = 0,
        wifiOverride: Boolean = false,
        // 2.0-dev2.2: resolve context for a later fresh retry (see
        // DownloadJob). A terminal existing job (FAILED/CANCELLED)
        // is replaced below, so a re-pull is always a fresh job
        // with a fresh link — never a resurrection.
        anilistId: Long? = null,
        fromMediaIndex: Boolean = false,
        listServer: Int? = null,
    ): String {
        init(context)
        val audio = if (episode.audio.equals("eng", ignoreCase = true)) "Dub" else "Sub"
        val id = "dl_${title.id}_${episode.number}_${episode.audio.lowercase()}_${quality.lowercase()}"
        synchronized(lock) {
            val existing = _jobs.value.firstOrNull { it.id == id }
            if (existing != null && existing.state in listOf(JobState.QUEUED, JobState.DOWNLOADING, JobState.PAUSED)) {
                return id // Already queued — no duplicate.
            }
            if (existing != null) {
                _jobs.value = _jobs.value.filter { it.id != id }
            }
            val job = DownloadJob(
                id = id,
                animeTitle = title.title,
                episodeNumber = episode.number,
                episodeTitle = episode.title,
                quality = quality,
                audio = audio,
                poster = title.poster,
                state = JobState.QUEUED,
                progress = 0,
                downloadedBytes = 0,
                totalBytes = 0,
                speedMBps = 0.0,
                error = null,
                filePath = null,
                serverNumber = serverNumber,
                wifiOverride = wifiOverride,
                titleId = title.id,
                anilistId = anilistId,
                fromMediaIndex = fromMediaIndex,
                listServer = listServer,
            )
            links[id] = link
            controls[id] = Control()
            attempts[id] = 0
            _jobs.value = _jobs.value + job
        }
        persist(context.applicationContext)
        val linkKind = when (link) {
            is ResolvedLink.DirectMp4 -> "DDL mp4"
            is ResolvedLink.Hls -> "HLS master"
            else -> "failed-link"
        }
        DownloadLogs.logJob(id, "ENGINE", "enqueue: ${title.title} E${episode.number} $quality $audio, server=$serverNumber, link=$linkKind, wifiOverride=$wifiOverride")
        try {
            context.startForegroundService(Intent(context, DownloadService::class.java))
            DownloadLogs.logJob(id, "ENGINE", "foreground service start requested")
        } catch (e: Exception) {
            DownloadLogs.logJob(id, "ENGINE", "foreground service start FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""} (jobs still run in engine scope)")
        }
        pump()
        return id
    }

    fun pause(id: String) {
        DownloadLogs.logJob(id, "ENGINE", "pause requested")
        controls[id]?.paused = true
        updateJob(id, { job ->
            if (job.state == JobState.DOWNLOADING || job.state == JobState.QUEUED) {
                job.copy(state = JobState.PAUSED, speedMBps = 0.0)
            } else job
        }, persistNow = true)
    }

    fun resume(id: String) {
        DownloadLogs.logJob(id, "ENGINE", "resume requested (sets wifiOverride=true for this job)")
        controls[id]?.paused = false
        updateJob(id, { job ->
            if (job.state == JobState.PAUSED || job.state == JobState.FAILED) {
                // An explicit Resume is consent for THIS job to use
                // mobile data (2.0-dev1.7 paused-trap fix): without
                // it, a Wi-Fi-gated job re-queues, runJob pauses it
                // again, and Resume can never work on mobile data.
                // Persisted, so the consent survives a restart.
                job.copy(state = JobState.QUEUED, error = null, speedMBps = 0.0, wifiOverride = true)
            } else job
        }, persistNow = true)
        pump()
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        DownloadLogs.logJob(id, "ENGINE", "cancel requested (state=${job.state})")
        // A finished job is not cancellable — cancelling it would
        // delete the downloaded file out from under the user.
        if (job.state == JobState.COMPLETED || job.state == JobState.CANCELLED) return
        controls[id]?.cancelled = true
        val ctx = appContext
        if (job.state != JobState.DOWNLOADING) {
            // Not running: mark cancelled + clean up partials now.
            deletePartials(ctx, job)
            updateJob(id, { it.copy(state = JobState.CANCELLED, speedMBps = 0.0, progress = 0) }, persistNow = true)
            ctx?.let { DownloadNotifications.cancelJob(it, id) }
            pump()
        }
        // Running jobs see the flag in their loop and clean up there.
    }

    fun deleteCompleted(context: Context, id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        deleteFile(context, job.filePath)
        deletePartials(context, job)
        synchronized(lock) {
            _jobs.value = _jobs.value.filter { it.id != id }
            links.remove(id)
            controls.remove(id)
            attempts.remove(id)
        }
        persist(context.applicationContext)
        DownloadNotifications.cancelJob(context, id)
    }

    fun completedJobs(context: Context): List<DownloadJob> {
        init(context)
        return _jobs.value.filter { it.state == JobState.COMPLETED }
    }

    fun openFile(context: Context, job: DownloadJob): Boolean {
        val path = job.filePath ?: return false
        return try {
            val uri = if (path.startsWith("content://")) {
                Uri.parse(path)
            } else {
                val file = File(path)
                if (!file.exists()) return false
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Pump / runner ---------------------------------------------------

    private fun pump() {
        val ctx = appContext ?: return
        val limit = parallelLimit(ctx)
        synchronized(lock) {
            val queued = _jobs.value.filter { it.state == JobState.QUEUED && it.id !in running }
            val slots = (limit - running.size).coerceAtLeast(0)
            queued.take(slots).forEach { job ->
                running += job.id
                scope.launch { runJob(job.id) }
            }
        }
    }

    private suspend fun runJob(id: String) {
        val ctx = appContext ?: return
        try {
            val job = _jobs.value.firstOrNull { it.id == id } ?: return
            val link = links[id] ?: run {
                DownloadLogs.logJob(id, "ENGINE", "FAILED: resolved link is gone (not in memory, not restored from storage)")
                updateJob(id, { it.copy(state = JobState.FAILED, error = "Download link is gone — tap to retry.") }, persistNow = true)
                return
            }
            val linkKind = when (link) {
                is ResolvedLink.DirectMp4 -> "DDL mp4"
                is ResolvedLink.Hls -> "HLS master"
                else -> "failed-link"
            }
            DownloadLogs.logJob(id, "ENGINE", "runJob start: server=${job.serverNumber}, link=$linkKind, quality=${job.quality} ${job.audio}")
            val control = controls.getOrPut(id) { Control() }
            if (control.cancelled) {
                updateJob(id, { it.copy(state = JobState.CANCELLED, speedMBps = 0.0) }, persistNow = true)
                return
            }
            // Wi-Fi gate: pause on mobile data instead of burning it.
            // Skipped when the job carries a wifiOverride (an explicit
            // Resume consented to mobile data for this job) — without
            // the skip, resume() re-queues the job and this gate
            // pauses it again, an unresumable loop (2.0-dev1.6 bug:
            // FMAB ep 1 stuck on PAUSED on mobile data). The honest
            // "Paused — waiting for Wi-Fi" error stays for jobs
            // without the override.
            val wifiOnlyOn = wifiOnly(ctx)
            val onWifi = isWifiConnected(ctx)
            DownloadLogs.logJob(id, "ENGINE", "wifi gate: wifiOnly=$wifiOnlyOn onWifi=$onWifi wifiOverride=${job.wifiOverride}")
            if (!job.wifiOverride && wifiOnlyOn && !onWifi) {
                DownloadLogs.logJob(id, "ENGINE", "GATED: paused — waiting for Wi-Fi (mobile data, no override)")
                updateJob(id, { it.copy(state = JobState.PAUSED, error = "Paused — waiting for Wi-Fi", speedMBps = 0.0) }, persistNow = true)
                return
            }
            // No network at all: wait up to 60s for one to appear
            // (transient drop), then fail with a retryable error.
            var waited = 0
            while (!hasNetwork(ctx) && waited < 60 && !control.cancelled) {
                delay(2000)
                waited += 2
            }
            if (control.cancelled) {
                updateJob(id, { it.copy(state = JobState.CANCELLED, speedMBps = 0.0) }, persistNow = true)
                return
            }
            if (!hasNetwork(ctx)) {
                DownloadLogs.logJob(id, "ENGINE", "FAILED: no network after ${waited}s wait")
                failOrRetry(id, "No connection — check your network and retry.")
                return
            }
            updateJob(id, { it.copy(state = JobState.DOWNLOADING, error = null) }, persistNow = true)
            val fileName = fileNameFor(job.animeTitle, job.episodeNumber, job.quality, job.audio)
            val dir = downloadDir(ctx, job.animeTitle)
            dir.mkdirs()
            val dest = resolveDestination(ctx, job, dir, fileName)
            DownloadLogs.logJob(id, "ENGINE", "downloading: dest=${if (safTreeUri(ctx) != null) "SAF tree (staged locally)" else "file"} dir=${dir.absolutePath} file=$fileName")
            val speedLimit = speedLimitMbps(ctx)
            val segmentWorkers = (parallelLimit(ctx) * 2).coerceIn(2, 10)
            var hlsSubs: List<SubtitleTrack> = emptyList()
            try {
                when (link) {
                    is ResolvedLink.DirectMp4 -> downloadMp4(
                        url = link.url,
                        referer = link.referer,
                        workFile = dest.workFile,
                        jobId = id,
                        control = control,
                        speedLimitMBps = speedLimit,
                        onProgress = { bytes, total, speed -> reportProgress(id, bytes, total, speed) },
                    )
                    is ResolvedLink.Hls -> hlsSubs = HlsDownloader.download(
                        masterUrl = link.masterPlaylistUrl,
                        outputFile = dest.workFile,
                        tempDir = dest.tempDir,
                        quality = job.quality,
                        segmentWorkers = segmentWorkers,
                        speedLimitMBps = speedLimit,
                        isCancelled = { control.cancelled },
                        isPaused = { control.paused },
                        onProgress = { bytes, total, speed -> reportProgress(id, bytes, total, speed) },
                        audio = job.audio,
                        keyOverrideB64 = link.keyOverrideB64,
                        ivOverrideB64 = link.ivOverrideB64,
                        referer = link.referer,
                        origin = link.origin,
                    )
                    is ResolvedLink.Failed -> throw java.io.IOException(link.message)
                }
                if (control.cancelled) throw CancellationException("cancelled")
                // SAF mode: copy the finished file into the chosen tree.
                // Size is read BEFORE finish(): a successful SAF copy
                // deletes the staged file, so reading after would be 0.
                val finishedBytes = dest.workFile.length()
                val finalPath = dest.finish(ctx, fileName)
                DownloadLogs.logJob(id, "ENGINE", "COMPLETED: ${finishedBytes / 1024} KB -> $finalPath")
                updateJob(id, {
                    it.copy(
                        state = JobState.COMPLETED, progress = 100,
                        downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes),
                        speedMBps = 0.0, error = null, filePath = finalPath,
                    )
                }, persistNow = true)
                // Subtitle sidecars (best-effort): union of the
                // resolve-time tracks and the ones the master
                // playlist itself advertised. A failed sidecar never
                // fails the job.
                try {
                    val linkSubs: List<SubtitleTrack> = when (link) {
                        is ResolvedLink.DirectMp4 -> link.subtitles
                        is ResolvedLink.Hls -> link.subtitles
                        else -> emptyList()
                    }
                    val allSubs = (linkSubs + hlsSubs).distinctBy { it.url }
                    if (allSubs.isNotEmpty()) {
                        // Subtitle sidecars live on the same gated
                        // CDNs, so they get the same Referer/Origin
                        // the segments got (HLS links only; DDL
                        // links have no Origin and a plain Referer).
                        val subHeaders: Map<String, String> = buildMap {
                            val ref = when (link) {
                                is ResolvedLink.DirectMp4 -> link.referer
                                is ResolvedLink.Hls -> link.referer
                                else -> null
                            }
                            ref?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                            (link as? ResolvedLink.Hls)?.origin
                                ?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
                        }
                        writeSubtitleSidecars(ctx, job, fileName, finalPath, allSubs, subHeaders)
                    }
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                DownloadLogs.logJob(id, "ENGINE", "CANCELLED by user")
                deletePartials(ctx, job)
                updateJob(id, { it.copy(state = JobState.CANCELLED, speedMBps = 0.0, progress = 0, downloadedBytes = 0) }, persistNow = true)
                ctx.let { DownloadNotifications.cancelJob(it, id) }
            } catch (e: Exception) {
                // 2.0-dev1.9 diagnostics: the exact failure, not the
                // friendly string — the friendly text is what the
                // job stores; this line is what the report shows.
                DownloadLogs.logJob(id, "ENGINE", "FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""} (friendly: ${friendlyDownloadError(e)})")
                // A failed HLS remux leaves a partial mp4 — drop it
                // (the segments in the temp dir are kept: that IS
                // the resume state). A failed DDL keeps its .part
                // file so auto-retry resumes via HTTP Range.
                if (link is ResolvedLink.Hls && dest.workFile.exists()) dest.workFile.delete()
                failOrRetry(id, friendlyDownloadError(e))
            }
        } finally {
            synchronized(lock) { running -= id }
            pump()
        }
    }

    private fun failOrRetry(id: String, message: String) {
        val ctx = appContext
        val shouldRetry = ctx != null && autoRetry(ctx) && (attempts[id] ?: 0) < 1
        if (shouldRetry) {
            attempts[id] = (attempts[id] ?: 0) + 1
            DownloadLogs.logJob(id, "ENGINE", "auto-retry ${attempts[id]}/1 in 5s (error: $message)")
            // Stay FAILED during the backoff so pump() (called from
            // runJob's finally) does NOT restart the job immediately.
            // The delayed re-queue below is the only path back.
            updateJob(id, { it.copy(state = JobState.FAILED, error = "Retrying… ($message)", speedMBps = 0.0) }, persistNow = true)
            scope.launch {
                delay(5000)
                val current = _jobs.value.firstOrNull { it.id == id }
                if (current?.state == JobState.FAILED) {
                    updateJob(id, { it.copy(state = JobState.QUEUED, error = null) }, persistNow = true)
                    pump()
                }
            }
        } else {
            DownloadLogs.logJob(id, "ENGINE", "final failure (no auto-retry left): $message")
            updateJob(id, { it.copy(state = JobState.FAILED, error = message, speedMBps = 0.0) }, persistNow = true)
        }
    }

    private fun reportProgress(id: String, bytes: Long, total: Long, speed: Double) {
        val now = System.currentTimeMillis()
        val last = lastEmitMs[id] ?: 0L
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        val progress = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else job.progress
        if (now - last < 500 && progress == job.progress) return
        lastEmitMs[id] = now
        updateJob(id, { it.copy(progress = progress, downloadedBytes = bytes, totalBytes = if (total > 0) total else it.totalBytes, speedMBps = speed) })
    }

    // --- DDL mp4 download (HTTP Range resume) ---------------------------

    private suspend fun downloadMp4(
        url: String,
        referer: String,
        workFile: File,
        jobId: String,
        control: Control,
        speedLimitMBps: Int,
        onProgress: (Long, Long, Double) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val downloadStartedMs = System.currentTimeMillis()
        var existing = if (workFile.exists()) workFile.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Referer", referer)
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            DownloadLogs.logJob(jobId, "DDL", "GET ${DownloadLogs.sanitizeUrl(url)} -> HTTP $code, resumeFrom=${existing}B")
            if (code !in 200..299 && code != 206) {
                throw java.io.IOException("Video server answered HTTP $code — retry the download.")
            }
            if (code == 200 && existing > 0) {
                // Server ignored Range: restart from zero.
                DownloadLogs.logJob(jobId, "DDL", "server ignored Range — restarting from 0")
                existing = 0
                workFile.delete()
            }
            val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val total = if (remaining > 0) existing + remaining else -1L
            DownloadLogs.logJob(jobId, "DDL", "total=${if (total > 0) "${total / 1024} KB" else "unknown"}")
            val throttle = SpeedThrottler(speedLimitMBps * 1024L * 1024L)
            var downloaded = existing
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var lastSpeed = 0.0
            conn.inputStream.use { input ->
                java.io.FileOutputStream(workFile, existing > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (control.cancelled) throw CancellationException("cancelled")
                        while (control.paused) {
                            if (control.cancelled) throw CancellationException("cancelled")
                            delay(300)
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        windowBytes += n
                        throttle.throttle(n)
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 1000) {
                            lastSpeed = windowBytes * 1000.0 / (now - windowStart).coerceAtLeast(1) / (1024.0 * 1024.0)
                            windowStart = now
                            windowBytes = 0
                        }
                        onProgress(downloaded, total, lastSpeed)
                    }
                }
            }
            onProgress(downloaded, total.coerceAtLeast(downloaded), lastSpeed)
            DownloadLogs.logJob(jobId, "DDL", "done: ${downloaded / 1024} KB in ${System.currentTimeMillis() - downloadStartedMs}ms")
        } finally {
            conn.disconnect()
        }
    }

    // --- Destination (file mode vs SAF tree) -----------------------------

    private class Dest(
        val workFile: File,
        val tempDir: File,
        private val safTreeUri: Uri?,
        private val safDirName: String,
        private val fileDir: File,
    ) {
        // On success: file mode already wrote the final file; SAF
        // mode copies the finished file into the chosen tree and
        // returns the document URI string as the file path.
        fun finish(context: Context, fileName: String): String {
            val tree = safTreeUri ?: return workFile.absolutePath
            val docUri = safCreateFile(context, tree, safDirName, fileName, workFile)
            if (docUri == null) {
                // 2.0-dev1.9 diagnostics: the SAF copy failed, so
                // the staged file is kept as the result instead of
                // failing the job — the log says which happened.
                DownloadLogs.log("ENGINE", "SAF copy failed for $fileName — keeping staged file ${workFile.absolutePath}")
                return workFile.absolutePath
            }
            DownloadLogs.log("ENGINE", "SAF copy done: $fileName -> ${docUri.host ?: "tree"}")
            return docUri.toString()
        }
    }

    private fun resolveDestination(context: Context, job: DownloadJob, dir: File, fileName: String): Dest {
        val tree = safTreeUri(context)
        val workFile = if (tree != null) {
            // SAF mode: stage locally, copy into the tree on success.
            // 2.0-dev1.9 FIX (provable from code): the .staging
            // parent was NEVER created — runJob only mkdirs() the
            // anime dir, and downloadMp4 opens a FileOutputStream
            // on this file, so EVERY DDL download with a custom
            // SAF folder picked died with FileNotFoundException
            // before a single byte was written. (HLS survived only
            // because remux() mkdirs() the output parent.) Create
            // it here so both paths share the guarantee.
            File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"), "${job.id}.part")
                .also { it.parentFile?.mkdirs() }
        } else {
            File(dir, fileName)
        }
        val tempDir = File(dir, ".tmp-${job.id}")
        return Dest(workFile, tempDir, tree, sanitizeFileName(job.animeTitle), dir)
    }

    private fun deletePartials(context: Context?, job: DownloadJob) {
        if (context == null) return
        try {
            val dir = downloadDir(context, job.animeTitle)
            val fileName = fileNameFor(job.animeTitle, job.episodeNumber, job.quality, job.audio)
            File(dir, fileName).delete()
            File(dir, ".tmp-${job.id}").deleteRecursively()
            File(
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"),
                "${job.id}.part",
            ).delete()
        } catch (_: Exception) {
        }
    }

    private fun deleteFile(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        try {
            if (path.startsWith("content://")) {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path))
            } else {
                File(path).delete()
            }
        } catch (_: Exception) {
        }
    }

    // --- Subtitle sidecars (best-effort) ------------------------------

    // Download each subtitle track next to the finished video:
    // same stem + .<language>.vtt. File mode writes beside the mp4;
    // SAF mode writes into the same tree folder via DocumentsContract
    // (reusing the safCreateFile pattern). A failed sidecar is
    // skipped silently and never fails the job.
    private fun writeSubtitleSidecars(
        context: Context,
        job: DownloadJob,
        fileName: String,
        finalPath: String,
        subs: List<SubtitleTrack>,
        headers: Map<String, String> = emptyMap(),
    ) {
        val stem = fileName.removeSuffix(".mp4")
        val used = mutableSetOf<String>()
        for (sub in subs) {
            try {
                val langRaw = sub.language.ifBlank { "sub" }
                    .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }
                    .joinToString("").trim('-').ifBlank { "sub" }
                var vttName = "$stem.$langRaw.vtt"
                var n = 2
                while (!used.add(vttName)) {
                    vttName = "$stem.$langRaw-$n.vtt"
                    n++
                }
                val bytes = try {
                    val resp = EngineHttp.requestWithRetry(sub.url, headers = headers, timeoutMs = 15_000)
                    if (resp.code != 200 || resp.bytes.isEmpty()) null else resp.bytes
                } catch (_: Exception) {
                    null
                } ?: continue
                if (finalPath.startsWith("content://")) {
                    val tree = safTreeUri(context) ?: continue
                    val staging = File(
                        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"),
                        "${job.id}-$vttName",
                    )
                    staging.parentFile?.mkdirs()
                    staging.writeBytes(bytes)
                    safCreateFile(context, tree, sanitizeFileName(job.animeTitle), vttName, staging, mimeType = "text/vtt")
                } else {
                    val dir = File(finalPath).parentFile ?: continue
                    File(dir, vttName).writeBytes(bytes)
                }
            } catch (_: Exception) {
                // Best-effort: skip this track, keep the rest.
            }
        }
    }

    // --- SAF helpers (framework DocumentsContract only, no new dep) -----

    private fun safFindChild(context: Context, treeUri: Uri, parentDocId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(1) ?: continue
                if (displayName == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun safCreateFile(context: Context, treeUri: Uri, dirName: String, fileName: String, source: File, mimeType: String = "video/mp4"): Uri? {
        return try {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            var dirDoc = safFindChild(context, treeUri, rootId, dirName)
            if (dirDoc == null) {
                dirDoc = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), DocumentsContract.Document.MIME_TYPE_DIR, dirName)
                    ?: return null
            }
            val dirId = DocumentsContract.getDocumentId(dirDoc)
            // Replace a previous file of the same name, if any.
            safFindChild(context, treeUri, dirId, fileName)?.let { existing ->
                try {
                    DocumentsContract.deleteDocument(resolver, existing)
                } catch (_: Exception) {
                }
            }
            val docUri = DocumentsContract.createDocument(resolver, dirDoc, mimeType, fileName) ?: return null
            resolver.openOutputStream(docUri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return null
            source.delete()
            docUri
        } catch (_: Exception) {
            null
        }
    }

    // --- Network state ---------------------------------------------------

    private fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasNetwork(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork) != null
        } catch (_: Exception) {
            false
        }
    }
}
