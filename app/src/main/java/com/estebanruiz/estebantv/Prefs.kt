package com.estebanruiz.estebantv

import android.content.Context

/** Ajustes de la app (SharedPreferences "settings"). Mantiene las claves antiguas. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var blockAds: Boolean get() = b("blockAds", true); set(v) = put("blockAds", v)
    var autoPlayer: Boolean get() = b("autoPlayer", true); set(v) = put("autoPlayer", v)
    var desktopMode: Boolean get() = b("desktopMode", false); set(v) = put("desktopMode", v)

    /** Preguntar Sí/No antes de abrir ExoPlayer. */
    var confirmExo: Boolean get() = b("confirmExo", true); set(v) = put("confirmExo", v)

    /** 0 = desactivada, 1 = solo tras 2 toques, 2 = nunca redirigir. */
    var redirMode: Int get() = i("redirMode", 1); set(v) = put("redirMode", v)

    /** 0 = sistema, 1 = automática (sensor), 2 = vertical, 3 = horizontal. */
    var rotation: Int get() = i("rotation", 1); set(v) = put("rotation", v)

    var restoreLast: Boolean get() = b("restoreLast", true); set(v) = put("restoreLast", v)
    var lastPage: String get() = s("lastPage", ""); set(v) = put("lastPage", v)
    var welcome: Boolean get() = b("welcome", true); set(v) = put("welcome", v)
    var accent: Int get() = i("accent", 0xFF39FF14.toInt()); set(v) = put("accent", v)
    var searchEngine: Int get() = i("searchEngine", 0); set(v) = put("searchEngine", v)
    var textZoom: Int get() = i("textZoom", 100); set(v) = put("textZoom", v)
    var loadImages: Boolean get() = b("loadImages", true); set(v) = put("loadImages", v)
    var thirdPartyCookies: Boolean get() = b("thirdPartyCookies", true); set(v) = put("thirdPartyCookies", v)
    var fullscreen: Boolean get() = b("fullscreen", false); set(v) = put("fullscreen", v)

    /** Altura máxima de video en ExoPlayer (0 = sin límite). */
    var maxQuality: Int get() = i("maxQuality", 0); set(v) = put("maxQuality", v)

    fun reset() {
        val keep = lastPage
        p.edit().clear().apply()
        lastPage = keep
    }
}
