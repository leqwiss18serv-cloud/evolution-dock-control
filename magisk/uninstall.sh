#!/system/bin/sh
# Evolution Dock Control - uninstall hook.
#
# Full reversibility: drop the stored flag, restore the launcher's default behaviour and
# remove every trace of the module.

settings delete secure evolution_dock_hidden 2>/dev/null
rm -rf /data/adb/evolution_dock_control 2>/dev/null

exit 0
