package com.shabakti.app.net

import com.shabakti.app.data.NetDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds live hosts on the local subnet without any router access: ICMP/echo via
 * isReachable plus quick TCP probes on common ports (a "connection refused" still proves
 * the host is up). Works on every router and every ISP.
 */
class LanScanner {

    private val probePorts = intArrayOf(80, 443, 53, 445, 139, 62078, 8080, 554, 7000, 8008, 5555)

    suspend fun scan(
        myIp: String,
        prefixLength: Int,
        gateway: String?,
        onProgress: (Float) -> Unit,
    ): List<NetDevice> = withContext(Dispatchers.IO) {
        val targets = hostsInSubnet(myIp, prefixLength)
        val done = AtomicInteger(0)
        val gate = Semaphore(48)
        val arp = readArpTable()

        val found = coroutineScope {
            targets.map { ip ->
                async {
                    gate.withPermit {
                        val t0 = System.nanoTime()
                        val alive = ip == myIp || isAlive(ip) || arp.containsKey(ip)
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        onProgress(done.incrementAndGet().toFloat() / targets.size)
                        if (!alive) null else NetDevice(
                            ip = ip,
                            mac = arp[ip],
                            isMe = ip == myIp,
                            isGateway = ip == gateway,
                            responseMs = if (ip == myIp) null else ms,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Reverse-DNS names (most home routers answer with the DHCP host name).
        coroutineScope {
            found.map { d ->
                async {
                    val name = withTimeoutOrNull(1500) {
                        runCatching { InetAddress.getByName(d.ip).canonicalHostName }.getOrNull()
                    }?.takeIf { it != d.ip && !it.equals("localhost", true) }
                    val arpAfter = d.mac ?: readArpTable()[d.ip]
                    d.copy(hostName = name?.removeSuffix(".lan")?.removeSuffix(".home"), mac = arpAfter)
                }
            }.awaitAll()
        }
    }

    private fun isAlive(ip: String): Boolean {
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        if (runCatching { addr.isReachable(350) }.getOrDefault(false)) return true
        for (port in probePorts) {
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 180) }
                return true
            } catch (e: ConnectException) {
                // ECONNREFUSED means a host answered.
                if (e.message?.contains("refused", true) == true) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** /proc/net/arp is readable up to Android 9; newer versions return nothing (handled). */
    private fun readArpTable(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to p[3].uppercase() else null
        }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        fun hostsInSubnet(myIp: String, prefixLength: Int): List<String> {
            val ip = ipToInt(myIp)
            // Large subnets (e.g. /16) are capped to the phone's own /24 to keep scans fast.
            val prefix = prefixLength.coerceIn(24, 30)
            val mask = (-1 shl (32 - prefix))
            val network = ip and mask
            val count = (1 shl (32 - prefix)) - 2
            return (1..count).map { intToIp(network + it) }
        }

        fun ipToInt(ip: String): Int =
            ip.split('.').fold(0) { acc, part -> (acc shl 8) or (part.toInt() and 0xff) }

        fun intToIp(v: Int): String =
            "${v ushr 24 and 0xff}.${v ushr 16 and 0xff}.${v ushr 8 and 0xff}.${v and 0xff}"
    }
}
