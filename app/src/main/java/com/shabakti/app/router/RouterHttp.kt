package com.shabakti.app.router

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** In-memory log of router traffic (passwords are never written) for troubleshooting. */
object DebugLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}

class HttpResult(val code: Int, val body: String, val headers: String, val finalUrl: String)

/**
 * HTTP session against the router only. Keeps cookies, reuses a single keep-alive connection
 * (Huawei binds its login token to the TCP connection) and accepts the self-signed HTTPS
 * certificates that home routers use. Never use this client for internet hosts.
 */
class RouterHttp(baseUrl: String) {
    val base: String = baseUrl.trimEnd('/')
    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                val m = store.getOrPut(url.host) { mutableMapOf() }
                cookies.forEach { m[it.name] = it }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store[url.host]?.values?.toList() ?: emptyList() }
    }

    private val client: OkHttpClient = run {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun setCookie(name: String, value: String) {
        val host = base.toHttpUrl().host
        val c = Cookie.Builder().name(name).value(value).hostOnlyDomain(host).path("/").build()
        synchronized(store) { store.getOrPut(host) { mutableMapOf() }[name] = c }
    }

    fun cookies(): List<Cookie> = synchronized(store) { store.values.flatMap { it.values } }

    fun clearCookies() = synchronized(store) { store.clear() }

    fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResult =
        exec("GET", path, null, headers)

    fun post(path: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResult {
        val b = FormBody.Builder()
        form.forEach { (k, v) -> b.add(k, v) }
        return exec("POST", path, b.build(), headers, form.keys)
    }

    fun postRaw(path: String, body: RequestBody, headers: Map<String, String> = emptyMap()): HttpResult =
        exec("POST", path, body, headers)

    private fun exec(
        method: String,
        path: String,
        body: RequestBody?,
        headers: Map<String, String>,
        formKeys: Set<String> = emptySet(),
    ): HttpResult {
        val url = if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")
        val rb = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Referer", "$base/")
            .header("Accept", "*/*")
        headers.forEach { (k, v) -> rb.header(k, v) }
        when (method) {
            "POST" -> rb.post(body ?: FormBody.Builder().build())
            else -> rb.get()
        }
        val shownPath = url.removePrefix(base)
        try {
            client.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                DebugLog.add(
                    "$method $shownPath ${if (formKeys.isNotEmpty()) "fields=$formKeys " else ""}→ ${resp.code}, " +
                        "${text.length}b: ${text.take(300).replace('\n', ' ')}"
                )
                return HttpResult(resp.code, text, resp.headers.toString(), resp.request.url.toString())
            }
        } catch (e: Exception) {
            DebugLog.add("$method $shownPath ✗ ${e.javaClass.simpleName}: ${e.message}")
            throw RouterException("مش قادر أوصل للراوتر (${e.javaClass.simpleName})", e.message)
        }
    }
}
