package com.js.anipull

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// 2.0-dev2.6 — PUBLIC DOWNLOADS STORAGE.
//
// Why MediaStore, not a raw path: on Android 10+ (scoped storage)
// an app may not write to /storage/emulated/0/Download directly,
// and files under getExternalFilesDir() (Android/data/…) are
// excluded from media scanning, so completed videos never showed
// in Gallery. Inserting into MediaStore.Downloads with
// RELATIVE_PATH "Download/Anipull/<Title>" is the sanctioned
// Android 10+ route: no storage permission needed for the app's
// own entries, the file lands in the real public Downloads folder,
// and MediaStore indexes it, so Gallery and Files pick it up.
// IS_PENDING=1 while the bytes copy, cleared to 0 when done, so
// no partial file is ever visible.
//
// Why staging-then-publish, not direct-to-MediaStore: resume.
// DDL resumes via HTTP Range on a File, and HLS remuxes via
// MediaMuxer, which needs a File path — neither works against a
// MediaStore Uri. So downloads still land in the app-private
// .staging partial (pause/resume untouched), and publish happens
// only AFTER the dev2.5 quality probe passes, so a quality-failed
// job never creates a MediaStore row at all.
//
// SAF mode is untouched: a user-picked custom folder still wins
// over the public Downloads default.

object DownloadStorage {
    const val PUBLIC_FOLDER_DISPLAY = "Download/Anipull"

    fun relativePathFor(animeTitle: String): String =
        "$PUBLIC_FOLDER_DISPLAY/${DownloadEngine.sanitizeFileName(animeTitle)}"

    fun isMediaStoreUri(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            Uri.parse(path).authority == "media"
        } catch (_: Exception) {
            false
        }
    }

    // Old (pre-2.6) home of completed downloads — app-private, invisible
    // to Gallery. Kept for migration + resume adoption, never written to
    // for new completions (unless publish falls back — see engine).
    fun legacyDownloadDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        return File(base, "Anipull")
    }

    // How many *.mp4 files are still sitting in the old private dir
    // (partials, orphans, or not-yet-migrated completions). Shown
    // honestly in Settings > Storage; 0 is the steady state.
    fun countLegacyVideoFiles(context: Context): Int {
        return try {
            val root = legacyDownloadDir(context)
            if (!root.exists()) return 0
            var count = 0
            root.walkTopDown().forEach {
                if (it.isFile && it.name.endsWith(".mp4", ignoreCase = true)) count++
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    // Find a previously published file by its display name (the file
    // name already embeds the title, so it is effectively unique).
    // Used when a persisted MediaStore Uri went stale (user deleted
    // the file in Files, or the row was recreated).
    fun findPublicFile(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            resolver.query(
                uri, projection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName), null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(pathCol) ?: ""
                    if (rel.startsWith(PUBLIC_FOLDER_DISPLAY)) {
                        return ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Publish raw bytes (subtitle sidecars) into the same public
    // folder. Separated from publishVideoFile so a multi-hundred-MB
    // video is streamed, not held in memory.
    fun publishBytes(context: Context, bytes: ByteArray, animeTitle: String, fileName: String, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (bytes.isEmpty()) return null
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            deleteExisting(context, animeTitle, fileName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(animeTitle) + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    ?: run { deleteRow(context, uri); return null }
                val pendingOff = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, pendingOff, null, null) < 1) {
                    deleteRow(context, uri)
                    return null
                }
                uri
            } catch (_: Exception) {
                deleteRow(context, uri)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Streamed publish for a finished video file. Same contract as
    // publishBytes, but copies in 64 KB chunks and verifies the
    // byte count before clearing IS_PENDING. Returns the MediaStore
    // Uri on success, null on any failure (caller keeps the staged
    // file and logs it — never fails the job over a publish). The
    // source file is NOT deleted here; the caller deletes it once
    // the Uri is persisted.
    fun publishVideoFile(context: Context, source: File, animeTitle: String, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!source.exists() || source.length() == 0L) return null
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            deleteExisting(context, animeTitle, fileName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(animeTitle) + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                var copied = 0L
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                        }
                    }
                } ?: run { deleteRow(context, uri); return null }
                if (copied != source.length()) {
                    deleteRow(context, uri)
                    return null
                }
                val pendingOff = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, pendingOff, null, null) < 1) {
                    deleteRow(context, uri)
                    return null
                }
                uri
            } catch (_: Exception) {
                deleteRow(context, uri)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // 2.0-dev2.6 SIZE: the MediaStore SIZE column for a published
    // file — the ground-truth size for a completed row. Null when
    // the row is gone or the size can't be read; never a guess.
    fun publishedSize(context: Context, uriString: String): Long? {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0).takeIf { it > 0 }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deletePublicFile(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteRow(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    // A re-download of the same episode must replace, not duplicate:
    // drop any existing row with the same name in our folder first.
    private fun deleteExisting(context: Context, animeTitle: String, fileName: String) {
        try {
            findPublicFile(context, fileName)?.let { deleteRow(context, it) }
        } catch (_: Exception) {
        }
    }
}
