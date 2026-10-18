# The module has no reflection-by-name in the obfuscated sense; keep the entry class name
# stable anyway so META-INF/xposed/java_init.list keeps resolving.
-keep class com.leqwiss.dockcontrol.DockControlModule { *; }
-keepclassmembers class com.leqwiss.dockcontrol.** { *; }
