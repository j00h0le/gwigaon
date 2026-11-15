#!/usr/bin/env bash
# Checks everything the emulator demo needs before a run or a recording, and says how to fix what's missing.
# Read-only: it starts nothing. Checkpoint F (F2) lost the AI report to a summary function that wasn't served,
# and the route script to a Virtual Console page that had gone to the background.
#
# Usage: scripts/demo-check.sh        exit 0 when everything is ready, 1 otherwise
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
CONSOLE="${CONSOLE:-http://127.0.0.1:8088}"
FUNCTIONS="${FUNCTIONS:-http://127.0.0.1:54321/functions/v1}"
APP=com.coral.gwigaon
missing=0

ok() { echo "ok    $1"; }
fail() { echo "FIX   $1"; echo "      → $2"; missing=1; }

if "$ADB" get-state >/dev/null 2>&1; then
  ok "emulator connected"
  user=$("$ADB" shell am get-current-user | tr -d '\r')
  if "$ADB" shell pm list packages --user "$user" | grep -q "package:$APP\$"; then
    ok "app installed (user $user)"
    # dumpsys lists grants per user; read only the current user's block.
    grants=$("$ADB" shell dumpsys package "$APP" | awk -v u="User $user:" '$1" "$2==u {on=1; next} /^    User [0-9]+:/ {on=0} on')
    for p in ACCESS_FINE_LOCATION RECORD_AUDIO; do
      if grep -q "$p: granted=true" <<<"$grants"; then ok "$p granted"
      else fail "$p not granted (connectedAndroidTest uninstalls the app and drops it)" \
        "$ADB shell pm grant --user $user $APP android.permission.$p"; fi
    done
  else
    fail "app not installed for user $user" "./gradlew installDebug"
  fi
else
  fail "no emulator" "~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2.1.0"
fi

if supabase status >/dev/null 2>&1; then ok "local Supabase stack running"
else fail "local Supabase stack not running" "supabase start"; fi

# Served: 4xx for a bare OPTIONS (405 seen). Not served: the gateway answers 503, or nothing answers (000).
code=$(curl -s -m 5 -o /dev/null -w "%{http_code}" -X OPTIONS "$FUNCTIONS/summarize-trip")
case "$code" in
  000|5*) fail "summarize-trip not served (http $code): the report shows 가져오지 못했어요" \
    "supabase functions serve summarize-trip --env-file supabase/.env.local   (keep it running)" ;;
  *) ok "summarize-trip served" ;;
esac

"$ADB" forward tcp:8088 tcp:8088 >/dev/null 2>&1 || true
body=$(curl -s -m 5 -X PUT "$CONSOLE/vehicle/turn-signal?mode=Off" || true)
if grep -q '"web_active":true' <<<"$body"; then ok "Virtual Console answering"
elif grep -q emulator_inactive <<<"$body"; then
  fail "Virtual Console page inactive: gear, seats and turn signals won't reach the car" \
    "open $CONSOLE/ in a browser and keep that tab in front during the run"
else
  fail "Virtual Console not reachable" "$ADB forward tcp:8088 tcp:8088, then open $CONSOLE/"
fi

[ "$missing" = 0 ] && echo "demo ready" || echo "demo not ready"
exit "$missing"
