#!/data/data/com.termux/files/usr/bin/bash
# ══════════════════════════════════════════════════════════════════════
# mc_manager.sh — headless server manager for MCPanel (Android app)
# Derived from minecraft_server_manager.sh (interactive original).
# Zero prompts. Every mutation writes state.json atomically (tmp+mv).
# Invoked by the app via Termux RUN_COMMAND intents. No TTY required.
#
# Subcommands:
#   bootstrap
#   install --loader paper|fabric|forge|neoforge --version X.Y.Z [--ram-min A --ram-max B]
#   start | stop | restart | status | send <cmd> | backup
#   mod-install <file-in-inbox> | mod-remove <name>
#   playit-start | playit-status
#   server-delete
# ══════════════════════════════════════════════════════════════════════
set -u

# Paths adapt to both Termux (legacy) and MCPanel embedded prefix (own app).
if [ -z "${PREFIX:-}" ]; then
    PREFIX="/data/data/com.termux/files/usr"
fi
HOME_DIR="${MC_HOME:-${PREFIX%/usr}/home}"
SERVER_DIR="$HOME_DIR/mcserver"
# Shared storage: real Termux FUSE mount, or $MC_SHARED override (tests/CI)
SHARED="${MC_SHARED:-/storage/emulated/0/MCPanel}"
INBOX="$SHARED/inbox"
STATE_FILE="$SHARED/state.json"
STATE_TMP="$SHARED/.state.json.tmp"
CONSOLE_LOG="$SHARED/console.log"
INSTALL_LOG="$SHARED/install.log"
CONFIG_FILE="$HOME_DIR/.mc_server_config"     # legacy-compatible
LEGACY_LOG="$HOME_DIR/.mc_installer.log"      # legacy-compatible
SCRIPT_DIR="$HOME_DIR/mcpanel"
TMUX_SESSION="minecraft"
PLAYIT_SESSION="playit"
BACKUP_DIR="$HOME_DIR/mc_backups"

VERSION=""; LOADER=""; RAM_MIN=""; RAM_MAX=""; TOTAL_RAM=0
PLAYIT_BIN=""

mkdir -p "$SERVER_DIR" "$INBOX" "$SCRIPT_DIR" 2>/dev/null
touch "$INSTALL_LOG" "$CONSOLE_LOG" 2>/dev/null

# ─── logging: install.log (shared) + legacy mirror ───────────────────
log() {  # level msg
    echo "[$1] $2" >> "$INSTALL_LOG"
    echo "[$1] $2" >> "$LEGACY_LOG" 2>/dev/null
}
now_ms() { date +%s%3N 2>/dev/null || echo "$(date +%s)000"; }

# ─── state.json: exact schema, atomic write ──────────────────────────
state_field() { # $1=jq filter on existing state; echoes value or "null"
    if [ -f "$STATE_FILE" ]; then
        jq -r "$1 // null" "$STATE_FILE" 2>/dev/null || echo "null"
    else
        echo "null"
    fi
}

write_state() { # $1..= jq update expr, e.g. '.running = true' or '.a = 1 | .b = 2'
    local merge="$1"
    # jq requires '|' between separate update expressions. Normalize the
    # space-joined field-update convention used by this script.
    merge=$(printf '%s' "$merge" | sed -E 's/[[:space:]]+\.(last_action|last_error|loader|version|ram_min|ram_max|running|started_at|installed|port|playit\.running)([[:space:]]*=)/ | .\1\2/g')
    local ts; ts=$(now_ms)
    local base='{"installed":false,"loader":null,"version":null,"ram_min":null,"ram_max":null,"running":false,"started_at":null,"port":25565,"playit":{"running":false,"claimed":false,"address":null},"last_action":null,"last_error":null,"updated_at":0}'
    local input="$STATE_FILE"
    [ -s "$STATE_FILE" ] || input=<(echo "$base")
    jq "${merge} | .updated_at=${ts}" "$input" > "$STATE_TMP" 2>/dev/null \
      || { echo "$base" | jq "${merge} | .updated_at=${ts}" > "$STATE_TMP"; }
    mv "$STATE_TMP" "$STATE_FILE"
}

state_set_error() {
    log "ERR" "$1"
    write_state ".last_error = \"$1\" .last_action = \"error\""
}

# ─── RAM presets (identical to original) ─────────────────────────────
detect_ram() {
    TOTAL_RAM=$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')
    if   [ "$TOTAL_RAM" -ge 8192 ]; then RAM_MIN="1G";   RAM_MAX="4G"
    elif [ "$TOTAL_RAM" -ge 6144 ]; then RAM_MIN="1G";   RAM_MAX="3G"
    elif [ "$TOTAL_RAM" -ge 4096 ]; then RAM_MIN="512M"; RAM_MAX="2G"
    elif [ "$TOTAL_RAM" -ge 3072 ]; then RAM_MIN="512M"; RAM_MAX="1500M"
    elif [ "$TOTAL_RAM" -ge 2048 ]; then RAM_MIN="256M"; RAM_MAX="1G"
    else RAM_MIN="256M"; RAM_MAX="512M"; fi
}

# ─── java version per MC version (identical rule to original) ────────
java_pkg_for() {
    local MINOR PATCH
    MINOR=$(echo "$1" | cut -d. -f2)
    PATCH=$(echo "$1" | cut -d. -f3); PATCH=${PATCH:-0}
    if [ "$MINOR" -ge 21 ] || { [ "$MINOR" -eq 20 ] && [ "$PATCH" -ge 5 ]; }; then
        echo "openjdk-21"
    else
        echo "openjdk-17"
    fi
}

server_running() {
    tmux has-session -t "$TMUX_SESSION" 2>/dev/null
}

refresh_running_state() {
    if server_running; then
        write_state ".running = true"
    else
        write_state ".running = false"
    fi
}

# ═══════════════════════════ SUBCOMMANDS ═════════════════════════════

cmd_bootstrap() {
    log "INF" "bootstrap: start"
    write_state '.last_action = "bootstrap"'
    command -v jq >/dev/null 2>&1 || pkg install -y jq >> "$INSTALL_LOG" 2>&1
    pkg install -y wget curl tmux jq unzip termux-tools >> "$INSTALL_LOG" 2>&1
    log "OK"  "bootstrap: base packages"
    # In the embedded (non-Termux) prefix there is no wake-lock binary;
    # the app's ServerService holds a partial wakelock while commands run.
    # playit via TUR repo (best effort; failure is not fatal)
    if ! command -v playit >/dev/null 2>&1 && ! command -v playitd >/dev/null 2>&1; then
        pkg install -y tur-repo >> "$INSTALL_LOG" 2>&1
        pkg update -y >> "$INSTALL_LOG" 2>&1
        pkg install -y playit >> "$INSTALL_LOG" 2>&1
    fi
    command -v playit  >/dev/null 2>&1 && PLAYIT_BIN="playit"
    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    log "OK"  "bootstrap: done"
    write_state ".last_action = \"bootstrap\" .last_error = null"
}

cmd_install() {
    LOADER=""; VERSION=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --loader)  LOADER="$2";  shift 2 ;;
            --version) VERSION="$2"; shift 2 ;;
            --ram-min) RAM_MIN="$2"; shift 2 ;;
            --ram-max) RAM_MAX="$2"; shift 2 ;;
            *) shift ;;
        esac
    done
    [ -z "$LOADER" ] || [ -z "$VERSION" ] && { state_set_error "install: loader/version required"; exit 1; }
    detect_ram
    [ -z "$RAM_MIN" ] && RAM_MIN="$(state_field .ram_min | sed 's/^null$//')"
    [ -z "$RAM_MAX" ] && RAM_MAX="$(state_field .ram_max | sed 's/^null$//')"

    log "INF" "install: $LOADER $VERSION ram=$RAM_MIN/$RAM_MAX"
    write_state ".last_action = \"install\" .loader = \"$LOADER\" .version = \"$VERSION\" .ram_min = \"$RAM_MIN\" .ram_max = \"$RAM_MAX\" .last_error = null"

    # java
    local JPKG; JPKG=$(java_pkg_for "$VERSION")
    log "INF" "install: java $JPKG"
    pkg install -y "$JPKG" >> "$INSTALL_LOG" 2>&1
    log "OK"  "install: java ready"

    mkdir -p "$SERVER_DIR"

    case "$LOADER" in
        paper)
            local BUILD=""
            # If the app downloaded the artifact, resolve its build from the
            # filename without requiring network access.
            for f in "$INBOX"/paper-"$VERSION"-*.jar; do
                [ -s "$f" ] || continue
                BUILD="${f##*-}"; BUILD="${BUILD%.jar}"; break
            done
            [ -z "$BUILD" ] && BUILD=$(curl -s "https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds" | jq -r '.builds[-1].build' 2>/dev/null)
            [ -z "$BUILD" ] || [ "$BUILD" = "null" ] && { state_set_error "install: no Paper build for $VERSION"; exit 1; }
            # app may have pre-downloaded the jar into inbox; prefer it
            local INBOX_JAR="$INBOX/paper-$VERSION-$BUILD.jar"
            if [ -s "$INBOX_JAR" ]; then
                mv "$INBOX_JAR" "$SERVER_DIR/server.jar"
                log "OK" "install: paper jar from inbox"
            else
                log "INF" "install: downloading paper $VERSION build $BUILD"
                wget -q "https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds/$BUILD/downloads/paper-$VERSION-$BUILD.jar" -O "$SERVER_DIR/server.jar" \
                  || { state_set_error "install: paper download failed"; exit 1; }
            fi
            [ -s "$SERVER_DIR/server.jar" ] || { state_set_error "install: paper jar empty"; exit 1; }
            ;;
        fabric)
            local INSTALLER_URL LOADER_VER
            INSTALLER_URL=$(curl -s https://meta.fabricmc.net/v2/versions/installer | jq -r '(map(select(.stable==true)) | .[0].url) // .[0].url' 2>/dev/null)
            LOADER_VER=$(curl -s https://meta.fabricmc.net/v2/versions/loader | jq -r '(map(select(.stable==true)) | .[0].version) // .[0].version' 2>/dev/null)
            [ -z "$INSTALLER_URL" ] || [ -z "$LOADER_VER" ] && { state_set_error "install: fabric meta failed"; exit 1; }
            wget -q "$INSTALLER_URL" -O "$SERVER_DIR/fabric-installer.jar" || { state_set_error "install: fabric installer download failed"; exit 1; }
            local intento VANILLA_OK=0 VANILLA_JAR=""
            for intento in 1 2 3; do
                log "INF" "install: fabric installer attempt $intento/3"
                java -jar "$SERVER_DIR/fabric-installer.jar" server \
                    -mcversion "$VERSION" -loader "$LOADER_VER" -downloadMinecraft -dir "$SERVER_DIR" >> "$INSTALL_LOG" 2>&1
                VANILLA_JAR=$(find "$SERVER_DIR/libraries" -name "server-*-*.jar" 2>/dev/null | head -1)
                [ -z "$VANILLA_JAR" ] && VANILLA_JAR=$(find "$SERVER_DIR/libraries" -name "minecraft-server-*.jar" 2>/dev/null | head -1)
                if [ -f "$SERVER_DIR/fabric-server-launch.jar" ] && [ -n "$VANILLA_JAR" ] && [ -s "$VANILLA_JAR" ]; then
                    VANILLA_OK=1; break
                fi
                rm -rf "$SERVER_DIR/libraries" "$SERVER_DIR/versions" 2>/dev/null
                sleep 2
            done
            [ "$VANILLA_OK" -ne 1 ] && { state_set_error "install: fabric vanilla download failed after 3 attempts"; exit 1; }
            cp "$SERVER_DIR/fabric-server-launch.jar" "$SERVER_DIR/server.jar"
            rm -f "$SERVER_DIR/fabric-installer.jar"
            ;;
        forge|neoforge)
            local INSTALLER_JAR=""
            if [ "$LOADER" = "forge" ]; then
                local FORGE_VER
                FORGE_VER=$(curl -s https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | grep -o "\"${VERSION}-recommended\":\"[^\"]*\"" | grep -o '[0-9][^"]*' | head -1)
                [ -z "$FORGE_VER" ] && FORGE_VER=$(curl -s https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | grep -o "\"${VERSION}-latest\":\"[^\"]*\"" | grep -o '[0-9][^"]*' | head -1)
                [ -z "$FORGE_VER" ] && { state_set_error "install: no forge for $VERSION"; exit 1; }
                local FULL="${VERSION}-${FORGE_VER}"
                INSTALLER_JAR="$SERVER_DIR/forge-installer.jar"
                wget -q "https://maven.minecraftforge.net/net/minecraftforge/forge/${FULL}/forge-${FULL}-installer.jar" -O "$INSTALLER_JAR" \
                  || { state_set_error "install: forge installer download failed"; exit 1; }
            else
                local NEO_VER MC_MINOR
                MC_MINOR=$(echo "$VERSION" | cut -d. -f2)
                NEO_VER=$(curl -s "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge" | grep -o "\"${MC_MINOR}\.[^\"]*\"" | tr -d '"' | grep -v "beta\|alpha\|rc" | tail -1)
                [ -z "$NEO_VER" ] && { state_set_error "install: no neoforge for $VERSION"; exit 1; }
                INSTALLER_JAR="$SERVER_DIR/neoforge-installer.jar"
                wget -q "https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEO_VER}/neoforge-${NEO_VER}-installer.jar" -O "$INSTALLER_JAR" \
                  || { state_set_error "install: neoforge installer download failed"; exit 1; }
            fi
            # app may have pre-downloaded the installer into inbox; prefer it
            for f in "$INBOX"/*installer*.jar; do
                [ -e "$f" ] || continue
                mv "$f" "$INSTALLER_JAR"; log "OK" "install: $LOADER installer from inbox"; break
            done
            log "INF" "install: running $LOADER installer (sync)"
            java -jar "$INSTALLER_JAR" --installServer "$SERVER_DIR" >> "$INSTALL_LOG" 2>&1
            [ -f "$SERVER_DIR/run.sh" ] || { state_set_error "install: $LOADER run.sh missing after install"; exit 1; }
            sed -i 's/pause//g' "$SERVER_DIR/run.sh"
            chmod +x "$SERVER_DIR/run.sh"
            rm -f "$INSTALLER_JAR"
            mkdir -p "$SERVER_DIR/mods"
            ;;
        *) state_set_error "install: unknown loader $LOADER"; exit 1 ;;
    esac

    # eula + server.properties (preserve existing)
    echo "eula=true" > "$SERVER_DIR/eula.txt"
    if [ ! -f "$SERVER_DIR/server.properties" ]; then
        cat > "$SERVER_DIR/server.properties" << EOF
server-port=25565
max-players=10
difficulty=normal
gamemode=survival
level-name=world
online-mode=false
view-distance=6
simulation-distance=4
EOF
    fi

    # legacy-compatible config
    cat > "$CONFIG_FILE" << EOF
LOADER=$LOADER
VERSION=$VERSION
SERVER_DIR=$SERVER_DIR
SERVER_JAR=server.jar
PLAYIT_BIN=${PLAYIT_BIN:-}
CF_KEY=
EOF

    log "OK" "install: complete $LOADER $VERSION"
    write_state ".installed = true .loader = \"$LOADER\" .version = \"$VERSION\" .ram_min = \"$RAM_MIN\" .ram_max = \"$RAM_MAX\" .last_action = \"install\" .last_error = null"
}

cmd_start() {
    refresh_running_state
    if [ "$(state_field .running)" = "true" ]; then
        log "INF" "start: already running"
        return 0
    fi
    if [ "$(state_field .installed)" != "true" ]; then
        state_set_error "start: not installed"; exit 1
    fi
    # source config for paths/loader (single source of truth on device)
    [ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"
    detect_ram
    RAM_MIN="${RAM_MIN:-$(state_field .ram_min | sed 's/^null$//')}"
    RAM_MAX="${RAM_MAX:-$(state_field .ram_max | sed 's/^null$//')}"
    RAM_MIN="${RAM_MIN:-256M}"; RAM_MAX="${RAM_MAX:-1G}"

    echo "eula=true" > "$SERVER_DIR/eula.txt"
    : > "$CONSOLE_LOG"   # fresh console per run
    command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

    cd "$SERVER_DIR" || { state_set_error "start: cannot cd $SERVER_DIR"; exit 1; }
    local RUN_CMD
    if [ -f "run.sh" ]; then
        RUN_CMD="bash run.sh >> '$CONSOLE_LOG' 2>&1"
    else
        RUN_CMD="java -Xms$RAM_MIN -Xmx$RAM_MAX -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar server.jar nogui >> '$CONSOLE_LOG' 2>&1"
    fi
    tmux new-session -d -s "$TMUX_SESSION" "$RUN_CMD" || { state_set_error "start: tmux failed"; exit 1; }
    sleep 1
    # console pipe: tmux pane output -> shared console.log (app tails it)
    tmux pipe-pane -t "$TMUX_SESSION" -o "cat >> $CONSOLE_LOG" 2>/dev/null
    # wake-unlock monitor when session dies
    (while tmux has-session -t "$TMUX_SESSION" 2>/dev/null; do sleep 30; done
     command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1 || true) >/dev/null 2>&1 &

    write_state ".running = true .started_at = $(date +%s) .last_action = \"start\" .last_error = null"
    log "OK" "start: running"
}

cmd_stop() {
    if server_running; then
        tmux send-keys -t "$TMUX_SESSION" "stop" Enter
        log "INF" "stop: graceful sent"
        local i=0
        while server_running && [ $i -lt 30 ]; do sleep 1; i=$((i+1)); done
        if server_running; then
            log "WRN" "stop: graceful timeout, terminating tmux session"
            tmux kill-session -t "$TMUX_SESSION" 2>/dev/null
            sleep 2
        fi
    fi
    # never leave java orphaned, but only after graceful attempt
    if pgrep -f "java.*mcserver\|java.*server.jar" >/dev/null 2>&1; then
        pkill -f "java.*mcserver\|java.*server.jar" 2>/dev/null
        sleep 1
    fi
    command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1 || true
    write_state ".running = false .started_at = null .last_action = \"stop\" .last_error = null"
    log "OK" "stop: done"
}

cmd_restart() {
    cmd_stop
    sleep 1
    cmd_start
}

cmd_status() {
    refresh_running_state
    local PLAYIT_RUNNING=false PLAYIT_ADDR=null
    if tmux has-session -t "$PLAYIT_SESSION" 2>/dev/null; then PLAYIT_RUNNING=true; fi
    if [ -f "$HOME_DIR/.playit/claim_url" ]; then PLAYIT_ADDR="\"$(cat "$HOME_DIR/.playit/claim_url" | head -1)\""; fi
    write_state ".playit.running = $PLAYIT_RUNNING"
    log "INF" "status: refreshed"
}

cmd_send() {
    local CMD="${1:-}"
    [ -z "$CMD" ] && { state_set_error "send: empty command"; exit 1; }
    if ! server_running; then state_set_error "send: not running"; exit 1; fi
    tmux send-keys -t "$TMUX_SESSION" "$CMD" Enter
    log "INF" "send: $CMD"
}

cmd_backup() {
    local TS; TS=$(date +"%Y%m%d_%H%M%S")
    mkdir -p "$BACKUP_DIR"
    log "INF" "backup: creating"
    tar -czf "$BACKUP_DIR/server_$TS.tar.gz" --exclude='logs' --exclude='crash-reports' -C "$SERVER_DIR" . 2>>"$INSTALL_LOG"
    local C; C=$(ls "$BACKUP_DIR"/*.tar.gz 2>/dev/null | wc -l)
    [ "$C" -gt 5 ] && ls -t "$BACKUP_DIR"/*.tar.gz | tail -n +6 | xargs rm -f
    write_state ".last_action = \"backup\" .last_error = null"
    log "OK" "backup: server_$TS.tar.gz"
}

cmd_mod_install() {
    local FILE_IN_INBOX="${1:-}"
    [ -z "$FILE_IN_INBOX" ] && { state_set_error "mod-install: file required"; exit 1; }
    local SRC="$INBOX/$FILE_IN_INBOX"
    [ -f "$SRC" ] || SRC="$FILE_IN_INBOX"
    [ -f "$SRC" ] || { state_set_error "mod-install: not found in inbox: $FILE_IN_INBOX"; exit 1; }
    [ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"
    local DEST
    if [ "${LOADER:-}" = "paper" ]; then DEST="$SERVER_DIR/plugins"; else DEST="$SERVER_DIR/mods"; fi
    mkdir -p "$DEST"
    # validate jar magic bytes
    if [ "$(head -c 2 "$SRC")" != "PK" ]; then
        state_set_error "mod-install: not a valid jar"; rm -f "$SRC"; exit 1
    fi
    mv "$SRC" "$DEST/$(basename "$SRC")"
    write_state ".last_action = \"mod-install\" .last_error = null"
    log "OK" "mod-install: $(basename "$SRC")"
}

cmd_mod_remove() {
    local NAME="${1:-}"
    [ -z "$NAME" ] && { state_set_error "mod-remove: name required"; exit 1; }
    [ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"
    local DIR
    if [ "${LOADER:-}" = "paper" ]; then DIR="$SERVER_DIR/plugins"; else DIR="$SERVER_DIR/mods"; fi
    rm -f "$DIR/$NAME" || { state_set_error "mod-remove: failed"; exit 1; }
    write_state ".last_action = \"mod-remove\" .last_error = null"
    log "OK" "mod-remove: $NAME"
}

cmd_playit_start() {
    command -v playit  >/dev/null 2>&1 && PLAYIT_BIN="playit"
    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    [ -z "$PLAYIT_BIN" ] && { state_set_error "playit-start: not installed"; exit 1; }
    tmux kill-session -t "$PLAYIT_SESSION" 2>/dev/null
    tmux new-session -d -s "$PLAYIT_SESSION" "$PLAYIT_BIN >> $INSTALL_LOG 2>&1"
    sleep 2
    write_state ".playit.running = true .last_action = \"playit-start\" .last_error = null"
    log "OK" "playit-start: session up"
}

cmd_playit_status() {
    if tmux has-session -t "$PLAYIT_SESSION" 2>/dev/null; then
        write_state ".playit.running = true"
    else
        write_state ".playit.running = false"
    fi
    log "INF" "playit-status: refreshed"
}

cmd_server_delete() {
    log "WRN" "server-delete: requested"
    cmd_stop >/dev/null 2>&1
    rm -rf "$SERVER_DIR"
    rm -f "$CONFIG_FILE"
    write_state '.installed = false .loader = null .version = null .ram_min = null .ram_max = null .running = false .started_at = null .last_action = "server-delete" .last_error = null'
    log "OK" "server-delete: done"
}

# ═══════════════════════════ dispatch ════════════════════════════════
CMD="${1:-}"; shift 2>/dev/null || true
case "$CMD" in
    bootstrap)      cmd_bootstrap "$@" ;;
    install)        cmd_install "$@" ;;
    start)          cmd_start "$@" ;;
    stop)           cmd_stop "$@" ;;
    restart)        cmd_restart "$@" ;;
    status)         cmd_status "$@" ;;
    send)           cmd_send "$@" ;;
    backup)         cmd_backup "$@" ;;
    mod-install)    cmd_mod_install "$@" ;;
    mod-remove)     cmd_mod_remove "$@" ;;
    playit-start)   cmd_playit_start "$@" ;;
    playit-status)  cmd_playit_status "$@" ;;
    server-delete)  cmd_server_delete "$@" ;;
    *) state_set_error "unknown command: ${CMD:-<none>}"; exit 1 ;;
esac
