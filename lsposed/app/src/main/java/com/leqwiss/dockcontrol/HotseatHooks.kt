package com.leqwiss.dockcontrol

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * All launcher hooks live here.
 *
 * How the AOSP / Pixel Launcher hotseat is built (Android 14 → 16):
 *
 *  - `res/layout/launcher.xml` contains `<include android:id="@+id/hotseat" layout="@layout/hotseat"/>`
 *  - `res/layout/hotseat.xml` is a single `com.android.launcher3.Hotseat` with
 *    `android:layout_height="match_parent"` — it is a full-screen *layer* above the workspace,
 *    and only its internal cell layout sits at the bottom of that layer.
 *  - `Launcher.setupViews()` does `mHotseat = findViewById(R.id.hotseat)` and
 *    `mHotseat.setWorkspace(mWorkspace)`.
 *  - `Hotseat` extends `CellLayout`, and `Hotseat.onMeasure()` sizes the QSB from `DeviceProfile`.
 *
 * Because the hotseat is a separate `match_parent` layer, hiding the view itself is enough:
 * the workspace keeps its own `match_parent` layer underneath and simply gains the freed space.
 * That is why we do NOT rewrite `DeviceProfile` dimensions — no layout math is touched, so the
 * change is fully reversible by simply flipping the flag back.
 *
 * Hook points, in order of importance:
 *  1. `Hotseat.onMeasure`   — self-healing: whatever happens later, the dock goes GONE again.
 *  2. `Hotseat.setInsets`   — early hook during layout, hides before the first frame.
 *  3. `Launcher.setupViews` — safety net for launchers that use their own Hotseat subclass.
 *
 * Nothing is hooked in any other process, and no hook allocates per-frame objects beyond a
 * cached boolean read.
 */
internal object HotseatHooks {

    private const val TAG = DockControlModule.TAG

    /** Class names across Launcher3 versions. Only the ones that exist are hooked. */
    private val HOTSEAT_CLASSES = arrayOf(
        "com.android.launcher3.Hotseat",          // Android 14/15/16 (AOSP main)
        "com.android.launcher3.hotseat.Hotseat",  // Android 12/13 era layout of the class
    )

    private val LAUNCHER_CLASSES = arrayOf(
        "com.android.launcher3.Launcher",
        "com.google.android.apps.nexuslauncher.NexusLauncher",
    )

    fun install(module: XposedModule, loader: ClassLoader) {
        var installed = 0

        for (name in HOTSEAT_CLASSES) {
            val cls = loadClass(loader, name) ?: continue
            installed += hookMeasure(module, cls)
            installed += hookSetInsets(module, cls)
        }

        for (name in LAUNCHER_CLASSES) {
            val cls = loadClass(loader, name) ?: continue
            installed += hookSetupViews(module, cls)
        }

        try {
            module.log(Log.INFO, TAG, "installed $installed hook(s)")
        } catch (t: Throwable) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- hooks

    private fun hookMeasure(module: XposedModule, cls: Class<*>): Int {
        return try {
            val method: Method = cls.getDeclaredMethod(
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.thisObject as? View
                        if (view != null && DockState.isHidden(view.context)) {
                            collapse(view)
                        }
                    } catch (t: Throwable) {
                        safeLog(module, "onMeasure pre-hook", t)
                    }
                    // Always measure normally; we only change visibility.
                    chain.proceed()
                }
            1
        } catch (t: Throwable) {
            safeLog(module, "hook ${cls.name}#onMeasure", t)
            0
        }
    }

    private fun hookSetInsets(module: XposedModule, cls: Class<*>): Int {
        return try {
            val method: Method = cls.getDeclaredMethod("setInsets", Rect::class.java)
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.thisObject as? View
                        if (view != null && DockState.isHidden(view.context)) {
                            collapse(view)
                        }
                    } catch (t: Throwable) {
                        safeLog(module, "setInsets pre-hook", t)
                    }
                    chain.proceed()
                }
            1
        } catch (t: Throwable) {
            safeLog(module, "hook ${cls.name}#setInsets", t)
            0
        }
    }

    private fun hookSetupViews(module: XposedModule, cls: Class<*>): Int {
        return try {
            val method: Method = cls.getDeclaredMethod("setupViews")
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val activity = chain.thisObject as? Activity
                        if (activity != null && DockState.isHidden(activity)) {
                            findHotseat(activity)?.let { collapse(it) }
                        }
                    } catch (t: Throwable) {
                        safeLog(module, "setupViews post-hook", t)
                    }
                    result
                }
            1
        } catch (t: Throwable) {
            safeLog(module, "hook ${cls.name}#setupViews", t)
            0
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * Hides the dock view. Cheap and idempotent: `setVisibility` is a no-op when the value is
     * already GONE, so repeated calls cost one field comparison.
     */
    private fun collapse(view: View) {
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
        }
    }

    /**
     * Finds the hotseat view without hard-coding resource ids: the id name is stable
     * ("hotseat"), its numeric value is not.
     */
    @Suppress("DEPRECATION")
    private fun findHotseat(activity: Activity): View? {
        return try {
            val id = activity.resources.getIdentifier("hotseat", "id", activity.packageName)
            if (id != 0) activity.findViewById<View>(id) else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? {
        return try {
            Class.forName(name, false, loader)
        } catch (t: Throwable) {
            null
        }
    }

    private fun safeLog(module: XposedModule, what: String, t: Throwable) {
        try {
            module.log(Log.WARN, TAG, "$what failed: ${t.javaClass.simpleName}: ${t.message}")
        } catch (ignored: Throwable) {
            // never propagate
        }
    }
}
