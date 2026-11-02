package com.shabakti.app.net

import android.content.Context

/** MAC prefix (OUI) → manufacturer, from the IEEE registry bundled as assets/oui.tsv. */
class VendorLookup(context: Context) {
    private val table: Map<String, String> by lazy {
        val map = HashMap<String, String>(40_000)
        context.assets.open("oui.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab == 6) map[line.substring(0, 6)] = line.substring(7)
            }
        }
        map
    }

    fun vendorOf(mac: String?): String? {
        if (mac == null) return null
        val hex = mac.replace(":", "").replace("-", "").uppercase()
        if (hex.length < 6) return null
        val first = hex.substring(0, 2).toIntOrNull(16) ?: return null
        if (first and 0x02 != 0) return null // randomized / private address
        return table[hex.substring(0, 6)]
    }
}
