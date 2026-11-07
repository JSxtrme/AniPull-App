package com.js.anipull

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 2.0-dev1.5 — Cronet (Chromium's network stack) for the Pahe engine
// path. WHY, diagnosed from JS's phone test + code inspection:
//
// dev1.3/1.4 harvest the clearance CORRECTLY — the visible dialog
// only finishes when the page is real AND a cf_clearance cookie is
// in the store, and the cookie + minting UA are replayed together.
// But the replay STILL got challenge HTML after the user completed
// the tap. Same phone, same IP, same cookie, same UA — the only
// thing left that differs between the WebView that minted the
// clearance and the HttpURLConnection that replays it is the TLS
// fingerprint (Java's handshake reads as Java, not Chrome) plus
// HTTP/1.1 vs HTTP/2. Research §8.1 states the rule plainly: a
// cf_clearance replayed under a different TLS fingerprint than the
// one that minted it is void. Chrome on the same phone gets real
// JSON, the WebView loads the real page, only HttpURLConnection is
// re-challenged. That is the fingerprint.
//
// Cronet IS Chromium's stack — the same TLS/JA3, HTTP/2 and header
// order as the WebView that minted the clearance — so the replay
// finally looks like the browser that solved the check.
//
// SCOPE: PaheResolver only (search, releases, play page, pahe.win,
// Kwik). HlsDownloader segments and the DDL mp4 GET still use
// EngineHttp — if HLS downloads fail on the phone, that is the next
// thing to move. If Cronet cannot start on a device (native lib
// missing), every call falls back to EngineHttp instead of
// crashing — the list may fail again there, but the app never dies.
//
// The three Cronet AARs (api, common, embedded 141.7340.3) are
// vendored in app/libs/ because this VM's Java tooling cannot
// download from Google Maven (see TOOLS.md). Swapping to Maven
// coordinates is a one-line change per AAR once local Gradle
// downloads work: implementation("org.chromium.net:cronet-api:141.7340.3")
// etc. — the version is pinned to the vendored files.

internal object CronetHttp {
    private val callbackExecutor = Executors.newCachedThreadPool()
    @Volatile private var appContext: Context? = null
    @Volatile private var engine: CronetEngine? = null
    @Volatile private var unavailable = false
    private val initLock = Any()

    // Cheap: only stores the application context. The engine itself
    // is created lazily on the first request (an IO thread), never
    // on the main thread — first Cronet load is slow.
    fun setContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensureEngine(): CronetEngine? {
        engine?.let { return it }
        if (unavailable) return null
        val ctx = appContext ?: return null
        synchronized(initLock) {
            engine?.let { return it }
            if (unavailable) return null
            return try {
                CronetEngine.Builder(ctx)
                    .setUserAgent(BROWSER_UA)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .build()
                    .also { engine = it }
            } catch (t: Throwable) {
                // Native lib missing / load failure: never crash the
                // app for this — callers fall back to EngineHttp.
                unavailable = true
                null
            }
        }
    }

    private class CollectingCallback(
        private val followRedirects: Boolean,
    ) : UrlRequest.Callback() {
        val status = java.util.concurrent.atomic.AtomicInteger(-1)
        val headers = mutableMapOf<String, List<String>>()
        val body = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var error: CronetException? = null
        var redirectLocation: String? = null
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(32 * 1024)

        private fun capture(info: UrlResponseInfo) {
            status.set(info.httpStatusCode)
            headers.putAll(info.allHeaders)
        }

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            try {
                if (followRedirects) {
                    request.followRedirect()
                } else {
                    // The 302 Location IS the result (the Kwik form
                    // POST's mp4). Capture status + headers, then
                    // stop — do not follow.
                    capture(info)
                    redirectLocation = newLocationUrl
                    request.cancel()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            try {
                capture(info)
                request.read(buffer)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            try {
                byteBuffer.flip()
                val chunk = ByteArray(byteBuffer.remaining())
                byteBuffer.get(chunk)
                body.write(chunk)
                byteBuffer.clear()
                request.read(byteBuffer)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            latch.countDown()
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo, error: CronetException) {
            this.error = error
            latch.countDown()
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo) {
            latch.countDown()
        }
    }

    // One request via Cronet. Throws on timeout / network failure
    // (the retry wrapper decides what that means) and on Cronet
    // being unavailable — the retry wrapper falls back to
    // EngineHttp for the whole call in that case.
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
    ): HttpResponse {
        val eng = ensureEngine()
            ?: throw java.io.IOException("Cronet unavailable — falling back to plain HTTP")
        val callback = CollectingCallback(followRedirects)
        val builder = eng.newUrlRequestBuilder(url, callback, callbackExecutor)
            .setHttpMethod(method)
            .disableCache()
        // Per-request headers override the engine default UA —
        // the minting WebView UA arrives here via cookieHeaders().
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (body != null) {
            builder.setUploadDataProvider(UploadDataProviders.create(body), callbackExecutor)
        }
        val req = builder.build()
        req.start()
        val finished = try {
            callback.latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            try { req.cancel() } catch (_: Exception) { }
            throw SocketTimeoutException("Cronet request timed out: $url")
        }
        callback.error?.let { throw it }
        callback.redirectLocation?.let { loc ->
            val h = callback.headers.toMutableMap()
            if (!h.keys.any { it.equals("Location", ignoreCase = true) }) {
                h["Location"] = listOf(loc)
            }
            return HttpResponse(callback.status.get().takeIf { it >= 0 } ?: 302, ByteArray(0), h)
        }
        val code = callback.status.get()
        if (code < 0) throw java.io.IOException("Cronet request ended with no response: $url")
        return HttpResponse(code, callback.body.toByteArray(), callback.headers)
    }

    // Same retry/backoff shape as EngineHttp.requestWithRetry (429/
    // 503 + Retry-After). Falls back to EngineHttp for the whole
    // call when Cronet is unavailable — never mixes stacks
    // mid-chain, because a mixed chain would replay the clearance
    // under two different fingerprints.
    fun requestWithRetry(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        maxAttempts: Int = 3,
    ): HttpResponse {
        if (ensureEngine() == null) {
            return EngineHttp.requestWithRetry(url, method, headers, body, timeoutMs, followRedirects, maxAttempts)
        }
        var last: HttpResponse? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                last = request(url, method, headers, body, timeoutMs, followRedirects)
                if (last.code !in listOf(429, 503) && last.code < 500) return last
                if (attempt == maxAttempts) return last
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                Thread.sleep(1000L shl (attempt - 1))
                continue
            }
            val retryAfter = last?.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60)
            val backoffMs = retryAfter?.times(1000) ?: (1000L shl (attempt - 1))
            Thread.sleep(backoffMs)
        }
        return last ?: throw java.io.IOException("Request failed with no response: $url")
    }
}
