package com.js.anipull

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 2.0-dev1.6 ENGINE — Server 8.
// UniqueStream JSON API at anime.uniquestream.net/api/v1, probed
// live 2026-11-10 (UNIQUE-STREAM-PROBE.md). No auth, no key, no
// Cloudflare challenge on the API itself — the most open API of the
// locked sources, and the soft-sub leg: 16 subtitle languages as
// separate .vtt files, verified with real WEBVTT fetched live.
// Endpoints (all verified with Frieren):
//   GET /api/v1/search?query=            -> series[] (content_id, title, episodes_count)
//   GET /api/v1/series/<id>              -> seasons[] (content_id, season_number, episode_count)
//   GET /api/v1/season/<id>/episodes?page=&limit=  -> episode_number (1.0), title, duration_ms
//   GET /api/v1/episode/<id>/media/hls/<locale>    -> media_id, hls.playlist (signed), hls.subtitles[]
// Locales: ja-JP = sub, en-US = dub (each locale is a separate
// signed playlist, not one playlist with audio tracks).
// THE BLOCKER (unresolved from the VM): the signed master playlist
// on get4.mediacache.cc 403s from datacenter IPs. The resolver
// returns the link anyway when the playlist fetch fails at resolve
// time? NO — it fails honestly: if the playlist cannot be read here,
// the downloader (same network, seconds later) cannot read it
// either. A residential-IP phone test decides this source.
// AES-128, WRAPPED KEY (public derivation, no embedded secret —
// from the Anivio plugin header, documented in the probe):
// key.bin is fetched with header `x-am-media-id: <media_id>` and
// unwrapped with key = SHA256("key"+mid)[:16] / iv =
// SHA256("iv"+mid)[:16]. The unwrapped 16 bytes are the segment
// key (keyOverrideB64); the derived iv is the segment IV override,
// used only when the playlist KEY tag lacks an explicit IV.
// The key.bin URL comes from the playlist's own #EXT-X-KEY URI —
// it is never guessed.
// Episode session encoding:
//   episodeSession = "animestream|<seriesId>|<seasonId>|<episodeContentId>|<number>",
//   animeSession = seriesId string.

internal class AnimeStreamEpisodeSource : BaseEpisodeSource(
    serverNumber = 8,
    serverDescription = ServerRegistry.description(8),
    sourceName = "AnimeStream",
) {
    companion object {
        private const val API = "https://anime.uniquestream.net/api/v1"
    }

    private suspend fun apiGet(path: String, timeoutMs: Int = 12_000): HttpResponse {
        pace()
        return httpGet("$API$path", timeoutMs = timeoutMs, followRedirects = true)
    }

    private data class SeriesHit(
        val contentId: String,
        val title: String,
        val episodesCount: Int,
        val subbed: Boolean,
        val dubbed: Boolean,
    )

    private suspend fun search(title: ApiTitle): List<SeriesHit> {
        val q = URLEncoder.encode(title.title, "UTF-8").replace("+", "%20")
        val resp = apiGet("/search?query=$q")
        if (resp.code != 200) return emptyList()
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("series") ?: return emptyList()
        val out = mutableListOf<SeriesHit>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("content_id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
            out += SeriesHit(
                contentId = id,
                title = name,
                episodesCount = obj.optInt("episodes_count", 0),
                subbed = obj.optBoolean("subbed", true),
                dubbed = obj.optBoolean("dubbed", false),
            )
        }
        return out
    }

    private fun pickSeries(hits: List<SeriesHit>, title: ApiTitle): SeriesHit? {
        if (hits.isEmpty()) return null
        val want = normalize(title.title)
        var best: SeriesHit? = null
        var bestScore = Int.MAX_VALUE
        for (hit in hits) {
            val s = score(normalize(hit.title), want)
            // Prefer an episode count that matches the catalog when
            // AniLab knows it.
            val tiebreak = if (title.episodes != null && title.episodes != hit.episodesCount) 1 else 0
            val total = s * 10 + tiebreak
            if (total < bestScore) {
                bestScore = total
                best = hit
            }
        }
        return if (bestScore >= 30) null else best
    }

    private data class Season(val contentId: String, val seasonNumber: Int, val episodeCount: Int)

    private suspend fun fetchSeasons(seriesId: String): List<Season> {
        val resp = apiGet("/series/$seriesId")
        if (resp.code != 200) return emptyList()
        val root = runCatching { JSONObject(resp.text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("seasons") ?: return emptyList()
        val out = mutableListOf<Season>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("content_id").takeIf { it.isNotBlank() } ?: continue
            out += Season(
                contentId = id,
                seasonNumber = obj.optInt("season_number", obj.optInt("season_seq_number", 0)),
                episodeCount = obj.optInt("episode_count", 0),
            )
        }
        return out.sortedBy { it.seasonNumber }
    }

    // Which season is this title? "Season 2" / "2nd Season" in the
    // name picks season 2; otherwise season 1 (or the only season).
    private fun wantedSeasonNumber(title: ApiTitle): Int {
        val lower = title.title.lowercase()
        Regex("""season\s*(\d+)""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(\d+)(?:st|nd|rd|th)\s+season""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    private fun pickSeason(seasons: List<Season>, title: ApiTitle): Season? {
        if (seasons.isEmpty()) return null
        if (seasons.size == 1) return seasons[0]
        val want = wantedSeasonNumber(title)
        return seasons.firstOrNull { it.seasonNumber == want } ?: seasons.firstOrNull { it.seasonNumber == 1 } ?: seasons[0]
    }

    private data class SeasonEpisode(
        val contentId: String,
        val number: Int,
        val title: String?,
        val durationMs: Long?,
    )

    private suspend fun fetchSeasonEpisodes(seasonId: String): List<SeasonEpisode> {
        val out = mutableListOf<SeasonEpisode>()
        var page = 1
        // 2.0-dev2.6: the API now enforces limit <= 20 (limit=100
        // returns HTTP 422); paginate at 20 to the end.
        while (page <= 50) {
            val resp = apiGet("/season/$seasonId/episodes?page=$page&limit=20")
            if (resp.code != 200) break
            val arr = runCatching { JSONObject(resp.text).optJSONArray("episodes") }.getOrNull()
                ?: runCatching { org.json.JSONArray(resp.text) }.getOrNull()
                ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val num = jsonEpisodeNumber(obj.opt("episode_number")) ?: continue
                val id = obj.optString("content_id").takeIf { it.isNotBlank() } ?: continue
                out += SeasonEpisode(
                    contentId = id,
                    number = num,
                    title = obj.optString("title").takeIf { it.isNotBlank() },
                    durationMs = obj.optLong("duration_ms", -1L).takeIf { it > 0 },
                )
            }
            // A short page is the last page.
            if (arr.length() < 20) break
            page++
        }
        return out.sortedBy { it.number }
    }

    private suspend fun resolveSeries(title: ApiTitle): Triple<SeriesHit, Season, List<SeasonEpisode>>? {
        val hit = pickSeries(search(title), title) ?: return null
        val seasons = fetchSeasons(hit.contentId)
        val season = pickSeason(seasons, title) ?: return null
        val episodes = fetchSeasonEpisodes(season.contentId)
        return Triple(hit, season, episodes)
    }

    override suspend fun loadEpisodes(title: ApiTitle, anilistId: Long?): EpisodeResult {
        return try {
            val (hit, season, episodes) = resolveSeries(title)
                ?: return EpisodeResult.Failed("No matching title on this server for \"${title.title}\".")
            if (episodes.isEmpty()) {
                return EpisodeResult.Failed("Couldn't load the episode list — tap Retry.")
            }
            val out = mutableListOf<EpisodeInfo>()
            for (ep in episodes) {
                val audios = buildList {
                    if (hit.subbed) add("jpn")
                    if (hit.dubbed) add("eng")
                    if (isEmpty()) add("jpn")
                }
                for (aud in audios) {
                    out += EpisodeInfo(
                        number = ep.number,
                        title = ep.title,
                        duration = msToDuration(ep.durationMs),
                        audio = aud,
                        fansub = null,
                        episodeSession = "animestream|${hit.contentId}|${season.contentId}|${ep.contentId}|${ep.number}",
                        animeSession = hit.contentId,
                    )
                }
            }
            EpisodeResult.Loaded(out)
        } catch (e: Exception) {
            EpisodeResult.Failed(friendlyEpisodeListError(e))
        }
    }

    private fun sha256First16(input: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.copyOf(16)
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // Fetch the master playlist, unwrap the wrapped AES key, and
    // return (keyOverrideB64, ivOverrideB64). Null = the playlist
    // could not be read, or it carries no wrapped key (plain AES or
    // unencrypted — the downloader handles those itself).
    // Returns a sentinel failure via the `failed` flag when the
    // playlist itself is unreadable.
    private data class KeyUnwrap(val keyB64: String?, val ivB64: String?, val playlistUnreachable: Boolean)

    private suspend fun unwrapKey(playlistUrl: String, mediaId: String): KeyUnwrap {
        val resp = try {
            pace()
            httpGet(playlistUrl, timeoutMs = 15_000, followRedirects = true)
        } catch (_: Exception) {
            return KeyUnwrap(null, null, playlistUnreachable = true)
        }
        if (resp.code != 200 || !resp.text.contains("#EXTM3U")) {
            return KeyUnwrap(null, null, playlistUnreachable = true)
        }
        // The KEY URI in the playlist points at key.bin (relative).
        val keyUri = Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(resp.text)?.groupValues?.getOrNull(1)
            ?: return KeyUnwrap(null, null, playlistUnreachable = false)
        val keyUrl = resolveUrl(playlistUrl, keyUri)
        val keyResp = try {
            pace()
            httpGet(
                keyUrl,
                headers = mapOf("x-am-media-id" to mediaId),
                timeoutMs = 10_000, followRedirects = true,
            )
        } catch (_: Exception) {
            return KeyUnwrap(null, null, playlistUnreachable = false)
        }
        if (keyResp.code != 200 || keyResp.bytes.isEmpty()) {
            return KeyUnwrap(null, null, playlistUnreachable = false)
        }
        // key.bin body is base64 ciphertext. Unwrap with the public
        // derivation: key = SHA256("key"+mid)[:16],
        // iv = SHA256("iv"+mid)[:16].
        val wrapped = runCatching {
            android.util.Base64.decode(keyResp.text.trim(), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return KeyUnwrap(null, null, playlistUnreachable = false)
        return try {
            val unwrapKey = sha256First16("key$mediaId")
            val unwrapIv = sha256First16("iv$mediaId")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(unwrapKey, "AES"),
                IvParameterSpec(unwrapIv),
            )
            val plain = cipher.doFinal(wrapped)
            if (plain.size != 16) return KeyUnwrap(null, null, playlistUnreachable = false)
            KeyUnwrap(b64(plain), b64(unwrapIv), playlistUnreachable = false)
        } catch (_: Exception) {
            KeyUnwrap(null, null, playlistUnreachable = false)
        }
    }

    private fun parseSubtitles(media: JSONObject): List<SubtitleTrack> {
        val out = mutableListOf<SubtitleTrack>()
        val hls = media.optJSONObject("hls") ?: return out
        val arr = hls.optJSONArray("subtitles") ?: return out
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.isNotBlank() }
                ?: s.optString("file").takeIf { it.isNotBlank() }
                ?: continue
            val locale = s.optString("locale").takeIf { it.isNotBlank() }
                ?: s.optString("language").takeIf { it.isNotBlank() }
                ?: s.optString("lang").takeIf { it.isNotBlank() }
            val label = s.optString("label").takeIf { it.isNotBlank() } ?: locale
            out += SubtitleTrack(
                language = locale ?: languageCodeFromLabel(label),
                url = url,
                label = label,
            )
        }
        return out
    }

    private suspend fun resolveMedia(episodeContentId: String, audio: String): ResolvedLink {
        val locale = if (audioCode(audio) == "eng") "en-US" else "ja-JP"
        val resp = apiGet("/episode/$episodeContentId/media/hls/$locale")
        if (resp.code != 200) {
            return ResolvedLink.Failed("Couldn't reach the stream for this episode — tap Retry.")
        }
        val root = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: return ResolvedLink.Failed("Couldn't read the stream for this episode — tap Retry.")
        val mediaId = root.optString("media_id").takeIf { it.isNotBlank() } ?: ""
        val hls = root.optJSONObject("hls")
            ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
        val playlist = hls.optString("playlist").takeIf { it.isNotBlank() }
            ?: return ResolvedLink.Failed("No stream found for this episode on this server.")
        val subs = parseSubtitles(root)
        // The playlist must be readable and the wrapped key unwrapped
        // NOW: the media CDN gates datacenter IPs, and the downloader
        // (same network, seconds later) would hit the same wall.
        // If the playlist is unreachable here, fail honestly instead
        // of queueing a dead link.
        val unwrap = if (mediaId.isNotBlank()) unwrapKey(playlist, mediaId) else KeyUnwrap(null, null, false)
        if (unwrap.playlistUnreachable) {
            return ResolvedLink.Failed("The video host isn't reachable right now — tap Retry.")
        }
        return ResolvedLink.Hls(
            playlist,
            subtitles = subs,
            keyOverrideB64 = unwrap.keyB64,
            ivOverrideB64 = unwrap.ivB64,
        )
    }

    override suspend fun resolveEpisode(
        title: ApiTitle,
        anilistId: Long?,
        number: Int,
        audio: String,
        quality: String,
    ): ResolvedLink {
        return try {
            val (_, _, episodes) = resolveSeries(title)
                ?: return ResolvedLink.Failed("No matching title on this server for \"${title.title}\".")
            val ep = episodes.firstOrNull { it.number == number }
                ?: return ResolvedLink.Failed("This episode isn't listed on this server.")
            resolveMedia(ep.contentId, audio)
        } catch (e: Exception) {
            ResolvedLink.Failed(friendlyDownloadError(e))
        }
    }

    override suspend fun resolveDownload(episode: EpisodeInfo, quality: String): ResolvedLink {
        val parts = episode.episodeSession.split("|")
        if (parts.size >= 5 && parts[0] == "animestream") {
            return resolveMedia(parts[3], episode.audio)
        }
        return ResolvedLink.Failed("This episode can't be resolved on this server.")
    }
}
