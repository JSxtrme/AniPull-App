package com.js.anipull

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// 2.0-dev1.7 — best-effort server health check for the Sources
// auto-test button. One cheap probe per server: a search, a
// homepage, or a HEAD — never a full episode load, never a
// clearance solve, never a download. This ranks servers by
// reachability + latency. It does NOT measure real download
// speed, and a gated server is UNKNOWN, never "failed", when the
// check cannot get past its gate — a clearance-free probe of a
// gated server proves nothing about whether it works once
// cleared, so it must not sink that server to the bottom.
enum class HealthStatus { OK, FAILED, UNKNOWN }

data class HealthResult(val status: HealthStatus, val latencyMs: Long?)

object ServerHealth {
    private const val TIMEOUT_MS = 8_000

    private data class Probe(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
    )

    // One cheap probe per server. Server 3 is clearance-gated: with
    // no stored, in-TTL clearance there is no honest probe — the
    // function returns null and the caller reports UNKNOWN instead
    // of calling the gate and misreading its 403 as "failed".
    // Server 2 needs its Origin header on every call; server 5 is
    // a static file, so a HEAD is enough and never downloads it.
    private fun probeFor(number: Int, context: Context): Probe? = when (number) {
        1 -> Probe("https://anizone.to/")
        2 -> Probe(
            "https://core.justanime.to/api/search?query=frieren",
            headers = mapOf("Origin" to "https://justanime.to"),
        )
        3 -> {
            val c = CloudflareClearance.loadStored(context) ?: return null
            Probe(
                "https://animepahe.pw/api?m=search&q=frieren",
                headers = mapOf(
                    "Cookie" to "cf_clearance=${c.cookieValue}",
                    "User-Agent" to c.userAgent,
                ),
            )
        }
        4 -> Probe("https://ani.pm/")
        5 -> Probe("https://beta.cno.to/library.json", method = "HEAD")
        6 -> Probe("https://aniwaves.tv/")
        7 -> Probe("https://anikotoapi.site/recent-anime?per_page=1")
        8 -> Probe("https://anime.uniquestream.net/api/v1/search?query=frieren")
        9 -> Probe(
            "https://anv.to/api/catalog",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"operation":"SearchAnime","variables":{"search":"frieren","page":1,"perPage":1}}"""
                .toByteArray(Charsets.UTF_8),
        )
        else -> null
    }

    private fun classify(resp: HttpResponse): HealthStatus {
        if (resp.code in 200..299) return HealthStatus.OK
        if (resp.code == 403) {
            // A Cloudflare challenge page is a gate, not a dead
            // server — UNKNOWN, so the reorder never punishes a
            // server for being gated from this network.
            val body = resp.text
            val challenged = resp.header("cf-ray") != null ||
                body.contains("Just a moment") ||
                body.contains("Attention Required") ||
                body.contains("cf_chl")
            if (challenged) return HealthStatus.UNKNOWN
        }
        return HealthStatus.FAILED
    }

    private suspend fun testOne(number: Int, context: Context): HealthResult =
        withContext(Dispatchers.IO) {
            val probe = probeFor(number, context)
                ?: return@withContext HealthResult(HealthStatus.UNKNOWN, null)
            val start = System.currentTimeMillis()
            try {
                val resp = EngineHttp.request(
                    probe.url,
                    method = probe.method,
                    headers = probe.headers,
                    body = probe.body,
                    timeoutMs = TIMEOUT_MS,
                )
                HealthResult(classify(resp), System.currentTimeMillis() - start)
            } catch (_: Exception) {
                HealthResult(HealthStatus.FAILED, null)
            }
        }

    // All nine probes in parallel — the whole test costs about as
    // long as the slowest single probe (worst case ~8s).
    suspend fun testAll(context: Context, order: List<Int>): Map<Int, HealthResult> =
        coroutineScope {
            order.map { n -> async { n to testOne(n, context) } }.awaitAll().toMap()
        }

    // Fastest working first, then servers the check could not judge
    // (kept in their current relative order), then failed ones
    // (also kept in relative order). A server with no result at all
    // stays where it was, between unknown and failed.
    fun reorderByHealth(order: List<Int>, results: Map<Int, HealthResult>): List<Int> {
        val ok = order.filter { results[it]?.status == HealthStatus.OK }
            .sortedBy { results[it]?.latencyMs ?: Long.MAX_VALUE }
        val unknown = order.filter { results[it]?.status == HealthStatus.UNKNOWN }
        val failed = order.filter { results[it]?.status == HealthStatus.FAILED }
        val rest = order.filter { it !in ok && it !in unknown && it !in failed }
        return ok + unknown + rest + failed
    }
}
