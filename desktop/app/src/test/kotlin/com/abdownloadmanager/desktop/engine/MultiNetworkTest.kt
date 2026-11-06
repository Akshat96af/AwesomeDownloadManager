package com.abdownloadmanager.desktop.engine

import com.abdownloadmanager.desktop.utils.NetworkDiscovery
import com.abdownloadmanager.desktop.utils.shouldSuggestAllNetworks
import ir.amirab.downloader.connection.BoundSocketFactory
import ir.amirab.downloader.connection.LocalNetwork
import ir.amirab.downloader.connection.MultiNetwork
import ir.amirab.downloader.connection.NetworkRotation
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiNetworkTest {
    private val loopback4 = InetAddress.getByName("127.0.0.1")
    private val wifi = LocalNetwork("WiFi", "Wi-Fi", listOf(InetAddress.getByName("192.168.1.10")))
    private val ethernet = LocalNetwork("Ethernet", "Ethernet", listOf(InetAddress.getByName("10.0.0.5")))
    private val usb = LocalNetwork("Phone", "USB tether", listOf(InetAddress.getByName("192.168.42.20")))

    @Test
    fun oneNetworkIsLeftToTheOsAndSeveralAreUsedInTurn() {
        val r = NetworkRotation()
        assertNull(r.pick(emptyList(), 0))
        assertNull(r.pick(listOf(wifi), 0))
        val all = listOf(wifi, ethernet, usb)
        assertEquals(listOf(wifi, ethernet, usb, wifi, ethernet, usb), List(6) { r.pick(all, 0) })
    }

    @Test
    fun aNetworkFailingForFiveSecondsIsDroppedThenProbedEveryFiveSeconds() {
        val r = NetworkRotation()
        val all = listOf(wifi, ethernet)
        r.onFailed(ethernet, 0)
        // still in rotation while failing for less than 5 s
        assertTrue(ethernet in List(4) { r.pick(all, 4_000) })
        r.onFailed(ethernet, 4_000)
        // down: only Wi-Fi, until 5 s after the last attempt
        assertEquals(List(4) { wifi }, List(4) { r.pick(all, 6_000) })
        // one probe at 9 s, then Wi-Fi again
        assertEquals(ethernet, r.pick(all, 9_000))
        assertEquals(List(3) { wifi }, List(3) { r.pick(all, 9_500) })
        // the probe connected: back in rotation
        r.onConnected(ethernet)
        assertTrue(ethernet in List(2) { r.pick(all, 10_000) })
    }

    @Test
    fun whenEveryNetworkIsDownAllAreStillUsed() {
        val r = NetworkRotation(probeEveryMs = Long.MAX_VALUE)
        val all = listOf(wifi, ethernet)
        all.forEach { r.onFailed(it, 0) }
        assertEquals(setOf(wifi, ethernet), List(4) { r.pick(all, 10_000) }.toSet())
    }

    @Test
    fun boundSocketsLeaveFromTheChosenLocalAddress() {
        ServerSocket(0, 1, loopback4).use { server ->
            BoundSocketFactory(listOf(loopback4)).createSocket().use { socket ->
                socket.connect(InetSocketAddress(loopback4, server.localPort), 2_000)
                assertEquals(loopback4, socket.localAddress)
            }
        }
    }

    @Test
    fun dnsIsNarrowedToTheNetworksAddressFamily() {
        val v6 = InetAddress.getByName("::1")
        val base = OkHttpClient.Builder().dns(Dns { listOf(v6, loopback4) }).build()
        val v4Only = LocalNetwork("WiFi", "Wi-Fi", listOf(loopback4))
        assertEquals(listOf(loopback4), MultiNetwork.clientFor(base, v4Only).dns.lookup("example.test"))
    }

    @Test
    fun hotspotsAndVirtualAdaptersAreNotNetworksAndKindsAreLabelled() {
        assertTrue(NetworkDiscovery.isVirtual("Microsoft Wi-Fi Direct Virtual Adapter #2"))
        assertTrue(NetworkDiscovery.isVirtual("Hyper-V Virtual Ethernet Adapter"))
        assertTrue(NetworkDiscovery.isVirtual("TAP-Windows Adapter V9"))
        assertFalse(NetworkDiscovery.isVirtual("Intel(R) Wi-Fi 6 AX201 160MHz"))
        assertFalse(NetworkDiscovery.isVirtual("Realtek PCIe GbE Family Controller"))
        assertEquals("Wi-Fi", NetworkDiscovery.labelFor("Intel(R) Wi-Fi 6 AX201 160MHz", 9))
        assertEquals("Ethernet", NetworkDiscovery.labelFor("Realtek PCIe GbE Family Controller", 14))
        assertEquals("USB tether", NetworkDiscovery.labelFor("Remote NDIS based Internet Sharing Device", 14))
        assertEquals("Wi-Fi", NetworkDiscovery.labelFor("Some Wireless LAN card", null))
    }

    @Test
    fun suggestionOnlyForLargeDownloadsWithTwoNetworksOncePerDownload() {
        val big = 600L * 1024 * 1024
        assertTrue(shouldSuggestAllNetworks(big, settingOn = false, usableNetworks = 2, alreadySuggested = false))
        assertFalse(shouldSuggestAllNetworks(500L * 1024 * 1024, false, 2, false))
        assertFalse(shouldSuggestAllNetworks(-1, false, 2, false))
        assertFalse(shouldSuggestAllNetworks(big, false, 1, false))
        assertFalse(shouldSuggestAllNetworks(big, true, 3, false))
        assertFalse(shouldSuggestAllNetworks(big, false, 2, alreadySuggested = true))
    }

    /**
     * Live check, off by default: set ADM_LIVE_NETWORKS=1 to list the discovered networks and fetch 4 MiB
     * over each one with its sockets bound to that network's local address.
     */
    @Test
    fun liveDownloadOverEachNetwork() {
        if (System.getenv("ADM_LIVE_NETWORKS") != "1") return
        val networks = NetworkDiscovery().refresh()
        println("LIVE networks: " + networks.joinToString { "${it.name} (${it.label}) ${it.addresses.map { a -> a.hostAddress }}" })
        val base = OkHttpClient()
        val extra = System.getenv("ADM_LIVE_EXTRA_BIND")?.let {
            listOf(LocalNetwork("extra", "extra", listOf(InetAddress.getByName(it))))
        }.orEmpty()
        for (network in networks + extra) {
            val started = System.nanoTime()
            val result = runCatching {
                MultiNetwork.clientFor(base, network).newCall(
                    Request.Builder().url("https://proof.ovh.net/files/10Mb.dat").header("Range", "bytes=0-4194303").build()
                ).execute().use { "HTTP ${it.code}, ${it.body.bytes().size} bytes" }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            println("LIVE ${network.name} ${network.addresses.map { it.hostAddress }}: ${result.getOrElse { "failed: $it" }} in %.1f s".format(seconds))
        }
    }
}
