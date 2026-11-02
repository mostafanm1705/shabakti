package com.shabakti.app.router

import android.util.Base64

/**
 * Huawei EchoLife ONTs (HG8145V5, EG8145V5, HG8245H, HG8546M ...) — the fibre routers used by WE.
 * Protocol from the open-source go-huawei-client and community scripts:
 *  1. POST /asp/GetRandCount.asp → one-time token
 *  2. POST /login.cgi (UserName, PassWord=base64, Language, x.X_HW_Token) with
 *     cookie "Cookie=body:Language:english:id=-1", on the same connection.
 *  3. POST /html/bbsp/common/GetLanUserDevInfo.asp → JS with `new USERDevice(...)` entries.
 */
class HuaweiOntDriver(private val http: RouterHttp) : RouterDriver {
    override val brand = RouterBrand.HUAWEI_ONT

    private fun token(): String {
        val r = http.post("/asp/GetRandCount.asp", emptyMap())
        val t = RouterParsers.parseHuaweiToken(r.body)
        if (t.isEmpty() || t.length > 64 || t.contains('<')) {
            throw RouterException("الراوتر مارجّعش كود الدخول (token)", r.body.take(200))
        }
        return t
    }

    override fun login(username: String, password: String) {
        http.clearCookies()
        http.setCookie("Cookie", "body:Language:english:id=-1")
        val tok = token()
        val pw = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val r = http.post(
            "/login.cgi",
            mapOf(
                "UserName" to username,
                "PassWord" to pw,
                "Language" to "english",
                "x.X_HW_Token" to tok,
            ),
            mapOf("Referer" to "${http.base}/", "Origin" to http.base),
        )
        val hasSid = http.cookies().any { it.value.contains("sid=", ignoreCase = true) }
        val ok = hasSid || r.body.contains("index.asp")
        if (!ok) {
            val locked = r.body.contains("locked", true) || r.body.contains("LockLeftTime", true)
            throw RouterException(
                if (locked) "الراوتر قافل الدخول مؤقتًا بسبب محاولات غلط. استنى دقيقة وجرّب تاني."
                else "اسم المستخدم أو الباسورد غلط",
                r.body.take(300),
            )
        }
    }

    override fun listDevices(): List<RouterParsers.RawDevice> {
        val r = http.post(
            "/html/bbsp/common/GetLanUserDevInfo.asp",
            emptyMap(),
            mapOf(
                "Referer" to "${http.base}/html/bbsp/userdevinfo/userdevinfo.asp",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        if (r.body.contains("login.asp") || r.body.contains("GetRandCount")) {
            throw RouterException("انتهت جلسة الدخول على الراوتر، سجّل دخول تاني")
        }
        return RouterParsers.parseHuaweiUserDevices(r.body)
    }

    override fun reboot() {
        val tok = token()
        val r = http.post(
            "/html/ssmp/devmanage/set.cgi?x=InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard" +
                "&RequestFile=html/ssmp/devmanage/devmanage.asp",
            mapOf("x.X_HW_Token" to tok),
        )
        if (r.code >= 400) throw RouterException("الراوتر رفض أمر إعادة التشغيل (${r.code})", r.body.take(200))
    }

    override fun logout() {
        runCatching {
            val tok = token()
            http.post("/logout.cgi?RequestFile=html/logout.html", mapOf("x.X_HW_Token" to tok))
        }
        http.clearCookies()
    }
}
