package com.shabakti.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shabakti.app.data.Connection
import com.shabakti.app.data.NetDevice
import com.shabakti.app.data.Prefs
import com.shabakti.app.data.WifiStatus
import com.shabakti.app.net.LanScanner
import com.shabakti.app.net.VendorLookup
import com.shabakti.app.net.WifiStatusReader
import com.shabakti.app.router.DebugLog
import com.shabakti.app.router.DetectedRouter
import com.shabakti.app.router.RouterBrand
import com.shabakti.app.router.RouterDetector
import com.shabakti.app.router.RouterDriver
import com.shabakti.app.router.RouterException
import com.shabakti.app.router.RouterParsers
import com.shabakti.app.router.UnsupportedOnRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Screen { HOME, PANEL, LOG }

enum class RouterState { NONE, DETECTING, DETECTED, LOGGING_IN, LOGGED_IN, FAILED }

/** Shown when an action must be finished in the router's own panel. */
data class Guide(val title: String, val mac: String?, val steps: String, val markBlocked: Boolean?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val wifiReader = WifiStatusReader(app)
    private val scanner = LanScanner()
    private val vendors = VendorLookup(app)
    private val routerLock = Mutex()

    var screen by mutableStateOf(Screen.HOME)
    var wifi by mutableStateOf(WifiStatus())
        private set
    var devices by mutableStateOf<List<NetDevice>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var router by mutableStateOf<DetectedRouter?>(null)
        private set
    var routerState by mutableStateOf(RouterState.NONE)
        private set
    var routerMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var guide by mutableStateOf<Guide?>(null)
    var panelUrl by mutableStateOf<String?>(null)
        private set
    var blocked by mutableStateOf(prefs.blocked())
        private set
    var nickVersion by mutableStateOf(0)
        private set

    val savedUser get() = prefs.routerUser
    val savedPass get() = if (prefs.rememberLogin) prefs.routerPass else ""
    val savedRemember get() = prefs.rememberLogin
    val savedBrand get() = prefs.brandOverride

    private var driver: RouterDriver? = null
    private var routerDevices: List<RouterParsers.RawDevice> = emptyList()
    private var scanned: List<NetDevice> = emptyList()

    val brand: RouterBrand get() = driver?.brand ?: router?.brand ?: RouterBrand.UNKNOWN

    fun nickname(d: NetDevice): String? = prefs.nickname(d.key)

    fun rename(d: NetDevice, name: String) {
        prefs.setNickname(d.key, name)
        nickVersion++
    }

    fun refreshWifi() {
        wifi = wifiReader.read()
    }

    /** Full refresh: Wi-Fi status, router detection/login, LAN scan. */
    fun refresh() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            progress = 0f
            try {
                refreshWifi()
                val w = wifi
                if (!w.connectedToWifi || w.myIp == null) {
                    message = "الموبايل مش متوصل بشبكة واي فاي"
                    return@launch
                }
                if (router == null && w.gateway != null) detectRouter(w.gateway)
                if (driver == null && prefs.rememberLogin && prefs.routerPass.isNotEmpty() &&
                    routerState != RouterState.FAILED
                ) {
                    doLogin(prefs.routerUser, prefs.routerPass, prefs.brandOverride)
                }
                if (driver != null) loadRouterDevices()
                scanned = scanner.scan(w.myIp, w.prefixLength ?: 24, w.gateway) { p -> progress = p }
                merge()
            } catch (e: Exception) {
                message = "حصل خطأ أثناء الفحص: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    private suspend fun detectRouter(gateway: String) {
        routerState = RouterState.DETECTING
        try {
            router = withContext(Dispatchers.IO) { RouterDetector.detect(gateway) }
            routerState = RouterState.DETECTED
            routerMessage = null
        } catch (e: RouterException) {
            routerState = RouterState.FAILED
            routerMessage = e.message
        }
    }

    fun login(user: String, pass: String, remember: Boolean, brandOverride: String) {
        prefs.routerUser = user
        prefs.rememberLogin = remember
        prefs.routerPass = if (remember) pass else ""
        prefs.brandOverride = brandOverride
        viewModelScope.launch {
            val gw = wifi.gateway ?: run { refreshWifi(); wifi.gateway }
            if (router == null && gw != null) detectRouter(gw)
            if (doLogin(user, pass, brandOverride)) {
                loadRouterDevices()
                merge()
                message = "تم الدخول على الراوتر ✔"
            }
        }
    }

    private suspend fun doLogin(user: String, pass: String, brandOverride: String): Boolean {
        val r0 = router ?: run {
            routerState = RouterState.FAILED
            routerMessage = "لسه ماعرفتش عنوان الراوتر. اتأكد إنك متوصل بالواي فاي واعمل تحديث."
            return false
        }
        val forced = runCatching { RouterBrand.valueOf(brandOverride) }.getOrNull()
        val r = if (forced != null) r0.copy(brand = forced) else r0
        val d = RouterDetector.driverFor(r)
        if (d == null) {
            routerState = RouterState.DETECTED
            routerMessage = "الراوتر ده (${r.brand.title}) لسه مالوش تحكم تلقائي في التطبيق. " +
                "تقدر تفتح لوحة التحكم من جوه التطبيق وتتحكم منها."
            return false
        }
        routerState = RouterState.LOGGING_IN
        return try {
            routerLock.withLock { withContext(Dispatchers.IO) { d.login(user, pass) } }
            driver = d
            routerState = RouterState.LOGGED_IN
            routerMessage = null
            true
        } catch (e: Exception) {
            driver = null
            routerState = RouterState.FAILED
            routerMessage = e.message ?: "فشل الدخول"
            false
        }
    }

    private suspend fun loadRouterDevices() {
        val d = driver ?: return
        try {
            routerDevices = routerLock.withLock { withContext(Dispatchers.IO) { d.listDevices() } }
            DebugLog.add("Router reported ${routerDevices.size} devices")
        } catch (e: Exception) {
            routerMessage = e.message
            if (e is RouterException && e.message?.contains("جلسة") == true) {
                driver = null
                routerState = RouterState.DETECTED
            }
        }
    }

    private fun merge() {
        val w = wifi
        val byKey = linkedMapOf<String, NetDevice>()
        for (r in routerDevices) {
            byKey[r.mac] = NetDevice(
                ip = r.ip,
                mac = r.mac,
                hostName = r.hostName,
                vendor = vendors.vendorOf(r.mac),
                online = r.online,
                connection = when (r.wifi) { true -> Connection.WIFI; false -> Connection.LAN; null -> Connection.UNKNOWN },
                isMe = r.ip != null && r.ip == w.myIp,
                isGateway = r.ip != null && r.ip == w.gateway,
                fromRouter = true,
            )
        }
        for (s in scanned) {
            val existing = byKey.values.firstOrNull { it.ip == s.ip && it.online }
            if (existing != null) {
                byKey[existing.key] = existing.copy(
                    hostName = existing.hostName ?: s.hostName,
                    responseMs = s.responseMs,
                    isMe = existing.isMe || s.isMe,
                    isGateway = existing.isGateway || s.isGateway,
                    online = true,
                )
            } else {
                val withVendor = s.copy(vendor = vendors.vendorOf(s.mac))
                byKey[withVendor.key] = withVendor
            }
        }
        devices = byKey.values.sortedWith(
            compareByDescending<NetDevice> { it.isGateway }
                .thenByDescending { it.isMe }
                .thenByDescending { it.online }
                .thenBy { it.ip?.let(LanScanner::ipToInt)?.toLong()?.and(0xffffffffL) ?: Long.MAX_VALUE }
        )
    }

    // ------------------------------------------------------------------ actions

    fun block(d: NetDevice) = blockOrUnblock(d, true)

    fun unblock(mac: String) = blockOrUnblock(NetDevice(ip = null, mac = mac), false)

    private fun blockOrUnblock(d: NetDevice, block: Boolean) {
        val mac = d.mac
        if (mac == null) {
            guide = Guide(
                "عنوان الـ MAC مش معروف",
                null,
                "علشان أحظر الجهاز ده لازم أعرف الـ MAC بتاعه، والراوتر بس هو اللي يعرفه على أندرويد الحديث. " +
                    "سجّل دخول على الراوتر من زرار \"الراوتر\" فوق، أو افتح لوحة التحكم ودوّر على الجهاز بالـ IP ${d.ip}.",
                null,
            )
            return
        }
        if (d.isMe && block) {
            message = "ده موبايلك! لو حظرته هتفصل نفسك عن الشبكة."
            return
        }
        viewModelScope.launch {
            val drv = driver
            if (drv != null) {
                try {
                    routerLock.withLock {
                        withContext(Dispatchers.IO) { if (block) drv.block(mac) else drv.unblock(mac) }
                    }
                    setBlocked(mac, block)
                    message = if (block) "تم حظر الجهاز ✔" else "تم إلغاء الحظر ✔"
                    loadRouterDevices(); merge()
                    return@launch
                } catch (_: UnsupportedOnRouter) {
                    // fall through to the guided flow
                } catch (e: Exception) {
                    message = "الراوتر رفض الأمر: ${e.message}"
                }
            }
            guide = Guide(
                if (block) "حظر الجهاز من لوحة الراوتر" else "إلغاء الحظر من لوحة الراوتر",
                mac,
                if (block) brand.blockHelp
                else "افتح نفس صفحة الحظر (MAC Filter / Access Control) وامسح عنوان الـ MAC ده من القايمة واضغط Apply.",
                block,
            )
        }
    }

    fun setBlocked(mac: String, isBlocked: Boolean) {
        prefs.setBlocked(mac, isBlocked)
        blocked = prefs.blocked()
    }

    fun reboot() {
        viewModelScope.launch {
            val drv = driver
            if (drv == null) {
                guide = Guide(
                    "إعادة تشغيل الراوتر",
                    null,
                    "افتح لوحة التحكم ودوّر على Reboot أو Restart (غالبًا تحت System Tools أو Management أو Maintenance).",
                    null,
                )
                return@launch
            }
            try {
                routerLock.withLock { withContext(Dispatchers.IO) { drv.reboot() } }
                message = "الراوتر بيعيد التشغيل… النت هيرجع خلال دقيقتين تقريبًا"
                driver = null
                routerState = RouterState.DETECTED
            } catch (_: UnsupportedOnRouter) {
                guide = Guide("إعادة تشغيل الراوتر", null, "افتح لوحة التحكم ودوّر على Reboot أو Restart.", null)
            } catch (e: Exception) {
                message = "مانفعش: ${e.message}"
            }
        }
    }

    fun logoutRouter() {
        val d = driver ?: return
        viewModelScope.launch {
            routerLock.withLock { withContext(Dispatchers.IO) { d.logout() } }
            driver = null
            routerDevices = emptyList()
            routerState = RouterState.DETECTED
            merge()
        }
    }

    /** Opens the router's own web panel inside the app. Logs our session out first (single-session routers). */
    fun openPanel() {
        val base = router?.baseUrl ?: wifi.gateway?.let { "http://$it" }
        if (base == null) {
            message = "مش لاقي عنوان الراوتر"
            return
        }
        driver?.let { d ->
            viewModelScope.launch {
                routerLock.withLock { withContext(Dispatchers.IO) { runCatching { d.logout() } } }
                driver = null
                if (routerState == RouterState.LOGGED_IN) routerState = RouterState.DETECTED
            }
        }
        panelUrl = "$base/"
        screen = Screen.PANEL
    }
}
