# MCPanel

Panel Android minimalista para instalar y controlar servidores Minecraft cuyo motor corre en Termux.

## Estado

F0 está implementada y probada en host: `termux/mc_manager.sh`, estado atómico, inbox, tmux, consola, backups, mods y playit.
La app Android contiene el esqueleto funcional de F1 y controles básicos de F2/F3/F4. No se pudo compilar ni probar en un dispositivo Android dentro de este entorno.

## Instalación

1. Instala Termux desde F-Droid o GitHub Releases. No uses la versión de Play Store.
2. Copia `termux/mc_manager.sh` y `termux/bootstrap.sh` a Termux, o usa el proyecto desde almacenamiento compartido.
3. Ejecuta `bash termux/bootstrap.sh` en Termux.
4. Ejecuta el comando que muestra el script para permitir aplicaciones externas:

```bash
echo allow-external-apps=true >> ~/.termux/termux.properties
```

5. Reinicia Termux y concede a MCPanel el permiso de gestión de todos los archivos.
6. Compila e instala el APK desde Android Studio.

## Arquitectura

La app no abre sockets, no inicia servidor HTTP y no usa WebView, JavaScript ni Compose. Descargas y consultas se realizan desde la app; la ejecución ocurre dentro del entorno Linux embebido (bootstrap oficial de Termux, GPLv3, incluido en el APK) en el directorio privado de la app. La app lanza `mc_manager.sh` mediante `ServerService` (servicio foreground propio) y lee `/sdcard/MCPanel/state.json`, `console.log` e `install.log`. No requiere la app de Termux.

El script utiliza `~/mcserver`, nunca `/sdcard`, para ejecutar Java. Los artifacts descargados por la app entran en `MCPanel/inbox/`.

## Intent

La ejecución es interna (sin intents externos):

- `ServerService` lanza `<prefix>/bin/bash mc_manager.sh <subcomando> [args]` con argv array y entorno controlado.
- `applicationId = io.mcpanel`: el bootstrap incluye rutas de 10 caracteres (`com.termux`) parcheadas byte a byte a `io.mcpanel` (misma longitud) durante la extracción. Cambiar el applicationId rompe los binarios.
- PREFIX: `/data/data/io.mcpanel/files/usr` · HOME: `/data/data/io.mcpanel/files/home`

## Pruebas del script

```bash
bash tests/run_mc_manager_tests.sh
bash -n termux/mc_manager.sh
```

## Limitaciones

- El APK, el comportamiento de permisos y la integración real con Termux requieren verificación en Android físico.
- Doze y restricciones de batería pueden detener procesos largos; usa `termux-wake-lock` durante el servidor.
- El servidor necesita RAM suficiente para la versión, loader y mods seleccionados.
- Minecraft anterior a 1.17 no está soportado.
- `online-mode=false` se conserva del script original y debe cambiarse si se necesita autenticación oficial.
- `MANAGE_EXTERNAL_STORAGE` es necesario para leer los archivos compartidos definidos por la arquitectura.

## Auditoría pendiente

Ejecutar en un entorno Android con SDK/Gradle:

```bash
./gradlew assembleRelease
du -h app/build/outputs/apk/release/*.apk
```

Revisar dependencias, instalar el APK, ejecutar bootstrap, comprobar instalación Paper/Fabric, stop graceful, consola menor a dos segundos, mods, playit y backup.
