package com.shabakti.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.shabakti.app.data.WifiStatus
import java.net.Inet4Address

class WifiStatusReader(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wm = app.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    fun read(): WifiStatus {
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!onWifi) return WifiStatus(connectedToWifi = false)

        // WifiManager.connectionInfo is deprecated but, unlike NetworkCapabilities.transportInfo,
        // still returns the real SSID when location permission is granted.
        val info: WifiInfo? = wm.connectionInfo
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps?.transportInfo as? WifiInfo else null

        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != WifiManager.UNKNOWN_SSID }
        val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }
        val rssi = info?.rssi?.takeIf { it in -120..0 }

        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?: wm.dhcpInfo?.gateway?.takeIf { it != 0 }?.let(::intToIp)

        return WifiStatus(
            connectedToWifi = true,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            signalPercent = rssi?.let { ((it + 100) * 2).coerceIn(0, 100) },
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            myIp = v4?.address?.hostAddress,
            prefixLength = v4?.prefixLength,
            gateway = gateway,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
        )
    }

    private fun intToIp(i: Int): String =
        "${i and 0xff}.${i shr 8 and 0xff}.${i shr 16 and 0xff}.${i shr 24 and 0xff}"
}
