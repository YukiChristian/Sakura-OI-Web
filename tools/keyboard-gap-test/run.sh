#!/usr/bin/env bash
# Keyboard white-space test on the emulator: records each case and checks every frame (see README.md).
# Usage: bash run.sh [light|dark|both]
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8
# Windows path under Git Bash (python cannot open /d/... paths), plain path elsewhere.
T="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
OUT="$T/out"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb.exe"
PKG=com.sakura.webapp

a() { timeout 20 "$ADB" "$@"; }
launch() { a shell am start -n $PKG/.MainActivity >/dev/null 2>&1; }
keyboard_up() { a shell dumpsys input_method | grep -q 'mInputShown=true'; }
closekb() { keyboard_up && a shell input keyevent 4; sleep 0.6; }

devtools() {
  local pid; pid=$(a shell pidof $PKG | tr -d '\r')
  a forward tcp:9333 localabstract:webview_devtools_remote_$pid >/dev/null
}

# Center of the message box, in device pixels.
tap_composer() {
  devtools
  local xy; xy=$(python "$T/page.py" "(()=>{const b=document.getElementById('chat-input'); if(!b) return ''; const r=b.getBoundingClientRect(); return Math.round((r.left+r.width/2)*devicePixelRatio)+' '+Math.round((r.top+r.height/2)*devicePixelRatio)})()" | tr -d '"')
  [ -z "$xy" ] && { echo "sin cuadro de mensaje"; return 1; }
  a shell input tap $xy
}

# record <label> <seconds> <actions...>
record() {
  local label=$1 secs=$2; shift 2
  a shell rm -f /sdcard/kb.mp4
  timeout $((secs + 8)) "$ADB" shell screenrecord --time-limit "$secs" --bit-rate 12000000 /sdcard/kb.mp4 &
  local rec=$!
  sleep 1
  "$@"
  wait $rec 2>/dev/null
  sleep 1
  a pull /sdcard/kb.mp4 "$OUT/$label.mp4" >/dev/null 2>&1
  python "$T/analyze.py" "$OUT/$label.mp4" "$OUT/frames" "$label"
}

open_close() { tap_composer; sleep 1.6; a shell input keyevent 4; sleep 1.6; }
home_return() { tap_composer; sleep 1.4; a shell input keyevent 3; sleep 1.8; launch; sleep 2.6; }
recents_return() { tap_composer; sleep 1.4; a shell input keyevent 187; sleep 1.6; a shell input tap 540 1200; sleep 2.6; }
widget_new_chat() {
  a shell am start -a com.sakura.webapp.action.NEW_CHAT -n $PKG/.MainActivity --ez com.sakura.webapp.extra.FOCUS true >/dev/null 2>&1
  sleep 3.2; a shell input keyevent 4; sleep 1.6
}
twice() { tap_composer; sleep 1.2; a shell input keyevent 4; sleep 0.4; tap_composer; sleep 1.2; a shell input keyevent 4; sleep 1.4; }

# Waits (up to 30 s) until the page shows its message box: a cold start shows a white loading screen first.
wait_composer() {
  for i in $(seq 1 30); do
    devtools
    [ "$(python "$T/page.py" "!!document.getElementById('chat-input')" 2>/dev/null)" = "True" ] && return 0
    sleep 1
  done
  echo "sin cuadro de mensaje tras 30 s"
}

run_mode() {
  local mode=$1
  a shell cmd uimode night $([ "$mode" = dark ] && echo yes || echo no) >/dev/null
  sleep 2; launch; sleep 2; wait_composer; sleep 1; closekb
  record "${mode}_abrir_cerrar" 6 open_close; closekb
  record "${mode}_inicio_y_volver" 9 home_return; closekb
  record "${mode}_recientes_y_volver" 9 recents_return; closekb
  record "${mode}_nuevo_chat_widget" 8 widget_new_chat; closekb
  record "${mode}_dos_veces" 7 twice; closekb
}

rm -rf "$OUT"; mkdir -p "$OUT/frames"
case "${1:-both}" in
  light) run_mode light ;;
  dark) run_mode dark ;;
  *) run_mode light; run_mode dark ;;
esac
