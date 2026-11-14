# Flux (nombre provisional)

Descargador de video y audio todo en uno para Android. Motor: yt-dlp + FFmpeg (youtubedl-android).
YouTube queda excluido por decisión de producto (`engine/SiteGuard.kt`).

## Cómo abrirlo
1. Android Studio (Koala o más nuevo) → *Open* → carpeta `Flux`.
2. Dejar que sincronice Gradle (genera el wrapper automáticamente).
3. Ejecutar en un dispositivo o emulador con Android 10+ (minSdk 29).

## Estructura
- `engine/Engine.kt` — análisis del enlace y descarga con yt-dlp/FFmpeg
- `engine/SiteGuard.kt` — validación de enlaces y sitios bloqueados
- `engine/Downloads.kt` — cola y estado de descargas · `DownloadService.kt` — descargas en segundo plano
- `adblock/` — motor de filtros y gestor de listas
- `browser/` — navegador seguro (controlador con la política de seguridad, pantalla, bloqueador)
- `engine/Storage.kt` — guarda en Descargas/Flux vía MediaStore
- `ui/` — pantalla principal (Compose), ViewModel, tema negro/naranja
- `logo.svg` — vista previa del logo; el ícono real está en `res/drawable/ic_launcher_foreground.xml`

## Fase 1 (esto)
Pegar o compartir enlace → analizar → elegir video (calidad) o solo audio (MP3/M4A/OPUS) → descargar con progreso, cancelar y abrir.

## Fase 2 (hecha)
Servicio en primer plano con notificación de progreso y botón "Cancelar todo", cola (2 descargas en paralelo), aviso al completar. Estado global en `engine/Downloads.kt`.

## Fase 3 (hecha): navegador seguro
`browser/` — WebView endurecido: solo HTTPS, certificados inválidos bloqueados, Navegación segura, popups/esquemas externos/redirecciones automáticas bloqueados, permisos denegados, cookies de terceros off, ejecutables bloqueados, bloqueo de anuncios y rastreadores, detección de video en la página y botón Descargar. Navegación y búsqueda con DuckDuckGo.

## Fase 4 (hecha): bloqueo serio + navegador completo
- `adblock/` — motor de filtros compatible con la sintaxis de EasyList/uBlock (reglas de red con índice por dominio y token, excepciones, opciones third-party / domain / tipo de recurso, `$important`) más filtrado cosmético (CSS que oculta huecos). `FilterManager` descarga y actualiza las listas (EasyList, EasyPrivacy, uBlock, badware, Peter Lowe, Fanboy, EasyList Español) cada 72 h o a demanda; hay una lista semilla para el primer arranque.
- Pausa de protección por sitio, contador de bloqueos, listas activables.
- Descargas: solo YouTube está bloqueado. Los archivos generales usan el gestor del sistema; los ejecutables piden confirmación.
- Pestañas, marcadores, historial, modo escritorio y botón de borrado total.

## Pulido y rendimiento (v0.1.1)
- Tema: `FluxTheme` ahora envuelve todo en un `Surface` con `contentColor` claro (antes los íconos/títulos sin color salían negros sobre negro). Tipografía y formas propias.
- Peso estimado por calidad (≈ MB/GB) en las opciones de video; el análisis lee el JSON completo de yt-dlp (`engine/Engine.kt`).
- Reddit: `engine/SiteResolver.kt` resuelve posts que enlazan a RedGifs para evitar el error de inicio de sesión.
- Rendimiento: build `release` con R8 (CI genera el APK release), progreso de descargas limitado a ~4 actualizaciones/s, listas de filtros y actualización de yt-dlp diferidas tras el arranque.
- Navegador: íconos reales de los sitios, selector de videos legible, botón Descargar en una línea.

## Identidad visual (v0.1.2)
- Tipografía: Inter (subconjunto latino, ~58 KB por peso) + Inter Display ExtraBold para el titular. Licencia en `FONTS-LICENSE.txt`.
- Modo claro y oscuro automáticos (texto blanco en oscuro, negro en claro).
- Pantalla principal con titular de marketing ("+1.000 sitios. Una sola app."), botones Pegar/Analizar y chips de sitios.

## Detección universal (v0.2.0)
- **Detector nativo** en el navegador (`MediaProbe`, `DetectedMedia`): lee archivos directos (HEAD), listas HLS (.m3u8) y DASH (.mpd) por su cuenta y muestra calidades, duración y peso en un instante, sin yt-dlp ni lista de sitios. Un script al inicio de la página (`SNIFF_JS`) añade lo que el reproductor sabe (`<video>`, og:video, JSON-LD, cabeceras `Content-Type` de fetch/XHR).
- Filtros de basura: píxeles/miniaturas (<150 KB), fragmentos de streaming y variantes de una lista maestra no se listan aparte.
- La descarga de un medio detectado usa las **cabeceras del sitio** (Referer, User-Agent, Cookie) con yt-dlp.
- **Análisis en Descargas** (`Engine.analyze`): extractor de yt-dlp y lector nativo de la página (`PageScanner`) en paralelo; si el nativo encuentra medios y yt-dlp tarda más de 2,5 s, se usa el nativo. El estimador de peso ya no deja "—" en HLS.
- Al pulsar **Descargar** se limpia el enlace y desaparece la tarjeta de análisis. Si no hay video: botón "Abrir en el navegador de Flux".
- Nueva pestaña **Archivos**.
- Compatibilidad del navegador: cookies de terceros, contenido mixto pasivo, iframes http, redirecciones dentro del mismo sitio, y los medios y scripts propios del sitio nunca se bloquean; menú "Quitar bloqueos de la página".

## Cambios (v0.1.4)
- El selector de descarga del navegador analiza la página y lista cada calidad con su peso; tocar una calidad inicia la descarga directamente.
- Los videos detectados muestran resolución (si la URL la trae) y peso (Content-Length); las transmisiones HLS/DASH no informan peso.

## Funciones nuevas (v0.1.3)
- **Aviso flotante** "Video detectado" en el navegador (descartable por página) que abre el selector de descarga.
- **Reproductor propio** (Media3/ExoPlayer): velocidad desde el engranaje, pantalla completa horizontal, pausa al salir.
- **Carpeta privada** con código de 6 dígitos (PBKDF2 + sal, bloqueo tras 5 intentos, se bloquea al salir de la app, sin capturas por defecto). Archivos en almacenamiento interno de la app (sin cifrar en disco).
- **Ajustes**: tema, solo Wi-Fi, límite de velocidad, calidad y formato de audio por defecto, mostrar en galería, ajustes del navegador (anuncios, redirecciones, listas, borrar datos), privacidad.
- Referencias visuales de cada sección en `referencias/`.

## Siguiente
- Progreso unificado video+audio (hoy el % se reinicia al pasar de una pista a otra)
- Fase 3: ajustes (solo Wi-Fi, límite de velocidad, carpeta), playlists, subtítulos, recorte antes de descargar
- Fase 4: bóveda privada, reproductor integrado, navegador con detección de videos
