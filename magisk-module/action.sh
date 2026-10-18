#!/system/bin/sh

MODDIR=${0%/*}
STATE_FILE="$MODPATH/.dock_state"
PIXEL_LAUNCHER="com.google.android.apps.nexuslauncher"
LAUNCHER3="com.android.launcher3"

ui_print() {
    echo "$1"
}

detect_launcher() {
    if pidof "$PIXEL_LAUNCHER" >/dev/null 2>&1; then
        echo "$PIXEL_LAUNCHER"
        return 0
    fi
    if pidof "$LAUNCHER3" >/dev/null 2>&1; then
        echo "$LAUNCHER3"
        return 0
    fi
    echo ""
    return 1
}

current_state() {
    if [ -f "$STATE_FILE" ]; then
        cat "$STATE_FILE" 2>/dev/null
        return 0
    fi
    value=$(settings get secure launcher_dock_height 2>/dev/null)
    if [ "$value" = "0" ]; then
        echo "off"
    else
        echo "on"
    fi
}

apply_state() {
    state="$1"
    launcher=$(detect_launcher) || launcher=""

    if [ -z "$launcher" ]; then
        ui_print "! No running launcher detected"
        return 1
    fi

    if [ "$state" = "off" ]; then
        settings put secure launcher_dock_height 0 || return 1
    else
        settings delete secure launcher_dock_height 2>/dev/null || true
    fi

    printf '%s\n' "$state" > "$STATE_FILE" 2>/dev/null || true
    am force-stop "$launcher" 2>/dev/null || return 1
    return 0
}

state=$(current_state)
if [ "$state" = "off" ]; then
    next="on"
    ui_print "Current: Dock OFF"
    ui_print "Target:  Dock ON"
else
    next="off"
    ui_print "Current: Dock ON"
    ui_print "Target:  Dock OFF"
fi

if apply_state "$next"; then
    ui_print "- Dock switched: $next"
    ui_print "- Launcher restarted"
else
    ui_print "! Switch failed"
    exit 1
fi
