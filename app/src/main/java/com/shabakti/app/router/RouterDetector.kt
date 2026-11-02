package com.shabakti.app.router

data class DetectedRouter(val baseUrl: String, val brand: RouterBrand, val title: String?)

object RouterDetector {

    /** Fetches the gateway's login page and guesses the vendor from its markup. */
    fun detect(gatewayIp: String): DetectedRouter {
        var lastError: Exception? = null
        for (scheme in listOf("http", "https")) {
            val base = "$scheme://$gatewayIp"
            try {
                val http = RouterHttp(base)
                val r = http.get("/")
                var brand = RouterParsers.detectBrand(r.body, r.headers)
                // Some routers serve a tiny frameset/redirect page first; follow one level.
                if (brand == RouterBrand.UNKNOWN) {
                    val next = Regex("""(?i)(?:location\.href|location|window\.location)\s*=\s*["']([^"']+)["']""")
                        .find(r.body)?.groupValues?.get(1)
                        ?: Regex("""(?i)<frame[^>]+src=["']([^"']+)["']""").find(r.body)?.groupValues?.get(1)
                    if (next != null) {
                        val r2 = runCatching { http.get(next) }.getOrNull()
                        if (r2 != null) brand = RouterParsers.detectBrand(r2.body, r2.headers)
                    }
                }
                val title = Regex("""(?is)<title>(.*?)</title>""").find(r.body)?.groupValues?.get(1)?.trim()
                val finalBase = r.finalUrl.toHttpBase() ?: base
                DebugLog.add("Detected router at $finalBase: $brand (title=$title)")
                return DetectedRouter(finalBase, brand, title)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw RouterException("مش قادر أفتح لوحة تحكم الراوتر على $gatewayIp", lastError?.message)
    }

    private fun String.toHttpBase(): String? =
        Regex("""^(https?://[^/]+)""").find(this)?.groupValues?.get(1)

    fun driverFor(router: DetectedRouter): RouterDriver? {
        val http = RouterHttp(router.baseUrl)
        return when (router.brand) {
            RouterBrand.HUAWEI_ONT -> HuaweiOntDriver(http)
            RouterBrand.ZTE -> ZteDriver(http)
            RouterBrand.TENDA -> TendaDriver(http)
            else -> null
        }
    }
}
