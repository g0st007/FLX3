package app.flux.downloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.webkit.CookieManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import app.flux.downloader.engine.MediaProbe
import app.flux.downloader.engine.MediaKind
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.adblock.ResourceType
import app.flux.downloader.engine.FileDownload
import app.flux.downloader.engine.FileDownloads
import app.flux.downloader.engine.SiteGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Una pestaña: su estado se guarda como Bundle y se restaura al volver a ella. */
class BrowserTab {
    var title by mutableStateOf("Nueva pestaña")
    var url by mutableStateOf<String?>(null)
    var state: Bundle? = null
}

/** Estado del navegador que sobrevive al cambio de pestaña de la app. */
class BrowserSession {
    val tabs = mutableStateListOf(BrowserTab())
    var activeIndex by mutableIntStateOf(0)
}

/**
 * Navegador endurecido sobre WebView.
 * La política de seguridad vive aquí: HTTPS obligatorio, esquemas peligrosos, popups,
 * redirecciones automáticas, permisos y bloqueo de anuncios/rastreadores con listas completas.
 */
class BrowserController(
    private val ctx: Context,
    private val prefs: BrowserPrefs,
    private val store: BrowserStore,
    private val session: BrowserSession,
) {

    var webView: WebView? = null

    // ---- Estado observable por la UI ----
    var currentUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var showStart by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var blockedCount by mutableIntStateOf(0)
    var totalBlocked by mutableLongStateOf(prefs.totalBlocked)
    var pendingFile by mutableStateOf<FileDownload?>(null)

    var blockAds by mutableStateOf(prefs.blockAds)
        private set
    var blockRedirects by mutableStateOf(prefs.blockRedirects)
        private set
    var desktopMode by mutableStateOf(prefs.desktopMode)
        private set
    var siteProtected by mutableStateOf(true)
        private set

    val detected = mutableStateListOf<DetectedMedia>()
    val bookmarks = mutableStateListOf<Entry>().also { it.addAll(store.loadBookmarks()) }
    val history = mutableStateListOf<Entry>().also { it.addAll(store.loadHistory()) }

    val tabs get() = session.tabs
    val activeIndex get() = session.activeIndex

    var onDownloadRequest: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val active: BrowserTab get() = session.tabs[session.activeIndex.coerceIn(0, session.tabs.lastIndex)]

    @Volatile private var pageHost: String? = null
    @Volatile private var siteAllowed = false
    private var pageFinished = false
    private var upgradeCount = 0
    private var mobileUa = ""
    private var docStartHandler: ScriptHandler? = null
    private var docStartSupported = true

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val hiddenVariants: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pageGen = 0
    @Volatile private var detectionUrl: String? = null
    @Volatile private var currentUa = ""
    @Volatile private var pageUrlNow: String? = null
    private var hasManifest = false
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------
    // Creación del WebView endurecido
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setAllowFileAccessFromFileURLs(false)
            setAllowUniversalAccessFromFileURLs(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Como Chrome: bloquea contenido activo inseguro pero deja pasar imágenes/video http
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSafeBrowsingEnabled(true)
            setGeolocationEnabled(false)
            setSaveFormData(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
            mobileUa = userAgentString
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Muchos reproductores y CDNs necesitan cookies de terceros; el bloqueo de rastreadores ya lo hace la red
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(FluxBridge(), "__fluxBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, SNIFF_JS, setOf("*"))
        }
        wv.webViewClient = SecureClient()
        wv.webChromeClient = SecureChrome()
        wv.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime) }

        applyUa()
        applyGenericCosmetics()
        restoreActive()
        return wv
    }

    // ------------------------------------------------------------------
    // Navegación
    // ------------------------------------------------------------------
    fun go(input: String) {
        val target = toUrl(input) ?: return
        showStart = false
        load(target)
    }

    fun back() {
        if (webView?.canGoBack() == true) webView?.goBack() else home()
    }

    fun forward() { webView?.goForward() }
    fun stop() { webView?.stopLoading(); loading = false }
    fun reload() { currentUrl?.let { load(it) } ?: webView?.reload() }

    fun home() {
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        showStart = true
        currentUrl = null
        pageTitle = ""
        active.url = null
        active.title = "Nueva pestaña"
        active.state = null
        setPage(null)
    }

    // ------------------------------------------------------------------
    // Pestañas
    // ------------------------------------------------------------------
    fun saveActive() {
        val t = active
        if (showStart) {
            t.url = null
            t.state = null
            return
        }
        webView?.let { wv ->
            val b = Bundle()
            wv.saveState(b)
            t.state = b
        }
        t.url = currentUrl
        t.title = pageTitle.ifBlank { currentUrl.orEmpty() }
    }

    fun restoreActive() {
        val t = active
        showStart = t.url == null
        currentUrl = t.url
        pageTitle = if (showStart) "" else t.title
        setPage(t.url)
        resetDetection(t.url)
        val wv = webView ?: return
        val st = t.state
        val u = t.url
        when {
            st != null -> if (wv.restoreState(st) == null && u != null) load(u)
            u != null -> load(u)
            else -> wv.loadUrl("about:blank")
        }
    }

    fun switchTo(i: Int) {
        if (i == session.activeIndex || i !in session.tabs.indices) return
        saveActive()
        session.activeIndex = i
        restoreActive()
    }

    fun newTab() {
        saveActive()
        session.tabs.add(BrowserTab())
        session.activeIndex = session.tabs.lastIndex
        restoreActive()
    }

    fun closeTab(i: Int) {
        if (i !in session.tabs.indices) return
        if (session.tabs.size == 1) {
            session.tabs[0].apply { url = null; title = "Nueva pestaña"; state = null }
            session.activeIndex = 0
            restoreActive()
            return
        }
        val wasActive = i == session.activeIndex
        session.tabs.removeAt(i)
        if (i < session.activeIndex) {
            session.activeIndex--
        } else if (wasActive) {
            session.activeIndex = session.activeIndex.coerceAtMost(session.tabs.lastIndex)
            restoreActive()
        }
    }

    // ------------------------------------------------------------------
    // Ajustes y privacidad
    // ------------------------------------------------------------------
    fun updateBlockAds(v: Boolean) {
        blockAds = v
        prefs.blockAds = v
        applyGenericCosmetics()
    }

    fun updateBlockRedirects(v: Boolean) {
        blockRedirects = v
        prefs.blockRedirects = v
    }

    fun updateSiteProtected(on: Boolean) {
        val h = pageHost ?: return
        prefs.setSiteProtected(h, on)
        setPage(currentUrl)
        applyGenericCosmetics()
        reload()
    }

    fun toggleDesktop() {
        desktopMode = !desktopMode
        prefs.desktopMode = desktopMode
        applyUa()
        reload()
    }

    fun clearData() {
        webView?.apply { clearCache(true); clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        say("Datos de navegación borrados")
    }

    /** Botón de pánico: borra datos, historial y cierra todas las pestañas. */
    fun panic() {
        clearData()
        clearHistory()
        session.tabs.clear()
        session.tabs.add(BrowserTab())
        session.activeIndex = 0
        restoreActive()
        say("Todo borrado")
    }

    // ------------------------------------------------------------------
    // Marcadores e historial
    // ------------------------------------------------------------------
    fun isBookmarked(): Boolean = currentUrl?.let { u -> bookmarks.any { it.url == u } } == true

    fun toggleBookmark() {
        val u = currentUrl ?: return
        val existing = bookmarks.firstOrNull { it.url == u }
        if (existing != null) bookmarks.remove(existing)
        else bookmarks.add(0, Entry(pageTitle.ifBlank { u }, u, System.currentTimeMillis()))
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        store.saveBookmarks(bookmarks)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }

    private fun addHistory(url: String, title: String) {
        if (history.firstOrNull()?.url == url) return
        history.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        store.saveHistory(history)
    }

    // ------------------------------------------------------------------
    // Descargas de archivos
    // ------------------------------------------------------------------
    fun confirmPendingFile() {
        pendingFile?.let(::startFile)
        pendingFile = null
    }

    private fun startFile(f: FileDownload) {
        say(if (FileDownloads.enqueue(ctx, f)) "Descargando archivo…" else "No se pudo iniciar la descarga")
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?) {
        // Única restricción: YouTube
        if (SiteGuard.isBlocked(url)) {
            say("Este sitio no es compatible con Flux")
            return
        }
        val isMedia = mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true ||
            mediaRegex.containsMatchIn(url)
        if (isMedia) {
            ui { onDownloadRequest?.invoke(url) }
            return
        }
        val file = FileDownload(url, ua, cd, mime)
        if (FileDownloads.isRisky(file)) ui { pendingFile = file } else startFile(file)
    }

    // ------------------------------------------------------------------
    // Utilidades internas
    // ------------------------------------------------------------------
    private fun load(url: String) {
        pageFinished = false
        webView?.loadUrl(url, mapOf("Sec-GPC" to "1", "DNT" to "1"))
    }

    private fun toUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("https://", true)) return t
        if (t.startsWith("http://", true)) return "https://" + t.substring(7)
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.startsWith(".") && !t.contains(':')
        return if (looksLikeHost) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    private fun applyUa() {
        currentUa = if (desktopMode) desktopUa else mobileUa
        webView?.settings?.apply {
            userAgentString = if (desktopMode) desktopUa else mobileUa
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
    }

    private fun setPage(url: String?) {
        val h = url?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }
        pageHost = h
        siteAllowed = h != null && prefs.allowedSites.any { h == it || h.endsWith(".$it") }
        siteProtected = !siteAllowed
    }

    private fun say(msg: String) { main.post { message = msg } }
    private fun ui(block: () -> Unit) { main.post(block) }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    private fun showError(view: WebView, title: String, detail: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="background:#0A0A0A;color:#fff;font-family:sans-serif;padding:32px;text-align:center">
            <h2 style="color:#FF6A00">${TextUtils.htmlEncode(title)}</h2>
            <p style="color:#B5B5B5">${TextUtils.htmlEncode(detail)}</p></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ------------------------------------------------------------------
    // Detección de medios (instantánea y sin lista de sitios)
    // ------------------------------------------------------------------
    private fun resetDetection(url: String?) {
        pageGen++
        detectionUrl = url
        pageUrlNow = url
        hasManifest = false
        seen.clear()
        hiddenVariants.clear()
        detected.clear()
    }

    private fun headersFor(url: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = currentUa.ifBlank { mobileUa }
        pageUrlNow?.let { h["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    /**
     * Un posible medio (por extensión en la red o reportado por el reproductor de la página).
     * Se lee en segundo plano (HEAD / lista HLS / MPD DASH) y, si es válido, aparece en [detected].
     */
    private fun onCandidate(url: String, hintHeight: Int = 0, hintDuration: Double = 0.0, trusted: Boolean = false, fromNet: Boolean = false) {
        if (!MediaProbe.isCandidate(url, trusted) || SiteGuard.isBlocked(url)) return
        // Piezas sueltas que la página pide por fetch/XHR cuando ya hay una lista de streaming: son fragmentos
        if (fromNet && hasManifest) return
        val key = MediaProbe.normalize(url)
        if (key in hiddenVariants || !seen.add(key)) return
        val gen = pageGen
        val headers = headersFor(url)
        probeScope.launch {
            val r = MediaProbe.probe(url, headers, hintHeight, hintDuration) ?: return@launch
            ui {
                if (gen != pageGen) return@ui
                if (r.kind != MediaKind.DIRECT) hasManifest = true
                r.hidden.forEach { hiddenVariants.add(MediaProbe.normalize(it)) }
                detected.removeAll { MediaProbe.normalize(it.url) in hiddenVariants }
                if (key in hiddenVariants || detected.any { it.id == key } || detected.size >= 12) return@ui
                detected.add(DetectedMedia(key, r.url, r.kind, r.durationSec, r.qualities, r.audioOnly, headers))
            }
        }
    }

    /** Puente JS → app: lo que el reproductor de la página sabe (src real, resolución, duración). */
    private inner class FluxBridge {
        @JavascriptInterface
        fun media(url: String?, w: Int, h: Int, dur: Double, src: String?) {
            if (url == null) return
            val d = if (dur.isNaN() || dur.isInfinite()) 0.0 else dur
            onCandidate(url, hintHeight = h, hintDuration = d, trusted = true, fromNet = src == "net")
        }
    }

    /** Quita los muros de "usa la app", banners y capas que tapan la página, y devuelve el scroll. */
    fun removeOverlays() {
        val js = """(function(){var n=0,vw=innerWidth,vh=innerHeight;
            document.querySelectorAll('body *').forEach(function(e){try{var s=getComputedStyle(e);
            if((s.position==='fixed'||s.position==='sticky')&&parseInt(s.zIndex||'0',10)>=5){var r=e.getBoundingClientRect();
            if(r.width>=vw*0.5&&r.height>=vh*0.2&&!/^(VIDEO|AUDIO)${'$'}/.test(e.tagName)&&!e.querySelector('video')){e.remove();n++;}}}catch(x){}});
            [document.documentElement,document.body].forEach(function(b){if(b){b.style.setProperty('overflow','auto','important');b.style.setProperty('position','static','important');}});
            return n;})()"""
        webView?.evaluateJavascript(js) { r ->
            val n = r?.trim('"')?.toIntOrNull() ?: 0
            say(if (n > 0) "Se quitaron $n elementos que tapaban la página" else "No se encontró nada que quitar")
        }
    }

    private fun registrable(host: String): String {
        val h = host.lowercase()
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val sld = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        val n = if (parts[parts.size - 2] in sld && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    private fun sameSite(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && registrable(a) == registrable(b)

    /** WebView no informa el tipo de recurso: se deduce por extensión y cabecera Accept. */
    private fun inferType(req: WebResourceRequest): Int {
        val path = req.url.path?.lowercase().orEmpty()
        when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> return ResourceType.SCRIPT
            "css" -> return ResourceType.CSS
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp" -> return ResourceType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> return ResourceType.FONT
            "mp4", "webm", "m4s", "mp3", "m4a", "ogg", "m3u8", "mpd", "ts" -> return ResourceType.MEDIA
        }
        val accept = req.requestHeaders["Accept"].orEmpty()
        return when {
            accept.contains("text/css") -> ResourceType.CSS
            accept.contains("text/html") -> ResourceType.SUBDOC
            accept.contains("image/") -> ResourceType.IMAGE
            else -> ResourceType.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // Filtrado cosmético (ocultar huecos y banners que quedan tras bloquear la red)
    // ------------------------------------------------------------------
    private fun styleScript(id: String, css: String): String =
        "(function(){try{var s=document.getElementById('$id');if(!s){s=document.createElement('style');" +
            "s.id='$id';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css)};}catch(e){}})();"

    /** CSS genérico: se inyecta antes de que cargue la página, para que no se vea parpadear. */
    fun applyGenericCosmetics() {
        val wv = webView ?: return
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!docStartSupported) return
        docStartHandler?.remove()
        docStartHandler = null
        if (!blockAds) return
        val css = FilterManager.engine.genericCss
        if (css.isEmpty()) return
        val allowed = JSONArray(prefs.allowedSites.toList()).toString()
        val script = "(function(){try{if($allowed.indexOf(location.hostname)>=0)return;" +
            "function f(){var s=document.createElement('style');s.id='__flux_g';" +
            "s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);}" +
            "if(document.documentElement)f();else document.addEventListener('DOMContentLoaded',f);}catch(e){}})();"
        docStartHandler = WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
    }

    private fun applySiteCosmetics(view: WebView) {
        if (!blockAds || siteAllowed) return
        val host = pageHost ?: return
        val engine = FilterManager.engine
        val css = engine.cssForHost(host) + if (docStartSupported) "" else engine.genericCss
        if (css.isNotEmpty()) view.evaluateJavascript(styleScript("__flux_d", css), null)
    }

    // ------------------------------------------------------------------
    // WebViewClient: política de seguridad
    // ------------------------------------------------------------------
    private inner class SecureClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()

            if (scheme == "about") return false

            // 1) HTTPS obligatorio: se sube de http a https
            if (scheme == "http") {
                if (!request.isForMainFrame) return false
                if (request.isForMainFrame) {
                    if (++upgradeCount > 5) {
                        upgradeCount = 0
                        showError(view, "No se pudo conectar de forma segura", "Este sitio no ofrece HTTPS.")
                    } else {
                        val https = uri.buildUpon().scheme("https").build().toString()
                        ui { load(https) }
                    }
                }
                return true
            }

            // 2) Otros esquemas (intent://, market://, file://, javascript:…) bloqueados
            if (scheme != "https") {
                if (request.isForMainFrame) say("Se bloqueó un intento de abrir otra app o archivo")
                return true
            }

            // 3) Redirecciones automáticas sin gesto del usuario
            if (blockRedirects && request.isForMainFrame && pageFinished &&
                !request.hasGesture() && !request.isRedirect
            ) {
                val from = pageHost
                if (from != null && !sameSite(from, uri.host.orEmpty())) {
                    say("Redirección automática bloqueada")
                    return true
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (blockAds && !siteAllowed && !request.isForMainFrame) {
                val type = inferType(request)
                val ph = pageHost
                val firstParty = ph != null && sameSite(uri.host.orEmpty(), ph)
                // Nunca se bloquea un medio ni los scripts/documentos propios del sitio (rompen reproductores)
                val protectedRequest = type == ResourceType.MEDIA ||
                    (firstParty && (type == ResourceType.SCRIPT || type == ResourceType.SUBDOC || type == ResourceType.UNKNOWN))
                if (!protectedRequest && FilterManager.engine.shouldBlock(uri.toString(), pageHost, type)) {
                    ui {
                        blockedCount++
                        totalBlocked++
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            onCandidate(uri.toString())
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageFinished = false
            loading = true
            progress = 0f
            resetDetection(url)
            if (url != null && url.startsWith("https://")) {
                if (url != currentUrl) blockedCount = 0
                currentUrl = url
                active.url = url
                showStart = false
                setPage(url)
            }
            refreshNav(view)
            applySiteCosmetics(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            applySiteCosmetics(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            pageFinished = true
            upgradeCount = 0
            refreshNav(view)
            prefs.totalBlocked = totalBlocked
            // Respaldo si el script de inicio no está disponible, y nuevo escaneo de los <video> ya cargados
            view.evaluateJavascript("$SNIFF_JS;window.__fluxScan&&window.__fluxScan();", null)
            if (url != null && url.startsWith("https://") && !showStart) addHistory(url, pageTitle)
            applySiteCosmetics(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Navegación interna de apps de una sola página (X, TikTok…): otro contenido, otra detección
            if (url != null && url.startsWith("https://") && url != detectionUrl) resetDetection(url)
            refreshNav(view)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError(view, "Conexión no segura", "El certificado de este sitio no es válido. Se canceló la carga para protegerte.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError(view, "No se pudo cargar la página", error.description?.toString().orEmpty())
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            say("Sitio peligroso bloqueado por Navegación segura")
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient: permisos denegados por defecto
    // ------------------------------------------------------------------
    private inner class SecureChrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
            if (!showStart && !title.isNullOrBlank()) active.title = title
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}


private val SNIFF_JS = """(function(){
if(window.__fluxS)return;window.__fluxS=1;
var B=window.__fluxBridge;if(!B)return;
var sent={};
function abs(u){try{return new URL(u,location.href).href}catch(e){return null}}
function send(u,w,h,d,s){u=abs(u);if(!u||u.indexOf('http')!==0)return;
var k=u+'|'+(h|0);if(sent[k])return;sent[k]=1;try{B.media(u,w|0,h|0,+d||0,s||'el')}catch(e){}}
function okType(ct){ct=(ct||'').toLowerCase();if(/mp2t|iso\.segment|mpeg-tts/.test(ct))return false;
return /^(video|audio)\//.test(ct)||/mpegurl|dash\+xml/.test(ct);}
function scanEl(v){var s=v.currentSrc||v.src;
if(s&&s.indexOf('blob:')!==0&&s.indexOf('data:')!==0)send(s,v.videoWidth,v.videoHeight,v.duration);
var ss=v.querySelectorAll?v.querySelectorAll('source'):[];
for(var i=0;i<ss.length;i++){if(ss[i].src)send(ss[i].src,v.videoWidth,v.videoHeight,v.duration);}}
function walk(o,d){if(!o||typeof o!=='object'||d>6)return;if(typeof o.contentUrl==='string')send(o.contentUrl,0,0,0);
for(var k in o){walk(o[k],d+1);}}
function scan(){try{var vs=document.querySelectorAll('video,audio');for(var i=0;i<vs.length;i++)scanEl(vs[i]);
var ms=document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');
for(var j=0;j<ms.length;j++){if(ms[j].content)send(ms[j].content,0,0,0);}
var ld=document.querySelectorAll('script[type="application/ld+json"]');
for(var n=0;n<ld.length;n++){try{walk(JSON.parse(ld[n].textContent),0)}catch(e){}}}catch(e){}}
window.__fluxScan=scan;
var onMedia=function(e){var t=e.target;if(t&&t.tagName&&/^(VIDEO|AUDIO)${'$'}/.test(t.tagName))scanEl(t);};
document.addEventListener('loadedmetadata',onMedia,true);
document.addEventListener('playing',onMedia,true);
var of=window.fetch;
if(of){window.fetch=function(){var a=arguments;var p=of.apply(this,a);
p.then(function(r){try{if(okType(r.headers.get('content-type')))send(r.url||(a[0]&&a[0].url)||a[0],0,0,0,'net')}catch(e){}}).catch(function(){});
return p;};}
var xo=XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open=function(m,u){var x=this;x.__fu=u;
x.addEventListener('readystatechange',function(){try{if(x.readyState===2&&okType(x.getResponseHeader('content-type')))send(x.responseURL||x.__fu,0,0,0,'net')}catch(e){}});
return xo.apply(this,arguments);};
var t0;function later(){clearTimeout(t0);t0=setTimeout(scan,200);}
function start(){scan();try{new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();"""
