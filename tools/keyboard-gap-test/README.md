# Prueba del espacio blanco del teclado

Graba la pantalla del emulador mientras abre y cierra el teclado en los casos que antes dejaban un espacio blanco, y revisa cada cuadro buscando una franja blanca plana a todo lo ancho.

Casos, en modo claro y oscuro:

- Abrir y cerrar el teclado.
- Ir al inicio con el teclado abierto y volver.
- Ir a recientes con el teclado abierto y volver.
- **Nuevo chat** desde el widget (abre solo el teclado) y cerrarlo con Atrás.
- Abrir y cerrar dos veces seguidas.

## Cómo correrla

Con el emulador encendido y una compilación de **depuración** instalada (la de publicación no permite leer la página), con la sesión iniciada:

```bash
bash tools/keyboard-gap-test/run.sh both
```

`light` o `dark` corren un solo modo. Cada caso imprime `ok` o `FALLA`; los cuadros con el problema quedan en `tools/keyboard-gap-test/out/frames`.

Necesita Python con `opencv-python`, `numpy` y `websocket-client`.

Córrela antes de publicar una versión y después de cambiar el tema de la web (CSS o JS).
