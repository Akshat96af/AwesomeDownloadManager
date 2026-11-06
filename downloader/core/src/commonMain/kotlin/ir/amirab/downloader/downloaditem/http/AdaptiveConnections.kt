package ir.amirab.downloader.downloaditem.http

import kotlin.math.max
import kotlin.math.min

/**
 * Decides how many connections a download should use right now.
 *
 * - Starts at full strength ([startConnections]) so a download is fast from the first second.
 * - Doubles while every connection is actually receiving data, up to the user's maximum.
 * - Each refusal from the server (429/503/509, refused or reset connections) lowers a ceiling by one,
 *   so the download settles on as many connections as the server tolerates instead of hammering it
 *   (measured against a public mirror: 8 simultaneous ranges got 4 refusals and then a temporary block).
 * - The ceiling recovers by one every [recoverAfterMs] without refusals.
 *
 * Measured throughput is deliberately not used: it is too noisy to judge one extra connection by.
 * Time is passed in, so the policy is testable.
 */
class AdaptiveConnections(
    private val startConnections: Int = 8,
    private val recoverAfterMs: Long = 60_000,
) {
    private var limit = startConnections
    private var ceiling = Int.MAX_VALUE
    private var lastRefusalAt: Long? = null

    /** The connection count to use when the user allows at most [maxConnections]. */
    fun limitFor(maxConnections: Int): Int = min(limit, ceiling).coerceIn(1, max(1, maxConnections))

    /** Call about twice a second with how many connections run and how many of them are receiving data. */
    fun onTick(nowMs: Long, activeConnections: Int, receivingConnections: Int, maxConnections: Int) {
        val refusedAt = lastRefusalAt
        if (refusedAt != null && ceiling < maxConnections && nowMs - refusedAt >= recoverAfterMs) {
            ceiling++
            lastRefusalAt = nowMs
        }
        val current = limitFor(maxConnections)
        // grow only when every slot is in use and every connection is delivering
        if (activeConnections >= current && receivingConnections >= current) {
            limit = min(current * 2, min(ceiling, maxConnections))
        }
    }

    /** One connection was refused or throttled by the server. */
    fun onServerPushback(nowMs: Long, activeConnections: Int, maxConnections: Int) {
        ceiling = max(1, min(limitFor(maxConnections), activeConnections) - 1)
        limit = min(limit, ceiling)
        lastRefusalAt = nowMs
    }

    companion object {
        /** HTTP answers that mean "too many requests from you", not "this file is broken". */
        private val PUSHBACK_CODES = setOf(429, 503, 509)

        fun isServerPushback(error: Throwable): Boolean = when (error) {
            is ir.amirab.downloader.exception.UnSuccessfulResponseException -> error.code in PUSHBACK_CODES
            is java.net.ConnectException -> true
            is java.net.SocketException -> error.message?.contains("reset", ignoreCase = true) == true
            else -> false
        }

        /**
         * Wait before retry number [tries]: 1 s, 2 s, 4 s ... capped at 15 s, with ±20% jitter so parts
         * do not retry in lockstep, or what the server asked for with Retry-After (capped at 2 minutes).
         * The old fixed 1 s retry kept hammering servers that were already refusing.
         */
        fun retryDelayMillis(tries: Int, lastError: Throwable?, random: Double = Math.random()): Long {
            val retryAfter = (lastError as? ir.amirab.downloader.exception.UnSuccessfulResponseException)?.retryAfterSeconds
            if (retryAfter != null) return (retryAfter * 1000).coerceIn(1_000, 120_000)
            val base = min(1_000L shl (tries - 1).coerceIn(0, 4), 15_000L)
            return (base * (0.8 + 0.4 * random)).toLong()
        }
    }
}
