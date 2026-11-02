package com.shabakti.app.router

import android.util.Base64

/**
 * Tenda routers. Two web APIs exist and are detected at login:
 *
 *  ECOS (F3, N301, F6, F9, AC5/AC6 V12 firmware … password-only login page):
 *    GET /goform/getstok → {"random": token}; POST /login/Auth password=md5(base64(pw)+token)
 *    → cookie ecos_pw. Clients: GET /goform/getQos?modules=onlineList,blackList.
 *    Block: POST /goform/setQos module1=qosList (full list, access=false for blocked).
 *    Reference: github.com/sohan-f/linkpilot (docs/TENDA_WEB_API.md).
 *
 *  AC (AC6/AC10/AC15/AC23/AC1200 V15+ firmware):
 *    POST /login/Auth username=admin&password=md5(pw) → cookie "password".
 *    Clients: GET /goform/getOnlineList; block: setBlackRule / delBlackRule.
 *    Reference: github.com/bluescorpian/tendactl.
 */
class TendaDriver(private val http: RouterHttp) : RouterDriver {
    override val brand = RouterBrand.TENDA
    override val reportsBlocked = true

    private enum class Api { ECOS, AC }

    private var api: Api? = null
    private fun rnd() = Math.random().toString()
    private val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")

    override fun login(username: String, password: String) {
        http.clearCookies()
        val stok = runCatching { http.get("/goform/getstok?random=${rnd()}", xhr) }.getOrNull()
        val token = stok?.body?.let { RouterParsers.jsonString(it, "random") }
        if (token != null) {
            val b64 = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val r = http.post("/login/Auth", mapOf("password" to RouterParsers.md5Hex(b64 + token)))
            if (http.cookies().any { it.name == "ecos_pw" && it.value.isNotBlank() }) {
                api = Api.ECOS
                return
            }
            failLogin(r)
        }

        runCatching { http.get("/login.html") }
        val r = http.post(
            "/login/Auth",
            mapOf("username" to username.ifBlank { "admin" }, "password" to RouterParsers.md5Hex(password)),
            xhr,
        )
        if (r.body.trim() != "1" && http.cookies().any { it.name == "password" && it.value.isNotBlank() }) {
            api = Api.AC
            return
        }
        failLogin(r)
    }

    private fun failLogin(r: HttpResult): Nothing {
        val code = Regex("""login\.html\?(\d+)""").find(r.finalUrl)?.groupValues?.get(1)?.toIntOrNull()
        val msg = when {
            code == 2 || code == 3 -> "فيه أجهزة تانية فاتحة لوحة تحكم الراوتر دلوقتي. اقفلها وجرّب تاني."
            code == 10 -> "الراوتر قافل الدخول حوالي 3 دقايق بسبب محاولات غلط."
            code != null && code in 11..14 -> "الباسورد غلط. فاضل ${code - 10} محاولات قبل ما الراوتر يقفل الدخول."
            else -> "الباسورد غلط. ده باسورد صفحة الراوتر (غالبًا admin أو نفس باسورد الواي فاي)."
        }
        throw RouterException(msg, "${r.finalUrl} ${r.body.take(150)}")
    }

    private fun checkSession(r: HttpResult) {
        val t = r.body.trimStart()
        if (r.finalUrl.contains("login.html") || t.startsWith("<!DOCTYPE", true) || t.startsWith("<html", true)) {
            throw RouterException("انتهت جلسة الدخول على الراوتر، سجّل دخول تاني")
        }
    }

    private fun qosJson(): String {
        val r = http.get("/goform/getQos?random=${rnd()}&modules=localhost%2ConlineList%2CblackList", xhr)
        checkSession(r)
        return r.body
    }

    override fun listDevices(): List<RouterParsers.RawDevice> = when (api) {
        Api.ECOS -> RouterParsers.parseTendaQos(qosJson())
        Api.AC -> {
            val r = http.get("/goform/getOnlineList?random=${rnd()}", xhr)
            checkSession(r)
            val online = RouterParsers.parseTendaOnlineList(r.body).second
            val black = runCatching {
                RouterParsers.parseTendaBlackRules(http.get("/goform/getBlackRuleList?random=${rnd()}", xhr).body)
            }.getOrDefault(emptyList())
            val seen = online.map { it.mac }.toSet()
            online.map { d -> if (black.any { it.mac == d.mac }) d.copy(blocked = true, online = false) else d } +
                black.filter { it.mac !in seen }
        }
        null -> throw RouterException("سجّل دخول على الراوتر الأول")
    }

    private fun expectOk(r: HttpResult, vararg okCodes: Int) {
        checkSession(r)
        val code = RouterParsers.tendaErrCode(r.body)
        if (code == null || code !in okCodes) {
            throw RouterException(
                if (code == 1) "قايمة الحظر في الراوتر مليانة" else "الراوتر رفض الأمر (errCode=$code)",
                r.body.take(200),
            )
        }
    }

    private fun setAccess(mac: String, allowed: Boolean) {
        val target = RouterParsers.normalizeMac(mac)
        when (api) {
            Api.ECOS -> {
                val rows = RouterParsers.parseTendaQosRows(qosJson()).toMutableList()
                val i = rows.indexOfFirst { it.mac == target }
                if (i >= 0) rows[i] = rows[i].copy(allowed = allowed)
                else if (!allowed) rows.add(RouterParsers.TendaQosRow("Unknown", "", target, false))
                val r = http.post(
                    "/goform/setQos?${rnd()}",
                    mapOf("module1" to "qosList", "qosList" to RouterParsers.encodeTendaQos(rows)),
                    xhr,
                )
                expectOk(r, 0)
            }
            Api.AC -> {
                val path = if (allowed) "/goform/delBlackRule" else "/goform/setBlackRule"
                expectOk(http.post("$path?random=${rnd()}", mapOf("mac" to target.lowercase()), xhr), 0)
            }
            null -> throw RouterException("سجّل دخول على الراوتر الأول")
        }
    }

    override fun block(mac: String) = setAccess(mac, allowed = false)

    override fun unblock(mac: String) = setAccess(mac, allowed = true)

    override fun reboot() {
        when (api) {
            Api.ECOS -> runCatching {
                http.post("/goform/sysReboot?${rnd()}", mapOf("module1" to "sysOperate", "action" to "reboot"), xhr)
            }
            Api.AC -> runCatching { http.post("/goform/SysToolReboot", mapOf("action" to "0"), xhr) }
            null -> throw RouterException("سجّل دخول على الراوتر الأول")
        }
        // The router drops the connection while restarting, so a network error here is expected.
    }

    override fun logout() {
        if (api == Api.ECOS) runCatching { http.post("/goform/loginOut", mapOf("action" to "loginout"), xhr) }
        api = null
        http.clearCookies()
    }
}
