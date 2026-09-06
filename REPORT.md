# Informe de implementación

## Implementado

- Refactor headless de Termux en `termux/mc_manager.sh`.
- Estado JSON atómico con el esquema requerido.
- Instalación Paper/Fabric/Forge/NeoForge en Termux.
- Sesión tmux, pipe de consola, stop graceful, backups, mods y playit.
- Tests headless aislados en `tests/run_mc_manager_tests.sh`.
- Proyecto Android Kotlin de un módulo, Views clásicas, sin Compose/WebView/JavaScript.
- Intentos Termux, pantalla de configuración, creación, consola, mods, túnel, backup y borrado confirmado.
- Workflow de GitHub Actions en `.github/workflows/android.yml`.

### Flujo de túnel playit.gg (modelo claim vía playit-cli)

playit v1.0.x no imprime claim en stdout: el daemon arranca y espera el
secreto por IPC ("Waiting for frontend secret provisioning"). El paquete
TUR instala `playit-cli` junto a `playitd`, y ese CLI es el frontend. El
flujo actual (sin pegar nada a mano):

1. `playit-start` sin vínculo → genera código+URL frescos
   (`playit-cli claim generate` / `claim url`) y los deja en
   `state.playit.claim_url` con `needs_claim=true`. La app muestra la URL
   al instante (~1.5s por el watcher).
2. Tras una espera de cortesía (`MC_PLAYIT_CLAIM_DELAY`, 20s por defecto)
   para que el usuario abra la página, el propio `playit-start` lanza el
   exchange solo: **la página del claim solo detecta al agente cuando
   `exchange` está corriendo** ("checking every 3 seconds" = espera del
   lado servidor). Sin exchange en curso no hay nada que aprobar.
3. El usuario aprueba en el navegador. Eso crea/vincula el agent en su
   cuenta: **el claim ES lo que crea el agent**, no hay "Create Agent"
   previo en el dashboard.
4. `playit-exchange` = `playit-cli claim exchange --wait 75 <code>`
   (lock mkdir anti-doble-ejecución, staleness 150s). El CLI **imprime el
   secreto por stdout al aprobarse**: se captura a fichero privado
   (`$HOME/.playit-exchange.out`, jamás `$SHARED`), se extrae (última
   línea sin espacios, no-URL, ≥32 chars), se escribe
   `secret_key = "…"` en `$HOME/.config/playit_gg/playit.toml` (modo 600,
   sobrescribe), se tritura la captura y se **relanza el daemon con
   `--secret-path <toml>`** (flag verificado en `playitd --help` 1.0.6 del
   dispositivo). Sin línea candidata → error, nunca falso vinculado.
   Luego espera la dirección publicada. El botón manual "Confirmar
   vinculación" (con la página abierta) ejecuta lo mismo sin espera.
5. El usuario crea un Tunnel en `playit.gg/account/tunnels` apuntando al
   puerto del servidor (`server-port`, default 25565). El daemon publica
   la dirección y la app la lee de `state.json`.
6. `playit-unlink` mata la sesión y corre `playit-cli reset` para poder
   reclamar de cero.

Los códigos caducan: cada `playit-start` sin vínculo genera uno nuevo.

> **Nota de seguridad (fuga histórica):** las builds ≤0.16 redirigían el
> stdout del exchange a `install.log` (almacenamiento compartido), por lo
> que cualquier secreto generado con ellas puede seguir ahí en claro.
> `playit-claim` purga automáticamente líneas `^[0-9a-fA-F]{64,}$` del log
> (ninguna línea legítima tiene esa forma). Tras actualizar: borrar
> `MCPanel/install.log`, `Desvincular` y reclamar de cero para jubilar el
> secreto filtrado.

## Dependencias declaradas

- `androidx.core:core-ktx:1.13.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- Android Gradle Plugin `8.5.2`
- Kotlin plugin `2.0.21`

## Validado en este entorno

- `bash -n termux/mc_manager.sh`
- `bash -n termux/bootstrap.sh`
- `bash tests/run_mc_manager_tests.sh`
- Tests F0: todos pasan.
- No está disponible `gradlew` ni un SDK Android local; no se pudo medir el APK.

## Pendiente de validar en GitHub Actions/dispositivo

- Compilación real del módulo Android.
- Tamaño del APK release.
- Instalación de Termux y permiso `allow-external-apps`.
- Descargas reales y APIs oficiales.
- Paper/Fabric punta a punta.
- Forge/NeoForge con sus instaladores actuales.
- Latencia de consola inferior a dos segundos.
- Doze, wake lock y procesos huérfanos en dispositivos reales.
- Playit claim y navegación externa.

## Seguridad

El token personal de GitHub proporcionado en el chat no se ha usado ni almacenado. Debe revocarse porque quedó expuesto.
