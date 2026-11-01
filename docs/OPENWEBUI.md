# Open WebUI y voz

## Versiones y nombres de modelo

La instalación local consultada el 22 de octubre de 2026 tiene Open WebUI **0.12.3**. El nombre visible configurado en Open WebUI es **Sakura**. Los registros de llama-server compartidos muestran llama.cpp **0.7.0-dev**, compilado para **x64 con CUDA** desde el commit **`ea0c4c2c8`**, cargando el modelo `SoAIHQ/Qwen3.5-9B-GGUF:Q4_K_M`: Qwen 3.5 de 9 mil millones de parámetros en formato GGUF con cuantización Q4_K_M. El log también indica un proyector multimodal `mmproj-Qwen3.5-9B-f16.gguf`. Por tanto, **llama.cpp es el motor/servidor de inferencia; el modelo cargado es Qwen 3.5, no un modelo de la familia Llama**. El nombre Sakura es el alias que se muestra en la interfaz.

Qwen3-TTS Fast es el servicio de voz independiente de este ejemplo. Su modelo reportado en los registros/configuración es `qwen3-tts-clone-0.6b`. Open WebUI lo llama mediante un endpoint compatible con OpenAI; no es el modelo LLM que genera la respuesta. **No necesitas instalar ni usar Qwen3-TTS**: configura cualquier proveedor TTS compatible con tu versión de Open WebUI, o deja el TTS desactivado.

## Servidor LLM llama.cpp (Qwen 3.5)

El registro proporcionado identifica un servidor `llama-server` escuchando en el puerto **50002**, con el modelo `SoAIHQ/Qwen3.5-9B-GGUF:Q4_K_M`, métricas activadas y cuatro slots de generación. El ejecutable de referencia en la PC original está en `C:\Llama-Sakura\build-cuda\bin\Release\llama-server.exe`; es una ruta específica de esa computadora, no un requisito del proyecto. La ruta local de los archivos del modelo y del proyector no se publica porque solo es válida en esa computadora. Configura el proveedor OpenAI-compatible de Open WebUI para apuntar al endpoint de llama.cpp que sea alcanzable desde el proceso Open WebUI.

El mismo registro muestra que el proceso se inicia con `--host 0.0.0.0`, `--port 50002` y `--metrics`, además de la advertencia **“no API key is set and CORS allows all origins”**. El enlace `0.0.0.0` escucha en todas las interfaces de red; no expongas ese servidor sin protección a Internet. Para otros despliegues, habilita una API key, restringe CORS al origen que necesita Open WebUI, usa el firewall y limita el acceso a la red privada o VPN. El ejemplo de esta instalación debe considerarse una configuración de laboratorio, no un valor seguro para copiar tal cual.

## Arranque manual de Open WebUI

Abre PowerShell en el directorio de la instalación de Open WebUI. Ajusta las rutas al equipo local; conserva la ruta de datos y la misma clave secreta entre reinicios:

```powershell
Set-Location '<carpeta-de-instalacion-de-Open-WebUI>'

$env:DATA_DIR = '<carpeta-de-instalacion-de-Open-WebUI>\data'
$env:WEBUI_SECRET_KEY = (Get-Content -LiteralPath '<archivo-local-de-WEBUI_SECRET_KEY>' -Raw).Trim()

$env:WHISPER_VAD_FILTER = 'true'
$env:WHISPER_LANGUAGE = 'es'
$env:WHISPER_MULTILINGUAL = 'true'
$env:WHISPER_COMPUTE_TYPE = 'int8'

$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8:backslashreplace'
$env:FROM_INIT_PY = 'true'
$env:WEBUI_URL = 'https://<IP-o-host-del-PC>:8081'
$env:CORS_ALLOW_ORIGIN = $env:WEBUI_URL

& '.\Scripts\python.exe' '-X' 'utf8' '.\run_open_webui_https.py'
```

La instalación que se usó tenía un lanzador `run_open_webui_https.py` que inicia Uvicorn en `0.0.0.0:8081` con `tls/server.crt` y `tls/server.key`. Mantén el proceso de PowerShell abierto; `Ctrl+C` lo detiene. La clave secreta, la carpeta `data`, `server.key` y cualquier copia de la base de datos son privadas y no pertenecen al repositorio.

`PYTHONUTF8`, `PYTHONIOENCODING` y `-X utf8` se añadieron para que Whisper/Python pueda procesar caracteres en español sin errores de codificación `charmap`. `WHISPER_LANGUAGE=es` fija el idioma; `WHISPER_VAD_FILTER`, `WHISPER_MULTILINGUAL` y `WHISPER_COMPUTE_TYPE=int8` son los ajustes de transcripción que se estaban usando.

## No perder perfil, chats ni ajustes

Open WebUI guarda los datos de usuario en `DATA_DIR`. Si se inicia con una carpeta diferente o vacía, puede aparecer de nuevo el formulario de creación de la cuenta administradora aunque los datos anteriores sigan en otra ubicación. Verifica `DATA_DIR` antes de crear otra cuenta. No borres la carpeta `data` ni regeneres `WEBUI_SECRET_KEY` de forma accidental.

## TTS es opcional: configurar Qwen3-TTS u otro proveedor

Sakura OI Web no incluye ni requiere un motor TTS específico. El texto a voz se configura en Open WebUI; puedes usar cualquier proveedor compatible con la versión de Open WebUI que tengas, o dejar la función desactivada. Revisa el endpoint, modelo, credenciales y requisitos de red del proveedor elegido. El ejemplo siguiente documenta Qwen3-TTS Fast porque es el que se usó en esta instalación; puedes omitirlo si usarás otro servicio.

### Ejemplo: Qwen3-TTS Fast

En una segunda terminal de Windows, inicia el servidor WSL y deja la ventana abierta mientras uses voz:

```powershell
wsl.exe -d Ubuntu -- /home/<usuario-linux>/qwen3-tts-fast-env/bin/python /mnt/d/Sakura/Voz/qwen3-tts/server_fast.py
```

El proceso reporta Uvicorn en `127.0.0.1:5052`. En los registros observados, las llamadas a `POST /v1/audio/speech` devolvieron `200 OK`.

En la configuración de Text-to-Speech de Open WebUI:

- Proveedor/engine: API compatible con OpenAI.
- Base URL: `http://127.0.0.1:5052/v1` si Windows puede alcanzar el puerto local de WSL.
- Modelo: `qwen3-tts-clone-0.6b`.
- La API Fast observada ignora los valores de voz `alloy` y modelo enviados por algunos clientes y utiliza su configuración de clonación/servidor.

Si no hay audio, verifica cada tramo: proceso Qwen vivo, endpoint alcanzable desde Windows, configuración TTS de Open WebUI y volumen multimedia del teléfono. La voz entrante (Whisper) y la voz saliente (Qwen3-TTS) son componentes separados.

## Variables de URL y CORS

`WEBUI_URL` y `CORS_ALLOW_ORIGIN` deben usar el mismo origen que abre el teléfono, incluido `https`, host y puerto. En la app pública, la dirección es una propiedad de Gradle y se pasa con `-PSAKURA_WEBUI_URL=...`. No uses `localhost`/127.0.0.1 para el teléfono: en el teléfono significaría el propio teléfono, no Windows.
