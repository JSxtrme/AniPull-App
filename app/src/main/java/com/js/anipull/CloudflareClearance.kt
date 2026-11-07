package com.js.anipull

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 2.0-dev1.3 CLEARANCE — on-device WebView clearance + cf_clearance
// cookie reuse (Mihon/kotatsu CloudflareInterceptor pattern, per
// PAHE-RESOLVER-RESEARCH.md §8.2).
//
// What this is, in plain terms: the app loads AnimePahe's own public
// homepage in a hidden browser view on the USER'S OWN phone, the way
// the user would in Chrome. Cloudflare's challenge runs its JS there
// and sets a cf_clearance session cookie; the app then reuses that
// cookie (and the browser's User-Agent) for its plain HTTP calls to
// the Pahe API. No server, no paid solver, no account, no fake
// identity — the cookie is one the site itself set for this phone.
//
// HARD BOUNDARY: this file is the ONLY place that touches a WebView
// or a cf_clearance cookie. The engine (PaheResolver, EpisodeSource)
// still only ever replays an already-obtained cookie string — it
// never solves a challenge itself. If this file is deleted, the app
// falls back to the honest SourcePending state, never fake episodes.
//
// Coherence rules (research §8.1): a clearance is void if replayed
// from a different IP or with a different UA/TLS fingerprint than
// the one that minted it, so the cookie AND the minting WebView's
// User-Agent are stored together and replayed together. A 200 proves
// nothing — challenge pages return 200 with no data — so success is
// asserted on page CONTENT (real title, no challenge markers), never
// on status alone. And the cookie is never harvested while the
// interstitial is still showing (the store can hold a stale one).

data class Clearance(
    val cookieValue: String, // just the cf_clearance token, not the full cookie string
    val userAgent: String, // the WebView UA that minted it — replay must use this exact UA
    val mintedAtMs: Long,
    val host: String = CloudflareClearance.PAHE_HOST,
)

sealed class ClearanceResult {
    data class Cleared(val clearance: Clearance) : ClearanceResult()
    object Failed : ClearanceResult()
    // The challenge needs a human tap (Turnstile checkbox). The UI
    // should surface a VISIBLE WebView for this — never claim success.
    object NeedsUserAction : ClearanceResult()
}

object CloudflareClearance {
    const val PAHE_HOST = "animepahe.pw"
    private const val PAHE_URL = "https://$PAHE_HOST/"
    private const val PREFS = "anipull_clearance"
    private const val KEY_COOKIE = "cf_clearance"
    private const val KEY_UA = "clearance_ua"
    private const val KEY_MINTED_AT = "clearance_minted_at_ms"
    private const val KEY_HOST = "clearance_host"

    // Clearance TTL: ~15 min (Nyora's design; pahebatcher uses 20).
    // Re-solving is silent and cheap, so the shorter end is safer.
    const val CLEARANCE_TTL_MS = 15 * 60 * 1000L
    private const val SOLVE_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 500L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Stored clearance, only if it is still inside its TTL. Expired
    // (or missing) -> null, and the caller re-solves.
    fun loadStored(context: Context): Clearance? {
        val p = prefs(context)
        val value = p.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() } ?: return null
        val ua = p.getString(KEY_UA, null)?.takeIf { it.isNotBlank() } ?: return null
        val minted = p.getLong(KEY_MINTED_AT, 0L)
        if (minted <= 0L || System.currentTimeMillis() - minted > CLEARANCE_TTL_MS) return null
        return Clearance(value, ua, minted, p.getString(KEY_HOST, PAHE_HOST) ?: PAHE_HOST)
    }

    private fun save(context: Context, clearance: Clearance) {
        prefs(context).edit()
            .putString(KEY_COOKIE, clearance.cookieValue)
            .putString(KEY_UA, clearance.userAgent)
            .putLong(KEY_MINTED_AT, clearance.mintedAtMs)
            .putString(KEY_HOST, clearance.host)
            .apply()
    }

    fun clearStored(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // Read the cf_clearance token straight out of the native WebView
    // cookie store. Unlike document.cookie in JS, the native store
    // exposes HttpOnly cookies, so cf_clearance is harvestable here.
    fun readCookieFromStore(url: String = PAHE_URL): String? {
        val raw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        } ?: return null
        return raw.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=") }
            ?.removePrefix("cf_clearance=")?.takeIf { it.isNotBlank() }
    }

    // After a VISIBLE WebView solve (user tapped the checkbox),
    // harvest whatever the store now holds and persist it.
    // Returns true if a fresh clearance was stored.
    fun persistFromStore(context: Context): Boolean {
        val value = readCookieFromStore() ?: return false
        val ua = lastSeenUa ?: return false
        save(context, Clearance(value, ua, System.currentTimeMillis()))
        return true
    }

    // The UA of the most recent WebView that loaded Pahe. The visible
    // dialog's WebView sets this; the hidden solve reads its own UA
    // directly. Only ever the app's own WebView UA, never a spoof.
    @Volatile
    private var lastSeenUa: String? = null

    // One shared WebView factory: real browser defaults, JS on (the
    // challenge needs it), DOM storage on, NO custom UA (the minted
    // UA must be the WebView's genuine one), and an explicit
    // pass-through shouldInterceptRequest — intercepting anything
    // during the challenge breaks the challenge's own sub-requests
    // (Tsuki's 2026 fix for its Cloudflare CAPTCHA loop).
    fun createPaheWebView(context: Context): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                return null // pass through — never intercept during a challenge
            }
        }
        lastSeenUa = webView.settings.userAgentString
        return webView
    }

    private fun isChallengeTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("just a moment") || t.contains("attention required") ||
            t.contains("verify you are human") || t.contains("cloudflare") ||
            t.contains("checking your browser")
    }

    private fun isLoadErrorTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("webpage not available") || t.startsWith("err_") ||
            t.contains("net::err")
    }

    // Returns a valid clearance, solving silently in a hidden WebView
    // when the stored one is missing/expired. Runs the WebView on
    // the main thread; safe to call from any coroutine. Returns
    // Failed (never throws) when there is no Activity to host the
    // WebView or the solve cannot complete.
    suspend fun ensureClearance(activity: Activity?): ClearanceResult {
        val ctx = activity ?: return ClearanceResult.Failed
        if (activity.isFinishing || activity.isDestroyed) return ClearanceResult.Failed
        loadStored(ctx)?.let { return ClearanceResult.Cleared(it) }
        return solveHidden(activity)
    }

    private suspend fun solveHidden(activity: Activity): ClearanceResult {
        return withContext(Dispatchers.Main) {
            val webView = try {
                createPaheWebView(activity)
            } catch (_: Exception) {
                return@withContext ClearanceResult.Failed
            }
            val decor = try {
                activity.window?.decorView as? ViewGroup
            } catch (_: Exception) {
                null
            } ?: run {
                try { webView.destroy() } catch (_: Exception) { }
                return@withContext ClearanceResult.Failed
            }
            // 1x1, invisible, but ATTACHED to the window — an
            // unattached WebView throttles JS timers and the
            // challenge may never complete.
            val params = ViewGroup.LayoutParams(1, 1)
            webView.visibility = View.INVISIBLE
            var result: ClearanceResult = ClearanceResult.Failed
            try {
                decor.addView(webView, params)
                CookieManager.getInstance().setAcceptCookie(true)
                webView.loadUrl(PAHE_URL)
                val deadline = System.currentTimeMillis() + SOLVE_TIMEOUT_MS
                var sawChallenge = false
                while (System.currentTimeMillis() < deadline) {
                    val title = try { webView.title ?: "" } catch (_: Exception) { "" }
                    val progress = try { webView.progress } catch (_: Exception) { 0 }
                    if (isChallengeTitle(title)) sawChallenge = true
                    // Early exit: the page finished loading and it is
                    // a plain load error, not a challenge — waiting
                    // the full 30s would just waste the user's time.
                    if (progress >= 100 && title.isNotBlank() && isLoadErrorTitle(title)) break
                    // Success needs ALL of: a cookie, a finished
                    // load, a real (non-challenge) title. The title
                    // check is what stops a stale cookie harvested
                    // while the interstitial is still showing from
                    // counting as a solve.
                    if (progress >= 100 && title.isNotBlank() && !isChallengeTitle(title)) {
                        val cookie = readCookieFromStore()
                        if (cookie != null) {
                            val ua = webView.settings.userAgentString
                            val clearance = Clearance(cookie, ua, System.currentTimeMillis())
                            save(activity, clearance)
                            try { CookieManager.getInstance().flush() } catch (_: Exception) { }
                            result = ClearanceResult.Cleared(clearance)
                            break
                        }
                    }
                    delay(POLL_MS)
                }
                if (result is ClearanceResult.Failed && sawChallenge) {
                    // The challenge was still on screen when time ran
                    // out — most likely a Turnstile checkbox the
                    // hidden view cannot tap. Surface the page.
                    result = ClearanceResult.NeedsUserAction
                }
                result
            } finally {
                // Always tear the hidden view down, including on
                // cancellation (e.g. the user left the detail page
                // mid-solve): a leaked WebView holds RAM and a
                // renderer process.
                try { decor.removeView(webView) } catch (_: Exception) { }
                try { webView.stopLoading() } catch (_: Exception) { }
                try { webView.destroy() } catch (_: Exception) { }
            }
        }
    }
}
