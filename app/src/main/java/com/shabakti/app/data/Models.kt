package com.shabakti.app.data

/** Snapshot of the phone's current Wi-Fi connection. */
data class WifiStatus(
    val connectedToWifi: Boolean = false,
    val hasInternet: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val signalPercent: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val myIp: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
) {
    val band: String?
        get() = frequencyMhz?.let {
            when {
                it in 2400..2500 -> "2.4 GHz"
                it in 4900..5900 -> "5 GHz"
                it in 5925..7125 -> "6 GHz"
                else -> "$it MHz"
            }
        }
}

enum class Connection { WIFI, LAN, UNKNOWN }

/** A device on the network, merged from the LAN scan and (when logged in) the router. */
data class NetDevice(
    val ip: String?,
    val mac: String? = null,
    val hostName: String? = null,
    val vendor: String? = null,
    val online: Boolean = true,
    val connection: Connection = Connection.UNKNOWN,
    val isMe: Boolean = false,
    val isGateway: Boolean = false,
    val fromRouter: Boolean = false,
    val responseMs: Long? = null,
) {
    /** Stable key: MAC when known (survives IP changes), else IP. */
    val key: String get() = mac?.uppercase() ?: ip ?: "?"

    /** Android/iOS "private address" MACs have the locally-administered bit set. */
    val isRandomMac: Boolean
        get() {
            val first = mac?.take(2)?.toIntOrNull(16) ?: return false
            return first and 0x02 != 0
        }
}
