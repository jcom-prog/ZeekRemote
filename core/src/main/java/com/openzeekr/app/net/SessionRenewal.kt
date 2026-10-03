package com.openzeekr.app.net

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Interceptor
import okhttp3.Response

/**
 * When the cloud sign-in is renewed (field 03/10: the TSP bearer expired after about two weeks and
 * every cloud call failed with `079012 Token expired`; the only way back was Sign out, which also
 * revokes the digital key).
 *
 *  - reactive: a `079012` response triggers a silent re-login;
 *  - proactive: a re-login when the bearer's own expiry (JWT `exp`) is less than a day away;
 *  - manual: Settings › Refresh sign-in.
 * Automatic attempts are at most one per [MIN_AUTO_INTERVAL_MS] and stop after
 * [MAX_AUTO_FAILURES] consecutive failures (e.g. a changed password) until a manual refresh, so a
 * bad password can never hammer the account. Re-login never touches the digital key.
 * `079021` (signed in elsewhere) is NOT a renewal trigger: fighting the official app would log it
 * out in turn.
 */
internal object ReloginPolicy {
    const val MIN_AUTO_INTERVAL_MS = 10 * 60_000L
    const val MAX_AUTO_FAILURES = 3
    const val RENEW_BEFORE_EXPIRY_MS = 24 * 60 * 60_000L
    const val TOKEN_EXPIRED_CODE = "079012"

    fun autoAllowed(nowMs: Long, lastAttemptAtMs: Long, consecutiveFailures: Int): Boolean =
        consecutiveFailures < MAX_AUTO_FAILURES &&
            (lastAttemptAtMs <= 0L || nowMs - lastAttemptAtMs >= MIN_AUTO_INTERVAL_MS || nowMs < lastAttemptAtMs)

    fun expiresSoon(nowMs: Long, expiresAtMs: Long): Boolean =
        expiresAtMs > 0L && expiresAtMs - nowMs <= RENEW_BEFORE_EXPIRY_MS

    private val expiredCode = Regex("\"code\"\\s*:\\s*\"?$TOKEN_EXPIRED_CODE\"?(?![0-9])")

    /** True only for a response whose `code` field is 079012 (not any digits elsewhere in the body). */
    fun isTokenExpired(body: String?): Boolean = body != null && expiredCode.containsMatchIn(body)

    /** A failure that never reached a server verdict (offline, timeout): not counted toward the stop. */
    fun isNetworkFailure(t: Throwable?): Boolean =
        generateSequence(t) { it.cause }.take(8).any { it is java.io.IOException }

    /** JWT `exp` (epoch seconds) to epoch ms; null when absent or implausible. */
    fun expiryMs(expClaim: String?): Long? =
        expClaim?.toLongOrNull()?.takeIf { it in 1_500_000_000L..4_000_000_000L }?.times(1000L)
}

/** Raised by [TokenExpiryInterceptor] when the cloud reports `079012 Token expired`. */
object TokenExpirySignal {
    @Volatile var listener: (() -> Unit)? = null
    fun raise() { listener?.invoke() }
}

/** Peeks small responses for `079012` (never consumes the body). */
class TokenExpiryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        val body = runCatching { resp.peekBody(1024).string() }.getOrNull()
        if (ReloginPolicy.isTokenExpired(body)) TokenExpirySignal.raise()
        return resp
    }
}

class SessionRenewer(
    private val store: ConfigStore,
    private val scope: CoroutineScope,
    private val login: suspend () -> Result<Unit> = { AccountLogin(store).login() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(val inFlight: Boolean = false, val autoStopped: Boolean = false, val message: String? = null)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(State(autoStopped = autoStopped()))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun autoStopped() = store.current().reloginFailures >= ReloginPolicy.MAX_AUTO_FAILURES

    /** A cloud call came back with `079012`. */
    fun onTokenExpired() { scope.launch { renew(manual = false, reason = "token expired") } }

    /** Cheap check, called from existing foreground ticks; renews ahead of the bearer's expiry. */
    fun maybeRenewAhead() {
        val cfg = store.current()
        if (cfg.accessToken.isBlank() || !ReloginPolicy.expiresSoon(now(), cfg.accessTokenExpiresAtMs)) return
        scope.launch { renew(manual = false, reason = "expires soon") }
    }

    /** Settings › Refresh sign-in. Always tries (unless one is running) and re-enables auto renewal. */
    suspend fun refreshNow(): Boolean = renew(manual = true, reason = "manual")

    private suspend fun renew(manual: Boolean, reason: String): Boolean {
        if (!mutex.tryLock()) {
            if (manual) _state.value = _state.value.copy(message = "A sign-in refresh is already running")
            return false
        }
        try {
            val cfg = store.current()
            // Signed out, or kicked out by 079021 (the official app holds the session): never fight it.
            if (SessionSignal.loggedInElsewhere.value || cfg.accessToken.isBlank() ||
                cfg.email.isBlank() || cfg.password.isBlank()) {
                if (manual) _state.value = State(autoStopped = autoStopped(), message =
                    if (SessionSignal.loggedInElsewhere.value) "Signed in elsewhere - sign in again in Settings"
                    else "No saved sign-in to refresh")
                return false
            }
            val t = now()
            if (!manual && !ReloginPolicy.autoAllowed(t, cfg.reloginLastAttemptAtMs, cfg.reloginFailures)) return false
            if (!manual && reason == "expires soon" && !ReloginPolicy.expiresSoon(t, cfg.accessTokenExpiresAtMs)) return false
            store.update { it.copy(reloginLastAttemptAtMs = t, reloginFailures = if (manual) 0 else it.reloginFailures) }
            _state.value = State(inFlight = true)
            Logx.d("session", "re-login start ($reason)")
            val kickoutsBefore = SessionSignal.kickouts
            val result = login()
            var ok = result.isSuccess
            var yielded = false
            // Single-car app: a renewal keeps the active car exactly as it was.
            if (ok) store.update { it.copy(vin = cfg.vin.ifBlank { it.vin }, isOwner = if (cfg.vin.isNotBlank()) cfg.isOwner else it.isOwner) }
            if (ok && SessionSignal.kickouts != kickoutsBefore) {
                // The official app took the session while we were renewing: yield to it (same state as
                // a normal 079021 kick-out). The UI may already have reset loggedInElsewhere, so the
                // monotonic counter is checked instead (review 0.1.58).
                store.update { it.copy(accessToken = "") }
                ok = false
                yielded = true
                Logx.w("session", "re-login yielded: signed in elsewhere during renewal")
            }
            val offline = !ok && ReloginPolicy.isNetworkFailure(result.exceptionOrNull())
            if (ok) {
                store.update { it.copy(reloginFailures = 0) }
                Logx.d("session", "re-login OK; valid until ${expiryLabel(store.current().accessTokenExpiresAtMs)}")
            } else if (offline || yielded) {
                if (offline) Logx.w("session", "re-login not possible (no connection); not counted")
            } else {
                store.update { it.copy(reloginFailures = it.reloginFailures + 1) }
                Logx.w("session", "re-login failed (${store.current().reloginFailures} in a row)")
            }
            val stopped = autoStopped()
            _state.value = State(
                autoStopped = stopped,
                message = when {
                    ok -> "Signed in again ✓"
                    yielded -> "Signed in elsewhere (official app) - sign in again in Settings"
                    offline -> "No connection - sign-in will be refreshed later"
                    stopped -> "Automatic sign-in stopped after ${ReloginPolicy.MAX_AUTO_FAILURES} failures - tap Refresh sign-in"
                    else -> "Sign-in refresh failed - will retry automatically"
                },
            )
            return ok
        } finally {
            mutex.unlock()
        }
    }

    private fun expiryLabel(ms: Long): String =
        if (ms <= 0L) "unknown" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))
}
