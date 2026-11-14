package app.flux.downloader.browser

import app.flux.downloader.engine.MediaKind
import app.flux.downloader.engine.Quality

/** Un video/audio que el navegador detectó y ya leyó (calidades y peso incluidos). */
data class DetectedMedia(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val durationSec: Long,
    val qualities: List<Quality>,
    val audioOnly: Boolean,
    /** Cabeceras con las que se leyó y con las que hay que descargar (Referer, User-Agent, Cookie). */
    val headers: Map<String, String>,
)
