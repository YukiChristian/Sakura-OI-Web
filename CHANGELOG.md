# Historial de versiones

Cambios de Sakura OI Web (antes SakuraWebApp) por versión. La versión de cada APK está en `app/build.gradle.kts` (`versionName` y `versionCode`).

## 0.9.1.7 (versionCode 69) — 13 de noviembre de 2026

Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.7).

### Agregado
- **Compartir abre el menú de compartir de Android.** El WebView de Android no tiene la Web Share API, así que el botón **Compartir** del visor de imágenes y del visor de archivos abría la imagen en lugar de compartirla. Ahora la app le da a la página `navigator.share` y `navigator.canShare` (`WebShareBridge`): los archivos se guardan en la caché de la app (carpeta `compartir`, se borra después de una hora) y se entregan con un `FileProvider` al menú nativo, que muestra la vista previa de la imagen y las apps. También funciona para compartir texto y enlaces.

## 0.9.1.6 (versionCode 68) — 13 de noviembre de 2026

Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.6).

### Cambiado
- **Vuelve el resaltado de foco de Android en la página**, como hasta la 0.9.1.4. El marco claro alrededor de la pantalla que se quiso quitar en la 0.9.1.5 no venía de ese resaltado sino del tema (un filo sobre la barra lateral, que en el teléfono ocupa toda la pantalla), y ya se corrigió en el tema; por eso se quita `defaultFocusHighlightEnabled = false`.

## 0.9.1.5 (versionCode 67) — 12 de noviembre de 2026

Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.5).

### Corregido
- **Sin marco claro alrededor de la página al abrir el teclado.** Cuando la app le da el foco a la página para abrir el teclado (**Nuevo** del widget o **Chat nuevo** del acceso directo), Android dibujaba su resaltado de foco, un marco claro alrededor de toda la página. Ahora el WebView no muestra ese resaltado (`defaultFocusHighlightEnabled = false`, Android 8 o superior).

## 0.9.1.4 (versionCode 66) — 12 de noviembre de 2026

Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.4).

### Corregido
- **«Oye, Sakura» ya no deja de escuchar con el tiempo.** `WakeWordService` revisa cada 30 segundos que esté escuchando y que el reconocedor siga recibiendo sonido, y lo reinicia si no; después de oír la frase vuelve a escuchar a los 10 segundos si Sakura no llegó a abrirse (pantalla bloqueada, Android impidió abrirla). Al encenderlo se pide quitar la restricción de batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, con el diálogo de Android); si no se quita, la pantalla «Oye, Sakura» lo avisa con el botón **Quitar restricción**.
- **Pantalla propia de «sin conexión» en lugar de «Webpage not available».** Cuando Open WebUI no alcanza su servidor abre `/error`; la app guardaba esa página como la última y, si Android la cerraba en segundo plano, al volver restauraba la página de error de Android sin intentar cargar nada. Ahora `/error` nunca se guarda como última página, el estado de la página no se guarda mientras hay un error, y cuando Open WebUI va a `/error` aparece «No hay conexión con Sakura» con **Reintentar**.
- **Nuevo** del widget y **Chat nuevo** vuelven a abrir el teclado cuando Open WebUI ya había puesto el cursor en el cuadro.

### Cambiado
- **Las barras de Android toman el color de la app junto a ellas:** la barra de estado el de la orilla superior de la página y la de navegación el de la inferior, leídos de la pantalla (`PixelCopy`, 4 dp hacia dentro para saltar bordes). Con la barra lateral abierta las dos quedan del gris de la barra lateral; en el chat siguen el encabezado y el fondo.

## 0.9.1.3 (versionCode 65) — 12 de noviembre de 2026

Incluye la compilación local 0.9.1.2. Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.3).

### Corregido
- **Ya no aparece un recuadro blanco del tamaño del teclado** al cambiar de app y regresar. Al volver, Android entregaba todavía la altura del teclado de antes de salir y la página se encogía para dejarle espacio. Ahora `MainActivity` no reserva espacio para el teclado hasta que la ventana vuelve a tener el foco (`returningToScreen`); al salir, el cuadro de mensaje suelta el cursor y el teclado se cierra (`NO_AUTOFOCUS_JS.leave`); cuando el teclado empieza a esconderse, la página ocupa su espacio de inmediato (`WindowInsetsAnimationCompat`), y el WebView se pausa 200 ms después, ya redibujado a su tamaño, para que al volver no reaparezca la imagen anterior encogida. Lo que llega a verse antes de que la página se dibuje toma su color (`setBackgroundColor`) en lugar de blanco. Probado con grabaciones de pantalla en el emulador: apps recientes, inicio, otra app, con el teclado abierto y cerrado.
- **Si Android cierra el proceso que dibuja la página, la app se recupera.** Antes no manejaba `onRenderProcessGone` y Android cerraba toda la app; al volver arrancaba de cero (la pantalla guardada, un destello blanco y la página cargando). Ahora la pantalla se reconstruye en el mismo chat, y el proceso de la página conserva prioridad alta en segundo plano (`setRendererPriorityPolicy`).

## 0.9.1.1 (versionCode 63) — 12 de noviembre de 2026

Desde esta versión la numeración tiene cuatro partes (0.9.1.1, 0.9.1.2…), para avanzar más despacio hacia la 1.0. Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1.1).

### Cambiado
- **El teclado ya no se asoma al cambiar de chat.** Open WebUI ponía el cursor en el cuadro de mensaje y el teclado alcanzaba a abrirse antes de que la app lo quitara. Ahora `NO_AUTOFOCUS_JS` ignora las llamadas a `focus()` sobre el cuadro que el usuario no pidió, así el teclado no llega a abrirse, y si algo lo abre de todos modos la app lo cierra (`SakuraApp.hideKeyboard`). Probado en el emulador en tres vueltas: abrir en frío, volver a la app, cambiar de chat (enlaces, barra lateral, varios seguidos, con el teclado abierto), Nuevo Chat, modelos fijados, Notas, Agenda, Atrás, modo voz, widget, pantalla apagada y encendida, notificaciones, apps recientes, cambio de tema y el final de una respuesta.
- **Nuevo** del widget y el acceso directo **Chat nuevo** vigilan 3 segundos que el cursor siga en el cuadro y lo devuelven si Open WebUI lo vuelve a dibujar (antes el teclado podía cerrarse solo medio segundo después).
- **El modo voz se cuelga al salir de la app.** Una llamada abierta en segundo plano ocupaba el micrófono unos segundos y «Oye, Sakura» no oía la frase mientras tanto. `MIC_WATCH_JS` guarda los flujos de micrófono de la página; al dejar la pantalla, la app pulsa «Finalizar llamada» y detiene el micrófono.
- **El widget y «¿Con quién quieres hablar?» solo muestran los modelos fijados** en Open WebUI (`ui.pinnedModels` en los ajustes del usuario), en ese orden y entre los disponibles. Los demás modelos del servidor ya no aparecen.

## 0.9.1 (versionCode 61) — 12 de noviembre de 2026

Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.1).

### Cambiado
- **Widget más transparente**, igual que los widgets del sistema de un teléfono de referencia: el fondo pasa de 92 % a 80 % de opacidad (`widget_glass` `#CCFFFFFF` y `#CC18181B` en tema oscuro) y la barra de accesos, los botones redondos y el «+N» de 100 % a 85 % (`widget_tint`). Android no permite a las apps desenfocar el fondo de sus widgets, así que detrás se ve nítido.

## 0.9.0 (versionCode 60) — 12 de noviembre de 2026

Reúne las compilaciones locales 0.8.8 y 0.8.9. Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.9.0).

### Cambiado
- **El teclado solo se abre cuando tocas el cuadro de mensaje.** Open WebUI pone el cursor ahí al cargar, al abrir o crear un chat, al terminar una respuesta y al volver a la app; `NO_AUTOFOCUS_JS` ya no lo permite en ningún momento (antes solo durante 4 s tras abrir un chat), salvo que el último toque o tecla haya sido en el cuadro o sus botones. Al cerrar el teclado el cuadro suelta el cursor, para que nada lo vuelva a abrir. `MainActivity` usa `stateAlwaysHidden`. Editar un mensaje, las notas y el buscador no cambian.
- **Única excepción:** el botón **Nuevo** del widget y el acceso directo **Chat nuevo** abren el chat con el cursor en el cuadro y el teclado listo (`IncomingIntents.EXTRA_FOCUS`, `FOCUS_COMPOSER_JS` y `SakuraApp.showKeyboard`), con la app abierta o cerrada.
- **Widget:** el diseño se elige por el tamaño real del widget. En Android 12 o superior la app entrega todos los diseños (`RemoteViews` con tamaños) y el launcher muestra el más grande que cabe; antes se decidía con la altura que reporta el launcher, y algunos la reportan menor, lo que ocultaba la fila de modelos del 4 × 2 aunque había espacio. Los widgets más anchos muestran más avatares (5 a 7) y el mediano los botones que quepan.

## 0.8.7 (versionCode 57) — 12 de noviembre de 2026

Sigue el diseño "Widgets · 1, 3 y 8 modelos, con Notas y Calendario". Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.8.7).

### Cambiado
- **Widget rediseñado en tres tamaños**, que se eligen solos según cómo lo dejes en la pantalla de inicio (`VoiceWidgetProvider.update`):
  - **Grande 4 × 2** (`widget_large.xml`): avatar, nombre del modelo con ▾ y el micrófono azul; una fila con los avatares de los modelos (no aparece si hay uno solo; caben hasta 7 y, si hay más, el último lugar es «+N»; el elegido lleva un aro de su color); y la barra **Nuevo · Calendario · Notas · Último**. Si el launcher le da menos de 200 dp de alto, la fila de avatares se oculta para que no se corte.
  - **Mediano de una fila** (`widget_medium.xml`): micrófono, modelo y los botones redondos Nuevo chat, Calendario y Notas (los que quepan, en ese orden).
  - **Chico 2 × 2** (`widget_small.xml`): avatar y micrófono, modelo, Nuevo chat y Calendario.
- Los modelos del widget son los visibles en tu Open WebUI (Sakura, Lain y Yoko primero). `ChatModels` guarda la lista y la vuelve a leer cuando la app ve tu sesión (cada 30 min como máximo) o al abrir el selector; los widgets se redibujan si cambió. Los modelos sin imagen muestran su inicial en un color propio, sin el prefijo de la conexión (“laín.qwen-27b” → “qwen-27b”).
- Tocar el nombre (▾) o «+N» abre **«¿Con quién quieres hablar?»**, ahora una cuadrícula de cinco columnas con todos los modelos y “N modelos · el widget recordará tu elección”. Tocar un avatar de la fila abre un chat nuevo con ese modelo y lo deja como modelo del widget; el micrófono sigue siendo el único acceso al modo de voz.
- **Calendario** y **Notas** abren `/calendar` y `/notes` de Open WebUI (`IncomingIntents.ACTION_CALENDAR` y `ACTION_NOTES`), dentro de la página si ya está cargada.
- Se quitaron los chips, la cápsula, la insignia y los botones oscuros del widget anterior y sus imágenes de vista previa.

## 0.8.6 (versionCode 56) — 12 de noviembre de 2026

Reúne las compilaciones locales 0.8.1 a 0.8.5. Publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.8.6).

### Cambiado
- **APK más ligero: de 35.4 MB a 6.6 MB**, con las mismas funciones y animaciones. El build `release` usa R8 sobre toda la app (código y recursos sin uso); `app/proguard-rules.pro` conserva lo que se llama por nombre: los métodos `@JavascriptInterface` de los puentes con la página y Vosk/JNA de «Oye, Sakura». La librería de voz va comprimida en el APK (`packaging.jniLibs.useLegacyPackaging`, de unos 8.6 a 2.8 MB por copia) y Android descomprime solo la del teléfono al instalar. El APK publicado trae `arm64-v8a` y `armeabi-v7a`; la compilación de depuración agrega `x86_64` para el emulador. Se quitaron las dependencias de Jetpack Compose y SwipeRefreshLayout, la plantilla `ui/theme` que no se usaba, y las imágenes de `drawable-nodpi` pasaron de PNG a WebP sin pérdida. El arranque en frío queda igual.
- **El teclado ya no se abre solo** al abrir Sakura, al volver a ella ni al abrir un chat (de la barra lateral, uno nuevo o con Atrás). `NO_AUTOFOCUS_JS` deshace durante 4 s el foco automático de Open WebUI en el cuadro de mensaje, salvo que lo toques o escribas; `MainActivity` usa `stateHidden`. Al compartir texto o abrir `?q=` el cuadro sí queda listo para escribir.
- **Deslizar hacia abajo ya no recarga la página**; solo desplaza el chat.
- Widget grande: **Chat nuevo** y **Último chat** tienen más peso (relleno oscuro, 54 dp de alto, icono de 21 dp y texto de 16 sp). El modo de voz se abre solo con el **micrófono azul**; “Habla con <modelo>” y los chips **Sakura, Lain y Yoko** abren un chat nuevo con ese modelo, que queda como modelo del widget.

## 0.8.0 (versionCode 50) — 12 de noviembre de 2026

Primer APK firmado, publicado en [GitHub Releases](https://github.com/YukiChristian/Sakura-OI-Web/releases/tag/v0.8.0).

### Añadido
- `AppLock.kt`: **bloqueo con huella** opcional en *Ajustes del teléfono*. Pide la huella, el rostro o el PIN del teléfono al volver a Sakura (al instante, a 1 o a 5 minutos); activarlo o desactivarlo también lo pide. Una pantalla “Sakura está bloqueada” tapa los chats, el modo de voz pedido mientras está bloqueada espera al desbloqueo, Ajustes también lo pide y la vista de apps recientes no muestra el contenido. Cambiar entre pantallas de la propia app no bloquea.
- `PushNotices.kt`: **avisos con la app cerrada** con UnifiedPush y una app distribuidora como ntfy. Al activarlo, Sakura se registra en ntfy, activa en Open WebUI los webhooks de usuario y agrega un destino de notificación para “chat terminado” y “chat fallido” cuando no estás conectado. Los mensajes se comprueban con un token; al tocarlos se abre el chat.
- `Updates.kt` y `UpdatePrompt.kt`: **actualizaciones** desde GitHub Releases. Revisa una vez al día y con *Buscar actualizaciones*; si hay versión nueva muestra “Hay una actualización” y descarga el APK para que Android lo instale. Las compilaciones personales (“0.8.0c”) comparan su número y también reciben el aviso.
- Firma con las propiedades `SAKURA_KEYSTORE_FILE`, `SAKURA_KEYSTORE_PASSWORD`, `SAKURA_KEY_ALIAS` y `SAKURA_KEY_PASSWORD` en `~/.gradle/gradle.properties`, fuera del repositorio. Si están, la compilación de depuración usa la misma llave, así una versión personal y la publicada se instalan una sobre otra.

### Cambiado
- La app acepta certificados instalados en Android (`<certificates src="user" />`), así un servidor de casa con certificado propio funciona con el APK publicado sin compilar uno propio.
- El build `release` no usa R8: optimizar solo `androidx` y `kotlin` rompía el arranque.

## 0.7.7 (versionCode 49) — 12 de noviembre de 2026

### Cambiado
- El widget grande ya no muestra “«Oye, Sakura» activo” debajo de “Habla con …”: distraía y no aportaba. Se quitaron los textos que ya no se usan.

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
