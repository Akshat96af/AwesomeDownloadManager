package com.abdownloadmanager.desktop.engine

import ir.amirab.downloader.downloaditem.http.AdaptiveConnections
import ir.amirab.downloader.exception.UnSuccessfulResponseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveConnectionsTest {
    @Test
    fun startsAtFullStrengthAndDoublesOnlyWhileEveryConnectionDelivers() {
        val c = AdaptiveConnections()
        assertEquals(8, c.limitFor(16))
        c.onTick(0, activeConnections = 8, receivingConnections = 7, maxConnections = 16)
        assertEquals(8, c.limitFor(16)) // one still connecting: hold
        c.onTick(500, activeConnections = 8, receivingConnections = 8, maxConnections = 16)
        assertEquals(16, c.limitFor(16))
        c.onTick(1000, activeConnections = 16, receivingConnections = 16, maxConnections = 16)
        assertEquals(16, c.limitFor(16)) // never above the user's maximum
        assertEquals(4, c.limitFor(4))
    }

    @Test
    fun refusalsLowerTheCeilingAndItRecoversSlowly() {
        val c = AdaptiveConnections()
        c.onServerPushback(0, activeConnections = 8, maxConnections = 16)
        c.onServerPushback(10, activeConnections = 7, maxConnections = 16)
        assertEquals(6, c.limitFor(16))
        c.onTick(1_000, activeConnections = 6, receivingConnections = 6, maxConnections = 16)
        assertEquals(6, c.limitFor(16)) // cannot grow past the ceiling
        c.onTick(61_000, activeConnections = 6, receivingConnections = 6, maxConnections = 16)
        assertEquals(7, c.limitFor(16)) // +1 after a quiet minute
        repeat(20) { c.onServerPushback(70_000L + it, activeConnections = 1, maxConnections = 16) }
        assertEquals(1, c.limitFor(16)) // never below one
    }

    @Test
    fun onlyThrottlingCountsAsPushback() {
        assertTrue(AdaptiveConnections.isServerPushback(UnSuccessfulResponseException(429, "Too Many Requests")))
        assertTrue(AdaptiveConnections.isServerPushback(UnSuccessfulResponseException(503, "Service Unavailable")))
        assertFalse(AdaptiveConnections.isServerPushback(UnSuccessfulResponseException(404, "Not Found")))
        assertTrue(AdaptiveConnections.isServerPushback(java.net.ConnectException("refused")))
    }

    @Test
    fun backoffGrowsIsCappedAndHonoursRetryAfter() {
        assertEquals(1_000, AdaptiveConnections.retryDelayMillis(1, null, random = 0.5))
        assertEquals(8_000, AdaptiveConnections.retryDelayMillis(4, null, random = 0.5))
        assertEquals(15_000, AdaptiveConnections.retryDelayMillis(9, null, random = 0.5))
        assertEquals(12_000, AdaptiveConnections.retryDelayMillis(9, null, random = 0.0)) // jitter: 15 s * 0.8
        assertEquals(18_000, AdaptiveConnections.retryDelayMillis(9, null, random = 1.0)) // jitter: 15 s * 1.2
        assertEquals(30_000, AdaptiveConnections.retryDelayMillis(1, UnSuccessfulResponseException(429, "", retryAfterSeconds = 30)))
        assertEquals(120_000, AdaptiveConnections.retryDelayMillis(1, UnSuccessfulResponseException(429, "", retryAfterSeconds = 9999)))
    }
}
