#!/system/bin/sh
# Evolution Dock Control - Magisk "Action" script.
#
# Behaviour:
#   * no arguments + interactive terminal  -> small menu (read, single pass, exits immediately)
#   * no arguments + no terminal (Magisk)  -> one toggle, then exit
#   * arguments                            -> action.sh on|off|toggle|status
#
# The script always terminates: there is no daemon, no `while true`, no polling.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

usage() {
  echo "usage: action.sh [on|off|toggle|status]"
}

interactive_menu() {
  echo "=================================="
  echo "   Evolution Dock Control"
  echo "=================================="
  if [ "$(dock_state)" = "1" ]; then
    echo " current: DOCK HIDDEN"
  else
    echo " current: DOCK VISIBLE"
  fi
  echo "----------------------------------"
  echo " 1) Hide dock"
  echo " 2) Show dock"
  echo " 3) Status"
  echo " 0) Exit"
  printf " > "

  read -r choice || exit 0

  case "$choice" in
    1) dock_apply 1 ;;
    2) dock_apply 0 ;;
    3) dock_status ;;
    0|"") echo "Bye." ;;
    *) echo "Unknown choice: $choice"; exit 1 ;;
  esac
  exit 0
}

MODE=${1:-auto}

case "$MODE" in
  status)
    dock_status
    exit 0
    ;;
  auto)
    # Magisk runs action.sh without a TTY: fall through to a single toggle.
    if [ -t 0 ] && [ -t 1 ]; then
      interactive_menu
    fi
    MODE=toggle
    ;;
esac

case "$MODE" in
  on|hide)
    dock_apply 1
    ;;
  off|show)
    dock_apply 0
    ;;
  toggle)
    if [ "$(dock_state)" = "1" ]; then
      dock_apply 0
    else
      dock_apply 1
    fi
    ;;
  *)
    usage
    exit 1
    ;;
esac

exit 0
