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
#   prop <key> <value> [key value ...] | ram-set <min> <max>
#   playit-start | playit-stop | playit-status | playit-secret <key> | playit-secret-clear
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
# (atomic write goes through a jq-managed tmp + read-modify-write; no
#  shared STATE_TMP is needed here — leave it implicit in jq.)
CONSOLE_LOG="$SHARED/console.log"
INSTALL_LOG="$SHARED/install.log"
TUNNEL_LOG="$SHARED/tunnel.log"    # playit agent output (app tails it)
CONFIG_FILE="$HOME_DIR/.mc_server_config"     # legacy-compatible
LEGACY_LOG="$HOME_DIR/.mc_installer.log"      # legacy-compatible
SCRIPT_DIR="$HOME_DIR/mcpanel"
TMUX_SESSION="minecraft"
PLAYIT_SESSION="playit"
BACKUP_DIR="$HOME_DIR/mc_backups"

VERSION=""; LOADER=""; RAM_MIN=""; RAM_MAX=""; TOTAL_RAM=0
PLAYIT_BIN=""

# ─── embedded prefix (MCPanel app) ────────────────────────────────────
# The app runs this script inside its own prefix (/data/data/io.mcpanel/…)
# but Termux packages hardcode /data/data/com.termux paths. Both strings
# are exactly 10 chars, so they are byte-patchable. All adaptation is
# skipped on a real Termux (PREFIX=com.termux) install.
EMBEDDED=0
case "$PREFIX" in
    /data/data/io.mcpanel/*) EMBEDDED=1 ;;
esac
# test hook: force embedded mode with an arbitrary PREFIX
[ "${MC_EMBEDDED:-0}" = "1" ] && EMBEDDED=1
export PREFIX   # dpkg shim inherits it
DEB_PATCH_BIN="$PREFIX/bin/mc-deb-patch"

ensure_embedded_env() {
    [ "$EMBEDDED" = "1" ] || return 0
    # dirs dpkg/apt require (some are empty-dir entries of the bootstrap)
    for d in "$PREFIX/etc/apt/apt.conf.d" "$PREFIX/etc/apt/preferences.d" \
             "$PREFIX/var/log/apt" "$PREFIX/var/cache/apt/archives/partial" \
             "$PREFIX/var/lib/dpkg/updates" "$PREFIX/var/lib/dpkg/info" \
             "$PREFIX/var/lib/dpkg/triggers" "$PREFIX/var/lib/apt/lists/partial" \
             "${PREFIX%/usr}/tmp" "$PREFIX/var/lib" "$PREFIX/bin"; do
        mkdir -p "$d" 2>/dev/null
    done
    # The dpkg shim is the single interception point for .deb path patching.
    # Remove any stale Pre-Install-Pkgs conf from very old versions.
    rm -f "$PREFIX/etc/apt/apt.conf.d/99mcpanel" 2>/dev/null
    local DPKG_REAL="$PREFIX/bin/dpkg.real"
    local SHIM_VER="$PREFIX/var/lib/mc-shim-version"
    local SHIM_WANT=3
    # 1) deb patcher: rewrite whenever its version marker is stale — covers
    #    devices that already extracted an older (buggy) version.
    if [ "$(cat "$SHIM_VER" 2>/dev/null)" != "$SHIM_WANT" ] || [ ! -x "$DEB_PATCH_BIN" ]; then
        cat > "$DEB_PATCH_BIN" <<'HOOK'
#!/data/data/io.mcpanel/files/usr/bin/bash
# Rewrites com.termux -> io.mcpanel inside .debs before dpkg unpacks them.
# Reads deb paths on stdin (called once per deb by the dpkg shim).
# Both strings are exactly 10 chars: same-length byte replacement keeps
# ELF offsets and tar offsets intact.
PREFIX="${PREFIX:-/data/data/io.mcpanel/files/usr}"
PATCHED_DIR="$PREFIX/var/cache/apt/mc-patched"
PATCH_LOG="$PREFIX/var/log/mc-deb-patch.log"
log_p() { echo "[patch $(date '+%H:%M:%S')] $*" >> "$PATCH_LOG" 2>/dev/null; }
mkdir -p "$PATCHED_DIR" "$PREFIX/var/log" 2>/dev/null
rm -rf "$PATCHED_DIR"/.work.* 2>/dev/null
WORK="$PATCHED_DIR/.work.$$"
while IFS= read -r DEB; do
    case "$DEB" in *.deb) ;; *) continue ;; esac
    [ -f "$DEB" ] || continue
    log_p "processing $(basename "$DEB") ($(wc -c < "$DEB") bytes)"
    if [ -f "$DEB.patched" ] && [ -s "$DEB.patched" ]; then
        mv -f "$DEB.patched" "$DEB" 2>/dev/null
        echo "$DEB"; continue
    fi
    rm -rf "$WORK"; mkdir -p "$WORK"
    if ! dpkg-deb -R "$DEB" "$WORK" >>"$PATCH_LOG" 2>&1; then
        log_p "dpkg-deb -R failed on $(basename "$DEB") - intentando con tar"
        rm -rf "$WORK"; mkdir -p "$WORK"
        if ! dpkg-deb --fsys-tarfile "$DEB" 2>>"$PATCH_LOG" | tar -xf - -C "$WORK" 2>>"$PATCH_LOG"; then
            log_p "EXTRACT FAILED for $DEB (espacio en /data?) - entregando SIN patchear"
            echo "$DEB"; continue
        fi
    fi
    # 1) the tree inside the deb is ./data/data/com.termux - rename it
    if [ -d "$WORK/data/data/com.termux" ]; then
        mv "$WORK/data/data/com.termux" "$WORK/data/data/io.mcpanel" 2>/dev/null
    fi
    # 2) rewrite path strings inside every packaged file (same-length ->
    #    ELF offsets intact)
    find "$WORK" -type f -size -8M -print0 2>/dev/null |         xargs -0 -r grep -l -a -F 'com.termux' 2>/dev/null |     while IFS= read -r f; do
        sed -i 's/com\.termux/io.mcpanel/g' "$f" 2>/dev/null || true
    done
    # 2b) Termux's dpkg refuses to repack debs whose maintainer scripts are
    #     644 ("bad permissions") - killed whole transactions (tur-repo).
    find "$WORK/DEBIAN" -type f -print0 2>/dev/null |         xargs -0 -r chmod 755 2>/dev/null
    # 2c) symlinks with absolute com.termux targets (openjdk ships several)
    find "$WORK" -type l -print0 2>/dev/null |     while IFS= read -r -d '' l; do
        t=$(readlink "$l") || continue
        case "$t" in *com.termux*)
            rm -f "$l"
            ln -s "${t//com.termux/io.mcpanel}" "$l" 2>/dev/null
        ;; esac
    done
    [ -d "$WORK/data/data/io.mcpanel/usr/bin" ] && chmod 755 "$WORK/data/data/io.mcpanel/usr/bin"/* 2>/dev/null
    # 3) repack to a sibling file; replace original ONLY on success.
    if dpkg-deb -b "$WORK" "$DEB.patched" >>"$PATCH_LOG" 2>&1 && [ -s "$DEB.patched" ]; then
        mv -f "$DEB.patched" "$DEB"
        log_p "repacked OK: $(basename "$DEB")"
    else
        log_p "REPACK FAILED for $(basename "$DEB") (espacio en /data?) - original intacto"
        rm -f "$DEB.patched" 2>/dev/null
    fi
    echo "$DEB"
    rm -rf "$WORK"
done
rm -rf "$WORK" 2>/dev/null
exit 0
HOOK
        chmod +x "$DEB_PATCH_BIN" 2>/dev/null
        echo "$SHIM_WANT" > "$SHIM_VER" 2>/dev/null
    fi
    # 2) dpkg shim: wrap a real (unwrapped) dpkg binary whenever present.
    if [ ! -f "$DPKG_REAL" ] && [ -f "$PREFIX/bin/dpkg" ] && ! head -2 "$PREFIX/bin/dpkg" 2>/dev/null | grep -q 'dpkg.real'; then
        mv "$PREFIX/bin/dpkg" "$DPKG_REAL" 2>/dev/null
        cat > "$PREFIX/bin/dpkg" <<'WRAP'
#!/data/data/io.mcpanel/files/usr/bin/bash
# Shim: patch com.termux paths inside queued .debs, then exec the real dpkg.
PREFIX="${PREFIX:-/data/data/io.mcpanel/files/usr}"
SHIM_LOG="$PREFIX/var/log/mc-dpkg-shim.log"
log_shim() { echo "[shim $(date '+%H:%M:%S')] $*" >> "$SHIM_LOG" 2>/dev/null; }
mkdir -p "$PREFIX/var/log" 2>/dev/null
log_shim "dpkg $*"
patch_one() {
    log_shim "patching $(basename "$1")"
    if ! OUT=$("$PREFIX/bin/mc-deb-patch" <<<"$1" 2>&1); then
        log_shim "PATCHER FAILED for $1: $OUT"
    fi
}
ARGS=()
for a in "$@"; do
    case "$a" in
        *.deb) [ -f "$a" ] && patch_one "$a" ;;
        *)
            # apt big installs: dpkg --unpack --recursive <dir> - patch ALL
            if [ -d "$a" ]; then
                for d in "$a"/*.deb; do [ -f "$d" ] && patch_one "$d"; done
            fi
            ;;
    esac
    ARGS+=("$a")
done
"$PREFIX/bin/dpkg.real" "${ARGS[@]}"
RC=$?
log_shim "dpkg exit $RC"
exit $RC
WRAP
        chmod +x "$PREFIX/bin/dpkg" 2>/dev/null
    fi
}

mkdir -p "$SHARED" "$SERVER_DIR" "$INBOX" "$SCRIPT_DIR" 2>/dev/null
touch "$INSTALL_LOG" "$CONSOLE_LOG" 2>/dev/null
ensure_embedded_env 2>/dev/null || true

# ─── logging: install.log (shared) + legacy mirror ───────────────────
log() {  # level msg
    echo "[$1] $2" >> "$INSTALL_LOG"
    echo "[$1] $2" >> "$LEGACY_LOG" 2>/dev/null
}
now_ms() { date +%s%3N 2>/dev/null || echo "$(date +%s)000"; }

# ─── installs with per-package progress ──────────────────────────────
# Streams pkg output into install.log and appends "[PROG] done total"
# markers as apt configures each package, so the app can render a real
# progress bar (openjdk and friends pull dozens of dependencies). If the
# total cannot be simulated (e.g. stale lists) total is 0 and the app
# falls back to an indeterminate bar.
apt_progress() {  # $@ = packages
    local total done rc
    total=$(apt-get install --simulate -y "$@" 2>/dev/null | grep -c '^Inst ')
    done=0
    local MARK="$SHARED/.apt-progress.$$"
    : > "$MARK"
    pkg install -y "$@" 2>&1 | while IFS= read -r line; do
        echo "$line" >> "$INSTALL_LOG"
        case "$line" in
            *"Setting up "*)
                done=$(cat "$MARK" 2>/dev/null); done=$((done + 1))
                echo "$done" > "$MARK"
                echo "[PROG] $done $total" >> "$INSTALL_LOG"
                ;;
        esac
    done
    rc=${PIPESTATUS[0]}
    rm -f "$MARK"
    return "$rc"
}

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
    # PID-scoped temp file: the keep-alive service may refresh state.json
    # in the background while a command writes it. A shared tmp path would
    # let two writers clobber each other mid-write; this keeps them apart.
    local TMPF="$SHARED/.state.json.tmp.$$"
    local input="$STATE_FILE"
    [ -s "$STATE_FILE" ] || input=<(echo "$base")
    jq "${merge} | .updated_at=${ts}" "$input" > "$TMPF" 2>/dev/null \
      || { echo "$base" | jq "${merge} | .updated_at=${ts}" > "$TMPF"; }
    mv "$TMPF" "$STATE_FILE"
}

state_set_error() {
    log "ERR" "$1"
    write_state ".last_error = \"$1\" .last_action = \"error\""
}

# ─── RAM presets (identical to original) ─────────────────────────────
# Priority: explicit env/flag > state.json > hardware preset.
# Without the early-return, cmd_install and cmd_start silently overwrite
# whatever the caller chose with the hardware-derived default.
detect_ram() {
    if [ -n "${RAM_MIN:-}" ] && [ -n "${RAM_MAX:-}" ]; then
        log "INF" "ram: keeping explicit $RAM_MIN/$RAM_MAX"
        return 0
    fi
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
    # tmux session OR a live java server process (guards against a killed
    # tmux server leaving java alive, or state.json claiming stale truth)
    tmux has-session -t "$TMUX_SESSION" 2>/dev/null && return 0
    pgrep -f "java.*(mcserver|server\.jar|fabric-server-launch)" >/dev/null 2>&1
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
    # jq first: every later step depends on it
    if ! command -v jq >/dev/null 2>&1; then
        apt_progress jq || true
    fi
    apt_progress wget curl tmux jq unzip procps findutils diffutils termux-tools || true
    # verify base tools; report exactly which one is missing
    local MISSING=""
    for tool in jq wget curl tmux unzip pgrep find diff bash; do
        command -v "$tool" >/dev/null 2>&1 || MISSING="$MISSING $tool"
    done
    if [ -n "$MISSING" ]; then
        state_set_error "bootstrap: faltan herramientas:$MISSING (ver instala.log)"
        exit 1
    fi
    log "OK"  "bootstrap: base packages"
    # In the embedded (non-Termux) prefix there is no wake-lock binary;
    # the app's ServerService holds a partial wakelock while commands run.
    # playit via TUR repo (best effort; failure is not fatal)
    if ! command -v playit >/dev/null 2>&1 && ! command -v playitd >/dev/null 2>&1; then
        pkg install -y tur-repo >> "$INSTALL_LOG" 2>&1
        pkg update -y >> "$INSTALL_LOG" 2>&1
        pkg install -y playit >> "$INSTALL_LOG" 2>&1 || log "WRN" "bootstrap: playit no disponible (no es fatal)"
    fi
    command -v playit  >/dev/null 2>&1 && PLAYIT_BIN="playit"
    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    # TMPDIR convention: $PREFIX/tmp (matches the app's check and Termux)
    mkdir -p "$PREFIX/tmp" "${PREFIX%/usr}/tmp" 2>/dev/null
    apt-get clean >> "$INSTALL_LOG" 2>&1 || true
    : > "$PREFIX/tmp/bootstrap-done"
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
    # Flags first (already in RAM_MIN/RAM_MAX). Fill the gaps from state.json.
    # detect_ram is a no-op when both are set; otherwise it derives a preset
    # that we discard if state.json had a stored value.
    if [ -z "${RAM_MIN:-}" ] || [ -z "${RAM_MAX:-}" ]; then
        RAM_MIN="${RAM_MIN:-$(state_field .ram_min | sed 's/^null$//')}"
        RAM_MAX="${RAM_MAX:-$(state_field .ram_max | sed 's/^null$//')}"
        [ -z "${RAM_MIN:-}" ] || [ -z "${RAM_MAX:-}" ] && detect_ram
    fi

    log "INF" "install: $LOADER $VERSION ram=$RAM_MIN/$RAM_MAX"
    write_state ".last_action = \"install\" .loader = \"$LOADER\" .version = \"$VERSION\" .ram_min = \"$RAM_MIN\" .ram_max = \"$RAM_MAX\" .last_error = null"

    # java: install AND verify — dpkg can fail mid-transaction (space,
    # unpack error) and the old code logged "ready" anyway, burning the
    # loader attempts on a missing binary.
    local JPKG; JPKG=$(java_pkg_for "$VERSION")
    local FREE_KB; FREE_KB=$(df -k "${PREFIX%/usr}" 2>/dev/null | awk 'NR==2{print $4}')
    if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 786432 ]; then
        log "WRN" "install: solo $((FREE_KB/1024)) MB libres; openjdk necesita ~700 MB descomprimido"
    fi
    log "INF" "install: java $JPKG"
    apt-get clean >> "$INSTALL_LOG" 2>&1 || true   # free cache: openjdk chain is huge
    apt_progress "$JPKG"
    local PKG_RC=$?
    if [ "$PKG_RC" -ne 0 ]; then
        log "ERR" "install: pkg terminó con código $PKG_RC — detalle en install.log y $PREFIX/var/log/mc-dpkg-shim.log"
        # dump the shim + patcher logs into install.log so the app's log
        # screen (which only tails install.log) shows the REAL dpkg error
        for lf in "$PREFIX/var/log/mc-dpkg-shim.log" "$PREFIX/var/log/mc-deb-patch.log"; do
            [ -f "$lf" ] && { log "INF" "── $lf (últimas líneas) ──"; tail -n 40 "$lf" >> "$INSTALL_LOG" 2>/dev/null; }
        done
    fi
    if ! command -v java >/dev/null 2>&1; then
        log "WRN" "install: java ausente tras instalar; reintentando con fix-broken"
        apt-get install -y -f "$JPKG" >> "$INSTALL_LOG" 2>&1
    fi
    if ! command -v java >/dev/null 2>&1; then
        log "ERR" "install: java no quedó instalado — ver install.log (causa típica: falta de espacio en /data)"
        state_set_error "install: java ($JPKG) no se pudo instalar"
        exit 1
    fi
    log "OK"  "install: java $(java -version 2>&1 | head -1)"

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
            # NOTE on layout: the current fabric-installer downloads the VANILLA
            # game jar to <dir>/server.jar (verified against upstream source);
            # old installers put it in libraries/server-*-*.jar. The launch jar
            # needs the vanilla jar intact, so server.jar must NEVER be
            # overwritten with fabric-server-launch.jar.
            local intento FABRIC_OK=0 LEGACY_JAR=""
            for intento in 1 2 3; do
                log "INF" "install: fabric installer attempt $intento/3"
                java -jar "$SERVER_DIR/fabric-installer.jar" server \
                    -mcversion "$VERSION" -loader "$LOADER_VER" -downloadMinecraft -dir "$SERVER_DIR" >> "$INSTALL_LOG" 2>&1
                if [ -s "$SERVER_DIR/fabric-server-launch.jar" ] && [ -s "$SERVER_DIR/server.jar" ]; then
                    FABRIC_OK=1; break
                fi
                LEGACY_JAR=$(find "$SERVER_DIR/libraries" \( -name "server-*-*.jar" -o -name "minecraft-server-*.jar" \) 2>/dev/null | head -1)
                if [ -s "$SERVER_DIR/fabric-server-launch.jar" ] && [ -n "$LEGACY_JAR" ] && [ -s "$LEGACY_JAR" ]; then
                    ln -sf fabric-server-launch.jar "$SERVER_DIR/server.jar" 2>/dev/null \
                      || cp "$SERVER_DIR/fabric-server-launch.jar" "$SERVER_DIR/server.jar"
                    FABRIC_OK=1; break
                fi
                rm -rf "$SERVER_DIR/libraries" "$SERVER_DIR/versions" 2>/dev/null
                sleep 2
            done
            [ "$FABRIC_OK" -ne 1 ] && { state_set_error "install: fabric vanilla download failed after 3 attempts"; exit 1; }
            rm -f "$SERVER_DIR/fabric-installer.jar"
            log "OK" "install: fabric ready (launch jar + vanilla jar)"
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
    if [ -z "${RAM_MIN:-}" ] || [ -z "${RAM_MAX:-}" ]; then
        RAM_MIN="${RAM_MIN:-$(state_field .ram_min | sed 's/^null$//')}"
        RAM_MAX="${RAM_MAX:-$(state_field .ram_max | sed 's/^null$//')}"
        [ -z "${RAM_MIN:-}" ] || [ -z "${RAM_MAX:-}" ] && detect_ram
    fi
    RAM_MIN="${RAM_MIN:-256M}"; RAM_MAX="${RAM_MAX:-1G}"

    echo "eula=true" > "$SERVER_DIR/eula.txt"
    : > "$CONSOLE_LOG"   # fresh console per run
    command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

    cd "$SERVER_DIR" || { state_set_error "start: cannot cd $SERVER_DIR"; exit 1; }
    local RUN_CMD JVM_FLAGS="-Xms$RAM_MIN -Xmx$RAM_MAX -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
    if [ -f "run.sh" ]; then
        # Forge/NeoForge: honor RAM via user_jvm_args.txt (run.sh reads it)
        echo "$JVM_FLAGS" > user_jvm_args.txt 2>/dev/null || true
        RUN_CMD="bash run.sh nogui >> '$CONSOLE_LOG' 2>&1"
    elif [ "${LOADER:-}" = "fabric" ] && [ -f "fabric-server-launch.jar" ]; then
        RUN_CMD="java $JVM_FLAGS -jar fabric-server-launch.jar nogui >> '$CONSOLE_LOG' 2>&1"
    else
        RUN_CMD="java $JVM_FLAGS -jar server.jar nogui >> '$CONSOLE_LOG' 2>&1"
    fi
    tmux new-session -d -s "$TMUX_SESSION" "$RUN_CMD" || { state_set_error "start: tmux failed"; exit 1; }
    sleep 1
    # The server's stdout/stderr is already redirected to $CONSOLE_LOG above.
    # Don't add a pipe-pane: it would write the same bytes a second time and
    # (because of buffering) scramble the tail the app reads.
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
    if pgrep -f "java.*(fabric-server-launch|/server\.jar| mcserver)" >/dev/null 2>&1; then
        pkill -f "java.*(fabric-server-launch|/server\.jar| mcserver)" 2>/dev/null
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
    playit_digest
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
    # Flush world state to disk first so the archive captures consistent data.
    # mc-server has no RCON plumbing for `save-all`; we send through tmux only
    # when the server is actually running, and only if it's an MC-style server
    # (Fabric/Forge/NeoForge all support the console command).
    if server_running 2>/dev/null; then
        tmux send-keys -t "$TMUX_SESSION" "save-all flush" Enter 2>/dev/null || true
        sleep 2
    fi
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
        # Preserve the file so the user can inspect it; rename rather than delete.
        mv "$SRC" "${SRC}.invalid" 2>/dev/null
        state_set_error "mod-install: no es un jar válido (renombrado a .invalid)"; exit 1
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

# Edit server.properties:  prop <key> <value> [<key> <value> ...]
cmd_prop() {
    [ $# -ge 2 ] && [ $(( $# % 2 )) -eq 0 ] || { state_set_error "prop: esperaba clave valor [clave valor…]"; exit 1; }
    [ -f "$CONFIG_FILE" ] && . "$CONFIG_FILE"
    [ -d "$SERVER_DIR" ] || { state_set_error "prop: aún no hay servidor instalado"; exit 1; }
    local F="$SERVER_DIR/server.properties"
    [ -f "$F" ] || touch "$F"
    while [ $# -gt 0 ]; do
        local K="$1" V="$2"; shift 2
        case "$K" in *[!A-Za-z0-9_.-]*) state_set_error "prop: clave inválida: $K"; exit 1 ;; esac
        V=${V//&/\&}   # escape & and | for sed's replacement side
        V=${V//|/\|}
        if grep -qE "^[# ]*${K}=" "$F"; then
            sed -i "s|^[# ]*${K}=.*|${K}=${V}|" "$F"
        else
            printf '%s=%s\n' "$K" "$V" >> "$F"
        fi
        log "OK" "prop: $K=$V"
        # Mirror key knobs into state.json so the app shows the live value.
        # Add more mappings here as the UI starts to read them.
        if [ "$K" = "server-port" ]; then
            write_state ".port = ${V%% *}"
        fi
    done
    write_state '.last_action = "prop" .last_error = null'
}

# Change how much RAM the server may use (applies on next start):
#   ram-set <min> <max>   (e.g. ram-set 512M 2G)
cmd_ram_set() {
    local MIN="${1:-}" MAX="${2:-}" v
    [ -n "$MIN" ] && [ -n "$MAX" ] || { state_set_error "ram-set: mínimo y máximo requeridos"; exit 1; }
    for v in "$MIN" "$MAX"; do
        case "$v" in [0-9]*[MG]) ;; *) state_set_error "ram-set: formato inválido (ej. 512M o 1G)"; exit 1 ;; esac
    done
    write_state ".ram_min = \"$MIN\" .ram_max = \"$MAX\" .last_action = \"ram-set\" .last_error = null"
    log "OK" "ram-set: $MIN/$MAX (aplica en el próximo arranque)"
}

# Detect the claim URL / public address from the agent log so the app can
# show the user what to do (open claim link, then copy the address).
playit_digest() {
    local RUN=false CLAIM="" ADDR="" SECRET=false
    tmux has-session -t "$PLAYIT_SESSION" 2>/dev/null && RUN=true
    # A saved secret is the prerequisite for v1.0.x to connect.
    [ -s "$HOME_DIR/.config/playit_gg/playit.toml" ] && \
        grep -qE '^secret[[:space:]]*=' "$HOME_DIR/.config/playit_gg/playit.toml" 2>/dev/null && \
        SECRET=true
    # Only meaningful while the agent is up: a stopped session means no
    # tunnel, so the app must not keep showing a stale claim/address.
    if [ "$RUN" = "true" ]; then
        # legacy playit writes the claim URL to a file it owns
        if [ -f "$HOME_DIR/.playit/claim_url" ]; then
            CLAIM=$(head -1 "$HOME_DIR/.playit/claim_url" 2>/dev/null)
        fi
        if [ -f "$TUNNEL_LOG" ]; then
            # modern claim codes contain dashes (abc-def-123)
            [ -z "$CLAIM" ] && CLAIM=$(grep -oE 'https://playit\.gg/claim/[A-Za-z0-9_?=&%.-]+' "$TUNNEL_LOG" 2>/dev/null | head -1)
            # modern addresses: tcp://h:port, *.playit.gg, *.ply.gg, *.joinmc.link — often bare
            ADDR=$(grep -oE '(tcp|udp|https?)://[^[:space:]]+|[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?\.(at\.ply\.gg|ply\.gg|playit\.gg|joinmc\.link)(:[0-9]+)?' "$TUNNEL_LOG" 2>/dev/null | grep -v '/claim/' | sed -e 's/[.,;:!?)]*$//' | tail -1)
        fi
    fi
    local upd=".playit.running = $RUN | .playit.secret = $SECRET"
    # address wins over claim: after the user claims, the log keeps the old
    # claim line, so preferring claim would hide the ready address forever.
    if [ -n "$ADDR" ]; then
        upd="$upd | .playit.claimed = true  | .playit.address = \"$ADDR\""
    elif [ -n "$CLAIM" ]; then
        upd="$upd | .playit.claimed = false | .playit.address = \"$CLAIM\""
    else
        upd="$upd | .playit.claimed = null  | .playit.address = null"
    fi
    write_state "$upd"
}

cmd_playit_start() {
    # auto-install the agent if it is missing (first run may have had no net)
    if ! command -v playit >/dev/null 2>&1 && ! command -v playitd >/dev/null 2>&1; then
        log "INF" "playit-start: agente no instalado, instalando…"
        pkg install -y tur-repo >> "$INSTALL_LOG" 2>&1 || true
        pkg update -y >> "$INSTALL_LOG" 2>&1 || true
        pkg install -y playit >> "$INSTALL_LOG" 2>&1 || log "ERR" "playit-start: la instalación del agente falló"
    fi
    command -v playit  >/dev/null 2>&1 && PLAYIT_BIN="playit"
    command -v playitd >/dev/null 2>&1 && PLAYIT_BIN="playitd"
    if [ -z "$PLAYIT_BIN" ]; then
        state_set_error "No se pudo iniciar playit: el agente no se instaló (revisa la conexión e inténtalo otra vez)"
        exit 1
    fi
    # v1.0.x rejects to start without a secret_key unless it's already in
    # ~/.config/playit_gg/playit.toml (legacy) or supplied via a frontend IPC.
    # We require the secret in the toml file. The app's "Túnel playit.gg"
    # dialog writes it via cmd_playit_secret.
    if [ ! -s "$HOME_DIR/.config/playit_gg/playit.toml" ] || \
       ! grep -qE '^secret[[:space:]]*=' "$HOME_DIR/.config/playit_gg/playit.toml" 2>/dev/null; then
        state_set_error "Falta secret_key de playit.gg. Abre Ajustes → Túnel playit.gg y pega tu secret_key (playit.gg/account/agents)."
        exit 1
    fi
    tmux kill-session -t "$PLAYIT_SESSION" 2>/dev/null
    : > "$TUNNEL_LOG" 2>/dev/null || true
    # stdbuf avoids block-buffering when stdout goes to a file (the daemon
    # otherwise may sit in its IPC loop forever and the digest sees an empty log)
    local AGENT_PREFIX=""
    command -v stdbuf >/dev/null 2>&1 && AGENT_PREFIX="stdbuf -o0 -e0 "
    tmux new-session -d -s "$PLAYIT_SESSION" "${AGENT_PREFIX}$PLAYIT_BIN >> '$TUNNEL_LOG' 2>&1"
    # Give the daemon time to come up and fetch its tunnel from playit.gg's API.
    # With a valid secret this is usually 2-5 s; without one the daemon exits
    # within the first second ("no agent registered") and we surface that fast.
    local WAIT="${MC_PLAYIT_WAIT:-30}"
    local i=0 A
    while [ $i -lt "$WAIT" ]; do
        sleep 1; i=$((i + 1))
        if ! tmux has-session -t "$PLAYIT_SESSION" 2>/dev/null; then
            playit_digest
            state_set_error "playit se cerró solo al arrancar (revisa la conexión, que el secret_key sea válido y que el agent exista en playit.gg/account/agents)"
            exit 1
        fi
        playit_digest
        A=$(state_field .playit.address)
        [ "$A" != "null" ] && [ -n "$A" ] && break
    done
    A=$(state_field .playit.address)
    if [ "$A" = "null" ] || [ -z "$A" ]; then
        # The daemon didn't publish an address. Most common reason: the
        # secret_key is valid but the user hasn't created a Tunnel in the
        # playit.gg dashboard yet (this is the free-tier requirement).
        state_set_error "playit arrancó pero no publicó ningún enlace. Crea un Tunnel en playit.gg/account/tunnels apuntando al puerto ${port:-25565} (estado tiene claim_url si aún no has reclamado)"
        exit 1
    fi
    write_state '.last_action = "playit-start" .last_error = null'
    log "OK" "playit-start: session up"
}

# Save the user's playit.gg secret_key into the daemon's config file and
# record that fact in state.json (without ever exposing the key).
cmd_playit_secret() {
    local KEY="${1:-}"
    # playit secrets look like "playit_<38-44 base64url chars>"; allow either
    # the unprefixed form (some dashboards copy just the suffix) or the
    # already-prefixed form.
    case "$KEY" in
        playit_*|p_*[A-Za-z0-9_-]) ;;
        *) state_set_error "playit-secret: formato inválido"; exit 1 ;;
    esac
    mkdir -p "$HOME_DIR/.config/playit_gg"
    local F="$HOME_DIR/.config/playit_gg/playit.toml"
    if [ -f "$F" ]; then
        # preserve any other keys (e.g. an account_id set previously)
        sed -i.bak -E "s|^secret[[:space:]]*=.*|secret = \"$KEY\"|" "$F" 2>/dev/null || \
            printf 'secret = "%s"\n' "$KEY" >> "$F"
        rm -f "$F.bak"
    else
        printf 'secret = "%s"\n' "$KEY" > "$F"
    fi
    chmod 600 "$F"
    write_state ".playit.secret = true .last_action = \"playit-secret\" .last_error = null"
    log "OK" "playit-secret: saved (length=${#KEY})"
}

# Delete the saved secret so a fresh setup can happen.
cmd_playit_secret_clear() {
    rm -f "$HOME_DIR/.config/playit_gg/playit.toml"
    write_state '.playit.secret = false .last_action = "playit-secret-clear" .last_error = null'
    log "OK" "playit-secret-clear: removed"
}

cmd_playit_stop() {
    tmux kill-session -t "$PLAYIT_SESSION" 2>/dev/null
    playit_digest
    write_state '.last_action = "playit-stop" .last_error = null'
    log "OK" "playit-stop: session down"
}

cmd_playit_status() {
    playit_digest
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
    prop)           cmd_prop "$@" ;;
    ram-set)        cmd_ram_set "$@" ;;
    playit-start)        cmd_playit_start "$@" ;;
    playit-stop)         cmd_playit_stop "$@" ;;
    playit-status)       cmd_playit_status "$@" ;;
    playit-secret)       cmd_playit_secret "$@" ;;
    playit-secret-clear) cmd_playit_secret_clear "$@" ;;
    server-delete)       cmd_server_delete "$@" ;;
    *) state_set_error "unknown command: ${CMD:-<none>}"; exit 1 ;;
esac
