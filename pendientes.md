# Pendientes — MCPanel

> Documento vivo de bugs conocidos y mejoras pendientes. Se actualiza tras cada
> ronda de pruebas en dispositivo. Las secciones marcadas con ✅ están
> cerradas en el commit/versión indicado; las `[ ]` siguen abiertas.

## Estado 0.19

B1–B4 cerrados (ver ✅ abajo). Suite 120/120 en verde, gemelos del script
sincronizados (`diff -q` vacío), `bash -n` OK en ambas copias.

## Bugs cerrados en 0.19 (post 0.18) ✅

### B1 — Inicio se queda en "Cargando" aunque Ajustes diga "Vinculado"
- **Síntoma:** Ajustes → Túnel playit.gg muestra "Vinculado" (y `playit.secret = true`
  en `state.json`), pero Inicio nunca termina de arrancar — la fila Dirección
  queda en estado "Cargando"/"Conectando…" aunque el túnel está vivo en el
  dashboard.
- **Causa raíz:** `watchMerged` no incluye `secret` ni la presencia de
  `address` en su firma (L591), así que un digest que solo actualiza esos
  campos no dispara re-render. Además, en Inicio, la rama `pRunning`
  (L548-556) es la única que muestra el spinner "Conectando…", y como el
  daemon local no se queda corriendo entre sesiones, `pRunning=false` →
  entra en la rama `else` con "Iniciar túnel", que al tap solo regenera
  claim (porque ya está vinculado) y vuelve a la rama `needsClaim`. Doble
  apariencia de "no ha pasado nada".
- **Fix propuesto:** (a) ampliar la firma de `watchMerged` con `secret` y
  presencia de `address`; (b) nueva rama en Inicio para "vinculado + sin
  dirección + daemon local no corriendo": muestra "Vinculado, sin dirección"
  en plano (sin spinner), con un solo fantasma "Escribir dirección" si
  aún no hay address; (c) quitar la rama "Conectando…" engañosa cuando
  no hay túnel local activo — la percibe el usuario como cuelgue.

### B2 — "Escribir dirección" rechaza hosts sin puerto
- **Síntoma:** el diálogo exige `host:puerto` (regex en `openAddressDialog`).
  Los tunnels de playit.gg vienen en `*.tun.ply.gg` sin puerto, que es
  rechazado en silencio. Decisión del usuario: **mantener el botón, hacer
  el parsing más flexible**.
- **Causa:** la regex `[^\\s:]+:[0-9]+` exige puerto. Falla con
  `percussion-refer.tun.ply.gg` (un solo token, sin `:`).
- **Fix propuesto:** aceptar `host` solo, `host:puerto`, y `host:puerto:puerto`
  (por si copy-paste trae un sufijo sobrante). Validar host como FQDN o
  IPv4 (no exigirlo como URL completa, no es un `https://…`):
  - `^[A-Za-z0-9._-]+(:[0-9]+)?$` para `host` o `host:puerto`.
  - Strip silencioso de path/prefijo si el usuario copia `https://algo` (recortar
    a partir de `://` y quedarnos con `host[:puerto]`).
  - Validar puerto 1-65535 si está presente; `default 25565` si falta.
  - Mensaje de error explícito: "host:puerto (puerto opcional)".
- **Pendiente UX:** el diálogo es de Inicio cuando `linked && pAddr == null`
  y desaparece si el botón desaparece (ver B1: si tras la corrección la
  dirección aparece sola vía `playit-cli status`, el caso sin-address es
  marginal). Mantener por ahora: tu comentario "no quitar" lo deja
  dentro.

### B3 — Falso error "playit vinculado pero sin dirección" persistente
- **Síntoma:** el mensaje aparece en Inicio bajo "Fabric 1.21.1" aunque
  el túnel está vivo y vinculado. `state.last_error` arrastra el error
  histórico.
- **Causa:** `playit_do_exchange` (`mc_manager.sh`) llama
  `state_set_error "playit vinculado pero sin dirección..."` cuando
  rc=0 (vínculo OK) pero el digest no encuentra address. El digest
  posterior **no borra** `last_error` cuando aparece dirección.
- **Fix propuesto:** en `playit_do_exchange` separar éxito-en-vinculo y
  éxito-con-dirección: si rc=0 pero `playit_wait_address` falla, usar
  `state_set_warn` (o `last_action=playit-exchange` con `last_error=null`)
  y un `last_action` distinto como "playit-vinculado". Más importante: en
  `playit_digest`, dentro del bloque de actualización, si
  `RUN=true && ADDR != ""` entonces `upd="$upd .last_error = null"` para
  que la UI no arrastre el mensaje obsoleto. Con esta pieza, B3 se
  resuelve solo sin tocar `openAddressDialog`.

### B4 — Botón "Ver registro" fantasma en Inicio
- **Síntoma:** en Inicio, debajo de "Fabric 1.21.1", aparece "Ver registro"
  (abre `lastRunLog`). Con el servidor apagado el log está vacío; con
  servidor encendido el detalle vive en Consola, no aquí. El botón añade
  ruido sin aportar.
- **Causa:** L510-512 de `MainActivity.kt`, dentro del bloque
  `if (err.isNotEmpty())`. El concepto "ver registro de error" tiene
  sentido en Consola (donde hay log de Minecraft) pero no en Inicio
  (donde solo hay metadata de playit).
- **Fix propuesto:** borrar el bloque completo. Si quieres diagnóstico
  del estado de playit, sigue disponible el botón "Diagnóstico" en
  Ajustes (que vuelca `playit-debug.log` redactado). Si quieres ver
  errores del último run del servidor, ve a Consola.

## Plan de ejecución (ejecutado en 0.19) ✅

1. ✅ App: "Ver registro" de Inicio borrado (B4).
2. ✅ App: B2 (parsing flexible en `openAddressDialog`: host solo,
   host:puerto, URLs pegadas recortadas, puerto 1-65535, sufijo sobrante
   `host:p:p` tolerado) y B1 (nueva rama "Vinculado, sin dirección" sin
   spinner + `watchMerged` ampliado con `secret`).
3. ✅ Script: B3 (`playit_do_exchange` termina en `playit-vinculado` con
   `last_error=null` y rc=2 cuando no hay dirección; los callers tratan
   rc=2 como éxito; `playit_digest` limpia `last_error` cuando
   `RUN=true && ADDR != ""`).
4. ✅ Tests: `playit-address` acepta host sin puerto y URLs pegadas;
   rechaza puertos fuera de rango; nuevos casos B3 (sin falso error al
   vincular sin dirección + digest limpia `last_error`).
5. ✅ Suite verde (120/120), push, APK 0.19 vía CI.

## Convenciones para el próximo agente

- Una sola causa raíz común a B1+B3 (cerrada): el digest no distinguía
  "túnel vinculado, dirección aún no propagada" de "todavía cargando".
- El flujo playit ahora tiene un estado explícito "vinculado sin
  dirección": el script lo marca con `last_action=playit-vinculado` y
  sin `last_error`; la UI lo muestra en plano con el escape manual
  "Escribir dirección" (que ahora acepta host sin puerto).
- Tras cualquier cambio al script: bash -n ambas copias, diff -q
  termux/mc_manager.sh app/src/main/res/raw/mc_manager, suite en verde
  (120/120 actual).
