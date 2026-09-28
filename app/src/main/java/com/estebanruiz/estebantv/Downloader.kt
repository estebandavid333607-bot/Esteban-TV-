package com.estebanruiz.estebantv

import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Una calidad detectada en el video: resolución/bitrate y cómo descargarla. */
data class Quality(
    val label: String,
    val width: Int,
    val height: Int,
    val bandwidth: Int,
    val kind: Kind,
    val uri: String,       // playlist de esa calidad (HLS) o representación (DASH) o el mp4 directo
    val durationSec: Double,
    val isLive: Boolean
) {
    enum class Kind { PROGRESSIVE, HLS, DASH_SIMPLE, UNSUPPORTED }
}

/** Analiza un enlace (.m3u8, .mpd o video directo) y devuelve las calidades disponibles. */
object MediaProbe {

    private fun get(url: String, referer: String, range: String? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
        if (range != null) c.setRequestProperty("Range", range)
        c.instanceFollowRedirects = true
        val code = c.responseCode
        val body = try {
            BufferedInputStream(if (code in 200..399) c.inputStream else c.errorStream).readBytes()
                .toString(Charsets.UTF_8)
        } catch (e: Exception) { "" }
        c.disconnect()
        return code to body
    }

    private fun resolve(base: String, ref: String): String =
        try { Uri.parse(base).buildUpon().build(); URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    fun probe(url: String, referer: String): List<Quality> = when {
        AdBlocker.isM3u8(url) -> probeHls(url, referer)
        AdBlocker.isMpd(url) -> probeDash(url, referer)
        else -> listOf(Quality("Original", 0, 0, 0, Quality.Kind.PROGRESSIVE, url, 0.0, false))
    }

    // ---------------------------------------------------------------- HLS

    private fun probeHls(url: String, referer: String): List<Quality> {
        val (code, text) = get(url, referer)
        if (code !in 200..299 || text.isBlank()) return emptyList()

        if ("#EXT-X-STREAM-INF" in text) {
            // Playlist maestra: una entrada por calidad.
            val lines = text.lines()
            val out = ArrayList<Quality>()
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
                    val w = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) j++
                    val uri = lines.getOrNull(j)?.trim().orEmpty()
                    if (uri.isNotEmpty() && !uri.startsWith("#")) {
                        val full = resolve(url, uri)
                        val label = if (h > 0) "${h}p" else if (bw > 0) "${bw / 1000} kbps" else "Calidad"
                        out.add(Quality(label, w, h, bw, Quality.Kind.HLS, full, 0.0, false))
                    }
                    i = j
                }
                i++
            }
            // Duración real: se mide luego con la playlist de la primera calidad.
            return out.sortedByDescending { it.height * 10000 + it.bandwidth }
                .distinctBy { it.label }
                .map { q ->
                    val (dur, live) = mediaPlaylistInfo(q.uri, referer)
                    q.copy(durationSec = dur, isLive = live)
                }
        }
        // Ya es una playlist de una sola calidad (variante) o de video simple.
        val (dur, live) = playlistDuration(text) to ("#EXT-X-ENDLIST" !in text)
        return listOf(Quality("Original", 0, 0, 0, Quality.Kind.HLS, url, dur, live))
    }

    private fun mediaPlaylistInfo(url: String, referer: String): Pair<Double, Boolean> {
        val (code, text) = get(url, referer)
        if (code !in 200..299) return 0.0 to false
        return playlistDuration(text) to ("#EXT-X-ENDLIST" !in text)
    }

    private fun playlistDuration(text: String): Double =
        Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    // ---------------------------------------------------------------- DASH (best effort)

    private fun probeDash(url: String, referer: String): List<Quality> {
        val (code, text) = get(url, referer)
        if (code !in 200..299 || text.isBlank()) return emptyList()

        val isLive = Regex("type=\"dynamic\"").containsMatchIn(text)
        val durMin = Regex("mediaPresentationDuration=\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?\"").find(text)
        val duration = durMin?.let {
            val h = it.groupValues[1].toDoubleOrNull() ?: 0.0
            val m = it.groupValues[2].toDoubleOrNull() ?: 0.0
            val s = it.groupValues[3].toDoubleOrNull() ?: 0.0
            h * 3600 + m * 60 + s
        } ?: 0.0

        val reps = Regex(
            "<Representation[^>]*id=\"([^\"]*)\"[^>]*(?:width=\"(\\d+)\")?[^>]*(?:height=\"(\\d+)\")?[^>]*bandwidth=\"(\\d+)\"[^>]*>",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(text).toList()

        if (reps.isEmpty()) {
            return listOf(Quality("Original (DASH)", 0, 0, 0, Quality.Kind.UNSUPPORTED, url, duration, isLive))
        }

        return reps.map { m ->
            val w = m.groupValues[2].toIntOrNull() ?: 0
            val h = m.groupValues[3].toIntOrNull() ?: 0
            val bw = m.groupValues[4].toIntOrNull() ?: 0
            val label = if (h > 0) "${h}p" else "${bw / 1000} kbps"
            // La descarga completa de DASH por segmentos no está soportada aún: se ofrece solo el manifiesto/calidad para referencia.
            Quality(label, w, h, bw, Quality.Kind.UNSUPPORTED, url, duration, isLive)
        }.sortedByDescending { it.height * 10000 + it.bandwidth }.distinctBy { it.label }
    }
}

/** Descarga progresiva (mp4) o por segmentos (HLS), con progreso y opción de cancelar. */
object VideoDownloader {

    class Progress { @Volatile var percent = Int.MIN_VALUE; @Volatile var done = false; @Volatile var error: String? = null; @Volatile var outputPath: String? = null }

    fun cancelFlag() = AtomicBoolean(false)

    /** Descarga en un hilo aparte y reporta avance en [progress]. Llamar desde un hilo de fondo. */
    fun download(context: android.content.Context, q: Quality, referer: String, fileBaseName: String, cancel: AtomicBoolean, progress: Progress) {
        try {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val safeName = fileBaseName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "video" }

            when (q.kind) {
                Quality.Kind.PROGRESSIVE -> downloadProgressive(q.uri, referer, File(dir, "$safeName.mp4"), cancel, progress)
                Quality.Kind.HLS -> downloadHls(q.uri, referer, File(dir, "$safeName.ts"), cancel, progress)
                else -> { progress.error = "Esta calidad no se puede descargar completa todavía"; progress.done = true }
            }
            if (progress.outputPath != null) publishToDownloads(context, File(progress.outputPath!!))
        } catch (e: Exception) {
            progress.error = e.message ?: "Error al descargar"
            progress.done = true
        }
    }

    private fun downloadProgressive(url: String, referer: String, out: File, cancel: AtomicBoolean, progress: Progress) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            RandomAccessFile(out, "rw").use { raf ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (!cancel.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    read += n
                    progress.percent = if (total > 0) ((read * 100) / total).toInt() else -1
                }
            }
        }
        c.disconnect()
        if (cancel.get()) { out.delete(); progress.error = "Descarga cancelada"; progress.done = true; return }
        progress.percent = 100
        progress.outputPath = out.absolutePath
        progress.done = true
    }

    /** Descarga cada fragmento de la playlist HLS y los une en un solo .ts (reproducible en la mayoría de reproductores). */
    private fun downloadHls(playlistUrl: String, referer: String, out: File, cancel: AtomicBoolean, progress: Progress) {
        val c = URL(playlistUrl).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
        val text = c.inputStream.bufferedReader().readText()
        c.disconnect()

        if ("#EXT-X-ENDLIST" !in text) {
            progress.error = "Esto es una transmisión en vivo: no tiene un final para descargar"
            progress.done = true
            return
        }

        val segs = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> URL(URL(playlistUrl), line.trim()).toString() }
        if (segs.isEmpty()) { progress.error = "No se encontraron fragmentos de video"; progress.done = true; return }

        RandomAccessFile(out, "rw").use { raf ->
            for ((idx, seg) in segs.withIndex()) {
                if (cancel.get()) break
                try {
                    val sc = URL(seg).openConnection() as HttpURLConnection
                    sc.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (referer.isNotEmpty()) sc.setRequestProperty("Referer", referer)
                    sc.connectTimeout = 15000
                    sc.readTimeout = 20000
                    sc.inputStream.use { raf.write(it.readBytes()) }
                    sc.disconnect()
                } catch (e: Exception) { /* fragmento perdido: se sigue con el resto */ }
                progress.percent = ((idx + 1) * 100) / segs.size
            }
        }
        if (cancel.get()) { out.delete(); progress.error = "Descarga cancelada"; progress.done = true; return }
        progress.outputPath = out.absolutePath
        progress.done = true
    }

    /** Copia el archivo terminado a la carpeta pública Downloads/EstebanTV y lo hace visible. */
    private fun publishToDownloads(context: android.content.Context, src: File) {
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/*")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/EstebanTV")
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else return
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
        } catch (e: Exception) { /* si falla, el archivo sigue disponible en la carpeta interna de la app */ }
    }
}
