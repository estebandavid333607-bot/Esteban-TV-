package com.estebanruiz.estebantv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "ExoPlayer with extras": reproductor con selector de resolución, velocidad, aspecto,
 * audio/subtítulos, gestos (doble toque ±10 s, brillo, volumen), bloqueo de controles,
 * rotación manual, buffer ampliado, reintento automático y copiar enlace.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val act: AppCompatActivity,
    private val layer: View,
    private val prefs: Prefs,
    private val host: Host
) {
    interface Host {
        fun accent(): Int
        fun setOrientation(o: Int)
        fun onPlayerClosed()
        fun showDialog(b: AlertDialog.Builder): AlertDialog
    }

    private class Opt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    private val playerView: PlayerView = layer.findViewById(R.id.playerView)
    private val gestureLayer: View = layer.findViewById(R.id.gestureLayer)
    private val topBar: View = layer.findViewById(R.id.playerTopBar)
    private val tvTitle: TextView = layer.findViewById(R.id.tvPlayerTitle)
    private val btnQuality: TextView = layer.findViewById(R.id.btnQuality)
    private val btnSpeed: TextView = layer.findViewById(R.id.btnSpeed)
    private val btnAspect: TextView = layer.findViewById(R.id.btnAspect)
    private val btnTracks: TextView = layer.findViewById(R.id.btnTracks)
    private val btnRotate: TextView = layer.findViewById(R.id.btnRotate)
    private val btnCopy: TextView = layer.findViewById(R.id.btnCopy)
    private val btnLock: TextView = layer.findViewById(R.id.btnLock)
    private val btnClose: ImageButton = layer.findViewById(R.id.btnClosePlayer)
    private val hudView: TextView = layer.findViewById(R.id.tvHud)

    private val handler = Handler(Looper.getMainLooper())
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var player: ExoPlayer? = null
        private set

    @Volatile
    var isOpen = false
        private set

    var isLocked = false
        private set

    private var currentUrl = ""
    private var aspectIdx = 0
    private var brightness = 0.5f
    private var volAcc = 0f

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val aspectNames = arrayOf("Ajustar", "Recortar", "Estirar")
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val hideHud = Runnable { hudView.visibility = View.GONE }
    private val hideLock = Runnable { if (isLocked) btnLock.visibility = View.GONE }

    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_SHORT).show()

    init {
        btnClose.setOnClickListener { close() }
        btnQuality.setOnClickListener { showQuality() }
        btnSpeed.setOnClickListener { showSpeed() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnTracks.setOnClickListener { showTracksMenu() }
        btnRotate.setOnClickListener { rotate() }
        btnCopy.setOnClickListener { copyLink() }
        btnLock.setOnClickListener { setLocked(!isLocked) }

        playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                if (isLocked) return
                val v = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                topBar.visibility = v
                btnLock.visibility = v
            }
        })

        val detector = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                val cur = act.window.attributes.screenBrightness
                brightness = if (cur >= 0f) cur else 0.5f
                volAcc = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val p = player ?: return true
                val left = e.x < gestureLayer.width / 2f
                var target = p.currentPosition + if (left) -10_000L else 10_000L
                if (target < 0) target = 0
                if (p.duration != C.TIME_UNSET) target = min(target, p.duration)
                p.seekTo(target)
                hud(if (left) "⏪  -10 s" else "+10 s  ⏩")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null) return false
                if (abs(distanceY) < abs(distanceX)) return false
                val frac = distanceY / gestureLayer.height.coerceAtLeast(1)
                if (e1.x < gestureLayer.width / 2f) adjustBrightness(frac) else adjustVolume(frac)
                return true
            }
        })
        gestureLayer.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
    }

    // ------------------------------------------------------------------ abrir / cerrar

    fun open(url: String, referer: String, userAgent: String, cookie: String?, title: String) {
        if (isOpen) return
        isOpen = true
        currentUrl = url
        isLocked = false
        aspectIdx = 0
        playerView.resizeMode = resizeModes[0]
        playerView.useController = true

        val headers = HashMap<String, String>()
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
            val u = Uri.parse(referer)
            if (u.scheme != null && u.host != null) headers["Origin"] = "${u.scheme}://${u.host}"
        }
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .apply {
                when {
                    AdBlocker.isM3u8(url) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    AdBlocker.isMpd(url) -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()

        // Buffer ampliado + decodificador alternativo si el principal falla
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 1_500, 3_000)
            .build()
        val renderers = DefaultRenderersFactory(act).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(act, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        val tp = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("es")
        if (prefs.maxQuality > 0) tp.setMaxVideoSize(Int.MAX_VALUE, prefs.maxQuality)
        p.trackSelectionParameters = tp.build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                showError(error)
            }

            override fun onTracksChanged(tracks: Tracks) = updateQualityLabel()
            override fun onVideoSizeChanged(videoSize: VideoSize) = updateQualityLabel()
        })

        playerView.player = p
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        player = p

        tvTitle.text = title
        applyAccent()
        updateQualityLabel()
        btnSpeed.text = "Velocidad 1x"
        btnAspect.text = "Aspecto: ${aspectNames[0]}"
        btnLock.text = "🔓"
        topBar.visibility = View.VISIBLE
        btnLock.visibility = View.VISIBLE
        layer.visibility = View.VISIBLE
        layer.keepScreenOn = true
        playerView.showController()
    }

    fun close() {
        if (!isOpen) return
        release()
        layer.visibility = View.GONE
        layer.keepScreenOn = false
        val lp = act.window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lp
        hudView.visibility = View.GONE
        isLocked = false
        isOpen = false
        host.onPlayerClosed()
    }

    /** Libera el reproductor sin tocar la interfaz (onDestroy). */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
    }

    fun pause() {
        player?.pause()
    }

    private fun showError(error: PlaybackException) {
        val p = player ?: return
        val b = AlertDialog.Builder(act)
            .setTitle("No se pudo reproducir")
            .setMessage("Error: ${error.errorCodeName}")
            .setPositiveButton("Reintentar") { _, _ ->
                p.prepare()
                p.playWhenReady = true
            }
            .setNegativeButton("Cerrar") { _, _ -> close() }
            .setCancelable(false)
        host.showDialog(b)
    }

    // ------------------------------------------------------------------ apariencia

    fun applyAccent() {
        val a = host.accent()
        val stroke = Ui.dp(act, 1)
        listOf(btnQuality, btnSpeed, btnAspect, btnTracks, btnRotate, btnCopy).forEach {
            it.background = Ui.outline(Color.parseColor("#B3000000"), a, stroke, Ui.dp(act, 18).toFloat())
        }
        btnLock.background = Ui.outline(Color.parseColor("#B3000000"), a, stroke, 0f, oval = true)
        btnClose.background = Ui.outline(Color.parseColor("#B3000000"), a, stroke, 0f, oval = true)
        btnClose.setColorFilter(a)
    }

    private fun hud(text: String) {
        hudView.text = text
        hudView.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 800)
    }

    private fun toggleControls() {
        if (isLocked) {
            btnLock.visibility = View.VISIBLE
            handler.removeCallbacks(hideLock)
            handler.postDelayed(hideLock, 2500)
            return
        }
        if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
    }

    private fun setLocked(v: Boolean) {
        isLocked = v
        handler.removeCallbacks(hideLock)
        if (v) {
            playerView.useController = false
            topBar.visibility = View.GONE
            btnLock.text = "🔒"
            btnLock.visibility = View.VISIBLE
            hud("Controles bloqueados")
            handler.postDelayed(hideLock, 2500)
        } else {
            playerView.useController = true
            playerView.showController()
            topBar.visibility = View.VISIBLE
            btnLock.text = "🔓"
            btnLock.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ gestos

    private fun adjustBrightness(frac: Float) {
        brightness = (brightness + frac * 1.2f).coerceIn(0.02f, 1f)
        val lp = act.window.attributes
        lp.screenBrightness = brightness
        act.window.attributes = lp
        hud("🔆  ${(brightness * 100).roundToInt()}%")
    }

    private fun adjustVolume(frac: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volAcc = (volAcc + frac * max * 1.2f).coerceIn(0f, max.toFloat())
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.roundToInt(), 0)
        hud("🔊  ${(volAcc / max * 100).roundToInt()}%")
    }

    // ------------------------------------------------------------------ resolución

    private fun qLabel(f: Format): String {
        val h = f.height
        val tag = when {
            h >= 2160 -> " · 4K"
            h >= 1440 -> " · 2K"
            h >= 1080 -> " · Full HD"
            h >= 720 -> " · HD"
            else -> ""
        }
        val fps = if (f.frameRate >= 50f) " ${f.frameRate.roundToInt()}fps" else ""
        val br = if (f.bitrate > 0) " · " + String.format(Locale.US, "%.1f", f.bitrate / 1_000_000f) + " Mbps" else ""
        return "${h}p$fps$tag$br"
    }

    private fun qualityOptions(p: ExoPlayer): List<Opt> {
        class Raw(val g: Tracks.Group, val i: Int, val f: Format)
        val raws = ArrayList<Raw>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                if (f.height > 0) raws.add(Raw(g, i, f))
            }
        }
        return raws
            .sortedWith(compareByDescending<Raw> { it.f.height }.thenByDescending { it.f.bitrate })
            .distinctBy { it.f.height }
            .map { Opt(it.g, it.i, qLabel(it.f), it.g.isTrackSelected(it.i)) }
    }

    private fun updateQualityLabel() {
        val p = player ?: return
        val h = p.videoSize.height
        val forced = p.trackSelectionParameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO }
        btnQuality.text = when {
            h <= 0 -> "Resolución"
            forced -> "Resolución: ${h}p"
            else -> "Resolución: ${h}p (Auto)"
        }
    }

    private fun showQuality() {
        val p = player ?: return
        val opts = qualityOptions(p)
        if (opts.isEmpty()) {
            val h = p.videoSize.height
            toast(if (h > 0) "Este video solo tiene una resolución: ${h}p" else "Resolución aún no disponible")
            return
        }
        val params = p.trackSelectionParameters
        val auto = params.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }
        val items = listOf("Automática") + opts.map { it.label }
        val idx = opts.indexOfFirst { o ->
            params.overrides[o.group.mediaTrackGroup]?.trackIndices?.contains(o.index) == true
        }
        val checked = if (auto || idx < 0) 0 else idx + 1
        showPicker("Resolución disponible", items, checked) { which ->
            val b = p.trackSelectionParameters.buildUpon()
            if (which == 0) {
                b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            } else {
                val o = opts[which - 1]
                b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
            }
            p.trackSelectionParameters = b.build()
            hud(if (which == 0) "Resolución: Automática" else "Resolución: ${items[which].substringBefore(" ·")}")
        }
    }

    // ------------------------------------------------------------------ audio / subtítulos

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language
        val name = f.label?.takeIf { it.isNotBlank() }
            ?: lang?.takeIf { it.isNotBlank() && it != "und" }?.let {
                Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it
            }
            ?: "Pista $n"
        val ch = if (f.channelCount > 2) " · ${f.channelCount} canales" else ""
        return name + ch
    }

    private fun showTracksMenu() {
        showPicker("Audio y subtítulos", listOf("Audio", "Subtítulos"), -1) { which ->
            showTrackPicker(if (which == 0) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT)
        }
    }

    private fun showTrackPicker(type: Int) {
        val p = player ?: return
        val opts = ArrayList<Opt>()
        var n = 1
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                opts.add(Opt(g, i, trackLabel(g.getTrackFormat(i), n++), g.isTrackSelected(i)))
            }
        }
        val isText = type == C.TRACK_TYPE_TEXT
        if (opts.isEmpty()) {
            toast(if (isText) "Este video no tiene subtítulos" else "Este video no tiene audios alternativos")
            return
        }
        val offset = if (isText) 1 else 0
        val items = (if (isText) listOf("Desactivados") else emptyList()) + opts.map { it.label }
        val sel = opts.indexOfFirst { it.selected }
        val checked = if (sel >= 0) sel + offset else if (isText) 0 else -1
        showPicker(if (isText) "Subtítulos" else "Audio", items, checked) { which ->
            val b = p.trackSelectionParameters.buildUpon()
            if (isText && which == 0) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val o = opts[which - offset]
                b.setTrackTypeDisabled(type, false)
                b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
            }
            p.trackSelectionParameters = b.build()
        }
    }

    // ------------------------------------------------------------------ velocidad, aspecto, rotación

    private fun showSpeed() {
        val p = player ?: return
        val cur = p.playbackParameters.speed
        val items = speeds.map { if (it == 1f) "Normal (1x)" else fmtSpeed(it) }
        val checked = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        showPicker("Velocidad", items, checked) { which ->
            p.setPlaybackSpeed(speeds[which])
            btnSpeed.text = "Velocidad ${fmtSpeed(speeds[which])}"
        }
    }

    private fun fmtSpeed(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[aspectIdx]
        btnAspect.text = "Aspecto: ${aspectNames[aspectIdx]}"
        hud("Aspecto: ${aspectNames[aspectIdx]}")
    }

    private fun rotate() {
        val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        host.setOrientation(
            if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        )
    }

    private fun copyLink() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("video", currentUrl))
        toast("Enlace del video copiado")
    }

    // ------------------------------------------------------------------ util

    private fun showPicker(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val b = AlertDialog.Builder(act)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton("Cerrar", null)
        host.showDialog(b)
    }
}
