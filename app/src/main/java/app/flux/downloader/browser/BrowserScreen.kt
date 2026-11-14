package app.flux.downloader.browser

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.flux.downloader.engine.Quality
import app.flux.downloader.engine.MediaKind
import app.flux.downloader.ui.formatDuration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.friendlyError
import app.flux.downloader.ui.formatSize
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Surface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.engine.FileDownloads
import app.flux.downloader.engine.SiteGuard
import app.flux.downloader.ui.FluxOrange
import coil.compose.SubcomposeAsyncImage

private val SecureGreen = Color(0xFF4CAF50)

private data class Shortcut(val name: String, val url: String)

private val shortcuts = listOf(
    Shortcut("TikTok", "https://www.tiktok.com"),
    Shortcut("X", "https://x.com"),
    Shortcut("Reddit", "https://www.reddit.com"),
    Shortcut("Instagram", "https://www.instagram.com"),
    Shortcut("Facebook", "https://m.facebook.com"),
    Shortcut("Vimeo", "https://vimeo.com"),
    Shortcut("Dailymotion", "https://www.dailymotion.com"),
    Shortcut("Twitch", "https://www.twitch.tv"),
)

@Composable
fun BrowserScreen(
    session: BrowserSession,
    pendingUrl: String?,
    onPendingConsumed: () -> Unit,
    onDownloadRequest: (String) -> Unit,
    onDownloadQuality: (MediaInfo, Int?) -> Unit,
) {
    val ctx = LocalContext.current
    val controller = remember {
        val app = ctx.applicationContext
        BrowserController(app, BrowserPrefs(app), BrowserStore(app), session)
    }
    SideEffect { controller.onDownloadRequest = onDownloadRequest }
    // Otra pestaña pidió abrir un enlace aquí (p. ej. "Abrir en el navegador de Flux")
    LaunchedEffect(pendingUrl) {
        pendingUrl?.let { controller.go(it); onPendingConsumed() }
    }

    val filterVersion by FilterManager.version.collectAsStateWithLifecycle()
    LaunchedEffect(filterVersion, controller.blockAds) { controller.applyGenericCosmetics() }

    val snackbar = remember { SnackbarHostState() }
    var showProtection by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var dismissedFor by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showTabs || !controller.showStart) {
        if (showTabs) showTabs = false else controller.back()
    }

    LaunchedEffect(controller.message) {
        controller.message?.let {
            snackbar.showSnackbar(it)
            controller.message = null
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AddressBar(
            c = controller,
            onShield = { showProtection = true },
            onLibrary = { showLibrary = true },
        )

        if (controller.loading) {
            LinearProgressIndicator(
                progress = { controller.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { c -> controller.createWebView(c) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { wv ->
                    controller.saveActive()
                    controller.webView = null
                    wv.destroy()
                },
            )
            if (controller.showStart) StartPage(controller, onOpen = controller::go)
            if (showTabs) TabSwitcher(controller, onClose = { showTabs = false })
            VideoDetectedBanner(
                visible = controller.detected.isNotEmpty() && !controller.showStart && !showTabs &&
                    dismissedFor != controller.currentUrl,
                subtitle = bannerSubtitle(controller.detected),
                onDownload = { showDownload = true },
                onDismiss = { dismissedFor = controller.currentUrl },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        BottomBar(controller, onTabs = { showTabs = !showTabs }, onDownload = { showDownload = true })
    }

    if (showProtection) {
        ProtectionDialog(
            c = controller,
            onFilters = { showProtection = false; showFilters = true },
            onDismiss = { showProtection = false },
        )
    }
    if (showFilters) FiltersDialog { showFilters = false }
    if (showDownload) {
        DownloadDialog(
            c = controller,
            onQuality = { info, h ->
                onDownloadQuality(info, h)
                controller.message = if (h != null) "Descarga iniciada · ${h}p" else "Descarga iniciada"
            },
            onDismiss = { showDownload = false },
        )
    }
    if (showLibrary) {
        LibraryDialog(controller, onOpen = { controller.go(it); showLibrary = false }) { showLibrary = false }
    }

    controller.pendingFile?.let { f ->
        AlertDialog(
            onDismissRequest = { controller.pendingFile = null },
            title = { Text("Archivo potencialmente peligroso") },
            text = {
                Text(
                    "“${FileDownloads.fileName(f)}” es un archivo ejecutable y podría contener software malicioso. " +
                        "Descárgalo solo si confías en el sitio.",
                )
            },
            confirmButton = { TextButton(onClick = controller::confirmPendingFile) { Text("Descargar") } },
            dismissButton = { TextButton(onClick = { controller.pendingFile = null }) { Text("Cancelar") } },
        )
    }
}

// ----------------------------------------------------------------------
// Aviso flotante "video detectado"
// ----------------------------------------------------------------------
@Composable
private fun VideoDetectedBanner(
    visible: Boolean,
    subtitle: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Video detectado", style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Descargar", maxLines = 1, softWrap = false)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Ocultar aviso", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Barra de direcciones + menú
// ----------------------------------------------------------------------
@Composable
private fun AddressBar(c: BrowserController, onShield: () -> Unit, onLibrary: () -> Unit) {
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val secure = c.currentUrl?.startsWith("https://") == true

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = when {
                editing -> text
                c.showStart -> ""
                else -> c.currentUrl.orEmpty().removePrefix("https://")
            },
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { st ->
                    if (st.isFocused && !editing) {
                        editing = true
                        text = if (c.showStart) "" else c.currentUrl.orEmpty()
                    } else if (!st.isFocused) {
                        editing = false
                    }
                },
            singleLine = true,
            placeholder = { Text("Busca o escribe una dirección") },
            leadingIcon = {
                when {
                    c.showStart -> Icon(Icons.Default.Search, contentDescription = null)
                    secure -> Icon(Icons.Default.Lock, contentDescription = "Conexión segura", tint = SecureGreen)
                    else -> Icon(Icons.Default.Warning, contentDescription = "Conexión no segura", tint = MaterialTheme.colorScheme.error)
                }
            },
            trailingIcon = {
                if (!c.showStart) {
                    IconButton(onClick = { if (c.loading) c.stop() else c.reload() }) {
                        Icon(
                            if (c.loading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (c.loading) "Detener" else "Recargar",
                        )
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = {
                c.go(text)
                focus.clearFocus()
            }),
        )
        IconButton(onClick = onShield) {
            BadgedBox(badge = { if (c.blockedCount > 0) Badge { Text("${c.blockedCount}") } }) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = "Protección",
                    tint = if (c.blockAds && c.siteProtected) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menú")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Nueva pestaña") },
                    onClick = { menu = false; c.newTab() },
                )
                DropdownMenuItem(
                    text = { Text(if (c.isBookmarked()) "Quitar marcador" else "Agregar marcador") },
                    enabled = !c.showStart,
                    onClick = { menu = false; c.toggleBookmark() },
                )
                DropdownMenuItem(
                    text = { Text("Marcadores e historial") },
                    onClick = { menu = false; onLibrary() },
                )
                DropdownMenuItem(
                    text = { Text(if (c.desktopMode) "Ver sitio móvil" else "Ver sitio de escritorio") },
                    onClick = { menu = false; c.toggleDesktop() },
                )
                DropdownMenuItem(
                    text = { Text("Quitar bloqueos de la página") },
                    onClick = { menu = false; c.removeOverlays() },
                )
                DropdownMenuItem(
                    text = { Text("Borrar todo y cerrar pestañas", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; c.panic() },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(c: BrowserController, onTabs: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = c::back, enabled = !c.showStart) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
        }
        IconButton(onClick = c::forward, enabled = c.canGoForward && !c.showStart) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Adelante")
        }
        IconButton(onClick = c::home) {
            Icon(Icons.Default.Home, contentDescription = "Inicio")
        }
        IconButton(onClick = onTabs) {
            Box(
                Modifier
                    .size(24.dp)
                    .border(BorderStroke(2.dp, LocalContentColor.current), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("${c.tabs.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = onDownload,
            enabled = !c.showStart,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Descargar", maxLines = 1, softWrap = false)
            if (c.detected.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                ) {
                    Text("${c.detected.size}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Página de inicio y selector de pestañas
// ----------------------------------------------------------------------
@Composable
private fun StartPage(c: BrowserController, onOpen: (String) -> Unit) {
    val tiles = shortcuts + c.bookmarks.take(8).map { Shortcut(it.title, it.url) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Shield, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(8.dp))
        Text("Navega con protección", style = MaterialTheme.typography.titleLarge)
        Text(
            "Solo HTTPS · bloqueo de anuncios y rastreadores · sin acceso a cámara ni ubicación",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (c.totalBlocked > 0) {
            Text("${c.totalBlocked} anuncios y rastreadores bloqueados", color = FluxOrange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tiles) { s ->
                Column(
                    Modifier.clickable { onOpen(s.url) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val letter: @Composable () -> Unit = {
                            Text(s.name.take(1).uppercase(), color = FluxOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        val host = remember(s.url) { Uri.parse(s.url).host.orEmpty().removePrefix("www.").removePrefix("m.") }
                        // Ícono real del sitio (DuckDuckGo, el mismo buscador del navegador); si falla, la inicial.
                        SubcomposeAsyncImage(
                            model = "https://icons.duckduckgo.com/ip3/$host.ico",
                            contentDescription = s.name,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                            loading = { letter() },
                            error = { letter() },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSwitcher(c: BrowserController, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.tabs.size == 1) "1 pestaña" else "${c.tabs.size} pestañas",
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = { c.newTab(); onClose() }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nueva")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(c.tabs) { i, tab ->
                val isActive = i == c.activeIndex
                Card(
                    modifier = Modifier.clickable { c.switchTo(i); onClose() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = if (isActive) BorderStroke(2.dp, FluxOrange) else null,
                ) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tab.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                tab.url?.let { Uri.parse(it).host } ?: "Página de inicio",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { c.closeTab(i) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar pestaña")
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Diálogos
// ----------------------------------------------------------------------
@Composable
private fun ProtectionDialog(c: BrowserController, onFilters: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Protección") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow("Bloquear anuncios y rastreadores", c.blockAds) { c.updateBlockAds(it) }
                if (c.blockAds && !c.showStart && c.currentUrl != null) {
                    val host = remember(c.currentUrl) { Uri.parse(c.currentUrl).host.orEmpty() }
                    SwitchRow("Protección en $host", c.siteProtected) { c.updateSiteProtected(it) }
                }
                SwitchRow("Bloquear redirecciones automáticas", c.blockRedirects) { c.updateBlockRedirects(it) }
                Text(
                    "${c.blockedCount} en esta página · ${c.totalBlocked} en total",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onFilters) { Text("Listas de filtros") }
                Text("Siempre activo", fontWeight = FontWeight.SemiBold)
                listOf(
                    "Solo HTTPS y certificados válidos",
                    "Navegación segura contra phishing y malware",
                    "Popups y apps externas bloqueados",
                    "Cámara, micrófono y ubicación denegados",
                    "Cookies de terceros bloqueadas",
                ).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { c.clearData() }) { Text("Borrar datos") } },
    )
}

@Composable
internal fun FiltersDialog(onDismiss: () -> Unit) {
    val states by FilterManager.states.collectAsStateWithLifecycle()
    val busy by FilterManager.busy.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listas de filtros") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(states) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 14.sp)
                            Text(
                                when {
                                    s.error != null -> "Error: ${s.error}"
                                    s.updatedAt == 0L -> "Sin descargar"
                                    else -> "${s.rules} reglas · ${ago(s.updatedAt)}"
                                },
                                fontSize = 12.sp,
                                color = if (s.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { FilterManager.setEnabled(s.id, it) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { FilterManager.updateNow() }, enabled = !busy) {
                Text(if (busy) "Actualizando…" else "Actualizar ahora")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "actualizada ahora"
        m < 60 -> "hace $m min"
        m < 1440 -> "hace ${m / 60} h"
        else -> "hace ${m / 1440} d"
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private sealed interface PageProbe {
    data object Idle : PageProbe
    data object Loading : PageProbe
    data class Ready(val info: MediaInfo) : PageProbe
    data class Failed(val message: String) : PageProbe
}

private fun qualityLabel(q: Quality) = if (q.height == 0) "Original" else "${q.height}p"

private fun sizeLabel(q: Quality): String? =
    q.bytes?.let { (if (q.estimated) "≈ " else "") + formatSize(it) }

private fun bannerSubtitle(list: List<DetectedMedia>): String {
    if (list.size != 1) return "${list.size} videos en esta página"
    val top = list.first().qualities.firstOrNull() ?: return "Listo para descargar"
    return listOfNotNull(qualityLabel(top), sizeLabel(top)).joinToString(" · ")
}

private fun kindLabel(m: DetectedMedia): String {
    val ext = when (m.kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.DIRECT -> m.url.substringBefore('?').substringAfterLast('.', "").uppercase().take(4).ifBlank { "Archivo" }
    }
    return (if (m.audioOnly) "Audio" else "Video") + " · " + ext
}

/**
 * Selector de descarga. Lo que el navegador ya detectó aparece al instante con sus calidades y peso
 * (lectura nativa de archivos, HLS y DASH); el extractor del sitio queda como complemento.
 */
@Composable
private fun DownloadDialog(
    c: BrowserController,
    onQuality: (MediaInfo, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val page = c.currentUrl
    val host = page?.let { Uri.parse(it).host.orEmpty().removePrefix("www.") }.orEmpty()
    var probe by remember { mutableStateOf<PageProbe>(PageProbe.Idle) }
    val scope = rememberCoroutineScope()

    fun runExtractor() {
        if (page == null || SiteGuard.isBlocked(page)) return
        probe = PageProbe.Loading
        scope.launch {
            probe = try {
                PageProbe.Ready(Engine.analyze(page))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageProbe.Failed(friendlyError(e))
            }
        }
    }

    // Sin nada detectado en la red se prueba de una vez el extractor del sitio (TikTok, Instagram, X…)
    LaunchedEffect(page) {
        if (c.detected.isEmpty()) runExtractor()
    }

    fun infoOf(m: DetectedMedia) = MediaInfo(
        url = m.url,
        title = c.pageTitle.ifBlank { host.ifBlank { "Video" } },
        uploader = host.ifBlank { null },
        thumbnail = null,
        durationSec = m.durationSec,
        qualities = m.qualities,
        headers = m.headers,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargar video") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    page == null -> Text("Abre una página primero.")
                    SiteGuard.isBlocked(page) -> Text("Este sitio no es compatible con Flux.")
                    else -> {
                        if (c.detected.isNotEmpty()) {
                            Text(
                                "Detectado en esta página",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            c.detected.forEach { m ->
                                DetectedCard(m, host) { h -> onQuality(infoOf(m), h); onDismiss() }
                            }
                        }

                        when (val p = probe) {
                            PageProbe.Idle -> if (c.detected.isNotEmpty()) {
                                SourceRow(
                                    title = "Buscar más con el extractor del sitio",
                                    subtitle = "Útil en TikTok, Instagram, X, Reddit…",
                                    highlighted = false,
                                    onClick = { runExtractor() },
                                )
                            }
                            PageProbe.Loading -> Row(
                                Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (c.detected.isEmpty()) "Buscando videos…" else "Analizando con el extractor del sitio…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is PageProbe.Failed -> if (c.detected.isEmpty()) {
                                Text(
                                    "Todavía no se detectó ningún video. Reprodúcelo en la página y aparecerá aquí al instante.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is PageProbe.Ready -> {
                                val info = p.info
                                Text(
                                    "Extractor del sitio",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(info.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (info.qualities.isEmpty()) {
                                    SourceRow(
                                        title = "Mejor calidad disponible",
                                        subtitle = host,
                                        highlighted = true,
                                        onClick = { onQuality(info, null); onDismiss() },
                                    )
                                } else {
                                    info.qualities.forEachIndexed { i, q ->
                                        SourceRow(
                                            title = qualityLabel(q),
                                            subtitle = if (i == 0) "Mejor calidad" else "Video",
                                            highlighted = i == 0,
                                            trailing = sizeLabel(q),
                                            onClick = { onQuality(info, q.height.takeIf { it > 0 }); onDismiss() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun DetectedCard(m: DetectedMedia, host: String, onPick: (Int?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(m), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val meta = listOfNotNull(
                m.durationSec.takeIf { it > 0 }?.let(::formatDuration),
                host.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        m.qualities.forEachIndexed { i, q ->
            SourceRow(
                title = qualityLabel(q),
                subtitle = if (i == 0 && m.qualities.size > 1) "Mejor calidad" else "Descargar",
                highlighted = i == 0,
                trailing = sizeLabel(q),
                onClick = { onPick(q.height.takeIf { it > 0 }) },
            )
        }
    }
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Download,
            contentDescription = null,
            tint = if (highlighted) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LibraryDialog(c: BrowserController, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val list = if (tab == 0) c.bookmarks.toList() else c.history.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Biblioteca") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Marcadores") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Historial") })
                }
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text("Nada por aquí todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(e.url) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        e.url.removePrefix("https://"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (tab == 0) {
                                    IconButton(onClick = { c.removeBookmark(e) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Quitar marcador")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (tab == 1 && c.history.isNotEmpty()) {
                TextButton(onClick = { c.clearHistory() }) { Text("Borrar historial") }
            }
        },
    )
}

private fun shortName(url: String): String {
    val u = Uri.parse(url)
    return "${u.host.orEmpty()}/${u.lastPathSegment.orEmpty()}"
}
