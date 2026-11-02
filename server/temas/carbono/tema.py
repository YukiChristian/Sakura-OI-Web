"""Activa o quita el tema Carbono de Open WebUI.

El tema vive en temas/carbono/ junto al lanzador (fuera de site-packages, asi que
pip install -U open-webui no lo borra). run_open_webui_https.py sirve carbono.css en
/static/custom.css y carbono.js en /static/loader.js mientras exista temas/carbono/ACTIVO.
No hace falta reiniciar Open WebUI: basta con recargar la pagina (Ctrl+F5).

Desde la carpeta de Open WebUI:

    .\\Scripts\\python.exe .\\temas\\carbono\\tema.py aplicar
    .\\Scripts\\python.exe .\\temas\\carbono\\tema.py quitar
    .\\Scripts\\python.exe .\\temas\\carbono\\tema.py estado
"""
import sys
from pathlib import Path

TEMA_DIR = Path(__file__).resolve().parent
CSS = TEMA_DIR / "carbono.css"
FLAG = TEMA_DIR / "ACTIVO"
LAUNCHER = TEMA_DIR.parent.parent / "run_open_webui_https.py"


def estado():
    activo = FLAG.exists()
    print("Tema Carbono:", "ACTIVO" if activo else "desactivado")
    if not LAUNCHER.is_file() or "tema_carbono" not in LAUNCHER.read_text(encoding="utf-8"):
        print("AVISO: run_open_webui_https.py no tiene el hook del tema; el CSS no se servira.")


def main():
    accion = sys.argv[1] if len(sys.argv) > 1 else "estado"
    if accion == "aplicar":
        if not CSS.is_file():
            sys.exit(f"No existe {CSS}")
        FLAG.touch()
    elif accion == "quitar":
        FLAG.unlink(missing_ok=True)
    elif accion != "estado":
        sys.exit("Uso: tema.py aplicar | quitar | estado")
    estado()
    if accion != "estado":
        print("Recarga Open WebUI en el navegador (Ctrl+F5) para ver el cambio.")


if __name__ == "__main__":
    main()
