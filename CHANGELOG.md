# Historial de versiones

Cambios de SakuraWebApp por versión. La versión de cada APK está en `app/build.gradle.kts` (`versionName` y `versionCode`).

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
