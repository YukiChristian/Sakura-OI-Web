# Historial de versiones

Cambios de Sakura OI Web (antes SakuraWebApp) por versión. La versión de cada APK está en `app/build.gradle.kts` (`versionName` y `versionCode`).

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
