#!/system/bin/sh
# Evolution Dock Control - late_start service.
#
# ONE SHOT ONLY. This script reconciles the persisted flag with Settings.Secure at boot and
# then exits. It does not start a daemon, does not poll, and does not touch the launcher.
# If you are looking for a background process: there is none, by design.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

# Nothing to reconcile? Exit immediately (the common case).
state=$(dock_state)
live=$(settings get secure "$DOCK_KEY" 2>/dev/null)

[ "$live" = "$state" ] && exit 0

settings put secure "$DOCK_KEY" "$state" 2>/dev/null
exit 0
