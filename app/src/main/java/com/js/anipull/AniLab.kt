package com.js.anipull

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// AniLab metadata API, verified live 2026-11-02, no key:
//   https://anilab2.amdapi.click/api/home
//   https://anilab2.amdapi.click/api/search?query=
//   https://anilab2.amdapi.click/api/post?id=
// /home and /search return posts with id + poster only, so titles come
// from /post per item. /post gives title, type, overview, genres, score,
// status, premiered and seasons. It does NOT give episode counts, so
// live titles carry episodes = null and the detail screen says so
// honestly instead of inventing a count.

data class ApiTitle(
    val id: Long,
    val title: String,
    val poster: String?,
    val type: String?,
    val year: String?,
    val score: String?,
    val overview: String?,
    val genres: String?,
    val status: String?,
    val episodes: Int?,
    val seasons: List<String> = emptyList(),
)

data class HomeSection(val name: String, val titles: List<ApiTitle>)
data class HomeData(val sections: List<HomeSection>, val featured: ApiTitle?)

fun demoToApi(t: DemoTitle): ApiTitle = ApiTitle(
    id = -t.title.hashCode().toLong(),
    title = t.title,
    poster = null,
    type = t.format,
    year = t.year,
    score = null,
    overview = null,
    genres = null,
    status = null,
    episodes = t.episodes,
)

private fun getText(urlStr: String): String? = try {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Anipull/1.0")
        setRequestProperty("Accept", "application/json")
    }
    val code = conn.responseCode
    if (code != 200) {
        null
    } else {
        conn.inputStream.bufferedReader().use { it.readText() }
    }
} catch (_: Exception) {
    null
}

private fun yearOf(premiered: String?): String? {
    if (premiered.isNullOrBlank()) return null
    return Regex("""\d{4}""").find(premiered)?.value
}

private fun cleanSectionName(raw: String): String =
    raw.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()

private fun postToApi(obj: JSONObject): ApiTitle {
    val seasons = mutableListOf<String>()
    val arr = obj.optJSONArray("seasons")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { seasons += it }
        }
    }
    return ApiTitle(
        id = obj.optLong("id", 0),
        title = obj.optString("title", "").ifBlank { "Untitled" },
        poster = obj.optString("poster").takeIf { it.isNotBlank() },
        type = obj.optString("type").takeIf { it.isNotBlank() },
        year = yearOf(obj.optString("premiered").takeIf { it.isNotBlank() }),
        score = obj.optString("score").takeIf { it.isNotBlank() && it != "null" },
        overview = obj.optString("overview").takeIf { it.isNotBlank() },
        genres = obj.optString("genres").takeIf { it.isNotBlank() },
        status = obj.optString("status").takeIf { it.isNotBlank() },
        episodes = null,
        seasons = seasons,
    )
}

private suspend fun fetchPost(id: Long): ApiTitle? = withContext(Dispatchers.IO) {
    val text = getText("https://anilab2.amdapi.click/api/post?id=$id") ?: return@withContext null
    try {
        postToApi(JSONObject(text))
    } catch (_: Exception) {
        null
    }
}

private suspend fun enrich(posts: List<Pair<Long, String?>>, limit: Int): List<ApiTitle> =
    coroutineScope {
        posts.take(limit).map { (id, poster) ->
            async {
                val full = fetchPost(id)
                full ?: ApiTitle(
                    id = id, title = "", poster = poster,
                    type = null, year = null, score = null, overview = null,
                    genres = null, status = null, episodes = null,
                )
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }
            .filter { it.title.isNotBlank() }
    }


// --- Disk cache: last good live home, so the NEXT launch is instant.
// Saved as one JSON string in SharedPreferences after every successful
// refresh; read synchronously on launch, before any network call.
// Posters are cached separately (see loadPoster) so cached rows render
// with real posters, not placeholders, on that instant load.
private fun ApiTitle.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("poster", poster ?: JSONObject.NULL)
    put("type", type ?: JSONObject.NULL); put("year", year ?: JSONObject.NULL)
    put("score", score ?: JSONObject.NULL); put("overview", overview ?: JSONObject.NULL)
    put("genres", genres ?: JSONObject.NULL); put("status", status ?: JSONObject.NULL)
    put("episodes", episodes ?: JSONObject.NULL)
    put("seasons", JSONArray(seasons))
}
private fun JSONObject.toApiTitle(): ApiTitle {
    val seasons = mutableListOf<String>()
    val arr = optJSONArray("seasons")
    if (arr != null) for (i in 0 until arr.length()) arr.optString(i)?.let { seasons += it }
    return ApiTitle(
        id = optLong("id", 0), title = optString("title", ""),
        poster = optString("poster").takeIf { it.isNotBlank() },
        type = optString("type").takeIf { it.isNotBlank() },
        year = optString("year").takeIf { it.isNotBlank() },
        score = optString("score").takeIf { it.isNotBlank() && it != "null" },
        overview = optString("overview").takeIf { it.isNotBlank() },
        genres = optString("genres").takeIf { it.isNotBlank() },
        status = optString("status").takeIf { it.isNotBlank() },
        episodes = if (isNull("episodes")) null else optInt("episodes"),
        seasons = seasons,
    )
}
fun saveHomeCache(context: Context, data: HomeData) {
    try {
        val root = JSONObject()
        root.put("featured", data.featured?.toJson() ?: JSONObject.NULL)
        val arr = JSONArray()
        data.sections.forEach { sec ->
            val o = JSONObject(); o.put("name", sec.name)
            val posts = JSONArray(); sec.titles.forEach { posts.put(it.toJson()) }
            o.put("titles", posts); arr.put(o)
        }
        root.put("sections", arr)
        context.getSharedPreferences("anipull_cache", Context.MODE_PRIVATE)
            .edit().putString("home_json", root.toString()).apply()
    } catch (_: Exception) { }
}
fun loadHomeCache(context: Context): HomeData? {
    return try {
        val text = context.getSharedPreferences("anipull_cache", Context.MODE_PRIVATE)
            .getString("home_json", null) ?: return null
        val root = JSONObject(text)
        val featured = if (root.isNull("featured")) null else root.optJSONObject("featured")?.toApiTitle()
        val sections = mutableListOf<HomeSection>()
        val arr = root.optJSONArray("sections") ?: return null
        for (i in 0 until arr.length()) {
            val sec = arr.optJSONObject(i) ?: continue
            val titles = mutableListOf<ApiTitle>()
            val posts = sec.optJSONArray("titles") ?: continue
            for (j in 0 until posts.length()) posts.optJSONObject(j)?.let { titles += it.toApiTitle() }
            if (titles.isNotEmpty()) sections += HomeSection(sec.optString("name"), titles)
        }
        if (sections.isEmpty() && featured == null) null else HomeData(sections, featured)
    } catch (_: Exception) { null }
}

// --- Library (Saved): favourites for later downloading, stored separately
// from the home cache, so "Clear home cache" in Settings never wipes
// them. Same JSON shape as the home cache, one array in its own prefs file.
fun loadLibraryTitles(context: Context): List<ApiTitle> {
    return try {
        val text = context.getSharedPreferences("anipull_library", Context.MODE_PRIVATE)
            .getString("saved_json", null) ?: return emptyList()
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toApiTitle() }
    } catch (_: Exception) { emptyList() }
}
fun saveLibraryTitles(context: Context, titles: List<ApiTitle>) {
    try {
        val arr = JSONArray()
        titles.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences("anipull_library", Context.MODE_PRIVATE)
            .edit().putString("saved_json", arr.toString()).apply()
    } catch (_: Exception) { }
}

suspend fun fetchHome(): HomeData? {
    val sections = mutableListOf<HomeSection>()
    var featured: ApiTitle? = null
    fetchHomeProgressive(
        onSection = { _, sec -> synchronized(sections) { sections += sec } },
        onFeatured = { featured = it },
    )
    val wanted = listOf("Spotlight", "Trending", "Most Popular", "Top Airing", "Most Watched", "Most Favorite", "Top 10 Today", "Top 10 Week", "Top 10 Month")
    val ordered = sections.sortedBy { wanted.indexOf(it.name) }
    return if (ordered.isEmpty() && featured == null) null else HomeData(ordered, featured)
}

// Progressive + parallel: all wanted sections (and featured) are fetched
// concurrently, and each section is handed to the UI the moment ITS 10
// details land — the first row appears in ~1s instead of waiting for all
// 50 detail calls to finish, which was the old sequential bottleneck.
suspend fun fetchHomeProgressive(
    onSection: suspend (Int, HomeSection) -> Unit,
    onFeatured: suspend (ApiTitle) -> Unit,
) = withContext(Dispatchers.IO) {
    val text = getText("https://anilab2.amdapi.click/api/home") ?: return@withContext
    try {
        val root = JSONObject(text)
        val featuredObj = root.optJSONObject("featured")
        val sectionsJson = root.optJSONArray("sections") ?: return@withContext
        val wanted = listOf("Spotlight", "Trending", "Most Popular", "Top Airing", "Most Watched", "Most Favorite", "Top 10 Today", "Top 10 Week", "Top 10 Month")
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        // coroutineScope so this returns only after every section finished,
        // while callbacks already fired progressively inside each launch.
        coroutineScope {
            for (i in 0 until sectionsJson.length()) {
                val sec = sectionsJson.optJSONObject(i) ?: continue
                val name = cleanSectionName(sec.optString("name"))
                val idx = wanted.indexOf(name)
                if (idx < 0) continue
                val postsArr = sec.optJSONArray("posts") ?: continue
                val posts = (0 until postsArr.length()).mapNotNull { j ->
                    val pp = postsArr.optJSONObject(j) ?: return@mapNotNull null
                    pp.optLong("id", 0).takeIf { it != 0L }?.let { it to pp.optString("poster").takeIf { x -> x.isNotBlank() } }
                }
                jobs += launch {
                    val titles = enrich(posts, 10)
                    if (titles.isNotEmpty()) onSection(idx, HomeSection(name, titles))
                }
            }
            val fid = featuredObj?.optLong("id", 0) ?: 0L
            if (fid != 0L) {
                jobs += launch {
                    fetchPost(fid)?.let { onFeatured(it) }
                }
            }
        }
    } catch (_: Exception) { }
}

// Genre catalog: /api/category returns the same id+poster posts as
// search, so enrich the first 10 the same way. IDs verified live
// 2026-11-02 from /api/categories: Action 1, Comedy 5, Fantasy 4,
// Romance 14 (plus Drama 2, Sci-Fi 6, Slice of Life 9, Supernatural 10).
suspend fun fetchCategory(id: Int, name: String): HomeSection? = withContext(Dispatchers.IO) {
    val text = getText("https://anilab2.amdapi.click/api/category?id=$id") ?: return@withContext null
    try {
        val root = JSONObject(text)
        val arr = root.optJSONArray("posts") ?: return@withContext null
        val posts = (0 until arr.length()).mapNotNull { j ->
            val pp = arr.optJSONObject(j) ?: return@mapNotNull null
            pp.optLong("id", 0).takeIf { it != 0L }?.let { it to pp.optString("poster").takeIf { x -> x.isNotBlank() } }
        }
        val titles = enrich(posts, 10)
        if (titles.isEmpty()) null else HomeSection(name, titles)
    } catch (_: Exception) { null }
}

// Paged category, for the Explore grid (1.9-dev4.8): /api/category
// takes ?page=, verified live 2026-11-02 for page=2 returning a
// distinct 30 posts. Enrich the first 10, like the genre rows.
suspend fun fetchCategoryPaged(id: Int, page: Int): HomeSection? = withContext(Dispatchers.IO) {
    val text = getText("https://anilab2.amdapi.click/api/category?id=$id&page=$page") ?: return@withContext null
    try {
        val root = JSONObject(text)
        val arr = root.optJSONArray("posts") ?: return@withContext null
        val posts = (0 until arr.length()).mapNotNull { j ->
            val pp = arr.optJSONObject(j) ?: return@mapNotNull null
            pp.optLong("id", 0).takeIf { it != 0L }?.let { it to pp.optString("poster").takeIf { x -> x.isNotBlank() } }
        }
        val titles = enrich(posts, 10)
        if (titles.isEmpty()) null else HomeSection("Explore", titles)
    } catch (_: Exception) { null }
}

suspend fun searchAniLab(query: String): List<ApiTitle> = withContext(Dispatchers.IO) {
    val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    val text = getText("https://anilab2.amdapi.click/api/search?query=$q") ?: return@withContext emptyList()
    try {
        val root = JSONObject(text)
        val arr = root.optJSONArray("posts") ?: return@withContext emptyList()
        val posts = (0 until arr.length()).mapNotNull { j ->
            val p = arr.optJSONObject(j) ?: return@mapNotNull null
            p.optLong("id", 0).takeIf { it != 0L }?.let { it to p.optString("poster").takeIf { s -> s.isNotBlank() } }
        }
        enrich(posts, 12)
    } catch (_: Exception) {
        emptyList()
    }
}

// --- Tiny poster loader: no Coil dependency, so no new download risk.
// Small AniLab posters only, in-memory cache, decoded off the main thread.
// 1.9-dev4.8.3: size-aware decode, Coil-style without Coil.
// The cache key includes the target width, because the same poster
// shows at 118dp in a row and at full width in the carousel —
// sharing one bitmap would either upscale the row or waste a
// full-size texture on every grid card during a fast scroll.
object PosterCache {
    // 1.9-dev4.8.4: byte-capped, not count-capped. 200 bitmaps at
    // ~350x525 ARGB was ~150MB, and that memory pressure during
    // a fast scroll (dozens of new posters at once) GC-stuttered.
    // Cap ~50MB, evict oldest first.
    private val map = LinkedHashMap<String, Bitmap>()
    private var totalBytes = 0L
    fun get(url: String, targetWidthPx: Int = 0): Bitmap? = synchronized(map) { map["$url@$targetWidthPx"] }
    fun put(url: String, bmp: Bitmap, targetWidthPx: Int = 0) = synchronized(map) {
        val key = "$url@$targetWidthPx"
        val bytes = (bmp.width * bmp.height * 4).toLong()
        map[key]?.let { totalBytes -= (it.width * it.height * 4).toLong() }
        map[key] = bmp; totalBytes += bytes
        while (totalBytes > 50L * 1024 * 1024 && map.size > 1) {
            val first = map.keys.first(); val old = map.remove(first)!!; totalBytes -= (old.width * old.height * 4).toLong()
        }
    }
}

// 1.9-dev4.8.4: at most 4 poster decodes at once. Unbounded IO
// meant a fast scroll launched dozens of decodes simultaneously,
// all competing for CPU + GPU upload in the same frames.
private val posterLoadSemaphore = kotlinx.coroutines.sync.Semaphore(4)

private fun sampleSizeFor(width: Int, targetWidthPx: Int): Int {
    if (targetWidthPx <= 0 || width <= targetWidthPx) return 1
    var s = 1
    while (width / (s * 2) >= targetWidthPx) s *= 2
    return s
}

suspend fun loadPoster(url: String, context: Context? = null, targetWidthPx: Int = 0): Bitmap? {
    PosterCache.get(url, targetWidthPx)?.let { return it }
    return posterLoadSemaphore.withPermit {
    withContext(Dispatchers.IO) {
        try {
            if (context != null) {
                val dir = java.io.File(context.cacheDir, "posters").apply { mkdirs() }
                val file = java.io.File(dir, "${url.hashCode()}.jpg")
                if (file.exists()) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx); inPreferredConfig = Bitmap.Config.HARDWARE }
                    (BitmapFactory.decodeFile(file.absolutePath, opts)
                        ?: BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx) }))?.let {
                        PosterCache.put(url, it, targetWidthPx); return@withContext it
                    }
                }
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Anipull/1.0")
            }
            if (conn.responseCode != 200) return@withContext null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx); inPreferredConfig = Bitmap.Config.HARDWARE }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx) })
                ?: return@withContext null
            if (context != null) {
                try {
                    val dir = java.io.File(context.cacheDir, "posters").apply { mkdirs() }
                    java.io.File(dir, "${url.hashCode()}.jpg").writeBytes(bytes)
                } catch (_: Exception) { }
            }
            PosterCache.put(url, bmp, targetWidthPx)
            bmp
        } catch (_: Exception) {
            null
        }
    }
    }
}
