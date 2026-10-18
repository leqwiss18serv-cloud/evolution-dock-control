#!/system/bin/sh
# Evolution Dock Control - installer configuration.
# Magisk sources this file after unpacking the archive into $MODPATH.

SKIPUNZIP=0

ui_print "****************************************"
ui_print "   Evolution Dock Control  v1.0.0"
ui_print "****************************************"
ui_print ""

set_perm_recursive "$MODPATH" 0 0 0755 0644

# Executable scripts.
for f in action.sh service.sh uninstall.sh common.sh; do
  [ -f "$MODPATH/$f" ] && set_perm "$MODPATH/$f" 0 0 0755
done

# dockctl is mounted into /system/bin, so it must be executable.
[ -f "$MODPATH/system/bin/dockctl" ] && set_perm "$MODPATH/system/bin/dockctl" 0 0 0755

# State directory survives module updates.
mkdir -p /data/adb/evolution_dock_control 2>/dev/null
chmod 0755 /data/adb/evolution_dock_control 2>/dev/null

ui_print "- Detected launchers:"
found=0
for p in com.google.android.apps.nexuslauncher com.android.launcher3; do
  if pm list packages 2>/dev/null | grep -q "package:$p"; then
    ui_print "    * $p"
    found=1
  fi
done
[ "$found" = "0" ] && ui_print "    ! no supported launcher found (dock control will be a no-op)"

ui_print ""
ui_print "- Default state: DOCK HIDDEN (evolution_dock_hidden=1)"
ui_print "- Magisk -> Modules -> Evolution Dock Control -> Action"
ui_print "  toggles the dock; 'dockctl on|off|status' works from a root shell."
ui_print "- A reboot is recommended after the first install."
