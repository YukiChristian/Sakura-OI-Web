# Consejo: usar Sakura fuera de casa con Tailscale

Si tu proveedor de internet usa CGNAT (no tienes IP pública), no puedes abrir puertos en el router y el teléfono solo alcanza Open WebUI dentro de tu Wi-Fi. La solución es un túnel: el PC abre una conexión hacia afuera y el teléfono entra por ahí. Esta guía usa [Tailscale](https://tailscale.com).

En los ejemplos, `https://192.168.1.50:8081` es la dirección local de Open WebUI y `https://<tu-pc>.<tu-tailnet>.ts.net` es la dirección que te asigna Tailscale. Cambia ambas por las tuyas y no publiques tu dirección real.

## Dos formas de usarlo

| | Tailscale como VPN | Tailscale Funnel |
|---|---|---|
| Qué necesita el teléfono | La app Tailscale encendida | Nada, solo la URL |
| Quién puede llegar al login | Solo tus dispositivos | Cualquiera que tenga la URL |
| Certificado | El tuyo (CA local) o `tailscale serve` | Válido de Tailscale, sin CA local |
| Inconveniente | Android admite una sola VPN a la vez | Open WebUI queda expuesto a internet |

- **VPN:** solo el tráfico hacia tus equipos (`100.x.x.x`) pasa por la VPN; el resto sale normal. Gasta poca batería y puede quedarse siempre activa en **Ajustes → Red → VPN → Tailscale → VPN siempre activa**.
- **Funnel:** es gratis en el plan Personal (revisa [los planes](https://tailscale.com/pricing)). Solo publica por los puertos 443, 8443 o 10000, siempre en un dominio `ts.net` y con un límite de ancho de banda no publicado; para chatear sobra.

El resto de la guía usa Funnel.

## Publicar Open WebUI con Funnel

1. Instala Tailscale en el PC que ejecuta Open WebUI e inicia sesión.
2. Publica el servidor. Como Open WebUI ya usa HTTPS con un certificado de CA local, el prefijo `https+insecure://` indica a Tailscale que se conecte sin validar ese certificado:

   ```powershell
   tailscale funnel --bg https+insecure://localhost:8081
   ```

   Si Open WebUI corre en otro equipo de la red, usa su IP en lugar de `localhost`. La primera vez aparece un enlace para habilitar HTTPS y Funnel en tu tailnet; se aprueba una sola vez.

3. Comprueba que quedó publicado:

   ```powershell
   tailscale funnel status
   ```

   ```
   https://<tu-pc>.<tu-tailnet>.ts.net (Funnel on)
   |-- / proxy https+insecure://localhost:8081
   ```

El teléfono ve el certificado público de Tailscale, así que no aparece ningún aviso de certificado. Para apagarlo: `tailscale funnel --https=443 off`.

## Agregar el origen de Tailscale a CORS

Si la página carga por Tailscale pero **las respuestas del modelo no aparecen** (en la red local sí llegan), Open WebUI está rechazando el websocket con el error `... is not an accepted origin.` Agrega la dirección de Tailscale a `CORS_ALLOW_ORIGIN`, separada por `;`, y reinicia Open WebUI:

```powershell
$env:WEBUI_URL = 'https://192.168.1.50:8081'
$env:CORS_ALLOW_ORIGIN = 'https://192.168.1.50:8081;https://<tu-pc>.<tu-tailnet>.ts.net'
```

[server/run_open_webui_https.py](../server/run_open_webui_https.py) respeta el valor de `CORS_ALLOW_ORIGIN` si ya está definido. Si usas tu propio lanzador, verifica que no lo sobrescriba con un solo origen.

Para comprobarlo desde el PC. Si el origen está aceptado responde `"Invalid transport"` (normal: Open WebUI solo usa websocket); si no, responde `"... is not an accepted origin."`:

```powershell
curl.exe -sk -H "Origin: https://<tu-pc>.<tu-tailnet>.ts.net" "https://localhost:8081/ws/socket.io/?EIO=4&transport=polling"
```

## Compilar la app con la dirección de Tailscale

La app abre una sola dirección, `SAKURA_WEBUI_URL`. Para usarla dentro y fuera de casa, compílala con la de Funnel (sin puerto, va por 443):

```powershell
.\gradlew.bat assembleDebug -PSAKURA_WEBUI_URL="https://<tu-pc>.<tu-tailnet>.ts.net/"
```

Esa misma dirección se usa para el permiso del micrófono y las notificaciones, por eso debe coincidir con la que abre la app. Al ser un certificado público no hace falta agregar la CA local al build ([HTTPS.md](HTTPS.md)).

## Que siga funcionando después de reiniciar

Con `--bg` la configuración de Funnel queda guardada y vuelve sola cuando arranca el servicio de Tailscale. Además:

- **Open WebUI también debe arrancar solo.** Si no está corriendo, la URL abre pero devuelve error 502.
- **Desactiva la expiración de la clave.** Por defecto Tailscale pide iniciar sesión de nuevo cada 180 días y Funnel se cae. En la [consola de máquinas](https://login.tailscale.com/admin/machines) elige tu PC → **⋯** → **Disable key expiry**.
- **Opcional:** en Windows, clic derecho en el icono de Tailscale → **Preferences → Run unattended**, para que funcione sin iniciar sesión en Windows.

Después de reiniciar, `tailscale funnel status` debe seguir mostrando `Funnel on`.

## Seguridad

Con Funnel, cualquiera que tenga la URL llega a la pantalla de inicio de sesión:

- Usa una contraseña fuerte.
- Desactiva el registro de usuarios nuevos: en **Ajustes de administración → General**, apaga **Enable New Sign Ups** (habilitar nuevos registros).
- No publiques tu dirección `ts.net` en capturas, issues ni en este repositorio.

## Problemas comunes

| Síntoma | Causa probable |
|---|---|
| La página carga, pero no llegan las respuestas | Falta el origen de Tailscale en `CORS_ALLOW_ORIGIN` |
| Error 502 | Open WebUI no está corriendo, o el puerto o el prefijo (`https+insecure://`) no coinciden |
| La URL dejó de funcionar tras meses | La clave del equipo expiró en Tailscale |
| La app muestra "No hay conexión con Sakura" | La app se compiló con otra `SAKURA_WEBUI_URL` |
