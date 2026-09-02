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
  send-keys|pipe-pane|list-sessions) exit 0 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$STUB/tmux"
for cmd in pkg termux-wake-lock termux-wake-unlock pgrep pkill; do
  printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/$cmd"
  chmod +x "$STUB/$cmd"
done
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
run send "say hi"
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'not running' && PASS "send stopped rejects" || FAIL "send stopped"
run frobnicate
echo "$(jq -r '.last_error' "$STATE")" | grep -q 'unknown command' && PASS "unknown command rejects" || FAIL "unknown command"
[ "$(find "$MC_SHARED" -name '.state.json.tmp' | wc -l)" -eq 0 ] && PASS "atomic temp cleaned" || FAIL "atomic temp remains"

printf '\nFailures: %s\n' "$FAILS"
[ "$FAILS" -eq 0 ] && printf 'ALL TESTS PASSED\n'
rm -rf "$TMP"
exit "$FAILS"
