#!/system/bin/sh

ui_print "- Evolution Dock Control"
ui_print "- Setting executable permissions"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Installed. Use the Action button in Magisk."
