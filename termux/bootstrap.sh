#!/data/data/com.termux/files/usr/bin/bash
set -e
termux-setup-storage
mkdir -p "$HOME/mcpanel"
# The user must enable external app intents manually before the Android app can control Termux:
printf '%s\n' 'echo allow-external-apps=true >> ~/.termux/termux.properties'
printf '%s\n' 'Run that command in Termux, then restart Termux.'
pkg update -y
pkg install -y wget curl tmux jq unzip termux-api
pkg install -y tur-repo || true
pkg update -y
pkg install -y playit || true
cp "$(dirname "$0")/mc_manager.sh" "$HOME/mcpanel/mc_manager.sh"
chmod 700 "$HOME/mcpanel/mc_manager.sh"
mkdir -p "$HOME/storage/shared/MCPanel/inbox"
"$HOME/mcpanel/mc_manager.sh" status
