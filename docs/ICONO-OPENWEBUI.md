# Cambiar el logo y el favicon de Open WebUI

Open WebUI no tiene una opción para cambiar su icono. [server/aplicar_icono.py](../server/aplicar_icono.py) reemplaza todas las imágenes que usa a partir de un solo logo.

Esto cambia el icono de **Open WebUI** (pestaña del navegador, barra lateral, app instalada). El icono de la app Android SakuraWebApp es otro y se explica en [ANDROID.md](ANDROID.md#ícono-y-la-imagen-de-sakura).

## Licencia

La licencia de Open WebUI restringe quitar o reemplazar su marca. Según su [licencia](https://docs.openwebui.com/license), está permitido en instalaciones con 50 usuarios o menos en un periodo de 30 días; revísala antes de aplicarlo en una instalación más grande. No publiques logos que incluyan la marca de Open WebUI.

## Uso

Prepara una imagen cuadrada, de preferencia PNG con fondo transparente de 512×512 o más. Desde la carpeta del entorno de Open WebUI, ejecuta el script con su Python (ya incluye Pillow):

```powershell
.\Scripts\python.exe C:\ruta\a\SakuraWebApp\server\aplicar_icono.py --logo C:\ruta\a\mi-logo.png
```

Por ejemplo, con la flor de este repositorio: `--logo C:\ruta\a\SakuraWebApp\assets\sakura-icon.png`.

El script encuentra solo el paquete `open_webui`. Si usas otro Python, indícalo con `--open-webui C:\ruta\a\site-packages\open_webui`.

No hace falta reiniciar Open WebUI: recarga la página con **Ctrl+F5**.

Para volver a los iconos originales:

```powershell
.\Scripts\python.exe C:\ruta\a\SakuraWebApp\server\aplicar_icono.py --restaurar
```

La primera vez guarda los originales en `open_webui/frontend/.iconos-originales`.

## Qué archivos cambia

| Archivo | Dónde se ve | Fondo |
|---|---|---|
| `favicon.png` | Pestaña, logo de la barra lateral, avatar por defecto de los modelos | Transparente |
| `favicon.ico`, `favicon.svg`, `favicon-96x96.png` | Pestaña del navegador | Transparente |
| `favicon.png` en la raíz | Imagen de respaldo cuando falla un avatar | Transparente |
| `logo.png` | Icono de la app instalada (`manifest.json`) | Transparente |
| `apple-touch-icon.png` | Pantalla de inicio en iPhone | Blanco con margen |
| `web-app-manifest-192x192.png`, `web-app-manifest-512x512.png` | Pantalla de inicio en Android | Blanco con margen |
| `splash.png`, `splash-dark.png` | Pantalla de carga | Transparente |

Al arrancar, Open WebUI borra `open_webui/static` y lo vuelve a llenar desde `open_webui/frontend/static`. Por eso el script escribe en `frontend/static` (si solo cambiaras `static`, se perdería al reiniciar) y además copia a `static` para que el servidor en marcha lo sirva de inmediato.

## Después de actualizar Open WebUI

`pip install -U open-webui` reinstala los iconos originales. Vuelve a ejecutar el script con `--logo` después de cada actualización.

## Si sigue apareciendo el icono anterior

El servidor ya sirve el nuevo; lo que queda es caché:

- **Pestaña del navegador:** abre `https://<tu-servidor>/static/favicon.png`, pulsa Ctrl+F5 y vuelve a abrir la pestaña de Open WebUI. Si no basta, borra **Archivos e imágenes en caché** del navegador.
- **Open WebUI instalada como app en Chrome:** guarda el icono al instalarse. Quítala en `chrome://apps`, abre `https://<tu-servidor>/static/logo.png` con Ctrl+F5 y vuelve a instalarla. Si la barra de tareas de Windows sigue mostrando el anterior, reinicia la sesión de Windows.
- **Acceso directo en el teléfono:** quítalo de la pantalla de inicio y vuelve a agregarlo.
