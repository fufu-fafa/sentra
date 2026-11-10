package com.sentra.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

data class FoundRelay(val url: String, val relayId: String)

/**
 * Finds a relay on whatever WiFi the phone is on (a laptop at home, or a phone hotspot at the
 * booth), so nobody has to type its address. A relay on the internet (the VPS) is typed in once. Asks every address in each of the phone's private
 * /24 networks for /v1/health and keeps the first that answers as the relay.
 */
object RelayDiscovery {
    const val PORT = 8787

    /** The phone's own private IPv4 networks as "a.b.c" prefixes, hotspot interfaces included. */
    fun localPrefixes(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!!.substringBeforeLast('.') }
            .distinct()
    }.getOrDefault(emptyList())

    /** [preferId] wins when several relays answer; null takes the first found. */
    suspend fun find(preferId: String? = null): FoundRelay? = withContext(Dispatchers.IO) {
        val hosts = localPrefixes().flatMap { p -> (1..254).map { "$p.$it" } }
        val gate = Semaphore(48)
        val found = coroutineScope {
            hosts.map { h -> async { gate.withPermit { probe("http://$h:$PORT") } } }.awaitAll().filterNotNull()
        }
        found.firstOrNull { it.relayId == preferId } ?: found.firstOrNull()
    }

    /** [probe] off the main thread. */
    suspend fun check(url: String): FoundRelay? = withContext(Dispatchers.IO) { probe(url) }

    /** The relay at [url], or null if nothing there answers like one. Blocks. */
    fun probe(url: String, timeoutMs: Int = 700): FoundRelay? = runCatching {
        val c = URL("$url/v1/health").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            if (j.optString("service") != "sentra-relay") return null
            FoundRelay(url, j.getString("relayId"))
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}

/** Re-finds the relay after its address changed, at most once a minute. */
class RelayFinder(
    private val settings: com.sentra.data.SettingsRepository,
    private val current: () -> com.sentra.data.AppSettings,
) {
    @Volatile private var lastTry = 0L

    /** Scans now; returns what was found and switches the app to it. */
    suspend fun findNow(): FoundRelay? {
        lastTry = System.currentTimeMillis()
        val found = RelayDiscovery.find(current().relayId) ?: return null
        val s = current()
        if (found.url != s.relayUrl || found.relayId != s.relayId) settings.setRelay(found.url, found.relayId)
        return found
    }

    /** After the relay couldn't be reached. */
    suspend fun afterFailure(): FoundRelay? =
        if (System.currentTimeMillis() - lastTry < 60_000) null else findNow()
}
