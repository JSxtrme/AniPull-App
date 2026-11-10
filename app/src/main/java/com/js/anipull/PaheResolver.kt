package com.js.anipull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

// 2.0-dev1.0 ENGINE — AnimePahe episode source.
// Pure parsing + HttpURLConnection, per PAHE-RESOLVER-RESEARCH.md §§2-3.
// DDL-first (pahe.win -> Kwik form -> direct mp4), HLS fallback
// (Kwik .m3u8). Pahe is HARD-subbed: no subtitle handling anywhere.
//
// NO Cloudflare clearance code lives here or anywhere in the engine:
// this class takes an already-obtained cf_clearance value as a plain
// String and only ever replays it as a Cookie header. With a null
// cookie it returns SourcePending / Failed WITHOUT any network call.

internal class PaheEpisodeSource(
    private val clearanceCookie: String?,
    private val clearanceUa: String? = null,
) : EpisodeSource {
    override val sourceName: String = "AnimePahe"
    override val serverNumber: Int = 3
    override val serverDescription: String = ServerRegistry.description(3)

    companion object {
        // Rotatable list, never a single hardcoded host (.pw is the
        // live canonical host as of the 2026-11-06 probe; .com/.org
        // 301 to it, .ru still seen in other tools).
        val PAHE_HOSTS = listOf("animepahe.pw", "animepahe.com", "animepahe.org", "animepahe.ru")
        val KWIK_TLDS = listOf("cx", "gg", "si", "me", "net", "in", "cc")
        private const val PACE_MS = 400L // PaheBatcher spacing; hammering one IP gets 429'd.
    }

    private var lastCallMs = 0L

    private suspend fun pace() {
        val wait = PACE_MS - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

    private fun cookieHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        // Referer: Pahe's API expects a browser-like request.
        clearanceCookie?.takeIf { it.isNotBlank() }?.let { raw ->
            headers["Cookie"] = if (raw.contains("cf_clearance=")) raw else "cf_clearance=$raw"
        }
        // Replay with the exact UA that minted the clearance — a
        // clearance replayed under a different UA is void. CronetHttp
        // applies this map after its default UA, so it overrides it.
        clearanceUa?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        headers.putAll(extra)
        return headers
    }

    private suspend fun paheGet(path: String): HttpResponse? = withContext(Dispatchers.IO) {
        // Try hosts in order; a 403/empty body on one host is not
        // proof the API is down — the next host may answer.
        // 2.0-dev1.5: return the LAST response a host actually gave
        // (even a 403), not null, when every host answered with
        // something — the caller can then tell a rejected session
        // (challenge HTML / 403) from no answer at all (network
        // down). Null now means only "no host answered".
        var lastResp: HttpResponse? = null
        for (host in PAHE_HOSTS) {
            pace()
            try {
                val resp = CronetHttp.requestWithRetry(
                    url = "https://$host$path",
                    headers = cookieHeaders(mapOf("Referer" to "https://$host/")),
                    timeoutMs = 8000,
                    followRedirects = true,
                )
                if (resp.code == 200 && resp.bytes.isNotEmpty()) return@withContext resp
                lastResp = resp
            } catch (_: Exception) {
                // Next host.
            }
        }
        lastResp
    }

    private fun normalize(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    override suspend fun loadEpisodes(title: ApiTitle): EpisodeResult {
        if (clearanceCookie.isNullOrBlank()) {
            DownloadLogs.log("RESOLVE", "Server 3: no clearance — SourcePending, no network call")
            return EpisodeResult.SourcePending
        }
        return try {
            val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
            val searchResp = paheGet("/api?m=search&l=8&q=$q")
                ?: return EpisodeResult.Failed("The source did not answer — it may be down or the session expired.")
            // 2.0-dev1.4: a challenge page is HTML with a 200 status —
            // parsing it as JSON threw, and the list failed with a
            // misleading "Download failed". A non-JSON answer means
            // the session was rejected: say so honestly.
            val searchBody = searchResp.text
            if (!searchBody.trimStart().startsWith("{")) {
                return EpisodeResult.Failed("The source is still showing its security check — the session didn't stick. Tap Retry to solve it again.")
            }
            val data = JSONObject(searchBody).optJSONArray("data")
                ?: return EpisodeResult.Failed("Search returned no results for \"${title.title}\".")
            val want = normalize(title.title)
            var bestSession: String? = null
            var bestScore = Int.MAX_VALUE
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val session = obj.optString("session").takeIf { it.isNotBlank() } ?: continue
                val found = normalize(obj.optString("title"))
                val score = when {
                    found == want -> 0
                    found.startsWith(want) || want.startsWith(found) -> 1
                    found.contains(want) || want.contains(found) -> 2
                    else -> 3
                }
                // Prefer the same type/year when several titles match.
                val tiebreak = (if (obj.optString("type").equals(title.type, ignoreCase = true)) 0 else 1) +
                    (if (obj.optInt("year", -1).toString() == title.year) 0 else 1)
                val total = score * 10 + tiebreak
                if (total < bestScore) {
                    bestScore = total
                    bestSession = session
                }
            }
            if (bestScore >= 30 || bestSession == null) {
                return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            }
            val episodes = mutableListOf<EpisodeInfo>()
            var incomplete = false
            var page = 1
            var lastPage = 1
            do {
                val resp = paheGet("/api?m=release&id=$bestSession&sort=episode_asc&page=$page")
                if (resp == null) {
                    // Never silently truncate on a failed page — flag it.
                    incomplete = true
                    break
                }
                // 2.0-dev1.4: same challenge-HTML guard as search —
                // the session can die mid-list.
                if (!resp.text.trimStart().startsWith("{")) {
                    return EpisodeResult.Failed("The source is still showing its security check — the session didn't stick. Tap Retry to solve it again.")
                }
                try {
                    val root = JSONObject(resp.text)
                    lastPage = root.optInt("last_page", page)
                    val arr = root.optJSONArray("data") ?: break
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val epSession = obj.optString("session").takeIf { it.isNotBlank() } ?: continue
                        episodes += EpisodeInfo(
                            number = obj.optInt("episode", 0),
                            title = obj.optString("title").takeIf { it.isNotBlank() && it != "?" },
                            duration = obj.optString("duration").takeIf { it.isNotBlank() },
                            audio = obj.optString("audio", "jpn").takeIf { it.isNotBlank() } ?: "jpn",
                            fansub = obj.optString("fansub").takeIf { it.isNotBlank() },
                            episodeSession = epSession,
                            animeSession = bestSession,
                        )
                    }
                } catch (_: Exception) {
                    incomplete = true
                    break
                }
                page++
            } while (page <= lastPage)
            episodes.sortBy { it.number }
            EpisodeResult.Loaded(episodes, incomplete)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    // --- Play page -----------------------------------------------------

    private data class Variant(
        val kwikUrl: String,
        val resolution: Int,
        val audio: String,
        val fansub: String?,
    )

    private fun attr(tag: String, name: String): String? =
        Regex("""$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.getOrNull(1)

    private fun parseVariants(html: String): List<Variant> {
        val out = mutableListOf<Variant>()
        // #resolutionMenu buttons first (the confirmed route), then
        // any button carrying a kwik data-src as a fallback.
        val buttonRe = Regex("""<button\b[^>]*>.*?</button>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in buttonRe.findAll(html)) {
            val tag = match.value
            val src = attr(tag, "data-src") ?: continue
            if (!src.contains("kwik.", ignoreCase = true)) continue
            val res = attr(tag, "data-resolution")?.toIntOrNull()
                ?: Regex("""(\d{3,4})p""").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val audio = attr(tag, "data-audio")?.lowercase()
                ?: if (tag.contains("eng", ignoreCase = true) || tag.contains("dub", ignoreCase = true)) "eng" else "jpn"
            out += Variant(src, res, audio, attr(tag, "data-fansub"))
        }
        if (out.isNotEmpty()) return out
        // Fallback regexes: any href|data-src pointing at kwik near a <n>p label.
        val fallbackRe = Regex("""(?:data-src|href)\s*=\s*"([^"]*kwik\.[a-z]+/e/[A-Za-z0-9]+[^"]*)"""", RegexOption.IGNORE_CASE)
        val resRe = Regex("""(\d{3,4})p""")
        for (match in fallbackRe.findAll(html)) {
            val url = match.groupValues[1]
            val res = resRe.find(html.substring(0, match.range.first).takeLast(200).plus(html.substring(match.range.first, (match.range.first + 200).coerceAtMost(html.length))))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
            out += Variant(url, res, "jpn", null)
        }
        return out
    }

    private fun parsePaheWinLinks(html: String): List<String> {
        val re = Regex("""https?://pahe\.win/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
        return re.findAll(html).map { it.value }.distinct().toList()
    }

    private fun pickVariant(variants: List<Variant>, audio: String, quality: String): Variant? {
        if (variants.isEmpty()) return null
        val wantedRes = Regex("""(\d+)""").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
        val byAudio = variants.filter { it.audio.equals(audio, ignoreCase = true) }
        val pool = byAudio.ifEmpty { variants }
        return pool.filter { it.resolution <= wantedRes }.maxByOrNull { it.resolution }
            ?: pool.minByOrNull { it.resolution }
    }

    // --- Kwik ----------------------------------------------------------

    private fun rotateKwikTld(url: String, tld: String): String? {
        val match = Regex("""^(https?://kwik\.)[a-z]+(/.*)$""").find(url) ?: return null
        return match.groupValues[1] + tld + match.groupValues[2]
    }

    // JsPacker (Dean Edwards' packer) port — pure string manipulation.
    // Input: eval(function(p,a,c,k,e,d){...}('payload', radix, count,
    // 'w1|w2|...'.split('|'))). Rebuilds the word table and substitutes
    // \b<token>\b occurrences. ~30 lines of logic.
    internal fun unpackJsPacker(script: String): String? {
        val re = Regex(
            """\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val m = re.find(script) ?: return null
        val payload = unescapeJsString(m.groupValues[1])
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val words = unescapeJsString(m.groupValues[4]).split("|")
        if (radix !in 2..62 || count <= 0) return null
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun base(n: Int): String {
            if (n == 0) return "0"
            var v = n
            val sb = StringBuilder()
            while (v > 0) {
                sb.append(digits[v % radix])
                v /= radix
            }
            return sb.reverse().toString()
        }
        var out = payload
        for (i in count - 1 downTo 0) {
            val token = base(i)
            val word = words.getOrNull(i) ?: continue
            if (word.isEmpty()) continue
            out = Regex("""\b${Regex.escape(token)}\b""").replace(out, Regex.escapeReplacement(word))
        }
        return out
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 6).coerceAtMost(s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: 'u')
                        i += 4
                    }
                    'x' -> {
                        val hex = s.substring((i + 2).coerceAtMost(s.length), (i + 4).coerceAtMost(s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: 'x')
                        i += 2
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // Unwrap eval("...") string escapes, then JsPacker, up to 6 rounds
    // (pahebatcher's shape), re-checking for the .m3u8 after each round.
    private fun extractM3u8(html: String): String? {
        val m3u8Re = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""")
        m3u8Re.find(html)?.let { return it.value }
        var text = html
        repeat(6) {
            m3u8Re.find(text)?.let { return it.value }
            // Longest <script> blocks first — the packed blob is the big one.
            val scripts = Regex("""<script[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(text).map { it.groupValues[1] }.sortedByDescending { it.length }
            var progressed = false
            for (script in scripts) {
                unpackJsPacker(script)?.let { unpacked ->
                    if (unpacked != text) {
                        text = unpacked
                        progressed = true
                    }
                }
                if (!progressed) {
                    Regex("""eval\("((?:[^"\\]|\\.)*)"\)""", RegexOption.DOT_MATCHES_ALL).find(script)?.let { m ->
                        text = unescapeJsString(m.groupValues[1])
                        progressed = true
                    }
                }
                if (progressed) break
            }
            if (!progressed) return@repeat
        }
        m3u8Re.find(text)?.let { return it.value }
        Regex("""<source[^>]+src\s*=\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
        return null
    }

    private suspend fun fetchKwikPage(kwikUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        // TLD rotation: Kwik's CF posture is lighter than Pahe's, and
        // the live TLD rotates (cx/gg/si/me/net/in/cc).
        val candidates = listOf(kwikUrl) + KWIK_TLDS.mapNotNull { rotateKwikTld(kwikUrl, it) }.filter { it != kwikUrl }
        for (url in candidates) {
            pace()
            try {
                val resp = CronetHttp.requestWithRetry(
                    url = url,
                    headers = cookieHeaders(mapOf("Referer" to "https://animepahe.pw/")),
                    timeoutMs = 15_000,
                    followRedirects = true,
                )
                if (resp.code == 200 && resp.bytes.isNotEmpty()) {
                    val cookies = resp.setCookies().mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                    return@withContext resp.text to cookies.joinToString("; ")
                }
            } catch (_: Exception) {
                // Next TLD.
            }
        }
        null
    }

    private fun extractKwikUrlFromPaheWin(html: String): String? {
        // The five locations the anime-kun32 wrapper checks, in order.
        val patterns = listOf(
            Regex("""href:\s*"([^"]*kwik\.[a-z]+/e/[A-Za-z0-9]+[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""href",\s*"([^"]*kwik\.[a-z]+/e/[A-Za-z0-9]+[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""href\s*=\s*"([^"]*kwik\.[a-z]+/e/[A-Za-z0-9]+[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex(""""(https?://kwik\.[a-z]+/e/[A-Za-z0-9]+[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""'(https?://kwik\.[a-z]+/e/[A-Za-z0-9]+[^']*)'""", RegexOption.IGNORE_CASE),
        )
        for (re in patterns) re.find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractKwikForm(html: String): Pair<String, String>? {
        val formRe = Regex("""<form\b[^>]*>.*?</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (form in formRe.findAll(html)) {
            val tag = form.value
            val action = attr(tag.substringBefore(">"), "action") ?: continue
            val token = Regex("""<input\b[^>]*name\s*=\s*"_token"[^>]*>""", RegexOption.IGNORE_CASE)
                .find(tag)?.value?.let { attr(it, "value") } ?: continue
            if (token.isNotBlank()) return action to token
        }
        return null
    }

    // DDL chain: pahe.win -> Kwik URL -> form action+token -> POST ->
    // 302 Location = direct mp4 (fallbacks: meta refresh /
    // window.location in a 200 body).
    private suspend fun tryDdl(paheWinUrl: String): String? = withContext(Dispatchers.IO) {
        val paheHost = PAHE_HOSTS.first()
        val winResp = try {
            pace()
            CronetHttp.requestWithRetry(
                url = paheWinUrl,
                headers = cookieHeaders(mapOf("Referer" to "https://$paheHost/")),
                timeoutMs = 10_000,
                followRedirects = true,
            )
        } catch (_: Exception) {
            return@withContext null
        }
        if (winResp.code != 200) return@withContext null
        val kwikUrl = extractKwikUrlFromPaheWin(winResp.text) ?: return@withContext null
        val (kwikHtml, kwikCookies) = fetchKwikPage(kwikUrl) ?: return@withContext null
        // The form is assembled by the page's scripts; if it is not in
        // the raw HTML, unpack the scripts and look again.
        var form = extractKwikForm(kwikHtml)
        if (form == null) {
            val scripts = Regex("""<script[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(kwikHtml).map { it.groupValues[1] }.sortedByDescending { it.length }
            for (script in scripts) {
                val unpacked = unpackJsPacker(script) ?: continue
                form = extractKwikForm(unpacked)
                if (form != null) break
            }
        }
        val (action, token) = form ?: return@withContext null
        val kwikHost = try {
            java.net.URL(kwikUrl).host
        } catch (_: Exception) {
            return@withContext null
        }
        val postUrl = resolveUrl(kwikUrl, action)
        delay(2000) // ~2s human-like pause before the form POST.
        val postResp = try {
            pace()
            CronetHttp.requestWithRetry(
                url = postUrl,
                method = "POST",
                headers = cookieHeaders(
                    mapOf(
                        "Origin" to "https://$kwikHost",
                        "Referer" to kwikUrl,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Cookie" to kwikCookies,
                    ),
                ),
                body = "_token=${URLEncoder.encode(token, "UTF-8")}".toByteArray(Charsets.UTF_8),
                timeoutMs = 15_000,
                followRedirects = false, // the 302 Location IS the mp4.
            )
        } catch (_: Exception) {
            return@withContext null
        }
        if (postResp.code in 300..399) {
            postResp.header("Location")?.takeIf { it.contains(".mp4") || it.startsWith("http") }?.let { return@withContext it }
        }
        if (postResp.code == 200) {
            val body = postResp.text
            Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]+content\s*=\s*["'][^"']*url=([^"'\s>]+)""", RegexOption.IGNORE_CASE)
                .find(body)?.let { return@withContext it.groupValues[1] }
            Regex("""window\.location\s*=\s*["']([^"']+\.mp4[^"']*)["']""")
                .find(body)?.let { return@withContext it.groupValues[1] }
        }
        null
    }

    // Decoupled resolve: search -> releases -> match the episode
    // number (and audio when present) -> the same DDL-first chain
    // as resolveDownload. The full list load is reused rather than
    // re-implementing the search + pagination.
    override suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        if (clearanceCookie.isNullOrBlank()) {
            // 2.0-dev1.9 TEMPORARY download diagnostics: Pahe never
            // makes a network call without a clearance — the log
            // must show that, or a dead server 3 looks like a
            // network failure instead of a missing session.
            DownloadLogs.log("RESOLVE", "Server 3: no clearance — skipping, no network call")
            return ResolvedLink.Failed("The session is not ready — try again in a moment.")
        }
        return when (val result = loadEpisodes(title)) {
            is EpisodeResult.Loaded -> {
                val wantAudio = audioCode(audio)
                val ep = result.episodes.firstOrNull { it.number == number && it.audio.equals(wantAudio, ignoreCase = true) }
                    ?: result.episodes.firstOrNull { it.number == number }
                    ?: return ResolvedLink.Failed("This episode isn't listed on this server.")
                resolveDownload(ep, quality)
            }
            is EpisodeResult.SourcePending ->
                ResolvedLink.Failed("The session is not ready — try again in a moment.")
            is EpisodeResult.Failed -> ResolvedLink.Failed(result.message)
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        if (clearanceCookie.isNullOrBlank()) {
            return ResolvedLink.Failed("The session is not ready — try again in a moment.")
        }
        return try {
            val playResp = paheGet("/play/${episode.animeSession}/${episode.episodeSession}")
                ?: return ResolvedLink.Failed("Could not open the episode page.")
            val html = playResp.text
            val variants = parseVariants(html)
            val chosen = pickVariant(variants, episode.audio, quality)
            // DDL first: try the pahe.win link at the chosen variant's
            // index first (same order as the buttons), then the rest.
            val winLinks = parsePaheWinLinks(html)
            val orderedLinks = mutableListOf<String>()
            if (chosen != null) {
                val idx = variants.indexOf(chosen)
                if (idx in winLinks.indices) orderedLinks += winLinks[idx]
            }
            orderedLinks += winLinks.filter { it !in orderedLinks }
            for (link in orderedLinks) {
                val mp4 = tryDdl(link) ?: continue
                val referer = link // download GET needs the pahe.win page as Referer.
                return ResolvedLink.DirectMp4(mp4, referer)
            }
            // HLS fallback: Kwik m3u8 via the chosen (or first) button.
            val kwikUrl = chosen?.kwikUrl ?: variants.firstOrNull()?.kwikUrl
                ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
            val (kwikHtml, _) = fetchKwikPage(kwikUrl)
                ?: return ResolvedLink.Failed("Could not reach the stream host — try again.")
            val m3u8 = extractM3u8(kwikHtml)
                ?: return ResolvedLink.Failed("Could not extract a stream link — the source may have changed its player.")
            ResolvedLink.Hls(m3u8)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }
}
