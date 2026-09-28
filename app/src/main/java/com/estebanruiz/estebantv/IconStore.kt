package com.estebanruiz.estebantv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/** Guarda el logotipo (favicon) de cada web en disco y lo descarga si hace falta. */
class IconStore(ctx: Context) {

    private val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
    private val cache = HashMap<String, Bitmap>()
    private val io = Executors.newFixedThreadPool(2)
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val main = Handler(Looper.getMainLooper())

    private fun key(url: String): String = AdBlocker.host(url).removePrefix("www.")
    private fun file(k: String) = File(dir, k.replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    @Synchronized
    fun get(url: String): Bitmap? {
        val k = key(url)
        if (k.isEmpty()) return null
        cache[k]?.let { return it }
        val f = file(k)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        cache[k] = b
        return b
    }

    /** Guarda el icono si es mejor (más grande) que el que ya había. */
    @Synchronized
    fun save(url: String, b: Bitmap) {
        val k = key(url)
        if (k.isEmpty()) return
        val old = cache[k] ?: file(k).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (old != null && old.width >= b.width) return
        try {
            file(k).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cache[k] = b
        } catch (e: Exception) {
            // sin espacio o sin permisos: se ignora
        }
    }

    /** Descarga el logotipo en segundo plano; [done] se llama en el hilo principal si se consiguió. */
    fun ensure(url: String, done: () -> Unit) {
        val k = key(url)
        if (k.isEmpty() || get(url) != null || !pending.add(k)) return
        io.execute {
            var bmp: Bitmap? = null
            val candidates = listOf(
                "https://www.google.com/s2/favicons?domain=$k&sz=128" to 32,
                "https://$k/apple-touch-icon.png" to 32,
                "https://$k/favicon.ico" to 16
            )
            for ((u, minW) in candidates) {
                bmp = download(u, minW)
                if (bmp != null) break
            }
            pending.remove(k)
            if (bmp != null) {
                save(url, bmp)
                main.post(done)
            }
        }
    }

    private fun download(u: String, minW: Int): Bitmap? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode != 200) null
        else c.inputStream.use { BitmapFactory.decodeStream(it) }?.takeIf { it.width >= minW && it.height >= minW }
    } catch (e: Exception) {
        null
    }
}
