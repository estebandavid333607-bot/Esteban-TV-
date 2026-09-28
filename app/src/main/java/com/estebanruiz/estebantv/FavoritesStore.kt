package com.estebanruiz.estebantv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Fav(val title: String, val url: String)

/** Guarda los favoritos en SharedPreferences como JSON. */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private fun norm(u: String) = u.trim().trimEnd('/').lowercase()

    fun all(): List<Fav> {
        val out = ArrayList<Fav>()
        try {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Fav(o.getString("title"), o.getString("url")))
            }
        } catch (e: Exception) {
            // lista corrupta: se ignora
        }
        return out
    }

    fun contains(url: String) = all().any { norm(it.url) == norm(url) }

    fun add(f: Fav) {
        if (contains(f.url)) return
        save(all() + f)
    }

    fun remove(url: String) {
        save(all().filter { norm(it.url) != norm(url) })
    }

    fun rename(url: String, title: String) {
        save(all().map { if (norm(it.url) == norm(url)) Fav(title, it.url) else it })
    }

    private fun save(list: List<Fav>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
