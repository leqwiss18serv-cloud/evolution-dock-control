package com.leqwiss.dockcontrol

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Entry point of the LSPosed module (libxposed API 101).
 *
 * The class name is declared in `META-INF/xposed/java_init.list`, the target scope in
 * `META-INF/xposed/scope.list`, and the API requirements in `META-INF/xposed/module.prop`.
 *
 * Design rules for this module:
 *  - No background threads, no timers, no polling loops. Everything happens inside the
 *    launcher process, on the launcher's own main thread, in hooks that already exist.
 *  - Every hook is wrapped in try/catch and runs in PROTECTIVE exception mode, so a failure
 *    can never take the launcher (or the system) down.
 *  - Nothing is hooked outside of the two launcher packages.
 */
class DockControlModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            log(Log.INFO, TAG, "loaded into process: ${param.processName}")
        } catch (t: Throwable) {
            // Never let logging break the process.
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val pkg = try {
            param.packageName
        } catch (t: Throwable) {
            return
        }
        if (pkg !in TARGET_PACKAGES) return

        try {
            HotseatHooks.install(this, param.defaultClassLoader)
        } catch (t: Throwable) {
            // A failed hook installation must degrade to "stock launcher behaviour".
            log(Log.ERROR, TAG, "hook installation failed for $pkg", t)
        }
    }

    companion object {
        const val TAG = "EvoDockControl"

        /** Packages we are interested in. Keep in sync with META-INF/xposed/scope.list. */
        val TARGET_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.nexuslauncher", // Pixel Launcher
            "com.android.launcher3",                 // AOSP / Evolution X Quickstep
        )
    }
}
