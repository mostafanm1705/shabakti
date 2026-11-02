package com.shabakti.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shabakti.app.data.Connection
import com.shabakti.app.data.NetDevice
import com.shabakti.app.router.DebugLog
import com.shabakti.app.router.RouterBrand

@Composable
fun AppRoot(vm: MainViewModel) {
    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.PANEL -> PanelScreen(vm)
        Screen.LOG -> LogScreen(vm)
    }
}

// =====================================================================  Home

@Composable
private fun HomeScreen(vm: MainViewModel) {
    val snack = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<NetDevice?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = "شبكتي",
                actions = {
                    IconButton(onClick = { vm.screen = Screen.LOG }) { Icon(Icons.Filled.Info, "سجل التشخيص") }
                    IconButton(onClick = { vm.refresh() }, enabled = !vm.scanning) { Icon(Icons.Filled.Refresh, "تحديث") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val onlineCount = vm.devices.count { it.online }
        val blockedOffline = vm.blocked.filter { mac -> vm.devices.none { it.mac == mac && it.online } }
        // Read so the list recomposes after a rename.
        @Suppress("UNUSED_VARIABLE") val nv = vm.nickVersion

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { WifiCard(vm) }
            item { RouterCard(vm, onLogin = { showLogin = true }) }
            item { Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "الأجهزة المتصلة ($onlineCount)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.scanning) Text("جاري الفحص… ${(vm.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (vm.scanning) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            } }
            if (vm.devices.isEmpty() && !vm.scanning) {
                item {
                    Text(
                        "اضغط زرار التحديث فوق علشان أفحص الشبكة.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            items(vm.devices, key = { it.key }) { d ->
                DeviceRow(d, vm.nickname(d), blocked = d.mac != null && d.mac in vm.blocked) { selected = d }
            }
            if (blockedOffline.isNotEmpty()) {
                item {
                    Text(
                        "الأجهزة المحظورة (${blockedOffline.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(blockedOffline, key = { "blocked_$it" }) { mac ->
                    BlockedRow(mac, vm.nickname(NetDevice(ip = null, mac = mac))) { vm.unblock(mac) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    selected?.let { d ->
        DeviceDialog(vm, d, onDismiss = { selected = null })
    }
    if (showLogin) LoginDialog(vm, onDismiss = { showLogin = false })
    vm.guide?.let { g -> GuideDialog(vm, g) }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
            else Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun WifiCard(vm: MainViewModel) {
    val w = vm.wifi
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val onP = MaterialTheme.colorScheme.onPrimary
            if (!w.connectedToWifi) {
                Text("غير متصل بالواي فاي", color = onP, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("وصّل الموبايل بشبكة البيت علشان تقدر تديرها.", color = onP.copy(alpha = 0.8f))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        w.ssid ?: "اسم الشبكة مخفي (اسمح بإذن الموقع)",
                        color = onP, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (w.hasInternet) Mint else Amber))
                        Spacer(Modifier.width(6.dp))
                        Text(if (w.hasInternet) "متصل بالإنترنت" else "متصل بالشبكة بدون إنترنت", color = onP.copy(alpha = 0.9f))
                    }
                }
                w.signalPercent?.let { SignalBars(it, onP) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("الإشارة", w.rssi?.let { "$it dBm" } ?: "—", onP)
                Stat("السرعة", w.linkSpeedMbps?.let { "$it Mbps" } ?: "—", onP)
                Stat("التردد", w.band ?: "—", onP)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("IP بتاعي", w.myIp ?: "—", onP)
                Stat("الراوتر", w.gateway ?: "—", onP)
                Stat("DNS", w.dns.firstOrNull() ?: "—", onP)
            }
        }
    }
}

@Composable
private fun SignalBars(percent: Int, color: Color) {
    val bars = when {
        percent >= 80 -> 4; percent >= 60 -> 3; percent >= 35 -> 2; percent > 0 -> 1; else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(
                Modifier.width(6.dp).height((6 + i * 6).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i <= bars) color else color.copy(alpha = 0.25f))
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = color.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun RouterCard(vm: MainViewModel, onLogin: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📡", fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("الراوتر: ${vm.brand.title}", fontWeight = FontWeight.Bold)
                    val state = when (vm.routerState) {
                        RouterState.NONE -> "لسه ماتفحصش"
                        RouterState.DETECTING -> "بتعرّف على الراوتر…"
                        RouterState.DETECTED -> if (vm.brand.hasDriver) "مدعوم — سجّل دخول للتحكم" else "متاح عن طريق لوحة التحكم"
                        RouterState.LOGGING_IN -> "جاري تسجيل الدخول…"
                        RouterState.LOGGED_IN -> "متصل ✔ — بيانات الأجهزة من الراوتر"
                        RouterState.FAILED -> "مش متصل"
                    }
                    Text(state, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.routerState == RouterState.DETECTING || vm.routerState == RouterState.LOGGING_IN) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            vm.routerMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.routerState == RouterState.LOGGED_IN) {
                    OutlinedButton(onClick = { vm.logoutRouter() }) { Text("خروج") }
                } else {
                    Button(onClick = onLogin) { Text("دخول الراوتر") }
                }
                OutlinedButton(onClick = { vm.openPanel() }) { Text("لوحة التحكم") }
                OutlinedButton(onClick = { vm.reboot() }) { Text("ريستارت") }
            }
        }
    }
}

private fun deviceEmoji(d: NetDevice): String {
    val v = (d.vendor.orEmpty() + " " + d.hostName.orEmpty()).lowercase()
    return when {
        d.isGateway -> "📡"
        d.isMe -> "🤳"
        listOf("playstation", "xbox", "nintendo").any { it in v } -> "🎮"
        listOf("tv", "roku", "hisense", "tcl", "chromecast", "firetv").any { it in v } -> "📺"
        listOf("laptop", "desktop", "pc", "intel", "dell", "lenovo", "hp", "asus", "acer", "msi", "realtek", "lite-on", "azurewave").any { it in v } -> "💻"
        listOf("espressif", "tuya", "hikvision", "dahua", "camera", "iot").any { it in v } -> "🔌"
        listOf("printer", "epson", "canon", "brother").any { it in v } -> "🖨️"
        else -> "📱"
    }
}

@Composable
private fun DeviceRow(d: NetDevice, nick: String?, blocked: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (blocked) Danger.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(deviceEmoji(d), fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = nick ?: d.hostName ?: d.vendor ?: when {
                    d.isGateway -> "الراوتر"
                    d.isRandomMac -> "جهاز بعنوان خاص"
                    else -> "جهاز غير معروف"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.isMe) Tag("جهازي", Mint)
                    if (d.isGateway) Tag("الراوتر", Navy)
                    if (blocked) Tag("محظور", Danger)
                }
                val sub = listOfNotNull(
                    d.ip,
                    when (d.connection) { Connection.WIFI -> "واي فاي"; Connection.LAN -> "كابل"; else -> null },
                    d.vendor?.takeIf { nick != null || d.hostName != null },
                ).joinToString(" • ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.online) Mint else Color.Gray))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Spacer(Modifier.width(6.dp))
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun BlockedRow(mac: String, nick: String?, onUnblock: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.08f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⛔", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nick ?: "جهاز محظور", fontWeight = FontWeight.SemiBold)
                Text(mac, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onUnblock) { Text("إلغاء الحظر") }
        }
    }
}

// =====================================================================  Dialogs

@Composable
private fun DeviceDialog(vm: MainViewModel, d: NetDevice, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    var name by remember { mutableStateOf(vm.nickname(d) ?: "") }
    val isBlocked = d.mac != null && d.mac in vm.blocked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.nickname(d) ?: d.hostName ?: d.vendor ?: "تفاصيل الجهاز") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Column {
                        InfoRow("IP", d.ip ?: "—")
                        InfoRow("MAC", d.mac ?: "غير معروف (سجّل دخول الراوتر)")
                        InfoRow("الشركة المصنعة", d.vendor ?: if (d.isRandomMac) "عنوان خاص (Private MAC)" else "—")
                        InfoRow("اسم الجهاز", d.hostName ?: "—")
                        InfoRow("نوع الاتصال", when (d.connection) { Connection.WIFI -> "واي فاي"; Connection.LAN -> "كابل"; else -> "—" })
                        InfoRow("الحالة", if (d.online) "متصل" else "غير متصل")
                        d.responseMs?.let { InfoRow("زمن الاستجابة", "$it ms") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("سمّي الجهاز (مثلًا: موبايل أحمد)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (!d.isGateway) {
                    if (isBlocked) {
                        Button(onClick = { vm.unblock(d.mac!!); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text("إلغاء الحظر ورجوع النت")
                        }
                    } else {
                        Button(
                            onClick = { vm.block(d); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        ) { Text("فصل النت وحظر الجهاز") }
                    }
                }
                if (d.mac != null) {
                    TextButton(onClick = { clip.setText(AnnotatedString(d.mac)); vm.message = "اتنسخ الـ MAC" }) {
                        Text("نسخ عنوان الـ MAC")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.rename(d, name); onDismiss() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LoginDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var user by remember { mutableStateOf(vm.savedUser.ifBlank { defaultUser(vm.brand) }) }
    var pass by remember { mutableStateOf(vm.savedPass) }
    var rememberMe by remember { mutableStateOf(vm.savedRemember) }
    var brand by remember { mutableStateOf(vm.savedBrand) }
    var menu by remember { mutableStateOf(false) }
    val brands = listOf("AUTO" to "تلقائي (${vm.router?.brand?.title ?: "لسه ماتعرفش"})") +
        RouterBrand.entries.filter { it.hasDriver }.map { it.name to it.title }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("دخول لوحة تحكم الراوتر") },
        text = {
            Column {
                Text(
                    "اسم المستخدم والباسورد بتوع صفحة الراوتر (مش باسورد الواي فاي). غالبًا مكتوبين على ستيكر ورا الراوتر.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("الباسورد") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberMe, { rememberMe = it })
                    Text("افتكر بيانات الدخول (متشفرة على الموبايل)")
                }
                Box {
                    OutlinedButton(onClick = { menu = true }) {
                        Text("نوع الراوتر: " + (brands.firstOrNull { it.first == brand }?.second ?: brand))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        brands.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { brand = key; menu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.login(user.trim(), pass, rememberMe, brand); onDismiss() }, enabled = pass.isNotEmpty()) {
                Text("دخول")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private fun defaultUser(b: RouterBrand) = when (b) {
    RouterBrand.HUAWEI_ONT -> "admin"
    RouterBrand.ZTE -> "admin"
    else -> "admin"
}

@Composable
private fun GuideDialog(vm: MainViewModel, g: Guide) {
    val clip = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { vm.guide = null },
        title = { Text(g.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (g.markBlocked != null) {
                    Text(
                        "التحكم التلقائي في الحظر لسه مش متاح لراوترك، فهنكمّله من لوحة تحكم الراوتر نفسها جوه التطبيق:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(g.steps)
                g.mac?.let {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clip.setText(AnnotatedString(it)); vm.message = "اتنسخ الـ MAC" }) { Text("نسخ") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                g.mac?.let { clip.setText(AnnotatedString(it)) }
                vm.openPanel()
            }) { Text("افتح لوحة التحكم") }
        },
        dismissButton = { TextButton(onClick = { vm.guide = null }) { Text("إلغاء") } },
    )
}

// =====================================================================  Router panel (WebView)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PanelScreen(vm: MainViewModel) {
    val clip = LocalClipboardManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    val routerHost = remember(vm.panelUrl) { vm.panelUrl?.let { Uri.parse(it).host } }

    fun close() { vm.screen = Screen.HOME }
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else close() }

    Column(Modifier.fillMaxSize()) {
        TopBar("لوحة تحكم الراوتر", onBack = { close() }, actions = {
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "إعادة تحميل") }
        })
        vm.guide?.let { g ->
            Surface(color = Amber.copy(alpha = 0.15f)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(g.steps, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        g.mac?.let { mac ->
                            Text(mac, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clip.setText(AnnotatedString(mac)); vm.message = "اتنسخ" }) { Text("نسخ") }
                        } ?: Spacer(Modifier.weight(1f))
                        if (g.mac != null && g.markBlocked != null) {
                            TextButton(onClick = {
                                vm.setBlocked(g.mac, g.markBlocked)
                                vm.guide = null
                                vm.message = if (g.markBlocked) "اتسجّل إن الجهاز محظور" else "اتشال من قايمة المحظورين"
                            }) { Text("خلصت ✔") }
                        }
                        TextButton(onClick = { vm.guide = null }) { Text("إخفاء") }
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true }

                        // Home routers use self-signed certificates; accept them for the router only.
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            val host = error?.url?.let { Uri.parse(it).host }
                            if (host != null && host == routerHost) handler?.proceed() else handler?.cancel()
                        }
                    }
                    vm.panelUrl?.let { loadUrl(it) }
                    webView = this
                }
            },
        )
    }
}

// =====================================================================  Diagnostics log

@Composable
private fun LogScreen(vm: MainViewModel) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf(DebugLog.text()) }
    BackHandler { vm.screen = Screen.HOME }
    Column(Modifier.fillMaxSize()) {
        TopBar("سجل التشخيص", onBack = { vm.screen = Screen.HOME })
        Column(Modifier.padding(12.dp)) {
            Text(
                "لو التحكم في راوترك مااشتغلش، انسخ السجل ده وابعته علشان نضيف دعم الموديل بتاعك. " +
                    "السجل مافيهوش الباسورد.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = {
                    val header = "Router: ${vm.router?.brand} ${vm.router?.title ?: ""} @ ${vm.router?.baseUrl}\n"
                    clip.setText(AnnotatedString(header + DebugLog.text()))
                    vm.message = "اتنسخ السجل"
                }) { Text("نسخ السجل") }
                OutlinedButton(onClick = { DebugLog.clear(); text = "" }) { Text("مسح") }
            }
            HorizontalDivider()
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                Text(text.ifEmpty { "السجل فاضي. اعمل تحديث أو سجّل دخول الراوتر الأول." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
