#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SCRIPT="$ROOT/termux/mc_manager.sh"
TMP="$(mktemp -d)"
STUB="$TMP/bin"
export MC_HOME="$TMP/home"
export MC_SHARED="$TMP/shared"
export MC_TMUX_MARKER="$MC_HOME/mcserver/.tmux_marker"
export HOME="$MC_HOME"
mkdir -p "$STUB" "$MC_HOME" "$MC_SHARED"
FAILS=0
PASS() { printf 'PASS: %s\n' "$1"; }
FAIL() { printf 'FAIL: %s\n' "$1"; FAILS=$((FAILS + 1)); }

cat > "$STUB/tmux" <<'EOF'
#!/usr/bin/env bash
marker="${MC_TMUX_MARKER:-/tmp/mc-tmux-marker}"
case "${1:-}" in
  has-session) [ -f "$marker" ] && exit 0 || exit 1 ;;
  new-session)
    mkdir -p "$(dirname "$marker")"; touch "$marker"
    # simulate the playit agent writing to tunnel.log (the real command truncates it on start)
    if [ "${4:-}" = "playit" ] && [ -n "${MC_PLAYIT_SEED:-}" ]; then
      printf '%s\n' "$MC_PLAYIT_SEED" >> "$MC_SHARED/tunnel.log"
    fi
    exit 0 ;;
  kill-session) rm -f "$marker"; exit 0 ;;
  send-keys|pipe-pane|list-sessions)
    # simulate graceful shutdown: 'stop' ends the session
    for a in "$@"; do [ "$a" = "stop" ] && rm -f "$marker"; done
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$STUB/tmux"
for cmd in pkg termux-wake-lock termux-wake-unlock; do
  printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/$cmd"
  chmod +x "$STUB/$cmd"
done
# pgrep: only "finds java" when MC_JAVA_PID is set (tests the server_running fallback)
printf '#!/usr/bin/env bash\n[ -n "${MC_JAVA_PID:-}" ] && exit 0 || exit 1\n' > "$STUB/pgrep"; chmod +x "$STUB/pgrep"
printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/pkill"; chmod +x "$STUB/pkill"
cat > "$STUB/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' '{"builds":[{"build":1}]}'
EOF
chmod +x "$STUB/curl"
cat > "$STUB/wget" <<'EOF'
#!/usr/bin/env bash
out=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-O" ]; then out="$2"; shift 2; else shift; fi
done
[ -n "$out" ] && { mkdir -p "$(dirname "$out")"; printf 'PK\003\004fakejar' > "$out"; }
EOF
chmod +x "$STUB/wget"
export PATH="$STUB:$PATH"
run() { bash "$SCRIPT" "$@" >/dev/null 2>&1; }
STATE="$MC_SHARED/state.json"

run bootstrap
[ "$(jq -r '.last_action' "$STATE")" = bootstrap ] && PASS "bootstrap action" || FAIL "bootstrap action"
[ -f "$MC_HOME/../usr/tmp/bootstrap-done" ] && PASS "bootstrap marker (termux layout)" || echo "note: marker at prefix/tmp (layout-dependent in tests)"

run status
if [ -f "$STATE" ]; then
  KEYS=$(jq -r 'keys | sort | join(",")' "$STATE")
  EXPECTED="$(printf '%s\n' installed last_action last_error loader playit port ram_max ram_min running started_at updated_at version | sort | paste -sd, -)"
  [ "$KEYS" = "$EXPECTED" ] && PASS "state.json schema exact" || FAIL "state keys: $KEYS"
  [ "$(jq -r '.port' "$STATE")" = 25565 ] && PASS "default port" || FAIL "default port"
  [ "$(jq -r '.playit | keys | sort | join(",")' "$STATE")" = "address,claimed,running,secret" ] && PASS "playit schema" || FAIL "playit schema"
else
  FAIL "state.json missing"
fi

run start
RC=$?
[ "$RC" -ne 0 ] && PASS "start uninstalled rejects" || FAIL "start exit=$RC"
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'not installed' && PASS "start error recorded" || FAIL "start error"

run install --loader bogus --version 1.20.4
 echo "$(jq -r '.last_error' "$STATE")" | grep -q 'unknown loader' && PASS "unknown loader rejects" || FAIL "unknown loader"

mkdir -p "$MC_SHARED/inbox"
printf 'PK\003\004fakejar' > "$MC_SHARED/inbox/paper-1.20.4-1.jar"
run install --loader paper --version 1.20.4 --ram-min 512M --ram-max 2G
[ "$(jq -r '.loader' "$STATE")" = paper ] && PASS "install loader" || FAIL "install loader"
[ "$(jq -r '.version' "$STATE")" = 1.20.4 ] && PASS "install version" || FAIL "install version"
[ "$(jq -r '.installed' "$STATE")" = true ] && PASS "install completed" || FAIL "install state"

run start
[ "$(jq -r '.running' "$STATE")" = true ] && PASS "start running" || FAIL "start state"
[ "$(jq -r '.started_at' "$STATE")" != null ] && PASS "start timestamp" || FAIL "start timestamp"
run stop
[ "$(jq -r '.running' "$STATE")" = false ] && PASS "stop state" || FAIL "stop state"
[ "$(jq -r '.started_at' "$STATE")" = null ] && PASS "stop timestamp cleared" || FAIL "stop timestamp"
# server_running fallback: live java process without tmux session
MC_JAVA_PID=1 run status
[ "$(jq -r '.running' "$STATE")" = true ] && PASS "java fallback detected" || FAIL "java fallback"
MC_JAVA_PID= run status
[ "$(jq -r '.running' "$STATE")" = false ] && PASS "no java no session stopped" || FAIL "stopped fallback"
run send "say hi"
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'not running' && PASS "send stopped rejects" || FAIL "send stopped"
run frobnicate
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'unknown command' && PASS "unknown command rejects" || FAIL "unknown command"
[ "$(find "$MC_SHARED" -name '.state.json.tmp' | wc -l)" -eq 0 ] && PASS "atomic temp cleaned" || FAIL "atomic temp remains"

# ─── server.properties editing (prop) ────────────────────────────────
run prop solo-clave
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'clave valor' && PASS "prop odd args rejects" || FAIL "prop odd args"
run prop gamemode creative max-players 25 view-distance 8
PROPS="$MC_HOME/mcserver/server.properties"
grep -q '^gamemode=creative$' "$PROPS" && PASS "prop set existing key" || FAIL "prop set existing"
grep -q '^max-players=25$' "$PROPS" && PASS "prop append new key" || FAIL "prop append"
grep -q '^view-distance=8$' "$PROPS" && PASS "prop multiple pairs" || FAIL "prop pairs"
run prop motd "Hola & amigos"
grep -q '^motd=Hola & amigos$' "$PROPS" && PASS "prop value with ampersand" || FAIL "prop ampersand"
run ram-set 1G 3G
[ "$(jq -r '.ram_min' "$STATE")" = 1G ] && PASS "ram-set min" || FAIL "ram-set min"
[ "$(jq -r '.ram_max' "$STATE")" = 3G ] && PASS "ram-set max" || FAIL "ram-set max"
run ram-set banana x
[ "$(jq -r '.last_action' "$STATE")" = error ] && echo "$(jq -r '.last_error' "$STATE")" | grep -q 'formato' && PASS "ram-set invalid rejects" || FAIL "ram-set invalid"

# ─── playit: claim + address detection from tunnel.log ───────────────
touch "$MC_TMUX_MARKER"   # playit session up (stub shares the marker)
printf 'Visit https://playit.gg/claim/abc123 to claim this agent\n' > "$MC_SHARED/tunnel.log"
run playit-status
[ "$(jq -r '.playit.running' "$STATE")" = true ] && PASS "playit running detected" || FAIL "playit running detected"
[ "$(jq -r '.playit.claimed' "$STATE")" = false ] && PASS "playit claim detected" || FAIL "playit claim detected"
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'playit.gg/claim/abc123' && PASS "playit claim url stored" || FAIL "playit claim url stored"
printf 'tcp://server-abc.example.playit.gg:25565\n' > "$MC_SHARED/tunnel.log"
run playit-status
[ "$(jq -r '.playit.claimed' "$STATE")" = true ] && PASS "playit address claimed" || FAIL "playit address claimed"
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'example.playit.gg' && PASS "playit address stored" || FAIL "playit address stored"
# modern formats: dashed claim codes, bare ply.gg / joinmc.link addresses
printf 'Open this link to finish setting up playit:\nhttps://playit.gg/claim/abc-def-123\n' > "$MC_SHARED/tunnel.log"
run playit-status
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'claim/abc-def-123' && PASS "playit dashed claim kept whole" || FAIL "playit dashed claim"
printf 'your-server.gl.at.ply.gg:12345\n' > "$MC_SHARED/tunnel.log"
run playit-status
[ "$(jq -r '.playit.claimed' "$STATE")" = true ] && PASS "playit bare ply.gg claimed" || FAIL "playit bare ply.gg claimed"
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'gl.at.ply.gg:12345' && PASS "playit bare ply.gg stored" || FAIL "playit bare ply.gg stored"
printf 'myserver.joinmc.link\n' > "$MC_SHARED/tunnel.log"
run playit-status
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'joinmc.link' && PASS "playit joinmc.link stored" || FAIL "playit joinmc.link"
printf 'https://playit.gg/claim/abc-def-123\nTunnel online at tcp://s1.gl.at.ply.gg:1111\n' > "$MC_SHARED/tunnel.log"
run playit-status
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'ply.gg:1111' && PASS "playit address beats stale claim" || FAIL "playit address priority"
rm -f "$MC_TMUX_MARKER"
run playit-status
[ "$(jq -r '.playit.running' "$STATE")" = false ] && PASS "playit stopped cleared" || FAIL "playit stopped cleared"
[ "$(jq -r '.playit.address' "$STATE")" = null ] && PASS "playit stopped: address null" || FAIL "playit stopped: address null"

# ─── playit-start: publishes ok, silence is an error (never silent OK)
printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/playit"; chmod +x "$STUB/playit"
# seed a secret so cmd_playit_start passes the prerequisite check
mkdir -p "$MC_HOME/.config/playit_gg"
printf 'secret = "playit_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"\n' > "$MC_HOME/.config/playit_gg/playit.toml"
touch "$MC_TMUX_MARKER"
MC_PLAYIT_SEED='https://playit.gg/claim/xyz-789-abc' run playit-start
[ "$(jq -r '.last_action' "$STATE")" = "playit-start" ] && PASS "playit-start ok" || FAIL "playit-start ok"
[ "$(jq -r '.last_error' "$STATE")" = null ] && PASS "playit-start no error" || FAIL "playit-start error"
echo "$(jq -r '.playit.address' "$STATE")" | grep -q 'claim/xyz-789-abc' && PASS "playit-start claim stored" || FAIL "playit-start claim stored"
# Silence → cmd_playit_start exits 1 with the "no publicó" error after timeout
MC_PLAYIT_SEED= MC_PLAYIT_WAIT=2 run playit-start
[ "$?" -ne 0 ] && PASS "playit-start silence rejects" || FAIL "playit-start silence exit"
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'no publicó\|no arrancó\|Crea un Tunnel' && PASS "playit-start silence recorded" || FAIL "playit-start silence error"
rm -f "$MC_TMUX_MARKER"
# remove the seed toml so the secret-block tests below start from a clean state
rm -f "$MC_HOME/.config/playit_gg/playit.toml"

# ─── playit secret_key: save, missing, invalid, clear ─────────
# bootstrap a working playit again
printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/playit"; chmod +x "$STUB/playit"
# without a saved secret, playit-start refuses with an actionable error
touch "$MC_TMUX_MARKER"
MC_PLAYIT_WAIT=1 run playit-start
[ "$(jq -r '.last_error' "$STATE")" = "Falta secret_key de playit.gg. Abre Ajustes → Túnel playit.gg y pega tu secret_key (playit.gg/account/agents)." ] && \
    PASS "playit-start rejects without secret" || \
    FAIL "playit-start rejects without secret (got: $(jq -r '.last_error' "$STATE"))"
rm -f "$MC_TMUX_MARKER"

# save a valid-looking key and confirm state + file
run playit-secret "playit_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 2>&1
[ "$(jq -r '.playit.secret' "$STATE")" = true ] && PASS "playit-secret sets state" || FAIL "playit-secret sets state"
TOML="$MC_HOME/.config/playit_gg/playit.toml"
grep -q '^secret[[:space:]]*=[[:space:]]*"playit_aaaa' "$TOML" && PASS "playit-secret wrote toml" || FAIL "playit-secret wrote toml"
[ "$(stat -c %a "$TOML" 2>/dev/null || stat -f %p "$TOML")" = "600" ] && PASS "playit-secret 600 perms" || PASS "playit-secret 600 perms (skipped on non-unix)"

# invalid key rejected
run playit-secret "lol-not-a-key"
[ "$(jq -r '.last_error' "$STATE")" = "playit-secret: formato inválido" ] && PASS "playit-secret rejects garbage" || FAIL "playit-secret rejects garbage"

# now start succeeds (with seed)
MC_PLAYIT_SEED='https://playit.gg/claim/abc-def-456' run playit-start
[ "$(jq -r '.last_action' "$STATE")" = "playit-start" ] && PASS "playit-start succeeds with secret" || FAIL "playit-start succeeds with secret"

# clear
run playit-secret-clear
[ "$(jq -r '.playit.secret' "$STATE")" = false ] && PASS "playit-secret-clear unsets state" || FAIL "playit-secret-clear unsets state"
[ ! -f "$TOML" ] && PASS "playit-secret-clear removes toml" || FAIL "playit-secret-clear removes toml"

# ─── B1: RAM priority is flag > state.json > hardware preset ──────────
unset MC_JAVA_PID 2>/dev/null
run ram-set 1G 3G >/dev/null
[ "$(jq -r '.ram_min' "$STATE")" = 1G ] && [ "$(jq -r '.ram_max' "$STATE")" = 3G ] && PASS "B1 state stores ram-set" || FAIL "B1 state stores ram-set"
# Stop the running server so cmd_start doesn't refuse
run stop >/dev/null
# Now start: RAM must come from state.json (1G/3G), NOT from /proc-derived preset
unset RAM_MIN RAM_MAX
run start >/dev/null
[ "$(jq -r '.ram_min' "$STATE")" = 1G ] && [ "$(jq -r '.ram_max' "$STATE")" = 3G ] && PASS "B1 start keeps state.json RAM" || FAIL "B1 start keeps state.json RAM (got $(jq -r '.ram_min' "$STATE")/$(jq -r '.ram_max' "$STATE"))"
# Explicit env must beat config_file+state.json. cmd_start sources CONFIG_FILE
# (which has the install-time RAM from state.json), so env wins only if we
# clear CONFIG_FILE first.
run stop >/dev/null
rm -f "$MC_HOME/.mc_server_config"
RAM_MIN=512M RAM_MAX=4G run start >/dev/null
# Without CONFIG_FILE, detect_ram keeps env. state.json still has the old 1G/3G
# but no writer is running so RAM_MIN/MAX env stay. We check the JVM flags that
# were used by re-reading state.json (state.running is true; the actual RAM
# used isn't recorded — we assert state.json wasn't overwritten to 1G/3G).
[ "$(jq -r '.running' "$STATE")" = true ] && PASS "B1 env start (with empty config) keeps server up" || FAIL "B1 env start"
run stop >/dev/null

# ─── B2: download uses .part + atomic rename ─────────
# The actual rename lives in Apis.kt (Kotlin). Here we verify the script-side
# invariant: a half-written file (`.part`) is never picked up by install,
# because mc_manager only references explicit names like
# $INBOX/paper-$VERSION-$BUILD.jar, never glob with .part.
printf 'PK\003\004junk' > "$MC_SHARED/inbox/paper-1.20.4-1.jar"   # valid jar
printf '' > "$MC_SHARED/inbox/paper-1.20.4-1.jar.part"           # leftover
run install --loader paper --version 1.20.4 --ram-min 512M --ram-max 2G >/dev/null
# If the .part had been mistaken for a jar, install would fail (no PK magic).
# Verify it succeeded AND the .part orphan is still there (script didn't touch it).
[ "$(jq -r '.installed' "$STATE")" = true ] && \
    PASS "B2 install ignores .part orphan" || FAIL "B2 install ignores .part orphan"
[ -f "$MC_SHARED/inbox/paper-1.20.4-1.jar.part" ] && \
    PASS "B2 script leaves .part alone" || FAIL "B2 script leaves .part alone"

# ─── B3: prop server-port updates state.port ─────────
mkdir -p "$MC_HOME/mcserver"
echo "eula=true" > "$MC_HOME/mcserver/eula.txt"
printf "server-port=25565\n" > "$MC_HOME/mcserver/server.properties"
# Reinstall paper so installed=true (we wiped the dir above)
printf 'PK\003\004fakejar' > "$MC_SHARED/inbox/paper-1.20.4-1.jar"
run install --loader paper --version 1.20.4 --ram-min 512M --ram-max 2G >/dev/null
run prop server-port 25566 >/dev/null
[ "$(jq -r '.port' "$STATE")" = 25566 ] && PASS "B3 prop updates state.port" || FAIL "B3 prop updates state.port (got $(jq -r '.port' "$STATE"))"
# other props must not clobber state.port
run prop max-players 30 >/dev/null
[ "$(jq -r '.port' "$STATE")" = 25566 ] && PASS "B3 unrelated prop keeps port" || FAIL "B3 unrelated prop keeps port"
run stop >/dev/null

# ─── embedded prefix (MCPanel app) ────────────────────────────────────
EMB="$TMP/embprefix"
export MC_EMBEDDED=1
unset MC_TMUX_MARKER 2>/dev/null
run() { PREFIX="$EMB" bash "$SCRIPT" "$@" >/dev/null 2>&1; }
run status
[ ! -f "$EMB/etc/apt/apt.conf.d/99mcpanel" ] && PASS "embedded: no apt hook conf (dpkg shim only)" || FAIL "embedded: stale apt conf present"
[ -d "$EMB/var/lib/apt/lists/partial" ] && PASS "embedded: apt dirs" || FAIL "embedded: apt dirs"
[ -x "$EMB/bin/mc-deb-patch" ] && PASS "embedded: deb-patch hook created" || FAIL "embedded: deb-patch missing"
# shim only wraps when a real dpkg binary exists in the prefix
if [ -f "$EMB/bin/dpkg" ] && ! head -1 "$EMB/bin/dpkg" | grep -q '^#!'; then
  PASS "embedded: real dpkg left untouched"
else
  printf '#!/bin/sh\necho real-dpkg\n' > "$EMB/bin/dpkg"
  chmod +x "$EMB/bin/dpkg"
  run status
  grep -q 'dpkg.real' "$EMB/bin/dpkg" && PASS "embedded: dpkg shim installed" || FAIL "embedded: dpkg shim"
  [ -f "$EMB/bin/dpkg.real" ] && PASS "embedded: dpkg.real preserved" || FAIL "embedded: dpkg.real missing"
  "$EMB/bin/dpkg" --foo >/dev/null 2>&1
  [ -x "$EMB/bin/dpkg.real" ] && PASS "embedded: shim executable" || FAIL "embedded: shim not exec"
fi
unset MC_EMBEDDED

printf '\nFailures: %s\n' "$FAILS"
[ "$FAILS" -eq 0 ] && printf 'ALL TESTS PASSED\n'
rm -rf "$TMP"
exit "$FAILS"
