#!/usr/bin/env bash
# Drives the 귀가온 demo on the Pleos Connect Emulator: GPS via `adb emu geo fix`,
# gear / speed / turn signal / seats via the Virtual Console HTTP API (port 8088).
#
# Route (Seoul): 강남역 → south on 강남대로 → detour east (route deviation) →
# long stop → back → 양재역 (destination). The navigation app on the emulator
# doesn't follow injected GPS, so the app's own monitoring uses these fixes.
#
# Usage:
#   scripts/demo-route.sh run [--kmh 40] [--stop-sec 360]   full scenario
#   scripts/demo-route.sh start                              place car at 강남역, parked, passengers seated
#   scripts/demo-route.sh sos                                turn-signal SOS pattern (L,R,L,R,L: 4 direction changes in ~3 s)
#   scripts/demo-route.sh seats empty|occupied               passenger + rear seats
#   scripts/demo-route.sh park                               speed 0, gear P
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
CONSOLE="${CONSOLE:-http://127.0.0.1:8088}"

# Destination the app should be given (양재역).
DEST_LAT=37.48460
DEST_LNG=127.03420

# Legs: "lat lng" waypoints. The detour leg moves away from the destination.
START="37.49794 127.02762"   # 강남역
LEG_SOUTH="37.49130 127.03070"
DETOUR="37.49520 127.04300"  # long stop happens here (> 500 m from destination)
BACK="37.48900 127.03600"
DEST="$DEST_LAT $DEST_LNG"

console() { # console <path?query>
  curl -sf -X PUT "$CONSOLE$1" -o /dev/null || { echo "Virtual Console not reachable at $CONSOLE (run: $ADB forward tcp:8088 tcp:8088)" >&2; exit 1; }
}
gear() { console "/vehicle/gear?gear=$1"; echo "gear $1"; }
# Speed is cosmetic (the app measures GPS), so a dropped console mid-route only warns: the Virtual Console page
# stops answering when its browser tab is in the background, and the route must still play out.
speed() {
  curl -sf -m 5 -X PUT "$CONSOLE/vehicle/controls?speed=$1&accelerator=0&brake=0&steering=0" -o /dev/null ||
    echo "warning: speed $1 not set (Virtual Console not answering; keep its page in front)" >&2
}
turn() { console "/vehicle/turn-signal?mode=$1"; }
seat() { console "/vehicle/seat?area=$1&kind=occupied&value=$2"; }
fix() { "$ADB" emu geo fix "$2" "$1" >/dev/null; } # fix <lat> <lng>  (adb takes lng first)

seats() {
  local v; [[ "$1" == empty ]] && v=false || v=true
  for a in passenger rear_left rear_right; do seat "$a" "$v"; done
  echo "passenger seats $1"
}

# drive <from "lat lng"> <to "lat lng"> <kmh>: one fix per second along a straight line.
drive() {
  local from=($1) to=($2) kmh=$3
  speed "$kmh"
  awk -v la1="${from[0]}" -v lo1="${from[1]}" -v la2="${to[0]}" -v lo2="${to[1]}" -v kmh="$kmh" 'BEGIN {
    pi = 3.14159265358979; r = 6371000
    dy = (la2 - la1) * pi / 180 * r
    dx = (lo2 - lo1) * pi / 180 * r * cos(la1 * pi / 180)
    d = sqrt(dx * dx + dy * dy); step = kmh / 3.6
    n = int(d / step); if (n < 1) n = 1
    for (i = 1; i <= n; i++) printf "%.6f %.6f\n", la1 + (la2 - la1) * i / n, lo1 + (lo2 - lo1) * i / n
  }' | while read -r lat lng; do
    fix "$lat" "$lng"
    sleep 1
  done
}

hold() { # hold <"lat lng"> <seconds>: stationary, keep sending fixes
  local p=($1) s
  speed 0
  for ((s = 0; s < $2; s++)); do fix "${p[0]}" "${p[1]}"; sleep 1; done
}

start() {
  "$ADB" forward tcp:8088 tcp:8088 >/dev/null
  speed 0; gear Park; turn Off
  seats occupied
  local p=($START); fix "${p[0]}" "${p[1]}"
  echo "car at 강남역, parked. Destination for the app: 양재역 ($DEST_LAT, $DEST_LNG)"
}

sos() {
  for m in BlinkLeft BlinkRight BlinkLeft BlinkRight BlinkLeft; do turn "$m"; sleep 0.7; done
  turn Off
  echo "turn-signal SOS pattern sent"
}

run() {
  local kmh=40 stop_sec=360
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --kmh) kmh=$2; shift 2 ;;
      --stop-sec) stop_sec=$2; shift 2 ;;
      *) echo "unknown option $1" >&2; exit 2 ;;
    esac
  done
  start
  sleep 2
  gear Drive
  echo "1/4 driving south on 강남대로"; drive "$START" "$LEG_SOUTH" "$kmh"
  echo "2/4 detour east (deviation)"; drive "$LEG_SOUTH" "$DETOUR" "$kmh"
  echo "3/4 long stop ${stop_sec}s"; hold "$DETOUR" "$stop_sec"
  echo "4/4 back toward 양재역"; drive "$DETOUR" "$BACK" "$kmh"; drive "$BACK" "$DEST" "$kmh"
  speed 0; gear Park
  echo "arrived at 양재역 and parked; passengers still seated. Run '$0 seats empty' to end the safe-arrival check."
}

case "${1:-}" in
  run) shift; run "$@" ;;
  start) start ;;
  sos) sos ;;
  seats) seats "${2:?empty|occupied}" ;;
  park) speed 0; gear Park ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
