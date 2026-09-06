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
   `state.playit.claim_url` con `needs_claim=true`. La app muestra la URL,
   `Abrir enlace` y `Ya lo aprobé, continuar`.
2. El usuario abre el enlace en el navegador (crea cuenta si no tiene) y
   aprueba. Eso crea/vincula el agent en su cuenta: **el claim ES lo que
   crea el agent**, no hay "Create Agent" previo en el dashboard.
3. `playit-exchange` corre `playit-cli claim exchange --wait 90 <code>`;
   el secreto viaja al daemon por IPC y **nunca se imprime, loguea ni
   guarda en state.json**. Luego espera la dirección publicada.
4. El usuario crea un Tunnel en `playit.gg/account/tunnels` apuntando al
   puerto del servidor (`server-port`, default 25565). El daemon publica
   la dirección y la app la lee de `state.json`.
5. `playit-unlink` mata la sesión y corre `playit-cli reset` para poder
   reclamar de cero.

Los códigos caducan: cada `playit-start` sin vínculo genera uno nuevo.

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
