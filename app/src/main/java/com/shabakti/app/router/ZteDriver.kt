package com.shabakti.app.router

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * ZTE routers with the "_type=loginData" web UI: ZXHN H188A, H168A, H169A, H288A, H3600,
 * F670L, F6600, F6640 ... (WE / Vodafone / Orange / Etisalat units).
 * Protocol from the open-source zte_tracker and zteScanner projects:
 *  1. GET  /?_type=loginData&_tag=login_entry  → JSON with sess_token
 *  2. GET  /?_type=loginData&_tag=login_token  → <ajax_response_xml_root>salt</...>
 *  3. POST /?_type=loginData&_tag=login_entry  action=login, Username,
 *          Password = sha256(password + salt), _sessionTOKEN
 *  4. GET  /?_type=menuView&_tag=localNetStatus, then menuData device tables.
 */
class ZteDriver(private val http: RouterHttp) : RouterDriver {
    override val brand = RouterBrand.ZTE
    private var guid = System.currentTimeMillis()
    private fun g() = guid++

    private fun sessionToken(): String {
        val r = http.get("/?_type=loginData&_tag=login_entry")
        val locking = RouterParsers.jsonInt(r.body, "lockingTime") ?: 0
        if (locking > 0) throw RouterException("الراوتر قافل الدخول لمدة $locking ثانية بسبب محاولات غلط")
        return RouterParsers.parseZteSessionToken(r.body)
            ?: throw RouterException("الراوتر مارجّعش كود الجلسة. ممكن يكون موديل ZTE مختلف", r.body.take(200))
    }

    override fun login(username: String, password: String) {
        http.clearCookies()
        http.get("/")
        val sess = sessionToken()
        val tokXml = http.get("/?_type=loginData&_tag=login_token&_=${g()}").body
        val salt = RouterParsers.parseZteLoginToken(tokXml)
            ?: throw RouterException("الراوتر مارجّعش كود الدخول (login token)", tokXml.take(200))
        val r = http.post(
            "/?_type=loginData&_tag=login_entry",
            mapOf(
                "action" to "login",
                "Password" to RouterParsers.sha256Hex(password + salt),
                "Username" to username,
                "_sessionTOKEN" to sess,
            ),
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val err = RouterParsers.jsonString(r.body, "loginErrMsg").orEmpty()
        val locking = RouterParsers.jsonInt(r.body, "lockingTime") ?: 0
        val needRefresh = RouterParsers.jsonInt(r.body, "login_need_refresh")
            ?: if (r.body.contains("\"login_need_refresh\":true")) 1 else 0
        when {
            locking > 0 || locking == -1 ->
                throw RouterException("الراوتر قافل الدخول مؤقتًا بسبب محاولات غلط. استنى شوية وجرّب تاني.", r.body.take(200))
            err.isNotBlank() -> throw RouterException("اسم المستخدم أو الباسورد غلط", err)
            needRefresh != 1 && !r.body.contains("login_need_refresh") ->
                throw RouterException("الدخول مانجحش، رد الراوتر مش متوقع", r.body.take(300))
        }
        http.get("/")
    }

    override fun listDevices(): List<RouterParsers.RawDevice> {
        http.get("/?_type=menuView&_tag=localNetStatus&_=${g()}")
        val all = linkedMapOf<String, RouterParsers.RawDevice>()
        var sessionLost = false
        val tags = listOf(
            "accessdev_landevs_lua.lua" to false,
            "accessdev_ssiddev_lua.lua" to true,
            "wlan_client_stat_lua.lua" to true,
        )
        for ((tag, wifi) in tags) {
            val body = runCatching { http.get("/?_type=menuData&_tag=$tag&_=${g()}").body }.getOrNull() ?: continue
            val err = RouterParsers.zteError(body)
            if (err != null && err.contains("Session", true)) sessionLost = true
            for (d in RouterParsers.parseZteDevices(body, wifi)) {
                val prev = all[d.mac]
                all[d.mac] = if (prev == null) d else prev.copy(
                    ip = prev.ip ?: d.ip,
                    hostName = prev.hostName ?: d.hostName,
                    wifi = prev.wifi ?: d.wifi,
                )
            }
        }
        if (all.isEmpty() && sessionLost) throw RouterException("انتهت جلسة الدخول على الراوتر، سجّل دخول تاني")
        return all.values.toList()
    }

    /** Signed POSTs carry a "Check" header: RSA(PKCS#1) of sha256(body), base64. */
    private fun checkHeader(postData: String, encrypted: Boolean): String {
        val digest = RouterParsers.sha256Hex(postData)
        if (!encrypted) return digest
        val der = Base64.decode(ZTE_PUBLIC_KEY, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(c.doFinal(digest.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun reboot() {
        http.get("/?_type=menuView&_tag=rebootAndReset&Menu3Location=0&_=${g()}")
        var lastErr: String? = null
        for (encrypted in listOf(true, false)) {
            val tok = sessionToken()
            val post = "IF_ACTION=Restart&Btn_restart=&_sessionTOKEN=$tok"
            val body = post.toByteArray(Charsets.UTF_8)
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val r = http.postRaw(
                "/?_type=menuData&_tag=devmgr_restartmgr_lua.lua&_=${g()}",
                body,
                mapOf("Check" to checkHeader(post, encrypted)),
            )
            lastErr = RouterParsers.zteError(r.body)
            if (r.code < 400 && lastErr == null) return
        }
        throw RouterException("الراوتر رفض أمر إعادة التشغيل", lastErr)
    }

    override fun logout() {
        runCatching { http.post("/?_type=loginData&_tag=logout_entry", mapOf("IF_LogOff" to "1")) }
        http.clearCookies()
    }

    companion object {
        // Public key shipped in the ZTE web UI (2048-bit), as used by zte_tracker.
        private const val ZTE_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAodPTerkUVCYmv28SOfRV" +
                "7UKHVujx/HjCUTAWy9l0L5H0JV0LfDudTdMNPEKloZsNam3YrtEnq6jqMLJV4ASb" +
                "1d6axmIgJ636wyTUS99gj4BKs6bQSTUSE8h/QkUYv4gEIt3saMS0pZpd90y6+B/9" +
                "hZxZE/RKU8e+zgRqp1/762TB7vcjtjOwXRDEL0w71Jk9i8VUQ59MR1Uj5E8X3WIc" +
                "fYSK5RWBkMhfaTRM6ozS9Bqhi40xlSOb3GBxCmliCifOJNLoO9kFoWgAIw5hkSIb" +
                "GH+4Csop9Uy8VvmmB+B3ubFLN35qIa5OG5+SDXn4L7FeAA5lRiGxRi8tsWrtew8w" +
                "nwIDAQAB"
    }
}
