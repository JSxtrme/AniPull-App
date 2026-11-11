package com.js.anipull

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
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
// 2.0-dev2.6 STORAGE: completed downloads live in the PUBLIC
// Downloads folder, not the app-private dir. Downloads still stage
// into getExternalFilesDir(DIRECTORY_DOWNLOADS)/.staging/<id>.part
// (DDL Range resume + HLS remux both need a real File, so direct
// MediaStore append was rejected — see DownloadStorage.kt), and
// on success — only after the dev2.5 quality probe passes — the
// finished file is published to MediaStore.Downloads with
// RELATIVE_PATH "Download/Anipull/<Title>" (IS_PENDING=1 during
// the copy, 0 after), so Gallery and Files see it. The persisted
// filePath is then the MediaStore content:// Uri; Open/Delete
// handle that, plain file paths (legacy / SAF fallback), and SAF
// document Uris. A user-picked SAF tree (folder_uri) still wins:
// those files copy into the chosen tree instead of Downloads.
// First launch after the update migrates already-completed legacy
// files into Downloads (see migrateLegacyCompleted); unfinished
// legacy files are adopted into staging when their job next runs.
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
    // 2.0-dev2.5: the ACTUAL quality of the delivered file
    // ("720p"), when known — from the resolving link's offered
    // height, the HLS variant pick, or the post-download
    // MediaExtractor probe. `quality` above stays the REQUESTED
    // quality. Null = unknown (old jobs, or a resolver that never
    // stated one). The file is named from this when set, never
    // from the requested quality.
    val actualQuality: String? = null,
    // 2.0-dev2.6 SIZE: true when totalBytes is an ESTIMATE, not a
    // measured total — HLS totals are segment-count x average
    // segment size, DDL totals are exact Content-Length. The UI
    // must label an estimated total with "~" and never present it
    // as exact. Defaults false so old persisted jobs parse.
    val totalEstimated: Boolean = false,
)

enum class JobState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

// 2.0-dev2.5 DEFECT 2: progress == -1 means INDETERMINATE —
// bytes are flowing (or the job is in its connecting /
// playlist-fetch phase) but the total size is unknown, so no %
// exists. It must NEVER be rendered as "-1%" or as a determinate
// "0%".
// TODO(COORDINATOR): App.kt must render progress < 0 as
// indeterminate in BOTH places that read job.progress today —
// DownloadJobRow (~"Downloading -> ${job.progress}% · … MB/s"
// and the progress bar, which already coerces to 0) and
// jobStateText (episode row, "Downloading -> ${job.progress}%").
// Suggested text: "X MB · Y MB/s" (job.downloadedBytes /
// job.speedMBps) with no %, bar left at 0/indeterminate. The
// notification already handles -1 (Notifications.kt
// showJobProgress). No engine change is needed beyond this
// sentinel; do NOT "fix" it by clamping progress back to 0 in
// App.kt — that reintroduces the frozen-0% defect.
internal const val PROGRESS_INDETERMINATE = -1

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
        // 2.0-dev2.5: actual delivered quality, so a completed
        // job keeps its honest name across restarts.
        job.actualQuality?.let { put("actual_quality", it) }
        // 2.0-dev2.6: whether totalBytes is an HLS estimate.
        if (job.totalEstimated) put("total_estimated", true)
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
                    link.actualQuality?.let { put("link_quality", it) }
                    link.estimatedSizeBytes?.let { put("link_size", it) }
                    if (link.sizeIsEstimate) put("link_size_est", true)
                }
                is ResolvedLink.Hls -> {
                    put("link_type", "hls")
                    put("link_url", link.masterPlaylistUrl)
                    link.keyOverrideB64?.let { put("link_key", it) }
                    link.ivOverrideB64?.let { put("link_iv", it) }
                    link.referer?.let { put("link_referer", it) }
                    link.origin?.let { put("link_origin", it) }
                    link.actualQuality?.let { put("link_quality", it) }
                    link.estimatedSizeBytes?.let { put("link_size", it) }
                    if (link.sizeIsEstimate) put("link_size_est", true)
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
            val linkQuality = obj.optInt("link_quality", 0).takeIf { it > 0 }
            val linkSize = obj.optLong("link_size", 0).takeIf { it > 0 }
            val linkSizeEst = obj.optBoolean("link_size_est", false)
            when (obj.optString("link_type")) {
                "mp4" -> links[id] = ResolvedLink.DirectMp4(obj.optString("link_url"), obj.optString("link_referer"), subsFromJson(obj), actualQuality = linkQuality, estimatedSizeBytes = linkSize, sizeIsEstimate = linkSizeEst)
                "hls" -> links[id] = ResolvedLink.Hls(
                    obj.optString("link_url"),
                    subsFromJson(obj),
                    obj.optString("link_key").takeIf { it.isNotBlank() },
                    obj.optString("link_iv").takeIf { it.isNotBlank() },
                    obj.optString("link_referer").takeIf { it.isNotBlank() },
                    obj.optString("link_origin").takeIf { it.isNotBlank() },
                    actualQuality = linkQuality,
                    estimatedSizeBytes = linkSize,
                    sizeIsEstimate = linkSizeEst,
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
                actualQuality = obj.optString("actual_quality").takeIf { it.isNotBlank() },
                totalEstimated = obj.optBoolean("total_estimated", false),
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
            // 2.0-dev2.5: a re-queued job also drops a stale
            // INDETERMINATE progress back to 0 — a queued job has
            // no bytes flowing yet, so -1 would be a lie.
            if (it.state == JobState.DOWNLOADING) it.copy(state = JobState.QUEUED, speedMBps = 0.0, progress = 0) else it
        }
        _jobs.value = restored
        DownloadLogs.log("ENGINE", "init: restored ${restored.size} job(s), ${restored.count { it.state == JobState.QUEUED }} queued")
        migrateLegacyCompleted(ctx)
        if (restored.any { it.state == JobState.QUEUED }) pump()
    }

    // 2.0-dev2.6 MIGRATION (once, first launch after the update):
    // completed jobs whose file still sits in the old app-private
    // dir are published to the public Downloads folder and their
    // filePath is re-pointed at the MediaStore Uri — silently
    // (notify=false: re-posting a completion ping per old download
    // would spam the user). Unfinished jobs are NOT touched: their
    // legacy file is resume state, adopted into staging by
    // resolveDestination when the job next runs, and published when
    // it finishes. A file that fails to publish stays put and is
    // retried next launch (the flag is only set after a full pass).
    private fun migrateLegacyCompleted(context: Context) {
        scope.launch {
            try {
                val settings = prefs(context)
                if (settings.getBoolean("public_dl_migrated_v1", false)) return@launch
                var moved = 0
                val legacyRoot = DownloadStorage.legacyDownloadDir(context)
                for (job in _jobs.value.filter { it.state == JobState.COMPLETED }) {
                    val path = job.filePath ?: continue
                    if (path.startsWith("content://")) continue
                    val file = File(path)
                    if (!file.exists() || !file.isFile) continue
                    // Only files actually under the old Anipull dir.
                    if (!file.absolutePath.startsWith(legacyRoot.absolutePath)) continue
                    val uri = DownloadStorage.publishVideoFile(context, file, job.animeTitle, file.name)
                    if (uri != null) {
                        try { file.delete() } catch (_: Exception) { }
                        updateJob(job.id, { it.copy(filePath = uri.toString()) }, persistNow = true, notify = false)
                        moved++
                    }
                }
                settings.edit().putBoolean("public_dl_migrated_v1", true).apply()
                DownloadLogs.log("ENGINE", "migration: moved $moved completed file(s) to public Downloads; ${DownloadStorage.countLegacyVideoFiles(context)} legacy video file(s) remain (unfinished/orphaned)")
            } catch (e: Exception) {
                DownloadLogs.log("ENGINE", "migration FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""} (retries next launch)")
            }
        }
    }

    private fun updateJob(id: String, transform: (DownloadJob) -> DownloadJob, persistNow: Boolean = false, notify: Boolean = true) {
        val ctx = appContext
        var changed: DownloadJob? = null
        synchronized(lock) {
            _jobs.value = _jobs.value.map {
                if (it.id == id) transform(it).also { changed = it } else it
            }
        }
        changed?.let { job ->
            if (persistNow) ctx?.let { persist(it) }
            if (notify) notifyJob(job)
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
                // 2.0-dev2.5 DEFECT 3: record what the link actually
                // offers at enqueue time (DDL) — the file is named
                // from this, and the Downloads chip can read it.
                // HLS links carry null here (master not yet parsed);
                // runJob fills it in from the variant pick / probe.
                actualQuality = link.offeredHeight()?.let { "${it}p" },
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
        DownloadLogs.logJob(id, "ENGINE", "enqueue: ${title.title} E${episode.number} $quality $audio, server=$serverNumber, link=$linkKind, wifiOverride=$wifiOverride" + (link.offeredHeight()?.let { offered ->
            if (offered < qualityHeight(quality)) " — DOWNGRADE: link offers only ${offered}p for requested $quality" else ""
        } ?: ""))
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
                // 2.0-dev2.5: also reset a stale INDETERMINATE
                // progress — the re-queued job shows 0% until its
                // first real progress report lands.
                job.copy(state = JobState.QUEUED, error = null, speedMBps = 0.0, wifiOverride = true, progress = 0)
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

    // 2.0-dev2.4: Clear all for the Downloads tab — removes every
    // job in the given states (FAILED and/or CANCELLED only; the
    // caller never passes Active/Queue/Completed). Drops their
    // links/controls/attempts entries, cancels their
    // notifications, and persists. Returns how many were removed
    // so the UI can toast the count. These are only terminal
    // records — no downloaded file is touched (a FAILED/CANCELLED
    // job has none; partials are left alone, same as before).
    fun clearJobs(context: Context, states: Set<JobState>): Int {
        val ctx = context.applicationContext
        val removed: List<DownloadJob>
        synchronized(lock) {
            removed = _jobs.value.filter { it.state in states }
            if (removed.isEmpty()) return 0
            val ids = removed.map { it.id }.toSet()
            _jobs.value = _jobs.value.filter { it.id !in ids }
            ids.forEach { id ->
                links.remove(id)
                controls.remove(id)
                attempts.remove(id)
            }
        }
        persist(ctx)
        removed.forEach { DownloadNotifications.cancelJob(ctx, it.id) }
        DownloadLogs.log("ENGINE", "clearJobs: removed ${removed.size} job(s) in states=${states.joinToString { it.name }}")
        return removed.size
    }

    fun completedJobs(context: Context): List<DownloadJob> {
        init(context)
        return _jobs.value.filter { it.state == JobState.COMPLETED }
    }

    fun openFile(context: Context, job: DownloadJob): Boolean {
        val uri = resolveOpenUri(context, job) ?: return false
        return try {
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

    // 2.0-dev2.6 SIZE: the honest size of a FINISHED download, read
    // from the actual file — MediaStore SIZE for a published file,
    // File.length for a legacy / publish-fallback path. When the
    // file is provably gone (row deleted, file deleted) this returns
    // null instead of a stale number. Only a path type that can't
    // be stat-ed (SAF document Uri) falls back to the totals
    // stamped at completion — those are the real finished length,
    // not progress bytes. Null = genuinely unknown: the caller
    // must show nothing or "size unknown", never a made-up number.
    // NOT for in-progress jobs: use job.downloadedBytes /
    // job.totalBytes there (totalBytes 0 = unknown), and label an
    // estimated total with "~" (see totalEstimated).
    fun completedSizeBytes(context: Context, job: DownloadJob): Long? {
        val path = job.filePath
        if (!path.isNullOrBlank()) {
            when {
                DownloadStorage.isMediaStoreUri(path) -> {
                    DownloadStorage.publishedSize(context, path)?.let { return it }
                    val name = fileNameFor(job.animeTitle, job.episodeNumber, job.actualQuality ?: job.quality, job.audio)
                    val found = DownloadStorage.findPublicFile(context, name)
                    if (found != null) return DownloadStorage.publishedSize(context, found.toString())
                    return null
                }
                path.startsWith("content://") -> {
                    // SAF document Uri — can't stat cheaply; fall
                    // through to the stamped totals below.
                }
                else -> {
                    val file = File(path)
                    if (file.exists()) return file.length().takeIf { it > 0 }
                    return null
                }
            }
        }
        if (job.state == JobState.COMPLETED) {
            if (job.totalBytes > 0) return job.totalBytes
            if (job.downloadedBytes > 0) return job.downloadedBytes
        }
        return null
    }

    // 2.0-dev2.6: resolve the Uri to open. A persisted MediaStore
    // Uri can go stale (file deleted in Files, row recreated), and
    // a legacy file path can vanish once migration moves it — in
    // both cases fall back to a live lookup by file name before
    // giving up, so Open keeps working after the move.
    private fun resolveOpenUri(context: Context, job: DownloadJob): Uri? {
        val path = job.filePath ?: return null
        val expectedName = fileNameFor(job.animeTitle, job.episodeNumber, job.actualQuality ?: job.quality, job.audio)
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val stillThere = try {
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) {
                false
            }
            if (stillThere) return uri
            return DownloadStorage.findPublicFile(context, expectedName)
        }
        val file = File(path)
        if (file.exists()) {
            return try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Exception) {
                null
            }
        }
        return DownloadStorage.findPublicFile(context, expectedName)
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
            updateJob(id, { it.copy(state = JobState.DOWNLOADING, error = null, totalEstimated = link is ResolvedLink.Hls) }, persistNow = true)
            // 2.0-dev2.5 DEFECT 5: post-resolve silence. The first
            // progress callback used to arrive only with the first
            // segment / first DDL chunk — until then the UI sat at
            // a silent 0%. Emit the connecting phase immediately:
            // INDETERMINATE, no fake %.
            reportProgress(id, 0, -1, 0.0)
            // 2.0-dev2.5 DEFECT 3: name the file from the ACTUAL
            // quality the link offers when it is known (DDL);
            // HLS names from the requested quality until the
            // variant pick lands, then runJob renames below.
            val namingQuality = link.offeredHeight()?.let { "${it}p" } ?: job.quality
            val fileName = fileNameFor(job.animeTitle, job.episodeNumber, namingQuality, job.audio)
            val dir = downloadDir(ctx, job.animeTitle)
            dir.mkdirs()
            val dest = resolveDestination(ctx, job, dir, fileName)
            DownloadLogs.logJob(id, "ENGINE", "downloading: dest=${if (safTreeUri(ctx) != null) "SAF tree (staged locally)" else "file"} dir=${dir.absolutePath} file=$fileName")
            val speedLimit = speedLimitMbps(ctx)
            val segmentWorkers = (parallelLimit(ctx) * 2).coerceIn(2, 10)
            var hlsSubs: List<SubtitleTrack> = emptyList()
            var hlsActualHeight: Int? = null
            try {
                when (link) {
                    is ResolvedLink.DirectMp4 -> {
                        // 2.0-dev2.6 (coordinator patch): last-chance probe — an
                        // unknown-quality DDL that reaches the engine gets ONE
                        // bounded probe before any byte is downloaded. A probed
                        // height below the floor fails over (QualityMismatchException
                        // -> FAILED, no auto-retry, Retry re-resolves fresh) instead
                        // of downloading then deleting. A failed probe (null) proceeds
                        // with the download as before; the post-download check stays
                        // the backstop.
                        if (link.actualQuality == null) {
                            val probe = DdlQualityProbe.probeDdlQuality(link.url, mapOf("Referer" to link.referer))
                            val probed = probe?.height
                            if (probed != null && probed < qualityFloorFor(qualityHeight(job.quality))) {
                                DownloadLogs.logJob(id, "ENGINE", "QUALITY MISMATCH (pre-download probe): DDL is ${probed}p, requested ${job.quality} (floor ${qualityFloorFor(qualityHeight(job.quality))}p) — failing, not downloading")
                                throw QualityMismatchException("Only ${probed}p is available for this episode — try a lower quality.")
                            }
                        }
                        downloadMp4(
                        url = link.url,
                        referer = link.referer,
                        workFile = dest.workFile,
                        jobId = id,
                        control = control,
                        speedLimitMBps = speedLimit,
                        onProgress = { bytes, total, speed -> reportProgress(id, bytes, total, speed) },
                    )
                    }
                    is ResolvedLink.Hls -> {
                        val hlsResult = HlsDownloader.download(
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
                        hlsSubs = hlsResult.subtitles
                        hlsActualHeight = hlsResult.actualHeight
                    }
                    is ResolvedLink.Failed -> throw java.io.IOException(link.message)
                }
                if (control.cancelled) throw CancellationException("cancelled")
                // 2.0-dev2.5 DEFECT 3 — verify what actually
                // arrived. MediaExtractor reads the delivered
                // file's real height; that is ground truth, ahead
                // of the offered/variant heights. A file below
                // the requested floor (e.g. 360p delivered for a
                // 1080p request, floor 720p) is NOT a completion:
                // it fails honestly so Retry re-resolves. An
                // unreadable file (no video track / extractor
                // error) is NOT failed on — verification that
                // can't run must not kill a good download.
                val deliveredHeight = probeVideoHeight(dest.workFile)
                val requestedHeight = qualityHeight(job.quality)
                if (deliveredHeight != null && deliveredHeight < qualityFloorFor(requestedHeight)) {
                    DownloadLogs.logJob(id, "ENGINE", "QUALITY MISMATCH: delivered ${deliveredHeight}p, requested ${job.quality} (floor ${qualityFloorFor(requestedHeight)}p) — failing, not completing")
                    try {
                        dest.workFile.delete()
                    } catch (_: Exception) {
                    }
                    throw QualityMismatchException("Downloaded ${deliveredHeight}p, not ${job.quality} — tap Retry.")
                }
                val actualHeight = deliveredHeight ?: hlsActualHeight ?: link.offeredHeight()
                val actualLabel = actualHeight?.let { "${it}p" } ?: job.quality
                // 2.0-dev2.5 DEFECT 3 — name the file from the
                // ACTUAL quality. The work file is a staging
                // partial in both modes now (2.0-dev2.6), so there
                // is no file to rename — only the name passed to
                // finish() changes, exactly like SAF mode before.
                var finalFileName = fileName
                if (actualLabel != namingQuality) {
                    finalFileName = fileNameFor(job.animeTitle, job.episodeNumber, actualLabel, job.audio)
                    DownloadLogs.logJob(id, "ENGINE", "publishing under actual quality name: $fileName -> $finalFileName")
                }
                // Size is read BEFORE finish(): a successful SAF
                // copy or MediaStore publish deletes the staged
                // file, so reading after would be 0.
                val finishedBytes = dest.workFile.length()
                val finalPath = dest.finish(ctx, finalFileName)
                DownloadLogs.logJob(id, "ENGINE", "COMPLETED: ${finishedBytes / 1024} KB -> $finalPath (requested ${job.quality}, delivered $actualLabel)")
                updateJob(id, {
                    it.copy(
                        state = JobState.COMPLETED, progress = 100,
                        // 2.0-dev2.6 SIZE: stamp the REAL finished
                        // file length, not the progress bytes — the
                        // HLS progress total is a segment-bytes sum,
                        // not the remuxed mp4 size, and an unknown
                        // DDL total left totalBytes at 0. The
                        // completed row reads totalBytes first, so
                        // this is what makes its size true after the
                        // storage move (publish copies byte-for-byte;
                        // publishVideoFile verifies the count).
                        downloadedBytes = finishedBytes,
                        totalBytes = finishedBytes,
                        speedMBps = 0.0, error = null, filePath = finalPath,
                        actualQuality = actualLabel,
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
                        writeSubtitleSidecars(ctx, job, finalFileName, finalPath, allSubs, subHeaders)
                    }
                } catch (_: Exception) {
                }
            } catch (e: QualityMismatchException) {
                // 2.0-dev2.5 DEFECT 3: no auto-retry — the same
                // link would deliver the same low file again.
                // Final FAILED with the honest message; the user's
                // Retry re-resolves (possibly to another server).
                DownloadLogs.logJob(id, "ENGINE", "FAILED (quality): ${e.message ?: ""}")
                deletePartials(ctx, job)
                updateJob(id, { it.copy(state = JobState.FAILED, error = e.message, speedMBps = 0.0) }, persistNow = true)
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

    // 2.0-dev2.5 DEFECT 2 + 1b: progress reporting.
    // - total <= 0 (chunked DDL with no Content-Length, or a
    //   connecting/playlist phase) is INDETERMINATE
    //   (PROGRESS_INDETERMINATE = -1): bytes + speed keep flowing,
    //   but NO % is ever shown — the old code froze progress at
    //   the previous value (0) while speed rose, then jumped to
    //   100 at EOF. A determinate % is only ever computed from a
    //   real total.
    // - Throttle (flicker): at most ~1 post/sec while progress is
    //   unchanged (speed-only movement), and at least 500ms
    //   between posts even when progress moved — the notification
    //   layer dedups identical content on top of this.
    private fun reportProgress(id: String, bytes: Long, total: Long, speed: Double) {
        val now = System.currentTimeMillis()
        val last = lastEmitMs[id] ?: 0L
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        val progress = if (total <= 0) PROGRESS_INDETERMINATE
        else ((bytes * 100) / total).toInt().coerceIn(0, 100)
        if (now - last < 1000 && progress == job.progress) return
        if (now - last < 500) return
        lastEmitMs[id] = now
        updateJob(id, { it.copy(progress = progress, downloadedBytes = bytes, totalBytes = if (total > 0) total else 0, speedMBps = speed) })
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
            // 2.0-dev2.5 DEFECT 2: with no Content-Length the total
            // stays -1 for the whole download — reportProgress now
            // turns that into INDETERMINATE (bytes + speed, no %)
            // instead of freezing at 0%. Emit once up front so
            // the UI leaves "0%" the moment the connection opens.
            onProgress(existing, -1, 0.0)
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
        // 2.0-dev2.6: the work file is always a staging partial
        // now (both modes) — the final file is a MediaStore publish
        // or a SAF copy, never the work file itself.
        val workFile: File,
        val tempDir: File,
        private val safTreeUri: Uri?,
        private val safDirName: String,
        private val fileDir: File,
    ) {
        // On success: SAF mode copies the finished file into the
        // chosen tree and returns the document URI string; default
        // mode publishes it to the public Downloads collection
        // (MediaStore) and returns the content:// Uri string, so
        // Gallery/Files see it. The staged file is deleted once
        // the publish succeeds. If publish fails, the file is
        // moved to the legacy final location (FileProvider-covered)
        // instead of failing the job — Open still works, and
        // Settings > Storage counts it among older files.
        fun finish(context: Context, fileName: String): String {
            val tree = safTreeUri
            if (tree != null) {
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
            val uri = DownloadStorage.publishVideoFile(context, workFile, safDirName, fileName)
            if (uri != null) {
                try { workFile.delete() } catch (_: Exception) { }
                DownloadLogs.log("ENGINE", "published to public Downloads: $fileName -> $uri")
                return uri.toString()
            }
            DownloadLogs.log("ENGINE", "MediaStore publish failed for $fileName — keeping file in legacy location ${fileDir.absolutePath}")
            return try {
                val legacy = File(fileDir, fileName)
                if (workFile.renameTo(legacy)) legacy.absolutePath else workFile.absolutePath
            } catch (_: Exception) {
                workFile.absolutePath
            }
        }
    }

    private fun resolveDestination(context: Context, job: DownloadJob, dir: File, fileName: String): Dest {
        val tree = safTreeUri(context)
        // 2.0-dev2.6: BOTH modes stage in .staging now — the default
        // mode used to download straight into the final file, but
        // the final file is now a MediaStore publish, not a File,
        // so the work file must be a staging partial (DDL Range
        // resume and the HLS remux both need a real File path).
        // The .staging parent is created here so both paths share
        // the guarantee (see the 2.0-dev1.9 fix note below).
        val staging = File(
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"),
            "${job.id}.part",
        ).also { it.parentFile?.mkdirs() }
        val workFile = if (!staging.exists()) {
            // Adopt a legacy resume file: a job paused/failed before
            // this update has its partial at the old final path
            // (File(dir, fileName)) — move it into staging instead
            // of restarting from zero. Same volume, so renameTo.
            val legacy = File(dir, fileName)
            if (tree == null && legacy.exists() && legacy.isFile) {
                if (legacy.renameTo(staging)) {
                    DownloadLogs.logJob(job.id, "ENGINE", "adopted legacy partial ${legacy.name} into staging (${staging.length() / 1024} KB) — resume, not restart")
                    staging
                } else {
                    legacy
                }
            } else {
                staging
            }
        } else {
            staging
        }
        val tempDir = File(dir, ".tmp-${job.id}")
        return Dest(workFile, tempDir, tree, sanitizeFileName(job.animeTitle), dir)
    }

    private fun deletePartials(context: Context?, job: DownloadJob) {
        if (context == null) return
        try {
            val dir = downloadDir(context, job.animeTitle)
            // 2.0-dev2.5: a job can own files under both its
            // requested name and its actual-quality name — drop
            // both, so a renamed partial never lingers.
            val names = mutableSetOf(fileNameFor(job.animeTitle, job.episodeNumber, job.quality, job.audio))
            job.actualQuality?.let { names += fileNameFor(job.animeTitle, job.episodeNumber, it, job.audio) }
            names.forEach { File(dir, it).delete() }
            File(dir, ".tmp-${job.id}").deleteRecursively()
            File(
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"),
                "${job.id}.part",
            ).delete()
        } catch (_: Exception) {
        }
    }

    // 2.0-dev2.5 DEFECT 3 — post-download verification. Reads the
    // delivered file's real video height with MediaExtractor (the
    // same API remux() already uses to count tracks). Returns null
    // when the height can't be read (no video track, corrupt or
    // unreadable file) — callers treat null as "unverifiable" and
    // must NOT fail the job on it.
    private fun probeVideoHeight(file: File): Int? {
        if (!file.exists() || file.length() == 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                return if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    format.getInteger(MediaFormat.KEY_HEIGHT).takeIf { it > 0 }
                } else {
                    null
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun deleteFile(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        try {
            if (path.startsWith("content://")) {
                // 2.0-dev2.6: a MediaStore Uri (authority "media")
                // is deleted through the resolver — that removes
                // both the file and its MediaStore row, so Gallery
                // never keeps a ghost entry. A SAF document Uri
                // still goes through DocumentsContract.
                if (DownloadStorage.isMediaStoreUri(path)) {
                    DownloadStorage.deletePublicFile(context, path)
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path))
                }
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
                    // 2.0-dev2.6: a MediaStore finalPath (authority
                    // "media") gets its sidecar published into the
                    // same public folder; a SAF document Uri still
                    // goes through the chosen tree.
                    if (DownloadStorage.isMediaStoreUri(finalPath)) {
                        DownloadStorage.publishBytes(context, bytes, job.animeTitle, vttName, "text/vtt")
                    } else {
                        val tree = safTreeUri(context) ?: continue
                        val staging = File(
                            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".staging"),
                            "${job.id}-$vttName",
                        )
                        staging.parentFile?.mkdirs()
                        staging.writeBytes(bytes)
                        safCreateFile(context, tree, sanitizeFileName(job.animeTitle), vttName, staging, mimeType = "text/vtt")
                    }
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
