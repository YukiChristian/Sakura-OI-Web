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
