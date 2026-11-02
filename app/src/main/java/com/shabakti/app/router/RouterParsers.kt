package com.shabakti.app.router

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure parsing helpers (no Android dependencies) so they can be unit-tested on the JVM.
 */
object RouterParsers {

    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val MAC = Regex("""^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$""")

    data class RawDevice(
        val mac: String,
        val ip: String?,
        val hostName: String?,
        val online: Boolean,
        val wifi: Boolean?,
        val blocked: Boolean = false,
    )

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun normalizeMac(mac: String): String = mac.trim().replace('-', ':').uppercase()

    /** Decodes JS string escapes such as \x2e, A, \" and \\. */
    fun unescapeJs(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'x' && i + 3 < s.length -> {
                        val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                        if (hex != null) { out.append(hex.toChar()); i += 4; continue }
                    }
                    n == 'u' && i + 5 < s.length -> {
                        val hex = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (hex != null) { out.append(hex.toChar()); i += 6; continue }
                    }
                    n == 'n' -> { out.append('\n'); i += 2; continue }
                    n == 't' -> { out.append('\t'); i += 2; continue }
                    else -> { out.append(n); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- Huawei ONT

    /** Splits the argument list of a JS call into string literals (other tokens kept raw). */
    private fun splitJsArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < args.length) {
            val c = args[i]
            if (quote != null) {
                if (c == '\\' && i + 1 < args.length) { cur.append(c).append(args[i + 1]); i += 2; continue }
                if (c == quote) { quote = null } else cur.append(c)
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    ',' -> { result.add(unescapeJs(cur.toString().trim())); cur.clear() }
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (cur.isNotBlank() || result.isNotEmpty()) result.add(unescapeJs(cur.toString().trim()))
        return result
    }

    /**
     * Parses /html/bbsp/common/GetLanUserDevInfo.asp. The page builds
     * `new USERDevice("domain","ip","mac",...)` objects. Field order differs between firmwares,
     * so the parameter names from `function USERDevice(...)` are used when the page declares
     * them, with value-shape heuristics as a fallback.
     */
    fun parseHuaweiUserDevices(js: String): List<RawDevice> {
        val sig = Regex("""function\s+USERDevice\s*\(([^)]*)\)""").find(js)
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase() }
        val hostIdx = sig?.indexOfFirst { it == "hostname" }?.takeIf { it >= 0 }
        val statusIdx = sig?.indexOfFirst { it == "devstatus" }?.takeIf { it >= 0 }
        val portIdx = sig?.indexOfFirst { it == "porttype" || it == "port" }?.takeIf { it >= 0 }

        val devices = mutableListOf<RawDevice>()
        val call = Regex("""new\s+USERDevice\s*\(""")
        for (m in call.findAll(js)) {
            // Find the matching closing parenthesis, honouring quoted strings.
            var i = m.range.last + 1
            var depth = 1
            var quote: Char? = null
            val start = i
            while (i < js.length && depth > 0) {
                val c = js[i]
                if (quote != null) {
                    if (c == '\\') i++ else if (c == quote) quote = null
                } else when (c) {
                    '"', '\'' -> quote = c
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            if (depth != 0) continue
            val args = splitJsArgs(js.substring(start, i - 1))
            val mac = args.firstOrNull { MAC.matches(it) } ?: continue
            val ip = args.firstOrNull { IPV4.matches(it) }
            val status = statusIdx?.let { args.getOrNull(it) }
                ?: args.firstOrNull { it.equals("online", true) || it.equals("offline", true) }
            val host = (hostIdx?.let { args.getOrNull(it) } ?: args.getOrNull(9))
                ?.takeIf { it.isNotBlank() && !IPV4.matches(it) && !MAC.matches(it) && it != "--" }
            val portInfo = (portIdx?.let { args.getOrNull(it) } ?: args.joinToString(" ")).uppercase()
            val wifi = when {
                portInfo.contains("SSID") || portInfo.contains("WIFI") || portInfo.contains("WLAN") -> true
                portInfo.contains("LAN") || portInfo.contains("ETH") -> false
                else -> null
            }
            devices.add(
                RawDevice(
                    mac = normalizeMac(mac),
                    ip = ip,
                    hostName = host,
                    online = status == null || status.equals("online", true),
                    wifi = wifi,
                )
            )
        }
        return devices
    }

    /** GetRandCount.asp returns the token, sometimes prefixed with a BOM or junk. */
    fun parseHuaweiToken(body: String): String {
        val t = body.replace("﻿", "").trim()
        return if (t.length > 48) t.takeLast(48) else t
    }

    // ---------------------------------------------------------------- ZTE

    fun parseZteSessionToken(json: String): String? =
        Regex(""""sess_token"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            ?.let { unescapeJs(it) }?.takeIf { it.isNotEmpty() }

    fun parseZteLoginToken(xml: String): String? =
        Regex("""<ajax_response_xml_root>\s*([^<\s]+)\s*</ajax_response_xml_root>""")
            .find(xml)?.groupValues?.get(1)

    fun jsonInt(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*"?(-?\d+)"?""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    fun jsonString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    fun zteError(xml: String): String? {
        val err = Regex("""<IF_ERRORSTR>([^<]*)</IF_ERRORSTR>""").find(xml)?.groupValues?.get(1)?.trim()
        return err?.takeIf { it.isNotEmpty() && it !in setOf("SUCC", "SUCCESS", "OK") }
    }

    /**
     * Parses ZTE menuData responses: every <Instance> holds alternating
     * <ParaName>/<ParaValue> children. Works for OBJ_ACCESSDEV_ID, OBJ_WLAN_AD_ID and friends.
     */
    fun parseZteDevices(xml: String, wifiHint: Boolean?): List<RawDevice> {
        if (!xml.contains("<Instance")) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = runCatching {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<RawDevice>()
        val instances = doc.getElementsByTagName("Instance")
        for (n in 0 until instances.length) {
            val inst = instances.item(n) as? Element ?: continue
            val fields = mutableMapOf<String, String>()
            var pendingName: String? = null
            val kids = inst.childNodes
            for (k in 0 until kids.length) {
                val el = kids.item(k) as? Element ?: continue
                when (el.tagName) {
                    "ParaName" -> pendingName = el.textContent?.trim()
                    "ParaValue" -> {
                        pendingName?.let { fields[it] = el.textContent?.trim().orEmpty() }
                        pendingName = null
                    }
                }
            }
            val mac = (fields["MACAddress"] ?: fields["PhysAddress"] ?: fields["MacAddr"])
                ?.takeIf { MAC.matches(it) } ?: continue
            val active = fields["Active"]?.lowercase()
            val iface = (fields["AliasName"].orEmpty() + fields["Layer1Interface"].orEmpty() +
                fields["SSID"].orEmpty() + fields["ConnectType"].orEmpty()).uppercase()
            val wifi = when {
                iface.contains("SSID") || iface.contains("WLAN") || iface.contains("WIFI") -> true
                iface.contains("LAN") || iface.contains("ETH") -> false
                else -> wifiHint
            }
            out.add(
                RawDevice(
                    mac = normalizeMac(mac),
                    ip = fields["IPAddress"]?.takeIf { IPV4.matches(it) },
                    hostName = fields["HostName"]?.takeIf { it.isNotBlank() },
                    online = active == null || active in setOf("1", "true", "yes"),
                    wifi = wifi,
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- Tenda

    /** Flat JSON objects ({...} without nesting) inside the array stored under [key]. */
    private fun jsonArrayObjects(json: String, key: String?): List<String> {
        val area = if (key == null) json else
            Regex(""""$key"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
                ?: return emptyList()
        return Regex("""\{[^{}]*\}""").findAll(area).map { it.value }.toList()
    }

    private fun jsonAny(obj: String, key: String): String? =
        Regex(""""$key"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|(-?[\w.]+))""").find(obj)?.let { m ->
            m.groups[1]?.value?.let(::unescapeJs) ?: m.groups[2]?.value
        }

    private fun cleanName(vararg names: String?): String? =
        names.firstOrNull { !it.isNullOrBlank() && !it.equals("Unknown", true) && it != "--" }?.trim()

    /** Newer Tenda firmware (F3/N301/F6/AC5/AC6 V12, "ecos"): GET /goform/getQos. */
    fun parseTendaQos(json: String): List<RawDevice> {
        val out = mutableListOf<RawDevice>()
        fun rows(key: String, blocked: Boolean) {
            for (o in jsonArrayObjects(json, key)) {
                val mac = jsonAny(o, "qosListMac")?.takeIf { MAC.matches(it) } ?: continue
                out.add(
                    RawDevice(
                        mac = normalizeMac(mac),
                        ip = jsonAny(o, "qosListIP")?.takeIf { IPV4.matches(it) },
                        hostName = cleanName(jsonAny(o, "qosListRemark"), jsonAny(o, "qosListHostname")),
                        online = !blocked,
                        wifi = when (jsonAny(o, "qosListConnectType")?.lowercase()) {
                            "wifi" -> true; "wires", "wire", "wired" -> false; else -> null
                        },
                        blocked = blocked || jsonAny(o, "qosListAccess") == "false",
                    )
                )
            }
        }
        rows("onlineList", false)
        rows("blackList", true)
        return out
    }

    /** One row of the qosList string that /goform/setQos expects (tab separated). */
    data class TendaQosRow(val hostName: String, val remark: String, val mac: String, val allowed: Boolean)

    fun parseTendaQosRows(json: String): List<TendaQosRow> {
        val out = linkedMapOf<String, TendaQosRow>()
        for ((key, blocked) in listOf("onlineList" to false, "blackList" to true)) {
            for (o in jsonArrayObjects(json, key)) {
                val mac = jsonAny(o, "qosListMac")?.takeIf { MAC.matches(it) } ?: continue
                out[normalizeMac(mac)] = TendaQosRow(
                    hostName = jsonAny(o, "qosListHostname") ?: "Unknown",
                    remark = jsonAny(o, "qosListRemark").orEmpty(),
                    mac = normalizeMac(mac),
                    allowed = !blocked && jsonAny(o, "qosListAccess") != "false",
                )
            }
        }
        return out.values.toList()
    }

    fun encodeTendaQos(rows: List<TendaQosRow>): String = rows.joinToString("\n") { r ->
        val limit = if (r.allowed) "38528" else "0"
        listOf(r.hostName, r.remark, r.mac, limit, limit, r.allowed.toString()).joinToString("\t")
    }

    /** Tenda AC series (AC6/AC10/AC15/AC23 V15+): GET /goform/getOnlineList, first element = admin host. */
    fun parseTendaOnlineList(json: String): Pair<String?, List<RawDevice>> {
        val objs = jsonArrayObjects(json, null)
        val hostMac = objs.firstOrNull()?.let { jsonAny(it, "localhostMac") }?.let(::normalizeMac)
        val devices = objs.mapNotNull { o ->
            val mac = jsonAny(o, "deviceId")?.takeIf { MAC.matches(it) } ?: return@mapNotNull null
            val black = jsonAny(o, "black")
            RawDevice(
                mac = normalizeMac(mac),
                ip = jsonAny(o, "ip")?.takeIf { IPV4.matches(it) },
                hostName = cleanName(jsonAny(o, "devName")),
                online = true,
                wifi = when (jsonAny(o, "line")) { "0" -> false; "1", "2" -> true; else -> null },
                blocked = black == "1" || black == "true",
            )
        }
        return hostMac to devices
    }

    fun parseTendaBlackRules(json: String): List<RawDevice> =
        jsonArrayObjects(json, null).mapNotNull { o ->
            val mac = jsonAny(o, "deviceId")?.takeIf { MAC.matches(it) } ?: return@mapNotNull null
            RawDevice(normalizeMac(mac), null, cleanName(jsonAny(o, "devName")), online = false, wifi = null, blocked = true)
        }

    fun tendaErrCode(json: String): Int? = jsonAny(json, "errCode")?.toIntOrNull()

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- Detection

    fun detectBrand(body: String, headers: String): RouterBrand {
        val s = (body.take(200_000) + "\n" + headers).lowercase()
        return when {
            "getrandcount" in s || "echolife" in s || "hg8145" in s || "eg8145" in s ||
                "hg8245" in s || "hg8546" in s || ("huawei" in s && "login.cgi" in s) -> RouterBrand.HUAWEI_ONT
            "_type=logindata" in s -> RouterBrand.ZTE
            "frm_logintoken" in s || "zxhn" in s || "zte" in s -> RouterBrand.ZTE_LEGACY
            "huawei" in s -> RouterBrand.HUAWEI_OTHER
            "tp-link" in s || "tplink" in s || "tp_link" in s || "mercusys" in s -> RouterBrand.TPLINK
            "nokia" in s || "alcatel" in s -> RouterBrand.NOKIA
            "fiberhome" in s -> RouterBrand.FIBERHOME
            "d-link" in s || "dlink" in s -> RouterBrand.DLINK
            "tenda" in s -> RouterBrand.TENDA
            "netgear" in s -> RouterBrand.NETGEAR
            "mikrotik" in s || "routeros" in s -> RouterBrand.MIKROTIK
            "openwrt" in s || "luci" in s -> RouterBrand.OPENWRT
            else -> RouterBrand.UNKNOWN
        }
    }
}
