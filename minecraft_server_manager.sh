#!/data/data/com.termux/files/usr/bin/bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║           MINECRAFT SERVER MANAGER · TERMUX EDITION                  ║
# ║     PaperMC · Fabric · Forge · NeoForge · playit.gg · Modrinth       ║
# ╚══════════════════════════════════════════════════════════════════════╝

# ─── COLORES Y SÍMBOLOS ──────────────────────────────────────────────
R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; C='\033[0;36m'
W='\033[1;37m'; DIM='\033[2m'; BOLD='\033[1m'; NC='\033[0m'

OK="${G}[OK]${NC}"; FAIL="${R}[ERROR]${NC}"; WARN="${Y}[WARN]${NC}"; INFO="${C}[INFO]${NC}"
ARROW="${C}>${NC}"

# ─── RUTAS Y VARIABLES ───────────────────────────────────────────────
SERVER_DIR="$HOME/minecraft_server"
SERVER_JAR="server.jar"
CONFIG_FILE="$HOME/.mc_server_config"
LOG_FILE="$HOME/.mc_installer.log"

VERSION=""; LOADER=""; RAM_MIN=""; RAM_MAX=""; TOTAL_RAM=0
PLAYIT_BIN=""; CF_KEY=""
TW=$(tput cols 2>/dev/null || echo 60)
[ "$TW" -lt 40 ] && TW=40; [ "$TW" -gt 80 ] && TW=80
_repeat() { printf '%*s' "$1" '' | tr ' ' "$2"; }

# ─── UTILIDADES DE UI LIMPIA ─────────────────────────────────────────
section() { echo ""; echo -e "${BOLD}${Y}=== $1 ===${NC}"; echo ""; }
divider() { echo -e "${DIM}$(_repeat $TW '─')${NC}"; }
log_ok()   { echo -e "  ${OK} $1"; echo "[OK]  $1" >> "$LOG_FILE"; }
log_fail() { echo -e "  ${FAIL} $1"; echo "[ERR] $1" >> "$LOG_FILE"; }
log_warn() { echo -e "  ${WARN} $1"; echo "[WRN] $1" >> "$LOG_FILE"; }
log_info() { echo -e "  ${INFO} $1"; echo "[INF] $1" >> "$LOG_FILE"; }
log_step() { echo -e "  ${ARROW} ${BOLD}$1${NC}"; echo "[...] $1" >> "$LOG_FILE"; }

spinner() {
    local pid=$1 msg="${2:-Procesando}"
    local frames=('⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏') i=0
    tput civis 2>/dev/null
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r  ${C}%s${NC} %s " "${frames[$((i % ${#frames[@]}))]}" "$msg"
        sleep 0.1; ((i++))
    done
    printf "\r%*s\r" "$TW" ""; tput cnorm 2>/dev/null
}

progress_bar() {
    local current=$1 total=$2 label="${3:-}"
    local pct=$(( current * 100 / total ))
    local filled=$(( current * (TW - 14) / total ))
    local empty=$(( (TW - 14) - filled ))
    printf "\r  ${C}[${G}%s${DIM}%s${C}]${NC} ${BOLD}%3d%%${NC} %s" \
        "$(_repeat $filled '█')" "$(_repeat $empty '░')" "$pct" "$label"
}

pause() { echo ""; echo -e "  ${DIM}Pulsa [Enter] para continuar...${NC}"; read -r; }
fatal() { echo -e "\n  ${FAIL} ${R}${BOLD}FATAL:${NC} ${R}$1${NC}\n  ${DIM}Log: $LOG_FILE${NC}\n"; exit 1; }
confirm() {
    local msg="$1" default="${2:-n}" hint
    [ "$default" = "s" ] && hint="[S/n]" || hint="[s/N]"
    echo -ne "  ${ARROW} $msg ${W}$hint${NC}: "; read -r resp
    resp="${resp:-$default}"; [[ "$resp" =~ ^[sS]$ ]]
}

# ─── SPLASH Y VERIFICACIONES ─────────────────────────────────────────
splash() {
    clear; echo -e "${G}"
    cat << 'ART'
  ███╗   ███╗██╗███╗   ██╗███████╗ ██████╗██████╗  █████╗ ███████╗████████╗
  ████╗ ████║██║████╗  ██║██╔════╝██╔════╝██╔══██╗██╔══██╗██╔════╝╚══██╔══╝
  ██╔████╔██║██║██╔██╗ ██║█████╗  ██║     ██████╔╝███████║█████╗     ██║
  ██║╚██╔╝██║██║██║╚██╗██║██╔══╝  ██║     ██╔══██╗██╔══██║██╔══╝     ██║
  ██║ ╚═╝ ██║██║██║ ╚████║███████╗╚██████╗██║  ██║██║  ██║██║        ██║
  ╚═╝     ╚═╝╚═╝╚═╝  ╚═══╝╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝        ╚═╝
ART
    echo -e "${NC}"
    echo -e "${BOLD}SERVER MANAGER · Termux Edition${NC}"
    echo -e "${DIM}PaperMC · Fabric · Forge · NeoForge · playit.gg${NC}"
    divider; sleep 0.5
}

verificar_conexion() {
    log_step "Verificando conexión..."
    if ! curl -s -m 8 https://api.papermc.io > /dev/null 2>&1; then
        fatal "Sin conexión a internet."
    fi
    log_ok "Conexión disponible"
}

# ─── DEPENDENCIAS (CRÍTICO: Instalar ANTES de usar jq) ───────────────
instalar_dependencias() {
    section "Dependencias del sistema"
    log_step "Instalando wget, curl, tmux, jq, unzip..."
    pkg install -y wget curl tmux dos2unix jq unzip >> "$LOG_FILE" 2>&1 &
    spinner $! "Descargando paquetes"; wait $!
    log_ok "Herramientas base instaladas"
}

instalar_java() {
    local MINOR PATCH
    MINOR=$(echo "$VERSION" | cut -d. -f2)
    PATCH=$(echo "$VERSION" | cut -d. -f3); PATCH=${PATCH:-0}
    if [ "$MINOR" -ge 21 ] || { [ "$MINOR" -eq 20 ] && [ "$PATCH" -ge 5 ]; }; then
        JAVA_PKG="openjdk-21"; else JAVA_PKG="openjdk-17"
    fi
    log_step "Instalando $JAVA_PKG..."
    pkg install -y "$JAVA_PKG" >> "$LOG_FILE" 2>&1 &
    spinner $! "Instalando Java"; wait $!; log_ok "Java listo"
}

# ─── RAM AUTOMÁTICA ──────────────────────────────────────────────────
get_ram() {
    TOTAL_RAM=$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')
    if   [ "$TOTAL_RAM" -ge 8192 ]; then RAM_MIN="1G";   RAM_MAX="4G";    RAM_TAG="${G}Excelente${NC}"
    elif [ "$TOTAL_RAM" -ge 6144 ]; then RAM_MIN="1G";   RAM_MAX="3G";    RAM_TAG="${G}Muy buena${NC}"
    elif [ "$TOTAL_RAM" -ge 4096 ]; then RAM_MIN="512M"; RAM_MAX="2G";    RAM_TAG="${Y}Buena${NC}"
    elif [ "$TOTAL_RAM" -ge 3072 ]; then RAM_MIN="512M"; RAM_MAX="1500M"; RAM_TAG="${Y}Suficiente${NC}"
    elif [ "$TOTAL_RAM" -ge 2048 ]; then RAM_MIN="256M"; RAM_MAX="1G";    RAM_TAG="${Y}Ajustada${NC}"
    else RAM_MIN="256M"; RAM_MAX="512M"; RAM_TAG="${R}Baja${NC}"; log_warn "Menos de 2 GB de RAM"
    fi
    echo ""
    echo -e "  ${BOLD}RAM del dispositivo:${NC} ${W}$TOTAL_RAM MB${NC}  (${RAM_TAG})"
    echo -e "  ${BOLD}Asignación:${NC} Mínima ${G}$RAM_MIN${NC} / Máxima ${G}$RAM_MAX${NC}"
    echo ""
}

# ─── SELECTORES ──────────────────────────────────────────────────────
elegir_loader() {
    section "Tipo de servidor"
    local LOADERS=("PaperMC" "Fabric" "Forge" "NeoForge")
    local DESCS=("Vanilla optimizado + Plugins" "Mods ligeros + Rendimiento" "Mods clásicos" "Fork moderno de Forge")
    for i in "${!LOADERS[@]}"; do
        printf "  ${C}%d)${NC} ${BOLD}%-10s${NC} ${DIM}%s${NC}\n" "$((i+1))" "${LOADERS[$i]}" "${DESCS[$i]}"
    done
    echo ""; echo -ne "  ${ARROW} Opción (1-4): "; read -r opt
    case "$opt" in
        1) LOADER="paper" ;; 2) LOADER="fabric" ;; 3) LOADER="forge" ;; 4) LOADER="neoforge" ;;
        *) log_warn "Opción inválida — usando PaperMC"; LOADER="paper" ;;
    esac
    log_ok "Loader: ${BOLD}$(echo "$LOADER" | tr '[:lower:]' '[:upper:]')${NC}"
}

elegir_version() {
    section "Versión de Minecraft"
    log_step "Buscando versiones..."
    local VERSIONES=""
    case "$LOADER" in
        paper) VERSIONES=$(curl -s https://api.papermc.io/v2/projects/paper | jq -r '.versions[]?' 2>/dev/null | grep -E '^[0-9]+\.[0-9]+(\.[0-9]+)?$' | sort -Vr | head -n 40) ;;
        fabric)
            local RAW=$(curl -s -m 15 https://meta.fabricmc.net/v2/versions/game)
            VERSIONES=$(echo "$RAW" | jq -r '.[] | select(.stable==true) | .version' 2>/dev/null | head -n 40)
            [ -z "$VERSIONES" ] && VERSIONES=$(echo "$RAW" | grep '"version"' | sed 's/.*"version": *"//;s/".*//' | grep -E '^[0-9]+\.[0-9]' | grep -v "snapshot" | head -n 40) ;;
        forge) VERSIONES=$(curl -s https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | grep -o '"[0-9]\+\.[0-9][0-9]*\.[0-9]*-latest"' | grep -o '[0-9]\+\.[0-9][0-9]*\.[0-9]*' | sort -Vr | uniq | head -n 40) ;;
        neoforge)
            local RAW=$(curl -s -m 15 "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")
            VERSIONES=$(echo "$RAW" | jq -r '.versions[]? | select(test("beta|alpha|rc") | not)' 2>/dev/null | sort -Vr | head -n 40) ;;
    esac
    [ -z "$VERSIONES" ] && fatal "No se pudieron obtener versiones."
    
    local i=1; declare -a VER_ARRAY
    while IFS= read -r ver; do
        [ -z "$ver" ] && continue; VER_ARRAY+=("$ver")
        if [ $i -eq 1 ]; then printf "  ${C}%3d)${NC} ${G}${BOLD}%-15s${NC} ${DIM}← más reciente${NC}\n" "$i" "$ver"
        else printf "  ${DIM}%3d)${NC} ${W}%-15s${NC}\n" "$i" "$ver"; fi
        ((i++))
    done <<< "$VERSIONES"
    
    echo ""; divider
    echo -e "  ${INFO} Elige un número o escribe la versión exacta (ej: 1.20.4, 1.16.5)"
    echo -ne "  ${ARROW} Selección: "; read -r eleccion
    
    if [[ "$eleccion" =~ ^[0-9]+$ ]]; then
        if [ "$eleccion" -ge 1 ] && [ "$eleccion" -le "${#VER_ARRAY[@]}" ]; then
            VERSION="${VER_ARRAY[$((eleccion-1))]}"
        else
            log_warn "Número fuera de rango. Usando la más reciente."
            VERSION="${VER_ARRAY[0]}"
        fi
    elif [[ "$eleccion" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
        VERSION="$eleccion"
        log_info "Versión personalizada: $VERSION"
    else
        log_warn "Entrada inválida. Usando la más reciente."
        VERSION="${VER_ARRAY[0]}"
    fi
    log_ok "Versión final: ${BOLD}$VERSION${NC}"
}

# ─── INSTALADORES ────────────────────────────────────────────────────
instalar_paper() {
    section "Instalando PaperMC $VERSION"
    log_step "Buscando último build..."
    local LATEST_BUILD=$(curl -s "https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds" | jq -r '.builds[-1].build' 2>/dev/null)
    [ -z "$LATEST_BUILD" ] || [ "$LATEST_BUILD" = "null" ] && fatal "No hay build para $VERSION"
    log_step "Descargando PaperMC..."
    wget -q --show-progress "https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds/$LATEST_BUILD/downloads/paper-$VERSION-$LATEST_BUILD.jar" -O "$SERVER_DIR/$SERVER_JAR" 2>&1
    echo ""; log_ok "PaperMC descargado"
}

instalar_fabric() {
    local FORCE_LOADER_VER="$1"
    section "Instalando Fabric para MC $VERSION"
    local RAW_INSTALLER=$(curl -s https://meta.fabricmc.net/v2/versions/installer)
    local INSTALLER_URL=$(echo "$RAW_INSTALLER" | jq -r '(map(select(.stable==true)) | .[0].url) // .[0].url' 2>/dev/null)
    local LOADER_VER="$FORCE_LOADER_VER"
    if [ -z "$LOADER_VER" ]; then
        local RAW_LOADER=$(curl -s https://meta.fabricmc.net/v2/versions/loader)
        LOADER_VER=$(echo "$RAW_LOADER" | jq -r '(map(select(.stable==true)) | .[0].version) // .[0].version' 2>/dev/null)
    fi
    [ -z "$INSTALLER_URL" ] || [ -z "$LOADER_VER" ] && fatal "Error obteniendo Fabric"
    
    log_step "Descargando instalador Fabric..."
    wget -q --show-progress "$INSTALLER_URL" -O "$SERVER_DIR/fabric-installer.jar" 2>&1
    echo ""
    
    # Verificación robusta: debe existir el JAR vanilla real en libraries/
    local intento VANILLA_OK=0 VANILLA_JAR=""
    for intento in 1 2 3; do
        log_step "Ejecutando instalador Fabric (intento $intento/3)..."
        # SÍNCRONO: sin background para evitar que Termux corte la red
        java -jar "$SERVER_DIR/fabric-installer.jar" server \
            -mcversion "$VERSION" \
            -loader "$LOADER_VER" \
            -downloadMinecraft \
            -dir "$SERVER_DIR" >> "$LOG_FILE" 2>&1
        
        # Buscar el JAR vanilla real (clave del error "couldn't locate the game")
        VANILLA_JAR=$(find "$SERVER_DIR/libraries" -name "server-*-*.jar" 2>/dev/null | head -1)
        [ -z "$VANILLA_JAR" ] && VANILLA_JAR=$(find "$SERVER_DIR/libraries" -name "minecraft-server-*.jar" 2>/dev/null | head -1)
        
        if [ -f "$SERVER_DIR/fabric-server-launch.jar" ] && [ -n "$VANILLA_JAR" ] && [ -s "$VANILLA_JAR" ]; then
            VANILLA_OK=1
            break
        fi
        log_warn "Descarga incompleta (intento $intento), reintentando..."
        rm -rf "$SERVER_DIR/libraries" "$SERVER_DIR/versions" 2>/dev/null
        sleep 2
    done
    
    [ "$VANILLA_OK" -ne 1 ] && fatal "Fabric no pudo descargar el servidor vanilla tras 3 intentos. Revisa tu conexión."
    
    cp "$SERVER_DIR/fabric-server-launch.jar" "$SERVER_DIR/$SERVER_JAR"
    rm -f "$SERVER_DIR/fabric-installer.jar"
    log_ok "Fabric instalado correctamente"
    [ -z "$FORCE_LOADER_VER" ] && instalar_fabric_api
}

instalar_fabric_api() {
    log_step "Descargando Fabric API..."
    mkdir -p "$SERVER_DIR/mods"
    local API_RESP=$(curl -s "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22${VERSION}%22%5D&loaders=%5B%22fabric%22%5D")
    local DOWNLOAD_URL=$(echo "$API_RESP" | jq -r '(.[0].files // []) as $fs | ($fs[] | select(.primary==true) | .url) // ($fs[0].url // empty)' 2>/dev/null | head -1)
    if [ -n "$DOWNLOAD_URL" ]; then
        wget -q --show-progress "$DOWNLOAD_URL" -O "$SERVER_DIR/mods/$(basename "$DOWNLOAD_URL")" 2>&1; echo ""
        log_ok "Fabric API instalada"
    else log_warn "No se pudo descargar Fabric API."
    fi
}

instalar_forge() {
    local FORCE_FORGE_VER="$1"
    section "Instalando Forge para MC $VERSION"
    local FORGE_VER="$FORCE_FORGE_VER"
    if [ -z "$FORGE_VER" ]; then
        FORGE_VER=$(curl -s https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | grep -o "\"${VERSION}-recommended\":\"[^\"]*\"" | grep -o '[0-9][^"]*' | head -1)
        [ -z "$FORGE_VER" ] && FORGE_VER=$(curl -s https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | grep -o "\"${VERSION}-latest\":\"[^\"]*\"" | grep -o '[0-9][^"]*' | head -1)
    fi
    [ -z "$FORGE_VER" ] && fatal "No hay Forge para MC $VERSION"
    local FORGE_FULL="${VERSION}-${FORGE_VER}"
    
    log_step "Descargando instalador Forge..."
    wget -q --show-progress "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_FULL}/forge-${FORGE_FULL}-installer.jar" -O "$SERVER_DIR/forge-installer.jar" 2>&1
    echo ""
    log_step "Instalando Forge..."
    # SÍNCRONO
    java -jar "$SERVER_DIR/forge-installer.jar" --installServer "$SERVER_DIR" >> "$LOG_FILE" 2>&1
    
    [ -f "$SERVER_DIR/run.sh" ] && { sed -i 's/pause//g' "$SERVER_DIR/run.sh"; chmod +x "$SERVER_DIR/run.sh"; }
    rm -f "$SERVER_DIR/forge-installer.jar"; mkdir -p "$SERVER_DIR/mods"
    log_ok "Forge $FORGE_FULL instalado"
}

instalar_neoforge() {
    local FORCE_NEOFORGE_VER="$1"
    section "Instalando NeoForge para MC $VERSION"
    local NEOFORGE_VER="$FORCE_NEOFORGE_VER"
    if [ -z "$NEOFORGE_VER" ]; then
        local MC_MINOR=$(echo "$VERSION" | cut -d. -f2)
        NEOFORGE_VER=$(curl -s "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge" | grep -o "\"${MC_MINOR}\.[^\"]*\"" | tr -d '"' | grep -v "beta\|alpha\|rc" | tail -1)
    fi
    [ -z "$NEOFORGE_VER" ] && fatal "No hay NeoForge para MC $VERSION"
    
    log_step "Descargando instalador NeoForge..."
    wget -q --show-progress "https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE_VER}/neoforge-${NEOFORGE_VER}-installer.jar" -O "$SERVER_DIR/neoforge-installer.jar" 2>&1
    echo ""
    log_step "Instalando NeoForge..."
    # SÍNCRONO
    java -jar "$SERVER_DIR/neoforge-installer.jar" --install-server "$SERVER_DIR" >> "$LOG_FILE" 2>&1
    
    [ -f "$SERVER_DIR/run.sh" ] && { sed -i 's/pause//g' "$SERVER_DIR/run.sh"; chmod +x "$SERVER_DIR/run.sh"; }
    rm -f "$SERVER_DIR/neoforge-installer.jar"; mkdir -p "$SERVER_DIR/mods"
    log_ok "NeoForge instalado"
}

# ─── CONFIGURACIÓN Y PLAYIT ──────────────────────────────────────────
configurar_servidor() {
    section "Configurando servidor"
    echo "eula=true" > "$SERVER_DIR/eula.txt"
    [ ! -f "$SERVER_DIR/server.properties" ] && cat > "$SERVER_DIR/server.properties" << EOF
server-port=25565
max-players=10
difficulty=normal
gamemode=survival
level-name=world
online-mode=false
view-distance=6
simulation-distance=4
EOF
    cat > "$CONFIG_FILE" << EOF
LOADER=$LOADER
VERSION=$VERSION
SERVER_DIR=$SERVER_DIR
SERVER_JAR=$SERVER_JAR
PLAYIT_BIN=${PLAYIT_BIN:-}
CF_KEY=${CF_KEY:-}
EOF
    log_ok "Configuración guardada"
}

instalar_playit() {
    section "Instalando playit.gg"
    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    command -v playit >/dev/null 2>&1 && PLAYIT_BIN="playit"
    [ -n "$PLAYIT_BIN" ] && { log_ok "playit.gg ya instalado ($PLAYIT_BIN)"; return 0; }

    log_step "Instalando TUR y playit..."
    pkg update -y -o Dpkg::Options::="--force-confold" >> "$LOG_FILE" 2>&1
    pkg install -y tur-repo >> "$LOG_FILE" 2>&1
    pkg update -y >> "$LOG_FILE" 2>&1
    pkg install -y playit tmux >> "$LOG_FILE" 2>&1

    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    command -v playit >/dev/null 2>&1 && PLAYIT_BIN="playit"
    [ -z "$PLAYIT_BIN" ] && { log_fail "Error instalando playit"; return 1; }
    log_ok "playit.gg instalado ($PLAYIT_BIN)"
}

configurar_playit() {
    section "Configurando playit.gg"
    [ -z "$PLAYIT_BIN" ] && { command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd" || PLAYIT_BIN="playit"; }
    [ -z "$PLAYIT_BIN" ] && { log_fail "playit no instalado"; return 1; }

    tmux kill-session -t playit 2>/dev/null
    log_step "Iniciando daemon..."
    tmux new-session -d -s playit "$PLAYIT_BIN"
    sleep 2

    echo -e "  ${INFO} Crea un túnel TCP al puerto 25565 en el panel de playit.gg"
    if command -v playit-cli >/dev/null 2>&1; then
        log_step "Generando link de claim..."
        playit-cli
    else
        log_step "Abriendo tmux para ver el link (Ctrl+B, D para salir)"
        sleep 1.5; tmux attach -t playit
    fi
}

# ─── SCRIPTS DE INICIO (AUTCONTENIDOS) ───────────────────────────────
crear_scripts_inicio() {
    section "Generando scripts de inicio"
    
    # Script simple (sin tmux) - AUTOCONTENIDO: rutas hardcodeadas
    cat > "$HOME/iniciar_minecraft.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
_SERVER_DIR="$SERVER_DIR"
_SERVER_JAR="$SERVER_JAR"
_MC_VERSION="$VERSION"
_LOADER="$LOADER"

TOTAL_RAM=\$(grep MemTotal /proc/meminfo | awk '{print int(\$2/1024)}')
if   [ "\$TOTAL_RAM" -ge 8192 ]; then RAM_MIN="1G"; RAM_MAX="4G"
elif [ "\$TOTAL_RAM" -ge 6144 ]; then RAM_MIN="1G"; RAM_MAX="3G"
elif [ "\$TOTAL_RAM" -ge 4096 ]; then RAM_MIN="512M"; RAM_MAX="2G"
elif [ "\$TOTAL_RAM" -ge 3072 ]; then RAM_MIN="512M"; RAM_MAX="1500M"
elif [ "\$TOTAL_RAM" -ge 2048 ]; then RAM_MIN="256M"; RAM_MAX="1G"
else RAM_MIN="256M"; RAM_MAX="512M"; fi

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

echo ""
echo "▶ RAM: min=\$RAM_MIN max=\$RAM_MAX"
echo "▶ Iniciando \$_LOADER MC \$_MC_VERSION..."
echo ""

cd "\$_SERVER_DIR" || exit 1

if [ -f "run.sh" ]; then
    bash run.sh
else
    java -Xms\$RAM_MIN -Xmx\$RAM_MAX -XX:+UseG1GC -jar "\$_SERVER_JAR" nogui
fi

command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1 || true
SCRIPT
    chmod +x "$HOME/iniciar_minecraft.sh"

    # Script con tmux - AUTOCONTENIDO: limpieza previa + rutas hardcodeadas
    cat > "$HOME/iniciar_minecraft_tmux.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
set -e

_SERVER_DIR="$SERVER_DIR"
_SERVER_JAR="$SERVER_JAR"
_MC_VERSION="$VERSION"
_LOADER="$LOADER"

cd "\$HOME"

echo "🧹 Limpiando sesiones anteriores..."
tmux kill-session -t minecraft 2>/dev/null && echo "✓ Sesión minecraft eliminada" || echo "- No había sesión minecraft"

if pgrep -f "java.*jar" > /dev/null; then
    pkill -f "java.*jar" && echo "✓ Procesos Java terminados" || true
fi

echo ""
echo "🔧 Configurando entorno..."

TOTAL_RAM=\$(grep MemTotal /proc/meminfo | awk '{print int(\$2/1024)}')
if   [ "\$TOTAL_RAM" -ge 8192 ]; then RAM_MIN="1G"; RAM_MAX="4G"
elif [ "\$TOTAL_RAM" -ge 6144 ]; then RAM_MIN="1G"; RAM_MAX="3G"
elif [ "\$TOTAL_RAM" -ge 4096 ]; then RAM_MIN="512M"; RAM_MAX="2G"
elif [ "\$TOTAL_RAM" -ge 3072 ]; then RAM_MIN="512M"; RAM_MAX="1500M"
elif [ "\$TOTAL_RAM" -ge 2048 ]; then RAM_MIN="256M"; RAM_MAX="1G"
else RAM_MIN="256M"; RAM_MAX="512M"; fi

echo "eula=true" > "\$_SERVER_DIR/eula.txt"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

echo "⏳ Esperando 2 segundos..."
sleep 2

echo "🎮 Iniciando \$_LOADER MC \$_MC_VERSION..."
cd "\$_SERVER_DIR"

# Monitor de wake-lock: se desbloquea cuando la sesión muere
(while tmux has-session -t minecraft 2>/dev/null; do sleep 30; done; command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1) & disown

if [ -f "run.sh" ]; then
    tmux new-session -d -s minecraft "bash run.sh"
else
    tmux new-session -d -s minecraft "java -Xms\$RAM_MIN -Xmx\$RAM_MAX -XX:+UseG1GC -jar \$_SERVER_JAR nogui"
fi

echo ""
echo "=========================================="
echo "✅ Servidor iniciado"
echo "=========================================="
echo ""
echo "📊 Sesiones activas:"
tmux list-sessions 2>/dev/null | sed 's/^/   /' || true
echo ""
echo "👀 Ver Minecraft: tmux attach -t minecraft"
echo "🔙 Salir de tmux: Ctrl+B, luego D"
echo ""
echo "⏳ El servidor tardará ~15-30 segundos en cargar"
SCRIPT
    chmod +x "$HOME/iniciar_minecraft_tmux.sh"
    log_ok "Scripts generados en ~/"
}

# ─── MODS Y MODPACKS ─────────────────────────────────────────────────
carpeta_contenido() { [ "$LOADER" = "paper" ] && echo "$SERVER_DIR/plugins" || echo "$SERVER_DIR/mods"; }
tipo_contenido() { [ "$LOADER" = "paper" ] && echo "plugin" || echo "mod"; }

buscar_modrinth() {
    local QUERY="$1" FACET_LOADER PROJECT_TYPE
    case "$LOADER" in paper) FACET_LOADER="bukkit"; PROJECT_TYPE="plugin" ;; *) FACET_LOADER="$LOADER"; PROJECT_TYPE="mod" ;; esac
    local QUERY_ENC=$(jq -rn --arg q "$QUERY" '$q|@uri')
    local FACETS=$(jq -rn --arg pt "$PROJECT_TYPE" --arg fl "$FACET_LOADER" --arg v "$VERSION" '[["project_type:"+$pt],["categories:"+$fl],["versions:"+$v]]|@uri')
    local HITS=$(curl -s "https://api.modrinth.com/v2/search?query=${QUERY_ENC}&facets=${FACETS}&limit=8" | jq -r '.hits[]? | "\(.slug)|\(.title)|\(.description // "" | .[0:55])"' 2>/dev/null)
    [ -z "$HITS" ] && { log_fail "Sin resultados"; return 1; }
    
    declare -a SLUGS; local i=1
    while IFS='|' read -r slug title desc; do
        printf "  ${C}%3d)${NC} ${BOLD}%-25s${NC} ${DIM}%s${NC}\n" "$i" "$title" "$desc"
        SLUGS+=("$slug"); ((i++))
    done <<< "$HITS"
    echo -ne "  ${ARROW} Descargar (0=cancelar): "; read -r el
    [ "$el" = "0" ] && return 0
    [[ "$el" =~ ^[0-9]+$ ]] && [ "$el" -ge 1 ] && [ "$el" -le "${#SLUGS[@]}" ] && descargar_modrinth "${SLUGS[$((el-1))]}"
}

descargar_modrinth() {
    local SLUG="$1" FACET_LOADER
    case "$LOADER" in paper) FACET_LOADER="bukkit" ;; *) FACET_LOADER="$LOADER" ;; esac
    local VER_RESP=$(curl -s "https://api.modrinth.com/v2/project/${SLUG}/version?game_versions=%5B%22${VERSION}%22%5D&loaders=%5B%22${FACET_LOADER}%22%5D")
    local URL=$(echo "$VER_RESP" | jq -r '(.[0].files // []) as $fs | ($fs[] | select(.primary==true) | .url) // ($fs[0].url // empty)' 2>/dev/null | head -1)
    [ -z "$URL" ] && { log_fail "Sin versión compatible"; return 1; }
    local DEST=$(carpeta_contenido); mkdir -p "$DEST"
    wget -q --show-progress "$URL" -O "$DEST/$(basename "$URL")" 2>&1; echo ""; log_ok "Descargado"
}

menu_gestor() {
    source "$CONFIG_FILE"
    local TIPO=$(tipo_contenido) CARPETA=$(carpeta_contenido)
    while true; do
        section "Gestor de ${TIPO}s"
        echo -e "  1) Buscar en Modrinth\n  2) Link directo\n  3) Listar\n  4) Volver"
        echo -ne "  ${ARROW} Opción: "; read -r opt
        case "$opt" in
            1) echo -ne "  ${ARROW} Buscar: "; read -r Q; buscar_modrinth "$Q" ;;
            2) echo -ne "  ${ARROW} URL directa (.jar): "; read -r URL
               [ -n "$URL" ] && { mkdir -p "$CARPETA"; wget -q --show-progress "$URL" -O "$CARPETA/$(basename "$URL")" 2>&1; echo ""; log_ok "Descargado"; } ;;
            3) ls "$CARPETA"/*.jar 2>/dev/null | while read -r f; do echo "  - $(basename "$f")"; done; pause ;;
            4) return ;;
        esac
    done
}

# ─── CONSOLA, BACKUP Y ESTADO ────────────────────────────────────────
consola_servidor() {
    if tmux has-session -t minecraft 2>/dev/null; then
        tmux attach -t minecraft
    else
        log_fail "Servidor no activo en tmux"; pause
    fi
}

enviar_comando() {
    if ! tmux has-session -t minecraft 2>/dev/null; then log_fail "Servidor no activo"; pause; return; fi
    echo -ne "  ${ARROW} Comando (sin /): "; read -r CMD
    [ -n "$CMD" ] && tmux send-keys -t minecraft:server "$CMD" Enter && log_ok "Enviado: $CMD"
    pause
}

hacer_backup() {
    local DIR="$HOME/mc_backups" TS=$(date +"%Y%m%d_%H%M%S")
    mkdir -p "$DIR"
    log_step "Creando backup..."
    tar -czf "$DIR/server_$TS.tar.gz" --exclude='logs' --exclude='crash-reports' -C "$SERVER_DIR" . 2>/dev/null &
    spinner $! "Comprimiendo"; wait $!
    log_ok "Backup en $DIR"
    local C=$(ls "$DIR"/*.tar.gz 2>/dev/null | wc -l)
    [ "$C" -gt 5 ] && { ls -t "$DIR"/*.tar.gz | tail -n +6 | xargs rm -f; log_info "Backups viejos borrados"; }
    pause
}

detener_servidor() {
    if tmux has-session -t minecraft 2>/dev/null; then
        tmux send-keys -t minecraft:server "stop" Enter
        log_ok "Enviado 'stop'. Esperando apagado..."
    else
        log_fail "Servidor no activo en tmux"
    fi
    pause
}

ver_estado() {
    section "Estado"
    echo -e "  Loader: ${BOLD}$(echo "$LOADER" | tr '[:lower:]' '[:upper:]')${NC}"
    echo -e "  Versión: ${BOLD}$VERSION${NC}"
    echo -e "  RAM: ${G}$RAM_MIN${NC} / ${G}$RAM_MAX${NC}"
    if tmux has-session -t minecraft 2>/dev/null; then echo -e "  Estado: ${G}ACTIVO (tmux)${NC}"
    elif pgrep -f "$SERVER_JAR" >/dev/null; then echo -e "  Estado: ${G}ACTIVO${NC}"
    else echo -e "  Estado: ${R}DETENIDO${NC}"; fi
    pause
}

# ─── MENÚ PRINCIPAL ──────────────────────────────────────────────────
menu_principal() {
    while true; do
        clear
        echo -e "${BOLD}${W}⛏ MINECRAFT SERVER MANAGER${NC}"
        echo -e "${DIM}$(echo "$LOADER" | tr '[:lower:]' '[:upper:]') · MC $VERSION · RAM $RAM_MIN–$RAM_MAX${NC}"
        divider
        
        if tmux has-session -t minecraft 2>/dev/null || pgrep -f "$SERVER_JAR" >/dev/null; then
            echo -e "  ${OK} ${G}Servidor CORRIENDO${NC}"
        else
            echo -e "  ${FAIL} ${R}Servidor DETENIDO${NC}"
        fi
        echo ""
        echo -e "  1) Iniciar (simple)       6) Instalar modpack"
        echo -e "  2) Iniciar (tmux)         7) Backup del mundo"
        echo -e "  3) Consola (tmux)         8) Ver estado"
        echo -e "  4) Enviar comando         9) Configurar playit.gg"
        echo -e "  5) Gestionar mods        10) Detener servidor"
        echo -e "  ${R}11) Salir${NC}"
        echo ""; echo -ne "  ${ARROW} Opción: "; read -r opt
        
        case "$opt" in
            1) bash "$HOME/iniciar_minecraft.sh" ;; 
            2) bash "$HOME/iniciar_minecraft_tmux.sh" ;;
            3) consola_servidor ;; 
            4) enviar_comando ;;
            5) menu_gestor ;; 
            6) log_warn "Modpacks deshabilitados en UI limpia. Usa gestor de mods." ;; 
            7) hacer_backup ;; 
            8) ver_estado ;;
            9) [ -z "$PLAYIT_BIN" ] && { instalar_playit; sed -i "s/PLAYIT_BIN=.*/PLAYIT_BIN=$PLAYIT_BIN/" "$CONFIG_FILE"; }; configurar_playit ;;
            10) detener_servidor ;;
            11) echo -e "\n  ${G}¡Adiós!${NC}\n"; exit 0 ;;
            *) log_warn "Opción inválida"; sleep 0.8 ;;
        esac
    done
}

# ─── FLUJO PRINCIPAL ─────────────────────────────────────────────────
main() {
    echo "=== MC Installer — $(date) ===" > "$LOG_FILE"
    splash
    verificar_conexion
    instalar_dependencias
    elegir_loader
    elegir_version
    get_ram
    instalar_java
    
    mkdir -p "$SERVER_DIR"
    case "$LOADER" in 
        paper) instalar_paper ;; 
        fabric) instalar_fabric ;; 
        forge) instalar_forge ;; 
        neoforge) instalar_neoforge ;; 
    esac
    
    configurar_servidor
    crear_scripts_inicio
    instalar_playit
    sed -i "s/PLAYIT_BIN=.*/PLAYIT_BIN=$PLAYIT_BIN/" "$CONFIG_FILE" 2>/dev/null
    
    clear
    section "INSTALACIÓN COMPLETADA"
    echo -e "  Loader: ${BOLD}$LOADER${NC}"
    echo -e "  Versión: ${BOLD}$VERSION${NC}"
    echo -e "  RAM: ${G}$RAM_MIN${NC} / ${G}$RAM_MAX${NC}"
    echo -e "  Carpeta: ${C}$SERVER_DIR${NC}"
    echo ""
    echo -e "  ${INFO} Para iniciar: ${W}bash ~/iniciar_minecraft_tmux.sh${NC}"
    
    if confirm "¿Configurar playit.gg ahora?" "s"; then configurar_playit; fi
    sleep 1; menu_principal
}

if [ -f "$CONFIG_FILE" ] && [ "$1" != "--reinstalar" ]; then
    source "$CONFIG_FILE"; get_ram; menu_principal
else main "$@"; fi
