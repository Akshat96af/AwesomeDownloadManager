package ir.amirab.downloader.connection

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/** A network this computer is connected to, and the local addresses its connections leave from. */
data class LocalNetwork(
    val name: String,
    val label: String,
    val addresses: List<InetAddress>,
)

/**
 * Picks the network for each new download connection: round-robin over the networks that work.
 * The engine hands idle connections half of the largest remaining part, so a faster network
 * finishes its pieces sooner, asks for more, and ends up carrying more of the file.
 *
 * A network whose connections fail for [downAfterMs] with no success in between is dropped from
 * the rotation; it gets one trial connection every [probeEveryMs] until one succeeds.
 */
class NetworkRotation(
    private val downAfterMs: Long = 5_000,
    private val probeEveryMs: Long = 5_000,
) {
    private class Health(val failingSince: Long, var lastAttempt: Long)

    private val failing = HashMap<String, Health>()
    private var next = 0

    /** The network for the next connection, or null to let the OS route it (fewer than two networks). */
    @Synchronized
    fun pick(networks: List<LocalNetwork>, nowMs: Long): LocalNetwork? {
        if (networks.size < 2) return null
        failing.keys.retainAll(networks.map { it.name }.toSet())
        networks.firstOrNull { isDown(it, nowMs) && nowMs - failing.getValue(it.name).lastAttempt >= probeEveryMs }
            ?.let { probe ->
                failing.getValue(probe.name).lastAttempt = nowMs
                return probe
            }
        // if every network is down, keep using them all rather than stalling the download
        val usable = networks.filter { !isDown(it, nowMs) }.ifEmpty { networks }
        return usable[Math.floorMod(next++, usable.size)]
    }

    @Synchronized
    fun isDown(network: LocalNetwork, nowMs: Long): Boolean {
        val health = failing[network.name] ?: return false
        return nowMs - health.failingSince >= downAfterMs
    }

    @Synchronized
    fun onConnected(network: LocalNetwork) {
        failing.remove(network.name)
    }

    @Synchronized
    fun onFailed(network: LocalNetwork, nowMs: Long) {
        val health = failing[network.name]
        if (health == null) failing[network.name] = Health(nowMs, nowMs) else health.lastAttempt = nowMs
    }
}

/**
 * Sends download connections out over every network when [isEnabled].
 * Each network gets its own client whose sockets are bound to that network's local address; Windows then
 * routes the connection by source address. DNS still goes through the base client's resolver; its answers
 * are narrowed to the address families the network has (an IPv4-only network connects to IPv4 servers).
 * Not covered: SOCKS proxies (OkHttp opens those sockets itself, so they use the OS route).
 */
class MultiNetwork(
    private val isEnabled: () -> Boolean,
    private val networks: () -> List<LocalNetwork>,
    private val rotation: NetworkRotation = NetworkRotation(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val clients = ConcurrentHashMap<LocalNetwork, OkHttpClient>()

    /** Runs [block] with the client for the next network (or [base] when not in use) and tracks how that network does. */
    suspend fun <T> connect(base: OkHttpClient, block: suspend (OkHttpClient) -> T): T {
        val network = if (isEnabled()) rotation.pick(networks(), now()) else null
        if (network == null) return block(base)
        // cached per network: OkHttp pools connections per (dns, socket factory), so reuse needs the same instances
        val client = clients.getOrPut(network) { clientFor(base, network) }
        return try {
            block(client).also { rotation.onConnected(network) }
        } catch (e: IOException) {
            rotation.onFailed(network, now())
            throw e
        }
    }

    companion object {
        fun clientFor(base: OkHttpClient, network: LocalNetwork): OkHttpClient {
            val baseDns = base.dns
            val hasV4 = network.addresses.any { it is Inet4Address }
            val hasV6 = network.addresses.any { it !is Inet4Address }
            return base.newBuilder()
                .socketFactory(BoundSocketFactory(network.addresses))
                .dns(Dns { host ->
                    baseDns.lookup(host)
                        .filter { if (it is Inet4Address) hasV4 else hasV6 }
                        .ifEmpty { throw UnknownHostException("$host has no address reachable over ${network.name}") }
                })
                .build()
        }
    }
}

/** Sockets that, just before connecting, bind to the local address of the same family as the server. */
class BoundSocketFactory(private val localAddresses: List<InetAddress>) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val remote = (endpoint as? InetSocketAddress)?.address
            if (remote != null && !isBound) {
                val local = localAddresses.firstOrNull { (it is Inet4Address) == (remote is Inet4Address) }
                    ?: throw ConnectException("no local address of the same family as $remote")
                bind(InetSocketAddress(local, 0))
            }
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(address, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        Socket(address, port, localAddress, localPort)
}
