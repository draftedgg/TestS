# Pendientes — MCPanel (post-análisis 0.12)

> Creado 2026-09-05 tras el análisis a fondo del proyecto. Referencias `archivo:linea` del estado actual.
> Verificado en host: `bash -n` OK y `tests/run_mc_manager_tests.sh` **pasa completo (0 fallos)**.

## 1. Seguridad (urgente, no es código)
- [ ] **Revocar el token personal de GitHub** expuesto en un chat anterior. REPORT.md ya lo documenta pero sigue sin evidencia de revocación. → **ACCIÓN DEL USUARIO** (no la puedo ejecutar yo sin credenciales).

## 2. Bugs a corregir (prioridad alta → baja)

### B1 — La RAM configurada nunca se aplica 🔴
`detect_ram()` (`termux/mc_manager.sh:254`) asigna RAM_MIN/RAM_MAX **incondicionalmente**, pisando todo:
- En `cmd_install` (`:341`) se llama después de parsear `--ram-min/--ram-max` → los flags se pierden.
- En `cmd_start` (`:512`) se llama **antes** de leer state.json (`:513`) → el fallback nunca dispara.

Resultado: el preset por RAM física siempre gana; el botón "Cambiar RAM" (ram-set) es cosmético aunque la UI promete "Aplica al reiniciar".
**Fix:** invertir prioridad → flags > state.json > preset. Ej.: al inicio de `detect_ram`, `[ -n "$RAM_MIN" ] && [ -n "$RAM_MAX" ] && return`.

### B2 — Carrera descarga↔instalación: jar parcial puede ser `server.jar` 🟠
`Apis.downloadToFile` escribe directo en `inbox/` sin tmp+rename (`Apis.kt:41`). `startInstall` lanza la descarga y a continuación `install` (`MainActivity.kt:1258-1274`); el script acepta el jar con `[ -s ]` (`mc_manager.sh:394`) → un jar a medio descargar pasa como válido. En forge/neoforge el `mv` del inbox (`mc_manager.sh:457`) puede pisar un installer ya bajado por el script.
**Fix:** descargar a `inbox/NAME.part` + rename atómico al terminar (la validación "PK" existe pero llega tarde).

### B3 — `prop server-port X` no actualiza `state.port` 🟠
`cmd_prop` (`mc_manager.sh:628`) no toca state.port; Inicio muestra `lanIP:$port` con el default 25565 (`MainActivity.kt:414`).
**Fix:** en `cmd_prop`, si `K=server-port`, hacer `write_state ".port = $V"`.

### B4 — `installScript` trunca el script en ejecución 🟡
`Embed.runManager` copia `res/raw/mc_manager` sobre el archivo en ejecución en cada llamada (`Embed.kt:282`, truncate). KeepAlive corre `status` cada 12 s; si hay un comando largo en curso, bash puede leer el archivo mezclado.
**Fix:** escribir a `mc_manager.sh.tmp` + rename atómico.

### Menores
- [x] `STATE_TMP` (`mc_manager.sh:29`) es variable muerta — eliminado.
- [x] `backup` sin `save-off`/`save-all` → mundo puede quedar inconsistente con server encendido (`mc_manager.sh:586`) — `cmd_backup` envía `save-all flush` por tmux antes de comprimir.
- [x] WakeLock de descargas limitado a 15 min (`DownloadService.kt:40`) — jar grande en red lenta sigue sin lock — subido a 30 min.
- [x] `cmd_start`: salida de java ya redirigida a console.log Y `pipe-pane` al mismo archivo (`mc_manager.sh:535`) — redundante, posible duplicado — quitado pipe-pane.
- [x] `mod-install` borra el jar inválido del inbox (`mc_manager.sh:609`) — mejor conservarlo y solo avisar — renombra a `.invalid`.
- [x] `clearError()` de la app reescribe state.json compitiendo con el script (`MainActivity.kt:327`) — frágil pero autocorrige.

### Túnel playit v1.0.x — flujo nuevo (commit posterior)
- [x] Scraping de stdout obsoleto: el daemon solo espera `secret_key` en `playit.toml` (`mc_manager.sh:cmd_playit_start`).
- [x] Nuevo subcomando `playit-secret <key>` que guarda en `$HOME/.config/playit_gg/playit.toml` con permisos 600.
- [x] Nuevo subcomando `playit-secret-clear` para reset.
- [x] Estado `state.json`: nueva clave `playit.secret = true|false` (sin guardar el valor del secret).
- [x] App: sección "Túnel playit.gg" en Ajustes con diálogo de pegado del secret.
- [x] App: botón "Iniciar túnel playit.gg" en Inicio detecta falta de secret y abre el diálogo en vez de fallar ciegamente.
- [x] Tests: 5 nuevos (sin secret → error, guardar → state + toml + 600, inválido → rechazado, con secret → arranca, clear → elimina toml).

## 3. Documentación (drift con el código)
- [ ] README/REPORT aún describen el **modo Termux externo** (intents `com.termux.RUN_COMMAND`, `allow-external-apps=true`); la app 0.12 es 100 % embebida (`Embed.runManager`, ProcessBuilder interno).
- [ ] `termux/bootstrap.sh` y `strings.xml` ("Termux no está instalado") son restos legacy — limpiar o marcar como legacy.
- [ ] REPORT.md: anotar que el WIP "prefijo embebido" ya está materializado (APK 0.12 construido con firma estable en CI).

## 4. Validación pendiente (la gran asignatura)
- [ ] **Checklist en dispositivo real** (`tests/manual_checklist.md`, 25 ítems): nada probado aún en hardware. Críticos: bootstrap extracción/exec, dpkg shim con openjdk real, playit claim, wakelock con pantalla apagada, instalación punta a punta.

## 5. Mejoras opcionales (backlog)
- [ ] Verificación de checksum en descargas (comparar con hash de la API) — hoy solo magic bytes "PK".
- [ ] Tests: caso con `MC_EMBEDDED=1` ya existe; añadir uno para B1 (RAM flags vs preset) al arreglarlo.

---
**Orden sugerido al retomar:** 1 (token) → B1 → B2 → B3+B4 → docs → dispositivo.
