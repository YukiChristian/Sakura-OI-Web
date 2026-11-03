# Tema Carbono para Open WebUI

> **Tema recomendado para Sakura OI Web.** La versión actual (0.82) está en **[YukiChristian/Sakura-OI-Web-Carbono](https://github.com/YukiChristian/Sakura-OI-Web-Carbono)**, con capturas, instrucciones y el bloque para el lanzador. Este documento describe la versión anterior que trae esta rama en `server/temas/carbono`.

**Carbono** es un tema sugerido para Open WebUI, la línea de estilo que acompaña a Sakura OI Web. Mantiene la interfaz neutra (superficies en tonos carbón y zinc, acción principal en negro) y la ordena: más jerarquía en el texto, tamaños más legibles en escritorio y en el teléfono, y los cinco colores del logo solo en detalles pequeños.

No modifica Open WebUI. Son dos archivos (`carbono.css` y `carbono.js`) que el lanzador [server/run_open_webui_https.py](../server/run_open_webui_https.py) sirve en lugar de los vacíos que Open WebUI ya carga en cada página (`/static/custom.css` y `/static/loader.js`). Se activa y se quita con un comando, y sobrevive a `pip install -U open-webui`.

Probado con Open WebUI **0.12.5**.

## Contenido

- [Principios](#principios)
- [Colores](#colores)
- [Tipografía y jerarquía](#tipografía-y-jerarquía)
- [Qué cambia en la interfaz](#qué-cambia-en-la-interfaz)
- [Instalar](#instalar)
- [Activar, quitar y ver el estado](#activar-quitar-y-ver-el-estado)
- [Actualizaciones de Open WebUI](#actualizaciones-de-open-webui)
- [Personalizar](#personalizar)
- [Limitaciones](#limitaciones)

## Principios

- **Superficies neutras, detalles de color.** Fondos, tarjetas, texto y el botón principal son grises o negros. El color entra en elementos de 32 px o menos (íconos, chips, badges) y en fondos tenues.
- **La acción principal es neutra.** Guardar, Enviar, Crear y el botón de voz usan `primary`: negro en tema claro, casi blanco en oscuro.
- **Jerarquía clara.** Cada pantalla distingue título, subtítulo, nombre de ajuste y descripción por tamaño, peso y color, nunca solo por color.
- **Legible en el teléfono.** Debajo de 768 px el texto y los controles crecen un paso y los botones táctiles miden al menos 40–44 px.
- **Contraste suficiente.** Todos los pares de texto cumplen 4.5:1 en ambos temas.
- **Respeta lo personal.** No toca la imagen de fondo del chat, las imágenes de los avatares ni la animación del modo voz.

## Colores

| Token | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| `bg` | `#fafafa` | `#131313` | Fondo de la página y del chat |
| `surface` | `#ffffff` | `#1b1b1b` | Composer, modales, menús, tarjetas |
| `surface-sidebar` | `#f4f4f5` | `#171717` | Barra lateral y navegación de Ajustes |
| `surface-hover` | `#ebebec` | `#262626` | Hover de filas y botones |
| `surface-selected` | `#e4e4e7` | `#2a2a2a` | Chat o pestaña activa |
| `border` / `border-strong` | `#e4e4e7` / `#8b8b93` | `#2a2a2a` / `#6a6a72` | Divisores / bordes de campos |
| `ink` | `#18181b` | `#ececee` | Texto principal |
| `ink-muted` | `#52525b` | `#a6a6ad` | Descripciones, íconos en reposo |
| `ink-subtle` | `#6b6b73` | `#8b8b93` | Etiquetas de sección, fechas |
| `primary` / `on-primary` | `#18181b` / `#ffffff` | `#ececee` / `#18181b` | Acción principal y su texto |

Colores del logo (solo detalles): `rosa` `#b4325c`, `naranja` `#a84f0c` (herramientas), `verde` `#2b7a3b` (búsqueda web), `azul` `#2361c7` (anillo de foco, enlaces, globo de tus mensajes `#e6eefb`) y `violeta` `#6f42c8`. Cada uno tiene una versión `-soft` para fondos tenues. Estados: `success` `#14706a`, `warning` `#8f5a00`, `danger` `#c0392b`.

## Tipografía y jerarquía

Figtree para toda la interfaz y JetBrains Mono para código (Google Fonts).

| Nivel | Escritorio | Móvil (< 768 px) | Dónde |
| --- | --- | --- | --- |
| Saludo: nombre del modelo | 40 px | 34 px | Pantalla de chat vacío |
| Saludo: frase | 30 px | 24 px | "¿Cómo puedo ayudarte hoy?", en gris |
| Título de página | 24 px, seminegrita | — | Ajustes › General, Cuenta… |
| Subtítulo / sección | 16–18 px, seminegrita | — | "WebUI Ajustes", "Parámetros Avanzados" |
| Nombre de ajuste | 16 px, peso medio | — | "Tema", "Idioma" |
| Texto del chat | 16 px | 17 px | Respuestas y tus mensajes |
| Filas del menú lateral | 15 px | 18 px | Opciones, modelos y chats |
| Descripción | 14–15 px, gris | — | Texto bajo cada ajuste |
| Etiqueta de sección | 11.5 px, mayúsculas | 12.5 px | MODELOS, CHATS, HOY |

## Qué cambia en la interfaz

- **Barra lateral:** filas del mismo tamaño, chat activo con fondo gris y barra de 3 px, etiquetas en mayúsculas, fechas legibles y nombre de la app más grande.
- **Chat:** composer con bordes de 24 px y sombra suave, globo de tus mensajes en azul muy claro, saludo que ya no se corta y barra superior con velo para que el texto no se lea detrás del título.
- **Selector de modelo y menús:** filas de 38–48 px, avatares de 28 px, modelo seleccionado marcado y "Establecer como Predeterminado" en azul.
- **Integraciones:** filas de 52 px con mosaicos de color (herramientas en naranja, búsqueda web en verde) y switch negro.
- **Ajustes y administración:** jerarquía título › sección › ajuste › descripción, controles de 36 px y menú lateral de 38 px.
- **Notas y Espacio de Trabajo:** textos de 13 px pasan a 15 px, metadatos a 13–14 px en gris y botón **Crear** en `primary`.
- **Panel de controles del chat:** secciones, parámetros y valores alineados en columna, más la pestaña Archivos con la misma jerarquía.
- **Inicio de sesión:** tarjeta con campos de 44 px y botón principal negro.
- **Notificaciones:** colores de estado del sistema (error, éxito, aviso, info) y esquinas de 16 px.
- **Modo voz:** avatar más grande, estado de 17 px con punto de color y botones de 64 px con su etiqueta.
- **Barras de desplazamiento:** invisibles en reposo; aparecen mientras desplazas (`carbono.js`).

## Instalar

1. Copia la carpeta [server/temas/carbono](../server/temas/carbono) dentro de la carpeta de tu Open WebUI, junto al lanzador:

   ```text
   Open WebUI\
   ├─ Scripts\python.exe
   ├─ run_open_webui_https.py
   └─ temas\
      └─ carbono\
         ├─ carbono.css
         ├─ carbono.js
         ├─ tema.py
         ├─ Aplicar-Tema.cmd
         └─ Quitar-Tema.cmd
   ```

2. Usa el lanzador [server/run_open_webui_https.py](../server/run_open_webui_https.py) de este repositorio, que ya incluye el hook del tema (la función `tema_carbono`). Si tienes tu propio lanzador, copia el bloque marcado como *Optional Carbono theme* justo después de `import open_webui.main` y antes de `uvicorn.run(...)`.
3. Reinicia Open WebUI **una sola vez** para que cargue el hook.

Opcional: define la variable de entorno `WEBUI_THEME_NAME` (por ejemplo, `Sakura OI Web`) para cambiar el nombre que se ve en la barra lateral, el inicio de sesión y la pestaña mientras el tema está activo. Se usa así y no con `WEBUI_NAME` porque Open WebUI le añade " (Open WebUI)" a cualquier nombre distinto. Revisa la [licencia de Open WebUI](https://docs.openwebui.com/license) antes de cambiar su marca: lo permite en instalaciones con 50 usuarios o menos en cualquier periodo de 30 días.

## Activar, quitar y ver el estado

Haz doble clic en `temas\carbono\Aplicar-Tema.cmd` o `Quitar-Tema.cmd`, o desde PowerShell en la carpeta de Open WebUI:

```powershell
.\Scripts\python.exe .\temas\carbono\tema.py aplicar
```

```powershell
.\Scripts\python.exe .\temas\carbono\tema.py quitar
```

```powershell
.\Scripts\python.exe .\temas\carbono\tema.py estado
```

Desde cualquier otra carpeta, escribe las rutas completas con el operador `&` de PowerShell:

```powershell
& 'C:\ruta\a\Open WebUI\Scripts\python.exe' 'C:\ruta\a\Open WebUI\temas\carbono\tema.py' aplicar
```

`aplicar` crea el archivo `temas\carbono\ACTIVO` y `quitar` lo borra. Ninguno reinicia el servidor: recarga la página con **Ctrl+F5**. En el teléfono, cierra la pestaña o la app y vuelve a abrirla para que no use el CSS en caché. `estado` avisa si el lanzador no tiene el hook.

Al quitarlo se desactivan a la vez los estilos, el comportamiento de las barras de desplazamiento y el nombre opcional: Open WebUI vuelve a verse como el original.

## Actualizaciones de Open WebUI

El tema vive fuera de `site-packages`, así que `pip install -U open-webui` no lo borra. Por la misma razón no se usa `open_webui/static/custom.css`: Open WebUI borra y vuelve a copiar esa carpeta cada vez que arranca.

Para resistir cambios de versión, `carbono.css`:

- cambia primero variables que Open WebUI ya usa en todas partes (la paleta `gray` de Tailwind, `--app-font-family`, `--text-sm`/`--text-xs`);
- después apunta a ids, roles y atributos estables (`#sidebar`, `#message-input-container`, `#controls-container`, `[role="tab"]`, `[aria-modal]`…) en lugar de cadenas de clases;
- no usa ningún `@layer`, de modo que gana a las utilidades de Tailwind sin `!important`.

Si una actualización cambia algún elemento, esa regla deja de aplicarse y la pantalla vuelve al estilo original de Open WebUI. No se rompe nada.

## Personalizar

Los colores están como variables al inicio de `carbono.css` (`--sk-bg`, `--sk-ink`, `--sk-primary`…), con su versión oscura en `html.dark`. Cambia los valores ahí y recarga con Ctrl+F5; no hace falta reiniciar. Los tamaños de móvil están juntos en el bloque `@media (max-width: 767px)`.

## Limitaciones

- Algunos detalles dependen de cómo Open WebUI arma su HTML (por ejemplo, el color de cada mosaico de Integraciones se elige por el dibujo de su ícono). Si cambian en una versión futura, esos detalles vuelven al estilo original.
- Las etiquetas bajo los botones del modo voz salen del texto accesible de Open WebUI ("Cámara", "Silenciar", "Finalizar llamada") y se traducen solas con el idioma de la interfaz.
- Con varios workers de uvicorn (`UVICORN_WORKERS` > 1) cada proceso importa de nuevo `open_webui.main`; el hook está pensado para un solo worker, que es el valor por defecto.
