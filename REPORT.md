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

### Flujo de túnel playit.gg (modelo secret_key)

playit v1.0.x no imprime claim en stdout — el daemon espera un
`secret_key` en `~/.config/playit_gg/playit.toml` antes de conectar con
la API de playit.gg. Por eso el scraping del log está obsoleto. El
flujo actual:

1. El usuario crea una cuenta en playit.gg y genera un Agent en
   `playit.gg/account/agents`.
2. En Ajustes → Túnel playit.gg pega el `secret_key` que muestra el
   dashboard (formato `playit_<38-44 base64url>`).
3. El script lo guarda en `$HOME/.config/playit_gg/playit.toml` con
   permisos 600 y levanta el daemon: `playitd <flags> >> tunnel.log`.
4. El usuario entra a `playit.gg/account/tunnels` y crea un Tunnel
   apuntando al puerto del servidor (`server-port`, default 25565). El
   daemon publica la dirección y la app la lee de `state.json`.

Sin `playit-cli` instalado (el binario es glibc y no funciona en
Termux/Android sin parcheo), este es el camino soportado. El secret
nunca sale de `playit.toml` ni se muestra en la UI.

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
