package com.js.anipull

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

// ---- 1.9-dev5.0: in-app updater ---------------------------------------
// Sideloaded app, so Play's in-app update API can't update it —
// this is a self-built mini-updater (AnymeX-style sheet, Nuvio
// channel picker), with no new server, account or dependency:
// a public version JSON on anipull.vercel.app (the repo is public
// now, so no token is baked into the APK anywhere), per channel:
//   { "versionCode": 42, "versionName": "1.9",
//     "apkUrl": "https://github.com/JSxtrme/AniPull-App/releases/download/v1.9/anipull-1.9.apk",
//     "sha256": "<hex, optional>",
//     "features": ["..."], "fixes": ["..."] }
// Only versionCode > installed counts (no downgrade, dev builds
// never offered to Stable users even though their versionCodes
// are higher than stable 1.8's). NO silent auto-update: the final
// step is always Android's own "Do you want to update this app?"
// dialog, and the user taps Update there.
// If updates.json doesn't exist yet (or has no entry for the
// channel), the check honestly reports "no update" — nothing is
// faked, and JS tests the real flow against the next published
// build.

// 1.9-dev5.4: the version JSON moved from the website repo to
// the app repo itself, so one push to dev/1.9 carries both the
// code and the update entry — no second repo, no Vercel redeploy
// wait. Primary source is the raw file on dev/1.9; the old Vercel
// URL is kept ONLY as a fallback while 5.3-and-earlier builds
// (which fetch Vercel only) are still in use. 5.4+ fetches raw
// first and falls back to Vercel if raw fails or 404s.
const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/JSxtrme/AniPull-App/dev/1.9/updates.json"
const val UPDATE_JSON_URL_LEGACY = "https://anipull.vercel.app/updates.json"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val features: List<String>,
    val fixes: List<String>,
)

sealed class UpdateCheckResult {
    object UpToDate : UpdateCheckResult()
    class Available(val info: UpdateInfo) : UpdateCheckResult()
    object NoNetwork : UpdateCheckResult()
    class Failed(val message: String) : UpdateCheckResult()
}

fun installedVersionCode(context: Context): Int = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
    else @Suppress("DEPRECATION") info.versionCode
} catch (_: Exception) { 0 }

fun installedVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: Exception) { "" }

/** Update channel. Default follows the downloaded build type on
 *  first launch (pref not yet set): "-dev" in the versionName ->
 *  Dev, clean versionName (1.8 / 1.9 / 2.0) -> Stable. Sticky
 *  after that unless changed in About — so a dev user updating
 *  to stable via the updater stays on the Dev track. */
fun updateChannel(context: Context): String {
    val prefs = context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE)
    return prefs.getString("update_channel", null) ?: if ("-dev" in installedVersionName(context)) "dev" else "stable"
}

fun setUpdateChannel(context: Context, channel: String) {
    context.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE).edit().putString("update_channel", channel).apply()
}

/** Fetches the version JSON from one URL. Ok(text) on HTTP
 *  200, NotFound on 404, ServerError on any other code,
 *  NetworkError when the request itself fails. */
private sealed interface FetchResult {
    class Ok(val text: String) : FetchResult
    object NotFound : FetchResult
    class ServerError(val code: Int) : FetchResult
    object NetworkError : FetchResult
}

private fun fetchUpdateJson(url: String): FetchResult {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        when (val code = conn.responseCode) {
            200 -> FetchResult.Ok(conn.inputStream.bufferedReader().use { it.readText() })
            404 -> FetchResult.NotFound
            else -> FetchResult.ServerError(code)
        }
    } catch (_: UnknownHostException) {
        FetchResult.NetworkError
    } catch (_: IOException) {
        FetchResult.NetworkError
    } catch (_: Exception) {
        FetchResult.ServerError(-1)
    } finally {
        conn?.disconnect()
    }
}

/** Parses one fetched version JSON for the given channel. */
private fun parseUpdateJson(text: String, channel: String, context: Context): UpdateCheckResult {
    val root = JSONObject(text)
    if (!root.has(channel)) return UpdateCheckResult.UpToDate
    val obj = root.getJSONObject(channel)
    val versionCode = obj.optInt("versionCode", 0)
    if (versionCode <= 0 || versionCode <= installedVersionCode(context)) return UpdateCheckResult.UpToDate
    val apkUrl = obj.optString("apkUrl", "")
    if (apkUrl.isBlank()) return UpdateCheckResult.UpToDate
    fun strList(key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }
    var features = strList("features")
    var fixes = strList("fixes")
    if (features.isEmpty() && fixes.isEmpty()) features = strList("changelog")
    return UpdateCheckResult.Available(
        UpdateInfo(
            versionCode = versionCode,
            versionName = obj.optString("versionName", ""),
            apkUrl = apkUrl,
            sha256 = obj.optString("sha256", null)?.takeIf { it.isNotBlank() },
            features = features,
            fixes = fixes,
        )
    )
}

suspend fun checkForUpdate(context: Context, channel: String = updateChannel(context)): UpdateCheckResult = withContext(Dispatchers.IO) {
    try {
        // 1.9-dev5.4: try the app-repo raw JSON first, fall back
        // to the Vercel copy if raw fails or 404s. A 404 on BOTH
        // means the JSON isn't published yet (no update to offer).
        // Any other non-200 is a server problem, not "up to date".
        when (val primary = fetchUpdateJson(UPDATE_JSON_URL)) {
            is FetchResult.Ok -> return@withContext parseUpdateJson(primary.text, channel, context)
            is FetchResult.ServerError -> {
                when (val legacy = fetchUpdateJson(UPDATE_JSON_URL_LEGACY)) {
                    is FetchResult.Ok -> return@withContext parseUpdateJson(legacy.text, channel, context)
                    is FetchResult.NotFound -> return@withContext UpdateCheckResult.UpToDate
                    is FetchResult.ServerError -> return@withContext UpdateCheckResult.Failed("Server returned HTTP ${primary.code}")
                    is FetchResult.NetworkError -> return@withContext UpdateCheckResult.NoNetwork
                }
            }
            is FetchResult.NotFound -> {
                when (val legacy = fetchUpdateJson(UPDATE_JSON_URL_LEGACY)) {
                    is FetchResult.Ok -> return@withContext parseUpdateJson(legacy.text, channel, context)
                    is FetchResult.NotFound -> return@withContext UpdateCheckResult.UpToDate
                    is FetchResult.ServerError -> return@withContext UpdateCheckResult.Failed("Server returned HTTP ${legacy.code}")
                    is FetchResult.NetworkError -> return@withContext UpdateCheckResult.NoNetwork
                }
            }
            is FetchResult.NetworkError -> {
                when (val legacy = fetchUpdateJson(UPDATE_JSON_URL_LEGACY)) {
                    is FetchResult.Ok -> return@withContext parseUpdateJson(legacy.text, channel, context)
                    else -> return@withContext UpdateCheckResult.NoNetwork
                }
            }
        }
    } catch (_: UnknownHostException) { UpdateCheckResult.NoNetwork }
    catch (_: IOException) { UpdateCheckResult.NoNetwork }
    catch (e: Exception) { UpdateCheckResult.Failed(e.message ?: "Check failed") }
}

/** Downloads the update APK to cacheDir/updates/, reporting
 *  progress. Verifies the SHA-256 while downloading when the
 *  JSON provides one — a mismatch deletes the file and returns
 *  null. Returns the file on success, null on any failure.
 *  onProgress is called from the IO thread; Compose state writes
 *  from there are safe (snapshot state is thread-safe).
 *  2.0-dev2.12: onPhase reports the connection setup honestly
 *  ("Contacting GitHub…" / "Redirecting to download server…" /
 *  "Starting download…") instead of one flat "Connecting…", and
 *  a failed attempt is retried ONCE — a transient stall used to
 *  be a dead end that needed a second tap. The download prefers
 *  the URL pre-resolved when the sheet opened (see
 *  resolveApkAssetUrl), so the tap connects straight to the
 *  asset host instead of waiting on GitHub's slow 302. */
suspend fun downloadUpdate(
    context: Context,
    info: UpdateInfo,
    onPhase: (String) -> Unit = {},
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): File? = withContext(Dispatchers.IO) {
    val out = downloadedUpdateFile(context)
    repeat(2) { attempt ->
        try {
            downloadUpdateOnce(context, info, out, onPhase, onProgress)?.let { return@withContext it }
            // Null = HTTP error or SHA mismatch: retrying the
            // same URL would fail the same way, so stop here.
            return@withContext null
        } catch (e: Exception) {
            // Connect/read failure mid-flight: delete the partial
            // file and try once more from scratch.
            out.delete()
            if (attempt == 0) onPhase("Retrying…")
        }
    }
    null
}

/** One download attempt. Throws on any IO failure (the caller
 *  retries once); returns null on an HTTP error or a SHA
 *  mismatch. Redirects are followed manually (max 5) so the
 *  phase callback can say "Redirecting…" instead of the UI
 *  sitting on one word while HttpURLConnection silently hops
 *  hosts — the github.com link 302s to
 *  release-assets.githubusercontent.com, and that hop is the
 *  slowest part of the whole update (~1 s of GitHub server time
 *  minting the signed URL, measured 2026-11-11). */
private fun downloadUpdateOnce(
    context: Context,
    info: UpdateInfo,
    out: File,
    onPhase: (String) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): File? {
    val digest = MessageDigest.getInstance("SHA-256")
    File(context.cacheDir, "updates").apply { mkdirs() }
    // Pre-resolved asset URL (sheet open) if still fresh,
    // otherwise the github.com link — the redirect is then
    // followed live below, exactly as before.
    val startUrl = cachedResolvedApkUrl(info.apkUrl) ?: info.apkUrl
    var conn: HttpURLConnection? = null
    try {
        onPhase(if (startUrl == info.apkUrl) "Contacting GitHub…" else "Connecting to download server…")
        var url = URL(startUrl)
        var redirects = 0
        while (true) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 30000
            }
            conn = c
            val code = c.responseCode
            if (code in 301..308) {
                val location = c.getHeaderField("Location")
                if (location.isNullOrBlank() || redirects >= 5) return null
                onPhase("Redirecting to download server…")
                c.disconnect()
                conn = null
                url = URL(url, location)
                redirects++
                continue
            }
            if (code != 200) return null
            break
        }
        val finalConn = conn ?: return null
        val total = finalConn.contentLengthLong
        onPhase("Starting download…")
        var downloaded = 0L
        finalConn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
        finalConn.disconnect()
        conn = null
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && !hex.equals(info.sha256, ignoreCase = true)) {
            out.delete()
            return null
        }
        return out
    } catch (e: Exception) {
        conn?.disconnect()
        out.delete()
        throw e
    }
}

// ---- 2.0-dev2.12: pre-resolved asset URL --------------------------
// A github.com release link 302s to a signed
// release-assets.githubusercontent.com URL (~1 h expiry, ~1 s
// of GitHub server time to mint it). Resolving that redirect
// when the update sheet opens moves the slowest hop out of the
// Download tap's path: the tap then connects straight to the
// asset host. In-memory only, 30 min TTL (half the signed-URL
// life), keyed by the github.com URL. A stale or failed resolve
// just means the download falls back to the original link and
// follows the redirect live — never worse than before.
private var resolvedApkUrl: String? = null
private var resolvedApkUrlFor: String? = null
private var resolvedApkUrlAtMs: Long = 0L
private const val RESOLVED_APK_URL_TTL_MS = 30 * 60 * 1000L

private fun cachedResolvedApkUrl(apkUrl: String): String? {
    if (resolvedApkUrlFor != apkUrl) return null
    if (System.currentTimeMillis() - resolvedApkUrlAtMs > RESOLVED_APK_URL_TTL_MS) return null
    return resolvedApkUrl
}

/** Follows the github.com release redirect once (headers only,
 *  body never read) and returns the final asset URL, caching it.
 *  Null when the link doesn't redirect or the request fails —
 *  the caller then uses the original URL. */
suspend fun resolveApkAssetUrl(apkUrl: String): String? = withContext(Dispatchers.IO) {
    cachedResolvedApkUrl(apkUrl)?.let { return@withContext it }
    var conn: HttpURLConnection? = null
    try {
        conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 8000
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location")
        val resolved = if (code in 301..308 && !location.isNullOrBlank()) {
            URL(URL(apkUrl), location).toString()
        } else null
        if (resolved != null) {
            resolvedApkUrl = resolved
            resolvedApkUrlFor = apkUrl
            resolvedApkUrlAtMs = System.currentTimeMillis()
        }
        resolved
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

/** The single file a downloaded update APK lives in
 *  (cacheDir/updates/anipull-update.apk). One file, one
 *  version at a time — a new download overwrites it. */
fun downloadedUpdateFile(context: Context): File =
    File(File(context.cacheDir, "updates"), "anipull-update.apk")

/** SHA-256 hex of a file, or null if it can't be read. */
fun sha256Of(file: File): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (_: Exception) { null }

/** 1.9-dev5.2: is there already a downloaded APK on disk for
 *  THIS exact update (same version, SHA-256 matches the JSON)?
 *  Returns it if yes, null if no. This is what stops the
 *  updater downloading the same APK twice: after the
 *  permission screen, after a dismissed sheet, even after
 *  the app was killed while the user was in settings — the
 *  file is still there and still verified, so the next tap
 *  installs it instead of re-downloading it. Without a SHA
 *  in the JSON there is nothing to verify identity against,
 *  so no cross-session reuse (the in-sheet pending file,
 *  whose origin is known, is still reused — see
 *  UpdateSheet). A mismatched file is deleted, never
 *  installed. */
fun usableDownloadedUpdate(context: Context, info: UpdateInfo): File? {
    val expected = info.sha256 ?: return null
    val file = downloadedUpdateFile(context)
    if (!file.exists() || file.length() <= 0) return null
    return if (sha256Of(file)?.equals(expected, ignoreCase = true) == true) file
    else { file.delete(); null }
}

/** Fires Android's system install dialog for the downloaded APK
 *  (FileProvider + ACTION_VIEW). Returns true if the dialog was
 *  fired. Returns false if Anipull isn't allowed to install
 *  unknown apps yet — the caller should open the settings
 *  screen (openUnknownSourcesSettings) and tell the user to
 *  allow it, then retry. REQUEST_INSTALL_PACKAGES is declared
 *  in the manifest for this. */
fun installUpdate(context: Context, apk: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return true
}

/** Intent for the per-app "install unknown apps" screen.
 *  1.9-dev5.2: deliberately NO FLAG_ACTIVITY_NEW_TASK here —
 *  UpdateSheet launches it through an ActivityResultLauncher,
 *  and a NEW_TASK flag would break result delivery (the
 *  return callback would never fire). */
fun unknownSourcesIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
    }

fun openUnknownSourcesSettings(context: Context) {
    context.startActivity(unknownSourcesIntent(context).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

// ---- Share ------------------------------------------------------------
// Plain Android share sheet, no account, no tracking.

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

/** Locked share message (JS, 2026-11-06) — repo link, now public. */
const val SHARE_ANIPULL_MESSAGE =
    "Anipull — Download anime, no streaming, no tracking, upto 1080p, sub + dub. Check it out now\nhttps://github.com/JSxtrme/AniPull-App"

fun shareAnipull(context: Context) = shareText(context, SHARE_ANIPULL_MESSAGE)
