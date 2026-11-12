# 🗺️ Ideas y hoja de ruta

Ideas para que Sakura OI Web se sienta como una app nativa de Android. El estado de cada una se actualiza cuando se publica una versión.

| # | Idea | Estado |
|---:|---|---|
| 1 | Compartir con Sakura | En desarrollo |
| 2 | "Preguntar a Sakura" al seleccionar texto | En desarrollo |
| 3 | Accesos directos del icono | En desarrollo |
| 4 | Sakura como asistente del teléfono | En desarrollo |
| 5 | Cambio automático entre la red de casa y la exterior | En desarrollo |
| 6 | Aviso de conexión perdida durante el uso | En desarrollo |
| 7 | Deslizar hacia abajo para recargar | En desarrollo |
| 8 | Avisos aunque Android cierre la app | Pendiente |
| 9 | Bloqueo con huella o rostro | Pendiente |
| 10 | APK firmado en GitHub Releases y aviso de actualización | Pendiente |
| 11 | Activación por voz "Oye, Sakura" | En desarrollo |

## Integración con Android

### 1. Compartir con Sakura
La app aparece en el menú **Compartir** de Android. Al compartir un texto, un enlace, una foto o un PDF desde cualquier app, se abre un chat nuevo en Open WebUI con ese contenido ya escrito o adjunto, listo para enviarlo.

### 2. "Preguntar a Sakura" al seleccionar texto
Al seleccionar un texto en cualquier app, el menú contextual (junto a Copiar y Compartir) muestra **Preguntar a Sakura**, que abre un chat nuevo con el texto ya escrito.

### 3. Accesos directos del icono
Al mantener pulsado el icono de la app aparecen **Chat nuevo**, **Modo de voz** y **Último chat**. Cualquiera se puede anclar en la pantalla de inicio.

### 4. Sakura como asistente del teléfono
Registrar la app como asistente digital de Android, para que el gesto o botón de asistente abra Sakura directamente en modo de voz.

## Conexión y velocidad

### 5. Cambio automático entre la red de casa y la exterior
En el Wi-Fi de casa, conectar por la dirección local del servidor, que es más rápida, y fuera de casa por la dirección exterior (por ejemplo, Tailscale), sin compilar dos versiones de la app.

### 6. Aviso de conexión perdida durante el uso
Si la red se cae con Open WebUI abierto, mostrar una barra discreta "Sin conexión, reconectando…" que desaparece sola al volver la red.

### 7. Deslizar hacia abajo para recargar
Como en Chrome: deslizar hacia abajo desde arriba del todo recarga la página.

## Notificaciones

### 8. Avisos aunque Android cierre la app
Hoy las notificaciones solo llegan mientras la app sigue viva en segundo plano. Con un servicio de notificaciones push (por ejemplo, ntfy, que se puede alojar en el mismo equipo) y una función de Open WebUI que lo avise al terminar cada respuesta, los avisos llegarían siempre.

## Privacidad y distribución

### 9. Bloqueo con huella o rostro
Bloqueo opcional al abrir la app, porque da acceso a todos los chats.

### 10. APK firmado en GitHub Releases y aviso de actualización
Publicar un APK firmado en GitHub Releases y avisar dentro de la app cuando haya una versión nueva.

## Voz

### 11. Activación por voz "Oye, Sakura"
Escuchar la frase "Oye, Sakura" con la app en segundo plano y abrir el modo de voz. Opcional y desactivada por defecto, porque mantiene el micrófono escuchando y consume batería.
