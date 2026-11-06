package com.abdownloadmanager.desktop.utils

import ir.amirab.downloader.connection.LocalNetwork
import ir.amirab.util.platform.Platform
import ir.amirab.util.platform.isWindows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The networks this computer can download over: up, not loopback, not virtual/VPN, with a routable address
 * (169.254.x.x and fe80:: are skipped). Addresses come from [NetworkInterface]; on Windows the adapter
 * names and kinds come from `Get-NetAdapter`, which is slow, so it runs only when the adapter set changes.
 */
class NetworkDiscovery {
    @Volatile
    private var snapshot: List<LocalNetwork> = emptyList()

    @Volatile
    private var takenAt = 0L
    private val refreshing = AtomicBoolean(false)

    private var adapters: Map<String, WindowsAdapter> = emptyMap()
    private var adaptersFor: Set<String>? = null
    private var adaptersTriedAt = Long.MIN_VALUE / 2

    /**
     * The last known networks, without waiting. While something keeps asking (downloads using every network),
     * a background refresh runs at most once a second, so networks that drop or appear are picked up.
     */
    fun current(): List<LocalNetwork> {
        if (System.currentTimeMillis() - takenAt >= 1_000 && refreshing.compareAndSet(false, true)) {
            thread(isDaemon = true, name = "network-discovery") {
                try {
                    refresh()
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return snapshot
    }

    /** Looks now (blocking; up to 15 s on Windows when the adapter set changed). */
    @Synchronized
    fun refresh(): List<LocalNetwork> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        val descriptions = interfaces.map { it.displayName.orEmpty() }.toSet()
        val now = System.currentTimeMillis()
        // a new adapter set is looked up at once; a failed lookup is retried at most once a minute
        // instead of spawning PowerShell every second
        val lookupFailedRecently = adapters.isEmpty() && now - adaptersTriedAt < 60_000
        if (Platform.isWindows() && descriptions != adaptersFor && !lookupFailedRecently) {
            adaptersTriedAt = now
            adapters = readWindowsAdapters()
            if (adapters.isNotEmpty()) adaptersFor = descriptions
        }
        val result = interfaces.mapNotNull { nif ->
            val description = nif.displayName.orEmpty()
            val adapter = adapters[description]
            // Get-NetAdapter worked but does not list it: a hidden filter or tunnel pseudo-interface
            if (adapters.isNotEmpty() && adapter == null) return@mapNotNull null
            if (isVirtual(description) || isVirtual(nif.name)) return@mapNotNull null
            val addresses = nif.inetAddresses.toList().filter {
                !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
            }
            if (addresses.isEmpty()) return@mapNotNull null
            LocalNetwork(
                name = adapter?.name ?: description.ifEmpty { nif.name },
                label = labelFor(description, adapter?.medium),
                addresses = addresses,
            )
        }
        snapshot = result
        takenAt = System.currentTimeMillis()
        return result
    }

    private class WindowsAdapter(val name: String, val medium: Int?)

    private fun readWindowsAdapters(): Map<String, WindowsAdapter> = runCatching {
        // output goes to a file so a slow or chatty PowerShell can never block on a full pipe
        val out = File.createTempFile("adm-adapters", ".json")
        try {
            val process = ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                        "Get-NetAdapter -ErrorAction Stop | Select-Object Name, InterfaceDescription, NdisPhysicalMedium | ConvertTo-Json -Compress",
            ).redirectOutput(out).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            // Plexo allows 5 s; Get-NetAdapter measured 9 s on a cold start on a real PC
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching emptyMap()
            }
            parseAdapters(out.readText(Charsets.UTF_8))
        } finally {
            out.delete()
        }
    }.getOrDefault(emptyMap())

    companion object {
        private val VIRTUAL = Regex(
            "virtual|hyper-v|vmware|virtualbox|vbox|\\btap\\b|tap-|wintun|wireguard|tunnel|vpn|teredo|isatap|" +
                    "loopback|bluetooth|docker|veth|virbr|zerotier|tailscale|npcap",
            RegexOption.IGNORE_CASE,
        )

        /** Adapters that do not reach the internet on their own: hosted hotspots, VM switches, VPN tunnels. */
        fun isVirtual(description: String) = VIRTUAL.containsMatchIn(description)

        /** NDIS physical medium: 1 = wireless LAN, 9 = native 802.11, 14 = 802.3 Ethernet. */
        fun labelFor(description: String, ndisPhysicalMedium: Int?): String {
            val d = description.lowercase()
            return when {
                ndisPhysicalMedium == 1 || ndisPhysicalMedium == 9 -> "Wi-Fi"
                Regex("rndis|remote ndis|tether|apple mobile device").containsMatchIn(d) -> "USB tether"
                ndisPhysicalMedium == 14 -> "Ethernet"
                Regex("wi-?fi|wireless|wlan|802\\.11").containsMatchIn(d) -> "Wi-Fi"
                Regex("ethernet|gbe|\\blan\\b").containsMatchIn(d) -> "Ethernet"
                else -> "Network"
            }
        }

        /** `ConvertTo-Json` gives an object for one adapter and an array for several. Keyed by description. */
        private fun parseAdapters(json: String): Map<String, WindowsAdapter> {
            val root = Json.parseToJsonElement(json.trim().removePrefix("\uFEFF").ifEmpty { "[]" })
            val items = (root as? JsonArray)?.toList() ?: listOf(root)
            return items.filterIsInstance<JsonObject>().mapNotNull { o ->
                val description = o["InterfaceDescription"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = o["Name"]?.jsonPrimitive?.content ?: description
                description to WindowsAdapter(name, o["NdisPhysicalMedium"]?.jsonPrimitive?.intOrNull)
            }.toMap()
        }
    }
}

/** Downloads above this size get the "use all networks" suggestion. */
const val SUGGEST_ALL_NETWORKS_ABOVE_BYTES = 500L * 1024 * 1024

/** Suggest using every network: a large download, the setting off, 2+ networks, and not suggested for this download yet. */
fun shouldSuggestAllNetworks(contentLength: Long, settingOn: Boolean, usableNetworks: Int, alreadySuggested: Boolean) =
    !settingOn && !alreadySuggested && usableNetworks >= 2 && contentLength > SUGGEST_ALL_NETWORKS_ABOVE_BYTES
