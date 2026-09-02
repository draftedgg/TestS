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
