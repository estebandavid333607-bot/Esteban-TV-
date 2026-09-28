package com.estebanruiz.estebantv

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var progress: ProgressBar
    private lateinit var home: View
    private lateinit var btnHome: ImageButton
    private lateinit var btnFav: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var favContainer: LinearLayout
    private lateinit var googleCard: View
    private lateinit var homeTitle: TextView
    private lateinit var homeFavLabel: TextView
    private lateinit var homeMadeBy: TextView
    private lateinit var playerLayer: View
    private lateinit var customContainer: FrameLayout
    private lateinit var favs: FavoritesStore
    private lateinit var prefs: Prefs
    private lateinit var icons: IconStore
    private lateinit var pc: PlayerController

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUa = ""
    private var pageTitle = ""
    private var clearHistoryOnBlank = false
    private var askDialog: AlertDialog? = null
    private val iconRequested = HashSet<String>()
    private val noExoSites: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var currentPageUrl = ""
    @Volatile private var sniffCooldownUntil = 0L

    // Protección anti-redirección
    @Volatile private var tapCount = 0
    @Volatile private var lastLinkHref = ""
    @Volatile private var lastLinkTrusted = false
    @Volatile private var lastLinkTime = 0L
    private var lastBlocked = ""
    private var lastBlockedToast = 0L
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private val accent: Int get() = prefs.accent

    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val engines = arrayOf(
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Brave" to "https://search.brave.com/search?q="
    )

    private val palette = arrayOf(
        "Verde neón" to 0xFF39FF14.toInt(),
        "Cian" to 0xFF00E5FF.toInt(),
        "Azul" to 0xFF448AFF.toInt(),
        "Violeta" to 0xFFB388FF.toInt(),
        "Magenta" to 0xFFFF2BD6.toInt(),
        "Rojo" to 0xFFFF1744.toInt(),
        "Naranja" to 0xFFFF9100.toInt(),
        "Amarillo" to 0xFFFFEA00.toInt(),
        "Blanco" to 0xFFFFFFFF.toInt()
    )

    private val playerHost = object : PlayerController.Host {
        override fun accent(): Int = prefs.accent
        override fun setOrientation(o: Int) {
            requestedOrientation = o
        }

        override fun onPlayerClosed() {
            hideSystemBars(prefs.fullscreen)
            applyRotation()
            webView.onResume()
            sniffCooldownUntil = System.currentTimeMillis() + 3000
        }

        override fun showDialog(b: AlertDialog.Builder): AlertDialog = showDlg(b)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        favs = FavoritesStore(this)
        prefs = Prefs(this)
        icons = IconStore(this)

        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        progress = findViewById(R.id.progress)
        home = findViewById(R.id.home)
        btnHome = findViewById(R.id.btnHome)
        btnFav = findViewById(R.id.btnFav)
        btnMenu = findViewById(R.id.btnMenu)
        favContainer = findViewById(R.id.favContainer)
        googleCard = findViewById(R.id.googleCard)
        homeTitle = findViewById(R.id.homeTitle)
        homeFavLabel = findViewById(R.id.homeFavLabel)
        homeMadeBy = findViewById(R.id.homeMadeBy)
        playerLayer = findViewById(R.id.playerLayer)
        customContainer = findViewById(R.id.customContainer)

        pc = PlayerController(this, playerLayer, prefs, playerHost)

        setupWebView()
        setupUi()
        showUrlSlogan()
        applyRotation()
        hideSystemBars(prefs.fullscreen)
        applyAccent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    pc.isOpen -> if (pc.isLocked) toast("Desbloquea los controles (🔒) para salir") else pc.close()
                    webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
                    webView.visibility == View.VISIBLE -> goHome()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            if (prefs.welcome) showWelcome()
            if (prefs.restoreLast && prefs.lastPage.isNotEmpty()) openUrl(prefs.lastPage)
        }
    }

    override fun onPause() {
        super.onPause()
        pc.pause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!pc.isOpen) webView.onResume()
    }

    override fun onDestroy() {
        pc.release()
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz

    private fun setupUi() {
        btnHome.setOnClickListener { goHome() }
        googleCard.setOnClickListener { openUrl("https://www.google.com") }
        btnMenu.setOnClickListener { showMenu(it) }
        btnFav.setOnClickListener { toggleFavorite() }

        etUrl.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadInput(etUrl.text.toString())
                hideKeyboard()
                true
            } else false
        }
        // Con el foco se ve la URL real (para copiarla/editarla); sin foco se ve el nombre de la app.
        etUrl.setOnFocusChangeListener { _, focused ->
            if (focused) {
                etUrl.setText(currentPageUrl)
                etUrl.setSelection(etUrl.text?.length ?: 0)
            } else {
                showUrlSlogan()
            }
        }
    }

    /** Muestra el nombre/eslogan de la app en la barra en vez de la URL real. */
    private fun showUrlSlogan() {
        if (etUrl.hasFocus()) return
        etUrl.setText(if (currentPageUrl.isEmpty()) getString(R.string.app_name_caps) else getString(R.string.url_slogan))
    }

    /** Aplica el color (paleta) elegido a toda la interfaz. */
    private fun applyAccent() {
        val a = accent
        progress.progressTintList = ColorStateList.valueOf(a)
        listOf(btnHome, btnFav, btnMenu).forEach { it.setColorFilter(a) }
        etUrl.background = Ui.outline(Color.parseColor("#0F0F0F"), a, dp(1), dp(22).toFloat())
        googleCard.background = Ui.outline(Color.parseColor("#0A0A0A"), a, dp(2), dp(18).toFloat())
        homeTitle.setTextColor(a)
        homeTitle.setShadowLayer(20f, 0f, 0f, a)
        homeFavLabel.setTextColor(a)
        homeMadeBy.setTextColor(ColorUtils.blendARGB(a, Color.BLACK, 0.45f))
        pc.applyAccent()
        refreshFavs()
    }

    private fun applyRotation() {
        requestedOrientation = when (prefs.rotation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyWebSettings() {
        with(webView.settings) {
            textZoom = prefs.textZoom
            blockNetworkImage = !prefs.loadImages
            userAgentString = if (prefs.desktopMode) desktopUa else defaultUa
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, prefs.thirdPartyCookies)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
        etUrl.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showDlg(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.show()
        val a = accent
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(a)
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(a)
        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(a)
        return d
    }

    private fun showWelcome() {
        val card = TextView(this).apply {
            text = "👋 ¡Bienvenido a Esteban TV!\nApp hecha por Esteban Ruiz"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = Ui.outline(Color.parseColor("#F2000000"), accent, dp(2), dp(18).toFloat())
            elevation = dp(8).toFloat()
            alpha = 0f
        }
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        // 300 ms entrada + 1400 ms visible + 300 ms salida ≈ 2 s
        card.animate().alpha(1f).setDuration(300).withEndAction {
            card.animate().alpha(0f).setStartDelay(1400).setDuration(300).withEndAction {
                (card.parent as? ViewGroup)?.removeView(card)
            }.start()
        }.start()
    }

    // ------------------------------------------------------------------ menú ⋮

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        val onPage = webView.visibility == View.VISIBLE && currentPageUrl.isNotEmpty()
        m.add(0, 1, 0, "Recargar")
        m.add(0, 2, 1, "Modo escritorio").apply { isCheckable = true; isChecked = prefs.desktopMode }
        if (onPage) {
            m.add(0, 3, 2, "Copiar enlace")
            m.add(0, 4, 3, "Compartir enlace")
            m.add(0, 5, 4, "Abrir en otro navegador")
        }
        if (lastBlocked.isNotEmpty()) m.add(0, 6, 5, "Permitir última redirección bloqueada")
        if (onPage && isNoExo()) m.add(0, 7, 6, "Volver a usar ExoPlayer en este sitio")
        m.add(0, 10, 7, "Descargar video")
        m.add(0, 8, 8, "Ajustes")
        m.add(0, 9, 9, "Acerca de")

        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (webView.visibility == View.VISIBLE) webView.reload()
                2 -> setDesktop(!prefs.desktopMode)
                3 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("enlace", currentPageUrl))
                    toast("Enlace copiado")
                }
                4 -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentPageUrl),
                        "Compartir enlace"
                    )
                )
                5 -> try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentPageUrl)))
                } catch (e: Exception) {
                    toast("No hay otro navegador")
                }
                6 -> {
                    val u = lastBlocked
                    lastBlocked = ""
                    if (u.isNotEmpty()) openUrl(u)
                }
                7 -> {
                    noExoSites.remove(AdBlocker.registrable(AdBlocker.host(currentPageUrl)))
                    webView.reload()
                }
                8 -> showSettings()
                9 -> showAbout()
                10 -> showDownloader()
            }
            true
        }
        pm.show()
    }

    private fun setDesktop(v: Boolean) {
        prefs.desktopMode = v
        webView.settings.userAgentString = if (v) desktopUa else defaultUa
        if (webView.visibility == View.VISIBLE) webView.reload()
    }

    private fun showAbout() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Esteban TV")
                .setMessage("App hecha por Esteban Ruiz\nVersión $ver")
                .setPositiveButton("OK", null)
        )
    }

    // ------------------------------------------------------------------ ajustes

    private fun showSettings() {
        val a = accent
        val dim = Color.parseColor("#8FBF86")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val scroll = ScrollView(this)
        scroll.addView(col)

        fun section(t: String) {
            val tv = TextView(this)
            tv.text = t
            tv.setTextColor(a)
            tv.textSize = 12f
            tv.letterSpacing = 0.12f
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding(0, dp(18), 0, dp(4))
            col.addView(tv)
        }

        fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val t = TextView(this)
            t.text = title
            t.setTextColor(Color.WHITE)
            t.textSize = 15f
            texts.addView(t)
            if (sub != null) {
                val s = TextView(this)
                s.text = sub
                s.setTextColor(dim)
                s.textSize = 12f
                texts.addView(s)
            }
            val sw = SwitchCompat(this)
            sw.isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            sw.thumbTintList = ColorStateList(states, intArrayOf(a, Color.GRAY))
            sw.trackTintList = ColorStateList(
                states, intArrayOf(ColorUtils.setAlphaComponent(a, 110), Color.parseColor("#33FFFFFF"))
            )
            sw.setOnCheckedChangeListener { _, v -> onChange(v) }
            row.setOnClickListener { sw.toggle() }
            row.addView(texts)
            row.addView(sw)
            col.addView(row)
        }

        fun choiceRow(title: String, options: Array<String>, current: () -> Int, onPick: (Int) -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))
            row.isClickable = true
            val t = TextView(this)
            t.text = title
            t.setTextColor(Color.WHITE)
            t.textSize = 15f
            val v = TextView(this)
            v.text = options[current().coerceIn(0, options.size - 1)]
            v.setTextColor(a)
            v.textSize = 13f
            row.addView(t)
            row.addView(v)
            row.setOnClickListener {
                showDlg(
                    AlertDialog.Builder(this)
                        .setTitle(title)
                        .setSingleChoiceItems(options, current()) { d, w ->
                            onPick(w)
                            v.text = options[w]
                            d.dismiss()
                        }
                        .setNegativeButton("Cancelar", null)
                )
            }
            col.addView(row)
        }

        fun actionRow(title: String, sub: String?, onClick: () -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))
            row.isClickable = true
            val t = TextView(this)
            t.text = title
            t.setTextColor(Color.WHITE)
            t.textSize = 15f
            row.addView(t)
            if (sub != null) {
                val s = TextView(this)
                s.text = sub
                s.setTextColor(dim)
                s.textSize = 12f
                row.addView(s)
            }
            row.setOnClickListener { onClick() }
            col.addView(row)
        }

        // ---- Navegación
        section("NAVEGACIÓN")
        choiceRow("Motor de búsqueda", engines.map { it.first }.toTypedArray(),
            { prefs.searchEngine }, { prefs.searchEngine = it })
        choiceRow("Protección anti-redirección",
            arrayOf("Desactivada", "Solo tras 2 toques (recomendado)", "Nunca redirigir a otras webs"),
            { prefs.redirMode }, { prefs.redirMode = it })
        switchRow("Bloquear popups y anuncios", "Filtra dominios de publicidad y ventanas emergentes",
            prefs.blockAds) { prefs.blockAds = it }
        switchRow("Modo escritorio", "Pide la versión de PC de las webs", prefs.desktopMode) { setDesktop(it) }
        val zoomVals = intArrayOf(85, 100, 125, 150)
        choiceRow("Tamaño del texto", arrayOf("Pequeño (85%)", "Normal (100%)", "Grande (125%)", "Muy grande (150%)"),
            { zoomVals.indexOf(prefs.textZoom).coerceAtLeast(1) }) {
            prefs.textZoom = zoomVals[it]
            webView.settings.textZoom = zoomVals[it]
        }
        switchRow("Cargar imágenes", "Apágalo para ahorrar datos", prefs.loadImages) {
            prefs.loadImages = it
            webView.settings.blockNetworkImage = !it
        }
        switchRow("Cookies de terceros", "Algunas webs las necesitan para iniciar sesión",
            prefs.thirdPartyCookies) {
            prefs.thirdPartyCookies = it
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, it)
        }
        switchRow("Recordar la última página", "Al abrir la app vuelve a donde estabas", prefs.restoreLast) {
            prefs.restoreLast = it
            if (!it) prefs.lastPage = ""
        }
        switchRow("Pantalla completa", "Oculta las barras del sistema", prefs.fullscreen) {
            prefs.fullscreen = it
            hideSystemBars(it)
        }

        // ---- Reproductor
        section("REPRODUCTOR (EXOPLAYER)")
        switchRow("Abrir videos en ExoPlayer", "Detecta streams .m3u8, .mpd y .mp4", prefs.autoPlayer) {
            prefs.autoPlayer = it
        }
        switchRow("Preguntar antes de abrir", "Muestra Sí / No con el nombre del video", prefs.confirmExo) {
            prefs.confirmExo = it
        }
        val qVals = intArrayOf(0, 1080, 720, 480, 360)
        choiceRow("Resolución máxima", arrayOf("Sin límite (Auto)", "1080p", "720p", "480p", "360p"),
            { qVals.indexOf(prefs.maxQuality).coerceAtLeast(0) }) { prefs.maxQuality = qVals[it] }

        // ---- Apariencia
        section("APARIENCIA")
        choiceRow("Paleta de colores", palette.map { it.first }.toTypedArray(),
            { palette.indexOfFirst { it.second == prefs.accent }.coerceAtLeast(0) }) {
            prefs.accent = palette[it].second
            applyAccent()
        }
        choiceRow("Rotación de pantalla",
            arrayOf("Según el sistema", "Automática (siempre gira)", "Solo vertical", "Solo horizontal"),
            { prefs.rotation }) {
            prefs.rotation = it
            applyRotation()
        }
        switchRow("Mensaje de bienvenida", "Aparece 2 segundos al abrir la app", prefs.welcome) { prefs.welcome = it }

        // ---- Datos
        section("DATOS")
        actionRow("Borrar caché", "Libera espacio de las páginas guardadas") {
            webView.clearCache(true)
            toast("Caché borrada")
        }
        actionRow("Borrar cookies y datos de sitios", "Cierra tus sesiones en las webs") {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            toast("Cookies y datos borrados")
        }
        actionRow("Restablecer ajustes", "Vuelve a los valores originales (no borra favoritos)") {
            showDlg(
                AlertDialog.Builder(this)
                    .setTitle("Restablecer ajustes")
                    .setMessage("¿Volver a los ajustes originales?")
                    .setPositiveButton("Restablecer") { _, _ ->
                        prefs.reset()
                        noExoSites.clear()
                        applyWebSettings()
                        applyRotation()
                        hideSystemBars(prefs.fullscreen)
                        applyAccent()
                        toast("Ajustes restablecidos")
                    }
                    .setNegativeButton("Cancelar", null)
            )
        }

        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(scroll)
                .setPositiveButton("Listo", null)
        )
    }

    // ------------------------------------------------------------------ navegación

    private fun loadInput(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            t.contains(' ') || !t.contains('.') ->
                engines[prefs.searchEngine.coerceIn(0, engines.size - 1)].second + Uri.encode(t)
            else -> "https://$t"
        }
        openUrl(url)
    }

    private fun openUrl(url: String) {
        tapCount = 0
        home.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun goHome() {
        if (webView.visibility == View.VISIBLE) {
            clearHistoryOnBlank = true
            webView.loadUrl("about:blank")
        }
        webView.visibility = View.GONE
        home.visibility = View.VISIBLE
        progress.visibility = View.GONE
        currentPageUrl = ""
        pageTitle = ""
        prefs.lastPage = ""
        etUrl.setText("")
        showUrlSlogan()
        hideKeyboard()
        refreshFavs()
        updateFavIcon()
    }

    // ------------------------------------------------------------------ favoritos

    private fun updateFavIcon() {
        val fav = currentPageUrl.isNotEmpty() && favs.contains(currentPageUrl)
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun toggleFavorite() {
        val url = currentPageUrl
        if (url.isEmpty()) {
            toast("Abre una página primero")
            return
        }
        if (favs.contains(url)) {
            favs.remove(url)
            toast("Quitado de favoritos")
            updateFavIcon()
        } else {
            askNameAndSave(cleanTitle(pageTitle).ifEmpty { suggestName(url) }, url)
        }
    }

    /** Antes de guardar, deja elegir el nombre (con una sugerencia). */
    private fun askNameAndSave(suggested: String, url: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = styledEdit("Nombre", suggested)
        name.selectAll()
        box.addView(name)
        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Guardar en favoritos")
                .setMessage(AdBlocker.host(url).removePrefix("www."))
                .setView(box)
                .setPositiveButton("Guardar") { _, _ ->
                    val n = name.text.toString().trim().ifEmpty { suggestName(url) }
                    favs.add(Fav(n, url))
                    icons.ensure(url) { refreshFavs() }
                    toast("Agregado a favoritos")
                    updateFavIcon()
                    refreshFavs()
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    private fun styledEdit(hint: String, text: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(text)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.parseColor("#8FBF86"))
        e.isSingleLine = true
        return e
    }

    /** "Mi página - Sitio | Otra cosa" -> "Mi página". */
    private fun cleanTitle(t: String): String {
        val first = t.split(" - ", " | ", " – ", " · ").map { it.trim() }.firstOrNull { it.length >= 2 } ?: return ""
        return if (first.length > 30) first.take(30).trim() else first
    }

    /** youtube.com -> "Youtube". */
    private fun suggestName(u: String): String {
        val t = u.trim()
        if (t.isEmpty()) return ""
        val h = AdBlocker.host(if (t.startsWith("http", true)) t else "https://$t").removePrefix("www.")
        if (h.isEmpty()) return ""
        return AdBlocker.registrable(h).substringBefore('.').replaceFirstChar { it.uppercaseChar() }
    }

    private fun refreshFavs() {
        if (!::favContainer.isInitialized) return
        favContainer.removeAllViews()
        val cells = ArrayList<View>()
        favs.all().forEach { f ->
            val icon = icons.get(f.url)
            if (icon == null && iconRequested.add(f.url)) {
                icons.ensure(f.url) { if (home.visibility == View.VISIBLE) refreshFavs() }
            }
            val letter = f.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            cells.add(makeTile(f.title, icon, letter, { openUrl(f.url) }, { favMenu(f) }))
        }
        cells.add(makeTile("Agregar", null, "+", { showAddDialog() }, null))

        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            rowCells.forEach { row.addView(it) }
            repeat(3 - rowCells.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            favContainer.addView(row)
        }
    }

    private fun makeTile(
        label: String, icon: Bitmap?, letter: String, onClick: () -> Unit, onLong: (() -> Unit)?
    ): View {
        val a = accent
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val circle = FrameLayout(this)
        circle.background = Ui.outline(
            if (icon != null) Color.WHITE else Color.parseColor("#0A0A0A"), a, dp(2), 0f, oval = true
        )
        circle.outlineProvider = ViewOutlineProvider.BACKGROUND
        circle.clipToOutline = true
        circle.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        if (icon != null) {
            val img = ImageView(this)
            img.setImageBitmap(icon)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            circle.addView(img, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        } else {
            val tv = TextView(this)
            tv.text = letter
            tv.gravity = Gravity.CENTER
            tv.setTextColor(a)
            tv.textSize = 22f
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            circle.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val name = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        tile.addView(circle)
        tile.addView(name)
        return tile
    }

    private fun favMenu(f: Fav) {
        showDlg(
            AlertDialog.Builder(this)
                .setTitle(f.title)
                .setItems(arrayOf("Renombrar", "Eliminar")) { _, which ->
                    if (which == 0) renameFav(f) else confirmDelete(f)
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    private fun renameFav(f: Fav) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = styledEdit("Nombre", f.title)
        name.selectAll()
        box.addView(name)
        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Renombrar")
                .setView(box)
                .setPositiveButton("Guardar") { _, _ ->
                    val n = name.text.toString().trim()
                    if (n.isNotEmpty()) {
                        favs.rename(f.url, n)
                        refreshFavs()
                    }
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    private fun confirmDelete(f: Fav) {
        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Eliminar favorito")
                .setMessage(f.title)
                .setPositiveButton("Eliminar") { _, _ ->
                    favs.remove(f.url)
                    refreshFavs()
                    updateFavIcon()
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    private fun showAddDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val url = styledEdit("https://ejemplo.com", "")
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val name = styledEdit("Nombre (te sugerimos uno, puedes cambiarlo)", "")
        box.addView(url)
        box.addView(name)

        var programmatic = false
        var edited = false
        name.doAfterTextChanged { if (!programmatic) edited = true }
        url.doAfterTextChanged {
            if (!edited) {
                programmatic = true
                name.setText(suggestName(it?.toString().orEmpty()))
                programmatic = false
            }
        }

        showDlg(
            AlertDialog.Builder(this)
                .setTitle("Nueva página")
                .setView(box)
                .setPositiveButton("Guardar") { _, _ ->
                    var u = url.text.toString().trim()
                    if (u.isEmpty()) return@setPositiveButton
                    if (!u.startsWith("http", true)) u = "https://$u"
                    val n = name.text.toString().trim().ifEmpty { suggestName(u) }
                    favs.add(Fav(n, u))
                    icons.ensure(u) { refreshFavs() }
                    refreshFavs()
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    // ------------------------------------------------------------------ WebView

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun injectScripts(view: WebView?) {
        if (prefs.blockAds) view?.evaluateJavascript(AdBlocker.AD_JS, null)
        if (prefs.redirMode > 0) view?.evaluateJavascript(AdBlocker.LINK_GUARD_JS, null)
        if (prefs.autoPlayer) view?.evaluateJavascript(AdBlocker.VIDEO_HOOK_JS, null)
    }

    private fun isNoExo(): Boolean =
        noExoSites.contains(AdBlocker.registrable(AdBlocker.host(currentPageUrl)))

    /**
     * ¿Se permite que la página actual navegue a OTRA web?
     *  - Desde buscadores siempre.
     *  - Si el usuario tocó un enlace visible y real hacia ese destino.
     *  - Modo "2 toques": solo tras 2 toques o más en la página.
     *  - Modo "nunca": no (salvo los casos anteriores).
     */
    private fun crossSiteAllowed(url: String, request: WebResourceRequest): Boolean {
        val mode = prefs.redirMode
        if (mode == 0) return true
        if (AdBlocker.isSearchHub(AdBlocker.host(currentPageUrl))) return true
        val fresh = System.currentTimeMillis() - lastLinkTime < 2500
        val sameTarget = AdBlocker.registrable(AdBlocker.host(lastLinkHref)) ==
            AdBlocker.registrable(AdBlocker.host(url))
        if (fresh && lastLinkTrusted && sameTarget && request.hasGesture()) return true
        if (mode == 1 && request.hasGesture() && tapCount >= 2) return true
        return false
    }

    private fun blockedRedirect(url: String) {
        lastBlocked = url
        val now = System.currentTimeMillis()
        if (now - lastBlockedToast > 1500) {
            lastBlockedToast = now
            toast("Redirección bloqueada: " + AdBlocker.host(url).removePrefix("www."))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Popups: no se permiten ventanas nuevas
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        defaultUa = webView.settings.userAgentString
        applyWebSettings()

        webView.addJavascriptInterface(Bridge(), "EstebanBridge")

        // Cuenta los toques reales del usuario en la página (para la protección anti-redirección)
        webView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downT = ev.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(ev.x - downX) < dp(12) && abs(ev.y - downY) < dp(12) && ev.eventTime - downT < 500) {
                        tapCount++
                    }
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {

            // Detección de video: mira cada petición de la página
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (prefs.blockAds && AdBlocker.isAdUrl(url)) return emptyResponse()

                if (prefs.autoPlayer && request.method == "GET" && AdBlocker.isStreamUrl(url) && !isNoExo()) {
                    if (!pc.isOpen && askDialog == null && System.currentTimeMillis() >= sniffCooldownUntil) {
                        val ref = currentPageUrl
                        runOnUiThread { offerPlayer(url, ref) }
                    }
                    // Evita que la página también descargue el stream
                    return emptyResponse()
                }
                return null
            }

            // Bloqueo de redirecciones, popunders y esquemas raros
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()
                val scheme = uri.scheme?.lowercase()

                if (scheme != "http" && scheme != "https") {
                    if (request.hasGesture() && (scheme == "tel" || scheme == "mailto")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { }
                    }
                    return true
                }
                if (prefs.blockAds && AdBlocker.isAdUrl(url)) return true

                if (request.isForMainFrame && !request.isRedirect && currentPageUrl.isNotEmpty()) {
                    val cross = AdBlocker.registrable(AdBlocker.host(url)) !=
                        AdBlocker.registrable(AdBlocker.host(currentPageUrl))
                    if (cross) {
                        if (!crossSiteAllowed(url, request)) {
                            blockedRedirect(url)
                            return true
                        }
                        tapCount = 0
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == null || url == "about:blank") return
                currentPageUrl = url
                pageTitle = ""
                if (!etUrl.hasFocus()) showUrlSlogan()
                updateFavIcon()
                if (prefs.blockAds) view?.evaluateJavascript(AdBlocker.AD_JS, null)
                if (prefs.redirMode > 0) view?.evaluateJavascript(AdBlocker.LINK_GUARD_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "about:blank") {
                    if (clearHistoryOnBlank) {
                        view?.clearHistory()
                        clearHistoryOnBlank = false
                    }
                    return
                }
                progress.visibility = View.GONE
                if (url != null) {
                    currentPageUrl = url
                    if (!etUrl.hasFocus()) showUrlSlogan()
                    if (prefs.restoreLast && url.startsWith("http")) prefs.lastPage = url
                }
                updateFavIcon()
                injectScripts(view)
            }

            // Certificados inválidos: se permite, pero preguntando primero
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                showDlg(
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Certificado no válido")
                        .setMessage("Este sitio tiene un problema de seguridad. ¿Continuar de todas formas?")
                        .setPositiveButton("Continuar") { _, _ -> handler.proceed() }
                        .setNegativeButton("Cancelar") { _, _ -> handler.cancel() }
                        .setCancelable(false)
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title.orEmpty()
            }

            // Logotipo de la web: se guarda para mostrarlo en favoritos
            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null && currentPageUrl.isNotEmpty()) icons.save(currentPageUrl, icon)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                customContainer.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                customContainer.visibility = View.VISIBLE
                hideSystemBars(true)
                if (prefs.rotation != 2) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() = hideCustomView()
        }
    }

    private fun hideCustomView() {
        if (customView == null) return
        customContainer.removeAllViews()
        customContainer.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
        hideSystemBars(prefs.fullscreen)
        applyRotation()
    }

    /** Puente JS -> Kotlin: fuentes de video y clics en enlaces detectados dentro de la página. */
    inner class Bridge {
        @JavascriptInterface
        fun onSource(url: String) {
            if (!prefs.autoPlayer || !url.startsWith("http", true)) return
            if (!AdBlocker.isStreamUrl(url) || AdBlocker.isAdUrl(url) || isNoExo()) return
            val ref = currentPageUrl
            runOnUiThread {
                if (!pc.isOpen && askDialog == null && System.currentTimeMillis() >= sniffCooldownUntil) {
                    offerPlayer(url, ref)
                }
            }
        }

        @JavascriptInterface
        fun onLinkClick(href: String, trusted: Boolean) {
            lastLinkHref = href
            lastLinkTrusted = trusted
            lastLinkTime = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------ reproductor (ExoPlayer)

    /** Nombre corto del video: título de la página, o el nombre del archivo, o el sitio. */
    private fun videoName(url: String): String {
        val t = cleanTitle(pageTitle)
        if (t.isNotEmpty()) return t
        val seg = try { Uri.parse(url).lastPathSegment.orEmpty() } catch (e: Exception) { "" }
        val base = Uri.decode(seg).substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        val generic = setOf("index", "master", "playlist", "manifest", "stream", "video", "chunklist", "main", "live", "hls")
        if (base.length > 3 && base.lowercase() !in generic) return base.take(40)
        return AdBlocker.host(url).removePrefix("www.")
    }

    /** Descarga el video con el gestor de descargas del sistema (progresivo, no HLS/DASH por partes). */
    /** Descarga rápida desde la ventana de ExoPlayer: usa MediaProbe para saber si hay varias calidades. */
    private fun downloadVideo(url: String) {
        val askedRef = currentPageUrl
        toast("Analizando video…")
        Thread {
            val qualities = try { MediaProbe.probe(url, askedRef) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (qualities.size <= 1) {
                    val q = qualities.firstOrNull() ?: Quality("Original", 0, 0, 0, Quality.Kind.PROGRESSIVE, url, 0.0, false)
                    startDownload(q, askedRef, videoName(url))
                } else {
                    showQualityPicker(qualities, askedRef, videoName(url))
                }
            }
        }.start()
    }

    /** Pantalla "Descargar video" del menú principal: pide una URL y analiza sus calidades. */
    private fun showDownloader() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), dp(4))

        val info = TextView(this)
        info.text = "Pega el enlace del video, de una página, o de un .m3u8/.mpd. Compatible con HLS y DASH."
        info.setTextColor(Color.parseColor("#8FBF86"))
        info.textSize = 12f
        box.addView(info)

        val input = EditText(this)
        input.hint = "https://ejemplo.com/video.m3u8"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.parseColor("#8FBF86"))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.isSingleLine = true
        input.setPadding(0, dp(10), 0, 0)
        if (currentPageUrl.startsWith("http")) input.setText(currentPageUrl)
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Descargar video")
            .setView(box)
            .setPositiveButton("Analizar") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                toast("Analizando calidades…")
                val ref = if (AdBlocker.host(u) == AdBlocker.host(currentPageUrl)) currentPageUrl else ""
                Thread {
                    val qualities = try { MediaProbe.probe(u, ref) } catch (e: Exception) { emptyList() }
                    runOnUiThread {
                        if (qualities.isEmpty()) toast("No se encontró ningún video en ese enlace")
                        else showQualityPicker(qualities, ref, videoName(u))
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fmtDuration(sec: Double): String {
        if (sec <= 0) return ""
        val s = sec.toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%d:%02d", m, ss)
    }

    /** Lista de calidades detectadas: el usuario elige una y arranca la descarga. */
    private fun showQualityPicker(qualities: List<Quality>, referer: String, name: String) {
        val labels = qualities.map { q ->
            val bits = mutableListOf(q.label)
            if (q.isLive) bits.add("EN VIVO") else if (q.durationSec > 0) bits.add(fmtDuration(q.durationSec))
            if (q.kind == Quality.Kind.UNSUPPORTED) bits.add("(solo info, aún no descargable)")
            bits.joinToString("  •  ")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Elige la calidad")
            .setItems(labels) { _, which -> startDownload(qualities[which], referer, name) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Descarga con barra de progreso; concatena segmentos si es HLS. */
    private fun startDownload(q: Quality, referer: String, name: String) {
        if (q.isLive) { toast("Es una transmisión en vivo: no se puede descargar"); return }
        if (q.kind == Quality.Kind.UNSUPPORTED) { toast("Esta calidad todavía no se puede descargar completa"); return }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#8FBF86")); textSize = 12f; setPadding(0, dp(8), 0, 0)
            text = "Preparando…"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
            addView(bar); addView(status)
        }
        val cancel = VideoDownloader.cancelFlag()
        val progress = VideoDownloader.Progress()

        val d = AlertDialog.Builder(this)
            .setTitle("Descargando…")
            .setView(box)
            .setNegativeButton("Cancelar") { _, _ -> cancel.set(true) }
            .setCancelable(false)
            .create()
        d.show()

        Thread { VideoDownloader.download(this, q, referer, name, cancel, progress) }.start()

        val poll = object : Runnable {
            override fun run() {
                if (progress.percent >= 0) { bar.isIndeterminate = false; bar.progress = progress.percent }
                else bar.isIndeterminate = true
                status.text = if (progress.percent >= 0) "${progress.percent}%" else "Descargando…"
                if (progress.done) {
                    d.dismiss()
                    if (progress.error != null) toast(progress.error!!) else toast("Video guardado en Downloads/EstebanTV")
                    return
                }
                if (!isFinishing) box.postDelayed(this, 300)
            }
        }
        box.postDelayed(poll, 200)
    }

    /** Ventana Sí / No antes de abrir ExoPlayer: mini vista previa, duración, nombre y botón de descarga. */
    private fun offerPlayer(url: String, ref: String) {
        if (pc.isOpen || askDialog != null || isFinishing) return
        if (System.currentTimeMillis() < sniffCooldownUntil) return
        if (!prefs.confirmExo) {
            openPlayer(url, ref)
            return
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), dp(4))

        val q = TextView(this)
        q.text = "Se va a ejecutar ExoPlayer. ¿Continuar?"
        q.setTextColor(Color.WHITE)
        q.textSize = 15f
        box.addView(q)

        // Mini vista previa muda y en bucle, estilo GIF, del video que se va a abrir.
        val previewFrame = FrameLayout(this)
        previewFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
            .apply { topMargin = dp(12) }
        previewFrame.setBackgroundColor(Color.parseColor("#0A0A0A"))
        val previewPlayer = ExoPlayer.Builder(this).build()
        val previewView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = false
            player = previewPlayer
        }
        previewFrame.addView(previewView)

        // Etiqueta de duración o EN VIVO, esquina inferior derecha de la vista previa.
        val badge = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundResource(R.drawable.bg_pill)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(8), dp(8))
            }
        }
        previewFrame.addView(badge)
        box.addView(previewFrame)

        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(webView.settings.userAgentString)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(if (ref.isNotEmpty()) mapOf("Referer" to ref) else emptyMap())
            previewPlayer.setMediaSource(DefaultMediaSourceFactory(httpFactory).createMediaSource(MediaItem.fromUri(url)))
            previewPlayer.volume = 0f
            previewPlayer.repeatMode = Player.REPEAT_MODE_ALL
            previewPlayer.prepare()
            previewPlayer.playWhenReady = true
        } catch (e: Exception) { /* si falla la vista previa, se deja el recuadro vacío */ }

        // Duración/EN VIVO: se averigua aparte (playlist HLS/DASH), sin bloquear la vista previa.
        Thread {
            val qs = try { MediaProbe.probe(url, ref) } catch (e: Exception) { emptyList() }
            val info = qs.firstOrNull()
            runOnUiThread {
                badge.text = when {
                    info == null -> ""
                    info.isLive -> "🔴 EN VIVO"
                    info.durationSec > 0 -> fmtDuration(info.durationSec)
                    else -> ""
                }
                if (badge.text.isEmpty()) previewFrame.removeView(badge)
            }
        }.start()

        val small = TextView(this)
        small.text = "▶ " + videoName(url)
        small.setTextColor(accent)
        small.textSize = 12f
        small.maxLines = 1
        small.ellipsize = TextUtils.TruncateAt.END
        small.setPadding(0, dp(10), 0, 0)
        box.addView(small)

        val dl = TextView(this)
        dl.text = "⭳   Descargar video"
        dl.setTextColor(Color.BLACK)
        dl.setTypeface(dl.typeface, android.graphics.Typeface.BOLD)
        dl.textSize = 13f
        dl.gravity = Gravity.CENTER
        dl.background = Ui.filled(accent, dp(20).toFloat())
        dl.setPadding(dp(18), dp(10), dp(18), dp(10))
        val dlWrap = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            addView(dl)
        }
        dl.isClickable = true
        dl.isFocusable = true
        dl.setOnClickListener { downloadVideo(url) }
        box.addView(dlWrap)

        fun cleanupPreview() {
            previewPlayer.release()
        }

        val d = AlertDialog.Builder(this)
            .setTitle("ExoPlayer")
            .setView(box)
            .setPositiveButton("Sí") { _, _ -> cleanupPreview(); openPlayer(url, ref) }
            .setNegativeButton("No") { _, _ -> cleanupPreview(); declineExo(ref) }
            .setOnCancelListener { cleanupPreview(); declineExo(ref) }
            .setOnDismissListener { cleanupPreview(); askDialog = null }
            .create()
        askDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
    }

    /** "No": ese sitio se reproduce en la propia página (se recarga) hasta que se reactive en el menú. */
    private fun declineExo(ref: String) {
        noExoSites.add(AdBlocker.registrable(AdBlocker.host(ref)))
        sniffCooldownUntil = System.currentTimeMillis() + 3000
        if (webView.visibility == View.VISIBLE) webView.reload()
    }

    private fun openPlayer(url: String, referer: String) {
        if (pc.isOpen || isFinishing) return
        webView.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})", null
        )
        webView.onPause()
        hideSystemBars(true)
        pc.open(
            url, referer, webView.settings.userAgentString,
            CookieManager.getInstance().getCookie(url), videoName(url)
        )
    }

    private fun hideSystemBars(hide: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
