"""Cambia el favicon, el logo y los iconos de app de una instalacion de Open WebUI.

Ejecutalo con el Python del entorno de Open WebUI (ya incluye Pillow):

    .\\Scripts\\python.exe aplicar_icono.py --logo C:\\ruta\\a\\mi-logo.png
    .\\Scripts\\python.exe aplicar_icono.py --restaurar

Al arrancar, Open WebUI borra open_webui/static y lo vuelve a llenar desde
open_webui/frontend/static, por eso los iconos se escriben en frontend/. Tambien
se copian a static/ para que el servidor en marcha los sirva sin reiniciar.
Una actualizacion de open-webui restaura los iconos originales: vuelve a
ejecutar este script despues de actualizar.
"""
import argparse
import base64
import importlib.util
import io
import shutil
import sys
from pathlib import Path

from PIL import Image

# archivo -> (tamano, fondo); fondo None = transparente
PNG_TARGETS = {
    "favicon.png": (500, None),
    "favicon-96x96.png": (96, None),
    "logo.png": (500, None),
    "apple-touch-icon.png": (180, (255, 255, 255)),
    "web-app-manifest-192x192.png": (192, (255, 255, 255)),
    "web-app-manifest-512x512.png": (512, (255, 255, 255)),
    "splash.png": (500, None),
    "splash-dark.png": (500, None),
}
STATIC_FILES = [*PNG_TARGETS, "favicon.ico", "favicon.svg"]
BACKUP_DIR_NAME = ".iconos-originales"


def find_open_webui() -> Path:
    # find_spec no importa el paquete; importarlo arrancaria toda la configuracion de Open WebUI
    spec = importlib.util.find_spec("open_webui")
    if spec is None or not spec.submodule_search_locations:
        sys.exit("No se encontro open_webui. Ejecuta el script con el Python de Open WebUI o usa --open-webui.")
    return Path(next(iter(spec.submodule_search_locations)))


def square(img: Image.Image, size: int, background=None, padding=0.0) -> Image.Image:
    """Centra el logo en un lienzo cuadrado sin deformarlo."""
    img = img.convert("RGBA")
    inner = round(size * (1 - 2 * padding))
    scale = inner / max(img.size)
    resized = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(resized, ((size - resized.width) // 2, (size - resized.height) // 2), resized)
    if background is None:
        return canvas
    solid = Image.new("RGBA", (size, size), background + (255,))
    solid.alpha_composite(canvas)
    return solid.convert("RGB")


def targets(package: Path):
    frontend = package / "frontend"
    files = [frontend / "static" / name for name in STATIC_FILES] + [frontend / "favicon.png"]
    return frontend, [f for f in files if f.exists()]


def backup_name(frontend: Path, f: Path) -> str:
    return str(f.relative_to(frontend)).replace("\\", "_").replace("/", "_")


def sync_live_static(package: Path):
    live = package / "static"
    if live.is_dir():
        for name in STATIC_FILES:
            src = package / "frontend" / "static" / name
            if src.exists():
                shutil.copyfile(src, live / name)


def apply(package: Path, logo_path: Path):
    frontend, files = targets(package)
    static = frontend / "static"
    backup = frontend / BACKUP_DIR_NAME
    if not backup.exists():
        backup.mkdir()
        for f in files:
            shutil.copy2(f, backup / backup_name(frontend, f))
        print(f"Originales guardados en {backup}")

    logo = Image.open(logo_path)
    for name, (size, background) in PNG_TARGETS.items():
        # los iconos de app con fondo llevan margen para que Android/iOS no recorten el logo
        padding = 0.08 if background else 0.0
        square(logo, size, background, padding).save(static / name)
        print("ok", name)

    square(logo, 256).save(static / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (256, 256)])
    print("ok favicon.ico")

    buf = io.BytesIO()
    square(logo, 256).save(buf, "PNG")
    data = base64.b64encode(buf.getvalue()).decode()
    (static / "favicon.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">'
        f'<image width="256" height="256" href="data:image/png;base64,{data}"/></svg>',
        encoding="utf-8",
    )
    print("ok favicon.svg")

    shutil.copyfile(static / "favicon.png", frontend / "favicon.png")
    print("ok favicon.png (raiz)")


def restore(package: Path):
    frontend, files = targets(package)
    backup = frontend / BACKUP_DIR_NAME
    if not backup.exists():
        sys.exit(f"No hay originales guardados en {backup}.")
    for f in files:
        saved = backup / backup_name(frontend, f)
        if saved.exists():
            shutil.copy2(saved, f)
            print("restaurado", f.relative_to(frontend))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--logo", type=Path, help="imagen cuadrada, PNG con fondo transparente de 512x512 o mas")
    group.add_argument("--restaurar", action="store_true", help="vuelve a los iconos originales de Open WebUI")
    parser.add_argument("--open-webui", type=Path, help="carpeta del paquete open_webui (se detecta sola)")
    args = parser.parse_args()

    package = args.open_webui or find_open_webui()
    if not (package / "frontend" / "static").is_dir():
        sys.exit(f"{package} no parece el paquete open_webui (falta frontend/static).")

    if args.restaurar:
        restore(package)
    else:
        if not args.logo.is_file():
            sys.exit(f"No existe el logo {args.logo}.")
        apply(package, args.logo)
    sync_live_static(package)
    print("\nListo. Recarga con Ctrl+F5; si instalaste Open WebUI como app, reinstalala para ver el icono nuevo.")


if __name__ == "__main__":
    main()
