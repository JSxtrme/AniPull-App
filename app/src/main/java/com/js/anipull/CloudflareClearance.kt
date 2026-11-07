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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // 2.0-dev1.4: the ONLY correct harvest check. A clearance counts
    // as solved only when BOTH hold: the page is a real (non-
    // challenge) page AND a cf_clearance cookie is in the store.
    // Harvesting on the cookie alone is what broke Done in dev1.3
    // — the user tapped Done while Cloudflare was still verifying,
    // and the store held a stale/dead cookie (or none), so the next
    // API call got the challenge page back and the list failed with
    // a misleading "Download failed". Never harvest without this.
    fun solvedClearance(webView: WebView): Clearance? {
        val title = try { webView.title ?: "" } catch (_: Exception) { "" }
        if (title.isBlank() || isChallengeTitle(title)) return null
        val cookie = readCookieFromStore() ?: return null
        val ua = try { webView.settings.userAgentString } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: return null
        return Clearance(cookie, ua, System.currentTimeMillis())
    }

    // Persist a clearance that was verified solved in a visible
    // WebView (the check dialog). False = the check has not
    // finished yet — the caller must NOT close the dialog.
    fun persistSolved(webView: WebView, context: Context): Boolean {
        val clearance = solvedClearance(webView) ?: return false
        save(context, clearance)
        return true
    }

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
    // 2.0-dev1.4: concurrent ensures (detail section + download
    // sheet) used to start TWO hidden solves at once — two WebViews
    // hammering the challenge, racing the cookie store, and
    // doubling the lag. One solve at a time now: a caller that
    // arrives mid-solve waits and shares the result.
    private val ensureMutex = Mutex()

    suspend fun ensureClearance(activity: Activity?): ClearanceResult {
        val ctx = activity ?: return ClearanceResult.Failed
        if (activity.isFinishing || activity.isDestroyed) return ClearanceResult.Failed
        loadStored(ctx)?.let { return ClearanceResult.Cleared(it) }
        return ensureMutex.withLock {
            // Another caller may have stored one while we waited.
            loadStored(ctx)?.let { return@withLock ClearanceResult.Cleared(it) }
            solveHidden(activity)
        }
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
            // 2.0-dev1.5: the solve WebView is FULL-SIZE and VISIBLE,
            // added BEHIND the app's own UI (decor index 0) — never
            // 1x1 and never INVISIBLE. JS's phone probe (2026-11-08)
            // proved the old 1x1 invisible view NEVER completes the
            // challenge on its own: full 30 s poll, title stuck at
            // "Just a moment...", no cookie harvested. A 1x1
            // invisible WebView is throttled by Android (timers
            // paused, renderer stalled) and too small for the
            // challenge to lay out in — the check cannot run in it.
            // Full-size + VISIBLE + attached runs it like a real
            // browser tab; behind the UI means the user still sees
            // the normal detail page (skeletons, then episodes)
            // while it solves. If the challenge needs a tap, this
            // still times out and the visible dialog takes over —
            // the sizing fixes a stalled automatic check, it does
            // not (and cannot) tap a checkbox by itself.
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            webView.visibility = View.VISIBLE
            var result: ClearanceResult = ClearanceResult.Failed
            try {
                decor.addView(webView, 0, params)
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
