"""HTTPS launcher for an existing Open WebUI Python installation.

Place this file beside the Open WebUI environment and a local tls/ directory
containing server.crt and server.key. Keep the private key out of Git.
"""
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

base_url = os.environ.get("WEBUI_URL", "").rstrip("/")
if not base_url.startswith("https://"):
    raise SystemExit("Set WEBUI_URL to the HTTPS origin used by clients.")

os.environ["FROM_INIT_PY"] = "true"
os.environ["WEBUI_URL"] = base_url
os.environ["CORS_ALLOW_ORIGIN"] = os.environ.get("CORS_ALLOW_ORIGIN", base_url)

import open_webui.main  # noqa: F401
import uvicorn
from open_webui.env import UVICORN_WORKERS, UVICORN_WS_PER_MESSAGE_DEFLATE

script_dir = Path(__file__).resolve().parent

# Optional Carbono theme (see docs/TEMA-CARBONO.md). While temas/carbono/ACTIVO exists,
# /static/custom.css and /static/loader.js are served from temas/carbono/ (outside
# site-packages, so pip upgrades keep it). WEBUI_THEME_NAME optionally renames the app.
from starlette.responses import Response

tema_dir = script_dir / "temas" / "carbono"
tema_archivos = {
    "/static/custom.css": ("carbono.css", "text/css"),
    "/static/loader.js": ("carbono.js", "text/javascript"),
}
tema_nombre = os.environ.get("WEBUI_THEME_NAME")
nombre_original = open_webui.main.app.state.WEBUI_NAME


@open_webui.main.app.middleware("http")
async def tema_carbono(request, call_next):
    activo = (tema_dir / "ACTIVO").exists()
    if tema_nombre:
        open_webui.main.app.state.WEBUI_NAME = tema_nombre if activo else nombre_original
    if request.url.path not in tema_archivos:
        return await call_next(request)
    nombre, media_type = tema_archivos[request.url.path]
    archivo = tema_dir / nombre
    body = archivo.read_bytes() if activo and archivo.is_file() else b""
    return Response(body, media_type=media_type, headers={"Cache-Control": "no-cache"})


tls_dir = script_dir / "tls"
cert_file = tls_dir / "server.crt"
key_file = tls_dir / "server.key"
if not cert_file.is_file() or not key_file.is_file():
    raise SystemExit("Place the local server.crt and server.key under tls/.")

port = int(os.environ.get("WEBUI_PORT", urlparse(base_url).port or 8081))
uvicorn.run(
    "open_webui.main:app",
    host=os.environ.get("WEBUI_HOST", "0.0.0.0"),
    port=port,
    forwarded_allow_ips=os.getenv("FORWARDED_ALLOW_IPS", "*"),
    workers=UVICORN_WORKERS,
    ws_per_message_deflate=UVICORN_WS_PER_MESSAGE_DEFLATE,
    loop="none" if sys.platform == "win32" else "auto",
    ssl_certfile=str(cert_file),
    ssl_keyfile=str(key_file),
)
