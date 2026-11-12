# Historial de versiones

Cambios de Sakura OI Web (antes SakuraWebApp) por versión. La versión de cada APK está en `app/build.gradle.kts` (`versionName` y `versionCode`).

## 0.7.6 (versionCode 48) — 12 de noviembre de 2026

Incluye la compilación local 0.7.5.

### Cambiado
- Widget: los botones Chat nuevo y Último chat, los chips de modelos, la cápsula y los botones redondos tienen relleno sólido y un borde fino, y el fondo del widget es más opaco (92 %). Sobre fondos de pantalla con mucho dibujo ya se distinguen en tema claro y oscuro; Android no permite el desenfoque del diseño en los widgets.
- La animación de activación de «Oye, Sakura» va 1.8 veces más lenta (unos 2.4 s) para cubrir la carga de la app, que arranca debajo desde el primer cuadro.
- Se quitó por ahora el tono de activación (`WakeActivation.TONE`); el sonido sigue en `res/raw` para usar otro más adelante.

## 0.7.4 (versionCode 46) — 12 de noviembre de 2026

Reúne las compilaciones locales 0.6.2 y 0.7.0 a 0.7.3. Sigue el diseño "App Android · widgets y «Oye, Sakura»".

### Añadido
- `QuickVoice.kt` y `ChatModels.kt`: widget de pantalla de inicio en tema claro y oscuro. En una fila muestra el avatar con insignia de micrófono, “Habla con” y la cápsula del modelo, y los botones redondos Nuevo chat y Último chat. En dos filas o más muestra “Habla con <modelo>”, el estado de «Oye, Sakura», el micrófono azul, los chips Sakura, Lain y Yoko para cambiar de un toque y los botones Nuevo chat y Último chat. Cada widget recuerda su modelo; el modo de voz y los chats nuevos se abren con él (`?models=`).
- `WidgetModelPickerActivity.kt`: «¿Con quién quieres hablar?», que se abre desde la cápsula del widget. «Ver todos los modelos» trae los modelos visibles del servidor con la sesión que la app ya guarda.
- `VoiceTileService` (en `QuickVoice.kt`): ajuste rápido “Hablar con Sakura” en el panel de notificaciones.
- Botón **Hablar** en la notificación de «Oye, Sakura» y apertura del modo de voz con el botón de los audífonos (`VOICE_COMMAND`, `VOICE_SEARCH_HANDS_FREE`).
- `WakeWordSettingsActivity.kt` y `SetupParts.kt`: pantalla propia de «Oye, Sakura» con el interruptor y su estado, la descarga del modelo en MB con barra, los avisos “Sin internet · Usa algo de batería · Se pausa al usar la app”, el permiso para abrir al instante, la frase de activación (hoja con frases sugeridas o propia), la sensibilidad (Estricta, Normal, Relajada), “Tu voz” y los accesos rápidos. En **Ajustes del teléfono** queda una tarjeta que la abre.
- `WakePhrase.kt`: frase configurable, sensibilidad y comparación fonética que tolera las transcripciones del modelo (“sacura”, “sa cura”, “zakura”…). Pruebas en `WakePhraseTest.kt`.
- `VoiceTrainingActivity.kt`: **Entrenar tu voz** (5 grabaciones con barra de progreso, micrófono y barras de nivel) y **Prueba en vivo**, que muestra lo que escucha y si Sakura se activaría, sin abrir nada.
- `WakeActivation.kt`: al oír la frase suena el tono de activación, brota la flor arriba al centro y una línea rosa recorre los bordes de la pantalla soltando pétalos hasta cerrar el aro. Se dibuja sobre cualquier app, en un hilo propio, y deja pasar los toques.
- Compilación de depuración: `WakeActivationTestReceiver` reproduce la activación desde adb (`am broadcast -a com.sakura.webapp.debug.WAKE`); solo responde a quien tiene el permiso `DUMP`.

### Cambiado
- La voz se abre más rápido: si Open WebUI ya está cargado, el modo de voz, Nuevo chat y Último chat se abren dentro de esa página, sin recargarla. Sakura arranca en cuanto empieza la animación de activación, debajo de ella.
- Atrás en la primera pantalla manda la app al fondo en lugar de cerrarla, para que la página siga cargada.
- El servicio de «Oye, Sakura» carga el motor web al arrancar, para que la app abra antes desde cero.

### Corregido
- El aviso de Open WebUI “Abrir enlace · ¿Iniciar una llamada de voz con tu micrófono?” se acepta solo en cualquier página y en cuanto aparece, sin límite de tiempo, y se oculta para que no llegue a verse. Antes solo se esperaba 30 s y en páginas abiertas con `?call=true`.
- Los parámetros `?call` y `?models` se quitan de la dirección al usarse, para que recargar no vuelva a pedir la llamada.

## 0.6.1 (versionCode 40) — 12 de noviembre de 2026

### Corregido
- Al abrir el modo de voz (“Oye, Sakura”, acceso directo o asistente) podía quedar el aviso de Open WebUI “Start a voice call using your microphone?”. La app solo lo aceptaba en la primera carga y durante 10 s; si la página se recargaba en la dirección de casa o al copiar la sesión, el aviso quedaba pendiente. Ahora lo acepta en cada carga de una página con `?call=true` y lo espera hasta 30 s.
- Una petición (modo de voz, compartir, acceso directo) que llegaba mientras la app elegía la dirección al arrancar se perdía y se abría el último chat. Ahora se abre la petición.

## 0.6.0 (versionCode 39) — 12 de noviembre de 2026

### Añadido
- `ConnectionActivity.kt` y `SetupUi.kt`: asistente de configuración inicial con el diseño "Conexión inicial" (tarjetas En casa / Fuera de casa, campos monoespaciados, prueba contra `/health` con estados Probando, Conectado o el motivo del fallo). Se abre si la app no tiene dirección; las direcciones se guardan en el teléfono (`ServerAddress.kt`) y las de compilación pasan a ser opcionales.
- `PermissionsActivity.kt`: paso de permisos (notificaciones, micrófono y asistente del teléfono opcional) con estado Concedido.
- `SettingsActivity.kt` (**Ajustes del teléfono**): conexión, permisos, asistente y “Oye, Sakura”. Se abre desde una opción añadida al menú de usuario de Open WebUI, desde el acceso directo, desde la información de la app y desde la pantalla de error.
- `IncomingIntents.kt` y `ShareBridge.kt`: compartir texto y archivos con Sakura (se pegan en el cuadro de mensaje de un chat nuevo), “Preguntar a Sakura” (`PROCESS_TEXT`) y accesos directos (Chat nuevo, Modo de voz, Último chat, Ajustes). Al abrir el modo de voz, la app acepta sola la confirmación de Open WebUI.
- `VoiceAssistant.kt`: Sakura como asistente digital de Android; el gesto de asistente abre el modo de voz.
- `ServerAddress.kt` y `SessionSync.kt`: dirección de casa y exterior; en Wi-Fi se usa la local si responde y se cambia sola entre redes, copiando la sesión para no volver a iniciar sesión.
- Barra “Sin conexión. Reconectando…” y reintento automático al volver la red.
- Deslizar hacia abajo para recargar (`SwipeRefreshLayout`), que solo actúa con el chat arriba del todo.
- `WakeWord.kt`: “Oye, Sakura” opcional con reconocimiento local Vosk y un modelo en español que se descarga al activarlo.
- Textos en inglés (`values-en`); la app sigue el idioma del teléfono.
- Propiedades de compilación opcionales `SAKURA_WEBUI_LAN_URL`, `SAKURA_VERSION_SUFFIX` y `SAKURA_APPLICATION_ID_SUFFIX`.

### Cambiado
- `SAKURA_WEBUI_URL` ya no es obligatoria.
- El APK incluye la librería de reconocimiento de voz para ARM de 64 y 32 bits y x86_64, y pasa a unos 38 MB.

## 0.3.2 (versionCode 38) — 10 de noviembre de 2026

### Cambiado
- Se quitó la pantalla de carga propia de la app (`createLoadingView`, `LogoSpinnerView.kt` y la espera mínima de 3 s). El WebView se muestra desde el inicio con el fondo del tema del teléfono.
- La pantalla "No hay conexión con Sakura" ya no sale por tiempo de espera (antes, a los 5 s). Solo aparece con `onReceivedError` del documento principal, errores TLS o respuestas 5xx. Durante un reintento la pantalla se queda con la flecha girando hasta que la página carga o vuelve a fallar.

### Añadido
- `ImmutableAssetCache.kt`: los archivos de `/_app/immutable/` (con hash en el nombre) se guardan en la caché de la app la primera vez y se sirven desde el teléfono en `shouldInterceptRequest`. Los que no se usan en 30 días se borran.

### Documentación
- Consejo para activar **Cachear lista de modelos** en Open WebUI, que redujo la carga de la lista de modelos de 2.6 s a 0.24 s en las pruebas.
- Se quitó la captura de la pantalla de carga.

## 0.3.1 (versionCode 37) — compilación local

- Apertura directa sin pantalla de carga y aviso de sin conexión solo ante errores reales. Incluido en 0.3.2.

## 0.3.0 (versionCode 36) — 8 de noviembre de 2026

### Corregido
- En el teléfono, el botón de descarga del visor de imágenes no hacía nada: la librería de zoom del visor (panzoom) llama a `preventDefault()` en `touchstart`, y Android no genera el `click`. El script de `DownloadBridge.kt` detecta un toque corto sobre un botón de un diálogo cuyo `touchstart` se canceló así y hace el clic.
- Las descargas sobrescribían o descartaban archivos con el mismo nombre, porque Open WebUI llama a todas las imágenes `generated_image.png`. Ahora cada descarga se guarda como `yyyyMMdd` más cuatro caracteres alfanuméricos al azar (por ejemplo `20261108Bn21.png`), también las que pasan por el gestor de descargas de Android.

## 0.2.11 (versionCode 35) — compilación local

- Nombres únicos de descarga. Incluido en 0.3.0.

## 0.2.10 (versionCode 34) — compilación local

- Repite el toque en los botones del visor de imágenes. Incluido en 0.3.0.

## 0.2.9 (versionCode 33) — 8 de noviembre de 2026

### Corregido
- El botón de descarga de Open WebUI (por ejemplo, en el visor de imágenes) no guardaba nada. Open WebUI usa file-saver, que crea un enlace `<a download>` con una URL `blob:` y le hace clic; el WebView de Android ignora esas descargas. `DownloadBridge.kt` intercepta esos enlaces (y los `data:` y los del propio servidor), lee el archivo en la página y lo guarda en Descargas/Sakura OI Web con MediaStore.

### Añadido
- `setDownloadListener`: las descargas http(s) se envían al `DownloadManager` de Android con las cookies del WebView; las `data:` se decodifican y las `blob:` se leen desde la página.
- Avisos "Guardado en Descargas/Sakura OI Web", "Descargando…" y "No se pudo guardar el archivo."
- Permiso `WRITE_EXTERNAL_STORAGE` solo para Android 9 o anterior (`maxSdkVersion="28"`).

## 0.2.8 (versionCode 32) — 8 de noviembre de 2026

### Añadido
- Los enlaces que salen de Open WebUI se abren en el navegador predeterminado del teléfono (`Intent.ACTION_VIEW`) en lugar de cargarse dentro del WebView. También los que usan `target="_blank"`.
- Los enlaces `mailto:`, `tel:` y otros esquemas se envían a la app que los maneja. Si no hay ninguna, se muestra el aviso "No hay ninguna app para abrir este enlace."

### Sin cambios
- Las páginas del servidor configurado en `SAKURA_WEBUI_URL` siguen dentro de la app. Las redirecciones del servidor y las descargas (`blob:`, `data:`) no se desvían al navegador.

## 0.2.7 (versionCode 31) — 2 de noviembre de 2026

### Corregido
- Las notificaciones de respuesta de Android ahora usan un canal nativo silencioso independiente de **Notificación Sonora** en Open WebUI. Se conservan visibles sin sonido ni vibración, siempre que estén activadas las notificaciones web en Open WebUI y el permiso de Android.
- El canal usa un identificador nuevo porque Android no permite cambiar el sonido de un canal ya creado. En Android, los avisos nuevos aparecen bajo **Mensajes de Sakura (sin sonido)**.

### Actualizado
- Las notificaciones muestran el logo multicolor de Sakura OI Web como icono grande. El icono pequeño de la barra de estado sigue siendo monocromo, según el diseño de notificaciones de Android.

## 0.2.6 (versionCode 30) — 2 de noviembre de 2026

### Añadido
- Al salir de Sakura con Open WebUI cargado, `SakuraKeepAliveService` inicia un servicio en primer plano y muestra la notificación silenciosa **Sakura sigue conectada**. Se detiene al regresar a la app. Esto eleva la prioridad del proceso para ayudar a mantener WebView y la conexión mientras se cambia a otra app; Android todavía puede terminar el proceso si necesita memoria.
- Canal Android independiente y silencioso **Conexión en segundo plano** para el aviso de servicio.

### Corregido
- Al regresar a Sakura ya no se borran todas las notificaciones de respuesta. El usuario puede abrirlas o descartarlas desde Android.

### Documentación
- Actualizados README, guía Android e historial para describir la conexión de fondo, el aviso persistente, el coste de batería y sus límites.


## 0.2.5 (versionCode 29) — 2 de noviembre de 2026

### Añadido
- Guarda la última URL válida de Open WebUI y la usa al recrear el proceso, para regresar a la ruta en la que estaba el usuario. Muestra la pantalla Sakura de carga mientras se restablece la página.

## 0.2.4 (versionCode 28) — 2 de noviembre de 2026

### Corregido
- Las barras de estado y navegación siguen el color declarado por Open WebUI para el tema elegido, en lugar de inferirlo a partir de zonas oscuras de la página. Esto evita activar apariencia oscura por diálogos o imágenes y responde al cambiar entre Sistema, Claro, Oscuro, OLED y Her.

## 0.2.3 (versionCode 27) — 1 de noviembre de 2026

### Cambiado
- Pantalla de error: el botón Reintentar es negro con la flecha blanca y su texto es negro, en lugar de rosa. En modo oscuro se invierten los colores.
- El repositorio pasa a llamarse **Sakura-OI-Web** (`rootProject.name` en `settings.gradle.kts` y rutas de ejemplo en las guías).
- Captura de la pantalla de error actualizada en `docs/images/connection-error.png`.

## 0.2.2 (versionCode 26) — 1 de noviembre de 2026

### Cambiado
- El indicador de carga (`LogoSpinnerView.kt`) y el texto "Conectándose con el servidor…" son negros en lugar de arcoíris y rosa. En modo oscuro se muestran en blanco.
- Captura de la pantalla de carga actualizada en `docs/images/loading.png`.

## 0.2.1 (versionCode 25) — 1 de noviembre de 2026

### Cambiado
- La app se llama **Sakura OI Web**. El identificador `com.sakura.webapp` no cambia, así que se instala como actualización de SakuraWebApp.
- Nueva identidad: icono adaptable y de versiones anteriores a Android 8, y logo de las pantallas de carga y de error, generados desde `assets/sakura-oi-web-original.png`.
- La pantalla de carga muestra el logo, el nombre y la versión, un indicador giratorio con los colores del logo (`LogoSpinnerView.kt`) y el texto "Conectándose con el servidor…" en lugar de "Conectando con Sakura…".

### Corregido
- Con Open WebUI en tema oscuro, la barra de estado y la barra de navegación seguían blancas. `PageThemeBridge.kt` lee el color de fondo de la página arriba y abajo y la app colorea las dos barras con él, con iconos claros u oscuros según el contraste.
- Las pantallas de carga y de error, y la ventana de inicio (`values-night/themes.xml`), siguen el modo oscuro del teléfono.
- Las direcciones HTTPS sin puerto (por ejemplo, las de Tailscale) no se reconocían como el servidor configurado, así que el micrófono y las notificaciones se rechazaban. Ahora se toma el puerto 443 por defecto.

### Documentación
- README, CHANGELOG y guías con el nuevo nombre, tabla con el historial de versiones, sección "Barras del sistema y tema oscuro" en [docs/ANDROID.md](docs/ANDROID.md#barras-del-sistema-y-tema-oscuro) y capturas nuevas de carga, error, tema oscuro e icono.

## 0.2.0 (versionCode 24) — compilación local

- Primer APK con el nombre Sakura OI Web. No se publicó por separado; sus cambios están en 0.2.1.

## 0.1.12 (versionCode 23) — compilación local

- La pantalla de error muestra la flor de Sakura (`sakura_logo`) en lugar del icono del lanzador. Incluido en 0.2.1, ya con el logo nuevo.

## 0.1.11 (versionCode 22) — compilación local

- Acepta direcciones HTTPS sin puerto, como `https://<tu-pc>.<tu-tailnet>.ts.net/`. Incluido en 0.2.1.

## Sin versión de la app — 1 de noviembre de 2026

### Añadido
- [docs/TAILSCALE.md](docs/TAILSCALE.md): usar Sakura fuera de casa sin IP pública con Tailscale Funnel, ajuste de `CORS_ALLOW_ORIGIN` y arranque automático.
- [server/aplicar_icono.py](server/aplicar_icono.py) y [docs/ICONO-OPENWEBUI.md](docs/ICONO-OPENWEBUI.md): cambiar el logo y el favicon de Open WebUI, con opción para restaurar los originales.

## 0.1.10 (versionCode 21) — 1 de noviembre de 2026

### Corregido
- Las notificaciones no aparecían en Android si el ajuste de notificaciones de Open WebUI ya estaba activado desde otro navegador. Open WebUI guarda ese ajuste en el servidor, así que la página nunca pedía permiso en el teléfono y Android bloqueaba los avisos. Ahora la app pide `POST_NOTIFICATIONS` la primera vez que carga Open WebUI (Android 13 o posterior).

### Añadido
- Registro en Logcat (etiqueta `SakuraWebApp`) cuando el puente descarta una notificación, indicando si fue por el origen o por el permiso.
- Sección "Notificaciones de Android" en [docs/ANDROID.md](docs/ANDROID.md#notificaciones-de-android) y captura de la notificación.
- Instrucciones para guardar `SAKURA_WEBUI_URL` en `%USERPROFILE%\.gradle\gradle.properties`.

## 0.1.9 (versionCode 20) — 1 de noviembre de 2026

- Versión de la compilación que incluye las notificaciones de Android. No cambia el código respecto a 0.1.8.

## 0.1.8 (versionCode 19) — 1 de noviembre de 2026

### Añadido
- Notificaciones de Android para los avisos de Open WebUI. El WebView de Android no implementa la API de notificaciones del navegador, así que `WebNotificationBridge.kt` sustituye `window.Notification` en la página y publica cada aviso en el canal "Mensajes de Sakura".
- Permiso `POST_NOTIFICATIONS`, icono de notificación y `launchMode="singleTop"` para que tocar un aviso vuelva a la app abierta.
- Los avisos pendientes se borran al volver a la app.

### Corregido
- La app ignoraba la URL indicada con `-PSAKURA_WEBUI_URL`: abría siempre `open-webui.example` y mostraba "sin conexión". La página inicial, la comprobación de carga y el permiso del micrófono usan ahora `BuildConfig.WEBUI_URL`.

## 0.1.7 (versionCode 18)

- Versión documentada antes de este historial: Open WebUI dentro de un WebView, pantallas de carga y de error con reintento, selector de archivos de Android, micrófono, ajuste al teclado e icono de Sakura.
