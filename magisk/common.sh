#!/system/bin/sh
# Evolution Dock Control - shared helpers.
# Sourced by action.sh, service.sh, uninstall.sh and dockctl.
#
# Nothing here runs in a loop and nothing here stays resident: every function is a single
# synchronous command sequence.

STATE_DIR=/data/adb/evolution_dock_control
STATE_FILE=$STATE_DIR/state
DOCK_KEY=evolution_dock_hidden
LAUNCHER_PKGS="com.google.android.apps.nexuslauncher com.android.launcher3"

# --------------------------------------------------------------------- state

# Prints 1 (dock hidden) or 0 (dock visible). Defaults to 1 = module active.
dock_state() {
  if [ -f "$STATE_FILE" ]; then
    tr -d ' \n\r' < "$STATE_FILE" 2>/dev/null
  else
    echo 1
  fi
}

dock_write_state() {
  mkdir -p "$STATE_DIR" 2>/dev/null
  printf '%s\n' "$1" > "$STATE_FILE" 2>/dev/null
  chmod 0644 "$STATE_FILE" 2>/dev/null
  chown 0:0 "$STATE_FILE" 2>/dev/null
}

# Writes the flag the launcher-side hook reads. Root only.
dock_apply_setting() {
  settings put secure "$DOCK_KEY" "$1" 2>/dev/null
  [ "$(settings get secure "$DOCK_KEY" 2>/dev/null)" = "$1" ]
}

# ---------------------------------------------------------------- launcher

dock_launcher_running() {
  for p in $LAUNCHER_PKGS; do
    if pidof "$p" >/dev/null 2>&1; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

# Restarts the launcher so it re-reads the flag. One shot, no waiting loops.
dock_restart_launcher() {
  for p in $LAUNCHER_PKGS; do
    if pidof "$p" >/dev/null 2>&1; then
      am force-stop "$p" 2>/dev/null
    fi
  done
  sleep 1
  input keyevent KEYCODE_HOME 2>/dev/null
}

# ---------------------------------------------------------------- actions

# Applies the requested state; idempotent by design (second call is a no-op).
dock_apply() {
  target=$1
  current=$(dock_state)
  live=$(settings get secure "$DOCK_KEY" 2>/dev/null)

  if [ "$current" = "$target" ] && [ "$live" = "$target" ]; then
    if [ "$target" = "1" ]; then
      echo "Dock already HIDDEN - nothing to do."
    else
      echo "Dock already VISIBLE - nothing to do."
    fi
    return 0
  fi

  dock_apply_setting "$target"
  dock_write_state "$target"
  dock_restart_launcher

  if [ "$target" = "1" ]; then
    echo "Dock HIDDEN (evolution_dock_hidden=1). Launcher restarted."
  else
    echo "Dock VISIBLE (evolution_dock_hidden=0). Launcher restarted."
  fi
}

dock_status() {
  st=$(dock_state)
  live=$(settings get secure "$DOCK_KEY" 2>/dev/null)
  run=$(dock_launcher_running || echo "not running")

  echo "state file : $st"
  echo "setting    : ${live:-<unset>}"
  echo "launcher   : $run"
  if [ "$st" = "1" ]; then
    echo "result     : dock hidden"
  else
    echo "result     : dock visible"
  fi
}
