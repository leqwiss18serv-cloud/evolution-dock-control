#!/system/bin/sh

# No daemon and no polling. This file only marks successful installation.
MODDIR=${0%/*}
touch "$MODDIR/.installed" 2>/dev/null || true
