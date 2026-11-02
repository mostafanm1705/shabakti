package com.shabakti.app.router

enum class RouterBrand(val title: String, val hasDriver: Boolean, val blockHelp: String) {
    HUAWEI_ONT(
        "Huawei (راوتر فايبر / ONT)", true,
        "من القايمة: Security ← WLAN MAC Filter. فعّل Enable WLAN MAC Filter واختار Filter Mode = Blacklist، " +
            "بعدين اضغط New والصق عنوان الـ MAC واضغط Apply.",
    ),
    HUAWEI_OTHER(
        "Huawei", false,
        "افتح Devices أو Manage Device واقفل Internet Access للجهاز، أو من Wi-Fi ← Wi-Fi MAC Filter اختار Block والصق الـ MAC.",
    ),
    ZTE(
        "ZTE", true,
        "من القايمة: Local Network ← WLAN ← Access Control List (أو Internet ← Security ← Filter Criteria ← MAC Filter). " +
            "اختار Block / Blacklist والصق عنوان الـ MAC واضغط Apply.",
    ),
    ZTE_LEGACY(
        "ZTE (موديل قديم)", false,
        "من القايمة: Network ← WLAN ← Access Control List. اختار Mode = Block والصق الـ MAC واضغط Submit.",
    ),
    TPLINK(
        "TP-Link", false,
        "في الواجهة الجديدة: Advanced ← Security ← Access Control ← Blocklist ← Add. " +
            "في الواجهة القديمة: Wireless ← Wireless MAC Filtering ← Add New والصق الـ MAC.",
    ),
    NOKIA(
        "Nokia / Alcatel", false,
        "من القايمة: Security ← MAC Filter (أو WLAN ← Access Control). اختار Blacklist والصق الـ MAC.",
    ),
    FIBERHOME(
        "FiberHome", false,
        "من القايمة: Security ← MAC Filter أو Network ← WLAN Settings ← MAC Filter، والصق الـ MAC.",
    ),
    DLINK("D-Link", false, "من القايمة: Advanced ← MAC Address Filter (أو Network Filter) والصق الـ MAC."),
    TENDA("Tenda", false, "افتح Connected Devices واضغط Block جنب الجهاز، أو Wi-Fi ← MAC Filter."),
    NETGEAR("Netgear", false, "Security ← Access Control ← Block، أو Attached Devices."),
    MIKROTIK("MikroTik", false, "Wireless ← Access List ← أضف الـ MAC مع إلغاء Authentication."),
    OPENWRT("OpenWrt", false, "Network ← Wireless ← Edit ← MAC-Filter ← Deny listed والصق الـ MAC."),
    UNKNOWN(
        "نوع غير معروف", false,
        "دوّر في لوحة التحكم على MAC Filter أو Access Control أو Blocklist (غالبًا تحت Security أو WLAN/Wireless)، " +
            "واختار الحظر (Block / Blacklist / Deny) والصق عنوان الـ MAC.",
    ),
}

class RouterException(message: String, val details: String? = null) : Exception(message)

class UnsupportedOnRouter(what: String) : Exception(what)

/** One implementation per router family. All calls run on a background thread. */
interface RouterDriver {
    val brand: RouterBrand

    /** Logs in; throws [RouterException] with an Arabic message on failure. */
    fun login(username: String, password: String)

    fun listDevices(): List<RouterParsers.RawDevice>

    /** Default: not automated on this router, the UI falls back to the router's own panel. */
    fun block(mac: String): Unit = throw UnsupportedOnRouter("block")

    fun unblock(mac: String): Unit = throw UnsupportedOnRouter("unblock")

    fun reboot(): Unit = throw UnsupportedOnRouter("reboot")

    fun logout() {}
}
