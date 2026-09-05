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
  new-session) mkdir -p "$(dirname "$marker")"; touch "$marker"; exit 0 ;;
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
  [ "$(jq -r '.playit | keys | sort | join(",")' "$STATE")" = "address,claimed,running" ] && PASS "playit schema" || FAIL "playit schema"
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
rm -f "$MC_TMUX_MARKER"
run playit-status
[ "$(jq -r '.playit.running' "$STATE")" = false ] && PASS "playit stopped cleared" || FAIL "playit stopped cleared"
[ "$(jq -r '.playit.address' "$STATE")" = null ] && PASS "playit stopped: address null" || FAIL "playit stopped: address null"

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
