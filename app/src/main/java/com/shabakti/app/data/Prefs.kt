package com.shabakti.app.data

import android.content.Context

/**
 * Local settings: router login (only if the user ticks "remember"), custom device names,
 * and the list of devices the user blocked through the app.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("shabakti", Context.MODE_PRIVATE)

    var routerUser: String
        get() = sp.getString("router_user", "") ?: ""
        set(v) = sp.edit().putString("router_user", v).apply()

    /** Stored encrypted with a Keystore-held key (see [SecretBox]). */
    var routerPass: String
        get() = SecretBox.decrypt(sp.getString("router_pass", "") ?: "")
        set(v) = sp.edit().putString("router_pass", SecretBox.encrypt(v)).apply()

    var rememberLogin: Boolean
        get() = sp.getBoolean("remember", false)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    /** Manual brand override ("AUTO" = detect). */
    var brandOverride: String
        get() = sp.getString("brand_override", "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString("brand_override", v).apply()

    fun nickname(key: String): String? = sp.getString("nick_$key", null)

    fun setNickname(key: String, name: String?) {
        if (name.isNullOrBlank()) sp.edit().remove("nick_$key").apply()
        else sp.edit().putString("nick_$key", name.trim()).apply()
    }

    fun blocked(): Set<String> = sp.getStringSet("blocked", emptySet())!!.toSet()

    fun setBlocked(mac: String, blocked: Boolean) {
        val s = blocked().toMutableSet()
        if (blocked) s.add(mac.uppercase()) else s.remove(mac.uppercase())
        sp.edit().putStringSet("blocked", s).apply()
    }
}
