#!/usr/bin/env bash
# Stores your Vercel AI Gateway API key in openWisprFlow on a phone connected over adb.
#
#   scripts/set-api-key.sh              # key from $AI_GATEWAY_API_KEY, else asks for it
#   scripts/set-api-key.sh -s SERIAL   # pick a phone when several are connected
#
# Needs the debug build installed (it carries DebugApiKeyReceiver). The key is not printed,
# not logged, and not passed as a command-line argument on this Mac.
set -euo pipefail

PKG=app.openwisprflow
RECEIVER="$PKG/.DebugApiKeyReceiver"
ACTION="$PKG.debug.SET_API_KEY"

ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
[[ -x "$ADB" ]] || { echo "adb not found; set ADB=/path/to/adb" >&2; exit 1; }

adb_args=()
if [[ "${1:-}" == "-s" ]]; then
  [[ -n "${2:-}" ]] || { echo "usage: $0 [-s SERIAL]" >&2; exit 2; }
  adb_args=(-s "$2")
fi
adb() { "$ADB" "${adb_args[@]+"${adb_args[@]}"}" "$@"; }

state=$(adb get-state 2>/dev/null || true)
if [[ "$state" != "device" ]]; then
  if [[ ${#adb_args[@]} -gt 0 ]]; then
    echo "No phone with serial '${adb_args[1]}' is ready. Serials from 'adb devices':" >&2
    "$ADB" devices | awk 'NR>1 && NF {print "  " $1 " (" $2 ")"}' >&2
  else
    echo "No phone ready over adb (state: ${state:-none}). Check 'adb devices'." >&2
  fi
  exit 1
fi

adb shell pm path "$PKG" >/dev/null 2>&1 || { echo "$PKG is not installed on the phone." >&2; exit 1; }

key="${AI_GATEWAY_API_KEY:-}"
if [[ -z "$key" ]]; then
  read -r -s -p "AI Gateway API key: " key
  echo
fi
key="$(printf '%s' "$key" | tr -d '\r\n')"
[[ -n "$key" ]] || { echo "No key given." >&2; exit 1; }

# adb shell runs the command through the phone's shell, so quote the key for it.
quoted="'${key//\'/\'\\\'\'}'"

# -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES): reaches the app even if it has not been opened since install.
# The command goes to the phone's shell on stdin, not as an adb argument, so the key never shows
# in this Mac's process list. (On the phone, `am` holds it in its arguments for about a second.)
out=$(printf '%s\n' "am broadcast -f 0x20 -n $RECEIVER -a $ACTION --es key $quoted" | adb shell 2>&1)

if [[ "$out" == *"result=-1"* ]]; then
  echo "API key ${out##*data=}" | tr -d '"'
  echo "Open openWisprFlow and tap Test to check it with the gateway."
else
  [[ "$out" == *"data="* ]] && echo "Failed: ${out##*data=}" >&2 \
    || echo "Failed. Is the installed build a debug build with DebugApiKeyReceiver? adb said: ${out##*$'\n'}" >&2
  exit 1
fi
